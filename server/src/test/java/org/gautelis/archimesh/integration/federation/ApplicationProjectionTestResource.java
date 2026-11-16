package org.gautelis.archimesh.integration.federation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import org.gautelis.archimesh.integration.PublicationTestResource;

/** OSS Rest.li-shaped target with partial commits, independent aspects and lost acknowledgements. */
public class ApplicationProjectionTestResource extends PublicationTestResource {
    private HttpServer target;
    private java.util.concurrent.ExecutorService executor;
    private Path configuration;
    @Override public Map<String,String> start() {
        var result=new HashMap<>(super.start());
        try {
            var json=new ObjectMapper(); var path=Path.of(result.get("app.integrations.configuration"));
            var legacy=(ObjectNode)json.readTree(Files.readString(path)); String owner=legacy.path("instanceId").asText();
            var sourceTarget=(ObjectNode)legacy.path("targets").get(0);
            var mapping=(ObjectNode)sourceTarget.path("mapping").path("fields").path("Application");
            mapping.putObject("name").put("remoteField","name").put("valueType","STRING").put("authority","TURBO_EA_MASTER");
            mapping.putObject("documentation").put("remoteField","description").put("valueType","STRING").put("authority","TURBO_EA_MASTER");
            Files.writeString(path,legacy.toString());
            var applications=json.createObjectNode(); var status=json.createObjectNode(); int[] writes={0},reads={0}; String[] fault={""};
            target=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            executor=Executors.newSingleThreadExecutor(); target.setExecutor(executor);
            target.createContext("/",exchange -> {
                try {
                    String route=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod(); int code=200;
                    JsonNode body=method.equals("POST") ? json.readTree(exchange.getRequestBody()) : null, response=json.createObjectNode();
                    if(route.equals("/__fixture")) {
                        if(body!=null) {
                            if(body.path("reset").asBoolean()) { applications.removeAll(); status.removeAll(); writes[0]=0; reads[0]=0; fault[0]=""; }
                            if(body.has("fault")) fault[0]=body.path("fault").asText();
                            if(body.has("urn")) {
                                String urn=body.path("urn").asText();
                                if(body.path("delete").asBoolean()) { applications.remove(urn); status.remove(urn); }
                                if(body.has("properties")) applications.set(urn,body.path("properties"));
                                if(body.has("removed")) status.set(urn,json.createObjectNode().put("removed",body.path("removed").asBoolean()));
                            }
                        }
                        response=json.createObjectNode().put("writes",writes[0]).put("reads",reads[0]).set("applications",applications.deepCopy());
                    } else if(route.equals("/aspects") && method.equals("POST")) {
                        var proposal=body.path("proposal"); String urn=proposal.path("entityUrn").asText(),aspect=proposal.path("aspectName").asText();
                        if(!proposal.path("entityType").asText().equals("application") || !urn.startsWith("urn:li:application:")
                                || !Set.of("applicationProperties","status").contains(aspect)) code=403;
                        else {
                            var value=json.readTree(proposal.path("aspect").path("value").asText());
                            if(aspect.equals("applicationProperties")) applications.set(urn,value); else status.set(urn,value);
                            writes[0]++;
                            if(fault[0].equals("commit-503") || fault[0].equals("status-commit-503") && aspect.equals("status")) { code=503; fault[0]=""; }
                        }
                    } else if(route.startsWith("/aspects/") && method.equals("GET")) {
                        reads[0]++; String urn=route.substring("/aspects/".length());
                        String aspect=Arrays.stream(exchange.getRequestURI().getQuery().split("&")).filter(q -> q.startsWith("aspect=")).findFirst().orElseThrow().substring(7);
                        JsonNode value=aspect.equals("applicationProperties") ? applications.get(urn) : aspect.equals("status") ? status.get(urn) : null;
                        if(value==null && aspect.equals("applicationKey") && applications.has(urn)) value=json.createObjectNode().put("id",urn.substring("urn:li:application:".length()));
                        if(value==null) code=404;
                        else { var envelope=json.createObjectNode(); envelope.putObject("aspect").set("com.linkedin.application.FixtureAspect",value); response=envelope; }
                    } else code=404;
                    byte[] bytes=json.writeValueAsBytes(response); exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(code,bytes.length); exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            }); target.start();
            String url="http://127.0.0.1:"+target.getAddress().getPort();
            var root=json.createObjectNode().put("schemaVersion",1).put("ownerInstanceId",owner);
            var source=root.putArray("sources").addObject().put("key","ea").put("turboTargetKey","fixture").put("timeoutMillis",3000).put("maxResponseBytes",65536);
            source.putObject("repository").put("system","turboea").put("instance",owner);
            var fields=source.putObject("types").putObject("Application").put("semanticKind","enterprise.application").put("archimateType","ApplicationComponent").putObject("fields");
            fields.putObject("name").put("aspect","card").put("path","/name");
            fields.putObject("documentation").put("aspect","card").put("path","/description");
            fields.putObject("property:lifecycle").put("aspect","card").put("path","/lifecycle");
            var grant=root.putArray("grants").addObject().put("principal","bridge").put("requiredRole","federation_bridge");
            grant.putArray("operations").add("READ"); grant.putObject("resources").putArray("ea").add("app-1").add("app-2").add("app-adopt");
            var destination=root.putArray("datahubTargets").addObject().put("key","applications").put("baseUrl",url)
                .put("tokenEnvironment",sourceTarget.path("tokenEnvironment").asText()).put("timeoutMillis",3000).put("maxResponseBytes",65536);
            destination.putObject("repository").put("system","datahub").put("instance",owner+"-datahub");
            var profile=root.putArray("applicationProjections").addObject().put("key","applications").put("sourceKey","ea").put("externalType","Application")
                .put("targetKey","applications").put("approved",true).put("acceptUnconditionalWriteRisk",true).put("approvalVersion","test-v1")
                .put("lifecycleField","property:lifecycle");
            profile.putArray("principals").add("bridge"); profile.putArray("nativeIds").add("app-1").add("app-2").add("app-adopt");
            profile.putObject("adoptions").put("app-adopt","urn:li:application:existing-app");
            configuration=Files.createTempFile("application-projection-",".json"); Files.writeString(configuration,root.toString());
            result.put("app.federation.enabled","true"); result.put("app.federation.configuration",configuration.toString());
            result.put("test.application.target",url); return result;
        } catch(Exception failure) { stop(); throw new IllegalStateException("Cannot create application projection fixture",failure); }
    }
    @Override public void stop() {
        if(target!=null) target.stop(0); if(executor!=null) executor.shutdownNow();
        if(configuration!=null) try { Files.deleteIfExists(configuration); } catch(Exception ignored) { }
        super.stop();
    }
}
