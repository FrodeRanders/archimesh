# ADR 0002: Modular DataHub, Turbo EA and ArchiMesh federation

Date: 2026-11-12. Status: accepted implementation direction following the decision
to take responsibility for DataHub–Turbo EA integration as well. The modules and
contracts below are planned; this ADR does not enable a DataHub connector or claim
a completed three-repository integration.

Extends [ADR 0001](0001-external-repository-integration.md). Delivery and acceptance
are specified in the [federation assignment](../../integrations/datahub-turboea/docs/implementation.md).

## Context

The agency uses self-hosted DataHub OSS, Turbo EA and Archi/ArchiMesh. This project
will own both the DataHub–Turbo EA bridge and their integration with ArchiMesh.
Publishing a DataHub dataset into Turbo EA and importing the resulting card into
ArchiMesh must resolve the original asset, including when an architect already
imported it directly from DataHub. A mirror must preserve information authority.

DataHub entities have URNs; aspects are the atomic metadata write unit and
relationships can be references within aspects. These do not have the same
shape as Turbo EA cards and relations. The verified Turbo EA API assigns its own
card IDs; `external_id` is nonunique and limited to 500 characters. It cannot
serve as an authoritative cross-system identity registry. See the
[DataHub metadata model](https://docs.datahub.com/docs/metadata-modeling/metadata-model)
and [local Turbo EA evidence](../turboea/m0-analysis.md).

## Decision

### One federation, independently usable bridge

Implement one shared identity/authority and reconciliation layer, two repository
adapters and an ArchiMesh binding adapter. The DataHub–Turbo EA bridge must work
without an Archi model, model ACL, contribution, HEAD, view or active client.
ArchiMesh imports must use that same registry rather than create a second mapping.

The bridge is a separately built/tested subproject under
`integrations/datahub-turboea/`. Its infrastructure, release-specific discovery,
jobs and operations stay there; ordinary ArchiMesh builds do not depend on it.
Shared contracts/adapters must be independently reusable, with no dependency from
ArchiMesh into bridge application internals. D1 must define the runtime/storage
boundary before enabling a separate worker: one active owner coordinates each
logical identity registry and its mutation lanes. Separate packaging does not
permit separate canonical mappings or independent writers without coordination.
The subproject can later be extracted into its own repository without moving the
model engine. Separate runtime execution is not yet implemented.

| Module / planned package | Responsibility | Dependencies |
| --- | --- | --- |
| Federation core / `integration.federation` | Identity references, canonical semantics, field authority, observation provenance, projection plans and repository-neutral ports | Java/value types; no HTTP, Quarkus, Neo4j, EMF or model operations |
| DataHub adapter / `integration.datahub` | OSS API/authentication, entities/aspects, source observations, capabilities and error classification | Core; HTTP/serialization |
| Turbo EA adapter / existing `integration.turboea` | API/authentication, metamodel validation, card/relation read/write and readback | Core; HTTP/serialization |
| Federation persistence / `integration.federation.store` | Atomic identity reservations, versioned state, baselines, audit, durable jobs and uncertainty fences | Core; existing integration metadata storage |
| Bridge application / subproject | Approved DataHub → Turbo EA projections, scheduling, conflict handling and reconciliation | Core ports; no concrete HTTP clients or model service |
| ArchiMesh binding / existing integration application services | Catalog/import/link, occurrence bindings and ordinary accepted inbound model operations | Core ports; model service |
| Runtime composition / separate subproject composition and ArchiMesh `IntegrationRuntime` | Wire each application's configuration, adapter construction, credentials and operations | Versioned shared contracts; no default bridge dependency/startup in ArchiMesh |

Extract and reuse existing capabilities where compatible. Do not build another
generic commit/event engine, duplicate the Turbo EA client or duplicate mutation
fences between bridge publication and model publication. The current runtime is
Turbo EA-specific; these boundaries require the D1 refactoring and are not
already provided by its connector interface.

### Identity and equivalence

Keep three separate identifiers:

1. An opaque agency-governed `canonicalId`, independent of repository IDs and URLs.
2. An exact external reference `(system, logicalInstanceId, resourceKind, nativeId)`.
3. Each model occurrence `(archimeshInstanceId, sourceModelId, entityKind, entityId)`.

For DataHub, the native ID is the complete URN. For Turbo EA, it is the native
card/relation ID. A logical instance ID identifies a repository across hostname
changes; dev, test, production and independent clones have distinct instance IDs.
A URL is transport configuration. No name, URL, array order or `external_id`
comparison establishes equivalence. Do not truncate, lowercase or reconstruct URNs.

The registry atomically binds each external reference to exactly one canonical
identity. Preserve the existing at-most-one active external representation per
canonical/system/instance/resource-kind constraint. Historical aliases are
separate records with lifecycle/provenance, not additional active bindings that
silently weaken uniqueness.

Canonical kind describes meaning, such as a cataloged dataset or governed data
product. External entity types and ArchiMate types are separate mapping decisions.
A data product and its member datasets have separate canonical identities. A
conceptual business object describing a dataset is related to it, not assumed
identical. An explicitly equivalent Turbo EA representation can share identity;
a broader portfolio abstraction requires its own identity and a typed link.

On first direct import or bridge observation, reserve the source binding before
creating any remote representation. If both systems already have different
canonical identities, report a reconciliation conflict. A steward-reviewed merge
must account for all bindings, jobs, authority rules, baselines and historical
references; do not silently reassign either identity.

### Information authority and transport provenance

| Information | Initial authority |
| --- | --- |
| Selected DataHub dataset/data-product catalog identity and approved catalog metadata | The specific DataHub logical instance and entity/aspect/field |
| Enterprise application/capability metadata | The specific Turbo EA instance and approved field |
| Cross-system equivalence | Agency-governed integration registry and audited stewardship |
| Model occurrences, views, notation, collaboration and history | ArchiMesh / Archi |
| Architectural relationships and other fields | Explicit versioned rule; unmapped means no transfer |

DataHub is the catalog authority for selected assets, not the owner of physical
database primary keys or underlying business data. Source adapters must resolve
curated/ingested field precedence against the verified OSS release.

Each transferable value must carry its authoritative external reference and
aspect/field, observation evidence, mapping/policy version and transport path.
For example, a DataHub-owned description read through a Turbo EA card still has
DataHub authority; the card is an observed representation. A timestamp from one
repository is not a revision comparable to another repository's timestamp.

Prefer a fresh direct source observation for authority-governed values. A mirror
must not advance a source baseline or overwrite a newer direct observation when
its source evidence cannot be ordered or verified. Diagnose stale/unverified
mirror values. Imported authoritative fields remain guarded against ordinary
model writes regardless of which catalog path supplied the occurrence.

Origin claims in user-editable Turbo EA attributes are hints, not trusted
equivalence or write capabilities. Accept provenance from a registry entry and
verified publication receipt, or a reviewed adoption with direct source checks.
A copied `canonicalId` or DataHub URN alone grants no binding or authority.

### Round trip

For a synthetic DataHub entity `D` and its exact source reference:

1. Reserve `D → C` once, where `C` is its canonical identity.
2. An approved bridge projection reserves a durable target intent for `C`, creates
   or explicitly adopts the compatible Turbo EA card `T`, reads it back and records
   `T → C`. This step requires no model occurrence.
3. Retain `D`, `C` and publication evidence in the registry. Optional integration-owned
   Turbo EA fields expose provenance and a source link if the target metamodel and
   permissions support them. Do not overwrite a foreign `external_id`.
4. Direct DataHub import and import of `T` resolve `C` through the registry before
   allocating a model occurrence. Within one model, reuse its existing occurrence;
   another model receives a distinct element ID bound to `C`.
5. A subsequent DataHub update changes selected DataHub-owned fields in `T` and in
   independently approved model HEADs. The arrival route never changes ownership.

Support exact source-reference resolution in the integration service. Turbo EA's
native ID stays its native ID; this design does not promise that its existing API
can fetch cards by DataHub URN. A native Turbo EA source-URN lookup endpoint would
be a separate, optional upstream change.

### Bridge policies, failures and security

Bridge policy is independent of model publication policy. It names selected source
entities/types, target instance, type/field/relation mappings, visibility scope,
approvers and mapping/policy versions. Default disabled; initially DataHub reads
only, with approved writes to Turbo EA. Reverse DataHub writes require a separate
aspect-safe design and explicit approval scope.

Use dedicated bridge permissions for preview, approval, execution and reconciliation;
model/global administrator roles do not implicitly grant them. Validate that the
target audience may receive the selected source metadata. DataHub service-account
visibility is not permission to disclose all visible entities to Turbo EA or Archi
users. Retraction of access suspends jobs and requires a reviewed downstream
redaction/removal policy; retaining a mirror indefinitely is not a security policy.

Persist reservations and immutable approved intent before dispatch, including the
source, target, canonical identity, content digest, configuration versions and
authorization evidence. Bridge and model writers share per-resource serialization
and uncertainty fences. Do not hold model locks or database transactions over HTTP.
Unknown POST outcomes block further creates until reviewed read-only verification
or explicit reconciliation. No blind retry or automatic adoption by matching name.

Poll source and target with independent checkpoints and bound job sizes. Events
are acceleration hints; suppression uses verified content/baselines and source
provenance rather than timestamps or origin strings alone. Detect target edits to
DataHub-owned fields as drift; block and report them initially, pending approved
reconciliation. Preserve Turbo EA-owned fields and unrelated attributes.

Source deletion, missing authorization and transient absence are distinct states.
Do not cascade hard deletion, recreate missing sources or turn a missing read into
a confirmed delete. Rename/URN replacement requires an explicit reviewed alias or
replacement decision. Loss of registry state requires recovery and reconciliation
before any creates. Cover bridge reservations and identities in the same versioned
integration backup, uncertainty fencing and paused restore procedure.

### Deployment and compatibility

All components remain self-hosted. DataHub OSS runs in an isolated, pinned Compose
project for development/tests; Cloud APIs and features are excluded. Follow the
[D0 environment plan](../../integrations/datahub-turboea/docs/d0-assessment.md).

Do not retrofit DataHub URNs into `CARD` or equate DataHub entity types with the
current Turbo EA-derived canonical kind. Introduce neutral resource descriptors
and a reviewed metadata/archive migration before wiring the new runtime. Preserve
existing canonical IDs, model history, v1 archive readability and uncertain work.
No semantic retyping or automatic reattachment during migration.

## Consequences

The bridge can be tested and operated without Archi clients, and both import paths
share a single identity decision. Repository adapters stay reusable and credentials
remain server-side. The additional work includes model-independent authorization,
durable bridge jobs, neutral authority rules and recovery coverage; a pair of HTTP
clients alone does not implement federation.

The first end-to-end release requires direct/indirect import, concurrent imports,
lost-create-response, source rename/deletion, stale mirror, forged provenance,
restricted visibility and paused restore tests. API/Compose discovery remains D0;
this decision is not evidence that those runtime checks have passed.
