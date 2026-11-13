# DataHub OSS ↔ Turbo EA ↔ ArchiMesh implementation assignment

Date: 2026-11-12. Status: D0 Compose/API harness and the
[D1 contract/authority increment](d1-contracts.md) and [durable identity/classification
increment](d1-registry-migration.md) are implemented. The [source/owner boundary](d1-owner-boundary.md)
adds authenticated source dispatch, model-independent registration and local field
protection. The [target-intent ledger](d1-target-intents.md) adds durable dispatch
fences and atomic verified binding. The [dataset slice](dataset-bridge.md) adds
standing scoped approval, polling, governed target execution and mirror-backed
ArchiMesh import/refresh. Products, direct DataHub model import and the reverse
application lane remain pending; this does not complete D2–D4.

This project takes responsibility for the DataHub–Turbo EA integration as well
as integration of both repositories with Archi/ArchiMesh. It extends the
[original Turbo EA assignment](../../../docs/archimesh-turboea-integration-codex.md), the
[D0 assessment](d0-assessment.md) and
[ADR 0002](../../../docs/adr/0002-three-repository-federation.md). ADR 0001 and existing M0–M6
safety/recovery contracts remain in force.

## Scope and first deliverable

Build a reusable DataHub OSS adapter and a model-independent bridge to Turbo EA,
with one shared canonical identity registry and field-authority policy. ArchiMesh
is a consumer of these modules and retains its own model acceptance pipeline.
The bridge is a separately built/tested subproject in `integrations/datahub-turboea/`,
with its own infrastructure, tooling, tests and operations documentation. Ordinary
ArchiMesh builds must not depend on bridge internals. Shared adapters/contracts
must be reusable through a versioned boundary. The bridge must not require an
Archi model to publish an approved DataHub asset into Turbo EA.

The first working slice is read-only toward DataHub and explicitly approved toward
Turbo EA for the dataset/product lane. The required reverse lane reads enterprise
applications from Turbo EA and writes approved application references/representations
in DataHub, using separate permissions. See [source directions and semantics](authority-directions.md).
The dataset/product lane covers selected datasets and governed data products when verified in
the target OSS version. It must support importing an asset directly from DataHub
and through its Turbo EA representation without duplicating its canonical identity
or an occurrence already imported into the same model.

All deployment is local/self-hosted. Docker Compose is the development/test
topology. No Cloud dependencies, arbitrary automatic bidirectional field mirroring,
name matching, automatic cross-system merges or cascade hard deletes.

## Module contract

Use the dependency boundaries in ADR 0002. Names below describe responsibilities,
not classes that already exist or a requirement to create one class per item.

| Boundary | Input / output | Must not own |
| --- | --- | --- |
| Identity registry | Exact external references ↔ canonical identities; reviewed alias/adoption evidence | HTTP, model revisions, semantic equivalence inferred from names |
| Source observation port | Selected entity/aspect fields, visibility/lifecycle, source evidence | Target mutations or ArchiMate assumptions |
| Authority/mapping planner | Verified observations + policy + baseline → typed plan or conflict | Network, storage writes or scheduling |
| Target repository port | Validated read/write capabilities, intent/result and readback | Source authority decisions or model ACLs |
| Bridge application | Approval-bound plans, durable execution, reconciliation and audit | Model existence, EMF, HEAD/tag selection |
| ArchiMesh binding application | Canonical resolution → occurrence reservation → accepted ordinary operations | Repository-specific HTTP/DTOs |
| Persistence/runtime | Atomic reservations, CAS jobs, shared fences, credentials and scheduling | Redefining repository semantics or model commit behavior |

Keep DataHub aspect DTOs and Turbo EA card DTOs within their adapters. Core value
types need exact native references, canonical semantic kind, external type,
field authority and observation provenance. Do not force aspects into card
attributes or lineage into standalone relations solely to fit the existing API.

The bridge and existing publication/central-edit workers must share the same
canonical resource lanes and uncertainty fences. Migration must preserve the
old Turbo EA paths rather than leave two independent synchronization engines.
Archi clients and browser code only call the server, using server-scoped credentials.

## Identity and field rules

- DataHub remains source authority for selected cataloged datasets/products and
  selected metadata. The full source reference includes its logical instance and
  exact URN. Turbo EA allocates a separate native representation ID.
- The agency-governed registry binds both to one canonical identity when equivalent.
  Preserve existing canonical IDs and per-model occurrence IDs. Separate canonical
  meaning from repository type names before connecting a third repository.
- A data product, its datasets and a conceptual object about them are distinct
  unless a reviewed semantic mapping establishes equivalence for a representation.
- Rules select authority per source instance/entity/aspect/field and per relationship.
  ArchiMesh model geometry, local documentation and history remain model-owned.
- A DataHub-owned value keeps its authority through Turbo EA. An editable source
  URN/canonical field is a provenance hint requiring registry verification, not a
  credential. Source visibility must authorize the downstream audience.
- A fresh direct observation must not be overwritten by an unverified stale mirror.
  No cross-system clock ordering is inferred from last-modified timestamps.
- URN changes, source replacement, conflicting preexisting canonicals and lost
  mappings require reviewed reconciliation; automatic creation/merge is blocked.

## Delivery sequence

The D0–D4 names from the assessment are retained; each stage has a concrete exit.
Keep writes disabled until its acceptance evidence exists. This document records
the expanded scope, not completion of any stage.

### D0 — runnable self-hosted contract evidence

Implement the isolated pinned DataHub OSS Compose environment, synthetic fixture,
authenticated API probes, restart probe and scoped cleanup from the D0 plan.
Record the selected release and contracts for dataset/product URNs, aspects,
visibility, pagination, lifecycle and effective field precedence. Pin a test release
as a development baseline when the agency version is unknown; record the distinction.
Verify the local Turbo EA metamodel for the two chosen representation types.

Exit: repeatable bring-up/seed/read/restart/cleanup; no host credentials mounted;
API evidence captured without secrets; unsupported OSS capabilities reported.

### D1 — neutral contracts and compatible state

Introduce the module boundaries, connector dispatch and source-aware authority.
Separate canonical semantic kind from external entity type; add entity/aspect-aware
references and validated identifier limits without truncation. Wire canonical
authority into actual field guards and planners, not merely configuration types.

Add model-independent source/target reservations and bridge policy/job state.
Version schema/archive changes, including alias and provenance records. Define
bridge permissions and named scopes without borrowing a model-admin capability.
Implement reviewed paused migration of existing Turbo EA metadata and legacy
backups, retaining IDs and unknown-outcome fences.

Exit: core unit tests need no repositories or Archi services; adapters can be
substituted at ports; existing Turbo EA regression and archive recovery tests pass;
one registry is shared by both model and bridge applications.

### D2 — DataHub catalog and direct ArchiMesh use

Provide approved search/preview, exact-URN resolution, import/link and inbound
refresh through ordinary accepted operations. Retain source authority, type and
provenance; no DataHub write credential required. Demonstrate one asset in two
model HEADs and two views, with distinct occurrences and unchanged historical tags.

Exit: pagination/visibility tested; concurrent imports reserve one occurrence per
model; source-owned local edits and offline replay are rejected through existing
client recovery; source outages do not block normal model commits.

### D3 — governed projections in both directions and round trip

First run the bridge without any model or Archi client. Preview selected source
entities, type/field projection and downstream audience; approve immutable intent;
create or explicitly adopt the compatible Turbo EA representation. Persist binding
and source provenance after readback. Then exercise both import paths in either
order, reusing existing canonical and occurrence identities.

Also implement the required Turbo EA → DataHub application-reference lane. Selected
application identity/name/description/lifecycle retain Turbo EA authority. Verify
the OSS target representation and semantic profile, preserve foreign aspect members
and keep the application writer separate from dataset/product writers. Reference
associations do not merge application and dataset identities. Both source directions
must reconcile without reflecting mirrored edits back as authoritative updates.

Use explicit relation mappings and contribution ownership. Dataset membership,
technical lineage and architectural PRODUCES/CONSUMES have separate meanings;
unsupported relations are diagnosed. Do not imply that a product contains only
one dataset or that an abstract business object is a technical dataset.

Exit: the matrix below passes, bridge policies operate independently of model
policies, durable jobs recover, and model/bridge workers honor the same fences.

### D4 — operational completion and optional reverse writes

Complete bridge diagnostics, source/target reconciliation, audit, paused backup and
restore, alias/adoption administration and cross-system recovery drills. Notifications
may accelerate polling after their contract is verified.

Edits back to DataHub-owned dataset/product metadata are a separately enabled
extension; the required application-reference lane is already D3 scope. Source
edits use approved owned aspects or fields only, preserve other references, test authorization and readback, and
distinguish technical MCP submission from human approval. The documented
[DataHub API acknowledgement behavior](https://docs.datahub.com/docs/api/datahub-apis)
requires verifying durable state rather than treating async acceptance as success.
Do not write directly to DataHub's internal mutation topic.

Exit: the initial read-only-source bridge has an operations/recovery runbook and
tested disaster recovery. Optional reverse writes are explicitly listed as enabled
and tested, or as unimplemented; they are not implied by the word integration.

## Required D3 acceptance matrix

| Scenario | Required result |
| --- | --- |
| Publish one source with no Archi models | One approved Turbo EA representation; registry binding and readback evidence |
| Import DataHub first, then Turbo EA; reverse the order | Same canonical identity and one active occurrence in the model |
| Import into a second model | Different element ID, same canonical identity; both can have multiple views |
| Concurrent bridge/direct imports | Atomic source/target reservations; no duplicate canonical or active target |
| Repeat scan or duplicate event | No new resource or model revision for unchanged verified content |
| Change DataHub-owned description | Approved Turbo EA field and model HEADs updated; ownership and tags preserved |
| Edit a Turbo EA-owned architecture field | Preserve it across DataHub dataset refresh; export only through an explicitly approved application/reference mapping |
| Update a Turbo EA-owned application name | Approved DataHub application reference follows it; datasets/products keep their own IDs and metadata |
| Edit an application mirror label in DataHub | Diagnose drift; do not make it authoritative over the Turbo EA source |
| Application writer attempts dataset edits | Denied; separate source/target and association scopes are required |
| Drift on a DataHub-owned mirror field | Report/block pending approved reconciliation; never make Turbo EA authoritative |
| Deliver stale mirrored observation after newer direct read | No source-baseline advancement or stale overwrite |
| Forged/copy-pasted source identity in another card | No automatic adoption, canonical merge or source authority grant |
| Existing distinct canonical IDs | Explicit identity conflict; reviewed migration required |
| Two repositories reuse a native ID or display name | Separate identities by logical instance; no name matching |
| URN exceeds supported bounds | Clear validation error; never truncate or hash in place of the native reference |
| Timeout/crash after target create, before receipt | Durable uncertainty fence; no blind POST; reviewed exact recovery |
| Bridge and model write collide | Shared lane/fence serializes approved work; no authority bypass |
| Source hidden/missing/soft-deleted | Distinct diagnostics; no inferred hard delete or automatic recreation |
| Data product and member dataset imported | Distinct canonical identities; membership retained as a relationship |
| Restricted source metadata, broader target visibility | Publication denied/redacted by explicit scope, including IDs/provenance |
| Restore registry and models from backup | Starts paused, preserves identities/fences, verifies source and target before resume |

## Implementation status and next action

Existing M0–M6 supplies Turbo EA integration, durable metadata and recovery building
blocks. D0 tooling and its [contract evidence](d0-contract-evidence.md) are in
this subproject. D1 has started with the [shared contracts and effective canonical
authority](d1-contracts.md) and [durable state/classification](d1-registry-migration.md);
the [source/owner increment](d1-owner-boundary.md) now adds read dispatch, authenticated
registration and configured source-field protection. The [target-intent increment](d1-target-intents.md)
adds durable shared lanes and verified completion. Approval/job capabilities,
alias/replacement lifecycle and source-aware inbound baseline handling remain. Existing native two-workbench
and live deployment recovery acceptance gaps
remain as recorded for Turbo EA; the expanded scope does not close them.
