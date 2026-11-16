# Publication Profiles delivery and operations

Implementation brief: [specification](archimesh-publication-profiles-codex.md).
Identity, ownership and migration decision: [ADR 0003](adr/0003-publication-profiles.md).

## P0 baseline

Branch `codex/turboea-m0`, HEAD `77ee9f0`; initial working tree contained only the
untracked implementation brief. No development services or data were reset.

| Command / check | Result before profile changes |
| --- | --- |
| `./scripts/validate-local.sh fast` | PASS: notation parity and hardening |
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
retains the relation and records `WITHDRAWN_REVIEW_REQUIRED`; remaining authorized
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
passed. Evidence: `/tmp/archimesh-profiles-p4-integration.log` and isolated run
`5270421d-696e-4789-be2b-0ed71d8c271a`. The optional test filter changes no default
gate coverage; an unset filter still runs every `*Test,*IT` case.
