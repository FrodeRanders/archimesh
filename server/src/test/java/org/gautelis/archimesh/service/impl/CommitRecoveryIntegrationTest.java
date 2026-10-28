package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gautelis.archimesh.model.RevisionRange;
import org.junit.jupiter.api.*;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class CommitRecoveryIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private Neo4jRepositoryImpl repository;
    private String model;
    private final List<String> extraModels = new ArrayList<>();

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
        if (repository != null) {
            for (String id : extraModels) repository.deleteModel(id);
            repository.deleteModel(model); repository.close();
        }
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
        Assertions.assertEquals(1,repository.pendingBroadcasts(model,25).size());
        repository.markBroadcastPublished(model, "durable");
        Assertions.assertTrue(repository.pendingBroadcasts(model,25).isEmpty());
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

    private String pendingModel(String suffix, int count) throws Exception {
        String id = model+"-"+suffix;
        extraModels.add(id); repository.registerModel(id,"Delivery isolation");
        List<Map<String,Object>> rows = new ArrayList<>();
        for (int i=1;i<=count;i++) {
            // Seed only the durable delivery queue to exercise a backlog larger than the old global limit.
            var batch = json.createObjectNode().put("modelId",id).put("opBatchId","batch-"+i);
            batch.set("assignedRevisionRange",json.createObjectNode().put("from",i).put("to",i));
            batch.putArray("ops").add(json.readTree(create("queued-"+i)));
            rows.add(Map.of("id","batch-"+i,"revision",i,"json",batch.toString()));
        }
        try (var driver = org.neo4j.driver.GraphDatabase.driver(repository.uri,org.neo4j.driver.AuthTokens.basic(repository.username,repository.password));
             var session = driver.session()) {
            session.run("MATCH (m:Model {modelId:$modelId}) UNWIND $rows AS row "
                    + "CREATE (c:Commit {modelId:$modelId,opBatchId:row.id,revisionFrom:row.revision,revisionTo:row.revision, "
                    + "published:false,payloadPruned:false,batchJson:row.json}) CREATE (m)-[:HAS_COMMIT]->(c)",
                    Map.of("modelId",id,"rows",rows)).consume();
        }
        return id;
    }

    @Test
    void modelSelectionAndPayloadBudgetsRemainIndependentAcrossRestart() throws Exception {
        String busy = pendingModel("a",120), healthy = pendingModel("z",1);
        Assertions.assertEquals(List.of(busy,healthy),repository.pendingBroadcastModels(model,2));
        Assertions.assertEquals(List.of("batch-1","batch-2","batch-3"),repository.pendingBroadcasts(busy,3).stream()
                .map(b -> b.path("opBatchId").asText()).toList());
        Assertions.assertEquals(1,repository.pendingBroadcasts(healthy,3).size());
        Assertions.assertEquals(healthy,repository.pendingBroadcastModels(busy,1).getFirst());
        repository.close(); repository = open();
        Assertions.assertEquals(List.of(busy,healthy),repository.pendingBroadcastModels(model,2));
        repository.markBroadcastPublished(busy,"batch-1");
        Assertions.assertEquals("batch-2",repository.pendingBroadcasts(busy,1).getFirst().path("opBatchId").asText());
        Assertions.assertThrows(IllegalArgumentException.class,() -> repository.pendingBroadcasts(busy,0));
        Assertions.assertThrows(IllegalArgumentException.class,() -> repository.pendingBroadcastModels(null,0));
    }

    @Test
    void corruptMissingAndMismatchedPayloadsRemainModelLocalAndCannotBeSkipped() throws Exception {
        String broken = pendingModel("a",2), healthy = pendingModel("z",1);
        try (var driver = org.neo4j.driver.GraphDatabase.driver(repository.uri,org.neo4j.driver.AuthTokens.basic(repository.username,repository.password));
             var session = driver.session()) {
            session.run("MATCH (c:Commit {modelId:$modelId,opBatchId:'batch-1'}) SET c.batchJson='broken-json'",
                    Map.of("modelId",broken)).consume();
            Assertions.assertEquals(List.of(broken,healthy),repository.pendingBroadcastModels(model,2));
            Assertions.assertThrows(IllegalStateException.class,() -> repository.pendingBroadcasts(broken,1));
            Assertions.assertEquals(1,repository.pendingBroadcasts(healthy,1).size());
            session.run("MATCH (c:Commit {modelId:$modelId,opBatchId:'batch-1'}) REMOVE c.batchJson",
                    Map.of("modelId",broken)).consume();
            Assertions.assertThrows(IllegalStateException.class,() -> repository.pendingBroadcasts(broken,1),
                    "missing payload at the head must block this model rather than return its successor");
            String foreign = repository.pendingBroadcasts(healthy,1).getFirst().toString();
            session.run("MATCH (c:Commit {modelId:$modelId,opBatchId:'batch-1'}) SET c.batchJson=$json",
                    Map.of("modelId",broken,"json",foreign)).consume();
            Assertions.assertThrows(IllegalStateException.class,() -> repository.pendingBroadcasts(broken,1));
        }
    }

    @Test
    void expiredWriterCannotAcknowledgePendingPublication() throws Exception {
        commit(batch("pending",1,create("first")));
        repository.runtimeLeaseKey = "delivery-lease-"+UUID.randomUUID();
        Assertions.assertTrue(repository.acquireServerLease(UUID.randomUUID().toString(),30_000));
        try (var driver = org.neo4j.driver.GraphDatabase.driver(repository.uri,org.neo4j.driver.AuthTokens.basic(repository.username,repository.password));
             var session = driver.session()) {
            session.run("MATCH (r:ArchimeshRuntime {id:$key}) SET r.expiresAt=0",
                    Map.of("key",repository.runtimeLeaseKey)).consume();
        }
        Assertions.assertThrows(IllegalStateException.class,() -> repository.markBroadcastPublished(model,"pending"));
        Assertions.assertEquals(1,repository.pendingBroadcasts(model,1).size());
    }
}
