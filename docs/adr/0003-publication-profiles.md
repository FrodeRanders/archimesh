# ADR 0003: Pinned publication profiles and evidenced architecture assertions

Date: 2026-11-16. Status: accepted implementation direction. Baseline:
`77ee9f086b3b8dbb0ee47367f1cb465d51823d1b` on `codex/turboea-m0`.
Delivery brief: [Publication Profiles](../archimesh-publication-profiles-codex.md).
Milestone evidence: [publication-profiles.md](../publication-profiles.md).

## P0 findings and impact matrix

The only initial uncommitted file was the user-supplied implementation brief.
Older M3 prose allowing HEAD publication is superseded by `91d7aa3`: preview,
enqueue and execution require an explicitly approved immutable TAG. Profiles
must preserve this restriction. They cannot introduce an alternative HEAD writer.

| Contract / implementation | Current authority and persistence | Affected checks |
| --- | --- | --- |
| `ArchitectureProjection`, `PublicationModel` | Accepted snapshots; at most 20 explicit elements and 20 relationships; type/field mapping intersection; no geometry | `ArchitectureProjectionTest`, `ArchitecturePublicationIT` |
| `ArchitecturePublicationService`, `PublicationWorker` | Model-admin ACL plus named target publisher; digest/version-bound policy, materialized plan, periodic reconciliation | Publication and authorization ITs |
| `IntegrationCanonicalObject`, `IntegrationExternalBinding`, `IntegrationIdentityClaim`, `IntegrationPublicationIdentity`, `IntegrationModelBinding` | Existing registry resolves exact canonical/native/occurrence IDs; name, URL and editable provenance never establish identity | Foundation, catalog, migration, canonical authority, federation ITs |
| `FIELD_AUTHORITY`, mapping `canonicalAuthorities`, `ExternalFieldGuard`, `FederatedFieldPolicy` | Field-specific ArchiMesh/TurboEA/DataHub/shared/local authority; imported catalog fields remain guarded | Authority, inbound, central edit ITs |
| `PUBLICATION_POLICY`, `PUBLICATION_JOB`, `CHECKPOINT`, `PUBLICATION_RESOURCE`, `CONTRIBUTION`, `CONFLICT` | Independent `IntegrationStateDocument` records with CAS; one steward for ordinary resource fields; withdrawals retain remote resources | Publication, operations, archive ITs |
| `RemoteMutationCoordinator`, writer lease, `CENTRAL_LANE`, `FEDERATION_LANE` / `FEDERATION_INTENT` | Shared process lock and durable cross-writer uncertainty fences; no blind redispatch | Central edits, target ledger, publication, projection ITs |
| `DatasetProjectionService` / standalone Python worker | DataHub datasets and products project **name and description** to TurboEA. Exact DataHub URN, agency canonical ID and TurboEA ID remain distinct. Membership/schema/lineage/owners are not projected as architecture relationships | `DatasetProjectionIT`, independent Python/HTTP tests, disposable DataHub gate |
| `ApplicationProjectionService` | TurboEA → native DataHub Application; reviewed adoption, optional lifecycle custom property, preserved foreign members; separate application credentials | `ApplicationProjectionIT`, live demo |
| DataHub relationship claims | No running producer/consumer, lineage, dataset-membership or application-association writer in the bridge. Existing DataHub associations are preserved. No relation-claim merge contract exists yet | Coexistence tests must prove separation rather than invent a DataHub relationship writer |
| M2/M4/M5 | Existing catalog/import/link, accepted inbound operations, proposal/write-through and independent fan-out | Catalog, inbound and central edit ITs |
| M6 `IntegrationOperationsService`, `IntegrationArchive` | Pause, model incarnation, attachment review, configuration fingerprints, bounded v1/v2 portable archive, restore-disabled policies and quarantined jobs | Operations/archive ITs and packaged recovery drill |
| `/admin/models/{modelId}/publication[/{targetKey}]`, Svelte `publication` route | Existing preview/approval/status/run/inspection/baseline management plane | REST ITs, Svelte production build |
| Core model/CRDT/Kafka/WebSocket/receipts/checkpoints | Formal model and timeline authority; independent delivery and recovery | Fast/client/server gates, isolated server, packaged recovery |

Additional inspected material: ADRs 0001/0002, M0–M6 deliverables, current target
example, architecture/CRDT/Neo4j/server documentation and project handoff. The
handoff is historical; current local code and tests are the implementation baseline.

Current semantic relationship snapshots do not persist a native Access enum.
The demo carries access flags as ordinary versioned relationship properties.
Derivation will therefore require an explicitly configured, validated access-mode
property and diagnose missing/unknown evidence. It will not guess READ from a
relationship name, geometry, notation or absent mode. The profile example uses
`archimesh.publication.accessMode`, represented with existing SetProperty ops;
no new CRDT field or protocol is required.

## Decision

1. Keep reusable, immutable versioned definitions in an opt-in checked-in profile
   catalog. Pin ID/version/content digest in the existing policy, queued plan and
   contribution records. An absent pin uses the unchanged legacy projector and
   byte-compatible legacy serialization/digests. Enforce the existing TAG rule.
2. Profiles only reduce explicit M3 candidate selections. Direct mapping references
   name existing target mappings, and field selectors intersect target allowlists
   and effective canonical authority. Explicit creation permission is separate
   from selecting a bound reference. A profile grants no privilege.
3. Build a bounded typed snapshot graph with deterministic diagnostics/evidence.
   Support the exact directed ApplicationComponent → Assignment →
   ApplicationFunction → Access → DataObject pattern. READ and WRITE form a set;
   READ_WRITE expands to both. No CREATE/DELETE, catalog ownership, product usage
   or technical lineage follows from that pattern.
4. Use a provider-neutral assertion/evidence contract in the existing shared core.
   A derived assertion's identity uses canonical endpoints, predicate, target,
   profile/rule scope and explicit mapping semantics. Source model occurrences
   and evidence paths remain separate contributions. Use the existing registry;
   do not manufacture formal model relationships or a parallel identity registry.
5. Reuse existing publication jobs, resource baselines, shared mutation lock and
   uncertainty fences. Add only narrow, explicitly validated relation-attribute
   writes to the existing target port. Validate the actual relation schema and
   allowed access values at execution. Unknown/manual target relations require
   explicit review, never automatic adoption.
6. Aggregate only compatible, active, approval/generation-checked source claims.
   Preserve independent model evidence and foreign connector claims. Withdrawal
   changes contribution state; remote deletion is never inferred. A final
   withdrawal remains visible for separate retirement review.
7. Bound DataHub-backed references must reuse registry identity and target card
   type. Profiles never patch catalog-owned fields, create competing products or
   reinterpret logical DataObjects as products. Architectural and catalog claims
   remain typed and distinct even when an explicit direct connection is allowed.
8. Extend existing publication REST/UI, including explainable diagnostics,
   immutable pin selection, provenance and withdrawals. Export independent profile
   records through M6; restored execution remains disabled/quarantined. Changes
   to profile content cannot silently reapprove existing policies.

## Migration and rollback

No core-model migration, label rewrite, data deletion or development infrastructure
reset is required. Optional JSON fields retain old constructors/read compatibility
and are omitted for legacy policies/plans. New document kinds remain under the
existing independent metadata label and `sourceModelId` scope. Keep profile
definitions with the integration backup and retain old versions for inspection.

Enabling a profile requires a fresh M3 preview and approval. Changed versions or
content require new approval; removing/disabling definitions blocks queued work.
Do not execute old unpinned jobs through a default profile. On restore, verify
profile closure/content and retain pins while disabling capabilities. Operators
must attach models and reapprove under the configured profile before resuming.

Rollback: pause integrations, retain matching model/integration backups, disable
profile feature flags and approve a legacy explicit selection if appropriate.
Never drop assertion/contribution records or uncertainty fences to downgrade.
An old binary unaware of new metadata kinds cannot restore a new archive; use
the retained pre-upgrade archive or a version-aware binary.
