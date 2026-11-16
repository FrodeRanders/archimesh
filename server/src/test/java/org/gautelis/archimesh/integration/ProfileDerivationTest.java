package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.gautelis.federation.ArchitectureAssertion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.PublicationModel.*;

class ProfileDerivationTest {
    final ObjectMapper json=new ObjectMapper();
    String dataKind="LogicalData";
    IntegrationConfiguration.Target target() throws Exception {
        return json.readValue("""
          {"key":"ea","instanceId":"ea-instance","baseUrl":"http://localhost","tokenEnvironment":"TOKEN","expectedApiVersion":"2.194.0","timeoutMillis":1000,"maxResponseBytes":65536,
           "mapping":{"version":"v1","cardTypes":{"Application":"ApplicationComponent","LogicalData":"DataObject"},
             "relationTypes":{"ApplicationDataAccess":{"archimateType":"AccessRelationship","sourceType":"Application","targetType":"LogicalData","cardinality":"n:m","reversed":false}},
             "fields":{"Application":{"name":{"remoteField":"name","valueType":"STRING","authority":"TURBO_EA_MASTER"}},"LogicalData":{"name":{"remoteField":"name","valueType":"STRING","authority":"ARCHIMESH_MASTER"}}}},
           "publicationAccess":{"enabled":true,"publishers":["publisher"],"cardTypes":["Application","LogicalData"],"relationTypes":["ApplicationDataAccess"],"fields":["name","attributes.operations"],"acceptUnconditionalWriteRisk":true,"completeAttributeReadsApproved":true}}
          """,IntegrationConfiguration.Target.class);
    }
    ObjectNode definition() throws Exception {
        var value=(ObjectNode)json.readTree(Files.readString(Path.of("examples/publication-profiles.example.json"))).path("profiles").get(0);
        value.put("derivationsEnabled",true); return value;
    }
    ObjectNode snapshot() throws Exception {
        return (ObjectNode)json.readTree("""
          {"headRevision":12,"elements":[{"id":"elem:a","archimateType":"ApplicationComponent","name":"Central application"},
           {"id":"elem:d","archimateType":"DataObject","name":"Logical order"},{"id":"elem:f1","archimateType":"ApplicationFunction","name":"Read"},
           {"id":"elem:f2","archimateType":"ApplicationFunction","name":"Write"}],
           "relationships":[{"id":"rel:s1","archimateType":"AssignmentRelationship","sourceId":"elem:a","targetId":"elem:f1"},
           {"id":"rel:s2","archimateType":"AssignmentRelationship","sourceId":"elem:a","targetId":"elem:f2"},
           {"id":"rel:r1","archimateType":"AccessRelationship","sourceId":"elem:f1","targetId":"elem:d"},
           {"id":"rel:r2","archimateType":"AccessRelationship","sourceId":"elem:f2","targetId":"elem:d"}],
           "propertyState":[{"type":"SetProperty","targetId":"elem:d","key":"archimesh.publication.eligible","value":"true"},
           {"type":"SetProperty","targetId":"rel:r1","key":"archimesh.publication.accessMode","value":"Read"},
           {"type":"SetProperty","targetId":"rel:r2","key":"archimesh.publication.accessMode","value":"WRITE"}],
           "views":[{"id":"view:secret","name":"Never exported"}]}
          """);
    }
    IntegrationStateRepository metadata() {
        return (IntegrationStateRepository)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IntegrationStateRepository.class},(proxy,method,args) -> {
            if(method.getName().equals("findPublicationIdentity")) {
                var key=(ModelElementKey)args[0]; boolean app=key.entityId().equals("elem:a");
                if(!app && !key.entityId().equals("elem:d")) return Optional.empty();
                return Optional.of(new IntegrationStateRepository.PublicationIdentity(key,new CanonicalObject(app ? "canonical-app" : "canonical-data",app ? "enterprise.application" : dataKind),
                    app ? "ApplicationComponent" : "DataObject","v1",app ? "Application" : "LogicalData"));
            }
            if(method.getName().equals("findExternal")) return Optional.of(new ExternalResourceKey("turboea","ea-instance",ResourceKind.CARD,args[0].equals("canonical-app") ? "remote-app" : "remote-data"));
            throw new AssertionError("Pure projection attempted metadata mutation: "+method.getName());
        });
    }
    Plan plan(ObjectNode snapshot,ObjectNode definition,String model) throws Exception {
        var profile=json.treeToValue(definition,PublicationProfile.Definition.class);
        var policy=new PolicySpec(Mode.TAG,"release",List.of("elem:a","elem:d"),List.of(),Set.of("elem:d"),profile.pin(json));
        return new ProfileProjection(json).build("instance",model,target(),policy,snapshot,metadata(),profile);
    }
    Resource assertion(Plan plan) { return plan.resources().stream().filter(r -> r.provenance().assertion()!=null).findFirst().orElseThrow(); }
    @Test void parallelFunctionsProduceOneEvidencedLogicalReadWriteAssertion() throws Exception {
        var result=plan(snapshot(),definition(),"model-a"); var usage=assertion(result);
        assertEquals(3,result.resources().size());
        assertEquals(json.readTree("[\"READ\",\"WRITE\"]"),usage.fields().get("attributes.operations"));
        assertEquals(2,usage.provenance().evidence().paths().size());
        assertEquals(List.of("elem:f1","elem:f2"),usage.provenance().evidence().paths().stream().map(ArchitectureAssertion.Path::functionId).toList());
        assertEquals(ArchitectureAssertion.Predicate.LOGICAL_DATA_ACCESS,usage.provenance().assertion().predicate());
        assertEquals("canonical-app",usage.provenance().assertion().subjectCanonicalId());
        assertTrue(usage.identity().model().entityId().startsWith("rel:derived:"));
        String serialized=json.valueToTree(result).toString();
        for(String absent:List.of("CREATE","DELETE","CATALOG_CONSUMES","TECHNICAL_LINEAGE","Never exported")) assertFalse(serialized.contains(absent),absent);
        assertTrue(result.resources().getFirst().fields().isEmpty(),"Central application is reference-only");
    }
    @Test void readOnlyAndReadWriteRepresentationsAreNormalizedWithoutCrudInference() throws Exception {
        var value=snapshot(); ((ArrayNode)value.get("relationships")).remove(3);
        assertEquals(json.readTree("[\"READ\"]"),assertion(plan(value,definition(),"a")).fields().get("attributes.operations"));
        ((ObjectNode)value.path("propertyState").get(1)).put("value","ReadWrite");
        assertEquals(json.readTree("[\"READ\",\"WRITE\"]"),assertion(plan(value,definition(),"a")).fields().get("attributes.operations"));
    }
    @Test void graphOrderAndDuplicateEvidenceDoNotChangeIdentityOrDigest() throws Exception {
        var before=snapshot(); var after=before.deepCopy();
        for(String field:List.of("elements","relationships","propertyState")) {
            var values=new ArrayList<JsonNode>(); after.path(field).forEach(values::add); Collections.reverse(values); after.set(field,json.valueToTree(values));
        }
        assertEquals(PublicationJson.digest(json.valueToTree(plan(before,definition(),"a"))),PublicationJson.digest(json.valueToTree(plan(after,definition(),"a"))));
        ((ArrayNode)after.get("relationships")).add(after.path("relationships").get(0).deepCopy());
        assertEquals("PROFILE_DUPLICATE_SOURCE_ID",assertThrows(Failure.class,() -> plan(after,definition(),"a")).getMessage());
    }
    @Test void modelsShareAssertionIdentityButRetainDifferentEvidenceAndOccurrences() throws Exception {
        var a=assertion(plan(snapshot(),definition(),"model-a")); var b=assertion(plan(snapshot(),definition(),"model-b"));
        assertEquals(a.identity().canonical(),b.identity().canonical()); assertNotEquals(a.identity().model(),b.identity().model());
        assertNotEquals(a.provenance().evidence().digest(),b.provenance().evidence().digest());
        var definition=definition().put("version","2.0.0");
        var upgraded=assertion(plan(snapshot(),definition,"model-a"));
        assertEquals(a.identity().canonical(),upgraded.identity().canonical());
        assertNotEquals(a.provenance().evidence().profileDigest(),upgraded.provenance().evidence().profileDigest());
    }
    @Test void wrongDirectionUnknownModeMissingAssignmentAndCyclesAreDiagnosed() throws Exception {
        for(String scenario:List.of("direction","mode","assignment","cycle")) {
            var value=snapshot(); ((ArrayNode)value.get("relationships")).remove(3);
            switch(scenario) {
                case "direction" -> ((ObjectNode)value.path("relationships").get(2)).put("sourceId","elem:d").put("targetId","elem:f1");
                case "mode" -> ((ObjectNode)value.path("propertyState").get(1)).put("value","DELETE");
                case "assignment" -> ((ArrayNode)value.get("relationships")).remove(0);
                case "cycle" -> ((ObjectNode)value.path("relationships").get(0)).put("sourceId","elem:f1").put("targetId","elem:f1");
            }
            var result=plan(value,definition(),"a");
            assertEquals(2,result.resources().size(),scenario); assertFalse(result.diagnostics().isEmpty(),scenario);
        }
    }
    @Test void removingOneEvidencePathRetainsTheOtherWithoutChangingAssertionIdentity() throws Exception {
        var value=snapshot(); var both=assertion(plan(value,definition(),"a")); ((ArrayNode)value.get("relationships")).remove(3);
        var remaining=assertion(plan(value,definition(),"a"));
        assertEquals(both.identity().canonical(),remaining.identity().canonical()); assertEquals(1,remaining.provenance().evidence().paths().size());
        assertEquals(Set.of(ArchitectureAssertion.Access.READ),remaining.provenance().evidence().access());
    }
    @Test void catalogProductIdentityCannotBeInferredAsLogicalDataUsage() throws Exception {
        dataKind="catalog.data-product"; var result=plan(snapshot(),definition(),"a");
        assertEquals(2,result.resources().size()); assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("LOGICAL_DATA_REQUIRED_NOT_CATALOG_ASSET")));
    }
    @Test void derivationsRequireExplicitEnablementAndStayWithinBounds() throws Exception {
        var definition=definition().put("derivationsEnabled",false);
        assertEquals(2,plan(snapshot(),definition,"a").resources().size());
        definition.put("derivationsEnabled",true); ((ObjectNode)definition.path("limits")).put("maxDerivedRelations",0);
        assertEquals("PROFILE_DERIVED_LIMIT_EXCEEDED",assertThrows(Failure.class,() -> plan(snapshot(),definition,"a")).getMessage());
    }
}
