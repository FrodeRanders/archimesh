package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import org.gautelis.archimesh.auth.AuthorizationSubject;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.PublicationModel.*;

/** Versioned read-only extensions to the existing model/target administration boundary. */
@ApplicationScoped
public class PublicationProfileAdministration {
    @Inject ArchitecturePublicationService publication;
    @Inject PublicationProfiles profiles;
    @Inject IntegrationRuntime runtime;
    @Inject ObjectMapper json;
    public ObjectNode available(String model,String target,AuthorizationSubject subject) {
        publication.authorize(model,target,subject); var result=json.createObjectNode().put("schemaVersion",1); var items=result.putArray("profiles");
        for(var definition:profiles.available()) { var item=items.addObject(); item.set("pin",json.valueToTree(definition.pin(json))); item.set("definition",json.valueToTree(definition)); }
        return result;
    }
    public ObjectNode read(String model,String target,AuthorizationSubject subject,String id,String version) {
        publication.authorize(model,target,subject);
        var definition=profiles.available().stream().filter(p -> p.profileId().equals(id) && p.version().equals(version)).findFirst().orElseThrow(() -> new Failure(404,"PUBLICATION_PROFILE_NOT_FOUND"));
        var result=json.createObjectNode().put("schemaVersion",1); result.set("pin",json.valueToTree(definition.pin(json))); result.set("definition",json.valueToTree(definition)); return result;
    }
    public ObjectNode validate(String model,String targetKey,AuthorizationSubject subject,PublicationProfile.Pin pin) {
        var target=publication.authorize(model,targetKey,subject); var definition=profiles.resolve(requiredPin(pin));
        runtime.validateTarget(targetKey); var meta=runtime.connector(targetKey).metamodel();
        var result=json.createObjectNode().put("schemaVersion",1); result.set("profile",json.valueToTree(pin)); var diagnostics=result.putArray("diagnostics");
        for(var selector:definition.elements()) {
            var type=meta.cardTypes().get(selector.mappingRef());
            if(type==null || type.hidden() || !target.publicationAccess().cardTypes().contains(selector.mappingRef()) || !selector.archimateType().equals(target.mapping().cardTypes().get(selector.mappingRef()))) diagnostic(diagnostics,selector.mappingRef(),"PROFILE_MAPPING_OR_TARGET_DENIED");
            for(var field:selector.fields()) {
                boolean owned=target.mapping().fieldsFor(selector.mappingRef(),null).values().stream().anyMatch(f -> f.remoteField().equals(field) && f.authority()==InformationAuthorityPolicy.Authority.ARCHIMESH_MASTER);
                if(!owned || !target.publicationAccess().fields().contains(field)) diagnostic(diagnostics,selector.mappingRef(),"PROFILE_FIELD_OUTSIDE_EFFECTIVE_AUTHORITY");
            }
        }
        for(var selector:definition.relationships()) {
            var mapping=target.mapping().relationTypes().get(selector.mappingRef()); var type=meta.relationTypes().get(selector.mappingRef());
            if(mapping==null || type==null || type.hidden() || !target.publicationAccess().relationTypes().contains(selector.mappingRef()) || !mapping.archimateType().equals(selector.archimateType())) diagnostic(diagnostics,selector.mappingRef(),"PROFILE_RELATION_TARGET_DENIED");
        }
        if(definition.derivationsEnabled()) for(var rule:definition.derivations()) {
            var type=meta.relationTypes().get(rule.targetMappingRef()); var mapping=target.mapping().relationTypes().get(rule.targetMappingRef());
            boolean valid=type!=null && !type.hidden() && mapping!=null && mapping.archimateType().equals("AccessRelationship")
                && type.sourceType().equals(mapping.sourceType()) && type.targetType().equals(mapping.targetType()) && type.cardinality().equals(mapping.cardinality())
                && target.publicationAccess().relationTypes().contains(rule.targetMappingRef()) && target.publicationAccess().fields().contains(rule.accessAttribute())
                && target.publicationAccess().completeAttributeReadsApproved() && target.publicationAccess().acceptUnconditionalWriteRisk();
            JsonNode attribute=null;
            if(type!=null && type.attributesSchema()!=null && type.attributesSchema().isArray()) for(var field:type.attributesSchema()) if(field.path("key").asText().equals(rule.accessAttribute().substring(11))) {
                if(attribute!=null) valid=false; attribute=field;
            }
            var options=new HashSet<String>();
            if(attribute!=null) for(var option:attribute.path("options")) options.add(option.isTextual() ? option.asText() : option.path("key").asText());
            valid=valid && attribute!=null && attribute.path("type").asText().equals("multiselect") && !attribute.path("hidden").asBoolean() && !attribute.path("is_hidden").asBoolean() && options.containsAll(rule.accessValues().values());
            if(!valid) diagnostic(diagnostics,rule.ruleId(),"DERIVED_TARGET_CAPABILITY_NOT_APPROVED");
        }
        result.put("valid",diagnostics.isEmpty()); return result;
    }
    public ObjectNode explain(String model,String targetKey,AuthorizationSubject subject,PolicySpec spec) {
        var target=publication.authorize(model,targetKey,subject); var preview=publication.preview(model,targetKey,subject,spec);
        var result=json.createObjectNode().put("schemaVersion",1).put("remoteReadsPerformed",false); result.set("preview",json.valueToTree(preview));
        var resources=result.putArray("resources"); var selected=new HashMap<String,Resource>(); int direct=0,derived=0,creations=0,reuses=0;
        for(var resource:preview.plan().resources()) {
            var canonical=resource.identity().canonical().canonicalId(); selected.put(canonical,resource);
            var kind=resource.identity().model().entityKind()==IntegrationIdentity.EntityKind.ELEMENT ? IntegrationIdentity.ResourceKind.CARD : IntegrationIdentity.ResourceKind.RELATION;
            var bound=runtime.metadata().findExternal(canonical,"turboea",target.instanceId(),kind);
            boolean assertion=PublicationClaims.derived(resource); if(assertion) derived++; else direct++;
            if(bound.isPresent()) reuses++; else creations++;
            var item=resources.addObject().put("entityId",resource.identity().model().entityId()).put("canonicalId",canonical).put("kind",assertion ? "DERIVED_ASSERTION" : "DIRECT")
                .put("operation",bound.isEmpty() ? "CREATE_REQUIRES_REMOTE_IDENTITY_CHECK" : resource.provenance()!=null && resource.provenance().referenceOnly() ? "REFERENCE" : "VERIFY_OR_UPDATE_OWNED_FIELDS");
            bound.ifPresent(b -> item.put("resourceId",b.resourceId()));
            var state=runtime.metadata().load(instance(),DocumentKind.PUBLICATION_RESOURCE,PublicationJson.key("pub-resource:",target.instanceId(),canonical));
            state.ifPresent(d -> { item.put("state",d.data().path("state").asText()); if(d.data().has("code")) item.set("code",d.data().path("code")); });
            if(resource.provenance()!=null) item.set("provenance",json.valueToTree(resource.provenance()));
            // No foreign source model IDs, evidence paths or metadata are disclosed here.
        }
        var withdrawals=result.putArray("withdrawals"); var contributions=result.putArray("contributions");
        var scope=runtime.metadata().load(instance(),DocumentKind.ASSERTION_SCOPE,PublicationJson.key("assertion-scope:",model,target.instanceId()));
        if(scope.isPresent()) for(String field:List.of("claims","withdrawnClaims")) for(var id:scope.get().data().path(field)) {
            var claim=runtime.metadata().load(instance(),DocumentKind.ASSERTION_CLAIM,id.asText()).orElseThrow(() -> new Failure(409,"ASSERTION_CLAIM_MISSING"));
            if(!claim.data().path("sourceModelId").asText().equals(model)) throw new Failure(409,"ASSERTION_SCOPE_CONFLICT");
            var old=json.convertValue(claim.data().path("resource"),Resource.class); String canonical=old.identity().canonical().canonicalId();
            var item=contributions.addObject().put("canonicalId",canonical).put("status",claim.data().path("status").asText()); item.set("profile",claim.data().path("profile")); item.set("evidence",json.valueToTree(old.provenance().evidence()));
            if(!selected.containsKey(canonical) && !claim.data().path("status").asText().equals("WITHDRAWN")) withdrawals.addObject().put("canonicalId",canonical).put("action","WITHDRAW_OWN_CONTRIBUTION").put("remoteRetirement","SEPARATE_REVIEW_REQUIRED");
            if(selected.containsKey(canonical)) {
                var next=selected.get(canonical); var removed=item.putArray("removedEvidencePaths");
                if(PublicationClaims.derived(next)) for(var path:old.provenance().evidence().paths()) if(!next.provenance().evidence().paths().contains(path)) removed.add(json.valueToTree(path));
            }
        }
        for(var previousResource:publication.status(model,targetKey,subject).resources()) if(!selected.containsKey(previousResource.canonicalId()) && !previousResource.entityId().startsWith("rel:derived:"))
            withdrawals.addObject().put("canonicalId",previousResource.canonicalId()).put("action","WITHDRAW_OWN_DIRECT_CONTRIBUTION").put("remoteRetirement","RETAIN_REMOTE_RESOURCE");
        result.putObject("counts").put("direct",direct).put("derived",derived).put("existingBindings",reuses).put("proposedCreations",creations).put("ownContributionWithdrawals",withdrawals.size());
        var previous=publication.policy(model,targetKey,subject); result.set("previousProfile",json.valueToTree(previous.policy().profile())); result.set("proposedProfile",json.valueToTree(spec.profile()));
        result.put("requiresApproval",true).put("sharedContributionPolicy","UNION_ONLY_CURRENT_APPROVED_COMPATIBLE_CLAIMS"); return result;
    }
    private PublicationProfile.Pin requiredPin(PublicationProfile.Pin pin) { if(pin==null) throw new Failure(400,"PUBLICATION_PROFILE_PIN_REQUIRED"); return pin; }
    private void diagnostic(ArrayNode list,String scope,String code) { list.addObject().put("severity","CONFLICT").put("entityId",scope).put("code",code); }
    private String instance() { return runtime.settings().instanceId(); }
}
