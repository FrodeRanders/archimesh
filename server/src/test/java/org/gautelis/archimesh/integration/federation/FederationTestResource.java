package org.gautelis.archimesh.integration.federation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.gautelis.archimesh.integration.ExternalCatalogTestResource;

public class FederationTestResource extends ExternalCatalogTestResource {
    public static final String DATASET="urn:li:dataset:(urn:li:dataPlatform:postgres,federation.orders,DEV)";
    public static final String LONG="urn:li:dataset:(urn:li:dataPlatform:postgres,federation."+"x".repeat(900)+",DEV)";
    private HttpServer datahub; private Path configuration;
    private int reads; private String name="Order book"; private boolean removed;
    @Override public Map<String,String> start() {
        var result=new HashMap<>(super.start());
        try {
            var json=new ObjectMapper(); var legacyPath=Path.of(result.get("app.integrations.configuration"));
            var legacy=(ObjectNode)json.readTree(Files.readString(legacyPath)); String owner="federation-it-"+UUID.randomUUID();
            legacy.put("instanceId",owner); var target=(ObjectNode)legacy.path("targets").get(0); target.put("instanceId",owner);
            Files.writeString(legacyPath,legacy.toString());
            datahub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            datahub.createContext("/",exchange -> {
                try {
                    JsonNode response; int status=200;
                    if (exchange.getRequestURI().getPath().equals("/__fixture")) {
                        if (exchange.getRequestMethod().equals("POST")) {
                            var command=json.readTree(exchange.getRequestBody());
                            if (command.path("reset").asBoolean()) { reads=0; name="Order book"; removed=false; }
                            if (command.has("name")) name=command.path("name").asText();
                            if (command.has("removed")) removed=command.path("removed").asBoolean();
                        }
                        response=json.createObjectNode().put("reads",reads);
                    } else {
                        reads++;
                        if (!exchange.getRequestMethod().equals("GET")) throw new IllegalStateException("Read-only source adapter wrote");
                        var aspect=json.createObjectNode(); var root=json.createObjectNode(); root.set("aspect",aspect);
                        if (exchange.getRequestURI().getQuery().contains("aspect=datasetProperties"))
                            aspect.putObject("com.linkedin.dataset.DatasetProperties").put("name",name).put("description","Catalog-owned description").put("private","PRIVATE-SOURCE-DATA");
                        else if (exchange.getRequestURI().getQuery().contains("aspect=status")) aspect.putObject("com.linkedin.common.Status").put("removed",removed);
                        else { status=404; }
                        response=root;
                    }
                    byte[] bytes=json.writeValueAsBytes(response); exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Exception invalid) { exchange.sendResponseHeaders(500,-1); }
                finally { exchange.close(); }
            }); datahub.start();
            var root=json.createObjectNode().put("schemaVersion",1).put("ownerInstanceId",owner);
            var sources=root.putArray("sources"); var source=sources.addObject().put("key","catalog")
                .put("baseUrl","http://127.0.0.1:"+datahub.getAddress().getPort()).put("tokenEnvironment",target.path("tokenEnvironment").asText())
                .put("timeoutMillis",3000).put("maxResponseBytes",65536).put("approvedPlainHttp",false);
            source.putObject("repository").put("system","datahub").put("instance",owner+"-catalog");
            var type=source.putObject("types").putObject("dataset").put("semanticKind","catalog.dataset").put("archimateType","DataObject");
            type.putObject("fields").putObject("name").put("aspect","datasetProperties").put("path","/name");
            ((ObjectNode)type.path("fields")).putObject("documentation").put("aspect","datasetProperties").put("path","/description");
            var ea=sources.addObject().put("key","ea").put("turboTargetKey","fixture").put("timeoutMillis",3000).put("maxResponseBytes",65536).put("approvedPlainHttp",false);
            ea.putObject("repository").put("system","turboea").put("instance",owner);
            ea.putObject("types").putObject("Application").put("semanticKind","enterprise.application").put("archimateType","ApplicationComponent")
                .putObject("fields").putObject("name").put("aspect","card").put("path","/name");
            var grants=root.putArray("grants");
            var bridge=grants.addObject().put("principal","bridge").put("requiredRole","federation_bridge");
            bridge.putArray("operations").add("READ").add("REGISTER_SOURCE");
            bridge.putObject("resources").putArray("catalog").add(DATASET).add(LONG); ((ObjectNode)bridge.path("resources")).putArray("ea").add("card-a");
            var reader=grants.addObject().put("principal","catalog-reader").put("requiredRole","federation_reader");
            reader.putArray("operations").add("READ"); reader.putObject("resources").putArray("catalog").add(DATASET);
            configuration=Files.createTempFile("archimesh-federation-",".json"); Files.writeString(configuration,root.toString());
            result.put("app.federation.enabled","true"); result.put("app.federation.configuration",configuration.toString());
            result.put("test.federation.url","http://127.0.0.1:"+datahub.getAddress().getPort()); return result;
        } catch (Exception invalid) { stop(); throw new IllegalStateException("Cannot start federation fixture",invalid); }
    }
    @Override public void stop() {
        if (datahub!=null) datahub.stop(0);
        if (configuration!=null) try { Files.deleteIfExists(configuration); } catch (Exception ignored) { }
        super.stop();
    }
}
