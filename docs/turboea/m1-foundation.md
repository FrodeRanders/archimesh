# Turbo EA integration: M1 foundation

M1 adds a working server-internal connector and independent metadata store.
Integration is **disabled by default**. There are no new REST/WebSocket endpoints,
Archi commands, publication workers, SSE subscriptions or changes to model
acceptance/CRDT behavior. The [M0 investigation](m0-analysis.md) and
[ADR 0001](../adr/0001-external-repository-integration.md) remain the design basis.

## Server components

| Component | Implemented behavior |
| --- | --- |
| `ExternalRepositoryConnector` | Generic typed reads for objects, paged catalog, relations, metamodel and API version. M1's implementation is Turbo EA only. This internal interface grants no user catalog permission. |
| `TurboEaClient` | Verified 2.194.0 endpoints and response shapes, bounded explicit card pagination, UTF-8 query/ID encoding, bearer credentials, strict JSON/payload checks, connection/whole-request deadlines and a streaming response byte limit. No redirects or automatic retries. |
| `IntegrationConfiguration` / `IntegrationRuntime` | Strict versioned JSON configuration, unique instance/target identities, server environment secret references and explicit enablement. Startup performs local validation only; it opens no database connection and makes no remote HTTP request. |
| `TurboEaMapping` | Per-target versioned card/relation/field whitelist; live type visibility, attribute datatype, relation direction/cardinality and pinned Archi relationship-rule validation. No implicit fallback mapping. |
| `InformationAuthorityPolicy` | Type rules, explicit canonical overrides, duplicate-rule rejection and `UNMAPPED` default. These rules are declarative in M1; model-operation enforcement is later work. |
| `Neo4jIntegrationStateRepository` | Transactional canonical/external/model identity reservations, durable prepared imports, receipt/entity-checked activation and independent compare-and-set state documents. It never writes model entities, operation logs, revisions or projection edges. |

All new Java code is under
[`server/.../integration`](../../server/src/main/java/org/gautelis/archimesh/integration/).
The existing collaboration service, repository, delivery service and client plugin
are unchanged.

## Configuration and target inspection

Copy and review
[`turboea-integration.example.json`](../../server/examples/turboea-integration.example.json).
Use stable, distinct identities for this ArchiMesh installation and every Turbo
EA environment. These are persistent identity namespaces, not display names; do
not generate a new instance ID on each restart.

The file contains no token. `tokenEnvironment` names a server environment
variable. Supply that secret through the deployment's secret mechanism. Do not
commit it or put it in the URL or browser configuration. A running process's
environment normally changes only on restart.

To enable the internal foundation in a development server:

```bash
export ARCHIMESH_INTEGRATIONS_ENABLED=true
export ARCHIMESH_INTEGRATIONS_CONFIGURATION=/absolute/path/to/reviewed-targets.json
# Supply TURBO_EA_TOKEN through your secret mechanism, then start the server normally.
```

Disabled mode ignores the configuration path and allocates neither an HTTP client
nor an integration database driver. Enabled mode requires valid local
configuration and nonempty referenced secrets. Errors are sanitized rather than
including parser input or credential values. Remote downtime does not prevent
server startup or ordinary model commits.

An internal caller uses `IntegrationRuntime.validateTarget(targetKey)` to check
the configured exact health version, fetch the current metamodel and validate the
target's mapping. It returns that metamodel; inspection is explicit, uncached and
does not mutate either system. Future import/publication callers must perform
this validation before using a mapping. A changed version raises
`INCOMPATIBLE_API`; changed/hidden types, field datatypes, cardinalities or invalid
Archi relationships are rejected. Unknown configuration properties and duplicate
JSON keys are rejected.

`connector(targetKey)` supplies the server-internal read client. M1 deliberately
has no end-user catalog endpoint. M2 must add model ACL plus approved catalog
visibility/delegation checks before exposing any service-account result,
including `withheld_ids`. A hidden/redacted resource is not proof of deletion.

HTTP is accepted only for literal loopback hosts; other targets require HTTPS.
Base URLs must be origins without user info, API path, query or fragment. Requests
never follow redirects. Configured deadlines are 1–120000 ms, response limits
1024–16777216 bytes; the example uses 10 seconds and 2 MiB. Catalog calls require
one-based pages and an explicit page size from 1 to 1000 (below Turbo EA's 10000
maximum). Search text is limited to 200 characters. Oversized responses are
cancelled before accumulating an unbounded body, and the deadline includes
stalled response bodies.

The client reports `AUTHENTICATION`, `AUTHORIZATION`, `NOT_FOUND`, `RATE_LIMIT`,
`VALIDATION`, `TRANSIENT`, `INVALID_RESPONSE`, `INCOMPATIBLE_API` or `CONFIGURATION`.
Only rate-limit/transient failures are classified retryable; no retry is performed
by M1. `Retry-After` accepts seconds or an HTTP date and is bounded to one hour.
Failures contain no remote response body, credential, request URL or original
parser exception. Publication/write methods and ambiguous-write reconciliation
belong to M3; this client cannot POST/PATCH/DELETE.

## Mapping contract

The example's proposed pilot types are `Application → ApplicationComponent`,
`BusinessProcess → BusinessProcess`, `Organization → BusinessActor` and
`BusinessCapability → Capability`. They still need deployment/governance review.
The rule set also supports `DataObject`, added for the explicitly configured
dataset representation. `Interface` and `ITComponent` remain unmapped.

`fields` is keyed by remote card type and local model field (`name`,
`documentation`, or `property:<key>`). Each entry has an explicit remote field,
value datatype and authority. Undefined fields stay `UNMAPPED`. Built-in remote
fields supported for mapping are `name`, `description`, `status` and the
`lifecycle` object; `attributes.<key>` must exist in the live type's field schema.
Unknown datatypes are rejected. Name/documentation mappings must be strings.
The example keeps model documentation local even though the remote description
field is named, so later consumers must obey the authority as well as the mapping.

Relations default to an empty whitelist. An approved relation entry specifies
`archimateType`, remote `sourceType`, `targetType`, exact `cardinality` and
`reversed`. Validation compares these against the actual remote metamodel and
checks the resulting local endpoints against the pinned Archi 5.8 matrix.
An explicit Association mapping is possible only when deliberately configured
and valid; there is no automatic Association fallback. Hierarchy `parent_id`
does not become a relation automatically.

[`archi-5.8-rules.json`](../../server/src/main/resources/integrations/archi-5.8-rules.json)
contains the five-type subset (including DataObject) derived from the local Archi model 5.8.0 source at
commit `b02aab8042665f46cd95efd22e172b2693aaf287`. It records the complete source
matrix's SHA-256 and the allowed ordered pairs. The source uses
`ArchimateModelUtils.isValidRelationship`/`RelationshipsMatrix` and
`model/relationships.xml` plus `relationships-keys.xml`. The
[Archi MIT license](../../server/src/main/resources/integrations/ARCHI-LICENSE.txt)
is included. Expanding types or upgrading Archi requires reviewed rule regeneration
and mapping tests; the server does not load Eclipse/EMF bundles.

## Identity reservation and persistence

The first `metadata()` call lazily opens an independent driver to the existing
configured Neo4j database and installs
[`schema-v1.cypher`](../../server/src/main/resources/integrations/schema-v1.cypher).
The idempotent migration contains only `Integration*` labels. Writes fail unless
installation succeeded; there is no automatic deletion or data rewrite migration.
It can also be applied explicitly using `cypher-shell` against the intended
database before enabling the foundation.

Unique constraints cover canonical identity, both directions of external
resource mapping, model entity identity, one occurrence per canonical/kind/model,
one reserved single-import batch per model, and versioned state document identity.
Every key component is validated in Java, because Neo4j Community uniqueness
does not imply property existence. Resource/entity kinds distinguish cards and
relations; names are never identity keys.

`reserveImport` is one Neo4j transaction. It locks the external resource mapping,
reuses or establishes the canonical identity, verifies canonical kind and reserves
the model occurrence. Concurrent requests share the winner's element ID and
opBatchId, even if callers proposed different candidates. Importing that resource
to another model reserves a different occurrence of the same canonical object.
Conflicting entity bindings, canonical/target mappings or semantic types roll
back the whole transaction. Original source target/mapping provenance is
immutable; adopting a different origin or mapping version requires later explicit
migration rather than silent reassignment.

Reservations are `PREPARED`; they do not imply model acceptance. M2's coordinator
must authorize the request and submit normal model operations using the reserved
opBatchId. `confirmImport` will mark a reservation active only when its exact
batch receipt and a materialized semantic entity of the reserved ID/type exist.
Activation changes metadata only and records the accepted end revision. The
coordinator must additionally verify the requested field values; a receipt does
not prove that every LWW field won. Crash recovery can reopen the stored
reservation and reuse the exact batch. M1 never submits or fabricates those model
operations.

`StateDocument` stores bounded JSON objects in independent namespaces for
`FIELD_AUTHORITY`, `CHECKPOINT`, `CONFLICT`, `CONTRIBUTION` and `JOB`. It uses
`(instanceId,kind,id)` identity and compare-and-set versions: version 0 inserts,
version N replaces only N and returns N+1. Failed CAS rolls back even a newly
created placeholder. Reads/writes defensively copy the document. Payload schema
and processing semantics belong to each later domain consumer; this store is not
a second model commit/merge engine or a job scheduler.

Metadata nodes carry `sourceModelId` where applicable, never the model graph's
`modelId` property, and have no relationships to projection nodes. They survive
model checkpoint capture, rebuild and deletion. That also means deletion or
overwrite can leave an obsolete binding: later reconciliation must check model
existence/current state and record orphaning explicitly. M1 has no worker that
performs that lifecycle transition.

Existing model exports/checkpoints exclude integration state. Use database-native
backup including these labels during development. A separately versioned
integration export/restore and paused reattachment procedure remains M6 work;
do not treat a model package as an integration backup or enable publication based
on a restored model's old receipts alone.

## Validation and boundaries

Run the existing gates sequentially, since Maven shares build directories:

```bash
./scripts/validate-local.sh fast
./scripts/validate-local.sh gate
./scripts/validate-local.sh server-isolated
```

The new unit/mock tests run with the ordinary server gate. They exercise disabled
startup, strict configuration, authority resolution, mapping drift, actual wire
metamodel shapes, pagination, identity checks, redirect refusal, token rotation,
error classification, malformed/duplicate/trailing JSON, bounded bodies and
whole-response deadlines. Real Neo4j tests run in `server-isolated`, including
concurrent imports, cross-model canonical reuse, rollback on conflicts, durable
metadata after reopening, CAS and checkpoint/rebuild/delete isolation.

An optional read-only deployment test is available after supplying an appropriately
scoped token and a reviewed local deployment URL:

```bash
cd server
RUN_TURBO_EA_IT=true TURBO_EA_BASE_URL=http://localhost:8920 \
  mvn -q -Dtest=TurboEaLocalIT test
```

Supply `TURBO_EA_TOKEN` through the process environment. This test creates no
accounts or objects. It verifies the example's API/mapping, paged catalog and
read paths; synthetic fixture provisioning, if needed, is a separate action on a
disposable instance. The flag defaults off; it does not connect during ordinary
or isolated ArchiMesh gates.

M2 still needs scoped catalog access, typed preview/import/link APIs, the shared
acceptance result, request authorization, verified application to real model
folders/views and Archi UI. M3–M6 add publication, inbound sync, ownership
enforcement, proposals, workers and operations/recovery tooling. None is implied
by enabling this M1 foundation.

## Verified M1 run — 2026-11-11

| Check | Result |
| --- | --- |
| `validate-local.sh fast` | PASS: notation parity and CRDT hardening. |
| `validate-local.sh gate` | PASS: plugin build, 52 client tests, server unit gate; infrastructure tests skipped as configured. |
| `validate-local.sh server-isolated` | PASS: 217 discovered, 215 passed, zero failures/errors; `ProcessRecoveryIT` and opt-in `TurboEaLocalIT` skipped. Includes all 22 new ordinary/mock/metadata tests. |
| `TurboEaLocalIT` with explicit deployment flag | PASS against a fresh source-built Turbo EA 2.194.0 instance, including the live metamodel, catalog, two application reads and one relation. |

The local Turbo EA checkout remains at
`6d9220e52d47b41f9d79351ec3233ded1f76fdc6`. Its dev Compose files were used with a
separate `archimesh-turboea-m1` project, loopback-only API port and fresh volumes.
Fixture provisioning created one synthetic bootstrap account, two applications
and one relation in that project only. The Java deployment test itself was
read-only; its token stayed in memory. Containers/network/test volumes were
removed after verification. No existing Turbo EA or ArchiMesh development data
was reset.

Machine-local evidence:

- `/tmp/archimesh-m1-gate.log` and `/tmp/archimesh-m1-fast.log`.
- `server/target/isolated-server-gate/a8d8707e-0020-4574-a44e-1a813df67679/`,
  with copied Surefire reports, infrastructure logs and exit code 0.
- `/tmp/archimesh-m1-turboea/`, with build/test/cleanup logs, fixture probe and
  sanitized `result.json`. Its private temporary environment file must not be
  published.

The independent process-crash drill was not rerun: the model/commit/recovery
runtime is unchanged, and the isolated tests directly exercise metadata alongside
real model checkpoint/rebuild/delete behavior. Production/delegated authorization,
real Archi UI behavior and cross-system write recovery remain later-milestone
acceptance work.
