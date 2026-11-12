package org.gautelis.archimesh.plugin.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;
import org.gautelis.archimesh.plugin.util.SimpleJson;

/** Talks only to ArchiMesh. No external repository credential or external HTTP URL is accepted. */
public final class EnterpriseRepositoryClient implements AutoCloseable {
    public record Target(String key,List<String> types,boolean canImport) { }
    public record Card(String id,String name,String type,String elementId) { }
    public record Page(List<Card> items,long total,int page,int pageSize) { }
    public record Existing(String id,String name) { }
    public record Preview(String cardId,String type,String name,String description,String digest,String elementId,
                          List<Existing> compatible,String detail) { }
    public record Result(String elementId,String canonicalId,long acceptedRevision) { }
    public record Request(String requestId,String target,String cardId,String mode,String elementId,String digest) { }
    public static final class RequestFailure extends IOException {
        private final int status;
        public RequestFailure(int status,String message) { super(message); this.status=status; }
        public int status() { return status; }
    }
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI origin;
    private final String path;
    private final String token;
    private final String user;
    public EnterpriseRepositoryClient(String wsBaseUrl,String modelId,String token,String user) throws IOException { this(wsBaseUrl,modelId,token,user,"external-objects"); }
    EnterpriseRepositoryClient(String wsBaseUrl,String modelId,String token,String user,String scope) throws IOException {
        if (!Set.of("external-objects","central-edits").contains(scope)) throw new IOException("Unsupported ArchiMesh API scope");
        URI ws=ModelCatalogClient.normalizeUri(wsBaseUrl);
        if (ws.getUserInfo()!=null || ws.getRawQuery()!=null || ws.getRawFragment()!=null) throw new IOException("Use an ArchiMesh server origin without credentials or query parameters");
        try { origin=new URI("wss".equalsIgnoreCase(ws.getScheme()) ? "https" : "http",null,ws.getHost(),ws.getPort(),null,null,null); }
        catch (java.net.URISyntaxException invalid) { throw new IOException("Invalid ArchiMesh origin"); }
        path="/models/"+encode(modelId)+"/"+scope;
        if (token!=null && (token.length()>16384 || token.trim().chars().anyMatch(c -> c<=32 || c>=127)))
            throw new IOException("Invalid ArchiMesh authentication token");
        if (user!=null && (user.length()>512 || user.chars().anyMatch(Character::isISOControl)))
            throw new IOException("Invalid ArchiMesh user identity");
        this.token=token; this.user=user;
    }
    public List<Target> targets() throws IOException,InterruptedException {
        var result=new ArrayList<Target>();
        for (String item:SimpleJson.splitArrayObjects(send("GET","/targets",null))) {
            var types=new ArrayList<String>();
            String raw=SimpleJson.readRawField(item,"types");
            var match=Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(raw == null ? "[]" : raw);
            while (match.find()) types.add(SimpleJson.decodeString(match.group(1)));
            result.add(new Target(required(item,"key"),List.copyOf(types),Boolean.TRUE.equals(SimpleJson.readBooleanField(item,"canImport"))));
        }
        return List.copyOf(result);
    }
    public Page search(String target,String type,String query,int page) throws IOException,InterruptedException {
        String response=send("GET","/catalog?target="+encode(target)+"&type="+encode(type)+"&query="+encode(query)+"&page="+page+"&pageSize=25",null);
        var cards=new ArrayList<Card>();
        for (String row:SimpleJson.readArrayObjectElements(response,"items"))
            cards.add(new Card(required(row,"id"),required(row,"name"),required(row,"type"),SimpleJson.readStringField(row,"elementId")));
        Long total=SimpleJson.readLongField(response,"total");
        if (total == null || total<0) throw new IOException("Invalid ArchiMesh catalog response");
        return new Page(List.copyOf(cards),total,page,25);
    }
    public Preview preview(String target,String cardId) throws IOException,InterruptedException {
        String response=send("GET","/preview?target="+encode(target)+"&cardId="+encode(cardId)+"&ref=HEAD",null);
        var existing=new ArrayList<Existing>();
        for (String row:SimpleJson.readArrayObjectElements(response,"compatibleElements"))
            existing.add(new Existing(required(row,"id"),SimpleJson.readStringField(row,"name")));
        StringBuilder detail=new StringBuilder("ArchiMate type: ").append(required(response,"archimateType"))
                .append("\nSource: ").append(target).append(" / ").append(cardId).append("\n\nFields imported when creating:\n");
        if (SimpleJson.readStringField(response,"bindingState")!=null)
            detail.append("Existing binding: ").append(SimpleJson.readStringField(response,"bindingState")).append("\n");
        for (String field:SimpleJson.readArrayObjectElements(response,"fields"))
            detail.append(required(field,"field")).append(": ").append(required(field,"value")).append(" [")
                    .append(required(field,"authority")).append("]\n");
        detail.append("\nLinking preserves the existing element's name and documentation.\nDiagram placement remains a separate Archi action.");
        return new Preview(cardId,required(response,"archimateType"),required(response,"name"),SimpleJson.readStringField(response,"description"),
                required(response,"digest"),SimpleJson.readStringField(response,"elementId"),List.copyOf(existing),detail.toString());
    }
    public Result importObject(Request request) throws IOException,InterruptedException {
        String body="{\"requestId\":"+quote(request.requestId())+",\"targetKey\":"+quote(request.target())
                +",\"cardId\":"+quote(request.cardId())+",\"ref\":\"HEAD\",\"mode\":"+quote(request.mode())
                +",\"elementId\":"+quote(request.elementId())+",\"previewDigest\":"+quote(request.digest())+"}";
        String response=send("POST","/import",body);
        Long revision=SimpleJson.readLongField(response,"acceptedRevision");
        if (revision == null || revision<1) throw new IOException("Invalid ArchiMesh acceptance response; retry the same import");
        return new Result(required(response,"elementId"),required(response,"canonicalId"),revision);
    }
    String send(String method,String suffix,String body) throws IOException,InterruptedException {
        var request=HttpRequest.newBuilder(URI.create(origin+path+suffix)).timeout(Duration.ofSeconds(20)).header("Accept","application/json");
        if (token!=null && !token.isBlank()) request.header("Authorization","Bearer "+token.trim());
        if (user!=null && !user.isBlank()) request.header("X-Archimesh-User",user);
        if (body == null) request.GET();
        else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));
        var pending=http.sendAsync(request.build(),info -> new BoundedBody());
        HttpResponse<byte[]> response;
        try { response=pending.get(20,TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { pending.cancel(true); throw interrupted; }
        catch (ExecutionException | TimeoutException failed) { pending.cancel(true); throw new IOException("ArchiMesh request did not complete. Retry to check the same request."); }
        String text=new String(response.body(),StandardCharsets.UTF_8);
        if (response.statusCode()!=200) {
            String code=SimpleJson.readStringField(text,"code");
            if (code == null || !code.matches("[A-Z_]{1,80}")) code="HTTP_"+response.statusCode();
            throw new RequestFailure(response.statusCode(),"Enterprise repository request: "+code);
        }
        if (!response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) throw new IOException("Invalid ArchiMesh response type");
        return text;
    }
    static String required(String json,String field) throws IOException {
        String value=SimpleJson.readStringField(json,field);
        if (value == null) throw new IOException("Invalid ArchiMesh response");
        return value;
    }
    static String encode(String text) { return URLEncoder.encode(text == null ? "" : text,StandardCharsets.UTF_8).replace("+","%20"); }
    static String quote(String value) {
        if (value == null) return "null";
        StringBuilder result=new StringBuilder("\"");
        for (char c:value.toCharArray()) {
            if (c=='"' || c=='\\') result.append('\\').append(c);
            else if (c<32) result.append(String.format("\\u%04x",(int)c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
    @Override public void close() { http.shutdownNow(); }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> body=new CompletableFuture<>(); final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (var chunk:chunks) {
                if (chunk.remaining()>1_048_576-bytes.size()) { subscription.cancel(); body.completeExceptionally(new IOException("Response too large")); return; }
                byte[] data=new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
