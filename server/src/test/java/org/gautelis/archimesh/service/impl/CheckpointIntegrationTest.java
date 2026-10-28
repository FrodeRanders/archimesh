package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gautelis.archimesh.model.ModelCheckpoint;
import org.gautelis.archimesh.model.RevisionRange;
import org.junit.jupiter.api.*;

import java.util.UUID;

class CheckpointIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private Neo4jRepositoryImpl repository;
    private String model;
    private long head;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        repository = open();
        model = "checkpoint-it-" + UUID.randomUUID();
        repository.registerModel(model, "Checkpoint regression");
    }

    private Neo4jRepositoryImpl open() { return open(100); }

    private Neo4jRepositoryImpl open(int limit) {
        var repo = new Neo4jRepositoryImpl();
        repo.uri = System.getenv().getOrDefault("NEO4J_URI", "bolt://localhost:7687");
        repo.username = System.getenv().getOrDefault("NEO4J_USER", "neo4j");
        repo.password = System.getenv().getOrDefault("NEO4J_PASSWORD", "devpassword");
        repo.compactionBatchLimit = limit;
        repo.init();
        return repo;
    }

    @AfterEach
    void cleanUp() {
        if (repository != null) { repository.deleteModel(model); repository.close(); }
    }

    private ObjectNode commit(String batchId, String... operations) throws Exception {
        var batch = json.createObjectNode().put("modelId",model).put("opBatchId",batchId).put("timestamp","2026-10-28T00:00:00Z");
        batch.set("assignedRevisionRange",json.createObjectNode().put("from",head+1).put("to",head+operations.length));
        var ops = batch.putArray("ops");
        for (String operation : operations) {
            var op = (ObjectNode) json.readTree(operation);
            if (!op.has("causal")) op.set("causal",json.createObjectNode().put("lamport",100+head+ops.size()).put("clientId","test"));
            ops.add(op);
        }
        repository.commitBatch(model,batchId,new RevisionRange(head+1,head+operations.length),batch);
        head += operations.length;
        return batch;
    }

    private String create(String id) {
        return "{\"type\":\"CreateElement\",\"element\":{\"id\":\"elem:"+id+"\",\"archimateType\":\"BusinessActor\",\"name\":\""+id+"\"}}";
    }

    @Test
    void checkpointRestoresPropertiesMembershipsAndRawMergeMetadataExactly() throws Exception {
        commit("initial", create("a"), create("b"), create("deleted"),
                "{\"type\":\"CreateFolder\",\"folder\":{\"id\":\"folder:custom\",\"folderType\":\"USER\",\"name\":\"Custom\",\"parentFolderId\":\"folder:root-business\"}}",
                "{\"type\":\"MoveElementToFolder\",\"elementId\":\"elem:a\",\"folderId\":\"folder:custom\"}",
                "{\"type\":\"SetProperty\",\"targetId\":\"elem:a\",\"key\":\"owner\",\"value\":\"Alice\"}",
                "{\"type\":\"UnsetProperty\",\"targetId\":\"elem:a\",\"key\":\"removed\"}",
                "{\"type\":\"AddPropertySetMember\",\"targetId\":\"elem:a\",\"key\":\"watchers\",\"member\":\"alpha\"}",
                "{\"type\":\"RemovePropertySetMember\",\"targetId\":\"elem:a\",\"key\":\"watchers\",\"member\":\"beta\"}",
                "{\"type\":\"DeleteElement\",\"elementId\":\"elem:deleted\"}",
                "{\"type\":\"CreateRelationship\",\"relationship\":{\"id\":\"rel:r\",\"archimateType\":\"AssociationRelationship\",\"sourceId\":\"elem:a\",\"targetId\":\"elem:b\"}}",
                "{\"type\":\"CreateView\",\"view\":{\"id\":\"view:v\",\"name\":\"View\"}}",
                "{\"type\":\"CreateViewObject\",\"viewObject\":{\"id\":\"vo:a\",\"viewId\":\"view:v\",\"representsId\":\"elem:a\",\"notationJson\":{\"x\":10,\"y\":20}}}",
                "{\"type\":\"CreateViewObject\",\"viewObject\":{\"id\":\"vo:b\",\"viewId\":\"view:v\",\"representsId\":\"elem:b\",\"notationJson\":{\"x\":50,\"y\":60}}}",
                "{\"type\":\"CreateConnection\",\"connection\":{\"id\":\"conn:c\",\"viewId\":\"view:v\",\"representsId\":\"rel:r\",\"sourceViewObjectId\":\"vo:a\",\"targetViewObjectId\":\"vo:b\",\"notationJson\":{\"lineWidth\":2}}}",
                "{\"type\":\"AddViewObjectChildMember\",\"parentViewObjectId\":\"vo:a\",\"childViewObjectId\":\"vo:b\"}");
        JsonNode before = repository.loadSnapshot(model);
        ModelCheckpoint checkpoint = repository.createCheckpoint(model);
        Assertions.assertEquals(head,checkpoint.revision());
        Assertions.assertEquals(checkpoint,repository.createCheckpoint(model),"checkpoint at an existing boundary is immutable");
        repository.rebuildProjection(model,checkpoint,json.createArrayNode(),head);
        Assertions.assertEquals(before,repository.loadSnapshot(model));
        // Native clock/tombstone properties, including updatedRevision, survive too.
        var support = new Neo4jCheckpointSupport(json);
        try (var driver = org.neo4j.driver.GraphDatabase.driver(repository.uri,org.neo4j.driver.AuthTokens.basic(repository.username,repository.password));
             var session = driver.session()) {
            var recaptured = session.executeRead(tx -> support.capture(tx,model,head));
            Assertions.assertEquals(checkpoint.state(),recaptured.state());
        }
    }

    @Test
    void checkpointPlusTailPreservesStaleWriteAndDeleteSafety() throws Exception {
        commit("initial",create("a"),create("gone"),"{\"type\":\"DeleteElement\",\"elementId\":\"elem:gone\"}");
        ModelCheckpoint checkpoint = repository.createCheckpoint(model);
        commit("tail", "{\"type\":\"UpdateElement\",\"elementId\":\"elem:a\",\"patch\":{\"name\":\"latest\"}}",
                "{\"type\":\"CreateElement\",\"element\":{\"id\":\"elem:gone\",\"archimateType\":\"BusinessActor\"},\"causal\":{\"lamport\":1,\"clientId\":\"old\"}}");
        JsonNode expected = repository.loadSnapshot(model);
        repository.rebuildProjection(model,checkpoint,repository.loadOpBatches(model,checkpoint.revision()+1,head),head);
        Assertions.assertEquals(expected,repository.loadSnapshot(model));
        Assertions.assertFalse(repository.elementExists(model,"elem:gone"));
    }
    @Test
    void pruningRequiresCheckpointAndPublicationAndKeepsAcceptanceReceipts() throws Exception {
        commit("first",create("a"));
        repository.markBroadcastPublished(model,"first");
        Assertions.assertEquals(0,repository.compactMetadata(model,0).deletedOpCount());
        commit("pending",create("b"));
        commit("later",create("c"));
        repository.markBroadcastPublished(model,"later");
        ModelCheckpoint checkpoint = repository.createCheckpoint(model);
        var preview = repository.compactMetadata(model,0,true);
        Assertions.assertTrue(preview.dryRun());
        Assertions.assertEquals(1,preview.eligibleBatchCount(),"unpublished batch must fence later payloads too");
        Assertions.assertEquals(0,preview.prunedBatchCount());
        Assertions.assertEquals(3,repository.loadOpBatches(model,1,head).size());
        var first = repository.compactMetadata(model,0);
        Assertions.assertEquals(1,first.prunedBatchCount());
        Assertions.assertEquals(1,first.deletedOpCount());
        Assertions.assertEquals(0,first.deletedCommitCount());
        Assertions.assertEquals(2,repository.loadOpBatches(model,1,head).size());
        repository.markBroadcastPublished(model,"pending");
        var rest = repository.compactMetadata(model,0);
        Assertions.assertEquals(2,rest.prunedBatchCount());
        Assertions.assertTrue(repository.loadOpBatches(model,1,head).isEmpty());
        Assertions.assertEquals(new RevisionRange(1,1),repository.findAcceptedRange(model,"first").orElseThrow());
        Assertions.assertEquals(3,repository.loadReceipts(model,head).size());
        JsonNode expected = repository.loadSnapshot(model);
        repository.rebuildProjection(model,checkpoint,json.createArrayNode(),head);
        Assertions.assertEquals(expected,repository.loadSnapshot(model));
        Assertions.assertEquals(0,repository.compactMetadata(model,0).prunedBatchCount(),"repeated pruning is idempotent");
    }

    @Test
    void boundedPruningResumesAfterRepositoryRestart() throws Exception {
        commit("one",create("a")); commit("two",create("b")); commit("three",create("c"));
        for (String id : new String[]{"one","two","three"}) repository.markBroadcastPublished(model,id);
        repository.createCheckpoint(model);
        repository.close(); repository = open(1);
        Assertions.assertEquals(1,repository.compactMetadata(model,0).prunedThroughRevision());
        repository.close(); repository = open(1);
        Assertions.assertEquals(2,repository.compactMetadata(model,0).prunedThroughRevision());
        Assertions.assertEquals(3,repository.compactMetadata(model,0).prunedThroughRevision());
        Assertions.assertEquals(0,repository.compactMetadata(model,0).eligibleBatchCount());
        Assertions.assertEquals(3,repository.loadReceipts(model,head).size());
    }

    @Test
    void retentionNeverSplitsAnAcceptedBatch() throws Exception {
        commit("one",create("a"),create("b"));
        commit("two",create("c"),create("d"));
        repository.markBroadcastPublished(model,"one"); repository.markBroadcastPublished(model,"two");
        repository.createCheckpoint(model);
        var result = repository.compactMetadata(model,1);
        Assertions.assertEquals(3,result.watermarkRevision());
        Assertions.assertEquals(2,result.prunedThroughRevision());
        var retained = repository.loadOpBatches(model,3,4);
        Assertions.assertEquals(1,retained.size());
        Assertions.assertEquals(2,retained.get(0).path("ops").size());
    }

    @Test
    void damagedCheckpointCannotAuthorizePruning() throws Exception {
        commit("one",create("a")); repository.markBroadcastPublished(model,"one");
        repository.createCheckpoint(model);
        try (var driver = org.neo4j.driver.GraphDatabase.driver(repository.uri,org.neo4j.driver.AuthTokens.basic(repository.username,repository.password));
             var session = driver.session()) {
            session.run("MATCH (c:ModelCheckpoint {modelId:$modelId}) SET c.sha256='damaged'",java.util.Map.of("modelId",model)).consume();
        }
        Assertions.assertThrows(RuntimeException.class,() -> repository.compactMetadata(model,0));
        Assertions.assertEquals(1,repository.loadOpBatches(model,1,head).size());
    }

    @Test
    void newerCheckpointCanReplacePrunedBaseAndCaptureTailMetadata() throws Exception {
        commit("base",create("a")); repository.markBroadcastPublished(model,"base");
        var first = repository.createCheckpoint(model);
        repository.compactMetadata(model,0);
        commit("tail","{\"type\":\"UnsetProperty\",\"targetId\":\"elem:a\",\"key\":\"removed\"}");
        repository.markBroadcastPublished(model,"tail");
        var newer = repository.createCheckpoint(model);
        Assertions.assertTrue(newer.revision() > first.revision());
        Assertions.assertEquals(newer,repository.latestCheckpoint(model).orElseThrow());
        var expected = repository.loadSnapshot(model);
        repository.compactMetadata(model,0);
        repository.rebuildProjection(model,newer,json.createArrayNode(),head);
        Assertions.assertEquals(expected,repository.loadSnapshot(model));
        Assertions.assertEquals(2,repository.loadReceipts(model,head).size());
        Assertions.assertTrue(repository.loadOpBatches(model,1,head).isEmpty());
    }

    @Test
    void unsupportedNativePropertyCannotBeSilentlyChangedByCheckpointEncoding() throws Exception {
        commit("base",create("a")); repository.markBroadcastPublished(model,"base");
        try (var driver = org.neo4j.driver.GraphDatabase.driver(repository.uri,org.neo4j.driver.AuthTokens.basic(repository.username,repository.password));
             var session = driver.session()) {
            session.run("MATCH (n:Element {modelId:$modelId}) SET n.nativeBytes=$bytes",
                    java.util.Map.of("modelId",model,"bytes",new byte[]{1,2,3})).consume();
        }
        Assertions.assertThrows(RuntimeException.class,() -> repository.createCheckpoint(model));
        Assertions.assertTrue(repository.latestCheckpoint(model).isEmpty());
        Assertions.assertEquals(0,repository.compactMetadata(model,0).prunedBatchCount());
    }

    @Test
    void checkpointRetentionPreservesNewestRecoveryBaseAndAcceptanceReceipts() throws Exception {
        for (String id : new String[]{"one","two","three"}) {
            commit(id,create(id)); repository.markBroadcastPublished(model,id); repository.createCheckpoint(model);
        }
        repository.compactMetadata(model,0);
        Assertions.assertEquals(1,repository.pruneCheckpoints(model,2));
        Assertions.assertEquals(3,repository.latestCheckpoint(model).orElseThrow().revision());
        Assertions.assertEquals(1,repository.pruneCheckpoints(model,1));
        Assertions.assertEquals(0,repository.pruneCheckpoints(model,1));
        Assertions.assertThrows(IllegalArgumentException.class,() -> repository.pruneCheckpoints(model,0));
        Assertions.assertEquals(3,repository.loadReceipts(model,head).size());
        var expected = repository.loadSnapshot(model);
        repository.rebuildProjection(model,repository.latestCheckpoint(model).orElseThrow(),json.createArrayNode(),head);
        Assertions.assertEquals(expected,repository.loadSnapshot(model));
    }

    @Test
    void checkpointRetentionRejectsDamagedLatestBaseAndIncompleteTail() throws Exception {
        commit("one",create("a")); repository.createCheckpoint(model);
        commit("two",create("b")); repository.createCheckpoint(model);
        commit("tail",create("c"));
        try (var driver = org.neo4j.driver.GraphDatabase.driver(repository.uri,org.neo4j.driver.AuthTokens.basic(repository.username,repository.password));
             var session = driver.session()) {
            session.run("MATCH (c:Commit {modelId:$modelId,opBatchId:'tail'})-[:HAS_OP]->(o:Op) DETACH DELETE o",
                    java.util.Map.of("modelId",model)).consume();
            Assertions.assertThrows(IllegalStateException.class,() -> repository.pruneCheckpoints(model,1));
            session.run("MATCH (c:ModelCheckpoint {modelId:$modelId,revision:2}) SET c.sha256='damaged'",
                    java.util.Map.of("modelId",model)).consume();
            Assertions.assertThrows(RuntimeException.class,() -> repository.pruneCheckpoints(model,1));
            Assertions.assertEquals(2,session.run("MATCH (c:ModelCheckpoint {modelId:$modelId}) RETURN count(c) AS n",
                    java.util.Map.of("modelId",model)).single().get("n").asInt());
        }
    }

    @Test
    void expiredDatabaseLeaseRejectsMaintenanceWritesWithoutChanges() throws Exception {
        commit("one",create("a")); repository.markBroadcastPublished(model,"one");
        var checkpoint = repository.createCheckpoint(model);
        commit("tail",create("b")); repository.markBroadcastPublished(model,"tail");
        repository.runtimeLeaseKey = "checkpoint-lease-"+UUID.randomUUID();
        String owner = UUID.randomUUID().toString();
        Assertions.assertTrue(repository.acquireServerLease(owner,30_000));
        try (var driver = org.neo4j.driver.GraphDatabase.driver(repository.uri,org.neo4j.driver.AuthTokens.basic(repository.username,repository.password));
             var session = driver.session()) {
            session.run("MATCH (r:ArchimeshRuntime {id:$key}) SET r.expiresAt=0",
                    java.util.Map.of("key",repository.runtimeLeaseKey)).consume();
        }
        Assertions.assertThrows(IllegalStateException.class,() -> repository.createCheckpoint(model));
        Assertions.assertThrows(IllegalStateException.class,() -> repository.compactMetadata(model,0));
        Assertions.assertThrows(IllegalStateException.class,() -> repository.pruneCheckpoints(model,1));
        Assertions.assertThrows(IllegalStateException.class,() -> repository.rebuildProjection(model,checkpoint,
                repository.loadOpBatches(model,2,head),head));
        Assertions.assertEquals(1,repository.latestCheckpoint(model).orElseThrow().revision());
        Assertions.assertEquals(2,repository.loadOpBatches(model,1,head).size());
        Assertions.assertEquals(head,repository.readHeadRevision(model));
    }

}
