package org.gautelis.archimesh.auth;

import org.gautelis.archimesh.model.ModelAccessControl;
import org.gautelis.archimesh.endpoint.ArchimeshEndpointConfigurator;
import org.gautelis.archimesh.service.ArchimeshService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.Session;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

@ApplicationScoped
public class AuthorizationService {
    public static final String USER_HEADER = "X-Archimesh-User";
    public static final String ROLES_HEADER = "X-Archimesh-Roles";
    public static final String PROXY_USER_HEADER_DEFAULT = "X-Forwarded-User";
    public static final String PROXY_ROLES_HEADER_DEFAULT = "X-Forwarded-Roles";
    public static final String WS_USER_PARAM = "user";
    public static final String WS_ROLES_PARAM = "roles";

    @Inject
    PolicyDecisionPoint policyDecisionPoint;

    @Inject
    ArchimeshService archimeshService;

    @Inject
    JWTParser jwtParser;

    @Inject
    AuthorizationRoleMapper roleMapper;

    @ConfigProperty(name = "app.authz.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "app.identity.mode", defaultValue = "bootstrap")
    String identityModeRaw;

    @ConfigProperty(name = "app.identity.proxy.user-header", defaultValue = PROXY_USER_HEADER_DEFAULT)
    String proxyUserHeader;

    @ConfigProperty(name = "app.identity.proxy.roles-header", defaultValue = PROXY_ROLES_HEADER_DEFAULT)
    String proxyRolesHeader;

    @ConfigProperty(name = "app.authz.admin-role", defaultValue = "admin")
    String adminRole;

    @ConfigProperty(name = "app.authz.reader-role", defaultValue = "model_reader")
    String readerRole;

    @ConfigProperty(name = "app.authz.writer-role", defaultValue = "model_writer")
    String writerRole;

    public void requireRestAllowed(HttpHeaders headers, SecurityContext securityContext, AuthorizationAction action, String modelId, String ref) {
        if (!enabled) {
            return;
        }
        // Identity extraction stays at the transport edge so the PDP can evaluate one uniform request shape.
        AuthorizationDecision decision = policyDecisionPoint.decide(new AuthorizationRequest(
                currentRestSubject(headers, securityContext),
                action,
                modelId,
                ref,
                lookupAccessControl(modelId),
                AuthorizationTransport.REST));
        if (!decision.allowed()) {
            throw new WebApplicationException(decision.reason(), Response.Status.FORBIDDEN);
        }
    }

    public void requireWebSocketAllowed(Session session, AuthorizationAction action, String modelId, String ref) {
        if (!enabled) {
            return;
        }
        // Websocket authorization is evaluated per inbound action, not just once at connect time.
        AuthorizationDecision decision = policyDecisionPoint.decide(new AuthorizationRequest(
                currentWebSocketSubject(session),
                action,
                modelId,
                ref,
                lookupAccessControl(modelId),
                AuthorizationTransport.WEBSOCKET));
        if (!decision.allowed()) {
            throw new AuthorizationDeniedException(decision.code(), decision.reason());
        }
    }

    /** Recheck a transport-resolved subject inside serialized internal model acceptance. */
    public void requireSubjectAllowed(AuthorizationSubject subject, AuthorizationAction action, String modelId, String ref) {
        if (subject == null || subject.userId() == null || subject.userId().isBlank())
            throw new WebApplicationException("Authenticated integration user required", Response.Status.FORBIDDEN);
        if (!enabled) return;
        var decision = policyDecisionPoint.decide(new AuthorizationRequest(subject,action,modelId,ref,
                lookupAccessControl(modelId),AuthorizationTransport.REST));
        if (!decision.allowed()) throw new WebApplicationException(decision.reason(),Response.Status.FORBIDDEN);
    }

    public AuthorizationSubject currentRestSubject(HttpHeaders headers, SecurityContext securityContext) {
        // The PEP always consumes the same subject shape; only the identity source varies by deployment mode.
        return switch (identityMode()) {
            case BOOTSTRAP -> subjectFromHeaderNames(headers, USER_HEADER, ROLES_HEADER);
            case PROXY -> subjectFromHeaderNames(headers, proxyUserHeader, proxyRolesHeader);
            case OIDC -> subjectFromSecurityContext(securityContext);
        };
    }

    public AuthorizationSubject currentWebSocketSubject(Session session) {
        return switch (identityMode()) {
            case BOOTSTRAP -> subjectFromBootstrapWebSocket(session);
            case PROXY -> subjectFromProxyWebSocket(session);
            case OIDC -> subjectFromOidcWebSocket(session);
        };
    }

    public String currentIdentityMode() {
        return identityMode().name().toLowerCase(Locale.ROOT);
    }

    private ModelAccessControl lookupAccessControl(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        return archimeshService.findModelAccessControl(modelId).orElse(null);
    }

    private IdentityMode identityMode() {
        return IdentityMode.fromConfig(identityModeRaw);
    }

    private AuthorizationSubject subjectFromHeaderNames(HttpHeaders headers, String userHeader, String rolesHeader) {
        if (headers == null) {
            return new AuthorizationSubject("", Set.of());
        }
        // Bootstrap and proxy modes share the same subject shape; only the trusted header names differ.
        return new AuthorizationSubject(
                trim(headers.getHeaderString(userHeader)),
                roleMapper.normalizeRoles(parseRoles(headers.getHeaderString(rolesHeader))));
    }

    private AuthorizationSubject subjectFromBootstrapWebSocket(Session session) {
        if (session == null) {
            return new AuthorizationSubject("", Set.of());
        }
        // Bootstrap mode mirrors REST dev headers with websocket query params for local testing.
        Map<String, java.util.List<String>> params = session.getRequestParameterMap();
        return new AuthorizationSubject(
                trim(first(params, WS_USER_PARAM)),
                roleMapper.normalizeRoles(parseRoles(first(params, WS_ROLES_PARAM))));
    }

    private AuthorizationSubject subjectFromProxyWebSocket(Session session) {
        if (session == null) {
            return new AuthorizationSubject("", Set.of());
        }
        // Proxy mode relies on headers captured during the upgrade because HttpHeaders are gone after connect.
        Map<String, java.util.List<String>> headers = handshakeHeaders(session);
        return new AuthorizationSubject(
                trim(first(headers, proxyUserHeader)),
                roleMapper.normalizeRoles(parseRoles(first(headers, proxyRolesHeader))));
    }

    private AuthorizationSubject subjectFromSecurityContext(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            return new AuthorizationSubject("", Set.of());
        }
        String userId = trim(securityContext.getUserPrincipal().getName());
        return new AuthorizationSubject(userId, roleMapper.rolesFromSecurityContext(securityContext));
    }

    private AuthorizationSubject subjectFromOidcWebSocket(Session session) {
        if (session == null) {
            return new AuthorizationSubject("", Set.of());
        }
        // Prefer the principal/roles captured during the upgrade. Only fall back to bearer-token parsing when
        // the container did not surface them on the websocket session itself.
        String userId = "";
        if (session.getUserPrincipal() != null && session.getUserPrincipal().getName() != null) {
            userId = trim(session.getUserPrincipal().getName());
        }
        if (userId.isBlank()) {
            Object stored = session.getUserProperties().get(ArchimeshEndpointConfigurator.HANDSHAKE_PRINCIPAL_NAME_KEY);
            userId = trim(stored == null ? "" : stored.toString());
        }
        Set<String> roles = roleMapper.normalizeRoles(handshakeRoles(session));
        if (userId.isBlank() || roles.isEmpty()) {
            AuthorizationSubject subject = subjectFromBearerToken(bearerToken(handshakeHeaders(session)));
            if (!subject.userId().isBlank()) {
                return subject;
            }
        }
        return new AuthorizationSubject(userId, roles);
    }

    @SuppressWarnings("unchecked")
    private Map<String, java.util.List<String>> handshakeHeaders(Session session) {
        Object raw = session.getUserProperties().get(ArchimeshEndpointConfigurator.HANDSHAKE_HEADERS_KEY);
        if (raw instanceof Map<?, ?> map) {
            Map<String, java.util.List<String>> headers = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null || !(entry.getValue() instanceof java.util.List<?> values)) {
                    continue;
                }
                java.util.List<String> strings = values.stream()
                        .filter(v -> v != null)
                        .map(Object::toString)
                        .toList();
                headers.put(entry.getKey().toString(), strings);
            }
            return headers;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Set<String> handshakeRoles(Session session) {
        Object raw = session.getUserProperties().get(ArchimeshEndpointConfigurator.HANDSHAKE_ROLES_KEY);
        if (raw instanceof Set<?> values) {
            LinkedHashSet<String> roles = new LinkedHashSet<>();
            for (Object value : values) {
                if (value != null) {
                    roles.add(trim(value.toString()).toLowerCase(Locale.ROOT));
                }
            }
            return Set.copyOf(roles);
        }
        return Set.of();
    }

    private AuthorizationSubject subjectFromBearerToken(String token) {
        if (token == null || token.isBlank()) {
            return new AuthorizationSubject("", Set.of());
        }
        try {
            // This is only an identity extraction fallback for websocket handshakes, not a second policy engine.
            JsonWebToken jwt = jwtParser.parse(token);
            String userId = trim(jwt.getName());
            LinkedHashSet<String> roles = new LinkedHashSet<>();
            if (jwt.getGroups() != null) {
                jwt.getGroups().stream()
                        .filter(v -> v != null && !v.isBlank())
                        .map(v -> v.toLowerCase(Locale.ROOT))
                        .forEach(roles::add);
            }
            return new AuthorizationSubject(userId, roleMapper.normalizeRoles(Set.copyOf(roles)));
        } catch (ParseException e) {
            return new AuthorizationSubject("", Set.of());
        }
    }

    private String bearerToken(Map<String, java.util.List<String>> headers) {
        String authorization = trim(first(headers, HttpHeaders.AUTHORIZATION));
        if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return "";
    }

    private String first(Map<String, java.util.List<String>> params, String key) {
        if (params == null) {
            return "";
        }
        java.util.List<String> values = params.get(key);
        if ((values == null || values.isEmpty()) && key != null) {
            values = params.get(key.toLowerCase(Locale.ROOT));
        }
        if ((values == null || values.isEmpty()) && key != null) {
            values = params.get(key.toUpperCase(Locale.ROOT));
        }
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.getFirst();
    }

    private Set<String> parseRoles(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(this::trim)
                .filter(v -> !v.isBlank())
                .map(v -> v.toLowerCase(Locale.ROOT))
                .forEach(roles::add);
        return Set.copyOf(roles);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
