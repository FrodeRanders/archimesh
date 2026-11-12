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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;

@QuarkusTest
@TestProfile(ExternalObjectCatalogIT.Profile.class)
@QuarkusTestResource(value=ExternalCatalogTestResource.class,restrictToAnnotatedClass=true)
class ExternalObjectCatalogIT {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() { return Map.of("app.authz.enabled","true","app.identity.mode","bootstrap"); }
    }
    @TestHTTPResource URI base;
    @Inject ArchimeshService models;
    @Inject IntegrationRuntime runtime;
    @Inject AuthorizationService authorization;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.catalog.url") String catalogControl;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> owned=new ArrayList<>();
    private String model;
    @BeforeEach void prepare() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        offline(false); model=provision();
    }
    private String provision() {
        String id=runtime.settings().instanceId()+"-model-"+UUID.randomUUID(); owned.add(id);
        models.registerModel(id,"Catalog import fixture","owner");
        models.updateModelAccessControl(id,Set.of("owner"),Set.of("architect"),Set.of("reader")); return id;
    }
    @AfterEach void cleanup() throws Exception {
        offline(false);
        if (model==null) return;
        for (String id:owned) models.deleteModel(id,true);
        try (var driver=GraphDatabase.driver(System.getenv().getOrDefault("NEO4J_URI","bolt://localhost:7687"),
                AuthTokens.basic(System.getenv().getOrDefault("NEO4J_USER","neo4j"),System.getenv().getOrDefault("NEO4J_PASSWORD","devpassword")));
             var session=driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (n) WHERE (n:IntegrationModelBinding OR n:IntegrationPublicationIdentity OR n:IntegrationIdentityClaim OR n:IntegrationStateDocument) AND n.instanceId=$scope DETACH DELETE n",Map.of("scope",runtime.settings().instanceId())).consume();
                var ids=tx.run("MATCH (e:IntegrationExternalBinding {targetInstanceId:$scope}) RETURN e.canonicalId AS id",Map.of("scope",runtime.settings().instanceId())).list(r -> r.get("id").asString());
                tx.run("MATCH (e:IntegrationExternalBinding {targetInstanceId:$scope}) DETACH DELETE e",Map.of("scope",runtime.settings().instanceId())).consume();
                tx.run("MATCH (c:IntegrationCanonicalObject) WHERE c.canonicalId IN $ids AND NOT EXISTS { MATCH (e:IntegrationExternalBinding {canonicalId:c.canonicalId}) } DETACH DELETE c",Map.of("ids",ids)).consume();
                return null;
            });
        }
    }
    private void offline(boolean value) throws Exception {
        http.send(HttpRequest.newBuilder(URI.create(catalogControl+"/__fixture?offline="+value)).GET().build(),HttpResponse.BodyHandlers.discarding());
    }
    private int remoteCalls() throws Exception {
        return Integer.parseInt(http.send(HttpRequest.newBuilder(URI.create(catalogControl+"/__fixture")).GET().build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    private HttpResponse<String> call(String method,String modelId,String suffix,String user,ObjectNode body) throws Exception {
        var request=HttpRequest.newBuilder(base.resolve("models/"+modelId+"/external-objects"+suffix)).timeout(Duration.ofSeconds(30))
                .header("X-Archimesh-User",user).header("X-Archimesh-Roles",user.equals("admin-user") ? "admin" : user.equals("reader") ? "model_reader" : "model_writer");
        if (body==null) request.GET(); else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body.toString()));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode preview(String modelId,String card) throws Exception {
        var response=call("GET",modelId,"/preview?target=fixture&cardId="+card,"architect",null);
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    private ObjectNode request(JsonNode preview) {
        return json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("targetKey","fixture")
                .put("cardId",preview.path("cardId").asText()).put("ref","HEAD").put("mode","CREATE").put("previewDigest",preview.path("digest").asText());
    }
    private JsonNode execute(String modelId,ObjectNode body) throws Exception {
        var response=call("POST",modelId,"/import","architect",body); assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    private void localElement(String id,String type,String name) {
        var ops=json.createArrayNode(); ops.addObject().put("type","CreateElement").putObject("element").put("id",id).put("archimateType",type).put("name",name);
        models.onSubmitOps(model,new SubmitOpsMessage(models.getSnapshot(model).path("headRevision").asLong(),UUID.randomUUID().toString(),new Actor("architect","fixture"),ops));
    }

    @Test void modelAclAndCatalogScopeAreIndependentAndRedactServiceAccountFields() throws Exception {
        int before=remoteCalls();
        assertEquals(403,call("GET",model,"/targets","outsider",null).statusCode());
        assertEquals(200,call("GET",model,"/targets","admin-user",null).statusCode());
        assertEquals(403,call("GET",model,"/catalog?target=fixture&type=Application","admin-user",null).statusCode());
        assertEquals(404,call("GET",model,"/catalog?target=fixture&type=SecretType","architect",null).statusCode());
        assertEquals(before,remoteCalls());
        var response=call("GET",model,"/catalog?target=fixture&type=Application","reader",null);
        assertEquals(200,response.statusCode()); assertFalse(response.body().contains("PRIVATE")); assertFalse(response.body().contains("withheld"));
        var preview=preview(model,"card-a"); assertFalse(preview.toString().contains("PRIVATE"));
        assertEquals(403,call("POST",model,"/import","reader",request(preview)).statusCode());
        var hidden=call("GET",model,"/preview?target=fixture&cardId=card-hidden","architect",null);
        assertEquals(404,hidden.statusCode()); assertFalse(hidden.body().contains("Shared application"));
    }

    @Test void importsReuseWithinModelShareCanonicalAcrossModelsAndNeverMatchNames() throws Exception {
        var body=request(preview(model,"card-a")); var first=execute(model,body);
        long revision=models.getSnapshot(model).path("headRevision").asLong();
        offline(true); assertEquals(first,execute(model,body)); offline(false);
        var again=execute(model,request(preview(model,"card-a")));
        assertEquals(first.path("elementId"),again.path("elementId")); assertTrue(again.path("reused").asBoolean());
        assertEquals(revision,models.getSnapshot(model).path("headRevision").asLong());
        String other=provision(); var second=execute(other,request(preview(other,"card-a")));
        assertNotEquals(first.path("elementId"),second.path("elementId")); assertEquals(first.path("canonicalId"),second.path("canonicalId"));
        var namesake=execute(model,request(preview(model,"card-b")));
        assertNotEquals(first.path("elementId"),namesake.path("elementId")); assertNotEquals(first.path("canonicalId"),namesake.path("canonicalId"));
        assertEquals(2,models.getSnapshot(model).path("elements").size());
        var changed=body.deepCopy().put("cardId","card-b"); assertEquals(409,call("POST",model,"/import","architect",changed).statusCode());
    }

    @Test void linksCompatibleSemanticElementWithoutChangingItsLocalFields() throws Exception {
        localElement("elem:existing","ApplicationComponent","Local name");
        var body=request(preview(model,"card-a")).put("mode","LINK").put("elementId","elem:existing");
        var result=execute(model,body); assertEquals("elem:existing",result.path("elementId").asText());
        var snapshot=models.getSnapshot(model); assertEquals(1,snapshot.path("elements").size());
        assertEquals("Local name",snapshot.path("elements").get(0).path("name").asText());
        var status=call("GET",model,"/elem:existing/status","architect",null);
        assertEquals(200,status.statusCode()); assertEquals("ACTIVE",json.readTree(status.body()).path("state").asText());
    }

    @RepeatedTest(5) void concurrentImportsConvergeOnOneEntityAndOneAcceptedBatch() throws Exception {
        var initial=preview(model,"card-a"); var requests=List.of(request(initial),request(initial),request(initial),request(initial));
        try (var executor=Executors.newFixedThreadPool(4)) {
            var jobs=new ArrayList<Callable<JsonNode>>(); for (var body:requests) jobs.add(() -> execute(model,body));
            var completed=executor.invokeAll(jobs); var winner=completed.getFirst().get();
            for (var result:completed) { assertEquals(winner.path("elementId"),result.get().path("elementId")); assertEquals(winner.path("opBatchId"),result.get().path("opBatchId")); }
        }
        assertEquals(1,models.getSnapshot(model).path("elements").size()); assertEquals(3,models.getSnapshot(model).path("headRevision").asLong());
        assertEquals("folder:root-application",models.getSnapshot(model).path("elementFolderMembers").get(0).path("folderId").asText());
    }

    @Test void stalePreviewReadOnlyRefTypeConflictAndRevokedWriterDoNotCommit() throws Exception {
        var stale=request(preview(model,"card-a")); localElement("elem:local","BusinessActor","Local");
        assertEquals(409,call("POST",model,"/import","architect",stale).statusCode());
        var wrong=request(preview(model,"card-a")).put("mode","LINK").put("elementId","elem:local");
        assertEquals(409,call("POST",model,"/import","architect",wrong).statusCode());
        assertEquals(409,call("POST",model,"/import","architect",request(preview(model,"card-a")).put("ref","approved")).statusCode());
        var before=request(preview(model,"card-a")); models.updateModelAccessControl(model,Set.of("owner"),Set.of(),Set.of("reader","architect"));
        assertEquals(403,call("POST",model,"/import","architect",before).statusCode()); assertEquals(1,models.getSnapshot(model).path("headRevision").asLong());
    }

    @Test void durableAcceptanceRecoversAfterCoordinatorFailureWithoutRemoteOrDuplicateOps() throws Exception {
        var body=request(preview(model,"card-a")); var delegate=runtime.metadata(); var fail=new AtomicBoolean(true);
        IntegrationStateRepository interrupted=new IntegrationStateRepository() {
            public String modelGeneration(String m) { return delegate.modelGeneration(m); }
            public boolean hasModelMetadata(String i,String m) { return delegate.hasModelMetadata(i,m); }
            public ReservedImport reserveImport(ImportReservation r) { return delegate.reserveImport(r); }
            public Optional<ReservedImport> findBinding(ModelElementKey k) { return delegate.findBinding(k); }
            public Optional<CanonicalObject> findCanonical(ExternalResourceKey k) { return delegate.findCanonical(k); }
            public Optional<ReservedImport> findModelBinding(String i,String m,ExternalResourceKey k) { return delegate.findModelBinding(i,m,k); }
            public ReservedImport confirmImport(ModelElementKey k,String b) { if (fail.getAndSet(false)) throw new IllegalStateException("synthetic coordinator stop"); return delegate.confirmImport(k,b); }
            public StateDocument save(StateDocument d) { return delegate.save(d); }
            public Optional<StateDocument> load(String i,DocumentKind k,String id) { return delegate.load(i,k,id); }
            public void close() { }
        };
        var coordinator=new ExternalObjectCatalogService(); coordinator.models=models; coordinator.authorization=authorization; coordinator.json=json;
        coordinator.runtime=new IntegrationRuntime() {
            public boolean enabled() { return runtime.enabled(); }
            public IntegrationConfiguration settings() { return runtime.settings(); }
            public ExternalRepositoryConnector connector(String k) { return runtime.connector(k); }
            public ExternalRepositoryConnector.Metamodel validateTarget(String k) { return runtime.validateTarget(k); }
            public IntegrationStateRepository metadata() { return interrupted; }
        };
        var request=json.treeToValue(body,ExternalObjectCatalogService.ImportRequest.class);
        assertThrows(IllegalStateException.class,() -> coordinator.importObject(model,new AuthorizationSubject("architect",Set.of("model_writer")),request));
        long head=models.getSnapshot(model).path("headRevision").asLong(); assertEquals(3,head);
        offline(true); execute(model,body);
        assertEquals(head,models.getSnapshot(model).path("headRevision").asLong()); assertEquals(1,models.getSnapshot(model).path("elements").size());
    }

    @Test void importSurvivesCheckpointAndRebuildWithoutChangingHistoricalTag() throws Exception {
        var first=execute(model,request(preview(model,"card-a"))); models.createModelTag(model,"approved","Before next import");
        var tagged=models.getSnapshot(model,"approved"); execute(model,request(preview(model,"card-b")));
        var before=models.getSnapshot(model); models.createModelCheckpoint(model); models.rebuildMaterializedState(model);
        assertEquals(before,models.getSnapshot(model)); assertEquals(tagged,models.getSnapshot(model,"approved"));
        assertEquals(200,call("GET",model,"/"+first.path("elementId").asText()+"/status","architect",null).statusCode());
    }

    @Test void remoteOutageDoesNotBlockOrdinaryModelCommit() throws Exception {
        offline(true);
        assertEquals(503,call("GET",model,"/catalog?target=fixture&type=Application","architect",null).statusCode());
        localElement("elem:offline","BusinessActor","Ordinary edit"); assertEquals(1,models.getSnapshot(model).path("elements").size());
    }

    @Test void deletedImportedElementIsReportedAsDriftAndNeverRecreatedByRetry() throws Exception {
        var body=request(preview(model,"card-a")); var accepted=execute(model,body);
        var ops=json.createArrayNode(); ops.addObject().put("type","DeleteElement").put("elementId",accepted.path("elementId").asText());
        models.onSubmitOps(model,new SubmitOpsMessage(models.getSnapshot(model).path("headRevision").asLong(),UUID.randomUUID().toString(),new Actor("architect","fixture"),ops));
        long head=models.getSnapshot(model).path("headRevision").asLong();
        var status=call("GET",model,"/"+accepted.path("elementId").asText()+"/status","architect",null);
        assertEquals("DRIFT",json.readTree(status.body()).path("state").asText());
        assertEquals(409,call("POST",model,"/import","architect",body).statusCode());
        assertEquals(409,call("POST",model,"/import","architect",request(preview(model,"card-a"))).statusCode());
        assertEquals(head,models.getSnapshot(model).path("headRevision").asLong()); assertEquals(0,models.getSnapshot(model).path("elements").size());
    }

    @Test void acceptedImportReachesTwoWebSocketClientsWithoutBroadcastingPrivateAcceptance() throws Exception {
        var a=new Frames(); var b=new Frames();
        String ws="ws://"+base.getHost()+":"+base.getPort()+"/models/"+model+"/stream";
        var first=http.newWebSocketBuilder().buildAsync(URI.create(ws+"?user=architect&roles=model_writer"),a).get(10,TimeUnit.SECONDS);
        var second=http.newWebSocketBuilder().buildAsync(URI.create(ws+"?user=reader&roles=model_reader"),b).get(10,TimeUnit.SECONDS);
        try {
            String join="{\"type\":\"Join\",\"payload\":{\"ref\":\"HEAD\",\"actor\":{\"userId\":\"ignored\",\"sessionId\":\"fixture\"}}}";
            first.sendText(join,true).join(); second.sendText(join,true).join(); a.await("CheckoutSnapshot"); b.await("CheckoutSnapshot");
            var accepted=execute(model,request(preview(model,"card-a")));
            String batch=accepted.path("opBatchId").asText();
            assertEquals(batch,json.readTree(a.await("OpsBroadcast")).path("payload").path("opBatch").path("opBatchId").asText());
            assertEquals(batch,json.readTree(b.await("OpsBroadcast")).path("payload").path("opBatch").path("opBatchId").asText());
            assertTrue(a.seen.stream().noneMatch(frame -> frame.contains("\"type\":\"OpsAccepted\"")));
            assertTrue(b.seen.stream().noneMatch(frame -> frame.contains("\"type\":\"OpsAccepted\"")));
        } finally { first.sendClose(1000,"done").join(); second.sendClose(1000,"done").join(); }
    }
    private static final class Frames implements WebSocket.Listener {
        final BlockingQueue<String> pending=new LinkedBlockingQueue<>(); final List<String> seen=new CopyOnWriteArrayList<>();
        final StringBuilder partial=new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last) {
            partial.append(text); if (last) { String frame=partial.toString(); partial.setLength(0); pending.add(frame); seen.add(frame); }
            socket.request(1); return null;
        }
        String await(String type) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime()<deadline) {
                String frame=pending.poll(1,TimeUnit.SECONDS);
                if (frame!=null && frame.contains("\"type\":\""+type+"\"")) return frame;
            }
            throw new AssertionError("Missing WebSocket frame: "+type);
        }
    }
}
