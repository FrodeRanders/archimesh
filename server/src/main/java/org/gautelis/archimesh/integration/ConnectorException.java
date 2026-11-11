package org.gautelis.archimesh.integration;

import java.time.Duration;

/** Safe to log: never carries credentials, request URLs, remote bodies or parser causes. */
public final class ConnectorException extends RuntimeException {
    public enum Kind { AUTHENTICATION, AUTHORIZATION, NOT_FOUND, RATE_LIMIT, VALIDATION, TRANSIENT, INVALID_RESPONSE, INCOMPATIBLE_API, CONFIGURATION }
    private final Kind kind;
    private final int status;
    private final Duration retryAfter;

    public ConnectorException(Kind kind, int status, Duration retryAfter) {
        super("External repository request failed: " + kind + (status > 0 ? " (HTTP " + status + ")" : ""));
        this.kind = kind; this.status = status; this.retryAfter = retryAfter;
    }
    public Kind kind() { return kind; }
    public int status() { return status; }
    public Duration retryAfter() { return retryAfter; }
    public boolean retryable() { return kind == Kind.RATE_LIMIT || kind == Kind.TRANSIENT; }
}
