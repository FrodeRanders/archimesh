package org.gautelis.archimesh.model;

public record AdminCompactionStatus(
        String modelId,
        long headRevision,
        long committedHorizonRevision,
        long watermarkRevision,
        long retainRevisions,
        long deletedCommitCount,
        long deletedOpCount,
        long deletedPropertyClockCount,
        long eligibleFieldClockCount,
        long retainedTombstoneCount,
        long eligibleTombstoneCount,
        boolean executed,
        String note,
        long checkpointRevision,
        long eligibleBatchCount,
        long prunedBatchCount,
        boolean dryRun,
        long prunedThroughRevision) {
    public AdminCompactionStatus(String modelId, long headRevision, long committedHorizonRevision,
            long watermarkRevision, long retainRevisions, long deletedCommitCount, long deletedOpCount,
            long deletedPropertyClockCount, long eligibleFieldClockCount, long retainedTombstoneCount,
            long eligibleTombstoneCount, boolean executed, String note) {
        this(modelId, headRevision, committedHorizonRevision, watermarkRevision, retainRevisions,
                deletedCommitCount, deletedOpCount, deletedPropertyClockCount, eligibleFieldClockCount,
                retainedTombstoneCount, eligibleTombstoneCount, executed, note, 0, 0, 0, false, 0);
    }
}
