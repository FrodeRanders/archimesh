package org.gautelis.archimesh.service.impl;

import org.gautelis.archimesh.model.AdminCompactionStatus;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

final class Neo4jCompactionSupport {
    private static final Logger LOG = LoggerFactory.getLogger(Neo4jCompactionSupport.class);

    AdminCompactionStatus compactMetadata(
            Session session,
            String modelId,
            long headRevision,
            long committedHorizonRevision,
            long watermarkRevision,
            long retainRevisions) {
        if (session == null) {
            return new AdminCompactionStatus(
                    modelId, headRevision, committedHorizonRevision, watermarkRevision, retainRevisions,
                    0L, 0L, 0L, 0L, 0L, 0L, false, "Neo4j session unavailable");
        }

        LOG.info("Compaction start: modelId={} headRevision={} committedHorizonRevision={} watermarkRevision={} retainRevisions={}",
                modelId, headRevision, committedHorizonRevision, watermarkRevision, retainRevisions);

        return session.executeWrite(tx -> {
            Map<String, Object> params = Map.of("modelId", modelId, "watermark", watermarkRevision);

            // History is the only rebuild/import source. Until durable checkpoints include
            // every merge clock and tombstone, pruning it would destroy recoverability.
            long deletedCommitCount = 0L;
            long deletedOpCount = 0L;

            // Property clocks can only be removed once the target object is gone; otherwise they are still part of
            // the LWW merge contract even if they are older than the watermark.
            Record propertyClockCounts = tx.run("""
                    MATCH (m:Model {modelId: $modelId})-[:HAS_PROPERTY_CLOCK]->(p:PropertyClock)
                    WHERE coalesce(p.updatedRevision, 0) <= $watermark
                      AND NOT EXISTS { MATCH (m)-[:HAS_ELEMENT]->(:Element {id: p.targetId}) }
                      AND NOT EXISTS { MATCH (m)-[:HAS_REL]->(:Relationship {id: p.targetId}) }
                      AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View {id: p.targetId}) }
                      AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:ViewObject {id: p.targetId}) }
                      AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:Connection {id: p.targetId}) }
                    RETURN count(p) AS propertyClockCount
                    """, params).single();
            long deletedPropertyClockCount = propertyClockCounts.get("propertyClockCount").asLong(0L);

            tx.run("""
                    MATCH (m:Model {modelId: $modelId})-[:HAS_PROPERTY_CLOCK]->(p:PropertyClock)
                    WHERE coalesce(p.updatedRevision, 0) <= $watermark
                      AND NOT EXISTS { MATCH (m)-[:HAS_ELEMENT]->(:Element {id: p.targetId}) }
                      AND NOT EXISTS { MATCH (m)-[:HAS_REL]->(:Relationship {id: p.targetId}) }
                      AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View {id: p.targetId}) }
                      AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:ViewObject {id: p.targetId}) }
                      AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:Connection {id: p.targetId}) }
                    DETACH DELETE p
                    """, params);

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
                    "Compaction completed; op-log, tombstones and field clocks retained for recovery");
        });
    }
}
