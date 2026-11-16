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
import org.gautelis.federation.http.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.DocumentKind;
import static org.gautelis.federation.TargetMutationLedger.*;

/** Turbo EA is source authority; only reviewed Application representations may be written in DataHub. */
@ApplicationScoped
public class ApplicationProjectionService {
    public static final String LIFECYCLE="federation.turboea.lifecycle";
    @Inject FederationRuntime federation;
    @Inject IntegrationRuntime integration;
    @Inject CommitDeliveryService delivery;
    @Inject RemoteMutationCoordinator coordination;
    @Inject ObjectMapper json;
    public record Request(String profile,String nativeId,String correlation) { }
    public record Execution(Request source,String intentId,long version) { }
    public record Plan(String intentId,long version,String state,String canonicalId,String targetId) { }
    private record Observation(FederationConfiguration.ApplicationProjection profile,FederationConfiguration.Source source,
                               FederationConfiguration.DataHubTarget target,SourceReader.Snapshot snapshot,ObjectNode owned) { }
    private String owner() { return integration.settings().instanceId(); }
    private IdentityRegistry registry() { return integration.metadata().identities(owner()); }
    private TargetMutationLedger ledger() { return integration.metadata().targetMutations(owner()); }
    private Optional<IntegrationStateRepository.StateDocument> previous(Canonical canonical,Observation o) {
        return integration.metadata().load(owner(),DocumentKind.FEDERATION_LANE,laneId(canonical.id(),o.target().repository(),ResourceKind.OBJECT))
            .filter(l -> l.data().hasNonNull("lastVerifiedIntent"))
            .flatMap(l -> integration.metadata().load(owner(),DocumentKind.FEDERATION_INTENT,l.data().path("lastVerifiedIntent").asText()));
    }
    private Observation observe(AuthorizationSubject subject,Request request) {
        if(request==null || request.correlation()==null || !UUID.fromString(request.correlation()).toString().equalsIgnoreCase(request.correlation()))
            throw new Failure(400,"APPLICATION_REQUEST_REQUIRED");
        Identity.text(request.nativeId(),8192);
        var profile=federation.settings().applicationProjections().stream().filter(p -> p.key().equals(request.profile()))
            .findFirst().orElseThrow(() -> new Failure(403,"APPLICATION_PROFILE_DENIED"));
        if(subject==null || !subject.roles().contains("federation_bridge") || !profile.principals().contains(subject.userId())
                || !profile.approved() || !profile.acceptUnconditionalWriteRisk() || !profile.nativeIds().contains(request.nativeId()))
            throw new Failure(403,"APPLICATION_SCOPE_DENIED");
        var source=federation.source(profile.sourceKey()); var type=federation.type(source,profile.externalType());
        var representation=new Representation(new Resource(source.repository(),ResourceKind.OBJECT,request.nativeId()),profile.externalType());
        var snapshot=federation.read(source,representation);
        if(snapshot.lifecycle()!=SourceReader.Lifecycle.ACTIVE) throw new Failure(409,"APPLICATION_SOURCE_NOT_ACTIVE");
        var sourceTarget=integration.settings().targets().stream().filter(t -> t.key().equals(source.turboTargetKey())).findFirst().orElseThrow();
        var known=registry().find(representation.resource());
        var fields=sourceTarget.mapping().fieldsFor(profile.externalType(),known.map(b -> b.canonical().id()).orElse(null));
        for(String field:List.of("name","documentation")) {
            var rule=fields.get(field);
            if(rule==null || rule.authority()!=InformationAuthorityPolicy.Authority.TURBO_EA_MASTER
                    || !rule.remoteField().equals(field.equals("name") ? "name" : "description"))
                throw new Failure(409,"APPLICATION_SOURCE_AUTHORITY_MISMATCH");
        }
        var owned=json.createObjectNode();
        for(String field:List.of("name","documentation")) {
            var value=snapshot.fields().get(type.fields().get(field));
            if(value==null || !value.present() || value.value().isNull()) {
                if(field.equals("name")) throw new Failure(409,"APPLICATION_SOURCE_NAME_REQUIRED");
                owned.put("description",""); continue;
            }
            if(!value.value().isTextual()) throw new Failure(409,"APPLICATION_SOURCE_FIELD_TYPE");
            owned.put(field.equals("name") ? "name" : "description",value.value().asText());
        }
        if(!type.fields().get("name").path().equals("/name") || !type.fields().get("documentation").path().equals("/description"))
            throw new Failure(409,"APPLICATION_SOURCE_MAPPING_MISMATCH");
        if(profile.lifecycleField()!=null) {
            var value=snapshot.fields().get(type.fields().get(profile.lifecycleField()));
            if(value==null || !value.present()) throw new Failure(409,"APPLICATION_SOURCE_LIFECYCLE_UNAVAILABLE");
            owned.put(LIFECYCLE,PublicationJson.canonical(value.value()).toString());
        }
        if(owned.path("name").asText().isBlank() || owned.path("name").asText().length()>512
                || owned.path("description").asText().length()>16384 || owned.path(LIFECYCLE).asText().length()>8192
                || owned.toString().contains("\\u0000")) throw new Failure(409,"APPLICATION_SOURCE_FIELD_BOUNDS");
        var target=federation.settings().datahubTargets().stream().filter(t -> t.key().equals(profile.targetKey())).findFirst().orElseThrow();
        return new Observation(profile,source,target,snapshot,owned);
    }
    private String approval(Observation o) {
        var profile=(ObjectNode)json.valueToTree(o.profile());
        profile.set("principals",json.valueToTree(new TreeSet<>(o.profile().principals())));
        profile.set("nativeIds",json.valueToTree(new TreeSet<>(o.profile().nativeIds())));
        var sourceTarget=integration.settings().targets().stream().filter(t -> t.key().equals(o.source().turboTargetKey())).findFirst().orElseThrow();
        return PublicationJson.digest(json.valueToTree(List.of("turboea-native-application-v1",profile,o.source(),o.target(),
            IntegrationConfigurationDigest.target(sourceTarget))));
    }
    private String requestId(Observation o,Canonical canonical) {
        var previous=previous(canonical,o); String predecessor="new";
        if(previous.isPresent()) {
            var request=previous.get().data().path("request"); predecessor=request.path("id").asText();
            if(request.path("sourceDigest").asText().equals(o.snapshot().digest()) && request.path("approvalDigest").asText().equals(approval(o))) return predecessor;
        }
        return "application-projection:"+IntegrationIdentity.sha256(canonical.id()+"\0"+approval(o)+"\0"+o.snapshot().digest()+"\0"+predecessor);
    }
    private ObjectNode owned(DataHubApplicationWriter.Application application,Observation o) {
        var props=application.properties(); var result=json.createObjectNode();
        result.set("name",props.path("name")); result.set("description",props.path("description").isMissingNode() ? json.getNodeFactory().textNode("") : props.path("description"));
        if(o.profile().lifecycleField()!=null) result.set(LIFECYCLE,props.path("customProperties").path(LIFECYCLE));
        return result;
    }
    private void matching(DataHubApplicationWriter.Application application,Observation o,String digest) {
        if(!application.active() || !PublicationJson.digest(owned(application,o)).equals(digest)) throw new Failure(409,"APPLICATION_TARGET_DRIFT_OR_REMOVED");
    }
    private DataHubApplicationWriter.Application required(Observation o,String urn) {
        return federation.applicationWriter(o.target().key()).read(urn).orElseThrow(() -> new Failure(409,"APPLICATION_TARGET_MISSING"));
    }
    private String targetId(Observation o,Canonical canonical) {
        var bound=integration.metadata().findExternal(canonical.id(),"datahub",o.target().repository().instance(),IntegrationIdentity.ResourceKind.ENTITY);
        String reviewed=o.profile().adoptions().get(o.snapshot().representation().resource().nativeId());
        if(bound.isPresent()) {
            if(reviewed!=null && !reviewed.equals(bound.get().resourceId())) throw new Failure(409,"APPLICATION_ADOPTION_IDENTITY_CONFLICT");
            return bound.get().resourceId();
        }
        return reviewed!=null ? reviewed : "urn:li:application:turboea-"+IntegrationIdentity.sha256(owner()+"\0"+canonical.id()+"\0"+o.target().repository().instance());
    }
    private ObjectNode payload(Observation o,Optional<DataHubApplicationWriter.Application> current) {
        var properties=current.map(a -> a.properties().deepCopy()).orElseGet(json::createObjectNode);
        properties.put("name",o.owned().path("name").asText()).put("description",o.owned().path("description").asText());
        if(o.profile().lifecycleField()!=null) {
            var custom=properties.has("customProperties") ? (ObjectNode)properties.get("customProperties") : properties.putObject("customProperties");
            custom.put(LIFECYCLE,o.owned().path(LIFECYCLE).asText());
        }
        return properties;
    }
    private Plan plan(Intent intent,String urn) { return new Plan(intent.request().id(),intent.version(),intent.state().name(),intent.request().canonical().id(),urn); }
    public Plan prepare(AuthorizationSubject subject,Request request) {
        if(!coordination.tryLock()) throw new Failure(409,"INTEGRATION_BUSY");
        try {
            delivery.requireWriterLease(); integration.requireRunning(); var o=observe(subject,request);
            var source=o.snapshot().representation(); var known=registry().find(source.resource());
            var binding=known.orElseGet(() -> registry().reserveSource(source,
                new Canonical("urn:agency:identity:"+IntegrationIdentity.sha256(owner()+"\0"+subject.userId()+"\0"+PublicationJson.digest(json.valueToTree(source.resource()))+"\0"+request.correlation()),"enterprise.application"),
                new IdentityRegistry.Evidence("application-source:"+subject.userId()+":"+request.correlation(),subject.userId(),o.snapshot().digest())));
            if(!binding.representation().equals(source) || !binding.canonical().semanticKind().equals("enterprise.application"))
                throw new Failure(409,"APPLICATION_SOURCE_IDENTITY_CONFLICT");
            var canonical=binding.canonical(); String urn=targetId(o,canonical), id=requestId(o,canonical);
            var existing=ledger().find(id);
            if(existing.isPresent()) {
                if(existing.get().state()==State.VERIFIED) matching(required(o,urn),o,PublicationJson.digest(o.owned()));
                return plan(existing.get(),urn);
            }
            var lane=integration.metadata().load(owner(),DocumentKind.FEDERATION_LANE,laneId(canonical.id(),o.target().repository(),ResourceKind.OBJECT));
            if(lane.isPresent() && lane.get().data().has("activeIntent")) {
                var active=integration.metadata().load(owner(),DocumentKind.FEDERATION_INTENT,lane.get().data().path("activeIntent").asText()).orElseThrow();
                var old=json.convertValue(active.data().path("request"),TargetMutationLedger.Request.class);
                if(active.data().path("state").asText().equals("PREPARED") && (!old.sourceDigest().equals(o.snapshot().digest()) || !old.approvalDigest().equals(approval(o))))
                    ledger().cancelPrepared(old.id(),active.version());
            }
            boolean bound=integration.metadata().findExternal(canonical.id(),"datahub",o.target().repository().instance(),IntegrationIdentity.ResourceKind.ENTITY).isPresent();
            var current=federation.applicationWriter(o.target().key()).read(urn); Action action;
            if(bound) {
                var previous=previous(canonical,o).orElseThrow(() -> new Failure(409,"APPLICATION_BASELINE_REQUIRED"));
                matching(current.orElseThrow(() -> new Failure(409,"APPLICATION_TARGET_MISSING")),o,previous.data().path("request").path("expectedReadbackDigest").asText());
                action=Action.UPDATE;
            } else if(current.isPresent()) {
                if(!o.profile().adoptions().containsValue(urn)) throw new Failure(409,"APPLICATION_REVIEWED_ADOPTION_REQUIRED");
                matching(current.get(),o,PublicationJson.digest(o.owned())); action=Action.ADOPT;
            } else {
                if(o.profile().adoptions().containsValue(urn)) throw new Failure(409,"APPLICATION_ADOPTION_TARGET_MISSING");
                action=Action.CREATE;
            }
            // Only owned values are pinned. Unowned aspect members are freshly merged at dispatch.
            return plan(ledger().reserve(new TargetMutationLedger.Request(id,canonical,new Slot(o.target().repository(),ResourceKind.OBJECT,"application"),
                action,action==Action.CREATE ? null : urn,PublicationJson.digest(o.owned()),PublicationJson.digest(o.owned()),
                o.snapshot().digest(),approval(o),subject.userId())),urn);
        } finally { coordination.unlock(); }
    }
    public Plan execute(AuthorizationSubject subject,Execution request) {
        if(request==null || request.source()==null) throw new Failure(400,"APPLICATION_EXECUTION_REQUIRED");
        if(!coordination.tryLock()) throw new Failure(409,"INTEGRATION_BUSY");
        try {
            delivery.requireWriterLease(); integration.requireRunning(); var o=observe(subject,request.source());
            var intent=ledger().find(request.intentId()).orElseThrow(() -> new Failure(404,"APPLICATION_INTENT_NOT_FOUND"));
            var canonical=registry().find(o.snapshot().representation().resource()).orElseThrow().canonical();
            String urn=targetId(o,canonical);
            if(!intent.request().canonical().equals(canonical) || !requestId(o,canonical).equals(request.intentId())
                    || !intent.request().actor().equals(subject.userId())) throw new Failure(409,"APPLICATION_REVIEW_STALE");
            if(intent.state()==State.VERIFIED) { matching(required(o,urn),o,intent.request().expectedReadbackDigest()); return plan(intent,urn); }
            if(intent.state()!=State.PREPARED) throw new Failure(409,"APPLICATION_OUTCOME_UNCERTAIN_OR_QUARANTINED");
            if(intent.version()!=request.version()) throw new Failure(409,"APPLICATION_INTENT_STALE");
            var writer=federation.applicationWriter(o.target().key()); var current=writer.read(urn);
            if(intent.request().action()==Action.ADOPT) {
                matching(current.orElseThrow(() -> new Failure(409,"APPLICATION_TARGET_MISSING")),o,intent.request().expectedReadbackDigest());
                return plan(ledger().verify(intent.request().id(),intent.version(),null,
                    new Representation(new Resource(o.target().repository(),ResourceKind.OBJECT,urn),"application"),evidence(subject,intent)),urn);
            }
            if(intent.request().action()==Action.CREATE) {
                if(current.isPresent()) throw new Failure(409,"APPLICATION_TARGET_APPEARED_REQUIRES_REVIEW");
            } else matching(current.orElseThrow(() -> new Failure(409,"APPLICATION_TARGET_MISSING")),o,
                previous(canonical,o).orElseThrow().data().path("request").path("expectedReadbackDigest").asText());
            if(!intent.request().payloadDigest().equals(PublicationJson.digest(o.owned()))) throw new Failure(409,"APPLICATION_PAYLOAD_CHANGED");
            var properties=payload(o,current);
            intent=ledger().begin(intent.request().id(),intent.version(),o.snapshot().digest(),approval(o));
            try {
                writer.writeProperties(urn,properties);
                if(intent.request().action()==Action.CREATE) writer.initializeStatus(urn);
                var readback=required(o,urn); matching(readback,o,intent.request().expectedReadbackDigest());
                // Verify preserved aspect members as well, without claiming authority over them.
                if(!preserves(properties,readback.properties())) throw new Failure(409,"APPLICATION_PRESERVED_FIELDS_CHANGED");
                return plan(ledger().verify(intent.request().id(),intent.version(),intent.attempt(),
                    new Representation(new Resource(o.target().repository(),ResourceKind.OBJECT,urn),"application"),evidence(subject,intent)),urn);
            } catch(RuntimeException failed) {
                ledger().uncertain(intent.request().id(),intent.version(),intent.attempt());
                throw new Failure(409,"APPLICATION_OUTCOME_UNCERTAIN_REQUIRES_REVIEW");
            }
        } finally { coordination.unlock(); }
    }
    private IdentityRegistry.Evidence evidence(AuthorizationSubject subject,Intent intent) {
        return new IdentityRegistry.Evidence("application-readback:"+IntegrationIdentity.sha256(intent.request().id()),subject.userId(),intent.request().expectedReadbackDigest());
    }
    private boolean preserves(JsonNode expected,JsonNode actual) {
        if(expected.isObject()) {
            if(!actual.isObject()) return false;
            var fields=expected.fields();
            while(fields.hasNext()) { var field=fields.next(); if(!actual.has(field.getKey()) || !preserves(field.getValue(),actual.get(field.getKey()))) return false; }
            return true;
        }
        return PublicationJson.equal(expected,actual);
    }
    /** Explicit reviewed recovery: read only; never fills a missing aspect or retries a submission. */
    public Plan verifyOutcome(AuthorizationSubject subject,Execution request) {
        if(request==null || request.source()==null) throw new Failure(400,"APPLICATION_EXECUTION_REQUIRED");
        if(!coordination.tryLock()) throw new Failure(409,"INTEGRATION_BUSY");
        try {
            delivery.requireWriterLease(); integration.requireRunning(); var o=observe(subject,request.source());
            var intent=ledger().find(request.intentId()).orElseThrow(() -> new Failure(404,"APPLICATION_INTENT_NOT_FOUND"));
            var canonical=registry().find(o.snapshot().representation().resource()).orElseThrow().canonical();
            if(!intent.request().canonical().equals(canonical) || !requestId(o,canonical).equals(intent.request().id())
                    || !intent.request().actor().equals(subject.userId()) || intent.version()!=request.version()
                    || !(intent.state()==State.UNCERTAIN || intent.state()==State.IN_FLIGHT))
                throw new Failure(409,"APPLICATION_RECOVERY_REVIEW_STALE");
            String urn=targetId(o,canonical); matching(required(o,urn),o,intent.request().expectedReadbackDigest());
            return plan(ledger().verify(intent.request().id(),intent.version(),intent.attempt(),
                new Representation(new Resource(o.target().repository(),ResourceKind.OBJECT,urn),"application"),evidence(subject,intent)),urn);
        } finally { coordination.unlock(); }
    }
}
