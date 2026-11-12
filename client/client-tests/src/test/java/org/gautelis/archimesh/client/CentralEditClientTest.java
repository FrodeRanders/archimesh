package org.gautelis.archimesh.client;

import com.sun.net.httpserver.HttpServer;
import org.gautelis.archimesh.plugin.ui.CentralEditClient;
import org.gautelis.archimesh.plugin.ui.EnterpriseRepositoryClient;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CentralEditClientTest {
    private HttpServer server;
    private CentralEditClient client;
    private final AtomicReference<String> response=new AtomicReference<>("{}");
    private final List<String> bodies=new ArrayList<>();
    private String path,auth,user;
    private int status=200;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.createContext("/",exchange -> {
            path=exchange.getRequestURI().getRawPath(); auth=exchange.getRequestHeaders().getFirst("Authorization"); user=exchange.getRequestHeaders().getFirst("X-Archimesh-User"); bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start(); client=new CentralEditClient("ws://127.0.0.1:"+server.getAddress().getPort(),"model/one","archimesh-token","architect");
    }
    @AfterEach void stop() { client.close(); server.stop(0); }
    @Test void contextPreservesNumericTextAndCallsOnlyTheArchiMeshOrigin() throws Exception {
        response.set("{\"elementId\":\"elem:a\",\"canonicalId\":\"urn:a\",\"resourceId\":\"card-a\",\"type\":\"Application\",\"fields\":[{\"field\":\"property:cost\",\"valueType\":\"NUMBER\",\"authority\":\"TURBO_EA_MASTER\",\"mode\":\"PROPOSAL\",\"current\":1.12345678901234567890123456789,\"local\":\"1\",\"baseline\":\"1\"}]}");
        var context=client.context("target/one","elem:a"); assertEquals("1.12345678901234567890123456789",context.fields().getFirst().current()); assertEquals("/models/model%2Fone/central-edits/target%2Fone/context/elem%3Aa",path); assertEquals("Bearer archimesh-token",auth); assertEquals("architect",user);
    }
    @Test void repeatedSubmissionUsesOneRequestIdentityAndLosslessJsonText() throws Exception {
        response.set("{\"id\":\"central:fixture\",\"state\":\"WAITING_APPROVAL\",\"remoteVerified\":false,\"synced\":0,\"pending\":0,\"conflicts\":0}");
        var request=new CentralEditClient.Request(UUID.randomUUID().toString(),"fixture","elem:a","property:cost","1.12345678901234567890123456789","digest");
        var job=client.submit(request); client.submit(request); assertEquals(bodies.get(0),bodies.get(1)); assertTrue(bodies.getFirst().contains("\"property:cost\":\"1.12345678901234567890123456789\"")); assertTrue(bodies.getFirst().contains("\"ref\":\"HEAD\"")); assertTrue(job.detail().contains("Remote result verified: false")); assertEquals("WAITING_APPROVAL",job.state());
    }
    @Test void fieldInputIsQuotedRatherThanInsertedAsJsonAndErrorsAreSanitized() throws Exception {
        response.set("{\"id\":\"central:fixture\",\"state\":\"PENDING\"}"); client.submit(new CentralEditClient.Request(UUID.randomUUID().toString(),"fixture","elem:a","name","a\"\n},\"status\":\"ARCHIVED","digest")); assertTrue(bodies.getFirst().contains("a\\\"\\u000a"));
        status=503; response.set("PRIVATE-DATA archimesh-token"); var error=assertThrows(EnterpriseRepositoryClient.RequestFailure.class,() -> client.get("fixture","central:fixture")); assertFalse(error.toString().contains("PRIVATE")); assertFalse(error.toString().contains("archimesh-token"));
    }
}
