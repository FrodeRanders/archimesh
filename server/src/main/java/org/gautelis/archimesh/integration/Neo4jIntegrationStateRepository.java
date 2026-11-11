package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.TransactionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;

/** Atomic identity reservations and compare-and-set metadata, separate from the model repository. */
public final class Neo4jIntegrationStateRepository implements IntegrationStateRepository {
    private final Driver driver;
    private final ObjectMapper json;
    private final boolean ownsDriver;
    private volatile boolean schemaInstalled;

    public Neo4jIntegrationStateRepository(Driver driver, ObjectMapper json, boolean ownsDriver) {
        this.driver = java.util.Objects.requireNonNull(driver);
        this.json = java.util.Objects.requireNonNull(json);
        this.ownsDriver = ownsDriver;
    }

    public void installSchema() {
        try (var input = getClass().getResourceAsStream("/integrations/schema-v1.cypher")) {
            if (input == null) throw new IllegalStateException("Missing integration schema");
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("(?m)^\\s*//.*$", "");
            try (var session = driver.session()) {
                for (String statement : schema.split(";")) {
                    if (!statement.isBlank()) session.run(statement).consume();
                }
                schemaInstalled=true;
            }
        } catch (IOException error) { throw new IllegalStateException("Cannot read integration schema", error); }
    }

    @Override public ReservedImport reserveImport(ImportReservation request) {
        requireSchema();
        var params = externalParams(request.external());
        params.put("candidate", request.canonicalCandidate().canonicalId());
        params.put("kind", request.canonicalCandidate().kind());
        params.putAll(modelParams(request.model()));
        params.put("archimateType", request.archimateType());
        params.put("mappingVersion", request.mappingVersion());
        params.put("batch", request.opBatchId());
        params.put("bindingMode", request.bindingMode());
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                // Fence this external resource before observing its canonical assignment.
                String canonical = tx.run("""
                        MERGE (e:IntegrationExternalBinding {system:$system, targetInstanceId:$target,
                            resourceKind:$resourceKind, resourceId:$resourceId})
                        ON CREATE SET e.canonicalId=$candidate
                        SET e.fence=coalesce(e.fence,0)+1
                        RETURN e.canonicalId AS canonical
                        """, params).single().get("canonical").asString();
                params.put("canonical", canonical);
                var object = tx.run("""
                        MERGE (c:IntegrationCanonicalObject {canonicalId:$canonical})
                        ON CREATE SET c.kind=$kind
                        RETURN c.kind AS kind
                        """, params).single();
                if (!request.canonicalCandidate().kind().equals(object.get("kind").asString()))
                    throw new IllegalStateException("Canonical kind conflict");
                var binding = tx.run("""
                        MERGE (b:IntegrationModelBinding {instanceId:$instance, sourceModelId:$sourceModel,
                            entityKind:$entityKind, canonicalId:$canonical})
                        ON CREATE SET b.entityId=$entityId, b.archimateType=$archimateType,
                            b.mappingVersion=$mappingVersion, b.opBatchId=$batch, b.state='PREPARED', b.bindingMode=$bindingMode,
                            b.acceptedRevision=0, b.system=$system, b.targetInstanceId=$target,
                            b.resourceKind=$resourceKind, b.resourceId=$resourceId
                        RETURN properties(b) AS binding
                        """, params).single();
                ReservedImport result = readBinding(binding, request.canonicalCandidate().kind());
                if (!result.archimateType().equals(request.archimateType())
                        || !result.mappingVersion().equals(request.mappingVersion())
                        || !result.external().equals(request.external()))
                    throw new IllegalStateException("Existing binding has incompatible type, mapping or origin");
                return result;
            });
        }
    }

    @Override public Optional<CanonicalObject> findCanonical(ExternalResourceKey key) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var rows = tx.run("""
                        MATCH (e:IntegrationExternalBinding {system:$system,targetInstanceId:$target,
                            resourceKind:$resourceKind,resourceId:$resourceId})
                        MATCH (c:IntegrationCanonicalObject {canonicalId:e.canonicalId})
                        RETURN c.canonicalId AS canonical,c.kind AS kind
                        """, externalParams(key));
                if (!rows.hasNext()) return Optional.empty();
                var row = rows.single();
                return Optional.of(new CanonicalObject(row.get("canonical").asString(), row.get("kind").asString()));
            });
        }
    }

    @Override public Optional<ReservedImport> findBinding(ModelElementKey key) {
        try (var session = driver.session()) { return session.executeRead(tx -> findBinding(tx, modelParams(key))); }
    }

    @Override public Optional<ReservedImport> findModelBinding(String instanceId, String sourceModelId, ExternalResourceKey key) {
        required(instanceId); required(sourceModelId);
        var params=externalParams(key); params.put("instance",instanceId); params.put("sourceModel",sourceModelId);
        try (var session=driver.session()) {
            return session.executeRead(tx -> {
                var rows=tx.run("""
                        MATCH (e:IntegrationExternalBinding {system:$system,targetInstanceId:$target,
                            resourceKind:$resourceKind,resourceId:$resourceId})
                        MATCH (c:IntegrationCanonicalObject {canonicalId:e.canonicalId})
                        MATCH (b:IntegrationModelBinding {instanceId:$instance,sourceModelId:$sourceModel,canonicalId:e.canonicalId})
                        WHERE b.entityKind=CASE WHEN $resourceKind='CARD' THEN 'ELEMENT' ELSE 'RELATIONSHIP' END
                        RETURN properties(b) AS binding,c.kind AS kind
                        """,params);
                if (!rows.hasNext()) return Optional.empty();
                var row=rows.single(); return Optional.of(readBinding(row,row.get("kind").asString()));
            });
        }
    }

    private Optional<ReservedImport> findBinding(TransactionContext tx, Map<String, Object> params) {
        var rows = tx.run("""
                MATCH (b:IntegrationModelBinding {instanceId:$instance,sourceModelId:$sourceModel,
                    entityKind:$entityKind,entityId:$entityId})
                MATCH (c:IntegrationCanonicalObject {canonicalId:b.canonicalId})
                RETURN properties(b) AS binding,c.kind AS kind
                """, params);
        if (!rows.hasNext()) return Optional.empty();
        var row = rows.single();
        return Optional.of(readBinding(row, row.get("kind").asString()));
    }

    @Override public ReservedImport confirmImport(ModelElementKey key, String opBatchId) {
        requireSchema();
        required(opBatchId);
        var params = modelParams(key); params.put("batch", opBatchId);
        String label = key.entityKind() == EntityKind.ELEMENT ? "Element" : "Relationship";
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var rows = tx.run("""
                        MATCH (b:IntegrationModelBinding {instanceId:$instance,sourceModelId:$sourceModel,
                            entityKind:$entityKind,entityId:$entityId,opBatchId:$batch})
                        SET b.fence=coalesce(b.fence,0)+1
                        WITH b WHERE b.state IN ['PREPARED','ACTIVE']
                        MATCH (c:Commit {modelId:b.sourceModelId,opBatchId:b.opBatchId})
                        MATCH (n:%s {modelId:b.sourceModelId,id:b.entityId})
                        WHERE n.archimateType=b.archimateType
                        SET b.state='ACTIVE', b.acceptedRevision=c.revisionTo
                        RETURN b
                        """.formatted(label), params);
                if (!rows.hasNext()) throw new IllegalStateException("Import lacks its durable receipt or matching semantic entity");
                rows.consume();
                return findBinding(tx, params).orElseThrow();
            });
        }
    }

    @Override public StateDocument save(StateDocument document) {
        requireSchema();
        Map<String, Object> params = documentParams(document.instanceId(), document.kind(), document.id());
        params.put("expected", document.version()); params.put("data", document.data().toString());
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var rows = tx.run("""
                        MERGE (d:IntegrationStateDocument {instanceId:$instance,kind:$kind,id:$id})
                        ON CREATE SET d.version=0
                        SET d.fence=coalesce(d.fence,0)+1
                        WITH d WHERE d.version=$expected
                        SET d.version=d.version+1,d.dataJson=$data
                        RETURN d.version AS version
                        """, params);
                if (!rows.hasNext()) throw new IllegalStateException("Integration state version conflict");
                return new StateDocument(document.instanceId(), document.kind(), document.id(),
                        rows.single().get("version").asLong(), document.data());
            });
        }
    }

    @Override public Optional<StateDocument> load(String instanceId, DocumentKind kind, String id) {
        var params = documentParams(instanceId, kind, id);
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var rows = tx.run("MATCH (d:IntegrationStateDocument {instanceId:$instance,kind:$kind,id:$id}) "
                        + "RETURN d.version AS version,d.dataJson AS data", params);
                if (!rows.hasNext()) return Optional.empty();
                var row = rows.single();
                try { return Optional.of(new StateDocument(instanceId, kind, id,
                        row.get("version").asLong(), json.readTree(row.get("data").asString()))); }
                catch (IOException error) { throw new IllegalStateException("Corrupt integration state document"); }
            });
        }
    }

    private static ReservedImport readBinding(Record row, String kind) {
        var b = row.get("binding");
        return new ReservedImport(new ModelElementKey(b.get("instanceId").asString(), b.get("sourceModelId").asString(),
                EntityKind.valueOf(b.get("entityKind").asString()), b.get("entityId").asString()),
                new CanonicalObject(b.get("canonicalId").asString(), kind),
                new ExternalResourceKey(b.get("system").asString(), b.get("targetInstanceId").asString(),
                        ResourceKind.valueOf(b.get("resourceKind").asString()), b.get("resourceId").asString()),
                b.get("archimateType").asString(), b.get("mappingVersion").asString(), b.get("opBatchId").asString(),
                BindingState.valueOf(b.get("state").asString()), b.get("acceptedRevision").asLong(), b.get("bindingMode").asString("CREATE"));
    }

    private static Map<String, Object> modelParams(ModelElementKey key) {
        return new HashMap<>(Map.of("instance",key.instanceId(),"sourceModel",key.sourceModelId(),
                "entityKind",key.entityKind().name(),"entityId",key.entityId()));
    }
    private static Map<String, Object> externalParams(ExternalResourceKey key) {
        return new HashMap<>(Map.of("system",key.system(),"target",key.targetInstanceId(),
                "resourceKind",key.resourceKind().name(),"resourceId",key.resourceId()));
    }
    private static Map<String, Object> documentParams(String instance, DocumentKind kind, String id) {
        required(instance); required(id);
        if (kind == null) throw new IllegalArgumentException("State kind is required");
        return new HashMap<>(Map.of("instance",instance,"kind",kind.name(),"id",id));
    }
    private void requireSchema() {
        if (!schemaInstalled) throw new IllegalStateException("Install the integration uniqueness constraints before writing metadata");
    }
    @Override public void close() { if (ownsDriver) driver.close(); }
}
