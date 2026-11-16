# Publication Profiles delivery and operations

Implementation brief: [specification](archimesh-publication-profiles-codex.md).
Identity, ownership and migration decision: [ADR 0003](adr/0003-publication-profiles.md).

## P0 baseline

Branch `codex/turboea-m0`, HEAD `77ee9f0`; initial working tree contained only the
untracked implementation brief. No development services or data were reset.

| Command / check | Result before profile changes |
| --- | --- |
| `./scripts/validate-local.sh fast` | PASS: notation parity and hardening |
| `./scripts/validate-local.sh recovery` | PASS: packaged JVM crashes, broker outage, interrupted transactional restore, stale barriers and rebuild; cleanup passed |
| `./scripts/validate-local.sh gate` | Existing client failure: 80 discovered, 79 passed, one error; missing `ModelSelectionHandler$IModelSelectionHandlerListener` at runtime. Server stage not reached |
| `mvn -q '-Dtest=*Test' test` in `server` | 224 discovered; 162 passed, zero failures/errors, 62 infrastructure/opt-in skips |

The client failure is a test-classpath omission: the plugin already compiles
against the prepared Archi editor bundle, but `client-tests` lists only the model
bundle. P0 adds that existing editor bundle to the headless test classpath. It
does not change the plugin, model or UI behavior. Baseline logs are in
`/tmp/archimesh-profiles-p0-{fast,gate,server-unit}.log`.

After the classpath repair, `./scripts/validate-local.sh gate` passed: all 80
headless client/EMF tests, plus 224 server cases (162 passed, 62 explicit skips).
Evidence: `/tmp/archimesh-profiles-p0-gate-repaired.log`.

## Milestones

P0 inventories the current integrations and migration boundary. P1 introduces
opt-in immutable profile definitions and policy pins. P2 implements deterministic
semantic projection and evidence. P3 integrates durable publication and shared
claims. P4 hardens DataHub coexistence. P5 exposes administration, recovery and
operator workflows. Exact validation results are recorded at each milestone.

## P1 — immutable definitions, selectors and pins

Profiles are opt-in through `ARCHIMESH_PUBLICATION_PROFILES_ENABLED` and
`ARCHIMESH_PUBLICATION_PROFILES_CONFIGURATION`. The latter names a non-secret
catalog conforming to [v1 JSON Schema](../schemas/publication-profile-v1.json);
the [example](../server/examples/publication-profiles.example.json) is illustrative,
not authorization to add target types or fields. Derivations remain disabled in
the example. A policy uses `profile: {profileId,version,digest}`; the digest is
computed from the normalized immutable definition.

Selectors filter existing explicit M3 candidate IDs. They intersect target
mapping refs, allowlisted fields and effective canonical authority, and cannot
raise M3's 20-element / 20-relationship limits. A reference-only selector requires
an existing registry-backed target representation. Conflicts are visible during
preview and cannot be approved. Unsupported/filtered source entities receive
deterministic diagnostics. Unpinned policy/plan serialization retains the old
shape, constructors and projection behavior; TAG-only approval is unchanged.

P1 validation: `mvn -q
'-Dtest=PublicationProfileTest,ArchitectureProjectionTest,IntegrationFoundationTest,IntegrationConfigurationDigestTest'
test` in `server`: **19 passed**, zero failures/errors/skips. The example also
passed the draft-2020-12 JSON Schema validator. Evidence:
`/tmp/archimesh-profiles-p1-tests.log`. Derived rules and durable reference/claim
execution are added in P2/P3, rather than silently ignored when enabled.

## P2 — semantic graph and evidenced logical access

`ProfileGraph` indexes accepted semantic elements, directed relationships and
materialized property values, with explicit graph/property limits. The first rule
matches only ApplicationComponent → AssignmentRelationship → ApplicationFunction
→ AccessRelationship → DataObject. Functions are evidence, not automatically
published cards. Configure the source Access property explicitly; the example
uses `archimesh.publication.accessMode`. Supported values are READ/Read,
WRITE/Write and READ_WRITE/ReadWrite. Unknown values, missing assignments,
incorrect direction, cycles outside the pattern, missing endpoints and catalog
assets are diagnosed and never fabricated into a relationship.

The shared dependency-free `ArchitectureAssertion` contract preserves predicate,
canonical endpoints, target/profile/rule/mapping scope, exact source IDs, ref,
revision, profile digest and each contributing assignment/function/Access path.
Parallel evidence consolidates to one deterministic READ/WRITE set. Removing a
path removes only its evidence; multiple models share assertion identity but have
different source occurrences and evidence digests. No CRUD CREATE/DELETE,
DataHub product producer/consumer, ownership or lineage fact is inferred.

The sum of direct and derived output relationships stays within M3's existing
20-relation ceiling. Evidence is bounded to 128 paths per assertion and the plan
retains the existing 180k limit. Profiles must explicitly enable derivation;
P2 previews are available through M3; P3 adds the durable writer before
enabling approval of derived assertions.

P2 checks: `mvn -q
'-Dtest=PublicationProfileTest,ProfileDerivationTest,ArchitectureProjectionTest,FederationCoreTest'
test` in `server`: **19 passed**, zero failures/errors/skips.
`python3 shared/federation-core/test.py` also passed, including provider-neutral
predicate separation and evidence normalization. Evidence:
`/tmp/archimesh-profiles-p2-tests.log`.


## P3 — durable shared assertions

The existing M3 policy, queue, writer lease, target validation, polling repair,
remote mutation coordinator, baselines and ambiguity fences execute profile
plans. Approval retains the immutable definition in `PUBLICATION_PROFILE`; jobs
and contributions carry the exact pin. Legacy policies remain unpinned.

`ASSERTION_CLAIM` records are distinct per source model and assertion. An
`ASSERTION_SCOPE` index replaces only one model's contribution set atomically.
Every aggregate validates all participating models' current generation, explicit
publisher/model-admin approval, tag revision, configuration and pinned profile.
Stale or competing profile versions block reconciliation for review. This is
not a last-writer-wins ownership decision. At most 256 source claims are read
per assertion; larger sets require an explicit scalability change.

Derived identities use the existing provider-neutral registry and proofs. They
never create synthetic formal model relationships or model bindings. Ordinary
card stewardship is unchanged. Reference-only resources verify an existing exact
card binding/type/lifecycle without acquiring sole stewardship or patching fields.

The derived relation writer verifies an explicitly approved multiselect attribute
and its READ/WRITE option values against the live Turbo EA metamodel. No built-in
CRUD field is assumed. Creation uses the existing bare relation POST, independently
reads back the exact identity, then patches only approved attributes while keeping
one durable CREATE fence throughout. Existing attributes and descriptions are
preserved. An unknown matching relation requires explicit inspected adoption.
Lost POST/PATCH replies never trigger blind mutation retries. Final withdrawal
during reconciliation retains the relation and records `WITHDRAWN_REVIEW_REQUIRED`; remaining authorized
claims are recomputed instead of being erased.

Turbo EA offers no conditional relation PATCH. The existing explicit unconditional
write-risk acknowledgment remains mandatory, with immediate pre-write read and
post-write verification; this does not provide target-side atomic compare-and-set.

P3 validation used the disposable server gate. The initial full run discovered
380 cases: 372 passed, four explicit opt-in skips and four errors in the new
fixture setup (duplicate seed batch ID). Subsequent focused runs exposed a
missing fixture model scope, a real JSON numeric-node comparison issue and a
registry proof request scope collision. Both implementation defects are fixed:
evidence uses canonical JSON equality, and source/target identity proofs have
distinct stable operation scopes. The four new P3 integration scenarios and all
legacy publication/M6 cases passed in the combined P3/P4 run below.

`ARCHIMESH_SERVER_GATE_TESTS='ProfilePublicationIT,CatalogProfilePublicationIT,DatasetProjectionIT,ApplicationProjectionIT,ArchitecturePublicationIT,IntegrationOperationsIT,PublicationProfileTest,ProfileDerivationTest,ArchitectureProjectionTest,TurboEaClientTest' ./scripts/validate-local.sh server-isolated`
passed: **78 passed**, zero failures/errors/skips. Evidence: `/tmp/archimesh-profiles-p4-integration.log` and isolated run
`5270421d-696e-4789-be2b-0ed71d8c271a`. The optional test filter changes no default
gate coverage; an unset filter still runs every `*Test,*IT` case.


## P4 — DataHub coexistence

The shared registry's exact DataHub dataset/product native identifiers and
external types establish catalog ownership, including older canonical objects
whose semantic kind is still unclassified. A DataHub Application mirror alone
does not make an enterprise application a catalog-owned data product. No names,
model properties or matching labels establish equivalence.

Catalog-owned cards are reference-only in profiles. Creation, field selection
and stewardship cannot override that guard, even when a permissive canonical
field mapping claims ArchiMesh authority. The worker independently enforces the
catalog field fence. Before using an existing product/dataset reference it calls
the existing projection service's source-freshness, scope, authority, verified
ledger and exact-readback checks. Rename requires catalog mirror refresh, source
removal blocks publication, and a new URN remains a new native identity; it never
retargets an old architecture edge by name. Catalog references without verifiable
source policy/receipts stop for review.

An explicit approved architecture relation to a resolved catalog card is
supported through ordinary M3 mappings. This does not create a logical Access,
consumer/producer or lineage assertion. The existing DataHub bridge still owns
only its implemented catalog name/description projections and Application
association preservation; it has no producer/consumer/lineage relationship writer.
The typed assertion predicates keep future catalog claims separate. Unknown or
already-bound target relation identities retain the explicit-link conflict fence.

The combined P3/P4 disposable integration command above passed, including the
new end-to-end catalog connection, rename, URN change and source removal case.
`python3 -m unittest discover -s tests -v` in `integrations/datahub-turboea`:
**23 passed**, zero failures/errors/skips. Local HTTP fixture tests required the
normal sandbox escalation for loopback sockets. Evidence:
`/tmp/archimesh-profiles-p4-bridge-unit.log`.

The additional P4 authority unit run, `mvn -q -f server/pom.xml
'-Dtest=PublicationProfileTest,ProfileDerivationTest,FederationCoreTest' test`, passed
**15 cases**, including a registry-backed product disguised by a legacy kind and
an ArchiMesh-owned name mapping. Evidence: `/tmp/archimesh-profiles-p4-unit.log`.

## P5 — administration and portable recovery

The existing Publication page offers legacy explicit selection or an exact
profile/version/digest pin, live capability validation, deterministic preview,
skips/conflicts, source paths, reuse/create counts and contribution withdrawals.
An unavailable/changed pin remains visible; loading an old policy never replaces
its pin with the newest catalog entry. Existing policy version, preview digest
and explicit publisher approval are still required. A reference-only card has no
editable outbound baseline. Derived baseline inspection shows this model's
contribution; the effective remote value can include other authorized claims.

New JSON responses are explicitly `schemaVersion: 1`, under the same
`/admin/models/{modelId}/publication/{targetKey}` authorization boundary:

| Route | Purpose / remote effects |
| --- | --- |
| `GET /profiles/v1` | List configured definitions and exact pins; metadata reads only |
| `GET /profiles/v1/{profileId}/{version}` | Read one immutable definition; no credential material |
| `POST /profiles/v1/validate` | Pin body; verify current API/metamodel and target allowances using GETs only |
| `POST /explain/v1` | Ordinary `PolicySpec` body; return preview, reuse/create classification, this model's evidence and planned withdrawals; no remote reads/writes |
| Existing `/preview`, `/policy`, `/run`, `/resource`, `/adopt-baseline` | Existing request/response contracts retained; optional profile/provenance fields are omitted for legacy policies |

Explanations show only the authorized source model's IDs and evidence. Aggregation
validates other models' approvals internally without returning their IDs, paths
or raw catalog payloads. The last twenty withdrawn assertion claims remain in
the model's bounded scope index for operational inspection; the full claim
history stays in independent metadata and the named-operator operations views.
Preview classification is advisory until the worker performs live identity and
baseline checks. No GET/preview adopts or creates a target resource.

M6's existing `archimesh-integration-v2` archive carries retained normalized
profile definitions, policy/job/resource/contribution pins, claim indexes and
individual source evidence. Validation rejects missing/changed definitions,
inconsistent evidence/job references and profile extensions disguised as v1.
Restore preserves ambiguity fences, disables publication policies, pauses model
scopes and quarantines live claims. Reattachment does not approve or activate a
profile: install the exact catalog/configuration, inspect source/remote state,
reattach through M6, then explicitly review new policies and queue new jobs.
No model/CRDT snapshot schema or migration is introduced.

## Configure and publish

1. Keep the feature off while reviewing the catalog and separate target mapping.
   [Profile catalog](../server/examples/publication-profiles.example.json),
   [target example](../server/examples/publication-profiles-target.example.json)
   and [schema](../schemas/publication-profile-v1.json) are non-secret examples.
   Their `LogicalData`, `ApplicationDataAccess` and `operations` names describe
   deliberately approved custom target types/attributes; they are not assumed
   Turbo EA defaults. Provision/verify them through normal target administration.
   The relation needs a visible multiselect attribute with explicit READ and WRITE
   option keys. Built-in CRUD booleans are not treated as equivalent.
2. Configure the existing target API version, type mappings, field authorities,
   named publishers and model-admin ACLs. Approve complete attribute reads and
   the existing unconditional-write risk only after reviewing that target.
   Catalog import, inbound synchronization and central editing remain separately
   scoped. Reuse/import an application's exact registry-backed card identity;
   names and editable `archimesh.external.*` properties cannot link identities.
3. Add ordinary source properties as needed. The example requires
   `archimesh.publication.eligible=true` on a selected logical DataObject and
   `archimesh.publication.accessMode=READ`, `WRITE` or `READ_WRITE` on each
   participating AccessRelationship. These annotations do not grant permission.
   The current snapshot protocol does not persist Archi's native Access enum;
   the explicit property is required rather than guessing it from labels. Native
   `Read`, `Write`, `ReadWrite` values are also recognized when stored in that
   configured property. Internal functions are retained as path evidence.
4. Enable `derivationsEnabled` in a reviewed **new immutable definition** and
   set `ARCHIMESH_PUBLICATION_PROFILES_ENABLED=true` with
   `ARCHIMESH_PUBLICATION_PROFILES_CONFIGURATION=/absolute/path/profiles.json`.
   Configuration is loaded on server startup. List/read pins from the API or UI;
   use the server's normalized digest, not a hash of whitespace-formatted JSON.
5. Create an immutable model tag. In Publication, select the exact profile pin,
   tag, candidate endpoint IDs and any required direct stewards. All profiles
   still operate within M3's explicit per-model selection; selector flags never
   authorize automatic publication of a whole type/model.
6. Validate live capabilities, preview/explain, review skipped/derived paths,
   field ownership and withdrawals, then approve against the displayed policy
   version and preview digest. Queue reconciliation and inspect status. The
   existing polling worker repairs missed notifications from full desired state;
   it does not replay raw model operations into Turbo EA.

Derived assertions do not require sole ownership of the endpoint cards. The
approved profile/policy grants only the bounded logical assertion contribution;
ordinary direct cards/relations retain M3's explicit stewardship checks. New
logical cards still require an approved ArchiMesh-owned name and steward.
Reference-only catalog products/datasets require an exact source binding and
fresh verified projection; they cannot be created or patched by the profile.

## Upgrade, withdraw and roll back

- Keep deployed definitions unchanged. Add a new `(profileId, version)` alongside
  the old definition, with a new normalized digest. Changes to enablement,
  selectors, access values, limits or semantics invalidate the old pin. A global
  feature pause can stop execution without editing immutable definitions.
- Preview the candidate version through `/explain/v1` before approval. It shows
  the old/new pin, selected output, removed paths and planned withdrawals. No
  remote retirement is part of this dry run. Live mapping/API changes still
  require existing configuration/mapping migration review.
- For a shared assertion with unchanged mapping semantics, have every affected
  model administrator approve the new version and queue its complete desired
  claim set. Mixed versions stop with `ASSERTION_PROFILE_MIGRATION_REQUIRES_REVIEW`;
  stale approvals stop with `ASSERTION_APPROVAL_CHANGED_REQUIRES_RECONCILIATION`.
  Once all desired sets are staged and current, queue reconciliation again for
  the conflicted models. Existing compatible assertion/card identities are reused.
- Changed predicate, canonical endpoints, rule/mapping scope or access semantics
  may yield a different assertion identity. An existing target triplet owned by
  the old identity is a conflict, not an adoption opportunity. Use a separately
  approved identity/mapping migration or distinct target relation type plus a
  reviewed retirement decision. Do not clear bindings, fences or remote edges
  to force an upgrade.
- Remove evidence in a new approved tag, narrow the profile/selection, or approve
  a disabled policy. Only that model's claim is withdrawn. Remaining current
  compatible claims are unioned; the final disappearance leaves
  `WITHDRAWN_REVIEW_REQUIRED`. A disabled policy performs metadata withdrawal
  only; an active compatible contributor can reconcile its remaining aggregate.
  The disabled policy retains the last verified resource baseline for separate
  retirement review. No remote DELETE or blind attribute clearing occurs. If a profile is unavailable,
  an explicitly reviewed disabled legacy policy (no pin) can withdraw its claims.
- To roll back, retain/install the exact prior definition and target configuration,
  select its pin and review/approve every affected model again. Rollback is a new
  approval, not revival of an old queued capability. Restored/reattached policies
  follow the same process. A paused/unauthorized source cannot silently disappear
  from another model's replacement map; an authorized owner must review its
  withdrawal or restore a current approval.
- For an ambiguous POST/PATCH or remote drift, use existing resource inspection
  and explicit baseline adoption with the current remote digest. Review the exact
  canonical/target identity, type, endpoints and owned values. Adoption itself
  does not write the remote target. Then queue a new reconciliation. Lost replies
  retain their durable fence across jobs, service restart and archive restore.

## Supported limits and verification boundaries

M3 remains TAG-only and limited to twenty selected semantic elements and twenty
output relationships (direct plus derived). Profiles can lower those limits.
Graphs are bounded at 10,000 elements / 20,000 relationships, each assertion at
128 evidence paths and 256 model claims, and the materialized plan at 180k
characters. Too-large or unsupported cases fail explicitly. There is no recursive
inference, generic Association fallback, CRUD Create/Delete inference, physical
schema expansion, DataHub producer/consumer inference or DataHub lineage writer.

Field ownership, native-identifier proof, full attribute read approval and
uncertainty checks remain upper bounds. The target has no atomic conditional
PATCH. Immediate read-before-write and verified readback reduce drift risk but
do not remove the explicitly acknowledged target-side race. In particular, a
concurrently introduced empty relation cannot be distinguished atomically from a
new relation by the upstream triplet-upsert POST. Existing manual identities are
checked before dispatch; this is not an atomic target-side creation guarantee.

Profile-specific end-to-end and restore checks use synthetic Turbo EA/DataHub
HTTP fixtures with real disposable Neo4j/Kafka. The independent DataHub OSS D0
gate ran real API/auth/restart probes. This delivery does not claim native Archi
workbench UI validation, profile publication against a production Turbo EA
metamodel, or production DataHub validation.


## Final validation (2026-11-16)

All Maven/client gates were run sequentially. Docker harnesses removed only their
own disposable projects/volumes. No development model repository or target was
reset. Profile-specific tests use two models and two actor client IDs, a shared
Turbo EA target, real registry/metadata persistence, and catalog projection
fixtures. The tests include live metamodel checks, reference-only ownership,
claims/withdrawals, mixed-version approval, service recreation, lost responses,
read-only explanations/ACLs, immutable TAG behavior and a portable restore into
a second fresh Neo4j database.

| Command | Result |
| --- | --- |
| `./scripts/validate-local.sh fast` | PASS: hardening and notation parity |
| `./scripts/validate-local.sh server-isolated` | **386 discovered: 382 passed, 4 explicit skips**, zero failures/errors |
| `./scripts/validate-local.sh recovery` | PASS: packaged JVM crashes, broker outage, interrupted transactional restore, stale barriers and rebuild; cleanup passed |
| `./scripts/validate-local.sh gate` | **80 client/EMF passed**; **238 server unit cases: 176 passed, 62 infrastructure/opt-in skips**, zero failures/errors |
| `npm run build` in `admin-web` | PASS: production Svelte build |
| `python3 shared/federation-core/test.py` | PASS: shared assertion/identity contracts |
| Draft-2020-12 schema validation of profile example; target example JSON parse | PASS |
| `python3 -m unittest discover -s tests -v` in `integrations/datahub-turboea` | **23 passed** |
| `./scripts/validate-local.sh datahub` | PASS: DataHub OSS initial API/auth and post-GMS/frontend-restart probes; scoped cleanup passed |

The full server gate's four opt-in skips are `ProcessRecoveryIT` (run separately
by the required recovery drill below) and the three actual-local-Turbo-EA suites
`TurboEaLocalIT`, `TurboEaPublicationLocalIT`, `TurboEaSignalsLocalIT`. Fixture-based
publication, federation, inbound, central-edit and M6 operations coverage passed.
The default unit gate's infrastructure skips are covered by the isolated gate;
counts are overlapping suites, not additional unique test totals.

The P5 focused run first found a diagnostic expectation mismatch; the correct
mixed-version conflict is `ASSERTION_PROFILE_MIGRATION_REQUIRES_REVIEW`. The
first full P5 run also exposed a shared Quarkus test-profile configuration leak
between the new recovery fixture and legacy publication tests. The fixture now
has its own test profile; the complete rerun passed. These were test isolation/
expectation corrections, not suppressed scenarios.

Evidence: `/tmp/archimesh-profiles-final-{fast,gate,isolated-verified}.log`,
`/tmp/archimesh-profiles-p5-admin-build.log`,
`/tmp/archimesh-profiles-p4-datahub.log`, and isolated server run
`server/target/isolated-server-gate/1a57a8c0-0a31-4e60-a3d8-f95027e7a583/`.
DataHub reports are in
`integrations/datahub-turboea/.run/datahub/it-6c40af9fc516/{report,restart-report}.json`.
Build/temporary evidence is machine-local and ignored by Git; commands above
reproduce it. Native Archi workbench and production target behavior remain
unverified as described above.

Recovery drill evidence:
`/tmp/archimesh-profiles-final-recovery.log` and
`server/target/recovery-drill/cf19c749-b3a7-40b2-9739-3dcbf6c323fd/report.json`.
The dedicated drill exercised the process-recovery case skipped by the general
gate; it did not require or reset the development Compose stack.

Profile-version rollback assumes a binary that understands profile metadata.
Older binaries/archives without these document kinds are not supported readers
of a profiled integration archive. Pause publication and use a reviewed archive
and capability rollback plan before a binary downgrade; do not pass active
profiled jobs to an older worker.

Final focused confirmation after adding the explicit accepted-snapshot immutability
assertion: `ARCHIMESH_SERVER_GATE_TESTS='ProfilePublicationIT'
./scripts/validate-local.sh server-isolated` — **8 passed**, zero failures/errors/
skips. Evidence: `/tmp/archimesh-profiles-final-profile-check.log` and isolated run
`6a43dd21-a3c4-4e7f-ae71-8d08eb36fbfc`.
