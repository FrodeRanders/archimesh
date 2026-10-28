package org.gautelis.archimesh.service.impl;

import org.gautelis.archimesh.model.AdminCompactionStatus;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

import java.util.Map;
import java.util.List;
import org.neo4j.driver.TransactionContext;
import java.util.function.Function;
import java.util.function.Consumer;

final class Neo4jCompactionSupport {
    private final Neo4jCheckpointSupport checkpoints;
    private final int batchLimit;
    private final Consumer<TransactionContext> requireWriterLease;

    Neo4jCompactionSupport(Neo4jCheckpointSupport checkpoints, int batchLimit, Consumer<TransactionContext> requireWriterLease) {
        this.checkpoints = checkpoints;
        this.batchLimit = Math.max(1, batchLimit);
        this.requireWriterLease = requireWriterLease;
    }

    AdminCompactionStatus compactMetadata(Session session, String modelId, long retainRevisions, boolean dryRun) {
        Function<TransactionContext, AdminCompactionStatus> compact = tx -> {
            if (!dryRun) requireWriterLease.accept(tx);
            if (!dryRun) tx.run("MATCH (m:Model {modelId:$modelId}) SET m.commitFence=coalesce(m.commitFence,0)+1",
                    Map.of("modelId",modelId)).consume();
            Record revisions = tx.run("MATCH (m:Model {modelId:$modelId}) OPTIONAL MATCH (c:Commit {modelId:$modelId}) "
                    + "RETURN coalesce(m.headRevision,0) AS head, coalesce(max(c.revisionTo),0) AS committed, "
                    + "coalesce(m.prunedThroughRevision,0) AS pruned", Map.of("modelId",modelId)).single();
            long headRevision = revisions.get("head").asLong();
            long committedHorizonRevision = revisions.get("committed").asLong();
            boolean aligned = headRevision == committedHorizonRevision;
            long watermarkRevision = Math.max(0L, committedHorizonRevision - retainRevisions);
            long previouslyPruned = revisions.get("pruned").asLong();
            var checkpoint = checkpoints.latest(tx, modelId);
            long checkpointRevision = checkpoint.map(c -> c.revision()).orElse(0L);
            if (checkpointRevision > headRevision) throw new IllegalStateException("Checkpoint is ahead of model head");
            long cutoff = aligned ? Math.min(checkpointRevision, watermarkRevision) : 0L;
            // Never cross an unacknowledged (or unclassified legacy) publication.
            var pending = tx.run("MATCH (c:Commit {modelId:$modelId}) WHERE c.revisionFrom <= $cutoff "
                    + "AND coalesce(c.published,false)=false RETURN min(c.revisionFrom) AS firstPending",
                    Map.of("modelId",modelId,"cutoff",cutoff)).single().get("firstPending");
            if (!pending.isNull()) cutoff = Math.min(cutoff,pending.asLong()-1);
            Map<String,Object> pruneParams = Map.of("modelId",modelId,"cutoff",cutoff,"limit",batchLimit);
            long eligibleBatches = tx.run("MATCH (c:Commit {modelId:$modelId}) WHERE c.revisionTo <= $cutoff "
                    + "AND coalesce(c.payloadPruned,false)=false RETURN count(c) AS count",pruneParams).single().get("count").asLong();
            List<Record> batches = tx.run("MATCH (c:Commit {modelId:$modelId}) WHERE c.revisionTo <= $cutoff "
                    + "AND coalesce(c.payloadPruned,false)=false WITH c ORDER BY c.revisionFrom LIMIT $limit "
                    + "OPTIONAL MATCH (c)-[:HAS_OP]->(o:Op) RETURN c.opBatchId AS id, c.revisionTo AS last, count(o) AS ops "
                    + "ORDER BY c.revisionTo",pruneParams).list();
            long selectedOps = batches.stream().mapToLong(row -> row.get("ops").asLong()).sum();
            long prunedThrough = previouslyPruned;
            if (!dryRun && !batches.isEmpty()) {
                List<String> ids = batches.stream().map(row -> row.get("id").asString()).toList();
                tx.run("UNWIND $ids AS batch MATCH (c:Commit {modelId:$modelId,opBatchId:batch}) "
                        + "OPTIONAL MATCH (c)-[:HAS_OP]->(o:Op) DETACH DELETE o WITH DISTINCT c "
                        + "SET c.payloadPruned=true REMOVE c.batchJson",Map.of("modelId",modelId,"ids",ids)).consume();
                prunedThrough = Math.max(previouslyPruned,batches.getLast().get("last").asLong());
                tx.run("MATCH (m:Model {modelId:$modelId}) SET m.prunedThroughRevision=$revision",
                        Map.of("modelId",modelId,"revision",prunedThrough)).consume();
            }
            long deletedCommitCount = 0L; // Compact acceptance receipts are retained indefinitely.
            long deletedOpCount = dryRun ? 0L : selectedOps;
            long deletedPropertyClockCount = 0L; // Checkpoint pruning does not garbage-collect CRDT metadata.
            Map<String, Object> params = Map.of("modelId", modelId, "watermark", watermarkRevision);

            Record fieldClockCounts = tx.run("""
                    CALL {
                      WITH $modelId AS modelId, $watermark AS watermark
                      MATCH (m:Model {modelId: modelId})-[:HAS_ELEMENT]->(n:Element)
                      UNWIND [k IN keys(n) WHERE k ENDS WITH '_lamport'] AS key
                      WITH coalesce(toInteger(n[key]), -1) AS lamport, watermark
                      WHERE lamport >= 0 AND lamport <= watermark
                      RETURN count(*) AS cElement
                    }
                    CALL {
                      WITH $modelId AS modelId, $watermark AS watermark
                      MATCH (m:Model {modelId: modelId})-[:HAS_REL]->(n:Relationship)
                      UNWIND [k IN keys(n) WHERE k ENDS WITH '_lamport'] AS key
                      WITH coalesce(toInteger(n[key]), -1) AS lamport, watermark
                      WHERE lamport >= 0 AND lamport <= watermark
                      RETURN count(*) AS cRelationship
                    }
                    CALL {
                      WITH $modelId AS modelId, $watermark AS watermark
                      MATCH (m:Model {modelId: modelId})-[:HAS_VIEW]->(n:View)
                      UNWIND [k IN keys(n) WHERE k ENDS WITH '_lamport'] AS key
                      WITH coalesce(toInteger(n[key]), -1) AS lamport, watermark
                      WHERE lamport >= 0 AND lamport <= watermark
                      RETURN count(*) AS cView
                    }
                    CALL {
                      WITH $modelId AS modelId, $watermark AS watermark
                      MATCH (m:Model {modelId: modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(n:ViewObject)
                      UNWIND [k IN keys(n) WHERE k ENDS WITH '_lamport'] AS key
                      WITH coalesce(toInteger(n[key]), -1) AS lamport, watermark
                      WHERE lamport >= 0 AND lamport <= watermark
                      RETURN count(*) AS cViewObject
                    }
                    CALL {
                      WITH $modelId AS modelId, $watermark AS watermark
                      MATCH (m:Model {modelId: modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(n:Connection)
                      UNWIND [k IN keys(n) WHERE k ENDS WITH '_lamport'] AS key
                      WITH coalesce(toInteger(n[key]), -1) AS lamport, watermark
                      WHERE lamport >= 0 AND lamport <= watermark
                      RETURN count(*) AS cConnection
                    }
                    RETURN cElement + cRelationship + cView + cViewObject + cConnection AS eligibleFieldClocks
                    """, params).single();
            long eligibleFieldClockCount = fieldClockCounts.get("eligibleFieldClocks").asLong(0L);

            // Tombstones are reported, not deleted. They remain part of the safety boundary that stops stale
            // recreates from resurrecting previously deleted objects after compaction.
            Record tombstoneCounts = tx.run("""
                    CALL {
                      WITH $modelId AS modelId
                      MATCH (:ElementTombstone {modelId: modelId})
                      RETURN count(*) AS elementTombstones
                    }
                    CALL {
                      WITH $modelId AS modelId
                      MATCH (:RelationshipTombstone {modelId: modelId})
                      RETURN count(*) AS relationshipTombstones
                    }
                    CALL {
                      WITH $modelId AS modelId
                      MATCH (:ViewTombstone {modelId: modelId})
                      RETURN count(*) AS viewTombstones
                    }
                    CALL {
                      WITH $modelId AS modelId
                      MATCH (:ViewObjectTombstone {modelId: modelId})
                      RETURN count(*) AS viewObjectTombstones
                    }
                    CALL {
                      WITH $modelId AS modelId
                      MATCH (:ConnectionTombstone {modelId: modelId})
                      RETURN count(*) AS connectionTombstones
                    }
                    RETURN elementTombstones
                         + relationshipTombstones
                         + viewTombstones
                         + viewObjectTombstones
                         + connectionTombstones AS retainedTombstones
                    """, params).single();
            long retainedTombstoneCount = tombstoneCounts.get("retainedTombstones").asLong(0L);

            Record eligibleTombstoneCounts = tx.run("""
                    CALL {
                      WITH $modelId AS modelId, $watermark AS watermark
                      MATCH (t:ElementTombstone {modelId: modelId})
                      RETURN count(CASE WHEN coalesce(t.updatedRevision, 0) <= watermark THEN 1 END) AS elementTombstonesEligible
                    }
                    CALL {
                      WITH $modelId AS modelId, $watermark AS watermark
                      MATCH (t:RelationshipTombstone {modelId: modelId})
                      RETURN count(CASE WHEN coalesce(t.updatedRevision, 0) <= watermark THEN 1 END) AS relationshipTombstonesEligible
                    }
                    CALL {
                      WITH $modelId AS modelId, $watermark AS watermark
                      MATCH (t:ViewTombstone {modelId: modelId})
                      RETURN count(CASE WHEN coalesce(t.updatedRevision, 0) <= watermark THEN 1 END) AS viewTombstonesEligible
                    }
                    CALL {
                      WITH $modelId AS modelId, $watermark AS watermark
                      MATCH (t:ViewObjectTombstone {modelId: modelId})
                      RETURN count(CASE WHEN coalesce(t.updatedRevision, 0) <= watermark THEN 1 END) AS viewObjectTombstonesEligible
                    }
                    CALL {
                      WITH $modelId AS modelId, $watermark AS watermark
                      MATCH (t:ConnectionTombstone {modelId: modelId})
                      RETURN count(CASE WHEN coalesce(t.updatedRevision, 0) <= watermark THEN 1 END) AS connectionTombstonesEligible
                    }
                    RETURN elementTombstonesEligible
                         + relationshipTombstonesEligible
                         + viewTombstonesEligible
                         + viewObjectTombstonesEligible
                         + connectionTombstonesEligible AS eligibleTombstones
                    """, params).single();
            long eligibleTombstoneCount = eligibleTombstoneCounts.get("eligibleTombstones").asLong(0L);

            return new AdminCompactionStatus(
                    modelId,
                    headRevision,
                    committedHorizonRevision,
                    watermarkRevision,
                    retainRevisions,
                    deletedCommitCount,
                    deletedOpCount,
                    deletedPropertyClockCount,
                    eligibleFieldClockCount,
                    retainedTombstoneCount,
                    eligibleTombstoneCount,
                    true,
                    // Field-clock and tombstone eligibility is reported so operators can see reclaimable state even
                    // though the compactor intentionally keeps the safety metadata in place.
                    (!aligned ? "Head/commit mismatch: payload pruning disabled. " : "")
                            + (checkpoint.isEmpty() ? "No checkpoint: operation payloads retained. " : "Checkpoint covers revision " + checkpointRevision + ". ")
                            + (dryRun ? "Preview: would prune " + batches.size() + " batches / " + selectedOps + " operations. "
                            : "Pruned " + batches.size() + " batches / " + selectedOps + " operations. ")
                            + "Acceptance receipts, clocks and tombstones retained; " + Math.max(0,eligibleBatches-batches.size()) + " eligible batches remain after this bounded run.",
                    checkpointRevision, eligibleBatches, dryRun ? 0L : batches.size(), dryRun, prunedThrough);
        };
        return dryRun ? session.executeRead(compact::apply) : session.executeWrite(compact::apply);
    }
}
