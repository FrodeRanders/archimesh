package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.gautelis.archimesh.integration.PublicationModel.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureProjectionTest {
    private final ObjectMapper json=new ObjectMapper();
    private IntegrationConfiguration.Target target(boolean attributeReads) throws Exception {
        return json.readValue("""
            {"key":"fixture","instanceId":"remote","baseUrl":"http://localhost:8920","tokenEnvironment":"TOKEN","expectedApiVersion":"2.194.0","timeoutMillis":1000,"maxResponseBytes":4096,
             "mapping":{"version":"v1","cardTypes":{"Application":"ApplicationComponent"},"relationTypes":{"Serves":{"archimateType":"ServingRelationship","sourceType":"Application","targetType":"Application","cardinality":"n:m","reversed":true}},
             "fields":{"Application":{"name":{"remoteField":"name","valueType":"STRING","authority":"ARCHIMESH_MASTER"},"documentation":{"remoteField":"description","valueType":"STRING","authority":"TURBO_EA_MASTER"},"property:owners":{"remoteField":"attributes.owners","valueType":"ARRAY","authority":"ARCHIMESH_MASTER"}}}},
             "publicationAccess":{"enabled":true,"publishers":["publisher"],"cardTypes":["Application"],"relationTypes":["Serves"],"fields":["name","description","attributes.owners"],"acceptUnconditionalWriteRisk":true,"completeAttributeReadsApproved":%s}}
            """.formatted(attributeReads),IntegrationConfiguration.Target.class);
    }
    private IntegrationStateRepository identities() {
        return (IntegrationStateRepository)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IntegrationStateRepository.class},
                (proxy,method,args) -> { if (method.getName().equals("findPublicationIdentity")) return Optional.empty(); throw new UnsupportedOperationException(); });
    }
    private JsonNode snapshot() throws Exception { return json.readTree("""
        {"headRevision":9,"elements":[{"id":"elem:a","archimateType":"ApplicationComponent","name":"Winning name","documentation":"Central-owned"},{"id":"elem:b","archimateType":"ApplicationComponent","name":"B"}],
         "relationships":[{"id":"rel:r","archimateType":"ServingRelationship","sourceId":"elem:a","targetId":"elem:b"}],
         "propertyState":[{"type":"SetProperty","targetId":"elem:a","key":"owners","value":"[\\\"z\\\",\\\"a\\\"]"}],"views":[{"id":"view:private","name":"Never publish"}]}
        """); }
    @Test void projectsMaterializedWinnersOwnedFieldsAndExplicitRelationDirection() throws Exception {
        var policy=new PolicySpec(Mode.HEAD,"HEAD",List.of("elem:a","elem:b"),List.of("rel:r"),Set.of("elem:a","elem:b","rel:r"));
        var plan=new ArchitectureProjection(json).build("instance","model",target(true),policy,snapshot(),identities());
        assertEquals(3,plan.resources().size()); assertEquals("Winning name",plan.resources().getFirst().fields().get("name").asText());
        assertFalse(plan.resources().getFirst().fields().containsKey("description"));
        assertEquals(json.readTree("[\"a\",\"z\"]"),plan.resources().getFirst().fields().get("attributes.owners"));
        assertEquals("elem:b",plan.resources().getLast().sourceEntityId()); assertEquals("elem:a",plan.resources().getLast().targetEntityId());
        assertFalse(json.valueToTree(plan).toString().contains("Never publish"));
        assertEquals(plan,new ArchitectureProjection(json).build("instance","model",target(true),policy,snapshot(),identities()));
    }
    @Test void refusesUnapprovedAttributeVisibilityAndUnknownMissingEntities() throws Exception {
        var policy=new PolicySpec(Mode.HEAD,"HEAD",List.of("elem:a"),List.of(),Set.of("elem:a"));
        assertThrows(Failure.class,() -> new ArchitectureProjection(json).build("instance","model",target(false),policy,snapshot(),identities()));
        var missing=new PolicySpec(Mode.HEAD,"HEAD",List.of("elem:missing"),List.of(),Set.of());
        assertThrows(Failure.class,() -> new ArchitectureProjection(json).build("instance","model",target(true),missing,snapshot(),identities()));
    }
    @Test void onlyKnownTombstonesWithdrawMissingSelections() throws Exception {
        var snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();
        snapshot.putArray("tombstones").addObject().put("targetId","elem:gone");
        var policy=new PolicySpec(Mode.HEAD,"HEAD",List.of("elem:gone"),List.of(),Set.of());
        assertTrue(new ArchitectureProjection(json).build("instance","model",target(true),policy,snapshot,identities()).resources().isEmpty());
    }
    @Test void canonicalDigestsIgnoreMapOrderAndNumericFormatting() throws Exception {
        assertEquals(PublicationJson.digest(json.readTree("{\"b\":2,\"a\":1.0}")),PublicationJson.digest(json.readTree("{\"a\":1,\"b\":2.0}")));
        assertThrows(IllegalArgumentException.class,() -> new PolicySpec(Mode.TAG,"HEAD",List.of("elem:a"),List.of(),Set.of()));
    }
    @Test void canonicalOverrideControlsPublicationWithoutChangingOtherObjectsOfSameType() throws Exception {
        var policy=new PolicySpec(Mode.HEAD,"HEAD",List.of("elem:a","elem:b"),List.of(),Set.of());
        var projector=new ArchitectureProjection(json);
        var original=projector.build("instance","model",target(true),policy,snapshot(),identities());
        String canonical=original.resources().getFirst().identity().canonical().canonicalId();
        var definition=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(target(true));
        ((com.fasterxml.jackson.databind.node.ObjectNode)definition.path("mapping")).putObject("canonicalAuthorities")
                .putObject(canonical).put("name","TURBO_EA_MASTER").put("documentation","ARCHIMESH_MASTER");
        var revised=projector.build("instance","model",json.treeToValue(definition,IntegrationConfiguration.Target.class),policy,snapshot(),identities());
        assertEquals(canonical,revised.resources().getFirst().identity().canonical().canonicalId());
        assertFalse(revised.resources().getFirst().fields().containsKey("name"));
        assertEquals("Central-owned",revised.resources().getFirst().fields().get("description").asText());
        assertEquals(original.resources().getLast(),revised.resources().getLast());
    }
}
