# ADR 0001: External repository identity, authority and recovery

Date: 2026-11-11. Status: accepted as the M0 implementation direction; runtime
support belongs to M1–M6. See the [M0 investigation](../turboea/m0-analysis.md)
for source evidence, API limitations, tests and remaining deployment checks.

## Context

The [integration assignment](../archimesh-turboea-integration-codex.md) requires
many ArchiMate model occurrences to refer to one centrally governed object.
ArchiMesh currently identifies entities by `(modelId, id)` and commits operations,
projection, head and delivery intent together. Its Lamport rules resolve model
edits; they do not decide information ownership between repositories.

Turbo EA is a separate transactional system. The local 2.194.0 source has no
card-create idempotency key or unique `external_id`. Card/relation PATCH replaces
the supplied attribute map, and no conditional-write contract was found. These
constraints rule out treating HTTP calls as part of an ArchiMesh commit.

## Decision

### Boundary and availability

Keep connectors, policies, identity storage and reconciliation on the Quarkus
server, under a separate `org.gautelis.archimesh.integration` package. Keep
connector-specific HTTP/DTO code under its `turboea` subpackage. These are planned
locations, not existing classes. The Archi plugin and Svelte admin call ArchiMesh;
credentials never reach either client.

Enablement and publication default to disabled. Normal model submission must
perform no external HTTP, wait for no integration worker and hold no integration
resource lock. A Kafka signal marks a scope dirty; periodic snapshot-based
reconciliation independently repairs missed signals. Give integration consumption
its own Kafka consumer group; sharing the WebSocket group's work would steal
fan-out records. Do not put remote calls in `KafkaConsumerImpl.handleRecord` or
`CommitDeliveryService`.

### Four identities and their uniqueness

| Identity | Definition and required uniqueness |
| --- | --- |
| Model occurrence | `(archimeshInstanceId, sourceModelId, entityKind, entityId)`; immutable binding to one canonical identity while active. Include kind and existing `elem:`/`rel:` identifiers. |
| Canonical object/relation | Opaque stable `canonicalId`, e.g. an organization-issued URN or generated `urn:uuid:…`; never a name or a model ID. |
| External resource | `(system, targetInstanceId, resourceKind, resourceId)` identifies exactly one canonical identity. `(canonicalId, system, targetInstanceId, resourceKind)` identifies at most one external resource. |
| Source contribution | `(canonicalId, archimeshInstanceId, sourceModelId, entityKind, entityId, targetInstanceId)` owns that occurrence's selected publication contribution. Ref, observed revision, digest and mapping/policy versions are versioned state. |

Also reserve `(archimeshInstanceId, sourceModelId, canonicalId, entityKind)` for
one active imported occurrence per model. Explicit additional occurrences require
a later policy change; concurrent imports cannot silently make two. Use Neo4j
composite unique constraints for both directions of the external mapping and
these occurrence/import keys. Missing key components must be rejected in Java;
uniqueness alone does not enforce property existence in Community Neo4j.

Model A and model B importing one card obtain separate element IDs and one
canonical object. Different cards with the same name remain distinct. Linking a
compatible existing element reserves the same keys. Canonical relations have
their own identity, direction and provenance; the model's relationship ID and a
Turbo EA relation ID are separate bindings. Resource kind prevents a card/relation
ID collision. Never infer identity from diagram objects or coordinates.

Retain a local mapping even when writing an integration-owned `external_id`.
Never replace an identifier owned by another system. Unknown POST outcome becomes
ambiguous pending work/conflict; card POST must not be retried blindly. Discovery
of candidates is followed by explicit verification, never name-based adoption.

### Integration metadata is outside the model projection

Use dedicated labels prefixed `Integration` and an independent repository and
constraint/migration file. Store model references as `sourceModelId` (and explicit
instance IDs), **not `modelId`**, and do not connect integration records to model
projection nodes. The current checkpoint capture rejects unknown model-scoped
nodes and unsupported projection edges; model deletion also deletes every node
with matching `modelId`. Merely choosing another label is insufficient isolation.

The metadata records canonical identities, model/external bindings, contributions,
field authority, jobs, per-field baselines, checkpoints, conflicts and proposals.
Use unique job/correlation IDs, mapping/policy versions and normalised content
digests. Model revision and integration checkpoint are different counters.

Durably reserve an import intent, stable element ID and opBatchId before model
submission. A reserved binding is `PREPARED`, not usable as an imported element.
Submit ordinary typed model operations, then mark it active only after persisted
acceptance and verification of the materialized entity. After a crash, consult
`findAcceptedRange`, the reserved batch and current snapshot before retrying or
activating. A definitive rejection can fail the intent; an uncertain outcome
retains the reservation. Concurrent importers share the reservation/result. This
avoids claiming an atomic transaction between independent metadata and model
writes or exposing a half-imported binding as successful.

### Information authority precedes merge

Resolve policy from a versioned type/field rule plus explicit canonical overrides.
Policies are `TURBO_EA_MASTER`, `ARCHIMESH_MASTER`, `LOCAL_MODEL_ONLY`,
`SHARED_PROPOSAL` and `UNMAPPED`; undefined mappings are `UNMAPPED`.

Initial pilot candidates, subject to metamodel and governance approval:

| Field | Authority and behavior |
| --- | --- |
| Linked central name, lifecycle, business-owner fields | Turbo EA master; inbound allowed by scoped policy. Ordinary local writes rejected; explicit proposal/write-through checked separately. |
| Model-specific documentation | Local model only by default; an explicit separate central-description mapping may be approved. Never assume every model's documentation belongs to the common card. |
| Model relationships | Local model only until type, direction, endpoints and contribution ownership are explicitly mapped. |
| Views, notation, coordinates, folders | Local model only. |
| Other attributes | Unmapped; no automatic transfer. |

Check policy on every operation path, including properties, create payloads,
delete/recreate, offline replay and older clients. Reject a mixed unauthorized
batch before changing the model; do not silently strip fields. Authorization is
not a clock comparison. Canonical contributions from several models need an
explicit steward/priority rule; conflicting contributions otherwise create a
conflict rather than choosing the latest revision from unrelated timelines.

### Inbound command and causality

Introduce a typed internal external-change command with explicit `HEAD`, trusted
system actor, scope, immutable correlation/batch ID, mapping/policy version and
expected baseline. Authorize before entering the shared acceptance pipeline.
The existing sessionless `onSubmitOps` is not an authorization boundary: current
identity checks live in endpoints, `ensureWritableRef` permits an unjoined actor,
and `reply(null, …)` broadcasts replies. Refactor the shared acceptance work
minimally to return a typed durable result to internal callers while retaining
the existing WebSocket contract. Do not use a fake session or infer success from
a broadcast. Do not implement a second commit or merge engine.

Within existing service timeline serialization, recheck the latest HEAD and
field/property/tombstone clocks, evaluate three-way baseline/local/remote values,
and produce ordinary operations. Server-generated clocks must advance beyond the
affected clocks, with overflow rejected; defaulting to revision is insufficient
when a client has previously sent a much higher Lamport value. Keep supplied
client clocks' existing semantics. Policy check and clock construction must be
inside the serialized acceptance work to avoid a stale read/write race.

Check acceptance by the durable receipt, then verify the actual field values.
Accepted operations can lose individual LWW comparisons. Checkpoint only the
verified result. Persist a stable inbound job before submit; crash recovery retries
that exact batch ID rather than generating another operation. If remote equals
verified local state, reconcile baseline without consuming another revision.
Update each linked writable model independently and retain failures per model;
there is no cross-model atomicity. Never modify tag snapshots.

### Authorization and client behavior

Add explicit policy actions for catalog visibility, import/link, inbound updates,
publication, proposals/write-through and resolution. Extend PDP switches with
denial tests: current fallback branches allow unknown actions, and global admin
currently bypasses model policy. A narrow integration principal must not receive
that bypass. Require model ACL checks plus field scope, enabled target and
mapping/policy authorization. Catalog visibility needs delegated access or an
approved shared catalog scope; model read access alone does not authorize all
objects visible to a broad service account. Redact hidden remote IDs/fields too.

Use existing `RemoteOpApplier` on SWT's UI thread under `RemoteApplyGuard` for
accepted imports/updates. It creates semantic EMF concepts in the default model
folder; view placement is a later Archi command. Existing remote apply mutates
EMF directly and is not automatically a user's undoable command. The import UI
must not manufacture optimistic duplicate concepts or promise remote undo.
Undo of a later explicit local edit follows normal Archi capture/authorization.

Use correlated `PRECONDITION_FAILED` for an initial authority denial so the
current outbox can drop that stale intent and resynchronize. A new authority
error code requires matching client handling: unrecognized errors currently
retry. Verify with two clients plus an offline queue before enabling inbound
authority enforcement. Do not silently discard unrelated queued work.

### Reconciliation, deletion and recovery

Project a consistent HEAD snapshot or stored approved tag. Compare selected
normalized fields against the last verified baseline and a fresh remote read.
Suppress unchanged writes. Patch only authorized fields, preserving other map
members according to the actual replacement semantics. Without conditional
writes, a fresh read and post-write verification detect some races but cannot
guarantee prevention of lost concurrent edits; shared field writes stay disabled
unless the deployment explicitly accepts that limitation.

Persist dirty scopes/jobs, retries with bounded exponential backoff and jitter,
last observed/verified revisions, per-field baselines, ownership/provenance and
conflicts. Serialize writes per canonical identity/target as well as model
acceptance, and reject stale job versions. Release integration coordination
before entering model acceptance; never hold a Neo4j transaction over HTTP.
Polling of bound resources is mandatory; SSE is optional acceleration. The local
source's ordinary SSE subscriber does not receive general card-change events,
and its in-process queue has no durable replay contract.

Model deletion removes the contribution, not the common object. Preserve
canonical/external identities while other contributors exist. Explicitly
reconcile source-model deletion/replacement: isolated metadata survives existing
delete/overwrite operations. A missing/hidden resource is an orphan or access
diagnostic; never automatically recreate it or interpret redacted/empty reads as
deletion. Only demonstrably integration-owned relations can be unpublished.

Existing model exports/checkpoints remain unchanged and do not back up this
metadata. Add a separately versioned integration backup/restore procedure before
enabling writes. A database backup must include both. Restoring a model package
under a new ID creates an unlinked model; reattachment requires explicit
verification, never copying source bindings automatically. On restore, pause
publication, verify target instance and actual remote state, then reconcile.
Losing the mapping after a remote create must not cause another blind POST.

### Future DataHub

Use generic system/instance/resource keys and per-field authority throughout.
A future `DataHubConnector` can bind a DataHub URN to the canonical object while
Turbo EA keeps a separate representation. DataHub-owned metadata must retain its
authority. No DataHub code, configuration or synchronization is part of M0.

## Consequences and alternatives

Identity and reconciliation need their own durable state and recovery tests.
Inbound acceptance needs a small, tested shared-pipeline refactor and authority
hook; M0 intentionally does not change it. Rejected alternatives are direct
Neo4j/EMF mutation (bypasses history/merge), client-side credentials (breaks the
trust boundary), per-operation HTTP mirroring (can publish losing LWW values),
name/`external_id` deduplication (not unique), and a second commit framework
(competing sequencing and recovery rules).

Progress through M1–M6 with integration disabled until each milestone's tests
prove its boundary. Operational visibility and cross-system recovery remain
required before enabling production writes.
