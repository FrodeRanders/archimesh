package org.gautelis.archimesh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gautelis.archimesh.model.AdminCompactionStatus;
import org.gautelis.archimesh.model.AdminActiveSession;
import org.gautelis.archimesh.model.AdminModelExport;
import org.gautelis.archimesh.model.AdminModelImportResult;
import org.gautelis.archimesh.model.Actor;
import org.gautelis.archimesh.model.ModelAccessControl;
import org.gautelis.archimesh.model.ModelCatalogEntry;
import org.gautelis.archimesh.model.ModelTagExportEntry;
import org.gautelis.archimesh.model.ModelTagEntry;
import org.gautelis.archimesh.model.RevisionRange;
import org.gautelis.archimesh.service.impl.InMemoryIdempotencyService;
import org.gautelis.archimesh.service.impl.InMemoryLockService;
import org.gautelis.archimesh.service.impl.InMemoryRevisionService;
import org.gautelis.archimesh.service.impl.InMemoryValidationService;
import org.gautelis.archimesh.wire.ServerEnvelope;
import org.gautelis.archimesh.wire.inbound.*;
import org.gautelis.archimesh.wire.outbound.CheckoutDeltaMessage;
import org.gautelis.archimesh.wire.outbound.CheckoutSnapshotMessage;
import org.gautelis.archimesh.wire.outbound.ErrorMessage;
import org.gautelis.archimesh.wire.outbound.OpsAcceptedMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

class ArchimeshServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectionIsCorrelatedAndSentOnlyToSubmittingSession() {
        ArchimeshService service = baseService();
        var neo = (RecordingNeo4jRepository) service.neo4jRepository;
        neo.elementExists = false;
        var properties = new java.util.HashMap<String, Object>();
        var session = (jakarta.websocket.Session) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{jakarta.websocket.Session.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> "submitting-session";
                    case "getUserProperties" -> properties;
                    default -> null;
                });
        service.onSubmitOps("demo", session, new SubmitOpsMessage(0, "rejected-batch", null,
                singleUpdateElementNameOp("elem:missing", "name")));
        var sessions = (RecordingSessionRegistry) service.sessionRegistry;
        Assertions.assertTrue(sessions.broadcasts.isEmpty());
        var error = (ErrorMessage) sessions.sends.getLast().payload();
        Assertions.assertEquals("rejected-batch", error.opBatchId());
        Assertions.assertEquals("PRECONDITION_FAILED", error.code());
    }

    @Test
    void durableAcceptanceIsCheckedBeforeStateDependentValidationAfterRestart() {
        ArchimeshService service = baseService();
        var neo = (RecordingNeo4jRepository) service.neo4jRepository;
        var message = new SubmitOpsMessage(0, "accepted-before-restart", null,
                singleUpdateElementNameOp("elem:e1", "updated"));
        service.onSubmitOps("demo", message);
        service.idempotencyService = new InMemoryIdempotencyService();
        neo.elementExists = false;
        service.onSubmitOps("demo", message);
        Assertions.assertEquals(1, neo.appendCount);
        var accepted = acceptedPayloads(((RecordingSessionRegistry) service.sessionRegistry).broadcasts);
        Assertions.assertEquals(2, accepted.size());
        Assertions.assertEquals(accepted.get(0).assignedRevisionRange(), accepted.get(1).assignedRevisionRange());
    }

    @Test
    void retryAfterFailedPersistenceCommitsInsteadOfAcknowledgingLostWork() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        neo.appendFailure = new IllegalStateException("database unavailable");
        var message = new SubmitOpsMessage(0, "retry-after-failure", null, singleCreateElementOp());
        Assertions.assertThrows(IllegalStateException.class, () -> service.onSubmitOps("demo", message));
        Assertions.assertTrue(sessions.broadcasts.isEmpty());
        neo.appendFailure = null;
        service.onSubmitOps("demo", message);
        Assertions.assertEquals(2, neo.appendCount);
        Assertions.assertEquals(1, neo.applyCount);
        Assertions.assertEquals(new RevisionRange(1, 1), acceptedPayloads(sessions.broadcasts).getFirst().assignedRevisionRange());
    }

    @Test
    void rebuildRejectsIncompleteHistoryBeforeClearingProjection() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        neo.readLatestCommitRevisionValue = 3;
        ObjectNode batch = objectMapper.createObjectNode();
        batch.set("assignedRevisionRange", objectMapper.createObjectNode().put("from", 3).put("to", 3));
        batch.set("ops", singleCreateElementOp());
        neo.opBatchesToReturn = objectMapper.createArrayNode().add(batch);
        Assertions.assertThrows(IllegalStateException.class, () -> service.rebuildMaterializedState("demo"));
        Assertions.assertEquals(0, neo.clearMaterializedStateCount);
    }

    @Test
    void nonemptyButIncompleteDeltaFallsBackToSnapshot() {
        ArchimeshService service = baseService();
        service.revisionService = new FixedRevisionService(3);
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        ObjectNode batch = objectMapper.createObjectNode();
        batch.set("assignedRevisionRange", objectMapper.createObjectNode().put("from", 3).put("to", 3));
        batch.set("ops", singleCreateElementOp());
        neo.opBatchesToReturn = objectMapper.createArrayNode().add(batch);
        service.onJoin("demo", null, new JoinMessage(0L, null, null));
        var sessions = (RecordingSessionRegistry) service.sessionRegistry;
        Assertions.assertEquals("CheckoutSnapshot", sessions.sends.getLast().type());
    }

    @Test
    void submitOpsAssignsRangeAndBroadcasts() {
        ArchimeshService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        SubmitOpsMessage message = new SubmitOpsMessage(0, "11111111-1111-1111-1111-111111111111", null, singleCreateElementOp());
        service.onSubmitOps("demo", message);

        Assertions.assertEquals(1, neo.appendCount);
        Assertions.assertEquals(1, neo.applyCount);
        Assertions.assertEquals(1, neo.updateHeadCount);
        Assertions.assertEquals(0, neo.readLatestCommitRevisionCount);
        Assertions.assertEquals(0, neo.isMaterializedStateConsistentCount);
        Assertions.assertEquals(1, sessions.broadcasts.size());
    }

    @Test
    void duplicateBatchIsDedupedByOpBatchId() {
        ArchimeshService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        SubmitOpsMessage message = new SubmitOpsMessage(0, "22222222-2222-2222-2222-222222222222", null, singleCreateElementOp());
        service.onSubmitOps("demo", message);
        service.onSubmitOps("demo", message);

        Assertions.assertEquals(1, neo.appendCount, "dedupe should skip second persistence");
        Assertions.assertEquals(1, neo.applyCount, "dedupe should skip second materialization");
        Assertions.assertEquals(1, neo.updateHeadCount, "dedupe should skip second head update");

        long acceptedMessages = sessions.broadcasts.stream().filter(m -> "OpsAccepted".equals(m.type())).count();
        long broadcastMessages = sessions.broadcasts.stream().filter(m -> "OpsBroadcast".equals(m.type())).count();
        Assertions.assertEquals(2, acceptedMessages, "duplicate still gets acceptance echo");
        Assertions.assertEquals(0, broadcastMessages, "ops broadcast is produced by Kafka consumer fan-out");
    }

    @Test
    void lockAndPresenceArePublishedToKafkaWithoutDirectBroadcast() {
        ArchimeshService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingKafkaPublisher kafka = (RecordingKafkaPublisher) service.kafkaPublisher;

        service.onAcquireLock("demo", new AcquireLockMessage(null, List.of("vo:o1"), 10_000L));
        service.onReleaseLock("demo", new ReleaseLockMessage(null, List.of("vo:o1")));
        service.onPresence("demo", new PresenceMessage(null, "view:v1", List.of("vo:o1"), null));

        Assertions.assertEquals(2, kafka.lockEventsPublished);
        Assertions.assertEquals(1, kafka.presenceEventsPublished);
        Assertions.assertEquals(0, sessions.broadcasts.size(), "fan-out is Kafka consumer responsibility");
    }

    @Test
    void lockAndPresenceRejectUnknownModel() {
        ArchimeshService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingKafkaPublisher kafka = (RecordingKafkaPublisher) service.kafkaPublisher;

        service.onAcquireLock("missing", new AcquireLockMessage(null, List.of("vo:o1"), 10_000L));
        service.onPresence("missing", new PresenceMessage(null, "view:v1", List.of("vo:o1"), null));

        Assertions.assertEquals(0, kafka.lockEventsPublished);
        Assertions.assertEquals(0, kafka.presenceEventsPublished);
        Assertions.assertEquals(2, sessions.broadcasts.size());
        for (ServerEnvelope envelope : sessions.broadcasts) {
            Assertions.assertEquals("Error", envelope.type());
            Assertions.assertInstanceOf(ErrorMessage.class, envelope.payload());
            ErrorMessage error = (ErrorMessage) envelope.payload();
            Assertions.assertEquals("MODEL_NOT_FOUND", error.code());
        }
    }

    @Test
    void joinWithoutLastSeenSendsSnapshot() {
        ArchimeshService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        service.onJoin("demo", null, new JoinMessage(null, null, null));

        Assertions.assertEquals(1, sessions.sends.size());
        Assertions.assertEquals("CheckoutSnapshot", sessions.sends.getFirst().type());
    }

    @Test
    void joinUnknownModelReturnsModelNotFoundAndDoesNotRegisterSession() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        service.onJoin("missing", null, new JoinMessage(null, null, null));

        Assertions.assertEquals(1, sessions.broadcasts.size());
        Assertions.assertEquals("Error", sessions.broadcasts.getFirst().type());
        Assertions.assertInstanceOf(ErrorMessage.class, sessions.broadcasts.getFirst().payload());
        ErrorMessage error = (ErrorMessage) sessions.broadcasts.getFirst().payload();
        Assertions.assertEquals("MODEL_NOT_FOUND", error.code());
        Assertions.assertEquals(0, sessions.sessionCount("missing"));
        Assertions.assertEquals(1, neo.accessControlChecks.getOrDefault("missing", 0));
    }

    @Test
    void registeredModelChecksAreCachedForKnownModelsOnly() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        service.onJoin("demo", null, new JoinMessage(null, null, null));
        service.onSubmitOps("demo", new SubmitOpsMessage(0, "eeeeeeee-0000-0000-0000-000000000000", null, singleCreateElementOp()));
        service.onJoin("missing", null, new JoinMessage(null, null, null));
        service.onJoin("missing", null, new JoinMessage(null, null, null));

        Assertions.assertEquals(1, neo.accessControlChecks.getOrDefault("demo", 0));
        Assertions.assertEquals(2, neo.accessControlChecks.getOrDefault("missing", 0));
    }

    @Test
    void deletingModelFlushesRegisteredModelCache() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        service.onJoin("demo", null, new JoinMessage(null, null, null));
        Assertions.assertEquals(1, neo.accessControlChecks.getOrDefault("demo", 0));

        service.deleteModel("demo", true);
        neo.modelNames.put("demo", "Demo");

        service.onJoin("demo", null, new JoinMessage(null, null, null));

        Assertions.assertEquals(2, neo.accessControlChecks.getOrDefault("demo", 0));
    }

    @Test
    void exportModelIncludesSnapshotOpsAndTags() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("modelId", "demo");
        snapshot.put("headRevision", 2);
        snapshot.set("elements", objectMapper.createArrayNode());
        snapshot.set("relationships", objectMapper.createArrayNode());
        snapshot.set("views", objectMapper.createArrayNode());
        snapshot.set("viewObjects", objectMapper.createArrayNode());
        snapshot.set("viewObjectChildMembers", objectMapper.createArrayNode());
        snapshot.set("connections", objectMapper.createArrayNode());
        neo.snapshotToReturn = snapshot;
        neo.readHeadRevisionValue = 2L;

        ArrayNode opBatches = objectMapper.createArrayNode();
        ObjectNode exportedBatch = objectMapper.createObjectNode();
        exportedBatch.put("opBatchId", "b1");
        exportedBatch.set("assignedRevisionRange", objectMapper.createObjectNode().put("from", 1).put("to", 2));
        exportedBatch.set("ops", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("type","CreateElement"))
                .add(objectMapper.createObjectNode().put("type","UpdateElement")));
        opBatches.add(exportedBatch);
        neo.opBatchesToReturn = opBatches;
        service.createModelTag("demo", "release-1", "Release 1");

        AdminModelExport export = service.exportModel("demo");

        Assertions.assertEquals("archi-model-export-v1", export.format());
        Assertions.assertEquals("demo", export.model().modelId());
        Assertions.assertEquals(2L, export.model().headRevision());
        Assertions.assertEquals(1, export.opBatches().size());
        Assertions.assertEquals(1, export.tags().size());
        Assertions.assertEquals("release-1", export.tags().getFirst().tagName());
    }

    @Test
    void importModelRehydratesHistoryAndTags() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ArrayNode exportedBatches = objectMapper.createArrayNode();
        ObjectNode importedBatch = objectMapper.createObjectNode();
        importedBatch.put("modelId", "imported");
        importedBatch.put("opBatchId", "b1");
        importedBatch.put("baseRevision", 0);
        importedBatch.put("timestamp", "2026-03-08T10:00:00Z");
        importedBatch.set("assignedRevisionRange", objectMapper.createObjectNode().put("from", 1).put("to", 1));
        importedBatch.set("ops", singleCreateElementOp());
        exportedBatches.add(importedBatch);
        ObjectNode importedSnapshot = objectMapper.createObjectNode();
        importedSnapshot.put("modelId", "imported");
        importedSnapshot.put("headRevision", 1);
        importedSnapshot.set("elements", objectMapper.createArrayNode());
        importedSnapshot.set("relationships", objectMapper.createArrayNode());
        importedSnapshot.set("views", objectMapper.createArrayNode());
        importedSnapshot.set("viewObjects", objectMapper.createArrayNode());
        importedSnapshot.set("viewObjectChildMembers", objectMapper.createArrayNode());
        importedSnapshot.set("connections", objectMapper.createArrayNode());

        AdminModelExport export = new AdminModelExport(
                "archi-model-export-v1",
                "2026-03-08T10:05:00Z",
                new ModelCatalogEntry("imported", "Imported Model", 1L),
                new ModelAccessControl("imported", Set.of("owner-user"), Set.of("writer-user"), Set.of("reader-user")),
                importedSnapshot,
                exportedBatches,
                List.of(new ModelTagExportEntry("imported", "release-1", "Release", 1L, "2026-03-08T10:01:00Z", importedSnapshot)));

        AdminModelImportResult result = service.importModel(export, false);

        Assertions.assertEquals("imported", result.modelId());
        Assertions.assertFalse(result.overwritten());
        Assertions.assertEquals(1L, result.headRevision());
        Assertions.assertEquals(1, neo.appendCount);
        Assertions.assertEquals(1, neo.applyCount);
        Assertions.assertEquals(1, neo.restoreModelTagCount);
        Assertions.assertEquals(1, neo.modelTags.getOrDefault("imported", java.util.Map.of()).size());
        Assertions.assertEquals("Imported Model", neo.modelNames.get("imported"));
        Assertions.assertEquals(Set.of("owner-user"), neo.accessControls.get("imported").adminUsers());
    }

    @Test
    void importModelRejectsExistingModelWithoutOverwrite() {
        ArchimeshService service = baseService();

        AdminModelExport export = new AdminModelExport(
                "archi-model-export-v1",
                "2026-03-08T10:05:00Z",
                new ModelCatalogEntry("demo", "Demo", 0L),
                null,
                objectMapper.createObjectNode(),
                objectMapper.createArrayNode(),
                List.of());

        IllegalStateException error = Assertions.assertThrows(IllegalStateException.class,
                () -> service.importModel(export, false));
        Assertions.assertTrue(error.getMessage().contains("overwrite=true"));
    }

    @Test
    void registerModelSeedsCreatorAccessControl() {
        ArchimeshService service = baseService();

        service.registerModel("demo", "Demo", "owner-user");

        ModelAccessControl accessControl = service.getModelAccessControl("demo");
        Assertions.assertEquals(Set.of("owner-user"), accessControl.adminUsers());
        Assertions.assertEquals(Set.of("owner-user"), accessControl.writerUsers());
        Assertions.assertEquals(Set.of("owner-user"), accessControl.readerUsers());
    }

    @Test
    void joinWithOlderRevisionSendsCheckoutDeltaWhenAvailable() {
        ArchimeshService service = baseService();
        service.revisionService = new FixedRevisionService(5);
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ArrayNode batches = objectMapper.createArrayNode();
        batches.add(objectMapper.createObjectNode()
                .put("opBatchId", "b-4")
                .set("assignedRevisionRange", objectMapper.createObjectNode().put("from", 4).put("to", 4)));
        batches.add(objectMapper.createObjectNode()
                .put("opBatchId", "b-5")
                .set("assignedRevisionRange", objectMapper.createObjectNode().put("from", 5).put("to", 5)));
        for (JsonNode batch : batches) ((ObjectNode) batch).set("ops", objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("type", "UpdateElement")));
        neo.opBatchesToReturn = batches;

        service.onJoin("demo", null, new JoinMessage(3L, null, null));

        Assertions.assertEquals(1, sessions.sends.size());
        Assertions.assertEquals("CheckoutDelta", sessions.sends.getFirst().type());
        Assertions.assertInstanceOf(CheckoutDeltaMessage.class, sessions.sends.getFirst().payload());
        CheckoutDeltaMessage payload = (CheckoutDeltaMessage) sessions.sends.getFirst().payload();
        Assertions.assertEquals(4L, payload.fromRevision());
        Assertions.assertEquals(5L, payload.toRevision());
        Assertions.assertEquals(2, payload.opBatches().size());
    }

    @Test
    void joinWithOlderRevisionFallsBackToSnapshotWhenDeltaMissing() {
        ArchimeshService service = baseService();
        service.revisionService = new FixedRevisionService(5);
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("headRevision", 5);
        neo.snapshotToReturn = snapshot;
        neo.opBatchesToReturn = objectMapper.createArrayNode();

        service.onJoin("demo", null, new JoinMessage(3L, null, null));

        Assertions.assertEquals(1, sessions.sends.size());
        Assertions.assertEquals("CheckoutSnapshot", sessions.sends.getFirst().type());
        Assertions.assertInstanceOf(CheckoutSnapshotMessage.class, sessions.sends.getFirst().payload());
        CheckoutSnapshotMessage payload = (CheckoutSnapshotMessage) sessions.sends.getFirst().payload();
        Assertions.assertEquals(5L, payload.headRevision());
    }

    @Test
    void joinAtHeadSendsEmptyDeltaWindow() {
        ArchimeshService service = baseService();
        service.revisionService = new FixedRevisionService(5);
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        service.onJoin("demo", null, new JoinMessage(5L, null, null));

        Assertions.assertEquals(1, sessions.sends.size());
        Assertions.assertEquals("CheckoutDelta", sessions.sends.getFirst().type());
        Assertions.assertInstanceOf(CheckoutDeltaMessage.class, sessions.sends.getFirst().payload());
        CheckoutDeltaMessage payload = (CheckoutDeltaMessage) sessions.sends.getFirst().payload();
        Assertions.assertEquals(6L, payload.fromRevision());
        Assertions.assertEquals(5L, payload.toRevision());
        Assertions.assertTrue(payload.opBatches().isArray());
        Assertions.assertTrue(payload.opBatches().isEmpty());
    }

    @Test
    void joinWithLastSeenAheadOfHeadFallsBackToSnapshotAtCurrentHead() {
        ArchimeshService service = baseService();
        service.revisionService = new FixedRevisionService(5);
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("headRevision", 5);
        snapshot.put("modelId", "demo");
        neo.snapshotToReturn = snapshot;

        service.onJoin("demo", null, new JoinMessage(99L, null, null));

        Assertions.assertEquals(1, sessions.sends.size());
        Assertions.assertEquals("CheckoutSnapshot", sessions.sends.getFirst().type());
        Assertions.assertInstanceOf(CheckoutSnapshotMessage.class, sessions.sends.getFirst().payload());
        CheckoutSnapshotMessage payload = (CheckoutSnapshotMessage) sessions.sends.getFirst().payload();
        Assertions.assertEquals(5L, payload.headRevision(), "snapshot fallback must use current head revision");
        Assertions.assertEquals("demo", payload.snapshot().path("modelId").asText());
    }

    @Test
    void preconditionFailureBroadcastsErrorAndSkipsPersistence() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        neo.viewExists = false;
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.set("views", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("id", "view:v1"))
                .add(objectMapper.createObjectNode().put("id", "view:v2")));
        snapshot.set("elements", objectMapper.createArrayNode());
        snapshot.set("relationships", objectMapper.createArrayNode());
        snapshot.set("viewObjects", objectMapper.createArrayNode());
        snapshot.set("connections", objectMapper.createArrayNode());
        neo.snapshotToReturn = snapshot;

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode viewObject = objectMapper.createObjectNode();
        viewObject.put("id", "vo:1");
        viewObject.put("viewId", "view:missing");
        viewObject.put("representsId", "elem:e1");
        viewObject.set("notationJson", objectMapper.createObjectNode());
        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "CreateViewObject");
        op.set("viewObject", viewObject);
        ops.add(op);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "33333333-3333-3333-3333-333333333333", null, ops));

        Assertions.assertEquals(0, neo.appendCount);
        Assertions.assertFalse(sessions.broadcasts.isEmpty());
        Assertions.assertEquals("Error", sessions.broadcasts.getFirst().type());
    }

    @Test
    void submitOpsPropagatesRepositoryWriteFailures() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        neo.appendFailure = new IllegalStateException("append failed");

        IllegalStateException thrown = Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.onSubmitOps(
                        "demo",
                        new SubmitOpsMessage(0, "aaaaaaaa-0000-0000-0000-000000000000", null, singleCreateElementOp())));

        Assertions.assertEquals("append failed", thrown.getMessage());
        Assertions.assertEquals(1, neo.appendCount);
        Assertions.assertEquals(0, neo.applyCount);
        Assertions.assertEquals(0, neo.updateHeadCount);
    }

    @Test
    void submitOpsRejectsUnknownModelAndSkipsPersistence() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        service.onSubmitOps("missing", new SubmitOpsMessage(0, "dddddddd-0000-0000-0000-000000000000", null, singleCreateElementOp()));

        Assertions.assertEquals(0, neo.appendCount);
        Assertions.assertEquals(0, neo.applyCount);
        Assertions.assertEquals(0, neo.updateHeadCount);
        Assertions.assertEquals(1, sessions.broadcasts.size());
        Assertions.assertEquals("Error", sessions.broadcasts.getFirst().type());
        Assertions.assertInstanceOf(ErrorMessage.class, sessions.broadcasts.getFirst().payload());
        ErrorMessage error = (ErrorMessage) sessions.broadcasts.getFirst().payload();
        Assertions.assertEquals("MODEL_NOT_FOUND", error.code());
        Assertions.assertEquals(1, neo.modelRegisteredChecks.getOrDefault("missing", 0));
    }

    @Test
    void submitOpsPropagatesMaterializedStateWriteFailures() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        neo.applyFailure = new IllegalStateException("apply failed");

        IllegalStateException thrown = Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.onSubmitOps(
                        "demo",
                        new SubmitOpsMessage(0, "bbbbbbbb-0000-0000-0000-000000000000", null, singleCreateElementOp())));

        Assertions.assertEquals("apply failed", thrown.getMessage());
        Assertions.assertEquals(1, neo.appendCount);
        Assertions.assertEquals(1, neo.applyCount);
        Assertions.assertEquals(0, neo.updateHeadCount);
    }

    @Test
    void createFolderRequiresExistingParentAndSkipsPersistence() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        neo.folderExists = false;

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "folder-parent-missing", null,
                singleCreateFolderOp("folder:f1", "folder:missing")));

        Assertions.assertEquals(0, neo.appendCount);
        Assertions.assertFalse(sessions.broadcasts.isEmpty());
        Assertions.assertEquals("Error", sessions.broadcasts.getFirst().type());
    }

    @Test
    void deleteRootFolderIsRejected() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "delete-root-folder", null,
                singleDeleteFolderOp("folder:root-business")));

        Assertions.assertEquals(0, neo.appendCount);
        Assertions.assertFalse(sessions.broadcasts.isEmpty());
        Assertions.assertEquals("Error", sessions.broadcasts.getFirst().type());
    }

    @Test
    void moveFolderAcrossRootsIsRejected() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        neo.folderRootIds.put("folder:f1", "folder:root-business");
        neo.folderRootIds.put("folder:root-other", "folder:root-other");

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "MoveFolder");
        op.put("folderId", "folder:f1");
        op.put("parentFolderId", "folder:root-other");
        ops.add(op);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "move-cross-root-folder", null, ops));

        Assertions.assertEquals(0, neo.appendCount);
        Assertions.assertFalse(sessions.broadcasts.isEmpty());
        Assertions.assertEquals("Error", sessions.broadcasts.getFirst().type());
        Assertions.assertInstanceOf(ErrorMessage.class, sessions.broadcasts.getFirst().payload());
        ErrorMessage error = (ErrorMessage) sessions.broadcasts.getFirst().payload();
        Assertions.assertEquals("PRECONDITION_FAILED", error.code());
        Assertions.assertTrue(error.message().contains("cannot cross root folders"));
    }

    @Test
    void submitOpsPropagatesHeadRevisionWriteFailures() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        neo.updateHeadFailure = new IllegalStateException("update head failed");

        IllegalStateException thrown = Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.onSubmitOps(
                        "demo",
                        new SubmitOpsMessage(0, "cccccccc-0000-0000-0000-000000000000", null, singleCreateElementOp())));

        Assertions.assertEquals("update head failed", thrown.getMessage());
        Assertions.assertEquals(1, neo.appendCount);
        Assertions.assertEquals(1, neo.applyCount);
        Assertions.assertEquals(1, neo.updateHeadCount);
    }

    @Test
    void updateViewObjectOpaqueRejectsUnknownNotationFields() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode update = objectMapper.createObjectNode();
        update.put("type", "UpdateViewObjectOpaque");
        update.put("viewId", "view:v1");
        update.put("viewObjectId", "vo:o1");
        update.set("notationJson", objectMapper.createObjectNode().put("unsupportedStyleKey", "x"));
        ops.add(update);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", null, ops));

        Assertions.assertEquals(0, neo.appendCount);
        Assertions.assertFalse(sessions.broadcasts.isEmpty());
        Assertions.assertEquals("Error", sessions.broadcasts.getFirst().type());
        Assertions.assertInstanceOf(ErrorMessage.class, sessions.broadcasts.getFirst().payload());
        ErrorMessage error = (ErrorMessage) sessions.broadcasts.getFirst().payload();
        Assertions.assertEquals("PRECONDITION_FAILED", error.code());
        Assertions.assertTrue(error.message().contains("unsupportedStyleKey"));
    }

    @Test
    void createViewObjectRejectsUnknownNotationFields() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode viewObject = objectMapper.createObjectNode();
        viewObject.put("id", "vo:o1");
        viewObject.put("viewId", "view:v1");
        viewObject.put("representsId", "elem:e1");
        viewObject.set("notationJson", objectMapper.createObjectNode().put("unsupportedStyleKey", 1));
        ObjectNode create = objectMapper.createObjectNode();
        create.put("type", "CreateViewObject");
        create.set("viewObject", viewObject);
        ops.add(create);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "f1f1f1f1-aaaa-bbbb-cccc-dddddddddddd", null, ops));

        Assertions.assertEquals(0, neo.appendCount);
        Assertions.assertFalse(sessions.broadcasts.isEmpty());
        Assertions.assertEquals("Error", sessions.broadcasts.getFirst().type());
        Assertions.assertInstanceOf(ErrorMessage.class, sessions.broadcasts.getFirst().payload());
        ErrorMessage error = (ErrorMessage) sessions.broadcasts.getFirst().payload();
        Assertions.assertEquals("PRECONDITION_FAILED", error.code());
        Assertions.assertTrue(error.message().contains("unsupportedStyleKey"));
    }

    @Test
    void createViewObjectAcceptsAllWhitelistedNotationFields() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode viewObject = objectMapper.createObjectNode();
        viewObject.put("id", "vo:o1");
        viewObject.put("viewId", "view:v1");
        viewObject.put("representsId", "elem:e1");
        viewObject.set("notationJson", objectMapper.createObjectNode()
                .put("x", 10)
                .put("y", 20)
                .put("width", 120)
                .put("height", 55)
                .put("type", 1)
                .put("alpha", 80)
                .put("lineAlpha", 70)
                .put("lineWidth", 2)
                .put("lineStyle", 1)
                .put("textAlignment", 1)
                .put("textPosition", 2)
                .put("gradient", 0)
                .put("iconVisibleState", 1)
                .put("deriveElementLineColor", true)
                .put("fillColor", "#ffffff")
                .put("lineColor", "#000000")
                .put("font", "Arial")
                .put("fontColor", "#010203")
                .put("iconColor", "#040506")
                .put("imagePath", "/tmp/icon.png")
                .put("imagePosition", 0)
                .put("name", "VO")
                .put("documentation", "doc"));
        ObjectNode create = objectMapper.createObjectNode();
        create.put("type", "CreateViewObject");
        create.set("viewObject", viewObject);
        ops.add(create);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "f2f2f2f2-aaaa-bbbb-cccc-dddddddddddd", null, ops));

        Assertions.assertEquals(1, neo.appendCount);
        Assertions.assertTrue(sessions.broadcasts.stream().anyMatch(m -> "OpsAccepted".equals(m.type())));
    }

    @Test
    void createConnectionRejectsUnknownNotationFields() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode connection = objectMapper.createObjectNode();
        connection.put("id", "conn:c1");
        connection.put("viewId", "view:v1");
        connection.put("representsId", "rel:r1");
        connection.put("sourceViewObjectId", "vo:o1");
        connection.put("targetViewObjectId", "vo:o2");
        connection.set("notationJson", objectMapper.createObjectNode().put("unsupportedConnectionField", 1));
        ObjectNode create = objectMapper.createObjectNode();
        create.put("type", "CreateConnection");
        create.set("connection", connection);
        ops.add(create);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "bbbbbbbb-cccc-dddd-eeee-ffffffffffff", null, ops));

        Assertions.assertEquals(0, neo.appendCount);
        Assertions.assertFalse(sessions.broadcasts.isEmpty());
        Assertions.assertEquals("Error", sessions.broadcasts.getFirst().type());
        Assertions.assertInstanceOf(ErrorMessage.class, sessions.broadcasts.getFirst().payload());
        ErrorMessage error = (ErrorMessage) sessions.broadcasts.getFirst().payload();
        Assertions.assertEquals("PRECONDITION_FAILED", error.code());
        Assertions.assertTrue(error.message().contains("unsupportedConnectionField"));
    }

    @Test
    void createConnectionAcceptsWhitelistedNotationFields() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode connection = objectMapper.createObjectNode();
        connection.put("id", "conn:c1");
        connection.put("viewId", "view:v1");
        connection.put("representsId", "rel:r1");
        connection.put("sourceViewObjectId", "vo:o1");
        connection.put("targetViewObjectId", "vo:o2");
        connection.set("notationJson", objectMapper.createObjectNode()
                .put("type", 1)
                .put("nameVisible", true)
                .put("lineColor", "#aabbcc")
                .set("bendpoints", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("startX", 1)
                                .put("startY", 2)
                                .put("endX", 3)
                                .put("endY", 4))));
        ObjectNode create = objectMapper.createObjectNode();
        create.put("type", "CreateConnection");
        create.set("connection", connection);
        ops.add(create);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "cccccccc-dddd-eeee-ffff-111111111111", null, ops));

        Assertions.assertEquals(1, neo.appendCount);
        Assertions.assertTrue(sessions.broadcasts.stream().anyMatch(m -> "OpsAccepted".equals(m.type())));
    }

    @Test
    void updateViewObjectOpaqueAcceptsAllWhitelistedNotationFields() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode update = objectMapper.createObjectNode();
        update.put("type", "UpdateViewObjectOpaque");
        update.put("viewId", "view:v1");
        update.put("viewObjectId", "vo:o1");
        update.set("notationJson", objectMapper.createObjectNode()
                .put("x", 10)
                .put("y", 20)
                .put("width", 120)
                .put("height", 55)
                .put("type", 1)
                .put("alpha", 80)
                .put("lineAlpha", 70)
                .put("lineWidth", 2)
                .put("lineStyle", 1)
                .put("textAlignment", 1)
                .put("textPosition", 2)
                .put("gradient", 0)
                .put("iconVisibleState", 1)
                .put("deriveElementLineColor", true)
                .put("fillColor", "#ffffff")
                .put("lineColor", "#000000")
                .put("font", "Arial")
                .put("fontColor", "#010203")
                .put("iconColor", "#040506")
                .put("imagePath", "/tmp/icon.png")
                .put("imagePosition", 0)
                .put("name", "VO")
                .put("documentation", "doc"));
        ops.add(update);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "dddddddd-eeee-ffff-0000-111111111111", null, ops));

        Assertions.assertEquals(1, neo.appendCount);
        Assertions.assertTrue(sessions.broadcasts.stream().anyMatch(m -> "OpsAccepted".equals(m.type())));
    }

    @Test
    void updateConnectionOpaqueAcceptsAllWhitelistedNotationFields() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode update = objectMapper.createObjectNode();
        update.put("type", "UpdateConnectionOpaque");
        update.put("viewId", "view:v1");
        update.put("connectionId", "conn:c1");
        update.set("notationJson", objectMapper.createObjectNode()
                .put("type", 1)
                .put("nameVisible", true)
                .put("textAlignment", 1)
                .put("textPosition", 2)
                .put("lineWidth", 3)
                .put("name", "Conn")
                .put("lineColor", "#123456")
                .put("font", "Arial")
                .put("fontColor", "#654321")
                .put("documentation", "doc")
                .set("bendpoints", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("startX", 1)
                                .put("startY", 2)
                                .put("endX", 3)
                                .put("endY", 4))));
        ops.add(update);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "eeeeeeee-ffff-0000-1111-222222222222", null, ops));

        Assertions.assertEquals(1, neo.appendCount);
        Assertions.assertTrue(sessions.broadcasts.stream().anyMatch(m -> "OpsAccepted".equals(m.type())));
    }

    @Test
    void schemaNotationDefinitionsMatchServerWhitelistPolicy() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(Path.of("..", "schemas", "ops.json")));

        Set<String> viewObjectSchemaKeys = schemaNotationKeys(schema, "ViewObjectNotationJson");
        Set<String> connectionSchemaKeys = schemaNotationKeys(schema, "ConnectionNotationJson");

        Set<String> expectedViewObjectKeys = Set.of(
                "x", "y", "width", "height",
                "type", "alpha", "lineAlpha", "lineWidth", "lineStyle",
                "textAlignment", "textPosition", "gradient", "iconVisibleState",
                "deriveElementLineColor",
                "fillColor", "lineColor", "font", "fontColor", "iconColor",
                "imagePath", "imagePosition",
                "name", "documentation");
        Set<String> expectedConnectionKeys = Set.of(
                "type", "nameVisible", "textAlignment", "textPosition", "lineWidth",
                "name", "lineColor", "font", "fontColor", "documentation",
                "bendpoints");

        Assertions.assertEquals(expectedViewObjectKeys, viewObjectSchemaKeys,
                "ViewObjectNotationJson schema keys must match server notation whitelist policy");
        Assertions.assertEquals(expectedConnectionKeys, connectionSchemaKeys,
                "ConnectionNotationJson schema keys must match server notation whitelist policy");

        String createViewObjectNotationRef = schema.at("/definitions/CreateViewObject/properties/viewObject/properties/notationJson/$ref").asText();
        String updateViewObjectNotationRef = schema.at("/definitions/UpdateViewObjectOpaque/properties/notationJson/$ref").asText();
        String createConnectionNotationRef = schema.at("/definitions/CreateConnection/properties/connection/properties/notationJson/$ref").asText();
        String updateConnectionNotationRef = schema.at("/definitions/UpdateConnectionOpaque/properties/notationJson/$ref").asText();

        Assertions.assertEquals("#/definitions/ViewObjectNotationJson", createViewObjectNotationRef);
        Assertions.assertEquals("#/definitions/ViewObjectNotationJson", updateViewObjectNotationRef);
        Assertions.assertEquals("#/definitions/ConnectionNotationJson", createConnectionNotationRef);
        Assertions.assertEquals("#/definitions/ConnectionNotationJson", updateConnectionNotationRef);
    }

    @Test
    void deleteElementExpandsCascadeDeletes() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        neo.relationshipIdsByElement = List.of("rel:r1");
        neo.viewObjectIdsByRepresents.put("elem:e1", List.of("vo:o1"));
        neo.viewObjectIdsByRepresents.put("rel:r1", List.of("vo:o2"));
        neo.connectionIdsByViewObject.put("vo:o1", List.of("conn:c1"));
        neo.connectionIdsByViewObject.put("vo:o2", List.of("conn:c2"));
        neo.connectionIdsByRelationship.put("rel:r1", List.of("conn:c3"));

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode deleteElement = objectMapper.createObjectNode();
        deleteElement.put("type", "DeleteElement");
        deleteElement.put("elementId", "elem:e1");
        ops.add(deleteElement);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", null, ops));

        JsonNode persisted = neo.lastOpBatch.path("ops");
        List<String> persistedTypes = new ArrayList<>();
        for (JsonNode op : persisted) {
            persistedTypes.add(op.path("type").asText());
        }
        Assertions.assertEquals(7, persistedTypes.size());
        Assertions.assertEquals("DeleteElement", persistedTypes.getLast());
        Assertions.assertTrue(persistedTypes.contains("DeleteRelationship"));
        Assertions.assertEquals(3, persistedTypes.stream().filter("DeleteConnection"::equals).count());
        Assertions.assertEquals(2, persistedTypes.stream().filter("DeleteViewObject"::equals).count());
        long generatedCascade = 0;
        for (JsonNode op : persisted) {
            if ("CascadeDelete".equals(op.path("generatedBy").asText(null))) {
                generatedCascade++;
            }
        }
        Assertions.assertEquals(6, generatedCascade);
    }

    private Set<String> schemaNotationKeys(JsonNode schema, String definitionName) {
        JsonNode properties = schema.path("definitions").path(definitionName).path("properties");
        Set<String> keys = new TreeSet<>();
        properties.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Test
    void consistencyChecksRunWhenEnabled() {
        ArchimeshService service = baseService();
        service.consistencyChecksEnabled = true;
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        SubmitOpsMessage message = new SubmitOpsMessage(0, "44444444-4444-4444-4444-444444444444", null, singleCreateElementOp());
        service.onSubmitOps("demo", message);

        Assertions.assertEquals(1, neo.readLatestCommitRevisionCount);
        Assertions.assertEquals(1, neo.isMaterializedStateConsistentCount);
    }

    @Test
    void consistencyStatusReflectsAlignmentAndConsistency() {
        ArchimeshService service = baseService();
        service.revisionService = new FixedRevisionService(7);
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        neo.readHeadRevisionValue = 7;
        neo.readLatestCommitRevisionValue = 7;
        neo.materializedStateConsistent = true;

        var status = service.getConsistencyStatus("demo");
        Assertions.assertEquals("demo", status.modelId());
        Assertions.assertEquals(7, status.inMemoryHeadRevision());
        Assertions.assertEquals(7, status.persistedHeadRevision());
        Assertions.assertEquals(7, status.latestCommitRevision());
        Assertions.assertTrue(status.materializedStateConsistent());
        Assertions.assertTrue(status.headAligned());
        Assertions.assertTrue(status.commitAligned());
        Assertions.assertTrue(status.consistent());
    }

    @Test
    void submitOpsAddsCausalMetadataWhenMissing() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        SubmitOpsMessage message = new SubmitOpsMessage(
                0,
                "55555555-5555-5555-5555-555555555555",
                new org.gautelis.archimesh.model.Actor("u1", "s1"),
                singleCreateElementOp());
        service.onSubmitOps("demo", message);

        JsonNode persistedOp = neo.lastOpBatch.path("ops").get(0);
        Assertions.assertEquals("s1", persistedOp.path("causal").path("clientId").asText());
        Assertions.assertEquals(1L, persistedOp.path("causal").path("lamport").asLong());
        Assertions.assertEquals("55555555-5555-5555-5555-555555555555:0", persistedOp.path("causal").path("opId").asText());
    }

    @Test
    void submitOpsKeepsProvidedCausalMetadata() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode element = objectMapper.createObjectNode();
        element.put("id", "elem:e2");
        element.put("archimateType", "BusinessActor");

        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "CreateElement");
        op.set("element", element);
        op.set("causal", objectMapper.createObjectNode()
                .put("clientId", "client-explicit")
                .put("lamport", 42L)
                .put("opId", "explicit-op"));
        ops.add(op);

        SubmitOpsMessage message = new SubmitOpsMessage(
                0,
                "66666666-6666-6666-6666-666666666666",
                new org.gautelis.archimesh.model.Actor("u1", "s1"),
                ops);
        service.onSubmitOps("demo", message);

        JsonNode persistedOp = neo.lastOpBatch.path("ops").get(0);
        Assertions.assertEquals("client-explicit", persistedOp.path("causal").path("clientId").asText());
        Assertions.assertEquals(42L, persistedOp.path("causal").path("lamport").asLong());
        Assertions.assertEquals("explicit-op", persistedOp.path("causal").path("opId").asText());
    }

    @Test
    void getSnapshotDelegatesToRepository() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("modelId", "demo");
        snapshot.put("headRevision", 12);
        neo.snapshotToReturn = snapshot;

        JsonNode result = service.getSnapshot("demo");
        Assertions.assertEquals("demo", result.path("modelId").asText());
        Assertions.assertEquals(1, neo.loadSnapshotCount);
    }

    @Test
    void createModelTagCapturesCurrentSnapshotAndLoadsByRef() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("modelId", "demo");
        snapshot.put("headRevision", 12);
        snapshot.put("marker", "head-snapshot");
        neo.snapshotToReturn = snapshot;

        ModelTagEntry tag = service.createModelTag("demo", "release-1", "Release 1");
        JsonNode tagged = service.getSnapshot("demo", "release-1");

        Assertions.assertEquals("release-1", tag.tagName());
        Assertions.assertEquals(12L, tag.revision());
        Assertions.assertEquals("head-snapshot", tagged.path("marker").asText());
        Assertions.assertEquals(1, neo.listModelTags("demo").size());
    }

    @Test
    void deleteModelTagIsDisabledByDefault() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("modelId", "demo");
        snapshot.put("headRevision", 12);
        neo.snapshotToReturn = snapshot;
        service.createModelTag("demo", "release-1", "Release 1");

        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class,
                () -> service.deleteModelTag("demo", "release-1"));
        Assertions.assertTrue(ex.getMessage().contains("disabled"));
        Assertions.assertEquals(1, neo.listModelTags("demo").size());
    }

    @Test
    void deleteModelTagCanBeExplicitlyEnabled() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        service.allowTagDelete = true;

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("modelId", "demo");
        snapshot.put("headRevision", 12);
        neo.snapshotToReturn = snapshot;
        service.createModelTag("demo", "release-1", "Release 1");

        service.deleteModelTag("demo", "release-1");

        Assertions.assertEquals(0, neo.listModelTags("demo").size());
    }

    @Test
    void submitOpsRejectsTaggedReferenceAsReadOnly() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("modelId", "demo");
        snapshot.put("headRevision", 7);
        snapshot.put("marker", "release-snapshot");
        neo.snapshotToReturn = snapshot;
        service.createModelTag("demo", "release-1", "Release 1");

        service.onJoin("demo", null, new JoinMessage(null, new Actor("u1", "tag-session"), "release-1"));
        service.onSubmitOps("demo", new SubmitOpsMessage(
                7,
                "tag-write-1",
                new Actor("u1", "tag-session"),
                singleCreateElementOp()));

        Assertions.assertEquals(0, neo.appendCount);
        Assertions.assertEquals(1, sessions.broadcasts.size());
        Assertions.assertEquals("Error", sessions.broadcasts.getFirst().type());
        Assertions.assertInstanceOf(ErrorMessage.class, sessions.broadcasts.getFirst().payload());
        ErrorMessage error = (ErrorMessage) sessions.broadcasts.getFirst().payload();
        Assertions.assertEquals("MODEL_REFERENCE_READ_ONLY", error.code());
    }

    @Test
    void rebuildMaterializedStateReplaysFromOpLog() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ArrayNode batches = objectMapper.createArrayNode();
        ObjectNode batch1 = objectMapper.createObjectNode();
        batch1.set("assignedRevisionRange", objectMapper.createObjectNode().put("from", 1).put("to", 1));
        batch1.set("ops", objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("type", "CreateElement")));
        ObjectNode batch2 = objectMapper.createObjectNode();
        batch2.set("assignedRevisionRange", objectMapper.createObjectNode().put("from", 2).put("to", 3));
        batch2.set("ops", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("type", "UpdateElement"))
                .add(objectMapper.createObjectNode().put("type", "CreateView")));
        batches.add(batch1);
        batches.add(batch2);

        neo.readLatestCommitRevisionValue = 3;
        neo.opBatchesToReturn = batches;
        neo.materializedStateConsistent = true;

        var status = service.rebuildMaterializedState("demo");

        Assertions.assertEquals("demo", status.modelId());
        Assertions.assertEquals(3, status.requestedToRevision());
        Assertions.assertEquals(3, status.rebuiltHeadRevision());
        Assertions.assertEquals(2, status.appliedBatchCount());
        Assertions.assertEquals(3, status.appliedOpCount());
        Assertions.assertTrue(status.consistent());

        Assertions.assertEquals(1, neo.clearMaterializedStateCount);
        Assertions.assertEquals(1, neo.loadOpBatchesCount);
        Assertions.assertEquals(2, neo.applyCount);
        Assertions.assertEquals(1, neo.updateHeadCount);
    }

    @Test
    void getAdminStatusAggregatesSnapshotAndConsistency() {
        ArchimeshService service = baseService();
        service.revisionService = new FixedRevisionService(9);
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("headRevision", 7);
        snapshot.set("elements", objectMapper.createArrayNode().add(objectMapper.createObjectNode()).add(objectMapper.createObjectNode()));
        snapshot.set("relationships", objectMapper.createArrayNode().add(objectMapper.createObjectNode()));
        snapshot.set("views", objectMapper.createArrayNode());
        snapshot.set("viewObjects", objectMapper.createArrayNode().add(objectMapper.createObjectNode()));
        snapshot.set("connections", objectMapper.createArrayNode().add(objectMapper.createObjectNode()).add(objectMapper.createObjectNode()).add(objectMapper.createObjectNode()));
        neo.snapshotToReturn = snapshot;
        neo.readHeadRevisionValue = 9;
        neo.readLatestCommitRevisionValue = 9;
        neo.materializedStateConsistent = true;

        var status = service.getAdminStatus("demo");

        Assertions.assertEquals("demo", status.modelId());
        Assertions.assertEquals(7, status.snapshotHeadRevision());
        Assertions.assertEquals(2, status.elementCount());
        Assertions.assertEquals(1, status.relationshipCount());
        Assertions.assertEquals(0, status.viewCount());
        Assertions.assertEquals(1, status.viewObjectCount());
        Assertions.assertEquals(3, status.connectionCount());
        Assertions.assertEquals("demo", status.consistency().modelId());
        Assertions.assertTrue(status.consistency().consistent());
    }

    @Test
    void rebuildAndGetAdminStatusReturnsBothSections() {
        ArchimeshService service = baseService();
        service.revisionService = new FixedRevisionService(1);
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ArrayNode batches = objectMapper.createArrayNode();
        ObjectNode batch = objectMapper.createObjectNode();
        batch.set("assignedRevisionRange", objectMapper.createObjectNode().put("from", 1).put("to", 1));
        batch.set("ops", objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("type", "CreateElement")));
        batches.add(batch);

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("headRevision", 1);
        snapshot.set("elements", objectMapper.createArrayNode().add(objectMapper.createObjectNode()));
        snapshot.set("relationships", objectMapper.createArrayNode());
        snapshot.set("views", objectMapper.createArrayNode());
        snapshot.set("viewObjects", objectMapper.createArrayNode());
        snapshot.set("connections", objectMapper.createArrayNode());

        neo.readLatestCommitRevisionValue = 1;
        neo.opBatchesToReturn = batches;
        neo.snapshotToReturn = snapshot;
        neo.materializedStateConsistent = true;
        neo.readHeadRevisionValue = 1;

        var result = service.rebuildAndGetAdminStatus("demo");

        Assertions.assertEquals(1, result.rebuild().rebuiltHeadRevision());
        Assertions.assertEquals(1, result.status().snapshotHeadRevision());
        Assertions.assertEquals(1, result.status().elementCount());
        Assertions.assertTrue(result.status().consistency().consistent());
    }

    @Test
    void getAdminModelWindowIncludesStatusActivityAndRecentOps() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        sessions.activeModelIds.add("demo");
        sessions.countByModel.put("demo", 2);
        seedActiveSessionDiagnostics(service, "demo", "ws-1", "user-a");
        seedActiveSessionDiagnostics(service, "demo", "ws-2", "user-b");
        service.onPresence("demo", new PresenceMessage(null, "view:v1", List.of(), null));

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("headRevision", 4);
        snapshot.set("elements", objectMapper.createArrayNode().add(objectMapper.createObjectNode()));
        snapshot.set("relationships", objectMapper.createArrayNode());
        snapshot.set("views", objectMapper.createArrayNode());
        snapshot.set("viewObjects", objectMapper.createArrayNode());
        snapshot.set("connections", objectMapper.createArrayNode());
        neo.snapshotToReturn = snapshot;
        neo.readHeadRevisionValue = 4;
        neo.readLatestCommitRevisionValue = 4;
        neo.materializedStateConsistent = true;

        ArrayNode batches = objectMapper.createArrayNode();
        batches.add(objectMapper.createObjectNode()
                .set("assignedRevisionRange", objectMapper.createObjectNode().put("from", 4).put("to", 4)));
        neo.opBatchesToReturn = batches;

        var window = service.getAdminModelWindow("demo", 10);
        Assertions.assertEquals("demo", window.modelId());
        Assertions.assertEquals(2, window.activeSessionCount());
        Assertions.assertFalse(window.accessSummary().aclConfigured());
        Assertions.assertEquals("open", window.accessSummary().mode());
        Assertions.assertEquals(4, window.status().snapshotHeadRevision());
        Assertions.assertFalse(window.recentActivity().isEmpty());
        Assertions.assertTrue(window.recentOpBatches().isArray());
    }

    @Test
    void getAdminModelWindowIncludesAclSummaryWhenConfigured() {
        ArchimeshService service = baseService();

        service.updateModelAccessControl("demo", Set.of("owner-user"), Set.of("writer-user"), Set.of("reader-user", "reader-two"));

        var window = service.getAdminModelWindow("demo", 10);
        Assertions.assertTrue(window.accessSummary().aclConfigured());
        Assertions.assertEquals("acl", window.accessSummary().mode());
        Assertions.assertEquals(1, window.accessSummary().adminUserCount());
        Assertions.assertEquals(1, window.accessSummary().writerUserCount());
        Assertions.assertEquals(2, window.accessSummary().readerUserCount());
    }

    @Test
    void getAdminOverviewReturnsKnownModels() {
        ArchimeshService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        sessions.activeModelIds.add("demo");
        sessions.countByModel.put("demo", 1);
        service.onDisconnect("alpha", null);
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("headRevision", 0);
        snapshot.set("elements", objectMapper.createArrayNode());
        snapshot.set("relationships", objectMapper.createArrayNode());
        snapshot.set("views", objectMapper.createArrayNode());
        snapshot.set("viewObjects", objectMapper.createArrayNode());
        snapshot.set("connections", objectMapper.createArrayNode());
        neo.snapshotToReturn = snapshot;

        var overview = service.getAdminOverview(5);
        Assertions.assertEquals(2, overview.size());
        Assertions.assertEquals("alpha", overview.get(0).modelId());
        Assertions.assertEquals("demo", overview.get(1).modelId());
    }

    @Test
    void getAdminIntegrityDetectsMissingReferences() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("headRevision", 1);
        ArrayNode elements = objectMapper.createArrayNode();
        elements.add(objectMapper.createObjectNode().put("id", "elem:e1"));
        ArrayNode relationships = objectMapper.createArrayNode();
        relationships.add(objectMapper.createObjectNode()
                .put("id", "rel:r1")
                .put("sourceId", "elem:e1")
                .put("targetId", "elem:missing"));
        ArrayNode views = objectMapper.createArrayNode();
        views.add(objectMapper.createObjectNode().put("id", "view:v1"));
        ArrayNode viewObjects = objectMapper.createArrayNode();
        viewObjects.add(objectMapper.createObjectNode()
                .put("id", "vo:o1")
                .put("viewId", "view:missing")
                .put("representsId", "elem:missing"));
        ArrayNode connections = objectMapper.createArrayNode();
        connections.add(objectMapper.createObjectNode()
                .put("id", "conn:c1")
                .put("sourceId", "vo:o1")
                .put("targetId", "vo:missing"));
        snapshot.set("elements", elements);
        snapshot.set("relationships", relationships);
        snapshot.set("views", views);
        snapshot.set("viewObjects", viewObjects);
        snapshot.set("connections", connections);
        neo.snapshotToReturn = snapshot;

        var report = service.getAdminIntegrity("demo");

        Assertions.assertEquals("demo", report.modelId());
        Assertions.assertFalse(report.ok());
        Assertions.assertEquals(1, report.missingRelationshipEndpointCount());
        Assertions.assertEquals(1, report.missingConnectionEndpointCount());
        Assertions.assertEquals(1, report.missingViewObjectReferenceCount());
        Assertions.assertEquals(1, report.missingViewContainerCount());
        Assertions.assertEquals(4, report.issueCount());
        Assertions.assertFalse(report.issues().getFirst().suggestedAction().isBlank());
    }

    @Test
    void submitOpsSeedsMissingViewForCreateViewObjectWhenModelHasNoViews() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        ObjectNode emptySnapshot = objectMapper.createObjectNode();
        emptySnapshot.set("views", objectMapper.createArrayNode());
        emptySnapshot.set("elements", objectMapper.createArrayNode());
        emptySnapshot.set("relationships", objectMapper.createArrayNode());
        emptySnapshot.set("viewObjects", objectMapper.createArrayNode());
        emptySnapshot.set("connections", objectMapper.createArrayNode());
        neo.snapshotToReturn = emptySnapshot;
        neo.viewExists = false;
        neo.elementExists = true;

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode viewObject = objectMapper.createObjectNode();
        viewObject.put("id", "vo:o1");
        viewObject.put("viewId", "view:local-default");
        viewObject.put("representsId", "elem:e1");
        viewObject.set("notationJson", objectMapper.createObjectNode());
        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "CreateViewObject");
        op.set("viewObject", viewObject);
        ops.add(op);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "77777777-7777-7777-7777-777777777777", null, ops));

        Assertions.assertEquals(1, neo.appendCount);
        Assertions.assertEquals("CreateView", neo.lastOpBatch.path("ops").get(0).path("type").asText());
        Assertions.assertEquals("CreateViewObject", neo.lastOpBatch.path("ops").get(1).path("type").asText());
        Assertions.assertTrue(sessions.broadcasts.stream().anyMatch(m -> "OpsAccepted".equals(m.type())));
    }

    @Test
    void deleteModelRejectsWhenSessionsActiveWithoutForce() {
        ArchimeshService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        sessions.countByModel.put("demo", 1);
        var result = service.deleteModel("demo", false);

        Assertions.assertFalse(result.deleted());
        Assertions.assertEquals(1, result.activeSessions());
        Assertions.assertEquals(0, neo.deleteModelCount);
    }

    @Test
    void deleteModelDeletesAndClearsCaches() {
        ArchimeshService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        sessions.countByModel.put("demo", 0);
        var result = service.deleteModel("demo", false);

        Assertions.assertTrue(result.deleted());
        Assertions.assertEquals(1, neo.deleteModelCount);
    }

    @Test
    void styleCountersTrackAcceptedAppliedAndRejected() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("headRevision", 0);
        snapshot.set("elements", objectMapper.createArrayNode());
        snapshot.set("relationships", objectMapper.createArrayNode());
        snapshot.set("views", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("id", "view:v1")));
        snapshot.set("viewObjects", objectMapper.createArrayNode());
        snapshot.set("connections", objectMapper.createArrayNode());
        neo.snapshotToReturn = snapshot;
        neo.viewExists = true;
        neo.viewObjectExists = true;

        ArrayNode styleOpsAccepted = objectMapper.createArrayNode();
        ObjectNode updateStyle = objectMapper.createObjectNode();
        updateStyle.put("type", "UpdateViewObjectOpaque");
        updateStyle.put("viewId", "view:v1");
        updateStyle.put("viewObjectId", "vo:o1");
        updateStyle.set("notationJson", objectMapper.createObjectNode().put("fillColor", "#ffeeaa"));
        styleOpsAccepted.add(updateStyle);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "88888888-8888-8888-8888-888888888888", null, styleOpsAccepted));

        neo.viewObjectExists = false;
        ArrayNode styleOpsRejected = objectMapper.createArrayNode();
        ObjectNode rejected = objectMapper.createObjectNode();
        rejected.put("type", "UpdateViewObjectOpaque");
        rejected.put("viewId", "view:v1");
        rejected.put("viewObjectId", "vo:missing");
        rejected.set("notationJson", objectMapper.createObjectNode().put("lineColor", "#000000"));
        styleOpsRejected.add(rejected);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "99999999-9999-9999-9999-999999999999", null, styleOpsRejected));

        var window = service.getAdminModelWindow("demo", 20);
        Assertions.assertEquals(2, window.styleCounters().received());
        Assertions.assertEquals(1, window.styleCounters().accepted());
        Assertions.assertEquals(1, window.styleCounters().applied());
        Assertions.assertEquals(1, window.styleCounters().rejected());
    }

    @Test
    void compactModelMetadataDelegatesToRepositoryAndReturnsStatus() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        service.defaultCompactionRetainRevisions = 321L;

        var status = service.compactModelMetadata("demo", null);

        Assertions.assertEquals(1, neo.compactMetadataCount);
        Assertions.assertEquals(321L, neo.lastCompactRetainRevisions);
        Assertions.assertEquals("demo", status.modelId());
        Assertions.assertEquals(50L, status.committedHorizonRevision());
        Assertions.assertEquals(2L, status.eligibleFieldClockCount());
        Assertions.assertTrue(status.executed());
    }

    @Test
    void staleJoinUsesDeltaAndDuplicateReplayKeepsStableAssignedRange() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        SubmitOpsMessage create = new SubmitOpsMessage(0, "dup-replay-1", null, singleCreateElementOp());
        service.onSubmitOps("demo", create);
        service.onSubmitOps("demo", create); // duplicate replay

        List<OpsAcceptedMessage> accepted = acceptedPayloads(sessions.broadcasts);
        Assertions.assertEquals(2, accepted.size(), "duplicate replay should still receive OpsAccepted");
        Assertions.assertEquals(1L, accepted.get(0).assignedRevisionRange().from());
        Assertions.assertEquals(1L, accepted.get(0).assignedRevisionRange().to());
        Assertions.assertEquals(accepted.get(0).assignedRevisionRange(), accepted.get(1).assignedRevisionRange(),
                "duplicate replay must return stable assigned revision range");
        Assertions.assertEquals(1, neo.appendCount, "duplicate replay must not append op-log twice");

        service.onJoin("demo", null, new JoinMessage(0L, null, null));
        Assertions.assertFalse(sessions.sends.isEmpty());
        ServerEnvelope checkout = sessions.sends.getLast();
        Assertions.assertEquals("CheckoutDelta", checkout.type());
        Assertions.assertInstanceOf(CheckoutDeltaMessage.class, checkout.payload());
        CheckoutDeltaMessage delta = (CheckoutDeltaMessage) checkout.payload();
        Assertions.assertEquals(1L, delta.fromRevision());
        Assertions.assertEquals(1L, delta.toRevision());
        Assertions.assertEquals(1, delta.opBatches().size(), "delta should expose only first accepted commit once");
        Assertions.assertEquals("dup-replay-1", delta.opBatches().get(0).path("opBatchId").asText());
    }

    @Test
    void duplicateReplayOfLaterBatchKeepsStableAssignedRange() {
        ArchimeshService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "first-batch", null, singleCreateElementOp()));
        SubmitOpsMessage second = new SubmitOpsMessage(1, "second-batch", null, singleUpdateElementNameOp("elem:e1", "updated"));
        service.onSubmitOps("demo", second);
        service.onSubmitOps("demo", second); // duplicate replay of same intent

        List<OpsAcceptedMessage> accepted = acceptedPayloads(sessions.broadcasts);
        OpsAcceptedMessage secondAccepted = accepted.stream()
                .filter(m -> "second-batch".equals(m.opBatchId()))
                .findFirst()
                .orElseThrow();
        long secondFrom = secondAccepted.assignedRevisionRange().from();
        long secondTo = secondAccepted.assignedRevisionRange().to();
        Assertions.assertEquals(2L, secondFrom);
        Assertions.assertEquals(2L, secondTo);

        List<OpsAcceptedMessage> secondDuplicates = accepted.stream()
                .filter(m -> "second-batch".equals(m.opBatchId()))
                .toList();
        Assertions.assertEquals(2, secondDuplicates.size());
        Assertions.assertEquals(secondDuplicates.get(0).assignedRevisionRange(), secondDuplicates.get(1).assignedRevisionRange(),
                "idempotent duplicate should return exactly same range");
    }

    @Test
    void submitOpsAcceptsPropertySetMemberCollectionOps() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode create = objectMapper.createObjectNode();
        create.put("type", "CreateElement");
        create.set("element", objectMapper.createObjectNode()
                .put("id", "elem:e1")
                .put("archimateType", "BusinessActor"));
        ops.add(create);

        ObjectNode add = objectMapper.createObjectNode();
        add.put("type", "AddPropertySetMember");
        add.put("targetId", "elem:e1");
        add.put("key", "owners");
        add.put("member", "alpha");
        ops.add(add);

        ObjectNode remove = objectMapper.createObjectNode();
        remove.put("type", "RemovePropertySetMember");
        remove.put("targetId", "elem:e1");
        remove.put("key", "owners");
        remove.put("member", "alpha");
        ops.add(remove);

        ObjectNode addChild = objectMapper.createObjectNode();
        addChild.put("type", "AddViewObjectChildMember");
        addChild.put("parentViewObjectId", "vo:parent-1");
        addChild.put("childViewObjectId", "vo:child-1");
        ops.add(addChild);

        ObjectNode removeChild = objectMapper.createObjectNode();
        removeChild.put("type", "RemoveViewObjectChildMember");
        removeChild.put("parentViewObjectId", "vo:parent-1");
        removeChild.put("childViewObjectId", "vo:child-1");
        ops.add(removeChild);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "orset-ops-0001", null, ops));

        Assertions.assertEquals(1, neo.appendCount);
        Assertions.assertTrue(sessions.broadcasts.stream().anyMatch(m -> "OpsAccepted".equals(m.type())));
    }

    @Test
    void adminWindowRecentOpBatchesRemainDeterministicAfterDuplicateReplay() {
        ArchimeshService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        sessions.activeModelIds.add("demo");
        sessions.countByModel.put("demo", 1);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "batch-1", null, singleCreateElementOp()));
        service.onSubmitOps("demo", new SubmitOpsMessage(1, "batch-2", null, singleUpdateElementNameOp("elem:e1", "v2")));
        service.onSubmitOps("demo", new SubmitOpsMessage(1, "batch-2", null, singleUpdateElementNameOp("elem:e1", "v2"))); // duplicate
        neo.readLatestCommitRevisionValue = 2L;

        var window = service.getAdminModelWindow("demo", 10);
        JsonNode recent = window.recentOpBatches();
        Assertions.assertEquals(2, recent.size(), "duplicate replay must not create duplicate recent op batches");
        Assertions.assertEquals("batch-1", recent.get(0).path("opBatchId").asText());
        Assertions.assertEquals("batch-2", recent.get(1).path("opBatchId").asText());
        Assertions.assertEquals(1L, recent.get(0).path("assignedRevisionRange").path("from").asLong());
        Assertions.assertEquals(2L, recent.get(1).path("assignedRevisionRange").path("to").asLong());
    }

    private ArchimeshService baseService() {
        // Unit harness: pure in-memory Archimesh so tests assert service orchestration only
        ArchimeshService service = new ArchimeshService();
        service.validationService = new InMemoryValidationService();
        service.revisionService = new InMemoryRevisionService();
        service.lockService = new InMemoryLockService();
        service.idempotencyService = new InMemoryIdempotencyService();
        service.neo4jRepository = new RecordingNeo4jRepository();
        service.kafkaPublisher = new RecordingKafkaPublisher();
        service.sessionRegistry = new RecordingSessionRegistry();
        ((RecordingNeo4jRepository) service.neo4jRepository).registerModel("demo", "Demo");
        return service;
    }

    private JsonNode singleCreateElementOp() {
        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode element = objectMapper.createObjectNode();
        element.put("id", "elem:e1");
        element.put("archimateType", "BusinessActor");

        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "CreateElement");
        op.set("element", element);

        ops.add(op);
        return ops;
    }

    private JsonNode singleUpdateElementNameOp(String elementId, String name) {
        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("name", name);
        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "UpdateElement");
        op.put("elementId", elementId);
        op.set("patch", patch);
        ops.add(op);
        return ops;
    }

    private JsonNode singleCreateFolderOp(String folderId, String parentFolderId) {
        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode folder = objectMapper.createObjectNode();
        folder.put("id", folderId);
        folder.put("folderType", "USER");
        folder.put("name", "Capabilities");
        if (parentFolderId != null) {
            folder.put("parentFolderId", parentFolderId);
        }
        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "CreateFolder");
        op.set("folder", folder);
        ops.add(op);
        return ops;
    }

    private JsonNode singleDeleteFolderOp(String folderId) {
        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "DeleteFolder");
        op.put("folderId", folderId);
        ops.add(op);
        return ops;
    }

    private List<OpsAcceptedMessage> acceptedPayloads(List<ServerEnvelope> envelopes) {
        List<OpsAcceptedMessage> accepted = new ArrayList<>();
        for (ServerEnvelope envelope : envelopes) {
            if ("OpsAccepted".equals(envelope.type()) && envelope.payload() instanceof OpsAcceptedMessage message) {
                accepted.add(message);
            }
        }
        return accepted;
    }

    private static class RecordingNeo4jRepository implements Neo4jRepository {
        // Records persistence side effects and simulates lookups/preconditions
        int appendCount;
        int applyCount;
        int updateHeadCount;
        int readLatestCommitRevisionCount;
        int isMaterializedStateConsistentCount;
        int clearMaterializedStateCount;
        int loadSnapshotCount;
        int loadOpBatchesCount;
        int deleteModelCount;
        int compactMetadataCount;
        int restoreModelTagCount;
        JsonNode lastOpBatch = JsonNodeFactory.instance.objectNode();
        JsonNode snapshotToReturn = JsonNodeFactory.instance.objectNode();
        JsonNode opBatchesToReturn = JsonNodeFactory.instance.arrayNode();
        final List<JsonNode> appendedOpBatches = new ArrayList<>();
        RuntimeException appendFailure;
        RuntimeException applyFailure;
        RuntimeException updateHeadFailure;
        long readHeadRevisionValue;
        long readLatestCommitRevisionValue = 1;
        long lastCompactRetainRevisions = -1L;
        boolean materializedStateConsistent = true;
        boolean elementExists = true;
        boolean relationshipExists = true;
        boolean viewExists = true;
        boolean folderExists = true;
        boolean folderEmpty = true;
        boolean folderMoveCreatesCycle = false;
        java.util.Map<String, String> folderRootIds = new java.util.HashMap<>();
        boolean viewObjectExists = true;
        boolean connectionExists = true;
        List<String> relationshipIdsByElement = List.of();
        java.util.Map<String, List<String>> viewObjectIdsByRepresents = new java.util.HashMap<>();
        java.util.Map<String, List<String>> connectionIdsByViewObject = new java.util.HashMap<>();
        java.util.Map<String, List<String>> connectionIdsByRelationship = new java.util.HashMap<>();
        java.util.Map<String, String> modelNames = new java.util.HashMap<>();
        java.util.Map<String, Integer> modelRegisteredChecks = new java.util.HashMap<>();
        java.util.Map<String, Integer> accessControlChecks = new java.util.HashMap<>();
        java.util.Map<String, ModelAccessControl> accessControls = new java.util.HashMap<>();
        java.util.Map<String, java.util.Map<String, ModelTagEntry>> modelTags = new java.util.HashMap<>();
        java.util.Map<String, java.util.Map<String, JsonNode>> tagSnapshots = new java.util.HashMap<>();

        @Override
        public Optional<RevisionRange> findAcceptedRange(String modelId, String opBatchId) {
            return appendedOpBatches.stream()
                    .filter(batch -> modelId.equals(batch.path("modelId").asText()) && opBatchId.equals(batch.path("opBatchId").asText()))
                    .findFirst().map(batch -> new RevisionRange(batch.path("assignedRevisionRange").path("from").asLong(),
                            batch.path("assignedRevisionRange").path("to").asLong()));
        }

        @Override
        public void commitBatch(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch) {
            int originalSize = appendedOpBatches.size();
            try {
                appendOpLog(modelId, opBatchId, range, opBatch);
                applyToMaterializedState(modelId, opBatch);
                updateHeadRevision(modelId, range.to());
            } catch (RuntimeException failure) {
                while (appendedOpBatches.size() > originalSize) appendedOpBatches.removeLast();
                throw failure;
            }
        }

        @Override
        public void appendOpLog(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch) {
            appendCount++;
            if (appendFailure != null) {
                throw appendFailure;
            }
            lastOpBatch = opBatch;
            appendedOpBatches.add(opBatch.deepCopy());
        }

        @Override
        public void applyToMaterializedState(String modelId, JsonNode opBatch) {
            applyCount++;
            if (applyFailure != null) {
                throw applyFailure;
            }
        }

        @Override
        public void updateHeadRevision(String modelId, long headRevision) {
            updateHeadCount++;
            if (updateHeadFailure != null) {
                throw updateHeadFailure;
            }
        }

        @Override
        public long readHeadRevision(String modelId) {
            return readHeadRevisionValue;
        }

        @Override
        public long readLatestCommitRevision(String modelId) {
            readLatestCommitRevisionCount++;
            return readLatestCommitRevisionValue;
        }

        @Override
        public JsonNode loadSnapshot(String modelId) {
            loadSnapshotCount++;
            return snapshotToReturn;
        }

        @Override
        public JsonNode loadTaggedSnapshot(String modelId, String tagName) {
            return tagSnapshots.getOrDefault(modelId, java.util.Map.of())
                    .getOrDefault(tagName, JsonNodeFactory.instance.objectNode());
        }

        @Override
        public JsonNode loadOpBatches(String modelId, long fromRevisionInclusive, long toRevisionInclusive) {
            loadOpBatchesCount++;
            if (opBatchesToReturn != null && opBatchesToReturn.isArray() && !opBatchesToReturn.isEmpty()) {
                return opBatchesToReturn;
            }
            // Fallback projection used by delta-window tests when explicit fixture data is absent.
            ArrayNode filtered = JsonNodeFactory.instance.arrayNode();
            for (JsonNode batch : appendedOpBatches) {
                long from = batch.path("assignedRevisionRange").path("from").asLong(Long.MIN_VALUE);
                long to = batch.path("assignedRevisionRange").path("to").asLong(Long.MIN_VALUE);
                if (from >= fromRevisionInclusive && to <= toRevisionInclusive) {
                    filtered.add(batch);
                }
            }
            return filtered;
        }

        @Override
        public AdminCompactionStatus compactMetadata(String modelId, long retainRevisions) {
            compactMetadataCount++;
            lastCompactRetainRevisions = retainRevisions;
            return new AdminCompactionStatus(modelId, 50L, 50L, 25L, retainRevisions,
                    2L, 10L, 3L, 2L, 4L, 1L, true, "ok");
        }

        @Override
        public void rebuildProjection(String modelId, JsonNode history, long head) {
            clearMaterializedState(modelId);
            for (JsonNode batch : history) applyToMaterializedState(modelId, batch);
            updateHeadRevision(modelId, head);
        }

        @Override
        public void clearMaterializedState(String modelId) {
            clearMaterializedStateCount++;
        }

        @Override
        public void deleteModel(String modelId) {
            deleteModelCount++;
            modelNames.remove(modelId);
            accessControls.remove(modelId);
            modelTags.remove(modelId);
            tagSnapshots.remove(modelId);
        }

        @Override
        public boolean isMaterializedStateConsistent(String modelId, long expectedHeadRevision) {
            isMaterializedStateConsistentCount++;
            return materializedStateConsistent;
        }

        @Override
        public boolean elementExists(String modelId, String elementId) {
            return elementExists;
        }

        @Override
        public boolean relationshipExists(String modelId, String relationshipId) {
            return relationshipExists;
        }

        @Override
        public boolean viewExists(String modelId, String viewId) {
            return viewExists;
        }

        @Override
        public boolean folderExists(String modelId, String folderId) {
            return folderExists;
        }

        @Override
        public boolean folderEmpty(String modelId, String folderId) {
            return folderEmpty;
        }

        @Override
        public boolean folderMoveCreatesCycle(String modelId, String folderId, String parentFolderId) {
            return folderMoveCreatesCycle;
        }

        @Override
        public String folderRootId(String modelId, String folderId) {
            return folderRootIds.get(folderId);
        }

        @Override
        public boolean viewObjectExists(String modelId, String viewObjectId) {
            return viewObjectExists;
        }

        @Override
        public boolean connectionExists(String modelId, String connectionId) {
            return connectionExists;
        }

        @Override
        public List<String> findRelationshipIdsByElement(String modelId, String elementId) {
            return relationshipIdsByElement;
        }

        @Override
        public List<String> findViewObjectIdsByRepresents(String modelId, String representsId) {
            return viewObjectIdsByRepresents.getOrDefault(representsId, List.of());
        }

        @Override
        public List<String> findConnectionIdsByViewObject(String modelId, String viewObjectId) {
            return connectionIdsByViewObject.getOrDefault(viewObjectId, List.of());
        }

        @Override
        public List<String> findConnectionIdsByRelationship(String modelId, String relationshipId) {
            return connectionIdsByRelationship.getOrDefault(relationshipId, List.of());
        }

        @Override
        public ModelCatalogEntry registerModel(String modelId, String modelName) {
            modelNames.put(modelId, modelName);
            return new ModelCatalogEntry(modelId, modelName, readHeadRevisionValue);
        }

        @Override
        public ModelCatalogEntry renameModel(String modelId, String modelName) {
            modelNames.put(modelId, modelName);
            return new ModelCatalogEntry(modelId, modelName, readHeadRevisionValue);
        }

        @Override
        public String readModelName(String modelId) {
            return modelNames.get(modelId);
        }

        @Override
        public boolean modelRegistered(String modelId) {
            modelRegisteredChecks.merge(modelId, 1, Integer::sum);
            return modelNames.containsKey(modelId);
        }

        @Override
        public Optional<ModelAccessControl> readModelAccessControl(String modelId) {
            accessControlChecks.merge(modelId, 1, Integer::sum);
            if (!modelNames.containsKey(modelId)) {
                return Optional.empty();
            }
            return Optional.of(accessControls.getOrDefault(modelId,
                    new ModelAccessControl(modelId, Set.of(), Set.of(), Set.of())));
        }

        @Override
        public ModelAccessControl updateModelAccessControl(String modelId, Set<String> adminUsers, Set<String> writerUsers, Set<String> readerUsers) {
            ModelAccessControl accessControl = new ModelAccessControl(modelId, adminUsers, writerUsers, readerUsers);
            accessControls.put(modelId, accessControl);
            return accessControl;
        }

        @Override
        public ModelTagEntry createModelTag(String modelId, String tagName, String description, long revision, JsonNode snapshot) {
            return restoreModelTag(modelId, tagName, description, revision, Instant.now().toString(), snapshot);
        }

        @Override
        public ModelTagEntry restoreModelTag(String modelId, String tagName, String description, long revision, String createdAt, JsonNode snapshot) {
            restoreModelTagCount++;
            ModelTagEntry tag = new ModelTagEntry(modelId, tagName, description, revision, createdAt);
            modelTags.computeIfAbsent(modelId, key -> new java.util.HashMap<>()).put(tagName, tag);
            tagSnapshots.computeIfAbsent(modelId, key -> new java.util.HashMap<>()).put(tagName, snapshot.deepCopy());
            return tag;
        }

        @Override
        public Optional<ModelTagEntry> readModelTag(String modelId, String tagName) {
            return Optional.ofNullable(modelTags.getOrDefault(modelId, java.util.Map.of()).get(tagName));
        }

        @Override
        public List<ModelTagEntry> listModelTags(String modelId) {
            return new ArrayList<>(modelTags.getOrDefault(modelId, java.util.Map.of()).values());
        }

        @Override
        public void deleteModelTag(String modelId, String tagName) {
            java.util.Map<String, ModelTagEntry> tags = modelTags.get(modelId);
            if (tags != null) {
                tags.remove(tagName);
            }
            java.util.Map<String, JsonNode> snapshots = tagSnapshots.get(modelId);
            if (snapshots != null) {
                snapshots.remove(tagName);
            }
        }

        @Override
        public List<ModelCatalogEntry> listModelCatalog() {
            List<ModelCatalogEntry> models = new ArrayList<>();
            for (var entry : modelNames.entrySet()) {
                models.add(new ModelCatalogEntry(entry.getKey(), entry.getValue(), readHeadRevisionValue));
            }
            return models;
        }
    }

    private static class RecordingKafkaPublisher implements KafkaPublisher {
        // Fan-out is validated separately in Kafka consumer tests; here we just count publishes
        int opsPublished;
        int lockEventsPublished;
        int presenceEventsPublished;

        @Override
        public CompletableFuture<Void> publishOps(String modelId, JsonNode opBatch) {
            opsPublished++;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void publishLockEvent(String modelId, Object lockEvent) {
            lockEventsPublished++;
        }

        @Override
        public void publishPresence(String modelId, Object presenceEvent) {
            presenceEventsPublished++;
        }
    }

    private static class RecordingSessionRegistry implements SessionRegistry {
        // Captures send/broadcast traffic to assert endpoint/service contract
        final List<ServerEnvelope> broadcasts = new ArrayList<>();
        final List<ServerEnvelope> sends = new ArrayList<>();
        final java.util.Map<String, Integer> countByModel = new java.util.HashMap<>();
        final java.util.Set<String> activeModelIds = new java.util.HashSet<>();

        @Override
        public void register(String modelId, jakarta.websocket.Session session) {
            activeModelIds.add(modelId);
            countByModel.put(modelId, countByModel.getOrDefault(modelId, 0) + 1);
        }

        @Override
        public void unregister(String modelId, jakarta.websocket.Session session) {
            int next = Math.max(0, countByModel.getOrDefault(modelId, 0) - 1);
            if (next == 0) {
                countByModel.remove(modelId);
                activeModelIds.remove(modelId);
            } else {
                countByModel.put(modelId, next);
            }
        }

        @Override
        public void send(jakarta.websocket.Session session, ServerEnvelope message) {
            sends.add(message);
        }

        @Override
        public void broadcast(String modelId, ServerEnvelope message) {
            broadcasts.add(message);
        }

        @Override
        public int sessionCount(String modelId) {
            return countByModel.getOrDefault(modelId, 0);
        }

        @Override
        public java.util.Set<String> activeModelIds() {
            return new java.util.HashSet<>(activeModelIds);
        }

        @Override
        public java.util.List<org.gautelis.archimesh.model.AdminActiveSession> activeSessions(String modelId) {
            return java.util.List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static void seedActiveSessionDiagnostics(ArchimeshService service, String modelId, String websocketSessionId, String userId) {
        try {
            Field diagnosticsField = ArchimeshService.class.getDeclaredField("joinedSessionDiagnosticsByWebsocketSessionId");
            diagnosticsField.setAccessible(true);
            Map<String, AdminActiveSession> diagnostics =
                    (Map<String, AdminActiveSession>) diagnosticsField.get(service);
            diagnostics.put(websocketSessionId, new AdminActiveSession(websocketSessionId, userId, Set.of(), "HEAD", true));

            Field modelIdsField = ArchimeshService.class.getDeclaredField("modelIdByWebsocketSessionId");
            modelIdsField.setAccessible(true);
            Map<String, String> modelIds = (Map<String, String>) modelIdsField.get(service);
            modelIds.put(websocketSessionId, modelId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to seed active session diagnostics", e);
        }
    }

    private static class FixedRevisionService implements RevisionService {
        // Deterministic head for join-window tests.
        private final long headRevision;

        private FixedRevisionService(long headRevision) {
            this.headRevision = headRevision;
        }

        @Override
        public RevisionRange assignRange(String modelId, int opCount) {
            long from = headRevision + 1;
            long to = from + Math.max(0, opCount - 1L);
            return new RevisionRange(from, to);
        }

        @Override
        public long headRevision(String modelId) {
            return headRevision;
        }

        @Override
        public void clearModel(String modelId) {
        }
    }
}
