package org.gautelis.federation.http;

/** Safe across an API/log boundary: no URL, credential, response body or parser cause. */
public final class RepositoryFailure extends RuntimeException {
    public enum Kind { AUTHENTICATION, AUTHORIZATION, NOT_FOUND, RATE_LIMIT, TRANSIENT, INVALID_RESPONSE, INCOMPATIBLE, CONFIGURATION }
    private final Kind kind;
    public RepositoryFailure(Kind kind) { super("Repository operation failed: " + kind); this.kind=kind; }
    public Kind kind() { return kind; }
    public boolean retryable() { return kind==Kind.RATE_LIMIT || kind==Kind.TRANSIENT; }
}
