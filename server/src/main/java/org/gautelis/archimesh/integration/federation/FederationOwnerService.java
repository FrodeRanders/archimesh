package org.gautelis.archimesh.integration.federation;

import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import org.gautelis.archimesh.auth.AuthorizationSubject;
import org.gautelis.archimesh.integration.*;
import org.gautelis.archimesh.service.CommitDeliveryService;
import org.gautelis.federation.Identity.*;
import org.gautelis.federation.IdentityRegistry;
import org.gautelis.federation.http.SourceReader;
import static org.gautelis.archimesh.integration.federation.FederationConfiguration.Operation.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** Host verifies source evidence; callers cannot submit bindings, observations or field values. */
@ApplicationScoped
public class FederationOwnerService {
    @Inject FederationRuntime federation;
    @Inject IntegrationRuntime integration;
    @Inject RemoteMutationCoordinator coordination;
    @Inject CommitDeliveryService delivery;
    @Inject ObjectMapper json;
    public record SourceRequest(String sourceKey,String nativeId,String externalType) { }
    public record Reservation(SourceRequest source,String correlation) { }
    private FederationConfiguration.Source scope(AuthorizationSubject subject,SourceRequest request,FederationConfiguration.Operation operation) {
        if (request==null) throw new Failure(400,"SOURCE_REQUEST_REQUIRED");
        org.gautelis.federation.Identity.text(request.nativeId(),8192);
        org.gautelis.federation.Identity.text(request.sourceKey(),128);
        org.gautelis.federation.Identity.text(request.externalType(),128);
        federation.authorize(subject,operation,request.sourceKey(),request.nativeId());
        var source=federation.source(request.sourceKey()); federation.type(source,request.externalType()); return source;
    }
    private Representation representation(FederationConfiguration.Source source,SourceRequest request) { return new Representation(new Resource(source.repository(),ResourceKind.OBJECT,request.nativeId()),request.externalType()); }
    public JsonNode lookup(AuthorizationSubject subject,SourceRequest request) {
        var source=scope(subject,request,READ); var representation=representation(source,request);
        var binding=registry().find(representation.resource());
        if (binding.isPresent() && !binding.get().representation().equals(representation)) throw new Failure(409,"FEDERATION_NATIVE_TYPE_CHANGED");
        return binding.<JsonNode>map(json::valueToTree).orElseGet(() -> json.createObjectNode().put("registered",false));
    }
    public JsonNode observe(AuthorizationSubject subject,SourceRequest request) {
        var source=scope(subject,request,READ); var snapshot=federation.read(source,representation(source,request));
        var result=json.createObjectNode().put("configurationDigest",federation.digest()).put("revision",snapshot.revision()).put("digest",snapshot.digest()).put("lifecycle",snapshot.lifecycle().name());
        result.set("representation",json.valueToTree(snapshot.representation())); var fields=result.putArray("fields");
        snapshot.fields().forEach((field,value) -> { var row=fields.addObject().put("aspect",field.aspect()).put("path",field.path()).put("present",value.present()).put("digest",value.digest()); row.set("value",value.value()); });
        return result;
    }
    public IdentityRegistry.Binding reserve(AuthorizationSubject subject,Reservation request) {
        if (request==null || request.source()==null || request.correlation()==null || !UUID.fromString(request.correlation()).toString().equalsIgnoreCase(request.correlation())) throw new Failure(400,"SOURCE_CORRELATION_REQUIRED");
        var source=scope(subject,request.source(),REGISTER_SOURCE);
        var representation=representation(source,request.source()); var snapshot=federation.read(source,representation);
        var type=federation.type(source,request.source().externalType()); var name=snapshot.fields().get(type.fields().get("name"));
        if (snapshot.lifecycle()!=SourceReader.Lifecycle.ACTIVE || !name.present() || !name.value().isTextual() || name.value().asText().isBlank() || name.value().asText().length()>512) throw new Failure(409,"SOURCE_NOT_ACTIVE_OR_NAME_UNAVAILABLE");
        String correlation="federation-source:"+subject.userId()+":"+request.correlation();
        String id="urn:agency:identity:"+IntegrationIdentity.sha256(federation.settings().ownerInstanceId()+"\0"+correlation);
        String evidence=PublicationJson.digest(json.valueToTree(List.of("verified-source-read-v1",federation.digest(),snapshot.digest())));
        if (!coordination.tryLock()) throw new Failure(409,"INTEGRATION_BUSY");
        try {
            delivery.requireWriterLease(); integration.requireRunning(); scope(subject,request.source(),REGISTER_SOURCE);
            return registry().reserveSource(representation,new Canonical(id,type.semanticKind()),new IdentityRegistry.Evidence(correlation,subject.userId(),evidence));
        } finally { coordination.unlock(); }
    }
    private IdentityRegistry registry() { return integration.metadata().identities(federation.settings().ownerInstanceId()); }
}
