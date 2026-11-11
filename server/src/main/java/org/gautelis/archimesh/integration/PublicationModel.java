package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.PublicationIdentity;

public final class PublicationModel {
    private PublicationModel() { }
    public enum Mode { DISABLED, HEAD, TAG }
    public record PolicySpec(Mode mode,String ref,List<String> elements,List<String> relationships,Set<String> stewards) {
        public PolicySpec {
            if (mode==null) throw new IllegalArgumentException("Publication mode required");
            IntegrationIdentity.required(ref);
            elements=elements==null ? List.of() : elements.stream().sorted().toList();
            relationships=relationships==null ? List.of() : relationships.stream().sorted().toList();
            stewards=stewards==null ? Set.of() : Set.copyOf(stewards);
            if (elements.size()>20 || relationships.size()>20 || new HashSet<>(elements).size()!=elements.size()
                    || new HashSet<>(relationships).size()!=relationships.size()) throw new IllegalArgumentException("Select at most twenty distinct elements and relationships");
            for (String id:elements) if (!IntegrationIdentity.required(id).startsWith("elem:")) throw new IllegalArgumentException("Semantic element ID required");
            for (String id:relationships) if (!IntegrationIdentity.required(id).startsWith("rel:")) throw new IllegalArgumentException("Semantic relationship ID required");
            var all=new HashSet<>(elements); all.addAll(relationships);
            if (!all.containsAll(stewards)) throw new IllegalArgumentException("Only selected entities can be stewards");
            if (mode!=Mode.DISABLED && elements.isEmpty()) throw new IllegalArgumentException("Explicit publication selection required");
            if (mode==Mode.TAG && "HEAD".equalsIgnoreCase(ref) || mode==Mode.HEAD && !"HEAD".equals(ref))
                throw new IllegalArgumentException("Publication ref does not match mode");
        }
    }
    public record Resource(PublicationIdentity identity,String remoteType,SortedMap<String,JsonNode> fields,
                           String sourceEntityId,String targetEntityId,boolean steward) {
        public Resource { fields=Collections.unmodifiableSortedMap(new TreeMap<>(fields)); }
    }
    public record Plan(String modelId,String targetKey,String targetInstanceId,String ref,long revision,
                       String mappingVersion,List<Resource> resources) {
        public Plan { resources=List.copyOf(resources); }
    }
    public record Preview(PolicySpec policy,Plan plan,String digest) { }
    public record Approval(PolicySpec policy,long expectedVersion,String previewDigest) { }
    public record RunRequest(String requestId,String previewDigest) { }
    public record Adoption(String canonicalId,String resourceId,String remoteDigest) { }
    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status,String code) { super(code); this.status=status; }
        public int status() { return status; }
    }
}
