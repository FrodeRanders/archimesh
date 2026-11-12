package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;

/** Independent metadata storage. No method writes model entities or revisions. */
public interface IntegrationStateRepository extends AutoCloseable {
    enum DocumentKind { FIELD_AUTHORITY, CHECKPOINT, CONFLICT, CONTRIBUTION, JOB, PUBLICATION_POLICY, PUBLICATION_JOB, PUBLICATION_RESOURCE, INBOUND_POLICY, INBOUND_STATE, CENTRAL_EDIT, CENTRAL_LANE, CENTRAL_DELIVERY, MODEL_SCOPE, OPERATIONS, RECONCILIATION, IDENTITY_PROOF }
    record PublicationIdentity(ModelElementKey model,CanonicalObject canonical,String archimateType,String mappingVersion,
                               @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String externalType) {
        public PublicationIdentity(ModelElementKey model,CanonicalObject canonical,String archimateType,String mappingVersion) {
            this(model,canonical,archimateType,mappingVersion,null);
        }
        public String remoteType() { return externalType==null ? canonical.kind() : externalType; }
    }
    default org.gautelis.federation.IdentityRegistry identities(String instance) { throw new UnsupportedOperationException(); }
    default JsonNode previewIdentityMigration(String instance,String canonical,String semanticKind) { throw new UnsupportedOperationException(); }
    default void migrateIdentity(String instance,String canonical,String semanticKind,String digest,String actor) { throw new UnsupportedOperationException(); }

    /** Opaque JSON state is typed by kind, versioned with CAS and scoped to an integration instance. */
    record StateDocument(String instanceId, DocumentKind kind, String id, long version, JsonNode data) {
        public StateDocument {
            required(instanceId); required(id);
            if (kind == null || version < 0 || data == null || !data.isObject() || data.toString().length() > 262_144)
                throw new IllegalArgumentException("Invalid integration state document");
            data = data.deepCopy();
        }
        @Override public JsonNode data() { return data.deepCopy(); }
    }

    default void migrateMappings(String instance,String model,JsonNode migrations) { throw new UnsupportedOperationException(); }
    default JsonNode statistics(String instance) { throw new UnsupportedOperationException(); }
    default java.util.List<IntegrationArchive.Node> archive(String instance) { throw new UnsupportedOperationException(); }
    default void restoreArchive(String instance,java.util.List<IntegrationArchive.Node> nodes) { throw new UnsupportedOperationException(); }
    default String modelGeneration(String model) { throw new UnsupportedOperationException(); }
    default boolean hasModelMetadata(String instance,String model) { throw new UnsupportedOperationException(); }

    ReservedImport reserveImport(ImportReservation request);
    Optional<ReservedImport> findBinding(ModelElementKey key);
    Optional<CanonicalObject> findCanonical(ExternalResourceKey key);
    Optional<ReservedImport> findModelBinding(String instanceId, String sourceModelId, ExternalResourceKey key);
    default Optional<PublicationIdentity> findPublicationIdentity(ModelElementKey key) { throw new UnsupportedOperationException(); }
    default PublicationIdentity reservePublicationIdentity(PublicationIdentity identity) { throw new UnsupportedOperationException(); }
    default Optional<ExternalResourceKey> findExternal(String canonicalId,String system,String target,ResourceKind kind) { throw new UnsupportedOperationException(); }
    default void recordPublishedIdentity(PublicationIdentity identity,ExternalResourceKey external,long revision,String jobId) { throw new UnsupportedOperationException(); }
    default java.util.List<StateDocument> list(String instanceId,DocumentKind kind,String prefix,String after,int limit) { throw new UnsupportedOperationException(); }
    default java.util.List<ReservedImport> bindings(String instanceId,String targetInstance,String sourceModel,String after,int limit) { throw new UnsupportedOperationException(); }
    default void orphanBinding(ModelElementKey key) { throw new UnsupportedOperationException(); }
    /** Requires the reserved batch's durable receipt and a matching materialized entity. */
    ReservedImport confirmImport(ModelElementKey key, String opBatchId);
    /** version=0 inserts; otherwise replaces exactly that stored version. Returns the new version. */
    StateDocument save(StateDocument document);
    default java.util.List<StateDocument> saveAtomically(java.util.List<StateDocument> documents) { throw new UnsupportedOperationException(); }
    Optional<StateDocument> load(String instanceId, DocumentKind kind, String id);
    @Override void close();
}
