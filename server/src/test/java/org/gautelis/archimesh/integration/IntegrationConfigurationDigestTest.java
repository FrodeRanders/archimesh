package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IntegrationConfigurationDigestTest {
    @Test void targetDigestSurvivesSetInsertionOrderButDetectsAuthorityChanges() throws Exception {
        var json=new ObjectMapper(); var a=(ObjectNode)json.readTree(Path.of("examples/turboea-integration.example.json").toFile()).path("targets").get(0); var b=a.deepCopy();
        ((ObjectNode)a.path("catalogAccess")).putArray("readers").add("alice").add("bob"); ((ObjectNode)b.path("catalogAccess")).putArray("readers").add("bob").add("alice");
        assertEquals(IntegrationConfigurationDigest.target(json.treeToValue(a,IntegrationConfiguration.Target.class)),IntegrationConfigurationDigest.target(json.treeToValue(b,IntegrationConfiguration.Target.class)));
        ((ObjectNode)b.path("mapping").path("fields").path("Application").path("name")).put("authority","SHARED_PROPOSAL");
        assertNotEquals(IntegrationConfigurationDigest.target(json.treeToValue(a,IntegrationConfiguration.Target.class)),IntegrationConfigurationDigest.target(json.treeToValue(b,IntegrationConfiguration.Target.class)));
    }
}
