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
import org.gautelis.federation.IdentityRegistry;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest @TestProfile(ApplicationProjectionIT.Profile.class)
@QuarkusTestResource(value=ApplicationProjectionTestResource.class,restrictToAnnotatedClass=true)
class ApplicationProjectionIT {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() { return Map.of("app.authz.enabled","true","app.identity.mode","bootstrap"); }
    }
    @Inject IntegrationRuntime runtime;
    @TestHTTPResource URI base;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.application.target") String target;
    private final ObjectMapper json=new ObjectMapper(); private final HttpClient http=HttpClient.newHttpClient();
    private String remote;
    @BeforeEach void setup() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        remote=runtime.settings().targets().getFirst().baseUrl().toString();
        control(remote,json.createObjectNode().put("reset",true)); control(target,json.createObjectNode().put("reset",true));
        for(String id:List.of("app-1","app-2","app-adopt")) source(id,"Order application");
    }
    private void source(String id,String name) throws Exception {
        var card=json.createObjectNode().put("id",id).put("type","Application").put("status","ACTIVE").put("name",name)
            .put("description","Turbo EA description"); card.putObject("attributes").put("local","unchanged");
        card.putObject("lifecycle").put("phase","production");
        var body=json.createObjectNode(); body.set("card",card); control(remote,body);
    }
    private JsonNode control(String url,JsonNode body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(url+"/__fixture"));
        if(body==null) builder.GET(); else builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return json.readTree(http.send(builder.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    private HttpResponse<String> api(String route,JsonNode body,String user,String role) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve("federation/v1/application-projections/"+route)).header("Content-Type","application/json")
            .header("X-Archimesh-User",user).header("X-Archimesh-Roles",role).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
    }
    private ObjectNode request(String id) { return json.createObjectNode().put("profile","applications").put("nativeId",id).put("correlation",UUID.randomUUID().toString()); }
    private JsonNode prepare(ObjectNode source) throws Exception {
        var response=api("prepare",source,"bridge","federation_bridge"); assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    private JsonNode action(String route,ObjectNode source,JsonNode plan,int expected) throws Exception {
        var command=json.createObjectNode().put("intentId",plan.path("intentId").asText()).put("version",plan.path("version").asLong()); command.set("source",source);
        var response=api(route,command,"bridge","federation_bridge"); assertEquals(expected,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    private ObjectNode properties() {
        var properties=json.createObjectNode().put("name","Order application").put("description","Turbo EA description");
        properties.putObject("customProperties").put(ApplicationProjectionService.LIFECYCLE,"{\"phase\":\"production\"}").put("local.note","preserve");
        properties.put("externalUrl","https://example.invalid/local"); properties.put("parentApplication","urn:li:application:parent");
        return properties;
    }
    private void destination(String urn,ObjectNode properties) throws Exception {
        var body=json.createObjectNode().put("urn",urn).put("removed",false); body.set("properties",properties); control(target,body);
    }
    private int writes() throws Exception { return control(target,null).path("writes").asInt(); }
    @AfterEach void cleanup() {
        if(!"true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT"))) return;
        try(var driver=GraphDatabase.driver(System.getenv("NEO4J_URI"),AuthTokens.basic(System.getenv("NEO4J_USER"),System.getenv("NEO4J_PASSWORD"))); var session=driver.session()) {
            String owner=runtime.settings().instanceId();
            session.executeWrite(tx -> {
                tx.run("MATCH (n:IntegrationStateDocument {instanceId:$owner}) DETACH DELETE n",Map.of("owner",owner)).consume();
                tx.run("MATCH (n:IntegrationExternalBinding) WHERE n.targetInstanceId IN $repositories DETACH DELETE n",Map.of("repositories",List.of(owner,owner+"-datahub"))).consume();
                tx.run("MATCH (n:IntegrationCanonicalObject {registryInstanceId:$owner}) DETACH DELETE n",Map.of("owner",owner)).consume(); return null;
            });
        }
    }
    @Test void sourceRenameRevertAndRepeatPreserveOneApplicationAndForeignAspectMembers() throws Exception {
        var request=request("app-1"); var created=action("execute",request,prepare(request),200); String urn=created.path("targetId").asText();
        assertEquals("VERIFIED",created.path("state").asText()); assertEquals(2,writes());
        var registry=runtime.metadata().identities(runtime.settings().instanceId());
        var original=registry.find(new Resource(new Repository("turboea",runtime.settings().instanceId()),ResourceKind.OBJECT,"app-1")).orElseThrow();
        var mirror=registry.find(new Resource(new Repository("datahub",runtime.settings().instanceId()+"-datahub"),ResourceKind.OBJECT,urn)).orElseThrow();
        assertEquals(original.canonical(),mirror.canonical());
        assertEquals(created.path("canonicalId").asText(),mirror.canonical().id());
        destination(urn,properties());
        source("app-1","Renamed application"); var changed=request("app-1");
        var updated=action("execute",changed,prepare(changed),200); assertEquals(created.path("targetId"),updated.path("targetId"));
        var actual=control(target,null).path("applications").path(urn);
        assertEquals("Renamed application",actual.path("name").asText());
        assertEquals("preserve",actual.path("customProperties").path("local.note").asText());
        assertEquals("urn:li:application:parent",actual.path("parentApplication").asText());
        assertEquals("https://example.invalid/local",actual.path("externalUrl").asText());
        source("app-1","Order application"); var reverted=request("app-1");
        assertEquals(urn,action("execute",reverted,prepare(reverted),200).path("targetId").asText());
        assertEquals("VERIFIED",prepare(request("app-1")).path("state").asText()); assertEquals(4,writes());
        assertEquals(1,control(target,null).path("applications").size());
        assertEquals(0,control(remote,null).path("writes").asInt());
    }
    @Test void completeWriteWithLostAcknowledgementCanOnlyBeRecoveredByExplicitReadback() throws Exception {
        var request=request("app-1"); var prepared=prepare(request);
        control(target,json.createObjectNode().put("fault","status-commit-503"));
        action("execute",request,prepared,409); var uncertain=prepare(request);
        assertEquals("UNCERTAIN",uncertain.path("state").asText()); assertEquals(2,writes());
        action("execute",request,uncertain,409); assertEquals(2,writes());
        assertEquals("VERIFIED",action("verify-outcome",request,uncertain,200).path("state").asText());
        assertEquals(2,writes()); assertEquals("VERIFIED",prepare(request).path("state").asText());
    }
    @Test void partialMultiAspectCreateStaysUncertainWithoutBlindRetryOrStatusRepair() throws Exception {
        var request=request("app-1"); var prepared=prepare(request);
        control(target,json.createObjectNode().put("fault","commit-503"));
        action("execute",request,prepared,409); var uncertain=prepare(request);
        assertEquals("UNCERTAIN",uncertain.path("state").asText());
        action("verify-outcome",request,uncertain,409); action("execute",request,uncertain,409); assertEquals(1,writes());
    }
    @Test void equalNamesNeverMergeDifferentApplicationIds() throws Exception {
        var first=request("app-1"); var second=request("app-2");
        var a=action("execute",first,prepare(first),200); var b=action("execute",second,prepare(second),200);
        assertNotEquals(a.path("canonicalId"),b.path("canonicalId")); assertNotEquals(a.path("targetId"),b.path("targetId"));
        assertEquals(2,control(target,null).path("applications").size());
    }
    @Test void reviewedExistingApplicationIsAdoptedWithoutWritesAndKeepsNativeUrn() throws Exception {
        String urn="urn:li:application:existing-app"; destination(urn,properties());
        var request=request("app-adopt"); var plan=prepare(request);
        assertEquals(urn,action("execute",request,plan,200).path("targetId").asText()); assertEquals(0,writes());
        source("app-adopt","New name"); var update=request("app-adopt");
        assertEquals(urn,action("execute",update,prepare(update),200).path("targetId").asText()); assertEquals(1,writes());
    }
    @Test void adoptionCannotMergeAnExistingDistinctCanonicalIdentity() throws Exception {
        String urn="urn:li:application:existing-app"; destination(urn,properties());
        String owner=runtime.settings().instanceId();
        runtime.metadata().identities(owner).reserveSource(new Representation(new Resource(new Repository("datahub",owner+"-datahub"),ResourceKind.OBJECT,urn),"application"),
            new Canonical("urn:agency:preexisting","enterprise.application"),new IdentityRegistry.Evidence("separate-source","steward","a".repeat(64)));
        var request=request("app-adopt"); action("execute",request,prepare(request),409); assertEquals(0,writes());
    }
    @Test void targetDriftRemovedAndMissingSourcesBlockWithoutRecreation() throws Exception {
        var request=request("app-1"); var plan=action("execute",request,prepare(request),200); String urn=plan.path("targetId").asText();
        destination(urn,properties().put("name","Foreign edit"));
        assertEquals(409,api("prepare",request,"bridge","federation_bridge").statusCode());
        destination(urn,properties()); control(target,json.createObjectNode().put("urn",urn).put("removed",true));
        assertEquals(409,api("prepare",request,"bridge","federation_bridge").statusCode());
        control(target,json.createObjectNode().put("urn",urn).put("delete",true));
        assertEquals(409,api("prepare",request,"bridge","federation_bridge").statusCode());
        var edit=json.createObjectNode().put("id","app-1"); edit.putObject("fields").put("status","ARCHIVED"); control(remote,edit);
        assertEquals(409,api("prepare",request,"bridge","federation_bridge").statusCode());
        control(remote,json.createObjectNode().put("id","app-1").put("delete",true));
        assertEquals(404,api("prepare",request,"bridge","federation_bridge").statusCode()); assertEquals(2,writes());
    }
    @Test void sourceChangesInvalidatePreparedReviewAndCanBeReplannedBeforeDispatch() throws Exception {
        var request=request("app-1"); var plan=prepare(request); source("app-1","New approved observation");
        action("execute",request,plan,409); assertEquals(0,writes());
        action("execute",request,prepare(request),200); assertEquals(2,writes());
    }
    @Test void principalRoleExactIdAndUnknownProfileAreCheckedBeforeRepositoryAccess() throws Exception {
        assertEquals(403,api("prepare",request("app-1"),"admin","admin").statusCode());
        assertEquals(403,api("prepare",request("app-1"),"bridge","admin").statusCode());
        assertEquals(403,api("prepare",request("outside"),"bridge","federation_bridge").statusCode());
        assertEquals(403,api("prepare",request("app-1").put("profile","datasets"),"bridge","federation_bridge").statusCode());
        assertEquals(0,control(remote,null).path("calls").asInt()); assertEquals(0,control(target,null).path("reads").asInt());
    }
}
