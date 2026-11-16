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
