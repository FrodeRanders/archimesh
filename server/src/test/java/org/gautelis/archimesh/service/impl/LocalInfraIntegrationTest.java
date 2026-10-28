package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gautelis.archimesh.model.RevisionRange;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

import java.time.Duration;
import java.util.*;

class LocalInfraIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void kafkaPublisherRoundTripAgainstLocalBroker() {
        assumeLocalInfraEnabled();

        String bootstrapServers = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String topicPrefix = "archimesh.model";
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String topic = topicPrefix + "." + modelId + ".ops";

        KafkaPublisherImpl publisher = new KafkaPublisherImpl();
        publisher.bootstrapServers = bootstrapServers;
        publisher.topicPrefix = topicPrefix;
        publisher.objectMapper = MAPPER;
        publisher.init();

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("modelId", modelId);
        payload.put("opBatchId", UUID.randomUUID().toString());
        payload.put("timestamp", "2026-01-01T00:00:00Z");
        payload.set("ops", JsonNodeFactory.instance.arrayNode());
        publisher.publishOps(modelId, payload).join();
        publisher.close();

        boolean received = false;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps(bootstrapServers))) {
            consumer.subscribe(java.util.List.of(topic));
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && !received) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.topic().equals(topic) && record.value().contains(modelId)) {
                        received = true;
                        break;
                    }
                }
            }
        }

        Assertions.assertTrue(received, "Expected one published ops message on local Kafka");
    }

    @Test
    void neo4jRepositoryWritesCommitAndHeadRevision() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String opBatchId = UUID.randomUUID().toString();

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode element = JsonNodeFactory.instance.objectNode();
        element.put("id", "elem:e1");
        element.put("archimateType", "BusinessActor");

        ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("type", "CreateElement");
        op.set("element", element);

        ObjectNode opBatch = JsonNodeFactory.instance.objectNode();
        opBatch.put("modelId", modelId);
        opBatch.put("opBatchId", opBatchId);
        opBatch.put("timestamp", "2026-01-01T00:00:00Z");
        opBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(op));

        RevisionRange range = new RevisionRange(1, 1);
        repository.appendOpLog(modelId, opBatchId, range, opBatch);
        repository.applyToMaterializedState(modelId, opBatch);
        repository.updateHeadRevision(modelId, 1);

        long head = repository.readHeadRevision(modelId);
        Assertions.assertEquals(1L, head);
        Assertions.assertTrue(repository.elementExists(modelId, "elem:e1"));

        var snapshot = repository.loadSnapshot(modelId);
        Assertions.assertEquals(modelId, snapshot.path("modelId").asText());
        Assertions.assertEquals(1, snapshot.path("elements").size());

        repository.close();

        try (var driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             var session = driver.session()) {
            var modelHead = session.run("MATCH (m:Model {modelId: $modelId}) RETURN m.headRevision AS head",
                    Map.of("modelId", modelId)).single().get("head").asLong();
            var commitCount = session.run("MATCH (:Commit {modelId: $modelId, opBatchId: $opBatchId}) RETURN count(*) AS c",
                    Map.of("modelId", modelId, "opBatchId", opBatchId)).single().get("c").asInt();

            Assertions.assertEquals(1L, modelHead);
            Assertions.assertEquals(1, commitCount);
        }
    }

    @Test
    void neo4jRepositoryReplayAndConsistencyChecks() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        ObjectNode element = JsonNodeFactory.instance.objectNode();
        element.put("id", "elem:e1");
        element.put("archimateType", "BusinessActor");
        element.put("name", "Actor 1");
        createElement.set("element", element);

        String batchId1 = "batch-1-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId2 = "batch-2-" + UUID.randomUUID().toString().substring(0, 8);

        ObjectNode batch1 = JsonNodeFactory.instance.objectNode();
        batch1.put("modelId", modelId);
        batch1.put("opBatchId", batchId1);
        batch1.put("timestamp", "2026-01-01T00:00:00Z");
        batch1.set("ops", JsonNodeFactory.instance.arrayNode().add(createElement));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        ObjectNode view = JsonNodeFactory.instance.objectNode();
        view.put("id", "view:v1");
        view.put("name", "View 1");
        createView.set("view", view);

        ObjectNode createViewObject = JsonNodeFactory.instance.objectNode();
        createViewObject.put("type", "CreateViewObject");
        ObjectNode viewObject = JsonNodeFactory.instance.objectNode();
        viewObject.put("id", "vo:o1");
        viewObject.put("viewId", "view:v1");
        viewObject.put("representsId", "elem:e1");
        viewObject.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("x", 50).put("y", 50).put("width", 120).put("height", 55));
        createViewObject.set("viewObject", viewObject);

        ObjectNode batch2 = JsonNodeFactory.instance.objectNode();
        batch2.put("modelId", modelId);
        batch2.put("opBatchId", batchId2);
        batch2.put("timestamp", "2026-01-01T00:00:01Z");
        batch2.set("ops", JsonNodeFactory.instance.arrayNode().add(createView).add(createViewObject));

        repository.appendOpLog(modelId, batchId1, new RevisionRange(1, 1), batch1);
        repository.applyToMaterializedState(modelId, batch1);
        repository.appendOpLog(modelId, batchId2, new RevisionRange(2, 3), batch2);
        repository.applyToMaterializedState(modelId, batch2);
        repository.updateHeadRevision(modelId, 3);

        Assertions.assertEquals(3L, repository.readHeadRevision(modelId));
        Assertions.assertEquals(3L, repository.readLatestCommitRevision(modelId));
        Assertions.assertTrue(repository.isMaterializedStateConsistent(modelId, 3L));

        var snapshot = repository.loadSnapshot(modelId);
        Assertions.assertEquals(1, snapshot.path("elements").size());
        Assertions.assertEquals(1, snapshot.path("views").size());
        Assertions.assertEquals(1, snapshot.path("viewObjects").size());

        var delta = repository.loadOpBatches(modelId, 1, 3);
        Assertions.assertEquals(2, delta.size());
        Assertions.assertEquals(1, delta.get(0).path("ops").size());
        Assertions.assertEquals(2, delta.get(1).path("ops").size());

        repository.close();
    }

    @Test
    void viewObjectGeometryUsesLamportLwwMerge() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String elementId = "elem:e1-" + UUID.randomUUID().toString().substring(0, 6);
        String viewId = "view:v1-" + UUID.randomUUID().toString().substring(0, 6);
        String viewObjectId = "vo:o1-" + UUID.randomUUID().toString().substring(0, 6);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        ObjectNode element = JsonNodeFactory.instance.objectNode();
        element.put("id", elementId);
        element.put("archimateType", "BusinessActor");
        createElement.set("element", element);
        createElement.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 1)
                .put("opId", "batch-1:0"));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        ObjectNode view = JsonNodeFactory.instance.objectNode();
        view.put("id", viewId);
        view.put("name", "View 1");
        createView.set("view", view);
        createView.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 2)
                .put("opId", "batch-1:1"));

        ObjectNode createViewObject = JsonNodeFactory.instance.objectNode();
        createViewObject.put("type", "CreateViewObject");
        ObjectNode viewObject = JsonNodeFactory.instance.objectNode();
        viewObject.put("id", viewObjectId);
        viewObject.put("viewId", viewId);
        viewObject.put("representsId", elementId);
        viewObject.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("x", 10).put("y", 10).put("width", 100).put("height", 50).put("name", "base"));
        createViewObject.set("viewObject", viewObject);
        createViewObject.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 10)
                .put("opId", "batch-1:2"));

        ObjectNode staleGeometryUpdate = JsonNodeFactory.instance.objectNode();
        staleGeometryUpdate.put("type", "UpdateViewObjectOpaque");
        staleGeometryUpdate.put("viewId", viewId);
        staleGeometryUpdate.put("viewObjectId", viewObjectId);
        staleGeometryUpdate.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("x", 999).put("y", 999).put("width", 999).put("height", 999).put("name", "stale-name"));
        staleGeometryUpdate.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 5)
                .put("opId", "batch-2:0"));

        ObjectNode winningGeometryUpdate = JsonNodeFactory.instance.objectNode();
        winningGeometryUpdate.put("type", "UpdateViewObjectOpaque");
        winningGeometryUpdate.put("viewId", viewId);
        winningGeometryUpdate.put("viewObjectId", viewObjectId);
        winningGeometryUpdate.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("x", 20));
        winningGeometryUpdate.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 11)
                .put("opId", "batch-3:0"));

        String batchId1 = "batch-1-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId2 = "batch-2-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId3 = "batch-3-" + UUID.randomUUID().toString().substring(0, 8);

        ObjectNode batch1 = JsonNodeFactory.instance.objectNode();
        batch1.put("modelId", modelId);
        batch1.put("opBatchId", batchId1);
        batch1.put("timestamp", "2026-01-01T00:00:00Z");
        batch1.set("ops", JsonNodeFactory.instance.arrayNode().add(createElement).add(createView).add(createViewObject));

        ObjectNode batch2 = JsonNodeFactory.instance.objectNode();
        batch2.put("modelId", modelId);
        batch2.put("opBatchId", batchId2);
        batch2.put("timestamp", "2026-01-01T00:00:01Z");
        batch2.set("ops", JsonNodeFactory.instance.arrayNode().add(staleGeometryUpdate));

        ObjectNode batch3 = JsonNodeFactory.instance.objectNode();
        batch3.put("modelId", modelId);
        batch3.put("opBatchId", batchId3);
        batch3.put("timestamp", "2026-01-01T00:00:02Z");
        batch3.set("ops", JsonNodeFactory.instance.arrayNode().add(winningGeometryUpdate));

        repository.appendOpLog(modelId, batchId1, new RevisionRange(1, 3), batch1);
        repository.applyToMaterializedState(modelId, batch1);
        repository.appendOpLog(modelId, batchId2, new RevisionRange(4, 4), batch2);
        repository.applyToMaterializedState(modelId, batch2);
        repository.appendOpLog(modelId, batchId3, new RevisionRange(5, 5), batch3);
        repository.applyToMaterializedState(modelId, batch3);
        repository.updateHeadRevision(modelId, 5L);

        var snapshot = repository.loadSnapshot(modelId);
        var notation = snapshot.path("viewObjects").get(0).path("notationJson");

        Assertions.assertEquals(20, notation.path("x").asInt());
        Assertions.assertEquals(10, notation.path("y").asInt(), "stale y update should be ignored");
        Assertions.assertEquals(100, notation.path("width").asInt(), "stale width update should be ignored");
        Assertions.assertEquals(50, notation.path("height").asInt(), "stale height update should be ignored");
        Assertions.assertEquals("base", notation.path("name").asText(), "stale name update should be ignored under LWW");

        repository.close();
    }

    @Test
    void viewObjectStyleFieldsUseLamportLwwMerge() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 1));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        createView.set("view", JsonNodeFactory.instance.objectNode()
                .put("id", "view:v1")
                .put("name", "View 1"));
        createView.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 2));

        ObjectNode createViewObject = JsonNodeFactory.instance.objectNode();
        createViewObject.put("type", "CreateViewObject");
        createViewObject.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", "vo:o1")
                .put("viewId", "view:v1")
                .put("representsId", "elem:e1")
                .set("notationJson", JsonNodeFactory.instance.objectNode()
                        .put("x", 10)
                        .put("y", 10)
                        .put("width", 100)
                        .put("height", 50)
                        .put("lineColor", "#111111")
                        .put("fontColor", "#222222")
                        .put("name", "base")));
        createViewObject.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 10));

        ObjectNode staleStyleUpdate = JsonNodeFactory.instance.objectNode();
        staleStyleUpdate.put("type", "UpdateViewObjectOpaque");
        staleStyleUpdate.put("viewId", "view:v1");
        staleStyleUpdate.put("viewObjectId", "vo:o1");
        staleStyleUpdate.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("lineColor", "#999999")
                .put("fontColor", "#999999")
                .put("name", "stale-name"));
        staleStyleUpdate.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 5));

        ObjectNode winningStyleUpdate = JsonNodeFactory.instance.objectNode();
        winningStyleUpdate.put("type", "UpdateViewObjectOpaque");
        winningStyleUpdate.put("viewId", "view:v1");
        winningStyleUpdate.put("viewObjectId", "vo:o1");
        winningStyleUpdate.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("lineColor", "#333333"));
        winningStyleUpdate.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 11));

        ObjectNode tieWinnerStyleUpdate = JsonNodeFactory.instance.objectNode();
        tieWinnerStyleUpdate.put("type", "UpdateViewObjectOpaque");
        tieWinnerStyleUpdate.put("viewId", "view:v1");
        tieWinnerStyleUpdate.put("viewObjectId", "vo:o1");
        tieWinnerStyleUpdate.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("fontColor", "#444444"));
        tieWinnerStyleUpdate.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 10));

        ObjectNode tieLoserStyleUpdate = JsonNodeFactory.instance.objectNode();
        tieLoserStyleUpdate.put("type", "UpdateViewObjectOpaque");
        tieLoserStyleUpdate.put("viewId", "view:v1");
        tieLoserStyleUpdate.put("viewObjectId", "vo:o1");
        tieLoserStyleUpdate.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("fontColor", "#555555"));
        tieLoserStyleUpdate.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 10));

        ObjectNode batch = JsonNodeFactory.instance.objectNode();
        batch.put("modelId", modelId);
        batch.put("opBatchId", "vo-style-batch");
        batch.put("timestamp", "2026-01-01T00:00:00Z");
        batch.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(createElement)
                .add(createView)
                .add(createViewObject)
                .add(staleStyleUpdate)
                .add(winningStyleUpdate)
                .add(tieWinnerStyleUpdate)
                .add(tieLoserStyleUpdate));

        repository.applyToMaterializedState(modelId, batch);

        var notation = repository.loadSnapshot(modelId)
                .path("viewObjects")
                .get(0)
                .path("notationJson");

        Assertions.assertEquals("#333333", notation.path("lineColor").asText());
        Assertions.assertEquals("#444444", notation.path("fontColor").asText());
        Assertions.assertEquals("base", notation.path("name").asText());

        repository.close();
    }

    @Test
    void viewObjectGeometryUsesClientIdTieBreakWhenLamportEqual() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String elementId = "elem:e1-" + UUID.randomUUID().toString().substring(0, 6);
        String viewId = "view:v1-" + UUID.randomUUID().toString().substring(0, 6);
        String viewObjectId = "vo:o1-" + UUID.randomUUID().toString().substring(0, 6);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        ObjectNode element = JsonNodeFactory.instance.objectNode();
        element.put("id", elementId);
        element.put("archimateType", "BusinessActor");
        createElement.set("element", element);
        createElement.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 1)
                .put("opId", "batch-1:0"));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        ObjectNode view = JsonNodeFactory.instance.objectNode();
        view.put("id", viewId);
        view.put("name", "View 1");
        createView.set("view", view);
        createView.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 2)
                .put("opId", "batch-1:1"));

        ObjectNode createViewObject = JsonNodeFactory.instance.objectNode();
        createViewObject.put("type", "CreateViewObject");
        ObjectNode viewObject = JsonNodeFactory.instance.objectNode();
        viewObject.put("id", viewObjectId);
        viewObject.put("viewId", viewId);
        viewObject.put("representsId", elementId);
        viewObject.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("x", 10).put("y", 10).put("width", 100).put("height", 50));
        createViewObject.set("viewObject", viewObject);
        createViewObject.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 10)
                .put("opId", "batch-1:2"));

        ObjectNode tieFirst = JsonNodeFactory.instance.objectNode();
        tieFirst.put("type", "UpdateViewObjectOpaque");
        tieFirst.put("viewId", viewId);
        tieFirst.put("viewObjectId", viewObjectId);
        tieFirst.set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 101));
        tieFirst.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 12)
                .put("opId", "batch-2:0"));

        ObjectNode tieSecond = JsonNodeFactory.instance.objectNode();
        tieSecond.put("type", "UpdateViewObjectOpaque");
        tieSecond.put("viewId", viewId);
        tieSecond.put("viewObjectId", viewObjectId);
        tieSecond.set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 202));
        tieSecond.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 12)
                .put("opId", "batch-3:0"));

        String batchId1 = "batch-1-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId2 = "batch-2-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId3 = "batch-3-" + UUID.randomUUID().toString().substring(0, 8);

        ObjectNode batch1 = JsonNodeFactory.instance.objectNode();
        batch1.put("modelId", modelId);
        batch1.put("opBatchId", batchId1);
        batch1.put("timestamp", "2026-01-01T00:00:00Z");
        batch1.set("ops", JsonNodeFactory.instance.arrayNode().add(createElement).add(createView).add(createViewObject));

        ObjectNode batch2 = JsonNodeFactory.instance.objectNode();
        batch2.put("modelId", modelId);
        batch2.put("opBatchId", batchId2);
        batch2.put("timestamp", "2026-01-01T00:00:01Z");
        batch2.set("ops", JsonNodeFactory.instance.arrayNode().add(tieFirst));

        ObjectNode batch3 = JsonNodeFactory.instance.objectNode();
        batch3.put("modelId", modelId);
        batch3.put("opBatchId", batchId3);
        batch3.put("timestamp", "2026-01-01T00:00:02Z");
        batch3.set("ops", JsonNodeFactory.instance.arrayNode().add(tieSecond));

        repository.appendOpLog(modelId, batchId1, new RevisionRange(1, 3), batch1);
        repository.applyToMaterializedState(modelId, batch1);
        repository.appendOpLog(modelId, batchId2, new RevisionRange(4, 4), batch2);
        repository.applyToMaterializedState(modelId, batch2);
        repository.appendOpLog(modelId, batchId3, new RevisionRange(5, 5), batch3);
        repository.applyToMaterializedState(modelId, batch3);
        repository.updateHeadRevision(modelId, 5L);

        var snapshot = repository.loadSnapshot(modelId);
        var notation = snapshot.path("viewObjects").get(0).path("notationJson");

        Assertions.assertEquals(202, notation.path("x").asInt(),
                "equal lamport should resolve by clientId tie-break (lexicographically larger wins)");
        Assertions.assertEquals(10, notation.path("y").asInt());
        Assertions.assertEquals(100, notation.path("width").asInt());
        Assertions.assertEquals(50, notation.path("height").asInt());

        repository.close();
    }

    @Test
    void elementSemanticFieldsUseLamportLwwMerge() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "base")
                .put("documentation", "base-doc"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 10)
                .put("opId", "batch-1:0"));

        ObjectNode staleUpdate = JsonNodeFactory.instance.objectNode();
        staleUpdate.put("type", "UpdateElement");
        staleUpdate.put("elementId", "elem:e1");
        staleUpdate.set("patch", JsonNodeFactory.instance.objectNode()
                .put("name", "stale")
                .put("documentation", "stale-doc"));
        staleUpdate.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 5)
                .put("opId", "batch-2:0"));

        ObjectNode winningUpdate = JsonNodeFactory.instance.objectNode();
        winningUpdate.put("type", "UpdateElement");
        winningUpdate.put("elementId", "elem:e1");
        winningUpdate.set("patch", JsonNodeFactory.instance.objectNode()
                .put("name", "winning")
                .put("documentation", "winning-doc"));
        winningUpdate.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 11)
                .put("opId", "batch-3:0"));

        String batchId1 = "batch-1-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId2 = "batch-2-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId3 = "batch-3-" + UUID.randomUUID().toString().substring(0, 8);

        ObjectNode batch1 = JsonNodeFactory.instance.objectNode();
        batch1.put("modelId", modelId);
        batch1.put("opBatchId", batchId1);
        batch1.put("timestamp", "2026-01-01T00:00:00Z");
        batch1.set("ops", JsonNodeFactory.instance.arrayNode().add(createElement));

        ObjectNode batch2 = JsonNodeFactory.instance.objectNode();
        batch2.put("modelId", modelId);
        batch2.put("opBatchId", batchId2);
        batch2.put("timestamp", "2026-01-01T00:00:01Z");
        batch2.set("ops", JsonNodeFactory.instance.arrayNode().add(staleUpdate));

        ObjectNode batch3 = JsonNodeFactory.instance.objectNode();
        batch3.put("modelId", modelId);
        batch3.put("opBatchId", batchId3);
        batch3.put("timestamp", "2026-01-01T00:00:02Z");
        batch3.set("ops", JsonNodeFactory.instance.arrayNode().add(winningUpdate));

        repository.appendOpLog(modelId, batchId1, new RevisionRange(1, 1), batch1);
        repository.applyToMaterializedState(modelId, batch1);
        repository.appendOpLog(modelId, batchId2, new RevisionRange(2, 2), batch2);
        repository.applyToMaterializedState(modelId, batch2);
        repository.appendOpLog(modelId, batchId3, new RevisionRange(3, 3), batch3);
        repository.applyToMaterializedState(modelId, batch3);
        repository.updateHeadRevision(modelId, 3L);

        var snapshot = repository.loadSnapshot(modelId);
        var elem = snapshot.path("elements").get(0);
        Assertions.assertEquals("winning", elem.path("name").asText());
        Assertions.assertEquals("winning-doc", elem.path("documentation").asText());

        repository.close();
    }

    @Test
    void deleteElementCreatesTombstoneAndBlocksStaleRecreate() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createAt10 = JsonNodeFactory.instance.objectNode();
        createAt10.put("type", "CreateElement");
        createAt10.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "v10"));
        createAt10.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 10)
                .put("opId", "batch-1:0"));

        ObjectNode deleteAt20 = JsonNodeFactory.instance.objectNode();
        deleteAt20.put("type", "DeleteElement");
        deleteAt20.put("elementId", "elem:e1");
        deleteAt20.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 20)
                .put("opId", "batch-2:0"));

        ObjectNode staleCreateAt19 = JsonNodeFactory.instance.objectNode();
        staleCreateAt19.put("type", "CreateElement");
        staleCreateAt19.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "stale-recreate"));
        staleCreateAt19.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 19)
                .put("opId", "batch-3:0"));

        ObjectNode staleUpdateAt19 = JsonNodeFactory.instance.objectNode();
        staleUpdateAt19.put("type", "UpdateElement");
        staleUpdateAt19.put("elementId", "elem:e1");
        staleUpdateAt19.set("patch", JsonNodeFactory.instance.objectNode()
                .put("name", "stale-update"));
        staleUpdateAt19.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-y")
                .put("lamport", 19)
                .put("opId", "batch-3:1"));

        ObjectNode winningCreateAt21 = JsonNodeFactory.instance.objectNode();
        winningCreateAt21.put("type", "CreateElement");
        winningCreateAt21.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "recreated"));
        winningCreateAt21.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 21)
                .put("opId", "batch-4:0"));

        ObjectNode batch1 = JsonNodeFactory.instance.objectNode();
        batch1.put("modelId", modelId);
        batch1.put("opBatchId", "batch-1");
        batch1.put("timestamp", "2026-01-01T00:00:00Z");
        batch1.set("ops", JsonNodeFactory.instance.arrayNode().add(createAt10));

        ObjectNode batch2 = JsonNodeFactory.instance.objectNode();
        batch2.put("modelId", modelId);
        batch2.put("opBatchId", "batch-2");
        batch2.put("timestamp", "2026-01-01T00:00:01Z");
        batch2.set("ops", JsonNodeFactory.instance.arrayNode().add(deleteAt20));

        ObjectNode batch3 = JsonNodeFactory.instance.objectNode();
        batch3.put("modelId", modelId);
        batch3.put("opBatchId", "batch-3");
        batch3.put("timestamp", "2026-01-01T00:00:02Z");
        batch3.set("ops", JsonNodeFactory.instance.arrayNode().add(staleCreateAt19));

        ObjectNode batch4 = JsonNodeFactory.instance.objectNode();
        batch4.put("modelId", modelId);
        batch4.put("opBatchId", "batch-4");
        batch4.put("timestamp", "2026-01-01T00:00:03Z");
        batch4.set("ops", JsonNodeFactory.instance.arrayNode().add(winningCreateAt21));

        repository.applyToMaterializedState(modelId, batch1);
        repository.applyToMaterializedState(modelId, batch2);
        repository.applyToMaterializedState(modelId, batch3);

        var snapshotAfterStaleCreate = repository.loadSnapshot(modelId);
        Assertions.assertEquals(0, snapshotAfterStaleCreate.path("elements").size(),
                "stale create should be blocked by tombstone");

        repository.applyToMaterializedState(modelId, batch4);

        var snapshotAfterWinningCreate = repository.loadSnapshot(modelId);
        Assertions.assertEquals(1, snapshotAfterWinningCreate.path("elements").size());
        Assertions.assertEquals("recreated", snapshotAfterWinningCreate.path("elements").get(0).path("name").asText());

        repository.close();
    }

    @Test
    void relationshipSemanticFieldsUseLamportLwwMerge() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createE1 = JsonNodeFactory.instance.objectNode();
        createE1.put("type", "CreateElement");
        createE1.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor"));
        createE1.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode createE2 = JsonNodeFactory.instance.objectNode();
        createE2.put("type", "CreateElement");
        createE2.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e2")
                .put("archimateType", "BusinessActor"));
        createE2.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 2));

        ObjectNode createE3 = JsonNodeFactory.instance.objectNode();
        createE3.put("type", "CreateElement");
        createE3.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e3")
                .put("archimateType", "BusinessActor"));
        createE3.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 3));

        ObjectNode createRelationship = JsonNodeFactory.instance.objectNode();
        createRelationship.put("type", "CreateRelationship");
        createRelationship.set("relationship", JsonNodeFactory.instance.objectNode()
                .put("id", "rel:r1")
                .put("archimateType", "AssociationRelationship")
                .put("name", "base")
                .put("documentation", "base-doc")
                .put("sourceId", "elem:e1")
                .put("targetId", "elem:e2"));
        createRelationship.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 10)
                .put("opId", "batch-1:3"));

        ObjectNode staleUpdate = JsonNodeFactory.instance.objectNode();
        staleUpdate.put("type", "UpdateRelationship");
        staleUpdate.put("relationshipId", "rel:r1");
        staleUpdate.set("patch", JsonNodeFactory.instance.objectNode()
                .put("name", "stale")
                .put("documentation", "stale-doc")
                .put("sourceId", "elem:e3"));
        staleUpdate.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 5)
                .put("opId", "batch-2:0"));

        ObjectNode winningUpdate = JsonNodeFactory.instance.objectNode();
        winningUpdate.put("type", "UpdateRelationship");
        winningUpdate.put("relationshipId", "rel:r1");
        winningUpdate.set("patch", JsonNodeFactory.instance.objectNode()
                .put("name", "winning")
                .put("documentation", "winning-doc")
                .put("sourceId", "elem:e3"));
        winningUpdate.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 11)
                .put("opId", "batch-3:0"));

        ObjectNode batch1 = JsonNodeFactory.instance.objectNode();
        batch1.put("modelId", modelId);
        batch1.put("opBatchId", "batch-1");
        batch1.put("timestamp", "2026-01-01T00:00:00Z");
        batch1.set("ops", JsonNodeFactory.instance.arrayNode().add(createE1).add(createE2).add(createE3).add(createRelationship));

        ObjectNode batch2 = JsonNodeFactory.instance.objectNode();
        batch2.put("modelId", modelId);
        batch2.put("opBatchId", "batch-2");
        batch2.put("timestamp", "2026-01-01T00:00:01Z");
        batch2.set("ops", JsonNodeFactory.instance.arrayNode().add(staleUpdate));

        ObjectNode batch3 = JsonNodeFactory.instance.objectNode();
        batch3.put("modelId", modelId);
        batch3.put("opBatchId", "batch-3");
        batch3.put("timestamp", "2026-01-01T00:00:02Z");
        batch3.set("ops", JsonNodeFactory.instance.arrayNode().add(winningUpdate));

        repository.applyToMaterializedState(modelId, batch1);
        repository.applyToMaterializedState(modelId, batch2);
        repository.applyToMaterializedState(modelId, batch3);

        var snapshot = repository.loadSnapshot(modelId);
        var rel = snapshot.path("relationships").get(0);
        Assertions.assertEquals("winning", rel.path("name").asText());
        Assertions.assertEquals("winning-doc", rel.path("documentation").asText());
        Assertions.assertEquals("elem:e3", rel.path("sourceId").asText());
        Assertions.assertEquals("elem:e2", rel.path("targetId").asText());

        repository.close();
    }

    @Test
    void deleteRelationshipCreatesTombstoneAndBlocksStaleRecreate() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createE1 = JsonNodeFactory.instance.objectNode();
        createE1.put("type", "CreateElement");
        createE1.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor"));
        createE1.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode createE2 = JsonNodeFactory.instance.objectNode();
        createE2.put("type", "CreateElement");
        createE2.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e2")
                .put("archimateType", "BusinessActor"));
        createE2.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 2));

        ObjectNode createRelAt10 = JsonNodeFactory.instance.objectNode();
        createRelAt10.put("type", "CreateRelationship");
        createRelAt10.set("relationship", JsonNodeFactory.instance.objectNode()
                .put("id", "rel:r1")
                .put("archimateType", "AssociationRelationship")
                .put("name", "v10")
                .put("sourceId", "elem:e1")
                .put("targetId", "elem:e2"));
        createRelAt10.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 10)
                .put("opId", "batch-1:2"));

        ObjectNode deleteAt20 = JsonNodeFactory.instance.objectNode();
        deleteAt20.put("type", "DeleteRelationship");
        deleteAt20.put("relationshipId", "rel:r1");
        deleteAt20.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 20)
                .put("opId", "batch-2:0"));

        ObjectNode staleCreateAt19 = JsonNodeFactory.instance.objectNode();
        staleCreateAt19.put("type", "CreateRelationship");
        staleCreateAt19.set("relationship", JsonNodeFactory.instance.objectNode()
                .put("id", "rel:r1")
                .put("archimateType", "AssociationRelationship")
                .put("name", "stale-recreate")
                .put("sourceId", "elem:e1")
                .put("targetId", "elem:e2"));
        staleCreateAt19.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 19)
                .put("opId", "batch-3:0"));

        ObjectNode staleUpdateAt19 = JsonNodeFactory.instance.objectNode();
        staleUpdateAt19.put("type", "UpdateElement");
        staleUpdateAt19.put("elementId", "elem:e1");
        staleUpdateAt19.set("patch", JsonNodeFactory.instance.objectNode()
                .put("name", "stale-update"));
        staleUpdateAt19.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-y")
                .put("lamport", 19)
                .put("opId", "batch-3:1"));

        ObjectNode winningCreateAt21 = JsonNodeFactory.instance.objectNode();
        winningCreateAt21.put("type", "CreateRelationship");
        winningCreateAt21.set("relationship", JsonNodeFactory.instance.objectNode()
                .put("id", "rel:r1")
                .put("archimateType", "AssociationRelationship")
                .put("name", "recreated")
                .put("sourceId", "elem:e1")
                .put("targetId", "elem:e2"));
        winningCreateAt21.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 21)
                .put("opId", "batch-4:0"));

        ObjectNode batch1 = JsonNodeFactory.instance.objectNode();
        batch1.put("modelId", modelId);
        batch1.put("opBatchId", "batch-1");
        batch1.put("timestamp", "2026-01-01T00:00:00Z");
        batch1.set("ops", JsonNodeFactory.instance.arrayNode().add(createE1).add(createE2).add(createRelAt10));

        ObjectNode batch2 = JsonNodeFactory.instance.objectNode();
        batch2.put("modelId", modelId);
        batch2.put("opBatchId", "batch-2");
        batch2.put("timestamp", "2026-01-01T00:00:01Z");
        batch2.set("ops", JsonNodeFactory.instance.arrayNode().add(deleteAt20));

        ObjectNode batch3 = JsonNodeFactory.instance.objectNode();
        batch3.put("modelId", modelId);
        batch3.put("opBatchId", "batch-3");
        batch3.put("timestamp", "2026-01-01T00:00:02Z");
        batch3.set("ops", JsonNodeFactory.instance.arrayNode().add(staleCreateAt19));

        ObjectNode batch4 = JsonNodeFactory.instance.objectNode();
        batch4.put("modelId", modelId);
        batch4.put("opBatchId", "batch-4");
        batch4.put("timestamp", "2026-01-01T00:00:03Z");
        batch4.set("ops", JsonNodeFactory.instance.arrayNode().add(winningCreateAt21));

        repository.applyToMaterializedState(modelId, batch1);
        repository.applyToMaterializedState(modelId, batch2);
        repository.applyToMaterializedState(modelId, batch3);

        var snapshotAfterStaleCreate = repository.loadSnapshot(modelId);
        Assertions.assertEquals(0, snapshotAfterStaleCreate.path("relationships").size(),
                "stale relationship recreate should be blocked by tombstone");

        repository.applyToMaterializedState(modelId, batch4);
        var snapshotAfterWinningCreate = repository.loadSnapshot(modelId);
        Assertions.assertEquals(1, snapshotAfterWinningCreate.path("relationships").size());
        Assertions.assertEquals("recreated", snapshotAfterWinningCreate.path("relationships").get(0).path("name").asText());

        repository.close();
    }

    @Test
    void deleteViewCreatesTombstoneAndBlocksStaleRecreate() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createAt10 = JsonNodeFactory.instance.objectNode();
        createAt10.put("type", "CreateView");
        createAt10.set("view", JsonNodeFactory.instance.objectNode().put("id", "view:v1").put("name", "v10"));
        createAt10.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 10));

        ObjectNode deleteAt20 = JsonNodeFactory.instance.objectNode();
        deleteAt20.put("type", "DeleteView");
        deleteAt20.put("viewId", "view:v1");
        deleteAt20.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 20));

        ObjectNode staleCreateAt19 = JsonNodeFactory.instance.objectNode();
        staleCreateAt19.put("type", "CreateView");
        staleCreateAt19.set("view", JsonNodeFactory.instance.objectNode().put("id", "view:v1").put("name", "stale"));
        staleCreateAt19.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 19));

        ObjectNode winningCreateAt21 = JsonNodeFactory.instance.objectNode();
        winningCreateAt21.put("type", "CreateView");
        winningCreateAt21.set("view", JsonNodeFactory.instance.objectNode().put("id", "view:v1").put("name", "recreated"));
        winningCreateAt21.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 21));

        ObjectNode b1 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b1").put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode().add(createAt10));
        ObjectNode b2 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b2").put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(deleteAt20));
        ObjectNode b3 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b3").put("timestamp", "2026-01-01T00:00:02Z");
        b3.set("ops", JsonNodeFactory.instance.arrayNode().add(staleCreateAt19));
        ObjectNode b4 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b4").put("timestamp", "2026-01-01T00:00:03Z");
        b4.set("ops", JsonNodeFactory.instance.arrayNode().add(winningCreateAt21));

        repository.applyToMaterializedState(modelId, b1);
        repository.applyToMaterializedState(modelId, b2);
        repository.applyToMaterializedState(modelId, b3);

        var afterStale = repository.loadSnapshot(modelId);
        Assertions.assertEquals(0, afterStale.path("views").size());

        repository.applyToMaterializedState(modelId, b4);
        var afterWinning = repository.loadSnapshot(modelId);
        Assertions.assertEquals(1, afterWinning.path("views").size());
        Assertions.assertEquals("recreated", afterWinning.path("views").get(0).path("name").asText());

        repository.close();
    }

    @Test
    void deleteViewObjectCreatesTombstoneAndBlocksStaleRecreate() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        createView.set("view", JsonNodeFactory.instance.objectNode().put("id", "view:v1").put("name", "View 1"));
        createView.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 2));

        ObjectNode createVoAt10 = JsonNodeFactory.instance.objectNode();
        createVoAt10.put("type", "CreateViewObject");
        createVoAt10.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", "vo:o1")
                .put("viewId", "view:v1")
                .put("representsId", "elem:e1")
                .set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 10).put("y", 10).put("width", 100).put("height", 50)));
        createVoAt10.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 10));

        ObjectNode deleteAt20 = JsonNodeFactory.instance.objectNode();
        deleteAt20.put("type", "DeleteViewObject");
        deleteAt20.put("viewObjectId", "vo:o1");
        deleteAt20.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 20));

        ObjectNode staleCreateAt19 = JsonNodeFactory.instance.objectNode();
        staleCreateAt19.put("type", "CreateViewObject");
        staleCreateAt19.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", "vo:o1")
                .put("viewId", "view:v1")
                .put("representsId", "elem:e1")
                .set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 99).put("y", 99)));
        staleCreateAt19.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 19));

        ObjectNode winningCreateAt21 = JsonNodeFactory.instance.objectNode();
        winningCreateAt21.put("type", "CreateViewObject");
        winningCreateAt21.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", "vo:o1")
                .put("viewId", "view:v1")
                .put("representsId", "elem:e1")
                .set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 33).put("y", 44)));
        winningCreateAt21.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 21));

        ObjectNode b1 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b1").put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode().add(createElement).add(createView).add(createVoAt10));
        ObjectNode b2 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b2").put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(deleteAt20));
        ObjectNode b3 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b3").put("timestamp", "2026-01-01T00:00:02Z");
        b3.set("ops", JsonNodeFactory.instance.arrayNode().add(staleCreateAt19));
        ObjectNode b4 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b4").put("timestamp", "2026-01-01T00:00:03Z");
        b4.set("ops", JsonNodeFactory.instance.arrayNode().add(winningCreateAt21));

        repository.applyToMaterializedState(modelId, b1);
        repository.applyToMaterializedState(modelId, b2);
        repository.applyToMaterializedState(modelId, b3);

        var afterStale = repository.loadSnapshot(modelId);
        Assertions.assertEquals(0, afterStale.path("viewObjects").size());

        repository.applyToMaterializedState(modelId, b4);
        var afterWinning = repository.loadSnapshot(modelId);
        Assertions.assertEquals(1, afterWinning.path("viewObjects").size());
        Assertions.assertEquals(33, afterWinning.path("viewObjects").get(0).path("notationJson").path("x").asInt());

        repository.close();
    }

    @Test
    void viewSemanticFieldsUseLamportLwwMerge() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String viewId = "view:v1-" + UUID.randomUUID().toString().substring(0, 6);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createAt10 = JsonNodeFactory.instance.objectNode();
        createAt10.put("type", "CreateView");
        createAt10.set("view", JsonNodeFactory.instance.objectNode()
                .put("id", viewId)
                .put("name", "base")
                .put("documentation", "base-doc"));
        createAt10.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 10));

        ObjectNode staleUpdate = JsonNodeFactory.instance.objectNode();
        staleUpdate.put("type", "UpdateView");
        staleUpdate.put("viewId", viewId);
        staleUpdate.set("patch", JsonNodeFactory.instance.objectNode()
                .put("name", "stale")
                .put("documentation", "stale-doc"));
        staleUpdate.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 5));

        ObjectNode winningUpdate = JsonNodeFactory.instance.objectNode();
        winningUpdate.put("type", "UpdateView");
        winningUpdate.put("viewId", viewId);
        winningUpdate.set("patch", JsonNodeFactory.instance.objectNode()
                .put("name", "winning")
                .put("documentation", "winning-doc"));
        winningUpdate.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 11));

        ObjectNode b1 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b1").put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode().add(createAt10));
        ObjectNode b2 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b2").put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(staleUpdate));
        ObjectNode b3 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b3").put("timestamp", "2026-01-01T00:00:02Z");
        b3.set("ops", JsonNodeFactory.instance.arrayNode().add(winningUpdate));

        repository.applyToMaterializedState(modelId, b1);
        repository.applyToMaterializedState(modelId, b2);
        repository.applyToMaterializedState(modelId, b3);

        var view = repository.loadSnapshot(modelId).path("views").get(0);
        Assertions.assertEquals("winning", view.path("name").asText());
        Assertions.assertEquals("winning-doc", view.path("documentation").asText());

        repository.close();
    }

    @Test
    void folderFieldsUseLamportLwwMerge() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createFolder = JsonNodeFactory.instance.objectNode();
        createFolder.put("type", "CreateFolder");
        createFolder.set("folder", JsonNodeFactory.instance.objectNode()
                .put("id", "folder:f1")
                .put("folderType", "USER")
                .put("name", "Base")
                .put("documentation", "base-doc")
                .put("parentFolderId", "folder:root-business"));
        createFolder.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 10));

        ObjectNode staleUpdate = JsonNodeFactory.instance.objectNode();
        staleUpdate.put("type", "UpdateFolder");
        staleUpdate.put("folderId", "folder:f1");
        staleUpdate.set("patch", JsonNodeFactory.instance.objectNode()
                .put("name", "Stale")
                .put("documentation", "stale-doc"));
        staleUpdate.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 5));

        ObjectNode winningUpdate = JsonNodeFactory.instance.objectNode();
        winningUpdate.put("type", "UpdateFolder");
        winningUpdate.put("folderId", "folder:f1");
        winningUpdate.set("patch", JsonNodeFactory.instance.objectNode()
                .put("name", "Winner")
                .put("documentation", "winner-doc"));
        winningUpdate.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 11));

        ObjectNode tieBreakerUpdate = JsonNodeFactory.instance.objectNode();
        tieBreakerUpdate.put("type", "UpdateFolder");
        tieBreakerUpdate.put("folderId", "folder:f1");
        tieBreakerUpdate.set("patch", JsonNodeFactory.instance.objectNode()
                .put("name", "Winner-Z"));
        tieBreakerUpdate.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 11));

        ObjectNode baseBatch = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "folder-base").put("timestamp", "2026-01-01T00:00:00Z");
        baseBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(createFolder));
        ObjectNode staleBatch = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "folder-stale").put("timestamp", "2026-01-01T00:00:01Z");
        staleBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(staleUpdate));
        ObjectNode winnerBatch = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "folder-winner").put("timestamp", "2026-01-01T00:00:02Z");
        winnerBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(winningUpdate));
        ObjectNode tieBatch = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "folder-tie").put("timestamp", "2026-01-01T00:00:03Z");
        tieBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(tieBreakerUpdate));

        repository.applyToMaterializedState(modelId, baseBatch);
        repository.applyToMaterializedState(modelId, staleBatch);
        repository.applyToMaterializedState(modelId, winnerBatch);
        repository.applyToMaterializedState(modelId, tieBatch);

        JsonNode folder = findById(repository.loadSnapshot(modelId).path("folders"), "folder:f1");
        Assertions.assertNotNull(folder);
        Assertions.assertEquals("Winner-Z", folder.path("name").asText());
        Assertions.assertEquals("winner-doc", folder.path("documentation").asText(),
                "partial later patch should not clear documentation");

        repository.close();
    }

    @Test
    void connectionNotationUsesLamportLwwMerge() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createE1 = JsonNodeFactory.instance.objectNode();
        createE1.put("type", "CreateElement");
        createE1.set("element", JsonNodeFactory.instance.objectNode().put("id", "elem:e1").put("archimateType", "BusinessActor"));
        createE1.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode createE2 = JsonNodeFactory.instance.objectNode();
        createE2.put("type", "CreateElement");
        createE2.set("element", JsonNodeFactory.instance.objectNode().put("id", "elem:e2").put("archimateType", "BusinessActor"));
        createE2.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 2));

        ObjectNode createRel = JsonNodeFactory.instance.objectNode();
        createRel.put("type", "CreateRelationship");
        createRel.set("relationship", JsonNodeFactory.instance.objectNode()
                .put("id", "rel:r1")
                .put("archimateType", "AssociationRelationship")
                .put("sourceId", "elem:e1")
                .put("targetId", "elem:e2"));
        createRel.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 3));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        createView.set("view", JsonNodeFactory.instance.objectNode().put("id", "view:v1").put("name", "View 1"));
        createView.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 4));

        ObjectNode createVo1 = JsonNodeFactory.instance.objectNode();
        createVo1.put("type", "CreateViewObject");
        createVo1.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", "vo:o1")
                .put("viewId", "view:v1")
                .put("representsId", "elem:e1")
                .set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 10).put("y", 10).put("width", 100).put("height", 50)));
        createVo1.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 5));

        ObjectNode createVo2 = JsonNodeFactory.instance.objectNode();
        createVo2.put("type", "CreateViewObject");
        createVo2.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", "vo:o2")
                .put("viewId", "view:v1")
                .put("representsId", "elem:e2")
                .set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 200).put("y", 10).put("width", 100).put("height", 50)));
        createVo2.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 6));

        ObjectNode createConn = JsonNodeFactory.instance.objectNode();
        createConn.put("type", "CreateConnection");
        createConn.set("connection", JsonNodeFactory.instance.objectNode()
                .put("id", "conn:c1")
                .put("viewId", "view:v1")
                .put("representsId", "rel:r1")
                .put("sourceViewObjectId", "vo:o1")
                .put("targetViewObjectId", "vo:o2")
                .set("notationJson", JsonNodeFactory.instance.objectNode().put("lineWidth", 1).put("name", "base")));
        createConn.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 10));

        ObjectNode staleUpdate = JsonNodeFactory.instance.objectNode();
        staleUpdate.put("type", "UpdateConnectionOpaque");
        staleUpdate.put("connectionId", "conn:c1");
        staleUpdate.set("notationJson", JsonNodeFactory.instance.objectNode().put("lineWidth", 9).put("name", "stale"));
        staleUpdate.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 5));

        ObjectNode winningUpdate = JsonNodeFactory.instance.objectNode();
        winningUpdate.put("type", "UpdateConnectionOpaque");
        winningUpdate.put("connectionId", "conn:c1");
        winningUpdate.set("notationJson", JsonNodeFactory.instance.objectNode().put("lineWidth", 2).put("name", "winning"));
        winningUpdate.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 11));

        ObjectNode b1 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b1").put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(createE1).add(createE2).add(createRel).add(createView).add(createVo1).add(createVo2).add(createConn));
        ObjectNode b2 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b2").put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(staleUpdate));
        ObjectNode b3 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b3").put("timestamp", "2026-01-01T00:00:02Z");
        b3.set("ops", JsonNodeFactory.instance.arrayNode().add(winningUpdate));

        repository.applyToMaterializedState(modelId, b1);
        repository.applyToMaterializedState(modelId, b2);
        repository.applyToMaterializedState(modelId, b3);

        var connNotation = repository.loadSnapshot(modelId).path("connections").get(0).path("notationJson");
        Assertions.assertEquals(2, connNotation.path("lineWidth").asInt());
        Assertions.assertEquals("winning", connNotation.path("name").asText());

        repository.close();
    }

    @Test
    void connectionNotationFieldsUseLamportLwwMergeAcrossAllWhitelistedKeys() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createE1 = JsonNodeFactory.instance.objectNode();
        createE1.put("type", "CreateElement");
        createE1.set("element", JsonNodeFactory.instance.objectNode().put("id", "elem:e1").put("archimateType", "BusinessActor"));
        createE1.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode createE2 = JsonNodeFactory.instance.objectNode();
        createE2.put("type", "CreateElement");
        createE2.set("element", JsonNodeFactory.instance.objectNode().put("id", "elem:e2").put("archimateType", "BusinessActor"));
        createE2.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 2));

        ObjectNode createRel = JsonNodeFactory.instance.objectNode();
        createRel.put("type", "CreateRelationship");
        createRel.set("relationship", JsonNodeFactory.instance.objectNode()
                .put("id", "rel:r1")
                .put("archimateType", "AssociationRelationship")
                .put("sourceId", "elem:e1")
                .put("targetId", "elem:e2"));
        createRel.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 3));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        createView.set("view", JsonNodeFactory.instance.objectNode().put("id", "view:v1").put("name", "View 1"));
        createView.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 4));

        ObjectNode createVo1 = JsonNodeFactory.instance.objectNode();
        createVo1.put("type", "CreateViewObject");
        createVo1.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", "vo:o1")
                .put("viewId", "view:v1")
                .put("representsId", "elem:e1")
                .set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 10).put("y", 10).put("width", 100).put("height", 50)));
        createVo1.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 5));

        ObjectNode createVo2 = JsonNodeFactory.instance.objectNode();
        createVo2.put("type", "CreateViewObject");
        createVo2.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", "vo:o2")
                .put("viewId", "view:v1")
                .put("representsId", "elem:e2")
                .set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 200).put("y", 10).put("width", 100).put("height", 50)));
        createVo2.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 6));

        ObjectNode createConn = JsonNodeFactory.instance.objectNode();
        createConn.put("type", "CreateConnection");
        createConn.set("connection", JsonNodeFactory.instance.objectNode()
                .put("id", "conn:c1")
                .put("viewId", "view:v1")
                .put("representsId", "rel:r1")
                .put("sourceViewObjectId", "vo:o1")
                .put("targetViewObjectId", "vo:o2")
                .set("notationJson", JsonNodeFactory.instance.objectNode()
                        .put("type", 1)
                        .put("nameVisible", false)
                        .put("textAlignment", 1)
                        .put("textPosition", 1)
                        .put("lineWidth", 1)
                        .put("name", "base")
                        .put("lineColor", "#111111")
                        .put("font", "font-base")
                        .put("fontColor", "#222222")
                        .put("documentation", "base-doc")
                        .set("bendpoints", JsonNodeFactory.instance.arrayNode()
                                .add(JsonNodeFactory.instance.objectNode()
                                        .put("startX", 1).put("startY", 2).put("endX", 3).put("endY", 4)))));
        createConn.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 10));

        ObjectNode staleUpdate = JsonNodeFactory.instance.objectNode();
        staleUpdate.put("type", "UpdateConnectionOpaque");
        staleUpdate.put("viewId", "view:v1");
        staleUpdate.put("connectionId", "conn:c1");
        staleUpdate.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("type", 9)
                .put("nameVisible", true)
                .put("textAlignment", 9)
                .put("textPosition", 9)
                .put("lineWidth", 9)
                .put("name", "stale")
                .put("lineColor", "#999999")
                .put("font", "font-stale")
                .put("fontColor", "#999999")
                .put("documentation", "stale-doc")
                .set("bendpoints", JsonNodeFactory.instance.arrayNode()
                        .add(JsonNodeFactory.instance.objectNode()
                                .put("startX", 9).put("startY", 9).put("endX", 9).put("endY", 9))));
        staleUpdate.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 5));

        ObjectNode winningUpdate = JsonNodeFactory.instance.objectNode();
        winningUpdate.put("type", "UpdateConnectionOpaque");
        winningUpdate.put("viewId", "view:v1");
        winningUpdate.put("connectionId", "conn:c1");
        winningUpdate.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("type", 2)
                .put("nameVisible", true)
                .put("textAlignment", 2)
                .put("textPosition", 2)
                .put("lineWidth", 2)
                .put("name", "winning")
                .put("lineColor", "#333333")
                .put("font", "font-winning")
                .put("fontColor", "#444444")
                .put("documentation", "winner-doc")
                .set("bendpoints", JsonNodeFactory.instance.arrayNode()
                        .add(JsonNodeFactory.instance.objectNode()
                                .put("startX", 10).put("startY", 11).put("endX", 12).put("endY", 13))));
        winningUpdate.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 11));

        ObjectNode b1 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b1").put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(createE1).add(createE2).add(createRel).add(createView).add(createVo1).add(createVo2).add(createConn));
        ObjectNode b2 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b2").put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(staleUpdate));
        ObjectNode b3 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b3").put("timestamp", "2026-01-01T00:00:02Z");
        b3.set("ops", JsonNodeFactory.instance.arrayNode().add(winningUpdate));

        repository.applyToMaterializedState(modelId, b1);
        repository.applyToMaterializedState(modelId, b2);
        repository.applyToMaterializedState(modelId, b3);

        JsonNode connNotation = repository.loadSnapshot(modelId).path("connections").get(0).path("notationJson");
        Assertions.assertEquals(2, connNotation.path("type").asInt());
        Assertions.assertTrue(connNotation.path("nameVisible").asBoolean());
        Assertions.assertEquals(2, connNotation.path("textAlignment").asInt());
        Assertions.assertEquals(2, connNotation.path("textPosition").asInt());
        Assertions.assertEquals(2, connNotation.path("lineWidth").asInt());
        Assertions.assertEquals("winning", connNotation.path("name").asText());
        Assertions.assertEquals("#333333", connNotation.path("lineColor").asText());
        Assertions.assertEquals("font-winning", connNotation.path("font").asText());
        Assertions.assertEquals("#444444", connNotation.path("fontColor").asText());
        Assertions.assertEquals("winner-doc", connNotation.path("documentation").asText());
        Assertions.assertEquals(1, connNotation.path("bendpoints").size());
        Assertions.assertEquals(10, connNotation.path("bendpoints").get(0).path("startX").asInt());
        Assertions.assertEquals(13, connNotation.path("bendpoints").get(0).path("endY").asInt());

        repository.close();
    }

    @Test
    void viewObjectNotationFieldsUseLamportLwwMergeAcrossAllWhitelistedKeys() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        createView.set("view", JsonNodeFactory.instance.objectNode().put("id", "view:v1").put("name", "View 1"));
        createView.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 2));

        ObjectNode createViewObject = JsonNodeFactory.instance.objectNode();
        createViewObject.put("type", "CreateViewObject");
        createViewObject.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", "vo:o1")
                .put("viewId", "view:v1")
                .put("representsId", "elem:e1")
                .set("notationJson", JsonNodeFactory.instance.objectNode()
                        .put("x", 10)
                        .put("y", 20)
                        .put("width", 120)
                        .put("height", 55)
                        .put("type", 1)
                        .put("alpha", 80)
                        .put("lineAlpha", 70)
                        .put("lineWidth", 1)
                        .put("lineStyle", 1)
                        .put("textAlignment", 1)
                        .put("textPosition", 1)
                        .put("gradient", 0)
                        .put("iconVisibleState", 0)
                        .put("deriveElementLineColor", false)
                        .put("fillColor", "#111111")
                        .put("lineColor", "#222222")
                        .put("font", "font-base")
                        .put("fontColor", "#333333")
                        .put("iconColor", "#444444")
                        .put("imagePath", "/base.png")
                        .put("imagePosition", 0)
                        .put("name", "base")
                        .put("documentation", "base-doc")));
        createViewObject.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 10));

        ObjectNode staleUpdate = JsonNodeFactory.instance.objectNode();
        staleUpdate.put("type", "UpdateViewObjectOpaque");
        staleUpdate.put("viewId", "view:v1");
        staleUpdate.put("viewObjectId", "vo:o1");
        staleUpdate.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("x", 900)
                .put("y", 901)
                .put("width", 902)
                .put("height", 903)
                .put("type", 9)
                .put("alpha", 9)
                .put("lineAlpha", 9)
                .put("lineWidth", 9)
                .put("lineStyle", 9)
                .put("textAlignment", 9)
                .put("textPosition", 9)
                .put("gradient", 9)
                .put("iconVisibleState", 9)
                .put("deriveElementLineColor", true)
                .put("fillColor", "#999900")
                .put("lineColor", "#999901")
                .put("font", "font-stale")
                .put("fontColor", "#999902")
                .put("iconColor", "#999903")
                .put("imagePath", "/stale.png")
                .put("imagePosition", 9)
                .put("name", "stale")
                .put("documentation", "stale-doc"));
        staleUpdate.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 5));

        ObjectNode winningUpdate = JsonNodeFactory.instance.objectNode();
        winningUpdate.put("type", "UpdateViewObjectOpaque");
        winningUpdate.put("viewId", "view:v1");
        winningUpdate.put("viewObjectId", "vo:o1");
        winningUpdate.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("x", 30)
                .put("y", 40)
                .put("width", 130)
                .put("height", 65)
                .put("type", 2)
                .put("alpha", 81)
                .put("lineAlpha", 71)
                .put("lineWidth", 2)
                .put("lineStyle", 2)
                .put("textAlignment", 2)
                .put("textPosition", 2)
                .put("gradient", 1)
                .put("iconVisibleState", 1)
                .put("deriveElementLineColor", true)
                .put("fillColor", "#aaaaaa")
                .put("lineColor", "#bbbbbb")
                .put("font", "font-winning")
                .put("fontColor", "#cccccc")
                .put("iconColor", "#dddddd")
                .put("imagePath", "/winner.png")
                .put("imagePosition", 1)
                .put("name", "winner")
                .put("documentation", "winner-doc"));
        winningUpdate.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 11));

        ObjectNode b1 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b1").put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode().add(createElement).add(createView).add(createViewObject));
        ObjectNode b2 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b2").put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(staleUpdate));
        ObjectNode b3 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b3").put("timestamp", "2026-01-01T00:00:02Z");
        b3.set("ops", JsonNodeFactory.instance.arrayNode().add(winningUpdate));

        repository.applyToMaterializedState(modelId, b1);
        repository.applyToMaterializedState(modelId, b2);
        repository.applyToMaterializedState(modelId, b3);

        JsonNode notation = repository.loadSnapshot(modelId).path("viewObjects").get(0).path("notationJson");
        Assertions.assertEquals(30, notation.path("x").asInt());
        Assertions.assertEquals(40, notation.path("y").asInt());
        Assertions.assertEquals(130, notation.path("width").asInt());
        Assertions.assertEquals(65, notation.path("height").asInt());
        Assertions.assertEquals(2, notation.path("type").asInt());
        Assertions.assertEquals(81, notation.path("alpha").asInt());
        Assertions.assertEquals(71, notation.path("lineAlpha").asInt());
        Assertions.assertEquals(2, notation.path("lineWidth").asInt());
        Assertions.assertEquals(2, notation.path("lineStyle").asInt());
        Assertions.assertEquals(2, notation.path("textAlignment").asInt());
        Assertions.assertEquals(2, notation.path("textPosition").asInt());
        Assertions.assertEquals(1, notation.path("gradient").asInt());
        Assertions.assertEquals(1, notation.path("iconVisibleState").asInt());
        Assertions.assertTrue(notation.path("deriveElementLineColor").asBoolean());
        Assertions.assertEquals("#aaaaaa", notation.path("fillColor").asText());
        Assertions.assertEquals("#bbbbbb", notation.path("lineColor").asText());
        Assertions.assertEquals("font-winning", notation.path("font").asText());
        Assertions.assertEquals("#cccccc", notation.path("fontColor").asText());
        Assertions.assertEquals("#dddddd", notation.path("iconColor").asText());
        Assertions.assertEquals("/winner.png", notation.path("imagePath").asText());
        Assertions.assertEquals(1, notation.path("imagePosition").asInt());
        Assertions.assertEquals("winner", notation.path("name").asText());
        Assertions.assertEquals("winner-doc", notation.path("documentation").asText());

        repository.close();
    }

    @Test
    void propertySetUnsetUsesLamportLwwMerge() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode setAt10 = JsonNodeFactory.instance.objectNode();
        setAt10.put("type", "SetProperty");
        setAt10.put("targetId", "elem:e1");
        setAt10.put("key", "risk");
        setAt10.put("value", "high");
        setAt10.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 10));

        ObjectNode staleSetAt9 = JsonNodeFactory.instance.objectNode();
        staleSetAt9.put("type", "SetProperty");
        staleSetAt9.put("targetId", "elem:e1");
        staleSetAt9.put("key", "risk");
        staleSetAt9.put("value", "low");
        staleSetAt9.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 9));

        ObjectNode unsetAt11 = JsonNodeFactory.instance.objectNode();
        unsetAt11.put("type", "UnsetProperty");
        unsetAt11.put("targetId", "elem:e1");
        unsetAt11.put("key", "risk");
        unsetAt11.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 11));

        ObjectNode staleSetAt10b = JsonNodeFactory.instance.objectNode();
        staleSetAt10b.put("type", "SetProperty");
        staleSetAt10b.put("targetId", "elem:e1");
        staleSetAt10b.put("key", "risk");
        staleSetAt10b.put("value", "medium");
        staleSetAt10b.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-c").put("lamport", 10));

        ObjectNode winningSetAt12 = JsonNodeFactory.instance.objectNode();
        winningSetAt12.put("type", "SetProperty");
        winningSetAt12.put("targetId", "elem:e1");
        winningSetAt12.put("key", "risk");
        winningSetAt12.put("value", "critical");
        winningSetAt12.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-d").put("lamport", 12));

        ObjectNode batch = JsonNodeFactory.instance.objectNode();
        batch.put("modelId", modelId);
        batch.put("opBatchId", "prop-batch");
        batch.put("timestamp", "2026-01-01T00:00:00Z");
        batch.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(createElement)
                .add(setAt10)
                .add(staleSetAt9)
                .add(unsetAt11)
                .add(staleSetAt10b)
                .add(winningSetAt12));

        repository.applyToMaterializedState(modelId, batch);

        String finalValue;
        try (var driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             var session = driver.session()) {
            finalValue = session.run("MATCH (e:Element {modelId: $modelId, id: 'elem:e1'}) RETURN e.risk AS risk",
                            Map.of("modelId", modelId))
                    .single()
                    .get("risk")
                    .asString(null);
        }
        Assertions.assertEquals("critical", finalValue);

        repository.close();
    }

    @Test
    void propertySetUsesClientIdTieBreakAtEqualLamport() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode setFromA = JsonNodeFactory.instance.objectNode();
        setFromA.put("type", "SetProperty");
        setFromA.put("targetId", "elem:e1");
        setFromA.put("key", "risk");
        setFromA.put("value", "low");
        setFromA.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 10));

        ObjectNode setFromZ = JsonNodeFactory.instance.objectNode();
        setFromZ.put("type", "SetProperty");
        setFromZ.put("targetId", "elem:e1");
        setFromZ.put("key", "risk");
        setFromZ.put("value", "high");
        setFromZ.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 10));

        ObjectNode batch = JsonNodeFactory.instance.objectNode();
        batch.put("modelId", modelId);
        batch.put("opBatchId", "prop-tie-set-batch");
        batch.put("timestamp", "2026-01-01T00:00:00Z");
        batch.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(createElement)
                .add(setFromA)
                .add(setFromZ));

        repository.applyToMaterializedState(modelId, batch);

        String finalValue;
        try (var driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             var session = driver.session()) {
            finalValue = session.run("MATCH (e:Element {modelId: $modelId, id: 'elem:e1'}) RETURN e.risk AS risk",
                            Map.of("modelId", modelId))
                    .single()
                    .get("risk")
                    .asString(null);
        }
        Assertions.assertEquals("high", finalValue);

        repository.close();
    }

    @Test
    void propertyUnsetCanWinAtEqualLamportByClientId() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode setAt20A = JsonNodeFactory.instance.objectNode();
        setAt20A.put("type", "SetProperty");
        setAt20A.put("targetId", "elem:e1");
        setAt20A.put("key", "risk");
        setAt20A.put("value", "medium");
        setAt20A.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 20));

        ObjectNode unsetAt20Z = JsonNodeFactory.instance.objectNode();
        unsetAt20Z.put("type", "UnsetProperty");
        unsetAt20Z.put("targetId", "elem:e1");
        unsetAt20Z.put("key", "risk");
        unsetAt20Z.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 20));

        ObjectNode staleSetAt20Y = JsonNodeFactory.instance.objectNode();
        staleSetAt20Y.put("type", "SetProperty");
        staleSetAt20Y.put("targetId", "elem:e1");
        staleSetAt20Y.put("key", "risk");
        staleSetAt20Y.put("value", "high");
        staleSetAt20Y.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-y").put("lamport", 20));

        ObjectNode batch = JsonNodeFactory.instance.objectNode();
        batch.put("modelId", modelId);
        batch.put("opBatchId", "prop-tie-unset-batch");
        batch.put("timestamp", "2026-01-01T00:00:00Z");
        batch.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(createElement)
                .add(setAt20A)
                .add(unsetAt20Z)
                .add(staleSetAt20Y));

        repository.applyToMaterializedState(modelId, batch);

        String finalValue;
        try (var driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             var session = driver.session()) {
            finalValue = session.run("MATCH (e:Element {modelId: $modelId, id: 'elem:e1'}) RETURN e.risk AS risk",
                            Map.of("modelId", modelId))
                    .single()
                    .get("risk")
                    .asString(null);
        }
        Assertions.assertNull(finalValue);

        repository.close();
    }

    @Test
    void propertySetMemberOpsConvergeUnderOutOfOrderAndDuplicateReplay() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelOrdered = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String modelReplay = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String elementId = "elem:e1-" + UUID.randomUUID().toString().substring(0, 6);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", elementId)
                .put("archimateType", "BusinessActor"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode addAlpha = JsonNodeFactory.instance.objectNode();
        addAlpha.put("type", "AddPropertySetMember");
        addAlpha.put("targetId", elementId);
        addAlpha.put("key", "owners");
        addAlpha.put("member", "alpha");
        addAlpha.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 10));

        ObjectNode addBeta = JsonNodeFactory.instance.objectNode();
        addBeta.put("type", "AddPropertySetMember");
        addBeta.put("targetId", elementId);
        addBeta.put("key", "owners");
        addBeta.put("member", "beta");
        addBeta.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 11));

        ObjectNode removeAlpha = JsonNodeFactory.instance.objectNode();
        removeAlpha.put("type", "RemovePropertySetMember");
        removeAlpha.put("targetId", elementId);
        removeAlpha.put("key", "owners");
        removeAlpha.put("member", "alpha");
        removeAlpha.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-c").put("lamport", 12));

        ObjectNode staleAddAlpha = JsonNodeFactory.instance.objectNode();
        staleAddAlpha.put("type", "AddPropertySetMember");
        staleAddAlpha.put("targetId", elementId);
        staleAddAlpha.put("key", "owners");
        staleAddAlpha.put("member", "alpha");
        staleAddAlpha.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 11));

        ObjectNode tieAddGamma = JsonNodeFactory.instance.objectNode();
        tieAddGamma.put("type", "AddPropertySetMember");
        tieAddGamma.put("targetId", elementId);
        tieAddGamma.put("key", "owners");
        tieAddGamma.put("member", "gamma");
        tieAddGamma.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 12));

        ObjectNode tieRemoveGamma = JsonNodeFactory.instance.objectNode();
        tieRemoveGamma.put("type", "RemovePropertySetMember");
        tieRemoveGamma.put("targetId", elementId);
        tieRemoveGamma.put("key", "owners");
        tieRemoveGamma.put("member", "gamma");
        tieRemoveGamma.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 12));

        ObjectNode foundationBatch = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelOrdered)
                .put("opBatchId", "orset-foundation")
                .put("timestamp", "2026-01-01T00:00:00Z");
        foundationBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(createElement));

        ObjectNode memberBatchA = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelOrdered)
                .put("opBatchId", "orset-a")
                .put("timestamp", "2026-01-01T00:00:01Z");
        memberBatchA.set("ops", JsonNodeFactory.instance.arrayNode().add(addAlpha).add(addBeta).add(tieAddGamma));

        ObjectNode memberBatchB = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelOrdered)
                .put("opBatchId", "orset-b")
                .put("timestamp", "2026-01-01T00:00:02Z");
        memberBatchB.set("ops", JsonNodeFactory.instance.arrayNode().add(removeAlpha).add(staleAddAlpha).add(tieRemoveGamma));

        // Scenario A: in-order.
        repository.applyToMaterializedState(modelOrdered, foundationBatch);
        repository.applyToMaterializedState(modelOrdered, memberBatchA);
        repository.applyToMaterializedState(modelOrdered, memberBatchB);

        // Scenario B: out-of-order + duplicates.
        repository.applyToMaterializedState(modelReplay, foundationBatch);
        repository.applyToMaterializedState(modelReplay, memberBatchB);
        repository.applyToMaterializedState(modelReplay, memberBatchA);
        repository.applyToMaterializedState(modelReplay, memberBatchB);
        repository.applyToMaterializedState(modelReplay, memberBatchA);

        String orderedOwners = readElementProperty(uri, username, password, modelOrdered, elementId, "owners");
        String replayOwners = readElementProperty(uri, username, password, modelReplay, elementId, "owners");

        Assertions.assertEquals(orderedOwners, replayOwners,
                "property-set member ops must converge under out-of-order and duplicate replay");
        Assertions.assertEquals("[\"beta\"]", replayOwners,
                "expected canonical OR-Set materialization after remove/tie-break semantics");

        repository.close();
    }

    @Test
    void viewObjectChildMemberOpsConvergeUnderOutOfOrderAndDuplicateReplay() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelOrdered = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String modelReplay = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String elementId = "elem:e1-" + UUID.randomUUID().toString().substring(0, 6);
        String viewId = "view:v1-" + UUID.randomUUID().toString().substring(0, 6);
        String parentId = "vo:p1-" + UUID.randomUUID().toString().substring(0, 6);
        String childId = "vo:c1-" + UUID.randomUUID().toString().substring(0, 6);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", elementId)
                .put("archimateType", "BusinessActor"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        createView.set("view", JsonNodeFactory.instance.objectNode()
                .put("id", viewId)
                .put("name", "Default View")
                .set("notationJson", JsonNodeFactory.instance.objectNode()));
        createView.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 2));

        ObjectNode createParent = JsonNodeFactory.instance.objectNode();
        createParent.put("type", "CreateViewObject");
        createParent.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", parentId)
                .put("viewId", viewId)
                .put("representsId", elementId)
                .set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 10).put("y", 10).put("width", 120).put("height", 55)));
        createParent.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 3));

        ObjectNode createChild = JsonNodeFactory.instance.objectNode();
        createChild.put("type", "CreateViewObject");
        createChild.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", childId)
                .put("viewId", viewId)
                .put("representsId", elementId)
                .set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 20).put("y", 20).put("width", 120).put("height", 55)));
        createChild.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 4));

        ObjectNode addChild = JsonNodeFactory.instance.objectNode();
        addChild.put("type", "AddViewObjectChildMember");
        addChild.put("parentViewObjectId", parentId);
        addChild.put("childViewObjectId", childId);
        addChild.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 10));

        ObjectNode removeChild = JsonNodeFactory.instance.objectNode();
        removeChild.put("type", "RemoveViewObjectChildMember");
        removeChild.put("parentViewObjectId", parentId);
        removeChild.put("childViewObjectId", childId);
        removeChild.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 12));

        ObjectNode staleAdd = JsonNodeFactory.instance.objectNode();
        staleAdd.put("type", "AddViewObjectChildMember");
        staleAdd.put("parentViewObjectId", parentId);
        staleAdd.put("childViewObjectId", childId);
        staleAdd.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-c").put("lamport", 11));

        ObjectNode tieAdd = JsonNodeFactory.instance.objectNode();
        tieAdd.put("type", "AddViewObjectChildMember");
        tieAdd.put("parentViewObjectId", parentId);
        tieAdd.put("childViewObjectId", childId);
        tieAdd.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 20));

        ObjectNode tieRemove = JsonNodeFactory.instance.objectNode();
        tieRemove.put("type", "RemoveViewObjectChildMember");
        tieRemove.put("parentViewObjectId", parentId);
        tieRemove.put("childViewObjectId", childId);
        tieRemove.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 20));

        ObjectNode foundationBatch = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelOrdered)
                .put("opBatchId", "vo-child-foundation")
                .put("timestamp", "2026-01-01T00:00:00Z");
        foundationBatch.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(createElement)
                .add(createView)
                .add(createParent)
                .add(createChild));

        ObjectNode memberBatchA = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelOrdered)
                .put("opBatchId", "vo-child-a")
                .put("timestamp", "2026-01-01T00:00:01Z");
        memberBatchA.set("ops", JsonNodeFactory.instance.arrayNode().add(addChild).add(tieAdd));

        ObjectNode memberBatchB = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelOrdered)
                .put("opBatchId", "vo-child-b")
                .put("timestamp", "2026-01-01T00:00:02Z");
        memberBatchB.set("ops", JsonNodeFactory.instance.arrayNode().add(removeChild).add(staleAdd).add(tieRemove));

        repository.applyToMaterializedState(modelOrdered, foundationBatch);
        repository.applyToMaterializedState(modelOrdered, memberBatchA);
        repository.applyToMaterializedState(modelOrdered, memberBatchB);

        repository.applyToMaterializedState(modelReplay, foundationBatch);
        repository.applyToMaterializedState(modelReplay, memberBatchB);
        repository.applyToMaterializedState(modelReplay, memberBatchA);
        repository.applyToMaterializedState(modelReplay, memberBatchB);
        repository.applyToMaterializedState(modelReplay, memberBatchA);

        String orderedParent = readViewObjectParentMembership(uri, username, password, modelOrdered, childId);
        String replayParent = readViewObjectParentMembership(uri, username, password, modelReplay, childId);

        Assertions.assertEquals(orderedParent, replayParent,
                "view-object child-member ops must converge under out-of-order and duplicate replay");
        Assertions.assertNull(replayParent,
                "expected child membership to be removed after remove-wins tie-break");

        repository.close();
    }

    @Test
    void viewObjectNotationConvergesUnderOutOfOrderAndDuplicateReplay() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelA = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String modelB = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String elementId = "elem:e1-" + UUID.randomUUID().toString().substring(0, 6);
        String viewId = "view:v1-" + UUID.randomUUID().toString().substring(0, 6);
        String viewObjectId = "vo:o1-" + UUID.randomUUID().toString().substring(0, 6);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", elementId)
                .put("archimateType", "BusinessActor"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        createView.set("view", JsonNodeFactory.instance.objectNode().put("id", viewId).put("name", "View 1"));
        createView.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 2));

        ObjectNode createViewObject = JsonNodeFactory.instance.objectNode();
        createViewObject.put("type", "CreateViewObject");
        createViewObject.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", viewObjectId)
                .put("viewId", viewId)
                .put("representsId", elementId)
                .set("notationJson", JsonNodeFactory.instance.objectNode()
                        .put("x", 10).put("y", 10).put("width", 100).put("height", 50)
                        .put("lineColor", "#111111").put("fontColor", "#222222")));
        createViewObject.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 3));

        ObjectNode staleLineColor = JsonNodeFactory.instance.objectNode();
        staleLineColor.put("type", "UpdateViewObjectOpaque");
        staleLineColor.put("viewId", viewId);
        staleLineColor.put("viewObjectId", viewObjectId);
        staleLineColor.set("notationJson", JsonNodeFactory.instance.objectNode().put("lineColor", "#aaaaaa"));
        staleLineColor.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 2));

        ObjectNode updateLineColor = JsonNodeFactory.instance.objectNode();
        updateLineColor.put("type", "UpdateViewObjectOpaque");
        updateLineColor.put("viewId", viewId);
        updateLineColor.put("viewObjectId", viewObjectId);
        updateLineColor.set("notationJson", JsonNodeFactory.instance.objectNode().put("lineColor", "#333333"));
        updateLineColor.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 20));

        ObjectNode updateFontColor = JsonNodeFactory.instance.objectNode();
        updateFontColor.put("type", "UpdateViewObjectOpaque");
        updateFontColor.put("viewId", viewId);
        updateFontColor.put("viewObjectId", viewObjectId);
        updateFontColor.set("notationJson", JsonNodeFactory.instance.objectNode().put("fontColor", "#444444"));
        updateFontColor.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-c").put("lamport", 21));

        ObjectNode baseBatch = JsonNodeFactory.instance.objectNode();
        baseBatch.put("modelId", modelA);
        baseBatch.put("opBatchId", "base");
        baseBatch.put("timestamp", "2026-01-01T00:00:00Z");
        baseBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(createElement).add(createView).add(createViewObject));

        ObjectNode staleBatch = JsonNodeFactory.instance.objectNode();
        staleBatch.put("modelId", modelA);
        staleBatch.put("opBatchId", "stale");
        staleBatch.put("timestamp", "2026-01-01T00:00:01Z");
        staleBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(staleLineColor));

        ObjectNode lineColorBatch = JsonNodeFactory.instance.objectNode();
        lineColorBatch.put("modelId", modelA);
        lineColorBatch.put("opBatchId", "line");
        lineColorBatch.put("timestamp", "2026-01-01T00:00:02Z");
        lineColorBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(updateLineColor));

        ObjectNode fontColorBatch = JsonNodeFactory.instance.objectNode();
        fontColorBatch.put("modelId", modelA);
        fontColorBatch.put("opBatchId", "font");
        fontColorBatch.put("timestamp", "2026-01-01T00:00:03Z");
        fontColorBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(updateFontColor));

        // Scenario A: mostly ordered.
        repository.applyToMaterializedState(modelA, baseBatch);
        repository.applyToMaterializedState(modelA, staleBatch);
        repository.applyToMaterializedState(modelA, lineColorBatch);
        repository.applyToMaterializedState(modelA, fontColorBatch);

        // Scenario B: out-of-order with duplicate replay.
        repository.applyToMaterializedState(modelB, baseBatch);
        repository.applyToMaterializedState(modelB, fontColorBatch);
        repository.applyToMaterializedState(modelB, lineColorBatch);
        repository.applyToMaterializedState(modelB, staleBatch);
        repository.applyToMaterializedState(modelB, fontColorBatch);

        JsonNode notationA = repository.loadSnapshot(modelA).path("viewObjects").get(0).path("notationJson");
        JsonNode notationB = repository.loadSnapshot(modelB).path("viewObjects").get(0).path("notationJson");

        Assertions.assertEquals("#333333", notationA.path("lineColor").asText());
        Assertions.assertEquals("#444444", notationA.path("fontColor").asText());
        Assertions.assertEquals(notationA.path("lineColor").asText(), notationB.path("lineColor").asText());
        Assertions.assertEquals(notationA.path("fontColor").asText(), notationB.path("fontColor").asText());
        Assertions.assertEquals(notationA.path("x").asInt(), notationB.path("x").asInt());
        Assertions.assertEquals(notationA.path("y").asInt(), notationB.path("y").asInt());

        repository.close();
    }

    @Test
    void replayedOpLogBatchesConvergeToSameSnapshotEvenWithDuplicateBatch() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String sourceModel = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String replayModel = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "base"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode updateName = JsonNodeFactory.instance.objectNode();
        updateName.put("type", "UpdateElement");
        updateName.put("elementId", "elem:e1");
        updateName.set("patch", JsonNodeFactory.instance.objectNode().put("name", "winning"));
        updateName.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 2));

        ObjectNode setProperty = JsonNodeFactory.instance.objectNode();
        setProperty.put("type", "SetProperty");
        setProperty.put("targetId", "elem:e1");
        setProperty.put("key", "risk");
        setProperty.put("value", "high");
        setProperty.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-c").put("lamport", 3));

        String batchId1 = "b1-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId2 = "b2-" + UUID.randomUUID().toString().substring(0, 8);

        ObjectNode b1 = JsonNodeFactory.instance.objectNode().put("modelId", sourceModel).put("opBatchId", batchId1).put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode().add(createElement));
        ObjectNode b2 = JsonNodeFactory.instance.objectNode().put("modelId", sourceModel).put("opBatchId", batchId2).put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(updateName).add(setProperty));

        repository.appendOpLog(sourceModel, batchId1, new RevisionRange(1, 1), b1);
        repository.applyToMaterializedState(sourceModel, b1);
        repository.appendOpLog(sourceModel, batchId2, new RevisionRange(2, 3), b2);
        repository.applyToMaterializedState(sourceModel, b2);
        repository.updateHeadRevision(sourceModel, 3);

        JsonNode batches = repository.loadOpBatches(sourceModel, 1, 3);
        for (JsonNode batch : batches) {
            repository.applyToMaterializedState(replayModel, batch);
            if (batchId2.equals(batch.path("opBatchId").asText())) {
                // Duplicate replay should be idempotent under LWW/idempotent merge semantics.
                repository.applyToMaterializedState(replayModel, batch);
            }
        }

        JsonNode sourceSnapshot = repository.loadSnapshot(sourceModel);
        JsonNode replaySnapshot = repository.loadSnapshot(replayModel);

        Assertions.assertEquals(sourceSnapshot.path("elements").size(), replaySnapshot.path("elements").size());
        Assertions.assertEquals(sourceSnapshot.path("elements").get(0).path("name").asText(),
                replaySnapshot.path("elements").get(0).path("name").asText());

        String sourceRisk = readElementProperty(uri, username, password, sourceModel, "elem:e1", "risk");
        String replayRisk = readElementProperty(uri, username, password, replayModel, "elem:e1", "risk");
        Assertions.assertEquals(sourceRisk, replayRisk);

        repository.close();
    }

    @Test
    void checkoutDeltaWindowBoundariesAndBatchRangeMetadataAreDeterministic() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String sourceModel = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String replayModel = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode create = JsonNodeFactory.instance.objectNode();
        create.put("type", "CreateElement");
        create.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "v1"));
        create.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1));

        ObjectNode updateNameV2 = JsonNodeFactory.instance.objectNode();
        updateNameV2.put("type", "UpdateElement");
        updateNameV2.put("elementId", "elem:e1");
        updateNameV2.set("patch", JsonNodeFactory.instance.objectNode().put("name", "v2"));
        updateNameV2.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 2));

        ObjectNode setRiskMedium = JsonNodeFactory.instance.objectNode();
        setRiskMedium.put("type", "SetProperty");
        setRiskMedium.put("targetId", "elem:e1");
        setRiskMedium.put("key", "risk");
        setRiskMedium.put("value", "medium");
        setRiskMedium.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 3));

        ObjectNode updateNameV3 = JsonNodeFactory.instance.objectNode();
        updateNameV3.put("type", "UpdateElement");
        updateNameV3.put("elementId", "elem:e1");
        updateNameV3.set("patch", JsonNodeFactory.instance.objectNode().put("name", "v3"));
        updateNameV3.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-c").put("lamport", 4));

        ObjectNode setRiskHigh = JsonNodeFactory.instance.objectNode();
        setRiskHigh.put("type", "SetProperty");
        setRiskHigh.put("targetId", "elem:e1");
        setRiskHigh.put("key", "risk");
        setRiskHigh.put("value", "high");
        setRiskHigh.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-c").put("lamport", 5));

        ObjectNode setOwner = JsonNodeFactory.instance.objectNode();
        setOwner.put("type", "SetProperty");
        setOwner.put("targetId", "elem:e1");
        setOwner.put("key", "owner");
        setOwner.put("value", "team-a");
        setOwner.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-c").put("lamport", 6));

        String batchId1 = "delta-b1-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId2 = "delta-b2-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId3 = "delta-b3-" + UUID.randomUUID().toString().substring(0, 8);

        ObjectNode b1 = JsonNodeFactory.instance.objectNode()
                .put("modelId", sourceModel)
                .put("opBatchId", batchId1)
                .put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode().add(create)); // revisions 1..1

        ObjectNode b2 = JsonNodeFactory.instance.objectNode()
                .put("modelId", sourceModel)
                .put("opBatchId", batchId2)
                .put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(updateNameV2).add(setRiskMedium)); // revisions 2..3

        ObjectNode b3 = JsonNodeFactory.instance.objectNode()
                .put("modelId", sourceModel)
                .put("opBatchId", batchId3)
                .put("timestamp", "2026-01-01T00:00:02Z");
        b3.set("ops", JsonNodeFactory.instance.arrayNode().add(updateNameV3).add(setRiskHigh).add(setOwner)); // revisions 4..6

        repository.appendOpLog(sourceModel, batchId1, new RevisionRange(1, 1), b1);
        repository.applyToMaterializedState(sourceModel, b1);
        repository.appendOpLog(sourceModel, batchId2, new RevisionRange(2, 3), b2);
        repository.applyToMaterializedState(sourceModel, b2);
        repository.appendOpLog(sourceModel, batchId3, new RevisionRange(4, 6), b3);
        repository.applyToMaterializedState(sourceModel, b3);
        repository.updateHeadRevision(sourceModel, 6L);

        JsonNode delta = repository.loadOpBatches(sourceModel, 2, 6);
        Assertions.assertEquals(2, delta.size(), "delta from 2..6 must include exactly commits [2..3] and [4..6]");

        JsonNode deltaBatch1 = delta.get(0);
        JsonNode deltaBatch2 = delta.get(1);
        Assertions.assertEquals(batchId2, deltaBatch1.path("opBatchId").asText());
        Assertions.assertEquals(2L, deltaBatch1.path("assignedRevisionRange").path("from").asLong());
        Assertions.assertEquals(3L, deltaBatch1.path("assignedRevisionRange").path("to").asLong());
        Assertions.assertEquals(1L, deltaBatch1.path("baseRevision").asLong(), "baseRevision must equal from-1");

        Assertions.assertEquals(batchId3, deltaBatch2.path("opBatchId").asText());
        Assertions.assertEquals(4L, deltaBatch2.path("assignedRevisionRange").path("from").asLong());
        Assertions.assertEquals(6L, deltaBatch2.path("assignedRevisionRange").path("to").asLong());
        Assertions.assertEquals(3L, deltaBatch2.path("baseRevision").asLong(), "baseRevision must equal from-1");

        Assertions.assertEquals(
                deltaBatch1.path("assignedRevisionRange").path("to").asLong() + 1L,
                deltaBatch2.path("assignedRevisionRange").path("from").asLong(),
                "delta windows must be contiguous without overlap or gaps");

        // Replay from stale reconnect head=1: apply base commit, then loaded delta commits.
        repository.applyToMaterializedState(replayModel, b1);
        repository.applyToMaterializedState(replayModel, deltaBatch1);
        repository.applyToMaterializedState(replayModel, deltaBatch2);

        JsonNode expected = canonicalizeSnapshot(repository.loadSnapshot(sourceModel));
        JsonNode actual = canonicalizeSnapshot(repository.loadSnapshot(replayModel));
        Assertions.assertEquals(expected, actual,
                "replaying loaded delta batches with their range metadata must converge to source snapshot");

        repository.close();
    }

    @Test
    void compactionRetainsReplayHistoryAndTombstoneDeleteSafety() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createAt10 = JsonNodeFactory.instance.objectNode();
        createAt10.put("type", "CreateElement");
        createAt10.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "v10"));
        createAt10.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 10)
                .put("opId", "batch-1:0"));

        ObjectNode deleteAt20 = JsonNodeFactory.instance.objectNode();
        deleteAt20.put("type", "DeleteElement");
        deleteAt20.put("elementId", "elem:e1");
        deleteAt20.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 20)
                .put("opId", "batch-2:0"));

        ObjectNode staleCreateAt19 = JsonNodeFactory.instance.objectNode();
        staleCreateAt19.put("type", "CreateElement");
        staleCreateAt19.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "stale-recreate"));
        staleCreateAt19.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 19)
                .put("opId", "batch-3:0"));

        ObjectNode staleUpdateAt19 = JsonNodeFactory.instance.objectNode();
        staleUpdateAt19.put("type", "UpdateElement");
        staleUpdateAt19.put("elementId", "elem:e1");
        staleUpdateAt19.set("patch", JsonNodeFactory.instance.objectNode()
                .put("name", "stale-update"));
        staleUpdateAt19.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-y")
                .put("lamport", 19)
                .put("opId", "batch-3:1"));

        ObjectNode winningCreateAt21 = JsonNodeFactory.instance.objectNode();
        winningCreateAt21.put("type", "CreateElement");
        winningCreateAt21.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "recreated"));
        winningCreateAt21.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 21)
                .put("opId", "batch-4:0"));

        String batchId1 = "batch-1-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId2 = "batch-2-" + UUID.randomUUID().toString().substring(0, 8);

        ObjectNode b1 = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelId)
                .put("opBatchId", batchId1)
                .put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode().add(createAt10));
        ObjectNode b2 = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelId)
                .put("opBatchId", batchId2)
                .put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(deleteAt20));

        repository.appendOpLog(modelId, batchId1, new RevisionRange(1, 1), b1);
        repository.applyToMaterializedState(modelId, b1);
        repository.appendOpLog(modelId, batchId2, new RevisionRange(2, 2), b2);
        repository.applyToMaterializedState(modelId, b2);
        repository.updateHeadRevision(modelId, 2L);

        var compaction = repository.compactMetadata(modelId, 0L);
        Assertions.assertTrue(compaction.executed());
        Assertions.assertEquals(2L, compaction.committedHorizonRevision(), "compaction horizon must follow committed revision");
        Assertions.assertEquals(2L, compaction.watermarkRevision(), "watermark should be derived from committed horizon");
        Assertions.assertEquals(0L, compaction.deletedCommitCount(), "recovery history must be retained");
        Assertions.assertEquals(0L, compaction.deletedOpCount(), "recovery operations must be retained");
        Assertions.assertTrue(compaction.retainedTombstoneCount() >= 1L, "tombstones must be retained for delete safety");
        Assertions.assertTrue(compaction.eligibleTombstoneCount() >= 1L, "eligible tombstones should be reported against watermark");

        JsonNode delta = repository.loadOpBatches(modelId, 1, 2);
        Assertions.assertEquals(2, delta.size(), "complete replay history must survive compaction");
        Assertions.assertEquals(batchId2, delta.get(1).path("opBatchId").asText());

        ObjectNode staleUpdateBatch = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelId)
                .put("opBatchId", "stale-update")
                .put("timestamp", "2026-01-01T00:00:02Z");
        staleUpdateBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(staleUpdateAt19));
        repository.applyToMaterializedState(modelId, staleUpdateBatch);
        Assertions.assertEquals(0, repository.loadSnapshot(modelId).path("elements").size(),
                "stale update must still be blocked after compaction");

        ObjectNode staleBatch = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelId)
                .put("opBatchId", "stale-recreate")
                .put("timestamp", "2026-01-01T00:00:03Z");
        staleBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(staleCreateAt19));
        repository.applyToMaterializedState(modelId, staleBatch);
        Assertions.assertEquals(0, repository.loadSnapshot(modelId).path("elements").size(),
                "stale recreate must still be blocked after compaction");

        ObjectNode winningBatch = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelId)
                .put("opBatchId", "winning-recreate")
                .put("timestamp", "2026-01-01T00:00:04Z");
        winningBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(winningCreateAt21));
        repository.applyToMaterializedState(modelId, winningBatch);
        Assertions.assertEquals(1, repository.loadSnapshot(modelId).path("elements").size(),
                "newer recreate must still be allowed after compaction");

        repository.close();
    }

    @Test
    void compactionWithNonZeroRetentionReportsEligibleAndRetainedMetadata() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createE1 = JsonNodeFactory.instance.objectNode();
        createE1.put("type", "CreateElement");
        createE1.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "to-delete"));
        createE1.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 1)
                .put("opId", "b1:0"));

        ObjectNode deleteE1 = JsonNodeFactory.instance.objectNode();
        deleteE1.put("type", "DeleteElement");
        deleteE1.put("elementId", "elem:e1");
        deleteE1.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 2)
                .put("opId", "b2:0"));

        ObjectNode createE2 = JsonNodeFactory.instance.objectNode();
        createE2.put("type", "CreateElement");
        createE2.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e2")
                .put("archimateType", "BusinessActor")
                .put("name", "clock-seed"));
        createE2.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 3)
                .put("opId", "b3:0"));

        ObjectNode updateE2 = JsonNodeFactory.instance.objectNode();
        updateE2.put("type", "UpdateElement");
        updateE2.put("elementId", "elem:e2");
        updateE2.set("patch", JsonNodeFactory.instance.objectNode().put("name", "latest"));
        updateE2.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 4)
                .put("opId", "b4:0"));

        ObjectNode b1 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b1").put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode().add(createE1)); // rev1
        ObjectNode b2 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b2").put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(deleteE1)); // rev2 tombstone
        ObjectNode b3 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b3").put("timestamp", "2026-01-01T00:00:02Z");
        b3.set("ops", JsonNodeFactory.instance.arrayNode().add(createE2)); // rev3 field clock eligible under watermark=3
        ObjectNode b4 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b4").put("timestamp", "2026-01-01T00:00:03Z");
        b4.set("ops", JsonNodeFactory.instance.arrayNode().add(updateE2)); // rev4 (newer than watermark)

        repository.appendOpLog(modelId, "b1", new RevisionRange(1, 1), b1);
        repository.applyToMaterializedState(modelId, b1);
        repository.appendOpLog(modelId, "b2", new RevisionRange(2, 2), b2);
        repository.applyToMaterializedState(modelId, b2);
        repository.appendOpLog(modelId, "b3", new RevisionRange(3, 3), b3);
        repository.applyToMaterializedState(modelId, b3);
        repository.appendOpLog(modelId, "b4", new RevisionRange(4, 4), b4);
        repository.applyToMaterializedState(modelId, b4);
        repository.updateHeadRevision(modelId, 4L);

        long retain = 1L; // watermark = committed(4) - retain(1) = 3
        var compaction = repository.compactMetadata(modelId, retain);
        Assertions.assertTrue(compaction.executed());
        Assertions.assertEquals(4L, compaction.committedHorizonRevision());
        Assertions.assertEquals(3L, compaction.watermarkRevision());
        Assertions.assertEquals(retain, compaction.retainRevisions());

        Assertions.assertTrue(compaction.retainedTombstoneCount() >= 1L, "delete tombstones should be retained");
        Assertions.assertTrue(compaction.eligibleTombstoneCount() >= 1L,
                "older tombstones should be reported eligible under watermark");
        Assertions.assertTrue(compaction.eligibleFieldClockCount() >= 1L,
                "field clocks at or below watermark should be reported eligible");
        Assertions.assertEquals(0L, compaction.deletedCommitCount(),
                "complete replay history must be retained");

        repository.close();
    }

    @Test
    void sameLogicalOpSetConvergesAcrossOrderAndDuplicateDelivery() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelInOrder = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String modelShuffled = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        String elementId = "elem:e1";
        String viewId = "view:v1";
        String viewObjectId = "vo:o1";

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", elementId)
                .put("archimateType", "BusinessActor")
                .put("name", "base"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 1)
                .put("opId", "base:0"));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        createView.set("view", JsonNodeFactory.instance.objectNode()
                .put("id", viewId)
                .put("name", "Default"));
        createView.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 2)
                .put("opId", "base:1"));

        ObjectNode createViewObject = JsonNodeFactory.instance.objectNode();
        createViewObject.put("type", "CreateViewObject");
        createViewObject.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", viewObjectId)
                .put("viewId", viewId)
                .put("representsId", elementId)
                .set("notationJson", JsonNodeFactory.instance.objectNode()
                        .put("x", 10).put("y", 20).put("width", 100).put("height", 50)
                        .put("lineColor", "#111111").put("fontColor", "#222222")));
        createViewObject.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 3)
                .put("opId", "base:2"));

        ObjectNode staleLineColor = JsonNodeFactory.instance.objectNode();
        staleLineColor.put("type", "UpdateViewObjectOpaque");
        staleLineColor.put("viewId", viewId);
        staleLineColor.put("viewObjectId", viewObjectId);
        staleLineColor.set("notationJson", JsonNodeFactory.instance.objectNode().put("lineColor", "#aaaaaa"));
        staleLineColor.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 4)
                .put("opId", "stale:0"));

        ObjectNode updateFontColor = JsonNodeFactory.instance.objectNode();
        updateFontColor.put("type", "UpdateViewObjectOpaque");
        updateFontColor.put("viewId", viewId);
        updateFontColor.put("viewObjectId", viewObjectId);
        updateFontColor.set("notationJson", JsonNodeFactory.instance.objectNode().put("fontColor", "#444444"));
        updateFontColor.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-c")
                .put("lamport", 5)
                .put("opId", "update:font"));

        ObjectNode updateLineColor = JsonNodeFactory.instance.objectNode();
        updateLineColor.put("type", "UpdateViewObjectOpaque");
        updateLineColor.put("viewId", viewId);
        updateLineColor.put("viewObjectId", viewObjectId);
        updateLineColor.set("notationJson", JsonNodeFactory.instance.objectNode().put("lineColor", "#333333"));
        updateLineColor.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 6)
                .put("opId", "update:line"));

        ObjectNode setRisk = JsonNodeFactory.instance.objectNode();
        setRisk.put("type", "SetProperty");
        setRisk.put("targetId", elementId);
        setRisk.put("key", "risk");
        setRisk.put("value", "high");
        setRisk.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 7)
                .put("opId", "update:risk"));

        ObjectNode setName = JsonNodeFactory.instance.objectNode();
        setName.put("type", "UpdateElement");
        setName.put("elementId", elementId);
        setName.set("patch", JsonNodeFactory.instance.objectNode().put("name", "winner"));
        setName.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 8)
                .put("opId", "update:name"));

        ObjectNode baseBatch = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelInOrder)
                .put("opBatchId", "base")
                .put("timestamp", "2026-01-01T00:00:00Z");
        baseBatch.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(createElement)
                .add(createView)
                .add(createViewObject));

        ObjectNode styleBatch = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelInOrder)
                .put("opBatchId", "style")
                .put("timestamp", "2026-01-01T00:00:01Z");
        styleBatch.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(staleLineColor)
                .add(updateFontColor));

        ObjectNode lineAndPropertyBatch = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelInOrder)
                .put("opBatchId", "line-and-prop")
                .put("timestamp", "2026-01-01T00:00:02Z");
        lineAndPropertyBatch.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(updateLineColor)
                .add(setRisk));

        ObjectNode nameBatch = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelInOrder)
                .put("opBatchId", "name")
                .put("timestamp", "2026-01-01T00:00:03Z");
        nameBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(setName));

        // Scenario A: in-order delivery.
        repository.applyToMaterializedState(modelInOrder, baseBatch);
        repository.applyToMaterializedState(modelInOrder, styleBatch);
        repository.applyToMaterializedState(modelInOrder, lineAndPropertyBatch);
        repository.applyToMaterializedState(modelInOrder, nameBatch);

        // Scenario B: out-of-order + duplicates.
        repository.applyToMaterializedState(modelShuffled, baseBatch);
        repository.applyToMaterializedState(modelShuffled, lineAndPropertyBatch);
        repository.applyToMaterializedState(modelShuffled, styleBatch);
        repository.applyToMaterializedState(modelShuffled, lineAndPropertyBatch);
        repository.applyToMaterializedState(modelShuffled, nameBatch);
        repository.applyToMaterializedState(modelShuffled, styleBatch);

        JsonNode inOrder = canonicalizeSnapshot(repository.loadSnapshot(modelInOrder));
        JsonNode shuffled = canonicalizeSnapshot(repository.loadSnapshot(modelShuffled));
        Assertions.assertEquals(inOrder, shuffled, "same logical op-set should converge despite order and duplicates");

        JsonNode notation = shuffled.path("viewObjects").get(0).path("notationJson");
        Assertions.assertEquals("#333333", notation.path("lineColor").asText());
        Assertions.assertEquals("#444444", notation.path("fontColor").asText());
        Assertions.assertEquals("winner", shuffled.path("elements").get(0).path("name").asText());
        Assertions.assertEquals("high", readElementProperty(uri, username, password, modelShuffled, elementId, "risk"));

        repository.close();
    }

    @Test
    void sameLogicalFolderOpSetConvergesAcrossOrderAndDuplicateDelivery() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelInOrder = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String modelShuffled = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createParent = JsonNodeFactory.instance.objectNode();
        createParent.put("type", "CreateFolder");
        createParent.set("folder", JsonNodeFactory.instance.objectNode()
                .put("id", "folder:f-parent")
                .put("folderType", "USER")
                .put("name", "Capabilities")
                .put("parentFolderId", "folder:root-business"));
        createParent.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1).put("opId", "base:0"));

        ObjectNode createChild = JsonNodeFactory.instance.objectNode();
        createChild.put("type", "CreateFolder");
        createChild.set("folder", JsonNodeFactory.instance.objectNode()
                .put("id", "folder:f-child")
                .put("folderType", "USER")
                .put("name", "Payments")
                .put("parentFolderId", "folder:f-parent"));
        createChild.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 2).put("opId", "base:1"));

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "Base Element"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 3).put("opId", "base:2"));

        ObjectNode moveElement = JsonNodeFactory.instance.objectNode();
        moveElement.put("type", "MoveElementToFolder");
        moveElement.put("elementId", "elem:e1");
        moveElement.put("folderId", "folder:f-child");
        moveElement.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 4).put("opId", "base:3"));

        ObjectNode renameFromB = JsonNodeFactory.instance.objectNode();
        renameFromB.put("type", "UpdateFolder");
        renameFromB.put("folderId", "folder:f-child");
        renameFromB.set("patch", JsonNodeFactory.instance.objectNode().put("name", "Shared Payments"));
        renameFromB.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 7).put("opId", "b:0"));

        ObjectNode moveToOther = JsonNodeFactory.instance.objectNode();
        moveToOther.put("type", "MoveFolder");
        moveToOther.put("folderId", "folder:f-child");
        moveToOther.put("parentFolderId", "folder:root-other");
        moveToOther.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-b").put("lamport", 8).put("opId", "b:1"));

        ObjectNode staleRename = JsonNodeFactory.instance.objectNode();
        staleRename.put("type", "UpdateFolder");
        staleRename.put("folderId", "folder:f-child");
        staleRename.set("patch", JsonNodeFactory.instance.objectNode().put("name", "Stale Payments"));
        staleRename.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 6).put("opId", "c:0"));

        ObjectNode staleMove = JsonNodeFactory.instance.objectNode();
        staleMove.put("type", "MoveFolder");
        staleMove.put("folderId", "folder:f-child");
        staleMove.put("parentFolderId", "folder:f-parent");
        staleMove.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-z").put("lamport", 7).put("opId", "c:1"));

        ObjectNode baseBatch = JsonNodeFactory.instance.objectNode().put("modelId", modelInOrder).put("opBatchId", "folder-base").put("timestamp", "2026-01-01T00:00:00Z");
        baseBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(createParent).add(createChild).add(createElement).add(moveElement));

        ObjectNode clientBBatch = JsonNodeFactory.instance.objectNode().put("modelId", modelInOrder).put("opBatchId", "folder-b").put("timestamp", "2026-01-01T00:00:01Z");
        clientBBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(renameFromB).add(moveToOther));

        ObjectNode clientCBatch = JsonNodeFactory.instance.objectNode().put("modelId", modelInOrder).put("opBatchId", "folder-c").put("timestamp", "2026-01-01T00:00:02Z");
        clientCBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(staleRename).add(staleMove));

        repository.applyToMaterializedState(modelInOrder, baseBatch);
        repository.applyToMaterializedState(modelInOrder, clientCBatch);
        repository.applyToMaterializedState(modelInOrder, clientBBatch);

        repository.applyToMaterializedState(modelShuffled, baseBatch);
        repository.applyToMaterializedState(modelShuffled, clientBBatch);
        repository.applyToMaterializedState(modelShuffled, clientCBatch);
        repository.applyToMaterializedState(modelShuffled, clientBBatch);
        repository.applyToMaterializedState(modelShuffled, clientCBatch);

        JsonNode inOrder = canonicalizeSnapshot(repository.loadSnapshot(modelInOrder));
        JsonNode shuffled = canonicalizeSnapshot(repository.loadSnapshot(modelShuffled));
        Assertions.assertEquals(inOrder, shuffled,
                "same logical folder op-set should converge despite order and duplicates");

        JsonNode childFolder = findById(shuffled.path("folders"), "folder:f-child");
        Assertions.assertNotNull(childFolder);
        Assertions.assertEquals("Shared Payments", childFolder.path("name").asText());
        Assertions.assertEquals("folder:root-other", childFolder.path("parentFolderId").asText());
        JsonNode member = findByField(shuffled.path("elementFolderMembers"), "elementId", "elem:e1");
        Assertions.assertNotNull(member);
        Assertions.assertEquals("folder:f-child", member.path("folderId").asText());

        repository.close();
    }

    @Test
    void staleReconnectDeltaWindowWithDuplicateReplayConvergesToHeadSnapshot() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String sourceModel = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String replayModel = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String elementId = "elem:e1-" + UUID.randomUUID().toString().substring(0, 6);
        String viewId = "view:v1-" + UUID.randomUUID().toString().substring(0, 6);
        String viewObjectId = "vo:o1-" + UUID.randomUUID().toString().substring(0, 6);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", elementId)
                .put("archimateType", "BusinessActor")
                .put("name", "base"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 1)
                .put("opId", "b1:0"));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        createView.set("view", JsonNodeFactory.instance.objectNode()
                .put("id", viewId)
                .put("name", "View 1"));
        createView.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 2)
                .put("opId", "b1:1"));

        ObjectNode createViewObject = JsonNodeFactory.instance.objectNode();
        createViewObject.put("type", "CreateViewObject");
        createViewObject.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", viewObjectId)
                .put("viewId", viewId)
                .put("representsId", elementId)
                .set("notationJson", JsonNodeFactory.instance.objectNode()
                        .put("x", 10)
                        .put("y", 10)
                        .put("width", 120)
                        .put("height", 55)
                        .put("lineColor", "#111111")));
        createViewObject.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 3)
                .put("opId", "b1:2"));

        ObjectNode updateLineColor = JsonNodeFactory.instance.objectNode();
        updateLineColor.put("type", "UpdateViewObjectOpaque");
        updateLineColor.put("viewId", viewId);
        updateLineColor.put("viewObjectId", viewObjectId);
        updateLineColor.set("notationJson", JsonNodeFactory.instance.objectNode().put("lineColor", "#333333"));
        updateLineColor.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 5)
                .put("opId", "b2:0"));

        ObjectNode updateName = JsonNodeFactory.instance.objectNode();
        updateName.put("type", "UpdateElement");
        updateName.put("elementId", elementId);
        updateName.set("patch", JsonNodeFactory.instance.objectNode().put("name", "winner"));
        updateName.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-c")
                .put("lamport", 6)
                .put("opId", "b3:0"));

        ObjectNode setRisk = JsonNodeFactory.instance.objectNode();
        setRisk.put("type", "SetProperty");
        setRisk.put("targetId", elementId);
        setRisk.put("key", "risk");
        setRisk.put("value", "high");
        setRisk.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-c")
                .put("lamport", 7)
                .put("opId", "b3:1"));

        String batchId1 = "b1-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId2 = "b2-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId3 = "b3-" + UUID.randomUUID().toString().substring(0, 8);

        ObjectNode b1 = JsonNodeFactory.instance.objectNode()
                .put("modelId", sourceModel)
                .put("opBatchId", batchId1)
                .put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(createElement)
                .add(createView)
                .add(createViewObject));

        ObjectNode b2 = JsonNodeFactory.instance.objectNode()
                .put("modelId", sourceModel)
                .put("opBatchId", batchId2)
                .put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(updateLineColor));

        ObjectNode b3 = JsonNodeFactory.instance.objectNode()
                .put("modelId", sourceModel)
                .put("opBatchId", batchId3)
                .put("timestamp", "2026-01-01T00:00:02Z");
        b3.set("ops", JsonNodeFactory.instance.arrayNode().add(updateName).add(setRisk));

        repository.appendOpLog(sourceModel, batchId1, new RevisionRange(1, 3), b1);
        repository.applyToMaterializedState(sourceModel, b1);
        repository.appendOpLog(sourceModel, batchId2, new RevisionRange(4, 4), b2);
        repository.applyToMaterializedState(sourceModel, b2);
        repository.appendOpLog(sourceModel, batchId3, new RevisionRange(5, 6), b3);
        repository.applyToMaterializedState(sourceModel, b3);
        repository.updateHeadRevision(sourceModel, 6L);

        JsonNode baseWindow = repository.loadOpBatches(sourceModel, 1, 3);
        JsonNode deltaWindow = repository.loadOpBatches(sourceModel, 4, 6);
        Assertions.assertEquals(1, baseWindow.size(), "snapshot base window must include initial commit only");
        Assertions.assertEquals(2, deltaWindow.size(), "stale reconnect delta window should include remaining commits");
        Assertions.assertEquals(4L, deltaWindow.get(0).path("assignedRevisionRange").path("from").asLong());
        Assertions.assertEquals(6L, deltaWindow.get(1).path("assignedRevisionRange").path("to").asLong());

        // Simulate stale reconnect at revision 3: apply snapshot base first.
        repository.applyToMaterializedState(replayModel, baseWindow.get(0));
        // Simulate local outbox replay that duplicates later server delta.
        repository.applyToMaterializedState(replayModel, deltaWindow.get(1));
        // Apply server delta out-of-order with duplicate replay.
        repository.applyToMaterializedState(replayModel, deltaWindow.get(1));
        repository.applyToMaterializedState(replayModel, deltaWindow.get(0));
        repository.applyToMaterializedState(replayModel, deltaWindow.get(1));

        JsonNode expected = canonicalizeSnapshot(repository.loadSnapshot(sourceModel));
        JsonNode actual = canonicalizeSnapshot(repository.loadSnapshot(replayModel));
        Assertions.assertEquals(expected, actual,
                "reconnect delta replay must converge despite out-of-order and duplicate batch application");

        JsonNode notation = actual.path("viewObjects").get(0).path("notationJson");
        Assertions.assertEquals("#333333", notation.path("lineColor").asText());
        Assertions.assertEquals("winner", actual.path("elements").get(0).path("name").asText());
        Assertions.assertEquals("high", readElementProperty(uri, username, password, replayModel, elementId, "risk"));

        repository.close();
    }

    @Test
    void twoLogicalClientsConvergeToByteEquivalentSnapshotAcrossAdversarialReplay() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String orderedModel = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String adversarialModel = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String elementId = "elem:e1-" + UUID.randomUUID().toString().substring(0, 6);
        String viewId = "view:v1-" + UUID.randomUUID().toString().substring(0, 6);
        String viewObjectId = "vo:o1-" + UUID.randomUUID().toString().substring(0, 6);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        createElement.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", elementId)
                .put("archimateType", "BusinessActor")
                .put("name", "base"));
        createElement.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 1)
                .put("opId", "cA:1"));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        createView.set("view", JsonNodeFactory.instance.objectNode()
                .put("id", viewId)
                .put("name", "Main"));
        createView.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 2)
                .put("opId", "cA:2"));

        ObjectNode createViewObject = JsonNodeFactory.instance.objectNode();
        createViewObject.put("type", "CreateViewObject");
        createViewObject.set("viewObject", JsonNodeFactory.instance.objectNode()
                .put("id", viewObjectId)
                .put("viewId", viewId)
                .put("representsId", elementId)
                .set("notationJson", JsonNodeFactory.instance.objectNode()
                        .put("x", 10).put("y", 10).put("width", 120).put("height", 55)
                        .put("lineColor", "#111111").put("fontColor", "#222222")));
        createViewObject.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 3)
                .put("opId", "cA:3"));

        ObjectNode nameFromA = JsonNodeFactory.instance.objectNode();
        nameFromA.put("type", "UpdateElement");
        nameFromA.put("elementId", elementId);
        nameFromA.set("patch", JsonNodeFactory.instance.objectNode().put("name", "name-from-a"));
        nameFromA.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 10)
                .put("opId", "cA:10"));

        ObjectNode lineColorFromA = JsonNodeFactory.instance.objectNode();
        lineColorFromA.put("type", "UpdateViewObjectOpaque");
        lineColorFromA.put("viewId", viewId);
        lineColorFromA.put("viewObjectId", viewObjectId);
        lineColorFromA.set("notationJson", JsonNodeFactory.instance.objectNode().put("lineColor", "#333333"));
        lineColorFromA.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 11)
                .put("opId", "cA:11"));

        ObjectNode nameFromB = JsonNodeFactory.instance.objectNode();
        nameFromB.put("type", "UpdateElement");
        nameFromB.put("elementId", elementId);
        nameFromB.set("patch", JsonNodeFactory.instance.objectNode().put("name", "name-from-b"));
        nameFromB.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 10)
                .put("opId", "cB:10"));

        ObjectNode fontColorFromB = JsonNodeFactory.instance.objectNode();
        fontColorFromB.put("type", "UpdateViewObjectOpaque");
        fontColorFromB.put("viewId", viewId);
        fontColorFromB.put("viewObjectId", viewObjectId);
        fontColorFromB.set("notationJson", JsonNodeFactory.instance.objectNode().put("fontColor", "#444444"));
        fontColorFromB.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 12)
                .put("opId", "cB:12"));

        ObjectNode riskFromB = JsonNodeFactory.instance.objectNode();
        riskFromB.put("type", "SetProperty");
        riskFromB.put("targetId", elementId);
        riskFromB.put("key", "risk");
        riskFromB.put("value", "high");
        riskFromB.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 13)
                .put("opId", "cB:13"));

        ObjectNode foundationBatch = JsonNodeFactory.instance.objectNode()
                .put("modelId", orderedModel)
                .put("opBatchId", "foundation")
                .put("timestamp", "2026-01-01T00:00:00Z");
        foundationBatch.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(createElement)
                .add(createView)
                .add(createViewObject));

        ObjectNode clientABatch = JsonNodeFactory.instance.objectNode()
                .put("modelId", orderedModel)
                .put("opBatchId", "client-a")
                .put("timestamp", "2026-01-01T00:00:01Z");
        clientABatch.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(nameFromA)
                .add(lineColorFromA));

        ObjectNode clientBBatch = JsonNodeFactory.instance.objectNode()
                .put("modelId", orderedModel)
                .put("opBatchId", "client-b")
                .put("timestamp", "2026-01-01T00:00:02Z");
        clientBBatch.set("ops", JsonNodeFactory.instance.arrayNode()
                .add(nameFromB)
                .add(fontColorFromB)
                .add(riskFromB));

        // Scenario A: in-order replay.
        repository.applyToMaterializedState(orderedModel, foundationBatch);
        repository.applyToMaterializedState(orderedModel, clientABatch);
        repository.applyToMaterializedState(orderedModel, clientBBatch);

        // Scenario B: out-of-order + duplicate replay of same logical op-set.
        repository.applyToMaterializedState(adversarialModel, foundationBatch);
        repository.applyToMaterializedState(adversarialModel, clientBBatch);
        repository.applyToMaterializedState(adversarialModel, clientABatch);
        repository.applyToMaterializedState(adversarialModel, clientBBatch);
        repository.applyToMaterializedState(adversarialModel, clientABatch);

        String orderedCanonical = canonicalSnapshotJson(repository.loadSnapshot(orderedModel));
        String adversarialCanonical = canonicalSnapshotJson(repository.loadSnapshot(adversarialModel));
        Assertions.assertEquals(orderedCanonical, adversarialCanonical,
                "two-client logical history must converge to byte-equivalent canonical snapshot");

        // Duplicate replay after convergence should not mutate materialized state.
        String beforeDuplicateReplay = adversarialCanonical;
        repository.applyToMaterializedState(adversarialModel, clientBBatch);
        repository.applyToMaterializedState(adversarialModel, clientABatch);
        String afterDuplicateReplay = canonicalSnapshotJson(repository.loadSnapshot(adversarialModel));
        Assertions.assertEquals(beforeDuplicateReplay, afterDuplicateReplay,
                "duplicate replay after convergence must be a no-op on canonical snapshot");

        JsonNode actual = repository.loadSnapshot(adversarialModel);
        JsonNode notation = actual.path("viewObjects").get(0).path("notationJson");
        Assertions.assertEquals("name-from-b", actual.path("elements").get(0).path("name").asText(),
                "tie on lamport must be resolved by higher clientId");
        Assertions.assertEquals("#333333", notation.path("lineColor").asText());
        Assertions.assertEquals("#444444", notation.path("fontColor").asText());
        Assertions.assertEquals("high", readElementProperty(uri, username, password, adversarialModel, elementId, "risk"));

        repository.close();
    }

    @Test
    void compactionWatermarkUsesCommittedHorizonWhenHeadIsAhead() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode create = JsonNodeFactory.instance.objectNode();
        create.put("type", "CreateElement");
        create.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "v1"));
        create.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 1)
                .put("opId", "batch-1:0"));

        ObjectNode update = JsonNodeFactory.instance.objectNode();
        update.put("type", "UpdateElement");
        update.put("elementId", "elem:e1");
        update.set("patch", JsonNodeFactory.instance.objectNode().put("name", "v2"));
        update.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 2)
                .put("opId", "batch-2:0"));

        String batchId1 = "batch-1-" + UUID.randomUUID().toString().substring(0, 8);
        String batchId2 = "batch-2-" + UUID.randomUUID().toString().substring(0, 8);

        ObjectNode b1 = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelId)
                .put("opBatchId", batchId1)
                .put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode().add(create));

        ObjectNode b2 = JsonNodeFactory.instance.objectNode()
                .put("modelId", modelId)
                .put("opBatchId", batchId2)
                .put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(update));

        repository.appendOpLog(modelId, batchId1, new RevisionRange(1, 1), b1);
        repository.applyToMaterializedState(modelId, b1);
        repository.appendOpLog(modelId, batchId2, new RevisionRange(2, 2), b2);
        repository.applyToMaterializedState(modelId, b2);

        // Simulate drift: head moved ahead without matching committed op-log horizon.
        repository.updateHeadRevision(modelId, 100L);
        Assertions.assertEquals(2L, repository.readLatestCommitRevision(modelId));
        Assertions.assertEquals(100L, repository.readHeadRevision(modelId));

        var compaction = repository.compactMetadata(modelId, 0L);
        Assertions.assertTrue(compaction.executed());
        Assertions.assertEquals(100L, compaction.headRevision());
        Assertions.assertEquals(2L, compaction.committedHorizonRevision());
        Assertions.assertEquals(2L, compaction.watermarkRevision(),
                "watermark must follow committed horizon, not drifted head");
        Assertions.assertTrue(compaction.eligibleFieldClockCount() >= 1L,
                "eligible field clocks should be reported for conservative retention policy");
        Assertions.assertEquals(0L, compaction.deletedCommitCount(),
                "compaction must preserve recoverability");

        JsonNode delta = repository.loadOpBatches(modelId, 1, 2);
        Assertions.assertEquals(2, delta.size(), "both commits must survive compaction");
        Assertions.assertEquals(batchId2, delta.get(1).path("opBatchId").asText());

        repository.close();
    }

    @Test
    void compactionStatusFieldsSatisfyAdminInvariants() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode create = JsonNodeFactory.instance.objectNode();
        create.put("type", "CreateElement");
        create.set("element", JsonNodeFactory.instance.objectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor")
                .put("name", "v1"));
        create.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 1).put("opId", "b1:0"));

        ObjectNode update = JsonNodeFactory.instance.objectNode();
        update.put("type", "UpdateElement");
        update.put("elementId", "elem:e1");
        update.set("patch", JsonNodeFactory.instance.objectNode().put("name", "v2"));
        update.set("causal", JsonNodeFactory.instance.objectNode().put("clientId", "client-a").put("lamport", 2).put("opId", "b2:0"));

        ObjectNode b1 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b1").put("timestamp", "2026-01-01T00:00:00Z");
        b1.set("ops", JsonNodeFactory.instance.arrayNode().add(create));
        ObjectNode b2 = JsonNodeFactory.instance.objectNode().put("modelId", modelId).put("opBatchId", "b2").put("timestamp", "2026-01-01T00:00:01Z");
        b2.set("ops", JsonNodeFactory.instance.arrayNode().add(update));

        repository.appendOpLog(modelId, "b1", new RevisionRange(1, 1), b1);
        repository.applyToMaterializedState(modelId, b1);
        repository.appendOpLog(modelId, "b2", new RevisionRange(2, 2), b2);
        repository.applyToMaterializedState(modelId, b2);
        repository.updateHeadRevision(modelId, 20L); // drifted head; committed horizon remains 2

        long retain = 5L;
        var status = repository.compactMetadata(modelId, retain);
        Assertions.assertTrue(status.executed());
        Assertions.assertEquals(modelId, status.modelId());
        Assertions.assertEquals(20L, status.headRevision(), "admin status should reflect persisted head");
        Assertions.assertEquals(2L, status.committedHorizonRevision(), "admin status should reflect committed horizon");
        Assertions.assertEquals(0L, status.watermarkRevision(), "watermark is max(0, committed-retain)");
        Assertions.assertEquals(retain, status.retainRevisions());

        Assertions.assertTrue(status.headRevision() >= status.committedHorizonRevision(),
                "invariant: headRevision must be >= committedHorizonRevision");
        Assertions.assertTrue(status.committedHorizonRevision() >= status.watermarkRevision(),
                "invariant: committedHorizonRevision must be >= watermarkRevision");

        repository.close();
    }

    private static void assumeLocalInfraEnabled() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")),
                "Set RUN_LOCAL_INFRA_IT=true to run local Kafka/Neo4j integration tests.");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Properties consumerProps(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "archi-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }

    private static String readElementProperty(String uri, String username, String password, String modelId, String elementId, String key) {
        String query = "MATCH (m:Model {modelId: $modelId})-[:HAS_ELEMENT]->(e:Element {id: $elementId}) RETURN e[$key] AS value";
        try (var driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             var session = driver.session()) {
            var result = session.run(query, Map.of("modelId", modelId, "elementId", elementId, "key", key));
            if (!result.hasNext()) {
                return null;
            }
            return result.single().get("value").asString(null);
        }
    }

    private static String readViewObjectParentMembership(String uri, String username, String password, String modelId, String childViewObjectId) {
        String query = """
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(parent:ViewObject)-[:CHILD_MEMBER]->(child:ViewObject {id: $childId})
                RETURN parent.id AS parentId
                ORDER BY parent.id
                """;
        try (var driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             var session = driver.session()) {
            var result = session.run(query, Map.of("modelId", modelId, "childId", childViewObjectId));
            if (!result.hasNext()) {
                return null;
            }
            return result.single().get("parentId").asString(null);
        }
    }

    private static JsonNode canonicalizeSnapshot(JsonNode snapshot) {
        // Canonical ordering removes serialization noise so equality means semantic convergence.
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("folders", canonicalizeArrayById(snapshot.path("folders")));
        out.set("elements", canonicalizeArrayById(snapshot.path("elements")));
        out.set("relationships", canonicalizeArrayById(snapshot.path("relationships")));
        out.set("views", canonicalizeArrayById(snapshot.path("views")));
        out.set("elementFolderMembers", canonicalizeArrayById(snapshot.path("elementFolderMembers")));
        out.set("relationshipFolderMembers", canonicalizeArrayById(snapshot.path("relationshipFolderMembers")));
        out.set("viewFolderMembers", canonicalizeArrayById(snapshot.path("viewFolderMembers")));
        out.set("viewObjects", canonicalizeArrayById(snapshot.path("viewObjects")));
        out.set("viewObjectChildMembers", canonicalizeArrayById(snapshot.path("viewObjectChildMembers")));
        out.set("connections", canonicalizeArrayById(snapshot.path("connections")));
        return out;
    }

    private static JsonNode findById(JsonNode array, String id) {
        return findByField(array, "id", id);
    }

    private static JsonNode findByField(JsonNode array, String field, String value) {
        if (array == null || !array.isArray()) {
            return null;
        }
        for (JsonNode node : array) {
            if (value.equals(node.path(field).asText())) {
                return node;
            }
        }
        return null;
    }

    private static String canonicalSnapshotJson(JsonNode snapshot) {
        try {
            return MAPPER.writeValueAsString(canonicalizeSnapshot(snapshot));
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize canonical snapshot", e);
        }
    }

    private static ArrayNode canonicalizeArrayById(JsonNode arrayNode) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (arrayNode == null || !arrayNode.isArray()) {
            return out;
        }
        ArrayList<JsonNode> items = new ArrayList<>();
        arrayNode.forEach(items::add);
        // Stable sort by id then payload text to keep deterministic tie-breaking for assertions.
        items.sort(Comparator
                .comparing((JsonNode n) -> n.path("id").asText(""))
                .thenComparing(JsonNode::toString));
        for (JsonNode item : items) {
            out.add(canonicalizeNode(item));
        }
        return out;
    }

    private static JsonNode canonicalizeNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> out.add(canonicalizeNode(child)));
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            ArrayList<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            // Sort field names so object encoding does not depend on insertion order.
            fields.sort(String::compareTo);
            for (String field : fields) {
                out.set(field, canonicalizeNode(node.path(field)));
            }
            return out;
        }
        return node.deepCopy();
    }

}
