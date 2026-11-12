# Local demo validation — 2026-11-12

## Clean-checkout Docker bootstrap

`scripts/demo.sh up` passed in run `7134d84adff9`. All application images were
built from source: Turbo EA database/backend, the compact Turbo EA UI, and the
ArchiMesh server/admin UI. The Turbo EA source archive's verified SHA-256 is
`ae3dc880bda752b5ba11ad21fe87bce271f23aef12f3fc926decda8300347103`.
No host Java/Maven/Node process or sibling checkout was used by this bootstrap.
The initial 18 cards/27 relations, 91 DataHub aspects and model at revision 176
passed readback. Both built web entry pages and the Turbo EA UI API proxy passed.
`scripts/demo.sh restart` then passed the same checks without reseeding.
Seven example/bootstrap tests passed, including archive link/traversal rejection,
loopback/private-service configuration and scoped cleanup after a failed build.
The earlier host-JVM run below was stopped with its volumes retained.

The current-source Docker `scripts/demo.sh test` also passed in run `b7238f5a1e23`:
fresh image builds, initial API/UI checks, restart without reseeding and scoped
cleanup completed successfully. This build includes the optional source/owner
and target-intent code, with federation execution still disabled. Eight example/
bootstrap tests passed, including exclusion of local environment/key files from
the application build context. The earlier host-JVM evidence follows.

## Earlier host-JVM validation

Final retained run: `5e0da21951d5`, using two uniquely scoped local Compose projects:
`northstar-demo-5e0da21951d5` and `datahub-turboea-it-5e0da21951d5`.
Existing development deployments were not seeded. Earlier failed synthetic runs'
containers and volumes were removed by their exact recorded project scope.

## Evidence

Fresh `local.py up --skip-build` passed, using the current Maven-packaged server.
`local.py restart --run 5e0da21951d5` then passed without reseeding:

| Target | Verified persisted content |
| --- | --- |
| Turbo EA API 2.194.0 | 18 native card IDs, types/names/canonical reference hints; 15 CRUD relations with flags and 12 infrastructure relations with exact endpoints |
| DataHub OSS v1.8.0 | 91 aspect readbacks across 22 entities: 9 datasets, 3 products, 6 application references, 1 domain and 3 synthetic team principals |
| ArchiMesh / Neo4j | 26 elements, 49 relationships, no views; full operation history and access annotations at revision 176; graph integrity and commit/materialized consistency true |

DataHub application metadata was derived from actual Turbo EA readback, including
its generated card IDs. Product memberships, field schemas, technical lineage,
application membership and owner references were read back from DataHub. This was
API validation, not a UI acceptance test or a test of continuous synchronization.

Generated reports are retained privately under
`.run/order-management/5e0da21951d5/artifacts/`. SHA-256:

```text
verification.json
7a446b8895e55fcb3e3998ed55cf03632ea8edbaec723bb4011b5231d13de6c3
after-restart.json
7a446b8895e55fcb3e3998ed55cf03632ea8edbaec723bb4011b5231d13de6c3
archimesh-verified-export.json
dd43b5576f25ac83f37aba4ac3b0da44640b40a4699ae4aa565e9b33ec461679
crosswalk.json
e7d4633b202d931f87494a98d26f2a8e5b094d8c5b630a7870dd29ec67c17992
```

The identical initial/restart reports confirm unchanged recorded IDs, content and
model state. Each verification performs fresh API reads; it does not compare
cached responses or reseed metadata.

## Offline and native-model checks

- 4 example tests passed: closed references/distinct identities, deterministic
  artifacts, ready-to-open model consistency, and sample-record key/price/invoice
  reconciliation.
- 11 standalone bridge tests passed, including the existing harness isolation
  checks and demo-specific confirmed-ID reuse, unknown-create fencing,
  Turbo EA-sourced application projection and nested readback validation.
- Installed Archi 5.8.0 EMF loader and model validation passed: 26 elements and
  49 resolved relationships. All endpoint pairs passed Archi's own ArchiMate 3.2
  relationship matrix.
- Native view synthesis and reload passed: 16 diagram objects, 30 connections,
  with the source semantic graph preserved. The source file remains viewless.

No production connector, cross-repository identity registry bindings or automatic
DataHub–Turbo EA jobs were enabled. See the [guide](README.md) for reproduction,
credentials location, operational limits and scoped cleanup.
