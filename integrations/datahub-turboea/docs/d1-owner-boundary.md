# D1 — authenticated source/registry owner boundary

Date: 2026-11-12. This increment adds shared source readers, exact dispatch,
authenticated model-independent source registration and configured field protection.
It does not enable target writes, bridge jobs, a DataHub model catalog/import UI,
automatic adoption, aliases or cross-repository synchronization. D1 remains open.

## Dependency and runtime boundaries

[`shared/federation-http`](../../../shared/federation-http/README.md) supplies the
independent Java 21 read port and DataHub adapter. It depends on Jackson and the
existing shared core, with no application/database dependencies. Turbo EA reads
reuse the existing server client through a small source-port facade.

ArchiMesh hosts the **existing** registry owner. The standalone bridge can consume
the versioned API using [`src/datahub_turboea/owner.py`](../src/datahub_turboea/owner.py)
and [`scripts/bridge.py`](../scripts/bridge.py), both Python standard library only.
The client does not connect to Neo4j, open another registry, call a model API or
submit source values as verified evidence. It can be built/tested without Java,
Maven, ArchiMesh or any repository deployment.

## Explicit configuration

The boundary is disabled by default. Enabling it requires:

- Existing integration runtime and authorization enabled, with one configured owner.
- `ARCHIMESH_FEDERATION_ENABLED=true` and `ARCHIMESH_FEDERATION_CONFIGURATION` naming
  a reviewed, non-secret JSON file. Its owner must match the existing registry owner.
- Source instances, native types, semantic kinds and selected aspect/pointer fields.
- Exact native-ID allowlists for named principals, with separate `READ` and
  `REGISTER_SOURCE` operations and an explicit federation role.
- Server-side source tokens named by environment variable. Turbo EA source definitions
  reference an existing target/client, preserving its configured version/transport.

See the [configuration example](../../../server/examples/federation-sources.example.json).
Its ArchiMate mappings and field choices are illustrative, not an approved agency
profile. In particular, `datasetProperties.description` is the ingested description;
curated `editableDatasetProperties.description` is a separate selectable field.
This increment does not guess precedence between them or equate logical data
objects with technical datasets. Native DataHub application mirrors are not used
as the authoritative enterprise-application source in the example.

The existing identity resolver handles bootstrap, trusted proxy or OIDC identities.
`ARCHIMESH_AUTHZ_FEDERATION_ROLES` defaults to `federation_reader,federation_bridge,
federation_operator`. These roles are retained by the role mapper but grant **no
model/admin capability**. A model administrator alone does not pass the owner PEP;
the configured principal, role, operation and exact resource must all match.
Bootstrap headers are for the loopback development environment; agency deployment
uses the existing authenticated OIDC/proxy configuration.

## API v1

All routes use POST and `Cache-Control: no-store`:

| Route under `/federation/v1` | Input | Behavior |
| --- | --- | --- |
| `/identities/lookup` | `sourceKey`, exact `nativeId`, `externalType` | Authorized lookup of an existing binding; unknown returns `registered:false`. No identity enumeration. |
| `/sources/observe` | Same source reference | Host reads configured fields from the original repository and returns selected values/presence/digests/lifecycle. No caller-supplied source evidence is accepted. |
| `/sources/reserve` | `source` reference plus retained UUID `correlation` | Host verifies an active source/name, then reserves its canonical identity with source/config evidence, using the existing owner and writer lease. |

Source authorization precedes repository reads or identity disclosure. DataHub's
unrestricted search totals/URNs are not exposed. Registration respects global pause
and the shared owner coordination. The principal is resolved by the host, never
taken from the request body. Native URNs retain their full spelling and byte bounds.

The candidate canonical ID is opaque and derived from the owner and request
correlation, independent of source names, native IDs and endpoints. Concurrent
registrations of the same source converge through the existing atomic registry.
Retain the same UUID for a retry; do not mint a new request merely because its
response was lost. An exact lookup can recover a committed binding. If the source
or configuration changed, old evidence cannot be reused: inspect the binding and
issue a fresh reviewed request. No source replacement, reassignment or merge occurs.

Observation revisions are content fingerprints, not comparable repository clocks.
They do not by themselves authorize baseline advancement or target/model mutation.
Registration is also not adoption of a second representation: that later workflow
requires semantic equivalence review and governed target intent/readback.

## Model field protection

For a registered generic source binding, the local acceptance guard checks the
same configured source instance, external/semantic type, registered canonical and
ArchiMate type. Mapped fields, identity marker and type are protected before LWW;
unmapped local properties remain editable. Missing or incompatible source policy
continues to block generic-source edits. All checks here use configuration/database
state; no source HTTP call runs inside model acceptance. This does not enable a
generic inbound change command or relax existing Turbo EA proposal capabilities.

## Standalone consumer

```bash
cd integrations/datahub-turboea
python3 scripts/bridge.py observe --owner https://owner.example.org \
  --source catalog --type dataset --native-id 'exact-approved-URN'
```

`FEDERATION_OWNER_TOKEN` is read from the environment, never a command argument.
For an explicitly local bootstrap deployment, `--bootstrap-user catalog-bridge`
selects development headers and is restricted to loopback. Registration additionally
requires `--correlation <retained-UUID>`. The client does not retry commands blindly.

## Remaining foundation work

Durable target-create intents, shared target uncertainty lanes, governed bridge
approval/jobs, alias/replacement lifecycle and source-aware inbound/observation
baseline CAS remain required. D2 direct imports and D3 projections must use these
services rather than bypass the owner or trust editable provenance hints. The demo
bootstrap continues to leave both integration and federation execution disabled.

## Validation

The isolated server gate `1f4ce8ec-9653-4806-83cf-feab4e626410` discovered 334 tests:
330 passed, zero failures/errors, four deployment-specific probes skipped. New
infrastructure tests exercise named role/resource authorization before source
reads, both dispatch paths, selected-field disclosure, model-independent long-URN
registration/lookup, concurrent candidates, immutable evidence, pause/removal and
local field protection with no source HTTP inside model acceptance.

The independent HTTP module's five contract tests and explicit live OSS demo
reader test passed. The latter read dataset, data-product and application aspects
from clean Docker demo `7134d84adff9`, using only its generated local token. The
bridge's 13 standalone Python tests and seven example/bootstrap tests passed.
