# Neo4j graph model

This document describes the persisted graph structure used by the Archimesh server.

It reflects the current implementation, not a future design sketch.

## Overview

The database stores four kinds of state per model:

1. model catalog and ACL metadata
2. materialized current state
3. committed op-log history
4. merge metadata such as tombstones and property clocks

The graph is model-scoped throughout:

- materialized entity identity is `(modelId, id)`
- commit identity is `(modelId, opBatchId)`
- tag identity is `(modelId, tagName)`

## Core model node

Every model is rooted at:

```cypher
(:Model {
  modelId,
  modelName,
  registered,
  headRevision,
  createdAt,
  updatedAt,
  adminUsers,
  writerUsers,
  readerUsers
})
```

Notes:

- `registered=true` means the model is available for client joins
- ACL data is stored directly on `:Model` as user-id arrays
- root folders are also anchored from `:Model`

## Materialized current state

### Nodes

```cypher
(:Element {
  modelId,
  id,
  archimateType,
  name,
  documentation,
  name_lamport,
  name_clientId,
  documentation_lamport,
  documentation_clientId
})

(:Relationship {
  modelId,
  id,
  archimateType,
  name,
  documentation,
  sourceId,
  targetId,
  name_lamport,
  name_clientId,
  documentation_lamport,
  documentation_clientId,
  source_lamport,
  source_clientId,
  target_lamport,
  target_clientId
})

(:Folder {
  modelId,
  id,
  folderType,
  name,
  documentation,
  name_lamport,
  name_clientId,
  documentation_lamport,
  documentation_clientId
})

(:View {
  modelId,
  id,
  name,
  documentation,
  notationJson,
  name_lamport,
  name_clientId,
  documentation_lamport,
  documentation_clientId,
  notation_lamport,
  notation_clientId
})

(:ViewObject {
  modelId,
  id,
  notationJson,
  notation_lamport_*,
  notation_clientId_*
})

(:Connection {
  modelId,
  id,
  sourceViewObjectId,
  targetViewObjectId,
  representsId,
  notationJson,
  notation_lamport_*,
  notation_clientId_*
})
```

Notes:

- `notation_lamport_*` and `notation_clientId_*` represent per-field Lamport LWW metadata for notation fields
- `ViewObject` and `Connection` notation is stored both as `notationJson` and as field-specific LWW metadata for merge correctness

### Relationships

```cypher
(:Model)-[:HAS_ELEMENT]->(:Element)
(:Model)-[:HAS_REL]->(:Relationship)
(:Relationship)-[:SOURCE]->(:Element)
(:Relationship)-[:TARGET]->(:Element)

(:Model)-[:HAS_FOLDER]->(:Folder)
(:Folder)-[:HAS_FOLDER]->(:Folder)
(:Folder)-[:CONTAINS_ELEMENT]->(:Element)
(:Folder)-[:CONTAINS_REL]->(:Relationship)
(:Folder)-[:CONTAINS_VIEW]->(:View)

(:Model)-[:HAS_VIEW]->(:View)
(:View)-[:CONTAINS]->(:ViewObject)
(:ViewObject)-[:REPRESENTS]->(:Element)
(:ViewObject)-[:CHILD_MEMBER]->(:ViewObject)

(:View)-[:CONTAINS]->(:Connection)
(:Connection)-[:REPRESENTS]->(:Relationship)
(:Connection)-[:FROM]->(:ViewObject)
(:Connection)-[:TO]->(:ViewObject)
```

### Folder structure

Folders are first-class persisted entities.

Important details:

- built-in Archi roots use stable ids such as:
  - `folder:root-strategy`
  - `folder:root-business`
  - `folder:root-diagrams`
- user folders use immutable ids
- folder membership is explicit
- a model-tree object belongs to one folder at a time
- cross-root folder moves are rejected by validation

## Model tags

Tags are stored as standalone nodes:

```cypher
(:ModelTag {
  modelId,
  tagName,
  description,
  revision,
  createdAt,
  snapshotJson
})
```

Notes:

- tags are immutable
- `snapshotJson` is a stored materialized snapshot at the tagged revision
- tags do not create branches
- tags are not currently linked to `:Model` by an explicit relationship; they are looked up by `(modelId, tagName)`

## Op-log history

### Commit nodes

```cypher
(:Commit {
  modelId,
  opBatchId,
  revisionFrom,
  revisionTo,
  ts
})
```

### Operation nodes

```cypher
(:Op {
  seq,
  type,
  targetId,
  payloadJson
})
```

### Relationships

```cypher
(:Model)-[:HAS_COMMIT]->(:Commit)
(:Commit)-[:HAS_OP]->(:Op)
(:Commit)-[:NEXT]->(:Commit)
```

Notes:

- `revisionFrom..revisionTo` is the assigned revision range for one committed submit batch
- `payloadJson` stores the normalized op payload as JSON
- `seq` preserves op order inside a batch
- `NEXT` keeps the timeline linear

## Merge metadata

The materialized graph also stores conflict-resolution metadata.

### Tombstones

These are used to reject stale recreates after deletes:

```cypher
(:ElementTombstone { modelId, id, lamport, clientId })
(:RelationshipTombstone { modelId, id, lamport, clientId })
(:ViewTombstone { modelId, id, lamport, clientId })
(:ViewObjectTombstone { modelId, id, lamport, clientId })
(:ConnectionTombstone { modelId, id, lamport, clientId })
```

Anchoring relationships:

```cypher
(:Model)-[:HAS_ELEMENT_TOMBSTONE]->(:ElementTombstone)
(:Model)-[:HAS_RELATIONSHIP_TOMBSTONE]->(:RelationshipTombstone)
(:Model)-[:HAS_VIEW_TOMBSTONE]->(:ViewTombstone)
(:Model)-[:HAS_VIEWOBJECT_TOMBSTONE]->(:ViewObjectTombstone)
(:Model)-[:HAS_CONNECTION_TOMBSTONE]->(:ConnectionTombstone)
```

### Property clocks

Property merge state is stored in dedicated metadata nodes:

```cypher
(:PropertyClock {
  modelId,
  ownerId,
  key,
  lamport,
  clientId,
  deleted
})
```

Anchoring relationship:

```cypher
(:Model)-[:HAS_PROPERTY_CLOCK]->(:PropertyClock)
```

Notes:

- this metadata supports LWW and OR-set style property semantics
- compaction may prune old metadata below the configured watermark/retention horizon

## Identity and constraints

Current schema constraints are defined in:

- [schema.cypher](./schema.cypher)

Key constraints:

```cypher
(:Model {modelId}) unique
(:Element {modelId, id}) unique
(:Relationship {modelId, id}) unique
(:View {modelId, id}) unique
(:Folder {modelId, id}) unique
(:ViewObject {modelId, id}) unique
(:Connection {modelId, id}) unique
(:Commit {modelId, opBatchId}) unique
(:ModelTag {modelId, tagName}) unique
```

## Snapshot/export relationship to the graph

The admin export format and checkout snapshot format are derived from the materialized graph.

That mapping is implemented in:

- [Neo4jReadSupport.java](../server/src/main/java/org/gautelis/archimesh/service/impl/Neo4jReadSupport.java)

Important consequence:

- snapshot/export reads materialized state directly
- rebuild/import reapply op-log history to reconstruct that state
- tags preserve historical materialized snapshots separately from current `HEAD`

## Operational notes

- `clearMaterializedState` deletes current entities and metadata but keeps the registered model and recreates built-in root folders
- import restores:
  - model registration
  - ACL fields
  - op-log commits and ops
  - materialized state
  - tags
- compaction removes old commits and merge metadata below the safe retention horizon


## Publication Profiles integration metadata

Profiles add no core graph labels, model entities, revisions or CRDT operations.
`IntegrationStateDocument` uses the additional kinds `PUBLICATION_PROFILE`
(immutable normalized definition and pin), `ASSERTION_SCOPE` (bounded per-model
claim index) and `ASSERTION_CLAIM` (per-model desired/active/withdrawn evidence).
Their payloads use `sourceModelId`. They are independent of model snapshot,
checkpoint, delete and rebuild traversals.

Canonical derived assertions and exact Turbo EA relation bindings reuse
`IntegrationCanonicalObject`, `IntegrationExternalBinding` and `IDENTITY_PROOF`.
They do not create synthetic `IntegrationModelBinding` records. Resource
baselines and durable uncertainty stay in `PUBLICATION_RESOURCE`. M6 v2 archives
validate definition/pin/evidence closure and restore claims quarantined, policies
disabled and model scopes paused. See [operations](../docs/publication-profiles.md).
