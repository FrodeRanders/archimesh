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
        for (String resource : java.util.List.of("schema-v1.cypher","schema-v2.cypher")) {
            try (var input=getClass().getResourceAsStream("/integrations/"+resource)) {
                if (input==null) throw new IllegalStateException("Missing integration schema");
                String schema=new String(input.readAllBytes(),StandardCharsets.UTF_8).replaceAll("(?m)^\\s*//.*$", "");
                try (var session=driver.session()) {
                    for (String statement:schema.split(";")) if (!statement.isBlank()) session.run(statement).consume();
                }
            } catch (IOException error) { throw new IllegalStateException("Cannot read integration schema",error); }
        }
        schemaInstalled=true;
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
                var claim=tx.run("""
                        MERGE (c:IntegrationIdentityClaim {instanceId:$instance,sourceModelId:$sourceModel,entityKind:$entityKind,entityId:$entityId})
                        ON CREATE SET c.canonicalId=$canonical
                        SET c.fence=coalesce(c.fence,0)+1 RETURN c.canonicalId AS canonical
                        """,params).single();
                if (!canonical.equals(claim.get("canonical").asString())) throw new IllegalStateException("Model identity is already claimed by another canonical object");
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

    @Override public Optional<PublicationIdentity> findPublicationIdentity(ModelElementKey key) {
        try (var session=driver.session()) {
            return session.executeRead(tx -> {
                var rows=tx.run("""
                        OPTIONAL MATCH (b:IntegrationModelBinding {instanceId:$instance,sourceModelId:$sourceModel,entityKind:$entityKind,entityId:$entityId})
                        OPTIONAL MATCH (p:IntegrationPublicationIdentity {instanceId:$instance,sourceModelId:$sourceModel,entityKind:$entityKind,entityId:$entityId})
                        RETURN properties(b) AS binding,properties(p) AS publication
                        """,modelParams(key)).single();
                var b=rows.get("binding"); var p=rows.get("publication");
                if (b.isNull() && p.isNull()) return Optional.empty();
                if (!b.isNull() && !"ACTIVE".equals(b.get("state").asString())) throw new IllegalStateException("Import binding is not accepted");
                if (!b.isNull() && !p.isNull() && !b.get("canonicalId").equals(p.get("canonicalId"))) throw new IllegalStateException("Conflicting publication identity");
                var chosen=b.isNull() ? p : b;
                String canonical=chosen.get("canonicalId").asString();
                var kind=tx.run("MATCH (c:IntegrationCanonicalObject {canonicalId:$id}) RETURN c.kind AS kind",Map.of("id",canonical)).single().get("kind").asString();
                return Optional.of(new PublicationIdentity(key,new CanonicalObject(canonical,kind),chosen.get("archimateType").asString(),chosen.get("mappingVersion").asString()));
            });
        }
    }
    @Override public PublicationIdentity reservePublicationIdentity(PublicationIdentity identity) {
        requireSchema(); var params=modelParams(identity.model());
        params.put("candidate",identity.canonical().canonicalId()); params.put("kind",identity.canonical().kind());
        params.put("type",required(identity.archimateType())); params.put("mapping",required(identity.mappingVersion()));
        try (var session=driver.session()) {
            return session.executeWrite(tx -> {
                var row=tx.run("""
                        OPTIONAL MATCH (b:IntegrationModelBinding {instanceId:$instance,sourceModelId:$sourceModel,entityKind:$entityKind,entityId:$entityId})
                        WITH b WHERE b IS NULL OR b.state='ACTIVE'
                        MERGE (claim:IntegrationIdentityClaim {instanceId:$instance,sourceModelId:$sourceModel,entityKind:$entityKind,entityId:$entityId})
                        ON CREATE SET claim.canonicalId=coalesce(b.canonicalId,$candidate)
                        SET claim.fence=coalesce(claim.fence,0)+1
                        RETURN claim.canonicalId AS canonical,b.canonicalId AS existing
                        """,params);
                if (!row.hasNext()) throw new IllegalStateException("Unaccepted import binding");
                var claim=row.single(); String canonical=claim.get("canonical").asString();
                if (!identity.canonical().canonicalId().equals(canonical)) throw new IllegalStateException("Publication identity changed; preview again");
                params.put("canonical",canonical);
                String kind=tx.run("MERGE (c:IntegrationCanonicalObject {canonicalId:$canonical}) ON CREATE SET c.kind=$kind RETURN c.kind AS kind",params).single().get("kind").asString();
                if (!identity.canonical().kind().equals(kind)) throw new IllegalStateException("Canonical type conflict");
                var publication=tx.run("""
                        MERGE (p:IntegrationPublicationIdentity {instanceId:$instance,sourceModelId:$sourceModel,entityKind:$entityKind,entityId:$entityId})
                        ON CREATE SET p.canonicalId=$canonical,p.archimateType=$type,p.mappingVersion=$mapping
                        RETURN p.canonicalId AS canonical,p.archimateType AS type,p.mappingVersion AS mapping
                        """,params).single();
                if (!canonical.equals(publication.get("canonical").asString()) || !identity.archimateType().equals(publication.get("type").asString())
                        || !identity.mappingVersion().equals(publication.get("mapping").asString())) throw new IllegalStateException("Publication mapping migration required");
                return identity;
            });
        }
    }
    @Override public Optional<ExternalResourceKey> findExternal(String canonicalId,String system,String target,ResourceKind kind) {
        required(canonicalId); required(system); required(target); java.util.Objects.requireNonNull(kind);
        try (var session=driver.session()) {
            return session.executeRead(tx -> {
                var rows=tx.run("MATCH (e:IntegrationExternalBinding {canonicalId:$canonical,system:$system,targetInstanceId:$target,resourceKind:$kind}) RETURN e.resourceId AS id",
                        Map.of("canonical",canonicalId,"system",system,"target",target,"kind",kind.name()));
                return rows.hasNext() ? Optional.of(new ExternalResourceKey(system,target,kind,rows.single().get("id").asString())) : Optional.empty();
            });
        }
    }
    @Override public void recordPublishedIdentity(PublicationIdentity identity,ExternalResourceKey external,long revision,String jobId) {
        requireSchema(); if (revision<1) throw new IllegalArgumentException("Publication requires accepted state");
        var params=modelParams(identity.model()); params.putAll(externalParams(external));
        params.put("canonical",identity.canonical().canonicalId()); params.put("type",identity.archimateType());
        params.put("mapping",identity.mappingVersion()); params.put("revision",revision); params.put("batch",required(jobId));
        try (var session=driver.session()) {
            session.executeWrite(tx -> {
                String canonical=tx.run("""
                        MERGE (e:IntegrationExternalBinding {system:$system,targetInstanceId:$target,resourceKind:$resourceKind,resourceId:$resourceId})
                        ON CREATE SET e.canonicalId=$canonical
                        SET e.fence=coalesce(e.fence,0)+1 RETURN e.canonicalId AS canonical
                        """,params).single().get("canonical").asString();
                if (!canonical.equals(identity.canonical().canonicalId())) throw new IllegalStateException("External identity conflict");
                var binding=tx.run("""
                        MERGE (b:IntegrationModelBinding {instanceId:$instance,sourceModelId:$sourceModel,entityKind:$entityKind,entityId:$entityId})
                        ON CREATE SET b.canonicalId=$canonical,b.archimateType=$type,b.mappingVersion=$mapping,
                            b.system=$system,b.targetInstanceId=$target,b.resourceKind=$resourceKind,b.resourceId=$resourceId,
                            b.state='ACTIVE',b.bindingMode='PUBLISHED',b.acceptedRevision=$revision,b.opBatchId=$batch
                        RETURN b.canonicalId AS canonical
                        """,params).single();
                if (!canonical.equals(binding.get("canonical").asString())) throw new IllegalStateException("Model identity conflict");
                return null;
            });
        }
    }
    @Override public java.util.List<StateDocument> list(String instanceId,DocumentKind kind,String prefix,String after,int limit) {
        required(instanceId); java.util.Objects.requireNonNull(kind);
        if (prefix==null || after==null || limit<1 || limit>100) throw new IllegalArgumentException("Invalid metadata scan bounds");
        try (var session=driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (d:IntegrationStateDocument {instanceId:$instance,kind:$kind})
                    WHERE d.id STARTS WITH $prefix AND d.id>$after
                    RETURN d.id AS id,d.version AS version,d.dataJson AS data ORDER BY d.id LIMIT $limit
                    """,Map.of("instance",instanceId,"kind",kind.name(),"prefix",prefix,"after",after,"limit",limit)).list(row -> {
                        try { return new StateDocument(instanceId,kind,row.get("id").asString(),row.get("version").asLong(),json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(row.get("data").asString())); }
                        catch (IOException corrupt) { throw new IllegalStateException("Corrupt integration state document"); }
                    }));
        }
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

    @Override public java.util.List<ReservedImport> bindings(String instance,String target,String model,String after,int limit) {
        required(instance); required(target);
        if (after==null || limit<1 || limit>100) throw new IllegalArgumentException("Invalid binding scan bounds");
        try (var session=driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (b:IntegrationModelBinding {instanceId:$instance,targetInstanceId:$target,entityKind:'ELEMENT'})
                    MATCH (c:IntegrationCanonicalObject {canonicalId:b.canonicalId})
                    WITH b,c,toString(size(b.sourceModelId))+':'+b.sourceModelId+':'+b.entityId AS cursor
                    WHERE ($model='' OR b.sourceModelId=$model) AND b.state<>'PREPARED' AND cursor>$after
                    RETURN properties(b) AS binding,c.kind AS kind ORDER BY cursor LIMIT $limit
                    """,Map.of("instance",instance,"target",target,"model",model==null ? "" : model,"after",after,"limit",limit))
                    .list(row -> readBinding(row,row.get("kind").asString())));
        }
    }
    @Override public void orphanBinding(ModelElementKey key) {
        requireSchema();
        try (var session=driver.session()) {
            session.executeWrite(tx -> { tx.run("""
                    MATCH (b:IntegrationModelBinding {instanceId:$instance,sourceModelId:$sourceModel,entityKind:$entityKind,entityId:$entityId})
                    SET b.state='ORPHANED'
                    """,modelParams(key)).consume(); return null; });
        }
    }

    @Override public StateDocument save(StateDocument document) { return saveAtomically(java.util.List.of(document)).getFirst(); }

    @Override public java.util.List<StateDocument> saveAtomically(java.util.List<StateDocument> documents) {
        requireSchema();
        if (documents==null || documents.isEmpty() || documents.size()>10) throw new IllegalArgumentException("Invalid metadata transaction size");
        var keys=new java.util.HashSet<java.util.List<Object>>();
        for (var document:documents) if (!keys.add(java.util.List.of(document.instanceId(),document.kind(),document.id()))) throw new IllegalArgumentException("Duplicate metadata transaction key");
        try (var session=driver.session()) {
            return session.executeWrite(tx -> {
                var saved=new java.util.ArrayList<StateDocument>();
                for (var document:documents) {
                    var params=documentParams(document.instanceId(),document.kind(),document.id());
                    params.put("expected",document.version()); params.put("data",document.data().toString());
                    var rows=tx.run("""
                            MERGE (d:IntegrationStateDocument {instanceId:$instance,kind:$kind,id:$id})
                            ON CREATE SET d.version=0
                            SET d.fence=coalesce(d.fence,0)+1
                            WITH d WHERE d.version=$expected
                            SET d.version=d.version+1,d.dataJson=$data
                            RETURN d.version AS version
                            """,params);
                    if (!rows.hasNext()) throw new IllegalStateException("Integration state version conflict");
                    saved.add(new StateDocument(document.instanceId(),document.kind(),document.id(),rows.single().get("version").asLong(),document.data()));
                }
                return java.util.List.copyOf(saved);
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
                        row.get("version").asLong(), json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(row.get("data").asString()))); }
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
