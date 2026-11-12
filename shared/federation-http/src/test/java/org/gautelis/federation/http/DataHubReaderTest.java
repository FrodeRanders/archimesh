package org.gautelis.federation.http;

import com.sun.net.httpserver.*;
import org.gautelis.federation.Identity.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DataHubReaderTest {
    private HttpServer server;
    private final Repository repository=new Repository("datahub","local-catalog");
    private final Representation dataset=new Representation(new Resource(repository,ResourceKind.OBJECT,
            "urn:li:dataset:(urn:li:dataPlatform:postgres,demo.order,%2FDEV)"),"dataset");
    private final SourceReader.Field name=new SourceReader.Field("datasetProperties","/name");
    private final SourceReader.Field curated=new SourceReader.Field("editableDatasetProperties","/description");
    private int status=200; private String body; private final List<String> requests=new ArrayList<>();
    @BeforeEach void start() throws Exception {
        body="{\"aspect\":{\"com.linkedin.dataset.DatasetProperties\":{\"name\":\"Orders\"}}}";
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            requests.add(exchange.getRequestURI().toString());
            assertEquals("GET",exchange.getRequestMethod()); assertEquals("Bearer synthetic",exchange.getRequestHeaders().getFirst("Authorization"));
            String response=body; int code=status;
            if (exchange.getRequestURI().getQuery().contains("aspect=status") && code==200) response="{\"aspect\":{\"com.linkedin.common.Status\":{\"removed\":false}}}";
            if (exchange.getRequestURI().getQuery().contains("aspect=editableDatasetProperties") && code==200) { code=404; response="{}"; }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(code,bytes.length);
            try (var out=exchange.getResponseBody()) { out.write(bytes); }
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private DataHubReader reader(int limit) { return new DataHubReader(repository,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),() -> "synthetic",Duration.ofSeconds(2),limit,false); }
    @Test void exactNativeUrnFieldsAndAbsentCuratedAspectArePreserved() {
        var snapshot=reader(65536).read(dataset,Set.of(name,curated));
        assertEquals("Orders",snapshot.fields().get(name).value().asText()); assertFalse(snapshot.fields().get(curated).present());
        assertEquals(SourceReader.Lifecycle.ACTIVE,snapshot.lifecycle());
        assertTrue(requests.getFirst().contains("%252FDEV"));
        assertEquals(snapshot.digest(),reader(65536).read(dataset,Set.of(curated,name)).digest());
    }
    @Test void deniedAndMissingAreDistinctAndDoNotExposeRemoteBodies() {
        for (int code:List.of(401,403,404,429,503)) {
            status=code; body="secret response body";
            var failure=assertThrows(RepositoryFailure.class,() -> reader(65536).read(dataset,Set.of(name)));
            assertFalse(failure.toString().contains(body)); assertNull(failure.getCause());
            assertEquals(switch(code) { case 401 -> RepositoryFailure.Kind.AUTHENTICATION; case 403 -> RepositoryFailure.Kind.AUTHORIZATION;
                case 404 -> RepositoryFailure.Kind.NOT_FOUND; case 429 -> RepositoryFailure.Kind.RATE_LIMIT; default -> RepositoryFailure.Kind.TRANSIENT; },failure.kind());
        }
    }
    @Test void duplicatesOversizeAndWrongResponseIdentityFailClosed() {
        for (String invalid:List.of("{\"aspect\":{},\"aspect\":{}}","{\"urn\":\"wrong\",\"aspect\":{\"com.linkedin.X\":{}}}","x".repeat(2000))) {
            body=invalid; assertEquals(RepositoryFailure.Kind.INVALID_RESPONSE,assertThrows(RepositoryFailure.class,() -> reader(1024).read(dataset,Set.of(name))).kind());
        }
    }
    @Test void nullAndAbsentValuesHaveDifferentDigestsAndDispatchRejectsWrongIdentity() {
        var absent=SourceReader.Value.of(RepositoryJson.JSON.missingNode()); var nil=SourceReader.Value.of(RepositoryJson.JSON.nullNode());
        assertNotEquals(absent.digest(),nil.digest());
        var different=new Representation(new Resource(new Repository("datahub","other"),ResourceKind.OBJECT,dataset.resource().nativeId()),"dataset");
        var dispatch=new ReaderDispatch(Map.of(repository,(representation,fields) -> SourceReader.snapshot(different,SourceReader.Lifecycle.ACTIVE,Map.of(name,nil))));
        assertThrows(RepositoryFailure.class,() -> dispatch.read(dataset,Set.of(name)));
        assertThrows(RepositoryFailure.class,() -> new ReaderDispatch(Map.of()).read(dataset,Set.of(name)));
    }
    @Test void credentialsRedirectsAndPlainHttpRequireExplicitConfiguration() {
        for (String invalid:List.of("http://example.org","//example.org","https://user:password@example.org","https://example.org/?token=secret"))
            assertThrows(RepositoryFailure.class,() -> new DataHubReader(repository,URI.create(invalid),() -> "synthetic",Duration.ofSeconds(1),1024,false));
        status=302; body="{}";
        assertEquals(RepositoryFailure.Kind.INVALID_RESPONSE,assertThrows(RepositoryFailure.class,() -> reader(65536).read(dataset,Set.of(name))).kind());
        assertEquals(1,requests.size());
    }
}
