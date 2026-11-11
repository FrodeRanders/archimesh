# Turbo EA integration: M0 investigation and regression baseline

Date: 2026-11-11. Branch: `codex/turboea-m0`.
ArchiMesh baseline: `4259c5b9f97a6615a9cc151f598dc147ac7c5710`.

M0's analysis, ADR, local API verification and regression baseline are complete.
The [assignment](../archimesh-turboea-integration-codex.md) remains the overall
M0–M6 scope. This milestone adds investigation tooling and evidence; runtime
connectors, schema migrations, configuration and UI begin at M1/M2.

Read the [identity/authority/recovery ADR](../adr/0001-external-repository-integration.md)
before implementing those milestones. The proposed component names in the
assignment do not exist in the current server.

## Verified source and deployment

| Source | Evidence |
| --- | --- |
| ArchiMesh | Baseline commit above; working tree initially contained only the untracked assignment. No applicable `AGENTS.md` was found. |
| Local Turbo EA | `../turbo-ea`, clean checkout at `6d9220e52d47b41f9d79351ec3233ded1f76fdc6`; `VERSION` is `2.194.0`. No files in that checkout were changed. |
| Local static OpenAPI | `../turbo-ea/docs/api/openapi.json`; OpenAPI `3.1.0`, `info.version=latest`, 457 paths; SHA-256 `5947a2320ea77bc72873c60021a2b5df0b21d040e4d4cb4ae671a916475c58a2`. |
| Running local API | Built the checkout with its root Compose plus `dev/docker-compose.dev.yml`; separate project `archimesh-turboea-m0`, fresh volumes, database/backend only, loopback API port, development mode, no demo data. Health returned `2.194.0`. The live contract equals the local static contract after normalizing only `info.version`. |
| Public reference | [Public OpenAPI](https://docs.turbo-ea.org/api/openapi.json), downloaded 2026-11-11: 478 paths; SHA-256 `68842b91202abdacbaa159e1bba81df6b445e038744f56d5091f063e497601ae`. It differs from the local target and is not its implementation contract. |

The static exporter deliberately writes `latest` (`scripts/dump_openapi.py` in
Turbo EA); that value is not a release number. Runtime inspection was performed
against the source-built disposable instance, not an existing production system.
Its permissions/metamodel are fresh seed defaults and can differ from a managed
deployment. Never substitute the public spec silently.

The dedicated Turbo EA containers, network and fresh volumes were removed after
verification; the local build images and sanitized evidence remain. The normal
Turbo EA Compose project and development data were not used or reset.

## Local code map and integration seams

Paths below refer to this ArchiMesh checkout. The existing
[architecture](../../architecture.md), [CRDT spec](../../crdt-spec.md),
[server guide](../../server/README.md), [client guide](../../client/README.md),
[graph model](../../neo4j/graph-model.md), schemas and recovery documentation were
checked against the source.

| Concern | Current implementation and implication |
| --- | --- |
| Trust boundary | [`ArchimeshEndpoint.onMessage`](../../server/src/main/java/org/gautelis/archimesh/endpoint/ArchimeshEndpoint.java) authorizes `MODEL_SUBMIT_OPS` and replaces the payload actor with the authenticated session actor. [`AuthorizationService`](../../server/src/main/java/org/gautelis/archimesh/auth/AuthorizationService.java) resolves bootstrap/proxy/OIDC identities and model ACLs. Internal service calls do not inherit these checks. |
| Policy decisions | [`AuthorizationAction`](../../server/src/main/java/org/gautelis/archimesh/auth/AuthorizationAction.java) has no integration actions. [`InProcessPolicyDecisionPoint.decide`](../../server/src/main/java/org/gautelis/archimesh/auth/InProcessPolicyDecisionPoint.java) allows global admin early and allows default switch cases. New actions need explicit permission and denial coverage; a generic writer/admin role is insufficient for external field ownership or catalog visibility. |
| Acceptance | [`ArchimeshService.onSubmitOps`](../../server/src/main/java/org/gautelis/archimesh/service/ArchimeshService.java) is synchronized on the service instance, not independently per model. It checks writer lease/registration/ref, prepares operations, finds persisted receipts, validates state/locks, assigns revisions, normalizes causality and calls `commitBatch`. New inbound work must reuse this sequence and return durable acceptance explicitly. |
| Ref and receipt hazards | `ensureWritableRef` uses session/join state and permits an unjoined actor; the sessionless overload is not a safe external-command entry point. `reply(null, …)` broadcasts. `onSubmitOps` returns `void`, so returning normally is not proof of acceptance. Persisted `(modelId, opBatchId)` receipts are the durable deduplication source. |
| Causality | `normalizeOpsWithCausal` preserves supplied nonnegative clocks and fills missing clocks from the assigned revision. A model can therefore contain field clocks greater than HEAD. External updates need fresh field/property/tombstone clocks under the same serialization; simply omitting `causal` may accept an operation whose value loses. |
| Atomic model persistence | [`Neo4jRepositoryImpl.commitBatch`](../../server/src/main/java/org/gautelis/archimesh/service/impl/Neo4jRepositoryImpl.java) uses one `executeWrite` for writer fencing, op-log, materialized state, head and pending publication. [`Neo4jOpLogSupport`](../../server/src/main/java/org/gautelis/archimesh/service/impl/Neo4jOpLogSupport.java) stores receipts/batches; [`Neo4jMaterializedStateSupport`](../../server/src/main/java/org/gautelis/archimesh/service/impl/Neo4jMaterializedStateSupport.java) performs field merges and tombstones. No Turbo EA HTTP may enter this transaction. |
| Durable delivery | [`CommitDeliveryService`](../../server/src/main/java/org/gautelis/archimesh/service/CommitDeliveryService.java) acquires/renews the repository writer lease, rotates models and publishes a bounded ordered prefix, marking each commit published after acknowledgement. Keep its work independent of external availability. |
| Kafka | [`KafkaPublisherImpl`](../../server/src/main/java/org/gautelis/archimesh/service/impl/KafkaPublisherImpl.java) sends records keyed by model ID to three shared topics. [`KafkaConsumerImpl.handleRecord`](../../server/src/main/java/org/gautelis/archimesh/service/impl/KafkaConsumerImpl.java) validates routing and fans out to sessions. Integration consumption requires a separate group and durable dirty scopes plus periodic repair. |
| Consistent reads | Synchronized `ArchimeshService.getSnapshot(modelId, ref)` resolves HEAD or stored tag state. [`Neo4jReadSupport`](../../server/src/main/java/org/gautelis/archimesh/service/impl/Neo4jReadSupport.java) exports materialized entities, folders, properties, `fieldClocks` and tombstones. Publish actual snapshot winners, not each accepted patch. |
| Tags/recovery | `createModelTag` stores immutable snapshots. Checkpoints, rebuild and v2 import use [`Neo4jCheckpointSupport`](../../server/src/main/java/org/gautelis/archimesh/service/impl/Neo4jCheckpointSupport.java) and [`CheckpointCodec`](../../server/src/main/java/org/gautelis/archimesh/service/CheckpointCodec.java). Capture rejects unknown nodes with `modelId` and unsupported projection edges. `deleteModel` deletes all nodes with `modelId`. Integration metadata needs distinct property names and no projection edges. |
| Export and overwrite | [`EXPORT_FORMAT.md`](../../server/EXPORT_FORMAT.md) describes v1 full-history and v2 checkpoint/receipts/tail recovery. Integration state must have a separate backup format; overwrite/rebind cannot automatically adopt old external mappings. [`CheckpointMaintenanceService`](../../server/src/main/java/org/gautelis/archimesh/service/CheckpointMaintenanceService.java) is disabled by default. |
| Local capture | [`EmfChangeCapture.notifyChanged`](../../client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/emf/EmfChangeCapture.java) suppresses remote application and debounces local EMF notifications. [`OpMapper`](../../client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/emf/OpMapper.java) emits typed operations, IDs and causal metadata; connection creation includes its semantic relationship. |
| Accepted client apply | [`RemoteOpApplier`](../../client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/emf/RemoteOpApplier.java) dispatches onto SWT under [`RemoteApplyGuard`](../../client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/emf/RemoteApplyGuard.java), constructs EMF objects through `IArchimateFactory`, inserts concepts in `getDefaultFolderForObject`, and applies merge rules. This is direct guarded remote mutation, not an undoable command-stack wrapper. Semantic import and diagram placement must stay separate. |
| Offline recovery | [`ArchimeshSessionManager`](../../client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/ws/ArchimeshSessionManager.java) persists FIFO intent, waits for correlated `OpsAccepted`, rebases and retries. Only correlated `PRECONDITION_FAILED`/`LOCK_CONFLICT` get conflict-drop handling; new ownership errors need corresponding recovery behavior. |
| UI entry points | [`plugin.xml`](../../client/org.gautelis.archimesh.plugin/plugin.xml), existing model-open handlers and `ui/ModelCatalogClient` show the Eclipse command/HTTP pattern. [`admin-web/src/lib/api/client.js`](../../admin-web/src/lib/api/client.js) supplies admin HTTP behavior; model/access/version/operations routes supply UI conventions. New UI belongs after server import contracts. |
| Wire/type validation | [`schemas/ops.json`](../../schemas/ops.json), [`schemas/common.json`](../../schemas/common.json), `InMemoryValidationService`, and `ArchimeshService.validatePreconditions` define the current wire and reference checks. Reference validity is not a full ArchiMate semantic relationship matrix. The adjacent Archi source exposes `ArchimateModelUtils.isValidRelationship` backed by `RelationshipsMatrix`; derive/test server mapping validation against the actual supported Archi version. |

Some prose predates the current implementation: the graph model still describes
deleting clocks/commits during compaction, and older CRDT progress bullets mention
drop-oldest/poison-entry eviction. Current source, the outbox tests and
[checkpoint maintenance guide](../checkpoint-pruning.md) instead preserve safety
metadata/receipts and queued work on transport failure. Do not implement the
integration from those stale statements.

## Turbo EA API findings

The checked-in [static inventory](api-baseline.json) records the exact target
contract's parameters, response shapes, schema fields and fingerprint.
[Runtime results](runtime-baseline.json) record 19 passing API probes and the
fresh instance's type keys/relation directions/cardinalities. A bootstrap admin
and two synthetic applications plus one relation were created only in the
dedicated M0 project. Account passwords/tokens and returned business field values
are excluded from both evidence files.

| Area | Verified behavior and implementation consequence |
| --- | --- |
| Authentication | Ordinary REST accepts bearer JWTs. Login uses JSON `{email,password}`; refresh accepted bearer authentication. Unauthenticated card listing returned 401. OpenAPI has no security schemes/declarations: generated clients must not infer anonymous access. Source: Turbo EA `backend/app/api/deps.py`, `api/v1/auth.py`, `schemas/auth.py`. |
| Card search | `GET /api/v1/cards` uses `search` (maximum 200 characters), `type`, `status`, other explicit filters, one-based `page`, `page_size` 1–10000 (default 10000), `sort_by`/`sort_dir`. Response is `{items,total,page,page_size,withheld_ids}`. Probe returned one item out of two with `page_size=1`. Use an explicitly bounded page size; do not copy defaults blindly. |
| Card create/read/update | `POST /api/v1/cards` requires `type,name`, returns 201 `CardResponse`; `GET/PATCH /api/v1/cards/{card_id}` returns 200. Update has no `type` field. Optional `attributes`, `description`, `lifecycle`, `external_id` and other fields are recorded in the inventory. `strict_attributes` defaults false; approved publication should validate fields and opt into strict validation. |
| Card identity | Two sequential POSTs with the same `external_id` created different card IDs. Source `models/card.py` declares a nullable `String(500)` without uniqueness, and no external-id migration constraint was found. `reference` has a different unique auto-generated purpose. The list API has no `external_id` filter. Keep a local identity registry and ambiguous-create handling. |
| PATCH semantics | Card and relation PATCH replaced `{m0_keep,m0_drop}` with the supplied `{m0_keep}`; omitted top-level card name/`external_id` survived. Source uses `model_dump(exclude_unset=True)` and assignment, with special permission shaping for cost fields. Distinguish absent/null/empty map, preserve unowned members, and verify supported null behavior field by field before enabling writes. This is not JSON Merge Patch. |
| Conditional writes | No `ETag`, `If-Match` or idempotency-key contract appears on the selected operations, nor corresponding support in the inspected card/relation write paths. Do not invent headers or claim atomic conflict prevention. The probes did not attempt to prove arbitrary concurrent-write behavior. |
| Relations | `GET /api/v1/relations` returns an array, not the card pagination envelope; filters include `card_id`, `type`, `types`, `card_type`, `card_ids` (up to 500 IDs). `POST` requires `type,source_id,target_id` and returns 201. Resource PATCH is `/{rel_id}` and permits attributes/description only; there is no GET-single-relation operation in this contract. Re-read using scoped listing. |
| Relation reuse | Repeated sequential POST of the same typed ordered endpoints returned the same relation ID. `card_write_service.upsert_relation` orients endpoints and reuses `(type,source,target)`; it can also update supplied fields. This does not authorize adopting or overwriting a manual relation and does not prove concurrent uniqueness. |
| Metamodel | `GET /metamodel/types` and `/metamodel/relation-types` returned arrays. Their OpenAPI 200 schemas are unspecified `{}`: use inspected DTOs plus payload validation. Serializers in `api/v1/metamodel.py` return type `key`, `fields_schema`, subtypes, hierarchy settings and relation `source_type_key,target_type_key,cardinality,attributes_schema`. Actual keys are case-sensitive. Hidden types and sensitive permissions are shaped by caller rights. |
| SSE | Bearer-only SSE returned 401; cookie `access_token` yielded 200 `text/event-stream`. Source also accepts a `token` query parameter, which should be avoided in URLs/logs. OpenAPI incorrectly leaves the response as untyped JSON. `events._event_visible_to` grants general audit events only to `admin.events`; ordinary users receive their own directed events. `services/event_bus.py` uses process-local bounded queues (256), emits `data:` frames, has no replay/Last-Event-ID implementation, and may signal before a transaction commits. Treat it as invalidation only; poll regardless. |
| Errors/visibility | The static surface mostly declares success/422; source also raises 400/401/403/404 and imposes rate limits. Hidden cards can appear as 404; field/cost redaction and `withheld_ids` matter. Unauthorized or redacted reads must never trigger remote deletion/blank overwrites. Retry 429/network/5xx with bounds; pause/re-authorize 401, surface 403 and validation failures. A timed-out create stays ambiguous. |

All Turbo EA source paths in this section are relative to `../turbo-ea` at the
recorded commit. Public [API](https://docs.turbo-ea.org/admin/api/) and
[metamodel](https://docs.turbo-ea.org/admin/metamodel/) documentation are reference
material; their examples are not deployment evidence.

### Mapping implications

The live seed contains 13 card types and 46 relation types, rather than assuming
the public documentation's counts. Initial reviewed candidates are
`Application → ApplicationComponent`, `BusinessProcess → BusinessProcess`,
`Organization → BusinessActor` and `BusinessCapability → Capability`.
These are a proposed pilot whitelist, not enabled mappings or semantic approval.
The assignment's lowercase example keys do not match this instance's keys.

Keep `DataObject`, `ITComponent` and `Interface` unmapped pending explicit semantic
review. In particular the seed describes `Interface` as data flows/integrations,
which is not automatically an ArchiMate `ApplicationInterface`. A Turbo EA
hierarchy `parent_id` is not a relation row and is not automatically ArchiMate
Composition. Start with no relation mappings: the recorded type keys, direction
and cardinality are input to review, not permission to use generic Association.
Version/hash the approved mapping, metamodel and policy, and stop affected sync
when they drift.

## Regression baseline

All shared-directory Maven/build gates ran sequentially. Initial sandbox denial
of Maven cache writes was resolved by rerunning with normal cache access.
The first isolated attempt failed before container creation while Docker was
stopped; the rerun after Docker startup passed.

| Command | Result on 2026-11-11 |
| --- | --- |
| `./scripts/validate-local.sh fast` | PASS: notation parity and CRDT hardening contracts. |
| `./scripts/validate-local.sh gate` | PASS: fresh plugin install, 52 client tests passed; server 194 discovered, 143 passed and 51 skipped, zero failures/errors. Skips are expected without infrastructure/recovery flags. |
| `./scripts/validate-local.sh server-isolated` | PASS: 194 discovered, 193 passed, one skipped (`ProcessRecoveryIT`); infrastructure, authorization and WebSocket coverage included, zero failures/errors. Dedicated Compose resources were cleaned up successfully. |
| `python3 -B -m unittest discover -s tests/tools -v` | PASS: four offline inventory checks covering missing surface/exit status, security declaration omission, response shape/inherited parameters and invalid input. |
| Disposable Turbo EA API probes | PASS: all 19 checks in `runtime-baseline.json`; health, matching live/static contract, login/refresh, catalog/metamodel, identity/PATCH/relation behavior and SSE handshake. |

Toolchain: macOS arm64, Java Zulu 25.0.3, server Maven 3.10.0, client Maven wrapper
3.9.16, Python 3.11.17. Machine-local unit gate logs are under
`/tmp/archimesh-m0-baseline/`. Durable isolated test evidence (until `mvn clean`):
`server/target/isolated-server-gate/1fd34c67-ca74-4aa9-82bb-3f5ff8e40476/`, including
copied Surefire XML, infrastructure and cleanup logs, and exit code 0.
Turbo EA raw OpenAPI, source-build log, probe and private temporary Compose
environment are under `/tmp/archimesh-m0-turboea/`; only sanitized results are
versioned here. Do not publish the temporary environment file.

The separate packaged process-crash drill was not rerun: M0 changes no model,
delivery, client or recovery runtime. Run `./scripts/validate-local.sh recovery`
when those paths change in later milestones. Manual interaction in two real
Archi workbenches, production permissions, concurrent API races, fault injection,
null handling, rate-limit behavior and expired-token recovery remain unverified.
Prior handoff recovery results are historical evidence, not new M0 results.

Reproduce the static inventory without starting services or needing secrets:

```bash
python3 -B scripts/inspect-turboea-openapi.py ../turbo-ea/docs/api/openapi.json > /tmp/turboea-api-inventory.json
diff -u docs/turboea/api-baseline.json /tmp/turboea-api-inventory.json
python3 -B -m unittest discover -s tests/tools -v
./scripts/validate-local.sh fast
./scripts/validate-local.sh gate
./scripts/validate-local.sh server-isolated
```

The inventory command exits 1 if a selected operation/schema is missing, 2 on
invalid input. It records undeclared security as `null`, not anonymous access.
It reports a static surface, not compatibility certification. Inspect any digest
or shape difference before accepting a new baseline.

## Risks and milestone handoff

| Risk | Required handling / milestone |
| --- | --- |
| Integration nodes break checkpoint/delete isolation | M1: separate labels, `sourceModelId`, no projection edges; prove checkpoint/rebuild/delete/overwrite leave metadata governed explicitly. |
| Duplicate occurrence or uncertain remote create | M1/M2: composite unique keys, durable reservation and stable request/batch identity; M3: never retry uncertain card POST blindly. |
| Broad service account exposes restricted catalog | M1/M2: approved shared catalog or delegated identity, explicit actions and redaction tests; separate real deployment acceptance. |
| Accepted inbound update loses LWW or bypasses ACL | M2/M4: shared typed acceptance result, policy check and clock advancement under existing serialization; durable receipt plus materialized-state verification. |
| Offline authority denial retries forever or loses work | M4/M5: correlated conflict/recovery behavior in the existing outbox; two live clients plus one offline replay test. |
| Attribute replacement or concurrent web edit loses central data | M3/M5: preserve unowned members, baseline/fresh-read/post-write verification; shared writes disabled until race limitations are explicitly accepted. |
| SSE unavailable/restricted; Kafka signal missing | M3/M4: independent group, persisted dirty scopes and scheduled bound-resource reconciliation; SSE optional. |
| Restore loses identities or reuses another environment's mappings | M1/M6: versioned integration backup and paused verified reattachment; never treat a model export as integration backup. |
| Wrong semantic mapping | M1/M2: validate actual case-sensitive keys, field types, relation direction/cardinality and Archi relationship rules; reject unmapped types. |
| Several models overwrite one canonical object | M3: explicit contribution ownership and conflict policy; publication disabled by default, approved tag as managed default. |

M1 can proceed with the connector boundary, versioned mapping/authority model,
isolated metadata repository/constraints, target configuration and a server-only
HTTP client backed by mock tests. Keep it disabled by default. Use the verified
2.194.0 contract and payload shapes, and retain the ADR's recovery/security
requirements. Import UI, publication workers and inbound authority enforcement
remain later milestone work.
