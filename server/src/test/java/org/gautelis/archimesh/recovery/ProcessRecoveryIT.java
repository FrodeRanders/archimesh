package org.gautelis.archimesh.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Opt-in black-box drill: real packaged JVMs, SIGKILL, isolated Docker databases and broker. */
class ProcessRecoveryIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root = Path.of("").toAbsolutePath().getParent();
    private final String runId = UUID.randomUUID().toString();
    private final String project = "archimesh-recovery-"+runId.substring(0,8);
    private final String password = "drill-"+UUID.randomUUID();
    private final String model = "recovery-"+runId.substring(0,8);
    private final ObjectNode report = JSON.createObjectNode();
    private final ArrayNode steps = report.putArray("steps");
    private final List<Process> servers = new ArrayList<>();
    private Path artifacts;
    private int kafkaPort, httpPort;
    private Driver source, restore;
    private Process server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private boolean infraCreated;

    @Test
    @Timeout(value=8,unit=TimeUnit.MINUTES)
    void forcedStopsPreserveDeliveryCheckpointRecoveryAndAtomicRestore() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_RECOVERY_DRILL")),
                "Run bash scripts/run-recovery-drill.sh for isolated process-crash recovery coverage");
        artifacts = root.resolve("server/target/recovery-drill").resolve(runId);
        Files.createDirectories(artifacts);
        report.put("runId",runId).put("dockerProject",project).put("startedAt",Instant.now().toString());
        report.put("neo4jImage","neo4j:5.26.0-community").put("kafkaImage","redpandadata/redpanda:v24.2.8");
        kafkaPort = freePort(); httpPort = freePort();
        try {
            Path jar = root.resolve("server/target/quarkus-app/quarkus-run.jar");
            Assertions.assertTrue(Files.isRegularFile(jar),"Build the packaged application before running the drill");
            report.put("launcherSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
            report.put("javaVersion",System.getProperty("java.version"));
            try (var files = Files.list(jar.getParent().resolve("app"))) {
                Path application = files.filter(path -> path.toString().endsWith(".jar")).findFirst().orElseThrow();
                report.put("applicationSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(application))));
            }
            step("Start isolated source database; leave the isolated Kafka broker offline");
            infraCreated = true;
            compose("up","-d","source-db");
            source = database("source-db"); applySchema(source);
            startServer(sourceUri());
            ok("POST","/admin/models/"+model+"?modelName=ProcessRecovery",null);
            ArrayNode baseOps = baselineOperations();
            long baselineHead = baseOps.size();
            JsonNode baselineAccepted;
            try (var client = connect(null)) {
                baselineAccepted = client.submit("baseline",0,baseOps);
                Assertions.assertEquals(baselineHead,baselineAccepted.path("payload").path("assignedRevisionRange").path("to").asLong());
            }
            JsonNode baselineSnapshot = snapshot();
            Assertions.assertEquals(1,count(source,"MATCH (c:Commit {modelId:$model,published:false}) RETURN count(c) AS n"));
            step("Accepted batch is durable and unpublished; force-kill its server JVM");
            crashServer();
            awaitLeaseExpiry(source);
            startServer(sourceUri());
            Assertions.assertEquals(baselineSnapshot,snapshot());

            try (var client = connect(0L)) {
                step("Start Kafka after reconnect; require the original pending batch to arrive via WebSocket fan-out");
                compose("up","-d","kafka"); awaitBroker();
                JsonNode broadcast = client.waitFor("OpsBroadcast","baseline",Duration.ofSeconds(60));
                Assertions.assertNotNull(broadcast,"restarted delivery must reach the subscribed client");
                await("publication acknowledgment",Duration.ofSeconds(20),() -> pending(source)==0);
                JsonNode retry = client.submit("baseline",0,baseOps);
                Assertions.assertEquals(baselineAccepted.path("payload"),retry.path("payload"));
                Assertions.assertEquals(baselineSnapshot,snapshot());
            }

            ok("POST","/admin/models/"+model+"/tags?tagName=baseline",null);
            JsonNode checkpoint = ok("POST","/admin/models/"+model+"/checkpoints",null);
            Assertions.assertEquals(baselineHead,checkpoint.path("revision").asLong());
            JsonNode pruning = ok("POST","/admin/models/"+model+"/compact?retainRevisions=0",null);
            Assertions.assertEquals(1,pruning.path("prunedBatchCount").asLong());
            Assertions.assertEquals(0,count(source,"MATCH (:Commit {modelId:$model})-[:HAS_OP]->(o:Op) RETURN count(o) AS n"));
            Assertions.assertEquals(1,count(source,"MATCH (c:Commit {modelId:$model,opBatchId:'baseline',payloadPruned:true}) WHERE c.batchJson IS NULL RETURN count(c) AS n"));
            Assertions.assertEquals(baselineSnapshot,snapshot());
            ArrayNode tail = ops("""
                    {"type":"UpdateElement","elementId":"elem:a","patch":{"name":"after-checkpoint"},"causal":{"lamport":200,"clientId":"drill"}},
                    {"type":"AddPropertySetMember","targetId":"elem:a","key":"watchers","member":"gamma","causal":{"lamport":201,"clientId":"drill"}}
                    """);
            try (var client = connect(null)) { client.submit("tail",baselineHead,tail); }
            JsonNode expected = snapshot();
            step("Force-kill after checkpoint-covered pruning and a later accepted tail");
            crashServer(); awaitLeaseExpiry(source); startServer(sourceUri());
            Assertions.assertEquals(expected,snapshot());
            try (var client = connect(0L)) {
                Assertions.assertEquals("CheckoutSnapshot",client.checkout.path("type").asText(),"pruned delta must fall back to snapshot");
                JsonNode retry = client.submit("baseline",0,baseOps);
                Assertions.assertEquals(baselineAccepted.path("payload"),retry.path("payload"));
                Assertions.assertEquals(expected,snapshot());
            }
            JsonNode rebuilt = ok("POST","/models/"+model+"/rebuild",null);
            Assertions.assertEquals(baselineHead,rebuilt.path("checkpointRevision").asLong());
            Assertions.assertEquals(expected,snapshot());
            ObjectNode exported = (ObjectNode)ok("GET","/admin/models/"+model+"/export",null);
            Assertions.assertEquals("archi-model-export-v2",exported.path("format").asText());
            Assertions.assertEquals(1,exported.path("receipts").size());
            Assertions.assertEquals(1,exported.path("opBatches").size());
            JSON.writerWithDefaultPrettyPrinter().writeValue(artifacts.resolve("export-v2.json").toFile(),exported);
            JSON.writerWithDefaultPrettyPrinter().writeValue(artifacts.resolve("expected-snapshot.json").toFile(),expected);
            stopServer();

            step("Start an empty destination database and apply its constraints");
            compose("stop","source-db"); compose("up","-d","restore-db");
            restore = database("restore-db"); applySchema(restore);
            Assertions.assertEquals(0,count(restore,"MATCH (n) RETURN count(n) AS n"));
            startServer(restoreUri());

            step("Block an element constraint inside v2 restore, then force-kill the JVM mid-transaction");
            // The importer has already created its model, receipts and earlier sorted projection nodes
            // when it waits on elem:b. The blocker is rolled back too; no fake state is committed.
            try (var session = restore.session(); var blocker = session.beginTransaction()) {
                blocker.run("CREATE (:Element {modelId:$model,id:'elem:b'})",Map.of("model",model)).consume();
                var importing = http.sendAsync(request("POST","/admin/models/import",exported),HttpResponse.BodyHandlers.ofString());
                try {
                    await("blocked restore transaction",Duration.ofSeconds(20),this::restoreIsBlocked);
                    report.put("interruptedRestoreElement","elem:b");
                    crashServer();
                } finally {
                    blocker.rollback(); importing.cancel(true);
                }
            }
            await("rollback of killed restore",Duration.ofSeconds(20),() -> count(restore,"MATCH (n {modelId:$model}) RETURN count(n) AS n")==0);
            awaitLeaseExpiry(restore); startServer(restoreUri());
            Assertions.assertEquals(0,count(restore,"MATCH (n {modelId:$model}) RETURN count(n) AS n"));
            ok("POST","/admin/models/import",exported);
            Assertions.assertEquals(expected,snapshot(),"fresh-database restore must preserve the complete client-facing state");
            Assertions.assertEquals(exported.path("accessControl"),ok("GET","/admin/models/"+model+"/acl",null));
            Assertions.assertEquals(checkpoint.path("sha256"),ok("GET","/admin/models/"+model+"/checkpoints/latest",null).path("sha256"));
            Assertions.assertEquals(baselineSnapshot,ok("GET","/models/"+model+"/snapshot?ref=baseline",null));

            step("Retry a pruned batch, reject stale merge winners, continue editing and rebuild the restored model");
            try (var client = connect(0L)) {
                Assertions.assertEquals("CheckoutSnapshot",client.checkout.path("type").asText());
                Assertions.assertEquals(expected,client.checkout.path("payload").path("snapshot"));
                JsonNode retry = client.submit("baseline",0,baseOps);
                Assertions.assertEquals(baselineAccepted.path("payload"),retry.path("payload"));
                Assertions.assertEquals(expected,snapshot());
                long head = expected.path("headRevision").asLong();
                client.submit("stale",head,ops("""
                        {"type":"UpdateElement","elementId":"elem:a","patch":{"name":"stale"},"causal":{"lamport":1,"clientId":"old"}},
                        {"type":"CreateElement","element":{"id":"elem:gone","archimateType":"BusinessActor"},"causal":{"lamport":1,"clientId":"old"}},
                        {"type":"AddPropertySetMember","targetId":"elem:a","key":"watchers","member":"beta","causal":{"lamport":1,"clientId":"old"}},
                        {"type":"RemoveViewObjectChildMember","parentViewObjectId":"vo:a","childViewObjectId":"vo:b","causal":{"lamport":1,"clientId":"old"}}
                        """));
                var expectedAfterStale = expected.deepCopy(); ((ObjectNode)expectedAfterStale).put("headRevision",head+4);
                // Normalize numeric node widths through the same JSON transport used by the actual snapshot.
                Assertions.assertEquals(JSON.readTree(expectedAfterStale.toString()),snapshot(),
                        "checkpoint clocks and tombstones must stop stale resurrection and overwrite");
                client.submit("new-edit",head+4,ops("""
                        {"type":"CreateElement","element":{"id":"elem:new","archimateType":"BusinessActor","name":"continued"},"causal":{"lamport":300,"clientId":"drill"}}
                        """));
            }
            JsonNode continued = snapshot();
            ok("POST","/models/"+model+"/rebuild",null);
            Assertions.assertEquals(continued,snapshot());
            Assertions.assertEquals(baselineSnapshot,ok("GET","/models/"+model+"/snapshot?ref=baseline",null));
            report.put("baselineRevision",baselineHead).put("restoredRevision",expected.path("headRevision").asLong())
                    .put("continuedRevision",continued.path("headRevision").asLong()).put("passed",true);
            step("All process-crash and fresh-database restore assertions passed");
        } catch (Throwable failure) {
            report.put("passed",false).put("failure",failure.toString());
            throw failure;
        } finally {
            boolean passed = report.path("passed").asBoolean();
            ArrayNode cleanupFailures = report.putArray("cleanupFailures");
            for (Process process : servers) if (process.isAlive()) {
                try {
                    process.destroyForcibly();
                    if (!process.waitFor(10,TimeUnit.SECONDS)) cleanupFailures.add("Server process did not stop: "+process.pid());
                } catch (Exception failure) { cleanupFailures.add(failure.toString()); }
            }
            for (Driver driver : new Driver[]{source,restore}) if (driver!=null) {
                try { driver.close(); } catch (Exception failure) { cleanupFailures.add(failure.toString()); }
            }
            if (infraCreated) {
                try { compose("logs","--no-color"); } catch (Exception failure) { report.put("logCollectionFailure",failure.toString()); }
                if (!"true".equalsIgnoreCase(System.getenv("RECOVERY_DRILL_KEEP_INFRA"))) {
                    try { compose("down","--volumes","--remove-orphans"); }
                    catch (Exception failure) { cleanupFailures.add(failure.toString()); }
                } else report.put("infrastructureRetained",true);
            }
            if (!cleanupFailures.isEmpty()) report.put("passed",false);
            report.put("finishedAt",Instant.now().toString());
            JSON.writerWithDefaultPrettyPrinter().writeValue(artifacts.resolve("report.json").toFile(),report);
            System.out.println("[recovery-drill] Report: "+artifacts.resolve("report.json"));
            if (passed && !cleanupFailures.isEmpty()) throw new AssertionError("Recovery assertions passed but cleanup failed: "+cleanupFailures);
        }
    }

    private ArrayNode baselineOperations() throws IOException {
        ArrayNode operations = ops("""
                {"type":"CreateElement","element":{"id":"elem:a","archimateType":"BusinessActor","name":"a"}},
                {"type":"CreateElement","element":{"id":"elem:b","archimateType":"BusinessActor","name":"b"}},
                {"type":"CreateElement","element":{"id":"elem:gone","archimateType":"BusinessActor","name":"gone"}},
                {"type":"SetProperty","targetId":"elem:a","key":"owner","value":"Alice"},
                {"type":"UnsetProperty","targetId":"elem:a","key":"removed"},
                {"type":"AddPropertySetMember","targetId":"elem:a","key":"watchers","member":"alpha"},
                {"type":"RemovePropertySetMember","targetId":"elem:a","key":"watchers","member":"beta"},
                {"type":"DeleteElement","elementId":"elem:gone"},
                {"type":"CreateFolder","folder":{"id":"folder:custom","folderType":"USER","name":"Custom","parentFolderId":"folder:root-business"}},
                {"type":"MoveElementToFolder","elementId":"elem:a","folderId":"folder:custom"},
                {"type":"CreateRelationship","relationship":{"id":"rel:r","archimateType":"AssociationRelationship","sourceId":"elem:a","targetId":"elem:b"}},
                {"type":"CreateView","view":{"id":"view:v","name":"View"}},
                {"type":"CreateViewObject","viewObject":{"id":"vo:a","viewId":"view:v","representsId":"elem:a","notationJson":{"x":10,"y":20}}},
                {"type":"CreateViewObject","viewObject":{"id":"vo:b","viewId":"view:v","representsId":"elem:b","notationJson":{"x":50,"y":60}}},
                {"type":"CreateConnection","connection":{"id":"conn:c","viewId":"view:v","representsId":"rel:r","sourceViewObjectId":"vo:a","targetViewObjectId":"vo:b","notationJson":{"lineWidth":2}}},
                {"type":"AddViewObjectChildMember","parentViewObjectId":"vo:a","childViewObjectId":"vo:b"}
                """);
        for (int i=0;i<operations.size();i++) ((ObjectNode)operations.get(i)).putObject("causal").put("lamport",100+i).put("clientId","drill");
        return operations;
    }

    private ArrayNode ops(String contents) throws IOException { return (ArrayNode)JSON.readTree("["+contents+"]"); }
    private static int freePort() throws IOException { try (var socket = new java.net.ServerSocket(0,0,InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private void step(String message) { System.out.println("[recovery-drill] "+message); steps.addObject().put("at",Instant.now().toString()).put("message",message); }
    private String sourceUri() { return sourceAddress; }
    private String sourceAddress, restoreAddress;
    private String restoreUri() { return restoreAddress; }

    private String compose(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker","compose","--project-name",project,"--file",root.resolve("tests/recovery/compose.yml").toString()));
        command.addAll(List.of(arguments));
        Path output = artifacts.resolve("docker-"+UUID.randomUUID().toString().substring(0,8)+".log");
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("DRILL_DB_PASSWORD",password); builder.environment().put("DRILL_KAFKA_PORT",Integer.toString(kafkaPort));
        Process process = builder.start();
        if (!process.waitFor(120,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Docker command timed out; see "+output); }
        String text = Files.readString(output);
        if (process.exitValue()!=0) throw new IllegalStateException("Docker command failed; see "+output+"\n"+text);
        return text.trim();
    }

    private Driver database(String service) throws Exception {
        String port = compose("port",service,"7687");
        String address = "bolt://127.0.0.1:"+port.substring(port.lastIndexOf(':')+1).trim();
        if (service.equals("source-db")) sourceAddress = address; else restoreAddress = address;
        Driver driver = GraphDatabase.driver(address,AuthTokens.basic("neo4j",password),Config.builder()
                .withConnectionTimeout(2,TimeUnit.SECONDS).withConnectionAcquisitionTimeout(3,TimeUnit.SECONDS).build());
        try { await(service+" readiness",Duration.ofSeconds(90),() -> { driver.verifyConnectivity(); return true; }); }
        catch (Throwable failure) { driver.close(); throw failure; }
        return driver;
    }

    private void applySchema(Driver driver) throws IOException {
        String schema = Files.readString(root.resolve("neo4j/schema.cypher")).replaceAll("(?s)/\\*.*?\\*/","");
        try (var session = driver.session()) { for (String statement : schema.split(";")) if (!statement.isBlank()) session.run(statement).consume(); }
    }

    private void startServer(String database) throws Exception {
        Path log = artifacts.resolve("server-"+(servers.size()+1)+".log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xms64m","-Xmx384m","-jar",
                root.resolve("server/target/quarkus-app/quarkus-run.jar").toString()).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env = builder.environment();
        // Override inherited deployment settings so this packaged process is confined to drill infrastructure.
        env.keySet().removeIf(key -> key.startsWith("QUARKUS_") || key.startsWith("ARCHIMESH_") || key.startsWith("NEO4J_")
                || key.startsWith("KAFKA_") || key.startsWith("MP_") || key.startsWith("SMALLRYE_")
                || Set.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS").contains(key));
        env.put("QUARKUS_HTTP_HOST","127.0.0.1"); env.put("QUARKUS_HTTP_PORT",Integer.toString(httpPort));
        env.put("NEO4J_URI",database); env.put("NEO4J_USER","neo4j"); env.put("NEO4J_PASSWORD",password);
        env.put("KAFKA_BOOTSTRAP_SERVERS","127.0.0.1:"+kafkaPort); env.put("KAFKA_TOPIC_PREFIX","drill");
        env.put("KAFKA_CONSUMER_GROUP_ID",project); env.put("ARCHIMESH_AUTHZ_ENABLED","true"); env.put("ARCHIMESH_IDENTITY_MODE","bootstrap");
        env.put("ARCHIMESH_QUARKUS_OIDC_ENABLED","false"); env.put("ARCHIMESH_CHECKPOINT_MAINTENANCE_ENABLED","false");
        server = builder.start(); servers.add(server);
        await("packaged server startup",Duration.ofSeconds(45),() -> {
            if (!server.isAlive()) throw new AssertionError("Packaged server exited; see "+log);
            try { return http.send(request("GET","/admin/models",null),HttpResponse.BodyHandlers.discarding()).statusCode()==200; }
            catch (IOException e) { return false; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        });
    }

    private void crashServer() throws Exception {
        Assertions.assertTrue(server.isAlive()); server.destroyForcibly();
        Assertions.assertTrue(server.waitFor(10,TimeUnit.SECONDS),"forced server stop did not complete");
    }
    private void stopServer() throws Exception {
        server.destroy();
        if (!server.waitFor(15,TimeUnit.SECONDS)) { server.destroyForcibly(); server.waitFor(10,TimeUnit.SECONDS); }
    }

    private void awaitLeaseExpiry(Driver driver) {
        step("Wait for the crashed writer's real database lease to expire");
        await("writer lease expiry",Duration.ofSeconds(40),() -> {
            try (var session = driver.session()) { return session.run("MATCH (r:ArchimeshRuntime {id:'writer'}) RETURN coalesce(r.expiresAt,0)<datetime().epochMillis AS expired").single().get("expired").asBoolean(); }
        });
    }
    private long pending(Driver driver) { return count(driver,"MATCH (c:Commit {modelId:$model,published:false}) RETURN count(c) AS n"); }
    private long count(Driver driver,String query) {
        try (var session = driver.session()) { return session.run(query,Map.of("model",model)).single().get("n").asLong(); }
    }
    private boolean restoreIsBlocked() {
        try (var session = restore.session()) {
            var rows = session.run("SHOW TRANSACTIONS YIELD currentQuery,status,parameters "
                    + "WHERE currentQuery CONTAINS 'CREATE (n:Element)' RETURN currentQuery,status,parameters AS params").list();
            report.set("restoreTransactionProbe",JSON.valueToTree(rows.stream().map(row -> row.asMap()).toList()));
            return rows.stream().anyMatch(row -> row.get("status").asString().startsWith("Blocked")
                            && model.equals(row.get("params").get("properties").get("modelId").asString(""))
                            && "elem:b".equals(row.get("params").get("properties").get("id").asString("")));
        }
    }
    private void awaitBroker() {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers","127.0.0.1:"+kafkaPort,"default.api.timeout.ms","3000","request.timeout.ms","2000"))) {
            await("isolated Kafka readiness",Duration.ofSeconds(60),() -> {
                try { return !admin.describeCluster().nodes().get(3,TimeUnit.SECONDS).isEmpty(); }
                catch (Exception e) { return false; }
            });
        }
    }
    private HttpRequest request(String method,String path,JsonNode body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+httpPort+path)).timeout(Duration.ofSeconds(30))
                .header("X-Archimesh-User","drill").header("X-Archimesh-Roles","admin").header("Content-Type","application/json")
                .method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }
    private JsonNode ok(String method,String path,JsonNode body) throws Exception {
        var response = http.send(request(method,path,body),HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200,response.statusCode(),response.body()); return JSON.readTree(response.body());
    }
    private JsonNode snapshot() throws Exception { return ok("GET","/models/"+model+"/snapshot",null); }
    private Client connect(Long lastSeen) throws Exception { return new Client(lastSeen); }
    private static void await(String description,Duration timeout,BooleanSupplier condition) {
        long deadline = System.nanoTime()+timeout.toNanos(); Throwable last = null;
        while (System.nanoTime()<deadline) {
            try { if (condition.getAsBoolean()) return; }
            catch (org.neo4j.driver.exceptions.ClientException invalidQuery) { throw invalidQuery; }
            catch (Exception failure) { last = failure; }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(description+" interrupted",e); }
        }
        throw new AssertionError("Timed out waiting for "+description,last);
    }

    private final class Client implements WebSocket.Listener,AutoCloseable {
        private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();
        private final WebSocket socket;
        final JsonNode checkout;
        Client(Long lastSeen) throws Exception {
            socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create("ws://127.0.0.1:"+httpPort+"/models/"+model+"/stream?user=drill&roles=admin"),this).get(10,TimeUnit.SECONDS);
            var payload = JSON.createObjectNode().put("ref","HEAD");
            if (lastSeen!=null) payload.put("lastSeenRevision",lastSeen);
            actor(payload); send("Join",payload);
            checkout = waitFor("Checkout",null,Duration.ofSeconds(15)); Assertions.assertNotNull(checkout);
        }
        private void actor(ObjectNode payload) { payload.putObject("actor").put("userId","drill").put("sessionId","drill-client"); }
        private void send(String type,ObjectNode payload) {
            var envelope = JSON.createObjectNode().put("type",type); envelope.set("payload",payload); socket.sendText(envelope.toString(),true).join();
        }
        JsonNode submit(String id,long base,ArrayNode operations) throws Exception {
            var payload = JSON.createObjectNode().put("opBatchId",id).put("baseRevision",base); actor(payload); payload.set("ops",operations);
            send("SubmitOps",payload); JsonNode accepted = waitFor("OpsAccepted",id,Duration.ofSeconds(20));
            Assertions.assertNotNull(accepted,"submit received no acceptance for "+id); return accepted;
        }
        JsonNode waitFor(String type,String id,Duration timeout) throws Exception {
            long deadline = System.nanoTime()+timeout.toNanos();
            while (System.nanoTime()<deadline) {
                JsonNode message = messages.poll(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                if (message==null) return null;
                Assertions.assertNotEquals("Error",message.path("type").asText(),message.toString());
                if (!(type.equals("Checkout") ? message.path("type").asText().startsWith("Checkout") : type.equals(message.path("type").asText()))) continue;
                String batch = message.path("payload").path("opBatchId").asText();
                if (batch.isEmpty()) batch = message.path("payload").path("opBatch").path("opBatchId").asText();
                if (id==null || id.equals(batch)) return message;
            }
            return null;
        }
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) {
            text.append(data);
            if (last) {
                try { messages.add(JSON.readTree(text.toString())); }
                catch (IOException e) { throw new CompletionException(e); }
                text.setLength(0);
            }
            ws.request(1); return null;
        }
        @Override public CompletionStage<?> onPing(WebSocket ws,ByteBuffer data) { ws.request(1); return ws.sendPong(data); }
        @Override public void close() { socket.abort(); }
    }
}
