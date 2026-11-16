package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.gautelis.archimesh.auth.*;
import org.gautelis.archimesh.model.Actor;
import org.gautelis.archimesh.service.ArchimeshService;
import org.gautelis.archimesh.wire.inbound.SubmitOpsMessage;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.PublicationModel.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;

@QuarkusTest
@TestProfile(ArchitecturePublicationIT.Profile.class)
@QuarkusTestResource(value=ProfilePublicationTestResource.class,restrictToAnnotatedClass=true)
class ProfilePublicationIT {
    @Inject ArchitecturePublicationService publication;
    @Inject PublicationProfiles profiles;
    @Inject IntegrationRuntime runtime;
    @Inject ArchimeshService models;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.publication.url") String remote;
    final ObjectMapper json=new ObjectMapper(); final HttpClient http=HttpClient.newHttpClient();
    final AuthorizationSubject publisher=new AuthorizationSubject("publisher",Set.of("model_writer"));
    final List<String> created=new ArrayList<>(); String scope,app,data,appRemote,dataRemote;
    @BeforeEach void prepare() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        control(json.createObjectNode().put("reset",true)); scope=runtime.settings().instanceId(); app="app-"+UUID.randomUUID(); data="logical-"+UUID.randomUUID(); appRemote="remote-app-"+UUID.randomUUID(); dataRemote="remote-data-"+UUID.randomUUID();
        for(var pair:Map.of(appRemote,"Application",dataRemote,"LogicalData").entrySet()) {
            var card=json.createObjectNode().put("id",pair.getKey()).put("type",pair.getValue()).put("name","Central name").put("status","ACTIVE"); card.putObject("attributes").put("catalogOwned","retain");
            control(json.createObjectNode().set("card",card));
        }
    }
    @AfterEach void cleanup() { for(String model:created) models.deleteModel(model,true); }
    JsonNode control(ObjectNode body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(remote+"/__fixture")); if(body==null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return json.readTree(http.send(request.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    String model(String access,String client) {
        String model=scope+"-profile-"+UUID.randomUUID(); created.add(model); models.registerModel(model,"Synthetic profile", "publisher"); runtime.modelScope(model);
        models.updateModelAccessControl(model,Set.of("publisher"),Set.of(),Set.of());
        var ops=json.createArrayNode();
        for(var pair:Map.of("elem:a","ApplicationComponent","elem:d","DataObject","elem:f","ApplicationFunction").entrySet())
            ops.addObject().put("type","CreateElement").putObject("element").put("id",pair.getKey()).put("archimateType",pair.getValue()).put("name","Local-only name");
        ops.addObject().put("type","CreateRelationship").putObject("relationship").put("id","rel:s").put("archimateType","AssignmentRelationship").put("sourceId","elem:a").put("targetId","elem:f");
        ops.addObject().put("type","CreateRelationship").putObject("relationship").put("id","rel:r").put("archimateType","AccessRelationship").put("sourceId","elem:f").put("targetId","elem:d");
        ops.addObject().put("type","SetProperty").put("targetId","elem:d").put("key","archimesh.publication.eligible").put("value","true");
        ops.addObject().put("type","SetProperty").put("targetId","rel:r").put("key","archimesh.publication.accessMode").put("value",access);
        commit(model,client,ops);
        for(String id:List.of("elem:a","elem:d")) {
            boolean application=id.equals("elem:a");
            var identity=new PublicationIdentity(new ModelElementKey(scope,model,EntityKind.ELEMENT,id),new CanonicalObject(application ? app : data,application ? "Application" : "LogicalData"),application ? "ApplicationComponent" : "DataObject","m3-fixture-v1",application ? "Application" : "LogicalData");
            runtime.metadata().reservePublicationIdentity(identity);
            runtime.metadata().recordPublishedIdentity(identity,new ExternalResourceKey("turboea",scope,ResourceKind.CARD,application ? appRemote : dataRemote),1,"profile-fixture:"+id);
        }
        approve(model); return model;
    }
    void commit(String model,String client,ArrayNode ops) { models.onSubmitOps(model,new SubmitOpsMessage(models.getSnapshot(model).path("headRevision").asLong(),UUID.randomUUID().toString(),new Actor("publisher",client),ops)); }
    PolicySpec approve(String model) {
        String tag="approved-"+UUID.randomUUID(); models.createModelTag(model,tag,"Reviewed synthetic profile");
        var spec=new PolicySpec(Mode.TAG,tag,List.of("elem:a","elem:d"),List.of(),Set.of(),profiles.available().getFirst().pin(json));
        var preview=publication.preview(model,"fixture",publisher,spec);
        publication.approve(model,"fixture",publisher,new Approval(spec,publication.policy(model,"fixture",publisher).version(),preview.digest())); return spec;
    }
    StateDocument run(String model) {
        var job=publication.enqueue(model,"fixture",publisher,new RunRequest(UUID.randomUUID().toString(),null)); publication.runJob(job.id());
        return runtime.metadata().load(scope,DocumentKind.PUBLICATION_JOB,job.id()).orElseThrow();
    }
    void synced(String model) { var job=run(model); assertEquals("SYNCED",job.data().path("state").asText(),job.data().toString()); }
    JsonNode relation() throws Exception { var values=control(null).path("relations"); assertEquals(1,values.size()); return values.elements().next(); }
    void access(String model,String value,long lamport) {
        var ops=json.createArrayNode(); var op=ops.addObject().put("type","SetProperty").put("targetId","rel:r").put("key","archimesh.publication.accessMode").put("value",value);
        op.putObject("causal").put("clientId","change").put("lamport",lamport).put("opId",UUID.randomUUID().toString()); commit(model,"change",ops); approve(model);
    }
    @Test void twoModelsAndClientsUnionClaimsAndWithdrawOnlyTheirOwnEvidence() throws Exception {
        var a=model("READ","client-a"); var b=model("WRITE","client-b"); var snapshot=models.getSnapshot(a);
        synced(a); assertEquals(json.readTree("[\"READ\"]"),relation().path("attributes").path("operations"));
        synced(b); assertEquals(json.readTree("[\"READ\",\"WRITE\"]"),relation().path("attributes").path("operations"));
        String id=relation().path("id").asText(); assertEquals(3,control(null).path("writes").asInt());
        synced(a); synced(b); assertEquals(3,control(null).path("writes").asInt());
        access(a,"UNKNOWN",100); synced(a); assertEquals(json.readTree("[\"WRITE\"]"),relation().path("attributes").path("operations"));
        assertEquals(id,relation().path("id").asText());
        access(b,"UNKNOWN",100); synced(b); assertEquals(id,relation().path("id").asText());
        assertEquals(2,runtime.metadata().list(scope,DocumentKind.ASSERTION_CLAIM,"","",100).stream().filter(c -> c.data().path("status").asText().equals("WITHDRAWN") && Set.of(a,b).contains(c.data().path("sourceModelId").asText())).count());
        assertEquals("WITHDRAWN_REVIEW_REQUIRED",publication.status(b,"fixture",publisher).job().path("state").asText().equals("SYNCED") ? runtime.metadata().list(scope,DocumentKind.PUBLICATION_RESOURCE,"","",100).stream().filter(r -> r.data().path("resourceId").asText().equals(id)).findFirst().orElseThrow().data().path("state").asText() : "job failed");
        assertEquals(2,control(null).path("cards").size()); assertEquals("Central name",control(null).path("cards").path(appRemote).path("name").asText());
        models.createModelCheckpoint(a); var before=models.getSnapshot(a); models.rebuildMaterializedState(a); assertEquals(before,models.getSnapshot(a));
        assertTrue(runtime.metadata().archive(scope).stream().filter(n -> n.label().equals("IntegrationModelBinding") && n.properties().get("sourceModelId").equals(a)).noneMatch(n -> n.properties().get("entityId").toString().startsWith("rel:derived:")));
    }
    @Test void lostRelationPostNeverRetriesOrDuplicatesAndRequiresReviewedIdentity() throws Exception {
        String a=model("READ","client-a"); control(json.createObjectNode().put("fault","relation-commit-503"));
        assertEquals("AMBIGUOUS",run(a).data().path("state").asText()); assertEquals(1,control(null).path("writes").asInt());
        assertEquals("AMBIGUOUS",run(a).data().path("state").asText()); assertEquals(1,control(null).path("writes").asInt());
        var r=publication.status(a,"fixture",publisher).resources().stream().filter(v -> v.entityId().startsWith("rel:derived:")).findFirst().orElseThrow();
        var inspect=publication.inspect(a,"fixture",publisher,r.canonicalId(),relation().path("id").asText());
        publication.adopt(a,"fixture",publisher,new Adoption(r.canonicalId(),inspect.resourceId(),inspect.remoteDigest())); synced(a);
        assertEquals(json.readTree("[\"READ\"]"),relation().path("attributes").path("operations")); assertEquals(2,control(null).path("writes").asInt());
    }
    @Test void lostPatchFencesOtherModelAndRemoteDriftNeedsReview() throws Exception {
        String a=model("READ","client-a"),b=model("WRITE","client-b"); synced(a);
        control(json.createObjectNode().put("fault","relation-patch-commit-503")); assertEquals("AMBIGUOUS",run(b).data().path("state").asText());
        assertEquals("AMBIGUOUS",run(a).data().path("state").asText()); assertEquals(3,control(null).path("writes").asInt());
        var r=publication.status(b,"fixture",publisher).resources().stream().filter(v -> v.entityId().startsWith("rel:derived:")).findFirst().orElseThrow();
        var inspect=publication.inspect(b,"fixture",publisher,r.canonicalId(),null); publication.adopt(b,"fixture",publisher,new Adoption(r.canonicalId(),inspect.resourceId(),inspect.remoteDigest())); synced(b);
        var changed=(ObjectNode)relation().deepCopy(); changed.withObject("attributes").putArray("operations").add("WRITE"); changed.withObject("attributes").put("manual","retain");
        control(json.createObjectNode().set("relation",changed)); assertEquals("REMOTE_DRIFT",run(a).data().path("code").asText());
        inspect=publication.inspect(a,"fixture",publisher,r.canonicalId(),null); publication.adopt(a,"fixture",publisher,new Adoption(r.canonicalId(),inspect.resourceId(),inspect.remoteDigest())); synced(a);
        assertEquals("retain",relation().path("attributes").path("manual").asText());
    }
    @Test void manualRelationIsNeverAutomaticallyClaimedAndRevocationStopsOtherContributors() throws Exception {
        String a=model("READ","client-a");
        var manual=json.createObjectNode().put("id","manual").put("type","ApplicationDataAccess").put("source_id",appRemote).put("target_id",dataRemote); manual.putObject("attributes").put("manual","keep");
        control(json.createObjectNode().set("relation",manual)); assertEquals("EXISTING_RELATION_REQUIRES_EXPLICIT_LINK",run(a).data().path("code").asText()); assertEquals(0,control(null).path("writes").asInt());
        var r=publication.status(a,"fixture",publisher).resources().stream().filter(v -> v.entityId().startsWith("rel:derived:")).findFirst().orElseThrow(); var inspect=publication.inspect(a,"fixture",publisher,r.canonicalId(),"manual");
        publication.adopt(a,"fixture",publisher,new Adoption(r.canonicalId(),"manual",inspect.remoteDigest())); synced(a); assertEquals("keep",relation().path("attributes").path("manual").asText());
        String b=model("WRITE","client-b"); models.updateModelAccessControl(a,Set.of("new-owner"),Set.of("publisher"),Set.of());
        assertEquals("PAUSED",run(b).data().path("state").asText()); assertEquals(json.readTree("[\"READ\"]"),relation().path("attributes").path("operations"));
    }
}
