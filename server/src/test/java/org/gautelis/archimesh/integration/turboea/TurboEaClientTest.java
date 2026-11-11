package org.gautelis.archimesh.integration.turboea;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.gautelis.archimesh.integration.ConnectorException;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.ConnectorException.Kind.*;

class TurboEaClientTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> token = new AtomicReference<>("secret-test-token");
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> authorization = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile String query;
    private volatile int status = 200;
    private volatile String media = "application/json";
    private volatile String retryAfter = "";
    private volatile long bodyDelay;
    private volatile String method,payload;
    private volatile java.util.Map<String,String> routes = java.util.Map.of();
    private TurboEaClient client;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/",this::respond); server.start();
        client = client(Duration.ofSeconds(3),4096);
    }
    private TurboEaClient client(Duration timeout, int limit) {
        return new TurboEaClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "2.194.0",token::get,timeout,limit,new ObjectMapper());
    }
    private void respond(HttpExchange exchange) throws java.io.IOException {
        calls.incrementAndGet(); query=exchange.getRequestURI().getRawQuery();
        method=exchange.getRequestMethod(); payload=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        authorization.add(header == null ? "" : header);
        exchange.getResponseHeaders().set("Content-Type",media);
        exchange.getResponseHeaders().set("Retry-After",retryAfter);
        exchange.getResponseHeaders().set("Location","/redirected");
        byte[] bytes = routes.getOrDefault(exchange.getRequestURI().getPath(),body.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status,bytes.length);
        try {
            if (bodyDelay > 0) Thread.sleep(bodyDelay);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { exchange.close(); }
    }
    @AfterEach void stop() { client.close(); server.stop(0); executor.shutdownNow(); }
    private static String card(String id) {
        return "{\"id\":\""+id+"\",\"type\":\"Application\",\"name\":\"App\",\"status\":\"ACTIVE\",\"attributes\":{\"owner\":\"team\"}}";
    }
    private ConnectorException failure(Runnable request, ConnectorException.Kind expected) {
        var error = assertThrows(ConnectorException.class,request::run);
        assertEquals(expected,error.kind());
        assertFalse(error.toString().contains(token.get()));
        assertFalse(error.toString().contains("remote-sensitive"));
        assertNull(error.getCause());
        return error;
    }

    @Test void searchUsesBoundedExplicitPaginationAndSafeEncoding() {
        body.set("{\"items\":["+card("a")+"],\"total\":2,\"page\":1,\"page_size\":1,\"withheld_ids\":[\"hidden\"]}");
        var page = client.search("a &b/å","Application",1,1);
        assertEquals(2,page.total()); assertEquals("a",page.items().getFirst().id());
        assertEquals(List.of("hidden"),page.withheldIds());
        assertTrue(query.contains("search=a%20%26b%2F%C3%A5"));
        assertTrue(query.contains("type=Application"));
        assertFalse(query.contains("token"));
        assertEquals(List.of("Bearer secret-test-token"),authorization);
        assertThrows(UnsupportedOperationException.class,() -> page.items().clear());
    }

    @Test void invalidRequestBoundsDoNotReachRemote() {
        assertThrows(IllegalArgumentException.class,() -> client.search(null,null,0,1));
        assertThrows(IllegalArgumentException.class,() -> client.search(null,null,1,1001));
        assertThrows(IllegalArgumentException.class,() -> client.search("a".repeat(201),null,1,1));
        assertThrows(IllegalArgumentException.class,() -> client.getObject(".."));
        assertEquals(0,calls.get());
    }

    @Test void readsCardsAndRelationsButRejectsMismatchedIdentities() {
        body.set(card("a")); assertEquals("team",client.getObject("a").attributes().path("owner").asText());
        failure(() -> client.getObject("b"),INVALID_RESPONSE);
        body.set("[{\"id\":\"r\",\"type\":\"Uses\",\"source_id\":\"a\",\"target_id\":\"b\"}]");
        assertEquals("b",client.relations("a").getFirst().targetId());
        failure(() -> client.relations("unrelated"),INVALID_RESPONSE);
        body.set("{\"items\":[]}"); failure(() -> client.relations("a"),INVALID_RESPONSE);
    }

    @Test void preservesUnownedDecimalValuesForReplacementAttributeMaps() {
        body.set(card("a").replace("\"owner\":\"team\"", "\"cost\":1.12345678901234567890123456789"));
        assertEquals(new java.math.BigDecimal("1.12345678901234567890123456789"),client.getObject("a").attributes().path("cost").decimalValue());
    }

    @Test void metamodelRequiresWireTypesAndUniqueKeys() {
        body.set("[]"); assertTrue(client.metamodel().cardTypes().isEmpty());
        body.set("[{\"key\":\"Application\",\"fields_schema\":[],\"is_hidden\":false},"
                + "{\"key\":\"Application\",\"fields_schema\":[],\"is_hidden\":false}]");
        failure(client::metamodel,INVALID_RESPONSE);
        body.set("[{\"key\":\"Application\",\"fields_schema\":{}}]"); failure(client::metamodel,INVALID_RESPONSE);
    }

    @Test void targetInspectionValidatesActualWireMetamodelAgainstExplicitMapping() {
        routes=java.util.Map.of(
                "/api/health","{\"status\":\"ok\",\"version\":\"2.194.0\"}",
                "/api/v1/metamodel/types","[{\"key\":\"Application\",\"fields_schema\":[],\"is_hidden\":false}]",
                "/api/v1/metamodel/relation-types","[{\"key\":\"Uses\",\"source_type_key\":\"Application\",\"target_type_key\":\"Application\",\"cardinality\":\"n:m\",\"attributes_schema\":[],\"is_hidden\":false}]");
        assertEquals("2.194.0",client.healthVersion());
        var metamodel=client.metamodel();
        var mapping = new TurboEaMapping("pilot-v1",java.util.Map.of("Application","ApplicationComponent"),
                java.util.Map.of("Uses",new TurboEaMapping.RelationMapping("ServingRelationship","Application","Application","n:m",false)),java.util.Map.of());
        mapping.validate(metamodel);
        assertEquals("n:m",metamodel.relationTypes().get("Uses").cardinality());
        assertEquals(3,calls.get());
    }

    @Test void errorTaxonomyDoesNotRetryOrExposeRemoteBodies() {
        var statuses = List.of(401,403,404,429,400,422,500,302);
        var kinds = List.of(AUTHENTICATION,AUTHORIZATION,NOT_FOUND,RATE_LIMIT,VALIDATION,VALIDATION,TRANSIENT,INVALID_RESPONSE);
        body.set("remote-sensitive " + token.get()); retryAfter="90000";
        for (int i=0;i<statuses.size();i++) {
            status=statuses.get(i);
            var error = failure(() -> client.getObject("a"),kinds.get(i));
            assertEquals(status,error.status());
            assertEquals(status == 429 || status >= 500,error.retryable());
            if (status == 429) assertEquals(Duration.ofHours(1),error.retryAfter());
        }
        assertEquals(statuses.size(),calls.get(),"no retries and no followed redirect");
    }

    @Test void malformedOversizedAndWrongMediaResponsesFailClosed() {
        body.set("{\"remote-sensitive\": "); failure(() -> client.getObject("a"),INVALID_RESPONSE);
        body.set(card("a") + " {}"); failure(() -> client.getObject("a"),INVALID_RESPONSE);
        body.set("{\"id\":\"a\",\"id\":\"b\"}"); failure(() -> client.getObject("a"),INVALID_RESPONSE);
        body.set("x".repeat(5000)); failure(() -> client.getObject("a"),INVALID_RESPONSE);
        body.set(card("a")); media="text/html"; failure(() -> client.getObject("a"),INVALID_RESPONSE);
    }

    @Test void deadlineIncludesStalledResponseBody() {
        client.close(); client=client(Duration.ofMillis(200),4096); body.set(card("a")); bodyDelay=2000;
        long start=System.nanoTime();
        failure(() -> client.getObject("a"),TRANSIENT);
        assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis() < 1500);
        assertEquals(1,calls.get());
    }

    @Test void tokenRotationAndHealthVersionAreExplicit() {
        body.set(card("a")); client.getObject("a"); token.set("rotated-token"); client.getObject("a");
        assertEquals(List.of("Bearer secret-test-token","Bearer rotated-token"),authorization);
        body.set("{\"status\":\"ok\",\"version\":\"2.194.0\"}"); assertEquals("2.194.0",client.healthVersion());
        assertEquals("",authorization.getLast(),"health does not require a secret");
        body.set("{\"status\":\"ok\",\"version\":\"different\"}"); failure(client::healthVersion,INCOMPATIBLE_API);
        token.set("invalid\r\nheader"); failure(() -> client.getObject("a"),CONFIGURATION);
    }

    @Test void paginationAndPayloadShapesAreValidated() {
        for (String invalid : List.of("{\"items\":[],\"total\":0,\"page\":2,\"page_size\":1}",
                "{\"items\":[],\"total\":-1,\"page\":1,\"page_size\":1}",
                "{\"items\":[],\"total\":0.5,\"page\":1,\"page_size\":1}",
                "{\"items\":[],\"total\":0,\"page\":1,\"page_size\":1,\"withheld_ids\":{}}")) {
            body.set(invalid); failure(() -> client.search(null,null,1,1),INVALID_RESPONSE);
        }
    }

    @Test void baseUrlCannotContainCredentialsOrRedirectSecretsToPlaintextOrigins() {
        for (String invalid : List.of("http://example.com","https://user:password@example.com",
                "https://example.com/api/v1","https://example.com?token=x","https://example.com#fragment","file:///tmp/api"))
            assertThrows(IllegalArgumentException.class,() -> TurboEaClient.validateBaseUrl(URI.create(invalid)));
        TurboEaClient.validateBaseUrl(URI.create("https://example.com"));
        TurboEaClient.validateBaseUrl(URI.create("http://[::1]:8920"));
    }

    @Test void publicationWritesUseExactMethodsAndNeverAllowIdentityOrLifecycleDeletionPatches() throws Exception {
        var json=new ObjectMapper(); status=201; body.set(card("created"));
        var create=json.createObjectNode().put("type","Application").put("name","App").put("external_id","urn:uuid:canonical").put("strict_attributes",true);
        client.createObject(create); assertEquals("POST",method); assertTrue(json.readTree(payload).path("strict_attributes").asBoolean());
        status=200; body.set(card("created"));
        client.patchObject("created",json.createObjectNode().put("description","Owned description")); assertEquals("PATCH",method);
        for (String forbidden:List.of("external_id","type","status","parent_id"))
            assertThrows(IllegalArgumentException.class,() -> client.patchObject("created",json.createObjectNode().put(forbidden,"bad")));
        int before=calls.get(); status=503;
        failure(() -> client.createObject(create),TRANSIENT); assertEquals(before+1,calls.get(),"POST is not retried after an uncertain response");
    }

    @Test void relationPostOmitsFieldsThatWouldMergeIntoAnExistingManualRelation() throws Exception {
        status=201; body.set("{\"id\":\"r\",\"type\":\"Serves\",\"source_id\":\"a\",\"target_id\":\"b\",\"attributes\":{\"manual\":\"keep\"}}");
        var result=client.createRelation("Serves","a","b");
        var posted=new ObjectMapper().readTree(payload);
        assertEquals("r",result.id()); assertEquals("POST",method); assertFalse(posted.has("attributes")); assertFalse(posted.has("description"));
    }
}
