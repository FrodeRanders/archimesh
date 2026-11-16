package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import static org.gautelis.archimesh.integration.PublicationProfile.*;
import static org.gautelis.archimesh.integration.PublicationModel.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;

/** Deterministic bounded profile selection over accepted materialized state; never reserves identities. */
public final class ProfileProjection {
    private final ObjectMapper json;
    public ProfileProjection(ObjectMapper json) { this.json=json; }
    public Plan build(String instance,String model,IntegrationConfiguration.Target target,PolicySpec policy,JsonNode snapshot,
                      IntegrationStateRepository metadata,Definition profile) {
        if(policy.mode()==Mode.DISABLED) return new Plan(model,target.key(),target.instanceId(),policy.ref(),0,target.mapping().version(),List.of(),policy.profile(),List.of());
        if(!profile.enabled() || !profile.sourceModes().contains(policy.mode())) throw new Failure(403,"PROFILE_SOURCE_NOT_ELIGIBLE");
        if(profile.derivationsEnabled() && !profile.derivations().isEmpty()) throw new Failure(409,"PROFILE_DERIVATIONS_NOT_IMPLEMENTED");
        if(policy.elements().size()>profile.limits().maxSelectedElements() || policy.relationships().size()>profile.limits().maxSelectedRelationships()
                || snapshot.path("elements").size()>profile.limits().maxGraphElements() || snapshot.path("relationships").size()>profile.limits().maxGraphRelationships())
            throw new Failure(413,"PROFILE_SCOPE_LIMIT_EXCEEDED");
        var elements=index(snapshot.path("elements")); var relationships=index(snapshot.path("relationships"));
        var diagnostics=new ArrayList<Diagnostic>(); var chosen=new TreeMap<String,ElementSelector>(); var rels=new TreeMap<String,String>();
        for(String id:policy.elements()) {
            var element=elements.get(id);
            if(element==null) { diagnostics.add(new Diagnostic(Severity.SKIP,id,"SOURCE_ELEMENT_MISSING",null)); continue; }
            var matches=profile.elements().stream().filter(s -> s.archimateType().equals(element.path("archimateType").asText())
                && (s.sourceIds().isEmpty() || s.sourceIds().contains(id)) && s.propertiesEquals().entrySet().stream().allMatch(e -> e.getValue().equals(property(snapshot,id,e.getKey())))).toList();
            if(matches.size()!=1) { diagnostics.add(new Diagnostic(matches.isEmpty() ? Severity.SKIP : Severity.CONFLICT,id,matches.isEmpty() ? "ELEMENT_FILTERED" : "AMBIGUOUS_PROFILE_SELECTOR",null)); continue; }
            var selector=matches.getFirst();
            if(!selector.archimateType().equals(target.mapping().cardTypes().get(selector.mappingRef())) || !target.publicationAccess().cardTypes().contains(selector.mappingRef())) {
                diagnostics.add(new Diagnostic(Severity.CONFLICT,id,"PROFILE_MAPPING_OR_TARGET_DENIED",null)); continue;
            }
            var key=new ModelElementKey(instance,model,EntityKind.ELEMENT,id); var bound=metadata.findPublicationIdentity(key);
            if(bound.isPresent() && !bound.get().remoteType().equals(selector.mappingRef())) {
                diagnostics.add(new Diagnostic(Severity.CONFLICT,id,"PROFILE_BOUND_TYPE_CONFLICT",null)); continue;
            }
            if(!selector.allowCreate() && (bound.isEmpty() || metadata.findExternal(bound.get().canonical().canonicalId(),"turboea",target.instanceId(),ResourceKind.CARD).isEmpty())) {
                diagnostics.add(new Diagnostic(Severity.CONFLICT,id,"PROFILE_EXISTING_BINDING_REQUIRED",null)); continue;
            }
            chosen.put(id,selector);
        }
        for(String id:policy.relationships()) {
            var rel=relationships.get(id);
            if(rel==null) { diagnostics.add(new Diagnostic(Severity.SKIP,id,"SOURCE_RELATION_MISSING",null)); continue; }
            var matches=profile.relationships().stream().filter(s -> s.archimateType().equals(rel.path("archimateType").asText()) && (s.sourceIds().isEmpty() || s.sourceIds().contains(id))).toList();
            if(matches.size()!=1) { diagnostics.add(new Diagnostic(matches.isEmpty() ? Severity.SKIP : Severity.CONFLICT,id,matches.isEmpty() ? "RELATION_FILTERED" : "AMBIGUOUS_PROFILE_SELECTOR",null)); continue; }
            if(!chosen.containsKey(rel.path("sourceId").asText()) || !chosen.containsKey(rel.path("targetId").asText())) {
                diagnostics.add(new Diagnostic(Severity.SKIP,id,"PROFILE_ENDPOINT_NOT_SELECTED",null)); continue;
            }
            if(!target.publicationAccess().relationTypes().contains(matches.getFirst().mappingRef())) {
                diagnostics.add(new Diagnostic(Severity.CONFLICT,id,"PROFILE_RELATION_TARGET_DENIED",null)); continue;
            }
            rels.put(id,matches.getFirst().mappingRef());
        }
        var ids=new HashSet<String>(chosen.keySet()); ids.addAll(rels.keySet());
        var stewards=new HashSet<>(policy.stewards()); stewards.retainAll(ids);
        var selection=new PolicySpec(chosen.isEmpty() ? Mode.DISABLED : policy.mode(),policy.ref(),List.copyOf(chosen.keySet()),List.copyOf(rels.keySet()),stewards);
        Plan base;
        if(chosen.isEmpty()) base=new Plan(model,target.key(),target.instanceId(),policy.ref(),snapshot.path("headRevision").asLong(),target.mapping().version(),List.of());
        else {
            var mappings=new TreeMap<String,String>(); chosen.forEach((id,s) -> mappings.put(id,s.mappingRef()));
            var fields=new TreeMap<String,Set<String>>(); chosen.forEach((id,s) -> fields.put(id,s.referenceOnly() ? Set.of() : s.fields()));
            base=new ArchitectureProjection(json).buildMapped(instance,model,target,selection,snapshot,metadata,mappings,rels,fields);
        }
        var resources=new ArrayList<Resource>();
        for(var resource:base.resources()) {
            var selector=chosen.get(resource.identity().model().entityId());
            if(selector==null) { resources.add(resource); continue; }
            var fields=new TreeMap<>(resource.fields()); fields.keySet().retainAll(selector.fields());
            if(selector.referenceOnly()) fields.clear();
            resources.add(new Resource(resource.identity(),resource.remoteType(),fields,resource.sourceEntityId(),resource.targetEntityId(),resource.steward()));
        }
        diagnostics.sort(Comparator.comparing(Diagnostic::entityId).thenComparing(Diagnostic::code));
        return new Plan(base.modelId(),base.targetKey(),base.targetInstanceId(),base.ref(),base.revision(),base.mappingVersion(),resources,policy.profile(),diagnostics);
    }
    static Map<String,JsonNode> index(JsonNode values) {
        var result=new TreeMap<String,JsonNode>();
        for(var value:values) if(result.put(value.path("id").asText(),value)!=null) throw new Failure(409,"PROFILE_DUPLICATE_SOURCE_ID");
        return result;
    }
    static String property(JsonNode snapshot,String id,String key) {
        for(var value:snapshot.path("propertyState")) if(id.equals(value.path("targetId").asText()) && key.equals(value.path("key").asText()) && "SetProperty".equals(value.path("type").asText()))
            return value.path("value").isTextual() ? value.path("value").asText() : value.path("value").toString();
        return null;
    }
}
