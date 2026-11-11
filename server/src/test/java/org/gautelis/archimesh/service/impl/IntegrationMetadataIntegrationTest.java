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
                                OR n:IntegrationModelBinding OR n:IntegrationStateDocument)
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
