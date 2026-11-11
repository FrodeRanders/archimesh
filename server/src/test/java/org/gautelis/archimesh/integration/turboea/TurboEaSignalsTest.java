package org.gautelis.archimesh.integration.turboea;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.gautelis.archimesh.integration.ConnectorException;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TurboEaSignalsTest {
    private HttpServer server;
    private ExecutorService workers;
    private TurboEaSignals signals;
    private String body="data: {\"event\":\"card.updated\",\"card_id\":\"card-a\",\"data\":{\"name\":\"Never apply this payload\"}}\n\n";
    private int status=200;
    private String media="text/event-stream",query,cookie,retryAfter="";
    private long delay;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); workers=Executors.newCachedThreadPool(); server.setExecutor(workers);
        server.createContext("/api/v1/events/stream",exchange -> {
            query=exchange.getRequestURI().getRawQuery(); cookie=exchange.getRequestHeaders().getFirst("Cookie");
            exchange.getResponseHeaders().set("Content-Type",media); exchange.getResponseHeaders().set("Retry-After",retryAfter); byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length);
            try { if (delay>0) Thread.sleep(delay); exchange.getResponseBody().write(bytes); } catch (Exception ignored) { } finally { exchange.close(); }
        }); server.start(); signals=new TurboEaSignals(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),() -> "fixture-token",new ObjectMapper(),Duration.ofSeconds(1));
    }
    @AfterEach void stop() { signals.close(); server.stop(0); workers.shutdownNow(); }
    @Test void authenticatesByCookieAndEmitsOnlyCardInvalidationsWithoutResumeAssumptions() {
        var ids=new ArrayList<String>(); body=": keepalive\r\nid: untrusted-resume\r\n"+body.replace("\n","\r\n")+body+"data: {\"event\":\"notification.created\",\"data\":{\"name\":\"private\"}}\n\n";
        assertEquals(2,signals.listen(ids::add,Duration.ofSeconds(2))); assertEquals(List.of("card-a","card-a"),ids);
        assertNull(query); assertEquals("access_token=fixture-token",cookie);
    }
    @Test void deadlineBoundsSilentStreamsAndCloseDisconnects() {
        delay=3000; long start=System.nanoTime();
        try { assertEquals(0,signals.listen(id -> fail(),Duration.ofMillis(150))); }
        catch (ConnectorException beforeHeaders) { assertEquals(ConnectorException.Kind.TRANSIENT,beforeHeaders.kind()); }
        assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1500);
    }
    @Test void rejectsMalformedOversizedAndWrongMediaStreams() {
        for (String invalid:List.of("data: {bad}\n\n","data: "+"x".repeat(70000)+"\n\n","data: {\"card_id\":\"a\",\"card_id\":\"b\"}\n\n")) {
            body=invalid; assertEquals(ConnectorException.Kind.INVALID_RESPONSE,assertThrows(ConnectorException.class,() -> signals.listen(id -> fail(),Duration.ofSeconds(2))).kind());
        }
        media="application/json"; assertEquals(ConnectorException.Kind.INVALID_RESPONSE,assertThrows(ConnectorException.class,() -> signals.listen(id -> fail(),Duration.ofSeconds(2))).kind());
    }
    @Test void rateLimitRespectsBoundedRetryAfter() {
        status=429; retryAfter="60";
        var error=assertThrows(ConnectorException.class,() -> signals.listen(id -> fail(),Duration.ofSeconds(2)));
        assertTrue(error.retryable()); assertEquals(Duration.ofSeconds(60),error.retryAfter());
    }
    @Test void authenticationFailuresDoNotExposeCredentialsOrBodies() {
        status=401; body="sensitive fixture-token";
        var error=assertThrows(ConnectorException.class,() -> signals.listen(id -> fail(),Duration.ofSeconds(2)));
        assertEquals(ConnectorException.Kind.AUTHENTICATION,error.kind()); assertFalse(error.toString().contains("fixture-token")); assertNull(error.getCause());
    }
}
