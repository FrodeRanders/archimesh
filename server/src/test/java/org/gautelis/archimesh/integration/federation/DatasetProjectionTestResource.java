package org.gautelis.archimesh.integration.federation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.gautelis.archimesh.integration.PublicationTestResource;

public class DatasetProjectionTestResource extends PublicationTestResource {
    private HttpServer source;
    private Path configuration;
    private String name="Order book";
    private boolean removed;
    private int reads;
    @Override public Map<String,String> start() {
        var result=new HashMap<>(super.start());
        try {
            var json=new ObjectMapper(); var legacyPath=Path.of(result.get("app.integrations.configuration"));
            var legacy=(ObjectNode)json.readTree(Files.readString(legacyPath)); String owner=legacy.path("instanceId").asText();
            var target=(ObjectNode)legacy.path("targets").get(0);
            var catalogAccess=target.putObject("catalogAccess").put("approved",true);
            catalogAccess.putArray("readers").add("publisher"); catalogAccess.putArray("importers").add("publisher");
            catalogAccess.putArray("cardTypes").add("Application"); catalogAccess.putArray("fields").add("name").add("description");
            var inbound=target.putObject("inboundAccess").put("enabled",true).put("sseEnabled",false);
            inbound.putArray("administrators").add("publisher"); inbound.putArray("cardTypes").add("Application");
            inbound.putArray("fields").add("name").add("description");
            var fields=((ObjectNode)target.path("mapping")).putObject("fields").putObject("Application");
            fields.putObject("name").put("remoteField","name").put("valueType","STRING").put("authority","DATAHUB_MASTER");
            fields.putObject("documentation").put("remoteField","description").put("valueType","STRING").put("authority","DATAHUB_MASTER");
            Files.writeString(legacyPath,legacy.toString());
            source=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            source.createContext("/",exchange -> {
                try {
                    JsonNode response;
                    if(exchange.getRequestURI().getPath().equals("/__fixture")) {
                        if(exchange.getRequestMethod().equals("POST")) {
                            var command=json.readTree(exchange.getRequestBody());
                            if(command.path("reset").asBoolean()) { name="Order book"; removed=false; reads=0; }
                            if(command.has("name")) name=command.path("name").asText();
                            if(command.has("removed")) removed=command.path("removed").asBoolean();
                        }
                        response=json.createObjectNode().put("reads",reads);
                    } else {
                        reads++;
                        var root=json.createObjectNode(); var aspect=root.putObject("aspect");
                        if(exchange.getRequestURI().getQuery().contains("aspect=status")) aspect.putObject("com.linkedin.common.Status").put("removed",removed);
                        else aspect.putObject("com.linkedin.dataset.DatasetProperties").put("name",name).put("description","Source-owned dataset description");
                        response=root;
                    }
                    byte[] bytes=json.writeValueAsBytes(response); exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            }); source.start();
            var root=json.createObjectNode().put("schemaVersion",1).put("ownerInstanceId",owner);
            var catalog=root.putArray("sources").addObject().put("key","catalog").put("baseUrl","http://127.0.0.1:"+source.getAddress().getPort())
                .put("tokenEnvironment",target.path("tokenEnvironment").asText()).put("timeoutMillis",3000).put("maxResponseBytes",65536);
            catalog.putObject("repository").put("system","datahub").put("instance",owner+"-datahub");
            var type=catalog.putObject("types").putObject("dataset").put("semanticKind","catalog.dataset").put("archimateType","ApplicationComponent");
            var selected=type.putObject("fields"); selected.putObject("name").put("aspect","datasetProperties").put("path","/name");
            selected.putObject("documentation").put("aspect","datasetProperties").put("path","/description");
            var grant=root.putArray("grants").addObject().put("principal","bridge").put("requiredRole","federation_bridge");
            grant.putArray("operations").add("READ"); grant.putObject("resources").putArray("catalog").add("urn:li:dataset:(urn:li:dataPlatform:postgres,test,DEV)");
            var profile=root.putArray("projections").addObject().put("key","datasets").put("sourceKey","catalog").put("externalType","dataset")
                .put("targetKey","fixture").put("targetType","Application").put("approved",true).put("acceptUnconditionalWriteRisk",true).put("approvalVersion","test-v1");
            profile.putArray("principals").add("bridge"); profile.putArray("nativePrefixes").add("urn:li:dataset:(");
            profile.putArray("descriptionFields").add("documentation");
            configuration=Files.createTempFile("dataset-projection-",".json"); Files.writeString(configuration,root.toString());
            result.put("app.federation.enabled","true"); result.put("app.federation.configuration",configuration.toString());
            result.put("app.integrations.inbound.worker-enabled","false");
            result.put("test.projection.source","http://127.0.0.1:"+source.getAddress().getPort());
            return result;
        } catch(Exception failure) { stop(); throw new IllegalStateException("Cannot create dataset projection fixture",failure); }
    }
    @Override public void stop() {
        if(source!=null) source.stop(0);
        if(configuration!=null) try { Files.deleteIfExists(configuration); } catch(Exception ignored) { }
        super.stop();
    }
}
