package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.gautelis.archimesh.integration.IntegrationStateRepository;
import org.gautelis.archimesh.integration.Neo4jIntegrationStateRepository;
import org.gautelis.archimesh.model.RevisionRange;
import org.gautelis.archimesh.service.RepositoryOnlyProfile;
import org.junit.jupiter.api.*;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs in the existing disposable server gate; never resets a shared database. */
@QuarkusTest
@TestProfile(RepositoryOnlyProfile.class)
class IntegrationMetadataIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private Neo4jRepositoryImpl modelRepository;
    private Driver driver;
    private Neo4jIntegrationStateRepository metadata;
    private String scope;
    private String model;
    private final List<String> models = new ArrayList<>();

    @BeforeEach void start() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        scope="integration-it-" + UUID.randomUUID(); model=scope+"-model";
        modelRepository = new Neo4jRepositoryImpl();
        modelRepository.uri=System.getenv().getOrDefault("NEO4J_URI","bolt://localhost:7687");
        modelRepository.username=System.getenv().getOrDefault("NEO4J_USER","neo4j");
        modelRepository.password=System.getenv().getOrDefault("NEO4J_PASSWORD","devpassword");
        modelRepository.init(); models.add(model);
        driver=GraphDatabase.driver(modelRepository.uri,AuthTokens.basic(modelRepository.username,modelRepository.password));
        metadata=new Neo4jIntegrationStateRepository(driver,json,false);
        metadata.installSchema();
    }

    @AfterEach void stop() {
        if (modelRepository == null) return;
        try {
            for (String id : models) modelRepository.deleteModel(id);
            try (var session=driver.session()) {
                session.executeWrite(tx -> {
                    tx.run("""
                            MATCH (n) WHERE (n:IntegrationCanonicalObject OR n:IntegrationExternalBinding
                                OR n:IntegrationModelBinding OR n:IntegrationPublicationIdentity OR n:IntegrationIdentityClaim OR n:IntegrationStateDocument)
                              AND (n.instanceId=$scope OR n.targetInstanceId=$scope OR n.canonicalId STARTS WITH $prefix)
                            DETACH DELETE n
                            """,Map.of("scope",scope,"prefix","urn:"+scope+":" )).consume();
                    return null;
                });
            }
        } finally { metadata.close(); driver.close(); modelRepository.close(); }
    }

    private ImportReservation request(String source, String entity, String resource, String candidate, String batch) {
        return new ImportReservation(new ModelElementKey(scope,source,EntityKind.ELEMENT,"elem:"+entity),
                new CanonicalObject("urn:"+scope+":"+candidate,"application"),
                new ExternalResourceKey("turboea",scope,ResourceKind.CARD,resource),"ApplicationComponent","mapping-v1",batch);
    }

    private org.gautelis.federation.Identity.Representation source(String id) {
        return new org.gautelis.federation.Identity.Representation(new org.gautelis.federation.Identity.Resource(
                new org.gautelis.federation.Identity.Repository("datahub",scope),org.gautelis.federation.Identity.ResourceKind.OBJECT,id),"dataset");
    }
    private org.gautelis.federation.Identity.Canonical neutral(String id) {
        return new org.gautelis.federation.Identity.Canonical("urn:"+scope+":"+id,"catalog.dataset");
    }
    private org.gautelis.federation.IdentityRegistry.Evidence evidence(String id) {
        return new org.gautelis.federation.IdentityRegistry.Evidence(id,"synthetic-steward","a".repeat(64));
    }
    @Test void independentConcurrentSourceReservationsPersistExactLongNativeIdentity() throws Exception {
        var source=source("urn:li:dataset:"+"å".repeat(2000)); var registry=metadata.identities(scope);
        try (var executor=Executors.newFixedThreadPool(4)) {
            var calls=new ArrayList<java.util.concurrent.Callable<org.gautelis.federation.IdentityRegistry.Binding>>();
            for (int i=0;i<12;i++) { int n=i; calls.add(() -> registry.reserveSource(source,neutral("candidate-"+n),evidence("reserve-"+n))); }
            var results=executor.invokeAll(calls); var winner=results.getFirst().get();
            for (var result:results) assertEquals(winner,result.get());
            var reopened=new Neo4jIntegrationStateRepository(driver,json,false); reopened.installSchema();
            assertEquals(winner,reopened.identities(scope).find(source.resource()).orElseThrow());
            assertEquals(source.resource().nativeId(),winner.representation().resource().nativeId());
        }
        try (var session=driver.session()) {
            assertEquals(0,session.run("MATCH (b:IntegrationModelBinding {instanceId:$scope}) RETURN count(b) AS n",Map.of("scope",scope)).single().get("n").asInt());
            var stored=session.run("MATCH (e:IntegrationExternalBinding {targetInstanceId:$scope}) RETURN e.nativeId AS native,e.resourceId AS index",Map.of("scope",scope)).single();
            assertEquals(source.resource().nativeId(),stored.get("native").asString()); assertTrue(stored.get("index").asString().startsWith("sha256:"));
        }
    }
    @Test void independentBindingSharesCanonicalAndOneModelOccurrenceAcrossDifferentNativeTypes() {
        var source=source("urn:li:dataset:synthetic"); var registry=metadata.identities(scope);
        var reserved=registry.reserveSource(source,neutral("dataset"),evidence("source"));
        var representation=new org.gautelis.federation.Identity.Representation(new org.gautelis.federation.Identity.Resource(
                new org.gautelis.federation.Identity.Repository("turboea",scope),org.gautelis.federation.Identity.ResourceKind.OBJECT,"card-dataset"),"DataObject");
        registry.bindRepresentation(reserved.canonical(),representation,evidence("representation"));
        assertEquals(registry.lookup(source.resource()),registry.lookup(representation.resource()));
        var canonical=new CanonicalObject(reserved.canonical().id(),reserved.canonical().semanticKind());
        var nativeSource=new ExternalResourceKey("datahub",scope,ResourceKind.ENTITY,source.resource().nativeId());
        var first=metadata.reserveImport(new ImportReservation(new ModelElementKey(scope,model,EntityKind.ELEMENT,"elem:data"),canonical,nativeSource,"DataObject","datahub-v1","batch-source","CREATE","dataset"));
        var card=new ExternalResourceKey("turboea",scope,ResourceKind.CARD,"card-dataset");
        var second=metadata.reserveImport(new ImportReservation(new ModelElementKey(scope,model,EntityKind.ELEMENT,"elem:other"),canonical,card,"DataObject","turboea-v1","batch-mirror","CREATE","DataObject"));
        assertEquals(first,second); assertEquals("dataset",second.remoteType()); assertEquals("catalog.dataset",second.canonical().kind());
        assertEquals(first,metadata.findModelBinding(scope,model,card).orElseThrow());
        assertEquals(first,metadata.findModelBinding(scope,model,nativeSource).orElseThrow());
    }
    @Test void registryRejectsSemanticReassignmentRequestReuseOwnerChangeAndIndexMismatch() {
        var registry=metadata.identities(scope); var source=source("urn:li:dataset:synthetic");
        var canonical=neutral("dataset"); registry.reserveSource(source,canonical,evidence("request"));
        var other=registry.reserveSource(source("urn:li:dataset:another"),neutral("another"),evidence("another-source"));
        assertThrows(RuntimeException.class,() -> registry.bindRepresentation(canonical,other.representation(),evidence("cannot-merge")));
        assertThrows(RuntimeException.class,() -> registry.reserveSource(source("urn:li:dataset:renamed"),canonical,evidence("cannot-rename")));
        assertThrows(RuntimeException.class,() -> registry.reserveSource(source,neutral("changed-request"),evidence("request")));
        assertThrows(RuntimeException.class,() -> registry.reserveSource(source,new org.gautelis.federation.Identity.Canonical(canonical.id(),"catalog.data-product"),evidence("wrong-kind")));
        assertThrows(RuntimeException.class,() -> metadata.identities(scope+"-another-owner").find(source.resource()));
        try (var session=driver.session()) { session.run("MATCH (e:IntegrationExternalBinding {canonicalId:$canonical}) SET e.nativeId='different-native-id'",Map.of("canonical",canonical.id())).consume(); }
        assertThrows(RuntimeException.class,() -> registry.find(source.resource()));
        assertThrows(RuntimeException.class,() -> registry.reserveSource(source,canonical,evidence("hash-conflict")));
    }
    @Test void applicationReferencesCanStartInTurboEaWhileDatasetReferencesStartInDataHub() {
        var registry=metadata.identities(scope);
        var application=new org.gautelis.federation.Identity.Canonical("urn:"+scope+":application","enterprise.application");
        var card=new org.gautelis.federation.Identity.Representation(new org.gautelis.federation.Identity.Resource(
                new org.gautelis.federation.Identity.Repository("turboea",scope),org.gautelis.federation.Identity.ResourceKind.OBJECT,"application-card"),"Application");
        registry.reserveSource(card,application,evidence("application-source"));
        var reference=new org.gautelis.federation.Identity.Representation(new org.gautelis.federation.Identity.Resource(
                new org.gautelis.federation.Identity.Repository("datahub",scope),org.gautelis.federation.Identity.ResourceKind.OBJECT,"urn:li:application:reviewed-reference"),"application");
        registry.bindRepresentation(application,reference,evidence("reviewed-application-reference"));
        var dataset=registry.reserveSource(source("urn:li:dataset:separate"),neutral("dataset"),evidence("dataset-source"));
        assertEquals(application,registry.lookup(reference.resource()).orElseThrow());
        assertEquals("Application",registry.find(card.resource()).orElseThrow().representation().externalType());
        assertEquals("application",registry.find(reference.resource()).orElseThrow().representation().externalType());
        assertNotEquals(application,dataset.canonical());
    }
    @Test void registryOnlyArchiveRestoresFullIdentityProofAndUnknownOutcomeWithoutAnyModels() {
        var registry=metadata.identities(scope); var source=source("urn:li:dataset:synthetic");
        var binding=registry.reserveSource(source,neutral("dataset"),evidence("request"));
        metadata.save(new StateDocument(scope,DocumentKind.CENTRAL_LANE,"unknown",0,json.createObjectNode().put("canonicalId",binding.canonical().id()).set("inFlight",json.createObjectNode().put("operationId","keep-unknown"))));
        var archive=metadata.archive(scope); assertTrue(archive.stream().anyMatch(n -> n.label().equals("IntegrationCanonicalObject")));
        assertTrue(archive.stream().anyMatch(n -> "IDENTITY_PROOF".equals(n.properties().get("kind"))));
        try (var session=driver.session()) { session.run("MATCH (n) WHERE n.instanceId=$scope OR n.registryInstanceId=$scope OR n.targetInstanceId=$scope DETACH DELETE n",Map.of("scope",scope)).consume(); }
        metadata.save(new StateDocument(scope,DocumentKind.OPERATIONS,"control",0,json.createObjectNode().put("paused",true)));
        metadata.restoreArchive(scope,archive);
        assertEquals(binding,metadata.identities(scope).find(source.resource()).orElseThrow());
        assertEquals("keep-unknown",metadata.load(scope,DocumentKind.CENTRAL_LANE,"unknown").orElseThrow().data().path("inFlight").path("operationId").asText());
        assertThrows(RuntimeException.class,() -> metadata.restoreArchive(scope,archive));
    }
    @Test void semanticClassificationRequiresPauseRejectsUnknownAndStaleReviewsAndKeepsAllIds() {
        modelRepository.registerModel(model,"Classification fixture");
        var first=metadata.reserveImport(request(model,"a","legacy","candidate","batch"));
        String canonical=first.canonical().canonicalId();
        assertThrows(RuntimeException.class,() -> metadata.previewIdentityMigration(scope,canonical,"enterprise.application"));
        metadata.save(new StateDocument(scope,DocumentKind.OPERATIONS,"control",0,json.createObjectNode().put("paused",true)));
        var review=metadata.previewIdentityMigration(scope,canonical,"enterprise.application");
        var uncertain=metadata.save(new StateDocument(scope,DocumentKind.CENTRAL_LANE,"lane",0,json.createObjectNode().put("canonicalId",canonical).set("inFlight",json.createObjectNode().put("operationId","unknown"))));
        assertThrows(RuntimeException.class,() -> metadata.migrateIdentity(scope,canonical,"enterprise.application",review.path("metadataDigest").asText(),"steward"));
        metadata.save(new StateDocument(scope,DocumentKind.CENTRAL_LANE,"lane",uncertain.version(),json.createObjectNode().put("canonicalId",canonical)));
        assertThrows(RuntimeException.class,() -> metadata.migrateIdentity(scope,canonical,"enterprise.application",review.path("metadataDigest").asText(),"steward"));
        var refreshed=metadata.previewIdentityMigration(scope,canonical,"enterprise.application");
        metadata.migrateIdentity(scope,canonical,"enterprise.application",refreshed.path("metadataDigest").asText(),"steward");
        var migrated=metadata.findBinding(first.model()).orElseThrow();
        assertEquals(first.model(),migrated.model()); assertEquals(first.external(),migrated.external()); assertEquals(first.opBatchId(),migrated.opBatchId());
        assertEquals(canonical,migrated.canonical().canonicalId()); assertEquals("enterprise.application",migrated.canonical().kind()); assertEquals("application",migrated.remoteType());
        assertTrue(metadata.load(scope,DocumentKind.MODEL_SCOPE,org.gautelis.archimesh.integration.PublicationJson.key("model-scope:",model)).orElseThrow().data().path("paused").asBoolean());
        var coreResource=new org.gautelis.federation.Identity.Resource(new org.gautelis.federation.Identity.Repository("turboea",scope),org.gautelis.federation.Identity.ResourceKind.OBJECT,"legacy");
        assertEquals(canonical,metadata.identities(scope).lookup(coreResource).orElseThrow().id());
        assertDoesNotThrow(() -> metadata.archive(scope));
    }

    @Test void repeatAndCrossModelImportsReuseCanonicalIdentityAndDurableReservation() {
        var first=metadata.reserveImport(request(model,"a","remote","candidate-a","batch-a"));
        var repeat=metadata.reserveImport(request(model,"another","remote","candidate-b","batch-b"));
        assertEquals(first,repeat);
        assertEquals(BindingState.PREPARED,first.state());
        var second=metadata.reserveImport(request(model+"-other","b","remote","candidate-c","batch-c"));
        assertEquals(first.canonical(),second.canonical()); assertNotEquals(first.model(),second.model());
        var reopened=new Neo4jIntegrationStateRepository(driver,json,false);
        reopened.installSchema();
        assertEquals(first,reopened.findBinding(first.model()).orElseThrow());
        assertEquals(first.canonical(),reopened.findCanonical(first.external()).orElseThrow());
    }

    @Test void concurrentImportsConvergeWithoutDuplicateCanonicalOrModelBindings() throws Exception {
        try (var executor=Executors.newFixedThreadPool(4)) {
            var calls=new ArrayList<java.util.concurrent.Callable<ReservedImport>>();
            for (int i=0;i<12;i++) {
                int n=i;
                calls.add(() -> metadata.reserveImport(request(model,"element-"+n,"remote","candidate-"+n,"batch-"+n)));
            }
            var results=executor.invokeAll(calls);
            var winner=results.getFirst().get();
            for (var result : results) assertEquals(winner,result.get());
        }
        try (var session=driver.session()) {
            assertEquals(1,session.run("MATCH (b:IntegrationModelBinding {instanceId:$scope}) RETURN count(b) AS n",
                    Map.of("scope",scope)).single().get("n").asInt());
            assertEquals(1,session.run("MATCH (c:IntegrationCanonicalObject) WHERE c.canonicalId STARTS WITH $prefix RETURN count(c) AS n",
                    Map.of("prefix","urn:"+scope+":" )).single().get("n").asInt());
        }
    }

    @Test void constraintAndTypeConflictsRollBackAllPartialMetadata() {
        var first=metadata.reserveImport(request(model,"a","remote-a","candidate-a","batch-a"));
        var collision=request(model,"a","remote-b","candidate-b","batch-b");
        assertThrows(RuntimeException.class,() -> metadata.reserveImport(collision));
        assertTrue(metadata.findCanonical(collision.external()).isEmpty());
        var sameCanonical=request(model+"-other","b","remote-b","candidate-a","batch-b");
        assertThrows(RuntimeException.class,() -> metadata.reserveImport(sameCanonical));
        assertTrue(metadata.findCanonical(sameCanonical.external()).isEmpty());
        var wrongType=new ImportReservation(request(model,"c","remote-a","candidate-c","batch-c").model(),
                first.canonical(),first.external(),"BusinessActor","mapping-v1","batch-c");
        assertThrows(IllegalStateException.class,() -> metadata.reserveImport(wrongType));
        assertEquals(first,metadata.findBinding(first.model()).orElseThrow());
    }

    @Test void stateDocumentsPersistAllDomainsWithCompareAndSetAndDefensiveCopies() {
        var data=json.createObjectNode().put("sourceModelId",model).put("mappingVersion","v1")
                .put("policyVersion","p1").put("status","PENDING");
        for (DocumentKind kind : DocumentKind.values()) {
            var first=metadata.save(new StateDocument(scope,kind,"state",0,data));
            assertEquals(1,first.version());
            ((com.fasterxml.jackson.databind.node.ObjectNode) first.data()).put("status","changed");
            assertEquals(first,metadata.load(scope,kind,"state").orElseThrow());
            assertThrows(IllegalStateException.class,() -> metadata.save(new StateDocument(scope,kind,"state",0,data)));
            var next=metadata.save(new StateDocument(scope,kind,"state",first.version(),data.deepCopy().put("status","SYNCED")));
            assertEquals(2,next.version());
            assertEquals("SYNCED",metadata.load(scope,kind,"state").orElseThrow().data().path("status").asText());
        }
        assertThrows(IllegalStateException.class,() -> metadata.save(new StateDocument(scope,DocumentKind.JOB,"missing",4,data)));
        assertTrue(metadata.load(scope,DocumentKind.JOB,"missing").isEmpty(),"failed CAS must not insert a placeholder");
    }

    @Test void activationRequiresAcceptanceAndMetadataSurvivesCheckpointRebuildAndDelete() {
        modelRepository.registerModel(model,"Integration isolation");
        var reserved=metadata.reserveImport(request(model,"a","remote-a","candidate-a","batch-a"));
        assertThrows(IllegalStateException.class,() -> metadata.confirmImport(reserved.model(),reserved.opBatchId()));
        var batch=json.createObjectNode().put("modelId",model).put("opBatchId",reserved.opBatchId());
        batch.set("assignedRevisionRange",json.createObjectNode().put("from",1).put("to",1));
        var op=batch.putArray("ops").addObject().put("type","CreateElement");
        op.putObject("element").put("id",reserved.model().entityId()).put("archimateType","ApplicationComponent").put("name","Imported");
        op.putObject("causal").put("lamport",1).put("clientId","integration-test");
        modelRepository.commitBatch(model,reserved.opBatchId(),new RevisionRange(1,1),batch);
        assertThrows(IllegalStateException.class,() -> metadata.confirmImport(reserved.model(),"unrelated-batch"));
        var active=metadata.confirmImport(reserved.model(),reserved.opBatchId());
        assertEquals(BindingState.ACTIVE,active.state()); assertEquals(1,active.acceptedRevision());
        assertEquals(active,metadata.confirmImport(reserved.model(),reserved.opBatchId()));
        var before=modelRepository.loadSnapshot(model);
        var checkpoint=modelRepository.createCheckpoint(model);
        modelRepository.rebuildProjection(model,checkpoint,json.createArrayNode(),1);
        assertEquals(before,modelRepository.loadSnapshot(model));
        assertEquals(active,metadata.findBinding(active.model()).orElseThrow());
        modelRepository.deleteModel(model);
        assertEquals(active,metadata.findBinding(active.model()).orElseThrow(),"model deletion must not delete integration mappings");
        try (var session=driver.session()) {
            assertEquals(0,session.run("MATCH (n) WHERE (n:IntegrationModelBinding OR n:IntegrationExternalBinding "
                    + "OR n:IntegrationCanonicalObject OR n:IntegrationStateDocument) AND n.modelId IS NOT NULL RETURN count(n) AS n")
                    .single().get("n").asInt());
        }
    }
}
