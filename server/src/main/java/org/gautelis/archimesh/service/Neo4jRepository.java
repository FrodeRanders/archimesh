package org.gautelis.archimesh.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.gautelis.archimesh.model.AdminCompactionStatus;
import org.gautelis.archimesh.model.ModelAccessControl;
import org.gautelis.archimesh.model.ModelCatalogEntry;
import org.gautelis.archimesh.model.ModelTagEntry;
import org.gautelis.archimesh.model.RevisionRange;
import org.gautelis.archimesh.model.ModelCheckpoint;
import org.gautelis.archimesh.model.BatchReceipt;
import org.gautelis.archimesh.model.AdminModelExport;

import java.util.Optional;
import java.util.List;
import java.util.Set;

public interface Neo4jRepository {
    /** Atomically persists the log, projection and head, or leaves all three unchanged. */
    default void commitBatch(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch) {
        throw new UnsupportedOperationException("Atomic commits are required");
    }

    default Optional<RevisionRange> findAcceptedRange(String modelId, String opBatchId) {
        return Optional.empty();
    }

    default List<JsonNode> pendingBroadcasts() { return List.of(); }

    default void markBroadcastPublished(String modelId, String opBatchId) { }

    default boolean acquireServerLease(String owner, long ttlMs) { return true; }

    default void releaseServerLease(String owner) { }

    void appendOpLog(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch);

    void applyToMaterializedState(String modelId, JsonNode opBatch);

    void updateHeadRevision(String modelId, long headRevision);

    long readHeadRevision(String modelId);

    long readLatestCommitRevision(String modelId);

    JsonNode loadSnapshot(String modelId);

    JsonNode loadOpBatches(String modelId, long fromRevisionInclusive, long toRevisionInclusive);

    AdminCompactionStatus compactMetadata(String modelId, long retainRevisions);

    default AdminCompactionStatus compactMetadata(String modelId, long retainRevisions, boolean dryRun) {
        if (dryRun) throw new UnsupportedOperationException("Compaction preview is unavailable");
        return compactMetadata(modelId, retainRevisions);
    }

    default ModelCheckpoint createCheckpoint(String modelId) {
        throw new UnsupportedOperationException("Checkpoints are unavailable");
    }

    default Optional<ModelCheckpoint> latestCheckpoint(String modelId) { return Optional.empty(); }

    default List<BatchReceipt> loadReceipts(String modelId, long throughRevision) { return List.of(); }

    default void importCheckpointModel(AdminModelExport exportPackage, boolean overwrite) {
        throw new UnsupportedOperationException("Checkpoint import is unavailable");
    }

    default void rebuildProjection(String modelId, ModelCheckpoint checkpoint, JsonNode history, long head) {
        if (checkpoint != null) throw new UnsupportedOperationException("Checkpoint restore is unavailable");
        rebuildProjection(modelId, history, head);
    }

    default void rebuildProjection(String modelId, JsonNode history, long head) {
        throw new UnsupportedOperationException("Atomic rebuild is required");
    }

    void clearMaterializedState(String modelId);

    void deleteModel(String modelId);

    boolean isMaterializedStateConsistent(String modelId, long expectedHeadRevision);

    boolean elementExists(String modelId, String elementId);

    boolean relationshipExists(String modelId, String relationshipId);

    boolean viewExists(String modelId, String viewId);

    boolean folderExists(String modelId, String folderId);

    boolean folderEmpty(String modelId, String folderId);

    boolean folderMoveCreatesCycle(String modelId, String folderId, String parentFolderId);

    String folderRootId(String modelId, String folderId);

    boolean viewObjectExists(String modelId, String viewObjectId);

    boolean connectionExists(String modelId, String connectionId);

    List<String> findRelationshipIdsByElement(String modelId, String elementId);

    List<String> findViewObjectIdsByRepresents(String modelId, String representsId);

    List<String> findConnectionIdsByViewObject(String modelId, String viewObjectId);

    List<String> findConnectionIdsByRelationship(String modelId, String relationshipId);

    ModelCatalogEntry registerModel(String modelId, String modelName);

    ModelCatalogEntry renameModel(String modelId, String modelName);

    String readModelName(String modelId);

    boolean modelRegistered(String modelId);

    Optional<ModelAccessControl> readModelAccessControl(String modelId);

    ModelAccessControl updateModelAccessControl(String modelId, Set<String> adminUsers, Set<String> writerUsers, Set<String> readerUsers);

    ModelTagEntry createModelTag(String modelId, String tagName, String description, long revision, JsonNode snapshot);

    ModelTagEntry restoreModelTag(String modelId, String tagName, String description, long revision, String createdAt, JsonNode snapshot);

    Optional<ModelTagEntry> readModelTag(String modelId, String tagName);

    JsonNode loadTaggedSnapshot(String modelId, String tagName);

    List<ModelTagEntry> listModelTags(String modelId);

    void deleteModelTag(String modelId, String tagName);

    List<ModelCatalogEntry> listModelCatalog();
}
