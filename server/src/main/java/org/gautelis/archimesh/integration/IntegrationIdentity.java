package org.gautelis.archimesh.integration;

/** Integration identities deliberately never use the projection's modelId property. */
public final class IntegrationIdentity {
    private IntegrationIdentity() { }

    public enum EntityKind { ELEMENT, RELATIONSHIP }
    public enum ResourceKind { CARD, RELATION, ENTITY }
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
            resourceId = resourceKind==ResourceKind.ENTITY ? org.gautelis.federation.Identity.text(resourceId,8192) : required(resourceId);
        }
        public String indexId() { return resourceKind==ResourceKind.ENTITY ? "sha256:"+sha256(resourceId) : resourceId; }
        public boolean object() { return resourceKind!=ResourceKind.RELATION; }
    }

    public record CanonicalObject(String canonicalId, String kind) {
        public CanonicalObject { canonicalId = required(canonicalId); kind = required(kind); }
    }

    public record ImportReservation(ModelElementKey model, CanonicalObject canonicalCandidate,
                                    ExternalResourceKey external, String archimateType,
                                    String mappingVersion, String opBatchId, String bindingMode,
                                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String externalType) {
        public ImportReservation(ModelElementKey model,CanonicalObject canonicalCandidate,ExternalResourceKey external,
                                 String archimateType,String mappingVersion,String opBatchId) {
            this(model,canonicalCandidate,external,archimateType,mappingVersion,opBatchId,"CREATE",null);
        }
        public ImportReservation(ModelElementKey model,CanonicalObject canonicalCandidate,ExternalResourceKey external,
                                 String archimateType,String mappingVersion,String opBatchId,String bindingMode) {
            this(model,canonicalCandidate,external,archimateType,mappingVersion,opBatchId,bindingMode,null);
        }
        public ImportReservation {
            if (model == null || canonicalCandidate == null || external == null)
                throw new IllegalArgumentException("Import identities are required");
            archimateType = required(archimateType); mappingVersion = required(mappingVersion);
            opBatchId = required(opBatchId);
            if (externalType!=null) required(externalType);
            if (external.resourceKind()==ResourceKind.ENTITY && externalType==null)
                throw new IllegalArgumentException("Native entity type is required independently of canonical semantics");
            if (!java.util.Set.of("CREATE","LINK").contains(bindingMode)) throw new IllegalArgumentException("Invalid import mode");
            if ((model.entityKind() == EntityKind.ELEMENT) != external.object())
                throw new IllegalArgumentException("Model and external resource kinds differ");
        }
        public String remoteType() { return externalType==null ? canonicalCandidate.kind() : externalType; }
    }

    public record ReservedImport(ModelElementKey model, CanonicalObject canonical, ExternalResourceKey external,
                                 String archimateType, String mappingVersion, String opBatchId,
                                 BindingState state, long acceptedRevision, String bindingMode,
                                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String externalType) {
        public ReservedImport(ModelElementKey model,CanonicalObject canonical,ExternalResourceKey external,
                              String archimateType,String mappingVersion,String opBatchId,BindingState state,long revision,String mode) {
            this(model,canonical,external,archimateType,mappingVersion,opBatchId,state,revision,mode,null);
        }
        public String remoteType() { return externalType==null ? canonical.kind() : externalType; }
    }

    public static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 512 || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Integration identity/version must be nonblank, bounded and contain no control characters");
        return value;
    }
}
