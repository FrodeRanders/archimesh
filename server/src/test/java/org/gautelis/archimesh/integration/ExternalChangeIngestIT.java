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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.ExternalChangeIngestService.*;

@QuarkusTest
@TestProfile(ExternalChangeIngestIT.Profile.class)
@QuarkusTestResource(value=InboundTestResource.class,restrictToAnnotatedClass=true)
class ExternalChangeIngestIT {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() { return Map.of("app.authz.enabled","true","app.identity.mode","bootstrap"); }
    }
    @Inject ExternalChangeIngestService inbound;
    @Inject ExternalObjectCatalogService catalog;
    @Inject ArchimeshService models;
    @Inject IntegrationRuntime runtime;
    @TestHTTPResource URI base;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.publication.url") String remote;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private final AuthorizationSubject admin=new AuthorizationSubject("publisher",Set.of("model_writer"));
    private final AuthorizationSubject architect=new AuthorizationSubject("architect",Set.of("model_writer"));
    private final List<String> owned=new ArrayList<>();
    private String model;
    private ExternalObjectCatalogService.ImportResult imported;
    @BeforeEach void prepare() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        control(json.createObjectNode().put("reset",true));
        var card=json.createObjectNode().put("id","card-a").put("type","Application").put("name","Initial central name").put("status","ACTIVE").put("description","Remote description");
        card.putObject("attributes").put("central","Initial owner").put("owner","Unowned remote field"); control(json.createObjectNode().set("card",card));
        model=provision(); imported=importCard(model,ExternalObjectCatalogService.ImportMode.CREATE,null); approve(model,true);
    }
    private String provision() { return provision(""); }
    private String provision(String suffix) {
        String id=runtime.settings().instanceId()+"-model-"+UUID.randomUUID()+suffix; owned.add(id); models.registerModel(id,"Inbound fixture","publisher");
        models.updateModelAccessControl(id,Set.of("publisher"),Set.of("architect"),Set.of("reader")); return id;
    }
    private ExternalObjectCatalogService.ImportResult importCard(String model,ExternalObjectCatalogService.ImportMode mode,String element) {
        var preview=catalog.preview(model,"HEAD",architect,"fixture","card-a");
        return catalog.importObject(model,architect,new ExternalObjectCatalogService.ImportRequest(UUID.randomUUID().toString(),"fixture","card-a","HEAD",mode,element,preview.digest()));
    }
    @AfterEach void cleanup() throws Exception {
        if (model==null) return;
        control(json.createObjectNode().put("fault","")); for (String id:owned) models.deleteModel(id,true);
        try (var driver=GraphDatabase.driver(System.getenv().getOrDefault("NEO4J_URI","bolt://localhost:7687"),AuthTokens.basic(System.getenv().getOrDefault("NEO4J_USER","neo4j"),System.getenv().getOrDefault("NEO4J_PASSWORD","devpassword"))); var session=driver.session()) {
            session.executeWrite(tx -> {
                Map<String,Object> params=Map.of("scope",runtime.settings().instanceId());
                var ids=tx.run("MATCH (n:IntegrationExternalBinding {targetInstanceId:$scope}) RETURN n.canonicalId AS id",params).list(r -> r.get("id").asString());
                tx.run("MATCH (n) WHERE (n:IntegrationModelBinding OR n:IntegrationPublicationIdentity OR n:IntegrationIdentityClaim OR n:IntegrationStateDocument) AND n.instanceId=$scope DETACH DELETE n",params).consume();
                tx.run("MATCH (n:IntegrationExternalBinding {targetInstanceId:$scope}) DETACH DELETE n",params).consume();
                tx.run("MATCH (n:IntegrationCanonicalObject) WHERE n.canonicalId IN $ids DETACH DELETE n",Map.of("ids",ids)).consume(); return null;
            });
        }
    }
    private JsonNode control(ObjectNode body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(remote+"/__fixture")); if (body==null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return json.readTree(http.send(request.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    private void remoteName(String name) throws Exception { control(json.createObjectNode().put("id","card-a").set("fields",json.createObjectNode().put("name",name))); }
    private void approve(String model,boolean enabled) {
        var status=inbound.status(model,"fixture",admin,""); inbound.approve(model,"fixture",admin,new Approval(enabled,status.policyVersion(),status.targetInstanceId(),status.mappingVersion()));
    }
    private ReservedImport binding(String model,String element) { return runtime.metadata().findBinding(new ModelElementKey(runtime.settings().instanceId(),model,EntityKind.ELEMENT,element)).orElseThrow(); }
    private ReservedImport binding() { return binding(model,imported.elementId()); }
    private JsonNode state(ReservedImport b) { return runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.INBOUND_STATE,stateId(b)).orElseThrow().data(); }
    private String name(String model,String element) { return InboundFields.element(models.getSnapshot(model),element).path("name").asText(); }
    private void commit(String model,ArrayNode ops) { models.onSubmitOps(model,new SubmitOpsMessage(models.getSnapshot(model).path("headRevision").asLong(),UUID.randomUUID().toString(),new Actor("architect","fixture"),ops)); }
    private InboundWorker poller() { var worker=new InboundWorker(); worker.runtime=runtime; worker.inbound=inbound; worker.json=json; worker.interval=5; return worker; }
    private void assertSynced(ReservedImport b) { assertEquals("SYNCED",state(b).path("state").asText(),state(b).toString()); }

    @Test void pollingFansOutToAllApprovedHeadsAndLeavesHistoricalTagsUntouched() throws Exception {
        String other=provision("🌐"); var second=importCard(other,ExternalObjectCatalogService.ImportMode.CREATE,null); approve(other,true);
        String third=provision("🌐"); var thirdImport=importCard(third,ExternalObjectCatalogService.ImportMode.CREATE,null); approve(third,true);
        var firstPage=runtime.metadata().bindings(runtime.settings().instanceId(),binding().external().targetInstanceId(),null,"",1);
        var secondPage=runtime.metadata().bindings(runtime.settings().instanceId(),binding().external().targetInstanceId(),null,cursor(firstPage.getFirst()),1);
        var thirdPage=runtime.metadata().bindings(runtime.settings().instanceId(),binding().external().targetInstanceId(),null,cursor(secondPage.getFirst()),1);
        assertEquals(1,thirdPage.size()); assertNotEquals(secondPage.getFirst().model(),thirdPage.getFirst().model());
        assertEquals(1,secondPage.size()); assertNotEquals(firstPage.getFirst().model(),secondPage.getFirst().model());
        assertEquals(imported.canonicalId(),second.canonicalId()); models.createModelTag(model,"approved","Before central edit"); var tag=models.getSnapshot(model,"approved");
        remoteName("New central name"); poller().tick();
        assertSynced(binding()); assertSynced(binding(other,second.elementId())); assertSynced(binding(third,thirdImport.elementId())); assertEquals("New central name",name(third,thirdImport.elementId())); assertEquals("New central name",name(model,imported.elementId())); assertEquals("New central name",name(other,second.elementId()));
        assertEquals(tag,models.getSnapshot(model,"approved")); long head=models.getSnapshot(model).path("headRevision").asLong();
        inbound.refresh(model,"fixture",admin,imported.elementId()); assertEquals(head,models.getSnapshot(model).path("headRevision").asLong()); assertEquals(0,control(null).path("writes").asInt());
        models.createModelCheckpoint(model); models.rebuildMaterializedState(model); assertEquals("New central name",name(model,imported.elementId()));
    }
    @Test void onlyCentralFieldsChangeAndGeneratedClockExceedsExistingHighLamport() throws Exception {
        var ops=json.createArrayNode(); var update=ops.addObject().put("type","UpdateElement").put("elementId",imported.elementId()); update.putObject("patch").put("documentation","Local documentation");
        update.putObject("causal").put("clientId","offline").put("lamport",10000).put("opId","local-high");
        ops.addObject().put("type","SetProperty").put("targetId",imported.elementId()).put("key","owner").put("value","Local owner"); commit(model,ops);
        control(json.createObjectNode().put("id","card-a").set("fields",json.createObjectNode().put("name","Central winner").put("description","Do not copy this").set("attributes",json.createObjectNode().put("central","New central owner").put("owner","Do not copy this either"))));
        inbound.refresh(model,"fixture",admin,imported.elementId()); assertSynced(binding()); var snapshot=models.getSnapshot(model);
        assertEquals("Local documentation",InboundFields.element(snapshot,imported.elementId()).path("documentation").asText());
        var local=InboundFields.local(json,snapshot,imported.elementId(),Set.of("property:central","property:owner")); assertEquals("New central owner",local.path("property:central").asText()); assertEquals("Local owner",local.path("property:owner").asText());
        long max=0; for (var clock:snapshot.path("fieldClocks")) max=Math.max(max,clock.path("causal").path("lamport").asLong()); assertTrue(max>10000);
    }
    @Test void highClockOfflineEditsAndSpoofedIntegrationActorCannotBypassOwnership() throws Exception {
        long revision=models.getSnapshot(model).path("headRevision").asLong();
        for (String type:List.of("UpdateElement","SetProperty","UnsetProperty","AddPropertySetMember","RemovePropertySetMember","CreateElement")) {
            var ops=json.createArrayNode(); var op=ops.addObject().put("type",type);
            if (type.equals("UpdateElement")) { op.put("elementId",imported.elementId()); op.putObject("patch").put("name","Unauthorized"); }
            else if (type.equals("CreateElement")) op.putObject("element").put("id",imported.elementId()).put("archimateType","ApplicationComponent").put("name","Unauthorized");
            else op.put("targetId",imported.elementId()).put("key","central").put("value","Unauthorized").put("member","Unauthorized");
            op.putObject("causal").put("clientId","external-sync:spoof").put("lamport",Long.MAX_VALUE).put("opId",UUID.randomUUID().toString());
            models.onSubmitOps(model,new SubmitOpsMessage(revision,UUID.randomUUID().toString(),new Actor("external-sync:"+runtime.settings().instanceId(),"spoof"),ops));
            assertEquals(revision,models.getSnapshot(model).path("headRevision").asLong(),type);
        }
        remoteName("Permitted central update"); inbound.refresh(model,"fixture",admin,imported.elementId()); assertSynced(binding()); assertEquals("Permitted central update",name(model,imported.elementId()));
    }
    @Test void pendingAcceptanceRecoversWithoutHttpOrDuplicateOpsAfterCoordinatorRestart() throws Exception {
        remoteName("Durable central value"); var original=(ExternalChangeIngestService)io.quarkus.arc.ClientProxy.unwrap(inbound); var fail=new AtomicBoolean(true); var delegate=runtime.metadata();
        var interrupted=(IntegrationStateRepository)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IntegrationStateRepository.class},(proxy,method,args) -> {
            if (method.getName().equals("save") && args[0] instanceof StateDocument d && d.kind()==DocumentKind.INBOUND_STATE && d.data().path("state").asText().equals("SYNCED") && d.data().has("lastAcceptedBatch") && fail.getAndSet(false)) throw new IllegalStateException("Synthetic coordinator stop after model acceptance");
            try { return method.invoke(delegate,args); } catch (java.lang.reflect.InvocationTargetException cause) { throw cause.getCause(); }
        });
        var restarted=new ExternalChangeIngestService(); restarted.models=models; restarted.access=original.access; restarted.delivery=original.delivery; restarted.json=json;
        restarted.runtime=new IntegrationRuntime() {
            @Override public boolean enabled() { return runtime.enabled(); }
            @Override public IntegrationConfiguration settings() { return runtime.settings(); }
            @Override public ExternalRepositoryConnector connector(String key) { return runtime.connector(key); }
            @Override public ExternalRepositoryConnector.Metamodel validateTarget(String key) { return runtime.validateTarget(key); }
            @Override public IntegrationStateRepository metadata() { return interrupted; }
        };
        restarted.refresh(model,"fixture",admin,imported.elementId()); assertTrue(state(binding()).has("pending")); long accepted=models.getSnapshot(model).path("headRevision").asLong();
        models.createModelCheckpoint(model); models.rebuildMaterializedState(model); control(json.createObjectNode().put("fault","offline")); int calls=control(null).path("calls").asInt();
        inbound.refresh(model,"fixture",admin,imported.elementId()); assertSynced(binding()); assertFalse(state(binding()).has("pending")); assertEquals(accepted,models.getSnapshot(model).path("headRevision").asLong()); assertEquals(calls,control(null).path("calls").asInt());
    }
    @Test void disabledOrUnauthorizedOccurrenceIsRecordedWithoutBlockingOtherModels() throws Exception {
        String other=provision(); var second=importCard(other,ExternalObjectCatalogService.ImportMode.CREATE,null); remoteName("New central name"); poller().tick();
        assertSynced(binding()); var b=binding(other,second.elementId()); assertEquals("PAUSED",state(b).path("state").asText()); assertEquals("INBOUND_POLICY_REQUIRED",state(b).path("code").asText()); assertEquals("Initial central name",name(other,second.elementId()));
        approve(other,true); inbound.refresh(other,"fixture",admin,second.elementId()); assertSynced(b); assertEquals("New central name",name(other,second.elementId()));
        models.updateModelAccessControl(other,Set.of("owner"),Set.of("architect"),Set.of()); remoteName("Another central change"); inbound.reconcile("fixture",b,false); assertEquals("PAUSED",state(b).path("state").asText()); assertEquals("New central name",name(other,second.elementId()));
    }
    @Test void linkedLocalDifferencesRequireExplicitReviewedRemoteResolution() throws Exception {
        String other=provision(); var ops=json.createArrayNode(); ops.addObject().put("type","CreateElement").putObject("element").put("id","elem:existing").put("archimateType","ApplicationComponent").put("name","Independent local name"); commit(other,ops);
        var linked=importCard(other,ExternalObjectCatalogService.ImportMode.LINK,"elem:existing"); approve(other,true); inbound.refresh(other,"fixture",admin,linked.elementId());
        var b=binding(other,linked.elementId()); assertEquals("CONFLICT",state(b).path("state").asText()); assertEquals("BASELINE_APPROVAL_REQUIRED",state(b).path("code").asText());
        var staleReview=inbound.inspect(other,"fixture",admin,linked.elementId()); remoteName("Changed during review"); assertThrows(PublicationModel.Failure.class,() -> inbound.acceptRemote(other,"fixture",admin,new Resolution(linked.elementId(),staleReview.digest())));
        var review=inbound.inspect(other,"fixture",admin,linked.elementId()); inbound.acceptRemote(other,"fixture",admin,new Resolution(linked.elementId(),review.digest())); assertSynced(b); assertEquals("Changed during review",name(other,linked.elementId()));
    }
    @Test void independentLocalDriftAndBothChangedValuesAreNeverBlindlyOverwritten() throws Exception {
        inbound.refresh(model,"fixture",admin,imported.elementId()); assertSynced(binding());
        var integration=(IntegrationRuntime)io.quarkus.arc.ClientProxy.unwrap(runtime);
        // Simulate legacy edits accepted while the entire integration was explicitly disabled.
        integration.enabled=false;
        try {
            var ops=json.createArrayNode(); ops.addObject().put("type","UpdateElement").put("elementId",imported.elementId()).putObject("patch").put("name","Independent local edit"); commit(model,ops);
        } finally { integration.enabled=true; }
        inbound.refresh(model,"fixture",admin,imported.elementId()); assertEquals("LOCAL_DRIFT",state(binding()).path("code").asText());
        assertEquals("Independent local edit",state(binding()).path("conflict").path("local").path("name").asText());
        assertEquals("Initial central name",state(binding()).path("conflict").path("remote").path("name").asText());
        remoteName("Independent remote edit"); inbound.refresh(model,"fixture",admin,imported.elementId()); assertEquals("BOTH_SIDES_CHANGED",state(binding()).path("code").asText());
        assertEquals("Independent local edit",name(model,imported.elementId())); assertEquals(0,control(null).path("writes").asInt());
        var review=inbound.inspect(model,"fixture",admin,imported.elementId()); inbound.acceptRemote(model,"fixture",admin,new Resolution(imported.elementId(),review.digest())); assertSynced(binding()); assertEquals("Independent remote edit",name(model,imported.elementId()));
    }
    @Test void nullCentralPropertyBecomesOrdinaryUnsetAndMissingCardIsOrphanedWithoutRecreation() throws Exception {
        inbound.refresh(model,"fixture",admin,imported.elementId());
        control(json.createObjectNode().put("id","card-a").set("fields",json.createObjectNode().set("attributes",json.createObjectNode())));
        inbound.refresh(model,"fixture",admin,imported.elementId()); assertSynced(binding()); assertTrue(InboundFields.local(json,models.getSnapshot(model),imported.elementId(),Set.of("property:central")).path("property:central").isNull());
        String oldDigest=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.JOB,"import-plan:"+imported.opBatchId()).orElseThrow().data().path("previewDigest").asText();
        var before=models.getSnapshot(model); control(json.createObjectNode().put("id","card-a").put("delete",true)); inbound.refresh(model,"fixture",admin,imported.elementId());
        assertEquals(BindingState.ORPHANED,binding().state()); assertEquals("ORPHANED",state(binding()).path("state").asText()); assertEquals(before,models.getSnapshot(model)); assertEquals(0,control(null).path("writes").asInt());
        assertThrows(ExternalObjectCatalogService.RequestFailure.class,() -> catalog.importObject(model,architect,new ExternalObjectCatalogService.ImportRequest(UUID.randomUUID().toString(),"fixture","card-a","HEAD",ExternalObjectCatalogService.ImportMode.CREATE,null,oldDigest)));
        assertEquals(BindingState.ORPHANED,binding().state());
    }
    @Test void retirementAndLocalDeletionAreDiagnosedWithoutDeletingViewsOrRecreatingElements() throws Exception {
        var before=models.getSnapshot(model); control(json.createObjectNode().put("id","card-a").set("fields",json.createObjectNode().put("status","ARCHIVED")));
        inbound.refresh(model,"fixture",admin,imported.elementId()); assertEquals("RETIRED",state(binding()).path("state").asText()); assertEquals(before,models.getSnapshot(model));
        control(json.createObjectNode().put("id","card-a").set("fields",json.createObjectNode().put("status","ACTIVE")));
        var ops=json.createArrayNode(); ops.addObject().put("type","DeleteElement").put("elementId",imported.elementId()); commit(model,ops); long revision=models.getSnapshot(model).path("headRevision").asLong();
        inbound.refresh(model,"fixture",admin,imported.elementId()); assertEquals("LOCAL_ELEMENT_MISSING",state(binding()).path("code").asText()); assertEquals(revision,models.getSnapshot(model).path("headRevision").asLong());
    }
    @Test void retriesAreBoundedAndRemoteOutageDoesNotBlockOrdinaryUnownedCommit() throws Exception {
        control(json.createObjectNode().put("fault","rate-limit")); var service=(ExternalChangeIngestService)io.quarkus.arc.ClientProxy.unwrap(inbound); long start=System.currentTimeMillis();
        try {
            for (int i=0;i<4;i++) { final long t=start+i*100000; service.now=() -> t; inbound.reconcile("fixture",binding(),false); }
            assertEquals("ERROR",state(binding()).path("state").asText()); assertEquals(4,state(binding()).path("attempts").asInt());
        } finally { service.now=System::currentTimeMillis; }
        control(json.createObjectNode().put("fault","offline")); var ops=json.createArrayNode(); var op=ops.addObject().put("type","UpdateElement").put("elementId",imported.elementId()); op.putObject("patch").put("documentation","Edit while remote is down"); commit(model,ops);
        assertEquals("Edit while remote is down",InboundFields.element(models.getSnapshot(model),imported.elementId()).path("documentation").asText());
    }
    @Test void externalCapabilityCannotWriteOtherFieldsOrTagsAndPolicyPauseKeepsOwnershipGuard() throws Exception {
        var review=inbound.inspect(model,"fixture",admin,imported.elementId()); var fields=(ObjectNode)review.remote().deepCopy(); fields.put("documentation","Forbidden external edit"); var b=binding();
        var command=new ExternalChangeCommand(runtime.settings().instanceId(),"fixture",b.external().targetInstanceId(),b.canonical().canonicalId(),b.external().resourceId(),model,"HEAD",b.model().entityId(),b.archimateType(),b.mappingVersion(),1,UUID.randomUUID().toString(),review.revision(),review.local(),fields);
        assertThrows(PublicationModel.Failure.class,() -> models.submitExternalChange(command));
        var tagged=new ExternalChangeCommand(command.instanceId(),command.targetKey(),command.targetInstanceId(),command.canonicalId(),command.resourceId(),model,"approved",command.elementId(),command.archimateType(),command.mappingVersion(),1,UUID.randomUUID().toString(),review.revision(),review.local(),review.remote());
        assertThrows(PublicationModel.Failure.class,() -> models.submitExternalChange(tagged));
        approve(model,false); remoteName("Paused remote change"); inbound.reconcile("fixture",b,false); assertEquals("PAUSED",state(b).path("state").asText());
        var ops=json.createArrayNode(); ops.addObject().put("type","UpdateElement").put("elementId",imported.elementId()).putObject("patch").put("name","Forbidden paused edit"); commit(model,ops); assertEquals("Initial central name",name(model,imported.elementId()));
        var request=HttpRequest.newBuilder(base.resolve("admin/models/"+model+"/inbound/fixture")).header("X-Archimesh-User","architect").header("X-Archimesh-Roles","admin").GET().build(); assertEquals(403,http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void acceptedCentralChangeReachesTwoConnectedClientsThroughNormalBroadcast() throws Exception {
        var a=new Frames(); var b=new Frames(); String ws="ws://"+base.getHost()+":"+base.getPort()+"/models/"+model+"/stream";
        var first=http.newWebSocketBuilder().buildAsync(URI.create(ws+"?user=architect&roles=model_writer"),a).get(10,TimeUnit.SECONDS);
        var second=http.newWebSocketBuilder().buildAsync(URI.create(ws+"?user=reader&roles=model_reader"),b).get(10,TimeUnit.SECONDS);
        try {
            String join="{\"type\":\"Join\",\"payload\":{\"actor\":{\"userId\":\"architect\",\"sessionId\":\"m4-websocket\"},\"ref\":\"HEAD\"}}";
            first.sendText(join,true).join(); second.sendText(join,true).join(); a.await("CheckoutSnapshot"); b.await("CheckoutSnapshot");
            remoteName("Websocket central update"); inbound.refresh(model,"fixture",admin,imported.elementId()); String batch=state(binding()).path("lastAcceptedBatch").asText();
            assertEquals(batch,json.readTree(a.awaitBatch(batch)).path("payload").path("opBatch").path("opBatchId").asText()); assertEquals(batch,json.readTree(b.awaitBatch(batch)).path("payload").path("opBatch").path("opBatchId").asText());
            assertFalse(a.seen.stream().anyMatch(frame -> frame.contains("\"type\":\"OpsAccepted\""))); assertFalse(b.seen.stream().anyMatch(frame -> frame.contains("\"type\":\"OpsAccepted\"")));
        } finally { first.abort(); second.abort(); }
    }
    private static final class Frames implements WebSocket.Listener {
        final BlockingQueue<String> pending=new LinkedBlockingQueue<>(); final List<String> seen=new CopyOnWriteArrayList<>(); final StringBuilder partial=new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last) { partial.append(text); if (last) { String frame=partial.toString(); partial.setLength(0); pending.add(frame); seen.add(frame); } socket.request(1); return null; }
        String await(String type) throws Exception { return awaitMatching(frame -> frame.contains("\"type\":\""+type+"\"")); }
        String awaitBatch(String batch) throws Exception { return awaitMatching(frame -> frame.contains("\"type\":\"OpsBroadcast\"") && frame.contains(batch)); }
        String awaitMatching(java.util.function.Predicate<String> match) throws Exception {
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while (System.nanoTime()<deadline) { String frame=pending.poll(1,TimeUnit.SECONDS); if (frame!=null && match.test(frame)) return frame; } throw new AssertionError("Expected WebSocket frame missing");
        }
    }
}
