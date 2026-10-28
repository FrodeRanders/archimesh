package org.gautelis.archimesh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gautelis.archimesh.model.*;
import org.gautelis.archimesh.endpoint.ArchimeshEndpoint;
import org.gautelis.archimesh.wire.ServerEnvelope;
import org.gautelis.archimesh.wire.inbound.*;
import org.gautelis.archimesh.wire.outbound.*;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.Session;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Application-scoped service that orchestrates model operations, WebSocket sessions,
 * revision tracking, lock management, and multi-client broadcast for collaborative
 * ArchiMate model editing.
 */
@ApplicationScoped
@io.quarkus.runtime.Startup
public class ArchimeshService {
    private static final Logger LOG = LoggerFactory.getLogger(ArchimeshService.class);
    private static final long DEFAULT_LOCK_TTL_MS = 10_000;
    private static final int MAX_ACTIVITY_EVENTS_PER_MODEL = 300;
    private static final int DEFAULT_WINDOW_LIMIT = 25;
    private static final String HEAD_REF = "HEAD";
    private static final String MODEL_EXPORT_FORMAT = "archi-model-export-v1";
    private static final String CHECKPOINT_EXPORT_FORMAT = "archi-model-export-v2";

    @Inject
    ValidationService validationService;

    @Inject
    RevisionService revisionService;

    @Inject
    LockService lockService;

    @Inject
    Neo4jRepository neo4jRepository;

    @Inject
    KafkaPublisher kafkaPublisher;

    @Inject
    KafkaConsumer kafkaConsumer;

    @Inject
    SessionRegistry sessionRegistry;

    @Inject
    IdempotencyService idempotencyService;

    @Inject
    CommitDeliveryService commitDeliveryService;

    @ConfigProperty(name = "app.consistency.check-enabled", defaultValue = "false")
    boolean consistencyChecksEnabled;

    @ConfigProperty(name = "app.compaction.retain-revisions", defaultValue = "1000")
    long defaultCompactionRetainRevisions;

    @ConfigProperty(name = "app.tags.allow-delete", defaultValue = "false")
    boolean allowTagDelete;

    // Positive-only cache: existing models are cheap after first lookup, but misses must re-check so
    // freshly created models become visible without a negative-cache invalidation path.
    private final Set<String> registeredModelCache = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, ModelAccessControl> modelAccessControlCache = new ConcurrentHashMap<>();
    // Session state is tracked by websocket session id because the actor session id is client-provided
    // and may be reused across reconnects.
    private final ConcurrentHashMap<String, JoinedModelRef> joinedRefsBySessionKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> actorSessionIdByWebsocketSessionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> modelIdByWebsocketSessionId = new ConcurrentHashMap<>();
    // Diagnostics are keyed by websocket session id, not actor session id, because actor session ids are
    // client-supplied and may legitimately repeat across reconnects.
    private final ConcurrentHashMap<String, AdminActiveSession> joinedSessionDiagnosticsByWebsocketSessionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<AdminActivityEvent>> recentActivityByModel = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MutableStyleCounters> styleCountersByModel = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        if (commitDeliveryService != null) commitDeliveryService.start();
        kafkaConsumer.start();
        LOG.info("Archimesh service initialized; kafka consumer started");
    }

    /**
     * Handles a client joining a model session by resolving the target ref (HEAD or tag),
     * authorizing access, performing checkout (full snapshot or delta replay), and broadcasting
     * the join event.
     */
    public synchronized void onJoin(String modelId, Session session, JoinMessage join) {
        if (!ensureRegisteredModelForJoin(modelId, session)) {
            return;
        }
        // Resolve HEAD/tag once up front so checkout and later write checks share the same ref decision.
        ResolvedModelRef resolvedRef = resolveModelRef(modelId, join != null ? join.ref() : null);
        if (resolvedRef == null) {
            sendError(session, modelId, "TAG_NOT_FOUND",
                    "Requested model reference was not found for modelId " + modelId);
            return;
        }
        rememberJoinedRef(session, join != null ? join.actor() : null, resolvedRef);

        if (!resolvedRef.writable()) {
            // Tagged refs are historical snapshots. They never register into the writable live session set.
            sessionRegistry.send(session, new ServerEnvelope("CheckoutSnapshot",
                    new CheckoutSnapshotMessage(resolvedRef.revision(), resolvedRef.snapshot())));
            return;
        }

        sessionRegistry.register(modelId, session);
        long head = revisionService.headRevision(modelId);
        Long lastSeen = join != null ? join.lastSeenRevision() : null;
        LOG.info("Join: modelId={} sessionId={} lastSeenRevision={} headRevision={}",
                modelId, session == null ? "n/a" : session.getId(), lastSeen, head);
        recordActivity(modelId, "Join", "sessionId=" + safeSessionId(session) + " lastSeen=" + lastSeen + " head=" + head);

        long safeLastSeen = lastSeen != null ? lastSeen : 0L;
        if (lastSeen == null) {
            // Cold join always gets a full snapshot
            JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
            sessionRegistry.send(session, new ServerEnvelope("CheckoutSnapshot",
                    new CheckoutSnapshotMessage(head, snapshot)));
            return;
        }

        if (safeLastSeen > head) {
            LOG.warn("Join ahead-of-head detected: modelId={} sessionId={} lastSeenRevision={} headRevision={} - sending snapshot",
                    modelId, session == null ? "n/a" : session.getId(), safeLastSeen, head);
            JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
            sessionRegistry.send(session, new ServerEnvelope("CheckoutSnapshot",
                    new CheckoutSnapshotMessage(head, snapshot)));
            return;
        }

        if (safeLastSeen < head) {
            // Prefer delta replay; fallback to snapshot when history window is unavailable
            JsonNode opBatches = neo4jRepository.loadOpBatches(modelId, safeLastSeen + 1, head);
            if (!coversRevisions(opBatches, safeLastSeen + 1, head)) {
                LOG.warn("CheckoutDelta requested but no op batches found: modelId={} from={} to={} - sending snapshot fallback",
                        modelId, safeLastSeen + 1, head);
                JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
                sessionRegistry.send(session, new ServerEnvelope("CheckoutSnapshot",
                        new CheckoutSnapshotMessage(head, snapshot)));
                return;
            }
            sessionRegistry.send(session, new ServerEnvelope("CheckoutDelta",
                    new CheckoutDeltaMessage(safeLastSeen + 1, head, opBatches)));
            return;
        }

        JsonNode empty = JsonNodeFactory.instance.arrayNode();
        sessionRegistry.send(session, new ServerEnvelope("CheckoutDelta",
                new CheckoutDeltaMessage(head + 1, head, empty)));
    }

    /**
     * Cleans up session state and broadcasts client departure when a WebSocket disconnects.
     */
    public void onDisconnect(String modelId, Session session) {
        LOG.info("Disconnect: modelId={} sessionId={}",
                modelId, session == null ? "n/a" : session.getId());
        // Forget local ref diagnostics before unregistering so admin views do not keep stale sessions around.
        forgetJoinedRef(session);
        sessionRegistry.unregister(modelId, session);
        recordActivity(modelId, "Disconnect", "sessionId=" + safeSessionId(session));
    }

    public synchronized void onSubmitOps(String modelId, SubmitOpsMessage submitOps) {
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        onSubmitOps(modelId, null, submitOps);
    }

    /**
     * Validates, normalizes, and processes incoming operational transforms: assigns revisions,
     * persists to the op-log, applies deltas to the materialized state, and broadcasts acceptance.
     */
    public synchronized void onSubmitOps(String modelId, Session session, SubmitOpsMessage submitOps) {
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        if (!ensureRegisteredModel(modelId, session, "SubmitOps")) {
            return;
        }
        if (!ensureWritableRef(modelId, session, submitOps.actor(), "SubmitOps")) {
            return;
        }
        validationService.validateSubmitOps(modelId, submitOps);
        // Submission pipeline: normalize/expand -> validate -> idempotency/locks -> assign revisions -> persist/apply/broadcast
        JsonNode preparedOps = prepareOpsForSubmission(modelId, submitOps.ops());
        int submittedOpCount = submitOps.ops() != null && submitOps.ops().isArray() ? submitOps.ops().size() : 0;
        int styleOpCount = countStyleOps(preparedOps);
        if (styleOpCount > 0) {
            styleCounters(modelId).received.addAndGet(styleOpCount);
            recordStyleActivity(modelId, "Received", styleOpCount, submitOps.opBatchId());
        }
        int opCount = preparedOps == null ? 0 : preparedOps.size();
        int cascadeDeleteGenerated = Math.max(0, opCount - submittedOpCount);
        String opBatchId = submitOps.opBatchId();
        long baseRevision = submitOps.baseRevision();
        long headRevision = revisionService.headRevision(modelId);
        LOG.info("SubmitOps received: modelId={} opBatchId={} baseRevision={} opCount={} ops={}",
                modelId, opBatchId, baseRevision, opCount, summarizeOps(preparedOps));
        recordActivity(modelId, "SubmitOpsReceived",
                "opBatchId=" + opBatchId + " baseRevision=" + baseRevision + " opCount=" + opCount
                        + " cascadeDeleteGenerated=" + cascadeDeleteGenerated);
        if (cascadeDeleteGenerated > 0) {
            recordActivity(modelId, "CascadeDeleteExpanded",
                    "opBatchId=" + opBatchId + " generatedDeleteOps=" + cascadeDeleteGenerated);
        }

        Optional<RevisionRange> known = neo4jRepository.findAcceptedRange(modelId, opBatchId)
                .or(() -> idempotencyService.findRange(modelId, opBatchId));
        if (known.isPresent()) {
            // Idempotency is model-scoped; repeat deliveries replay the accepted range instead of mutating again.
            LOG.info("SubmitOps idempotency hit: modelId={} opBatchId={} assigned={}..{}",
                    modelId, opBatchId, known.get().from(), known.get().to());
            recordActivity(modelId, "SubmitOpsIdempotent",
                    "opBatchId=" + opBatchId + " assigned=" + known.get().from() + ".." + known.get().to());
            if (styleOpCount > 0) {
                styleCounters(modelId).accepted.addAndGet(styleOpCount);
                recordStyleActivity(modelId, "AcceptedIdempotent", styleOpCount, opBatchId);
            }
            reply(session, modelId,
                    new ServerEnvelope("OpsAccepted", new OpsAcceptedMessage(opBatchId, baseRevision, known.get())));
            return;
        }

        if (baseRevision > headRevision) {
            LOG.warn("SubmitOps rejected: modelId={} opBatchId={} baseRevision={} exceeds headRevision={}",
                    modelId, opBatchId, baseRevision, headRevision);
            recordActivity(modelId, "SubmitOpsRejectedAhead",
                    "opBatchId=" + opBatchId + " baseRevision=" + baseRevision + " headRevision=" + headRevision);
            if (styleOpCount > 0) {
                styleCounters(modelId).rejected.addAndGet(styleOpCount);
                recordStyleActivity(modelId, "RejectedAhead", styleOpCount, opBatchId);
            }
            reply(session, modelId,
                    new ServerEnvelope("Error", new ErrorMessage("REVISION_AHEAD",
                            "baseRevision " + baseRevision + " exceeds server headRevision " + headRevision + "; rejoin required", opBatchId)));
            return;
        }

        Optional<String> preconditionFailure = validatePreconditions(modelId, preparedOps);
        if (preconditionFailure.isPresent()) {
            LOG.warn("SubmitOps precondition failed: modelId={} opBatchId={} details={}",
                    modelId, opBatchId, preconditionFailure.get());
            recordActivity(modelId, "SubmitOpsRejectedPrecondition",
                    "opBatchId=" + opBatchId + " details=" + preconditionFailure.get());
            if (styleOpCount > 0) {
                styleCounters(modelId).rejected.addAndGet(styleOpCount);
                recordStyleActivity(modelId, "RejectedPrecondition", styleOpCount, opBatchId);
            }
            reply(session, modelId,
                    new ServerEnvelope("Error", new ErrorMessage("PRECONDITION_FAILED", preconditionFailure.get(), opBatchId)));
            return;
        }

        Actor actor = submitOps.actor() != null ? submitOps.actor() : Actor.anonymous();
        List<String> lockTargets = lockRequiredTargets(preparedOps);
        if (!lockTargets.isEmpty()) {
            Optional<String> conflict = lockService.checkLockConflicts(modelId, actor, lockTargets);
            if (conflict.isPresent()) {
                LOG.warn("SubmitOps lock conflict: modelId={} opBatchId={} details={}",
                        modelId, opBatchId, conflict.get());
                recordActivity(modelId, "SubmitOpsRejectedLockConflict",
                        "opBatchId=" + opBatchId + " details=" + conflict.get());
                if (styleOpCount > 0) {
                    styleCounters(modelId).rejected.addAndGet(styleOpCount);
                    recordStyleActivity(modelId, "RejectedLockConflict", styleOpCount, opBatchId);
                }
                reply(session, modelId,
                        new ServerEnvelope("Error", new ErrorMessage("LOCK_CONFLICT", conflict.get(), opBatchId)));
                return;
            }
        }

        if (!lockTargets.isEmpty()) lockService.acquire(modelId, actor, lockTargets, DEFAULT_LOCK_TTL_MS);

        RevisionRange range = revisionService.assignRange(modelId, opCount);
        // Per-op causal tuples are derived from the assigned range so downstream LWW/tombstone checks compare
        // exact op revisions rather than only the enclosing batch.
        JsonNode normalizedOps = normalizeOpsWithCausal(preparedOps, actor, opBatchId, range.from());
        ObjectNode opBatch = JsonNodeFactory.instance.objectNode();
        opBatch.put("modelId", modelId);
        opBatch.put("opBatchId", opBatchId);
        opBatch.put("baseRevision", baseRevision);
        opBatch.set("assignedRevisionRange", toJsonRange(range));
        opBatch.put("timestamp", Instant.now().toString());
        opBatch.set("ops", normalizedOps);

        try {
            neo4jRepository.commitBatch(modelId, opBatchId, range, opBatch);
        } catch (RuntimeException failure) {
            revisionService.clearModel(modelId);
            throw failure;
        }
        idempotencyService.remember(modelId, opBatchId, range);
        LOG.info("SubmitOps accepted: modelId={} opBatchId={} assigned={}..{}",
                modelId, opBatchId, range.from(), range.to());
        recordActivity(modelId, "SubmitOpsAccepted",
                "opBatchId=" + opBatchId + " assigned=" + range.from() + ".." + range.to() + " opCount=" + opCount);
        if (styleOpCount > 0) {
            styleCounters(modelId).accepted.addAndGet(styleOpCount);
            recordStyleActivity(modelId, "Accepted", styleOpCount, opBatchId);
        }

        if (styleOpCount > 0) {
            styleCounters(modelId).applied.addAndGet(styleOpCount);
            recordStyleActivity(modelId, "Applied", styleOpCount, opBatchId);
        }
        runConsistencyChecksIfEnabled(modelId, opBatchId, range.to());

        if (commitDeliveryService != null) {
            commitDeliveryService.requestDrain();
        } else kafkaPublisher.publishOps(modelId, opBatch).exceptionally(ex -> {
            LOG.warn("Kafka publish failed; broadcasting OpsBroadcast directly to sessions: modelId={} opBatchId={}", modelId, opBatchId, ex);
            sessionRegistry.broadcast(modelId,
                    new ServerEnvelope("OpsBroadcast", new OpsBroadcastMessage(opBatch)));
            return null;
        });

        reply(session, modelId,
                new ServerEnvelope("OpsAccepted", new OpsAcceptedMessage(opBatchId, baseRevision, range)));
    }

    public synchronized void onAcquireLock(String modelId, AcquireLockMessage message) {
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        onAcquireLock(modelId, null, message);
    }

    /**
     * Acquires locks on the specified element targets for the requesting actor and publishes
     * the lock event to all model sessions via Kafka.
     */
    public synchronized void onAcquireLock(String modelId, Session session, AcquireLockMessage message) {
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        if (!ensureRegisteredModel(modelId, session, "AcquireLock")) {
            return;
        }
        if (!ensureWritableRef(modelId, session, message.actor(), "AcquireLock")) {
            return;
        }
        Actor actor = message.actor() != null ? message.actor() : Actor.anonymous();
        List<String> targets = message.targets() != null ? message.targets() : List.of();
        long ttlMs = message.ttlMs() != null ? message.ttlMs() : DEFAULT_LOCK_TTL_MS;
        LOG.debug("AcquireLock: modelId={} actor={}/{} targetCount={} ttlMs={}",
                modelId, actor.userId(), actor.sessionId(), targets.size(), ttlMs);
        recordActivity(modelId, "AcquireLock",
                "actor=" + actor.userId() + "/" + actor.sessionId() + " targetCount=" + targets.size() + " ttlMs=" + ttlMs);

        var event = lockService.acquire(modelId, actor, targets, ttlMs);
        kafkaPublisher.publishLockEvent(modelId, event);
    }

    public synchronized void onReleaseLock(String modelId, ReleaseLockMessage message) {
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        onReleaseLock(modelId, null, message);
    }

    /**
     * Releases locks held by the requesting actor on the specified targets and broadcasts
     * the release event.
     */
    public synchronized void onReleaseLock(String modelId, Session session, ReleaseLockMessage message) {
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        if (!ensureRegisteredModel(modelId, session, "ReleaseLock")) {
            return;
        }
        if (!ensureWritableRef(modelId, session, message.actor(), "ReleaseLock")) {
            return;
        }
        Actor actor = message.actor() != null ? message.actor() : Actor.anonymous();
        List<String> targets = message.targets() != null ? message.targets() : List.of();
        LOG.debug("ReleaseLock: modelId={} actor={}/{} targetCount={}",
                modelId, actor.userId(), actor.sessionId(), targets.size());
        recordActivity(modelId, "ReleaseLock",
                "actor=" + actor.userId() + "/" + actor.sessionId() + " targetCount=" + targets.size());

        var event = lockService.release(modelId, actor, targets);
        kafkaPublisher.publishLockEvent(modelId, event);
    }

    public void onPresence(String modelId, PresenceMessage message) {
        onPresence(modelId, null, message);
    }

    /**
     * Broadcasts the client's viewport cursor position and selection state to all sessions
     * subscribed to the model.
     */
    public void onPresence(String modelId, Session session, PresenceMessage message) {
        if (!ensureRegisteredModel(modelId, session, "Presence")) {
            return;
        }
        if (!ensureWritableRef(modelId, session, message.actor(), "Presence")) {
            return;
        }
        Actor actor = message.actor() != null ? message.actor() : Actor.anonymous();
        LOG.debug("Presence: modelId={} actor={}/{} viewId={}",
                modelId, actor.userId(), actor.sessionId(), message.viewId());
        recordActivity(modelId, "Presence",
                "actor=" + actor.userId() + "/" + actor.sessionId() + " viewId=" + message.viewId());
        var event = new PresenceBroadcastMessage(actor, Instant.now(), message.viewId(), message.selection(), message.cursor());
        kafkaPublisher.publishPresence(modelId, event);
    }

    /**
     * Compares in-memory head revision against the persisted head, latest commit revision,
     * and materialized state to produce a consistency report for diagnostics.
     */
    public ConsistencyStatus getConsistencyStatus(String modelId) {
        long inMemoryHead = revisionService.headRevision(modelId);
        long persistedHead = neo4jRepository.readHeadRevision(modelId);
        long latestCommitRevision = neo4jRepository.readLatestCommitRevision(modelId);
        boolean materializedStateConsistent = neo4jRepository.isMaterializedStateConsistent(modelId, inMemoryHead);
        boolean headAligned = inMemoryHead == persistedHead;
        boolean commitAligned = inMemoryHead == latestCommitRevision;
        boolean consistent = headAligned && commitAligned && materializedStateConsistent;
        return new ConsistencyStatus(
                modelId,
                inMemoryHead,
                persistedHead,
                latestCommitRevision,
                materializedStateConsistent,
                headAligned,
                commitAligned,
                consistent);
    }

    public synchronized JsonNode getSnapshot(String modelId) {
        return getSnapshot(modelId, null);
    }

    /**
     * Resolves the given ref (HEAD or tag name) and returns the associated materialized
     * state snapshot.
     */
    public synchronized JsonNode getSnapshot(String modelId, String ref) {
        ResolvedModelRef resolvedRef = resolveModelRef(modelId, ref);
        if (resolvedRef == null) {
            throw new IllegalArgumentException("Unknown model reference for modelId " + modelId + ": " + ref);
        }
        return resolvedRef.snapshot();
    }

    /**
     * Returns administrative status for a model including element/relationship/view counts
     * and consistency diagnostics.
     */
    public AdminStatus getAdminStatus(String modelId) {
        JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
        ConsistencyStatus consistency = getConsistencyStatus(modelId);
        return new AdminStatus(
                modelId,
                snapshot.path("headRevision").asLong(0),
                countArray(snapshot, "elements"),
                countArray(snapshot, "relationships"),
                countArray(snapshot, "views"),
                countArray(snapshot, "viewObjects"),
                countArray(snapshot, "connections"),
                consistency);
    }

    /**
     * Rebuilds the materialized state by replaying all commits from the op-log and returns
     * the updated admin status including consistency info.
     */
    public AdminRebuildStatus rebuildAndGetAdminStatus(String modelId) {
        RebuildStatus rebuild = rebuildMaterializedState(modelId);
        AdminStatus status = getAdminStatus(modelId);
        return new AdminRebuildStatus(rebuild, status);
    }

    /**
     * Returns a comprehensive admin view of a model including active sessions, ACL summary,
     * tag summary, status, style counters, integrity report, and recent activity.
     */
    public AdminModelWindow getAdminModelWindow(String modelId, Integer limit) {
        int safeLimit = sanitizeLimit(limit);
        JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
        List<ModelTagEntry> tags = neo4jRepository.listModelTags(modelId);
        ModelAccessControl accessControl = findModelAccessControl(modelId)
                .orElse(new ModelAccessControl(normalizeModelId(modelId), Set.of(), Set.of(), Set.of()));
        return new AdminModelWindow(
                modelId,
                neo4jRepository.readModelName(modelId),
                activeSessionsForModel(modelId).size(),
                activeSessionsForModel(modelId),
                summarizeAccess(accessControl),
                summarizeTags(tags),
                getAdminStatus(modelId),
                styleCountersSnapshot(modelId),
                computeIntegrityFromSnapshot(modelId, snapshot),
                getRecentActivity(modelId, safeLimit),
                getRecentOpBatches(modelId, safeLimit));
    }

    /**
     * Validates referential integrity of the model snapshot by checking that all relationship
     * sources/targets and view object references resolve to existing elements.
     */
    public AdminIntegrityReport getAdminIntegrity(String modelId) {
        JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
        return computeIntegrityFromSnapshot(modelId, snapshot);
    }

    /**
     * Removes a model and all associated data from the repository and in-memory caches,
     * optionally forcing deletion even when active sessions exist.
     */
    public synchronized AdminDeleteResult deleteModel(String modelId, boolean force) {
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        int activeSessions = sessionRegistry.sessionCount(modelId);
        if (activeSessions > 0 && !force) {
            String message = "Model has active sessions; close clients first or use force=true.";
            recordActivity(modelId, "DeleteModelRejected", message + " activeSessions=" + activeSessions);
            return new AdminDeleteResult(modelId, false, activeSessions, message);
        }

        neo4jRepository.deleteModel(modelId);
        revisionService.clearModel(modelId);
        idempotencyService.clearModel(modelId);
        lockService.clearModel(modelId);
        registeredModelCache.remove(modelId);
        modelAccessControlCache.remove(modelId);
        recentActivityByModel.remove(modelId);
        styleCountersByModel.remove(modelId);
        recordActivity(modelId, "ModelDeleted", "force=" + force + " activeSessionsAtDelete=" + activeSessions);
        return new AdminDeleteResult(modelId, true, activeSessions, "Model deleted from server state.");
    }

    /**
     * Returns a lightweight aggregated view of all active models for operator dashboards.
     */
    public List<AdminModelWindow> getAdminOverview(Integer limit) {
        int safeLimit = sanitizeLimit(limit);
        Set<String> modelIds = new LinkedHashSet<>(sessionRegistry.activeModelIds());
        modelIds.addAll(recentActivityByModel.keySet());
        for (ModelCatalogEntry entry : neo4jRepository.listModelCatalog()) {
            modelIds.add(entry.modelId());
        }
        List<AdminModelWindow> windows = new ArrayList<>();
        for (String modelId : modelIds) {
            windows.add(getAdminModelWindow(modelId, safeLimit));
        }
        windows.sort(Comparator.comparing(AdminModelWindow::modelId));
        return windows;
    }

    /**
     * Lists all registered models from the repository catalog.
     */
    public List<ModelCatalogEntry> getModelCatalog() {
        return neo4jRepository.listModelCatalog();
    }

    /**
     * Looks up the access control list for a model, returning a cached result when available.
     */
    public Optional<ModelAccessControl> findModelAccessControl(String modelId) {
        String normalizedModelId = normalizeModelId(modelId);
        ModelAccessControl cached = modelAccessControlCache.get(normalizedModelId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<ModelAccessControl> accessControl = neo4jRepository.readModelAccessControl(normalizedModelId);
        accessControl.ifPresent(value -> {
            registeredModelCache.add(normalizedModelId);
            modelAccessControlCache.put(normalizedModelId, value);
        });
        return accessControl;
    }

    /**
     * Returns the access control list for a model, ensuring it is registered and falling
     * back to an empty ACL when none is configured.
     */
    public ModelAccessControl getModelAccessControl(String modelId) {
        String normalizedModelId = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(normalizedModelId);
        return findModelAccessControl(normalizedModelId)
                .orElse(new ModelAccessControl(normalizedModelId, Set.of(), Set.of(), Set.of()));
    }

    /**
     * Updates the admin, writer, and reader user sets for a model's access control list
     * and invalidates the local cache.
     */
    public ModelAccessControl updateModelAccessControl(String modelId, Set<String> adminUsers, Set<String> writerUsers, Set<String> readerUsers) {
        String normalizedModelId = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(normalizedModelId);
        ModelAccessControl accessControl = neo4jRepository.updateModelAccessControl(
                normalizedModelId,
                adminUsers,
                writerUsers,
                readerUsers);
        registeredModelCache.add(normalizedModelId);
        modelAccessControlCache.put(normalizedModelId, accessControl);
        return accessControl;
    }

    /**
     * Lists all named snapshot tags for a model.
     */
    public List<ModelTagEntry> getModelTags(String modelId) {
        ensureRegisteredModelForAdmin(modelId);
        return neo4jRepository.listModelTags(modelId);
    }

    private AdminTagSummary summarizeTags(List<ModelTagEntry> tags) {
        if (tags == null || tags.isEmpty()) {
            return new AdminTagSummary(0, null, 0L, null);
        }
        ModelTagEntry latest = tags.get(0);
        return new AdminTagSummary(tags.size(), latest.tagName(), latest.revision(), latest.createdAt());
    }

    private AdminAccessSummary summarizeAccess(ModelAccessControl accessControl) {
        if (accessControl == null || !accessControl.configured()) {
            return new AdminAccessSummary(false, "open", 0, 0, 0);
        }
        return new AdminAccessSummary(
                true,
                "acl",
                accessControl.adminUsers().size(),
                accessControl.writerUsers().size(),
                accessControl.readerUsers().size());
    }

    public ModelCatalogEntry registerModel(String modelId, String modelName) {
        return registerModel(modelId, modelName, null);
    }

    /**
     * Registers a new model in the catalog and optionally seeds the access control list
     * with the creating user as admin.
     */
    public ModelCatalogEntry registerModel(String modelId, String modelName, String creatorUserId) {
        String normalizedModelId = normalizeModelId(modelId);
        ModelCatalogEntry entry = neo4jRepository.registerModel(normalizedModelId, modelName);
        registeredModelCache.add(normalizedModelId);
        if (creatorUserId != null && !creatorUserId.isBlank()) {
            ModelAccessControl accessControl = neo4jRepository.updateModelAccessControl(
                    normalizedModelId,
                    Set.of(creatorUserId),
                    Set.of(creatorUserId),
                    Set.of(creatorUserId));
            modelAccessControlCache.put(normalizedModelId, accessControl);
        } else {
            modelAccessControlCache.remove(normalizedModelId);
        }
        return entry;
    }

    /**
     * Renames a registered model in the catalog.
     */
    public ModelCatalogEntry renameModel(String modelId, String modelName) {
        String normalizedModelId = normalizeModelId(modelId);
        ModelCatalogEntry entry = neo4jRepository.renameModel(normalizedModelId, modelName);
        registeredModelCache.add(normalizedModelId);
        return entry;
    }

    /**
     * Creates a named snapshot tag at the current HEAD revision, persisting the full
     * materialized state for later retrieval.
     */
    public synchronized ModelTagEntry createModelTag(String modelId, String tagName, String description) {
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        String normalizedModelId = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(normalizedModelId);
        String normalizedTagName = normalizeTagName(tagName);
        JsonNode snapshot = neo4jRepository.loadSnapshot(normalizedModelId);
        long revision = resolvedHeadRevision(normalizedModelId, snapshot);
        return neo4jRepository.createModelTag(normalizedModelId, normalizedTagName, description, revision, snapshot);
    }

    /**
     * Deletes a named tag from a model, subject to the configured tag deletion policy.
     */
    public void deleteModelTag(String modelId, String tagName) {
        String normalizedModelId = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(normalizedModelId);
        if (!allowTagDelete) {
            throw new IllegalStateException("Tag deletion is disabled; set app.tags.allow-delete=true to allow it.");
        }
        neo4jRepository.deleteModelTag(normalizedModelId, normalizeTagName(tagName));
    }

    /**
     * Exports the full model state including snapshot, op-log history, tags, and ACL
     * as a portable serialization package.
     */
    public synchronized AdminModelExport exportModel(String modelId) {
        String normalizedModelId = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(normalizedModelId);
        JsonNode snapshot = neo4jRepository.loadSnapshot(normalizedModelId);
        long headRevision = resolvedHeadRevision(normalizedModelId, snapshot);
        ModelCheckpoint checkpoint = neo4jRepository.latestCheckpoint(normalizedModelId).orElse(null);
        long base = checkpoint == null ? 0L : checkpoint.revision();
        JsonNode opBatches = neo4jRepository.loadOpBatches(normalizedModelId, base+1, headRevision);
        if (base > headRevision || !coversRevisions(opBatches,base+1,headRevision)) {
            throw new IllegalStateException("Cannot export: recovery history is incomplete");
        }
        List<BatchReceipt> receipts = checkpoint == null ? List.of() : neo4jRepository.loadReceipts(normalizedModelId,base);
        List<ModelTagExportEntry> tags = neo4jRepository.listModelTags(normalizedModelId).stream()
                .map(tag -> new ModelTagExportEntry(
                        tag.modelId(),
                        tag.tagName(),
                        tag.description(),
                        tag.revision(),
                        tag.createdAt(),
                        neo4jRepository.loadTaggedSnapshot(normalizedModelId, tag.tagName())))
                .toList();
        return new AdminModelExport(
                checkpoint == null ? MODEL_EXPORT_FORMAT : CHECKPOINT_EXPORT_FORMAT,
                Instant.now().toString(),
                new ModelCatalogEntry(normalizedModelId, neo4jRepository.readModelName(normalizedModelId), headRevision),
                getModelAccessControl(normalizedModelId),
                snapshot,
                opBatches,
                tags,
                checkpoint,
                checkpoint == null ? null : receipts);
    }

    /**
     * Imports a previously exported model package, optionally overwriting an existing model
     * and restoring its op-log, tags, and access control.
     */
    public synchronized AdminModelImportResult importModel(AdminModelExport exportPackage, boolean overwrite) {
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        if (exportPackage == null) {
            throw new IllegalArgumentException("Import payload is required.");
        }
        if (!MODEL_EXPORT_FORMAT.equals(exportPackage.format()) && !CHECKPOINT_EXPORT_FORMAT.equals(exportPackage.format())) {
            throw new IllegalArgumentException("Unsupported import format: " + exportPackage.format());
        }
        if (exportPackage.model() == null) {
            throw new IllegalArgumentException("Import payload must include model metadata.");
        }

        String modelId = normalizeModelId(exportPackage.model().modelId());
        validateImportPayload(exportPackage);
        boolean exists = neo4jRepository.modelRegistered(modelId);
        if (exists && !overwrite) {
            throw new IllegalStateException("Model already exists; re-run import with overwrite=true to replace it.");
        }
        if (exists && sessionRegistry.sessionCount(modelId) > 0) {
            throw new IllegalStateException("Model has active sessions; close clients before overwriting.");
        }

        if (CHECKPOINT_EXPORT_FORMAT.equals(exportPackage.format())) {
            neo4jRepository.importCheckpointModel(exportPackage,overwrite);
            revisionService.clearModel(modelId);
            idempotencyService.clearModel(modelId);
            modelAccessControlCache.remove(modelId);
            registeredModelCache.add(modelId);
            int batches = exportPackage.opBatches().size();
            int tags = exportPackage.tags() == null ? 0 : exportPackage.tags().size();
            recordActivity(modelId,"ImportModel","checkpointRevision="+exportPackage.checkpoint().revision()+" tailBatches="+batches);
            return new AdminModelImportResult(modelId,exists,exportPackage.model().headRevision(),batches,tags,
                    "Model restored from checkpoint and subsequent history.");
        }

        if (exists) {
            deleteModel(modelId, true);
        }
        registerModel(modelId, exportPackage.model().modelName());
        if (exportPackage.accessControl() != null) {
            updateModelAccessControl(modelId,
                    exportPackage.accessControl().adminUsers(),
                    exportPackage.accessControl().writerUsers(),
                    exportPackage.accessControl().readerUsers());
        }

        int importedOpBatchCount = 0;
        long importedHeadRevision = 0L;
        JsonNode opBatches = exportPackage.opBatches();
        if (opBatches != null && opBatches.isArray()) {
            for (JsonNode opBatch : opBatches) {
                String opBatchId = requireText(opBatch, "opBatchId");
                JsonNode assignedRange = opBatch.path("assignedRevisionRange");
                long from = assignedRange.path("from").asLong(Long.MIN_VALUE);
                long to = assignedRange.path("to").asLong(Long.MIN_VALUE);
                if (from <= 0L || to < from) {
                    throw new IllegalArgumentException("Import op batch " + opBatchId + " has invalid assignedRevisionRange.");
                }
                RevisionRange range = new RevisionRange(from, to);
                neo4jRepository.appendOpLog(modelId, opBatchId, range, opBatch);
                neo4jRepository.applyToMaterializedState(modelId, opBatch);
                neo4jRepository.updateHeadRevision(modelId, to);
                idempotencyService.remember(modelId, opBatchId, range);
                importedHeadRevision = Math.max(importedHeadRevision, to);
                importedOpBatchCount++;
            }
        }

        if (importedOpBatchCount == 0) {
            importedHeadRevision = Math.max(0L, exportPackage.model().headRevision());
            neo4jRepository.updateHeadRevision(modelId, importedHeadRevision);
        }

        int importedTagCount = 0;
        if (exportPackage.tags() != null) {
            for (ModelTagExportEntry tag : exportPackage.tags()) {
                if (tag == null) {
                    continue;
                }
                neo4jRepository.restoreModelTag(
                        modelId,
                        normalizeTagName(tag.tagName()),
                        tag.description(),
                        tag.revision(),
                        tag.createdAt(),
                        tag.snapshot());
                importedTagCount++;
            }
        }

        revisionService.clearModel(modelId);
        registeredModelCache.add(modelId);
        recordActivity(modelId, "ImportModel",
                "overwrite=" + overwrite + " importedOpBatches=" + importedOpBatchCount + " importedTags=" + importedTagCount);
        return new AdminModelImportResult(
                modelId,
                exists,
                importedHeadRevision,
                importedOpBatchCount,
                importedTagCount,
                exists ? "Model overwritten from import package." : "Model imported from package.");
    }

    public synchronized CheckpointSummary createModelCheckpoint(String modelId) {
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        String id = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(id);
        ModelCheckpoint checkpoint = neo4jRepository.createCheckpoint(id);
        recordActivity(id,"CreateCheckpoint","revision="+checkpoint.revision()+" sha256="+checkpoint.sha256());
        return checkpoint.summary();
    }

    public synchronized Optional<CheckpointSummary> getLatestModelCheckpoint(String modelId) {
        String id = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(id);
        return neo4jRepository.latestCheckpoint(id).map(ModelCheckpoint::summary);
    }

    /** One serialized model maintenance pass; callers decide scheduling and model fairness. */
    public synchronized CheckpointMaintenanceResult maintainCheckpoints(String modelId, long minRevisions, int retainCheckpoints) {
        if (minRevisions < 1 || retainCheckpoints < 1) throw new IllegalArgumentException("Checkpoint policy values must be positive");
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        String id = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(id);
        var previous = neo4jRepository.latestCheckpoint(id);
        long base = previous.map(ModelCheckpoint::revision).orElse(0L);
        long head = neo4jRepository.readHeadRevision(id);
        if (base > head) throw new IllegalStateException("Checkpoint is ahead of model head");
        boolean create = head - base >= minRevisions;
        if (create) base = createModelCheckpoint(id).revision();
        if (!create && previous.isEmpty()) return new CheckpointMaintenanceResult(id,false,0,0,0,0);
        var pruning = compactModelMetadata(id,null,false);
        long removed = neo4jRepository.pruneCheckpoints(id,retainCheckpoints);
        var result = new CheckpointMaintenanceResult(id,create,base,pruning.prunedBatchCount(),pruning.deletedOpCount(),removed);
        if (create || pruning.prunedBatchCount() > 0 || removed > 0) {
            recordActivity(id,"CheckpointMaintenance",result.toString());
        }
        return result;
    }

    /**
     * Prunes checkpoint-covered, published operation payloads below the retention
     * watermark while retaining acceptance receipts and all merge metadata.
     */
    public synchronized AdminCompactionStatus compactModelMetadata(String modelId, Long retainRevisionsOverride) {
        return compactModelMetadata(modelId,retainRevisionsOverride,false);
    }

    public synchronized AdminCompactionStatus compactModelMetadata(String modelId, Long retainRevisionsOverride, boolean dryRun) {
        ensureRegisteredModelForAdmin(modelId);
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        long retainRevisions = retainRevisionsOverride == null
                ? defaultCompactionRetainRevisions
                : Math.max(0L, retainRevisionsOverride);
        AdminCompactionStatus status = neo4jRepository.compactMetadata(modelId, retainRevisions,dryRun);
        recordActivity(modelId, "CompactMetadata",
                "retainRevisions=" + retainRevisions
                        + " committedHorizon=" + status.committedHorizonRevision()
                        + " watermark=" + status.watermarkRevision()
                        + " deletedCommits=" + status.deletedCommitCount()
                        + " deletedOps=" + status.deletedOpCount()
                        + " deletedPropertyClocks=" + status.deletedPropertyClockCount()
                        + " eligibleFieldClocks=" + status.eligibleFieldClockCount()
                        + " retainedTombstones=" + status.retainedTombstoneCount()
                        + " eligibleTombstones=" + status.eligibleTombstoneCount()
                        + " executed=" + status.executed());
        return status;
    }

    /**
     * Replaces the projection transactionally from the latest checkpoint and complete
     * operation tail, or from complete history when no checkpoint exists.
     */
    public synchronized RebuildStatus rebuildMaterializedState(String modelId) {
        if (commitDeliveryService != null) commitDeliveryService.requireWriterLease();
        long latestCommitRevision = neo4jRepository.readLatestCommitRevision(modelId);
        ModelCheckpoint checkpoint = neo4jRepository.latestCheckpoint(modelId).orElse(null);
        long base = checkpoint == null ? 0 : checkpoint.revision();
        JsonNode history = neo4jRepository.loadOpBatches(modelId, base+1, latestCommitRevision);
        if (base > latestCommitRevision || !coversRevisions(history, base+1, latestCommitRevision)) {
            throw new IllegalStateException("Cannot rebuild: complete checkpoint recovery history is unavailable");
        }
        neo4jRepository.rebuildProjection(modelId, checkpoint, history, latestCommitRevision);

        int appliedBatchCount = 0;
        int appliedOpCount = 0;
        long rebuiltHeadRevision = base;

        if (latestCommitRevision > 0) {
            JsonNode opBatches = history;
            if (opBatches != null && opBatches.isArray()) {
                for (JsonNode opBatch : opBatches) {
                    appliedBatchCount++;
                    if (opBatch.path("ops").isArray()) {
                        appliedOpCount += opBatch.path("ops").size();
                    }
                    JsonNode assignedRange = opBatch.path("assignedRevisionRange");
                    long toRevision = assignedRange.path("to").asLong(-1L);
                    if (toRevision >= 0L) {
                        rebuiltHeadRevision = Math.max(rebuiltHeadRevision, toRevision);
                    }
                }
            }
        }

        if (rebuiltHeadRevision == 0L && latestCommitRevision > 0L) {
            rebuiltHeadRevision = latestCommitRevision;
        }
        revisionService.clearModel(modelId);
        boolean consistent = neo4jRepository.isMaterializedStateConsistent(modelId, rebuiltHeadRevision);
        LOG.info("Rebuild materialized state: modelId={} requestedToRevision={} rebuiltHeadRevision={} appliedBatchCount={} appliedOpCount={} consistent={}",
                modelId, latestCommitRevision, rebuiltHeadRevision, appliedBatchCount, appliedOpCount, consistent);
        recordActivity(modelId, "RebuildMaterializedState",
                "requestedToRevision=" + latestCommitRevision + " rebuiltHeadRevision=" + rebuiltHeadRevision
                        + " appliedBatchCount=" + appliedBatchCount + " appliedOpCount=" + appliedOpCount
                        + " consistent=" + consistent);
        return new RebuildStatus(modelId, latestCommitRevision, rebuiltHeadRevision, appliedBatchCount, appliedOpCount, consistent,base);
    }

    private int countArray(JsonNode node, String field) {
        JsonNode array = node.path(field);
        return array.isArray() ? array.size() : 0;
    }

    private AdminIntegrityReport computeIntegrityFromSnapshot(String modelId, JsonNode snapshot) {
        List<AdminIntegrityIssue> issues = new ArrayList<>();

        Set<String> elementIds = collectIds(snapshot.path("elements"), "id");
        Set<String> relationshipIds = collectIds(snapshot.path("relationships"), "id");
        Set<String> viewIds = collectIds(snapshot.path("views"), "id");
        Set<String> viewObjectIds = collectIds(snapshot.path("viewObjects"), "id");

        int missingRelationshipEndpointCount = 0;
        JsonNode relationships = snapshot.path("relationships");
        if (relationships.isArray()) {
            for (JsonNode relationship : relationships) {
                String relationshipId = relationship.path("id").asText("");
                String sourceId = relationship.path("sourceId").asText("");
                String targetId = relationship.path("targetId").asText("");
                if (sourceId.isBlank() || !elementIds.contains(sourceId)) {
                    missingRelationshipEndpointCount++;
                    issues.add(new AdminIntegrityIssue(
                            "REL_MISSING_SOURCE",
                            "error",
                            "relationship",
                            relationshipId,
                            "Relationship sourceId is missing or unknown: " + sourceId,
                            "Delete/fix this relationship, or recreate the missing source element."));
                }
                if (targetId.isBlank() || !elementIds.contains(targetId)) {
                    missingRelationshipEndpointCount++;
                    issues.add(new AdminIntegrityIssue(
                            "REL_MISSING_TARGET",
                            "error",
                            "relationship",
                            relationshipId,
                            "Relationship targetId is missing or unknown: " + targetId,
                            "Delete/fix this relationship, or recreate the missing target element."));
                }
            }
        }

        int missingConnectionEndpointCount = 0;
        JsonNode connections = snapshot.path("connections");
        if (connections.isArray()) {
            for (JsonNode connection : connections) {
                String connectionId = connection.path("id").asText("");
                String sourceId = connection.path("sourceId").asText("");
                String targetId = connection.path("targetId").asText("");
                if (sourceId.isBlank() || !viewObjectIds.contains(sourceId)) {
                    missingConnectionEndpointCount++;
                    issues.add(new AdminIntegrityIssue(
                            "CONN_MISSING_SOURCE",
                            "error",
                            "connection",
                            connectionId,
                            "Connection sourceId is missing or unknown: " + sourceId,
                            "Delete this connection or recreate the missing source view object."));
                }
                if (targetId.isBlank() || !viewObjectIds.contains(targetId)) {
                    missingConnectionEndpointCount++;
                    issues.add(new AdminIntegrityIssue(
                            "CONN_MISSING_TARGET",
                            "error",
                            "connection",
                            connectionId,
                            "Connection targetId is missing or unknown: " + targetId,
                            "Delete this connection or recreate the missing target view object."));
                }
            }
        }

        int missingViewObjectReferenceCount = 0;
        int missingViewContainerCount = 0;
        JsonNode viewObjects = snapshot.path("viewObjects");
        if (viewObjects.isArray()) {
            for (JsonNode viewObject : viewObjects) {
                String viewObjectId = viewObject.path("id").asText("");
                String representsId = viewObject.path("representsId").asText("");
                String viewId = viewObject.path("viewId").asText("");
                if (!representsId.isBlank()
                        && !elementIds.contains(representsId)
                        && !relationshipIds.contains(representsId)) {
                    missingViewObjectReferenceCount++;
                    issues.add(new AdminIntegrityIssue(
                            "VIEWOBJ_MISSING_REPRESENTS",
                            "error",
                            "viewObject",
                            viewObjectId,
                            "ViewObject representsId is unknown: " + representsId,
                            "Delete/recreate this view object, or recreate its represented element/relationship."));
                }
                if (viewId.isBlank() || !viewIds.contains(viewId)) {
                    missingViewContainerCount++;
                    issues.add(new AdminIntegrityIssue(
                            "VIEWOBJ_MISSING_VIEW",
                            "error",
                            "viewObject",
                            viewObjectId,
                            "ViewObject viewId is missing or unknown: " + viewId,
                            "Delete this orphan view object, or recreate the missing view."));
                }
            }
        }

        return new AdminIntegrityReport(
                modelId,
                issues.isEmpty(),
                issues.size(),
                missingRelationshipEndpointCount,
                missingConnectionEndpointCount,
                missingViewObjectReferenceCount,
                missingViewContainerCount,
                issues);
    }

    private Set<String> collectIds(JsonNode arrayNode, String idField) {
        Set<String> ids = new HashSet<>();
        if (arrayNode == null || !arrayNode.isArray()) {
            return ids;
        }
        for (JsonNode node : arrayNode) {
            String id = node.path(idField).asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<AdminActivityEvent> getRecentActivity(String modelId, int limit) {
        Deque<AdminActivityEvent> queue = recentActivityByModel.get(modelId);
        if (queue == null || queue.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<AdminActivityEvent> events = new ArrayList<>(Math.min(limit, queue.size()));
        int skipped = Math.max(0, queue.size() - limit);
        int index = 0;
        for (AdminActivityEvent event : queue) {
            if (index++ < skipped) {
                continue;
            }
            events.add(event);
        }
        return events;
    }

    private JsonNode getRecentOpBatches(String modelId, int limit) {
        if (limit <= 0) {
            return JsonNodeFactory.instance.arrayNode();
        }
        long latestCommitRevision = neo4jRepository.readLatestCommitRevision(modelId);
        if (latestCommitRevision <= 0) {
            return JsonNodeFactory.instance.arrayNode();
        }
        long fromRevision = Math.max(1L, latestCommitRevision - limit + 1L);
        return neo4jRepository.loadOpBatches(modelId, fromRevision, latestCommitRevision);
    }

    private int sanitizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_WINDOW_LIMIT;
        }
        return Math.max(1, Math.min(limit, 200));
    }

    private String normalizeModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId is required");
        }
        return modelId.trim();
    }

    private String normalizeTagName(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            throw new IllegalArgumentException("tagName is required");
        }
        String normalized = tagName.trim();
        if (HEAD_REF.equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException("tagName HEAD is reserved");
        }
        return normalized;
    }

    private String requireText(JsonNode node, String fieldName) {
        String value = node == null ? null : node.path(fieldName).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Import payload is missing required field: " + fieldName);
        }
        return value;
    }

    private void validateImportPayload(AdminModelExport exportPackage) {
        if (CHECKPOINT_EXPORT_FORMAT.equals(exportPackage.format())) {
            ModelCheckpoint checkpoint = exportPackage.checkpoint();
            CheckpointCodec.verify(checkpoint);
            long base = checkpoint.revision();
            if (exportPackage.opBatches() == null || !exportPackage.opBatches().isArray()
                    || base > exportPackage.model().headRevision() || !coversRevisions(exportPackage.opBatches(),base+1,exportPackage.model().headRevision())) {
                throw new IllegalArgumentException("Import requires complete history after the checkpoint");
            }
            Set<String> ids = new HashSet<>();
            long next = 1;
            if (exportPackage.receipts() == null) throw new IllegalArgumentException("Checkpoint import requires acceptance receipts");
            for (BatchReceipt receipt : exportPackage.receipts()) {
                if (receipt == null || receipt.opBatchId() == null || receipt.opBatchId().isBlank()
                        || !ids.add(receipt.opBatchId()) || receipt.revisionFrom() != next
                        || receipt.revisionTo() < next || receipt.revisionTo() > base || receipt.timestamp() == null) {
                    throw new IllegalArgumentException("Invalid or incomplete checkpoint acceptance receipts");
                }
                next = receipt.revisionTo()+1;
            }
            if (next != base+1) throw new IllegalArgumentException("Acceptance receipts do not cover the checkpoint");
            for (JsonNode batch : exportPackage.opBatches()) {
                if (!ids.add(requireText(batch,"opBatchId"))) throw new IllegalArgumentException("Duplicate imported batch identity");
            }
            if (exportPackage.tags() != null) {
                Set<String> names = new HashSet<>();
                for (ModelTagExportEntry tag : exportPackage.tags()) {
                    if (tag == null || tag.tagName() == null || tag.tagName().isBlank() || !names.add(tag.tagName())
                            || HEAD_REF.equalsIgnoreCase(tag.tagName()) || tag.revision() < 0 || tag.revision() > exportPackage.model().headRevision()
                            || tag.snapshot() == null || !tag.snapshot().isObject() || tag.createdAt() == null) {
                        throw new IllegalArgumentException("Invalid checkpoint export tag");
                    }
                }
            }
            return;
        }
        if (exportPackage.checkpoint() != null) throw new IllegalArgumentException("A checkpoint requires export format v2");
        JsonNode opBatches = exportPackage.opBatches();
        boolean hasOpBatches = opBatches != null && opBatches.isArray() && !opBatches.isEmpty();
        if (hasOpBatches) {
            if (!coversRevisions(opBatches, 1, exportPackage.model().headRevision())) {
                throw new IllegalArgumentException("Import requires complete, contiguous op-log history");
            }
            return;
        }
        boolean hasSnapshotContent = snapshotHasMaterializedContent(exportPackage.snapshot());
        boolean hasHistoricalTags = exportPackage.tags() != null && exportPackage.tags().stream()
                .filter(Objects::nonNull)
                .anyMatch(tag -> tag.revision() > 0L);
        if (exportPackage.model().headRevision() > 0L || hasSnapshotContent || hasHistoricalTags) {
            throw new IllegalArgumentException("Import payload is missing op-log history for a non-empty model.");
        }
    }

    private boolean snapshotHasMaterializedContent(JsonNode snapshot) {
        if (snapshot == null || snapshot.isMissingNode() || snapshot.isNull()) {
            return false;
        }
        return countArray(snapshot, "elements") > 0
                || countNonRootFolders(snapshot.path("folders")) > 0
                || countArray(snapshot, "relationships") > 0
                || countArray(snapshot, "views") > 0
                || countArray(snapshot, "elementFolderMembers") > 0
                || countArray(snapshot, "relationshipFolderMembers") > 0
                || countArray(snapshot, "viewFolderMembers") > 0
                || countArray(snapshot, "viewObjects") > 0
                || countArray(snapshot, "viewObjectChildMembers") > 0
                || countArray(snapshot, "connections") > 0;
    }

    private long countNonRootFolders(JsonNode folders) {
        if (folders == null || !folders.isArray()) {
            return 0L;
        }
        long count = 0L;
        for (JsonNode folder : folders) {
            String id = folder.path("id").asText("");
            if (!id.startsWith("folder:root-")) {
                count++;
            }
        }
        return count;
    }

    private String normalizeRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return HEAD_REF;
        }
        String normalized = ref.trim();
        return normalized.isBlank() ? HEAD_REF : normalized;
    }

    private long resolvedHeadRevision(String modelId, JsonNode snapshot) {
        long snapshotHead = snapshot == null ? 0L : snapshot.path("headRevision").asLong(0L);
        long inMemoryHead = revisionService.headRevision(modelId);
        long persistedHead = neo4jRepository.readHeadRevision(modelId);
        long latestCommitHead = neo4jRepository.readLatestCommitRevision(modelId);
        return Math.max(Math.max(snapshotHead, inMemoryHead), Math.max(persistedHead, latestCommitHead));
    }

    private boolean ensureRegisteredModelForJoin(String modelId, Session session) {
        if (isRegisteredModel(modelId)) {
            return true;
        }
        LOG.warn("Join rejected for unknown modelId={}", modelId);
        sendError(session, modelId, "MODEL_NOT_FOUND",
                "modelId " + modelId + " is not registered; create it via admin first");
        return false;
    }

    private boolean ensureRegisteredModel(String modelId, Session session, String operation) {
        if (isRegisteredModel(modelId)) {
            return true;
        }
        LOG.warn("{} rejected for unknown modelId={}", operation, modelId);
        sendError(session, modelId, "MODEL_NOT_FOUND",
                "modelId " + modelId + " is not registered; create it via admin first");
        return false;
    }

    private void ensureRegisteredModelForAdmin(String modelId) {
        if (!isRegisteredModel(modelId)) {
            throw new IllegalArgumentException("modelId " + modelId + " is not registered");
        }
    }

    private boolean isRegisteredModel(String modelId) {
        if (registeredModelCache.contains(modelId)) {
            return true;
        }
        if (findModelAccessControl(modelId).isPresent()) {
            return true;
        }
        boolean registered = neo4jRepository.modelRegistered(modelId);
        if (registered) {
            registeredModelCache.add(modelId);
        }
        return registered;
    }

    private boolean ensureWritableRef(String modelId, Session session, Actor actor, String operation) {
        JoinedModelRef joinedRef = joinedRef(session, actor);
        if (joinedRef == null || joinedRef.writable()) {
            return true;
        }
        LOG.warn("{} rejected for read-only ref: modelId={} ref={}", operation, modelId, joinedRef.ref());
        sendError(session, modelId, "MODEL_REFERENCE_READ_ONLY",
                "modelId " + modelId + " reference " + joinedRef.ref() + " is read-only");
        return false;
    }

    private ResolvedModelRef resolveModelRef(String modelId, String ref) {
        String normalizedRef = normalizeRef(ref);
        if (HEAD_REF.equalsIgnoreCase(normalizedRef)) {
            JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
            long revision = resolvedHeadRevision(modelId, snapshot);
            return new ResolvedModelRef(HEAD_REF, revision, true, snapshot);
        }
        Optional<ModelTagEntry> tag = neo4jRepository.readModelTag(modelId, normalizedRef);
        if (tag.isEmpty()) {
            return null;
        }
        JsonNode snapshot = neo4jRepository.loadTaggedSnapshot(modelId, normalizedRef);
        return new ResolvedModelRef(tag.get().tagName(), tag.get().revision(), false, snapshot);
    }

    private void rememberJoinedRef(Session session, Actor actor, ResolvedModelRef resolvedRef) {
        String websocketSessionId = sessionId(session);
        if (websocketSessionId != null) {
            joinedRefsBySessionKey.put(websocketSessionId, new JoinedModelRef(resolvedRef.ref(), resolvedRef.writable()));
            rememberSessionDiagnostics(session, websocketSessionId, actor, resolvedRef);
        }
        String actorSessionId = actor != null ? actor.sessionId() : null;
        if (actorSessionId != null && !actorSessionId.isBlank()) {
            joinedRefsBySessionKey.put(actorSessionId, new JoinedModelRef(resolvedRef.ref(), resolvedRef.writable()));
            if (websocketSessionId != null) {
                actorSessionIdByWebsocketSessionId.put(websocketSessionId, actorSessionId);
            }
        }
    }

    private void forgetJoinedRef(Session session) {
        String websocketSessionId = sessionId(session);
        if (websocketSessionId == null) {
            return;
        }
        joinedSessionDiagnosticsByWebsocketSessionId.remove(websocketSessionId);
        modelIdByWebsocketSessionId.remove(websocketSessionId);
        joinedRefsBySessionKey.remove(websocketSessionId);
        String actorSessionId = actorSessionIdByWebsocketSessionId.remove(websocketSessionId);
        if (actorSessionId != null) {
            joinedRefsBySessionKey.remove(actorSessionId);
        }
    }

    private JoinedModelRef joinedRef(Session session, Actor actor) {
        String websocketSessionId = sessionId(session);
        if (websocketSessionId != null) {
            JoinedModelRef ref = joinedRefsBySessionKey.get(websocketSessionId);
            if (ref != null) {
                return ref;
            }
        }
        String actorSessionId = actor != null ? actor.sessionId() : null;
        if (actorSessionId == null || actorSessionId.isBlank()) {
            return null;
        }
        return joinedRefsBySessionKey.get(actorSessionId);
    }

    private void rememberSessionDiagnostics(Session session, String websocketSessionId, Actor actor, ResolvedModelRef resolvedRef) {
        if (session == null || websocketSessionId == null || websocketSessionId.isBlank()) {
            return;
        }
        String modelId = sessionModelId(session);
        if (modelId != null && !modelId.isBlank()) {
            modelIdByWebsocketSessionId.put(websocketSessionId, modelId);
        }
        Object rawRoles = session.getUserProperties().get(ArchimeshEndpoint.AUTH_SUBJECT_ROLES_KEY);
        @SuppressWarnings("unchecked")
        Set<String> roles = rawRoles instanceof Set<?> values
                ? values.stream().filter(v -> v != null).map(Object::toString)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))
                : Set.of();
        String userId = firstNonBlank(
                stringUserProperty(session, ArchimeshEndpoint.AUTH_SUBJECT_USER_ID_KEY),
                actor == null ? "" : actor.userId(),
                session.getUserPrincipal() == null ? "" : session.getUserPrincipal().getName(),
                "anonymous");
        joinedSessionDiagnosticsByWebsocketSessionId.put(websocketSessionId, new AdminActiveSession(
                websocketSessionId,
                userId,
                Set.copyOf(roles),
                resolvedRef.ref(),
                resolvedRef.writable()));
    }

    private List<AdminActiveSession> activeSessionsForModel(String modelId) {
        List<AdminActiveSession> sessions = new ArrayList<>();
        for (Map.Entry<String, AdminActiveSession> entry : joinedSessionDiagnosticsByWebsocketSessionId.entrySet()) {
            if (Objects.equals(modelId, modelIdByWebsocketSessionId.get(entry.getKey()))) {
                sessions.add(entry.getValue());
            }
        }
        sessions.sort(Comparator.comparing(AdminActiveSession::websocketSessionId));
        return sessions;
    }

    private String sessionModelId(Session session) {
        if (session == null || session.getRequestURI() == null) {
            return "";
        }
        String path = session.getRequestURI().getPath();
        if (path == null || path.isBlank()) {
            return "";
        }
        String marker = "/models/";
        int start = path.indexOf(marker);
        int end = path.lastIndexOf("/stream");
        if (start < 0 || end <= start + marker.length()) {
            return "";
        }
        return path.substring(start + marker.length(), end);
    }

    private String stringUserProperty(Session session, String key) {
        Object value = session == null ? null : session.getUserProperties().get(key);
        return value == null ? "" : value.toString().trim();
    }

    private void sendError(Session session, String modelId, String code, String message) {
        ServerEnvelope envelope = new ServerEnvelope("Error", new ErrorMessage(code, message));
        if (session != null) {
            sessionRegistry.send(session, envelope);
            return;
        }
        sessionRegistry.broadcast(modelId, envelope);
    }

    private MutableStyleCounters styleCounters(String modelId) {
        return styleCountersByModel.computeIfAbsent(modelId, key -> new MutableStyleCounters());
    }

    private AdminStyleCounters styleCountersSnapshot(String modelId) {
        MutableStyleCounters counters = styleCountersByModel.get(modelId);
        if (counters == null) {
            return new AdminStyleCounters(0, 0, 0, 0);
        }
        return new AdminStyleCounters(
                counters.received.get(),
                counters.accepted.get(),
                counters.rejected.get(),
                counters.applied.get());
    }

    private int countStyleOps(JsonNode ops) {
        if (ops == null || !ops.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode op : ops) {
            String type = op.path("type").asText("");
            if (!"UpdateViewObjectOpaque".equals(type) && !"UpdateConnectionOpaque".equals(type)) {
                continue;
            }
            JsonNode notation = op.path("notationJson");
            if (NotationMetadata.containsStyleActivityField(notation)) {
                count++;
            }
        }
        return count;
    }

    private String safeSessionId(Session session) {
        return session == null ? "n/a" : session.getId();
    }

    private String sessionId(Session session) {
        return session == null ? null : session.getId();
    }

    private void recordActivity(String modelId, String type, String details) {
        Deque<AdminActivityEvent> queue = recentActivityByModel.computeIfAbsent(modelId, key -> new ConcurrentLinkedDeque<>());
        queue.addLast(new AdminActivityEvent(Instant.now().toString(), type, modelId, details));
        while (queue.size() > MAX_ACTIVITY_EVENTS_PER_MODEL) {
            queue.pollFirst();
        }
    }

    private void recordStyleActivity(String modelId, String stage, int count, String opBatchId) {
        recordActivity(modelId, "StyleOps" + stage, "count=" + count + " opBatchId=" + opBatchId);
    }

    private static final class MutableStyleCounters {
        private final AtomicLong received = new AtomicLong();
        private final AtomicLong accepted = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private final AtomicLong applied = new AtomicLong();
    }

    private record ResolvedModelRef(String ref, long revision, boolean writable, JsonNode snapshot) {
    }

    private record JoinedModelRef(String ref, boolean writable) {
    }

    private JsonNode toJsonRange(RevisionRange range) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("from", range.from());
        node.put("to", range.to());
        return node;
    }

    private List<String> lockRequiredTargets(JsonNode ops) {
        List<String> targets = new ArrayList<>();
        if (ops == null || !ops.isArray()) {
            return targets;
        }

        for (JsonNode op : ops) {
            String type = op.path("type").asText("");
            if ("UpdateViewObjectOpaque".equals(type)) {
                String target = op.path("viewObjectId").asText(null);
                if (target != null) {
                    targets.add(target);
                }
            } else if ("UpdateConnectionOpaque".equals(type)) {
                String target = op.path("connectionId").asText(null);
                if (target != null) {
                    targets.add(target);
                }
            }
        }

        return targets;
    }

    private JsonNode prepareOpsForSubmission(String modelId, JsonNode ops) {
        // Bootstrap normalization runs before cascade expansion so generated ops inherit corrected references
        JsonNode normalized = normalizeViewRefsForBootstrap(modelId, ops);
        return expandDeleteCascades(modelId, normalized);
    }

    private JsonNode normalizeViewRefsForBootstrap(String modelId, JsonNode ops) {
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            return ops;
        }

        ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
        Set<String> explicitCreateViews = new LinkedHashSet<>();
        Set<String> ensureCreateViews = new LinkedHashSet<>();
        boolean changed = false;
        int remapCount = 0;

        String soleViewId = null;
        Integer viewCount = null;

        for (JsonNode opNode : ops) {
            ObjectNode op = opNode.deepCopy();
            String type = op.path("type").asText("");

            if ("CreateView".equals(type)) {
                String createViewId = op.path("view").path("id").asText(null);
                if (createViewId != null && !createViewId.isBlank()) {
                    explicitCreateViews.add(createViewId);
                }
            }

            String currentViewId = extractViewId(op);
            if (currentViewId != null && !currentViewId.isBlank() && !neo4jRepository.viewExists(modelId, currentViewId)) {
                if (viewCount == null) {
                    // Snapshot probe is lazy to avoid repeated repository reads per op
                    JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
                    JsonNode views = snapshot.path("views");
                    viewCount = views.isArray() ? views.size() : 0;
                    if (viewCount == 1) {
                        soleViewId = views.get(0).path("id").asText(null);
                    }
                }

                if (viewCount != null && viewCount == 1 && soleViewId != null && !soleViewId.isBlank()) {
                    // Single-view bootstrap convenience: remap dangling references to the only known view
                    setViewId(op, soleViewId);
                    changed = true;
                    remapCount++;
                    recordActivity(modelId, "ViewIdRemapped",
                            "type=" + type + " from=" + currentViewId + " to=" + soleViewId);
                } else if (viewCount != null && viewCount == 0) {
                    // Empty model bootstrap: seed referenced views so create ops can pass preconditions
                    ensureCreateViews.add(currentViewId);
                }
            }

            normalized.add(op);
        }

        ensureCreateViews.removeAll(explicitCreateViews);
        if (!ensureCreateViews.isEmpty()) {
            ArrayNode withSeededViews = JsonNodeFactory.instance.arrayNode();
            for (String viewId : ensureCreateViews) {
                ObjectNode createView = JsonNodeFactory.instance.objectNode();
                createView.put("type", "CreateView");
                ObjectNode view = JsonNodeFactory.instance.objectNode();
                view.put("id", viewId);
                view.put("name", "Default View");
                view.set("notationJson", JsonNodeFactory.instance.objectNode());
                createView.set("view", view);
                withSeededViews.add(createView);
                recordActivity(modelId, "SeedCreateView", "viewId=" + viewId + " reason=missing-view-reference");
            }
            LOG.info("[BOOTSTRAP_VIEW_FIX] modelId={} action=seed-create-view seededCount={} remappedCount={}",
                    modelId, ensureCreateViews.size(), remapCount);
            withSeededViews.addAll(normalized);
            return withSeededViews;
        }

        if (remapCount > 0) {
            LOG.info("[BOOTSTRAP_VIEW_FIX] modelId={} action=remap-view-id remappedCount={} targetViewId={}",
                    modelId, remapCount, soleViewId);
        }

        return changed ? normalized : ops;
    }

    private String extractViewId(ObjectNode op) {
        String type = op.path("type").asText("");
        return switch (type) {
            case "CreateViewObject" -> op.path("viewObject").path("viewId").asText(null);
            case "CreateConnection" -> op.path("connection").path("viewId").asText(null);
            case "UpdateView", "DeleteView", "UpdateViewObjectOpaque", "DeleteViewObject", "UpdateConnectionOpaque",
                 "DeleteConnection" -> op.path("viewId").asText(null);
            default -> op.path("viewId").asText(null);
        };
    }

    private void setViewId(ObjectNode op, String viewId) {
        String type = op.path("type").asText("");
        switch (type) {
            case "CreateViewObject" -> {
                ObjectNode viewObject = op.with("viewObject");
                viewObject.put("viewId", viewId);
            }
            case "CreateConnection" -> {
                ObjectNode connection = op.with("connection");
                connection.put("viewId", viewId);
            }
            default -> op.put("viewId", viewId);
        }
    }

    private JsonNode expandDeleteCascades(String modelId, JsonNode ops) {
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            return ops;
        }

        Set<String> deletedElements = new HashSet<>();
        Set<String> deletedRelationships = new HashSet<>();
        Set<String> deletedViewObjects = new HashSet<>();
        Set<String> deletedConnections = new HashSet<>();
        for (JsonNode op : ops) {
            collectExplicitDeleteIds(op, deletedElements, deletedRelationships, deletedViewObjects, deletedConnections);
        }

        ArrayNode expanded = JsonNodeFactory.instance.arrayNode();
        boolean changed = false;
        for (JsonNode op : ops) {
            String type = op.path("type").asText("");
            switch (type) {
                case "DeleteElement" -> {
                    String elementId = op.path("elementId").asText(null);
                    if (elementId != null && !elementId.isBlank()) {
                        changed |= enqueueDeleteElementCascade(
                                modelId, elementId, expanded,
                                deletedRelationships, deletedViewObjects, deletedConnections);
                    }
                }
                case "DeleteRelationship" -> {
                    String relationshipId = op.path("relationshipId").asText(null);
                    if (relationshipId != null && !relationshipId.isBlank()) {
                        changed |= enqueueDeleteRelationshipCascade(
                                modelId, relationshipId, expanded, deletedViewObjects, deletedConnections);
                    }
                }
                case "DeleteViewObject" -> {
                    String viewObjectId = op.path("viewObjectId").asText(null);
                    if (viewObjectId != null && !viewObjectId.isBlank()) {
                        for (String connectionId : neo4jRepository.findConnectionIdsByViewObject(modelId, viewObjectId)) {
                            if (connectionId != null && !connectionId.isBlank() && deletedConnections.add(connectionId)) {
                                expanded.add(newDeleteConnectionOp(connectionId, "CascadeDelete"));
                                changed = true;
                            }
                        }
                    }
                }
                default -> {
                    // no-op
                }
            }
            expanded.add(op);
        }

        return changed ? expanded : ops;
    }

    private boolean enqueueDeleteElementCascade(String modelId,
                                                String elementId,
                                                ArrayNode expanded,
                                                Set<String> deletedRelationships,
                                                Set<String> deletedViewObjects,
                                                Set<String> deletedConnections) {
        boolean changed = false;

        for (String relationshipId : neo4jRepository.findRelationshipIdsByElement(modelId, elementId)) {
            if (relationshipId == null || relationshipId.isBlank()) {
                continue;
            }
            changed |= enqueueDeleteRelationshipCascade(
                    modelId, relationshipId, expanded, deletedViewObjects, deletedConnections);
            if (deletedRelationships.add(relationshipId)) {
                expanded.add(newDeleteRelationshipOp(relationshipId, "CascadeDelete"));
                changed = true;
            }
        }

        for (String viewObjectId : neo4jRepository.findViewObjectIdsByRepresents(modelId, elementId)) {
            if (viewObjectId == null || viewObjectId.isBlank()) {
                continue;
            }
            for (String connectionId : neo4jRepository.findConnectionIdsByViewObject(modelId, viewObjectId)) {
                if (connectionId != null && !connectionId.isBlank() && deletedConnections.add(connectionId)) {
                    expanded.add(newDeleteConnectionOp(connectionId, "CascadeDelete"));
                    changed = true;
                }
            }
            if (deletedViewObjects.add(viewObjectId)) {
                expanded.add(newDeleteViewObjectOp(viewObjectId, "CascadeDelete"));
                changed = true;
            }
        }

        return changed;
    }

    private boolean enqueueDeleteRelationshipCascade(String modelId,
                                                     String relationshipId,
                                                     ArrayNode expanded,
                                                     Set<String> deletedViewObjects,
                                                     Set<String> deletedConnections) {
        boolean changed = false;
        for (String connectionId : neo4jRepository.findConnectionIdsByRelationship(modelId, relationshipId)) {
            if (connectionId != null && !connectionId.isBlank() && deletedConnections.add(connectionId)) {
                expanded.add(newDeleteConnectionOp(connectionId, "CascadeDelete"));
                changed = true;
            }
        }

        for (String viewObjectId : neo4jRepository.findViewObjectIdsByRepresents(modelId, relationshipId)) {
            if (viewObjectId == null || viewObjectId.isBlank()) {
                continue;
            }
            for (String connectionId : neo4jRepository.findConnectionIdsByViewObject(modelId, viewObjectId)) {
                if (connectionId != null && !connectionId.isBlank() && deletedConnections.add(connectionId)) {
                    expanded.add(newDeleteConnectionOp(connectionId, "CascadeDelete"));
                    changed = true;
                }
            }
            if (deletedViewObjects.add(viewObjectId)) {
                expanded.add(newDeleteViewObjectOp(viewObjectId, "CascadeDelete"));
                changed = true;
            }
        }
        return changed;
    }

    private void collectExplicitDeleteIds(JsonNode op,
                                          Set<String> deletedElements,
                                          Set<String> deletedRelationships,
                                          Set<String> deletedViewObjects,
                                          Set<String> deletedConnections) {
        if (op == null || !op.isObject()) {
            return;
        }
        String type = op.path("type").asText("");
        switch (type) {
            case "DeleteElement" -> addIfPresent(deletedElements, op.path("elementId").asText(null));
            case "DeleteRelationship" -> addIfPresent(deletedRelationships, op.path("relationshipId").asText(null));
            case "DeleteViewObject" -> addIfPresent(deletedViewObjects, op.path("viewObjectId").asText(null));
            case "DeleteConnection" -> addIfPresent(deletedConnections, op.path("connectionId").asText(null));
            default -> {
                // no-op
            }
        }
    }

    private ObjectNode newDeleteRelationshipOp(String relationshipId, String generatedBy) {
        ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("type", "DeleteRelationship");
        op.put("relationshipId", relationshipId);
        if (generatedBy != null && !generatedBy.isBlank()) {
            op.put("generatedBy", generatedBy);
        }
        return op;
    }

    private ObjectNode newDeleteViewObjectOp(String viewObjectId, String generatedBy) {
        ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("type", "DeleteViewObject");
        op.put("viewObjectId", viewObjectId);
        if (generatedBy != null && !generatedBy.isBlank()) {
            op.put("generatedBy", generatedBy);
        }
        return op;
    }

    private ObjectNode newDeleteConnectionOp(String connectionId, String generatedBy) {
        ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("type", "DeleteConnection");
        op.put("connectionId", connectionId);
        if (generatedBy != null && !generatedBy.isBlank()) {
            op.put("generatedBy", generatedBy);
        }
        return op;
    }

    private String summarizeOps(JsonNode ops) {
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            return "[]";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode op : ops) {
            String type = op.path("type").asText("?");
            String id = firstNonBlank(
                    op.path("elementId").asText(null),
                    op.path("relationshipId").asText(null),
                    op.path("viewId").asText(null),
                    op.path("viewObjectId").asText(null),
                    op.path("connectionId").asText(null),
                    op.path("targetId").asText(null));
            String name = extractName(op);
            StringBuilder part = new StringBuilder(type);
            if (id != null) {
                part.append("(").append(id);
                if (name != null) {
                    part.append(", name=").append(name);
                }
                part.append(")");
            } else if (name != null) {
                part.append("(name=").append(name).append(")");
            }
            parts.add(part.toString());
        }
        return parts.toString();
    }

    private String extractName(JsonNode op) {
        if (op == null || op.isMissingNode()) {
            return null;
        }
        JsonNode element = op.path("element");
        if (element.isObject()) {
            String name = element.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        JsonNode relationship = op.path("relationship");
        if (relationship.isObject()) {
            String name = relationship.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        JsonNode view = op.path("view");
        if (view.isObject()) {
            String name = view.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        JsonNode patch = op.path("patch");
        if (patch.isObject()) {
            String name = patch.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Optional<String> validatePreconditions(String modelId, JsonNode ops) {
        if (ops == null || !ops.isArray()) {
            return Optional.empty();
        }

        Set<String> createdElementIds = new HashSet<>();
        Set<String> createdRelationshipIds = new HashSet<>();
        Set<String> createdViewIds = new HashSet<>();
        Set<String> createdFolderIds = new HashSet<>();
        Map<String, String> createdFolderRoots = new HashMap<>();
        Set<String> createdViewObjectIds = new HashSet<>();
        Set<String> createdConnectionIds = new HashSet<>();

        for (JsonNode op : ops) {
            String type = op.path("type").asText("");
            switch (type) {
                case "CreateElement" -> addIfPresent(createdElementIds, op.path("element").path("id").asText(null));
                case "CreateRelationship" ->
                        addIfPresent(createdRelationshipIds, op.path("relationship").path("id").asText(null));
                case "CreateView" -> addIfPresent(createdViewIds, op.path("view").path("id").asText(null));
                case "CreateFolder" -> {
                    JsonNode folder = op.path("folder");
                    String folderId = folder.path("id").asText(null);
                    addIfPresent(createdFolderIds, folderId);
                    if (folderId != null && !folderId.isBlank()) {
                        String parentFolderId = folder.path("parentFolderId").asText(null);
                        String rootId = resolveFolderRootId(modelId, parentFolderId, createdFolderRoots);
                        if (rootId == null && folderId.startsWith("folder:root-")) {
                            rootId = folderId;
                        }
                        if (rootId != null && !rootId.isBlank()) {
                            createdFolderRoots.put(folderId, rootId);
                        }
                    }
                }
                case "CreateViewObject" ->
                        addIfPresent(createdViewObjectIds, op.path("viewObject").path("id").asText(null));
                case "CreateConnection" ->
                        addIfPresent(createdConnectionIds, op.path("connection").path("id").asText(null));
                default -> {
                    // no-op
                }
            }
        }

        // Second pass validates references against persisted state plus entities created in this same batch
        for (JsonNode op : ops) {
            String type = op.path("type").asText("");
            switch (type) {
                case "UpdateElement", "DeleteElement" -> {
                    String elementId = op.path("elementId").asText(null);
                    if (elementId == null || elementId.isBlank()
                            || !(createdElementIds.contains(elementId) || neo4jRepository.elementExists(modelId, elementId))) {
                        return Optional.of(type + " requires existing elementId: " + elementId);
                    }
                }
                case "CreateFolder" -> {
                    JsonNode folder = op.path("folder");
                    String folderId = folder.path("id").asText(null);
                    String parentFolderId = folder.path("parentFolderId").asText(null);
                    if (folderId == null || folderId.isBlank()) {
                        return Optional.of("CreateFolder requires folder.id");
                    }
                    if (parentFolderId != null && !parentFolderId.isBlank()
                            && !(createdFolderIds.contains(parentFolderId) || neo4jRepository.folderExists(modelId, parentFolderId))) {
                        return Optional.of("CreateFolder requires existing parentFolderId: " + parentFolderId);
                    }
                    String rootId = resolveFolderRootId(modelId, parentFolderId, createdFolderRoots);
                    if (rootId != null && !rootId.isBlank()) {
                        createdFolderRoots.put(folderId, rootId);
                    }
                }
                case "UpdateFolder", "DeleteFolder" -> {
                    String folderId = op.path("folderId").asText(null);
                    if (folderId == null || folderId.isBlank()
                            || !(createdFolderIds.contains(folderId) || neo4jRepository.folderExists(modelId, folderId))) {
                        return Optional.of(type + " requires existing folderId: " + folderId);
                    }
                    if ("DeleteFolder".equals(type) && !folderId.startsWith("folder:root-") && !neo4jRepository.folderEmpty(modelId, folderId)) {
                        return Optional.of("DeleteFolder requires an empty folder: " + folderId);
                    }
                    if ("DeleteFolder".equals(type) && folderId.startsWith("folder:root-")) {
                        return Optional.of("DeleteFolder cannot delete root folder: " + folderId);
                    }
                }
                case "MoveFolder" -> {
                    String folderId = op.path("folderId").asText(null);
                    String parentFolderId = op.path("parentFolderId").asText(null);
                    if (folderId == null || folderId.isBlank()
                            || !(createdFolderIds.contains(folderId) || neo4jRepository.folderExists(modelId, folderId))) {
                        return Optional.of("MoveFolder requires existing folderId: " + folderId);
                    }
                    if (folderId.startsWith("folder:root-")) {
                        return Optional.of("MoveFolder cannot move root folder: " + folderId);
                    }
                    if (parentFolderId == null || parentFolderId.isBlank()
                            || !(createdFolderIds.contains(parentFolderId) || neo4jRepository.folderExists(modelId, parentFolderId))) {
                        return Optional.of("MoveFolder requires existing parentFolderId: " + parentFolderId);
                    }
                    if (neo4jRepository.folderMoveCreatesCycle(modelId, folderId, parentFolderId)) {
                        return Optional.of("MoveFolder would create a cycle: " + folderId + " -> " + parentFolderId);
                    }
                    String folderRootId = resolveFolderRootId(modelId, folderId, createdFolderRoots);
                    String parentRootId = resolveFolderRootId(modelId, parentFolderId, createdFolderRoots);
                    if (folderRootId != null && parentRootId != null && !folderRootId.equals(parentRootId)) {
                        return Optional.of("MoveFolder cannot cross root folders: " + folderId + " -> " + parentFolderId);
                    }
                }
                case "CreateRelationship" -> {
                    JsonNode relationship = op.path("relationship");
                    String sourceId = relationship.path("sourceId").asText(null);
                    String targetId = relationship.path("targetId").asText(null);
                    if (sourceId == null || sourceId.isBlank()
                            || !(createdElementIds.contains(sourceId) || neo4jRepository.elementExists(modelId, sourceId))) {
                        return Optional.of("CreateRelationship requires existing sourceId: " + sourceId);
                    }
                    if (targetId == null || targetId.isBlank()
                            || !(createdElementIds.contains(targetId) || neo4jRepository.elementExists(modelId, targetId))) {
                        return Optional.of("CreateRelationship requires existing targetId: " + targetId);
                    }
                }
                case "UpdateRelationship", "DeleteRelationship" -> {
                    String relationshipId = op.path("relationshipId").asText(null);
                    if (relationshipId == null || relationshipId.isBlank()
                            || !(createdRelationshipIds.contains(relationshipId) || neo4jRepository.relationshipExists(modelId, relationshipId))) {
                        return Optional.of(type + " requires existing relationshipId: " + relationshipId);
                    }
                    if ("UpdateRelationship".equals(type)) {
                        JsonNode patch = op.path("patch");
                        if (patch.has("sourceId")) {
                            String sourceId = patch.path("sourceId").asText(null);
                            if (sourceId == null || sourceId.isBlank()
                                    || !(createdElementIds.contains(sourceId) || neo4jRepository.elementExists(modelId, sourceId))) {
                                return Optional.of("UpdateRelationship sourceId does not exist: " + sourceId);
                            }
                        }
                        if (patch.has("targetId")) {
                            String targetId = patch.path("targetId").asText(null);
                            if (targetId == null || targetId.isBlank()
                                    || !(createdElementIds.contains(targetId) || neo4jRepository.elementExists(modelId, targetId))) {
                                return Optional.of("UpdateRelationship targetId does not exist: " + targetId);
                            }
                        }
                    }
                }
                case "UpdateView", "DeleteView" -> {
                    String viewId = op.path("viewId").asText(null);
                    if (viewId == null || viewId.isBlank()
                            || !(createdViewIds.contains(viewId) || neo4jRepository.viewExists(modelId, viewId))) {
                        return Optional.of(type + " requires existing viewId: " + viewId);
                    }
                }
                case "CreateViewObject" -> {
                    JsonNode viewObject = op.path("viewObject");
                    String viewId = viewObject.path("viewId").asText(null);
                    String representsId = viewObject.path("representsId").asText(null);
                    if (viewId == null || viewId.isBlank()
                            || !(createdViewIds.contains(viewId) || neo4jRepository.viewExists(modelId, viewId))) {
                        return Optional.of("CreateViewObject requires existing viewId: " + viewId);
                    }
                    if (representsId == null || representsId.isBlank()
                            || !(createdElementIds.contains(representsId) || neo4jRepository.elementExists(modelId, representsId))) {
                        return Optional.of("CreateViewObject requires existing representsId: " + representsId);
                    }
                    Optional<String> invalidNotation = validateNotationKeys(
                            "CreateViewObject",
                            viewObject.path("notationJson"),
                            NotationMetadata.VIEW_OBJECT_FIELDS);
                    if (invalidNotation.isPresent()) {
                        return invalidNotation;
                    }
                }
                case "UpdateViewObjectOpaque", "DeleteViewObject" -> {
                    String viewObjectId = op.path("viewObjectId").asText(null);
                    if (viewObjectId == null || viewObjectId.isBlank()
                            || !(createdViewObjectIds.contains(viewObjectId) || neo4jRepository.viewObjectExists(modelId, viewObjectId))) {
                        return Optional.of(type + " requires existing viewObjectId: " + viewObjectId);
                    }
                    if ("UpdateViewObjectOpaque".equals(type)) {
                        Optional<String> invalidNotation = validateNotationKeys(
                                type,
                                op.path("notationJson"),
                                NotationMetadata.VIEW_OBJECT_FIELDS);
                        if (invalidNotation.isPresent()) {
                            return invalidNotation;
                        }
                    }
                }
                case "CreateConnection" -> {
                    JsonNode connection = op.path("connection");
                    String viewId = connection.path("viewId").asText(null);
                    String representsId = connection.path("representsId").asText(null);
                    String sourceId = connection.path("sourceViewObjectId").asText(null);
                    String targetId = connection.path("targetViewObjectId").asText(null);
                    if (viewId == null || viewId.isBlank()
                            || !(createdViewIds.contains(viewId) || neo4jRepository.viewExists(modelId, viewId))) {
                        return Optional.of("CreateConnection requires existing viewId: " + viewId);
                    }
                    if (representsId == null || representsId.isBlank()
                            || !(createdRelationshipIds.contains(representsId) || neo4jRepository.relationshipExists(modelId, representsId))) {
                        return Optional.of("CreateConnection requires existing representsId: " + representsId);
                    }
                    if (sourceId == null || sourceId.isBlank()
                            || !(createdViewObjectIds.contains(sourceId) || neo4jRepository.viewObjectExists(modelId, sourceId))) {
                        return Optional.of("CreateConnection requires existing sourceViewObjectId: " + sourceId);
                    }
                    if (targetId == null || targetId.isBlank()
                            || !(createdViewObjectIds.contains(targetId) || neo4jRepository.viewObjectExists(modelId, targetId))) {
                        return Optional.of("CreateConnection requires existing targetViewObjectId: " + targetId);
                    }
                    Optional<String> invalidNotation = validateNotationKeys(
                            "CreateConnection",
                            connection.path("notationJson"),
                            NotationMetadata.CONNECTION_FIELDS);
                    if (invalidNotation.isPresent()) {
                        return invalidNotation;
                    }
                }
                case "UpdateConnectionOpaque", "DeleteConnection" -> {
                    String connectionId = op.path("connectionId").asText(null);
                    if (connectionId == null || connectionId.isBlank()
                            || !(createdConnectionIds.contains(connectionId) || neo4jRepository.connectionExists(modelId, connectionId))) {
                        return Optional.of(type + " requires existing connectionId: " + connectionId);
                    }
                    if ("UpdateConnectionOpaque".equals(type)) {
                        Optional<String> invalidNotation = validateNotationKeys(
                                type,
                                op.path("notationJson"),
                                NotationMetadata.CONNECTION_FIELDS);
                        if (invalidNotation.isPresent()) {
                            return invalidNotation;
                        }
                    }
                }
                case "MoveElementToFolder" -> {
                    String elementId = op.path("elementId").asText(null);
                    String folderId = op.path("folderId").asText(null);
                    if (elementId == null || elementId.isBlank()
                            || !(createdElementIds.contains(elementId) || neo4jRepository.elementExists(modelId, elementId))) {
                        return Optional.of("MoveElementToFolder requires existing elementId: " + elementId);
                    }
                    if (folderId == null || folderId.isBlank()
                            || !(createdFolderIds.contains(folderId) || neo4jRepository.folderExists(modelId, folderId))) {
                        return Optional.of("MoveElementToFolder requires existing folderId: " + folderId);
                    }
                }
                case "MoveRelationshipToFolder" -> {
                    String relationshipId = op.path("relationshipId").asText(null);
                    String folderId = op.path("folderId").asText(null);
                    if (relationshipId == null || relationshipId.isBlank()
                            || !(createdRelationshipIds.contains(relationshipId) || neo4jRepository.relationshipExists(modelId, relationshipId))) {
                        return Optional.of("MoveRelationshipToFolder requires existing relationshipId: " + relationshipId);
                    }
                    if (folderId == null || folderId.isBlank()
                            || !(createdFolderIds.contains(folderId) || neo4jRepository.folderExists(modelId, folderId))) {
                        return Optional.of("MoveRelationshipToFolder requires existing folderId: " + folderId);
                    }
                }
                case "MoveViewToFolder" -> {
                    String viewId = op.path("viewId").asText(null);
                    String folderId = op.path("folderId").asText(null);
                    if (viewId == null || viewId.isBlank()
                            || !(createdViewIds.contains(viewId) || neo4jRepository.viewExists(modelId, viewId))) {
                        return Optional.of("MoveViewToFolder requires existing viewId: " + viewId);
                    }
                    if (folderId == null || folderId.isBlank()
                            || !(createdFolderIds.contains(folderId) || neo4jRepository.folderExists(modelId, folderId))) {
                        return Optional.of("MoveViewToFolder requires existing folderId: " + folderId);
                    }
                }
                default -> {
                    // no-op
                }
            }
        }

        return Optional.empty();
    }

    private String resolveFolderRootId(String modelId, String folderId, Map<String, String> createdFolderRoots) {
        if (folderId == null || folderId.isBlank()) {
            return null;
        }
        if (folderId.startsWith("folder:root-")) {
            return folderId;
        }
        String created = createdFolderRoots.get(folderId);
        if (created != null && !created.isBlank()) {
            return created;
        }
        return neo4jRepository.folderRootId(modelId, folderId);
    }

    private Optional<String> validateNotationKeys(String opType, JsonNode notationJson, Set<String> allowedKeys) {
        if (notationJson == null || notationJson.isMissingNode() || notationJson.isNull()) {
            return Optional.empty();
        }
        if (!notationJson.isObject()) {
            return Optional.of(opType + " notationJson must be an object");
        }

        Set<String> unknownKeys = new TreeSet<>();
        notationJson.fieldNames().forEachRemaining(field -> {
            if (!allowedKeys.contains(field)) {
                unknownKeys.add(field);
            }
        });
        if (unknownKeys.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(opType + " notationJson has unsupported field(s): " + String.join(",", unknownKeys));
    }

    private void addIfPresent(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value);
        }
    }

    private void runConsistencyChecksIfEnabled(String modelId, String opBatchId, long assignedHeadRevision) {
        if (!consistencyChecksEnabled) {
            return;
        }
        long latestCommitRevision = neo4jRepository.readLatestCommitRevision(modelId);
        boolean consistent = neo4jRepository.isMaterializedStateConsistent(modelId, assignedHeadRevision);
        if (latestCommitRevision != assignedHeadRevision || !consistent) {
            LOG.error("Consistency check failed: modelId={} opBatchId={} expectedHead={} latestCommitRevision={} materializedConsistent={}",
                    modelId, opBatchId, assignedHeadRevision, latestCommitRevision, consistent);
        } else {
            LOG.debug("Consistency check passed: modelId={} opBatchId={} headRevision={}",
                    modelId, opBatchId, assignedHeadRevision);
        }
    }

    static boolean coversRevisions(JsonNode batches, long from, long to) {
        if (from > to) return batches != null && batches.isArray() && batches.isEmpty();
        if (batches == null || !batches.isArray()) return false;
        long next = from;
        for (JsonNode batch : batches) {
            long start = batch.path("assignedRevisionRange").path("from").asLong(-1);
            long end = batch.path("assignedRevisionRange").path("to").asLong(-1);
            if (start != next || end < start || !batch.path("ops").isArray()
                    || batch.path("ops").size() != end - start + 1) return false;
            next = end + 1;
        }
        return next == to + 1;
    }

    private void reply(Session session, String modelId, ServerEnvelope message) {
        if (session == null) sessionRegistry.broadcast(modelId, message);
        else sessionRegistry.send(session, message);
    }

    private JsonNode normalizeOpsWithCausal(JsonNode ops, Actor actor, String opBatchId, long assignedFromRevision) {
        if (ops == null || !ops.isArray()) {
            return JsonNodeFactory.instance.arrayNode();
        }

        String actorClientId = firstNonBlank(actor.sessionId(), actor.userId(), "anonymous-session");
        ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
        int index = 0;
        for (JsonNode op : ops) {
            ObjectNode opNode = op != null && op.isObject() ? ((ObjectNode) op).deepCopy() : JsonNodeFactory.instance.objectNode();

            ObjectNode causalNode = opNode.path("causal").isObject()
                    ? ((ObjectNode) opNode.path("causal")).deepCopy()
                    : JsonNodeFactory.instance.objectNode();

            String incomingClientId = causalNode.path("clientId").asText(null);
            String clientId = firstNonBlank(incomingClientId, actorClientId, "anonymous-session");
            long lamport = causalNode.path("lamport").asLong(-1L);
            if (lamport < 0) {
                // Deterministic fallback when clients omit causal metadata
                lamport = assignedFromRevision + index;
            }
            String incomingOpId = causalNode.path("opId").asText(null);
            String opId = firstNonBlank(incomingOpId, opBatchId + ":" + index);

            causalNode.put("clientId", clientId);
            causalNode.put("lamport", lamport);
            causalNode.put("opId", opId);
            opNode.set("causal", causalNode);

            normalized.add(opNode);
            index++;
        }

        return normalized;
    }
}
