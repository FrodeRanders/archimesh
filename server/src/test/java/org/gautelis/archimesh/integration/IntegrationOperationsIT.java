package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.quarkus.test.common.*;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.IntegrationOperationsService.*;

@QuarkusTest @TestProfile(IntegrationOperationsIT.Profile.class)
@QuarkusTestResource(value=IntegrationOperationsTestResource.class,restrictToAnnotatedClass=true)
class IntegrationOperationsIT {
    public static class Profile implements QuarkusTestProfile { @Override public Map<String,String> getConfigOverrides() { return Map.of("app.authz.enabled","true","app.identity.mode","bootstrap"); } }
    @Inject IntegrationRuntime runtime;
    @Inject IntegrationOperationsService operations;
    @Inject CentralEditService central;
    @Inject ExternalObjectCatalogService catalog;
    @Inject ExternalChangeIngestService inbound;
    @Inject ArchimeshService models;
    @TestHTTPResource URI base;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.publication.url") String remote;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.operations.destination",defaultValue="") String destination;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.operations.password",defaultValue="") String destinationPassword;
    final AuthorizationSubject operator=new AuthorizationSubject("operator",Set.of("admin"));
    final AuthorizationSubject architect=new AuthorizationSubject("architect",Set.of("model_writer"));
    final AuthorizationSubject publisher=new AuthorizationSubject("publisher",Set.of("model_writer"));
    final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    final HttpClient http=HttpClient.newHttpClient();
    final Map<String,ExternalObjectCatalogService.ImportResult> owned=new LinkedHashMap<>();
    String model;
    ExternalObjectCatalogService.ImportRequest originalImport;
    @BeforeEach void setup() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        fixture(json.createObjectNode().put("reset",true));
        var card=json.createObjectNode().put("id","card-a").put("type","Application").put("name","Initial name").put("status","ACTIVE"); card.putObject("attributes").put("central","Initial central").put("shared","Initial shared").put("budget",42).put("owner","Unowned remote"); fixture(json.createObjectNode().set("card",card));
        model=provision(true);
    }
    String provision(boolean approve) {
        String id=runtime.settings().instanceId()+"-ops-"+UUID.randomUUID(); models.registerModel(id,"Recovery fixture","publisher"); models.updateModelAccessControl(id,Set.of("publisher"),Set.of("architect"),Set.of());
        var preview=catalog.preview(id,"HEAD",architect,"fixture","card-a"); var request=new ExternalObjectCatalogService.ImportRequest(UUID.randomUUID().toString(),"fixture","card-a","HEAD",ExternalObjectCatalogService.ImportMode.CREATE,null,preview.digest());
        owned.put(id,catalog.importObject(id,architect,request)); if (model==null) originalImport=request; if (approve) { approve(id); inbound.refresh(id,"fixture",publisher,owned.get(id).elementId()); } return id;
    }
    void approve(String id) { var view=inbound.status(id,"fixture",publisher,""); inbound.approve(id,"fixture",publisher,new ExternalChangeIngestService.Approval(true,view.policyVersion(),view.targetInstanceId(),view.mappingVersion())); }
    JsonNode fixture(ObjectNode body) throws Exception { var request=HttpRequest.newBuilder(URI.create(remote+"/__fixture")); request.method(body==null ? "GET" : "POST",body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString())); return json.readTree(http.send(request.build(),HttpResponse.BodyHandlers.ofString()).body()); }
    HttpResponse<String> api(String path,String method,JsonNode body,String user,String roles) throws Exception { var request=HttpRequest.newBuilder(base.resolve("admin/integrations"+path)).header("X-Archimesh-User",user).header("X-Archimesh-Roles",roles).header("Content-Type","application/json"); request.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString())); return http.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    long controlVersion() { return operations.overview(operator).path("controlVersion").asLong(); }
    void resume() { var review=operations.probe(operator); operations.resume(operator,new Decision(controlVersion(),review.digest())); }
    void attach(String id) { var review=operations.previewAttachment(operator,id); assertTrue(review.details().path("canAttach").asBoolean(),review.details().toString()); operations.attach(operator,id,new Decision(review.details().path("scopeVersion").asLong(),review.digest())); }
    CentralEditModel.Job requestCentral(String value) { var proposed=Map.<String,JsonNode>of("property:central",json.getNodeFactory().textNode(value)); var preview=central.preview(model,"fixture",architect,new CentralEditModel.PreviewRequest("HEAD",owned.get(model).elementId(),proposed)); return central.submit(model,"fixture",architect,new CentralEditModel.Request(UUID.randomUUID().toString(),"HEAD",owned.get(model).elementId(),proposed,preview.digest())); }
    String local(String id,String field) { return InboundFields.local(json,models.getSnapshot(id),owned.get(id).elementId(),Set.of(field)).path(field).asText(); }
    void property(String id,String key,String value) { var ops=json.createArrayNode(); ops.addObject().put("type","SetProperty").put("targetId",owned.get(id).elementId()).put("key",key).put("value",value); models.onSubmitOps(id,new SubmitOpsMessage(models.getSnapshot(id).path("headRevision").asLong(),UUID.randomUUID().toString(),new Actor("architect","ops-it"),ops)); }
    void clearMetadata(Driver driver) {
        try (var session=driver.session()) { session.executeWrite(tx -> {
            var p=Map.<String,Object>of("instance",runtime.settings().instanceId()); var ids=tx.run("MATCH (n:IntegrationModelBinding {instanceId:$instance}) RETURN DISTINCT n.canonicalId AS id",p).list(r -> r.get("id").asString());
            tx.run("MATCH (n {instanceId:$instance}) WHERE n:IntegrationStateDocument OR n:IntegrationModelBinding OR n:IntegrationPublicationIdentity OR n:IntegrationIdentityClaim DETACH DELETE n",p).consume();
            tx.run("MATCH (n) WHERE (n:IntegrationCanonicalObject OR n:IntegrationExternalBinding) AND n.canonicalId IN $ids DETACH DELETE n",Map.of("ids",ids)).consume(); return null;
        }); }
    }
    Driver sourceDriver() { return GraphDatabase.driver(System.getenv("NEO4J_URI"),AuthTokens.basic(System.getenv().getOrDefault("NEO4J_USER","neo4j"),System.getenv("NEO4J_PASSWORD"))); }
    @AfterEach void cleanup() throws Exception {
        if (model==null) return; fixture(json.createObjectNode().put("fault","")); for (String id:owned.keySet()) models.deleteModel(id,true);
        try (var driver=sourceDriver()) { clearMetadata(driver); }
        if (!destination.isEmpty()) try (var driver=GraphDatabase.driver(destination,AuthTokens.basic("neo4j",destinationPassword))) { clearMetadata(driver); }
    }
    @Test void operatorRoleAndNamedScopeAreBothRequiredAndStatusMetricsAreRedacted() throws Exception {
        assertEquals(403,api("","GET",null,"outsider","admin").statusCode()); assertEquals(403,api("","GET",null,"operator","model_writer").statusCode());
        var response=api("","GET",null,"operator","admin"); assertEquals(200,response.statusCode(),response.body()); assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(response.body().contains("Initial central")); assertFalse(response.body().contains("tokenEnvironment"));
        var metrics=api("/metrics","GET",null,"operator","admin"); assertEquals(200,metrics.statusCode(),metrics.body()); assertTrue(metrics.body().contains("archimesh_integration_bindings{state=\"ACTIVE\"} 1")); assertFalse(metrics.body().contains(model));
        var diagnostics=operations.documents(operator,DocumentKind.INBOUND_STATE,""); assertEquals(1,diagnostics.items().size()); assertFalse(diagnostics.items().getFirst().has("baseline"));
    }
    @Test void durablePauseBlocksExternalWorkWhileLocalEditingAndOwnershipGuardRemainAvailable() throws Exception {
        var job=requestCentral("Pending during pause"); operations.pause(operator,controlVersion()); fixture(json.createObjectNode().put("fault","offline"));
        property(model,"owner","Local editing continues"); assertEquals("Local editing continues",local(model,"property:owner")); central.run(job.id()); assertEquals(0,fixture(null).path("writes").asInt());
        assertThrows(PublicationModel.Failure.class,() -> requestCentral("Denied while paused")); assertThrows(ConnectorException.class,() -> operations.probe(operator)); assertTrue(operations.overview(operator).path("paused").asBoolean());
        long revision=models.getSnapshot(model).path("headRevision").asLong(); property(model,"central","Ordinary forbidden edit"); assertEquals(revision,models.getSnapshot(model).path("headRevision").asLong()); assertEquals("Initial central",local(model,"property:central"));
        fixture(json.createObjectNode().put("fault","")); resume(); var stale=operations.probe(operator); operations.pause(operator,controlVersion()); assertThrows(PublicationModel.Failure.class,() -> operations.resume(operator,new Decision(controlVersion(),stale.digest())));
        assertTrue(operations.overview(operator).path("paused").asBoolean());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void overwriteChangesGenerationAndFencesOldRequestsBeforeAnyRemoteWrite(boolean checkpoint) throws Exception {
        var job=requestCentral("Obsolete intent"); String old=runtime.requireModelActive(model); if (checkpoint) models.createModelCheckpoint(model); var exported=models.exportModel(model); assertEquals(checkpoint ? "archi-model-export-v2" : "archi-model-export-v1",exported.format());
        models.importModel(exported,true); assertNotEquals(old,runtime.metadata().modelGeneration(model)); assertThrows(PublicationModel.Failure.class,() -> runtime.requireModelActive(model));
        central.run(job.id()); assertEquals(0,fixture(null).path("writes").asInt()); inbound.refresh(model,"fixture",publisher,owned.get(model).elementId()); assertEquals("CONFLICT",inbound.status(model,"fixture",publisher,"").bindings().getFirst().state());
        attach(model); assertThrows(PublicationModel.Failure.class,() -> catalog.importObject(model,architect,originalImport));
        var stored=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.CENTRAL_EDIT,job.id()).orElseThrow(); assertTrue(Set.of("MODEL_GENERATION_CHANGED","MODEL_REATTACHMENT_REQUIRED").contains(stored.data().path("code").asText()));
        approve(model); inbound.refresh(model,"fixture",publisher,owned.get(model).elementId()); assertEquals("Initial central",local(model,"property:central"));
    }
    @Test void attachmentReviewDetectsLocalRemoteAndScopeChangesWithoutDiscardingIdentity() throws Exception {
        operations.pauseModel(operator,model,runtime.modelScope(model).version()); var review=operations.previewAttachment(operator,model);
        property(model,"owner","New local value"); assertThrows(PublicationModel.Failure.class,() -> operations.attach(operator,model,new Decision(review.details().path("scopeVersion").asLong(),review.digest())));
        var next=operations.previewAttachment(operator,model); fixture(json.createObjectNode().put("id","card-a").set("fields",json.createObjectNode().put("name","New remote name")));
        assertThrows(PublicationModel.Failure.class,() -> operations.attach(operator,model,new Decision(next.details().path("scopeVersion").asLong(),next.digest()))); assertTrue(runtime.modelScope(model).data().path("paused").asBoolean());
        assertEquals(1,runtime.metadata().bindings(runtime.settings().instanceId(),runtime.settings().targets().getFirst().instanceId(),model,"",100).size()); attach(model);
        approve(model); inbound.refresh(model,"fixture",publisher,owned.get(model).elementId()); assertEquals("Initial name",local(model,"name")); assertEquals("CONFLICT",inbound.status(model,"fixture",publisher,"").bindings().getFirst().state());
    }
    @Test void pagedFullScanResumesFromDurableCursorAndRepairsMissedSignalsWithoutChangingTags() throws Exception {
        for (int i=0;i<11;i++) provision(i!=10); models.createModelTag(model,"before","Before scan"); var tag=models.getSnapshot(model,"before");
        fixture(json.createObjectNode().put("id","card-a").set("fields",json.createObjectNode().put("name","Changed without signal")));
        var request=new ScanRequest(UUID.randomUUID().toString(),"fixture"); var job=operations.scan(operator,request); assertEquals(job.id(),operations.scan(operator,request).id()); operations.runScan(job.id());
        var first=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.RECONCILIATION,job.id()).orElseThrow(); assertEquals(10,first.data().path("processed").asInt()); assertFalse(first.data().path("cursor").asText().isEmpty());
        var restarted=copy(); for (int i=0;i<5;i++) restarted.runScan(job.id()); var done=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.RECONCILIATION,job.id()).orElseThrow();
        assertEquals("COMPLETE",done.data().path("state").asText()); assertEquals(12,done.data().path("processed").asInt()); assertEquals("Changed without signal",local(model,"name")); assertEquals(tag,models.getSnapshot(model,"before")); assertEquals(0,fixture(null).path("writes").asInt());
        long head=models.getSnapshot(model).path("headRevision").asLong(); operations.runScan(job.id()); assertEquals(head,models.getSnapshot(model).path("headRevision").asLong());
    }
    @Test void compatibleMappingMigrationIsReviewedPausedAndRequiresNewPublicationApproval() throws Exception {
        property(model,"owner","Unowned remote"); var selected=List.of(owned.get(model).elementId());
        var spec=new PublicationModel.PolicySpec(PublicationModel.Mode.HEAD,"HEAD",selected,List.of(),Set.copyOf(selected));
        var service=(IntegrationOperationsService)io.quarkus.arc.ClientProxy.unwrap(operations); var publication=service.publication;
        var initial=publication.preview(model,"fixture",publisher,spec); publication.approve(model,"fixture",publisher,new PublicationModel.Approval(spec,0,initial.digest()));
        var oldJob=publication.enqueue(model,"fixture",publisher,new PublicationModel.RunRequest(UUID.randomUUID().toString(),null)); publication.runJob(oldJob.id());
        var bean=(IntegrationRuntime)io.quarkus.arc.ClientProxy.unwrap(runtime); var settings=IntegrationRuntime.class.getDeclaredField("settings"); settings.setAccessible(true); var old=runtime.settings();
        var definition=(ObjectNode)json.valueToTree(old); ((ObjectNode)definition.path("targets").get(0).path("mapping")).put("version","m6-migrated-v2");
        try {
            settings.set(bean,json.treeToValue(definition,IntegrationConfiguration.class));
            assertThrows(PublicationModel.Failure.class,() -> publication.enqueue(model,"fixture",publisher,new PublicationModel.RunRequest(UUID.randomUUID().toString(),null)));
            var blocked=operations.previewAttachment(operator,model); assertFalse(blocked.details().path("canAttach").asBoolean());
            operations.pause(operator,controlVersion()); var reviewed=operations.previewAttachment(operator,model); assertTrue(reviewed.details().path("canAttach").asBoolean(),reviewed.details().toString()); assertEquals(1,reviewed.details().path("mappingMigrations").size());
            operations.attach(operator,model,new Decision(reviewed.details().path("scopeVersion").asLong(),reviewed.digest())); resume();
            var binding=runtime.metadata().bindings(runtime.settings().instanceId(),runtime.settings().targets().getFirst().instanceId(),model,"",100).getFirst(); assertEquals("m6-migrated-v2",binding.mappingVersion());
            assertThrows(PublicationModel.Failure.class,() -> publication.enqueue(model,"fixture",publisher,new PublicationModel.RunRequest(UUID.randomUUID().toString(),null)));
            var next=publication.preview(model,"fixture",publisher,spec); publication.approve(model,"fixture",publisher,new PublicationModel.Approval(spec,publication.policy(model,"fixture",publisher).version(),next.digest()));
            var job=publication.enqueue(model,"fixture",publisher,new PublicationModel.RunRequest(UUID.randomUUID().toString(),null)); publication.runJob(job.id()); assertEquals("SYNCED",runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.PUBLICATION_JOB,job.id()).orElseThrow().data().path("state").asText()); assertEquals(0,fixture(null).path("writes").asInt());
        } finally { settings.set(bean,old); }
    }
    @Test void mappingMigrationCannotEraseAnUnknownRemoteOutcome() throws Exception {
        var job=requestCentral("Uncertain before migration"); fixture(json.createObjectNode().put("fault","patch-commit-503")); central.run(job.id()); fixture(json.createObjectNode().put("fault",""));
        operations.pause(operator,controlVersion()); var bean=(IntegrationRuntime)io.quarkus.arc.ClientProxy.unwrap(runtime); var field=IntegrationRuntime.class.getDeclaredField("settings"); field.setAccessible(true); var old=runtime.settings();
        var definition=(ObjectNode)json.valueToTree(old); ((ObjectNode)definition.path("targets").get(0).path("mapping")).put("version","m6-blocked-v2");
        try {
            field.set(bean,json.treeToValue(definition,IntegrationConfiguration.class)); var review=operations.previewAttachment(operator,model); assertFalse(review.details().path("canAttach").asBoolean());
            assertTrue(review.details().toString().contains("MIGRATION_UNCERTAIN_OUTCOME")); assertThrows(PublicationModel.Failure.class,() -> operations.attach(operator,model,new Decision(review.details().path("scopeVersion").asLong(),review.digest())));
            var lane=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.CENTRAL_LANE,CentralEditModel.laneId(runtime.settings().targets().getFirst().instanceId(),owned.get(model).canonicalId())).orElseThrow(); assertTrue(lane.data().has("inFlight")); assertEquals(1,fixture(null).path("writes").asInt());
        } finally { field.set(bean,old); }
    }
    @Test void metadataAndModelRecoveryPreserveAmbiguousFenceAndResumeTwoModelsOnlyAfterReviewedAttachment() throws Exception {
        String numeric="1.12345678901234567890123456789"; var proposed=Map.<String,JsonNode>of("property:budget",json.getNodeFactory().textNode(numeric));
        var numericPreview=central.preview(model,"fixture",architect,new CentralEditModel.PreviewRequest("HEAD",owned.get(model).elementId(),proposed));
        var numericJob=central.submit(model,"fixture",architect,new CentralEditModel.Request(UUID.randomUUID().toString(),"HEAD",owned.get(model).elementId(),proposed,numericPreview.digest()));
        String second=provision(true); for (String id:owned.keySet()) { models.createModelTag(id,"before","Recovery tag"); models.createModelCheckpoint(id); }
        var exports=new LinkedHashMap<String,AdminModelExport>(); var tags=new HashMap<String,JsonNode>(); for (String id:owned.keySet()) { exports.put(id,models.exportModel(id)); tags.put(id,models.getSnapshot(id,"before")); }
        var job=requestCentral("Verified after restore"); fixture(json.createObjectNode().put("fault","patch-commit-503")); central.run(job.id()); job=central.get(model,"fixture",job.id(),architect); assertEquals("AMBIGUOUS",job.state()); assertEquals(1,fixture(null).path("writes").asInt());
        fixture(json.createObjectNode().put("fault","")); operations.pause(operator,controlVersion()); var archive=operations.exportArchive(operator);
        assertTrue(archive.nodes().stream().anyMatch(n -> n.label().equals("IntegrationStateDocument") && "CENTRAL_LANE".equals(n.properties().get("kind")) && n.properties().get("dataJson").toString().contains("inFlight")));
        assertThrows(PublicationModel.Failure.class,() -> operations.previewRestore(operator,archive));
        var tampered=(ObjectNode)json.valueToTree(archive); tampered.put("digest","0".repeat(64)); var invalid=api("/archive/preview","POST",tampered,"operator","admin"); assertEquals(400,invalid.statusCode(),invalid.body()); assertTrue(invalid.body().contains("ARCHIVE_DIGEST_MISMATCH"));
        // A fresh independent database receives only metadata, atomically and paused.
        try (var driver=GraphDatabase.driver(destination,AuthTokens.basic("neo4j",destinationPassword))) {
            var repository=new Neo4jIntegrationStateRepository(driver,json,false); repository.installSchema(); repository.save(new StateDocument(runtime.settings().instanceId(),DocumentKind.OPERATIONS,"control",0,json.createObjectNode().put("paused",true)));
            var external=archive.nodes().stream().filter(n -> n.label().equals("IntegrationExternalBinding")).findFirst().orElseThrow(); var bad=new HashMap<>(external.properties()); String collision="urn:conflict:"+UUID.randomUUID(); bad.put("canonicalId",collision);
            try (var session=driver.session()) { session.run("CREATE (c:IntegrationCanonicalObject {canonicalId:$canonical,kind:'Application'}) CREATE (e:IntegrationExternalBinding) SET e=$p",Map.of("canonical",collision,"p",bad)).consume(); }
            var ordered=new ArrayList<>(archive.nodes()); ordered.remove(external); ordered.add(external);
            assertThrows(PublicationModel.Failure.class,() -> repository.restoreArchive(runtime.settings().instanceId(),ordered));
            try (var session=driver.session()) {
                assertEquals(1,session.run("MATCH (n {instanceId:$instance}) RETURN count(n) AS count",Map.of("instance",runtime.settings().instanceId())).single().get("count").asInt());
                session.run("MATCH (n) WHERE (n:IntegrationCanonicalObject OR n:IntegrationExternalBinding) AND n.canonicalId=$canonical DETACH DELETE n",Map.of("canonical",collision)).consume();
            }
            repository.restoreArchive(runtime.settings().instanceId(),archive.nodes());
            var restoredNumber=repository.load(runtime.settings().instanceId(),DocumentKind.CENTRAL_EDIT,numericJob.id()).orElseThrow().data().path("proposed").path("property:budget"); assertEquals(numeric,restoredNumber.decimalValue().toPlainString());
            assertEquals(2,repository.bindings(runtime.settings().instanceId(),runtime.settings().targets().getFirst().instanceId(),null,"",100).size());
            var restored=repository.load(runtime.settings().instanceId(),DocumentKind.CENTRAL_EDIT,job.id()).orElseThrow(); assertEquals("AMBIGUOUS",restored.data().path("state").asText()); assertTrue(restored.data().path("modelGeneration").asText().startsWith("restored:"));
            assertFalse(repository.load(runtime.settings().instanceId(),DocumentKind.INBOUND_POLICY,InboundAuthorization.policyId(model,"fixture")).orElseThrow().data().path("enabled").asBoolean());
            assertTrue(repository.load(runtime.settings().instanceId(),DocumentKind.MODEL_SCOPE,runtime.modelScope(model).id()).orElseThrow().data().path("paused").asBoolean());
        }
        // Restore model packages via the existing operation/checkpoint path, then their independent metadata archive.
        for (String id:owned.keySet()) models.deleteModel(id,true); try (var driver=sourceDriver()) { clearMetadata(driver); }
        for (var entry:exports.entrySet()) models.importModel(entry.getValue(),false);
        operations.pause(operator,0); var preview=operations.previewRestore(operator,archive); operations.restore(operator,new RestoreRequest(archive,preview.digest()));
        assertTrue(operations.overview(operator).path("paused").asBoolean()); central.run(job.id()); assertEquals(1,fixture(null).path("writes").asInt()); resume();
        for (String id:owned.keySet()) { assertThrows(PublicationModel.Failure.class,() -> runtime.requireModelActive(id)); attach(id); assertEquals(tags.get(id),models.getSnapshot(id,"before")); }
        // Explicit read-only outcome verification clears the old fence; stale generations still cannot replay it.
        var current=central.get(model,"fixture",job.id(),architect); central.verifyOutcome(model,"fixture",job.id(),architect,new CentralEditModel.Outcome(current.version())); central.run(job.id());
        assertEquals(1,fixture(null).path("writes").asInt());
        for (String id:owned.keySet()) {
            approve(id); inbound.refresh(id,"fixture",publisher,owned.get(id).elementId()); var review=inbound.inspect(id,"fixture",publisher,owned.get(id).elementId());
            inbound.acceptRemote(id,"fixture",publisher,new ExternalChangeIngestService.Resolution(owned.get(id).elementId(),review.digest())); assertEquals("Verified after restore",local(id,"property:central")); assertEquals(tags.get(id),models.getSnapshot(id,"before"));
        }
        assertEquals("Verified after restore",local(second,"property:central")); assertEquals(1,fixture(null).path("writes").asInt());
    }
    IntegrationOperationsService copy() { var original=(IntegrationOperationsService)io.quarkus.arc.ClientProxy.unwrap(operations); var result=new IntegrationOperationsService(); result.runtime=runtime; result.authorization=original.authorization; result.models=models; result.delivery=original.delivery; result.execution=original.execution; result.inbound=inbound; result.publication=original.publication; result.json=json; return result; }
}
