# M5 — controlled central changes

M5 adds explicit requests from the Archi plugin and ArchiMesh administration UI.
A permitted write-through is queued after preview/confirmation. A proposal stays
`WAITING_APPROVAL` until a separately scoped model administrator approves its
reviewed values. Verified central changes return to linked HEAD occurrences
through the existing M4 ordinary operation/receipt/Kafka/WebSocket path. Tags and
diagram geometry remain unchanged.

## Enablement and access

The example in `server/examples/turboea-integration.example.json` denies central
edits by default. Global integration enablement, an enabled target inbound scope
and a model-specific inbound approval are prerequisites. Add a target scope such
as this only after choosing the named users, mapping and permitted fields:

```json
"centralEditAccess": {
  "enabled": true,
  "requesters": ["architect"],
  "approvers": ["repository-reviewer"],
  "cardTypes": ["Application"],
  "fields": {
    "name": "PROPOSAL",
    "attributes.businessOwner": "WRITE_THROUGH",
    "attributes.classification": "PROPOSAL"
  },
  "acceptUnconditionalWriteRisk": true,
  "completeAttributeReadsApproved": true
}
```

This fragment assumes corresponding mappings and inbound field/type scopes.
Only `TURBO_EA_MASTER` and `SHARED_PROPOSAL` mappings are eligible, and shared
fields require `PROPOSAL`. A request containing any proposal field requires
approval of the whole request. Status, type, identity and external ID changes
cannot be requested. Relationship editing remains the M3 explicit mapping flow.

Requesters must also be actual model writers/admins. Approvers must also be actual
model admins. Global roles cannot bypass the target's named users or actual model
ACLs. A user may occupy both scopes if explicitly configured; configuration controls
that choice. Current ACLs, scope/mapping digests, binding identity and the source
model's inbound approval version are rechecked before remote dispatch. Changed
scope invalidates old requests. The service account remains server-side and is
not delegated impersonation; it needs sufficient Turbo EA access for the approved
resource/fields. Neither UI exposes its token or a generic write proxy.

Turbo EA 2.194.0 has no verified ETag/If-Match guarantee, and an attributes PATCH
replaces the map. M5 rereads before writing, compares selected fields to the
reviewed base, and preserves all unowned attributes from the latest read. It
requires explicit unconditional-write risk acknowledgment, plus complete attribute
read acknowledgment for attribute edits. Concurrent third-party writes between
read and PATCH can still race. Do not enable these acknowledgments for an account
that cannot read the complete attribute map.

`ARCHIMESH_CENTRAL_EDITS_WORKER_ENABLED=false` stops background dispatch/fan-out.
Pausing a model's inbound policy prevents its delivery and prevents new source
requests. Ownership guards remain in force when workers or model policies are
paused. Global integration disable releases the entire feature, including its
ordinary local-edit guards; reenabling requires drift review.

## Request, review and conflict workflow

In Archi, use **Enterprise Repository: Propose/change central data...** on the
connected model. Choose an approved target and linked semantic element, inspect
field authority/mode and baseline/local/central values, enter a proposed value,
preview and confirm. No EMF mutation is performed by this command. The request
uses the attached model's current authenticated ArchiMesh session; context changes
invalidate pending callbacks. Recent request status is available in the dialog.

The `/admin-ui/central-edits` route supports the same request workflow and scoped
approval. A reviewer sees the original proposal against current baseline, local
and central values. A changed original local/central base requires a new proposal.
Missing baselines or deliberate drift resolution require an explicit checkbox;
write-through cannot silently resolve drift. Reject/cancel does not write remotely.
Stable request UUIDs and exact payload fingerprints permit response-loss retries;
a reused UUID with changed intent is rejected. Typed values other than STRING
must be submitted as JSON **text strings** inside `proposed`; `null` clears a
nullable field. Limits are eight fields and 32,768 JSON characters of proposed data. Dedicated display
strings preserve decimal precision through JavaScript and SWT review surfaces.

Native Archi property editors remain optimistic. Ordinary edits to central/shared
fields are rejected by the authoritative pre-merge server guard, including offline
batches and forged integration actors. The existing correlated conflict handling
retains later queued work and performs a cold checkout. M5 deliberately uses an
explicit central command instead of intercepting arbitrary native EMF commands.

Each model occurrence needs its own current inbound approval. Fan-out compares
its local value to the agreed baseline and approved pre-change value. Independent
local intent becomes a per-model conflict; it is never silently overwritten.
An authorized inbound administrator can inspect and explicitly accept the selected
approved central values into **their own model**, without becoming a requester or
approver on the source model. Other conflicting, unselected central fields still
need the normal M4 inbound resolution workflow.

## Durable outcomes and recovery

The normal sequence is:

```text
WAITING_APPROVAL -> PENDING -> IN_FLIGHT -> REMOTE_VERIFIED -> SYNCING
                                                       -> SYNCED / PARTIAL
```

Write-through starts at PENDING. Rejected/cancelled requests are terminal. Newer
queued intent for the same canonical card supersedes an older undispatched job.
Before PATCH, one Neo4j transaction records both the dispatch intent and canonical
lane fence using compare-and-set versions. M3 publication and M5 share the writer
lease/remote lock and honor each other's durable uncertain-write markers.

Timeout, lost response, 5xx, malformed response or unavailable/divergent readback
after dispatch leaves `AMBIGUOUS`. Restarted `IN_FLIGHT` becomes ambiguous too.
The PATCH is never blindly retried. A separate **Verify remote outcome** action
performs a read only; exact approved content clears the fence and resumes fan-out.
A different value remains ambiguous, blocking later writes to this canonical card.
Investigate the remote card and retained intent before any operator repair. M5
provides no unsafe "assume not applied" reset. A definitive denied PATCH clears
its fence; safe pre-dispatch read failures and explicit 429 responses use bounded
retry with jitter/Retry-After. A 429 during successful PATCH readback is ambiguous.

`remoteVerified` means the remote content was read back successfully. It does not
mean every model received it. The UI shows synced/pending/conflict counts and the
source model's delivery state separately. Paginated fan-out checkpoints each
binding/result atomically with its job cursor/counts. Paused or conflicting models
produce `PARTIAL`; retrying model delivery reads/reconciles without another PATCH.
The latest canonical job and current remote values must still match. New imports
after a completed scan can be included by explicit delivery retry. A per-model
conflict can be resolved after the full initial scan completes.

M4 acceptance receipts recover a crash between model commit and integration
checkpoint without duplicate model operations or remote PATCH. The only additional
core acceptance check rejects superseded proposal proof before a new commit;
accepted receipts remain authoritative. Shared field baselines survive subsequent
ordinary inbound polling. M3 excludes these central/shared fields from publication,
preventing an outbound echo.

## API and storage

All routes below use normal ArchiMesh authentication beneath
`/models/{modelId}/central-edits` and return `Cache-Control: no-store`:

| Route | Purpose |
| --- | --- |
| `GET targets`, `GET {target}/capabilities` | Current request/approve/resolve scopes |
| `GET {target}/bindings`, `GET {target}/context/{element}` | Bounded linked elements and field context |
| `POST {target}/preview`, `POST {target}/requests` | Preview, then stable UUID request |
| `GET {target}/requests[/{id}]` | Paged jobs and separate delivery status |
| `GET {target}/requests/{id}/review` | Fresh scoped approval review |
| `POST {target}/requests/{id}/decision` | Version/digest-bound approve or reject |
| `POST {target}/requests/{id}/cancel` | Requester cancellation before verified remote outcome |
| `POST {target}/requests/{id}/verify-outcome` | Explicit read-only ambiguous-outcome verification |
| `POST {target}/requests/{id}/retry-fanout` | Reconcile occurrences without remote mutation |
| `GET {target}/deliveries` | Only the current model's incoming deliveries |
| `GET/POST {target}/incoming/{id}/{element}` | Inspect/resolve this model's approved incoming values |

`CENTRAL_EDIT`, `CENTRAL_LANE` and `CENTRAL_DELIVERY` reuse the independent
`IntegrationStateDocument` store/constraints. They do not add `modelId` to core
model nodes or change model exports, snapshots, history or rebuild formats. Logs
record sanitized actors/identifiers, actions, field names and decisions; denied
REST actions record status/code without proposed values, bodies or credentials.

Use database-native backup including all integration metadata. Disable integration
workers/policies before model overwrite, deletion or restore, and retain uncertain
intents/fences. Portable integration-state export/paused reattachment, model
identity generation fences, retention/metrics and full operational restore remain
M6 work. The existing packaged recovery drill validates core model recovery; it
is not a claim of portable integration-state restore.

## Validation

The M5 suite uses a persistent synthetic Turbo EA HTTP service, real isolated
Neo4j/Kafka and ordinary model acceptance. It covers write-through fan-out to two
models, unchanged tags, unowned attribute preservation, approval and drift checks,
shared local conflicts and resolver-only access, stable UUIDs, decimal precision,
uncertain writes/readback rate limits, bounded safe retries, ACL revocation,
superseded jobs/proofs, forged values/fields/resources, publication fencing,
transaction rollback and a stop between model acceptance and checkpoint.

The client suite covers authenticated ArchiMesh routing, exact typed JSON text,
stable retries, escaping and sanitized errors. The plugin compiles against the
Archi target platform. The Svelte production build passes. A Safari smoke check
with synthetic local API responses exercised request preview/confirmation, pending
proposal, approval and separate verified/delivered status. A native Archi workbench
session and a live Turbo EA M5 orchestration were not manually exercised; the M3
live PATCH and M4 live SSE API findings remain the connector baseline.


Verified run (2026-11-12):

| Check | Result |
| --- | --- |
| Hardening/notation checks | PASS |
| Full plugin/client/server gate | PASS; all 62 client tests passed |
| Isolated server gate | PASS; 295 discovered, 291 passed, zero failures/errors; four opt-in tests skipped |
| M5 integration cases | All 17 passed |
| M5 field/configuration unit cases | All four passed |
| Svelte production build | PASS |
| Browser request/review/approval smoke | PASS with synthetic local API responses |
| Packaged JVM crash/fresh-database restore drill | PASS |

The four isolated skips are the separately invoked packaged recovery test and
three opt-in live Turbo EA profiles. Machine-local logs are
`/tmp/archimesh-m5-fast-final.log`, `/tmp/archimesh-m5-gate-final.log`,
`/tmp/archimesh-m5-web-final.log` and `/tmp/archimesh-m5-isolated-final.log`.
The isolated run retained copied Surefire/infrastructure/cleanup evidence and exit
code 0 in
`server/target/isolated-server-gate/484caf26-d44d-4d8b-b2ec-339bc6a30a6a/`.
Its disposable containers, volumes and network were removed; development data
was not used.

The packaged recovery drill passed all process-crash, delayed fan-out, pruning,
interrupted restore, receipt retry, stale-merge rejection and continued-edit/rebuild
assertions. Its report, with the packaged application fingerprint and no cleanup
failures, is
`server/target/recovery-drill/ee438dac-6df5-4cea-98f7-0a84e4d78c7b/report.json`;
the console log is `/tmp/archimesh-m5-recovery.log`.
