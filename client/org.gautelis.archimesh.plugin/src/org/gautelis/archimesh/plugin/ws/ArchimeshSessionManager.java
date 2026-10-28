package org.gautelis.archimesh.plugin.ws;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.Base64;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gautelis.archimesh.plugin.ArchimeshPlugin;
import org.gautelis.archimesh.plugin.emf.ArchimeshModelController;
import org.gautelis.archimesh.plugin.util.ArchimeshAuthHints;
import org.gautelis.archimesh.plugin.util.SimpleJson;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/**
 * Manages one mesh websocket session and a durable per-model outbox.
 * The outbox is drained in FIFO order and advances only after server OpsAccepted.
 */
public class ArchimeshSessionManager {
    private static final long CACHE_SAVE_DEBOUNCE_MS = 2000L;
    private static final String CACHE_DIR_NAME = "archimesh-cache";
    private static final int MAX_OUTBOX_SIZE = 1000;
    private static final int OUTBOX_CAPACITY_WARN_THRESHOLD = (int)(MAX_OUTBOX_SIZE * 0.8);
    private static final String OUTBOX_FILE_SUFFIX = ".outbox.properties";
    private static final long OUTBOX_RETRY_INITIAL_DELAY_MS = 250L;
    private static final long OUTBOX_RETRY_MAX_DELAY_MS = 5000L;
    private static final long OUTBOX_ACK_TIMEOUT_MS = 5000L;

    public interface SessionStateListener {
        void stateChanged(boolean connected, String modelId);
    }

    public interface SubmitConflictListener {
        void conflictDetected(String modelId, String opBatchId, String code, String message);
    }

    public interface OutboxCapacityListener {
        void outboxApproachingCapacity(String modelId, int currentSize, int maxSize);
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final InboundMessageDispatcher inboundMessageDispatcher = new InboundMessageDispatcher(this);
    private final ArchimeshModelController modelController = new ArchimeshModelController(this);

    private volatile WebSocket webSocket;
    private volatile String currentModelId;
    private volatile String currentModelRef = "HEAD";
    private volatile String currentWsBaseUrl = "";
    private volatile IArchimateModel attachedModel;
    private volatile String userId = "anonymous";
    private volatile String sessionId = "archimesh-" + UUID.randomUUID();
    private volatile String authToken = "";
    private volatile long lastKnownRevision;
    private volatile boolean serverBackedSession = true;
    private volatile long lastCacheSaveEpochMillis;
    private volatile boolean pendingCacheRevisionComparison;
    private volatile long cacheRevisionAtJoin = -1;
    private volatile boolean coldSnapshotRebuildRequested;
    private volatile boolean awaitingCheckout;
    private volatile boolean outboxFlushInFlight;
    private volatile boolean outboxRetryScheduled;
    private volatile QueuedSubmitOp outboxAwaitingAck;
    private volatile String outboxAwaitingAckOpBatchId;
    private volatile long outboxAwaitingAckDeadlineEpochMs;
    private volatile ConflictSnapshot lastConflictSnapshot;
    private volatile String lastUserHint;
    private volatile boolean forceColdStartOnNextConnect;
    private final Deque<QueuedSubmitOp> offlineOutbox = new ArrayDeque<>();
    private final CopyOnWriteArrayList<SessionStateListener> sessionStateListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SubmitConflictListener> submitConflictListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<OutboxCapacityListener> outboxCapacityListeners = new CopyOnWriteArrayList<>();

    /**
     * Opens a websocket to the mesh server for the given model at HEAD revision.
     */
    public synchronized void connect(String baseWsUrl, String modelId) {
        connect(baseWsUrl, modelId, "HEAD", false);
    }

    /**
     * Opens a websocket to the mesh server for the given model, optionally forcing a cold start that requests a full snapshot.
     */
    public synchronized void connect(String baseWsUrl, String modelId, boolean forceColdStart) {
        connect(baseWsUrl, modelId, "HEAD", forceColdStart);
    }

    /**
     * Opens a websocket to the mesh server for the given model at the specified revision or branch ref,
     * optionally forcing a cold start that discards any cached projection and requests a full snapshot.
     */
    public synchronized void connect(String baseWsUrl, String modelId, String modelRef, boolean forceColdStart) {
        Objects.requireNonNull(baseWsUrl, "baseWsUrl");
        Objects.requireNonNull(modelId, "modelId");

        disconnect();
        forceColdStartOnNextConnect = forceColdStart;
        String normalizedRef = normalizeModelRef(modelRef);

        URI uri = URI.create(baseWsUrl + "/models/" + modelId + "/stream");

        try {
            clearUserHint();
            cacheRevisionAtJoin = -1;
            pendingCacheRevisionComparison = false;
            coldSnapshotRebuildRequested = false;

            CacheRejoinDecision rejoinDecision = resolveJoinDecision(modelId, normalizedRef);
            if(rejoinDecision.discardCacheProjection()) {
                discardCacheProjection(rejoinDecision.modelId(), normalizedRef, rejoinDecision.reason());
            }
            if("HEAD".equalsIgnoreCase(normalizedRef)) {
                loadOutboxFromDisk(modelId);
            }

            var wsBuilder = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5));
            if(authToken != null && !authToken.isBlank()) {
                wsBuilder.header("Authorization", "Bearer " + authToken.trim());
            }
            webSocket = wsBuilder.buildAsync(uri, new Listener()).join();
            currentWsBaseUrl = baseWsUrl;
            currentModelId = modelId;
            currentModelRef = normalizedRef;
            lastKnownRevision = rejoinDecision.joinRevision() == null ? 0L : rejoinDecision.joinRevision();
            Long joinRevision = rejoinDecision.joinRevision();
            if(joinRevision != null) {
                cacheRevisionAtJoin = joinRevision;
                pendingCacheRevisionComparison = true;
            }
            sendJoin(joinRevision, normalizedRef);
            fireStateChanged(true, modelId);
            ArchimeshPlugin.logInfo("Connected archimesh websocket for model " + modelId
                    + " ref=" + normalizedRef
                    + " mode=" + (serverBackedSession ? "server-backed" : "local-first")
                    + " rejoin=" + rejoinDecision.reason());
        }
        catch(Exception ex) {
            rememberUserHint(ArchimeshAuthHints.describeConnectionFailure(ex, authToken != null && !authToken.isBlank()));
            ArchimeshPlugin.logError("Failed to connect archimesh websocket", ex);
            webSocket = null;
            currentWsBaseUrl = "";
            currentModelId = null;
            currentModelRef = "HEAD";
            forceColdStartOnNextConnect = false;
            fireStateChanged(false, null);
        }
    }

    /**
     * Gracefully closes the websocket, persists the cache snapshot, and fires a disconnected state change.
     */
    public synchronized void disconnect() {
        maybePersistCacheSnapshot("disconnect", true);

        boolean wasConnected = webSocket != null;
        if(webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect").join();
            }
            catch(Exception ex) {
                if(isExpectedCloseException(ex)) {
                    ArchimeshPlugin.logDebug("Archimesh websocket already closed while disconnecting");
                }
                else {
                    ArchimeshPlugin.logError("Error closing archimesh websocket", ex);
                }
            }
        }

        webSocket = null;
        currentModelId = null;
        currentModelRef = "HEAD";
        cacheRevisionAtJoin = -1;
        pendingCacheRevisionComparison = false;
        coldSnapshotRebuildRequested = false;
        outboxAwaitingAck = null;
        outboxAwaitingAckOpBatchId = null;
        outboxAwaitingAckDeadlineEpochMs = 0L;
        if(wasConnected) {
            fireStateChanged(false, null);
        }
    }

    private boolean isExpectedCloseException(Throwable throwable) {
        Throwable cursor = throwable;
        while(cursor != null) {
            String message = cursor.getMessage();
            if(message != null && message.contains("Output closed")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * Sets the user identity and session identifier sent with every operation.
     */
    public synchronized void setActor(String userId, String sessionId) {
        this.userId = userId;
        this.sessionId = sessionId;
    }

    /**
     * Sets the Bearer authentication token sent as a header on websocket connections.
     */
    public synchronized void setAuthToken(String authToken) {
        this.authToken = authToken == null ? "" : authToken.trim();
    }

    /**
     * Attaches a local EMF model to the session, enabling inbound remote-apply and cache persistence.
     */
    public void attachModel(IArchimateModel model) {
        attachedModel = model;
        modelController.attach(model);
        inboundMessageDispatcher.replayBufferedMutationsIfAny();
        maybePersistCacheSnapshot("attach", true);
    }

    /**
     * Detaches the local EMF model, persisting the cache before releasing the reference.
     */
    public void detachModel() {
        maybePersistCacheSnapshot("detach", true);
        attachedModel = null;
        modelController.detach();
    }

    /**
     * Sends a join message using the last known revision at the current model ref,
     * triggering the server to deliver a delta or full snapshot.
     */
    public void sendJoin(Long lastSeenRevision) {
        sendJoin(lastSeenRevision, currentModelRef);
    }

    /**
     * Sends a join message with the last known revision and a specific model ref,
     * triggering the server to deliver a delta or full snapshot.
     */
    public void sendJoin(Long lastSeenRevision, String modelRef) {
        awaitingCheckout = true;
        StringBuilder payload = new StringBuilder("{");
        payload.append("\"type\":\"Join\",");
        payload.append("\"payload\":{");
        if(lastSeenRevision != null) {
            payload.append("\"lastSeenRevision\":").append(lastSeenRevision).append(",");
        }
        payload.append("\"ref\":\"").append(escape(normalizeModelRef(modelRef))).append("\",");
        payload.append("\"actor\":{");
        payload.append("\"userId\":\"").append(escape(userId)).append("\",");
        payload.append("\"sessionId\":\"").append(escape(sessionId)).append("\"");
        payload.append("}");
        payload.append("}");
        payload.append("}");
        if(lastSeenRevision == null) {
            ArchimeshPlugin.logInfo("Sending Join without lastSeenRevision (cold start snapshot requested) ref=" + normalizeModelRef(modelRef));
        }
        else {
            ArchimeshPlugin.logTrace("Sending Join with lastSeenRevision=" + lastSeenRevision + " ref=" + normalizeModelRef(modelRef));
        }
        sendRaw(payload.toString());
    }

    /**
     * Sends or enqueues a batch of operations; falls back to the durable offline outbox when disconnected
     * or when an existing drain is in progress.
     */
    public void sendSubmitOps(String opBatchJson) {
        if(isCurrentReferenceReadOnly()) {
            ArchimeshPlugin.logInfo("Ignoring SubmitOps for read-only model ref modelId=" + currentModelId + " ref=" + currentModelRef);
            return;
        }
        inboundMessageDispatcher.recordLocalOperations(opBatchJson);
        sendSubmitOpsOrQueue(opBatchJson);
    }

    /**
     * Requests an exclusive lock on one or more model targets for a specified TTL in milliseconds.
     */
    public void sendAcquireLock(String targetsJsonArray, long ttlMs) {
        if(isCurrentReferenceReadOnly()) {
            ArchimeshPlugin.logTrace("Ignoring AcquireLock for read-only model ref modelId=" + currentModelId + " ref=" + currentModelRef);
            return;
        }
        String payload = "{" +
                "\"type\":\"AcquireLock\"," +
                "\"payload\":{" +
                "\"actor\":{" +
                "\"userId\":\"" + escape(userId) + "\"," +
                "\"sessionId\":\"" + escape(sessionId) + "\"" +
                "}," +
                "\"targets\":" + targetsJsonArray + "," +
                "\"ttlMs\":" + ttlMs +
                "}" +
                "}";
        sendRaw(payload);
    }

    /**
     * Releases a previously acquired lock on one or more model targets.
     */
    public void sendReleaseLock(String targetsJsonArray) {
        if(isCurrentReferenceReadOnly()) {
            ArchimeshPlugin.logTrace("Ignoring ReleaseLock for read-only model ref modelId=" + currentModelId + " ref=" + currentModelRef);
            return;
        }
        String payload = "{" +
                "\"type\":\"ReleaseLock\"," +
                "\"payload\":{" +
                "\"actor\":{" +
                "\"userId\":\"" + escape(userId) + "\"," +
                "\"sessionId\":\"" + escape(sessionId) + "\"" +
                "}," +
                "\"targets\":" + targetsJsonArray +
                "}" +
                "}";
        sendRaw(payload);
    }

    /**
     * Broadcasts the current user's active view, selected objects, and cursor position to collaborators.
     */
    public void sendPresence(String viewId, String selectionJsonArray, String cursorJson) {
        if(isCurrentReferenceReadOnly()) {
            ArchimeshPlugin.logTrace("Ignoring Presence for read-only model ref modelId=" + currentModelId + " ref=" + currentModelRef);
            return;
        }
        String payload = "{" +
                "\"type\":\"Presence\"," +
                "\"payload\":{" +
                "\"actor\":{" +
                "\"userId\":\"" + escape(userId) + "\"," +
                "\"sessionId\":\"" + escape(sessionId) + "\"" +
                "}," +
                "\"viewId\":\"" + escape(viewId) + "\"," +
                "\"selection\":" + selectionJsonArray + "," +
                "\"cursor\":" + cursorJson +
                "}" +
                "}";
        sendRaw(payload);
    }

    public String getCurrentModelId() {
        return currentModelId;
    }

    public String getCurrentModelRef() {
        return currentModelRef;
    }

    public String getCurrentWsBaseUrl() {
        return currentWsBaseUrl;
    }

    public boolean isCurrentReferenceReadOnly() {
        return currentModelRef != null && !"HEAD".equalsIgnoreCase(currentModelRef);
    }

    public IArchimateModel getAttachedModel() {
        return attachedModel;
    }

    public boolean isConnected() {
        return webSocket != null;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAuthToken() {
        return authToken;
    }

    public String getLastUserHint() {
        return lastUserHint;
    }

    public long getLastKnownRevision() {
        return lastKnownRevision;
    }

    /**
     * Records the latest server revision, triggering cache staleness comparison and outbox flush if possible.
     */
    public synchronized void setLastKnownRevision(long lastKnownRevision) {
        long normalized = Math.max(0L, lastKnownRevision);
        evaluateCacheRevisionComparison(normalized);
        this.lastKnownRevision = Math.max(normalized, this.lastKnownRevision);
        flushOutboxIfPossible();
    }

    public boolean isServerBackedSession() {
        return serverBackedSession;
    }

    /**
     * Sets whether the session uses server-backed mode (cache persisted to disk) or local-first mode.
     */
    public synchronized void setServerBackedSession(boolean serverBackedSession) {
        this.serverBackedSession = serverBackedSession;
    }

    /**
     * Registers a listener for session connection state changes.
     */
    public void addSessionStateListener(SessionStateListener listener) {
        if(listener != null) {
            sessionStateListeners.addIfAbsent(listener);
        }
    }

    /**
     * Unregisters a session state listener.
     */
    public void removeSessionStateListener(SessionStateListener listener) {
        if(listener != null) {
            sessionStateListeners.remove(listener);
        }
    }

    /**
     * Registers a listener for submit conflict events (e.g. precondition failures, lock conflicts).
     */
    public void addSubmitConflictListener(SubmitConflictListener listener) {
        if(listener != null) {
            submitConflictListeners.addIfAbsent(listener);
        }
    }

    /**
     * Unregisters a submit conflict listener.
     */
    public void removeSubmitConflictListener(SubmitConflictListener listener) {
        if(listener != null) {
            submitConflictListeners.remove(listener);
        }
    }

    /**
     * Registers a listener that fires when the offline outbox approaches capacity.
     */
    public void addOutboxCapacityListener(OutboxCapacityListener listener) {
        if(listener != null) {
            outboxCapacityListeners.addIfAbsent(listener);
        }
    }

    /**
     * Unregisters an outbox capacity listener.
     */
    public void removeOutboxCapacityListener(OutboxCapacityListener listener) {
        if(listener != null) {
            outboxCapacityListeners.remove(listener);
        }
    }

    public ConflictSnapshot getLastConflictSnapshot() {
        return lastConflictSnapshot;
    }

    private void fireStateChanged(boolean connected, String modelId) {
        for(SessionStateListener listener : sessionStateListeners) {
            listener.stateChanged(connected, modelId);
        }
    }

    private synchronized void sendSubmitOpsOrQueue(String submitOpsJson) {
        if(submitOpsJson == null || submitOpsJson.isBlank()) {
            return;
        }
        String modelId = extractModelId(submitOpsJson);
        if(modelId == null || modelId.isBlank()) {
            modelId = currentModelId;
        }
        if(modelId == null || modelId.isBlank()) {
            ArchimeshPlugin.logInfo("Dropping SubmitOps without modelId");
            return;
        }

        WebSocket ws = webSocket;
        if(ws == null) {
            enqueueOutbox(modelId, submitOpsJson, "websocket-disconnected");
            return;
        }
        if(currentModelId != null && !currentModelId.isBlank() && !currentModelId.equals(modelId)) {
            enqueueOutbox(modelId, submitOpsJson, "model-mismatch");
            return;
        }
        enqueueOutbox(modelId, submitOpsJson, "awaiting-commit");
        flushOutboxIfPossible();
    }

    private CompletableFuture<WebSocket> sendWithNotationLease(WebSocket ws, String submit) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(submit, "payload"));
        List<String> targets = new ArrayList<>();
        for (String op : SimpleJson.readArrayObjectElements(payload, "ops")) {
            String type = SimpleJson.readStringField(op, "type");
            String target = "UpdateViewObjectOpaque".equals(type) ? SimpleJson.readStringField(op, "viewObjectId")
                    : "UpdateConnectionOpaque".equals(type) ? SimpleJson.readStringField(op, "connectionId") : null;
            if (target != null) targets.add("\"" + escape(target) + "\"");
        }
        if (targets.isEmpty()) return ws.sendText(submit, true);
        String targetJson = "[" + String.join(",", targets) + "]";
        String actor = SimpleJson.readRawField(payload, "actor");
        String acquire = "{\"type\":\"AcquireLock\",\"payload\":{\"actor\":" + actor
                + ",\"targets\":" + targetJson + ",\"ttlMs\":10000}}";
        String release = "{\"type\":\"ReleaseLock\",\"payload\":{\"actor\":" + actor
                + ",\"targets\":" + targetJson + "}}";
        return ws.sendText(acquire, true).thenCompose(ignored -> ws.sendText(submit, true))
                .thenCompose(ignored -> ws.sendText(release, true));
    }

    public synchronized void completeCheckout() {
        awaitingCheckout = false;
        flushOutboxIfPossible();
    }

    public synchronized void reapplyQueuedOperations() {
        for (QueuedSubmitOp queued : offlineOutbox) {
            if (Objects.equals(queued.modelId, currentModelId)) inboundMessageDispatcher.recordLocalOperations(queued.submitOpsJson);
        }
    }

    private synchronized void enqueueOutbox(String modelId, String submitOpsJson, String reason) {
        if(offlineOutbox.size() >= MAX_OUTBOX_SIZE) {
            rememberUserHint("The collaboration queue is full. Reconnect before making more shared edits.");
            throw new IllegalStateException("Outbox full; existing uncommitted edits have been preserved");
        }
        offlineOutbox.addLast(new QueuedSubmitOp(modelId, submitOpsJson, System.currentTimeMillis(), 0, 0L));
        persistOutboxToDisk(modelId);
        ArchimeshPlugin.logInfo("Queued SubmitOps for offline replay modelId=" + modelId
                + " queueSize=" + offlineOutbox.size()
                + " reason=" + reason);
        if(offlineOutbox.size() >= OUTBOX_CAPACITY_WARN_THRESHOLD) {
            for(OutboxCapacityListener listener : outboxCapacityListeners) {
                listener.outboxApproachingCapacity(modelId, offlineOutbox.size(), MAX_OUTBOX_SIZE);
            }
        }
    }

    private synchronized void flushOutboxIfPossible() {
        if(offlineOutbox.isEmpty()) {
            return;
        }
        if(isCurrentReferenceReadOnly()) {
            return;
        }
        if(outboxFlushInFlight || awaitingCheckout) {
            return;
        }
        WebSocket ws = webSocket;
        if(ws == null) {
            return;
        }
        if(currentModelId == null || currentModelId.isBlank()) {
            return;
        }
        if(lastKnownRevision < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if(outboxAwaitingAck != null) {
            // At most one in-flight replayed entry at a time; timeout retries the same head entry
            if(outboxAwaitingAckDeadlineEpochMs > now) {
                scheduleOutboxRetry(outboxAwaitingAckDeadlineEpochMs - now);
                return;
            }
            QueuedSubmitOp timedOut = outboxAwaitingAck;
            outboxAwaitingAck = null;
            outboxAwaitingAckOpBatchId = null;
            outboxAwaitingAckDeadlineEpochMs = 0L;
            handleOutboxReplaySendResult(timedOut, new RuntimeException("ack-timeout"));
            return;
        }

        QueuedSubmitOp next = firstQueuedEntryForModel(currentModelId);
        if(next == null) {
            return;
        }
        if(next.nextRetryEpochMs > now) {
            scheduleOutboxRetry(next.nextRetryEpochMs - now);
            return;
        }

        outboxFlushInFlight = true;
        String rebased = rebaseSubmitOpsBaseRevision(next.submitOpsJson, lastKnownRevision);
        ArchimeshPlugin.logInfo("Replaying queued offline op modelId=" + currentModelId
                + " rebaseRevision=" + lastKnownRevision);
        ArchimeshPlugin.logTrace("WS OUT " + summarizeEnvelope(rebased));
        outboxAwaitingAck = next;
        outboxAwaitingAckOpBatchId = extractOpBatchId(next.submitOpsJson);
        outboxAwaitingAckDeadlineEpochMs = System.currentTimeMillis() + OUTBOX_ACK_TIMEOUT_MS;
        sendWithNotationLease(ws, rebased).whenComplete((ignored, ex) -> handleOutboxReplaySendResult(next, ex));
    }

    private void handleOutboxReplaySendResult(QueuedSubmitOp replayed, Throwable error) {
        synchronized(this) {
            outboxFlushInFlight = false;
            if(error == null) {
                if (outboxAwaitingAck == replayed) scheduleOutboxRetry(OUTBOX_ACK_TIMEOUT_MS);
            } else if (offlineOutbox.contains(replayed)) {
                outboxAwaitingAck = null;
                outboxAwaitingAckOpBatchId = null;
                outboxAwaitingAckDeadlineEpochMs = 0L;
                offlineOutbox.remove(replayed);
                int attempts = Math.min(30, replayed.replayAttempts + 1);
                long retryDelayMs = computeOutboxRetryDelayMs(attempts);
                offlineOutbox.addFirst(new QueuedSubmitOp(replayed.modelId, replayed.submitOpsJson,
                        replayed.queuedAtEpochMs, attempts, System.currentTimeMillis() + retryDelayMs));
                persistOutboxToDisk(replayed.modelId);
                scheduleOutboxRetry(retryDelayMs);
            }
        }
        flushOutboxIfPossible();
    }

    private QueuedSubmitOp firstQueuedEntryForModel(String modelId) {
        for(QueuedSubmitOp queued : offlineOutbox) {
            if(modelId.equals(queued.modelId)) {
                return queued;
            }
        }
        return null;
    }

    private boolean hasQueuedEntriesForModel(String modelId) {
        return firstQueuedEntryForModel(modelId) != null;
    }

    private long computeOutboxRetryDelayMs(int attempts) {
        if(attempts <= 0) {
            return 0L;
        }
        long delay = OUTBOX_RETRY_INITIAL_DELAY_MS;
        for(int i = 1; i < attempts; i++) {
            delay = Math.min(OUTBOX_RETRY_MAX_DELAY_MS, delay * 2L);
        }
        return delay;
    }

    private void scheduleOutboxRetry(long delayMs) {
        long boundedDelayMs = Math.max(1L, Math.min(OUTBOX_RETRY_MAX_DELAY_MS, delayMs));
        synchronized(this) {
            if(outboxRetryScheduled) {
                return;
            }
            outboxRetryScheduled = true;
        }
        CompletableFuture.runAsync(
                this::retryOutboxFlushAfterDelay,
                CompletableFuture.delayedExecutor(boundedDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS))
                .exceptionally(ex -> {
                    synchronized(this) {
                        outboxRetryScheduled = false;
                    }
                    return null;
                });
    }

    private void retryOutboxFlushAfterDelay() {
        synchronized(this) {
            outboxRetryScheduled = false;
        }
        flushOutboxIfPossible();
    }

    /**
     * Called when the server acknowledges an outbox-replayed operation batch, allowing the next
     * queued batch to be flushed.
     */
    public void onServerOpsAccepted(String opBatchId) {
        boolean advanced = false;
        synchronized(this) {
            if(outboxAwaitingAck != null && Objects.equals(opBatchId, outboxAwaitingAckOpBatchId)) {
                QueuedSubmitOp accepted = outboxAwaitingAck;
                outboxAwaitingAck = null;
                outboxAwaitingAckOpBatchId = null;
                outboxAwaitingAckDeadlineEpochMs = 0L;
                offlineOutbox.remove(accepted);
                persistOutboxToDisk(accepted.modelId);
                advanced = true;
            }
        }
        if(advanced) {
            flushOutboxIfPossible();
        }
    }

    /**
     * Called when the server rejects an operation batch with an error. Drops conflicting entries
     * (PRECONDITION_FAILED, LOCK_CONFLICT) and retries other transient failures.
     */
    public void onServerError(String code, String message) {
        // Legacy callers may show a hint, but an uncorrelated error cannot reject queued work.
        onServerError(code, message, null);
    }

    public void onServerError(String code, String message, String opBatchId) {
        rememberUserHint(ArchimeshAuthHints.describeServerError(code, message,
                authToken != null && !authToken.isBlank(), isCurrentReferenceReadOnly()));
        boolean retry = false;
        boolean droppedConflict = false;
        String conflictModelId = null;
        String conflictOpBatchId = null;
        synchronized(this) {
            if(outboxAwaitingAck == null || opBatchId == null
                    || !Objects.equals(opBatchId, outboxAwaitingAckOpBatchId)) {
                return;
            }
            QueuedSubmitOp waiting = outboxAwaitingAck;
            String waitingOpBatchId = outboxAwaitingAckOpBatchId;
            outboxAwaitingAck = null;
            outboxAwaitingAckOpBatchId = null;
            outboxAwaitingAckDeadlineEpochMs = 0L;

            if("PRECONDITION_FAILED".equals(code) || "LOCK_CONFLICT".equals(code)) {
                // Deterministic conflict handling: drop stale/conflicting intent and continue queue
                offlineOutbox.remove(waiting);
                persistOutboxToDisk(waiting.modelId);
                droppedConflict = true;
                conflictModelId = waiting.modelId;
                conflictOpBatchId = waitingOpBatchId;
            }
            else {
                retry = true;
            }
        }

        if(droppedConflict) {
            fireSubmitConflict(conflictModelId, conflictOpBatchId, code, message);
            if (currentWsBaseUrl != null && !currentWsBaseUrl.isBlank()) sendJoin(null);
            else flushOutboxIfPossible();
        }
        if(retry) {
            if ("REVISION_AHEAD".equals(code)) sendJoin(null);
            else flushOutboxIfPossible();
        }
    }

    private void fireSubmitConflict(String modelId, String opBatchId, String code, String message) {
        lastConflictSnapshot = new ConflictSnapshot(modelId, opBatchId, code, message, System.currentTimeMillis());
        ArchimeshPlugin.logInfo("Dropping stale queued SubmitOps due to server conflict modelId="
                + modelId + " opBatchId=" + opBatchId + " code=" + code + " message=" + message);
        for(SubmitConflictListener listener : submitConflictListeners) {
            listener.conflictDetected(modelId, opBatchId, code, message);
        }
    }

    private String extractModelId(String submitOpsJson) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(submitOpsJson, "payload"));
        return payload == null ? null : SimpleJson.readStringField(payload, "modelId");
    }

    private String extractOpBatchId(String submitOpsJson) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(submitOpsJson, "payload"));
        return payload == null ? null : SimpleJson.readStringField(payload, "opBatchId");
    }

    private String rebaseSubmitOpsBaseRevision(String submitOpsJson, long revision) {
        Pattern pattern = Pattern.compile("(\\\"baseRevision\\\"\\s*:\\s*)\\d+");
        Matcher matcher = pattern.matcher(submitOpsJson);
        if(!matcher.find()) {
            return submitOpsJson;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + Math.max(0L, revision)));
    }

    private void sendRaw(String payload) {
        WebSocket ws = webSocket;
        if(ws == null) {
            ArchimeshPlugin.logTrace("sendRaw skipped: websocket not connected payload=" + summarizeEnvelope(payload));
            return;
        }

        ArchimeshPlugin.logTrace("WS OUT " + summarizeEnvelope(payload));
        ws.sendText(payload, true).exceptionally(ex -> {
            ArchimeshPlugin.logError("Failed sending Archimesh message", ex);
            return null;
        });
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String normalizeModelRef(String modelRef) {
        if(modelRef == null || modelRef.isBlank()) {
            return "HEAD";
        }
        return modelRef.trim();
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder fragments = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if(last) {
                String message = fragments.toString();
                ArchimeshPlugin.logTrace("WS IN " + summarizeEnvelope(message));
                if(ArchimeshPlugin.isTraceEnabled()) {
                    String type = SimpleJson.readStringField(message, "type");
                    if("OpsBroadcast".equals(type)) {
                        ArchimeshPlugin.logTrace("WS IN RAW OpsBroadcast " + message);
                    }
                }
                inboundMessageDispatcher.dispatch(message);
                String type = SimpleJson.readStringField(message, "type");
                if("CheckoutSnapshot".equals(type)
                        || "CheckoutDelta".equals(type)
                        || "OpsBroadcast".equals(type)
                        || "OpsAccepted".equals(type)) {
                    maybePersistCacheSnapshot(type, false);
                }
                fragments.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ArchimeshPlugin.logError("Archimesh websocket listener error", error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            synchronized(ArchimeshSessionManager.this) {
                if(ArchimeshSessionManager.this.webSocket == webSocket) {
                    ArchimeshSessionManager.this.webSocket = null;
                }
            }
            if(statusCode == 1008 && (reason != null && !reason.isBlank())) {
                rememberUserHint(ArchimeshAuthHints.describeServerError(null, reason,
                        authToken != null && !authToken.isBlank(), isCurrentReferenceReadOnly()));
            }
            fireStateChanged(false, currentModelId);
            ArchimeshPlugin.logInfo("Archimesh websocket closed statusCode=" + statusCode + " reason=" + reason);
            return CompletableFuture.completedFuture(null);
        }
    }

    private synchronized void rememberUserHint(String hint) {
        lastUserHint = hint == null ? null : hint.trim();
    }

    private synchronized void clearUserHint() {
        lastUserHint = null;
    }

    private CacheRejoinDecision resolveJoinDecision(String modelId, String modelRef) {
        if(forceColdStartOnNextConnect) {
            forceColdStartOnNextConnect = false;
            return new CacheRejoinDecision(modelId, null, false, "forced-cold-start");
        }
        if(serverBackedSession) {
            // In server-backed mode we only trust cache metadata that matches the target model/session mode.
            CacheMetadata metadata = readCacheMetadata(modelId, modelRef);
            if(metadata == null) {
                return new CacheRejoinDecision(modelId, null, false, "no-cache-metadata");
            }
            if(!metadata.serverBacked) {
                return new CacheRejoinDecision(modelId, null, true, "cache-not-server-backed");
            }
            if(!Objects.equals(modelId, metadata.modelId)) {
                return new CacheRejoinDecision(modelId, null, true, "cache-modelId-mismatch");
            }
            if(!Objects.equals(normalizeModelRef(modelRef), metadata.modelRef)) {
                return new CacheRejoinDecision(modelId, null, true, "cache-modelRef-mismatch");
            }
            if(!metadata.cacheFile.exists()) {
                return new CacheRejoinDecision(modelId, null, true, "cache-file-missing");
            }
            if(metadata.revision >= 1 && metadata.cacheFile.length() < 1024L) {
                // File exists but is implausibly small for a model with revision >= 1;
                // treat as corrupted/incomplete and discard so server sends a fresh snapshot.
                ArchimeshPlugin.logInfo("Cache file appears truncated or empty for revision="
                        + metadata.revision + " size=" + metadata.cacheFile.length()
                        + " — discarding stale projection");
                return new CacheRejoinDecision(modelId, null, true, "cache-file-too-small");
            }
            if(metadata.revision < 0) {
                return new CacheRejoinDecision(modelId, null, true, "cache-revision-unknown");
            }
            return new CacheRejoinDecision(modelId, metadata.revision, false, "cache-revision-rejoin");
        }
        return new CacheRejoinDecision(modelId, lastKnownRevision, false, "local-lastKnownRevision");
    }

    private void evaluateCacheRevisionComparison(long serverRevision) {
        if(!pendingCacheRevisionComparison) {
            return;
        }
        pendingCacheRevisionComparison = false;

        if(cacheRevisionAtJoin < 0) {
            return;
        }
        if(serverRevision > cacheRevisionAtJoin) {
            ArchimeshPlugin.logInfo("Cache stale on reconnect: cacheRevision=" + cacheRevisionAtJoin
                    + " serverRevision=" + serverRevision + " (delta/snapshot refresh expected)");
            return;
        }
        if(serverRevision == cacheRevisionAtJoin) {
            ArchimeshPlugin.logTrace("Cache revision matches server head on reconnect: revision=" + serverRevision);
            return;
        }

        ArchimeshPlugin.logInfo("Cache revision inconsistent with server head: cacheRevision="
                + cacheRevisionAtJoin + " serverRevision=" + serverRevision
                + " (requesting cold snapshot rebuild)");
        requestColdSnapshotRebuild();
    }

    private synchronized void requestColdSnapshotRebuild() {
        if(coldSnapshotRebuildRequested) {
            return;
        }
        if(webSocket == null) {
            return;
        }
        coldSnapshotRebuildRequested = true;
        sendJoin(null);
    }

    private void maybePersistCacheSnapshot(String reason, boolean force) {
        if(!serverBackedSession) {
            return;
        }
        if(!canPersistCacheSnapshot()) {
            ArchimeshPlugin.logTrace("Skipping archimesh cache persist while workbench is shutting down: reason=" + reason);
            return;
        }
        IArchimateModel model = attachedModel;
        String modelId = currentModelId;
        String modelRef = currentModelRef;
        if(model == null || modelId == null || modelId.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        if(!force && now - lastCacheSaveEpochMillis < CACHE_SAVE_DEBOUNCE_MS) {
            // Avoid a save storm while the model is receiving many incremental updates
            return;
        }

        boolean executed = runOnUiThreadSync(() -> {
            try {
                if(!canPersistCacheSnapshot()) {
                    ArchimeshPlugin.logTrace("Skipping archimesh cache persist on UI thread during shutdown: reason=" + reason);
                    return;
                }
                File cacheFile = resolveCacheFile(modelId, modelRef);
                if(cacheFile == null) {
                    return;
                }

                File parent = cacheFile.getParentFile();
                if(parent != null && !parent.exists() && !parent.mkdirs()) {
                    ArchimeshPlugin.logDebug("Could not create archimesh cache directory: " + parent);
                    return;
                }

                if(model.getFile() == null || !cacheFile.equals(model.getFile())) {
                    model.setFile(cacheFile);
                }

                boolean dirty = IEditorModelManager.INSTANCE.isModelDirty(model);
                if(dirty || force) {
                    IEditorModelManager.INSTANCE.saveModel(model);
                }
                writeCacheMetadata(cacheFile, modelId, modelRef, lastKnownRevision);
                lastCacheSaveEpochMillis = System.currentTimeMillis();
                ArchimeshPlugin.logTrace("Archimesh cache persisted reason=" + reason
                        + " modelId=" + modelId + " ref=" + modelRef + " revision=" + lastKnownRevision + " dirty=" + dirty);
            }
            catch(Exception ex) {
                ArchimeshPlugin.logError("Error persisting archimesh cache", ex);
            }
        });
        if(!executed) {
            ArchimeshPlugin.logTrace("Skipping archimesh cache persist because UI thread is unavailable: reason=" + reason);
        }
    }

    private File resolveCacheFile(String modelId, String modelRef) {
        String safeCacheKey = sanitizeCacheKeyForFileName(modelId, modelRef);
        if(safeCacheKey.isBlank()) {
            return null;
        }
        String userHome = System.getProperty("user.home", "");
        if(userHome.isBlank()) {
            return null;
        }
        Path path = Path.of(userHome, "Archi", CACHE_DIR_NAME, safeCacheKey + ".archimate");
        return path.toFile();
    }

    private String sanitizeCacheKeyForFileName(String modelId, String modelRef) {
        String cacheKey = (modelId == null ? "" : modelId) + "__" + normalizeModelRef(modelRef);
        return cacheKey.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void writeCacheMetadata(File cacheFile, String modelId, String modelRef, long revision) throws IOException {
        if(cacheFile == null) {
            return;
        }
        File metadataFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".meta.properties");
        Properties properties = new Properties();
        properties.setProperty("modelId", modelId == null ? "" : modelId);
        properties.setProperty("modelRef", normalizeModelRef(modelRef));
        properties.setProperty("lastKnownRevision", String.valueOf(revision));
        properties.setProperty("serverBacked", String.valueOf(serverBackedSession));
        properties.setProperty("savedAtEpochMs", String.valueOf(System.currentTimeMillis()));
        try(FileOutputStream outputStream = new FileOutputStream(metadataFile)) {
            properties.store(outputStream, "Archimesh cache metadata");
        }
    }

    private CacheMetadata readCacheMetadata(String modelId, String modelRef) {
        File cacheFile = resolveCacheFile(modelId, modelRef);
        if(cacheFile == null) {
            return null;
        }
        File metadataFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".meta.properties");
        if(!metadataFile.exists()) {
            return null;
        }

        Properties properties = new Properties();
        try(FileInputStream inputStream = new FileInputStream(metadataFile)) {
            properties.load(inputStream);
        }
        catch(IOException ex) {
            ArchimeshPlugin.logInfo("Failed reading archimesh cache metadata, forcing snapshot rebuild: " + ex.getMessage());
            return new CacheMetadata(cacheFile, metadataFile, modelId, normalizeModelRef(modelRef), -1L, false);
        }

        String metadataModelId = properties.getProperty("modelId", "");
        String metadataModelRef = normalizeModelRef(properties.getProperty("modelRef", "HEAD"));
        long metadataRevision = parseLong(properties.getProperty("lastKnownRevision"), -1L);
        boolean metadataServerBacked = Boolean.parseBoolean(properties.getProperty("serverBacked", "false"));
        return new CacheMetadata(cacheFile, metadataFile, metadataModelId, metadataModelRef, metadataRevision, metadataServerBacked);
    }

    private long parseLong(String value, long fallback) {
        if(value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        }
        catch(NumberFormatException ex) {
            return fallback;
        }
    }

    private void discardCacheProjection(String modelId, String modelRef, String reason) {
        File cacheFile = resolveCacheFile(modelId, modelRef);
        if(cacheFile == null) {
            return;
        }
        File metadataFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".meta.properties");
        boolean removedCache = !cacheFile.exists() || cacheFile.delete();
        boolean removedMetadata = !metadataFile.exists() || metadataFile.delete();
        ArchimeshPlugin.logInfo("Discarded archimesh cache projection reason=" + reason
                + " modelId=" + modelId
                + " ref=" + modelRef
                + " cacheDeleted=" + removedCache
                + " metadataDeleted=" + removedMetadata
                + " outboxPreserved=true");
    }

    private synchronized void loadOutboxFromDisk(String modelId) {
        File outboxFile = resolveOutboxFile(modelId);
        if(outboxFile == null || !outboxFile.exists()) {
            return;
        }
        // Prevent duplicates on reconnect by clearing in-memory entries for this model first.
        offlineOutbox.removeIf(queued -> modelId.equals(queued.modelId));

        Properties properties = new Properties();
        try(FileInputStream inputStream = new FileInputStream(outboxFile)) {
            properties.load(inputStream);
        }
        catch(IOException ex) {
            ArchimeshPlugin.logInfo("Could not read durable outbox for modelId=" + modelId + " reason=" + ex.getMessage());
            return;
        }

        int count = (int)parseLong(properties.getProperty("count"), 0L);
        if(count <= 0) {
            return;
        }

        int loaded = 0;
        for(int i = 0; i < count; i++) {
            String encoded = properties.getProperty("item." + i + ".payload");
            if(encoded == null || encoded.isBlank()) {
                continue;
            }
            String json = decodeBase64Utf8(encoded);
            if(json == null || json.isBlank()) {
                continue;
            }
            long queuedAt = parseLong(properties.getProperty("item." + i + ".queuedAtEpochMs"), System.currentTimeMillis());
            int replayAttempts = (int)parseLong(properties.getProperty("item." + i + ".replayAttempts"), 0L);
            long nextRetryEpochMs = parseLong(properties.getProperty("item." + i + ".nextRetryEpochMs"), 0L);
            if(offlineOutbox.size() >= MAX_OUTBOX_SIZE) {
                offlineOutbox.removeFirst();
            }
            offlineOutbox.addLast(new QueuedSubmitOp(modelId, json, queuedAt, replayAttempts, nextRetryEpochMs));
            loaded++;
        }

        if(loaded > 0) {
            ArchimeshPlugin.logInfo("Loaded durable outbox entries modelId=" + modelId + " count=" + loaded);
        }
    }

    private synchronized void persistOutboxToDisk(String modelId) {
        File outboxFile = resolveOutboxFile(modelId);
        if(outboxFile == null) {
            return;
        }
        File parent = outboxFile.getParentFile();
        if(parent != null && !parent.exists() && !parent.mkdirs()) {
            ArchimeshPlugin.logDebug("Could not create outbox directory: " + parent);
            return;
        }

        List<QueuedSubmitOp> modelEntries = new ArrayList<>();
        for(QueuedSubmitOp queued : offlineOutbox) {
            if(modelId.equals(queued.modelId)) {
                modelEntries.add(queued);
            }
        }
        if(modelEntries.isEmpty()) {
            if(outboxFile.exists() && !outboxFile.delete()) {
                ArchimeshPlugin.logDebug("Could not delete empty durable outbox file: " + outboxFile);
            }
            return;
        }

        Properties properties = new Properties();
        properties.setProperty("modelId", modelId);
        properties.setProperty("count", String.valueOf(modelEntries.size()));
        for(int i = 0; i < modelEntries.size(); i++) {
            QueuedSubmitOp queued = modelEntries.get(i);
            properties.setProperty("item." + i + ".queuedAtEpochMs", String.valueOf(queued.queuedAtEpochMs));
            properties.setProperty("item." + i + ".replayAttempts", String.valueOf(queued.replayAttempts));
            properties.setProperty("item." + i + ".nextRetryEpochMs", String.valueOf(queued.nextRetryEpochMs));
            properties.setProperty("item." + i + ".payload", encodeBase64Utf8(queued.submitOpsJson));
        }
        try {
            Path temporary = java.nio.file.Files.createTempFile(outboxFile.toPath().getParent(), "outbox-", ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                properties.store(output, "Archimesh durable outbox");
                output.getFD().sync();
            }
            try {
                java.nio.file.Files.move(temporary, outboxFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                java.nio.file.Files.move(temporary, outboxFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch(IOException ex) {
            throw new IllegalStateException("Could not persist uncommitted edits for model " + modelId, ex);
        }
    }

    private File resolveOutboxFile(String modelId) {
        File cacheFile = resolveCacheFile(modelId, "HEAD");
        if(cacheFile == null) {
            return null;
        }
        return new File(cacheFile.getParentFile(), cacheFile.getName() + OUTBOX_FILE_SUFFIX);
    }

    private String encodeBase64Utf8(String value) {
        if(value == null) {
            return "";
        }
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeBase64Utf8(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return new String(decoded, StandardCharsets.UTF_8);
        }
        catch(IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean runOnUiThreadSync(Runnable runnable) {
        Display display = getDisplay();
        if(display == null || display.isDisposed()) {
            return false;
        }
        if(Thread.currentThread() == display.getThread()) {
            runnable.run();
            return true;
        }
        try {
            display.syncExec(() -> {
                if(!display.isDisposed()) {
                    runnable.run();
                }
            });
            return true;
        }
        catch(SWTException ex) {
            ArchimeshPlugin.logDebug("Skipping archimesh cache save due to SWT shutdown: " + ex.getMessage());
            return false;
        }
    }

    private boolean canPersistCacheSnapshot() {
        if(!isWorkbenchRunning()) {
            return false;
        }
        Display display = getDisplay();
        return display != null && !display.isDisposed();
    }

    protected boolean isWorkbenchRunning() {
        return PlatformUI.isWorkbenchRunning();
    }

    protected Display getDisplay() {
        return Display.getDefault();
    }

    private String summarizeEnvelope(String json) {
        if(json == null || json.isBlank()) {
            return "empty";
        }
        String type = SimpleJson.readStringField(json, "type");
        if(type == null) {
            return "type=?";
        }
        StringBuilder summary = new StringBuilder("type=").append(type);
        if("SubmitOps".equals(type)) {
            String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(json, "payload"));
            String modelId = payload == null ? null : SimpleJson.readStringField(payload, "modelId");
            String opBatchId = payload == null ? null : SimpleJson.readStringField(payload, "opBatchId");
            int opCount = payload == null ? 0 : SimpleJson.readArrayObjectElements(payload, "ops").size();
            summary.append(" modelId=").append(modelId).append(" opBatchId=").append(opBatchId).append(" opCount=").append(opCount);
        }
        else if("OpsBroadcast".equals(type)) {
            String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(json, "payload"));
            String opBatch = payload == null ? null : SimpleJson.asJsonObject(SimpleJson.readRawField(payload, "opBatch"));
            if(opBatch == null && payload != null && SimpleJson.readRawField(payload, "ops") != null) {
                opBatch = payload;
            }
            if(opBatch == null) {
                opBatch = SimpleJson.asJsonObject(SimpleJson.readRawField(json, "opBatch"));
            }
            String opBatchId = opBatch == null ? null : SimpleJson.readStringField(opBatch, "opBatchId");
            int opCount = opBatch == null ? 0 : SimpleJson.readArrayObjectElements(opBatch, "ops").size();
            summary.append(" opBatchId=").append(opBatchId).append(" opCount=").append(opCount);
        }
        return summary.toString();
    }

    private record CacheMetadata(
            File cacheFile,
            File metadataFile,
            String modelId,
            String modelRef,
            long revision,
            boolean serverBacked) {
    }

    private record CacheRejoinDecision(
            String modelId,
            Long joinRevision,
            boolean discardCacheProjection,
            String reason) {
    }

    private record QueuedSubmitOp(
            String modelId,
            String submitOpsJson,
            long queuedAtEpochMs,
            int replayAttempts,
            long nextRetryEpochMs) {
    }

    public record ConflictSnapshot(
            String modelId,
            String opBatchId,
            String code,
            String message,
            long occurredAtEpochMs) {
    }
}
