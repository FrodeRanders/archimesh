package org.gautelis.archimesh.endpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gautelis.archimesh.auth.AuthorizationAction;
import org.gautelis.archimesh.auth.AuthorizationDeniedException;
import org.gautelis.archimesh.auth.AuthorizationService;
import org.gautelis.archimesh.model.WebSocketAuditEvent;
import org.gautelis.archimesh.service.ArchimeshService;
import org.gautelis.archimesh.model.Actor;
import org.gautelis.archimesh.service.SessionRegistry;
import org.gautelis.archimesh.wire.ClientEnvelope;
import org.gautelis.archimesh.wire.ServerEnvelope;
import org.gautelis.archimesh.wire.inbound.*;
import org.gautelis.archimesh.wire.outbound.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * WebSocket endpoint for real-time collaborative model editing. Handles client lifecycle
 * (open, message, close, error) for the {@code /models/{modelId}/stream} path,
 * performing authorization checks and delegating business logic to {@link org.gautelis.archimesh.service.ArchimeshService}.
 */
@ServerEndpoint(value = "/models/{modelId}/stream", configurator = ArchimeshEndpointConfigurator.class)
public class ArchimeshEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(ArchimeshEndpoint.class);
    public static final String AUTH_SUBJECT_USER_ID_KEY = "archimesh.auth.subject.userId";
    public static final String AUTH_SUBJECT_ROLES_KEY = "archimesh.auth.subject.roles";
    public static final String AUTH_SUBJECT_REF_KEY = "archimesh.auth.subject.ref";
    public static final String AUTH_SUBJECT_WRITABLE_KEY = "archimesh.auth.subject.writable";

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ArchimeshService archimeshService;

    @Inject
    SessionRegistry sessionRegistry;

    @Inject
    AuthorizationService authorizationService;

    @ConfigProperty(name = "app.audit.websocket.actions",
            defaultValue = "WebSocketOpenRejected,WebSocketJoin,WebSocketMessageRejected,WebSocketClosed")
    String configuredAuditActions;

    @ConfigProperty(name = "app.audit.websocket.verbose", defaultValue = "false")
    boolean verboseWebSocketAudit;

    /**
     * Handles initial WebSocket connection by authorizing the client for model access
     * and storing resolved subject information in the session user properties.
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("modelId") String modelId) {
        try {
            // The initial socket open only proves the caller may attempt HEAD. The authoritative ref check still
            // happens again on Join because clients may later target a read-only tag.
            authorizationService.requireWebSocketAllowed(session, AuthorizationAction.MODEL_JOIN, modelId, "HEAD");
            rememberAuthorizedSubject(session, "HEAD", true);
        } catch (AuthorizationDeniedException ex) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("code", ex.code());
            context.put("ref", "HEAD");
            audit("WebSocketOpenRejected", modelId, session, null, context);
            sessionRegistry.send(session, new ServerEnvelope("Error",
                    new ErrorMessage(ex.code(), ex.getMessage())));
            closeUnauthorized(session, ex.getMessage());
            return;
        }
        LOG.debug("WebSocket opened: sessionId={} modelId={}", sessionId(session), modelId);
    }

    /**
     * Decodes incoming JSON messages, enforces per-action authorization, and dispatches
     * to the appropriate handler in ArchimeshService based on the envelope type.
     */
    @OnMessage
    public void onMessage(Session session, @PathParam("modelId") String modelId, String message) {
        try {
            ClientEnvelope envelope = objectMapper.readValue(message, ClientEnvelope.class);
            if (envelope.type() == null || envelope.type().isBlank()) {
                throw new IllegalArgumentException("Missing message type");
            }

            // Keep endpoint thin: decode envelope and delegate business rules to ArchimeshService
            switch (envelope.type()) {
                case "Join" -> {
                    JoinMessage join = objectMapper.treeToValue(envelope.payload(), JoinMessage.class);
                    authorizationService.requireWebSocketAllowed(session, AuthorizationAction.MODEL_JOIN, modelId,
                            join == null ? "HEAD" : join.ref());
                    // Join establishes the effective ref for all later write checks and admin session diagnostics.
                    rememberAuthorizedSubject(session, join == null ? "HEAD" : join.ref(),
                            join == null || join.ref() == null || join.ref().isBlank() || "HEAD".equalsIgnoreCase(join.ref()));
                    archimeshService.onJoin(modelId, session, join);
                    Map<String, Object> context = new LinkedHashMap<>();
                    context.put("ref", join == null || join.ref() == null || join.ref().isBlank() ? "HEAD" : join.ref());
                    context.put("writable", join == null || join.ref() == null || join.ref().isBlank() || "HEAD".equalsIgnoreCase(join.ref()));
                    context.put("messageType", "Join");
                    audit("WebSocketJoin", modelId, session, authorizedUserId(session), context);
                }
                case "SubmitOps" -> {
                    authorizationService.requireWebSocketAllowed(session, AuthorizationAction.MODEL_SUBMIT_OPS, modelId, null);
                    SubmitOpsMessage submit = objectMapper.treeToValue(envelope.payload(), SubmitOpsMessage.class);
                    archimeshService.onSubmitOps(modelId, session,
                            new SubmitOpsMessage(submit.baseRevision(), submit.opBatchId(), trustedActor(session), submit.ops()));
                    auditVerboseMessage("SubmitOps", modelId, session);
                }
                case "AcquireLock" -> {
                    authorizationService.requireWebSocketAllowed(session, AuthorizationAction.MODEL_ACQUIRE_LOCK, modelId, null);
                    AcquireLockMessage acquire = objectMapper.treeToValue(envelope.payload(), AcquireLockMessage.class);
                    archimeshService.onAcquireLock(modelId, session,
                            new AcquireLockMessage(trustedActor(session), acquire.targets(), acquire.ttlMs()));
                    auditVerboseMessage("AcquireLock", modelId, session);
                }
                case "ReleaseLock" -> {
                    authorizationService.requireWebSocketAllowed(session, AuthorizationAction.MODEL_RELEASE_LOCK, modelId, null);
                    ReleaseLockMessage release = objectMapper.treeToValue(envelope.payload(), ReleaseLockMessage.class);
                    archimeshService.onReleaseLock(modelId, session,
                            new ReleaseLockMessage(trustedActor(session), release.targets()));
                    auditVerboseMessage("ReleaseLock", modelId, session);
                }
                case "Presence" -> {
                    authorizationService.requireWebSocketAllowed(session, AuthorizationAction.MODEL_PRESENCE, modelId, null);
                    archimeshService.onPresence(modelId, session,
                            objectMapper.treeToValue(envelope.payload(), PresenceMessage.class));
                    auditVerboseMessage("Presence", modelId, session);
                }
                default -> {
                    LOG.warn("Unsupported message type: sessionId={} modelId={} type={}",
                            sessionId(session), modelId, envelope.type());
                    sessionRegistry.send(session, new ServerEnvelope("Error",
                            new ErrorMessage("UNSUPPORTED_MESSAGE_TYPE", envelope.type())));
                }
            }
        } catch (JsonProcessingException e) {
            // Protocol-level JSON errors are returned as structured Error messages over the same session
            LOG.warn("Invalid json payload: sessionId={} modelId={} error={}",
                    sessionId(session), modelId, e.getOriginalMessage());
            sessionRegistry.send(session, new ServerEnvelope("Error",
                    new ErrorMessage("INVALID_JSON", e.getOriginalMessage())));
        } catch (AuthorizationDeniedException e) {
            LOG.warn("Authorization denied: sessionId={} modelId={} code={}",
                    sessionId(session), modelId, e.code());
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("code", e.code());
            context.put("ref", stringProp(session, AUTH_SUBJECT_REF_KEY, "HEAD"));
            audit("WebSocketMessageRejected", modelId, session, authorizedUserId(session), context);
            sessionRegistry.send(session, new ServerEnvelope("Error",
                    new ErrorMessage(e.code(), e.getMessage())));
        } catch (Exception e) {
            LOG.error("Request handling failed: sessionId={} modelId={}",
                    sessionId(session), modelId, e);
            sessionRegistry.send(session, new ServerEnvelope("Error",
                    new ErrorMessage("REQUEST_FAILED", "Submission could not be committed; retry the same batch", submissionBatchId(message))));
        }
    }

    /**
     * Audits client disconnection and delegates cleanup to the service layer.
     */
    @OnClose
    public void onClose(Session session, @PathParam("modelId") String modelId) {
        LOG.info("WebSocket closed: sessionId={} modelId={}", sessionId(session), modelId);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("ref", stringProp(session, AUTH_SUBJECT_REF_KEY, "HEAD"));
        context.put("writable", Boolean.parseBoolean(stringProp(session, AUTH_SUBJECT_WRITABLE_KEY, "false")));
        audit("WebSocketClosed", modelId, session, authorizedUserId(session), context);
        archimeshService.onDisconnect(modelId, session);
    }

    /**
     * Logs WebSocket transport errors and triggers session cleanup.
     */
    @OnError
    public void onError(Session session, @PathParam("modelId") String modelId, Throwable throwable) {
        LOG.warn("WebSocket error: sessionId={} modelId={}", sessionId(session), modelId, throwable);
        archimeshService.onDisconnect(modelId, session);
    }

    private Actor trustedActor(Session session) {
        return new Actor(stringProp(session, AUTH_SUBJECT_USER_ID_KEY, "anonymous"), sessionId(session));
    }

    private String submissionBatchId(String message) {
        try {
            var envelope = objectMapper.readTree(message);
            return "SubmitOps".equals(envelope.path("type").asText())
                    ? envelope.path("payload").path("opBatchId").asText(null) : null;
        } catch (Exception ignored) { return null; }
    }

    private String sessionId(Session session) {
        return session == null ? "n/a" : session.getId();
    }

    private void closeUnauthorized(Session session, String reason) {
        if (session == null) {
            return;
        }
        try {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
        } catch (Exception ignored) {
        }
    }

    private void rememberAuthorizedSubject(Session session, String ref, boolean writable) {
        if (session == null) {
            return;
        }
        var subject = authorizationService.currentWebSocketSubject(session);
        session.getUserProperties().put(AUTH_SUBJECT_USER_ID_KEY, subject.userId());
        session.getUserProperties().put(AUTH_SUBJECT_ROLES_KEY, subject.roles());
        session.getUserProperties().put(AUTH_SUBJECT_REF_KEY, ref == null || ref.isBlank() ? "HEAD" : ref);
        session.getUserProperties().put(AUTH_SUBJECT_WRITABLE_KEY, Boolean.toString(writable));
    }

    private void audit(String action, String modelId, Session session, String userId, Map<String, Object> context) {
        if (!allowedAuditActions().contains(action)) {
            return;
        }
        // Keep websocket audit payloads machine-readable and stable so downstream sinks do not depend on message text.
        WebSocketAuditEvent event = new WebSocketAuditEvent(
                Instant.now().toString(),
                action,
                modelId == null || modelId.isBlank() ? "-" : modelId,
                sessionId(session),
                userId == null || userId.isBlank() ? "anonymous" : userId,
                context == null ? Map.of() : context);
        try {
            LOG.info("ws_audit {}", objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            LOG.info("ws_audit action={} modelId={} websocketSessionId={} userId={} context={}",
                    event.action(), event.modelId(), event.websocketSessionId(), event.userId(), event.context());
        }
    }

    private Set<String> allowedAuditActions() {
        if (configuredAuditActions == null || configuredAuditActions.isBlank()) {
            return Set.of();
        }
        Set<String> actions = new LinkedHashSet<>();
        Arrays.stream(configuredAuditActions.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(actions::add);
        return actions;
    }

    private String authorizedUserId(Session session) {
        return stringProp(session, AUTH_SUBJECT_USER_ID_KEY, "");
    }

    private void auditVerboseMessage(String messageType, String modelId, Session session) {
        if (!verboseWebSocketAudit) {
            return;
        }
        // Verbose mode is intentionally separate from lifecycle audit so high-volume accepted messages stay opt-in.
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("messageType", messageType);
        context.put("ref", stringProp(session, AUTH_SUBJECT_REF_KEY, "HEAD"));
        context.put("writable", Boolean.parseBoolean(stringProp(session, AUTH_SUBJECT_WRITABLE_KEY, "false")));
        audit("WebSocketMessageAccepted", modelId, session, authorizedUserId(session), context);
    }

    private String stringProp(Session session, String key, String fallback) {
        if (session == null) {
            return fallback;
        }
        Object value = session.getUserProperties().get(key);
        if (value == null) {
            return fallback;
        }
        String text = value.toString().trim();
        return text.isBlank() ? fallback : text;
    }
}
