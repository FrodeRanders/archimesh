package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Persistent synthetic remote: supports a committed POST with a lost/503 reply. */
public class PublicationTestResource implements QuarkusTestResourceLifecycleManager {
    private HttpServer server;
    private java.util.concurrent.ExecutorService workers;
    private Path config;
    private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final ObjectNode cards=json.createObjectNode(),relations=json.createObjectNode();
    private int writes,calls;
    private String fault="";
    @Override public Map<String,String> start() {
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            workers=Executors.newSingleThreadExecutor(); server.setExecutor(workers);
            server.createContext("/",exchange -> {
                try {
                    String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
                    if (!path.equals("/__fixture")) calls++;
                    JsonNode body=method.equals("POST") || method.equals("PATCH") ? json.readTree(exchange.getRequestBody()) : null;
                    JsonNode response; int status=200;
                    if (path.equals("/__fixture")) {
                        if (body!=null) {
                            if (body.path("reset").asBoolean()) { cards.removeAll(); relations.removeAll(); writes=0; calls=0; fault=""; }
                            if (body.has("fault")) fault=body.path("fault").asText();
                            if (body.has("card")) cards.set(body.path("card").path("id").asText(),body.path("card").deepCopy());
                            if (body.has("relation")) relations.set(body.path("relation").path("id").asText(),body.path("relation").deepCopy());
                            if (body.has("id")) {
                                String id=body.path("id").asText();
                                if (body.path("delete").asBoolean()) cards.remove(id);
                                else if (cards.has(id)) ((ObjectNode)cards.get(id)).setAll((ObjectNode)body.path("fields"));
                            }
                        }
                        response=json.createObjectNode().put("writes",writes).put("calls",calls).set("cards",cards.deepCopy());
                        ((ObjectNode)response).set("relations",relations.deepCopy());
                    } else if (fault.equals("offline")) { status=503; response=json.createObjectNode(); }
                    else if (fault.equals("rate-limit")) { status=429; response=json.createObjectNode(); exchange.getResponseHeaders().set("Retry-After","1"); }
                    else if (fault.equals("forbid-patch") && method.equals("PATCH")) { status=403; response=json.createObjectNode(); }
                    else if (path.equals("/api/health")) response=json.readTree("{\"status\":\"ok\",\"version\":\"2.194.0\"}");
                    else if (path.equals("/api/v1/metamodel/types")) response=json.readTree("[{\"key\":\"Application\",\"fields_schema\":[{\"fields\":[{\"key\":\"owner\",\"type\":\"text\"},{\"key\":\"central\",\"type\":\"text\"},{\"key\":\"shared\",\"type\":\"text\"},{\"key\":\"budget\",\"type\":\"number\"}]}],\"is_hidden\":false}]");
                    else if (path.equals("/api/v1/metamodel/relation-types")) response=json.readTree("[{\"key\":\"Serves\",\"source_type_key\":\"Application\",\"target_type_key\":\"Application\",\"cardinality\":\"n:m\",\"attributes_schema\":[],\"is_hidden\":false}]");
                    else if (path.equals("/api/v1/cards") && method.equals("POST")) {
                        String id="card-"+UUID.randomUUID(); var card=((ObjectNode)body).deepCopy(); card.remove("strict_attributes"); card.put("id",id).put("status","ACTIVE");
                        cards.set(id,card); writes++; response=card; status=201;
                        if (fault.equals("commit-503")) { fault=""; status=503; }
                    } else if (path.equals("/api/v1/relations") && method.equals("GET")) {
                        String id=exchange.getRequestURI().getQuery().substring("card_id=".length()); var list=json.createArrayNode();
                        for (var r:relations) if (r.path("source_id").asText().equals(id) || r.path("target_id").asText().equals(id)) list.add(r);
                        response=list;
                    } else if (path.startsWith("/api/v1/cards/")) {
                        String id=path.substring("/api/v1/cards/".length());
                        if (id.endsWith("/relations")) {
                            id=id.substring(0,id.length()-"/relations".length()); var list=json.createArrayNode();
                            for (var r:relations) if (r.path("source_id").asText().equals(id) || r.path("target_id").asText().equals(id)) list.add(r);
                            response=list;
                        } else if (!cards.has(id)) { status=404; response=json.createObjectNode(); }
                        else { if (method.equals("PATCH")) {
                            ((ObjectNode)cards.get(id)).setAll((ObjectNode)body); writes++;
                            if (fault.equals("patch-commit-503")) { fault=""; status=503; }
                            else if (fault.equals("after-patch-rate-limit")) fault="rate-limit";
                        } response=cards.get(id); }
                    } else if (path.equals("/api/v1/relations") && method.equals("POST")) {
                        var relation=((ObjectNode)body).deepCopy().put("id","relation-"+UUID.randomUUID()); relations.set(relation.path("id").asText(),relation); writes++; response=relation; status=201;
                    } else { status=404; response=json.createObjectNode(); }
                    byte[] bytes=json.writeValueAsBytes(response); exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Exception error) { exchange.sendResponseHeaders(500,-1); }
                finally { exchange.close(); }
            }); server.start();
            String scope="m3-it-"+UUID.randomUUID(),url="http://127.0.0.1:"+server.getAddress().getPort();
            String env=List.of("USER","LOGNAME","SHELL","PATH").stream().filter(k -> System.getenv(k)!=null && !System.getenv(k).isBlank()
                    && System.getenv(k).chars().noneMatch(c -> c<=32 || c>=127)).findFirst().orElseThrow();
            var root=json.createObjectNode().put("schemaVersion",1).put("instanceId",scope);
            var target=root.putArray("targets").addObject().put("key","fixture").put("instanceId",scope).put("baseUrl",url)
                    .put("tokenEnvironment",env).put("expectedApiVersion","2.194.0").put("timeoutMillis",3000).put("maxResponseBytes",65536);
            var mapping=target.putObject("mapping").put("version","m3-fixture-v1"); mapping.putObject("cardTypes").put("Application","ApplicationComponent");
            mapping.putObject("relationTypes").putObject("Serves").put("archimateType","ServingRelationship").put("sourceType","Application").put("targetType","Application").put("cardinality","n:m").put("reversed",false);
            var fields=mapping.putObject("fields").putObject("Application");
            fields.putObject("name").put("remoteField","name").put("valueType","STRING").put("authority","ARCHIMESH_MASTER");
            fields.putObject("property:owner").put("remoteField","attributes.owner").put("valueType","STRING").put("authority","ARCHIMESH_MASTER");
            fields.putObject("property:central").put("remoteField","attributes.central").put("valueType","STRING").put("authority","TURBO_EA_MASTER");
            var access=target.putObject("publicationAccess").put("enabled",true).put("acceptUnconditionalWriteRisk",true).put("completeAttributeReadsApproved",true);
            access.putArray("publishers").add("publisher"); access.putArray("cardTypes").add("Application"); access.putArray("relationTypes").add("Serves");
            access.putArray("fields").add("name").add("attributes.owner").add("attributes.central");
            config=Files.createTempFile("archimesh-m3-publication-",".json"); Files.writeString(config,root.toString());
            return Map.of("app.integrations.enabled","true","app.integrations.configuration",config.toString(),"app.integrations.publication.worker-enabled","false","test.publication.url",url);
        } catch (Exception error) { stop(); throw new IllegalStateException(error); }
    }
    @Override public void stop() {
        if (server!=null) server.stop(0); if (workers!=null) workers.shutdownNow();
        if (config!=null) try { Files.deleteIfExists(config); } catch (java.io.IOException ignored) { }
    }
}
