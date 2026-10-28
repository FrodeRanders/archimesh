package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.gautelis.archimesh.model.RevisionRange;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

final class Neo4jOpLogSupport {
    private static final Logger LOG = LoggerFactory.getLogger(Neo4jOpLogSupport.class);

    void appendOpLog(Session session, String modelId, String opBatchId, RevisionRange range, JsonNode opBatch) {
        if (session == null) {
            return;
        }
        LOG.debug("appendOpLog: modelId={} opBatchId={} range={}..{}",
                modelId, opBatchId, range.from(), range.to());
        session.executeWrite(tx -> {
            appendOpLog(tx, modelId, opBatchId, range, opBatch);
            return null;
        });
    }

    void appendOpLog(TransactionContext tx, String modelId, String opBatchId, RevisionRange range, JsonNode opBatch) {
        if (tx.run("MATCH (c:Commit {modelId:$modelId, opBatchId:$opBatchId}) RETURN c",
                Map.of("modelId", modelId, "opBatchId", opBatchId)).hasNext()) {
            return;
        }
        tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        MERGE (c:Commit {
                          modelId: $modelId,
                          opBatchId: $opBatchId
                        })
                        SET c.revisionFrom = $from,
                            c.revisionTo = $to,
                            c.ts = $ts
                        MERGE (m)-[:HAS_COMMIT]->(c)
                        WITH c
                        OPTIONAL MATCH (prev:Commit {modelId: $modelId, revisionTo: $prevTo})
                        FOREACH (_ IN CASE WHEN prev IS NULL THEN [] ELSE [1] END | MERGE (prev)-[:NEXT]->(c))
                        """,
                Map.of(
                        "modelId", modelId,
                        "from", range.from(),
                        "to", range.to(),
                        "prevTo", range.from() - 1,
                        "opBatchId", opBatchId,
                        "ts", opBatch.path("timestamp").asText()
                ));

        int seq = 0;
        for (JsonNode op : opBatch.path("ops")) {
            tx.run("""
                    MATCH (c:Commit {modelId: $modelId, opBatchId: $opBatchId})
                    CREATE (o:Op {
                      seq: $seq,
                      type: $type,
                      targetId: $targetId,
                      payloadJson: $payloadJson
                    })
                    CREATE (c)-[:HAS_OP]->(o)
                    """, Map.of(
                    "modelId", modelId,
                    "opBatchId", opBatchId,
                    "seq", seq++,
                    "type", op.path("type").asText(),
                    "targetId", deriveTargetId(op),
                    "payloadJson", op.toString()
            ));
        }
    }

    private String deriveTargetId(JsonNode op) {
        if (op.has("elementId")) {
            return op.path("elementId").asText();
        }
        if (op.has("relationshipId")) {
            return op.path("relationshipId").asText();
        }
        if (op.has("viewId")) {
            return op.path("viewId").asText();
        }
        if (op.has("viewObjectId")) {
            return op.path("viewObjectId").asText();
        }
        if (op.has("connectionId")) {
            return op.path("connectionId").asText();
        }
        if (op.has("targetId")) {
            return op.path("targetId").asText();
        }
        if (op.has("parentViewObjectId")) {
            return op.path("parentViewObjectId").asText();
        }
        return "";
    }
}
