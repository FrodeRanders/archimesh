package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IntegrationConfigurationDigestTest {
    @Test void legacyMappingSerializationAndApprovalsStayStableUntilAnOverrideIsAdded() throws Exception {
        var json=new ObjectMapper(); var node=(ObjectNode)json.readTree(Path.of("examples/turboea-integration.example.json").toFile()).path("targets").get(0);
        var legacy=json.treeToValue(node,IntegrationConfiguration.Target.class);
        assertFalse(json.valueToTree(legacy.mapping()).has("canonicalAuthorities"));
        ((ObjectNode)node.path("mapping")).putObject("canonicalAuthorities");
        var empty=json.treeToValue(node,IntegrationConfiguration.Target.class);
        assertEquals(IntegrationConfigurationDigest.target(legacy),IntegrationConfigurationDigest.target(empty));
        assertEquals(CentralEditAuthorization.digest(legacy),CentralEditAuthorization.digest(empty));
        assertEquals(ExternalChangeIngestService.configurationDigest(legacy),ExternalChangeIngestService.configurationDigest(empty));
        ((ObjectNode)node.path("mapping").path("canonicalAuthorities")).putObject("urn:asset:a").put("name","LOCAL_MODEL_ONLY");
        var revised=json.treeToValue(node,IntegrationConfiguration.Target.class);
        assertNotEquals(IntegrationConfigurationDigest.target(legacy),IntegrationConfigurationDigest.target(revised));
        assertNotEquals(CentralEditAuthorization.digest(legacy),CentralEditAuthorization.digest(revised));
        assertNotEquals(ExternalChangeIngestService.configurationDigest(legacy),ExternalChangeIngestService.configurationDigest(revised));
        assertThrows(IllegalArgumentException.class,() -> new org.gautelis.archimesh.integration.turboea.TurboEaMapping("v1",legacy.mapping().cardTypes(),legacy.mapping().relationTypes(),legacy.mapping().fields(),java.util.Map.of("urn:a",java.util.Map.of("not-mapped",InformationAuthorityPolicy.Authority.TURBO_EA_MASTER))).fieldsFor("Application","urn:a"));
    }
    @Test void targetDigestSurvivesSetInsertionOrderButDetectsAuthorityChanges() throws Exception {
        var json=new ObjectMapper(); var a=(ObjectNode)json.readTree(Path.of("examples/turboea-integration.example.json").toFile()).path("targets").get(0); var b=a.deepCopy();
        ((ObjectNode)a.path("catalogAccess")).putArray("readers").add("alice").add("bob"); ((ObjectNode)b.path("catalogAccess")).putArray("readers").add("bob").add("alice");
        assertEquals(IntegrationConfigurationDigest.target(json.treeToValue(a,IntegrationConfiguration.Target.class)),IntegrationConfigurationDigest.target(json.treeToValue(b,IntegrationConfiguration.Target.class)));
        ((ObjectNode)b.path("mapping").path("fields").path("Application").path("name")).put("authority","SHARED_PROPOSAL");
        assertNotEquals(IntegrationConfigurationDigest.target(json.treeToValue(a,IntegrationConfiguration.Target.class)),IntegrationConfigurationDigest.target(json.treeToValue(b,IntegrationConfiguration.Target.class)));
    }
}
