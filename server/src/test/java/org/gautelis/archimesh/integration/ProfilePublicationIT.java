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
@TestProfile(ProfilePublicationIT.Profile.class)
@QuarkusTestResource(value=ProfileRecoveryTestResource.class,restrictToAnnotatedClass=true)
class ProfilePublicationIT {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() { return Map.of("app.authz.enabled","true","app.identity.mode","bootstrap","app.tags.allow-delete","true"); }
    }
    @Inject ArchitecturePublicationService publication;
    @Inject PublicationProfiles profiles;
    @Inject PublicationProfileAdministration administration;
    @Inject IntegrationOperationsService operations;
    @io.quarkus.test.common.http.TestHTTPResource URI base;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.operations.destination",defaultValue="") String destination;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.operations.password",defaultValue="") String destinationPassword;
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
        assertEquals(snapshot,models.getSnapshot(a),"Publication metadata must not change the accepted model snapshot");
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
        var source=(ArchitecturePublicationService)io.quarkus.arc.ClientProxy.unwrap(publication); var restarted=new ArchitecturePublicationService();
        restarted.runtime=runtime; restarted.models=models; restarted.authorization=source.authorization; restarted.delivery=source.delivery; restarted.json=json; restarted.profiles=profiles; restarted.catalogProjections=source.catalogProjections;
        var retry=publication.enqueue(a,"fixture",publisher,new RunRequest(UUID.randomUUID().toString(),null)); restarted.runJob(retry.id());
        assertEquals("AMBIGUOUS",runtime.metadata().load(scope,DocumentKind.PUBLICATION_JOB,retry.id()).orElseThrow().data().path("state").asText()); assertEquals(1,control(null).path("writes").asInt());
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
    @Test void administrationIsReadOnlyVersionedAndDoesNotRevealOtherModelsEvidence() throws Exception {
        String a=model("READ","client-a"),b=model("WRITE","client-b");
        var spec=publication.policy(a,"fixture",publisher).policy(); var before=runtime.metadata().archive(scope);
        assertEquals(1,administration.available(a,"fixture",publisher).path("schemaVersion").asInt());
        assertTrue(administration.validate(a,"fixture",publisher,spec.profile()).path("valid").asBoolean());
        var explanation=administration.explain(a,"fixture",publisher,spec);
        assertEquals(1,explanation.path("counts").path("derived").asInt()); assertEquals(2,explanation.path("counts").path("existingBindings").asInt());
        assertEquals(before,runtime.metadata().archive(scope)); assertEquals(0,control(null).path("writes").asInt());
        var request=HttpRequest.newBuilder(base.resolve("admin/models/"+a+"/publication/fixture/profiles/v1")).header("X-Archimesh-User","outsider").header("X-Archimesh-Roles","admin").GET().build();
        assertEquals(403,http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        request=HttpRequest.newBuilder(base.resolve("admin/models/"+a+"/publication/fixture/profiles/v1")).header("X-Archimesh-User","publisher").GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString()); assertEquals(200,response.statusCode(),response.body()); assertEquals(1,json.readTree(response.body()).path("schemaVersion").asInt());
        synced(a); synced(b); explanation=administration.explain(a,"fixture",publisher,spec);
        assertEquals(1,explanation.path("contributions").size()); assertFalse(explanation.toString().contains(b));
        access(a,"UNKNOWN",100); var withdrawn=administration.explain(a,"fixture",publisher,publication.policy(a,"fixture",publisher).policy());
        assertEquals(1,withdrawn.path("withdrawals").size()); assertEquals("WITHDRAW_OWN_CONTRIBUTION",withdrawn.path("withdrawals").get(0).path("action").asText());
        synced(a); assertTrue(administration.explain(a,"fixture",publisher,publication.policy(a,"fixture",publisher).policy()).path("contributions").toString().contains("WITHDRAWN"));
    }
    @Test void versionChangesRequireEveryContributorToBeExplicitlyReviewed() throws Exception {
        String a=model("READ","client-a"),b=model("WRITE","client-b"); synced(a); synced(b); String id=relation().path("id").asText();
        var version=profiles.available().stream().filter(p -> p.version().equals("2.0.0")).findFirst().orElseThrow().pin(json);
        for(String current:List.of(a,b)) {
            var old=publication.policy(current,"fixture",publisher); var spec=old.policy();
            var upgrade=new PolicySpec(spec.mode(),spec.ref(),spec.elements(),spec.relationships(),spec.stewards(),version);
            var review=administration.explain(current,"fixture",publisher,upgrade); assertNotEquals(review.path("previousProfile"),review.path("proposedProfile"));
            publication.approve(current,"fixture",publisher,new Approval(upgrade,old.version(),review.path("preview").path("digest").asText()));
            var job=run(current);
            assertEquals(current.equals(a) ? "ASSERTION_PROFILE_MIGRATION_REQUIRES_REVIEW" : "",job.data().path("code").asText());
        }
        synced(a); assertEquals(id,relation().path("id").asText()); assertEquals(3,control(null).path("writes").asInt());
    }
    @Test void disabledFeatureAndNewHeadCannotActivateUnapprovedPublication() throws Exception {
        String a=model("READ","client-a"); var job=publication.enqueue(a,"fixture",publisher,new RunRequest(UUID.randomUUID().toString(),null));
        var bean=(PublicationProfiles)io.quarkus.arc.ClientProxy.unwrap(profiles); bean.enabled=false;
        try { publication.runJob(job.id()); assertEquals("PAUSED",runtime.metadata().load(scope,DocumentKind.PUBLICATION_JOB,job.id()).orElseThrow().data().path("state").asText()); assertEquals(0,control(null).path("writes").asInt()); }
        finally { bean.enabled=true; }
        var ops=json.createArrayNode(); var property=ops.addObject().put("type","SetProperty").put("targetId","rel:r").put("key","archimesh.publication.accessMode").put("value","WRITE");
        property.putObject("causal").put("clientId","new-head").put("lamport",100).put("opId",UUID.randomUUID().toString()); commit(a,"new-head",ops);
        synced(a); assertEquals(json.readTree("[\"READ\"]"),relation().path("attributes").path("operations"));
    }
    @Test void portableArchiveValidatesPinsAndRestoresClaimsQuarantinedWithAmbiguityIntact() throws Exception {
        String a=model("READ","client-a"),b=model("WRITE","client-b"); synced(a);
        control(json.createObjectNode().put("fault","relation-patch-commit-503")); assertEquals("AMBIGUOUS",run(b).data().path("state").asText());
        var operator=new AuthorizationSubject("operator",Set.of("admin")); operations.pause(operator,operations.overview(operator).path("controlVersion").asLong());
        try {
            var archive=operations.exportArchive(operator); var nodes=archive.nodes();
            assertTrue(nodes.stream().anyMatch(n -> n.label().equals("IntegrationStateDocument") && n.properties().get("kind").equals("PUBLICATION_PROFILE")));
            var missing=nodes.stream().filter(n -> !n.label().equals("IntegrationStateDocument") || !n.properties().get("kind").equals("PUBLICATION_PROFILE")).toList();
            assertEquals("ARCHIVE_PUBLICATION_PROFILE_PIN_MISSING_OR_CHANGED",assertThrows(Failure.class,() -> IntegrationArchive.validate(scope,missing,json)).getMessage());
            assertThrows(Failure.class,() -> IntegrationArchive.validate(IntegrationArchive.LEGACY_FORMAT,scope,nodes,json));
            var snapshot=models.getSnapshot(a);
            try(var driver=org.neo4j.driver.GraphDatabase.driver(destination,org.neo4j.driver.AuthTokens.basic("neo4j",destinationPassword))) {
                var repository=new Neo4jIntegrationStateRepository(driver,json,false); repository.installSchema();
                repository.save(new StateDocument(scope,DocumentKind.OPERATIONS,"control",0,json.createObjectNode().put("paused",true)));
                repository.restoreArchive(scope,nodes);
                assertTrue(repository.list(scope,DocumentKind.PUBLICATION_POLICY,"","",100).stream().allMatch(p -> p.data().path("policy").path("mode").asText().equals("DISABLED")));
                assertTrue(repository.list(scope,DocumentKind.ASSERTION_CLAIM,"","",100).stream().filter(c -> Set.of(a,b).contains(c.data().path("sourceModelId").asText())).allMatch(c -> c.data().path("status").asText().equals("QUARANTINED")));
                assertTrue(repository.list(scope,DocumentKind.PUBLICATION_RESOURCE,"","",100).stream().anyMatch(r -> r.data().has("inFlight")));
                assertFalse(repository.list(scope,DocumentKind.PUBLICATION_PROFILE,"","",100).isEmpty());
                IntegrationArchive.validate(scope,repository.archive(scope),json);
            }
            assertEquals(snapshot,models.getSnapshot(a)); assertEquals(3,control(null).path("writes").asInt());
        } finally {
            var review=operations.probe(operator); operations.resume(operator,new IntegrationOperationsService.Decision(operations.overview(operator).path("controlVersion").asLong(),review.digest()));
        }
    }

}
