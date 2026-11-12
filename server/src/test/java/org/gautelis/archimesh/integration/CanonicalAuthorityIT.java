package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.gautelis.archimesh.auth.AuthorizationSubject;
import org.gautelis.archimesh.model.Actor;
import org.gautelis.archimesh.service.ArchimeshService;
import org.gautelis.archimesh.wire.inbound.SubmitOpsMessage;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RUN_LOCAL_INFRA_IT",matches="(?i)true")
@TestProfile(ExternalChangeIngestIT.Profile.class)
@QuarkusTestResource(value=CanonicalAuthorityTestResource.class,restrictToAnnotatedClass=true)
class CanonicalAuthorityIT {
    @Inject ArchimeshService models;
    @Inject IntegrationRuntime runtime;
    @Inject ExternalObjectCatalogService catalog;
    @Inject ExternalChangeIngestService inbound;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.publication.url") String remote;
    private final ObjectMapper json=new ObjectMapper();
    private final AuthorizationSubject admin=new AuthorizationSubject("publisher",Set.of("model_writer"));
    private final AuthorizationSubject architect=new AuthorizationSubject("architect",Set.of("model_writer"));
    private String model;

    private void control(ObjectNode value) throws Exception {
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(remote+"/__fixture"))
                .POST(HttpRequest.BodyPublishers.ofString(value.toString())).build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(200,response.statusCode());
    }
    @AfterEach void cleanup() {
        if (model==null) return;
        models.deleteModel(model,true);
        String scope=runtime.settings().instanceId();
        try (var driver=GraphDatabase.driver(System.getenv("NEO4J_URI"),AuthTokens.basic(System.getenv("NEO4J_USER"),System.getenv("NEO4J_PASSWORD"))); var session=driver.session()) {
            session.executeWrite(tx -> {
                var ids=tx.run("MATCH (e:IntegrationExternalBinding {targetInstanceId:$scope}) RETURN e.canonicalId AS id",Map.of("scope",scope)).list(r -> r.get("id").asString());
                tx.run("MATCH (n) WHERE (n:IntegrationModelBinding OR n:IntegrationIdentityClaim OR n:IntegrationStateDocument) AND n.instanceId=$scope DETACH DELETE n",Map.of("scope",scope)).consume();
                tx.run("MATCH (e:IntegrationExternalBinding {targetInstanceId:$scope}) DETACH DELETE e",Map.of("scope",scope)).consume();
                tx.run("MATCH (c:IntegrationCanonicalObject) WHERE c.canonicalId IN $ids DETACH DELETE c",Map.of("ids",ids)).consume();
                return null;
            });
        }
    }
    @Test void canonicalOverrideControlsImportOrdinaryAcceptanceAndInboundWithoutChangingTags() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        String scope=runtime.settings().instanceId(); model=scope+"-override-"+UUID.randomUUID();
        models.registerModel(model,"Canonical authority fixture","publisher");
        models.updateModelAccessControl(model,Set.of("publisher"),Set.of("architect"),Set.of("reader"));
        control(json.createObjectNode().put("reset",true));
        var card=json.createObjectNode().put("id","override-card").put("type","Application").put("status","ACTIVE")
                .put("name","Central name").put("description","Central documentation");
        card.putObject("attributes").put("central","Owner"); control(json.createObjectNode().set("card",card));
        String canonical="urn:"+scope+":override";
        // A preexisting registry identity is deliberately reserved independently of this model import.
        runtime.metadata().reserveImport(new ImportReservation(new ModelElementKey(scope,scope+"-reservation",EntityKind.ELEMENT,"elem:reserved"),
                new CanonicalObject(canonical,"Application"),new ExternalResourceKey("turboea",scope,ResourceKind.CARD,"override-card"),
                "ApplicationComponent",runtime.settings().targets().getFirst().mapping().version(),UUID.randomUUID().toString()));
        var preview=catalog.preview(model,"HEAD",architect,"fixture","override-card");
        var imported=catalog.importObject(model,architect,new ExternalObjectCatalogService.ImportRequest(UUID.randomUUID().toString(),"fixture","override-card","HEAD",
                ExternalObjectCatalogService.ImportMode.CREATE,null,preview.digest()));
        assertEquals(canonical,imported.canonicalId());
        var policy=inbound.status(model,"fixture",admin,"");
        inbound.approve(model,"fixture",admin,new ExternalChangeIngestService.Approval(true,policy.policyVersion(),policy.targetInstanceId(),policy.mappingVersion()));
        String id=imported.elementId();
        assertEquals("Central documentation",InboundFields.element(models.getSnapshot(model),id).path("documentation").asText());
        var local=json.createArrayNode(); var op=local.addObject().put("type","UpdateElement").put("elementId",id);
        op.putObject("patch").put("name","Locally owned name");
        op.putObject("causal").put("clientId","architect").put("lamport",10000).put("opId",UUID.randomUUID().toString());
        models.onSubmitOps(model,new SubmitOpsMessage(models.getSnapshot(model).path("headRevision").asLong(),UUID.randomUUID().toString(),new Actor("architect","fixture"),local));
        assertEquals("Locally owned name",InboundFields.element(models.getSnapshot(model),id).path("name").asText());
        long head=models.getSnapshot(model).path("headRevision").asLong();
        var denied=json.createArrayNode(); var edit=denied.addObject().put("type","UpdateElement").put("elementId",id);
        edit.putObject("patch").put("documentation","Unauthorized high-clock edit");
        edit.putObject("causal").put("clientId","spoof").put("lamport",Long.MAX_VALUE).put("opId",UUID.randomUUID().toString());
        models.onSubmitOps(model,new SubmitOpsMessage(head,UUID.randomUUID().toString(),new Actor("external-sync:spoof","fixture"),denied));
        assertEquals(head,models.getSnapshot(model).path("headRevision").asLong());
        models.createModelTag(model,"before-refresh","Before source update"); var tag=models.getSnapshot(model,"before-refresh");
        control(json.createObjectNode().put("id","override-card").set("fields",json.createObjectNode().put("name","Ignored central name").put("description","New central documentation")));
        inbound.refresh(model,"fixture",admin,id);
        var element=InboundFields.element(models.getSnapshot(model),id);
        assertEquals("Locally owned name",element.path("name").asText());
        assertEquals("New central documentation",element.path("documentation").asText());
        assertEquals(tag,models.getSnapshot(model,"before-refresh"));
    }
}
