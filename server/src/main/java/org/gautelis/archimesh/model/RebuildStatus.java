package org.gautelis.archimesh.model;

public record RebuildStatus(
        String modelId,
        long requestedToRevision,
        long rebuiltHeadRevision,
        int appliedBatchCount,
        int appliedOpCount,
        boolean consistent,
        long checkpointRevision) {
    public RebuildStatus(String modelId, long requestedToRevision, long rebuiltHeadRevision,
            int appliedBatchCount, int appliedOpCount, boolean consistent) {
        this(modelId, requestedToRevision, rebuiltHeadRevision, appliedBatchCount, appliedOpCount, consistent, 0);
    }
}

