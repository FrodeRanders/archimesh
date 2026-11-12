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
import java.util.Set;
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
        for (String resource : java.util.List.of("schema-v1.cypher","schema-v2.cypher","schema-v3.cypher","schema-v4.cypher")) {
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

    @Override public void migrateMappings(String instance,String model,com.fasterxml.jackson.databind.JsonNode migrations) {
        requireSchema(); if (!migrations.isArray() || migrations.size()>500) throw new IllegalArgumentException("Invalid mapping migration");
        try (var session=driver.session()) { session.executeWrite(tx -> {
            for (var migration:migrations) {
                String entity=required(migration.path("entityId").asText()),canonical=required(migration.path("canonicalId").asText()),from=required(migration.path("from").asText()),to=required(migration.path("to").asText()),target=required(migration.path("targetInstanceId").asText());
                var p=Map.<String,Object>of("instance",required(instance),"model",required(model),"entity",entity,"canonical",canonical,"from",from,"to",to);
                var records=tx.run("MATCH (n {instanceId:$instance,sourceModelId:$model,entityId:$entity,canonicalId:$canonical}) WHERE n:IntegrationModelBinding OR n:IntegrationPublicationIdentity RETURN n.mappingVersion AS mapping",p).list();
                if (records.isEmpty() || records.stream().anyMatch(r -> !Set.of(from,to).contains(r.get("mapping").asString()))) throw new PublicationModel.Failure(409,"MAPPING_MIGRATION_STALE");
                tx.run("MATCH (n {instanceId:$instance,sourceModelId:$model,entityId:$entity,canonicalId:$canonical}) WHERE n:IntegrationModelBinding OR n:IntegrationPublicationIdentity SET n.mappingVersion=$to",p).consume();
                var centralLane=tx.run("MATCH (d:IntegrationStateDocument {instanceId:$instance,kind:'CENTRAL_LANE',id:$id}) RETURN d.dataJson AS data",Map.of("instance",instance,"id",CentralEditModel.laneId(target,canonical)));
                try { if (centralLane.hasNext() && json.readTree(centralLane.single().get("data").asString()).has("inFlight")) throw new PublicationModel.Failure(409,"MIGRATION_UNCERTAIN_OUTCOME"); }
                catch (java.io.IOException corrupt) { throw new PublicationModel.Failure(409,"CORRUPT_INTEGRATION_METADATA"); }
                String id=PublicationJson.key("pub-resource:",target,canonical);
                var resources=tx.run("MATCH (d:IntegrationStateDocument {instanceId:$instance,kind:'PUBLICATION_RESOURCE',id:$id}) SET d.fence=coalesce(d.fence,0)+1 RETURN d.dataJson AS data",Map.of("instance",instance,"id",id));
                if (resources.hasNext()) {
                    try {
                        var d=(com.fasterxml.jackson.databind.node.ObjectNode)json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(resources.single().get("data").asString());
                        if (d.has("inFlight")) throw new PublicationModel.Failure(409,"MIGRATION_UNCERTAIN_OUTCOME");
                        if (!Set.of(from,to).contains(d.path("mappingVersion").asText())) throw new PublicationModel.Failure(409,"MAPPING_MIGRATION_STALE");
                        if (to.equals(d.path("mappingVersion").asText())) continue;
                        d.put("mappingVersion",to).put("state","CONFLICT").put("code","MAPPING_BASELINE_REVIEW_REQUIRED"); d.remove("baseline");
                        tx.run("MATCH (d:IntegrationStateDocument {instanceId:$instance,kind:'PUBLICATION_RESOURCE',id:$id}) SET d.dataJson=$data,d.version=d.version+1,d.operationState='CONFLICT',d.updatedAt=$now",Map.of("instance",instance,"id",id,"data",d.toString(),"now",System.currentTimeMillis())).consume();
                    } catch (java.io.IOException corrupt) { throw new PublicationModel.Failure(409,"CORRUPT_INTEGRATION_METADATA"); }
                }
            }
            return null;
        }); }
    }

    @Override public org.gautelis.federation.IdentityRegistry identities(String instance) {
        requireSchema(); return new Neo4jFederationRegistry(driver,json,instance);
    }
    @Override public org.gautelis.federation.TargetMutationLedger targetMutations(String instance) {
        requireSchema(); return new Neo4jTargetMutationLedger(driver,json,instance);
    }
    @Override public com.fasterxml.jackson.databind.JsonNode previewIdentityMigration(String instance,String canonical,String semantic) {
        requireSchema(); return new IdentityMigration(driver,json).preview(instance,canonical,semantic);
    }
    @Override public void migrateIdentity(String instance,String canonical,String semantic,String digest,String actor) {
        requireSchema(); new IdentityMigration(driver,json).apply(instance,canonical,semantic,digest,actor);
    }

    @Override public com.fasterxml.jackson.databind.JsonNode statistics(String instance) {
        try (var session=driver.session()) {
            return session.executeRead(tx -> {
                var result=json.createObjectNode(); var documents=result.putArray("documents"); var bindings=result.putArray("bindings");
                tx.run("MATCH (d:IntegrationStateDocument {instanceId:$instance}) RETURN d.kind AS kind,coalesce(d.operationState,'UNRECORDED') AS state,count(*) AS count,min(CASE WHEN d.verifiedAt>0 THEN d.verifiedAt END) AS oldestVerifiedAt,min(CASE WHEN d.nextAttemptAt>0 THEN d.nextAttemptAt END) AS nextRetryAt",Map.of("instance",instance)).forEachRemaining(row -> {
                    var item=documents.addObject().put("kind",row.get("kind").asString()).put("state",row.get("state").asString()).put("count",row.get("count").asLong());
                    if (!row.get("oldestVerifiedAt").isNull()) item.put("oldestVerifiedAt",row.get("oldestVerifiedAt").asLong());
                    if (!row.get("nextRetryAt").isNull()) item.put("nextRetryAt",row.get("nextRetryAt").asLong());
                });
                tx.run("MATCH (b:IntegrationModelBinding {instanceId:$instance}) RETURN b.state AS state,count(*) AS count",Map.of("instance",instance)).forEachRemaining(row -> bindings.addObject().put("state",row.get("state").asString()).put("count",row.get("count").asLong()));
                return result;
            });
        }
    }

    @Override public java.util.List<IntegrationArchive.Node> archive(String instance) { requireSchema(); return new IntegrationArchive(driver,json).capture(instance); }
    @Override public void restoreArchive(String instance,java.util.List<IntegrationArchive.Node> nodes) { requireSchema(); new IntegrationArchive(driver,json).restore(instance,nodes); }

    @Override public String modelGeneration(String model) {
        try (var session=driver.session()) {
            var rows=session.run("MATCH (m:Model {modelId:$model,registered:true}) RETURN m.integrationGeneration AS generation",Map.of("model",required(model)));
            if (!rows.hasNext() || rows.peek().get("generation").isNull()) throw new PublicationModel.Failure(409,"MODEL_GENERATION_UNAVAILABLE");
            return rows.single().get("generation").asString();
        }
    }
    @Override public boolean hasModelMetadata(String instance,String model) {
        try (var session=driver.session()) {
            return session.run("MATCH (n {instanceId:$instance,sourceModelId:$model}) WHERE n:IntegrationModelBinding OR n:IntegrationPublicationIdentity OR n:IntegrationIdentityClaim RETURN n LIMIT 1",Map.of("instance",required(instance),"model",required(model))).hasNext();
        }
    }

    @Override public ReservedImport reserveImport(ImportReservation request) {
        requireSchema();
        var params = externalParams(request.external());
        params.put("candidate", request.canonicalCandidate().canonicalId());
        params.put("kind", request.canonicalCandidate().kind());
        params.put("externalType",request.remoteType());
        params.putAll(modelParams(request.model()));
        params.put("archimateType", request.archimateType());
        params.put("mappingVersion", request.mappingVersion());
        params.put("batch", request.opBatchId());
        params.put("bindingMode", request.bindingMode());
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                // Fence this external resource before observing its canonical assignment.
                var external = tx.run("""
                        MERGE (e:IntegrationExternalBinding {system:$system, targetInstanceId:$target,
                            resourceKind:$resourceKind, resourceId:$resourceId})
                        ON CREATE SET e.canonicalId=$candidate,e.externalType=$externalType,e.nativeId=$nativeId
                        SET e.fence=coalesce(e.fence,0)+1
                        RETURN e.canonicalId AS canonical,properties(e) AS external
                        """, params).single();
                Neo4jFederationRegistry.checkNative(external.get("external"),request.external());
                String canonical=external.get("canonical").asString();
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
                        RETURN coalesce(c.semanticKind,c.kind) AS kind,c.kind AS legacyKind,c.semanticKind AS semantic
                        """, params).single();
                if (!request.canonicalCandidate().kind().equals(object.get("kind").asString()))
                    throw new IllegalStateException("Canonical kind conflict");
                if (!object.get("semantic").isNull() && external.get("external").get("identityProofId").isNull())
                    throw new PublicationModel.Failure(409,"IDENTITY_PROOF_REQUIRED");
                String externalType=external.get("external").get("externalType").asString(object.get("legacyKind").asString());
                if (!request.remoteType().equals(externalType)) throw new IllegalStateException("External type conflict");
                var binding = tx.run("""
                        MERGE (b:IntegrationModelBinding {instanceId:$instance, sourceModelId:$sourceModel,
                            entityKind:$entityKind, canonicalId:$canonical})
                        ON CREATE SET b.entityId=$entityId, b.archimateType=$archimateType,
                            b.mappingVersion=$mappingVersion, b.opBatchId=$batch, b.state='PREPARED', b.bindingMode=$bindingMode,
                            b.acceptedRevision=0, b.system=$system, b.targetInstanceId=$target,
                            b.resourceKind=$resourceKind, b.resourceId=$resourceId,b.nativeId=$nativeId,b.externalType=$externalType
                        RETURN properties(b) AS binding
                        """, params).single();
                ReservedImport result = readBinding(binding, request.canonicalCandidate().kind(),externalType);
                if (!result.archimateType().equals(request.archimateType())
                        || result.external().equals(request.external()) && !result.mappingVersion().equals(request.mappingVersion()))
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
                        RETURN c.canonicalId AS canonical,coalesce(c.semanticKind,c.kind) AS kind,properties(e) AS external
                        """, externalParams(key));
                if (!rows.hasNext()) return Optional.empty();
                var row = rows.single();
                Neo4jFederationRegistry.checkNative(row.get("external"),key);
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
                var types=tx.run("MATCH (c:IntegrationCanonicalObject {canonicalId:$id}) RETURN coalesce(c.semanticKind,c.kind) AS kind,c.kind AS legacyKind",Map.of("id",canonical)).single();
                String kind=types.get("kind").asString(); String remote=chosen.get("externalType").asString(types.get("legacyKind").asString());
                return Optional.of(new PublicationIdentity(key,new CanonicalObject(canonical,kind),chosen.get("archimateType").asString(),chosen.get("mappingVersion").asString(),kind.equals(remote) ? null : remote));
            });
        }
    }
    @Override public PublicationIdentity reservePublicationIdentity(PublicationIdentity identity) {
        requireSchema(); var params=modelParams(identity.model());
        params.put("candidate",identity.canonical().canonicalId()); params.put("kind",identity.canonical().kind()); params.put("externalType",identity.remoteType());
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
                String kind=tx.run("MERGE (c:IntegrationCanonicalObject {canonicalId:$canonical}) ON CREATE SET c.kind=$kind RETURN coalesce(c.semanticKind,c.kind) AS kind",params).single().get("kind").asString();
                if (!identity.canonical().kind().equals(kind)) throw new IllegalStateException("Canonical type conflict");
                var publication=tx.run("""
                        MERGE (p:IntegrationPublicationIdentity {instanceId:$instance,sourceModelId:$sourceModel,entityKind:$entityKind,entityId:$entityId})
                        ON CREATE SET p.canonicalId=$canonical,p.archimateType=$type,p.mappingVersion=$mapping,p.externalType=$externalType
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
                var rows=tx.run("MATCH (e:IntegrationExternalBinding {canonicalId:$canonical,system:$system,targetInstanceId:$target,resourceKind:$kind}) RETURN coalesce(e.nativeId,e.resourceId) AS id",
                        Map.of("canonical",canonicalId,"system",system,"target",target,"kind",kind.name()));
                return rows.hasNext() ? Optional.of(new ExternalResourceKey(system,target,kind,rows.single().get("id").asString())) : Optional.empty();
            });
        }
    }
    @Override public void recordPublishedIdentity(PublicationIdentity identity,ExternalResourceKey external,long revision,String jobId) {
        requireSchema(); if (revision<1) throw new IllegalArgumentException("Publication requires accepted state");
        var params=modelParams(identity.model()); params.putAll(externalParams(external));
        params.put("canonical",identity.canonical().canonicalId()); params.put("type",identity.archimateType());
        params.put("mapping",identity.mappingVersion()); params.put("externalType",identity.remoteType()); params.put("revision",revision); params.put("batch",required(jobId));
        try (var session=driver.session()) {
            session.executeWrite(tx -> {
                String canonical=tx.run("""
                        MERGE (e:IntegrationExternalBinding {system:$system,targetInstanceId:$target,resourceKind:$resourceKind,resourceId:$resourceId})
                        ON CREATE SET e.canonicalId=$canonical,e.externalType=$externalType,e.nativeId=$nativeId
                        SET e.fence=coalesce(e.fence,0)+1 RETURN e.canonicalId AS canonical
                        """,params).single().get("canonical").asString();
                if (!canonical.equals(identity.canonical().canonicalId())) throw new IllegalStateException("External identity conflict");
                var registered=tx.run("MATCH (c:IntegrationCanonicalObject {canonicalId:$canonical}) RETURN c.semanticKind AS semantic,c.registryInstanceId AS owner",params).single();
                if (!registered.get("semantic").isNull()) {
                    if (!identity.model().instanceId().equals(registered.get("owner").asString(null))) throw new PublicationModel.Failure(409,"IDENTITY_REGISTRY_OWNER_CONFLICT");
                    String proof="identity-publish:"+IntegrationIdentity.sha256(jobId+":"+external.targetInstanceId()+":"+canonical);
                    var data=json.createObjectNode().put("canonicalId",canonical).put("mode","PUBLISH").put("jobId",jobId)
                            .put("sourceModelId",identity.model().sourceModelId()).put("sourceRevision",revision)
                            .put("externalType",identity.remoteType()).put("resourceId",external.resourceId()).put("targetInstanceId",external.targetInstanceId());
                    params.put("proof",proof); params.put("proofData",data.toString());
                    tx.run("MERGE (d:IntegrationStateDocument {instanceId:$instance,kind:'IDENTITY_PROOF',id:$proof}) ON CREATE SET d.version=1,d.dataJson=$proofData",params).consume();
                    tx.run("MATCH (e:IntegrationExternalBinding {system:$system,targetInstanceId:$target,resourceKind:$resourceKind,resourceId:$resourceId}) SET e.identityProofId=coalesce(e.identityProofId,$proof)",params).consume();
                }
                var binding=tx.run("""
                        MERGE (b:IntegrationModelBinding {instanceId:$instance,sourceModelId:$sourceModel,entityKind:$entityKind,entityId:$entityId})
                        ON CREATE SET b.canonicalId=$canonical,b.archimateType=$type,b.mappingVersion=$mapping,
                            b.system=$system,b.targetInstanceId=$target,b.resourceKind=$resourceKind,b.resourceId=$resourceId,b.nativeId=$nativeId,b.externalType=$externalType,
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
                        WHERE b.entityKind=CASE WHEN $resourceKind IN ['CARD','ENTITY'] THEN 'ELEMENT' ELSE 'RELATIONSHIP' END
                            AND ($nativeId IS NULL OR e.nativeId=$nativeId)
                        RETURN properties(b) AS binding,coalesce(c.semanticKind,c.kind) AS kind,c.kind AS legacyKind
                        """,params);
                if (!rows.hasNext()) return Optional.empty();
                var row=rows.single(); return Optional.of(readBinding(row,row.get("kind").asString(),row.get("legacyKind").asString()));
            });
        }
    }

    private Optional<ReservedImport> findBinding(TransactionContext tx, Map<String, Object> params) {
        var rows = tx.run("""
                MATCH (b:IntegrationModelBinding {instanceId:$instance,sourceModelId:$sourceModel,
                    entityKind:$entityKind,entityId:$entityId})
                MATCH (c:IntegrationCanonicalObject {canonicalId:b.canonicalId})
                RETURN properties(b) AS binding,coalesce(c.semanticKind,c.kind) AS kind,c.kind AS legacyKind
                """, params);
        if (!rows.hasNext()) return Optional.empty();
        var row = rows.single();
        return Optional.of(readBinding(row, row.get("kind").asString(),row.get("legacyKind").asString()));
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
                    RETURN properties(b) AS binding,coalesce(c.semanticKind,c.kind) AS kind,c.kind AS legacyKind ORDER BY cursor LIMIT $limit
                    """,Map.of("instance",instance,"target",target,"model",model==null ? "" : model,"after",after,"limit",limit))
                    .list(row -> readBinding(row,row.get("kind").asString(),row.get("legacyKind").asString())));
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
                    params.put("state",document.data().path("state").asText("UNRECORDED")); params.put("verifiedAt",document.data().path("verifiedAt").asLong());
                    params.put("nextAttemptAt",document.data().path("nextAttemptAt").asLong()); params.put("updatedAt",System.currentTimeMillis());
                    var rows=tx.run("""
                            MERGE (d:IntegrationStateDocument {instanceId:$instance,kind:$kind,id:$id})
                            ON CREATE SET d.version=0
                            SET d.fence=coalesce(d.fence,0)+1
                            WITH d WHERE d.version=$expected
                            SET d.version=d.version+1,d.dataJson=$data,d.operationState=$state,d.verifiedAt=$verifiedAt,d.nextAttemptAt=$nextAttemptAt,d.updatedAt=$updatedAt
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

    private static ReservedImport readBinding(Record row, String kind,String legacyType) {
        var b = row.get("binding");
        return new ReservedImport(new ModelElementKey(b.get("instanceId").asString(), b.get("sourceModelId").asString(),
                EntityKind.valueOf(b.get("entityKind").asString()), b.get("entityId").asString()),
                new CanonicalObject(b.get("canonicalId").asString(), kind),
                new ExternalResourceKey(b.get("system").asString(), b.get("targetInstanceId").asString(),
                        ResourceKind.valueOf(b.get("resourceKind").asString()), b.get("nativeId").asString(b.get("resourceId").asString())),
                b.get("archimateType").asString(), b.get("mappingVersion").asString(), b.get("opBatchId").asString(),
                BindingState.valueOf(b.get("state").asString()), b.get("acceptedRevision").asLong(), b.get("bindingMode").asString("CREATE"),kind.equals(b.get("externalType").asString(legacyType)) ? null : b.get("externalType").asString(legacyType));
    }

    private static Map<String, Object> modelParams(ModelElementKey key) {
        return new HashMap<>(Map.of("instance",key.instanceId(),"sourceModel",key.sourceModelId(),
                "entityKind",key.entityKind().name(),"entityId",key.entityId()));
    }
    private static Map<String, Object> externalParams(ExternalResourceKey key) {
        return Neo4jFederationRegistry.parameters(key);
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
