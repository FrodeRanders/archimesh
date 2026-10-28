package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gautelis.archimesh.model.RevisionRange;
import org.junit.jupiter.api.*;
import java.util.UUID;

class CommitRecoveryIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private Neo4jRepositoryImpl repository;
    private String model;

    private Neo4jRepositoryImpl open() {
        var repo = new Neo4jRepositoryImpl();
        repo.uri = System.getenv().getOrDefault("NEO4J_URI", "bolt://localhost:7687");
        repo.username = System.getenv().getOrDefault("NEO4J_USER", "neo4j");
        repo.password = System.getenv().getOrDefault("NEO4J_PASSWORD", "devpassword");
        repo.init();
        return repo;
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        repository = open();
        model = "recovery-it-" + UUID.randomUUID();
        repository.registerModel(model, "Recovery regression");
    }

    @AfterEach
    void cleanUp() {
        if (repository != null) { repository.deleteModel(model); repository.close(); }
    }

    private ObjectNode batch(String id, long start, String... operations) throws Exception {
        ObjectNode batch = json.createObjectNode().put("modelId", model).put("opBatchId", id)
                .put("timestamp", "2026-10-28T00:00:00Z");
        batch.set("assignedRevisionRange", json.createObjectNode().put("from", start).put("to", start + operations.length - 1));
        var ops = batch.putArray("ops");
        for (String operation : operations) {
            var op = (ObjectNode) json.readTree(operation);
            op.set("causal", json.createObjectNode().put("lamport", start + ops.size()).put("clientId", "test"));
            ops.add(op);
        }
        return batch;
    }

    private void commit(ObjectNode batch) {
        var range = batch.path("assignedRevisionRange");
        repository.commitBatch(model, batch.path("opBatchId").asText(),
                new RevisionRange(range.path("from").asLong(), range.path("to").asLong()), batch);
    }

    private String create(String id) {
        return "{\"type\":\"CreateElement\",\"element\":{\"id\":\"elem:" + id
                + "\",\"archimateType\":\"BusinessActor\",\"name\":\"" + id + "\"}}";
    }

    @Test
    void projectionFailureRollsBackLogHeadAndAllEarlierOperations() throws Exception {
        commit(batch("existing", 1, create("existing")));
        var broken = batch("broken", 2, create("new"),
                "{\"type\":\"SetProperty\",\"targetId\":\"elem:new\",\"key\":\"id\",\"value\":\"elem:existing\"}");
        Assertions.assertThrows(IllegalStateException.class, () -> commit(broken));
        Assertions.assertEquals(1, repository.readHeadRevision(model));
        Assertions.assertTrue(repository.findAcceptedRange(model, "broken").isEmpty());
        Assertions.assertFalse(repository.elementExists(model, "elem:new"));
        Assertions.assertEquals(1, repository.loadOpBatches(model, 1, 3).size());
    }

    @Test
    void acceptanceAndPendingDeliverySurviveRepositoryRestart() throws Exception {
        commit(batch("durable", 1, create("first")));
        repository.close();
        repository = open();
        Assertions.assertEquals(new RevisionRange(1, 1), repository.findAcceptedRange(model, "durable").orElseThrow());
        Assertions.assertTrue(repository.pendingBroadcasts().stream().anyMatch(b -> model.equals(b.path("modelId").asText())));
        repository.markBroadcastPublished(model, "durable");
        Assertions.assertFalse(repository.pendingBroadcasts().stream().anyMatch(b -> model.equals(b.path("modelId").asText())));
    }

    @Test
    void compactionAndAtomicRebuildPreservePropertiesAndMergeState() throws Exception {
        commit(batch("create", 1, create("first")));
        commit(batch("property", 2, "{\"type\":\"SetProperty\",\"targetId\":\"elem:first\",\"key\":\"owner\",\"value\":\"Alice\"}"));
        commit(batch("later", 3, create("second")));
        JsonNode before = repository.loadSnapshot(model);
        Assertions.assertEquals("Alice", before.path("propertyState").get(0).path("value").asText());
        Assertions.assertFalse(before.path("fieldClocks").isEmpty());
        Assertions.assertEquals(0, repository.compactMetadata(model, 0).deletedCommitCount());
        JsonNode history = repository.loadOpBatches(model, 1, 3);
        repository.rebuildProjection(model, history, 3);
        Assertions.assertEquals(before, repository.loadSnapshot(model));
    }

    @Test
    void twoServersCannotOwnTheWriterLeaseTogether() {
        repository.runtimeLeaseKey = "lease-test-" + UUID.randomUUID();
        String owner = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        try {
            Assertions.assertTrue(repository.acquireServerLease(owner, 30_000));
            Assertions.assertFalse(repository.acquireServerLease(other, 30_000));
            Assertions.assertTrue(repository.acquireServerLease(owner, 30_000));
            repository.releaseServerLease(owner);
            Assertions.assertTrue(repository.acquireServerLease(other, 30_000));
        } finally {
            repository.releaseServerLease(owner);
            repository.releaseServerLease(other);
        }
    }
}
