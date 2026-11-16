package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.gautelis.archimesh.integration.PublicationModel.Mode;

/** Non-executable, normalized profile vocabulary. Profiles restrict approval; they never grant it. */
public final class PublicationProfile {
    private PublicationProfile() { }
    public enum Access { READ, WRITE }
    public enum DerivationKind { APPLICATION_DATA_ACCESS_AGGREGATE }
    public enum Severity { INFO, SKIP, CONFLICT }
    public record Diagnostic(Severity severity,String entityId,String code,String ruleId) { }
    public record Pin(String profileId,String version,String digest) {
        public Pin { key(profileId); key(version); if(digest==null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Exact profile digest required"); }
    }
    public record ElementSelector(String archimateType,String mappingRef,Set<String> fields,Set<String> sourceIds,
                                  Map<String,String> propertiesEquals,boolean requiresExplicitPublication,
                                  boolean allowCreate,boolean referenceOnly) {
        public ElementSelector {
            key(archimateType); key(mappingRef); fields=sorted(fields); sourceIds=sorted(sourceIds);
            propertiesEquals=propertiesEquals==null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(propertiesEquals));
            if(fields.size()>32 || sourceIds.size()>20 || propertiesEquals.size()>8 || referenceOnly && allowCreate)
                throw new IllegalArgumentException("Bounded, non-conflicting element selector required");
            fields.forEach(PublicationProfile::field); sourceIds.forEach(id -> id(id,"elem:"));
            propertiesEquals.forEach((k,v) -> { property(k); text(v,512); });
        }
    }
    public record RelationSelector(String archimateType,String mappingRef,Set<String> sourceIds,boolean requiresApprovedEndpoints) {
        public RelationSelector {
            key(archimateType); key(mappingRef); sourceIds=sorted(sourceIds); sourceIds.forEach(id -> id(id,"rel:"));
            if(sourceIds.size()>20 || !requiresApprovedEndpoints) throw new IllegalArgumentException("Approved relation endpoints required");
        }
    }
    public record Derivation(String ruleId,DerivationKind kind,String targetMappingRef,String accessModeProperty,
                             String accessAttribute,Map<Access,String> accessValues,Set<Access> allowedAccessModes,
                             boolean retainProvenance) {
        public Derivation {
            key(ruleId); Objects.requireNonNull(kind); key(targetMappingRef); property(accessModeProperty); field(accessAttribute);
            accessValues=accessValues==null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(accessValues));
            allowedAccessModes=allowedAccessModes==null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(allowedAccessModes));
            if(!accessAttribute.startsWith("attributes.") || !retainProvenance || allowedAccessModes.isEmpty()
                    || !accessValues.keySet().equals(allowedAccessModes) || new HashSet<>(accessValues.values()).size()!=accessValues.size())
                throw new IllegalArgumentException("Explicit access mapping and retained provenance required");
            accessValues.values().forEach(PublicationProfile::key);
        }
    }
    public record Limits(int maxSelectedElements,int maxSelectedRelationships,int maxDerivedRelations,int maxGraphElements,int maxGraphRelationships) {
        public Limits {
            if(maxSelectedElements<1 || maxSelectedElements>20 || maxSelectedRelationships<0 || maxSelectedRelationships>20
                    || maxDerivedRelations<0 || maxDerivedRelations>20 || maxGraphElements<1 || maxGraphElements>10000
                    || maxGraphRelationships<1 || maxGraphRelationships>20000) throw new IllegalArgumentException("Profile cannot raise M3 selection limits");
        }
    }
    public record Definition(int schemaVersion,String profileId,String version,String name,String description,
                              boolean enabled,Set<Mode> sourceModes,List<ElementSelector> elements,List<RelationSelector> relationships,
                              boolean derivationsEnabled,List<Derivation> derivations,Limits limits,String conflictStrategy,
                              String identityStrategy,String ownershipRule,String targetSystem) {
        public Definition {
            key(profileId); key(version); text(name,256); text(description,4096); Objects.requireNonNull(limits);
            if(schemaVersion!=1 || !"REPORT_AND_STOP".equals(conflictStrategy) || !"REGISTRY_OR_STABLE_OCCURRENCE".equals(identityStrategy)
                    || !"SOURCE_AUTHORITY_AND_APPROVED_STEWARD".equals(ownershipRule) || !"TURBO_EA".equals(targetSystem))
                throw new IllegalArgumentException("Unsupported profile schema, identity, ownership or target strategy");
            sourceModes=sourceModes==null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(sourceModes));
            if(sourceModes.isEmpty() || sourceModes.contains(Mode.DISABLED)) throw new IllegalArgumentException("Explicit source eligibility required");
            elements=elements==null ? List.of() : elements.stream().sorted(Comparator.comparing(ElementSelector::archimateType).thenComparing(ElementSelector::mappingRef)).toList();
            relationships=relationships==null ? List.of() : relationships.stream().sorted(Comparator.comparing(RelationSelector::archimateType).thenComparing(RelationSelector::mappingRef)).toList();
            derivations=derivations==null ? List.of() : derivations.stream().sorted(Comparator.comparing(Derivation::ruleId)).toList();
            if(elements.isEmpty() || elements.size()>20 || relationships.size()>20 || derivations.size()>4
                    || elements.stream().map(e -> e.archimateType()+"\0"+e.mappingRef()).distinct().count()!=elements.size()
                    || relationships.stream().map(e -> e.archimateType()+"\0"+e.mappingRef()).distinct().count()!=relationships.size()
                    || derivations.stream().map(Derivation::ruleId).distinct().count()!=derivations.size())
                throw new IllegalArgumentException("Unique bounded profile selectors required");
        }
        public Pin pin(ObjectMapper json) { return new Pin(profileId,version,PublicationJson.digest(json.valueToTree(this))); }
    }
    public record Catalog(int schemaVersion,List<Definition> profiles) {
        public Catalog {
            profiles=profiles==null ? List.of() : List.copyOf(profiles);
            if(schemaVersion!=1 || profiles.size()>32 || profiles.stream().map(p -> p.profileId()+"\0"+p.version()).distinct().count()!=profiles.size())
                throw new IllegalArgumentException("Unique bounded immutable profile catalog required");
        }
    }
    private static Set<String> sorted(Set<String> values) { return values==null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(values)); }
    private static void key(String value) { if(value==null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}")) throw new IllegalArgumentException("Invalid profile key"); }
    private static void text(String value,int max) { if(value==null || value.isBlank() || value.length()>max || value.chars().anyMatch(c -> c<32)) throw new IllegalArgumentException("Invalid profile text"); }
    private static void id(String value,String prefix) { text(value,512); if(!value.startsWith(prefix)) throw new IllegalArgumentException("Semantic source ID required"); }
    private static void field(String value) { if(!Set.of("name","description","lifecycle").contains(value) && (value==null || !value.matches("attributes\\.[a-zA-Z0-9_.-]{1,128}"))) throw new IllegalArgumentException("Explicit supported target field required"); }
    private static void property(String value) { text(value,256); if(value.startsWith("archimesh.external.") || !value.matches("[a-zA-Z0-9_.:-]+")) throw new IllegalArgumentException("Property selector cannot spoof identity"); }
}
