package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CentralFieldsTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode definition() throws Exception {
        var target=(ObjectNode)json.readTree(Path.of("examples/turboea-integration.example.json").toFile()).path("targets").get(0);
        var inbound=target.putObject("inboundAccess").put("enabled",true); inbound.putArray("administrators").add("admin"); inbound.putArray("cardTypes").add("Application"); inbound.putArray("fields").add("name").add("attributes.cost").add("attributes.shared");
        var central=target.putObject("centralEditAccess").put("enabled",true).put("acceptUnconditionalWriteRisk",true).put("completeAttributeReadsApproved",true);
        central.putArray("requesters").add("a").add("b"); central.putArray("approvers").add("admin"); central.putArray("cardTypes").add("Application"); central.putObject("fields").put("name","PROPOSAL").put("attributes.cost","WRITE_THROUGH").put("attributes.shared","PROPOSAL");
        var fields=(ObjectNode)target.path("mapping").path("fields").path("Application");
        fields.putObject("property:cost").put("remoteField","attributes.cost").put("valueType","NUMBER").put("authority","TURBO_EA_MASTER");
        fields.putObject("property:shared").put("remoteField","attributes.shared").put("valueType","STRING").put("authority","SHARED_PROPOSAL"); return target;
    }
    @Test void exactJsonTextIsParsedWithoutDoubleRoundingAndDisplayedLosslessly() throws Exception {
        var target=json.treeToValue(definition(),IntegrationConfiguration.Target.class); String value="1.12345678901234567890123456789";
        var proposed=CentralFields.proposed(json,target,"Application",Map.of("property:cost",json.getNodeFactory().textNode(value)));
        assertEquals(value,CentralFields.displayMap(proposed).get("property:cost")); assertEquals(value,CentralFields.localRepresentation(json,proposed).path("property:cost").asText());
        assertThrows(PublicationModel.Failure.class,() -> CentralFields.proposed(json,target,"Application",Map.of("property:cost",json.getNodeFactory().numberNode(1.2345))));
    }
    @Test void sharedWriteThroughAndIdentityStatusFieldsCannotBeEnabled() throws Exception {
        var definition=definition(); ((ObjectNode)definition.path("centralEditAccess").path("fields")).put("attributes.shared","WRITE_THROUGH");
        assertThrows(Exception.class,() -> json.treeToValue(definition,IntegrationConfiguration.Target.class));
        var invalid=definition(); ((ObjectNode)invalid.path("centralEditAccess").path("fields")).put("external_id","PROPOSAL");
        assertThrows(Exception.class,() -> json.treeToValue(invalid,IntegrationConfiguration.Target.class));
    }
    @Test void scopeDigestsIgnoreSetAndMapInsertionOrder() throws Exception {
        var first=definition(); var second=definition(); ((ObjectNode)second.path("centralEditAccess")).putArray("requesters").add("b").add("a");
        assertEquals(CentralEditAuthorization.digest(json.treeToValue(first,IntegrationConfiguration.Target.class)),CentralEditAuthorization.digest(json.treeToValue(second,IntegrationConfiguration.Target.class)));
    }
    @Test void replacementPayloadPreservesUnownedMembersAndCannotChangeIdentity() throws Exception {
        var target=json.treeToValue(definition(),IntegrationConfiguration.Target.class);
        var card=new ExternalRepositoryConnector.ExternalObject("id","Application","Name",null,"foreign-id","ACTIVE",json.createObjectNode().put("unowned","keep").put("cost",1),null);
        var payload=CentralFields.payload(json,target,"Application",CentralFields.proposed(json,target,"Application",Map.of("property:cost",json.getNodeFactory().textNode("2.5"))),card);
        assertEquals("keep",payload.path("attributes").path("unowned").asText()); assertFalse(payload.has("external_id")); assertFalse(payload.has("status")); assertEquals(1,payload.size());
    }
    @Test void canonicalUnmappedFieldsCannotBeProposedOrPublishedThroughCentralHelpers() throws Exception {
        var definition=definition();
        ((ObjectNode)definition.path("mapping")).putObject("canonicalAuthorities").putObject("urn:asset:a").put("name","UNMAPPED");
        var target=json.treeToValue(definition,IntegrationConfiguration.Target.class);
        assertTrue(CentralFields.mappings(target,"Application").containsKey("name"));
        assertFalse(CentralFields.mappings(target,"Application","urn:asset:a").containsKey("name"));
        assertFalse(InboundFields.normalMappings(target,"Application","urn:asset:a").containsKey("name"));
        assertThrows(PublicationModel.Failure.class,() -> CentralFields.proposed(json,target,"Application",Map.of("name",json.getNodeFactory().textNode("Rejected")),"urn:asset:a"));
        var card=new ExternalRepositoryConnector.ExternalObject("id","Application","Name",null,null,"ACTIVE",json.createObjectNode(),null);
        assertThrows(PublicationModel.Failure.class,() -> CentralFields.payload(json,target,"Application",json.createObjectNode().put("name","Rejected"),card,"urn:asset:a"));
    }
    @Test void canonicalSharedAuthorityCannotUseATypeLevelWriteThroughPermission() throws Exception {
        var definition=definition();
        ((ObjectNode)definition.path("mapping")).putObject("canonicalAuthorities").putObject("urn:asset:a").put("property:cost","SHARED_PROPOSAL");
        var target=json.treeToValue(definition,IntegrationConfiguration.Target.class);
        assertThrows(PublicationModel.Failure.class,() -> CentralFields.mappings(target,"Application","urn:asset:a"));
    }
}
