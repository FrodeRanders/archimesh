package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import org.gautelis.federation.ArchitectureAssertion;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.PublicationModel.*;

/** Durable source contributions, not another identity registry or an independent write worker. */
final class PublicationClaims {
    private final IntegrationStateRepository metadata;
    private final ObjectMapper json;
    private final String instance;
    PublicationClaims(IntegrationStateRepository metadata,ObjectMapper json,String instance) { this.metadata=metadata; this.json=json; this.instance=instance; }
    static boolean derived(Resource resource) { return resource.provenance()!=null && resource.provenance().assertion()!=null; }
    static String definitionId(PublicationProfile.Pin pin) { return PublicationJson.key("pub-profile:",pin.profileId(),pin.version()); }
    void retain(PublicationProfile.Definition definition) {
        var pin=definition.pin(json); String id=definitionId(pin);
        var prior=metadata.load(instance,DocumentKind.PUBLICATION_PROFILE,id);
        if(prior.isPresent()) {
            if(!json.valueToTree(pin).equals(prior.get().data().path("pin"))) throw new Failure(409,"PUBLICATION_PROFILE_VERSION_IMMUTABLE");
            return;
        }
        var data=json.createObjectNode().put("schemaVersion",1); data.set("pin",json.valueToTree(pin)); data.set("definition",json.valueToTree(definition));
        metadata.save(new StateDocument(instance,DocumentKind.PUBLICATION_PROFILE,id,0,data));
    }
    /** Replace only this model's contribution set, atomically, before any remote dispatch. */
    List<Resource> stage(Plan plan,StateDocument job) {
        String scope=PublicationJson.key("assertion-scope:",plan.modelId(),plan.targetInstanceId());
        var prior=metadata.load(instance,DocumentKind.ASSERTION_SCOPE,scope);
        var selected=new TreeMap<String,Resource>();
        plan.resources().stream().filter(PublicationClaims::derived).forEach(r -> selected.put(claimId(plan,r),r));
        if(prior.isEmpty() && selected.isEmpty()) return List.of();
        var changes=new ArrayList<StateDocument>(); var removed=new ArrayList<Resource>();
        if(prior.isPresent()) for(var id:prior.get().data().path("claims")) {
            if(selected.containsKey(id.asText())) continue;
            var old=metadata.load(instance,DocumentKind.ASSERTION_CLAIM,id.asText()).orElseThrow(() -> new Failure(409,"ASSERTION_CLAIM_MISSING"));
            var data=(ObjectNode)old.data(); data.put("status","WITHDRAWN").put("withdrawnAtRevision",plan.revision());
            changes.add(new StateDocument(instance,old.kind(),old.id(),old.version(),data));
            removed.add(json.convertValue(data.path("resource"),Resource.class));
        }
        for(var entry:selected.entrySet()) {
            var old=metadata.load(instance,DocumentKind.ASSERTION_CLAIM,entry.getKey());
            var data=json.createObjectNode().put("schemaVersion",1).put("sourceModelId",plan.modelId()).put("targetKey",plan.targetKey())
                .put("targetInstanceId",plan.targetInstanceId()).put("canonicalId",entry.getValue().identity().canonical().canonicalId())
                .put("status","DESIRED").put("policyVersion",job.data().path("policyVersion").asLong()).put("modelGeneration",job.data().path("modelGeneration").asText())
                .put("jobId",job.id()).put("approvedPlanDigest",PublicationJson.digest(json.valueToTree(plan)));
            data.set("profile",json.valueToTree(plan.profile())); data.set("resource",json.valueToTree(entry.getValue()));
            changes.add(new StateDocument(instance,DocumentKind.ASSERTION_CLAIM,entry.getKey(),old.map(StateDocument::version).orElse(0L),data));
        }
        var data=json.createObjectNode().put("schemaVersion",1).put("sourceModelId",plan.modelId()).put("targetInstanceId",plan.targetInstanceId());
        data.set("claims",json.valueToTree(selected.keySet()));
        changes.add(new StateDocument(instance,DocumentKind.ASSERTION_SCOPE,scope,prior.map(StateDocument::version).orElse(0L),data));
        metadata.saveAtomically(changes);
        return List.copyOf(removed);
    }
    void active(Plan plan,Resource resource) {
        var old=metadata.load(instance,DocumentKind.ASSERTION_CLAIM,claimId(plan,resource)).orElseThrow();
        var data=(ObjectNode)old.data(); data.put("status","ACTIVE"); metadata.save(new StateDocument(instance,old.kind(),old.id(),old.version(),data));
    }
    List<StateDocument> claims(Resource resource) {
        String prefix=prefix(resource),after=""; var result=new ArrayList<StateDocument>();
        do {
            var page=metadata.list(instance,DocumentKind.ASSERTION_CLAIM,prefix,after,100);
            result.addAll(page); if(result.size()>256) throw new Failure(413,"ASSERTION_CONTRIBUTION_LIMIT_EXCEEDED");
            if(page.size()<100) return List.copyOf(result);
            after=page.getLast().id();
        } while(true);
    }
    /** Never discard another model's stale/unavailable approval while computing a replacement attribute map. */
    Resource aggregate(Resource resource,PublicationProfile.Definition definition,Consumer<StateDocument> requireCurrent) {
        var access=EnumSet.noneOf(ArchitectureAssertion.Access.class); var key=resource.provenance().assertion();
        for(var document:claims(resource)) {
            if(document.data().path("status").asText().equals("WITHDRAWN")) continue;
            if(!Set.of("ACTIVE","DESIRED").contains(document.data().path("status").asText())) throw new Failure(409,"ASSERTION_CONTRIBUTION_REVIEW_REQUIRED");
            requireCurrent.accept(document);
            var contributor=json.convertValue(document.data().path("resource"),Resource.class);
            if(!key.equals(contributor.provenance().assertion()) || !definition.pin(json).equals(json.convertValue(document.data().path("profile"),PublicationProfile.Pin.class)))
                throw new Failure(409,"ASSERTION_PROFILE_MIGRATION_REQUIRES_REVIEW");
            access.addAll(contributor.provenance().evidence().access());
        }
        if(access.isEmpty()) return null;
        var rule=definition.derivations().stream().filter(r -> r.ruleId().equals(key.ruleId())).findFirst().orElseThrow(() -> new Failure(409,"ASSERTION_RULE_RETIRED_REQUIRES_REVIEW"));
        var fields=new TreeMap<String,JsonNode>(); var values=json.createArrayNode();
        access.stream().map(a -> rule.accessValues().get(PublicationProfile.Access.valueOf(a.name()))).sorted().forEach(values::add);
        fields.put(rule.accessAttribute(),values);
        return new Resource(resource.identity(),resource.remoteType(),fields,resource.sourceEntityId(),resource.targetEntityId(),true,resource.provenance());
    }
    private static String prefix(Resource resource) { return PublicationJson.key("assertion-claim:",resource.identity().canonical().canonicalId())+":"; }
    private static String claimId(Plan plan,Resource resource) { return prefix(resource)+PublicationJson.key("",plan.modelId(),plan.targetInstanceId()); }
}
