package org.gautelis.archimesh.model;

/** One model's scheduled checkpoint, payload pruning, and checkpoint retention result. */
public record CheckpointMaintenanceResult(
        String modelId,
        boolean checkpointCreated,
        long checkpointRevision,
        long prunedBatchCount,
        long deletedOpCount,
        long deletedCheckpointCount) { }
