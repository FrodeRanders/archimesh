package org.gautelis.federation.http;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.gautelis.federation.Identity;
import org.gautelis.federation.Identity.Repository;
import static org.gautelis.federation.http.RepositoryFailure.Kind.*;

/** Narrow OSS target port: applicationProperties and initial status only. No dataset/association writes. */
public final class DataHubApplicationWriter implements AutoCloseable {
    public record Application(ObjectNode properties, boolean active) { }
    private final DataHubReader reader;
    private final HttpClient http;
    private final URI base;
    private final Supplier<String> token;
    private final Duration timeout;
    private final int maxBytes;

    public DataHubApplicationWriter(Repository repository, URI base, Supplier<String> token,
                                    Duration timeout, int maxBytes, boolean approvedPlainHttp) {
        reader=new DataHubReader(repository,base,token,timeout,maxBytes,approvedPlainHttp);
        this.base=base; this.token=token; this.timeout=timeout; this.maxBytes=maxBytes;
        http=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private void application(String urn) {
        Identity.text(urn,8192);
        if(!urn.startsWith("urn:li:application:") || urn.length()=="urn:li:application:".length())
            throw new RepositoryFailure(CONFIGURATION);
    }
    private Optional<ObjectNode> aspect(String urn,String name) {
        try { return Optional.of((ObjectNode)reader.aspect(urn,name)); }
        catch(RepositoryFailure missing) { if(missing.kind()!=NOT_FOUND) throw missing; return Optional.empty(); }
    }
    public Optional<Application> read(String urn) {
        application(urn);
        var properties=aspect(urn,"applicationProperties"); var status=aspect(urn,"status");
        if(properties.isEmpty()) {
            // A partial or independently existing entity must never be silently adopted/recreated.
            if(status.isPresent() || aspect(urn,"applicationKey").isPresent()) throw new RepositoryFailure(INCOMPATIBLE);
            return Optional.empty();
        }
        if(properties.get().has("customProperties") && !properties.get().path("customProperties").isObject())
            throw new RepositoryFailure(INVALID_RESPONSE);
        return Optional.of(new Application(properties.get(),status.map(s -> s.path("removed").isBoolean()
            && !s.path("removed").asBoolean()).orElse(false)));
    }
    public void writeProperties(String urn,ObjectNode properties) { application(urn); write(urn,"applicationProperties",properties); }
    public void initializeStatus(String urn) { application(urn); write(urn,"status",RepositoryJson.JSON.createObjectNode().put("removed",false)); }
    private void write(String urn,String aspect,ObjectNode value) {
        var body=RepositoryJson.JSON.createObjectNode();
        var proposal=body.putObject("proposal").put("entityType","application").put("entityUrn",urn)
            .put("changeType","UPSERT").put("aspectName",aspect);
        proposal.putObject("aspect").put("value",value.toString()).put("contentType","application/json");
        body.put("async","false");
        if(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>maxBytes) throw new RepositoryFailure(CONFIGURATION);
        String credential;
        try { credential=token.get(); } catch(RuntimeException invalid) { throw new RepositoryFailure(CONFIGURATION); }
        if(credential==null || credential.isBlank() || credential.chars().anyMatch(c -> c<=32 || c>=127)) throw new RepositoryFailure(CONFIGURATION);
        try {
            var request=HttpRequest.newBuilder(URI.create(base.toString().replaceFirst("/$","")+"/aspects?action=ingestProposal"))
                .timeout(timeout).header("Authorization","Bearer "+credential).header("Content-Type","application/json")
                .header("X-RestLi-Protocol-Version","2.0.0").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                int code=response.statusCode();
                if(code<200 || code>=300) throw new RepositoryFailure(switch(code) {
                    case 401 -> AUTHENTICATION; case 403 -> AUTHORIZATION; case 429 -> RATE_LIMIT;
                    default -> code>=500 ? TRANSIENT : INVALID_RESPONSE;
                });
                if(stream.readNBytes(maxBytes+1).length>maxBytes) throw new RepositoryFailure(INVALID_RESPONSE);
            }
            // A 2xx is submission acknowledgement only. The owner must independently read back.
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RepositoryFailure(TRANSIENT); }
        catch(IOException unavailable) { throw new RepositoryFailure(TRANSIENT); }
    }
    @Override public void close() { reader.close(); http.close(); }
}
