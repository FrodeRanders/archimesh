package org.gautelis.federation.http;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.*;
import org.gautelis.federation.Identity.Repository;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class DataHubApplicationWriterTest {
    private HttpServer server;
    private final List<JsonNode> proposals=new ArrayList<>();
    private int code=200;
    private String urn="urn:li:application:Case.%2FApp";
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            assertEquals("Bearer app-only",exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response="{}".getBytes(); int status=code;
            if(exchange.getRequestMethod().equals("POST")) {
                proposals.add(RepositoryJson.JSON.readTree(exchange.getRequestBody()));
            } else {
                assertTrue(exchange.getRequestURI().getRawPath().contains("Case.%252FApp"));
                String aspect=exchange.getRequestURI().getQuery();
                if(aspect.contains("aspect=applicationProperties")) response="{\"aspect\":{\"com.linkedin.application.ApplicationProperties\":{\"name\":\"App\",\"parentApplication\":\"urn:li:application:parent\",\"customProperties\":{\"local.note\":\"keep\"}}}}".getBytes();
                else if(aspect.contains("aspect=status")) response="{\"aspect\":{\"com.linkedin.common.Status\":{\"removed\":false}}}".getBytes();
                else status=404;
            }
            exchange.sendResponseHeaders(status,response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private DataHubApplicationWriter writer() {
        return new DataHubApplicationWriter(new Repository("datahub","catalog"),URI.create("http://127.0.0.1:"+server.getAddress().getPort()),
            () -> "app-only",Duration.ofSeconds(2),65536,false);
    }
    @Test void nativeUrnsAndUnownedAspectMembersSurviveAndOnlyApplicationAspectsCanBeWritten() throws Exception {
        try(var writer=writer()) {
            var current=writer.read(urn).orElseThrow(); assertTrue(current.active());
            var properties=current.properties().deepCopy().put("name","Renamed"); writer.writeProperties(urn,properties); writer.initializeStatus(urn);
            assertEquals(2,proposals.size());
            var proposal=proposals.getFirst().path("proposal");
            assertEquals(urn,proposal.path("entityUrn").asText()); assertEquals("application",proposal.path("entityType").asText());
            assertEquals("applicationProperties",proposal.path("aspectName").asText()); assertEquals("false",proposals.getFirst().path("async").asText());
            var value=RepositoryJson.JSON.readTree(proposal.path("aspect").path("value").asText());
            assertEquals("keep",value.path("customProperties").path("local.note").asText());
            assertEquals("urn:li:application:parent",value.path("parentApplication").asText());
            assertThrows(RepositoryFailure.class,() -> writer.writeProperties("urn:li:dataset:any",properties));
            assertThrows(RepositoryFailure.class,() -> writer.initializeStatus("urn:li:dataProduct:any"));
            assertEquals(2,proposals.size());
        }
    }
    @Test void rejectedSubmissionsNeverExposeRepositoryResponseOrCredentials() {
        for(int status:List.of(302,401,403,429,503)) {
            code=status;
            try(var writer=writer()) {
                var failure=assertThrows(RepositoryFailure.class,() -> writer.writeProperties(urn,RepositoryJson.JSON.createObjectNode().put("name","App")));
                assertFalse(failure.toString().contains("app-only")); assertNull(failure.getCause());
                assertEquals(switch(status) { case 401 -> RepositoryFailure.Kind.AUTHENTICATION; case 403 -> RepositoryFailure.Kind.AUTHORIZATION;
                    case 429 -> RepositoryFailure.Kind.RATE_LIMIT; case 503 -> RepositoryFailure.Kind.TRANSIENT; default -> RepositoryFailure.Kind.INVALID_RESPONSE; },failure.kind());
            }
        }
        assertEquals(5,proposals.size());
    }
}
