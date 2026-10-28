package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gautelis.archimesh.model.ModelCheckpoint;
import org.gautelis.archimesh.service.CheckpointCodec;
import org.neo4j.driver.Record;
import org.neo4j.driver.TransactionContext;

import java.util.*;

/** Lossless projection capture/restore, including raw clock properties and orphan safety metadata. */
final class Neo4jCheckpointSupport {
    static final Set<String> LABELS = Set.of("Element", "Relationship", "Folder", "View", "ViewObject", "Connection",
            "PropertyClock", "ElementTombstone", "RelationshipTombstone", "ViewTombstone", "ViewObjectTombstone", "ConnectionTombstone");
    private static final Set<String> EDGE_TYPES = Set.of("HAS_ELEMENT", "HAS_REL", "HAS_FOLDER", "HAS_VIEW",
            "HAS_PROPERTY_CLOCK", "HAS_ELEMENT_TOMBSTONE", "HAS_RELATIONSHIP_TOMBSTONE", "HAS_VIEW_TOMBSTONE",
            "HAS_VIEWOBJECT_TOMBSTONE", "HAS_CONNECTION_TOMBSTONE", "SOURCE", "TARGET", "CONTAINS", "REPRESENTS",
            "FROM", "TO", "CHILD_MEMBER", "CONTAINS_ELEMENT", "CONTAINS_REL", "CONTAINS_VIEW");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final ObjectMapper json;

    Neo4jCheckpointSupport(ObjectMapper json) { this.json = json; }

    ModelCheckpoint capture(TransactionContext tx, String modelId, long revision) {
        Map<String, Object> params = Map.of("modelId", modelId, "labels", new ArrayList<>(LABELS));
        if (tx.run("MATCH (n {modelId:$modelId}) WHERE NOT (n:Model OR n:Commit OR n:ModelTag OR n:ModelCheckpoint) "
                + "AND NOT any(label IN labels(n) WHERE label IN $labels) RETURN n LIMIT 1", params).hasNext()) {
            throw new IllegalStateException("Model contains state unsupported by this checkpoint format");
        }
        Map<String, String> nodeKeys = new HashMap<>();
        var root = tx.run("MATCH (m:Model {modelId:$modelId}) RETURN elementId(m) AS id", params).single();
        nodeKeys.put(root.get("id").asString(), "model");
        List<ObjectNode> nodes = new ArrayList<>();
        for (Record row : tx.run("MATCH (n {modelId:$modelId}) WHERE any(label IN labels(n) WHERE label IN $labels) "
                + "RETURN elementId(n) AS id, labels(n) AS labels, properties(n) AS properties", params).list()) {
            List<String> labels = row.get("labels").asList(v -> v.asString());
            if (labels.size() != 1 || !LABELS.contains(labels.getFirst())) {
                throw new IllegalStateException("Unsupported projection labels: " + labels);
            }
            String label = labels.getFirst();
            JsonNode properties = propertyTree(row.get("properties").asMap());
            String key = identity(label, properties);
            nodeKeys.put(row.get("id").asString(), key);
            ObjectNode node = json.createObjectNode().put("key", key).put("label", label);
            node.set("properties", properties);
            nodes.add(node);
        }
        List<ObjectNode> edges = new ArrayList<>();
        for (Record row : tx.run("MATCH (n {modelId:$modelId})-[r]-() "
                + "WHERE any(label IN labels(n) WHERE label IN $labels) "
                + "RETURN DISTINCT elementId(startNode(r)) AS source, elementId(endNode(r)) AS target, "
                + "type(r) AS type, properties(r) AS properties", params).list()) {
            String source = nodeKeys.get(row.get("source").asString());
            String target = nodeKeys.get(row.get("target").asString());
            if (source == null || target == null) throw new IllegalStateException("Projection has a cross-model or unsupported reference");
            ObjectNode edge = json.createObjectNode().put("from", source).put("to", target).put("type", row.get("type").asString());
            edge.set("properties", propertyTree(row.get("properties").asMap()));
            edges.add(edge);
        }
        nodes.sort(Comparator.comparing(n -> n.path("key").asText()));
        edges.sort(Comparator.comparing(n -> CheckpointCodec.canonicalize(n).toString()));
        ObjectNode state = json.createObjectNode();
        state.set("nodes", json.valueToTree(nodes));
        state.set("edges", json.valueToTree(edges));
        ModelCheckpoint checkpoint = CheckpointCodec.create(modelId, revision, state);
        validate(checkpoint);
        return checkpoint;
    }

    private JsonNode propertyTree(Map<String,Object> properties) {
        for (Object value : properties.values()) {
            if (value instanceof String || value instanceof Boolean || value instanceof Number) continue;
            if (value instanceof List<?> values && values.stream().allMatch(v -> v instanceof String || v instanceof Boolean || v instanceof Number)) continue;
            throw new IllegalStateException("Checkpoint v1 cannot encode this native property type losslessly");
        }
        return json.valueToTree(properties);
    }

    static void validate(ModelCheckpoint checkpoint) {
        CheckpointCodec.verify(checkpoint);
        Map<String, String> labels = new HashMap<>();
        labels.put("model", "Model");
        Set<String> identities = new HashSet<>();
        for (JsonNode node : checkpoint.state().path("nodes")) {
            String key = text(node, "key");
            String label = text(node, "label");
            JsonNode properties = node.path("properties");
            if (!LABELS.contains(label) || !properties.isObject() || "model".equals(key)
                    || !checkpoint.modelId().equals(properties.path("modelId").asText())
                    || !key.equals(identity(label, properties)) || !identities.add(key)) {
                throw new IllegalArgumentException("Invalid or duplicate checkpoint node: " + key);
            }
            validateProperties(properties);
            labels.put(key, label);
        }
        for (JsonNode edge : checkpoint.state().path("edges")) {
            String from = text(edge, "from"), to = text(edge, "to"), type = text(edge, "type");
            if (!EDGE_TYPES.contains(type) || !labels.containsKey(from) || !labels.containsKey(to)
                    || !validEdge(type, labels.get(from), labels.get(to)) || !edge.path("properties").isObject()) {
                throw new IllegalArgumentException("Invalid checkpoint relationship: " + type);
            }
            validateProperties(edge.path("properties"));
        }
    }

    private static boolean validEdge(String type, String from, String to) {
        return switch (type) {
            case "HAS_ELEMENT" -> from.equals("Model") && to.equals("Element");
            case "HAS_REL" -> from.equals("Model") && to.equals("Relationship");
            case "HAS_FOLDER" -> (from.equals("Model") || from.equals("Folder")) && to.equals("Folder");
            case "HAS_VIEW" -> from.equals("Model") && to.equals("View");
            case "HAS_PROPERTY_CLOCK" -> from.equals("Model") && to.equals("PropertyClock");
            case "HAS_ELEMENT_TOMBSTONE" -> from.equals("Model") && to.equals("ElementTombstone");
            case "HAS_RELATIONSHIP_TOMBSTONE" -> from.equals("Model") && to.equals("RelationshipTombstone");
            case "HAS_VIEW_TOMBSTONE" -> from.equals("Model") && to.equals("ViewTombstone");
            case "HAS_VIEWOBJECT_TOMBSTONE" -> from.equals("Model") && to.equals("ViewObjectTombstone");
            case "HAS_CONNECTION_TOMBSTONE" -> from.equals("Model") && to.equals("ConnectionTombstone");
            case "SOURCE", "TARGET" -> from.equals("Relationship") && to.equals("Element");
            case "CONTAINS" -> from.equals("View") && (to.equals("ViewObject") || to.equals("Connection"));
            case "REPRESENTS" -> (from.equals("ViewObject") && to.equals("Element")) || (from.equals("Connection") && to.equals("Relationship"));
            case "FROM", "TO" -> from.equals("Connection") && to.equals("ViewObject");
            case "CHILD_MEMBER" -> from.equals("ViewObject") && to.equals("ViewObject");
            case "CONTAINS_ELEMENT" -> from.equals("Folder") && to.equals("Element");
            case "CONTAINS_REL" -> from.equals("Folder") && to.equals("Relationship");
            case "CONTAINS_VIEW" -> from.equals("Folder") && to.equals("View");
            default -> false;
        };
    }

    private static void validateProperties(JsonNode properties) {
        properties.elements().forEachRemaining(value -> {
            if (value.isArray()) {
                for (JsonNode item : value) validateScalar(item);
            } else validateScalar(value);
        });
    }

    private static void validateScalar(JsonNode value) {
        if (value.isTextual() || value.isBoolean()) return;
        if (value.isIntegralNumber() && value.canConvertToLong()) return;
        if (value.isFloatingPointNumber() && Double.isFinite(value.doubleValue())) return;
        throw new IllegalArgumentException("Checkpoint properties must be finite scalar values or scalar arrays");
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Missing checkpoint field: " + key);
        return value.asText();
    }

    private static String identity(String label, JsonNode properties) {
        var key = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(label);
        if ("PropertyClock".equals(label)) {
            return key.add(text(properties, "targetId")).add(text(properties, "key")).toString();
        }
        return key.add(text(properties, "id")).toString();
    }

    Optional<ModelCheckpoint> latest(TransactionContext tx, String modelId) {
        var rows = tx.run("MATCH (c:ModelCheckpoint {modelId:$modelId}) RETURN c.payloadJson AS payload, c.revision AS revision, c.sha256 AS checksum "
                + "ORDER BY c.revision DESC LIMIT 1", Map.of("modelId", modelId));
        if (!rows.hasNext()) return Optional.empty();
        try {
            Record row = rows.single();
            ModelCheckpoint checkpoint = json.readValue(row.get("payload").asString(), ModelCheckpoint.class);
            checkpoint = new ModelCheckpoint(checkpoint.format(), checkpoint.modelId(), checkpoint.revision(),
                    checkpoint.createdAt(), checkpoint.sha256(), CheckpointCodec.canonicalize(checkpoint.state()));
            validate(checkpoint);
            if (!modelId.equals(checkpoint.modelId()) || checkpoint.revision() != row.get("revision").asLong()
                    || !checkpoint.sha256().equals(row.get("checksum").asString())) {
                throw new IllegalStateException("Checkpoint metadata mismatch");
            }
            return Optional.of(checkpoint);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Cannot read model checkpoint", e); }
    }

    void persist(TransactionContext tx, ModelCheckpoint checkpoint) {
        validate(checkpoint);
        Map<String, Object> params = Map.of("modelId", checkpoint.modelId(), "revision", checkpoint.revision(),
                "checksum", checkpoint.sha256(), "payload", json.valueToTree(checkpoint).toString());
        var existing = tx.run("MATCH (c:ModelCheckpoint {modelId:$modelId, revision:$revision}) RETURN c.sha256 AS checksum", params);
        if (existing.hasNext()) {
            if (!checkpoint.sha256().equals(existing.single().get("checksum").asString())) {
                throw new IllegalStateException("An immutable checkpoint already exists at this revision");
            }
            return;
        }
        tx.run("MATCH (m:Model {modelId:$modelId}) "
                + "CREATE (c:ModelCheckpoint {modelId:$modelId, revision:$revision, sha256:$checksum, payloadJson:$payload}) "
                + "CREATE (m)-[:HAS_CHECKPOINT]->(c)", params).consume();
    }

    void restore(TransactionContext tx, ModelCheckpoint checkpoint) {
        validate(checkpoint);
        Map<String, String> ids = new HashMap<>();
        ids.put("model", tx.run("MATCH (m:Model {modelId:$modelId}) RETURN elementId(m) AS id",
                Map.of("modelId", checkpoint.modelId())).single().get("id").asString());
        for (JsonNode node : checkpoint.state().path("nodes")) {
            String id = tx.run("CREATE (n:" + node.path("label").asText() + ") SET n=$properties RETURN elementId(n) AS id",
                    Map.of("properties", json.convertValue(node.path("properties"), MAP))).single().get("id").asString();
            ids.put(node.path("key").asText(), id);
        }
        for (JsonNode edge : checkpoint.state().path("edges")) {
            tx.run("MATCH (a) WHERE elementId(a)=$from MATCH (b) WHERE elementId(b)=$to "
                    + "CREATE (a)-[r:" + edge.path("type").asText() + "]->(b) SET r=$properties",
                    Map.of("from", ids.get(edge.path("from").asText()), "to", ids.get(edge.path("to").asText()),
                            "properties", json.convertValue(edge.path("properties"), MAP))).consume();
        }
    }
}
