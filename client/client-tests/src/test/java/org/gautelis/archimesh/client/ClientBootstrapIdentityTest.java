package org.gautelis.archimesh.client;

import com.sun.net.httpserver.HttpServer;
import org.gautelis.archimesh.plugin.ui.ModelCatalogClient;
import org.gautelis.archimesh.plugin.util.ClientBootstrapIdentity;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ClientBootstrapIdentityTest {
    private final Map<String,String> previous = new HashMap<>();
    @BeforeEach void setup() {
        for(String key : new String[]{"archimesh.bootstrap.user", "archimesh.bootstrap.roles"}) {
            previous.put(key, System.getProperty(key));
        }
        System.setProperty("archimesh.bootstrap.user", "demo architect");
        System.setProperty("archimesh.bootstrap.roles", "model_reader,model_writer");
    }
    @AfterEach void restore() {
        previous.forEach((key,value) -> {
            if(value == null) System.clearProperty(key); else System.setProperty(key,value);
        });
    }
    @Test void modelDiscoverySendsBootstrapIdentityToLocalServer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/models", exchange -> {
            boolean allowed = "demo architect".equals(exchange.getRequestHeaders().getFirst("X-Archimesh-User"))
                    && "model_reader,model_writer".equals(exchange.getRequestHeaders().getFirst("X-Archimesh-Roles"));
            byte[] body = (allowed ? "[{\"modelId\":\"seeded\",\"modelName\":\"Prepared model\"}]" : "[]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            assertEquals("seeded", ModelCatalogClient.fetchModels("ws://127.0.0.1:"+server.getAddress().getPort()).getFirst().modelId());
        } finally { server.stop(0); }
    }
    @Test void websocketIdentityIsEncodedForBootstrapHandshake() {
        URI uri = ClientBootstrapIdentity.webSocketUri(URI.create("ws://127.0.0.1:8081/models/demo/stream"), "");
        assertEquals("user=demo+architect&roles=model_reader%2Cmodel_writer", uri.getRawQuery());
        assertEquals("/models/demo/stream", uri.getPath());
    }
    @Test void bootstrapIsOptInAndBearerAuthenticationTakesPrecedence() {
        URI uri = URI.create("https://repository.example/models");
        var builder = HttpRequest.newBuilder(uri);
        ClientBootstrapIdentity.applyRestHeaders(builder,uri,"oidc-token");
        assertTrue(builder.GET().build().headers().map().isEmpty());
        assertEquals(uri, ClientBootstrapIdentity.webSocketUri(uri,"oidc-token"));
        System.setProperty("archimesh.bootstrap.user", "");
        System.setProperty("archimesh.bootstrap.roles", "");
        assertEquals(uri, ClientBootstrapIdentity.webSocketUri(uri,""));
    }
    @Test void partialIdentityAndRemoteServersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClientBootstrapIdentity.webSocketUri(URI.create("wss://repository.example/models/demo/stream"),""));
        System.setProperty("archimesh.bootstrap.roles", "");
        assertThrows(IllegalArgumentException.class, () -> ClientBootstrapIdentity.webSocketUri(URI.create("ws://localhost/models/demo/stream"),""));
    }
}
