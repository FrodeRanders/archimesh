package org.gautelis.archimesh.plugin.util;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ArchimeshAuthHints {

    private static final String BEARER_TOKEN_PREFLIGHT_HINT =
            "Bearer token mode: verify that the token is still valid, unexpired, and grants the required roles or model access.";
    private static final DateTimeFormatter TOKEN_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private ArchimeshAuthHints() {
    }

    public static String describePreflightAuthHint(boolean usingBearerToken) {
        return usingBearerToken
                ? BEARER_TOKEN_PREFLIGHT_HINT
                : "Bootstrap/proxy mode: ensure the server can resolve your user identity and roles before connecting.";
    }

    public static String describeTokenExpiry(String token) {
        String trimmed = token == null ? "" : token.trim();
        if(trimmed.isEmpty()) {
            return "No bearer token set.";
        }

        String[] parts = trimmed.split("\\.");
        if(parts.length < 2) {
            return "Bearer token format is invalid.";
        }

        try {
            // This is only local payload inspection for operator hints. It does not validate the signature.
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Long exp = readLongField(payload, "exp");
            if(exp == null) {
                return "Bearer token has no exp claim.";
            }
            Instant expiresAt = Instant.ofEpochSecond(exp);
            String formatted = TOKEN_TIME_FORMAT.format(expiresAt);
            if(expiresAt.isBefore(Instant.now())) {
                return "Bearer token expired at " + formatted + ".";
            }
            return "Bearer token expires at " + formatted + ".";
        } catch(IllegalArgumentException ex) {
            return "Bearer token payload is not valid base64url.";
        }
    }

    public static String describeTokenIdentity(String token) {
        String trimmed = token == null ? "" : token.trim();
        if(trimmed.isEmpty()) {
            return "No bearer token identity available.";
        }

        String[] parts = trimmed.split("\\.");
        if(parts.length < 2) {
            return "Bearer token identity cannot be inspected locally.";
        }

        try {
            // We intentionally read only common subject/role-like claims so the UI can hint at obvious mismatches
            // without taking a dependency on provider-specific JWT libraries in the client.
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String subject = firstNonBlank(
                    readStringField(payload, "preferred_username"),
                    readStringField(payload, "sub"),
                    readStringField(payload, "email"));
            Set<String> roles = new LinkedHashSet<>();
            roles.addAll(readStringArrayField(payload, "groups"));
            roles.addAll(readStringArrayField(payload, "roles"));
            roles.addAll(readStringArrayField(payload, "permissions"));
            roles.addAll(readStringArrayField(payload, "scope"));

            if(subject == null && roles.isEmpty()) {
                return "Bearer token subject and role claims were not found.";
            }
            if(subject == null) {
                return "Bearer token roles: " + String.join(", ", roles) + ".";
            }
            if(roles.isEmpty()) {
                return "Bearer token subject: " + subject + ".";
            }
            return "Bearer token subject: " + subject + ". Roles: " + String.join(", ", roles) + ".";
        } catch(IllegalArgumentException ex) {
            return "Bearer token identity cannot be inspected locally.";
        }
    }

    public static String describeHttpFailure(String action, int statusCode, boolean usingBearerToken) {
        String base = action + " failed: HTTP " + statusCode;
        if(statusCode == 401) {
            return usingBearerToken
                    ? base + " (bearer token missing, invalid, or expired)"
                    : base + " (authentication required)";
        }
        if(statusCode == 403) {
            return usingBearerToken
                    ? base + " (bearer token accepted but missing required roles/access)"
                    : base + " (request forbidden)";
        }
        return base;
    }

    public static String describeConnectionFailure(Throwable error, boolean usingBearerToken) {
        String message = rootMessage(error);
        if(message != null && !message.isBlank()) {
            if(usingBearerToken) {
                return "Connection failed. " + BEARER_TOKEN_PREFLIGHT_HINT + " Details: " + message;
            }
            return "Connection failed: " + message;
        }
        return usingBearerToken
                ? "Connection failed. " + BEARER_TOKEN_PREFLIGHT_HINT
                : "Connection failed.";
    }

    public static String describeServerError(String code, String message, boolean usingBearerToken, boolean readOnlyRef) {
        if(code == null || code.isBlank()) {
            return message == null || message.isBlank() ? "" : message;
        }
        if("AUTH_REQUIRED".equals(code)) {
            return usingBearerToken
                    ? "Authentication required. Check that the bearer token is present and still valid."
                    : "Authentication required by the server.";
        }
        if("ADMIN_ROLE_REQUIRED".equals(code)
                || "MODEL_READ_ROLE_REQUIRED".equals(code)
                || "MODEL_WRITE_ROLE_REQUIRED".equals(code)
                || "MODEL_ACCESS_DENIED".equals(code)
                || "MODEL_ADMIN_REQUIRED".equals(code)) {
            return usingBearerToken
                    ? "Authorization denied. The bearer token is valid but does not grant the required roles or model access."
                    : (message == null || message.isBlank() ? "Authorization denied by the server." : message);
        }
        if ("EXTERNAL_FIELD_OWNED".equals(code)) return "This field is centrally managed. Use Enterprise Repository: Propose/change central data. The rejected batch is reconciled from the server.";
        if("MODEL_REFERENCE_READ_ONLY".equals(code) || readOnlyRef) {
            return "This model reference is read-only. Switch back to HEAD to submit changes.";
        }
        return message == null || message.isBlank() ? code : message;
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while(cursor != null && cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor == null ? null : cursor.getMessage();
    }

    private static Long readLongField(String json, String fieldName) {
        String quotedField = "\"" + fieldName + "\"";
        int fieldIndex = json.indexOf(quotedField);
        if(fieldIndex < 0) {
            return null;
        }
        int colonIndex = json.indexOf(':', fieldIndex + quotedField.length());
        if(colonIndex < 0) {
            return null;
        }
        int index = colonIndex + 1;
        while(index < json.length() && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
        int start = index;
        while(index < json.length() && Character.isDigit(json.charAt(index))) {
            index++;
        }
        if(start == index) {
            return null;
        }
        try {
            return Long.parseLong(json.substring(start, index));
        } catch(NumberFormatException ex) {
            return null;
        }
    }

    private static String readStringField(String json, String fieldName) {
        String quotedField = "\"" + fieldName + "\"";
        int fieldIndex = json.indexOf(quotedField);
        if(fieldIndex < 0) {
            return null;
        }
        int colonIndex = json.indexOf(':', fieldIndex + quotedField.length());
        if(colonIndex < 0) {
            return null;
        }
        int firstQuote = json.indexOf('"', colonIndex + 1);
        if(firstQuote < 0) {
            return null;
        }
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if(secondQuote < 0) {
            return null;
        }
        String value = json.substring(firstQuote + 1, secondQuote).trim();
        return value.isEmpty() ? null : value;
    }

    private static List<String> readStringArrayField(String json, String fieldName) {
        String quotedField = "\"" + fieldName + "\"";
        int fieldIndex = json.indexOf(quotedField);
        if(fieldIndex < 0) {
            return List.of();
        }
        int colonIndex = json.indexOf(':', fieldIndex + quotedField.length());
        if(colonIndex < 0) {
            return List.of();
        }
        int arrayStart = json.indexOf('[', colonIndex + 1);
        if(arrayStart < 0) {
            // Some providers collapse role-like claims into a single space-delimited string (for example `scope`).
            String singleValue = readStringField(json, fieldName);
            if(singleValue == null) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for(String part : singleValue.split("\\s+")) {
                if(!part.isBlank()) {
                    values.add(part.trim());
                }
            }
            return values;
        }
        int arrayEnd = json.indexOf(']', arrayStart + 1);
        if(arrayEnd < 0) {
            return List.of();
        }
        String content = json.substring(arrayStart + 1, arrayEnd).trim();
        if(content.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for(String raw : content.split(",")) {
            String cleaned = raw.trim();
            if(cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            }
            cleaned = cleaned.trim();
            if(!cleaned.isBlank()) {
                values.add(cleaned);
            }
        }
        return values;
    }

    private static String firstNonBlank(String... values) {
        for(String value : values) {
            if(value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
