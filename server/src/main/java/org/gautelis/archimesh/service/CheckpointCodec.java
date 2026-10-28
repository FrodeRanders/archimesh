package org.gautelis.archimesh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gautelis.archimesh.model.ModelCheckpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.TreeSet;

/** Deterministic encoding makes stored and exported checkpoints independently verifiable. */
public final class CheckpointCodec {
    public static final String FORMAT = "archimesh-checkpoint-v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private CheckpointCodec() { }

    public static ModelCheckpoint create(String modelId, long revision, JsonNode state) {
        String createdAt = Instant.now().toString();
        JsonNode canonical = canonicalize(state);
        return new ModelCheckpoint(FORMAT, modelId, revision, createdAt,
                digest(FORMAT, modelId, revision, createdAt, canonical), canonical);
    }

    public static void verify(ModelCheckpoint checkpoint) {
        if (checkpoint == null || !FORMAT.equals(checkpoint.format()) || checkpoint.modelId() == null
                || checkpoint.modelId().isBlank() || checkpoint.revision() < 0 || checkpoint.createdAt() == null
                || checkpoint.state() == null || !checkpoint.state().isObject()
                || !checkpoint.state().path("nodes").isArray() || !checkpoint.state().path("edges").isArray()) {
            throw new IllegalArgumentException("Invalid checkpoint format or metadata");
        }
        try { Instant.parse(checkpoint.createdAt()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid checkpoint timestamp", e); }
        String expected = digest(checkpoint.format(), checkpoint.modelId(), checkpoint.revision(),
                checkpoint.createdAt(), checkpoint.state());
        if (!expected.equals(checkpoint.sha256())) throw new IllegalArgumentException("Checkpoint checksum mismatch");
    }

    /** Preserve the recovery revision and timestamp when importing into a different model namespace. */
    public static ModelCheckpoint retarget(ModelCheckpoint checkpoint, String modelId) {
        verify(checkpoint);
        if (modelId.equals(checkpoint.modelId())) return checkpoint;
        JsonNode state = checkpoint.state().deepCopy();
        for (JsonNode node : state.path("nodes")) ((ObjectNode)node.path("properties")).put("modelId",modelId);
        return new ModelCheckpoint(checkpoint.format(),modelId,checkpoint.revision(),checkpoint.createdAt(),
                digest(checkpoint.format(),modelId,checkpoint.revision(),checkpoint.createdAt(),state),canonicalize(state));
    }

    public static JsonNode canonicalize(JsonNode value) {
        if (value == null) throw new IllegalArgumentException("Checkpoint value is required");
        if (value.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            TreeSet<String> keys = new TreeSet<>();
            value.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) out.set(key, canonicalize(value.get(key)));
            return out;
        }
        if (value.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            value.forEach(item -> out.add(canonicalize(item)));
            return out;
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return com.fasterxml.jackson.databind.node.LongNode.valueOf(value.longValue());
        }
        if (value.isFloatingPointNumber()) {
            return com.fasterxml.jackson.databind.node.DoubleNode.valueOf(value.doubleValue());
        }
        return value.deepCopy();
    }

    private static String digest(String format, String modelId, long revision, String createdAt, JsonNode state) {
        ObjectNode document = JSON.createObjectNode().put("format", format).put("modelId", modelId)
                .put("revision", revision).put("createdAt", createdAt);
        document.set("state", state);
        try {
            byte[] bytes = canonicalize(document).toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
