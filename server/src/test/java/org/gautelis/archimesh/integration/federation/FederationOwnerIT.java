package org.gautelis.archimesh.integration.federation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.quarkus.test.common.*;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.gautelis.archimesh.auth.*;
import org.gautelis.archimesh.integration.*;
import org.gautelis.archimesh.model.*;
import org.gautelis.archimesh.service.ArchimeshService;
import org.gautelis.archimesh.wire.inbound.SubmitOpsMessage;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.federation.FederationTestResource.*;

@QuarkusTest @TestProfile(FederationOwnerIT.Profile.class)
@QuarkusTestResource(value=FederationTestResource.class,restrictToAnnotatedClass=true)
class FederationOwnerIT {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() { return Map.of("app.authz.enabled","true","app.identity.mode","bootstrap"); }
    }
    @Inject IntegrationRuntime runtime;
    @Inject FederationRuntime federation;
    @Inject ArchimeshService models;
    @TestHTTPResource URI base;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.federation.url") String remote;
    private final ObjectMapper json=new ObjectMapper(); private final HttpClient http=HttpClient.newHttpClient();
    private final List<String> ownedModels=new ArrayList<>();
    @BeforeEach void prepare() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        control(json.createObjectNode().put("reset",true));
    }
    private JsonNode control(JsonNode command) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(remote+"/__fixture"));
        if (command==null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(command.toString()));
        return json.readTree(http.send(request.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    private ObjectNode source(String key,String id,String type) { return json.createObjectNode().put("sourceKey",key).put("nativeId",id).put("externalType",type); }
    private HttpResponse<String> api(String path,JsonNode body,String principal,String role) throws Exception {
        var request=HttpRequest.newBuilder(base.resolve("federation/v1/"+path)).header("Content-Type","application/json")
            .header("X-Archimesh-User",principal).header("X-Archimesh-Roles",role).POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private ObjectNode request(String urn,String correlation) { var request=json.createObjectNode().put("correlation",correlation); request.set("source",source("catalog",urn,"dataset")); return request; }
    private JsonNode reserve(String urn,String correlation) throws Exception {
        var response=api("sources/reserve",request(urn,correlation),"bridge","federation_bridge");
        for (int i=0;i<100 && response.statusCode()==409 && response.body().contains("INTEGRATION_BUSY");i++) {
            Thread.sleep(10); response=api("sources/reserve",request(urn,correlation),"bridge","federation_bridge");
        }
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    @AfterEach void cleanup() {
        for (String model:ownedModels) models.deleteModel(model,true);
        if (!"true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT"))) return;
        try (var driver=GraphDatabase.driver(System.getenv().getOrDefault("NEO4J_URI","bolt://localhost:7687"),AuthTokens.basic(System.getenv().getOrDefault("NEO4J_USER","neo4j"),System.getenv().getOrDefault("NEO4J_PASSWORD","devpassword"))); var session=driver.session()) {
            session.executeWrite(tx -> {
                String owner=runtime.settings().instanceId(); var values=Map.<String,Object>of("owner",owner,"repositories",List.of(owner,owner+"-catalog"));
                tx.run("MATCH (n) WHERE (n:IntegrationModelBinding OR n:IntegrationPublicationIdentity OR n:IntegrationIdentityClaim OR n:IntegrationStateDocument) AND n.instanceId=$owner DETACH DELETE n",values).consume();
                tx.run("MATCH (n:IntegrationExternalBinding) WHERE n.targetInstanceId IN $repositories DETACH DELETE n",values).consume();
                tx.run("MATCH (n:IntegrationCanonicalObject {registryInstanceId:$owner}) DETACH DELETE n",values).consume(); return null;
            });
        }
    }
    @Test void modelAdminAndOutsideScopeRequestsAreDeniedBeforeSourceReads() throws Exception {
        var request=source("catalog",DATASET,"dataset");
        assertEquals(403,api("sources/observe",request,"admin","admin").statusCode());
        assertEquals(403,api("sources/observe",request,"bridge","admin").statusCode());
        assertEquals(403,api("sources/observe",source("catalog","urn:li:dataset:restricted","dataset"),"bridge","federation_bridge").statusCode());
        assertEquals(403,api("sources/reserve",request(DATASET,UUID.randomUUID().toString()),"catalog-reader","federation_reader").statusCode());
        assertEquals(0,control(null).path("reads").asInt());
    }
    @Test void observationDisclosesOnlyConfiguredFieldsAndDispatchesBothRepositories() throws Exception {
        var response=api("sources/observe",source("catalog",DATASET,"dataset"),"catalog-reader","federation_reader");
        assertEquals(200,response.statusCode(),response.body()); assertFalse(response.body().contains("PRIVATE-SOURCE-DATA"));
        var observation=json.readTree(response.body()); assertEquals(2,observation.path("fields").size()); assertEquals("ACTIVE",observation.path("lifecycle").asText());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var ea=api("sources/observe",source("ea","card-a","Application"),"bridge","federation_bridge");
        assertEquals(200,ea.statusCode(),ea.body()); assertTrue(ea.body().contains("Shared application")); assertFalse(ea.body().contains("PRIVATE-DESCRIPTION"));
    }
    @Test void sourceReservationsAreModelIndependentRepeatableAndRetainFullNativeUrns() throws Exception {
        int count=models.getModelCatalog().size(); String correlation=UUID.randomUUID().toString();
        var first=reserve(LONG,correlation); assertEquals(first,reserve(LONG,correlation));
        assertEquals(LONG,first.path("representation").path("resource").path("nativeId").asText());
        assertEquals("catalog.dataset",first.path("canonical").path("semanticKind").asText());
        var lookup=api("identities/lookup",source("catalog",LONG,"dataset"),"bridge","federation_bridge");
        assertEquals(200,lookup.statusCode()); assertEquals(first,json.readTree(lookup.body())); assertEquals(count,models.getModelCatalog().size());
    }
    @Test void concurrentCandidatesConvergeAndSourceDriftCannotReuseEvidence() throws Exception {
        var first=reserve(DATASET,UUID.randomUUID().toString());
        try (var pool=Executors.newFixedThreadPool(2)) {
            var calls=pool.<JsonNode>invokeAll(List.of(() -> reserve(DATASET,UUID.randomUUID().toString()),() -> reserve(DATASET,UUID.randomUUID().toString())));
            for (var call:calls) assertEquals(first.path("canonical"),call.get().path("canonical"));
        }
        String correlation=UUID.randomUUID().toString(); reserve(DATASET,correlation); control(json.createObjectNode().put("name","New source name"));
        assertEquals(409,api("sources/reserve",request(DATASET,correlation),"bridge","federation_bridge").statusCode());
        assertEquals(first.path("canonical"),reserve(DATASET,UUID.randomUUID().toString()).path("canonical"));
    }
    @Test void globalPauseAndSourceRemovalBlockRegistration() throws Exception {
        runtime.setPause(true,0,"test");
        assertEquals(409,api("sources/reserve",request(DATASET,UUID.randomUUID().toString()),"bridge","federation_bridge").statusCode());
        runtime.setPause(false,1,"test"); control(json.createObjectNode().put("removed",true));
        assertEquals(409,api("sources/reserve",request(DATASET,UUID.randomUUID().toString()),"bridge","federation_bridge").statusCode());
    }
    @Test void registeredEntityFieldsAreProtectedWhileLocalPropertiesRemainEditableWithoutHttp() throws Exception {
        var identity=reserve(DATASET,UUID.randomUUID().toString()); String owner=runtime.settings().instanceId();
        String model=owner+"-model-"+UUID.randomUUID(), element="elem:"+UUID.randomUUID(), batch=UUID.randomUUID().toString();
        models.registerModel(model,"Source protection fixture","architect"); ownedModels.add(model);
        String generation=runtime.requireModelActive(model);
        var key=new ModelElementKey(owner,model,EntityKind.ELEMENT,element);
        runtime.metadata().reserveImport(new ImportReservation(key,new CanonicalObject(identity.path("canonical").path("id").asText(),"catalog.dataset"),
            new ExternalResourceKey("datahub",owner+"-catalog",ResourceKind.ENTITY,DATASET),"DataObject",federation.digest(),batch,"CREATE","dataset"));
        var ops=json.createArrayNode(); ops.addObject().put("type","CreateElement").putObject("element").put("id",element).put("archimateType","DataObject").put("name","Order book").put("documentation","Catalog-owned description");
        ops.addObject().put("type","SetProperty").put("targetId",element).put("key",ExternalObjectCatalogService.CANONICAL_PROPERTY).put("value",identity.path("canonical").path("id").asText());
        assertTrue(models.submitExternalImport(new ExternalModelImport(model,"HEAD",new AuthorizationSubject("architect",Set.of("model_writer")),batch,element,"DataObject",true,0,ops,generation)).accepted());
        runtime.metadata().confirmImport(key,batch); long head=models.getSnapshot(model).path("headRevision").asLong(); int reads=control(null).path("reads").asInt();
        for (String field:List.of("name","documentation","archimateType")) {
            var mutation=json.createArrayNode(); var op=mutation.addObject().put("type","UpdateElement").put("elementId",element); op.putObject("patch").put(field,"Spoofed value");
            op.putObject("causal").put("lamport",999999).put("clientId","external-sync:forged");
            models.onSubmitOps(model,new SubmitOpsMessage(head,UUID.randomUUID().toString(),new Actor("architect","fixture"),mutation));
            assertEquals(head,models.getSnapshot(model).path("headRevision").asLong());
        }
        var local=json.createArrayNode(); local.addObject().put("type","SetProperty").put("targetId",element).put("key","local.note").put("value","Architect-owned detail");
        models.onSubmitOps(model,new SubmitOpsMessage(head,UUID.randomUUID().toString(),new Actor("architect","fixture"),local));
        assertEquals(head+1,models.getSnapshot(model).path("headRevision").asLong()); assertEquals(reads,control(null).path("reads").asInt());
    }
}
