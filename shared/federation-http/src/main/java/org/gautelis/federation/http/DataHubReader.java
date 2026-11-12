package org.gautelis.federation.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.gautelis.federation.Identity.*;
import static org.gautelis.federation.http.RepositoryFailure.Kind.*;

/** Read-only OSS Rest.li aspect adapter. Never searches, writes, follows redirects or guesses identity. */
public final class DataHubReader implements SourceReader,AutoCloseable {
    private final Repository repository;
    private final URI base;
    private final Supplier<String> token;
    private final Duration timeout;
    private final int maxBytes;
    private final HttpClient http;
    public DataHubReader(Repository repository,URI base,Supplier<String> token,Duration timeout,int maxBytes,boolean approvedPlainHttp) {
        this.repository=Objects.requireNonNull(repository); this.base=Objects.requireNonNull(base); this.token=Objects.requireNonNull(token);
        this.timeout=Objects.requireNonNull(timeout); this.maxBytes=maxBytes;
        if (!repository.system().equals("datahub") || base.getHost()==null || base.getRawUserInfo()!=null || base.getRawQuery()!=null
                || base.getRawFragment()!=null || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()) &&
                (approvedPlainHttp || Set.of("localhost","127.0.0.1","[::1]","::1").contains(base.getHost())))
                || timeout.toMillis()<1 || timeout.toMillis()>120000 || maxBytes<1024 || maxBytes>16_777_216)
            throw new RepositoryFailure(CONFIGURATION);
        this.http=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }
    @Override public Snapshot read(Representation representation,Set<Field> fields) {
        if (!representation.resource().repository().equals(repository) || representation.resource().kind()!=ResourceKind.OBJECT
                || !Set.of("dataset","dataProduct","application").contains(representation.externalType())
                || !representation.resource().nativeId().startsWith("urn:li:"+representation.externalType()+":")
                || fields.isEmpty() || fields.size()>32) throw new RepositoryFailure(CONFIGURATION);
        var aspects=new TreeMap<String,JsonNode>();
        String primary=switch(representation.externalType()) { case "dataset" -> "datasetProperties"; case "dataProduct" -> "dataProductProperties"; default -> "applicationProperties"; };
        aspects.put(primary,aspect(representation.resource().nativeId(),primary));
        for (var field:fields) {
            if (!field.aspect().matches("[a-zA-Z][a-zA-Z0-9]{0,127}")) throw new RepositoryFailure(CONFIGURATION);
            if (!aspects.containsKey(field.aspect())) {
                try { aspects.put(field.aspect(),aspect(representation.resource().nativeId(),field.aspect())); }
                catch (RepositoryFailure missing) { if (missing.kind()!=NOT_FOUND) throw missing; aspects.put(field.aspect(),RepositoryJson.JSON.missingNode()); }
            }
        }
        JsonNode status;
        try { status=aspects.containsKey("status") ? aspects.get("status") : aspect(representation.resource().nativeId(),"status"); }
        catch (RepositoryFailure missing) { if (missing.kind()!=NOT_FOUND) throw missing; status=RepositoryJson.JSON.missingNode(); }
        Lifecycle lifecycle=status.path("removed").isBoolean() ? status.path("removed").asBoolean() ? Lifecycle.SOFT_DELETED : Lifecycle.ACTIVE : Lifecycle.UNKNOWN;
        var values=new TreeMap<Field,Value>(); fields.forEach(field -> values.put(field,Value.of(aspects.get(field.aspect()).at(field.path()))));
        return SourceReader.snapshot(representation,lifecycle,values);
    }
    private JsonNode aspect(String urn,String aspect) {
        String encoded=URLEncoder.encode(urn,StandardCharsets.UTF_8).replace("+","%20");
        String path=base.toString().replaceFirst("/$","")+"/aspects/"+encoded+"?aspect="+aspect+"&version=0";
        String credential=token.get();
        if (credential==null || credential.isBlank() || credential.chars().anyMatch(Character::isISOControl)) throw new RepositoryFailure(CONFIGURATION);
        try {
            var request=HttpRequest.newBuilder(URI.create(path)).timeout(timeout).header("Accept","application/json")
                    .header("X-RestLi-Protocol-Version","2.0.0").header("Authorization","Bearer "+credential).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var body=response.body()) {
                int code=response.statusCode();
                if (code!=200) throw new RepositoryFailure(switch(code) { case 401 -> AUTHENTICATION; case 403 -> AUTHORIZATION; case 404 -> NOT_FOUND; case 429 -> RATE_LIMIT; default -> code>=500 ? TRANSIENT : INVALID_RESPONSE; });
                bytes=body.readNBytes(maxBytes+1);
            }
            if (bytes.length>maxBytes) throw new RepositoryFailure(INVALID_RESPONSE);
            JsonNode root;
            try { root=RepositoryJson.JSON.readTree(bytes); } catch (Exception invalid) { throw new RepositoryFailure(INVALID_RESPONSE); }
            var envelope=root.path("aspect");
            for (String idField:List.of("urn","entityUrn")) if (root.has(idField) && !urn.equals(root.path(idField).asText())) throw new RepositoryFailure(INVALID_RESPONSE);
            if (!envelope.isObject()) throw new RepositoryFailure(INVALID_RESPONSE);
            if (envelope.path("value").isTextual()) {
                try { var value=RepositoryJson.JSON.readTree(envelope.path("value").asText()); if (!value.isObject()) throw new RepositoryFailure(INVALID_RESPONSE); return value; }
                catch (IOException invalid) { throw new RepositoryFailure(INVALID_RESPONSE); }
            }
            if (envelope.size()!=1 || !envelope.fieldNames().next().startsWith("com.linkedin.")) throw new RepositoryFailure(INVALID_RESPONSE);
            var value=envelope.elements().next();
            if (!value.isObject()) throw new RepositoryFailure(INVALID_RESPONSE);
            return value;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RepositoryFailure(TRANSIENT); }
        catch (IOException unavailable) { throw new RepositoryFailure(TRANSIENT); }
    }
    @Override public void close() { http.close(); }
}
