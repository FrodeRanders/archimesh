package org.gautelis.archimesh.integration.turboea;

import com.fasterxml.jackson.databind.*;
import org.gautelis.archimesh.integration.ConnectorException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.*;
import static org.gautelis.archimesh.integration.ConnectorException.Kind.*;

/** Cookie-authenticated, bounded SSE windows. No query credentials, event replay or model payload application. */
public final class TurboEaSignals implements AutoCloseable {
    private final URI base;
    private final Supplier<String> token;
    private final ObjectMapper json;
    private final HttpClient http;
    private volatile CompletableFuture<?> pending;
    private volatile Stream subscriber;
    public TurboEaSignals(URI base,Supplier<String> token,ObjectMapper json,Duration timeout) {
        TurboEaClient.validateBaseUrl(base); this.base=base; this.token=token; this.json=json;
        http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public int listen(Consumer<String> invalidate,Duration window) {
        if (window.toMillis()<1 || window.compareTo(Duration.ofSeconds(60))>0) throw new IllegalArgumentException("SSE window must be bounded");
        String secret=token.get();
        if (secret==null || !secret.matches("[A-Za-z0-9._~+/-]{1,16384}={0,2}")) throw error(CONFIGURATION,0);
        var stream=new Stream(invalidate); subscriber=stream;
        var request=HttpRequest.newBuilder(base.resolve("/api/v1/events/stream")).header("Accept","text/event-stream")
                .header("Cookie","access_token="+secret).GET().build();
        var connected=new java.util.concurrent.atomic.AtomicBoolean();
        var future=http.sendAsync(request,info -> {
            int status=info.statusCode();
            if (status!=200) throw new ConnectorException(switch(status) { case 401 -> AUTHENTICATION; case 403 -> AUTHORIZATION; case 429 -> RATE_LIMIT; default -> status>=500 ? TRANSIENT : INVALID_RESPONSE; },status,TurboEaClient.retryAfter(info.headers().firstValue("Retry-After").orElse("")));
            if (!info.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim().equalsIgnoreCase("text/event-stream")) throw error(INVALID_RESPONSE,status);
            connected.set(true); return stream;
        }); pending=future;
        try { future.get(window.toMillis(),TimeUnit.MILLISECONDS); return stream.signals; }
        catch (TimeoutException normalWindowEnd) { if (!connected.get()) throw error(TRANSIENT,0); return stream.signals; }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw error(TRANSIENT,0); }
        catch (CancellationException stopped) { throw error(TRANSIENT,0); }
        catch (ExecutionException failed) {
            for (Throwable cause=failed;cause!=null;cause=cause.getCause()) if (cause instanceof ConnectorException safe) throw safe;
            throw error(TRANSIENT,0);
        } finally { stream.cancel(); future.cancel(true); pending=null; subscriber=null; }
    }
    private ConnectorException error(ConnectorException.Kind kind,int status) { return new ConnectorException(kind,status,Duration.ZERO); }
    private final class Stream implements HttpResponse.BodySubscriber<Void> {
        final CompletableFuture<Void> body=new CompletableFuture<>();
        final ByteArrayOutputStream line=new ByteArrayOutputStream(),event=new ByteArrayOutputStream();
        final Consumer<String> invalidate;
        volatile Flow.Subscription subscription;
        int signals,events,total;
        Stream(Consumer<String> invalidate) { this.invalidate=invalidate; }
        @Override public CompletionStage<Void> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            try {
                for (var chunk:chunks) while (chunk.hasRemaining()) {
                    int value=Byte.toUnsignedInt(chunk.get());
                    if (++total>8*1024*1024 || line.size()>65536) throw error(INVALID_RESPONSE,0);
                    if (value=='\n') { consumeLine(); line.reset(); } else line.write(value);
                }
                if (events>=1000) { cancel(); body.complete(null); } else subscription.request(1);
            } catch (RuntimeException malformed) { cancel(); body.completeExceptionally(error(INVALID_RESPONSE,0)); }
        }
        void consumeLine() {
            byte[] bytes=line.toByteArray(); int size=bytes.length;
            if (size>0 && bytes[size-1]=='\r') size--;
            if (size==0) {
                if (event.size()>0) {
                    events++;
                    try {
                        var message=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(event.toByteArray());
                        var id=message.path("card_id");
                        if (id.isTextual() && !id.asText().isBlank() && id.asText().length()<=512 && id.asText().chars().noneMatch(Character::isISOControl)) { invalidate.accept(id.asText()); signals++; }
                    } catch (java.io.IOException malformed) { throw error(INVALID_RESPONSE,0); }
                    event.reset();
                }
            } else if (size>=5 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("data:")) {
                int offset=size>5 && bytes[5]==' ' ? 6 : 5;
                if (event.size()+size-offset+1>65536) throw error(INVALID_RESPONSE,0);
                event.write(bytes,offset,size-offset); event.write('\n');
            }
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(error(TRANSIENT,0)); }
        @Override public void onComplete() { body.complete(null); }
        void cancel() { if (subscription!=null) subscription.cancel(); }
    }
    @Override public void close() { var active=subscriber; if (active!=null) active.cancel(); var call=pending; if (call!=null) call.cancel(true); http.close(); }
}
