package org.gautelis.archimesh.integration.federation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.*;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.gautelis.archimesh.integration.*;
import org.gautelis.federation.Identity.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest @TestProfile(DatasetProjectionIT.Profile.class)
@QuarkusTestResource(value=DatasetProjectionTestResource.class,restrictToAnnotatedClass=true)
class DatasetProjectionIT {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() { return Map.of("app.authz.enabled","true","app.identity.mode","bootstrap"); }
    }
    @Inject IntegrationRuntime runtime;
    @Inject org.gautelis.archimesh.service.ArchimeshService models;
    @Inject ExternalObjectCatalogService catalog;
    @Inject ExternalChangeIngestService inbound;
    private String model;
    @TestHTTPResource URI base;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.projection.source") String source;
    private final ObjectMapper json=new ObjectMapper(); private final HttpClient http=HttpClient.newHttpClient();
    private String remote;
    private static final String URN="urn:li:dataset:(urn:li:dataPlatform:postgres,orders,DEV)";
    @BeforeEach void setup() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        remote=runtime.settings().targets().getFirst().baseUrl().toString();
        control(remote,json.createObjectNode().put("reset",true)); control(source,json.createObjectNode().put("reset",true));
    }
    private JsonNode control(String url,JsonNode body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(url+"/__fixture"));
        if(body==null) builder.GET(); else builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return json.readTree(http.send(builder.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    private HttpResponse<String> api(String route,JsonNode body,String user,String role) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve("federation/v1/projections/"+route)).header("Content-Type","application/json")
            .header("X-Archimesh-User",user).header("X-Archimesh-Roles",role).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
    }
    private ObjectNode request(String urn) { return json.createObjectNode().put("profile","datasets").put("nativeId",urn).put("correlation",UUID.randomUUID().toString()); }
    private JsonNode prepare(ObjectNode source) throws Exception { var r=api("prepare",source,"bridge","federation_bridge"); assertEquals(200,r.statusCode(),r.body()); return json.readTree(r.body()); }
    private JsonNode execute(ObjectNode source,JsonNode plan,int expected) throws Exception {
        var command=json.createObjectNode().put("intentId",plan.path("intentId").asText()).put("version",plan.path("version").asLong()); command.set("source",source);
        var response=api("execute",command,"bridge","federation_bridge"); assertEquals(expected,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    @AfterEach void cleanup() {
        if(!"true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT"))) return;
        if(model!=null) models.deleteModel(model,true);
        try(var driver=GraphDatabase.driver(System.getenv("NEO4J_URI"),AuthTokens.basic(System.getenv("NEO4J_USER"),System.getenv("NEO4J_PASSWORD"))); var session=driver.session()) {
            String owner=runtime.settings().instanceId();
            session.executeWrite(tx -> {
                tx.run("MATCH (n:IntegrationStateDocument {instanceId:$owner}) DETACH DELETE n",Map.of("owner",owner)).consume();
                tx.run("MATCH (n:IntegrationExternalBinding) WHERE n.targetInstanceId IN $repositories DETACH DELETE n",Map.of("repositories",List.of(owner,owner+"-datahub"))).consume();
                tx.run("MATCH (n:IntegrationCanonicalObject {registryInstanceId:$owner}) DETACH DELETE n",Map.of("owner",owner)).consume();
                return null;
            });
        }
    }
    @Test void createUpdateRevertAndRetryKeepOneCanonicalAndTargetWithoutAnyModel() throws Exception {
        var request=request(URN); var created=execute(request,prepare(request),200); String id=created.path("targetId").asText();
        assertEquals("VERIFIED",created.path("state").asText());
        var sourceBinding=runtime.metadata().identities(runtime.settings().instanceId()).find(new Resource(new Repository("datahub",runtime.settings().instanceId()+"-datahub"),ResourceKind.OBJECT,URN)).orElseThrow();
        assertEquals(sourceBinding.canonical().id(),created.path("canonicalId").asText());
        assertEquals(id,prepare(request(URN)).path("targetId").asText()); assertEquals(1,control(remote,null).path("writes").asInt());
        control(source,json.createObjectNode().put("name","Renamed order book"));
        var changed=request(URN); var updated=execute(changed,prepare(changed),200);
        assertEquals(id,updated.path("targetId").asText()); assertEquals(created.path("canonicalId"),updated.path("canonicalId"));
        control(source,json.createObjectNode().put("name","Order book"));
        var reverted=request(URN); assertEquals(id,execute(reverted,prepare(reverted),200).path("targetId").asText());
        assertEquals(1,control(remote,null).path("cards").size()); assertEquals(3,control(remote,null).path("writes").asInt());
    }
    @Test void committedCreateWithLostReplyKeepsDurableUncertaintyAndCannotPostAgain() throws Exception {
        var request=request(URN); var plan=prepare(request); control(remote,json.createObjectNode().put("fault","commit-503"));
        execute(request,plan,409);
        assertEquals("UNCERTAIN",prepare(request).path("state").asText()); execute(request,prepare(request),409);
        assertEquals(1,control(remote,null).path("writes").asInt());
        assertEquals(org.gautelis.federation.TargetMutationLedger.State.UNCERTAIN,runtime.metadata().targetMutations(runtime.settings().instanceId()).find(plan.path("intentId").asText()).orElseThrow().state());
    }
    @Test void reusedCorrelationAndEqualNamesCannotMergeDifferentNativeReferences() throws Exception {
        var first=request(URN); var second=first.deepCopy().put("nativeId",URN.replace("orders,","other_orders,"));
        var a=execute(first,prepare(first),200);
        assertEquals(409,api("prepare",second,"bridge","federation_bridge").statusCode());
        second.put("correlation",UUID.randomUUID().toString());
        var b=execute(second,prepare(second),200);
        assertNotEquals(a.path("canonicalId"),b.path("canonicalId"));
        assertNotEquals(a.path("targetId"),b.path("targetId"));
        assertEquals(2,control(remote,null).path("cards").size());
    }
    @Test void targetOwnedFieldDriftAndRemovedSourceBlockWrites() throws Exception {
        var request=request(URN); var created=execute(request,prepare(request),200);
        var edit=json.createObjectNode().put("id",created.path("targetId").asText()); edit.putObject("fields").put("name","Manual target edit"); control(remote,edit);
        control(source,json.createObjectNode().put("name","New source name"));
        assertEquals(409,api("prepare",request(URN),"bridge","federation_bridge").statusCode());
        control(source,json.createObjectNode().put("removed",true));
        assertEquals(409,api("prepare",request(URN),"bridge","federation_bridge").statusCode());
        assertEquals(1,control(remote,null).path("writes").asInt());
    }
    @Test void principalRoleAndPrefixAreCheckedBeforeSourceReads() throws Exception {
        assertEquals(403,api("prepare",request(URN),"admin","admin").statusCode());
        assertEquals(403,api("prepare",request(URN),"bridge","admin").statusCode());
        assertEquals(403,api("prepare",request("urn:li:application:outside"),"bridge","federation_bridge").statusCode());
        assertEquals(0,control(source,null).path("reads").asInt()); assertEquals(0,control(remote,null).path("writes").asInt());
    }
    @Test void linkedModelWaitsForFreshMirrorThenResumesWithSameIdentity() throws Exception {
        var request=request(URN); var created=execute(request,prepare(request),200);
        var publisher=new org.gautelis.archimesh.auth.AuthorizationSubject("publisher",Set.of("model_writer"));
        model=runtime.settings().instanceId()+"-model-"+UUID.randomUUID(); models.registerModel(model,"Dataset consumer","publisher");
        models.updateModelAccessControl(model,Set.of("publisher"),Set.of("publisher"),Set.of("publisher"));
        var preview=catalog.preview(model,"HEAD",publisher,"fixture",created.path("targetId").asText());
        var imported=catalog.importObject(model,publisher,new ExternalObjectCatalogService.ImportRequest(UUID.randomUUID().toString(),"fixture",created.path("targetId").asText(),"HEAD",ExternalObjectCatalogService.ImportMode.CREATE,null,preview.digest()));
        var target=runtime.settings().targets().getFirst();
        inbound.approve(model,"fixture",publisher,new ExternalChangeIngestService.Approval(true,0,target.instanceId(),target.mapping().version()));
        control(source,json.createObjectNode().put("name","Source changed before mirror"));
        inbound.refresh(model,"fixture",publisher,imported.elementId());
        assertEquals("WAITING_SOURCE",inbound.status(model,"fixture",publisher,"").bindings().getFirst().state());
        assertThrows(PublicationModel.Failure.class,() -> inbound.inspect(model,"fixture",publisher,imported.elementId()));
        execute(request(URN),prepare(request(URN)),200);
        inbound.refresh(model,"fixture",publisher,imported.elementId());
        assertEquals("SYNCED",inbound.status(model,"fixture",publisher,"").bindings().getFirst().state());
        assertEquals("Source changed before mirror",models.getSnapshot(model).path("elements").get(0).path("name").asText());
        assertEquals(created.path("canonicalId").asText(),imported.canonicalId());
        assertEquals(1,models.getSnapshot(model).path("elements").size());
    }
}
