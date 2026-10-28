package org.gautelis.archimesh.model;

public record CheckpointSummary(String modelId, long revision, String createdAt, String sha256,
                                int nodeCount, int relationshipCount) { }
