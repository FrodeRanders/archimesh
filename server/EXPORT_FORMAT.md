# Admin Export Format

This document describes the JSON package returned by:

- `GET /admin/models/{modelId}/export`

and accepted by:

- `POST /admin/models/import?overwrite=true|false`

The format is intended for:

- model backup
- model migration between environments
- reproducible restore of a model timeline, including tags and ACLs

It is not a partial patch format. It represents one whole model timeline package.

## Format versions and checkpoint recovery

The server emits `archi-model-export-v1` when no checkpoint exists and `archi-model-export-v2` when a checkpoint exists. Both are accepted on import. The v1 fields and full-history requirements below remain supported.

V2 adds two required fields:

- `checkpoint`: an `archimesh-checkpoint-v1` object with `format`, `modelId`, `revision`, `createdAt`, `sha256`, and `state`. State contains raw projection `nodes` and `edges`, preserving properties, clocks, tombstones, and memberships. Each node has `key`, `label`, and `properties`; each edge has `from`, `to`, `type`, and `properties`. The root model endpoint is the reserved key `model`.
- `receipts`: ordered acceptance records for every batch through checkpoint revision C, each with `opBatchId`, `revisionFrom`, `revisionTo`, and `timestamp`. They cover revisions 1..C exactly and preserve retry deduplication. For C=0 this is an empty array.

In v2, `opBatches` contains only the complete tail C+1..`model.headRevision`, with whole batch boundaries and matching operation counts. An empty tail is valid when C equals HEAD, even for a non-empty model. The current `snapshot`, `accessControl`, and `tags` remain in the package. Restore uses the raw checkpoint and tail as its recovery source; the current snapshot is a convenience representation. Tagged historical snapshots remain independently available even when their old operation payloads are absent.

Example field arrangement (illustrative; the checksum and state must come from a real export):

```text
format: archi-model-export-v2
model: {modelId: demo, modelName: Demo, headRevision: 43}
checkpoint: {format: archimesh-checkpoint-v1, modelId: demo,
             revision: 42, createdAt: ..., sha256: ..., state: {nodes: [...], edges: [...]}}
receipts: [all batch IDs and acceptance ranges covering 1..42]
opBatches: [complete accepted batch at revision 43]
snapshot: current client-facing state at 43
accessControl: model ACL
tags: stored historical snapshots
```

Import verifies the checkpoint checksum, graph schema/model scope, receipt continuity and unique IDs, exact tail coverage, and tag metadata. V2 model creation/overwrite, graph restore, receipts, tail, ACLs, and tags are committed in one Neo4j transaction; a failed overwrite leaves the destination intact. Existing destinations still require `overwrite=true` and no live sessions.

To import under another ID, change only `model.modelId`. The server verifies the original checkpoint, rebinds its model-scoped nodes to the destination, and computes a new checksum. Source tail batches and tag snapshots are also rebound. Do not manually change the raw checkpoint or its checksum.

V2 is a recoverable state-and-timeline package; it does not preserve individual operation payloads at or before C. Archive a v1 full-history export before creating the first checkpoint if those payloads are required. Retained receipt IDs and ranges are still exported. See [checkpoint maintenance and limits](../docs/checkpoint-pruning.md).

## Revisions and revision ranges

The Archimesh server uses a linear revision timeline per model.

Important terms:

- `headRevision`
  - the latest committed revision on the model timeline
- `baseRevision`
  - the client revision the submitted op batch was based on
- `assignedRevisionRange`
  - the contiguous revision interval the server assigned to one accepted op batch

### Why a range exists

Clients submit batches, not single operations.

Example:

- client submits one `SubmitOps` batch containing 3 ops
- server head is currently `10`
- the batch is accepted
- the server assigns:
  - `from = 11`
  - `to = 13`

That means:

- the batch advanced the model timeline from revision `10` to revision `13`
- the batch owns revisions `11`, `12`, and `13`
- `headRevision` becomes `13`

So revision ranges exist because one accepted submit can contain multiple operations.

### How `baseRevision` works

When a client submits a batch, it includes the model revision it believes it is building on:

- `baseRevision`

The server compares that against the current model head:

- if `baseRevision <= headRevision`, the batch may still be accepted
- if `baseRevision > headRevision`, the batch is rejected because the client is ahead of the server and must rejoin

`baseRevision` is therefore a client-side precondition, not the revision assigned to the new work.

### How `assignedRevisionRange` works

Once a batch is accepted, the server allocates one contiguous range:

- `assignedRevisionRange.from`
- `assignedRevisionRange.to`

This range is persisted in the op log and returned in:

- `OpsAccepted`
- exported `opBatches`

The range is authoritative. It is what import replays and what rebuild uses to restore the exact committed timeline.

### Per-op causal metadata inside a batch

Each op also carries a `causal` object.

If the client does not provide causal Lamport metadata, the server fills it deterministically from the assigned range:

- first op gets lamport `assignedRevisionRange.from`
- second op gets `from + 1`
- and so on

This is why the export format stores both:

- batch-level `assignedRevisionRange`
- per-op `causal`

The range explains where the batch sits on the model timeline.
The per-op causal fields are used by downstream merge logic such as LWW and tombstone comparison.

### How `headRevision` relates to export

The export package contains several revision-bearing fields:

- `model.headRevision`
- `snapshot.headRevision`
- each tag `revision`
- each op batch `assignedRevisionRange`

Their meanings are:

- `model.headRevision`
  - exported current head of the model timeline
- `snapshot.headRevision`
  - head revision represented by the exported current materialized snapshot
- tag `revision`
  - the historical revision that tag points to
- batch `assignedRevisionRange`
  - the exact revision interval occupied by one committed batch

For a healthy export:

- `model.headRevision` should match the highest committed revision in `opBatches`, or C for a v2 package with an empty tail
- `snapshot.headRevision` should represent the same current head state
- tag revisions should be less than or equal to `model.headRevision`

### Why v1 import requires op-log history for non-empty models

For a non-empty model, snapshot alone is not enough to restore the linear revision timeline faithfully.

Import therefore rejects packages that have:

- a non-zero `model.headRevision`, or
- non-empty materialized content, or
- historical tags

but no `opBatches`

This is deliberate. It prevents restoring current state while silently losing the actual committed revision history.

## Top-level shape

The v1 package shape is:

```json
{
  "format": "archi-model-export-v1",
  "exportedAt": "2026-03-10T12:34:56Z",
  "model": {
    "modelId": "demo",
    "modelName": "Demo",
    "headRevision": 42
  },
  "accessControl": {
    "configured": true,
    "adminUsers": ["admin"],
    "writerUsers": ["editor"],
    "readerUsers": ["viewer"]
  },
  "snapshot": { "...": "materialized model state at HEAD" },
  "opBatches": [
    { "...": "revision-ordered committed op batch" }
  ],
  "tags": [
    {
      "modelId": "demo",
      "tagName": "approved-2026-03-08",
      "description": "Approved baseline",
      "revision": 24,
      "createdAt": "2026-03-08T09:00:00Z",
      "snapshot": { "...": "materialized model state at tag revision" }
    }
  ]
}
```

## Top-level fields

### `format`

Current value:

- `archi-model-export-v1`

Import rejects packages with any other value.

### `exportedAt`

- UTC timestamp for when the package was produced
- informational only
- not used as part of restore semantics

### `model`

Model catalog metadata:

- `modelId`
  - required
  - stable model timeline identifier
- `modelName`
  - optional display name
- `headRevision`
  - highest revision represented by the package
  - see [Revisions and revision ranges](#revisions-and-revision-ranges)

### `accessControl`

Optional per-model ACL snapshot.

Fields:

- `configured`
  - whether model-scoped ACLs are active
- `adminUsers`
- `writerUsers`
- `readerUsers`

If present on import, the ACL is restored for the imported model.

### `snapshot`

Materialized current state of the model at `HEAD`.

This is the fast restore and inspection view of the model graph. It is not the authoritative history by itself. The authoritative history is `opBatches`.

Current snapshot format:

- `format`
  - currently `archimate-materialized-v1`
- `modelId`
- `headRevision`
  - current materialized head revision for this snapshot
- `folders`
- `elements`
- `relationships`
- `views`
- `elementFolderMembers`
- `relationshipFolderMembers`
- `viewFolderMembers`
- `viewObjects`
- `viewObjectChildMembers`
- `connections`

#### `folders`

Each folder object contains:

- `id`
- `folderType`
- `name`
- `documentation`
- `parentFolderId`

Notes:

- root folders use stable internal ids like `folder:root-business`
- exported root names are normalized to vanilla Archi labels such as `Business`, `Views`, and `Technology & Physical`
- user folders use immutable ids so rename does not affect identity

#### `elements`

Each element contains:

- `id`
- `archimateType`
- `name`
- `documentation`

#### `relationships`

Each relationship contains:

- `id`
- `archimateType`
- `name`
- `documentation`
- `sourceId`
- `targetId`

#### `views`

Each view contains:

- `id`
- `name`
- `documentation`
- `notationJson`

#### `elementFolderMembers`

Placement of elements in the model tree.

Each item contains:

- `folderId`
- `elementId`

#### `relationshipFolderMembers`

Placement of relationships in the model tree.

Each item contains:

- `folderId`
- `relationshipId`

#### `viewFolderMembers`

Placement of views in the model tree.

Each item contains:

- `folderId`
- `viewId`

#### `viewObjects`

Each diagram object contains:

- `id`
- `viewId`
- `representsId`
- `notationJson`

`representsId` points at an element id.

#### `viewObjectChildMembers`

Nested diagram object membership.

Each item contains:

- `parentViewObjectId`
- `childViewObjectId`

#### `connections`

Each connection contains:

- `id`
- `viewId`
- `representsId`
- `sourceViewObjectId`
- `targetViewObjectId`
- `notationJson`

`representsId` points at a relationship id.

### `opBatches`

Ordered committed op-log history for the model timeline.

This is the authoritative replay history used during import and rebuild: full history in v1, or the complete checkpoint tail in v2.

Each batch contains:

- `modelId`
- `opBatchId`
- `baseRevision`
- `assignedRevisionRange`
  - `from`
  - `to`
- `timestamp`
- `ops`

Semantics:

- `opBatches` are ordered by assigned revision
- each batch corresponds to one committed submit
- each op inside the batch already contains normalized causal metadata from the server
- import appends these batches to the op log, applies them to materialized state, and restores head revision from the highest assigned range
- see [Revisions and revision ranges](#revisions-and-revision-ranges) for how `baseRevision` and `assignedRevisionRange` differ

For v1, if `opBatches` is empty, import only accepts the package if the model is effectively empty:

- `headRevision == 0`
- no materialized snapshot content
- no historical tags

### `tags`

Immutable named revision points on the same linear model timeline.

Each tag entry contains:

- `modelId`
- `tagName`
- `description`
- `revision`
  - historical timeline revision the tag points to
- `createdAt`
- `snapshot`

Notes:

- tag snapshots are historical materialized states at the tagged revision
- tags are restored after the model timeline itself is restored
- tags do not create branches
- imported tags remain read-only references

## What the export represents

The package combines three layers:

1. Model identity and operator metadata
   - `model`
   - `accessControl`

2. Current materialized state
   - `snapshot`

3. Replayable history and historical anchors
   - `opBatches`
   - `tags`

This means the package is suitable for both:

- fast restore of current state
- faithful restore of the linear revision history

## Import behavior

Import currently behaves as follows:

- the `format` must match exactly
- `model.modelId` must be present
- existing models are rejected unless `overwrite=true`
- overwrite is rejected if the model has active sessions
- if overwriting, the existing model is deleted first
- ACLs are restored if present
- op batches are restored first
- head revision is restored from imported history
- tags are restored after the model timeline

## What is preserved

Import/export preserves:

- model id and model name
- head revision
- full committed op-log history
- current materialized state
- folders and folder memberships
- views, view objects, connections, and notation payloads
- immutable tags and their historical snapshots
- model-scoped ACLs

## What it is not

The export package is not:

- a branch format
- a partial incremental sync format
- a conflict-resolution format
- a raw Archi `.archimate` file

It is a Archimesh-server package for one model timeline.
