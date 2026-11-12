# D0 local OSS contract evidence

Date: 2026-11-12. Result: isolated development/test environment and synthetic
API/restart probes passed. The DataHub connector and bridge runtime are not
implemented. These observations describe the pinned development release, not
the agency's still-unknown installed version.

## Baseline and reproduction

From this subproject:

```bash
python3 -m unittest discover -s tests -v
python3 scripts/local.py test
```

The final live run was `datahub-turboea-it-6572e8fb4615`, on Linux ARM64 Docker with
16 GB allocated. It started fresh MySQL/OpenSearch/Kafka volumes, ran system-update
successfully before GMS/frontend, seeded synthetic metadata, probed the APIs,
restarted GMS/frontend on retained volumes and probed again without reseeding.
Exit code was zero. Containers, network and all three test volumes were removed;
the final Docker inventory confirmed no running containers or remaining run volumes.

Ignored local evidence is under `.run/datahub/it-6572e8fb4615/`:

| Artifact | Evidence |
| --- | --- |
| `report.json` | Initial checks passed; SHA-256 `38e91b612eb750a9e109f30735acebdee199c491c29e8123c3b92036beef3e4c` |
| `restart-report.json` | Restart checks passed; SHA-256 `693fb0a531341f60e8d53aa485b86bf8eeeb5911ed9d22d8e27c325a6318fcea` |
| `images.json` | Release references, resolved image IDs/digests and architecture |
| `graphql-schema.json` | Introspection snapshot; SHA-256 `1cbb99f7fc5670a0f5b4818c1e97f93612f946d5caa091d0d80213dba1e2687b` |
| `openapi.json` | Served OpenAPI snapshot; SHA-256 `93f8e1b1ff32c75880f5c2d34555af060ca1785483bb5a049482065c17f6a02a` |
| `topics.txt`, `event-topic-config.txt` | Topic availability and versioned MCL retention settings |
| `compose.log` | Redacted startup/service/cleanup logs |

The local artifacts can be regenerated, and are not committed credentials or
deployment data. Schema digests belong to this run; generated server URLs may
change with allocated ports. The standalone unit suite passed all seven tests.
The root's four existing tool tests and fast notation/CRDT gate also passed.
ArchiMesh server/client implementation and default gate membership did not change.

## Version and API surfaces

GMS `/config` reported DataHub `v1.8.0`, commit
`faab576d96cbdb27b8344a755e0bc0f42fdc9c15`, matching the pinned source tree.
Images were DataHub GMS/frontend/upgrade `v1.8.0`, Confluent Kafka `8.2.2`,
MySQL `8.2` and OpenSearch `2.19.3`; all resolved to ARM64 images in this run.
The adapted source URL/checksum and license are recorded in `../upstream.json`
and `../LICENSE.datahub`.

Verified reads used GMS Rest.li `/aspects/{encodedUrn}?aspect=...&version=0`
and GraphQL `/api/graphql`. The served schema at `/openapi/v3/api-docs` declared
OpenAPI `3.0.1`, API document version `2.0.0`, with 438 paths. That document version
is distinct from the DataHub release. The snapshot includes generic/entity-specific
OpenAPI v2 entity/aspect routes; capturing them is not a claim that every route
was exercised. No conditional-write support is assumed for future adapters.

## Synthetic identity and metadata observations

- Dataset: `urn:li:dataset:(urn:li:dataPlatform:postgres,bridge_d0.customer,DEV)`.
  Platform, native dataset name and fabric are retained in the exact URN. Changing
  the display name in properties did not change this key; native rename/move
  behavior for agency ingestion connectors remains a separate verification.
- Product: `urn:li:dataProduct:bridge-d0-customer`. OSS accepted/read back its
  `dataProductProperties` with an asset association containing the exact dataset
  reference in `destinationUrn`. Product and dataset have distinct identities.
- `datasetProperties` and `editableDatasetProperties` were independently readable.
  Updating ingested name/description preserved the curated description. GraphQL
  returned both descriptions separately, so a connector must make effective
  description precedence explicit in its mapping; it must not conflate the aspects.
- An `ownership` aspect retained the exact synthetic owner URN while the properties
  aspect changed. This does not establish inherited/domain-owner precedence.
- The raw Rest.li union envelope wraps a typed aspect; the probe preserves its
  complete reference and unwraps the aspect explicitly.

## Authentication and visibility

GMS authentication, REST authorization and view authorization were enabled.
Anonymous and invalid bearer reads returned `401`. A synthetic reader could read
only its approved dataset/product via the tested aspect path. A denied user and
an out-of-scope dataset read returned `403`. Reader writes and writer attempts
outside its selected resources returned `403`; the selected writer could update
the approved dataset. Policy/index propagation required bounded read retries,
including independently for the data-product resource.

The upstream all-users view policy was deactivated in this synthetic environment;
leaving it enabled would not test restricted users. Test bootstrap used one-hour
SESSION tokens signed using the environment's generated key. PAT issuance,
agency SSO and operational credential management are not validated by that fixture.

Frontend anonymous GraphQL returned `401`; the generated JAAS password permitted
login and an authenticated GraphQL proxy call (`200`); an incorrect password
returned `400` with invalid credentials. The harness does not use the upstream
default password and never records session cookies or bearer tokens in reports.

**Disclosure gap:** GraphQL search returned both dataset URNs and total `2` to the
scoped reader, including the restricted dataset whose exact aspect read returned
`403`. Direct GraphQL lookup also exposed its URN/name while masking the tested
description to null. View authorization is therefore not sufficient catalog
isolation for this adapter. D1/D2 must authorize candidates against explicit
catalog/disclosure scope and permitted source reads before returning names, IDs,
provenance, counts or cursors. Do not forward upstream search pages or totals.

The admin's one-item search pages returned distinct exact URNs with start `0`/`1`.
This verified pagination while separately recording the restricted-reader behavior.

## Mutation, lifecycle and restart observations

These mutations seeded/tested disposable data; they do not enable DataHub writes
for a connector:

- `/aspects?action=ingestProposal` accepted the `async` action parameter as a JSON
  **string** (`"true"`/`"false"`); a JSON boolean failed parameter validation.
- Async selected-writer UPSERT returned `200`; the harness separately polled
  primary aspect reads until the desired value appeared. An acknowledgement is
  not used as a durable-write checkpoint or assumed to imply immediate commit.
- UPSERT of the properties aspect with a smaller `customProperties` map removed
  the omitted member. Preserve unowned aspect members before any future write.
- Explicit JSON null for the tested optional description returned `422` and left
  prior state intact. Do not use null as an assumed field-removal contract.
- A JSON PATCH envelope adding/replacing description succeeded and preserved the
  other tested custom-property members. Other aspect patch templates and full
  aspect deletion require their own contracts before use.
- `status.removed=true`, then `false`, read back correctly for the restricted
  synthetic dataset. This is a lifecycle marker; no hard delete/recreate occurred.
- After GMS/frontend restart, property values, curated description, product
  membership, ownership and scoped authorization were read back without reseeding.

MetadataChangeEvent, MetadataChangeProposal and versioned/timeseries MCL topics
were present. The versioned MCL topic had delete cleanup and retention
`604800000` ms (seven days). No event consumer/replay guarantee was tested.
The integration still requires polling and independent durable checkpoints.

## Remaining gates

D0's local environment/API/restart baseline is verified. D1 still needs neutral
contracts, runtime/registry ownership boundaries, actual source authority
enforcement and compatible metadata/archive migration. D2/D3 must prove direct
and mirrored imports share identity. No DataHub → Turbo EA publication occurred.

The inspected Turbo EA source seeds a `DataObject` type but does not establish a
governed data-product mapping. The two pilot representation types and their fields
are not selected or live-validated here. Verify/approve them in the target metamodel
before enabling bridge publication; do not cast every dataset/product into that
type or invent an implicit fallback. Agency-version compatibility, field/relationship
semantics and downstream disclosure policy remain explicit deployment gates.

## Extension: Turbo EA application-reference direction

The required source direction was clarified: enterprise applications referenced
in DataHub are sourced from Turbo EA. The isolated run
`datahub-turboea-it-0118d1f4c39b` passed the full original checks plus the new
application/reference checks, then passed restart without reseeding and removed
its own Docker resources. Evidence is in this subproject's ignored
`.run/datahub/it-0118d1f4c39b/`:

- `report.json`, passed; SHA-256 `028172144c45e059675407d95c5962ada89e9047f55cd3fa7e84c9ee379940b3`.
- `restart-report.json`, passed; SHA-256 `681881dff5a5ca35e817428200812b92e7fc90e29fee5601eaeb20fa0c8f9861`.

OSS GMS accepted `applicationProperties` for native application URN
`urn:li:application:bridge-d0-customer-app` and a dataset `applications` aspect
containing that exact reference. A separately scoped application writer updated
name/description and `bridge.source.*` reference properties while preserving
`local.note`. Its dataset mutation returned `403`; the dataset writer's application
mutation also returned `403`. Readback retained the separate synthetic Turbo EA
native card ID and source canonical ID, including after restart.

The source application snapshot is explicitly **simulated**. No live Turbo EA →
DataHub connector, native application UI feature or agency semantic mapping was
verified. The probe does not bind the DataHub grouping or its dataset to the source
application's canonical identity. See the [authority and representation decision](authority-directions.md).
The pinned source defines the [`applications` reference aspect](https://raw.githubusercontent.com/datahub-project/datahub/v1.8.0/metadata-models/src/main/pegasus/com/linkedin/application/Applications.pdl)
and [`applicationProperties`](https://raw.githubusercontent.com/datahub-project/datahub/v1.8.0/metadata-models/src/main/pegasus/com/linkedin/application/ApplicationProperties.pdl).
