package org.gautelis.archimesh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gautelis.archimesh.model.ModelCheckpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

class CheckpointCodecTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void checksumSurvivesJsonRoundTripAndObjectKeyReordering() throws Exception {
        var checkpoint = CheckpointCodec.create("m", 12, json.readTree("{\"edges\":[],\"nodes\":[],\"extra\":{\"b\":2,\"a\":1}}"));
        var copy = json.readValue(json.writeValueAsBytes(checkpoint), ModelCheckpoint.class);
        Assertions.assertDoesNotThrow(() -> CheckpointCodec.verify(copy));
        var reordered = json.readTree("{\"extra\":{\"a\":1,\"b\":2},\"nodes\":[],\"edges\":[]}");
        var equivalent = new ModelCheckpoint(copy.format(), copy.modelId(), copy.revision(), copy.createdAt(), copy.sha256(), reordered);
        Assertions.assertDoesNotThrow(() -> CheckpointCodec.verify(equivalent));
    }

    @Test
    void checksumRejectsChangedStateRevisionAndModelIdentity() throws Exception {
        var checkpoint = CheckpointCodec.create("m", 12, json.readTree("{\"nodes\":[],\"edges\":[]}"));
        var changedRevision = new ModelCheckpoint(checkpoint.format(), "m", 13, checkpoint.createdAt(), checkpoint.sha256(), checkpoint.state());
        var changedModel = new ModelCheckpoint(checkpoint.format(), "other", 12, checkpoint.createdAt(), checkpoint.sha256(), checkpoint.state());
        var state = checkpoint.state().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)state).put("unexpected", true);
        var changedState = new ModelCheckpoint(checkpoint.format(), "m", 12, checkpoint.createdAt(), checkpoint.sha256(), state);
        for (var changed : new ModelCheckpoint[]{changedRevision, changedModel, changedState}) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> CheckpointCodec.verify(changed));
        }
    }
}
