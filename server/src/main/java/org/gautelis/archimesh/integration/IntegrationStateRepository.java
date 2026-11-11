package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;

/** Independent metadata storage. No method writes model entities or revisions. */
public interface IntegrationStateRepository extends AutoCloseable {
    enum DocumentKind { FIELD_AUTHORITY, CHECKPOINT, CONFLICT, CONTRIBUTION, JOB }

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

    ReservedImport reserveImport(ImportReservation request);
    Optional<ReservedImport> findBinding(ModelElementKey key);
    Optional<CanonicalObject> findCanonical(ExternalResourceKey key);
    /** Requires the reserved batch's durable receipt and a matching materialized entity. */
    ReservedImport confirmImport(ModelElementKey key, String opBatchId);
    /** version=0 inserts; otherwise replaces exactly that stored version. Returns the new version. */
    StateDocument save(StateDocument document);
    Optional<StateDocument> load(String instanceId, DocumentKind kind, String id);
    @Override void close();
}
