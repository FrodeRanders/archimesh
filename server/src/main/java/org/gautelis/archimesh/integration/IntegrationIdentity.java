package org.gautelis.archimesh.integration;

/** Integration identities deliberately never use the projection's modelId property. */
public final class IntegrationIdentity {
    private IntegrationIdentity() { }

    public enum EntityKind { ELEMENT, RELATIONSHIP }
    public enum ResourceKind { CARD, RELATION }
    public enum BindingState { PREPARED, ACTIVE, ORPHANED }

    public record ModelElementKey(String instanceId, String sourceModelId, EntityKind entityKind, String entityId) {
        public ModelElementKey {
            instanceId = required(instanceId); sourceModelId = required(sourceModelId);
            if (entityKind == null) throw new IllegalArgumentException("Entity kind is required");
            entityId = required(entityId);
            if (!entityId.startsWith(entityKind == EntityKind.ELEMENT ? "elem:" : "rel:") || entityId.endsWith(":"))
                throw new IllegalArgumentException("Entity ID must carry its semantic kind prefix");
        }
    }

    public record ExternalResourceKey(String system, String targetInstanceId, ResourceKind resourceKind, String resourceId) {
        public ExternalResourceKey {
            system = required(system); targetInstanceId = required(targetInstanceId);
            if (resourceKind == null) throw new IllegalArgumentException("Resource kind is required");
            resourceId = required(resourceId);
        }
    }

    public record CanonicalObject(String canonicalId, String kind) {
        public CanonicalObject { canonicalId = required(canonicalId); kind = required(kind); }
    }

    public record ImportReservation(ModelElementKey model, CanonicalObject canonicalCandidate,
                                    ExternalResourceKey external, String archimateType,
                                    String mappingVersion, String opBatchId, String bindingMode) {
        public ImportReservation(ModelElementKey model,CanonicalObject canonicalCandidate,ExternalResourceKey external,
                                 String archimateType,String mappingVersion,String opBatchId) {
            this(model,canonicalCandidate,external,archimateType,mappingVersion,opBatchId,"CREATE");
        }
        public ImportReservation {
            if (model == null || canonicalCandidate == null || external == null)
                throw new IllegalArgumentException("Import identities are required");
            archimateType = required(archimateType); mappingVersion = required(mappingVersion);
            opBatchId = required(opBatchId);
            if (!java.util.Set.of("CREATE","LINK").contains(bindingMode)) throw new IllegalArgumentException("Invalid import mode");
            if ((model.entityKind() == EntityKind.ELEMENT) != (external.resourceKind() == ResourceKind.CARD))
                throw new IllegalArgumentException("Model and external resource kinds differ");
        }
    }

    public record ReservedImport(ModelElementKey model, CanonicalObject canonical, ExternalResourceKey external,
                                 String archimateType, String mappingVersion, String opBatchId,
                                 BindingState state, long acceptedRevision, String bindingMode) { }

    public static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 512 || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Integration identity/version must be nonblank, bounded and contain no control characters");
        return value;
    }
}
