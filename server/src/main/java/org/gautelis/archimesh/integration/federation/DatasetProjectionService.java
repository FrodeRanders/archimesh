package org.gautelis.archimesh.integration.federation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import org.gautelis.archimesh.auth.AuthorizationSubject;
import org.gautelis.archimesh.integration.*;
import org.gautelis.archimesh.service.CommitDeliveryService;
import org.gautelis.federation.*;
import org.gautelis.federation.Identity.*;
import org.gautelis.federation.http.SourceReader;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.DocumentKind;
import static org.gautelis.federation.TargetMutationLedger.*;

/** Registry-owner target capability. Scheduling/discovery belong to the independent bridge. */
@ApplicationScoped
public class DatasetProjectionService {
    @Inject FederationRuntime federation;
    @Inject IntegrationRuntime integration;
    @Inject CommitDeliveryService delivery;
    @Inject RemoteMutationCoordinator coordination;
    @Inject ObjectMapper json;

    public record Request(String profile,String nativeId,String correlation) { }
    public record Execution(Request source,String intentId,long version) { }
    public record Plan(String intentId,long version,String state,String canonicalId,String targetId) { }
    private record Observation(FederationConfiguration.Projection profile,FederationConfiguration.Source source,
                               FederationConfiguration.Type type,SourceReader.Snapshot snapshot,ObjectNode owned) { }
    private String owner() { return integration.settings().instanceId(); }
    private IdentityRegistry registry() { return integration.metadata().identities(owner()); }
    private TargetMutationLedger ledger() { return integration.metadata().targetMutations(owner()); }
    private IntegrationConfiguration.Target target(String key) {
        return integration.settings().targets().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow(() -> new Failure(403,"PROJECTION_TARGET_DENIED"));
    }
    private FederationConfiguration.Projection profile(String key) {
        return federation.settings().projections().stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow(() -> new Failure(403,"PROJECTION_PROFILE_DENIED"));
    }
    private Observation observe(AuthorizationSubject subject,Request request) {
        if(request==null || request.nativeId()==null || request.correlation()==null
                || !UUID.fromString(request.correlation()).toString().equalsIgnoreCase(request.correlation())) throw new Failure(400,"PROJECTION_REQUEST_REQUIRED");
        Identity.text(request.nativeId(),8192);
        var profile=profile(request.profile());
        if(subject==null || !subject.roles().contains("federation_bridge") || !profile.principals().contains(subject.userId())
                || !profile.approved() || !profile.acceptUnconditionalWriteRisk()
                || profile.nativePrefixes().stream().noneMatch(request.nativeId()::startsWith) || !request.nativeId().endsWith(")"))
            throw new Failure(403,"PROJECTION_SCOPE_DENIED");
        return read(profile,request.nativeId());
    }
    private Observation read(FederationConfiguration.Projection profile,String nativeId) {
        var source=federation.source(profile.sourceKey()); var type=federation.type(source,profile.externalType());
        var representation=new Representation(new Resource(source.repository(),ResourceKind.OBJECT,nativeId),profile.externalType());
        var snapshot=federation.read(source,representation);
        if(snapshot.lifecycle()!=SourceReader.Lifecycle.ACTIVE) throw new Failure(409,"PROJECTION_SOURCE_NOT_ACTIVE");
        var name=snapshot.fields().get(type.fields().get("name"));
        if(name==null || !name.present() || !name.value().isTextual() || name.value().asText().isBlank()) throw new Failure(409,"PROJECTION_SOURCE_NAME_UNAVAILABLE");
        var owned=json.createObjectNode().put("name",name.value().asText()); String description="";
        for(String field:profile.descriptionFields()) {
            var value=snapshot.fields().get(type.fields().get(field));
            if(value!=null && value.present() && !value.value().isNull()) {
                if(!value.value().isTextual()) throw new Failure(409,"PROJECTION_SOURCE_FIELD_TYPE");
                description=value.value().asText(); break;
            }
        }
        owned.put("description",description);
        if(owned.path("name").asText().length()>512 || description.length()>16384 || owned.toString().contains("\\u0000")) throw new Failure(409,"PROJECTION_SOURCE_FIELD_BOUNDS");
        var target=target(profile.targetKey());
        if(!target.mapping().archimateType(profile.targetType()).equals(type.archimateType())
                || !target.mapping().fieldsFor(profile.targetType(),null).keySet().containsAll(Set.of("name","documentation"))) throw new Failure(409,"PROJECTION_MAPPING_MISMATCH");
        for(String field:List.of("name","documentation")) {
            var mapped=target.mapping().fieldsFor(profile.targetType(),null).get(field);
            if(mapped.authority()!=InformationAuthorityPolicy.Authority.DATAHUB_MASTER
                    || !mapped.remoteField().equals(field.equals("name") ? "name" : "description")) throw new Failure(409,"PROJECTION_AUTHORITY_MISMATCH");
        }
        return new Observation(profile,source,type,snapshot,owned);
    }
    private String approval(Observation o) {
        var p=o.profile();
        // Sets must hash identically across JVM restarts; description precedence is ordered.
        return PublicationJson.digest(json.valueToTree(List.of(o.source(),p.key(),p.externalType(),p.targetKey(),p.targetType(),
            new TreeSet<>(p.principals()),new TreeSet<>(p.nativePrefixes()),p.descriptionFields(),p.approved(),
            p.acceptUnconditionalWriteRisk(),p.approvalVersion(),IntegrationConfigurationDigest.target(target(p.targetKey())))));
    }
    private String requestId(Observation o,Canonical canonical) {
        var destination=target(o.profile().targetKey());
        var lane=integration.metadata().load(owner(),DocumentKind.FEDERATION_LANE,
            laneId(canonical.id(),new Repository("turboea",destination.instanceId()),ResourceKind.OBJECT));
        var previous=lane.filter(l -> l.data().hasNonNull("lastVerifiedIntent")).flatMap(l -> integration.metadata().load(owner(),DocumentKind.FEDERATION_INTENT,l.data().path("lastVerifiedIntent").asText()));
        String predecessor="new";
        if(previous.isPresent()) {
            var request=previous.get().data().path("request"); predecessor=request.path("id").asText();
            if(request.path("sourceDigest").asText().equals(o.snapshot().digest()) && request.path("approvalDigest").asText().equals(approval(o))) return predecessor;
        }
        return "dataset-projection:"+IntegrationIdentity.sha256(canonical.id()+"\0"+approval(o)+"\0"+o.snapshot().digest()+"\0"+predecessor);
    }
    private ObjectNode owned(ExternalRepositoryConnector.ExternalObject card) { return json.createObjectNode().put("name",card.name()).put("description",card.description()==null ? "" : card.description()); }
    private void matching(ExternalRepositoryConnector.ExternalObject card,Observation o,String expected) {
        if(!card.type().equals(o.profile().targetType()) || !"ACTIVE".equals(card.status())
                || !PublicationJson.digest(owned(card)).equals(expected)) throw new Failure(409,"PROJECTION_TARGET_DRIFT");
    }
    private Optional<IntegrationIdentity.ExternalResourceKey> mirror(Canonical canonical,IntegrationConfiguration.Target target) {
        return integration.metadata().findExternal(canonical.id(),"turboea",target.instanceId(),IntegrationIdentity.ResourceKind.CARD);
    }
    private void authority(Observation observation,String canonical) {
        var mappings=target(observation.profile().targetKey()).mapping().fieldsFor(observation.profile().targetType(),canonical);
        for(String field:List.of("name","documentation"))
            if(mappings.get(field).authority()!=InformationAuthorityPolicy.Authority.DATAHUB_MASTER)
                throw new Failure(409,"PROJECTION_AUTHORITY_MISMATCH");
    }
    private Plan plan(Intent value) { return new Plan(value.request().id(),value.version(),value.state().name(),value.request().canonical().id(),value.verifiedNativeId()); }

    public Plan prepare(AuthorizationSubject subject,Request request) {
        if(!coordination.tryLock()) throw new Failure(409,"INTEGRATION_BUSY");
        try {
            delivery.requireWriterLease(); integration.requireRunning(); var o=observe(subject,request);
            var representation=o.snapshot().representation();
            var known=registry().find(representation.resource());
            var binding=known.orElseGet(() -> registry().reserveSource(representation,
                new Canonical("urn:agency:identity:"+IntegrationIdentity.sha256(owner()+"\0"+subject.userId()+"\0"
                    +PublicationJson.digest(json.valueToTree(representation.resource()))+"\0"+request.correlation()),o.type().semanticKind()),
                new IdentityRegistry.Evidence("projection-source:"+subject.userId()+":"+request.correlation(),subject.userId(),
                    PublicationJson.digest(json.valueToTree(List.of(federation.digest(),o.snapshot().digest()))))));
            if(!binding.representation().equals(representation) || !binding.canonical().semanticKind().equals(o.type().semanticKind())) throw new Failure(409,"PROJECTION_SOURCE_IDENTITY_CONFLICT");
            var canonical=binding.canonical(); authority(o,canonical.id());
            var target=target(o.profile().targetKey()); integration.validateTarget(target.key());
            String id=requestId(o,canonical); var existing=ledger().find(id); var external=mirror(canonical,target);
            if(existing.isPresent()) {
                if(existing.get().state()==State.VERIFIED) matching(integration.connector(target.key()).getObject(existing.get().verifiedNativeId()),o,PublicationJson.digest(o.owned()));
                return plan(existing.get());
            }
            var lane=integration.metadata().load(owner(),DocumentKind.FEDERATION_LANE,laneId(canonical.id(),new Repository("turboea",target.instanceId()),ResourceKind.OBJECT));
            if(lane.isPresent() && lane.get().data().has("activeIntent")) {
                var active=integration.metadata().load(owner(),DocumentKind.FEDERATION_INTENT,lane.get().data().path("activeIntent").asText()).orElseThrow();
                var old=json.convertValue(active.data().path("request"),TargetMutationLedger.Request.class);
                if(active.data().path("state").asText().equals("PREPARED") && (!old.sourceDigest().equals(o.snapshot().digest()) || !old.approvalDigest().equals(approval(o))))
                    ledger().cancelPrepared(old.id(),active.version());
            }
            if(external.isPresent()) {
                var previous=lane.filter(l -> l.data().hasNonNull("lastVerifiedIntent")).flatMap(l -> integration.metadata().load(owner(),DocumentKind.FEDERATION_INTENT,l.data().path("lastVerifiedIntent").asText()));
                if(previous.isEmpty() || !previous.get().data().path("state").asText().equals("VERIFIED")) throw new Failure(409,"PROJECTION_BASELINE_REQUIRED");
                matching(integration.connector(target.key()).getObject(external.get().resourceId()),o,
                    previous.get().data().path("request").path("expectedReadbackDigest").asText());
            }
            var payload=payload(o,canonical,external.isEmpty());
            return plan(ledger().reserve(new TargetMutationLedger.Request(id,canonical,
                new Slot(new Repository("turboea",target.instanceId()),ResourceKind.OBJECT,o.profile().targetType()),external.isEmpty() ? Action.CREATE : Action.UPDATE,
                external.map(IntegrationIdentity.ExternalResourceKey::resourceId).orElse(null),PublicationJson.digest(payload),PublicationJson.digest(o.owned()),
                o.snapshot().digest(),approval(o),subject.userId())));
        } finally { coordination.unlock(); }
    }
    private ObjectNode payload(Observation o,Canonical canonical,boolean create) {
        var result=o.owned().deepCopy();
        if(create) { result.put("type",o.profile().targetType()).put("external_id","federation:"+IntegrationIdentity.sha256(canonical.id())).put("strict_attributes",true); result.putObject("attributes"); }
        return result;
    }
    public Plan execute(AuthorizationSubject subject,Execution request) {
        if(request==null || request.source()==null) throw new Failure(400,"PROJECTION_EXECUTION_REQUIRED");
        if(!coordination.tryLock()) throw new Failure(409,"INTEGRATION_BUSY");
        try {
            delivery.requireWriterLease(); integration.requireRunning(); var o=observe(subject,request.source());
            var intent=ledger().find(request.intentId()).orElseThrow(() -> new Failure(404,"PROJECTION_INTENT_NOT_FOUND"));
            var source=registry().find(o.snapshot().representation().resource()).orElseThrow();
            authority(o,source.canonical().id());
            if(!intent.request().canonical().equals(source.canonical()) || !requestId(o,source.canonical()).equals(request.intentId())
                    || !intent.request().actor().equals(subject.userId())) throw new Failure(409,"PROJECTION_REVIEW_STALE");
            if(intent.state()==State.VERIFIED) { verifyMirror(target(o.profile().targetKey()),integration.connector(o.profile().targetKey()).getObject(intent.verifiedNativeId())); return plan(intent); }
            if(intent.state()!=State.PREPARED) throw new Failure(409,"PROJECTION_OUTCOME_UNCERTAIN_OR_QUARANTINED");
            if(intent.version()!=request.version()) throw new Failure(409,"PROJECTION_INTENT_STALE");
            var target=target(o.profile().targetKey()); integration.validateTarget(target.key());
            // Recheck the last owned-field baseline immediately before dispatch; PATCH never includes unrelated fields.
            if(intent.request().action()==Action.UPDATE) {
                var lane=integration.metadata().load(owner(),DocumentKind.FEDERATION_LANE,laneId(source.canonical().id(),intent.request().target().repository(),ResourceKind.OBJECT)).orElseThrow();
                var previous=integration.metadata().load(owner(),DocumentKind.FEDERATION_INTENT,lane.data().path("lastVerifiedIntent").asText()).orElseThrow();
                matching(integration.connector(target.key()).getObject(intent.request().knownNativeId()),o,previous.data().path("request").path("expectedReadbackDigest").asText());
            }
            var payload=payload(o,source.canonical(),intent.request().action()==Action.CREATE);
            if(!PublicationJson.digest(payload).equals(intent.request().payloadDigest())) throw new Failure(409,"PROJECTION_PAYLOAD_CHANGED");
            intent=ledger().begin(intent.request().id(),intent.version(),o.snapshot().digest(),approval(o));
            try {
                var card=intent.request().action()==Action.CREATE ? integration.projectionWriter(target.key()).createObject(payload)
                    : integration.projectionWriter(target.key()).patchObject(intent.request().knownNativeId(),payload);
                var readback=integration.connector(target.key()).getObject(card.id()); matching(readback,o,intent.request().expectedReadbackDigest());
                var actual=new Representation(new Resource(intent.request().target().repository(),ResourceKind.OBJECT,card.id()),o.profile().targetType());
                return plan(ledger().verify(intent.request().id(),intent.version(),intent.attempt(),actual,
                    new IdentityRegistry.Evidence("projection-readback:"+IntegrationIdentity.sha256(intent.request().id()),subject.userId(),intent.request().expectedReadbackDigest())));
            } catch(RuntimeException failed) {
                ledger().uncertain(intent.request().id(),intent.version(),intent.attempt());
                throw new Failure(409,"PROJECTION_OUTCOME_UNCERTAIN_REQUIRES_REVIEW");
            }
        } finally { coordination.unlock(); }
    }

    /** Verify original-source freshness before disclosing/importing/updating a mirror-owned field. */
    public void verifyMirror(IntegrationConfiguration.Target target,ExternalRepositoryConnector.ExternalObject card) {
        boolean datahub=target.mapping().fieldsFor(card.type(),null).values().stream().anyMatch(f -> f.authority()==InformationAuthorityPolicy.Authority.DATAHUB_MASTER);
        if(!datahub) return;
        var profile=federation.settings().projections().stream().filter(p -> p.approved() && p.targetKey().equals(target.key()) && p.targetType().equals(card.type())).findFirst().orElseThrow(() -> new Failure(409,"PROJECTION_SOURCE_POLICY_REQUIRED"));
        var canonical=integration.metadata().findCanonical(new IntegrationIdentity.ExternalResourceKey("turboea",target.instanceId(),IntegrationIdentity.ResourceKind.CARD,card.id())).orElseThrow(() -> new Failure(409,"PROJECTION_IDENTITY_PROOF_REQUIRED"));
        var source=federation.source(profile.sourceKey());
        var origin=integration.metadata().findExternal(canonical.canonicalId(),"datahub",source.repository().instance(),IntegrationIdentity.ResourceKind.ENTITY).orElseThrow(() -> new Failure(409,"PROJECTION_SOURCE_BINDING_REQUIRED"));
        if(profile.nativePrefixes().stream().noneMatch(origin.resourceId()::startsWith)) throw new Failure(403,"PROJECTION_SOURCE_SCOPE_CHANGED");
        var o=read(profile,origin.resourceId());
        authority(o,canonical.canonicalId());
        if(!"ACTIVE".equals(card.status()) || !PublicationJson.digest(owned(card)).equals(PublicationJson.digest(o.owned())))
            throw new Failure(409,"PROJECTION_SOURCE_REFRESH_REQUIRED");
        var lane=integration.metadata().load(owner(),DocumentKind.FEDERATION_LANE,laneId(canonical.canonicalId(),new Repository("turboea",target.instanceId()),ResourceKind.OBJECT)).orElseThrow(() -> new Failure(409,"PROJECTION_VERIFIED_READBACK_REQUIRED"));
        if(!lane.data().hasNonNull("lastVerifiedIntent")) throw new Failure(409,"PROJECTION_SOURCE_REFRESH_REQUIRED");
        var verified=integration.metadata().load(owner(),DocumentKind.FEDERATION_INTENT,lane.data().path("lastVerifiedIntent").asText()).orElseThrow(() -> new Failure(409,"PROJECTION_SOURCE_REFRESH_REQUIRED"));
        if(!verified.data().path("state").asText().equals("VERIFIED") || !verified.data().path("request").path("expectedReadbackDigest").asText().equals(PublicationJson.digest(o.owned()))
                || !verified.data().path("request").path("approvalDigest").asText().equals(approval(o))
                || !verified.data().path("request").path("sourceDigest").asText().equals(o.snapshot().digest())) throw new Failure(409,"PROJECTION_SOURCE_REFRESH_REQUIRED");
    }
}
