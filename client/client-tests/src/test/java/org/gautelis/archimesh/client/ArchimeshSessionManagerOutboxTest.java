package org.gautelis.archimesh.client;

import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

class ArchimeshSessionManagerOutboxTest {

    private String originalUserHome;
    private Path tempHome;

    @BeforeEach
    void setUpUserHome() throws Exception {
        originalUserHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("archimesh-outbox-test-home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if(originalUserHome == null) {
            System.clearProperty("user.home");
        }
        else {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void queuedReplayDrainsInOrderAndDequeuesOnlyAfterSuccessfulSend() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        String modelId = "model-a";
        String op1 = submitOps(modelId, 1, "op-1");
        String op2 = submitOps(modelId, 2, "op-2");

        manager.sendSubmitOps(op1);
        manager.sendSubmitOps(op2);

        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", modelId);

        manager.setLastKnownRevision(10L);

        awaitTrue(() -> ws.sentTexts.size() == 1, Duration.ofSeconds(1));
        Assertions.assertEquals(2, queuedPayloads(manager).size(), "first queued entry must remain until send succeeds");

        ws.completeSend(0, null);
        Assertions.assertEquals(2, queuedPayloads(manager).size(), "entry remains queued until OpsAccepted");

        manager.onServerOpsAccepted("op-1");
        awaitTrue(() -> ws.sentTexts.size() == 2, Duration.ofSeconds(1));

        ws.completeSend(1, null);
        Assertions.assertEquals(1, queuedPayloads(manager).size(), "second entry remains queued until OpsAccepted");
        manager.onServerOpsAccepted("op-2");
        awaitTrue(() -> queuedPayloads(manager).isEmpty(), Duration.ofSeconds(1));
    }

    @Test
    void onlineSubmissionsAreDurableBeforeAcknowledgment() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", "online");
        manager.sendSubmitOps(submitOps("online", 0, "online-batch"));
        Assertions.assertEquals(1, queuedPayloads(manager).size());
        // An unusually fast server may acknowledge before the send future completes.
        manager.onServerOpsAccepted("online-batch");
        ws.completeSend(0, null);
        Assertions.assertTrue(queuedPayloads(manager).isEmpty());
        Assertions.assertNull(getField(manager, "outboxAwaitingAck"));
    }

    @Test
    void readOnlyRefSuppressesLocalSubmitEmission() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", "model-tagged");
        setField(manager, "currentModelRef", "release-1");

        manager.sendSubmitOps(submitOps("model-tagged", 1, "tagged-op"));

        Assertions.assertTrue(ws.sentTexts.isEmpty(), "read-only tagged ref must not emit SubmitOps");
        Assertions.assertTrue(queuedPayloads(manager).isEmpty(), "read-only tagged ref must not queue SubmitOps");
    }

    @Test
    void joinPayloadIncludesExplicitRef() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", "model-tagged");
        setField(manager, "currentModelRef", "release-1");

        manager.sendJoin(12L);

        Assertions.assertEquals(1, ws.sentTexts.size());
        Assertions.assertTrue(ws.sentTexts.get(0).contains("\"ref\":\"release-1\""));
        Assertions.assertTrue(ws.sentTexts.get(0).contains("\"lastSeenRevision\":12"));
    }

    @Test
    void forcedColdStartIgnoresCachedRevisionDuringJoinDecision() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        setField(manager, "serverBackedSession", true);
        setField(manager, "forceColdStartOnNextConnect", true);

        Path cacheDir = tempHome.resolve("Archi").resolve("archimesh-cache");
        Files.createDirectories(cacheDir);
        Path cacheFile = cacheDir.resolve("model-cold__HEAD.archimate");
        Files.writeString(cacheFile, "placeholder");
        Path metadataFile = cacheDir.resolve("model-cold__HEAD.archimate.meta.properties");
        Files.writeString(
                metadataFile,
                "modelId=model-cold\n"
                        + "modelRef=HEAD\n"
                        + "lastKnownRevision=42\n"
                        + "serverBacked=true\n");

        Method resolveJoinDecision = ArchimeshSessionManager.class.getDeclaredMethod(
                "resolveJoinDecision",
                String.class,
                String.class);
        resolveJoinDecision.setAccessible(true);

        Object decision = resolveJoinDecision.invoke(manager, "model-cold", "HEAD");
        Method joinRevision = decision.getClass().getDeclaredMethod("joinRevision");
        joinRevision.setAccessible(true);
        Method reason = decision.getClass().getDeclaredMethod("reason");
        reason.setAccessible(true);

        Assertions.assertNull(joinRevision.invoke(decision),
                "forced cold start must request a full snapshot instead of rejoining from cache revision");
        Assertions.assertEquals("forced-cold-start", reason.invoke(decision));
    }

    @Test
    void queuedReplayFailureRetainsHeadEntryWithoutReordering() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        String modelId = "model-b";
        String op1 = submitOps(modelId, 1, "head-op");
        String op2 = submitOps(modelId, 2, "tail-op");

        manager.sendSubmitOps(op1);
        manager.sendSubmitOps(op2);

        ControlledWebSocket ws = new ControlledWebSocket();
        ws.failFirstSend = true;
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", modelId);

        manager.setLastKnownRevision(20L);

        awaitTrue(() -> !ws.sentTexts.isEmpty(), Duration.ofSeconds(1));
        List<String> queued = queuedPayloads(manager);
        Assertions.assertEquals(2, queued.size());
        Assertions.assertEquals(op1, queued.get(0), "failed head entry must stay at queue head");
        Assertions.assertEquals(op2, queued.get(1), "tail entry must not move ahead on head failure");
    }

    @Test
    void repeatedTransportFailuresPreserveHeadAndTailOrder() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        String modelId = "model-c";
        String head = submitOps(modelId, 1, "poison-head");
        String tail = submitOps(modelId, 2, "tail-op");

        manager.sendSubmitOps(head);
        manager.sendSubmitOps(tail);

        Object headEntry = outboxEntries(manager).get(0);
        Method handleResult = ArchimeshSessionManager.class.getDeclaredMethod(
                "handleOutboxReplaySendResult",
                headEntry.getClass(),
                Throwable.class);
        handleResult.setAccessible(true);

        for(int i = 0; i < 5; i++) {
            Object currentHead = outboxEntries(manager).get(0);
            handleResult.invoke(manager, currentHead, new RuntimeException("simulated failure " + i));
        }

        List<String> queued = queuedPayloads(manager);
        Assertions.assertEquals(2, queued.size(), "transport failures cannot discard uncommitted work");
        Assertions.assertEquals(head, queued.get(0));
        Assertions.assertEquals(tail, queued.get(1));
    }

    @Test
    void connectedSubmitForDifferentModelIsQueuedAndNotSentOnCurrentSocket() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", "model-active");

        String foreign = submitOps("model-foreign", 1, "foreign-op");
        manager.sendSubmitOps(foreign);

        Assertions.assertTrue(ws.sentTexts.isEmpty(), "cross-model submit must not be sent on active model socket");
        List<String> queued = queuedPayloads(manager);
        Assertions.assertEquals(1, queued.size());
        Assertions.assertEquals(foreign, queued.get(0));
    }

    @Test
    void flushReplaysOnlyCurrentModelEntriesLeavingOtherModelQueued() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        String modelAHead = submitOps("model-a", 1, "a-head");
        String modelBTail = submitOps("model-b", 1, "b-tail");
        String modelANext = submitOps("model-a", 2, "a-next");

        manager.sendSubmitOps(modelAHead);
        manager.sendSubmitOps(modelBTail);
        manager.sendSubmitOps(modelANext);

        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", "model-a");
        manager.setLastKnownRevision(50L);

        awaitTrue(() -> ws.sentTexts.size() == 1, Duration.ofSeconds(1));
        ws.completeSend(0, null);
        manager.onServerOpsAccepted("a-head");
        awaitTrue(() -> ws.sentTexts.size() == 2, Duration.ofSeconds(1));
        ws.completeSend(1, null);
        manager.onServerOpsAccepted("a-next");
        awaitTrue(() -> queuedPayloads(manager).size() == 1, Duration.ofSeconds(1));

        List<String> queued = queuedPayloads(manager);
        Assertions.assertEquals(modelBTail, queued.get(0), "non-current model entry must remain queued");
    }

    @Test
    void queuedEntriesForOtherModelReplayWhenThatModelBecomesActive() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        String modelA = submitOps("model-a", 1, "a-op");
        String modelB = submitOps("model-b", 1, "b-op");

        manager.sendSubmitOps(modelA);
        manager.sendSubmitOps(modelB);

        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", "model-a");
        manager.setLastKnownRevision(10L);

        awaitTrue(() -> ws.sentTexts.size() == 1, Duration.ofSeconds(1));
        ws.completeSend(0, null);
        manager.onServerOpsAccepted("a-op");
        awaitTrue(() -> queuedPayloads(manager).size() == 1, Duration.ofSeconds(1));

        setField(manager, "currentModelId", "model-b");
        manager.setLastKnownRevision(11L);
        awaitTrue(() -> ws.sentTexts.size() == 2, Duration.ofSeconds(1));
        Assertions.assertTrue(ws.sentTexts.get(1).contains("\"opBatchId\":\"b-op\""),
                "once model-b is active, its queued entry should replay");

        ws.completeSend(1, null);
        manager.onServerOpsAccepted("b-op");
        awaitTrue(() -> queuedPayloads(manager).isEmpty(), Duration.ofSeconds(1));
    }

    @Test
    void preconditionFailureDropsAwaitingHeadAndSurfacesConflict() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        String modelId = "model-d";
        String stale = submitOps(modelId, 1, "stale-op");
        String next = submitOps(modelId, 2, "next-op");

        manager.sendSubmitOps(stale);
        manager.sendSubmitOps(next);

        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", modelId);
        manager.setLastKnownRevision(100L);

        awaitTrue(() -> ws.sentTexts.size() == 1, Duration.ofSeconds(1));
        ws.completeSend(0, null);
        awaitTrue(() -> getField(manager, "outboxAwaitingAck") != null, Duration.ofSeconds(1));

        List<String> conflicts = new ArrayList<>();
        manager.addSubmitConflictListener((m, op, code, message) -> conflicts.add(m + "|" + op + "|" + code));

        manager.onServerError("PRECONDITION_FAILED", "stale local intent", "stale-op");
        awaitTrue(() -> ws.sentTexts.size() == 2, Duration.ofSeconds(1));

        List<String> queued = queuedPayloads(manager);
        Assertions.assertEquals(1, queued.size(), "stale head must be dropped from outbox");
        Assertions.assertEquals(next, queued.get(0));
        Assertions.assertEquals(1, conflicts.size(), "conflict must be surfaced explicitly");
        Assertions.assertEquals("model-d|stale-op|PRECONDITION_FAILED", conflicts.get(0));
    }

    @Test
    void lockConflictDropsAwaitingHeadAndSurfacesConflict() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        String modelId = "model-lock";
        String stale = submitOps(modelId, 1, "lock-stale");
        String next = submitOps(modelId, 2, "lock-next");

        manager.sendSubmitOps(stale);
        manager.sendSubmitOps(next);

        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", modelId);
        manager.setLastKnownRevision(200L);

        awaitTrue(() -> ws.sentTexts.size() == 1, Duration.ofSeconds(1));
        ws.completeSend(0, null);
        awaitTrue(() -> getField(manager, "outboxAwaitingAck") != null, Duration.ofSeconds(1));

        List<String> conflicts = new ArrayList<>();
        manager.addSubmitConflictListener((m, op, code, message) -> conflicts.add(m + "|" + op + "|" + code));

        manager.onServerError("LOCK_CONFLICT", "target locked by another actor", "lock-stale");
        awaitTrue(() -> ws.sentTexts.size() == 2, Duration.ofSeconds(1));

        List<String> queued = queuedPayloads(manager);
        Assertions.assertEquals(1, queued.size(), "lock-conflicting head must be dropped from outbox");
        Assertions.assertEquals(next, queued.get(0));
        Assertions.assertEquals(1, conflicts.size(), "lock conflict must be surfaced explicitly");
        Assertions.assertEquals("model-lock|lock-stale|LOCK_CONFLICT", conflicts.get(0));
    }

    @Test
    void reconnectSnapshotThenDeltaRebasesQueuedReplayToLatestRevision() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        String modelId = "model-reconnect";
        String op1 = submitOps(modelId, 1, "replay-1");
        String op2 = submitOps(modelId, 2, "replay-2");

        manager.sendSubmitOps(op1);
        manager.sendSubmitOps(op2);

        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", modelId);

        // Reconnect flow: first snapshot/checkout indicates head=10, then delta/broadcast advances to 12.
        manager.setLastKnownRevision(10L);
        awaitTrue(() -> ws.sentTexts.size() == 1, Duration.ofSeconds(1));
        Assertions.assertEquals(10L, extractBaseRevision(ws.sentTexts.get(0)),
                "first replay must be rebased to snapshot head");

        manager.setLastKnownRevision(12L);

        ws.completeSend(0, null);
        manager.onServerOpsAccepted("replay-1");
        awaitTrue(() -> ws.sentTexts.size() == 2, Duration.ofSeconds(1));
        Assertions.assertEquals(12L, extractBaseRevision(ws.sentTexts.get(1)),
                "next replay must be rebased to latest revision after delta");

        ws.completeSend(1, null);
        manager.onServerOpsAccepted("replay-2");
        awaitTrue(() -> queuedPayloads(manager).isEmpty(), Duration.ofSeconds(1));
    }

    @Test
    void ackTimeoutRetainsHeadAndRetriesSameQueuedOp() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        String modelId = "model-ack-timeout";
        String op = submitOps(modelId, 1, "ack-timeout-op");
        manager.sendSubmitOps(op);

        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", modelId);
        manager.setLastKnownRevision(33L);

        awaitTrue(() -> ws.sentTexts.size() == 1, Duration.ofSeconds(1));
        ws.completeSend(0, null);
        awaitTrue(() -> getField(manager, "outboxAwaitingAck") != null, Duration.ofSeconds(1));

        setField(manager, "outboxAwaitingAckDeadlineEpochMs", 1L);
        manager.setLastKnownRevision(33L);

        List<String> queued = queuedPayloads(manager);
        Assertions.assertEquals(1, queued.size(), "timed-out head must stay queued for deterministic retry");
        Assertions.assertTrue(queued.get(0).contains("\"opBatchId\":\"ack-timeout-op\""));
        Assertions.assertNull(getField(manager, "outboxAwaitingAck"),
                "ack-timeout should clear awaiting-ack so replay can continue");
        Assertions.assertEquals(1, replayAttempts(outboxEntries(manager).get(0)),
                "timed-out entry should advance replay attempts for deterministic backoff");
    }

    @Test
    void unrelatedAndUncorrelatedRejectionsCannotDropQueuedWork() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        manager.sendSubmitOps(submitOps("correlation", 0, "mine"));
        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", "correlation");
        manager.setLastKnownRevision(0);
        ws.completeSend(0, null);
        manager.onServerError("PRECONDITION_FAILED", "another client's edit", "theirs");
        manager.onServerError("LOCK_CONFLICT", "legacy uncorrelated error");
        Assertions.assertEquals(1, queuedPayloads(manager).size());
        Assertions.assertNotNull(getField(manager, "outboxAwaitingAck"));
    }

    @Test
    void nonConflictServerErrorRetainsHeadAndRetries() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        String modelId = "model-retry-error";
        String op = submitOps(modelId, 1, "retry-op");
        manager.sendSubmitOps(op);

        ControlledWebSocket ws = new ControlledWebSocket();
        setField(manager, "webSocket", ws);
        setField(manager, "currentModelId", modelId);
        manager.setLastKnownRevision(44L);

        awaitTrue(() -> ws.sentTexts.size() == 1, Duration.ofSeconds(1));
        ws.completeSend(0, null);
        awaitTrue(() -> getField(manager, "outboxAwaitingAck") != null, Duration.ofSeconds(1));

        manager.onServerError("INTERNAL_ERROR", "transient failure", "retry-op");
        awaitTrue(() -> ws.sentTexts.size() == 2, Duration.ofSeconds(1));

        List<String> queued = queuedPayloads(manager);
        Assertions.assertEquals(1, queued.size(), "non-conflict errors must not drop queued intent");
        Assertions.assertTrue(queued.get(0).contains("\"opBatchId\":\"retry-op\""));
    }

    @Test
    void outboxCapacityPreservesExistingUncommittedEdits() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        for (int i = 0; i < 1000; i++) manager.sendSubmitOps(submitOps("capacity", i, "op-" + i));
        Assertions.assertThrows(IllegalStateException.class,
                () -> manager.sendSubmitOps(submitOps("capacity", 1000, "overflow")));
        List<String> queued = queuedPayloads(manager);
        Assertions.assertEquals(1000, queued.size());
        Assertions.assertTrue(queued.getFirst().contains("op-0"));
    }

    @Test
    void replayBackoffIsDeterministicAndCapped() throws Exception {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        Method delay = ArchimeshSessionManager.class.getDeclaredMethod("computeOutboxRetryDelayMs", int.class);
        delay.setAccessible(true);

        Assertions.assertEquals(0L, (Long)delay.invoke(manager, 0));
        Assertions.assertEquals(250L, (Long)delay.invoke(manager, 1));
        Assertions.assertEquals(500L, (Long)delay.invoke(manager, 2));
        Assertions.assertEquals(1000L, (Long)delay.invoke(manager, 3));
        Assertions.assertEquals(2000L, (Long)delay.invoke(manager, 4));
        Assertions.assertEquals(4000L, (Long)delay.invoke(manager, 5));
        Assertions.assertEquals(5000L, (Long)delay.invoke(manager, 6));
        Assertions.assertEquals(5000L, (Long)delay.invoke(manager, 10));
    }

    @Test
    void identicalHeadAndQueuedOpsProduceDeterministicReplaySequence() throws Exception {
        String modelId = "model-deterministic";
        String op1 = submitOps(modelId, 1, "det-op-1");
        String op2 = submitOps(modelId, 2, "det-op-2");

        ArchimeshSessionManager managerA = new ArchimeshSessionManager();
        managerA.sendSubmitOps(op1);
        managerA.sendSubmitOps(op2);
        ControlledWebSocket wsA = new ControlledWebSocket();
        setField(managerA, "webSocket", wsA);
        setField(managerA, "currentModelId", modelId);
        managerA.setLastKnownRevision(77L);
        awaitTrue(() -> wsA.sentTexts.size() == 1, Duration.ofSeconds(1));
        wsA.completeSend(0, null);
        managerA.onServerOpsAccepted("det-op-1");
        awaitTrue(() -> wsA.sentTexts.size() == 2, Duration.ofSeconds(1));
        wsA.completeSend(1, null);
        managerA.onServerOpsAccepted("det-op-2");
        awaitTrue(() -> queuedPayloads(managerA).isEmpty(), Duration.ofSeconds(1));

        ArchimeshSessionManager managerB = new ArchimeshSessionManager();
        managerB.sendSubmitOps(op1);
        managerB.sendSubmitOps(op2);
        ControlledWebSocket wsB = new ControlledWebSocket();
        setField(managerB, "webSocket", wsB);
        setField(managerB, "currentModelId", modelId);
        managerB.setLastKnownRevision(77L);
        awaitTrue(() -> wsB.sentTexts.size() == 1, Duration.ofSeconds(1));
        wsB.completeSend(0, null);
        managerB.onServerOpsAccepted("det-op-1");
        awaitTrue(() -> wsB.sentTexts.size() == 2, Duration.ofSeconds(1));
        wsB.completeSend(1, null);
        managerB.onServerOpsAccepted("det-op-2");
        awaitTrue(() -> queuedPayloads(managerB).isEmpty(), Duration.ofSeconds(1));

        Assertions.assertEquals(wsA.sentTexts, wsB.sentTexts,
                "same head + same queued ops must replay in identical sequence");
        Assertions.assertEquals(77L, extractBaseRevision(wsA.sentTexts.get(0)));
        Assertions.assertEquals(77L, extractBaseRevision(wsA.sentTexts.get(1)));
    }

    private static String submitOps(String modelId, long baseRevision, String opId) {
        return "{"
                + "\"type\":\"SubmitOps\","
                + "\"payload\":{"
                + "\"modelId\":\"" + modelId + "\","
                + "\"baseRevision\":" + baseRevision + ","
                + "\"opBatchId\":\"" + opId + "\","
                + "\"ops\":[{\"op\":\"Noop\"}]"
                + "}"
                + "}";
    }

    private static long extractBaseRevision(String submitOpsEnvelopeJson) {
        String payload = extractObjectField(submitOpsEnvelopeJson, "payload");
        if(payload == null) {
            return -1L;
        }
        String token = "\"baseRevision\":";
        int start = payload.indexOf(token);
        if(start < 0) {
            return -1L;
        }
        int index = start + token.length();
        while(index < payload.length() && Character.isWhitespace(payload.charAt(index))) {
            index++;
        }
        int end = index;
        while(end < payload.length() && Character.isDigit(payload.charAt(end))) {
            end++;
        }
        if(end <= index) {
            return -1L;
        }
        return Long.parseLong(payload.substring(index, end));
    }

    private static String extractObjectField(String json, String fieldName) {
        String marker = "\"" + fieldName + "\":";
        int keyIndex = json.indexOf(marker);
        if(keyIndex < 0) {
            return null;
        }
        int braceStart = json.indexOf('{', keyIndex + marker.length());
        if(braceStart < 0) {
            return null;
        }
        int depth = 0;
        for(int i = braceStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if(c == '{') {
                depth++;
            }
            else if(c == '}') {
                depth--;
                if(depth == 0) {
                    return json.substring(braceStart, i + 1);
                }
            }
        }
        return null;
    }

    @Test
    void authServerErrorStoresUserHint() {
        ArchimeshSessionManager manager = new ArchimeshSessionManager();
        manager.setAuthToken("jwt-token");

        manager.onServerError("AUTH_REQUIRED", "Authenticated subject is required.");

        Assertions.assertNotNull(manager.getLastUserHint());
        Assertions.assertTrue(manager.getLastUserHint().contains("bearer token"), manager.getLastUserHint());
    }

    @SuppressWarnings("unchecked")
    private static List<String> queuedPayloads(ArchimeshSessionManager manager) throws Exception {
        List<Object> entries = outboxEntries(manager);
        List<String> payloads = new ArrayList<>();
        for(Object queuedEntry : entries) {
            Method payloadAccessor = queuedEntry.getClass().getDeclaredMethod("submitOpsJson");
            payloadAccessor.setAccessible(true);
            payloads.add((String)payloadAccessor.invoke(queuedEntry));
        }
        return payloads;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> outboxEntries(ArchimeshSessionManager manager) throws Exception {
        Field outboxField = ArchimeshSessionManager.class.getDeclaredField("offlineOutbox");
        outboxField.setAccessible(true);
        Iterable<Object> outbox = (Iterable<Object>)outboxField.get(manager);
        List<Object> entries = new ArrayList<>();
        for(Object queuedEntry : outbox) {
            entries.add(queuedEntry);
        }
        return entries;
    }

    private static int replayAttempts(Object queuedEntry) throws Exception {
        Method replayAttemptsAccessor = queuedEntry.getClass().getDeclaredMethod("replayAttempts");
        replayAttemptsAccessor.setAccessible(true);
        return (Integer)replayAttemptsAccessor.invoke(queuedEntry);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void awaitTrue(Condition condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline) {
            if(condition.evaluate()) {
                return;
            }
            Thread.sleep(10L);
        }
        Assertions.fail("Condition was not met within " + timeout);
    }

    @FunctionalInterface
    private interface Condition {
        boolean evaluate() throws Exception;
    }

    private static final class ControlledWebSocket implements WebSocket {
        private final CopyOnWriteArrayList<String> sentTexts = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<CompletableFuture<WebSocket>> sendFutures = new CopyOnWriteArrayList<>();
        private volatile boolean failFirstSend;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sentTexts.add(data == null ? "" : data.toString());
            CompletableFuture<WebSocket> future = new CompletableFuture<>();
            if(failFirstSend && sentTexts.size() == 1) {
                future.completeExceptionally(new RuntimeException("simulated send failure"));
            }
            sendFutures.add(future);
            return future;
        }

        void completeSend(int index, Throwable error) {
            CompletableFuture<WebSocket> future = sendFutures.get(index);
            if(error == null) {
                future.complete(this);
            }
            else {
                future.completeExceptionally(error);
            }
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }
    }
}
