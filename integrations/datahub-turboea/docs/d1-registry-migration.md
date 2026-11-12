# D1 — durable identity registry and reviewed classification

Date: 2026-11-12. Status: the durable registry, semantic/native-type separation and
v2 archive increment are implemented. D1 remains in progress: connector dispatch,
source-aware runtime policy, bridge permissions/jobs and target-create uncertainty
handling are still required before enabling either bridge direction.

Update: the [source/owner boundary](d1-owner-boundary.md) and
[target-intent ledger](d1-target-intents.md) now supply authenticated read dispatch,
registration, configured local field protection and shared uncertainty handling.
The remaining-work list below describes this earlier registry increment; governed
approval/jobs, inbound baselines and alias/replacement lifecycle remain outstanding.

## One registry owner

`IdentityRegistry` is a shared, dependency-free persistence port. Its Neo4j adapter
uses the **existing** integration labels, composite uniqueness constraints, driver
and owner instance; it does not open another identity database or start a worker.
Obtain it internally through `IntegrationStateRepository.identities(instance)`.
There is no public source-registration/adoption API or new bridge service principal
yet. The host must authorize callers, verify original source reads and use the
existing owner coordination before invoking this trusted persistence port.

The port supplies:

- Atomic source reservation without a model ID, with concurrent candidates
  converging to one canonical identity.
- Explicit representation binding to an already classified canonical identity,
  rejecting conflicting bindings, native type changes, semantic kind changes and
  registry-owner changes. It never merges existing canonical objects.
- Exact lookup through either native reference, retaining the canonical ID,
  semantic kind, repository entity type and provenance record.
- Immutable correlation/evidence checks and durable association audit records.
  Keep the same candidate/correlation/evidence when retrying a reservation; a
  changed request cannot reuse a correlation. After a lost response, exact lookup
  can recover the committed binding without creating another identity.

One active owner remains authoritative. Bridge clients must eventually use its
versioned authenticated boundary, not instantiate an independent adapter/driver
against the same database. The adapter's primitive identity transactions are not
human approval or remote write capabilities.

Both source directions use the same port. A Turbo EA application can be reserved
as `enterprise.application`, then explicitly associated with a reviewed DataHub
representation/reference. A DataHub dataset can separately be reserved as
`catalog.dataset` and represented in Turbo EA. Their identities never merge merely
because an application uses the dataset. See the [direction decision](authority-directions.md).

## Stored types and native IDs

Canonical `semanticKind` is separate from an external binding's `externalType`.
Legacy canonical `kind` is retained for compatibility/history; readers select the
explicit semantic kind when classified. Existing Turbo EA runtime consumers now
use the binding/publication's native type for metamodel, field mapping and live
type checks, rather than interpreting semantic kind as a Turbo EA type key.

Native card/relation IDs retain their existing storage keys. Generic entity
references use `ENTITY` with the complete native ID, limited to 8192 UTF-8 bytes,
stored in `nativeId`. An internal `resourceId=sha256:<digest>` supplies a bounded
index key. It is not a replacement native ID and is never returned as one by the
registry. Reads/reservations compare the stored full native ID; inconsistent index
content fails closed. No name matching, truncation, case folding or URN rebuilding.

The canonical/system/instance/resource-kind uniqueness constraint still allows
only one active representation in each slot. Renaming/replacing a source requires
a future reviewed alias/replacement operation; this increment supplies no alias,
automatic reassignment or canonical merge API. Non-Turbo EA aspect-reference
relationships also require an explicit adapter identity contract before registration.

Model lookup through either registered reference returns the same canonical model
occurrence. A repeated reservation may reuse that occurrence while retaining its
original primary binding and mapping version. This does not authorize a second
source's fields or rewire an active model's source. Unsupported generic-entity
model edits are guarded until source-aware runtime policy exists. The DataHub UI
import path is not enabled by this storage capability.

## Classifying legacy objects

Schema installation adds only the registry-owner index (`schema-v4.cypher`). It
does not assign semantic kinds or replace IDs. New ordinary Turbo EA bindings can
record a native type; classification remains explicit.

The operator API adds two endpoints under `/admin/integrations`:

```text
POST /identities/preview
POST /identities/classify
```

Example preview body:

```json
{
  "canonicalId": "urn:agency:application:4711",
  "semanticKind": "enterprise.application"
}
```

The caller must hold both the global integration-operations permission and the
named operator scope. Global integration pause is mandatory. Preview includes the
bounded metadata closure, old native type, affected models, control version and
fresh configured Turbo EA card/metamodel observations. Classify sends the same
canonical ID/semantic kind plus the returned **outer** `digest`; remote or metadata
changes invalidate it. This is a classification of the same object, not a model
retyping operation or permission to equate a software application with a dataset.
The public workflow currently supports card/object classification, not relation
retyping or source-instance replacement.

Within one metadata transaction, classification:

1. Rechecks pause, owner scope, expected digest and the complete bounded closure.
2. Rejects cross-instance bindings and unresolved in-flight, ambiguous or pending
   work. It cannot erase an uncertainty marker to make migration possible.
3. Preserves canonical/model/external IDs, native IDs, accepted receipts and model
   contents; adds semantic/native type metadata and an actor/digest audit record.
4. Pauses affected model scopes, disables inbound/publication policies, clears
   baselines and quarantines old execution generations/requests. Original intent
   and correlations remain available for audit.

Afterward, inspect/export the new state, resume global operations, review model
attachments, approve fresh policies and explicitly establish/accept baselines.
Old central/publication requests do not revive. New imports, verified central
edits/fan-out and publication continue using native type keys while the canonical
ID and semantic kind remain stable. No snapshot, tag or accepted model operation
is rewritten by classification.

## Archive compatibility

Exports now use `archimesh-integration-v2`; original v1 packages remain readable
with their original format-specific digest. A v1 label cannot carry new v2 fields.
The UI uses a version-neutral download filename.

V2 validates native/index consistency, semantic/native types, registry ownership
and provenance closure. Registry-owned identities and all their external bindings
are captured even when no model refers to them. Missing proofs/native types or
conflicting globals fail atomically. The same 20,000-node / 8-MiB portable limits
apply; database-native backup remains the unbounded recovery path.

Restore requires an empty, paused owner scope, preserves unknown outcomes and
keeps policies/model scopes paused with execution capabilities quarantined. Identity
proofs survive as historical association evidence, not revived write approval.
Losing a binding/proof requires reconciliation; it must not trigger another blind
remote create.

## Validation and remaining work

Tests cover concurrent long-URN registration/reopen, different native types sharing
one canonical/model occurrence, both source directions, conflicting identity/kind/
owner/correlation, inconsistent index content, model-less registry restore and
retained unknown outcomes. Migration tests cover pause, stale/changed remote
reviews, ambiguity blocking, unchanged IDs/tags, explicit baseline review, fresh
post-classification imports, central fan-out and publication. V1 import and v2
native/proof validation are covered separately.

Still required before bridge execution: authenticated bridge permissions, durable
target **create intents** before non-idempotent dispatch, shared uncertainty lanes,
job approval/recovery, alias/replacement lifecycle, connector dispatch and exact
source/aspect/field/disclosure enforcement. Binding a known representation is not
a target-create reservation or a guarantee about a lost POST response. Existing
Turbo EA M3/M5 fences remain in force; no new bridge dispatch is enabled here.

Final isolated server gate: `ff2ab6b7-4b5d-400e-ad26-56748f8c5b70`, 327 discovered,
323 passed, zero failures/errors, four skipped deployment probes. Reports and
cleanup evidence are under the root's ignored `server/target/isolated-server-gate/<run>/`.
The standalone core build/scenarios, root fast checks, four Python tool tests,
seven bridge harness tests and frontend build passed. The SDK JAR depends only on
`java.base`. The separate OSS application-reference/restart probe also passed;
its evidence is linked from the [D0 report](d0-contract-evidence.md).
