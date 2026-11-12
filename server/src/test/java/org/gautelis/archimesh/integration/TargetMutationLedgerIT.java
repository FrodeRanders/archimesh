package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.gautelis.federation.*;
import org.gautelis.federation.Identity.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.federation.TargetMutationLedger.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.DocumentKind;

@QuarkusTest @TestProfile(TargetMutationLedgerIT.Profile.class)
@QuarkusTestResource(value=IntegrationOperationsTestResource.class,restrictToAnnotatedClass=true)
class TargetMutationLedgerIT {
    public static class Profile implements QuarkusTestProfile { @Override public Map<String,String> getConfigOverrides() { return Map.of("app.authz.enabled","true","app.identity.mode","bootstrap"); } }
    @Inject IntegrationRuntime runtime;
    @Inject RemoteMutationCoordinator coordination;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.publication.url") String remote;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.operations.destination",defaultValue="") String destination;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.operations.password",defaultValue="") String destinationPassword;
    private final ObjectMapper json=new ObjectMapper(); private final HttpClient http=HttpClient.newHttpClient();
    private final String digest="a".repeat(64), sourceDigest="c".repeat(64), approvalDigest="d".repeat(64);
    private TargetMutationLedger ledger;
    @BeforeEach void setup() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        ledger=runtime.metadata().targetMutations(owner()); fixture(json.createObjectNode().put("reset",true));
    }
    private String owner() { return runtime.settings().instanceId(); }
    private Repository target() { return new Repository("turboea",runtime.settings().targets().getFirst().instanceId()); }
    private Canonical source() {
        var canonical=new Canonical("urn:test:mutation:"+UUID.randomUUID(),"catalog.dataset");
        var source=new Representation(new Resource(new Repository("datahub",owner()+"-source"),ResourceKind.OBJECT,"urn:li:dataset:synthetic-"+UUID.randomUUID()),"dataset");
        return runtime.metadata().identities(owner()).reserveSource(source,canonical,new IdentityRegistry.Evidence(UUID.randomUUID().toString(),"source-operator",digest)).canonical();
    }
    private Request request(Canonical canonical,Action action,String nativeId) {
        String expected=PublicationJson.digest(json.createObjectNode().put("name","Dataset projection"));
        String payload=PublicationJson.digest(json.createObjectNode().put("type","DataObject").put("name","Dataset projection"));
        return new Request(UUID.randomUUID().toString(),canonical,new Slot(target(),ResourceKind.OBJECT,"DataObject"),action,nativeId,payload,expected,sourceDigest,approvalDigest,"bridge");
    }
    private JsonNode fixture(JsonNode body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(remote+"/__fixture")); if (body==null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return json.readTree(http.send(request.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    private IdentityRegistry.Evidence readback(Request request) { return new IdentityRegistry.Evidence(UUID.randomUUID().toString(),"recovery-operator",request.expectedReadbackDigest()); }
    private Representation actual(Request request,String nativeId) { return new Representation(new Resource(request.target().repository(),request.target().kind(),nativeId),request.target().externalType()); }
    private Driver driver(String uri,String password) { return GraphDatabase.driver(uri,AuthTokens.basic("neo4j",password)); }
    @AfterEach void cleanup() {
        if (!"true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT"))) return;
        try (var driver=driver(System.getenv("NEO4J_URI"),System.getenv("NEO4J_PASSWORD"))) { clear(driver); }
        if (!destination.isBlank()) try (var driver=driver(destination,destinationPassword)) { clear(driver); }
    }
    private void clear(Driver driver) {
        try (var session=driver.session()) { session.executeWrite(tx -> {
            var parameters=Map.<String,Object>of("owner",owner(),"repositories",List.of(owner(),owner()+"-source"));
            tx.run("MATCH (n:IntegrationStateDocument {instanceId:$owner}) DETACH DELETE n",parameters).consume();
            tx.run("MATCH (n:IntegrationExternalBinding) WHERE n.targetInstanceId IN $repositories DETACH DELETE n",parameters).consume();
            tx.run("MATCH (n:IntegrationCanonicalObject {registryInstanceId:$owner}) DETACH DELETE n",parameters).consume(); return null;
        }); }
    }
    @Test void lostCreateReplyCannotRedispatchAndReviewedReadbackAtomicallyBindsAndReleases() throws Exception {
        var request=request(source(),Action.CREATE,null); var prepared=ledger.reserve(request); assertEquals(prepared,ledger.reserve(request));
        assertThrows(PublicationModel.Failure.class,() -> coordination.requirePublicationAllowed(runtime,target().instance(),request.canonical().id()));
        assertThrows(PublicationModel.Failure.class,() -> coordination.requireCentralAllowed(runtime,target().instance(),request.canonical().id()));
        var dispatched=ledger.begin(request.id(),prepared.version(),sourceDigest,approvalDigest); fixture(json.createObjectNode().put("fault","commit-503"));
        var payload=json.createObjectNode().put("type","DataObject").put("name","Dataset projection");
        var post=HttpRequest.newBuilder(URI.create(remote+"/api/v1/cards")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        assertEquals(503,http.send(post.build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(1,fixture(null).path("writes").asInt());
        try (var driver=driver(System.getenv("NEO4J_URI"),System.getenv("NEO4J_PASSWORD")); var reopened=new Neo4jIntegrationStateRepository(driver,json,false)) {
            reopened.installSchema(); var same=reopened.targetMutations(owner()); assertEquals(State.IN_FLIGHT,same.find(request.id()).orElseThrow().state());
            assertThrows(PublicationModel.Failure.class,() -> same.begin(request.id(),dispatched.version(),sourceDigest,approvalDigest));
        }
        assertThrows(PublicationModel.Failure.class,() -> ledger.cancelPrepared(request.id(),dispatched.version()));
        var uncertain=ledger.uncertain(request.id(),dispatched.version(),dispatched.attempt());
        String nativeId=fixture(null).path("cards").fieldNames().next();
        var response=http.send(HttpRequest.newBuilder(URI.create(remote+"/api/v1/cards/"+nativeId)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals("Dataset projection",json.readTree(response.body()).path("name").asText());
        var evidence=readback(request); var verified=ledger.verify(request.id(),uncertain.version(),uncertain.attempt(),actual(request,nativeId),evidence);
        assertEquals(State.VERIFIED,verified.state()); assertEquals(verified,ledger.verify(request.id(),uncertain.version(),uncertain.attempt(),actual(request,nativeId),evidence));
        assertEquals(request.canonical(),runtime.metadata().identities(owner()).find(actual(request,nativeId).resource()).orElseThrow().canonical());
        coordination.requireCentralAllowed(runtime,target().instance(),request.canonical().id()); coordination.requirePublicationAllowed(runtime,target().instance(),request.canonical().id());
        assertThrows(PublicationModel.Failure.class,() -> ledger.reserve(request(request.canonical(),Action.CREATE,null))); assertEquals(1,fixture(null).path("writes").asInt());
        IntegrationArchive.validate(owner(),runtime.metadata().archive(owner()),json);
        var missingBinding=runtime.metadata().archive(owner()).stream().filter(node -> !(node.label().equals("IntegrationExternalBinding") && node.properties().get("system").equals("turboea"))).toList();
        assertThrows(PublicationModel.Failure.class,() -> IntegrationArchive.validate(owner(),missingBinding,json));
        // A lost mapping is not evidence that the remote create never happened.
        try (var driver=driver(System.getenv("NEO4J_URI"),System.getenv("NEO4J_PASSWORD")); var session=driver.session()) {
            session.executeWrite(tx -> { tx.run("MATCH (e:IntegrationExternalBinding {canonicalId:$canonical,system:'turboea',targetInstanceId:$target}) DETACH DELETE e",
                Map.of("canonical",request.canonical().id(),"target",target().instance())).consume(); return null; });
        }
        assertThrows(PublicationModel.Failure.class,() -> ledger.reserve(request(request.canonical(),Action.CREATE,null)));
        assertThrows(PublicationModel.Failure.class,() -> coordination.requirePublicationAllowed(runtime,target().instance(),request.canonical().id()));
        var recover=request(request.canonical(),Action.ADOPT,nativeId); var review=ledger.reserve(recover);
        ledger.verify(recover.id(),review.version(),null,actual(recover,nativeId),readback(recover));
        assertEquals(1,fixture(null).path("writes").asInt()); IntegrationArchive.validate(owner(),runtime.metadata().archive(owner()),json);
    }
    @Test void oldMutationFencesAndChangedReviewsBlockNewDispatch() {
        var request=request(source(),Action.CREATE,null); String id=CentralEditModel.laneId(target().instance(),request.canonical().id());
        var old=json.createObjectNode(); old.putObject("inFlight").put("kind","PATCH");
        var saved=runtime.metadata().save(new IntegrationStateRepository.StateDocument(owner(),DocumentKind.CENTRAL_LANE,id,0,old));
        assertThrows(PublicationModel.Failure.class,() -> ledger.reserve(request));
        runtime.metadata().save(new IntegrationStateRepository.StateDocument(owner(),DocumentKind.CENTRAL_LANE,id,saved.version(),json.createObjectNode()));
        var prepared=ledger.reserve(request);
        assertThrows(PublicationModel.Failure.class,() -> ledger.begin(request.id(),prepared.version(),"e".repeat(64),approvalDigest));
        assertThrows(PublicationModel.Failure.class,() -> ledger.begin(request.id(),prepared.version(),sourceDigest,"e".repeat(64)));
        assertEquals(State.CANCELLED,ledger.cancelPrepared(request.id(),prepared.version()).state());
        coordination.requireCentralAllowed(runtime,target().instance(),request.canonical().id());
    }
    @Test void failedBindingDoesNotReleaseAnUncertainLane() {
        var request=request(source(),Action.CREATE,null); var prepared=ledger.reserve(request); var dispatched=ledger.begin(request.id(),prepared.version(),sourceDigest,approvalDigest);
        var other=source(); var occupied=actual(request,"occupied-native-id");
        runtime.metadata().identities(owner()).bindRepresentation(other,occupied,new IdentityRegistry.Evidence(UUID.randomUUID().toString(),"operator",digest));
        assertThrows(PublicationModel.Failure.class,() -> ledger.verify(request.id(),dispatched.version(),dispatched.attempt(),occupied,readback(request)));
        assertEquals(State.IN_FLIGHT,ledger.find(request.id()).orElseThrow().state());
        assertEquals(other,runtime.metadata().identities(owner()).find(occupied.resource()).orElseThrow().canonical());
        assertThrows(PublicationModel.Failure.class,() -> coordination.requirePublicationAllowed(runtime,target().instance(),request.canonical().id()));
    }
    @Test void restoreQuarantinesPreparedWorkAndRetainsUnknownOutcomeFences() {
        var ready=ledger.reserve(request(source(),Action.CREATE,null)); var pending=ledger.reserve(request(source(),Action.CREATE,null));
        var dispatched=ledger.begin(pending.request().id(),pending.version(),sourceDigest,approvalDigest);
        var nodes=runtime.metadata().archive(owner());
        var missingLane=nodes.stream().filter(node -> !node.label().equals("IntegrationStateDocument") || !node.properties().get("kind").equals("FEDERATION_LANE")).toList();
        assertThrows(PublicationModel.Failure.class,() -> IntegrationArchive.validate(owner(),missingLane,json));
        try (var driver=driver(destination,destinationPassword); var restored=new Neo4jIntegrationStateRepository(driver,json,false)) {
            restored.installSchema(); restored.save(new IntegrationStateRepository.StateDocument(owner(),DocumentKind.OPERATIONS,"control",0,json.createObjectNode().put("paused",true)));
            restored.restoreArchive(owner(),nodes); var ledger=restored.targetMutations(owner());
            assertEquals(State.QUARANTINED,ledger.find(ready.request().id()).orElseThrow().state()); assertEquals(State.UNCERTAIN,ledger.find(pending.request().id()).orElseThrow().state());
            restored.save(new IntegrationStateRepository.StateDocument(owner(),DocumentKind.OPERATIONS,"control",1,json.createObjectNode().put("paused",false)));
            assertThrows(PublicationModel.Failure.class,() -> ledger.begin(ready.request().id(),ready.version(),sourceDigest,approvalDigest));
            assertThrows(PublicationModel.Failure.class,() -> ledger.begin(pending.request().id(),dispatched.version(),sourceDigest,approvalDigest));
            assertThrows(PublicationModel.Failure.class,() -> ledger.cancelPrepared(pending.request().id(),dispatched.version()));
            ledger.cancelPrepared(ready.request().id(),ready.version()); IntegrationArchive.validate(owner(),restored.archive(owner()),json);
        }
    }
    @Test void adoptionIsReadbackOnlyAndCannotTurnIntoAWrite() {
        var request=request(source(),Action.ADOPT,"reviewed-existing-target"); var prepared=ledger.reserve(request);
        assertThrows(PublicationModel.Failure.class,() -> ledger.begin(request.id(),prepared.version(),sourceDigest,approvalDigest));
        assertThrows(PublicationModel.Failure.class,() -> ledger.verify(request.id(),prepared.version(),null,actual(request,"different-target"),readback(request)));
        assertEquals(State.VERIFIED,ledger.verify(request.id(),prepared.version(),null,actual(request,request.knownNativeId()),readback(request)).state());
    }
    @Test void genericTargetUpdatesRetainFullNativeIdsAndVerifiedHistoryAfterCancellation() {
        var canonical=source(); String nativeId="urn:li:dataset:reviewed-target-"+"x".repeat(700);
        var representation=new Representation(new Resource(new Repository("datahub",owner()),ResourceKind.OBJECT,nativeId),"dataset");
        runtime.metadata().identities(owner()).bindRepresentation(canonical,representation,new IdentityRegistry.Evidence(UUID.randomUUID().toString(),"operator",digest));
        var request=new Request(UUID.randomUUID().toString(),canonical,new Slot(representation.resource().repository(),ResourceKind.OBJECT,"dataset"),Action.UPDATE,nativeId,digest,digest,sourceDigest,approvalDigest,"bridge");
        var prepared=ledger.reserve(request); var begun=ledger.begin(request.id(),prepared.version(),sourceDigest,approvalDigest);
        var verified=ledger.verify(request.id(),begun.version(),begun.attempt(),representation,new IdentityRegistry.Evidence(UUID.randomUUID().toString(),"operator",digest));
        assertEquals(nativeId,verified.verifiedNativeId()); assertEquals(canonical,runtime.metadata().identities(owner()).find(representation.resource()).orElseThrow().canonical());
        var next=new Request(UUID.randomUUID().toString(),canonical,request.target(),Action.UPDATE,nativeId,digest,digest,sourceDigest,approvalDigest,"bridge");
        var pending=ledger.reserve(next); ledger.cancelPrepared(next.id(),pending.version());
        IntegrationArchive.validate(owner(),runtime.metadata().archive(owner()),json);
    }
}
