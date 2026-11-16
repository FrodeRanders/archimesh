package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.gautelis.federation.ArchitectureAssertion;
import org.gautelis.federation.Identity;
import static org.gautelis.archimesh.integration.PublicationProfile.*;
import static org.gautelis.archimesh.integration.PublicationModel.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;

/** Exact two-edge pattern, no recursive traversal and no inference of catalog/CRUD/lineage semantics. */
final class ProfileDerivation {
    private final ObjectMapper json;
    ProfileDerivation(ObjectMapper json) { this.json=json; }
    List<Resource> derive(String instance,String model,IntegrationConfiguration.Target target,PolicySpec policy,Plan direct,
                          ProfileGraph graph,Definition profile,List<Diagnostic> diagnostics,IntegrationStateRepository metadata) {
        var result=new TreeMap<String,Resource>(); var catalogOwnership=new HashMap<String,Boolean>();
        var selected=new TreeMap<String,Resource>(); direct.resources().stream().filter(r -> r.identity().model().entityKind()==EntityKind.ELEMENT)
            .forEach(r -> selected.put(r.identity().model().entityId(),r));
        for(var rule:profile.derivations()) {
            var mapping=target.mapping().relationTypes().get(rule.targetMappingRef());
            if(mapping==null || !mapping.archimateType().equals("AccessRelationship") || !target.publicationAccess().relationTypes().contains(rule.targetMappingRef())
                    || !target.publicationAccess().fields().contains(rule.accessAttribute()) || !target.publicationAccess().completeAttributeReadsApproved()) {
                diagnostics.add(new Diagnostic(Severity.CONFLICT,"rule:"+rule.ruleId(),"DERIVED_MAPPING_OR_AUTHORITY_DENIED",rule.ruleId())); continue;
            }
            var assignments=new TreeMap<String,List<ProfileGraph.Edge>>();
            for(var edge:graph.edges()) if(edge.type().equals("AssignmentRelationship")) {
                if(graph.type(edge.source(),"ApplicationComponent") && graph.type(edge.target(),"ApplicationFunction"))
                    assignments.computeIfAbsent(edge.target(),ignored -> new ArrayList<>()).add(edge);
                else diagnostics.add(new Diagnostic(Severity.SKIP,edge.id(),"WRONG_ASSIGNMENT_DIRECTION_OR_TYPE",rule.ruleId()));
            }
            record Group(Resource application,Resource data,ArchitectureAssertion.Key key,List<ArchitectureAssertion.Path> paths) { }
            var groups=new TreeMap<String,Group>();
            for(var edge:graph.edges()) if(edge.type().equals("AccessRelationship")) {
                if(!graph.type(edge.source(),"ApplicationFunction") || !graph.type(edge.target(),"DataObject")) {
                    diagnostics.add(new Diagnostic(Severity.SKIP,edge.id(),"WRONG_ACCESS_DIRECTION_OR_TYPE",rule.ruleId())); continue;
                }
                var assigned=assignments.getOrDefault(edge.source(),List.of());
                if(assigned.isEmpty()) { diagnostics.add(new Diagnostic(Severity.SKIP,edge.id(),"FUNCTION_ASSIGNMENT_REQUIRED",rule.ruleId())); continue; }
                var access=access(graph.property(edge.id(),rule.accessModeProperty()));
                if(access.isEmpty() || !rule.allowedAccessModes().containsAll(access.stream().map(a -> PublicationProfile.Access.valueOf(a.name())).toList())) {
                    diagnostics.add(new Diagnostic(Severity.SKIP,edge.id(),access.isEmpty() ? "UNKNOWN_ACCESS_MODE" : "ACCESS_MODE_NOT_APPROVED",rule.ruleId())); continue;
                }
                for(var assignment:assigned) {
                    var app=selected.get(assignment.source()); var data=selected.get(edge.target());
                    if(app==null || data==null) { diagnostics.add(new Diagnostic(Severity.SKIP,edge.id(),"DERIVED_ENDPOINT_NOT_SELECTED",rule.ruleId())); continue; }
                    if(data.identity().canonical().kind().startsWith("catalog.") || catalogOwnership.computeIfAbsent(data.identity().canonical().canonicalId(),metadata::catalogOwned)) {
                        diagnostics.add(new Diagnostic(Severity.SKIP,edge.id(),"LOGICAL_DATA_REQUIRED_NOT_CATALOG_ASSET",rule.ruleId())); continue;
                    }
                    if(!mapping.sourceType().equals((mapping.reversed() ? data : app).remoteType()) || !mapping.targetType().equals((mapping.reversed() ? app : data).remoteType())) {
                        diagnostics.add(new Diagnostic(Severity.CONFLICT,edge.id(),"DERIVED_ENDPOINT_MAPPING_MISMATCH",rule.ruleId())); continue;
                    }
                    String semantics=PublicationJson.digest(json.valueToTree(List.of(mapping,rule.accessAttribute(),rule.accessValues())));
                    var key=new ArchitectureAssertion.Key(app.identity().canonical().canonicalId(),ArchitectureAssertion.Predicate.LOGICAL_DATA_ACCESS,
                        data.identity().canonical().canonicalId(),new Identity.Repository("turboea",target.instanceId()),profile.profileId(),rule.ruleId(),rule.targetMappingRef(),semantics);
                    var group=groups.computeIfAbsent(key.canonicalId(),ignored -> new Group(app,data,key,new ArrayList<>()));
                    group.paths().add(new ArchitectureAssertion.Path(assignment.id(),edge.source(),edge.id(),access));
                    if(group.paths().size()>128) throw new Failure(413,"PROFILE_EVIDENCE_LIMIT_EXCEEDED");
                }
            }
            for(var group:groups.values()) {
                String synthetic="rel:derived:"+IntegrationIdentity.sha256(group.key().canonicalId());
                var evidence=new ArchitectureAssertion.Evidence("archimesh",instance,model,policy.ref(),direct.revision(),profile.profileId(),profile.version(),policy.profile().digest(),rule.ruleId(),
                    ArchitectureAssertion.Authority.ARCHIMESH_ARCHITECTURE,List.of(group.application().identity().model().entityId(),group.data().identity().model().entityId()),group.paths());
                var fields=new TreeMap<String,JsonNode>(); var values=json.createArrayNode();
                evidence.access().stream().map(a -> rule.accessValues().get(PublicationProfile.Access.valueOf(a.name()))).sorted().forEach(values::add);
                fields.put(rule.accessAttribute(),values);
                var identity=new IntegrationStateRepository.PublicationIdentity(new ModelElementKey(instance,model,EntityKind.RELATIONSHIP,synthetic),
                    new CanonicalObject(group.key().canonicalId(),"architecture.logical-data-access"),"AccessRelationship",target.mapping().version(),rule.targetMappingRef());
                var resource=new Resource(identity,rule.targetMappingRef(),fields,mapping.reversed() ? group.data().identity().model().entityId() : group.application().identity().model().entityId(),
                    mapping.reversed() ? group.application().identity().model().entityId() : group.data().identity().model().entityId(),true,new Provenance(group.key(),evidence,false));
                if(result.put(group.key().canonicalId(),resource)!=null) throw new Failure(409,"PROFILE_ASSERTION_MAPPING_CONFLICT");
            }
        }
        return List.copyOf(result.values());
    }
    static Set<ArchitectureAssertion.Access> access(String value) {
        if(value==null) return Set.of();
        return switch(value) {
            case "READ", "Read" -> Set.of(ArchitectureAssertion.Access.READ);
            case "WRITE", "Write" -> Set.of(ArchitectureAssertion.Access.WRITE);
            case "READ_WRITE", "ReadWrite" -> Set.of(ArchitectureAssertion.Access.READ,ArchitectureAssertion.Access.WRITE);
            default -> Set.of();
        };
    }
}
