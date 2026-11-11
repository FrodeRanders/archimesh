package org.gautelis.archimesh.integration.turboea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gautelis.archimesh.integration.ConnectorException;
import org.gautelis.archimesh.integration.ExternalRepositoryConnector;
import org.gautelis.archimesh.integration.IntegrationIdentity;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import static org.gautelis.archimesh.integration.ConnectorException.Kind.*;

/** Bounded reads of the verified v1 surface. No implicit retries, redirects, SSE or writes. */
public final class TurboEaClient implements ExternalRepositoryConnector, AutoCloseable {
    private final URI base;
    private final String expectedVersion;
    private final Supplier<String> token;
    private final Duration timeout;
    private final int maxBytes;
    private final ObjectMapper json;
    private final HttpClient http;

    public TurboEaClient(URI base, String expectedVersion, Supplier<String> token,
                         Duration timeout, int maxBytes, ObjectMapper json) {
        validateBaseUrl(base);
        this.base = URI.create(base.toString().replaceAll("/+$", ""));
        this.expectedVersion = IntegrationIdentity.required(expectedVersion);
        this.token = java.util.Objects.requireNonNull(token);
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofMinutes(2)) > 0
                || maxBytes < 1024 || maxBytes > 16 * 1024 * 1024)
            throw new IllegalArgumentException("Invalid external HTTP limits");
        this.timeout = timeout; this.maxBytes = maxBytes; this.json = json;
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static void validateBaseUrl(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
            throw new IllegalArgumentException("Turbo EA base URL must be an origin without credentials, path, query or fragment");
        boolean loopback = List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback))
            throw new IllegalArgumentException("Turbo EA requires HTTPS except on loopback");
    }

    @Override public String healthVersion() {
        JsonNode health = get("/api/health", false);
        String version = text(health, "version");
        if (!"ok".equals(text(health, "status"))) throw invalid();
        if (!expectedVersion.equals(version)) throw new ConnectorException(INCOMPATIBLE_API,0,Duration.ZERO);
        return version;
    }

    @Override public CatalogPage search(String search, String type, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 1000 || (search != null && search.length() > 200))
            throw new IllegalArgumentException("Invalid catalog page or search bounds");
        String path = "/api/v1/cards?page=" + page + "&page_size=" + pageSize;
        if (search != null && !search.isBlank()) path += "&search=" + encode(search);
        if (type != null) path += "&type=" + encode(IntegrationIdentity.required(type));
        var response = get(path, true);
        if (!response.path("items").isArray() || !response.path("total").canConvertToLong()
                || !response.path("total").isIntegralNumber() || response.path("total").asLong() < 0
                || !response.path("page").isIntegralNumber() || !response.path("page_size").isIntegralNumber()
                || response.path("page").asLong() != page || response.path("page_size").asLong() != pageSize
                || response.path("items").size() > pageSize) throw invalid();
        var items = new ArrayList<ExternalObject>();
        response.path("items").forEach(item -> items.add(object(item)));
        var withheld = new ArrayList<String>();
        var hidden = response.path("withheld_ids");
        if (!hidden.isMissingNode()) {
            if (!hidden.isArray()) throw invalid();
            hidden.forEach(id -> { if (!id.isTextual() || id.asText().isBlank()) throw invalid(); withheld.add(id.asText()); });
        }
        return new CatalogPage(items, response.path("total").asLong(), page, pageSize, withheld);
    }

    @Override public ExternalObject getObject(String resourceId) {
        var result = object(get("/api/v1/cards/" + resourceSegment(resourceId), true));
        if (!resourceId.equals(result.id())) throw invalid();
        return result;
    }

    @Override public List<ExternalRelation> relations(String resourceId) {
        IntegrationIdentity.required(resourceId);
        var response = get("/api/v1/relations?card_id=" + encode(resourceId), true);
        if (!response.isArray()) throw invalid();
        var result = new ArrayList<ExternalRelation>();
        response.forEach(row -> {
            String source = text(row, "source_id"), target = text(row, "target_id");
            if (!resourceId.equals(source) && !resourceId.equals(target)) throw invalid();
            result.add(new ExternalRelation(text(row,"id"),text(row,"type"),source,target,
                    optionalText(row,"description"), objectOrNull(row,"attributes")));
        });
        return List.copyOf(result);
    }

    @Override public Metamodel metamodel() {
        var types = get("/api/v1/metamodel/types", true);
        var relations = get("/api/v1/metamodel/relation-types", true);
        if (!types.isArray() || !relations.isArray()) throw invalid();
        var cards = new HashMap<String, CardType>();
        types.forEach(row -> {
            String key = text(row, "key");
            if (!row.path("fields_schema").isArray() || !row.path("is_hidden").isBoolean()
                    || cards.put(key,new CardType(key,row.path("fields_schema").deepCopy(),row.path("is_hidden").asBoolean())) != null)
                throw invalid();
        });
        var rels = new HashMap<String, RelationType>();
        relations.forEach(row -> {
            String key = text(row,"key");
            var value = new RelationType(key,text(row,"source_type_key"),text(row,"target_type_key"),text(row,"cardinality"),
                    row.path("attributes_schema").deepCopy(), row.path("is_hidden").asBoolean());
            if (!value.attributesSchema().isArray() || !row.path("is_hidden").isBoolean() || rels.put(key,value) != null)
                throw invalid();
        });
        return new Metamodel(cards,rels);
    }

    private ExternalObject object(JsonNode row) {
        return new ExternalObject(text(row,"id"),text(row,"type"),text(row,"name"),optionalText(row,"description"),
                optionalText(row,"external_id"),text(row,"status"),objectOrNull(row,"attributes"),objectOrNull(row,"lifecycle"));
    }

    private JsonNode get(String path, boolean authenticated) {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).header("Accept","application/json");
        if (authenticated) {
            String secret;
            try { secret = token.get(); }
            catch (RuntimeException unavailable) { throw new ConnectorException(CONFIGURATION,0,Duration.ZERO); }
            if (secret == null || secret.isBlank() || secret.chars().anyMatch(c -> c <= 32 || c >= 127))
                throw new ConnectorException(CONFIGURATION,0,Duration.ZERO);
            request.header("Authorization","Bearer " + secret);
        }
        var future = http.sendAsync(request.GET().build(), info -> new LimitedBody(maxBytes));
        HttpResponse<byte[]> response;
        try { response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            future.cancel(true); Thread.currentThread().interrupt(); throw new ConnectorException(TRANSIENT,0,Duration.ZERO);
        } catch (TimeoutException timeoutFailure) {
            future.cancel(true); throw new ConnectorException(TRANSIENT,0,Duration.ZERO);
        } catch (ExecutionException failure) {
            boolean oversized = failure.getCause() instanceof BodyLimitException;
            throw new ConnectorException(oversized ? INVALID_RESPONSE : TRANSIENT,0,Duration.ZERO);
        }
        int status = response.statusCode();
        if (status != 200) {
            var kind = switch (status) {
                case 401 -> AUTHENTICATION;
                case 403 -> AUTHORIZATION;
                case 404 -> NOT_FOUND;
                case 429 -> RATE_LIMIT;
                case 400, 409, 422 -> VALIDATION;
                case 408 -> TRANSIENT;
                default -> status >= 500 ? TRANSIENT : INVALID_RESPONSE;
            };
            throw new ConnectorException(kind,status,retryAfter(response.headers().firstValue("Retry-After").orElse("")));
        }
        String media = response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim();
        if (!"application/json".equalsIgnoreCase(media)) throw invalid();
        try {
            var value = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(response.body());
            if (value == null) throw invalid();
            return value;
        } catch (java.io.IOException malformed) { throw invalid(); }
    }

    private static Duration retryAfter(String value) {
        try {
            long seconds;
            try { seconds = Long.parseLong(value); }
            catch (NumberFormatException notSeconds) {
                seconds = Duration.between(java.time.Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds();
            }
            return Duration.ofSeconds(Math.max(0,Math.min(seconds,3600)));
        } catch (RuntimeException malformed) { return Duration.ZERO; }
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 16_384) throw invalid();
        return value.asText();
    }
    private static String optionalText(JsonNode node, String field) {
        var value = node.path(field);
        if (value.isNull() || value.isMissingNode()) return null;
        if (!value.isTextual()) throw invalid();
        return value.asText();
    }
    private static JsonNode objectOrNull(JsonNode node, String field) {
        var value = node.path(field);
        if (value.isNull() || value.isMissingNode()) return null;
        if (!value.isObject()) throw invalid();
        return value.deepCopy();
    }
    private static String resourceSegment(String value) {
        IntegrationIdentity.required(value);
        if (value.equals(".") || value.equals("..")) throw new IllegalArgumentException("Invalid resource identity");
        return encode(value);
    }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
    private static ConnectorException invalid() { return new ConnectorException(INVALID_RESPONSE,0,Duration.ZERO); }
    @Override public void close() { http.shutdownNow(); }

    private static final class BodyLimitException extends RuntimeException { }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new BodyLimitException()); return;
                }
                var chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
