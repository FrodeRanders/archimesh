package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.PublicationModel.*;

class PublicationProfileTest {
    private final ObjectMapper json=new ObjectMapper();
    ObjectNode definition() throws Exception { return (ObjectNode)json.readTree(Files.readString(Path.of("examples/publication-profiles.example.json"))).path("profiles").get(0); }
    @Test void catalogHasStableDigestIndependentOfSetAndSelectorOrdering() throws Exception {
        var value=definition(); var a=json.treeToValue(value,PublicationProfile.Definition.class);
        var list=(ArrayNode)value.get("elements"); var first=list.remove(0); list.add(first);
        assertEquals(a.pin(json),json.treeToValue(value,PublicationProfile.Definition.class).pin(json));
        value.put("description","Reviewed semantic change");
        assertNotEquals(a.pin(json),json.treeToValue(value,PublicationProfile.Definition.class).pin(json));
    }
    @Test void profileCannotIncreaseLimitsInjectExpressionsOrSelectIdentityMarkers() throws Exception {
        for(String scenario:List.of("limit","identity","expression","ownership","target","provenance")) {
            var value=definition();
            switch(scenario) {
                case "limit" -> ((ObjectNode)value.get("limits")).put("maxSelectedElements",21);
                case "identity" -> ((ObjectNode)value.path("elements").get(0)).putObject("propertiesEquals").put("archimesh.external.canonicalId","forged");
                case "expression" -> value.put("expression","execute arbitrary expression");
                case "ownership" -> value.put("ownershipRule","OVERRIDE_DATAHUB");
                case "target" -> value.put("targetSystem","DATAHUB");
                case "provenance" -> ((ObjectNode)value.path("derivations").get(0)).put("retainProvenance",false);
            }
            assertThrows(Exception.class,() -> json.treeToValue(value,PublicationProfile.Definition.class),scenario);
        }
    }
    @Test void disabledFeatureAndChangedDefinitionCannotResolveAnApprovedPin() throws Exception {
        var profile=json.treeToValue(definition(),PublicationProfile.Definition.class);
        var catalog=new PublicationProfiles(); catalog.json=json;
        assertEquals("PUBLICATION_PROFILES_DISABLED",assertThrows(Failure.class,() -> catalog.resolve(profile.pin(json))).getMessage());
        catalog.enabled=true; catalog.configuration=Optional.of("examples/publication-profiles.example.json"); catalog.start();
        assertEquals(profile,catalog.resolve(profile.pin(json)));
        assertEquals("PUBLICATION_PROFILE_CONTENT_CHANGED",assertThrows(Failure.class,() -> catalog.resolve(new PublicationProfile.Pin(profile.profileId(),profile.version(),"a".repeat(64)))).getMessage());
    }
    @Test void unpinnedPolicyAndPlanRetainTheirExactLegacyJsonShapeAndConstructors() {
        var policy=new PolicySpec(Mode.DISABLED,"HEAD",List.of(),List.of(),Set.of());
        var plan=new Plan("m","t","remote","HEAD",0,"v",List.of());
        assertFalse(json.valueToTree(policy).has("profile")); assertFalse(json.valueToTree(plan).has("profile"));
        assertFalse(json.valueToTree(plan).has("diagnostics"));
        assertEquals(policy,json.convertValue(json.valueToTree(policy),PolicySpec.class));
        assertEquals(plan,json.convertValue(json.valueToTree(plan),Plan.class));
    }
    @Test void selectorsOnlyNarrowExplicitCandidatesFieldsAndEndpoints() throws Exception {
        var value=definition(); var selectors=(ArrayNode)value.get("elements"); selectors.removeAll();
        selectors.addObject().put("archimateType","ApplicationComponent").put("mappingRef","Application").put("allowCreate",true).putArray("fields").add("name");
        var profile=json.treeToValue(value,PublicationProfile.Definition.class);
        var target=json.readValue("""
          {"key":"fixture","instanceId":"remote","baseUrl":"http://localhost","tokenEnvironment":"TOKEN","expectedApiVersion":"2.194.0","timeoutMillis":1000,"maxResponseBytes":4096,
           "mapping":{"version":"v1","cardTypes":{"Application":"ApplicationComponent"},"relationTypes":{},"fields":{"Application":{"name":{"remoteField":"name","valueType":"STRING","authority":"ARCHIMESH_MASTER"},"documentation":{"remoteField":"description","valueType":"STRING","authority":"DATAHUB_MASTER"}}}},
           "publicationAccess":{"enabled":true,"publishers":["publisher"],"cardTypes":["Application"],"relationTypes":[],"fields":["name","description"]}}
          """,IntegrationConfiguration.Target.class);
        var metadata=(IntegrationStateRepository)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IntegrationStateRepository.class},
            (p,m,a) -> { if(m.getName().equals("findPublicationIdentity")) return Optional.empty(); throw new AssertionError("Preview must not write or read repositories: "+m.getName()); });
        var snapshot=json.readTree("""
          {"headRevision":2,"elements":[{"id":"elem:a","archimateType":"ApplicationComponent","name":"Selected","documentation":"Catalog owned"},{"id":"elem:b","archimateType":"ApplicationComponent","name":"Not selected"},{"id":"elem:f","archimateType":"ApplicationFunction","name":"Internal"}],"relationships":[],"propertyState":[]}
          """);
        var policy=new PolicySpec(Mode.TAG,"release",List.of("elem:a","elem:f"),List.of(),Set.of("elem:a"),profile.pin(json));
        var plan=new ProfileProjection(json).build("local","model",target,policy,snapshot,metadata,profile);
        assertEquals(1,plan.resources().size()); assertEquals(Set.of("name"),plan.resources().getFirst().fields().keySet());
        assertEquals("elem:a",plan.resources().getFirst().identity().model().entityId());
        assertEquals("ELEMENT_FILTERED",plan.diagnostics().getFirst().code());
        assertEquals(plan,new ProfileProjection(json).build("local","model",target,policy,snapshot,metadata,profile));
    }
}
