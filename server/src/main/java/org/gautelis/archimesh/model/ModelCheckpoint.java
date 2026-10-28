package org.gautelis.archimesh.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Immutable persisted recovery boundary; state contains the complete projection graph. */
public record ModelCheckpoint(
        String format,
        String modelId,
        long revision,
        String createdAt,
        String sha256,
        JsonNode state) {
    public CheckpointSummary summary() {
        return new CheckpointSummary(modelId, revision, createdAt, sha256,
                state.path("nodes").size(), state.path("edges").size());
    }
}
