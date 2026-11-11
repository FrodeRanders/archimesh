package org.gautelis.archimesh.model;

/** Private acceptance outcome for internal callers, independent of WebSocket acknowledgement delivery. */
public record SubmissionResult(boolean accepted, RevisionRange range, String code) {
    public static SubmissionResult rejected(String code) { return new SubmissionResult(false,null,code); }
}
