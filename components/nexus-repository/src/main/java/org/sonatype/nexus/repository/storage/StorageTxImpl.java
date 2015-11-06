/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.entity.EntityHelper;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.stateguard.StateGuard;
import org.sonatype.nexus.common.stateguard.StateGuardAware;
import org.sonatype.nexus.common.stateguard.Transitions;
import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.IllegalOperationException;
import org.sonatype.nexus.repository.Repository;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.tx.OTransaction.TXTYPE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.sonatype.nexus.common.entity.EntityHelper.id;
import static org.sonatype.nexus.repository.storage.StorageFacet.P_ATTRIBUTES;
import static org.sonatype.nexus.repository.storage.StorageFacet.P_CHECKSUM;
import static org.sonatype.nexus.repository.storage.StorageTxImpl.State.ACTIVE;
import static org.sonatype.nexus.repository.storage.StorageTxImpl.State.CLOSED;
import static org.sonatype.nexus.repository.storage.StorageTxImpl.State.OPEN;

/**
 * Default {@link StorageTx} implementation.
 *
 * @since 3.0
 */
public class StorageTxImpl
    implements StorageTx, StateGuardAware
{
  private static final Logger log = LoggerFactory.getLogger(StorageTxImpl.class);

  private static final long DELETE_BATCH_SIZE = 100L;

  private static final int MAX_RETRIES = 8;

  private final String createdBy;

  private final BlobTx blobTx;

  private final ODatabaseDocumentTx db;

  private final Bucket bucket;

  private final WritePolicy writePolicy;

  private final WritePolicySelector writePolicySelector;

  private final StateGuard stateGuard = new StateGuard.Builder().initial(OPEN).create();

  private final BucketEntityAdapter bucketEntityAdapter;

  private final ComponentEntityAdapter componentEntityAdapter;

  private final AssetEntityAdapter assetEntityAdapter;

  private final boolean strictContentValidation;

  private final ContentValidator contentValidator;

  private final MimeRulesSource mimeRulesSource;

  private int retries = 0;

  public StorageTxImpl(final String createdBy,
                       final BlobTx blobTx,
                       final ODatabaseDocumentTx db,
                       final Bucket bucket,
                       final WritePolicy writePolicy,
                       final WritePolicySelector writePolicySelector,
                       final BucketEntityAdapter bucketEntityAdapter,
                       final ComponentEntityAdapter componentEntityAdapter,
                       final AssetEntityAdapter assetEntityAdapter,
                       final boolean strictContentValidation,
                       final ContentValidator contentValidator,
                       final MimeRulesSource mimeRulesSource)
  {
    this.createdBy = checkNotNull(createdBy);
    this.blobTx = checkNotNull(blobTx);
    this.db = checkNotNull(db);
    this.bucket = checkNotNull(bucket);
    this.writePolicy = checkNotNull(writePolicy);
    this.writePolicySelector = checkNotNull(writePolicySelector);
    this.bucketEntityAdapter = checkNotNull(bucketEntityAdapter);
    this.componentEntityAdapter = checkNotNull(componentEntityAdapter);
    this.assetEntityAdapter = checkNotNull(assetEntityAdapter);
    this.strictContentValidation = strictContentValidation;
    this.contentValidator = checkNotNull(contentValidator);
    this.mimeRulesSource = checkNotNull(mimeRulesSource);

    // This is only here for now to yell in case of nested TX
    // To be discussed in future, or at the point when we will have need for nested TX
    // Note: orient DB sports some rudimentary support for nested TXes
    checkArgument(!db.getTransaction().isActive(), "Nested DB TX!");
  }

  public static final class State
  {
    public static final String OPEN = "OPEN";

    public static final String ACTIVE = "ACTIVE";

    public static final String CLOSED = "CLOSED";
  }

  @Override
  @Nonnull
  public StateGuard getStateGuard() {
    return stateGuard;
  }

  @Override
  @Transitions(from = OPEN, to = ACTIVE)
  public void begin() {
    db.begin(TXTYPE.OPTIMISTIC);
  }

  @Override
  @Transitions(from = ACTIVE, to = OPEN, silent = true)
  public void commit() {
    db.commit();
    blobTx.commit();
    retries = 0;
  }

  @Override
  @Transitions(from = ACTIVE, to = OPEN)
  public void rollback() {
    db.rollback();
    blobTx.rollback();
  }

  @Override
  public boolean isActive() {
    return ACTIVE.equals(stateGuard.getCurrent());
  }

  /**
   * Custom retry strategy that throws {@link RetryDeniedException} when retry limit is breached.
   */
  @Override
  public boolean allowRetry(final Exception cause) throws RetryDeniedException {
    if (retries < MAX_RETRIES) {
      retries++;
      log.debug("Retrying operation: {}/{}", retries, MAX_RETRIES);
      return true;
    }

    String message = String.format("Reached max retries: %d/%d", retries, MAX_RETRIES);
    log.warn(message);

    throw new RetryDeniedException(message, cause);
  }

  @Override
  @Transitions(from = {OPEN, ACTIVE}, to = CLOSED)
  public void close() {
    // If the transaction has not been committed, then we roll back.
    if (ACTIVE.equals(stateGuard.getCurrent())) {
      rollback();
    }

    db.close(); // rolls back and releases ODatabaseDocumentTx to pool
  }

  @Override
  @Guarded(by = {ACTIVE})
  public ODatabaseDocumentTx getDb() {
    return db;
  }

  @Override
  @Guarded(by = {ACTIVE})
  public Iterable<ODocument> browse(final String selectSql, @Nullable final Map<String, Object> params) {
    return OrientAsyncHelper.asyncIterable(db, selectSql, params);
  }

  @Nullable
  @Override
  @Guarded(by = ACTIVE)
  public Bucket findBucket(final Repository repository) {
    return bucketOf(repository.getName());
  }

  @Override
  @Guarded(by = ACTIVE)
  public Iterable<Bucket> browseBuckets() {
    return bucketEntityAdapter.browse(db);
  }

  @Override
  @Guarded(by = ACTIVE)
  public Iterable<Asset> browseAssets(final Bucket bucket) {
    return assetEntityAdapter.browseByBucket(db, bucket);
  }

  @Override
  @Guarded(by = ACTIVE)
  public Iterable<Asset> browseAssets(final Component component) {
    return assetEntityAdapter.browseByComponent(db, component);
  }

  @Override
  @Guarded(by = ACTIVE)
  public Asset firstAsset(final Component component) {
    return Iterables.getFirst(browseAssets(component), null);
  }

  @Override
  @Guarded(by = ACTIVE)
  public Iterable<Component> browseComponents(final Bucket bucket) {
    return componentEntityAdapter.browseByBucket(db, bucket);
  }

  @Nullable
  @Override
  @Guarded(by = ACTIVE)
  public Asset findAsset(final EntityId id, final Bucket bucket) {
    checkNotNull(id);
    checkNotNull(bucket);
    Asset asset = assetEntityAdapter.read(db, id);
    return bucketOwns(bucket, asset) ? asset : null;
  }

  private boolean bucketOwns(final Bucket bucket, final @Nullable MetadataNode<?> item) {
    return item != null && Objects.equals(id(bucket), item.bucketId());
  }

  @Nullable
  @Override
  @Guarded(by = ACTIVE)
  public Asset findAssetWithProperty(final String propName, final Object propValue, final Bucket bucket) {
    return assetEntityAdapter.findByProperty(db, propName, propValue, bucket);
  }

  @Nullable
  @Override
  @Guarded(by = ACTIVE)
  public Asset findAssetWithProperty(final String propName, final Object propValue, final Component component) {
    return assetEntityAdapter.findByProperty(db, propName, propValue, component);
  }

  @Override
  @Guarded(by = ACTIVE)
  public Iterable<Asset> findAssets(@Nullable String whereClause,
                                    @Nullable Map<String, Object> parameters,
                                    @Nullable Iterable<Repository> repositories,
                                    @Nullable String querySuffix)
  {
    return assetEntityAdapter.browseByQuery(db, whereClause, parameters, bucketsOf(repositories), querySuffix);
  }

  @Override
  @Guarded(by = ACTIVE)
  public Iterable<Asset> findAssets(final Query query, @Nullable final Iterable<Repository> repositories) {
    return findAssets(query.getWhere(), query.getParameters(), repositories, query.getQuerySuffix());
  }

  @Override
  @Guarded(by = ACTIVE)
  public long countAssets(@Nullable String whereClause,
                          @Nullable Map<String, Object> parameters,
                          @Nullable Iterable<Repository> repositories,
                          @Nullable String querySuffix)
  {
    return assetEntityAdapter.countByQuery(db, whereClause, parameters, bucketsOf(repositories), querySuffix);
  }

  @Override
  @Guarded(by = ACTIVE)
  public long countAssets(final Query query, @Nullable final Iterable<Repository> repositories) {
    return countAssets(query.getWhere(), query.getParameters(), repositories, query.getQuerySuffix());
  }

  @Nullable
  @Override
  @Guarded(by = ACTIVE)
  public Component findComponent(final EntityId id, final Bucket bucket) {
    checkNotNull(id);
    checkNotNull(bucket);
    Component component = componentEntityAdapter.read(db, id);
    return bucketOwns(bucket, component) ? component : null;
  }

  @Nullable
  @Override
  @Guarded(by = ACTIVE)
  public Component findComponentWithProperty(final String propName, final Object propValue, final Bucket bucket) {
    return componentEntityAdapter.findByProperty(db, propName, propValue, bucket);
  }

  @Override
  @Guarded(by = ACTIVE)
  public Iterable<Component> findComponents(@Nullable String whereClause,
                                            @Nullable Map<String, Object> parameters,
                                            @Nullable Iterable<Repository> repositories,
                                            @Nullable String querySuffix)
  {
    return componentEntityAdapter.browseByQuery(db, whereClause, parameters, bucketsOf(repositories), querySuffix);
  }

  @Override
  @Guarded(by = ACTIVE)
  public Iterable<Component> findComponents(final Query query, @Nullable final Iterable<Repository> repositories) {
    return findComponents(query.getWhere(), query.getParameters(), repositories, query.getQuerySuffix());
  }

  @Override
  @Guarded(by = ACTIVE)
  public long countComponents(@Nullable String whereClause,
                              @Nullable Map<String, Object> parameters,
                              @Nullable Iterable<Repository> repositories,
                              @Nullable String querySuffix)
  {
    return componentEntityAdapter.countByQuery(db, whereClause, parameters, bucketsOf(repositories), querySuffix);
  }

  @Override
  @Guarded(by = ACTIVE)
  public long countComponents(final Query query, @Nullable final Iterable<Repository> repositories) {
    return countComponents(query.getWhere(), query.getParameters(), repositories, query.getQuerySuffix());
  }

  @Override
  @Guarded(by = ACTIVE)
  public List<String> getUniqueComponentNames(@Nullable final Iterable<Repository> repositories) {
    return componentEntityAdapter.getUniqueComponentNames(db, bucketsOf(repositories));
  }

  @Override
  @Guarded(by = ACTIVE)
  public Asset createAsset(final Bucket bucket, final Format format) {
    checkNotNull(format);
    return createAsset(bucket, format.toString());
  }

  private Asset createAsset(final Bucket bucket, final String format) {
    checkNotNull(bucket);
    Asset asset = new Asset();
    asset.bucketId(id(bucket));
    asset.format(format);
    asset.attributes(new NestedAttributesMap(P_ATTRIBUTES, new HashMap<String, Object>()));
    return asset;
  }

  @Override
  @Guarded(by = ACTIVE)
  public Asset createAsset(final Bucket bucket, final Component component) {
    checkNotNull(component);
    Asset asset = createAsset(bucket, component.format());
    asset.componentId(id(component));
    return asset;
  }

  @Override
  @Guarded(by = ACTIVE)
  public Component createComponent(final Bucket bucket, final Format format) {
    checkNotNull(bucket);
    checkNotNull(format);

    Component component = new Component();
    component.bucketId(id(bucket));
    component.format(format.toString());
    component.attributes(new NestedAttributesMap(P_ATTRIBUTES, new HashMap<String, Object>()));
    return component;
  }

  @Override
  @Guarded(by = ACTIVE)
  public void saveComponent(final Component component) {
    if (EntityHelper.hasMetadata(component)) {
      componentEntityAdapter.edit(db, component);
    }
    else {
      componentEntityAdapter.add(db, component);
    }
  }

  @Override
  @Guarded(by = ACTIVE)
  public void saveAsset(final Asset asset) {
    if (EntityHelper.hasMetadata(asset)) {
      assetEntityAdapter.edit(db, asset);
    }
    else {
      assetEntityAdapter.add(db, asset);
    }
  }

  @Override
  @Guarded(by = ACTIVE)
  public void deleteComponent(Component component) {
    deleteComponent(component, true);
  }

  private void deleteComponent(final Component component, final boolean checkWritePolicy) {
    checkNotNull(component);

    for (Asset asset : browseAssets(component)) {
      deleteAsset(asset, checkWritePolicy ? writePolicySelector.select(asset, writePolicy) : null);
    }
    componentEntityAdapter.delete(db, component);
  }

  @Override
  @Guarded(by = ACTIVE)
  public void deleteAsset(Asset asset) {
    deleteAsset(asset, writePolicySelector.select(asset, writePolicy));
  }

  private void deleteAsset(final Asset asset, @Nullable final WritePolicy effectiveWritePolicy) {
    checkNotNull(asset);

    BlobRef blobRef = asset.blobRef();
    if (blobRef != null) {
      deleteBlob(blobRef, effectiveWritePolicy);
    }
    assetEntityAdapter.delete(db, asset);
  }

  @Override
  @Guarded(by = ACTIVE)
  public void deleteBucket(Bucket bucket) {
    checkNotNull(bucket);

    long count = 0;

    // first delete all components and constituent assets
    for (Component component : browseComponents(bucket)) {
      deleteComponent(component, false);
      count++;
      if (count == DELETE_BATCH_SIZE) {
        commit();
        count = 0;
      }
    }
    commit();

    // then delete all standalone assets
    for (Asset asset : browseAssets(bucket)) {
      deleteAsset(asset, null);
      count++;
      if (count == DELETE_BATCH_SIZE) {
        commit();
        count = 0;
      }
    }
    commit();

    // finally, delete the bucket document
    bucketEntityAdapter.delete(db, bucket);
    commit();
  }

  @Override
  @Guarded(by = ACTIVE)
  public AssetBlob createBlob(final String blobName,
                              final Supplier<InputStream> streamSupplier,
                              final Iterable<HashAlgorithm> hashAlgorithms,
                              @Nullable final Map<String, String> headers,
                              @Nullable final String declaredContentType,
                              final boolean skipContentVerification) throws IOException
  {
    checkNotNull(blobName);
    checkNotNull(streamSupplier);
    checkNotNull(hashAlgorithms);
    checkArgument(
        !skipContentVerification || !Strings2.isBlank(declaredContentType),
        "skipContentVerification set true but no declaredContentType provided"
    );

    if (!writePolicy.checkCreateAllowed()) {
      throw new IllegalOperationException("Repository is read only: " + bucket.getRepositoryName());
    }

    ImmutableMap.Builder<String, String> storageHeaders = ImmutableMap.builder();
    storageHeaders.put(Bucket.REPO_NAME_HEADER, bucket.getRepositoryName());
    storageHeaders.put(BlobStore.BLOB_NAME_HEADER, blobName);
    storageHeaders.put(BlobStore.CREATED_BY_HEADER, createdBy);
    if (!skipContentVerification) {
      storageHeaders.put(
          BlobStore.CONTENT_TYPE_HEADER,
          determineContentType(streamSupplier, blobName, declaredContentType)
      );
    }
    else {
      storageHeaders.put(BlobStore.CONTENT_TYPE_HEADER, declaredContentType);
    }
    if (headers != null) {
      storageHeaders.putAll(headers);
    }
    Map<String, String> storageHeadersMap = storageHeaders.build();
    return blobTx.create(
        streamSupplier.get(),
        storageHeadersMap,
        hashAlgorithms,
        storageHeadersMap.get(BlobStore.CONTENT_TYPE_HEADER)
    );
  }

  @Override
  @Guarded(by = ACTIVE)
  public void attachBlob(final Asset asset, final AssetBlob assetBlob)
  {
    checkNotNull(asset);
    checkNotNull(assetBlob);
    checkArgument(!assetBlob.isAttached(), "Blob is already attached to an asset");

    final WritePolicy effectiveWritePolicy = writePolicySelector.select(asset, writePolicy);
    if (!effectiveWritePolicy.checkCreateAllowed()) {
      throw new IllegalOperationException("Repository is read only: " + bucket.getRepositoryName());
    }

    // Delete old blob if necessary
    BlobRef oldBlobRef = asset.blobRef();
    if (oldBlobRef != null) {
      if (!effectiveWritePolicy.checkUpdateAllowed()) {
        throw new IllegalOperationException(
            "Repository does not allow updating assets: " + bucket.getRepositoryName());
      }
      deleteBlob(oldBlobRef, effectiveWritePolicy);
    }

    asset.blobRef(assetBlob.getBlobRef());
    asset.size(assetBlob.getSize());
    asset.contentType(assetBlob.getContentType());

    // Set attributes map to contain computed checksum metadata
    NestedAttributesMap checksums = asset.attributes().child(P_CHECKSUM);
    for (HashAlgorithm algorithm : assetBlob.getHashes().keySet()) {
      checksums.set(algorithm.name(), assetBlob.getHashes().get(algorithm).toString());
    }

    assetBlob.setAttached(true);
  }

  @Override
  @Guarded(by = ACTIVE)
  public AssetBlob setBlob(final Asset asset,
                           final String blobName,
                           final Supplier<InputStream> streamSupplier,
                           final Iterable<HashAlgorithm> hashAlgorithms,
                           @Nullable final Map<String, String> headers,
                           @Nullable final String declaredContentType,
                           final boolean skipContentVerification) throws IOException
  {
    checkNotNull(asset);

    // Enforce write policy ahead, as we have asset here
    BlobRef oldBlobRef = asset.blobRef();
    if (oldBlobRef != null) {
      if (!writePolicySelector.select(asset, writePolicy).checkUpdateAllowed()) {
        throw new IllegalOperationException(
            "Repository does not allow updating assets: " + bucket.getRepositoryName());
      }
    }
    final AssetBlob assetBlob = createBlob(
        blobName,
        streamSupplier,
        hashAlgorithms,
        headers,
        declaredContentType,
        skipContentVerification
    );
    attachBlob(asset, assetBlob);
    return assetBlob;
  }

  @Nullable
  @Override
  @Guarded(by = ACTIVE)
  public Blob getBlob(final BlobRef blobRef) {
    checkNotNull(blobRef);

    return blobTx.get(blobRef);
  }

  @Override
  @Guarded(by = ACTIVE)
  public Blob requireBlob(final BlobRef blobRef) {
    Blob blob = getBlob(blobRef);
    checkState(blob != null, "Blob not found: %s", blobRef);
    return blob;
  }

  @Nonnull
  private String determineContentType(final Supplier<InputStream> inputStreamSupplier,
                                      final String blobName,
                                      @Nullable final String declaredContentType)
      throws IOException
  {
    return contentValidator.determineContentType(
        strictContentValidation,
        inputStreamSupplier,
        mimeRulesSource,
        blobName,
        declaredContentType
    );
  }

  /**
   * Deletes a blob w/ enforcing {@link WritePolicy} if not {@code null}. otherwise write policy will NOT be checked.
   */
  private void deleteBlob(final BlobRef blobRef, @Nullable WritePolicy effectiveWritePolicy) {
    checkNotNull(blobRef);
    if (effectiveWritePolicy != null && !effectiveWritePolicy.checkDeleteAllowed()) {
      throw new IllegalOperationException(
          "Repository does not allow deleting assets: " + bucket.getRepositoryName());
    }
    blobTx.delete(blobRef);
  }

  /**
   * Returns the {@link Bucket} associated with given repository.
   */
  @Nullable
  private Bucket bucketOf(final String repositoryName) {
    if (bucket.getRepositoryName().equals(repositoryName)) {
      return bucket;
    }
    else {
      return bucketEntityAdapter.getByRepositoryName(db, repositoryName);
    }
  }

  /**
   * Returns the {@link Bucket}s associated with the given repositories.
   */
  @Nullable
  private Iterable<Bucket> bucketsOf(@Nullable final Iterable<Repository> repositories) {
    if (repositories == null) {
      return null;
    }
    ImmutableList.Builder<Bucket> bucketsBuilder = ImmutableList.builder();
    for (Repository repository : repositories) {
      bucketsBuilder.add(bucketOf(repository.getName()));
    }
    return bucketsBuilder.build();
  }
}
