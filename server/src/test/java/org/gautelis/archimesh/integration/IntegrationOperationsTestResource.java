package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.neo4j.driver.*;

/** A second disposable database proves archive restore without existing integration identities. */
public class IntegrationOperationsTestResource extends CentralEditTestResource {
    private String project,password;
    private final String compose=Path.of("../tests/recovery/compose.yml").toAbsolutePath().normalize().toString();
    private String command(String... tail) throws Exception {
        var args=new ArrayList<>(List.of("docker","compose","--project-name",project,"--file",compose)); args.addAll(List.of(tail));
        var process=new ProcessBuilder(args).redirectErrorStream(true); process.environment().put("DRILL_DB_PASSWORD",password); process.environment().put("DRILL_KAFKA_PORT","19092");
        var running=process.start(); var bytes=running.getInputStream().readAllBytes(); if (!running.waitFor(90,TimeUnit.SECONDS) || running.exitValue()!=0) throw new IllegalStateException("Disposable integration recovery infrastructure failed"); return new String(bytes,java.nio.charset.StandardCharsets.UTF_8).trim();
    }
    @Override public Map<String,String> start() {
        var result=new HashMap<>(super.start());
        try {
            var json=new ObjectMapper(); var path=Path.of(result.get("app.integrations.configuration")); var root=(ObjectNode)json.readTree(Files.readString(path)); root.putArray("operators").add("operator"); Files.writeString(path,root.toString());
            result.put("app.integrations.operations.worker-enabled","false");
            if (!"true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT"))) return result;
            project="archimesh-integration-recovery-"+UUID.randomUUID().toString().substring(0,8); password="recovery-"+UUID.randomUUID(); command("up","-d","restore-db");
            String uri="bolt://"+command("port","restore-db","7687"); boolean ready=false;
            for (int attempt=0;attempt<60;attempt++) {
                try (var driver=GraphDatabase.driver(uri,AuthTokens.basic("neo4j",password))) { driver.verifyConnectivity(); ready=true; break; }
                catch (Exception pending) { Thread.sleep(1000); }
            }
            if (!ready) throw new IllegalStateException("Disposable archive destination did not start");
            result.put("test.operations.destination",uri); result.put("test.operations.password",password); return result;
        } catch (Exception failure) { stop(); throw new IllegalStateException("Integration operations fixture failed",failure); }
    }
    @Override public void stop() {
        try { if (project!=null) { command("down","--volumes","--remove-orphans"); project=null; } }
        catch (Exception cleanup) { throw new IllegalStateException("Disposable integration recovery cleanup failed",cleanup); }
        finally { super.stop(); }
    }
}
