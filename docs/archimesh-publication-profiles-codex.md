# Codex implementation brief — Publication Profiles for ArchiMesh, Turbo EA and DataHub

**Target repository:** <https://github.com/FrodeRanders/archimesh>  
**Working branch:** `codex/turboea-m0`  
**Objective:** Extend the existing ArchiMesh integrations with versioned, declarative **Publication Profiles** that select, transform, explain and govern which architectural information is published, without bypassing the existing M0–M6 security, identity, synchronization and recovery invariants.

> Implement incrementally in the existing repository. This is an implementation assignment, not a request to design a disconnected proof of concept. First inspect the current local branch, including code that may not yet be reflected in the public documentation. Preserve changes made by other contributors/Codex sessions and do not reset or delete development infrastructure or data.

## 1. Why this change is needed

ArchiMesh can model the internal functions, interfaces, services, data objects and ArchiMate relations of applications. Turbo EA is intended to present an organization-wide architecture graph. DataHub catalogs data products, datasets, schemas, quality metadata and technical lineage.

We need publication rules that express which parts of a detailed ArchiMate model contribute to the enterprise architecture and which remain in the modeling repository. In particular, publication should support **derived, traceable application–information usage** without publishing every internal modeling detail.

Example:

- An `ApplicationComponent` is assigned two `ApplicationFunction` elements.
- One function has an ArchiMate `Access` relationship with `READ` access to a `DataObject`.
- The other has `WRITE` access to the same object.
- A profile may produce one consolidated application-to-data-object relationship with effective `{READ, WRITE}` usage and provenance linking back to the contributing function and Access relationships.

This derived result is a **logical/architectural usage assertion**. It must not, without further verified mappings, be interpreted as a technical dataset lineage edge, a DataHub data-product producer/consumer fact, a CRUD `CREATE` or `DELETE` permission, a master-data ownership claim, or an assertion about a physical schema field.

## 2. Repository reconnaissance is mandatory (milestone P0)

Do not assume the earlier published M3/M5 documentation describes today's HEAD. Before editing:

1. Confirm branch, working tree, HEAD SHA and any uncommitted changes: `git status --short --branch`, `git rev-parse HEAD`, `git log -10 --oneline`.
2. Inventory **all** integration-related packages, persistence labels, REST endpoints, configuration schemas, admin UI routes, tests and ADRs: Turbo EA M0–M6, DataHub→Turbo EA, DataHub-related metadata/identity adapters, publication and inbound synchronization.
3. Read `docs/adr/0001-external-repository-integration.md`, `docs/turboea/m3-publication.md`, `docs/turboea/m4-inbound.md`, `docs/turboea/m5-central-edits.md`, any M6 deliverables, `server/examples/turboea-integration.example.json`, `architecture.md`, `crdt-spec.md`, `neo4j/graph-model.md`, `server/README.md`, `docs/project-status.md` and tests.
4. Trace current publication from admin preview/approval through policy persistence, projection/snapshot, planner, worker, remote verification, independent identity registry and replay/recovery.
5. Trace DataHub ingestion and mapping (including DataHub URNs, data-product cards, any datasets, producer/consumer associations, and target-side relation ownership). Locate actual implementation, not just documentation.
6. Run the available fast/unit tests before changes, and record the baseline. Use the project's supported validation scripts; run overlapping Maven gates sequentially.
7. Produce a short **impact matrix** listing every integration/contract that might be affected, current authority, persistence location and tests. If DataHub code is on a different local branch or not available, document that finding; build no speculative integration implementation.

**P0 deliverable:** `docs/adr/<next-number>-publication-profiles.md` (or consistent existing ADR convention), implementation inventory, proposed migration strategy and baseline results. Proceed to implementation without an unnecessary approval pause unless a destructive migration or unresolved contract contradiction is encountered.

## 3. Non-negotiable invariants

- The ArchiMate model, CRDT merge semantics, `HEAD`/immutable tags, Neo4j operation log, accepted receipts and existing Kafka/WebSocket delivery remain unchanged unless a minimal, tested interface extension is required.
- A publication profile does not grant authorization. Effective permissions are the **intersection** of repository/target access, model ACLs, current M3 publication policy, mapping authority, publication profile constraints, and any DataHub ownership rules.
- No remote HTTP calls inside model acceptance, CRDT merge or Neo4j model transactions.
- Existing M2 imports, M3 publication, M4 inbound synchronization, M5 central edits, M6 backup/restore/generation fencing and their invariants continue to work.
- Existing external/canonical identity bindings are authoritative; model properties, names and labels cannot establish or redirect identity.
- Do not use local last-writer-wins to decide ownership between independent repositories or models.
- Unknown/unsupported semantic mappings must fail closed or be reported as skipped with explicit diagnostics. Do not invent generic `Association` fallbacks.
- A profile can reduce the publication surface; it must never elevate field authority, import/catalog visibility, inbound rights, relationship mutation rights or target API privileges.
- Retain the existing durable `IN_FLIGHT`/`AMBIGUOUS` remote-write fences, baseline review and human resolution mechanics. No blind POST/PATCH retry after uncertain outcomes.
- No automatic delete of remote cards, data products or manually owned relations because they disappear from a model or fall out of a profile. Track **withdrawn contributions** separately.
- Keep secret tokens server-side. Avoid logging sensitive data values or raw DataHub metadata payloads in operational/audit records.
- Existing configuration must preserve its behavior when the new feature is not enabled (backward compatibility and disabled-by-default for new derived publication features).

## 4. Scope and semantics of a Publication Profile

Introduce a versioned, declarative, reusable `PublicationProfile` definition. Separate it from per-model/per-target **approved publication policy** and from the target's **capability/authority mapping**.

A profile defines:

1. Stable `profileId`, human-readable name, description, version/schema version and immutable content digest.
2. Source model/reference eligibility (`HEAD` or immutable named `TAG` through existing per-model publication policy; the profile itself must not silently switch the approved source reference).
3. Selectors for eligible ArchiMate element types, relationships, properties/flags and optionally explicit source-defined IDs. Start with a small, typed, deterministic selector vocabulary, not arbitrary executable expressions.
4. Direct mappings to approved Turbo EA metamodel objects and relationship types, with explicit field projection and original ArchiMate semantic type retained as provenance.
5. Optional **derived relations** that navigate a bounded, explicitly defined ArchiMate pattern to produce organization-wide assertions.
6. Required identity strategy, ownership rule, provenance requirement and destination capability checks.
7. Explicit exclusions for diagram geometry, view objects, local-only documentation, internal helper elements and detailed data schemas unless separately approved.
8. Scope/size limits, conflict strategy (`REPORT_AND_STOP` by default), and deterministic normalization rules.
9. Optional target-specific binding names only; never carry credentials or override a target's allowlists.

A model publication policy then references a pinned `(profileId, version, digest)` and still requires existing M3 preview, explicit publisher/model administrator approval, policy version/digest check, and source revision/tag. A reusable profile **is not** an automatic authorization to publish every matching object in every model.

### Suggested conceptual example (not a guaranteed existing JSON schema)

```json
{
  "schemaVersion": 1,
  "profileId": "application-information-landscape",
  "version": "1.0.0",
  "description": "Publish selected application services/functions and logical data use",
  "selectors": {
    "elements": [
      { "archimateType": "ApplicationComponent", "mappingRef": "app-component" },
      { "archimateType": "ApplicationService", "mappingRef": "app-service", "requiresExplicitPublication": true },
      { "archimateType": "ApplicationFunction", "mappingRef": "app-function", "requiresExplicitPublication": true },
      { "archimateType": "DataObject", "mappingRef": "logical-data-object", "requiresExplicitPublication": true }
    ],
    "relationships": [
      { "archimateType": "Serving", "mappingRef": "serves", "requiresApprovedEndpoints": true },
      { "archimateType": "Realization", "mappingRef": "realizes", "requiresApprovedEndpoints": true }
    ]
  },
  "derivations": [
    {
      "ruleId": "application-data-access",
      "kind": "APPLICATION_DATA_ACCESS_AGGREGATE",
      "functionAssignmentRequired": true,
      "dataAccessRelationship": "Access",
      "allowedAccessModes": ["READ", "WRITE"],
      "targetMappingRef": "application-data-access",
      "onUnknownAccessMode": "REPORT_AND_SKIP",
      "retainProvenance": true
    }
  ],
  "limits": { "maxSelectedElements": 20, "maxSelectedRelationships": 20,
              "maxDerivedRelations": 20 },
  "target": { "system": "TURBO_EA" }
}
```

This illustrates **intent**, not permission to assume target type names, route names, field shapes, or API features. Reconcile it with the repository's actual schema and Turbo EA metamodel. Preserve existing M3 selectors and limits by default. If expanding those limits is necessary, make it an explicit separately tested scalability change (bounded paging, performance budgets and administrative review), not a hidden side effect of profile support.

## 5. Separation of concepts and information ownership

Represent at least these distinct conceptual categories in mappings:

| Concept | Meaning | Expected authority |
| --- | --- | --- |
| `ApplicationComponent` / application | Deployable/managed application at architectural scope | Turbo EA for governed enterprise application attributes; ArchiMesh for formal modeled occurrences and local relations |
| `ApplicationFunction` | Internal architecturally significant behavior | ArchiMesh unless a specific shared catalog type and steward is approved |
| `ApplicationService` / `Interface` | Exposed behavior/interface | Explicit mapping/authority, often architecture-managed; do not assume an interface equals a DataHub dataset |
| `BusinessObject` / information concept | Semantic business concept | Shared information governance/glossary, with stable identity |
| ArchiMate `DataObject` | Logical application-level data representation | ArchiMesh or designated shared information model; not automatically a DataHub dataset |
| Data product | Governed package of consumable data assets | DataHub, identified by DataHub URN |
| Dataset/topic/schema field | Physical or technical data asset/shape | DataHub/catalog or source schema repository |
| Data offer | Contract and access channel for a data product | Explicitly modeled if established; do not silently conflate with data product |

`DataObject` is NOT automatically converted to a `DataProduct`, `Dataset`, `BusinessObject` or glossary term. Use explicitly approved semantic links (`REPRESENTS`, `IMPLEMENTED_BY`, `OFFERED_AS`, etc.) with verified identifiers, not name matching. Logical model attributes (UML-like schema, value domains, constraints, nested types) remain in their own versioned model or appropriate catalog; do not create one Turbo EA card for each column/JSON field by default.

## 6. Derived application-to-data usage — first supported rule

Implement `APPLICATION_DATA_ACCESS_AGGREGATE` as a concrete and well-tested derivation, behind a disabled-by-default profile flag.

Inputs:

- A selected `ApplicationComponent` assigned to an `ApplicationFunction` through a semantically valid ArchiMate `Assignment` relationship (validate source/target direction using actual metamodel semantics, not assumed graph edge direction).
- That function participates in an ArchiMate `Access` relationship to a selected `DataObject`, with known access mode: `READ`, `WRITE`, or `READ_WRITE` according to the actual Archi/ArchiMesh representation.
- Any additional source paths must be explicitly defined and validated; no accidental inference from `Association`, `Flow`, `Serving` or name similarity.

Result:

- A deterministic logical assertion keyed by canonical application identity, canonical data-object identity, target, relation semantic type and projection/profile scope. Maintain distinct identities for source model occurrence and enterprise canonical object.
- Effective access is the union of individually evidenced access modes for the approved source contribution set. Preserve `READ` and `WRITE` separately. **Do not infer Create or Delete** from Write, and do not infer data-master/producer/consumer-of-DataHub-product from mere logical access.
- Retain compact but sufficient provenance: `sourceModelId`, selected ref/revision, contributing element/relation IDs, originating function IDs, applicable profile/rule version and content digest.
- If the Turbo EA relation metamodel has a verified `operations`/access attribute, map only the supported values. Otherwise use a deliberately created and approved custom relation/attribute mapping. No hard-coded guesses.
- One application and data object may have multiple independent evidence paths and source-model contributions. A path removal removes only its contribution; recompute the aggregate. If the final contribution disappears, mark withdrawal and use the existing safe, reviewed relation retirement policy; do not delete unrelated or manually managed remote relations.
- An effective relation may be shared with DataHub-derived facts. Reconcile *claims* and provenance by source/authority; do not overwrite a DataHub-owned fact or silently assert both claims equivalent.

Important edge cases: cycles, incorrect direction, duplicate source paths, function reassignment, relation deletion, renamed/retyped entities, changed canonical binding, unknown access mode, competing stewards, conflicting mappings and immutable tags.

## 7. Direct publication mappings and defaults

Propose these initial defaults, subject to metamodel discovery and explicit approval:

| ArchiMate source | Publish? | Notes |
| --- | --- | --- |
| `ApplicationComponent` | Usually yes | Reuse canonical Turbo EA application card; never adopt by name |
| `ApplicationService` | Selected exposed/reusable only | Approved custom card type if target supports it |
| `ApplicationFunction` | Selected architecturally significant only | Avoid flooding enterprise repository with internal implementation detail |
| `ApplicationInterface` | Selected | Link to service/application where semantics are explicit |
| `BusinessProcess`, `Capability` | Selected | Existing mappings may be reused |
| `BusinessObject` | Selected if shared information concept | Link to glossary/information identity if explicitly known |
| `DataObject` | Selected logical objects | Distinguish from DataHub dataset and product |
| `Assignment`, `Serving`, `Realization` | Selected with valid endpoints | Retain semantic type/direction; no implicit generic edge |
| `Access` | Direct or derived according to profile | Derive read/write aggregate only with proven pattern |
| Diagram/view/notation/folders | No | Stay within Archi/ArchiMesh |
| UML field definitions, physical schema fields | No by default | Catalog/modeling source of truth outside Turbo EA |

Selection must use explicit eligibility rules; a source model type alone is not sufficient evidence that a new enterprise-level entity should be created. Preserve access policy and target metamodel constraints, fail/report for unmapped target types, and provide clear dry-run diagnostics.

## 8. Compatibility with DataHub ↔ Turbo EA implementation

This is a required cross-integration concern, **not** a request to rewrite the DataHub connector.

Codex must inspect the existing implementation and document whether it:

- Creates or updates Turbo EA Data Product cards using DataHub URNs.
- Owns selected data product properties (name, description, ownership, lifecycle, domain, classification).
- Writes relationship claims such as `PRODUCES`, `TRANSFORMS`, `CONSUMES`, `DERIVED_FROM`, application-to-product links or dataset links.
- Stores its own mappings, digests, provenance, sync checkpoints and uncertainty markers.
- Has any relation-level ownership/merge semantics shared with the Turbo EA publication connector.

Introduce a stable **cross-connector identity and claim contract** or adapt existing equivalent abstractions. In particular:

1. `urn:li:dataProduct:...` and other DataHub URNs remain canonical **external source identifiers**, not inferred from labels. Preserve exact values; distinguish DataHub URN from ArchiMesh canonical ID and Turbo EA card ID.
2. DataHub-owned data-product attributes remain DataHub-owned. An ArchiMesh publication profile may create a relation **to** an already-resolved DataHub-backed data product if policy explicitly permits it, but must not create a competing product or patch catalog-owned attributes.
3. Architecture-derived `Application → Data Object` usage and DataHub technical `Dataset → Dataset` lineage are distinct assertions with separate types and provenance.
4. A product producer/consumer relation is permitted only when supported by an explicit architecture mapping, an authoritative catalog claim, or an approved transformation rule. Do not infer it from generic `READ`/`WRITE` access to a logical data object.
5. Track ownership and source contributions per **relationship assertion**, not merely per target resource ID. Two connectors must not race to replace each other's relationship attributes.
6. If an identity or relation already exists in Turbo EA but its provenance/ownership cannot be verified, stop with a resolvable conflict. Never claim it automatically.
7. Provide a deterministic reconciliation story when DataHub renames a product, changes its URN mapping, removes a dataset, reassigns ownership, or withdraws a producer/consumer assertion while ArchiMesh has a related architecture claim.
8. Do not emit dataset or column lineage to DataHub through Turbo EA by default. If later required, design a separate, explicit DataHub publication contract, following DataHub metadata and lineage APIs and owners.

Use a **typed assertions model**, such as:

`{subjectCanonicalId, predicate, objectCanonicalId, sourceSystem, sourceResourceIds, sourceRevision, publicationProfileId/version, authority, evidenceDigest}`

as an internal conceptual contract. Reuse/extend existing types rather than adding a redundant parallel identity registry. It is acceptable to implement a provider-neutral assertion/provenance core while keeping provider-specific REST clients and DTOs separate.

### Conflict example

DataHub claims data product `P` is consumed by application `A`. ArchiMesh derives `A READS logical DataObject D`. The two assertions can coexist; they must **not** be deduplicated or equated unless an explicit `D ↔ P` mapping, semantic rule and ownership policy authorize a derived link. A lack of mapping must produce no implicit claim.

## 9. Policy composition and approval

Preserve the M3 approval and least-privilege model:

- Existing `publicationAccess.enabled`, named publisher, model-admin ACL check, target card/relation types, allowed fields and write-risk acknowledgments are mandatory upper bounds.
- A profile is approved for specific model, target and source `HEAD`/tag, with immutable profile version/digest and a deterministic selected source snapshot/derived projection digest.
- Existing `TURBO_EA_MASTER`, `ARCHIMESH_MASTER`, `LOCAL_MODEL_ONLY`, `SHARED_PROPOSAL`, `UNMAPPED` authority semantics remain normative. Publication does not bypass M4 guards or M5 proposal/write-through paths.
- Profile selection must not implicitly enable DataHub writes, DataHub catalog access, Turbo EA central edit permissions, global target scope or additional card/relation types.
- Any change to selector, derivation, mapping or aggregation semantics invalidates an old approval unless a consciously designed and tested compatible migration procedure is used.
- Preserve target metamodel and API version checks at execution time, not only preview time.
- For large models, produce bounded preview with counts, warnings, examples and a stable full-plan digest; protect against uncontrolled graph traversal and huge request payloads.

The administrative preview must distinguish:

- Directly selected objects/relations.
- Derived relationships with source evidence.
- Existing bindings being reused.
- Proposed creations/updates.
- Conflicts/ambiguous remote operations.
- Skips due to filters, missing endpoints, missing mappings, unsupported semantics or insufficient rights.
- Planned contribution withdrawals and any separate review requirement.

Do not make a preview itself change model/target state. No silent re-approval of stale profiles after profile edits or M6 restore/reattachment.

## 10. Projection pipeline and deterministic reconciliation

Prefer a modular pipeline that builds on the existing M3 implementation:

```text
Approved source snapshot (HEAD/tag)
  -> Profile selector
  -> Typed intermediate architecture graph
  -> Direct mappings + derived assertion rules
  -> Canonical identity and source-contribution resolution
  -> Authority/target/metamodel validation
  -> Stable sorted desired projection + digest + evidence
  -> Existing preview/approval + durable reconciliation plan
  -> Existing Turbo EA write worker / remote verification
```

The intermediate representation should carry source element/relation type, IDs and semantic direction. Derived evidence should survive normalization, deduplication and consolidation.

Repeatability requirements:

- Same approved profile/version, same accepted snapshot, mappings and identities → byte-equivalent canonical projection and digest.
- Stable ordering independent of Neo4j traversal order or Kafka notification order.
- Do not derive actual state by replaying raw operations directly into Turbo EA: use accepted materialized snapshots.
- A losing CRDT/LWW update cannot change the exported value.
- Independent model contribution sets combine only under documented ownership/stewardship rules; conflicting claims remain conflicts.
- Profile changes must not implicitly delete remote resources; record contribution differences and reconcile safely.
- Existing M3/M5 remote writer lease/uncertain markers remain shared for writes to a canonical resource. Preserve M6 recovery/generation fences and checkpoint safety.

## 11. APIs and administration

Extend existing `/admin/models/{modelId}/publication/{targetKey}` APIs and the Svelte Publication administration area rather than inventing a second management plane.

Add whichever operations fit current conventions to:

- List/read available immutable profiles and versions.
- Validate a profile against actual current metamodel and target allowances.
- Select/pin a profile on an existing model/target publication policy.
- Preview both direct and derived materialized output.
- Approve with existing policy/version/preview digest controls.
- Show publication and contribution/provenance status per assertion.
- Inspect why an assertion was emitted (source model elements, relationship IDs, derivation rule, selected ref, version) without revealing unauthorized remote metadata.
- Show conflicts involving DataHub-produced target objects or relations.
- Inspect dry-run effects of profile upgrades or withdrawal.

Do not change existing endpoint behavior for clients that do not use publication profiles. Explicitly version any new REST shape, with predictable error responses and bounded payloads. Continue using server-side credentials and restrictive model/target ACLs.

An optional UI convenience is a concise model-side marker to flag an element/function/data object as eligible for enterprise publication. If introduced, define a **reserved, validated property namespace** and ensure it cannot spoof canonical binding or remote authority. Avoid introducing intrusive changes to the Archi editor if server-side profile selection is sufficient for the first iteration.

## 12. Persistence and migration

- Store immutable profile definitions or reference their immutable versioned configuration in the existing integration metadata domain. Document whether administrators manage them through checked-in configuration or persisted records.
- Persist pinned `(profileId, version, digest)` on the approved publication policy and on queued jobs and per-resource contribution/evidence records.
- Keep integration labels/records isolated from Neo4j model snapshot, checkpoint and delete traversals. Respect the M0 ADR's use of `sourceModelId` rather than `modelId` on independent integration metadata.
- Define migration behavior for existing policies with no profile: **legacy explicit-selection behavior remains unchanged**. Do not silently reinterpret them through an implicit broad default profile.
- Enforce schema/config validation, safe rollback, and explicit review before upgrading a profile already attached to live policies.
- Include profiles and their pinned versions in the appropriate M6 portable paused integration backup/restore mechanism if that mechanism exists in the current code; never automatically reactivate restored policies or old writer identities.
- Preserve clean recovery from partially applied remote writes, ambiguous operations and target-side drift.

## 13. Tests and acceptance criteria

Use existing unit/integration test infrastructure, disposable Neo4j/Kafka and synthetic Turbo EA/DataHub HTTP endpoints as appropriate. Keep test data synthetic. Add fixtures for at least two ArchiMesh models, two clients, one Turbo EA target and existing DataHub-backed product metadata.

**Backward compatibility**

1. Existing publication policies without profiles still publish the same approved resources.
2. M2 import, M4 inbound and M5 central edit/request/approval flows remain intact.
3. Existing M6 backup/restore/reconciliation and generation fences still pass.
4. Disabled/unapproved profile has no remote side effects.

**Profile and derivation semantics**

5. A profile selects only approved types/fields/endpoints, and retains target publicationAccess restrictions.
6. ApplicationComponent → Assignment → ApplicationFunction → Access(READ) → DataObject derives `READ` only.
7. Parallel `WRITE` evidence derives `{READ, WRITE}`; no automatic `CREATE`/`DELETE` or `PRODUCES`/`CONSUMES` Data Product assertion.
8. Wrong edge direction, missing function assignment, unsupported access value, missing or unapproved endpoint produces an explicit diagnostic and no fabricated relation.
9. Two functions contribute to one effective assertion without duplicate remote relations; removing one function's evidence updates the aggregate without withdrawing the other.
10. Multiple models contribute distinct provenance; removing one model's evidence never erases another model's contribution.
11. Same source snapshot/profile/mapping version produces stable sorted projection and digest; version change invalidates old approval.
12. HEAD updates do not overwrite newer desired state; TAG publication remains pinned to its immutable revision.

**Authority and DataHub compatibility**

13. A DataHub-owned product's name/owner/metadata cannot be overwritten by an ArchiMesh profile.
14. Product URNs, canonical IDs and Turbo EA card IDs remain distinct and recoverable.
15. Direct `DataObject` publication never silently creates a DataHub dataset/product.
16. DataHub and ArchiMesh relations can coexist with independent provenance; one connector cannot delete or clobber the other's asserted relationship.
17. An unverified existing Turbo EA relation produces conflict rather than automatic adoption.
18. An explicit valid DataObject-to-product identity association permits *only* the mapped, approved architectural connection, not inferred technical lineage.

**Failures, operations and restoration**

19. Lost response after remote POST/PATCH leaves durable ambiguous state and never causes a blind retry or duplicate resource.
20. A new/changed profile cannot bypass baseline, remote drift, authorization or central ownership checks.
21. Service restart, Kafka notification loss, prolonged target outage and restoration recover from a full desired-state reconciliation.
22. Per-target and per-model failures are isolated; ordinary ArchiMesh collaboration continues.
23. Admin preview explains provenance and skips; profile changes and withdrawals are visible before execution.
24. Snapshot/export/restore/rebuild remain consistent and do not accidentally include integration-specific graph records.

Run the repository's actual equivalent of:

```bash
./scripts/validate-local.sh fast
./scripts/validate-local.sh gate
./scripts/validate-local.sh server-isolated
./scripts/validate-local.sh recovery
```

Run gates sequentially if build output paths overlap. Include Svelte admin build and headless plugin/EMF tests. If there is an established DataHub integration test gate, it is mandatory for this change. Record exact commands, pass/fail counts, skipped opt-in tests and unverified live behavior. Do not claim native Archi workbench or production DataHub/Turbo EA validation without running it.

## 14. Suggested delivery milestones

- **P0 – Inventory and ADR:** code map, DataHub/Turbo EA impact analysis, schema, migration plan, baseline.
- **P1 – Profile model and validator:** versioned definitions, deterministic selectors, effective policy intersection, legacy compatibility, tests.
- **P2 – Semantic projection rules:** intermediate graph, direct mappings, evidence/provenance and `APPLICATION_DATA_ACCESS_AGGREGATE`, tests.
- **P3 – Durable publication:** integrate with existing M3 preview/approval/queue/reconciliation, target metamodel checking, migration and replay safety, tests.
- **P4 – DataHub coexistence:** identify/reuse actual existing DataHub/Turbo EA integration contracts, implement cross-connector identity/claim safeguards where needed, regression tests.
- **P5 – Administration and operations:** profile selection, preview explanations, audit, documentation, optional versioned UI, recovery and end-to-end tests.

Keep each milestone independently testable and make logical commits. Do not postpone integration with the actual existing publication service to the final milestone. Avoid gratuitous global refactors or changing the CRDT engine.

## 15. Definition of done

The change is complete when an authorized administrator can attach a versioned publication profile to an existing ArchiMesh M3 policy, preview a deterministic result including derived read/write relations and their evidence, approve it under the existing security boundary, safely publish/reconcile it in Turbo EA, and inspect/update the resulting contributions without breaking the existing DataHub→Turbo EA or Turbo EA→ArchiMesh flows.

Deliver:

1. Implementation in existing modules, with migrations/configuration examples.
2. Versioned profile schema and at least one illustrative profile for application/data architecture.
3. Documentation of supported derivations, excluded semantics, authority model and limitation boundaries.
4. ADR and explicit DataHub integration impact analysis.
5. Unit, integration, recovery and compatibility tests.
6. Admin preview/approval/status support.
7. Exact validation results, known remaining risks, and an operator-safe profile-upgrade/rollback procedure.

**Primary design criterion:** Publication Profiles are controlled architectural *projections*, not additional repositories of truth. ArchiMesh retains formal models and CRDT history; Turbo EA retains the consolidated enterprise architecture graph; DataHub retains catalog metadata, datasets, products and technical lineage. Stable identity, explicit authority and source-level evidence bind these views together.

## 16. Relevant existing references

Inspect the current local source first; these branch documents describe the previously published implementation and may have been superseded by newer local commits:

- <https://github.com/FrodeRanders/archimesh/tree/codex/turboea-m0>
- <https://raw.githubusercontent.com/FrodeRanders/archimesh/codex/turboea-m0/docs/adr/0001-external-repository-integration.md>
- <https://raw.githubusercontent.com/FrodeRanders/archimesh/codex/turboea-m0/docs/turboea/m3-publication.md>
- <https://raw.githubusercontent.com/FrodeRanders/archimesh/codex/turboea-m0/docs/turboea/m4-inbound.md>
- <https://raw.githubusercontent.com/FrodeRanders/archimesh/codex/turboea-m0/docs/turboea/m5-central-edits.md>
- <https://raw.githubusercontent.com/FrodeRanders/archimesh/codex/turboea-m0/server/examples/turboea-integration.example.json>

