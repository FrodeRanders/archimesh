package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.*;

/** Synthetic HTTP catalog; credentials are taken from a harmless existing env entry and never logged. */
public class ExternalCatalogTestResource implements QuarkusTestResourceLifecycleManager {
    static final String SCOPE="m2-it-"+UUID.randomUUID();
    static final AtomicInteger calls=new AtomicInteger();
    static final AtomicBoolean offline=new AtomicBoolean();
    private HttpServer server;
    private java.util.concurrent.ExecutorService workers;
    private Path config;
    @Override public Map<String,String> start() {
        try {
            var json=new ObjectMapper();
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            workers=Executors.newCachedThreadPool(); server.setExecutor(workers);
            server.createContext("/",exchange -> {
                if (exchange.getRequestURI().getPath().equals("/__fixture")) {
                    String query=exchange.getRequestURI().getQuery();
                    if (query!=null) offline.set(query.equals("offline=true"));
                    byte[] bytes=Integer.toString(calls.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); return;
                }
                calls.incrementAndGet();
                String path=exchange.getRequestURI().getPath(); String response;
                int status=offline.get() ? 503 : 200;
                if (path.equals("/api/health")) response="{\"status\":\"ok\",\"version\":\"2.194.0\"}";
                else if (path.equals("/api/v1/metamodel/types")) response="[{\"key\":\"Application\",\"fields_schema\":[],\"is_hidden\":false}]";
                else if (path.equals("/api/v1/metamodel/relation-types")) response="[]";
                else if (path.equals("/api/v1/cards")) response="{\"items\":["+card("card-a")+","+card("card-b")+"],\"total\":2,\"page\":1,\"page_size\":25,\"withheld_ids\":[\"PRIVATE-WITHHELD\"]}";
                else response=card(path.substring(path.lastIndexOf('/')+1));
                byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start();
            String env=List.of("USER","LOGNAME","SHELL","PATH").stream().filter(k -> System.getenv(k)!=null && !System.getenv(k).isBlank()
                    && System.getenv(k).chars().noneMatch(c -> c<=32 || c>=127)).findFirst().orElseThrow();
            var root=json.createObjectNode().put("schemaVersion",1).put("instanceId",SCOPE);
            var target=root.putArray("targets").addObject().put("key","fixture").put("instanceId",SCOPE)
                    .put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("tokenEnvironment",env)
                    .put("expectedApiVersion","2.194.0").put("timeoutMillis",3000).put("maxResponseBytes",16384);
            var mapping=target.putObject("mapping").put("version","m2-fixture-v1");
            mapping.putObject("cardTypes").put("Application","ApplicationComponent"); mapping.putObject("relationTypes");
            mapping.putObject("fields").putObject("Application").putObject("name").put("remoteField","name").put("valueType","STRING").put("authority","TURBO_EA_MASTER");
            var access=target.putObject("catalogAccess").put("approved",true);
            access.putArray("readers").add("architect").add("reader"); access.putArray("importers").add("architect");
            access.putArray("cardTypes").add("Application"); access.putArray("fields").add("name");
            config=Files.createTempFile("archimesh-m2-catalog-",".json"); Files.writeString(config,root.toString());
            return Map.of("app.integrations.enabled","true","app.integrations.configuration",config.toString(),
                    "test.catalog.url","http://127.0.0.1:"+server.getAddress().getPort());
        } catch (Exception error) { stop(); throw new IllegalStateException("Cannot start synthetic catalog fixture",error); }
    }
    private String card(String id) {
        return "{\"id\":\""+id+"\",\"type\":\""+(id.equals("card-hidden") ? "SecretType" : "Application")
                +"\",\"name\":\"Shared application\",\"status\":\"ACTIVE\",\"description\":\"PRIVATE-DESCRIPTION\",\"external_id\":\"PRIVATE-EXTERNAL-ID\",\"attributes\":{\"secret\":\"PRIVATE-ATTRIBUTE\"}}";
    }
    @Override public void stop() {
        if (server!=null) server.stop(0);
        if (workers!=null) workers.shutdownNow();
        if (config!=null) try { Files.deleteIfExists(config); } catch (java.io.IOException ignored) { }
    }
}
