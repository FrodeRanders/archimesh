package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import static org.gautelis.archimesh.integration.PublicationModel.*;

/** Pin/evidence closure inside the existing portable integration archive. No model records. */
final class PublicationProfileArchive {
    private PublicationProfileArchive() { }
    static boolean extension(String kind,JsonNode data) {
        return Set.of("PUBLICATION_PROFILE","ASSERTION_CLAIM","ASSERTION_SCOPE").contains(kind) || data.hasNonNull("profile")
            || data.path("policy").hasNonNull("profile") || data.path("plan").hasNonNull("profile") || data.path("archivedPolicy").hasNonNull("profile");
    }
    static void validate(String format,String instance,List<IntegrationArchive.Node> nodes,ObjectMapper json) {
        var definitions=new HashMap<String,PublicationProfile.Definition>(); var documents=new HashMap<String,JsonNode>(); var canonical=new HashSet<String>();
        try {
            for(var node:nodes) {
                if(node.label().equals("IntegrationCanonicalObject")) canonical.add((String)node.properties().get("canonicalId"));
                if(!node.label().equals("IntegrationStateDocument")) continue;
                String kind=(String)node.properties().get("kind"),id=(String)node.properties().get("id");
                var data=json.readTree((String)node.properties().get("dataJson")); documents.put(kind+":"+id,data);
                if(IntegrationArchive.LEGACY_FORMAT.equals(format) && extension(kind,data)) throw new Failure(400,"ARCHIVE_LEGACY_PROFILE_EXTENSION_INVALID");
                if(kind.equals("PUBLICATION_PROFILE")) {
                    var definition=json.readerFor(PublicationProfile.Definition.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).<PublicationProfile.Definition>readValue(data.path("definition"));
                    var pin=json.treeToValue(data.path("pin"),PublicationProfile.Pin.class);
                    if(data.path("schemaVersion").asInt()!=1 || !definition.pin(json).equals(pin) || !PublicationClaims.definitionId(pin).equals(id)) throw new IllegalArgumentException();
                    definitions.put(id,definition);
                }
            }
            for(var entry:documents.entrySet()) {
                var data=entry.getValue();
                for(var pinNode:List.of(data.path("profile"),data.path("policy").path("profile"),data.path("plan").path("profile"),data.path("archivedPolicy").path("profile"))) {
                    if(pinNode.isMissingNode() || pinNode.isNull()) continue;
                    requireDefinition(pinNode,definitions,json);
                }
                if(entry.getKey().startsWith("ASSERTION_CLAIM:")) {
                    var resource=json.treeToValue(data.path("resource"),Resource.class);
                    if(!PublicationClaims.derived(resource) || data.path("schemaVersion").asInt()!=1 || !Set.of("ACTIVE","DESIRED","WITHDRAWN","QUARANTINED").contains(data.path("status").asText())) throw new IllegalArgumentException();
                    var evidence=resource.provenance().evidence(); var key=resource.provenance().assertion();
                    var definition=requireDefinition(data.path("profile"),definitions,json);
                    var pin=definition.pin(json);
                    if(!pin.equals(new PublicationProfile.Pin(evidence.profileId(),evidence.profileVersion(),evidence.profileDigest()))
                            || !evidence.sourceSystem().equals("archimesh") || !evidence.sourceInstanceId().equals(instance)
                            || !evidence.sourceModelId().equals(data.path("sourceModelId").asText()) || !resource.identity().model().sourceModelId().equals(evidence.sourceModelId())
                            || !resource.identity().model().instanceId().equals(instance) || !key.canonicalId().equals(resource.identity().canonical().canonicalId())
                            || !key.canonicalId().equals(data.path("canonicalId").asText()) || !key.profileId().equals(pin.profileId()) || !key.ruleId().equals(evidence.ruleId())
                            || key.predicate()!=org.gautelis.federation.ArchitectureAssertion.Predicate.LOGICAL_DATA_ACCESS
                            || evidence.authority()!=org.gautelis.federation.ArchitectureAssertion.Authority.ARCHIMESH_ARCHITECTURE
                            || !key.target().system().equals("turboea") || !key.target().instance().equals(data.path("targetInstanceId").asText())
                            || !canonical.containsAll(List.of(key.canonicalId(),key.subjectCanonicalId(),key.objectCanonicalId()))) throw new IllegalArgumentException();
                    String expectedId=PublicationJson.key("assertion-claim:",key.canonicalId())+":"+PublicationJson.key("",evidence.sourceModelId(),key.target().instance());
                    if(!entry.getKey().equals("ASSERTION_CLAIM:"+expectedId) || !resource.identity().archimateType().equals("AccessRelationship") || resource.identity().model().entityKind()!=IntegrationIdentity.EntityKind.RELATIONSHIP
                            || !resource.identity().canonical().kind().equals("architecture.logical-data-access") || resource.provenance().referenceOnly()) throw new IllegalArgumentException();
                    var rule=definition.derivations().stream().filter(r -> r.ruleId().equals(evidence.ruleId()) && r.targetMappingRef().equals(resource.remoteType())).findFirst().orElseThrow();
                    var expected=json.createArrayNode(); evidence.access().stream().map(a -> rule.accessValues().get(PublicationProfile.Access.valueOf(a.name()))).sorted().forEach(expected::add);
                    if(!key.mappingRef().equals(rule.targetMappingRef()) || resource.fields().size()!=1 || !PublicationJson.equal(resource.fields().get(rule.accessAttribute()),expected)) throw new IllegalArgumentException();
                    if(!definition.derivationsEnabled() || evidence.paths().isEmpty() || !rule.allowedAccessModes().containsAll(evidence.access().stream().map(a -> PublicationProfile.Access.valueOf(a.name())).toList())) throw new IllegalArgumentException();
                    var job=documents.get("PUBLICATION_JOB:"+data.path("jobId").asText());
                    if(job==null || !PublicationJson.equal(job.path("plan").path("profile"),data.path("profile")) || !job.path("sourceModelId").asText().equals(evidence.sourceModelId()) || !PublicationJson.digest(job.path("plan")).equals(data.path("approvedPlanDigest").asText())) throw new IllegalArgumentException();
                    boolean found=false;
                    for(var candidate:job.path("plan").path("resources")) if(PublicationJson.equal(candidate,data.path("resource"))) found=true;
                    if(!found) throw new IllegalArgumentException();
                }
                if(entry.getKey().startsWith("ASSERTION_SCOPE:")) for(String field:List.of("claims","withdrawnClaims")) {
                    if(!data.has(field)) continue;
                    if(!data.path(field).isArray() || data.path(field).size()>20) throw new IllegalArgumentException();
                    for(var id:data.path(field)) {
                        var claim=documents.get("ASSERTION_CLAIM:"+id.asText());
                        if(claim==null || !claim.path("sourceModelId").asText().equals(data.path("sourceModelId").asText()) || !claim.path("targetInstanceId").asText().equals(data.path("targetInstanceId").asText())) throw new IllegalArgumentException();
                    }
                }
            }
        } catch(Failure failure) { throw failure; }
        catch(Exception invalid) { throw new Failure(400,"ARCHIVE_PUBLICATION_PROFILE_CLOSURE_INVALID"); }
    }
    private static PublicationProfile.Definition requireDefinition(JsonNode node,Map<String,PublicationProfile.Definition> definitions,ObjectMapper json) throws java.io.IOException {
        var pin=json.treeToValue(node,PublicationProfile.Pin.class); var definition=definitions.get(PublicationClaims.definitionId(pin));
        if(definition==null || !definition.pin(json).equals(pin)) throw new Failure(400,"ARCHIVE_PUBLICATION_PROFILE_PIN_MISSING_OR_CHANGED");
        return definition;
    }
}
