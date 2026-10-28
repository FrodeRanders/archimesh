package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.gautelis.archimesh.service.NotationMetadata;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

final class Neo4jReadSupport {
    private static final Logger LOG = LoggerFactory.getLogger(Neo4jReadSupport.class);

    private final ObjectMapper objectMapper;

    Neo4jReadSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode loadSnapshot(Session session, String modelId, long headRevision) {
        if (session == null) return loadSnapshotState(null, modelId, headRevision);
        return session.executeRead(tx -> {
            long head = tx.run("MATCH (m:Model {modelId:$id}) RETURN coalesce(m.headRevision,0) AS head",
                    Map.of("id", modelId)).single().get("head").asLong();
            return loadSnapshotState(tx, modelId, head);
        });
    }

    private JsonNode loadSnapshotState(TransactionContext session, String modelId, long headRevision) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("format", "archimate-materialized-v1");
        snapshot.put("modelId", modelId);
        snapshot.put("headRevision", headRevision);
        snapshot.set("folders", objectMapper.createArrayNode());
        snapshot.set("elements", objectMapper.createArrayNode());
        snapshot.set("relationships", objectMapper.createArrayNode());
        snapshot.set("views", objectMapper.createArrayNode());
        snapshot.set("elementFolderMembers", objectMapper.createArrayNode());
        snapshot.set("relationshipFolderMembers", objectMapper.createArrayNode());
        snapshot.set("viewFolderMembers", objectMapper.createArrayNode());
        snapshot.set("viewObjects", objectMapper.createArrayNode());
        snapshot.set("viewObjectChildMembers", objectMapper.createArrayNode());
        snapshot.set("connections", objectMapper.createArrayNode());

        if (session == null) {
            return snapshot;
        }

        // Snapshot export is assembled from the materialized graph rather than replaying ops on demand so
        // checkout and admin export stay fast even when the op-log is large.
        ArrayNode folders = loadFolders(session, modelId);
        ArrayNode elements = loadElements(session, modelId);
        ArrayNode relationships = loadRelationships(session, modelId);
        ArrayNode views = loadViews(session, modelId);
        ArrayNode elementFolderMembers = loadFolderMembers(session, modelId, "CONTAINS_ELEMENT", "elementId");
        ArrayNode relationshipFolderMembers = loadFolderMembers(session, modelId, "CONTAINS_REL", "relationshipId");
        ArrayNode viewFolderMembers = loadFolderMembers(session, modelId, "CONTAINS_VIEW", "viewId");
        ArrayNode viewObjects = loadViewObjects(session, modelId);
        ArrayNode viewObjectChildMembers = loadViewObjectChildMembers(session, modelId);
        ArrayNode connections = loadConnections(session, modelId);
        snapshot.set("folders", folders);
        snapshot.set("elements", elements);
        snapshot.set("relationships", relationships);
        snapshot.set("views", views);
        snapshot.set("elementFolderMembers", elementFolderMembers);
        snapshot.set("relationshipFolderMembers", relationshipFolderMembers);
        snapshot.set("viewFolderMembers", viewFolderMembers);
        snapshot.set("viewObjects", viewObjects);
        snapshot.set("viewObjectChildMembers", viewObjectChildMembers);
        snapshot.set("connections", connections);

        appendMergeState(session, modelId, snapshot);
        return snapshot;
    }

    private void appendMergeState(TransactionContext tx, String modelId, ObjectNode snapshot) {
        ArrayNode fields = snapshot.putArray("fieldClocks");
        ArrayNode propertyState = snapshot.putArray("propertyState");
        ArrayNode tombstones = snapshot.putArray("tombstones");
        for (Record row : tx.run("MATCH (n {modelId:$modelId}) WHERE n:Element OR n:Relationship OR n:Folder "
                + "OR n:View OR n:ViewObject OR n:Connection RETURN n.id AS id, labels(n) AS labels, properties(n) AS state ORDER BY n.id",
                Map.of("modelId", modelId)).list()) {
            String id = row.get("id").asString();
            Map<String, Object> state = row.get("state").asMap();
            var labels = row.get("labels").asList(v -> v.asString());
            for (String key : state.keySet().stream().sorted().toList()) {
                if (!key.endsWith("_lamport")) continue;
                String prefix = key.substring(0, key.length() - "_lamport".length());
                String field = prefix;
                var notationFields = labels.contains("ViewObject") ? NotationMetadata.VIEW_OBJECT_PERSISTED_FIELDS
                        : labels.contains("Connection") ? NotationMetadata.CONNECTION_PERSISTED_FIELDS : java.util.List.<NotationMetadata.PersistedNotationField>of();
                for (var notation : notationFields) if (notation.propertyPrefix().equals(prefix)) field = notation.fieldName();
                ObjectNode clock = fields.addObject().put("targetId", id).put("field", field);
                clock.set("causal", causal(((Number) state.get(key)).longValue(), String.valueOf(state.getOrDefault(prefix + "_clientId", ""))));
            }
        }
        for (Record row : tx.run("MATCH (p:PropertyClock {modelId:$modelId}) "
                + "OPTIONAL MATCH (n {modelId:$modelId, id:p.targetId}) "
                + "RETURN p.targetId AS target, p.key AS key, p.lamport AS lamport, p.clientId AS client, "
                + "coalesce(p.deleted,false) AS deleted, n[p.key] AS value ORDER BY p.targetId, p.key",
                Map.of("modelId", modelId)).list()) {
            String key = row.get("key").asString();
            String target = row.get("target").asString();
            boolean deleted = row.get("deleted").asBoolean();
            ObjectNode op = propertyState.addObject();
            op.set("causal", causal(row.get("lamport").asLong(0), row.get("client").asString("")));
            if (key.startsWith("orset:vo-child:")) {
                op.put("type", deleted ? "RemoveViewObjectChildMember" : "AddViewObjectChildMember");
                op.put("parentViewObjectId", target).put("childViewObjectId", decodeMember(key.substring("orset:vo-child:".length())));
            } else if (key.startsWith("orset:")) {
                int separator = key.lastIndexOf(':');
                op.put("type", deleted ? "RemovePropertySetMember" : "AddPropertySetMember");
                op.put("targetId", target).put("key", key.substring(6, separator)).put("member", decodeMember(key.substring(separator + 1)));
            } else {
                op.put("type", deleted ? "UnsetProperty" : "SetProperty");
                op.put("targetId", target).put("key", key);
                op.set("value", objectMapper.valueToTree(row.get("value").asObject()));
            }
        }
        for (Record row : tx.run("MATCH (t {modelId:$modelId}) WHERE t:ElementTombstone OR t:RelationshipTombstone "
                + "OR t:ViewTombstone OR t:ViewObjectTombstone OR t:ConnectionTombstone "
                + "RETURN t.id AS id, t.lamport AS lamport, t.clientId AS client ORDER BY t.id",
                Map.of("modelId", modelId)).list()) {
            tombstones.addObject().put("targetId", row.get("id").asString())
                    .set("causal", causal(row.get("lamport").asLong(0), row.get("client").asString("")));
        }
    }

    private ObjectNode causal(long lamport, String clientId) {
        return objectMapper.createObjectNode().put("lamport", lamport).put("clientId", clientId);
    }

    private String decodeMember(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private ArrayNode loadFolders(TransactionContext session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_FOLDER]->(f:Folder)
                        OPTIONAL MATCH (parent:Folder {modelId: $modelId})-[:HAS_FOLDER]->(f)
                        RETURN f.id AS id,
                               f.folderType AS folderType,
                               f.name AS name,
                               f.documentation AS documentation,
                               parent.id AS parentFolderId
                        ORDER BY f.id
                        """, Map.of("modelId", modelId))
                .list();
        for (Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            String folderId = record.get("id").asString("");
            String folderType = record.get("folderType").asString(null);
            node.put("id", folderId);
            putNullableText(node, "folderType", record.get("folderType").asString(null));
            putNullableText(node, "name", canonicalFolderName(folderId, folderType, record.get("name").asString(null)));
            putNullableText(node, "documentation", record.get("documentation").asString(null));
            putNullableText(node, "parentFolderId", record.get("parentFolderId").asString(null));
            array.add(node);
        }
        return array;
    }

    private String canonicalFolderName(String folderId, String folderType, String storedName) {
        if (folderId == null || !folderId.startsWith("folder:root-")) {
            return storedName;
        }
        return switch (folderType == null ? "" : folderType) {
            case "STRATEGY" -> "Strategy";
            case "BUSINESS" -> "Business";
            case "APPLICATION" -> "Application";
            case "TECHNOLOGY" -> "Technology & Physical";
            case "RELATIONS" -> "Relations";
            case "OTHER" -> "Other";
            case "DIAGRAMS" -> "Views";
            case "MOTIVATION" -> "Motivation";
            case "IMPLEMENTATION_MIGRATION" -> "Implementation & Migration";
            default -> storedName;
        };
    }

    boolean isMaterializedStateConsistent(Session session, String modelId, long expectedHeadRevision) {
        boolean modelExists = session.executeRead(tx -> tx.run("""
                MATCH (m:Model {modelId: $modelId})
                RETURN count(m) > 0 AS exists
                """, Map.of("modelId", modelId)).single().get("exists").asBoolean(false));

        if (!modelExists) {
            long latestCommitRevision = session.executeRead(tx -> tx.run("""
                            MATCH (c:Commit {modelId: $modelId})
                            RETURN coalesce(max(c.revisionTo), 0) AS latestCommitRevision
                            """, Map.of("modelId", modelId))
                    .single()
                    .get("latestCommitRevision")
                    .asLong(0L));
            return expectedHeadRevision == 0L && latestCommitRevision == 0L;
        }

        return session.executeRead(tx -> {
            // Consistency is stronger than "head revision matches": all materialized references must still point
            // at live nodes inside the same model subgraph.
            var result = tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        OPTIONAL MATCH (c:Commit {modelId: $modelId})
                        WITH m, coalesce(max(c.revisionTo), 0) AS latestCommitRevision
                        
                        OPTIONAL MATCH (m)-[:HAS_REL]->(rel:Relationship)
                        WHERE rel.sourceId IS NOT NULL AND rel.sourceId <> ''
                          AND NOT EXISTS { MATCH (m)-[:HAS_ELEMENT]->(:Element {id: rel.sourceId}) }
                        WITH m, latestCommitRevision, count(rel) AS danglingRelSources
                        
                        OPTIONAL MATCH (m)-[:HAS_REL]->(rel2:Relationship)
                        WHERE rel2.targetId IS NOT NULL AND rel2.targetId <> ''
                          AND NOT EXISTS { MATCH (m)-[:HAS_ELEMENT]->(:Element {id: rel2.targetId}) }
                        WITH m, latestCommitRevision, danglingRelSources, count(rel2) AS danglingRelTargets
                        
                        OPTIONAL MATCH (m)-[:HAS_VIEW]->(v1:View)-[:CONTAINS]->(vo:ViewObject)
                        WHERE vo.representsId IS NOT NULL AND vo.representsId <> ''
                          AND NOT EXISTS { MATCH (m)-[:HAS_ELEMENT]->(:Element {id: vo.representsId}) }
                        WITH m, latestCommitRevision, danglingRelSources, danglingRelTargets, count(vo) AS danglingViewObjectRepresents
                        
                        OPTIONAL MATCH (m)-[:HAS_VIEW]->(v2:View)-[:CONTAINS]->(conn:Connection)
                        WHERE conn.representsId IS NOT NULL AND conn.representsId <> ''
                          AND NOT EXISTS { MATCH (m)-[:HAS_REL]->(:Relationship {id: conn.representsId}) }
                        WITH m, latestCommitRevision, danglingRelSources, danglingRelTargets, danglingViewObjectRepresents, count(conn) AS danglingConnectionRepresents
                        
                        OPTIONAL MATCH (m)-[:HAS_VIEW]->(v3:View)-[:CONTAINS]->(conn2:Connection)
                        WHERE conn2.sourceViewObjectId IS NOT NULL AND conn2.sourceViewObjectId <> ''
                          AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:ViewObject {id: conn2.sourceViewObjectId}) }
                        WITH m, latestCommitRevision, danglingRelSources, danglingRelTargets, danglingViewObjectRepresents, danglingConnectionRepresents, count(conn2) AS danglingConnectionSources
                        
                        OPTIONAL MATCH (m)-[:HAS_VIEW]->(v4:View)-[:CONTAINS]->(conn3:Connection)
                        WHERE conn3.targetViewObjectId IS NOT NULL AND conn3.targetViewObjectId <> ''
                          AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:ViewObject {id: conn3.targetViewObjectId}) }
                        RETURN coalesce(m.headRevision, 0) AS headRevision,
                               latestCommitRevision,
                               danglingRelSources,
                               danglingRelTargets,
                               danglingViewObjectRepresents,
                               danglingConnectionRepresents,
                               danglingConnectionSources,
                               count(conn3) AS danglingConnectionTargets
                    """, Map.of("modelId", modelId));
            if (!result.hasNext()) {
                long latestCommitRevision = tx.run("""
                                MATCH (c:Commit {modelId: $modelId})
                                RETURN coalesce(max(c.revisionTo), 0) AS latestCommitRevision
                                """, Map.of("modelId", modelId))
                        .single()
                        .get("latestCommitRevision")
                        .asLong(0L);
                return expectedHeadRevision == 0L && latestCommitRevision == 0L;
            }
            Record record = result.next();

            long actualHeadRevision = record.get("headRevision").asLong(0L);
            long latestCommitRevision = record.get("latestCommitRevision").asLong(0L);
            long danglingRelSources = record.get("danglingRelSources").asLong(0L);
            long danglingRelTargets = record.get("danglingRelTargets").asLong(0L);
            long danglingViewObjectRepresents = record.get("danglingViewObjectRepresents").asLong(0L);
            long danglingConnectionRepresents = record.get("danglingConnectionRepresents").asLong(0L);
            long danglingConnectionSources = record.get("danglingConnectionSources").asLong(0L);
            long danglingConnectionTargets = record.get("danglingConnectionTargets").asLong(0L);

            return actualHeadRevision == expectedHeadRevision
                    && actualHeadRevision == latestCommitRevision
                    && danglingRelSources == 0
                    && danglingRelTargets == 0
                    && danglingViewObjectRepresents == 0
                    && danglingConnectionRepresents == 0
                    && danglingConnectionSources == 0
                    && danglingConnectionTargets == 0;
        });
    }

    JsonNode loadOpBatches(Session session, String modelId, long fromRevisionInclusive, long toRevisionInclusive) {
        ArrayNode opBatches = objectMapper.createArrayNode();
        if (session == null || fromRevisionInclusive > toRevisionInclusive) {
            return opBatches;
        }
        LOG.debug("loadOpBatches: modelId={} range={}..{}",
                modelId, fromRevisionInclusive, toRevisionInclusive);
        List<Record> records = session.executeRead(tx -> tx.run("""
                            MATCH (c:Commit {modelId: $modelId})
                            WHERE c.revisionFrom >= $fromRevision AND c.revisionTo <= $toRevision
                              AND coalesce(c.payloadPruned, false) = false
                            OPTIONAL MATCH (c)-[:HAS_OP]->(o:Op)
                            WITH c, o
                            ORDER BY c.revisionFrom ASC, o.seq ASC
                            RETURN c.opBatchId AS opBatchId,
                                   c.revisionFrom AS revisionFrom,
                                   c.revisionTo AS revisionTo,
                                   c.ts AS ts,
                                   collect(o.payloadJson) AS opPayloads
                            ORDER BY revisionFrom ASC
                            """, Map.of(
                            "modelId", modelId,
                            "fromRevision", fromRevisionInclusive,
                            "toRevision", toRevisionInclusive
                    ))
                .list());

        for (Record record : records) {
            ObjectNode opBatch = objectMapper.createObjectNode();
            long revisionFrom = record.get("revisionFrom").asLong();
            long revisionTo = record.get("revisionTo").asLong();
            opBatch.put("modelId", modelId);
            opBatch.put("opBatchId", record.get("opBatchId").asString(""));
            opBatch.put("baseRevision", Math.max(0, revisionFrom - 1));

            ObjectNode assignedRange = objectMapper.createObjectNode();
            assignedRange.put("from", revisionFrom);
            assignedRange.put("to", revisionTo);
            opBatch.set("assignedRevisionRange", assignedRange);
            opBatch.put("timestamp", record.get("ts").asString(""));

            ArrayNode ops = objectMapper.createArrayNode();
            for (var payloadValue : record.get("opPayloads").values()) {
                if (payloadValue == null || payloadValue.isNull()) {
                    continue;
                }
                String payload = payloadValue.asString(null);
                if (payload == null || payload.isBlank()) {
                    continue;
                }
                try {
                    ops.add(objectMapper.readTree(payload));
                } catch (Exception e) {
                    // Delta checkout should degrade to a shorter/missing batch rather than fail the whole response
                    // because one stored payload is malformed.
                    LOG.warn("Skipping malformed op payload while loading checkout delta: modelId={} opBatchId={}",
                            modelId, record.get("opBatchId").asString(""), e);
                }
            }
            opBatch.set("ops", ops);
            opBatches.add(opBatch);
        }

        return opBatches;
    }
    private ArrayNode loadElements(TransactionContext session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_ELEMENT]->(e:Element)
                        RETURN e.id AS id,
                               e.archimateType AS archimateType,
                               e.name AS name,
                               e.documentation AS documentation
                        ORDER BY id
                        """, Map.of("modelId", modelId))
                .list();
        for (Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", record.get("id").asString(""));
            node.put("archimateType", record.get("archimateType").asString(""));
            putNullableText(node, "name", record.get("name").asString(null));
            putNullableText(node, "documentation", record.get("documentation").asString(null));
            array.add(node);
        }
        return array;
    }

    private ArrayNode loadRelationships(TransactionContext session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_REL]->(r:Relationship)
                        OPTIONAL MATCH (r)-[:SOURCE]->(src:Element)
                        OPTIONAL MATCH (r)-[:TARGET]->(dst:Element)
                        RETURN r.id AS id,
                               r.archimateType AS archimateType,
                               r.name AS name,
                               r.documentation AS documentation,
                               src.id AS sourceId,
                               dst.id AS targetId
                        ORDER BY id
                        """, Map.of("modelId", modelId))
                .list();
        for (Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", record.get("id").asString(""));
            node.put("archimateType", record.get("archimateType").asString(""));
            putNullableText(node, "name", record.get("name").asString(null));
            putNullableText(node, "documentation", record.get("documentation").asString(null));
            putNullableText(node, "sourceId", record.get("sourceId").asString(null));
            putNullableText(node, "targetId", record.get("targetId").asString(null));
            array.add(node);
        }
        return array;
    }

    private ArrayNode loadViews(TransactionContext session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(v:View)
                        RETURN v.id AS id,
                               v.name AS name,
                               v.documentation AS documentation,
                               v.notationJson AS notationJson
                        ORDER BY id
                        """, Map.of("modelId", modelId))
                .list();
        for (Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", record.get("id").asString(""));
            putNullableText(node, "name", record.get("name").asString(null));
            putNullableText(node, "documentation", record.get("documentation").asString(null));
            node.set("notationJson", parseJsonOrNull(record.get("notationJson").asString(null)));
            array.add(node);
        }
        return array;
    }

    private ArrayNode loadViewObjects(TransactionContext session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(v:View)-[:CONTAINS]->(vo:ViewObject)
                        OPTIONAL MATCH (vo)-[:REPRESENTS]->(e:Element)
                        RETURN vo.id AS id,
                               v.id AS viewId,
                               e.id AS representsId,
                               vo.notationJson AS notationJson
                        ORDER BY id
                        """, Map.of("modelId", modelId))
                .list();
        for (Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", record.get("id").asString(""));
            putNullableText(node, "viewId", record.get("viewId").asString(null));
            putNullableText(node, "representsId", record.get("representsId").asString(null));
            node.set("notationJson", parseJsonOrNull(record.get("notationJson").asString(null)));
            array.add(node);
        }
        return array;
    }

    private ArrayNode loadViewObjectChildMembers(TransactionContext session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(parent:ViewObject)-[:CHILD_MEMBER]->(child:ViewObject)
                        RETURN parent.id AS parentViewObjectId,
                               child.id AS childViewObjectId
                        ORDER BY parentViewObjectId, childViewObjectId
                        """, Map.of("modelId", modelId))
                .list();
        for (Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            putNullableText(node, "parentViewObjectId", record.get("parentViewObjectId").asString(null));
            putNullableText(node, "childViewObjectId", record.get("childViewObjectId").asString(null));
            array.add(node);
        }
        return array;
    }

    private ArrayNode loadConnections(TransactionContext session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(v:View)-[:CONTAINS]->(c:Connection)
                        OPTIONAL MATCH (c)-[:REPRESENTS]->(r:Relationship)
                        OPTIONAL MATCH (c)-[:FROM]->(f:ViewObject)
                        OPTIONAL MATCH (c)-[:TO]->(t:ViewObject)
                        RETURN c.id AS id,
                               v.id AS viewId,
                               r.id AS representsId,
                               f.id AS sourceViewObjectId,
                               t.id AS targetViewObjectId,
                               c.notationJson AS notationJson
                        ORDER BY id
                        """, Map.of("modelId", modelId))
                .list();
        for (Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", record.get("id").asString(""));
            putNullableText(node, "viewId", record.get("viewId").asString(null));
            putNullableText(node, "representsId", record.get("representsId").asString(null));
            putNullableText(node, "sourceViewObjectId", record.get("sourceViewObjectId").asString(null));
            putNullableText(node, "targetViewObjectId", record.get("targetViewObjectId").asString(null));
            node.set("notationJson", parseJsonOrNull(record.get("notationJson").asString(null)));
            array.add(node);
        }
        return array;
    }

    private ArrayNode loadFolderMembers(TransactionContext session, String modelId, String relType, String fieldName) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_FOLDER]->(f:Folder)-[r]->(target)
                        WHERE type(r) = $relType
                        RETURN f.id AS folderId,
                               target.id AS targetId
                        ORDER BY folderId, targetId
                        """, Map.of("modelId", modelId, "relType", relType))
                .list();
        for (Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            putNullableText(node, "folderId", record.get("folderId").asString(null));
            putNullableText(node, fieldName, record.get("targetId").asString(null));
            array.add(node);
        }
        return array;
    }

    private JsonNode parseJsonOrNull(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return JsonNodeFactory.instance.nullNode();
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            LOG.warn("Failed parsing stored notation json", e);
            return JsonNodeFactory.instance.nullNode();
        }
    }

    private void putNullableText(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
            return;
        }
        node.put(field, value);
    }

}
