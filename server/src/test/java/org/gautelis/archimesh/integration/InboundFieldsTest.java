package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class InboundFieldsTest {
    private final ObjectMapper json=new ObjectMapper();
    private IntegrationConfiguration.Target target() throws Exception {
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Path.of("examples/turboea-integration.example.json").toFile()).path("targets").get(0);
        var inbound=node.putObject("inboundAccess").put("enabled",true); inbound.putArray("administrators").add("admin"); inbound.putArray("cardTypes").add("Application"); inbound.putArray("fields").add("name").add("attributes.central");
        ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("mapping").path("fields").path("Application")).putObject("property:central").put("remoteField","attributes.central").put("valueType","NUMBER").put("authority","TURBO_EA_MASTER");
        return json.treeToValue(node,IntegrationConfiguration.Target.class);
    }
    @Test void projectsOnlyApprovedCentralFieldsAndKeepsDecimalPrecision() throws Exception {
        var value=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree("{\"central\":1.12345678901234567890123456789,\"private\":\"hidden\"}");
        var card=new ExternalRepositoryConnector.ExternalObject("a","Application","Central","Local-owned description",null,"ACTIVE",value,null);
        var result=InboundFields.remote(json,target(),card);
        assertEquals("1.12345678901234567890123456789",result.path("property:central").asText()); assertEquals(2,result.size()); assertFalse(result.toString().contains("hidden"));
    }
    @Test void propertyRemovalIsDistinctFromAnEmptyStringAndTypeMismatchFailsClosed() throws Exception {
        var card=new ExternalRepositoryConnector.ExternalObject("a","Application","Central",null,null,"ACTIVE",json.createObjectNode(),null);
        assertTrue(InboundFields.remote(json,target(),card).path("property:central").isNull());
        var wrong=new ExternalRepositoryConnector.ExternalObject("a","Application","Central",null,null,"ACTIVE",json.createObjectNode().put("central","not numeric"),null);
        assertThrows(PublicationModel.Failure.class,() -> InboundFields.remote(json,target(),wrong));
    }
}
