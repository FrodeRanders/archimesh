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
