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
P2 previews are available through M3, but derived approval is deliberately fenced
until the P3 durable writer is installed.

P2 checks: `mvn -q
'-Dtest=PublicationProfileTest,ProfileDerivationTest,ArchitectureProjectionTest,FederationCoreTest'
test` in `server`: **19 passed**, zero failures/errors/skips.
`python3 shared/federation-core/test.py` also passed, including provider-neutral
predicate separation and evidence normalization. Evidence:
`/tmp/archimesh-profiles-p2-tests.log`.
