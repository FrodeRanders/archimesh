package org.gautelis.archimesh.client;

import com.sun.net.httpserver.HttpServer;
import org.gautelis.archimesh.plugin.ui.EnterpriseRepositoryClient;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class EnterpriseRepositoryClientTest {
    private HttpServer server;
    private EnterpriseRepositoryClient client;
    private final AtomicReference<String> response=new AtomicReference<>("[]");
    private final List<String> bodies=new ArrayList<>();
    private String auth,user,path;
    private int status=200;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            path=exchange.getRequestURI().getRawPath(); auth=exchange.getRequestHeaders().getFirst("Authorization");
            user=exchange.getRequestHeaders().getFirst("X-Archimesh-User"); bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        client=new EnterpriseRepositoryClient("ws://127.0.0.1:"+server.getAddress().getPort(),"model/one","archimesh-test-token","architect");
    }
    @AfterEach void stop() { client.close(); server.stop(0); }
    @Test void onlyCallsArchimeshWithSessionIdentityAndParsesTargetScope() throws Exception {
        response.set("[{\"key\":\"shared\",\"types\":[\"Application\",\"BusinessProcess\"],\"canImport\":true}]");
        var targets=client.targets();
        assertEquals(List.of("Application","BusinessProcess"),targets.getFirst().types()); assertTrue(targets.getFirst().canImport());
        assertEquals("/models/model%2Fone/external-objects/targets",path); assertEquals("Bearer archimesh-test-token",auth); assertEquals("architect",user);
    }
    @Test void previewCarriesAuthorityAndCompatibleModelElements() throws Exception {
        response.set("{\"cardId\":\"a\",\"archimateType\":\"ApplicationComponent\",\"name\":\"App\",\"digest\":\"digest\",\"compatibleElements\":[{\"id\":\"elem:local\",\"name\":\"Local\"}],\"fields\":[{\"field\":\"name\",\"value\":\"App\",\"authority\":\"TURBO_EA_MASTER\"}]}");
        var preview=client.preview("shared","a"); assertEquals("elem:local",preview.compatible().getFirst().id());
        assertTrue(preview.detail().contains("TURBO_EA_MASTER")); assertTrue(preview.detail().contains("preserves"));
    }
    @Test void retriesKeepRequestIdentityAndReportDurableAcceptanceWithoutLocalMutation() throws Exception {
        response.set("{\"elementId\":\"elem:imported\",\"canonicalId\":\"urn:uuid:one\",\"acceptedRevision\":2}");
        var request=new EnterpriseRepositoryClient.Request(UUID.randomUUID().toString(),"shared","a\"\nb","CREATE",null,"digest");
        var accepted=client.importObject(request); client.importObject(request);
        assertEquals("elem:imported",accepted.elementId()); assertEquals(2,accepted.acceptedRevision());
        assertEquals(bodies.get(0),bodies.get(1)); assertTrue(bodies.getFirst().contains(request.requestId()));
        assertTrue(bodies.getFirst().contains("a\\\"\\u000ab")); assertTrue(bodies.getFirst().contains("\"ref\":\"HEAD\""));
    }
    @Test void errorsNeverDisplayRawServerBodiesOrTokens() {
        status=503; response.set("PRIVATE-DATA archimesh-test-token");
        var error=assertThrows(EnterpriseRepositoryClient.RequestFailure.class,client::targets);
        assertEquals(503,error.status()); assertFalse(error.toString().contains("PRIVATE")); assertFalse(error.toString().contains("test-token"));
    }
}
