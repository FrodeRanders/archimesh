package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.gautelis.archimesh.model.AdminCompactionStatus;
import org.gautelis.archimesh.model.ModelAccessControl;
import org.gautelis.archimesh.model.ModelCatalogEntry;
import org.gautelis.archimesh.model.ModelTagEntry;
import org.gautelis.archimesh.model.RevisionRange;
import org.gautelis.archimesh.model.ModelCheckpoint;
import org.gautelis.archimesh.model.BatchReceipt;
import org.gautelis.archimesh.model.AdminModelExport;
import org.gautelis.archimesh.service.Neo4jRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Neo4j-backed implementation of {@link Neo4jRepository}. Manages op-log persistence,
 * materialized graph state, model metadata, tags, snapshots, and administrative
 * operations using Cypher queries over the Bolt protocol.
 */
@ApplicationScoped
public class Neo4jRepositoryImpl implements Neo4jRepository {
    private static final Logger LOG = LoggerFactory.getLogger(Neo4jRepositoryImpl.class);

    @ConfigProperty(name = "app.neo4j.uri", defaultValue = "bolt://localhost:7687")
    String uri;

    @ConfigProperty(name = "app.neo4j.username", defaultValue = "neo4j")
    String username;

    @ConfigProperty(name = "app.neo4j.password", defaultValue = "devpassword")
    String password;

    @ConfigProperty(name = "app.compaction.max-batches-per-run", defaultValue = "100")
    int compactionBatchLimit = 100;

    @Inject
    ObjectMapper objectMapper;

    private Driver driver;
    private Neo4jMaterializedStateSupport materializedStateSupport;
    private Neo4jReadSupport readSupport;
    private Neo4jCompactionSupport compactionSupport;
    private Neo4jOpLogSupport opLogSupport;
    private Neo4jCheckpointSupport checkpointSupport;
    private volatile String runtimeLeaseOwner;
    String runtimeLeaseKey = "writer";

    @PostConstruct
    void init() {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        materializedStateSupport = new Neo4jMaterializedStateSupport(objectMapper);
        readSupport = new Neo4jReadSupport(objectMapper);
        opLogSupport = new Neo4jOpLogSupport();
        checkpointSupport = new Neo4jCheckpointSupport(objectMapper);
        compactionSupport = new Neo4jCompactionSupport(checkpointSupport, compactionBatchLimit, this::requireWriterLease);
        LOG.debug("Neo4j repository ready at {}", uri);
    }

    @PreDestroy
    void close() {
        if (driver != null) {
            if (runtimeLeaseOwner != null) releaseServerLease(runtimeLeaseOwner);
            driver.close();
        }
    }

    /**
     * Persists an operation batch to the op-log as a commit node with linked op nodes
     * for the given revision range.
     */
    @Override
    public Optional<RevisionRange> findAcceptedRange(String modelId, String opBatchId) {
        try (var session = requireDriver("findAcceptedRange").session()) {
            return session.executeRead(tx -> {
                var rows = tx.run("MATCH (c:Commit {modelId:$modelId, opBatchId:$batch}) "
                                + "RETURN c.revisionFrom AS start, c.revisionTo AS end",
                        Map.of("modelId", modelId, "batch", opBatchId));
                if (!rows.hasNext()) return Optional.empty();
                var row = rows.single();
                return Optional.of(new RevisionRange(row.get("start").asLong(), row.get("end").asLong()));
            });
        }
    }

    @Override
    public boolean acquireServerLease(String owner, long ttlMs) {
        try (var session = requireDriver("acquireServerLease").session()) {
            session.run("CREATE CONSTRAINT archimesh_runtime_id IF NOT EXISTS FOR (r:ArchimeshRuntime) REQUIRE r.id IS UNIQUE").consume();
            boolean acquired = session.executeWrite(tx -> {
                tx.run("MERGE (r:ArchimeshRuntime {id:$key}) SET r.fence=coalesce(r.fence,0)+1", Map.of("key", runtimeLeaseKey)).consume();
                var rows = tx.run("MATCH (r:ArchimeshRuntime {id:$key}) "
                                + "WHERE r.owner=$owner OR coalesce(r.expiresAt,0) < datetime().epochMillis "
                                + "SET r.owner=$owner, r.expiresAt=datetime().epochMillis+$ttl RETURN r.owner",
                        Map.of("key", runtimeLeaseKey, "owner", owner, "ttl", ttlMs));
                return rows.hasNext();
            });
            if (acquired) runtimeLeaseOwner = owner;
            return acquired;
        }
    }

    @Override
    public void releaseServerLease(String owner) {
        try (var session = requireDriver("releaseServerLease").session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (r:ArchimeshRuntime {id:$key, owner:$owner}) SET r.expiresAt=0 REMOVE r.owner",
                        Map.of("key", runtimeLeaseKey, "owner", owner));
                return null;
            });
        }
    }

    @Override
    public List<String> pendingBroadcastModels(String afterModelId, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Pending model limit must be positive");
        try (var session = requireDriver("pendingBroadcastModels").session()) {
            return session.executeRead(tx -> tx.run("MATCH (c:Commit) WHERE c.published=false "
                            + "WITH DISTINCT c.modelId AS modelId WHERE modelId > $after "
                            + "RETURN modelId ORDER BY modelId LIMIT $limit",
                    Map.of("after",afterModelId == null ? "" : afterModelId,"limit",limit))
                    .list(row -> row.get("modelId").asString()));
        }
    }

    @Override
    public List<JsonNode> pendingBroadcasts(String modelId, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Pending batch limit must be positive");
        try (var session = requireDriver("pendingBroadcasts").session()) {
            return session.executeRead(tx -> tx.run("MATCH (c:Commit {modelId:$modelId,published:false}) "
                            + "RETURN c.opBatchId AS id,c.revisionFrom AS start,c.revisionTo AS end,c.batchJson AS json "
                            + "ORDER BY c.revisionFrom LIMIT $limit",
                    Map.of("modelId",modelId,"limit",limit)).list(row -> {
                try {
                    JsonNode batch = objectMapper.readTree(row.get("json").asString());
                    long start = row.get("start").asLong(), end = row.get("end").asLong();
                    if (batch == null || !batch.isObject() || !modelId.equals(batch.path("modelId").asText())
                            || !row.get("id").asString().equals(batch.path("opBatchId").asText())
                            || start <= 0 || end < start
                            || start != batch.path("assignedRevisionRange").path("from").asLong(-1)
                            || end != batch.path("assignedRevisionRange").path("to").asLong(-1)
                            || !batch.path("ops").isArray() || batch.path("ops").size() != end-start+1) {
                        throw new IllegalArgumentException("Delivery payload identity or revision range mismatch");
                    }
                    return batch;
                } catch (Exception e) {
                    throw new IllegalStateException("Invalid persisted delivery payload for model " + modelId, e);
                }
            }));
        }
    }

    @Override
    public void markBroadcastPublished(String modelId, String opBatchId) {
        try (var session = requireDriver("markBroadcastPublished").session()) {
            session.executeWrite(tx -> {
                requireWriterLease(tx);
                tx.run("MATCH (c:Commit {modelId:$modelId, opBatchId:$batch}) SET c.published=true",
                        Map.of("modelId", modelId, "batch", opBatchId));
                return null;
            });
        }
    }

    @Override
    public void commitBatch(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch) {
        try (var session = requireDriver("commitBatch").session()) {
            session.executeWrite(tx -> {
                requireWriterLease(tx);
                // Acquire the model's write lock before reading its head. A competing server can
                // never commit an overlapping range, even if its in-process cache is stale.
                var row = tx.run("MATCH (m:Model {modelId:$modelId}) "
                                + "SET m.commitFence = coalesce(m.commitFence, 0) + 1 "
                                + "RETURN coalesce(m.headRevision, 0) AS head",
                        Map.of("modelId", modelId)).single();
                var existing = tx.run("MATCH (c:Commit {modelId:$modelId, opBatchId:$batch}) RETURN c",
                        Map.of("modelId", modelId, "batch", opBatchId));
                if (existing.hasNext()) {
                    throw new IllegalStateException("Batch was already committed; retry to retrieve its acceptance");
                }
                if (row.get("head").asLong() != range.from() - 1L) {
                    throw new IllegalStateException("Model head changed; retry submission");
                }
                opLogSupport.appendOpLog(tx, modelId, opBatchId, range, opBatch);
                materializedStateSupport.applyBatch(tx, modelId, opBatch);
                tx.run("MATCH (m:Model {modelId:$modelId}) SET m.headRevision=$head",
                        Map.of("modelId", modelId, "head", range.to()));
                // Persist delivery intent with the commit; restart recovery republishes unacknowledged batches.
                tx.run("MATCH (c:Commit {modelId:$modelId, opBatchId:$batch}) "
                                + "SET c.batchJson=$json, c.published=false",
                        Map.of("modelId", modelId, "batch", opBatchId, "json", opBatch.toString()));
                return null;
            });
        } catch (Exception e) {
            throw writeFailure("commitBatch", modelId, e);
        }
    }

    @Override
    public void appendOpLog(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch) {
        try (var session = requireDriver("appendOpLog").session()) {
            opLogSupport.appendOpLog(session, modelId, opBatchId, range, opBatch);
        } catch (Exception e) {
            LOG.warn("appendOpLog failed for model={} batch={}", modelId, opBatchId, e);
            throw writeFailure("appendOpLog", modelId, e);
        }
    }

    /**
     * Applies an operation batch to the materialized graph state as a single write transaction,
     * creating, updating, or removing element, relationship, view, and tombstone nodes.
     */
    @Override
    public void applyToMaterializedState(String modelId, JsonNode opBatch) {
        int opCount = opBatch.path("ops").isArray() ? opBatch.path("ops").size() : 0;
        LOG.debug("applyToMaterializedState: modelId={} opCount={}", modelId, opCount);
        try (var session = requireDriver("applyToMaterializedState").session()) {
            session.executeWrite(tx -> {
                materializedStateSupport.applyBatch(tx, modelId, opBatch);
                return null;
            });
        } catch (Exception e) {
            LOG.warn("applyToMaterializedState failed for model={}", modelId, e);
            throw writeFailure("applyToMaterializedState", modelId, e);
        }
    }

    /**
     * Merges the model node and sets its current head revision pointer in the graph.
     */
    @Override
    public void updateHeadRevision(String modelId, long headRevision) {
        LOG.debug("updateHeadRevision: modelId={} headRevision={}", modelId, headRevision);
        try (var session = requireDriver("updateHeadRevision").session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        SET m.headRevision = $headRevision
                        """, Map.of("modelId", modelId, "headRevision", headRevision));
                return null;
            });
        } catch (Exception e) {
            LOG.warn("updateHeadRevision failed for model={} head={}", modelId, headRevision, e);
            throw writeFailure("updateHeadRevision", modelId, e);
        }
    }

    @Override
    public long readHeadRevision(String modelId) {
        if (driver == null) {
            return 0L;
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        RETURN coalesce(m.headRevision, 0) AS headRevision
                        """, Map.of("modelId", modelId));
                if (!result.hasNext()) {
                    return 0L;
                }
                return result.next().get("headRevision").asLong(0L);
            });
        } catch (Exception e) {
            LOG.warn("readHeadRevision failed for model={}", modelId, e);
            throw new IllegalStateException("readHeadRevision failed for model " + modelId, e);
        }
    }

    @Override
    public ModelCatalogEntry registerModel(String modelId, String modelName) {
        String normalizedName = normalizeModelName(modelName);
        String now = Instant.now().toString();
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("modelName", normalizedName);
        params.put("now", now);
        try (var session = requireDriver("registerModel").session()) {
            return session.executeWrite(tx -> {
                Record record = tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        ON CREATE SET m.headRevision = coalesce(m.headRevision, 0),
                                      m.adminUsers = coalesce(m.adminUsers, []),
                                      m.writerUsers = coalesce(m.writerUsers, []),
                                      m.readerUsers = coalesce(m.readerUsers, []),
                                      m.createdAt = $now
                        SET m.registered = true,
                            m.modelName = $modelName,
                            m.updatedAt = $now
                        RETURN m.modelId AS modelId,
                               m.modelName AS modelName,
                               coalesce(m.headRevision, 0) AS headRevision
                        """, params).single();
                ensureRootFolders(tx, modelId);
                return toModelCatalogEntry(record);
            });
        } catch (Exception e) {
            LOG.warn("registerModel failed for model={}", modelId, e);
            throw writeFailure("registerModel", modelId, e);
        }
    }

    @Override
    public ModelCatalogEntry renameModel(String modelId, String modelName) {
        String normalizedName = normalizeModelName(modelName);
        String now = Instant.now().toString();
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("modelName", normalizedName);
        params.put("now", now);
        try (var session = requireDriver("renameModel").session()) {
            return session.executeWrite(tx -> {
                var result = tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        SET m.modelName = $modelName,
                            m.updatedAt = $now,
                            m.registered = true
                        RETURN m.modelId AS modelId,
                               m.modelName AS modelName,
                               coalesce(m.headRevision, 0) AS headRevision
                        """, params);
                if (!result.hasNext()) {
                    return registerModel(modelId, normalizedName);
                }
                return toModelCatalogEntry(result.next());
            });
        } catch (Exception e) {
            LOG.warn("renameModel failed for model={}", modelId, e);
            throw writeFailure("renameModel", modelId, e);
        }
    }

    @Override
    public String readModelName(String modelId) {
        if (driver == null) {
            return null;
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        RETURN m.modelName AS modelName
                        """, Map.of("modelId", modelId));
                if (!result.hasNext()) {
                    return null;
                }
                Value name = result.next().get("modelName");
                return name.isNull() ? null : name.asString(null);
            });
        } catch (Exception e) {
            LOG.warn("readModelName failed for model={}", modelId, e);
            return null;
        }
    }

    @Override
    public boolean modelRegistered(String modelId) {
        if (driver == null) {
            return false;
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        RETURN coalesce(m.registered, false) AS registered
                        """, Map.of("modelId", modelId));
                if (!result.hasNext()) {
                    return false;
                }
                return result.next().get("registered").asBoolean(false);
            });
        } catch (Exception e) {
            LOG.warn("modelRegistered failed for model={}", modelId, e);
            return false;
        }
    }

    @Override
    public Optional<ModelAccessControl> readModelAccessControl(String modelId) {
        if (driver == null) {
            return Optional.empty();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        WHERE coalesce(m.registered, false) = true
                        RETURN m.modelId AS modelId,
                               coalesce(m.adminUsers, []) AS adminUsers,
                               coalesce(m.writerUsers, []) AS writerUsers,
                               coalesce(m.readerUsers, []) AS readerUsers
                        """, Map.of("modelId", modelId));
                if (!result.hasNext()) {
                    return Optional.<ModelAccessControl>empty();
                }
                return Optional.of(toModelAccessControl(result.next()));
            });
        } catch (Exception e) {
            LOG.warn("readModelAccessControl failed for model={}", modelId, e);
            throw new IllegalStateException("readModelAccessControl failed for model " + modelId, e);
        }
    }

    @Override
    public ModelAccessControl updateModelAccessControl(String modelId, Set<String> adminUsers, Set<String> writerUsers, Set<String> readerUsers) {
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("adminUsers", normalizeUsers(adminUsers));
        params.put("writerUsers", normalizeUsers(writerUsers));
        params.put("readerUsers", normalizeUsers(readerUsers));
        params.put("now", Instant.now().toString());
        try (var session = requireDriver("updateModelAccessControl").session()) {
            return session.executeWrite(tx -> {
                Record record = tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        WHERE coalesce(m.registered, false) = true
                        SET m.adminUsers = $adminUsers,
                            m.writerUsers = $writerUsers,
                            m.readerUsers = $readerUsers,
                            m.updatedAt = $now
                        RETURN m.modelId AS modelId,
                               coalesce(m.adminUsers, []) AS adminUsers,
                               coalesce(m.writerUsers, []) AS writerUsers,
                               coalesce(m.readerUsers, []) AS readerUsers
                        """, params).single();
                return toModelAccessControl(record);
            });
        } catch (Exception e) {
            LOG.warn("updateModelAccessControl failed for model={}", modelId, e);
            throw writeFailure("updateModelAccessControl", modelId, e);
        }
    }

    @Override
    public ModelTagEntry createModelTag(String modelId, String tagName, String description, long revision, JsonNode snapshot) {
        return restoreModelTag(modelId, tagName, description, revision, Instant.now().toString(), snapshot);
    }

    @Override
    public ModelTagEntry restoreModelTag(String modelId, String tagName, String description, long revision, String createdAt, JsonNode snapshot) {
        String normalizedDescription = normalizeTagDescription(description);
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("tagName", tagName);
        params.put("description", normalizedDescription);
        params.put("revision", revision);
        params.put("snapshotJson", toJsonString(snapshot));
        params.put("createdAt", createdAt == null || createdAt.isBlank() ? Instant.now().toString() : createdAt);
        try (var session = requireDriver("restoreModelTag").session()) {
            return session.executeWrite(tx -> toModelTagEntry(tx.run("""
                    MATCH (m:Model {modelId: $modelId})
                    WHERE coalesce(m.registered, false) = true
                    CREATE (t:ModelTag {
                        modelId: $modelId,
                        tagName: $tagName,
                        description: $description,
                        revision: $revision,
                        snapshotJson: $snapshotJson,
                        createdAt: $createdAt
                    })
                    RETURN t.modelId AS modelId,
                           t.tagName AS tagName,
                           t.description AS description,
                           t.revision AS revision,
                           t.createdAt AS createdAt
                    """, params).single()));
        } catch (Exception e) {
            LOG.warn("restoreModelTag failed for model={} tag={}", modelId, tagName, e);
            throw writeFailure("restoreModelTag", modelId, e);
        }
    }

    @Override
    public Optional<ModelTagEntry> readModelTag(String modelId, String tagName) {
        if (driver == null) {
            return Optional.empty();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (t:ModelTag {modelId: $modelId, tagName: $tagName})
                        RETURN t.modelId AS modelId,
                               t.tagName AS tagName,
                               t.description AS description,
                               t.revision AS revision,
                               t.createdAt AS createdAt
                        """, Map.of("modelId", modelId, "tagName", tagName));
                if (!result.hasNext()) {
                    return Optional.empty();
                }
                return Optional.of(toModelTagEntry(result.next()));
            });
        } catch (Exception e) {
            LOG.warn("readModelTag failed for model={} tag={}", modelId, tagName, e);
            return Optional.empty();
        }
    }

    @Override
    public JsonNode loadTaggedSnapshot(String modelId, String tagName) {
        if (driver == null) {
            return JsonNodeFactory.instance.objectNode();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (t:ModelTag {modelId: $modelId, tagName: $tagName})
                        RETURN t.snapshotJson AS snapshotJson
                        """, Map.of("modelId", modelId, "tagName", tagName));
                if (!result.hasNext()) {
                    return JsonNodeFactory.instance.objectNode();
                }
                return parseJsonOrNull(result.next().get("snapshotJson").asString(null));
            });
        } catch (Exception e) {
            LOG.warn("loadTaggedSnapshot failed for model={} tag={}", modelId, tagName, e);
            return JsonNodeFactory.instance.objectNode();
        }
    }

    @Override
    public List<ModelTagEntry> listModelTags(String modelId) {
        if (driver == null) {
            return List.of();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (t:ModelTag {modelId: $modelId})
                    RETURN t.modelId AS modelId,
                           t.tagName AS tagName,
                           t.description AS description,
                           t.revision AS revision,
                           t.createdAt AS createdAt
                    ORDER BY t.revision DESC, t.tagName ASC
                    """, Map.of("modelId", modelId))
                    .list(this::toModelTagEntry));
        } catch (Exception e) {
            LOG.warn("listModelTags failed for model={}", modelId, e);
            return List.of();
        }
    }

    @Override
    public void deleteModelTag(String modelId, String tagName) {
        try (var session = requireDriver("deleteModelTag").session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MATCH (t:ModelTag {modelId: $modelId, tagName: $tagName})
                        DELETE t
                        """, Map.of("modelId", modelId, "tagName", tagName));
                return null;
            });
        } catch (Exception e) {
            LOG.warn("deleteModelTag failed for model={} tag={}", modelId, tagName, e);
            throw writeFailure("deleteModelTag", modelId, e);
        }
    }

    @Override
    public List<ModelCatalogEntry> listModelCatalog() {
        if (driver == null) {
            return List.of();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (m:Model)
                        WHERE coalesce(m.registered, false) = true
                        RETURN m.modelId AS modelId,
                               m.modelName AS modelName,
                               coalesce(m.headRevision, 0) AS headRevision
                        ORDER BY m.modelId
                        """);
                List<ModelCatalogEntry> models = new ArrayList<>();
                while (result.hasNext()) {
                    models.add(toModelCatalogEntry(result.next()));
                }
                return models;
            });
        } catch (Exception e) {
            LOG.warn("listModelCatalog failed", e);
            return List.of();
        }
    }

    @Override
    public long readLatestCommitRevision(String modelId) {
        if (driver == null) {
            return 0L;
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (c:Commit {modelId: $modelId})
                        RETURN coalesce(max(c.revisionTo), 0) AS latestRevision
                        """, Map.of("modelId", modelId));
                if (!result.hasNext()) {
                    return 0L;
                }
                return result.next().get("latestRevision").asLong(0L);
            });
        } catch (Exception e) {
            LOG.warn("readLatestCommitRevision failed for model={}", modelId, e);
            throw new IllegalStateException("readLatestCommitRevision failed for model " + modelId, e);
        }
    }

    /**
     * Loads the full materialized state snapshot for a model including all elements,
     * relationships, views, folders, and connections.
     */
    @Override
    public JsonNode loadSnapshot(String modelId) {
        long headRevision = readHeadRevision(modelId);
        if (driver == null) {
            return readSupport.loadSnapshot(null, modelId, headRevision);
        }
        try (var session = driver.session()) {
            return readSupport.loadSnapshot(session, modelId, headRevision);
        } catch (Exception e) {
            LOG.warn("loadSnapshot failed for model={}", modelId, e);
            throw new IllegalStateException("loadSnapshot failed for model " + modelId, e);
        }
    }

    @Override
    public boolean isMaterializedStateConsistent(String modelId, long expectedHeadRevision) {
        if (driver == null) {
            return true;
        }
        try (var session = driver.session()) {
            return readSupport.isMaterializedStateConsistent(session, modelId, expectedHeadRevision);
        } catch (Exception e) {
            LOG.warn("isMaterializedStateConsistent failed for model={} expectedHeadRevision={}",
                    modelId, expectedHeadRevision, e);
            return false;
        }
    }

    @Override
    public JsonNode loadOpBatches(String modelId, long fromRevisionInclusive, long toRevisionInclusive) {
        if (driver == null) {
            return readSupport.loadOpBatches(null, modelId, fromRevisionInclusive, toRevisionInclusive);
        }
        try (var session = driver.session()) {
            return readSupport.loadOpBatches(session, modelId, fromRevisionInclusive, toRevisionInclusive);
        } catch (Exception e) {
            LOG.warn("loadOpBatches failed for model={} range={}..{}",
                    modelId, fromRevisionInclusive, toRevisionInclusive, e);
            throw new IllegalStateException("loadOpBatches failed for model " + modelId, e);
        }
    }

    /**
     * Compacts metadata history by deleting commit and op nodes below a computed
     * watermark revision, retaining a configurable number of recent revisions.
     */
    @Override
    public AdminCompactionStatus compactMetadata(String modelId, long retainRevisions) {
        return compactMetadata(modelId, retainRevisions, false);
    }

    @Override
    public AdminCompactionStatus compactMetadata(String modelId, long retainRevisions, boolean dryRun) {
        try (var session = requireDriver("compactMetadata").session()) {
            return compactionSupport.compactMetadata(session, modelId, Math.max(0,retainRevisions), dryRun);
        }
    }

    /**
     * Deletes all materialized state nodes (elements, relationships, views, folders,
     * tombstones, and property clocks) for a model, preserving only root system folders.
     */
    @Override
    public void clearMaterializedState(String modelId) {
        try (var session = requireDriver("clearMaterializedState").session()) {
            session.executeWrite(tx -> { clearProjection(tx, modelId); return null; });
        }
    }

    private void clearProjection(TransactionContext tx, String modelId) {
        tx.run("MATCH (n {modelId:$modelId}) WHERE n:Element OR n:Relationship OR n:View OR n:ViewObject "
                + "OR n:Connection OR n:Folder OR n:PropertyClock OR n:ElementTombstone OR n:RelationshipTombstone "
                + "OR n:ViewTombstone OR n:ViewObjectTombstone OR n:ConnectionTombstone DETACH DELETE n",
                Map.of("modelId", modelId));
        ensureRootFolders(tx, modelId);
        tx.run("MATCH (m:Model {modelId:$modelId}) SET m.headRevision=0", Map.of("modelId", modelId));
    }

    @Override
    public ModelCheckpoint createCheckpoint(String modelId) {
        try (var session = requireDriver("createCheckpoint").session()) {
            session.run("CREATE CONSTRAINT model_checkpoint_revision IF NOT EXISTS FOR (c:ModelCheckpoint) REQUIRE (c.modelId,c.revision) IS UNIQUE").consume();
            return session.executeWrite(tx -> {
                requireWriterLease(tx);
                long head = tx.run("MATCH (m:Model {modelId:$modelId}) WHERE coalesce(m.registered,false)=true "
                        + "SET m.commitFence=coalesce(m.commitFence,0)+1 RETURN coalesce(m.headRevision,0) AS head",
                        Map.of("modelId", modelId)).single().get("head").asLong();
                long committed = tx.run("MATCH (c:Commit {modelId:$modelId}) RETURN coalesce(max(c.revisionTo),0) AS head",
                        Map.of("modelId",modelId)).single().get("head").asLong();
                if (head != committed) throw new IllegalStateException("Cannot checkpoint a model whose committed history and head disagree");
                var previous = checkpointSupport.latest(tx, modelId);
                if (previous.isPresent() && previous.get().revision() > head) throw new IllegalStateException("Checkpoint is ahead of the model head");
                verifyCheckpointHistory(tx, modelId, previous.map(c -> c.revision()).orElse(0L), head);
                if (previous.isPresent() && previous.get().revision() == head) return previous.get();
                ModelCheckpoint checkpoint = checkpointSupport.capture(tx, modelId, head);
                checkpointSupport.persist(tx, checkpoint);
                return checkpoint;
            });
        }
    }

    private void verifyCheckpointHistory(TransactionContext tx, String modelId, long base, long head) {
        long next = 1L;
        for (Record row : tx.run("MATCH (c:Commit {modelId:$modelId}) OPTIONAL MATCH (c)-[:HAS_OP]->(o:Op) "
                + "RETURN c.revisionFrom AS start,c.revisionTo AS end,coalesce(c.payloadPruned,false) AS pruned,count(o) AS ops "
                + "ORDER BY start",Map.of("modelId",modelId)).list()) {
            long start = row.get("start").asLong(), end = row.get("end").asLong();
            if (start != next || end < start || (end > base && (row.get("pruned").asBoolean()
                    || row.get("ops").asLong() != end-start+1))) {
                throw new IllegalStateException("Cannot checkpoint: recovery history or acceptance receipts are incomplete");
            }
            next = end+1;
        }
        if (next != head+1) throw new IllegalStateException("Cannot checkpoint: acceptance receipts do not cover the model head");
    }

    @Override
    public Optional<ModelCheckpoint> latestCheckpoint(String modelId) {
        try (var session = requireDriver("latestCheckpoint").session()) {
            return session.executeRead(tx -> checkpointSupport.latest(tx, modelId));
        }
    }

    @Override
    public long pruneCheckpoints(String modelId, int retainCount) {
        if (retainCount < 1) throw new IllegalArgumentException("At least one checkpoint must be retained");
        try (var session = requireDriver("pruneCheckpoints").session()) {
            return session.executeWrite(tx -> {
                requireWriterLease(tx);
                long head = tx.run("MATCH (m:Model {modelId:$modelId}) "
                        + "SET m.commitFence=coalesce(m.commitFence,0)+1 RETURN coalesce(m.headRevision,0) AS head",
                        Map.of("modelId",modelId)).single().get("head").asLong();
                var latest = checkpointSupport.latest(tx,modelId);
                if (latest.isEmpty()) return 0L;
                if (latest.get().revision() > head) throw new IllegalStateException("Checkpoint is ahead of model head");
                // Do not discard recovery bases if receipts or the latest checkpoint's tail are incomplete.
                verifyCheckpointHistory(tx,modelId,latest.get().revision(),head);
                var ids = tx.run("MATCH (c:ModelCheckpoint {modelId:$modelId}) WITH c ORDER BY c.revision DESC "
                        + "SKIP $retain LIMIT 100 RETURN elementId(c) AS id",
                        Map.of("modelId",modelId,"retain",retainCount)).list(row -> row.get("id").asString());
                tx.run("UNWIND $ids AS id MATCH (c:ModelCheckpoint) WHERE elementId(c)=id DETACH DELETE c",
                        Map.of("ids",ids)).consume();
                return (long) ids.size();
            });
        }
    }

    private void requireWriterLease(TransactionContext tx) {
        String owner = runtimeLeaseOwner;
        if (owner != null && !tx.run("MATCH (r:ArchimeshRuntime {id:$key,owner:$owner}) "
                        + "WHERE r.expiresAt >= datetime().epochMillis RETURN r",
                Map.of("key",runtimeLeaseKey,"owner",owner)).hasNext()) {
            throw new IllegalStateException("Authoritative writer lease expired");
        }
    }

    @Override
    public List<BatchReceipt> loadReceipts(String modelId, long throughRevision) {
        try (var session = requireDriver("loadReceipts").session()) {
            return session.executeRead(tx -> tx.run("MATCH (c:Commit {modelId:$modelId}) WHERE c.revisionTo <= $through "
                    + "RETURN c.opBatchId AS id, c.revisionFrom AS start, c.revisionTo AS end, coalesce(c.ts,'') AS ts ORDER BY c.revisionFrom",
                    Map.of("modelId",modelId,"through",throughRevision)).list(row -> new BatchReceipt(row.get("id").asString(),
                    row.get("start").asLong(), row.get("end").asLong(), row.get("ts").asString())));
        }
    }

    @Override
    public void rebuildProjection(String modelId, JsonNode history, long head) {
        rebuildProjection(modelId, null, history, head);
    }

    @Override
    public void rebuildProjection(String modelId, ModelCheckpoint checkpoint, JsonNode history, long head) {
        if (checkpoint != null) {
            Neo4jCheckpointSupport.validate(checkpoint);
            if (!modelId.equals(checkpoint.modelId()) || checkpoint.revision() > head) throw new IllegalArgumentException("Checkpoint revision/model mismatch");
        }
        try (var session = requireDriver("rebuildProjection").session()) {
            session.executeWrite(tx -> {
                requireWriterLease(tx);
                tx.run("MATCH (m:Model {modelId:$modelId}) SET m.commitFence=coalesce(m.commitFence,0)+1", Map.of("modelId",modelId));
                long committed = tx.run("MATCH (c:Commit {modelId:$modelId}) RETURN coalesce(max(c.revisionTo),0) AS head",
                        Map.of("modelId",modelId)).single().get("head").asLong();
                if (committed != head) throw new IllegalStateException("Committed history changed; retry rebuild");
                clearProjection(tx, modelId);
                if (checkpoint != null) {
                    // clearProjection creates canonical roots; the checkpoint supplies its exact raw folder state.
                    tx.run("MATCH (f:Folder {modelId:$modelId}) DETACH DELETE f", Map.of("modelId",modelId));
                    checkpointSupport.restore(tx, checkpoint);
                }
                for (JsonNode batch : history) materializedStateSupport.applyBatch(tx, modelId, batch);
                tx.run("MATCH (m:Model {modelId:$modelId}) SET m.headRevision=$head", Map.of("modelId",modelId,"head",head));
                return null;
            });
        }
    }

    /**
     * Permanently removes a model and all associated nodes from the graph, including
     * commits, ops, materialized state, tombstones, and property clocks.
     */
    @Override
    public void deleteModel(String modelId) {
        LOG.warn("deleteModel: modelId={}",modelId);
        try (var session = requireDriver("deleteModel").session()) {
            session.executeWrite(tx -> { deleteModel(tx,modelId); return null; });
        }
    }

    private void deleteModel(TransactionContext tx, String modelId) {
        tx.run("MATCH (c:Commit {modelId:$modelId}) OPTIONAL MATCH (c)-[:HAS_OP]->(o:Op) DETACH DELETE o",
                Map.of("modelId",modelId)).consume();
        // Include independent tags/checkpoints and orphaned projection metadata, not only root-reachable nodes.
        tx.run("MATCH (n {modelId:$modelId}) DETACH DELETE n",Map.of("modelId",modelId)).consume();
    }

    @Override
    public void importCheckpointModel(AdminModelExport exportPackage, boolean overwrite) {
        ModelCheckpoint original = exportPackage.checkpoint();
        Neo4jCheckpointSupport.validate(original);
        String modelId = exportPackage.model().modelId();
        ModelCheckpoint checkpoint = org.gautelis.archimesh.service.CheckpointCodec.retarget(original,modelId);
        Neo4jCheckpointSupport.validate(checkpoint);
        try (var session = requireDriver("importCheckpointModel").session()) {
            session.run("CREATE CONSTRAINT model_checkpoint_revision IF NOT EXISTS FOR (c:ModelCheckpoint) REQUIRE (c.modelId,c.revision) IS UNIQUE").consume();
            session.executeWrite(tx -> {
                requireWriterLease(tx);
                boolean exists = tx.run("MATCH (m:Model {modelId:$modelId}) RETURN m",Map.of("modelId",modelId)).hasNext();
                if (exists && !overwrite) throw new IllegalStateException("Model already exists");
                if (exists) deleteModel(tx,modelId);
                Map<String,Object> params = new HashMap<>();
                params.put("modelId",modelId);
                params.put("modelName",normalizeModelName(exportPackage.model().modelName()));
                params.put("now",Instant.now().toString());
                params.put("head",exportPackage.model().headRevision());
                params.put("checkpointRevision",checkpoint.revision());
                var acl = exportPackage.accessControl();
                params.put("admins",acl == null ? List.of() : new ArrayList<>(acl.adminUsers()));
                params.put("writers",acl == null ? List.of() : new ArrayList<>(acl.writerUsers()));
                params.put("readers",acl == null ? List.of() : new ArrayList<>(acl.readerUsers()));
                tx.run("CREATE (m:Model {modelId:$modelId,registered:true,headRevision:$head,prunedThroughRevision:$checkpointRevision, "
                        + "adminUsers:$admins,writerUsers:$writers,readerUsers:$readers,createdAt:$now,updatedAt:$now}) "
                        + "SET m.modelName=$modelName",params).consume();
                for (BatchReceipt receipt : exportPackage.receipts()) {
                    tx.run("MATCH (m:Model {modelId:$modelId}) CREATE (c:Commit {modelId:$modelId,opBatchId:$id, "
                            + "revisionFrom:$start,revisionTo:$end,opCount:$count,ts:$ts,published:true,payloadPruned:true}) "
                            + "CREATE (m)-[:HAS_COMMIT]->(c)",Map.of("modelId",modelId,"id",receipt.opBatchId(),
                            "start",receipt.revisionFrom(),"end",receipt.revisionTo(),"count",receipt.revisionTo()-receipt.revisionFrom()+1,
                            "ts",receipt.timestamp())).consume();
                }
                tx.run("MATCH (a:Commit {modelId:$modelId}), (b:Commit {modelId:$modelId}) WHERE a.revisionTo=b.revisionFrom-1 "
                        + "MERGE (a)-[:NEXT]->(b)",Map.of("modelId",modelId)).consume();
                checkpointSupport.restore(tx,checkpoint);
                checkpointSupport.persist(tx,checkpoint);
                for (JsonNode sourceBatch : exportPackage.opBatches()) {
                    var batch = ((com.fasterxml.jackson.databind.node.ObjectNode)sourceBatch).deepCopy().put("modelId",modelId);
                    RevisionRange range = new RevisionRange(batch.path("assignedRevisionRange").path("from").asLong(),
                            batch.path("assignedRevisionRange").path("to").asLong());
                    opLogSupport.appendOpLog(tx,modelId,batch.path("opBatchId").asText(),range,batch);
                    materializedStateSupport.applyBatch(tx,modelId,batch);
                }
                if (exportPackage.tags() != null) for (var tag : exportPackage.tags()) {
                    Map<String,Object> tagParams = new HashMap<>();
                    tagParams.put("modelId",modelId);
                    tagParams.put("name",tag.tagName());
                    tagParams.put("description",normalizeTagDescription(tag.description()));
                    tagParams.put("revision",tag.revision());
                    tagParams.put("createdAt",tag.createdAt());
                    JsonNode tagged = tag.snapshot().deepCopy();
                    if (tagged.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode)tagged).put("modelId",modelId);
                    tagParams.put("snapshot",tagged.toString());
                    tx.run("CREATE (t:ModelTag {modelId:$modelId,tagName:$name,revision:$revision,snapshotJson:$snapshot}) "
                            + "SET t.description=$description,t.createdAt=$createdAt",tagParams).consume();
                }
                return null;
            });
        } catch (Exception failure) {
            throw writeFailure("importCheckpointModel",modelId,failure);
        }
    }

    @Override
    public boolean elementExists(String modelId, String elementId) {
        if (elementId == null || elementId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_ELEMENT]->(:Element {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, elementId);
    }

    @Override
    public boolean relationshipExists(String modelId, String relationshipId) {
        if (relationshipId == null || relationshipId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_REL]->(:Relationship {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, relationshipId);
    }

    @Override
    public boolean viewExists(String modelId, String viewId) {
        if (viewId == null || viewId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, viewId);
    }

    @Override
    public boolean folderExists(String modelId, String folderId) {
        if (folderId == null || folderId.isBlank()) {
            return false;
        }
        if (isRootFolderId(folderId)) {
            return true;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_FOLDER]->(:Folder {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, folderId);
    }

    @Override
    public boolean folderEmpty(String modelId, String folderId) {
        if (folderId == null || folderId.isBlank() || driver == null || isRootFolderId(folderId)) {
            return false;
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                            MATCH (f:Folder {modelId: $modelId, id: $id})
                            RETURN COUNT { (f)-[:HAS_FOLDER]->() } = 0
                               AND COUNT { (f)-[:CONTAINS_ELEMENT]->() } = 0
                               AND COUNT { (f)-[:CONTAINS_REL]->() } = 0
                               AND COUNT { (f)-[:CONTAINS_VIEW]->() } = 0 AS empty
                            """, Map.of("modelId", modelId, "id", folderId))
                    .single()
                    .get("empty")
                    .asBoolean(false));
        } catch (Exception e) {
            LOG.warn("folderEmpty failed for model={} folder={}", modelId, folderId, e);
            return false;
        }
    }

    @Override
    public boolean folderMoveCreatesCycle(String modelId, String folderId, String parentFolderId) {
        if (folderId == null || folderId.isBlank() || parentFolderId == null || parentFolderId.isBlank() || driver == null) {
            return false;
        }
        if (folderId.equals(parentFolderId) || isRootFolderId(folderId)) {
            return true;
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                            MATCH (folder:Folder {modelId: $modelId, id: $folderId})
                            MATCH (parent:Folder {modelId: $modelId, id: $parentFolderId})
                            RETURN EXISTS { MATCH (folder)-[:HAS_FOLDER*1..]->(parent) } AS createsCycle
                            """, Map.of("modelId", modelId, "folderId", folderId, "parentFolderId", parentFolderId))
                    .single()
                    .get("createsCycle")
                    .asBoolean(false));
        } catch (Exception e) {
            LOG.warn("folderMoveCreatesCycle failed for model={} folder={} parent={}", modelId, folderId, parentFolderId, e);
            return true;
        }
    }

    @Override
    public String folderRootId(String modelId, String folderId) {
        if (folderId == null || folderId.isBlank() || driver == null) {
            return null;
        }
        if (isRootFolderId(folderId)) {
            return folderId;
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                            MATCH (folder:Folder {modelId: $modelId, id: $folderId})
                            OPTIONAL MATCH path = (root:Folder {modelId: $modelId})-[:HAS_FOLDER*0..]->(folder)
                            WHERE root.id STARTS WITH 'folder:root-'
                              AND NOT EXISTS { MATCH (:Folder {modelId: $modelId})-[:HAS_FOLDER]->(root) }
                            WITH root, length(path) AS depth
                            ORDER BY depth ASC
                            RETURN root.id AS rootId
                            LIMIT 1
                            """, Map.of("modelId", modelId, "folderId", folderId))
                    .stream()
                    .findFirst()
                    .map(record -> record.get("rootId").asString(null))
                    .orElse(null));
        } catch (Exception e) {
            LOG.warn("folderRootId failed for model={} folder={}", modelId, folderId, e);
            return null;
        }
    }

    @Override
    public boolean viewObjectExists(String modelId, String viewObjectId) {
        if (viewObjectId == null || viewObjectId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:ViewObject {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, viewObjectId);
    }

    @Override
    public boolean connectionExists(String modelId, String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:Connection {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, connectionId);
    }

    @Override
    public List<String> findRelationshipIdsByElement(String modelId, String elementId) {
        if (elementId == null || elementId.isBlank() || driver == null) {
            return List.of();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                            MATCH (m:Model {modelId: $modelId})-[:HAS_REL]->(r:Relationship)
                            WHERE r.sourceId = $elementId OR r.targetId = $elementId
                            RETURN DISTINCT r.id AS id
                            """, Map.of("modelId", modelId, "elementId", elementId))
                    .list(record -> record.get("id").asString(null))
                    .stream()
                    .filter(id -> id != null && !id.isBlank())
                    .toList());
        } catch (Exception e) {
            LOG.warn("findRelationshipIdsByElement failed for model={} elementId={}", modelId, elementId, e);
            return List.of();
        }
    }

    @Override
    public List<String> findViewObjectIdsByRepresents(String modelId, String representsId) {
        if (representsId == null || representsId.isBlank() || driver == null) {
            return List.of();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                            MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(vo:ViewObject)
                            WHERE vo.representsId = $representsId
                            RETURN DISTINCT vo.id AS id
                            """, Map.of("modelId", modelId, "representsId", representsId))
                    .list(record -> record.get("id").asString(null))
                    .stream()
                    .filter(id -> id != null && !id.isBlank())
                    .toList());
        } catch (Exception e) {
            LOG.warn("findViewObjectIdsByRepresents failed for model={} representsId={}", modelId, representsId, e);
            return List.of();
        }
    }

    @Override
    public List<String> findConnectionIdsByViewObject(String modelId, String viewObjectId) {
        if (viewObjectId == null || viewObjectId.isBlank() || driver == null) {
            return List.of();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                            MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(c:Connection)
                            WHERE c.sourceViewObjectId = $viewObjectId OR c.targetViewObjectId = $viewObjectId
                            RETURN DISTINCT c.id AS id
                            """, Map.of("modelId", modelId, "viewObjectId", viewObjectId))
                    .list(record -> record.get("id").asString(null))
                    .stream()
                    .filter(id -> id != null && !id.isBlank())
                    .toList());
        } catch (Exception e) {
            LOG.warn("findConnectionIdsByViewObject failed for model={} viewObjectId={}", modelId, viewObjectId, e);
            return List.of();
        }
    }

    @Override
    public List<String> findConnectionIdsByRelationship(String modelId, String relationshipId) {
        if (relationshipId == null || relationshipId.isBlank() || driver == null) {
            return List.of();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                            MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(c:Connection)
                            WHERE c.representsId = $relationshipId
                            RETURN DISTINCT c.id AS id
                            """, Map.of("modelId", modelId, "relationshipId", relationshipId))
                    .list(record -> record.get("id").asString(null))
                    .stream()
                    .filter(id -> id != null && !id.isBlank())
                    .toList());
        } catch (Exception e) {
            LOG.warn("findConnectionIdsByRelationship failed for model={} relationshipId={}", modelId, relationshipId, e);
            return List.of();
        }
    }

    private boolean exists(String cypher, String modelId, String id) {
        if (driver == null) {
            return false;
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(cypher, Map.of("modelId", modelId, "id", id));
                if (!result.hasNext()) {
                    return false;
                }
                return result.next().get("exists").asBoolean(false);
            });
        } catch (Exception e) {
            LOG.warn("Exists query failed for model={} id={}", modelId, id, e);
            return false;
        }
    }

    private JsonNode parseJsonOrNull(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return JsonNodeFactory.instance.nullNode();
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            LOG.warn("Failed parsing stored notation json", e);
            return JsonNodeFactory.instance.nullNode();
        }
    }

    private ModelCatalogEntry toModelCatalogEntry(Record record) {
        String modelId = record.get("modelId").asString("");
        String modelName = record.get("modelName").isNull() ? null : record.get("modelName").asString(null);
        long headRevision = record.get("headRevision").asLong(0L);
        return new ModelCatalogEntry(modelId, modelName, headRevision);
    }

    private ModelAccessControl toModelAccessControl(Record record) {
        return new ModelAccessControl(
                record.get("modelId").asString(""),
                toStringSet(record.get("adminUsers")),
                toStringSet(record.get("writerUsers")),
                toStringSet(record.get("readerUsers")));
    }

    private ModelTagEntry toModelTagEntry(Record record) {
        String modelId = record.get("modelId").asString("");
        String tagName = record.get("tagName").asString("");
        String description = record.get("description").isNull() ? null : record.get("description").asString(null);
        long revision = record.get("revision").asLong(0L);
        String createdAt = record.get("createdAt").isNull() ? null : record.get("createdAt").asString(null);
        return new ModelTagEntry(modelId, tagName, description, revision, createdAt);
    }

    private String toJsonString(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize json payload", e);
        }
    }

    private String normalizeModelName(String modelName) {
        if (modelName == null) {
            return null;
        }
        String trimmed = modelName.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeTagDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private List<String> normalizeUsers(Set<String> users) {
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String user : users) {
            if (user == null) {
                continue;
            }
            String trimmed = user.trim();
            if (!trimmed.isBlank()) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    private Set<String> toStringSet(Value value) {
        if (value == null || value.isNull()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (Object raw : value.asList()) {
            if (raw == null) {
                continue;
            }
            String text = raw.toString().trim();
            if (!text.isBlank()) {
                normalized.add(text);
            }
        }
        return Set.copyOf(normalized);
    }

    private void ensureRootFolders(org.neo4j.driver.TransactionContext tx, String modelId) {
        for (String folderType : List.of(
                "STRATEGY",
                "BUSINESS",
                "APPLICATION",
                "TECHNOLOGY",
                "RELATIONS",
                "OTHER",
                "DIAGRAMS",
                "MOTIVATION",
                "IMPLEMENTATION_MIGRATION")) {
            String id = "folder:root-" + folderType.toLowerCase().replace('_', '-');
            String displayName = rootFolderDisplayName(folderType);
            tx.run("""
                    MERGE (m:Model {modelId: $modelId})
                    MERGE (f:Folder {modelId: $modelId, id: $id})
                    SET f.folderType = $folderType,
                        f.name = $displayName
                    MERGE (m)-[:HAS_FOLDER]->(f)
                    """, Map.of("modelId", modelId, "id", id, "folderType", folderType, "displayName", displayName));
        }
    }

    private String rootFolderDisplayName(String folderType) {
        return switch (folderType) {
            case "STRATEGY" -> "Strategy";
            case "BUSINESS" -> "Business";
            case "APPLICATION" -> "Application";
            case "TECHNOLOGY" -> "Technology & Physical";
            case "RELATIONS" -> "Relations";
            case "OTHER" -> "Other";
            case "DIAGRAMS" -> "Views";
            case "MOTIVATION" -> "Motivation";
            case "IMPLEMENTATION_MIGRATION" -> "Implementation & Migration";
            default -> folderType;
        };
    }

    private boolean isRootFolderId(String folderId) {
        return folderId != null && folderId.startsWith("folder:root-");
    }

    private Driver requireDriver(String operation) {
        if (driver == null) {
            throw new IllegalStateException("Neo4j driver unavailable for " + operation);
        }
        return driver;
    }

    private RuntimeException writeFailure(String operation, String modelId, Exception cause) {
        return new IllegalStateException(
                "Neo4j write operation failed: " + operation + " modelId=" + modelId,
                cause);
    }

    private record CausalTuple(long lamport, String clientId) {
    }

    private record PropertyClockState(CausalTuple clock, boolean deleted) {
    }
}
