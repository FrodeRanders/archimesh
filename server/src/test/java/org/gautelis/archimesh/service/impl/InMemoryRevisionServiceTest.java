package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.gautelis.archimesh.model.AdminCompactionStatus;
import org.gautelis.archimesh.model.ModelAccessControl;
import org.gautelis.archimesh.model.ModelCatalogEntry;
import org.gautelis.archimesh.model.ModelTagEntry;
import org.gautelis.archimesh.model.RevisionRange;
import org.gautelis.archimesh.service.Neo4jRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

class InMemoryRevisionServiceTest {

    @Test
    void concurrentBatchesReserveDisjointContiguousRanges() throws Exception {
        InMemoryRevisionService service = new InMemoryRevisionService();
        var start = new java.util.concurrent.CountDownLatch(1);
        var seen = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int thread = 0; thread < 8; thread++) futures.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                for (int batch = 0; batch < 1000; batch++) {
                    RevisionRange range = service.assignRange("concurrent", 3);
                    for (long revision = range.from(); revision <= range.to(); revision++) {
                        Assertions.assertTrue(seen.add(revision), "revision was assigned twice: " + revision);
                    }
                }
            }));
            start.countDown();
            for (var future : futures) future.get();
        }
        Assertions.assertEquals(24000, seen.size());
        Assertions.assertEquals(24000, service.headRevision("concurrent"));
    }

    @Test
    void headRevisionReSyncsUpWhenPersistedHeadAdvances() {
        InMemoryRevisionService service = new InMemoryRevisionService();
        RecordingNeo4jRepository neo = new RecordingNeo4jRepository();
        service.neo4jRepository = neo;

        neo.headRevision = 0;
        Assertions.assertEquals(0, service.headRevision("demo"));

        neo.headRevision = 50;
        Assertions.assertEquals(50, service.headRevision("demo"));

        RevisionRange assigned = service.assignRange("demo", 1);
        Assertions.assertEquals(51, assigned.from());
        Assertions.assertEquals(51, assigned.to());
    }

    @Test
    void headRevisionNeverMovesBackwardFromPersistedHead() {
        InMemoryRevisionService service = new InMemoryRevisionService();
        RecordingNeo4jRepository neo = new RecordingNeo4jRepository();
        service.neo4jRepository = neo;

        neo.headRevision = 10;
        Assertions.assertEquals(10, service.headRevision("demo"));

        neo.headRevision = 3;
        Assertions.assertEquals(10, service.headRevision("demo"));
    }

    private static class RecordingNeo4jRepository implements Neo4jRepository {
        long headRevision;

        @Override
        public void appendOpLog(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch) {
        }

        @Override
        public void applyToMaterializedState(String modelId, JsonNode opBatch) {
        }

        @Override
        public void updateHeadRevision(String modelId, long headRevision) {
            this.headRevision = headRevision;
        }

        @Override
        public long readHeadRevision(String modelId) {
            return headRevision;
        }

        @Override
        public long readLatestCommitRevision(String modelId) {
            return headRevision;
        }

        @Override
        public JsonNode loadSnapshot(String modelId) {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public JsonNode loadTaggedSnapshot(String modelId, String tagName) {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public JsonNode loadOpBatches(String modelId, long fromRevisionInclusive, long toRevisionInclusive) {
            return JsonNodeFactory.instance.arrayNode();
        }

        @Override
        public AdminCompactionStatus compactMetadata(String modelId, long retainRevisions) {
            return new AdminCompactionStatus(modelId, headRevision, headRevision, headRevision, retainRevisions,
                    0L, 0L, 0L, 0L, 0L, 0L, true, "noop");
        }

        @Override
        public void clearMaterializedState(String modelId) {
        }

        @Override
        public void deleteModel(String modelId) {
            headRevision = 0L;
        }

        @Override
        public boolean isMaterializedStateConsistent(String modelId, long expectedHeadRevision) {
            return true;
        }

        @Override
        public boolean elementExists(String modelId, String elementId) {
            return true;
        }

        @Override
        public boolean relationshipExists(String modelId, String relationshipId) {
            return true;
        }

        @Override
        public boolean viewExists(String modelId, String viewId) {
            return true;
        }

        @Override
        public boolean folderExists(String modelId, String folderId) {
            return true;
        }

        @Override
        public boolean folderEmpty(String modelId, String folderId) {
            return true;
        }

        @Override
        public boolean folderMoveCreatesCycle(String modelId, String folderId, String parentFolderId) {
            return false;
        }

        @Override
        public String folderRootId(String modelId, String folderId) {
            return folderId != null && folderId.startsWith("folder:root-") ? folderId : "folder:root-business";
        }

        @Override
        public boolean viewObjectExists(String modelId, String viewObjectId) {
            return true;
        }

        @Override
        public boolean connectionExists(String modelId, String connectionId) {
            return true;
        }

        @Override
        public List<String> findRelationshipIdsByElement(String modelId, String elementId) {
            return List.of();
        }

        @Override
        public List<String> findViewObjectIdsByRepresents(String modelId, String representsId) {
            return List.of();
        }

        @Override
        public List<String> findConnectionIdsByViewObject(String modelId, String viewObjectId) {
            return List.of();
        }

        @Override
        public List<String> findConnectionIdsByRelationship(String modelId, String relationshipId) {
            return List.of();
        }

        @Override
        public ModelCatalogEntry registerModel(String modelId, String modelName) {
            return new ModelCatalogEntry(modelId, modelName, headRevision);
        }

        @Override
        public ModelCatalogEntry renameModel(String modelId, String modelName) {
            return new ModelCatalogEntry(modelId, modelName, headRevision);
        }

        @Override
        public String readModelName(String modelId) {
            return null;
        }

        @Override
        public boolean modelRegistered(String modelId) {
            return true;
        }

        @Override
        public Optional<ModelAccessControl> readModelAccessControl(String modelId) {
            return Optional.of(new ModelAccessControl(modelId, Set.of(), Set.of(), Set.of()));
        }

        @Override
        public ModelAccessControl updateModelAccessControl(String modelId, Set<String> adminUsers, Set<String> writerUsers, Set<String> readerUsers) {
            return new ModelAccessControl(modelId, adminUsers, writerUsers, readerUsers);
        }

        @Override
        public ModelTagEntry createModelTag(String modelId, String tagName, String description, long revision, JsonNode snapshot) {
            return new ModelTagEntry(modelId, tagName, description, revision, null);
        }

        @Override
        public ModelTagEntry restoreModelTag(String modelId, String tagName, String description, long revision, String createdAt, JsonNode snapshot) {
            return new ModelTagEntry(modelId, tagName, description, revision, createdAt);
        }

        @Override
        public Optional<ModelTagEntry> readModelTag(String modelId, String tagName) {
            return Optional.empty();
        }

        @Override
        public List<ModelTagEntry> listModelTags(String modelId) {
            return List.of();
        }

        @Override
        public void deleteModelTag(String modelId, String tagName) {
        }

        @Override
        public List<ModelCatalogEntry> listModelCatalog() {
            return List.of();
        }
    }
}
