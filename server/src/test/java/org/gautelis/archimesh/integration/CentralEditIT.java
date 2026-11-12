package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.gautelis.archimesh.auth.*;
import org.gautelis.archimesh.model.*;
import org.gautelis.archimesh.service.ArchimeshService;
import org.gautelis.archimesh.wire.inbound.SubmitOpsMessage;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.CentralEditModel.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;

@QuarkusTest
@TestProfile(CentralEditIT.Profile.class)
@QuarkusTestResource(value=CentralEditTestResource.class,restrictToAnnotatedClass=true)
class CentralEditIT {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() { return Map.of("app.authz.enabled","true","app.identity.mode","bootstrap"); }
    }
    @Inject CentralEditService edits;
    @Inject ExternalChangeIngestService inbound;
    @Inject ExternalObjectCatalogService catalog;
    @Inject ArchitecturePublicationService publication;
    @Inject ArchimeshService models;
    @Inject IntegrationRuntime runtime;
    @Inject CentralProposalGrant grants;
    @TestHTTPResource URI base;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.publication.url") String remote;
    private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final HttpClient http=HttpClient.newHttpClient();
    private final AuthorizationSubject architect=new AuthorizationSubject("architect",Set.of("model_writer"));
    private final AuthorizationSubject admin=new AuthorizationSubject("publisher",Set.of("model_writer"));
    private final AuthorizationSubject resolver=new AuthorizationSubject("resolver",Set.of("model_writer"));
    private final List<String> owned=new ArrayList<>();
    private String model;
    private ExternalObjectCatalogService.ImportResult imported;
    @BeforeEach void prepare() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        control(json.createObjectNode().put("reset",true));
        var card=json.createObjectNode().put("id","card-a").put("type","Application").put("name","Initial name").put("status","ACTIVE");
        card.putObject("attributes").put("central","Initial central").put("shared","Initial shared").put("owner","Keep this unowned value").put("budget",42);
        control(json.createObjectNode().set("card",card)); model=provision(); imported=importCard(model); approveIncoming(model); inbound.refresh(model,"fixture",admin,imported.elementId());
    }
    private String provision() { String id=runtime.settings().instanceId()+"-model-"+UUID.randomUUID(); owned.add(id); models.registerModel(id,"Central edit fixture","publisher"); models.updateModelAccessControl(id,Set.of("publisher"),Set.of("architect"),Set.of("reader")); return id; }
    private ExternalObjectCatalogService.ImportResult importCard(String model) { var preview=catalog.preview(model,"HEAD",architect,"fixture","card-a"); return catalog.importObject(model,architect,new ExternalObjectCatalogService.ImportRequest(UUID.randomUUID().toString(),"fixture","card-a","HEAD",ExternalObjectCatalogService.ImportMode.CREATE,null,preview.digest())); }
    private void approveIncoming(String model) { approveIncoming(model,admin); }
    private void approveIncoming(String model,AuthorizationSubject actor) { var status=inbound.status(model,"fixture",actor,""); inbound.approve(model,"fixture",actor,new ExternalChangeIngestService.Approval(true,status.policyVersion(),status.targetInstanceId(),status.mappingVersion())); }
    @AfterEach void cleanup() throws Exception {
        if (model==null) return; control(json.createObjectNode().put("fault","")); for (String id:owned) models.deleteModel(id,true);
        try (var driver=GraphDatabase.driver(System.getenv().getOrDefault("NEO4J_URI","bolt://localhost:7687"),AuthTokens.basic(System.getenv().getOrDefault("NEO4J_USER","neo4j"),System.getenv().getOrDefault("NEO4J_PASSWORD","devpassword"))); var session=driver.session()) {
            session.executeWrite(tx -> { Map<String,Object> p=Map.of("scope",runtime.settings().instanceId()); var ids=tx.run("MATCH (n:IntegrationExternalBinding {targetInstanceId:$scope}) RETURN n.canonicalId AS id",p).list(r -> r.get("id").asString());
                tx.run("MATCH (n) WHERE (n:IntegrationModelBinding OR n:IntegrationPublicationIdentity OR n:IntegrationIdentityClaim OR n:IntegrationStateDocument) AND n.instanceId=$scope DETACH DELETE n",p).consume();
                tx.run("MATCH (n:IntegrationExternalBinding {targetInstanceId:$scope}) DETACH DELETE n",p).consume(); tx.run("MATCH (n:IntegrationCanonicalObject) WHERE n.canonicalId IN $ids DETACH DELETE n",Map.of("ids",ids)).consume(); return null; });
        }
    }
    private JsonNode control(ObjectNode body) throws Exception { var request=HttpRequest.newBuilder(URI.create(remote+"/__fixture")); if (body==null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(body.toString())); return json.readTree(http.send(request.build(),HttpResponse.BodyHandlers.ofString()).body()); }
    private int writes() throws Exception { return control(null).path("writes").asInt(); }
    private HttpResponse<String> api(String method,String scope,String path,JsonNode body,String user) throws Exception {
        var request=HttpRequest.newBuilder(base.resolve("models/"+scope+"/central-edits/fixture/"+path)).header("X-Archimesh-User",user).header("X-Archimesh-Roles","model_writer").header("Content-Type","application/json");
        request.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString())); return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode remoteCard() throws Exception { return control(null).path("cards").path("card-a"); }
    private Job request(String field,String value) { var proposed=Map.<String,JsonNode>of(field,json.getNodeFactory().textNode(value)); var preview=edits.preview(model,"fixture",architect,new PreviewRequest("HEAD",imported.elementId(),proposed)); return edits.submit(model,"fixture",architect,new Request(UUID.randomUUID().toString(),"HEAD",imported.elementId(),proposed,preview.digest())); }
    private Job approve(Job job,boolean resolve) { var review=edits.review(model,"fixture",job.id(),admin); return edits.decide(model,"fixture",job.id(),admin,new Decision(job.version(),true,review.digest(),resolve)); }
    private Job get(Job job) { return edits.get(model,"fixture",job.id(),architect); }
    private Job drain(Job job) { for (int i=0;i<30;i++) { job=get(job); if (!Set.of("PENDING","RETRY","IN_FLIGHT","REMOTE_VERIFIED","SYNCING").contains(job.state())) break; edits.run(job.id()); } return get(job); }
    private String local(String model,String element,String field) { return InboundFields.local(json,models.getSnapshot(model),element,Set.of(field)).path(field).asText(null); }
    private void property(String model,String element,String key,String value) { var ops=json.createArrayNode(); ops.addObject().put("type","SetProperty").put("targetId",element).put("key",key).put("value",value); models.onSubmitOps(model,new SubmitOpsMessage(models.getSnapshot(model).path("headRevision").asLong(),UUID.randomUUID().toString(),new Actor("architect","fixture"),ops)); }

    @Test void writeThroughPreservesUnownedAttributesAndFansOutWithoutChangingTags() throws Exception {
        String other=provision(); var second=importCard(other); approveIncoming(other); models.createModelTag(model,"approved","Before central request"); var tag=models.getSnapshot(model,"approved");
        var job=request("property:central","Explicit central update"); assertEquals("PENDING",job.state()); assertEquals(0,writes());
        job=drain(job); assertEquals("SYNCED",job.state(),job.toString()); assertEquals(2,job.synced()); assertTrue(job.remoteVerified()); assertEquals(1,writes());
        assertEquals("Explicit central update",local(model,imported.elementId(),"property:central")); assertEquals("Explicit central update",local(other,second.elementId(),"property:central"));
        assertEquals("Keep this unowned value",remoteCard().path("attributes").path("owner").asText()); assertEquals("Initial shared",remoteCard().path("attributes").path("shared").asText()); assertEquals(tag,models.getSnapshot(model,"approved"));
        long revision=models.getSnapshot(model).path("headRevision").asLong(); edits.run(job.id()); assertEquals(1,writes()); assertEquals(revision,models.getSnapshot(model).path("headRevision").asLong());
    }
    @Test void proposalDoesNotWriteUntilApprovalAndPublicationDoesNotEchoCentralName() throws Exception {
        property(model,imported.elementId(),"owner","Keep this unowned value"); var job=request("name","Approved new name");
        assertEquals("WAITING_APPROVAL",job.state()); edits.run(job.id()); assertEquals(0,writes()); assertEquals("Initial name",local(model,imported.elementId(),"name"));
        job=drain(approve(job,false)); assertEquals("SYNCED",job.state(),job.toString()); assertEquals("Approved new name",local(model,imported.elementId(),"name"));
        var spec=new PublicationModel.PolicySpec(PublicationModel.Mode.HEAD,"HEAD",List.of(imported.elementId()),List.of(),Set.of(imported.elementId()));
        var preview=publication.preview(model,"fixture",admin,spec); assertFalse(preview.plan().resources().getFirst().fields().containsKey("name"));
        publication.approve(model,"fixture",admin,new PublicationModel.Approval(spec,0,preview.digest())); var outgoing=publication.enqueue(model,"fixture",admin,new PublicationModel.RunRequest(UUID.randomUUID().toString(),null)); publication.runJob(outgoing.id()); assertEquals(1,writes());
    }
    @Test void sharedProposalRequiresBaselineDecisionAndLocalConflictsHaveScopedResolution() throws Exception {
        String other=provision(); var second=importCard(other); models.updateModelAccessControl(other,Set.of("resolver"),Set.of("architect"),Set.of("reader")); approveIncoming(other,resolver);
        var configuration=(IntegrationRuntime)io.quarkus.arc.ClientProxy.unwrap(runtime); configuration.enabled=false;
        try { property(other,second.elementId(),"shared","Independent local intent"); } finally { configuration.enabled=true; }
        var job=request("property:shared","Approved shared value"); var initial=job; assertThrows(PublicationModel.Failure.class,() -> approve(initial,false)); assertEquals(0,writes());
        job=drain(approve(job,true)); assertEquals("PARTIAL",job.state(),job.toString()); assertEquals(1,job.synced()); assertEquals(1,job.conflicts());
        assertEquals("Approved shared value",local(model,imported.elementId(),"property:shared")); assertEquals("Independent local intent",local(other,second.elementId(),"property:shared"));
        assertEquals(List.of("fixture"),edits.targets(other,resolver)); assertEquals(Map.of("canRequest",false,"canApprove",false,"canResolve",true),edits.capabilities(other,"fixture",resolver));
        assertEquals(403,api("GET",model,"requests",null,"resolver").statusCode()); assertEquals(403,api("GET",other,"requests",null,"resolver").statusCode());
        var rows=edits.deliveries(other,"fixture",resolver,""); assertEquals(1,rows.size()); assertEquals("CONFLICT",rows.getFirst().path("state").asText());
        var review=edits.inspectDelivery(other,"fixture",resolver,second.elementId(),job.id()); edits.resolveDelivery(other,"fixture",resolver,second.elementId(),job.id(),review.digest());
        assertEquals("SYNCED",get(job).state()); assertEquals(2,get(job).synced()); assertEquals(0,get(job).conflicts()); assertEquals("Approved shared value",local(other,second.elementId(),"property:shared")); assertEquals(1,writes());
    }
    @Test void stalePreviewApprovalAndRemoteDriftCannotOverwriteAnotherCentralEdit() throws Exception {
        var proposed=Map.<String,JsonNode>of("name",json.getNodeFactory().textNode("Proposed")); var preview=edits.preview(model,"fixture",architect,new PreviewRequest("HEAD",imported.elementId(),proposed));
        control(json.createObjectNode().put("id","card-a").set("fields",json.createObjectNode().put("name","Manual central name")));
        assertThrows(PublicationModel.Failure.class,() -> edits.submit(model,"fixture",architect,new Request(UUID.randomUUID().toString(),"HEAD",imported.elementId(),proposed,preview.digest())));
        var job=request("name","Proposed"); var review=edits.review(model,"fixture",job.id(),admin);
        control(json.createObjectNode().put("id","card-a").set("fields",json.createObjectNode().put("name","Another manual name")));
        assertThrows(PublicationModel.Failure.class,() -> edits.decide(model,"fixture",job.id(),admin,new Decision(job.version(),true,review.digest(),true))); assertEquals(0,writes());
        inbound.refresh(model,"fixture",admin,imported.elementId()); var next=approve(request("name","Now approved"),false);
        control(json.createObjectNode().put("id","card-a").set("fields",json.createObjectNode().put("name","Newer manual name"))); next=drain(next); assertEquals("CONFLICT",next.state()); assertEquals("CENTRAL_REMOTE_DRIFT",next.code()); assertEquals(0,writes());
    }
    @Test void repeatedRequestsKeepIdentityDuringOutageAndRejectChangedPayload() throws Exception {
        var proposed=Map.<String,JsonNode>of("name",json.getNodeFactory().textNode("Proposed")); var preview=edits.preview(model,"fixture",architect,new PreviewRequest("HEAD",imported.elementId(),proposed)); var request=new Request(UUID.randomUUID().toString(),"HEAD",imported.elementId(),proposed,preview.digest());
        var job=edits.submit(model,"fixture",architect,request); control(json.createObjectNode().put("fault","offline")); assertEquals(job.id(),edits.submit(model,"fixture",architect,request).id());
        assertThrows(PublicationModel.Failure.class,() -> edits.submit(model,"fixture",architect,new Request(request.requestId(),"HEAD",imported.elementId(),Map.of("name",json.getNodeFactory().textNode("Changed")),preview.digest()))); assertEquals(0,writes());
    }
    @Test void committedPatchWithLostReplyIsNeverRepeatedAndFencesPublicationUntilVerified() throws Exception {
        var job=request("property:central","Uncertain update"); control(json.createObjectNode().put("fault","patch-commit-503")); edits.run(job.id()); job=get(job); assertEquals("AMBIGUOUS",job.state()); assertEquals(1,writes());
        var original=(CentralEditService)io.quarkus.arc.ClientProxy.unwrap(edits); var restarted=copy(original); restarted.run(job.id()); assertEquals(1,writes());
        assertThrows(PublicationModel.Failure.class,() -> request("property:central","Another request"));
        var gate=((ArchitecturePublicationService)io.quarkus.arc.ClientProxy.unwrap(publication)).execution;
        assertThrows(PublicationModel.Failure.class,() -> gate.requirePublicationAllowed(runtime,runtime.settings().targets().getFirst().instanceId(),imported.canonicalId()));
        job=edits.verifyOutcome(model,"fixture",job.id(),architect,new Outcome(job.version())); job=drain(job); assertEquals("SYNCED",job.state(),job.toString()); assertEquals(1,writes());
    }
    @Test void rateLimitDuringReadbackRemainsAmbiguousRatherThanReplayingPatch() throws Exception {
        var job=request("property:central","Verified later"); control(json.createObjectNode().put("fault","after-patch-rate-limit")); edits.run(job.id()); job=get(job); assertEquals("AMBIGUOUS",job.state()); assertEquals(1,writes());
        edits.run(job.id()); assertEquals(1,writes()); control(json.createObjectNode().put("fault","")); job=edits.verifyOutcome(model,"fixture",job.id(),architect,new Outcome(job.version())); assertEquals("SYNCED",drain(job).state()); assertEquals(1,writes());
    }
    @Test void safeReadRetriesAreBoundedAndDefinitiveWriteDenialDoesNotLeaveAnUncertaintyFence() throws Exception {
        var job=request("property:central","Pending"); control(json.createObjectNode().put("fault","rate-limit")); var service=(CentralEditService)io.quarkus.arc.ClientProxy.unwrap(edits); long start=System.currentTimeMillis();
        try { for (int i=0;i<4;i++) { final long t=start+i*100000; service.now=() -> t; edits.run(job.id()); } } finally { service.now=System::currentTimeMillis; }
        assertEquals("ERROR",get(job).state()); assertEquals(0,writes()); control(json.createObjectNode().put("fault","")); var denied=request("property:central","Denied"); control(json.createObjectNode().put("fault","forbid-patch")); denied=drain(denied); assertEquals("ERROR",denied.state()); assertFalse(denied.remoteVerified()); assertEquals(0,writes());
        control(json.createObjectNode().put("fault","")); assertEquals("SYNCED",drain(request("property:central","Allowed now")).state()); assertEquals(1,writes());
    }
    @Test void revokedRequesterAndMissingIncomingApprovalPreventRemoteWriteAndGlobalRolesDoNotBypassScope() throws Exception {
        var job=request("property:central","Revoked"); models.updateModelAccessControl(model,Set.of("publisher"),Set.of(),Set.of("architect")); edits.run(job.id());
        assertEquals("PAUSED",runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.CENTRAL_EDIT,job.id()).orElseThrow().data().path("state").asText()); assertEquals(0,writes());
        var request=HttpRequest.newBuilder(base.resolve("models/"+model+"/central-edits/fixture/context/"+imported.elementId())).header("X-Archimesh-User","outsider").header("X-Archimesh-Roles","admin").GET().build(); assertEquals(403,http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertThrows(IllegalArgumentException.class,() -> edits.preview(model,"fixture",architect,new PreviewRequest("approved",imported.elementId(),Map.of("name",json.getNodeFactory().textNode("Invalid ref")))));
    }
    @Test void newerQueuedIntentSupersedesOlderJobWithoutWritingItsOldValue() throws Exception {
        var first=request("property:central","First"); var second=request("property:central","Second"); edits.run(first.id()); assertEquals("SUPERSEDED",get(first).state()); assertEquals(0,writes());
        second=drain(second); assertEquals("SYNCED",second.state()); assertEquals("Second",local(model,imported.elementId(),"property:central")); edits.run(first.id()); assertEquals(1,writes());
    }
    @Test void pausedOccurrenceIsReportedAsPartialAndRetryFanoutMakesNoAdditionalRemoteWrite() throws Exception {
        String other=provision(); var second=importCard(other); var job=drain(request("property:central","Fanout value")); assertEquals("PARTIAL",job.state()); assertEquals(1,job.pending()); assertEquals("SYNCED",job.sourceState());
        approveIncoming(other); job=edits.retryFanout(model,"fixture",job.id(),architect,new Outcome(job.version())); job=drain(job); assertEquals("SYNCED",job.state()); assertEquals(2,job.synced()); assertEquals(0,job.pending()); assertEquals("Fanout value",local(other,second.elementId(),"property:central")); assertEquals(1,writes());
    }
    @Test void coordinatorRestartAfterModelAcceptanceRecoversReceiptWithoutDuplicateOpsOrPatch() throws Exception {
        var job=request("property:central","Durable fanout"); edits.run(job.id()); assertTrue(get(job).remoteVerified());
        var original=(ExternalChangeIngestService)io.quarkus.arc.ClientProxy.unwrap(inbound); var fail=new AtomicBoolean(true); var delegate=runtime.metadata();
        var interrupted=(IntegrationStateRepository)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IntegrationStateRepository.class},(proxy,method,args) -> {
            if (method.getName().equals("save") && args[0] instanceof StateDocument d && d.kind()==DocumentKind.INBOUND_STATE && d.data().path("state").asText().equals("SYNCED") && d.data().has("lastAcceptedBatch") && fail.getAndSet(false)) throw new IllegalStateException("Synthetic stop after model acceptance");
            try { return method.invoke(delegate,args); } catch (java.lang.reflect.InvocationTargetException cause) { throw cause.getCause(); }
        });
        var recovery=new ExternalChangeIngestService(); recovery.models=models; recovery.access=original.access; recovery.delivery=original.delivery; recovery.json=json; recovery.proposalGrants=original.proposalGrants;
        recovery.runtime=new IntegrationRuntime() { @Override public boolean enabled() { return runtime.enabled(); } @Override public IntegrationConfiguration settings() { return runtime.settings(); } @Override public IntegrationStateRepository metadata() { return interrupted; } @Override public ExternalRepositoryConnector connector(String key) { return runtime.connector(key); } @Override public ExternalRepositoryConnector.Metamodel validateTarget(String key) { return runtime.validateTarget(key); } };
        var restarted=copy((CentralEditService)io.quarkus.arc.ClientProxy.unwrap(edits)); restarted.inbound=recovery; restarted.run(job.id()); long accepted=models.getSnapshot(model).path("headRevision").asLong();
        assertEquals("SYNCED",drain(job).state()); assertEquals(accepted,models.getSnapshot(model).path("headRevision").asLong()); assertEquals(1,writes());
    }
    @Test void approvedTypedNumericValuePreservesEveryDecimalDigit() throws Exception {
        String value="1.12345678901234567890123456789"; var job=drain(approve(request("property:budget",value),false)); assertEquals("SYNCED",job.state(),job.toString());
        assertEquals(new java.math.BigDecimal(value),remoteCard().path("attributes").path("budget").decimalValue()); assertEquals(value,local(model,imported.elementId(),"property:budget"));
    }
    @Test void restProposalReviewCarriesLosslessValuesAndSeparatesRequesterFromApprover() throws Exception {
        String value="1.12345678901234567890123456789";
        var body=json.createObjectNode().put("ref","HEAD").put("elementId",imported.elementId()); body.putObject("proposed").put("property:budget",value);
        var preview=api("POST",model,"preview",body,"architect"); assertEquals(200,preview.statusCode(),preview.body()); assertEquals("no-store",preview.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(value,json.readTree(preview.body()).path("proposedText").path("property:budget").asText());
        body.put("requestId",UUID.randomUUID().toString()).put("previewDigest",json.readTree(preview.body()).path("digest").asText());
        var submitted=api("POST",model,"requests",body,"architect"); assertEquals(200,submitted.statusCode(),submitted.body()); var job=json.readTree(submitted.body()); String path="requests/"+job.path("id").asText();
        assertEquals("WAITING_APPROVAL",job.path("state").asText()); assertEquals(0,writes()); assertEquals(job,json.readTree(api("POST",model,"requests",body,"architect").body()));
        assertEquals(403,api("GET",model,path+"/review",null,"architect").statusCode());
        var reviewed=api("GET",model,path+"/review",null,"publisher"); assertEquals(200,reviewed.statusCode(),reviewed.body()); var review=json.readTree(reviewed.body());
        assertEquals("42",review.path("preview").path("context").path("fields").get(0).path("currentText").asText());
        assertEquals(value,review.path("preview").path("proposedText").path("property:budget").asText());
        var decision=json.createObjectNode().put("expectedVersion",job.path("version").asLong()).put("approve",true).put("reviewDigest",review.path("digest").asText()).put("resolveDrift",false);
        assertEquals(403,api("POST",model,path+"/decision",decision,"architect").statusCode()); assertEquals(0,writes());
        var approved=api("POST",model,path+"/decision",decision,"publisher"); assertEquals(200,approved.statusCode(),approved.body());
        assertEquals("SYNCED",drain(edits.get(model,"fixture",job.path("id").asText(),architect)).state()); assertEquals(1,writes());
        var status=json.readTree(api("GET",model,path,null,"architect").body()); assertTrue(status.path("remoteVerified").asBoolean()); assertEquals("SYNCED",status.path("sourceState").asText()); assertEquals(value,status.path("proposedText").path("property:budget").asText());
    }
    @Test void rejectedAndCancelledProposalsNeverWriteOrGrantInboundAuthority() throws Exception {
        var rejected=request("name","Rejected intent"); var review=edits.review(model,"fixture",rejected.id(),admin);
        rejected=edits.decide(model,"fixture",rejected.id(),admin,new Decision(rejected.version(),false,review.digest(),false)); assertEquals("REJECTED",drain(rejected).state());
        var cancelled=request("property:central","Cancelled intent"); cancelled=edits.cancel(model,"fixture",cancelled.id(),architect,new Outcome(cancelled.version())); assertEquals("CANCELLED",drain(cancelled).state()); assertEquals(0,writes());
        assertFalse(grants.current(rejected.id())); assertFalse(grants.current(cancelled.id()));
    }
    @Test void forgedProposalValuesFieldsResourceAndSupersededProofAreRejectedBeforeModelAcceptance() throws Exception {
        var job=request("property:shared","Approved shared value"); job=approve(job,true); edits.run(job.id()); job=get(job); assertTrue(job.remoteVerified());
        var target=runtime.settings().targets().getFirst(); var proof=grants.proof(job.id(),"fixture",imported.canonicalId(),"card-a"); var mappings=grants.fields(target,"Application",proof);
        var remoteValues=InboundFields.remote(json,runtime.connector("fixture").getObject("card-a"),mappings);
        long revision=models.getSnapshot(model).path("headRevision").asLong();
        var tampered=remoteValues.deepCopy(); ((ObjectNode)tampered).put("property:shared","Forged intent"); var activeJob=job;
        assertThrows(PublicationModel.Failure.class,() -> models.submitExternalChange(command(activeJob,tampered,"card-a")));
        var extra=remoteValues.deepCopy(); ((ObjectNode)extra).put("property:owner","Forged unowned value");
        assertThrows(PublicationModel.Failure.class,() -> models.submitExternalChange(command(activeJob,extra,"card-a")));
        assertThrows(PublicationModel.Failure.class,() -> models.submitExternalChange(command(activeJob,remoteValues,"another-card")));
        assertEquals(revision,models.getSnapshot(model).path("headRevision").asLong());
        request("property:central","Newer canonical intent"); var stale=models.submitExternalChange(command(job,remoteValues,"card-a")); assertFalse(stale.accepted()); assertEquals("EXTERNAL_CHANGE_PROPOSAL_STALE",stale.code()); assertEquals(revision,models.getSnapshot(model).path("headRevision").asLong());
    }
    private ExternalChangeCommand command(Job job,JsonNode remoteValues,String resource) {
        var target=runtime.settings().targets().getFirst(); var snapshot=models.getSnapshot(model); var expected=InboundFields.local(json,snapshot,imported.elementId(),CentralFields.keys(remoteValues));
        return new ExternalChangeCommand(runtime.settings().instanceId(),"fixture",target.instanceId(),imported.canonicalId(),resource,model,"HEAD",imported.elementId(),"ApplicationComponent",target.mapping().version(),inbound.status(model,"fixture",admin,"").policyVersion(),UUID.randomUUID().toString(),snapshot.path("headRevision").asLong(),expected,remoteValues,job.id(),runtime.requireModelActive(model));
    }
    @Test void metadataTransactionRollsBackEveryDocumentWhenOneVersionIsStale() {
        var state=runtime.metadata(); var a=new StateDocument(runtime.settings().instanceId(),DocumentKind.CHECKPOINT,"atomic-a",0,json.createObjectNode().put("value",1));
        var b=state.save(new StateDocument(runtime.settings().instanceId(),DocumentKind.CHECKPOINT,"atomic-b",0,json.createObjectNode().put("value",2)));
        assertThrows(IllegalStateException.class,() -> state.saveAtomically(List.of(a,new StateDocument(b.instanceId(),b.kind(),b.id(),0,json.createObjectNode().put("value",3)))));
        assertTrue(state.load(a.instanceId(),a.kind(),a.id()).isEmpty()); assertEquals(2,state.load(b.instanceId(),b.kind(),b.id()).orElseThrow().data().path("value").asInt());
    }
    private CentralEditService copy(CentralEditService source) { var result=new CentralEditService(); result.runtime=source.runtime; result.authorization=source.authorization; result.incoming=source.incoming; result.inbound=source.inbound; result.grants=source.grants; result.models=source.models; result.delivery=source.delivery; result.execution=source.execution; result.json=source.json; return result; }
}
