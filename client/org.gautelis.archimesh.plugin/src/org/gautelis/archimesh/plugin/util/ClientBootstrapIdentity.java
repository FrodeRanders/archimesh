package org.gautelis.archimesh.plugin.util;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Explicit identity for local bootstrap-mode servers; bearer sessions take precedence. */
public final class ClientBootstrapIdentity {
    private ClientBootstrapIdentity() { }

    public static void applyRestHeaders(HttpRequest.Builder request, URI uri, String bearerToken) {
        Identity identity = resolve(uri, bearerToken);
        if(identity != null) {
            request.setHeader("X-Archimesh-User", identity.user());
            request.setHeader("X-Archimesh-Roles", identity.roles());
        }
    }

    public static URI webSocketUri(URI uri, String bearerToken) {
        Identity identity = resolve(uri, bearerToken);
        if(identity == null) return uri;
        if(uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Bootstrap WebSocket endpoint must not contain a query or fragment");
        }
        return URI.create(uri + "?user=" + encode(identity.user()) + "&roles=" + encode(identity.roles()));
    }

    private static Identity resolve(URI uri, String bearerToken) {
        if(bearerToken != null && !bearerToken.isBlank()) return null;
        String user = setting("archimesh.bootstrap.user", "ARCHIMESH_BOOTSTRAP_USER");
        String roles = setting("archimesh.bootstrap.roles", "ARCHIMESH_BOOTSTRAP_ROLES");
        if(user.isBlank() && roles.isBlank()) return null;
        if(user.isBlank() || roles.isBlank()) {
            throw new IllegalArgumentException("Set both ARCHIMESH_BOOTSTRAP_USER and ARCHIMESH_BOOTSTRAP_ROLES");
        }
        if(!Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost())) {
            throw new IllegalArgumentException("Client bootstrap identity is restricted to loopback servers");
        }
        if(user.length() > 512 || roles.length() > 512
                || user.chars().anyMatch(Character::isISOControl)
                || roles.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid client bootstrap identity");
        }
        return new Identity(user, roles);
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        if(value == null) value = System.getenv(environment);
        return value == null ? "" : value.trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Identity(String user, String roles) { }
}
