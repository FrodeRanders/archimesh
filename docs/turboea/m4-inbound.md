# M4 — external changes into linked HEAD models

M4 adds continuous inbound reconciliation for mapped Turbo EA card fields.
Every change becomes an ordinary validated and durably accepted ArchiMesh batch,
then reaches connected clients through existing Kafka/WebSocket delivery. No HTTP
call runs inside model acceptance. Historical tags, views and geometry are not
changed. M3 publication excludes these centrally owned fields, preventing echo
writes back to Turbo EA.

## Approval and configuration

Integration and inbound access remain disabled by default. A target may declare:

```json
"inboundAccess": {
  "enabled": true,
  "administrators": ["named-model-admin"],
  "cardTypes": ["Application"],
  "fields": ["name"],
  "sseEnabled": false
}
```

The target's versioned mapping must assign those remote fields to local semantic
fields or `property:<key>` with authority `TURBO_EA_MASTER`. Other authorities and
unmapped fields are never ingested. Typed values are validated; numeric precision
is preserved. Structured property values use canonical JSON strings, multiselects
compare as sets, and missing central properties produce normal `UnsetProperty`
operations. Mapping/type changes require explicit review rather than retyping.

This is a separate visibility approval from catalog import and publication.
Only configure types and fields approved for disclosure to **all readers of each
participating model**; this is not delegated per-user Turbo EA access. Credentials
stay on the server, and the client/admin never receives the SSE stream or its raw
audit payloads. The configured service account needs only the required read rights;
M4 never creates or patches a remote resource.

Open **Inbound sync** in `/admin-ui/`, select a model and target, review the mapped
fields, and approve the policy. The caller must be both a configured inbound
administrator and an explicit administrator in that model's ACL. A global admin
role alone does not grant this capability. Policy approval compares the current
version, target identity and mapping version. The stored configuration digest
also fences changed field/type scopes under a reused mapping version.

Approval covers all bound cards in that model within the target's declared scope.
Each occurrence is reconciled independently: a model missing approval or current
permissions gets a durable paused diagnostic, while other approved models can
advance. The worker rechecks the original approver's live ACL and target scope.
No general-purpose privileged integration principal is exposed over REST or
WebSocket. The internal command is restricted to one verified binding and the
exact approved centrally owned fields.

Use **Pause inbound updates** to stop model changes while retaining field guards.
`app.integrations.inbound.worker-enabled=false` stops automatic reads/SSE while
retaining the policy guard and explicit administrative reconciliation. The global
`app.integrations.enabled=false` switch disables the entire integration, including
its guard; independent edits accepted during that period are detected as drift
when synchronization resumes and are not silently overwritten.

## Detection and bounded reconciliation

The worker scans every bound occurrence, not an operation/event replay stream.
Scans are paged, including Unicode model IDs, and restart safely from the beginning.
`app.integrations.inbound.interval-seconds` defaults to 300 (range 5–86400).
The worker is independent of normal commits, Kafka delivery and M3's consumer group.
A disconnected, unavailable or unauthorized Turbo EA target cannot block ordinary
edits to local-owned fields.

Optional SSE accelerates scans. The inspected local Turbo EA 2.194.0 endpoint
`GET /api/v1/events/stream` authenticates with an `access_token` cookie or query
parameter, **not** a bearer header. The implementation uses the cookie, never puts
credentials in a URL, and refuses redirects. Turbo EA exposes full card events
only to accounts with `admin.events`; other accounts receive their user-directed
notifications. Do not broaden permissions just to enable SSE: polling remains
sufficient when the chosen account cannot see card events.

SSE is an invalidation hint only. The parser extracts a bounded `card_id`, discards
all event field values, ignores resume IDs, and requests an independent card read.
It bounds line/frame sizes, event counts, connection traffic and listening windows.
A window reconnect does not imply an event history guarantee. Malformed streams or
401/403 stop SSE; transient errors and 429 respect bounded Retry-After/backoff and
stop after four failures, with polling continuing. Polling repairs lost, duplicated,
out-of-order or absent SSE events and normal server restarts.

Reads validate the current API version and metamodel. Per-binding retries persist
attempt counts and a jittered next-attempt time, stop after four failures, and are
also subject to the polling interval. Explicit **Reconcile** permits another attempt.
A remote 404 marks the binding `ORPHANED`; it is not recreated or reactivated by
an old import preview. Remote archival/inactive status is recorded as `RETIRED`
without deleting an element or its views. Local deletion/type/provenance drift is
reported; it does not cause implicit recreation or remapping.

## Baselines and acceptance

Reconciliation compares normalized `baseline`, current `local` HEAD and freshly
read `remote` fields. Equal local/remote values checkpoint without a new model
operation. Remote-only changes apply when local still agrees with the baseline.
Local-only or independent changes on both sides stop as `LOCAL_DRIFT` or
`BOTH_SIDES_CHANGED`. The baseline and observed conflict values remain in the
independent integration state for diagnosis. One conflicting field blocks the
whole proposed element update.

For M2-created elements, the retained verified import plan supplies the initial
baseline, so a central change between import and first polling is not lost.
Manually linked local values have no implicit agreement: differences require a
reviewed **Accept these central values into HEAD** action. Inspection includes
current values and a digest of the model revision, policy, binding, mapping and
remote values. A changed review is rejected. This action only accepts remote
values locally; centrally owned field proposals/write-through remain M5 work.

Before acceptance, the worker persists an `ExternalChangeCommand` in the binding's
inbound state, including correlation UUID, policy version, expected revision,
expected local values and the bounded remote observation. Under the existing
serialized model monitor, the server checks the binding, scope, live approval,
HEAD, type, provenance and expected values, generates only normal typed operations,
and assigns causal clocks above the persisted clocks. There is no competing merge
engine or direct graph mutation.

The existing commit path stores operations, materialized state, acceptance receipt
and delivery intent atomically. Only a durable accepted result followed by a fresh
snapshot verification advances the integration baseline. If the coordinator stops
after acceptance but before checkpoint, the retained command and receipt recover
without another model mutation or a fresh HTTP read. An unrelated intervening
local commit makes an unaccepted command stale; it is discarded and reconciled
again rather than applied against a stale revision.

## Ownership and offline clients

A database-only guard runs before ordinary field merge/revision assignment when
inbound target access is enabled. It protects every mapped `TURBO_EA_MASTER` and
`SHARED_PROPOSAL` field on a bound element, plus its canonical marker and semantic
type. It covers `UpdateElement`, property set/unset, property-set membership and
replacement `CreateElement` attempts. User, actor, session and causal-client names
cannot grant a bypass; a greater Lamport value cannot grant authority.

A rejected atomic batch receives correlated `EXTERNAL_FIELD_OWNED`. The plugin
drops that rejected outbox entry, retains later queued work, surfaces a conflict
and requests a cold checkout to repair optimistic local projection. Unrelated
already accepted batches retain normal receipt/idempotency behavior. Metadata
unavailability is a transient fail-closed policy error, with no remote HTTP in the
submission path. Trusted server import and external-change methods have separate
narrow entry points; normal client operation JSON cannot select them.

The existing UI/EMF apply pipeline applies accepted changes under `RemoteApplyGuard`.
A semantic element reused in several views remains one element and all references
see its new name. Native field-editor restrictions and central edit/proposal UI
are M5 work; the server guard is authoritative for offline and older clients.

## REST and operations

| Method/path under `/admin/models/{modelId}/inbound` | Action |
| --- | --- |
| GET | Authorized target keys |
| GET `/{target}?after=…` | Policy scope and bounded binding status page |
| POST `/{target}/policy` | Enable/pause with expectedVersion, targetInstanceId, mappingVersion |
| POST `/{target}/refresh/{elementId}` | Explicit reconciliation of one bound occurrence |
| GET `/{target}/resource/{elementId}` | Three-way inspection and review digest |
| POST `/{target}/accept-remote` | Resolution with elementId and inspectionDigest |

Status responses omit field values; inspection requires the same explicit model
admin and target scope. Responses are non-cacheable. Audits contain identifiers,
actions, decisions, field names, revisions and correlations rather than credentials
or central field values. Policy and inbound state reuse the independent constrained
`IntegrationStateDocument` store; binding orphaning changes only integration labels.

Model export/checkpoint/rebuild formats remain unchanged and exclude this metadata.
Use database-native backup including integration state. Disable publication and
inbound policies before model overwrite, deletion or restore; portable paused
reattachment, generation fencing and integration export/restore remain M6 work.
M4 supports card field updates; it does not infer central relationship imports,
rewire formal ArchiMate relationships or translate diagrams.

## Verified M4 run — 2026-11-12

| Check | Result |
| --- | --- |
| Hardening/notation checks | PASS. |
| Full plugin/client/server gate | PASS; all 58 client tests passed. |
| Final isolated server gate | PASS; 274 discovered, 270 passed, zero failures/errors; four opt-in tests skipped. |
| Inbound integration tests | All 12 passed with real Neo4j, real ordinary commits, persistent synthetic HTTP and real two-client WebSocket fan-out. |
| Field/SSE unit tests | PASS, including typed projection/precision, null removal, cookie-only authentication, malformed/bounded streams, duplicate invalidations and Retry-After. |
| Svelte production build | PASS; inbound route emitted. |
| Live Java SSE check | PASS against disposable source-built Turbo EA 2.194.0, observing invalidation after verified synthetic card PATCHes. |
| Packaged JVM crash/fresh-database restore drill | PASS. |

The integration suite covers three approved model occurrences of one canonical
card (including paged Unicode IDs), tags unchanged by HEAD updates, initial import
baseline recovery, independent local/remote drift, explicit reviewed resolution,
null properties, orphaning and old import retry, retirement, deleted local elements,
revoked/missing approval, high-clock/forged-actor rejection, bounded retries and
ordinary local-owned edits during a remote outage. A simulated coordinator stop
after model acceptance resumes its retained command without HTTP or duplicate ops.
The client suite verifies rejected offline intent causes a cold checkout while
later work remains queued, and a headless real EMF element updates both view
references under the remote guard.

The packaged recovery drill verifies the shared acceptance/delivery/restore runtime:
forced JVM stops, delayed Kafka fan-out, checkpoint-covered pruning, interrupted
transactional restore into a fresh database, receipt retries, stale merge rejection,
continued editing and rebuild. Specific inbound coordinator failure is exercised
in the infrastructure test rather than claiming portable integration-state restore.
Native Archi workbench interaction and the browser administration workflow were
not manually exercised; the plugin/real EMF tests and Svelte production build passed.

The live SSE probe used cached source-built images from adjacent checkout
`6d9220e52d47b41f9d79351ec3233ded1f76fdc6`, a dedicated
`archimesh-turboea-m4` Compose project, loopback HTTP and fresh volumes. The fixture
account/card were synthetic. Tokens remained in process memory; no query credential
was used. The project's containers, volumes and network were removed. The adjacent
checkout and all existing development data remain unchanged.

Machine-local evidence:

- `/tmp/archimesh-m4-fast.log`, `/tmp/archimesh-m4-gate-final.log`,
  `/tmp/archimesh-m4-web-final.log`.
- `server/target/isolated-server-gate/124f6cfc-ddc6-4b83-8fa1-225ec6b23209/`,
  including copied Surefire reports, infrastructure and cleanup logs, exit code 0.
- `server/target/recovery-drill/14ba2744-577b-4aed-a2de-924728499216/report.json`,
  with `passed: true` and the packaged application fingerprint.
- `/tmp/archimesh-m4-turboea/result.json`, `live-signals-report.xml`,
  `java-signals-test.log` and `cleanup.log`.
