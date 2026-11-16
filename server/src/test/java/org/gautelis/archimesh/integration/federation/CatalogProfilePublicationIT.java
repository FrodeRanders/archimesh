package org.gautelis.archimesh.integration.federation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.gautelis.archimesh.auth.AuthorizationSubject;
import org.gautelis.archimesh.integration.*;
import org.gautelis.archimesh.model.Actor;
import org.gautelis.archimesh.service.ArchimeshService;
import org.gautelis.archimesh.wire.inbound.SubmitOpsMessage;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.PublicationModel.*;

@QuarkusTest @TestProfile(DatasetProjectionIT.Profile.class)
@QuarkusTestResource(value=CatalogProfileTestResource.class,restrictToAnnotatedClass=true)
class CatalogProfilePublicationIT {
    @Inject IntegrationRuntime runtime;
    @Inject ArchimeshService models;
    @Inject ExternalObjectCatalogService catalog;
    @Inject DatasetProjectionService projections;
    @Inject PublicationProfiles profiles;
    @Inject ArchitecturePublicationService publication;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="test.projection.source") String source;
    final ObjectMapper json=new ObjectMapper(); final HttpClient http=HttpClient.newHttpClient();
    final AuthorizationSubject bridge=new AuthorizationSubject("bridge",Set.of("federation_bridge")),publisher=new AuthorizationSubject("publisher",Set.of("model_writer"));
    String model,remote; static final String URN="urn:li:dataProduct:profile-orders";
    @BeforeEach void setup() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        remote=runtime.settings().targets().getFirst().baseUrl().toString();
        control(remote,json.createObjectNode().put("reset",true)); control(source,json.createObjectNode().put("reset",true));
        model=runtime.settings().instanceId()+"-catalog-profile-"+UUID.randomUUID(); models.registerModel(model,"Catalog connection fixture","publisher"); runtime.modelScope(model);
    }
    @AfterEach void cleanup() { if(model!=null) models.deleteModel(model,true); }
    JsonNode control(String url,ObjectNode body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(url+"/__fixture")); if(body==null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return json.readTree(http.send(request.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    DatasetProjectionService.Plan project(String urn) {
        var request=new DatasetProjectionService.Request("products",urn,UUID.randomUUID().toString()); var plan=projections.prepare(bridge,request);
        return projections.execute(bridge,new DatasetProjectionService.Execution(request,plan.intentId(),plan.version()));
    }
    StateDocument run() {
        var job=publication.enqueue(model,"fixture",publisher,new RunRequest(UUID.randomUUID().toString(),null)); publication.runJob(job.id());
        return runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.PUBLICATION_JOB,job.id()).orElseThrow();
    }
    @Test void explicitConnectionReusesCatalogProductAndRefreshesByExactUrnWithoutTakingOwnership() throws Exception {
        var product=project(URN); String card=product.targetId();
        var reviewed=catalog.preview(model,"HEAD",publisher,"fixture",card);
        var imported=catalog.importObject(model,publisher,new ExternalObjectCatalogService.ImportRequest(UUID.randomUUID().toString(),"fixture",card,"HEAD",ExternalObjectCatalogService.ImportMode.CREATE,null,reviewed.digest()));
        String app="ea-app-"+UUID.randomUUID(),canonical="ea-canonical-"+UUID.randomUUID();
        control(remote,json.createObjectNode().set("card",json.createObjectNode().put("id",app).put("type","Application").put("name","Enterprise application").put("status","ACTIVE")));
        var ops=json.createArrayNode(); ops.addObject().put("type","CreateElement").putObject("element").put("id","elem:app").put("archimateType","ApplicationComponent").put("name","Local name");
        ops.addObject().put("type","CreateRelationship").putObject("relationship").put("id","rel:connection").put("archimateType","ServingRelationship").put("sourceId","elem:app").put("targetId",imported.elementId());
        models.onSubmitOps(model,new SubmitOpsMessage(models.getSnapshot(model).path("headRevision").asLong(),UUID.randomUUID().toString(),new Actor("publisher","architecture-client"),ops));
        var identity=new PublicationIdentity(new ModelElementKey(runtime.settings().instanceId(),model,EntityKind.ELEMENT,"elem:app"),new CanonicalObject(canonical,"Application"),"ApplicationComponent","m3-fixture-v1","Application");
        runtime.metadata().reservePublicationIdentity(identity); runtime.metadata().recordPublishedIdentity(identity,new ExternalResourceKey("turboea",runtime.settings().instanceId(),ResourceKind.CARD,app),1,"seed-app");
        models.createModelTag(model,"reviewed","Explicit architectural connection");
        var spec=new PolicySpec(Mode.TAG,"reviewed",List.of("elem:app",imported.elementId()),List.of("rel:connection"),Set.of("rel:connection"),profiles.available().getFirst().pin(json));
        var preview=publication.preview(model,"fixture",publisher,spec); assertTrue(preview.plan().resources().stream().allMatch(r -> r.fields().isEmpty()));
        publication.approve(model,"fixture",publisher,new Approval(spec,0,preview.digest()));
        assertEquals("SYNCED",run().data().path("state").asText()); assertEquals(2,control(remote,null).path("writes").asInt());
        assertEquals(1,control(remote,null).path("relations").size()); assertEquals(2,control(remote,null).path("cards").size());
        assertTrue(runtime.metadata().catalogOwned(product.canonicalId())); assertFalse(runtime.metadata().catalogOwned(canonical));
        assertEquals(URN,runtime.metadata().findExternal(product.canonicalId(),"datahub",runtime.settings().instanceId()+"-datahub",ResourceKind.ENTITY).orElseThrow().resourceId());
        assertNotEquals(URN,card); assertNotEquals(product.canonicalId(),card);
        control(source,json.createObjectNode().put("name","Renamed source product"));
        assertEquals("PROJECTION_SOURCE_REFRESH_REQUIRED",run().data().path("code").asText()); assertEquals(2,control(remote,null).path("writes").asInt());
        assertEquals(card,project(URN).targetId()); assertEquals("SYNCED",run().data().path("state").asText());
        assertEquals("Renamed source product",control(remote,null).path("cards").path(card).path("name").asText());
        var other=project(URN+"-new-native-identity"); assertNotEquals(product.canonicalId(),other.canonicalId()); assertNotEquals(card,other.targetId());
        assertEquals("SYNCED",run().data().path("state").asText());
        assertEquals(card,control(remote,null).path("relations").elements().next().path("target_id").asText());
        control(source,json.createObjectNode().put("removed",true)); assertEquals("CONFLICT",run().data().path("state").asText());
        assertEquals(1,control(remote,null).path("relations").size()); assertEquals(3,control(remote,null).path("cards").size());
        assertTrue(runtime.metadata().list(runtime.settings().instanceId(),DocumentKind.ASSERTION_CLAIM,"","",100).isEmpty(),"Explicit connection creates no logical READ/WRITE or catalog consumer assertion");
    }
}
