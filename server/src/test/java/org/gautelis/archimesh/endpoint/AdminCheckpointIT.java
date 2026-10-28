package org.gautelis.archimesh.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.gautelis.archimesh.service.ArchimeshService;
import org.gautelis.archimesh.service.Neo4jRepository;
import org.gautelis.archimesh.wire.inbound.SubmitOpsMessage;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@QuarkusTest
@TestProfile(AdminAuthorizationEnabledProfile.class)
class AdminCheckpointIT {
    @TestHTTPResource URI baseUri;
    @Inject ArchimeshService service;
    @Inject Neo4jRepository repository;
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> models = new ArrayList<>();

    @BeforeEach
    void requireInfra() { Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT"))); }

    @AfterEach
    void cleanUp() { for (String id : models) service.deleteModel(id,true); }

    private String model(String name) {
        String id = "checkpoint-api-"+UUID.randomUUID(); models.add(id);
        service.registerModel(id,name,"owner"); return id;
    }

    private String create(String id) {
        return "{\"type\":\"CreateElement\",\"element\":{\"id\":\"elem:"+id+"\",\"archimateType\":\"BusinessActor\",\"name\":\""+id+"\"}}";
    }

    private void submit(String model, String batch, String... ops) throws Exception {
        var array = json.createArrayNode();
        for (String op : ops) array.add(json.readTree(op));
        service.onSubmitOps(model,new SubmitOpsMessage(repository.readHeadRevision(model),batch,null,array));
    }

    private HttpResponse<String> request(String method, String path, JsonNode body, String user, String roles) throws Exception {
        var builder = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(15));
        if (user != null) builder.header("X-Archimesh-User",user);
        if (roles != null) builder.header("X-Archimesh-Roles",roles);
        if (body != null) builder.header("Content-Type","application/json");
        builder.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(String method, String path, JsonNode body) throws Exception {
        var response = request(method,path,body,"administrator","admin");
        Assertions.assertEquals(200,response.statusCode(),response.body());
        return json.readTree(response.body());
    }

    private JsonNode snapshot(String model) throws Exception { return ok("GET","/models/"+model+"/snapshot",null); }

    @Test
    void checkpointPruneExportImportAndFurtherEditsRoundTrip() throws Exception {
        String source = model("Source"), target = "checkpoint-destination-"+UUID.randomUUID(); models.add(target);
        submit(source,"baseline",create("a"),"{\"type\":\"SetProperty\",\"targetId\":\"elem:a\",\"key\":\"owner\",\"value\":\"Alice\"}");
        service.createModelTag(source,"baseline","Before subsequent edits");
        var checkpoint = ok("POST","/admin/models/"+source+"/checkpoints",null);
        Assertions.assertEquals(2,checkpoint.path("revision").asLong());
        Assertions.assertEquals(checkpoint,ok("GET","/admin/models/"+source+"/checkpoints/latest",null));
        repository.markBroadcastPublished(source,"baseline");
        var preview = ok("POST","/admin/models/"+source+"/compact?retainRevisions=0&dryRun=true",null);
        Assertions.assertTrue(preview.path("dryRun").asBoolean());
        Assertions.assertEquals(1,preview.path("eligibleBatchCount").asLong());
        Assertions.assertEquals(0,preview.path("deletedOpCount").asLong());
        var pruned = ok("POST","/admin/models/"+source+"/compact?retainRevisions=0",null);
        Assertions.assertEquals(1,pruned.path("prunedBatchCount").asLong());
        submit(source,"tail","{\"type\":\"UpdateElement\",\"elementId\":\"elem:a\",\"patch\":{\"name\":\"after-checkpoint\"}}");
        ObjectNode exported = (ObjectNode)ok("GET","/admin/models/"+source+"/export",null);
        Assertions.assertEquals("archi-model-export-v2",exported.path("format").asText());
        Assertions.assertEquals(1,exported.path("receipts").size());
        Assertions.assertEquals(1,exported.path("opBatches").size());
        ((ObjectNode)exported.path("model")).put("modelId",target);
        ok("POST","/admin/models/import",exported);
        ObjectNode expected = (ObjectNode)snapshot(source); expected.put("modelId",target);
        Assertions.assertEquals(expected,snapshot(target));
        Assertions.assertEquals(2,repository.findAcceptedRange(target,"baseline").orElseThrow().to());
        Assertions.assertTrue(repository.loadOpBatches(target,1,2).isEmpty());
        var rebuilt = service.rebuildMaterializedState(target);
        Assertions.assertEquals(2,rebuilt.checkpointRevision());
        Assertions.assertEquals(1,rebuilt.appliedBatchCount());
        Assertions.assertEquals(expected,snapshot(target));
        submit(target,"baseline",create("ignored-retry"));
        Assertions.assertEquals(3,repository.readHeadRevision(target),"old pruned batch must deduplicate after import");
        submit(target,"new-edit",create("b"));
        Assertions.assertEquals(4,repository.readHeadRevision(target));
        var tagged = ok("GET","/models/"+target+"/snapshot?ref=baseline",null);
        Assertions.assertEquals("a",tagged.path("elements").get(0).path("name").asText());
        Assertions.assertEquals(target,tagged.path("modelId").asText());
    }

    @Test
    void checkpointActionsRequireModelAdminAndEmptyModelRoundTrips() throws Exception {
        String source = model("Empty");
        Assertions.assertEquals(404,request("GET","/admin/models/"+source+"/checkpoints/latest",null,"owner",null).statusCode());
        Assertions.assertEquals(403,request("POST","/admin/models/"+source+"/checkpoints",null,"writer","model_writer").statusCode());
        Assertions.assertEquals(200,request("POST","/admin/models/"+source+"/checkpoints",null,"owner",null).statusCode());
        ObjectNode exported = (ObjectNode)ok("GET","/admin/models/"+source+"/export",null);
        Assertions.assertTrue(exported.path("receipts").isArray());
        Assertions.assertEquals(0,exported.path("receipts").size());
        String target = "checkpoint-empty-destination-"+UUID.randomUUID(); models.add(target);
        ((ObjectNode)exported.path("model")).put("modelId",target);
        ok("POST","/admin/models/import",exported);
        Assertions.assertEquals(0,repository.readHeadRevision(target));
        Assertions.assertTrue(snapshot(target).path("elements").isEmpty());
    }

    @Test
    void failedTailRestoreRollsBackOverwriteAndCorruptCheckpointIsRejected() throws Exception {
        String source = model("Source"), target = model("Destination");
        submit(source,"base",create("a")); service.createModelCheckpoint(source);
        submit(target,"existing",create("prior"));
        JsonNode original = snapshot(target);
        ObjectNode exported = (ObjectNode)ok("GET","/admin/models/"+source+"/export",null);
        ((ObjectNode)exported.path("model")).put("modelId",target).put("headRevision",3);
        var tail = json.createObjectNode().put("modelId",source).put("opBatchId","bad-tail").put("timestamp","2026-10-28T00:00:00Z");
        tail.set("assignedRevisionRange",json.createObjectNode().put("from",2).put("to",3));
        tail.putArray("ops").add(json.readTree(create("collision"))).add(json.readTree(
                "{\"type\":\"SetProperty\",\"targetId\":\"elem:collision\",\"key\":\"id\",\"value\":\"elem:a\"}"));
        exported.putArray("opBatches").add(tail);
        Assertions.assertEquals(409,request("POST","/admin/models/import?overwrite=true",exported,"admin","admin").statusCode());
        Assertions.assertEquals(original,snapshot(target));
        Assertions.assertTrue(repository.findAcceptedRange(target,"existing").isPresent());
        ((ObjectNode)exported.path("checkpoint")).put("sha256","corrupted");
        Assertions.assertEquals(400,request("POST","/admin/models/import?overwrite=true",exported,"admin","admin").statusCode());
        Assertions.assertEquals(original,snapshot(target));
    }
}
