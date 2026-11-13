package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.gautelis.archimesh.auth.*;
import org.gautelis.archimesh.model.Actor;
import org.gautelis.archimesh.service.ArchimeshService;
import org.gautelis.archimesh.wire.inbound.SubmitOpsMessage;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.PublicationModel.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;

@QuarkusTest
@TestProfile(ArchitecturePublicationIT.Profile.class)
@QuarkusTestResource(value=PublicationTestResource.class,restrictToAnnotatedClass=true)
class ArchitecturePublicationIT {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() { return Map.of("app.authz.enabled","true","app.identity.mode","bootstrap","app.tags.allow-delete","true"); }
    }
    @Inject ArchitecturePublicationService publication;
    @Inject ArchimeshService models;
    @Inject IntegrationRuntime runtime;
    @TestHTTPResource URI base;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.publication.url") String remote;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private final AuthorizationSubject publisher=new AuthorizationSubject("publisher",Set.of("model_writer"));
    private String model;
    private final List<String> otherModels=new ArrayList<>();
    private PolicySpec spec;
    @BeforeEach void prepare() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        control(json.createObjectNode().put("reset",true));
        model=runtime.settings().instanceId()+"-"+UUID.randomUUID(); models.registerModel(model,"Publication fixture","publisher");
        models.updateModelAccessControl(model,Set.of("publisher"),Set.of("writer"),Set.of("reader"));
        var ops=json.createArrayNode();
        ops.addObject().put("type","CreateElement").putObject("element").put("id","elem:a").put("archimateType","ApplicationComponent").put("name","A");
        ops.addObject().put("type","CreateElement").putObject("element").put("id","elem:b").put("archimateType","ApplicationComponent").put("name","B");
        ops.addObject().put("type","CreateRelationship").putObject("relationship").put("id","rel:r").put("archimateType","ServingRelationship").put("sourceId","elem:a").put("targetId","elem:b");
        commit(ops);
        spec=new PolicySpec(Mode.HEAD,"HEAD",List.of("elem:a","elem:b"),List.of("rel:r"),Set.of("elem:a","elem:b","rel:r")); approve(spec);
    }
    @AfterEach void cleanup() {
        if (model==null) return; models.deleteModel(model,true); for (String other:otherModels) models.deleteModel(other,true);
        try (var driver=GraphDatabase.driver(System.getenv().getOrDefault("NEO4J_URI","bolt://localhost:7687"),AuthTokens.basic(System.getenv().getOrDefault("NEO4J_USER","neo4j"),System.getenv().getOrDefault("NEO4J_PASSWORD","devpassword")));var session=driver.session()) {
            session.executeWrite(tx -> {
                Map<String,Object> params=Map.of("scope",runtime.settings().instanceId());
                var ids=tx.run("MATCH (n:IntegrationPublicationIdentity {instanceId:$scope}) RETURN n.canonicalId AS id",params).list(r -> r.get("id").asString());
                tx.run("MATCH (n) WHERE (n:IntegrationModelBinding OR n:IntegrationPublicationIdentity OR n:IntegrationIdentityClaim OR n:IntegrationStateDocument) AND n.instanceId=$scope DETACH DELETE n",params).consume();
                tx.run("MATCH (n:IntegrationExternalBinding {targetInstanceId:$scope}) DETACH DELETE n",params).consume();
                tx.run("MATCH (n:IntegrationCanonicalObject) WHERE n.canonicalId IN $ids DETACH DELETE n",Map.of("ids",ids)).consume();return null;
            });
        }
    }
    private JsonNode control(ObjectNode body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(remote+"/__fixture"));
        if (body==null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return json.readTree(http.send(request.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    private void commit(ArrayNode ops) { models.onSubmitOps(model,new SubmitOpsMessage(models.getSnapshot(model).path("headRevision").asLong(),UUID.randomUUID().toString(),new Actor("publisher","fixture"),ops)); }
    private void rename(String name,long lamport) {
        var ops=json.createArrayNode(); var update=ops.addObject().put("type","UpdateElement").put("elementId","elem:a"); update.putObject("patch").put("name",name);
        update.putObject("causal").put("clientId","fixture").put("lamport",lamport).put("opId",UUID.randomUUID().toString()); commit(ops);
    }
    private void approve(PolicySpec policy) {
        if (policy.mode()==Mode.HEAD) {
            String tag="release-"+UUID.randomUUID(); models.createModelTag(model,tag,"Reviewed test release");
            policy=new PolicySpec(Mode.TAG,tag,policy.elements(),policy.relationships(),policy.stewards());
        }
        var preview=publication.preview(model,"fixture",publisher,policy);
        publication.approve(model,"fixture",publisher,new Approval(policy,publication.policy(model,"fixture",publisher).version(),preview.digest())); spec=policy;
    }
    private StateDocument enqueue() { return publication.enqueue(model,"fixture",publisher,new RunRequest(UUID.randomUUID().toString(),null)); }
    private StateDocument execute() { var job=enqueue(); publication.runJob(job.id()); return runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.PUBLICATION_JOB,job.id()).orElseThrow(); }
    private String state(StateDocument job) { return job.data().path("state").asText(); }
    private ArchitecturePublicationService.ResourceView resource(String entity) { return publication.status(model,"fixture",publisher).resources().stream().filter(r -> r.entityId().equals(entity)).findFirst().orElseThrow(); }
    private void assertSynced(StateDocument job) { assertEquals("SYNCED",state(job),job.data().toString()); }

    @Test void publishesSemanticEndpointsBeforeRelationAndRepeatedJobsDoNotWrite() throws Exception {
        var snapshot=models.getSnapshot(model); var job=execute(); assertSynced(job);
        assertEquals(3,control(null).path("writes").asInt()); assertEquals(1,control(null).path("relations").size());
        assertSynced(execute()); assertEquals(3,control(null).path("writes").asInt()); assertEquals(snapshot,models.getSnapshot(model));
        var request=new RunRequest(UUID.randomUUID().toString(),null); var first=publication.enqueue(model,"fixture",publisher,request);
        var repeated=publication.enqueue(model,"fixture",publisher,request); assertEquals(first.id(),repeated.id()); assertEquals(first.version(),repeated.version()); assertTrue(PublicationJson.equal(first.data(),repeated.data()));
        models.createModelCheckpoint(model); models.rebuildMaterializedState(model); assertEquals(snapshot,models.getSnapshot(model));
        assertNotNull(runtime.metadata().findPublicationIdentity(new IntegrationIdentity.ModelElementKey(runtime.settings().instanceId(),model,IntegrationIdentity.EntityKind.ELEMENT,"elem:a")).orElseThrow());
    }
    @Test void projectsLwwWinnerPreservesUnownedAttributesAndIgnoresLosingPatch() throws Exception {
        assertSynced(execute()); String id=resource("elem:a").resourceId();
        control(json.createObjectNode().put("id",id).set("fields",json.createObjectNode().set("attributes",json.createObjectNode().put("central","Centrally managed"))));
        rename("Winner",100); approve(new PolicySpec(Mode.HEAD,"HEAD",spec.elements(),spec.relationships(),spec.stewards())); assertSynced(execute()); assertEquals(4,control(null).path("writes").asInt());
        assertEquals("Centrally managed",control(null).path("cards").path(id).path("attributes").path("central").asText());
        rename("Loser",99); assertSynced(execute()); assertEquals(4,control(null).path("writes").asInt());
        assertEquals("Winner",control(null).path("cards").path(id).path("name").asText());
    }
    @Test void remoteDriftRequiresReviewedBaselineAndNeverSilentlyOverwrites() throws Exception {
        assertSynced(execute()); var r=resource("elem:a");
        control(json.createObjectNode().put("id",r.resourceId()).set("fields",json.createObjectNode().put("name","Remote edit")));
        var job=execute(); assertEquals("CONFLICT",state(job)); assertEquals("REMOTE_DRIFT",job.data().path("code").asText()); assertEquals(3,control(null).path("writes").asInt());
        var inspected=publication.inspect(model,"fixture",publisher,r.canonicalId(),null);
        publication.adopt(model,"fixture",publisher,new Adoption(r.canonicalId(),r.resourceId(),inspected.remoteDigest()));
        assertSynced(execute()); assertEquals(4,control(null).path("writes").asInt());
    }
    @Test void committedPostWithLostReplyFreezesAcrossJobsUntilIdentityIsVerified() throws Exception {
        control(json.createObjectNode().put("fault","commit-503")); var job=execute(); assertEquals("AMBIGUOUS",state(job));
        assertEquals(1,control(null).path("writes").asInt()); var source=(ArchitecturePublicationService)io.quarkus.arc.ClientProxy.unwrap(publication);
        var restarted=new ArchitecturePublicationService(); restarted.runtime=runtime; restarted.models=models; restarted.authorization=source.authorization; restarted.delivery=source.delivery; restarted.json=json;
        var retry=enqueue(); restarted.runJob(retry.id());
        assertEquals("AMBIGUOUS",runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.PUBLICATION_JOB,retry.id()).orElseThrow().data().path("state").asText()); assertEquals(1,control(null).path("writes").asInt());
        var r=resource("elem:a"); String id=control(null).path("cards").fieldNames().next();
        var inspected=publication.inspect(model,"fixture",publisher,r.canonicalId(),id);
        publication.adopt(model,"fixture",publisher,new Adoption(r.canonicalId(),id,inspected.remoteDigest()));
        assertSynced(execute()); assertEquals(3,control(null).path("writes").asInt());
    }
    @Test void tagIsImmutableAndHeadPublicationIsDenied() throws Exception {
        var head=new PolicySpec(Mode.HEAD,"HEAD",spec.elements(),spec.relationships(),spec.stewards());
        assertEquals("PUBLICATION_TAG_REQUIRED",assertThrows(Failure.class,() -> publication.preview(model,"fixture",publisher,head)).getMessage());
        var pending=enqueue(); rename("Later HEAD",100); publication.runJob(pending.id());
        assertEquals("SYNCED",runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.PUBLICATION_JOB,pending.id()).orElseThrow().data().path("state").asText());
        assertEquals("A",control(null).path("cards").path(resource("elem:a").resourceId()).path("name").asText());
        models.createModelTag(model,"approved","Reviewed architecture");
        approve(new PolicySpec(Mode.TAG,"approved",spec.elements(),spec.relationships(),spec.stewards()));
        var tag=models.getSnapshot(model,"approved"); rename("Unapproved",101); assertSynced(execute());
        assertEquals("Later HEAD",control(null).path("cards").path(resource("elem:a").resourceId()).path("name").asText()); assertEquals(tag,models.getSnapshot(model,"approved"));
    }
    @Test void queuedLegacyHeadPolicyCannotDispatch() throws Exception {
        var job=enqueue();
        var policy=runtime.metadata().list(runtime.settings().instanceId(),DocumentKind.PUBLICATION_POLICY,"","",100).stream()
            .filter(d -> d.data().path("sourceModelId").asText().equals(model)).findFirst().orElseThrow();
        var data=policy.data().deepCopy(); ((ObjectNode)data.path("policy")).put("mode","HEAD").put("ref","HEAD");
        runtime.metadata().save(new StateDocument(runtime.settings().instanceId(),policy.kind(),policy.id(),policy.version(),data));
        publication.runJob(job.id());
        assertEquals("SUPERSEDED",runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.PUBLICATION_JOB,job.id()).orElseThrow().data().path("state").asText());
        assertEquals(0,control(null).path("writes").asInt());
    }
    @Test void reusingDeletedTagNameRequiresNewApproval() throws Exception {
        var approved=spec; models.deleteModelTag(model,approved.ref()); rename("Unreviewed replacement",100);
        models.createModelTag(model,approved.ref(),"Reused label");
        assertEquals("PUBLICATION_TAG_APPROVAL_STALE",assertThrows(Failure.class,this::enqueue).getMessage());
        assertEquals(0,control(null).path("writes").asInt());
        approve(approved); assertSynced(execute());
    }
    @Test void withdrawingSelectionRetainsExternalResourcesAndOrphansAreNotRecreated() throws Exception {
        assertSynced(execute()); String id=resource("elem:a").resourceId();
        approve(new PolicySpec(Mode.HEAD,"HEAD",List.of("elem:a"),List.of(),Set.of("elem:a"))); assertSynced(execute());
        assertEquals(2,control(null).path("cards").size()); assertEquals(1,control(null).path("relations").size());
        var contributions=runtime.metadata().list(runtime.settings().instanceId(),DocumentKind.CONTRIBUTION,"","",100);
        assertEquals(2,contributions.stream().filter(d -> d.data().path("status").asText().equals("WITHDRAWN")).count());
        control(json.createObjectNode().put("id",id).put("delete",true)); var job=execute(); assertEquals("CONFLICT",state(job)); assertEquals("ORPHANED",resource("elem:a").state()); assertEquals(3,control(null).path("writes").asInt());
    }
    @Test void authorizationRevocationPausesQueuedJobAndOutageDoesNotBlockModelCommit() throws Exception {
        var pending=enqueue(); models.updateModelAccessControl(model,Set.of("owner"),Set.of("publisher"),Set.of()); publication.runJob(pending.id());
        assertEquals("PAUSED",runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.PUBLICATION_JOB,pending.id()).orElseThrow().data().path("state").asText()); assertEquals(0,control(null).path("writes").asInt());
        control(json.createObjectNode().put("fault","offline")); rename("Offline edit",100); assertEquals("Offline edit",models.getSnapshot(model).path("elements").get(0).path("name").asText());
        var denied=assertThrows(Failure.class,() -> publication.preview(model,"fixture",new AuthorizationSubject("owner",Set.of("admin")),spec)); assertEquals(403,denied.status());
    }
    @Test void boundedRetriesAndUnchangedProjectionPeriodicDriftChecks() throws Exception {
        control(json.createObjectNode().put("fault","rate-limit")); var job=enqueue(); long start=System.currentTimeMillis(); ((ArchitecturePublicationService)io.quarkus.arc.ClientProxy.unwrap(publication)).now=() -> start;
        try {
            for (int attempt=0;attempt<4;attempt++) { final long time=start+attempt*100000; ((ArchitecturePublicationService)io.quarkus.arc.ClientProxy.unwrap(publication)).now=() -> time; publication.runJob(job.id()); }
            assertEquals("ERROR",runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.PUBLICATION_JOB,job.id()).orElseThrow().data().path("state").asText()); assertEquals(0,control(null).path("writes").asInt());
            control(json.createObjectNode().put("fault","")); assertSynced(execute());
            var policy=runtime.metadata().list(runtime.settings().instanceId(),DocumentKind.PUBLICATION_POLICY,"","",10).getFirst();
            int before=runtime.metadata().list(runtime.settings().instanceId(),DocumentKind.PUBLICATION_JOB,"","",100).size(); publication.schedulePolicy(policy);
            assertEquals(before,runtime.metadata().list(runtime.settings().instanceId(),DocumentKind.PUBLICATION_JOB,"","",100).size());
            ((ArchitecturePublicationService)io.quarkus.arc.ClientProxy.unwrap(publication)).now=() -> start+1000000; publication.schedulePolicy(policy);
            assertEquals(before+1,runtime.metadata().list(runtime.settings().instanceId(),DocumentKind.PUBLICATION_JOB,"","",100).size());
        } finally { ((ArchitecturePublicationService)io.quarkus.arc.ClientProxy.unwrap(publication)).now=System::currentTimeMillis; }
    }
    @Test void manuallyManagedRelationRequiresExplicitLinkAndKeepsItsAttributes() throws Exception {
        var full=spec; approve(new PolicySpec(Mode.HEAD,"HEAD",spec.elements(),List.of(),Set.of("elem:a","elem:b"))); assertSynced(execute());
        String a=resource("elem:a").resourceId(),b=resource("elem:b").resourceId();
        var relation=json.createObjectNode().put("id","manual-relation").put("type","Serves").put("source_id",a).put("target_id",b).put("description","Manual description"); relation.putObject("attributes").put("manual","retain");
        control(json.createObjectNode().set("relation",relation)); approve(full); var job=execute();
        assertEquals("CONFLICT",state(job)); assertEquals("EXISTING_RELATION_REQUIRES_EXPLICIT_LINK",job.data().path("code").asText());
        var r=resource("rel:r"); var inspected=publication.inspect(model,"fixture",publisher,r.canonicalId(),"manual-relation");
        publication.adopt(model,"fixture",publisher,new Adoption(r.canonicalId(),"manual-relation",inspected.remoteDigest()));
        assertSynced(execute()); assertEquals(2,control(null).path("writes").asInt()); assertEquals(relation,control(null).path("relations").path("manual-relation"));
    }
    @Test void sharedCanonicalContributorsReuseResourceAndCannotStealStewardship() throws Exception {
        assertSynced(execute()); var canonical=resource("elem:a").canonicalId(); var original=model;
        model=runtime.settings().instanceId()+"-other-"+UUID.randomUUID(); otherModels.add(model); models.registerModel(model,"Other occurrence","publisher"); runtime.modelScope(model);
        try {
            var ops=json.createArrayNode(); ops.addObject().put("type","CreateElement").putObject("element").put("id","elem:a").put("archimateType","ApplicationComponent").put("name","A"); commit(ops);
            var key=new IntegrationIdentity.ModelElementKey(runtime.settings().instanceId(),model,IntegrationIdentity.EntityKind.ELEMENT,"elem:a");
            runtime.metadata().reservePublicationIdentity(new PublicationIdentity(key,new IntegrationIdentity.CanonicalObject(canonical,"Application"),"ApplicationComponent","m3-fixture-v1"));
            approve(new PolicySpec(Mode.HEAD,"HEAD",List.of("elem:a"),List.of(),Set.of())); assertSynced(execute()); assertEquals(3,control(null).path("writes").asInt());
            assertEquals(canonical,runtime.metadata().findBinding(key).orElseThrow().canonical().canonicalId());
            approve(new PolicySpec(Mode.HEAD,"HEAD",List.of("elem:a"),List.of(),Set.of("elem:a"))); var job=execute();
            assertEquals("CONFLICT",state(job)); assertEquals("CANONICAL_STEWARD_CONFLICT",job.data().path("code").asText()); assertEquals(3,control(null).path("writes").asInt());
        } finally { model=original; }
    }
    @Test void durablePlansAndBaselinesKeepExactDecimalValues() {
        var document=json.createObjectNode().set("value",com.fasterxml.jackson.databind.node.DecimalNode.valueOf(new java.math.BigDecimal("1.12345678901234567890123456789")));
        var saved=runtime.metadata().save(new StateDocument(runtime.settings().instanceId(),DocumentKind.CHECKPOINT,"decimal-fixture",0,document));
        assertTrue(PublicationJson.equal(saved.data(),runtime.metadata().load(saved.instanceId(),saved.kind(),saved.id()).orElseThrow().data()));
        assertTrue(PublicationJson.equal(saved.data(),runtime.metadata().list(saved.instanceId(),saved.kind(),"decimal-fixture","",1).getFirst().data()));
    }
    @Test void restRequiresExplicitModelAdminAndPolicyApprovalIsCompareAndSet() throws Exception {
        var request=HttpRequest.newBuilder(base.resolve("admin/models/"+model+"/publication/fixture")).header("X-Archimesh-User","writer").header("X-Archimesh-Roles","admin").GET().build();
        assertEquals(403,http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        var preview=publication.preview(model,"fixture",publisher,spec);
        assertThrows(IllegalStateException.class,() -> publication.approve(model,"fixture",publisher,new Approval(spec,0,preview.digest())));
        var pending=enqueue(); approve(new PolicySpec(Mode.DISABLED,"HEAD",List.of(),List.of(),Set.of())); publication.runJob(pending.id());
        assertEquals("SUPERSEDED",runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.PUBLICATION_JOB,pending.id()).orElseThrow().data().path("state").asText());
    }
}
