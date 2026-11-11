# M3 — controlled outbound publication

M3 adds selective server-side publication from accepted snapshots to Turbo EA.
It remains off unless both integration and target publication access are enabled.
M2 import/catalog behavior remains available independently. Inbound synchronization
and centrally owned field editing remain M4/M5 work.

## Approval and administration

Open **Publication** in `/admin-ui/`. The caller must be both a named target
publisher and an explicit member of that model's administrator ACL, including
when the development authorization switch is off. Global administrator roles
alone do not grant publication. The worker rechecks the original approver's
current access before executing each resource, alongside the writer lease.

Choose `DISABLED`, continuous `HEAD`, or an explicit immutable `TAG`; select at
most twenty semantic elements and twenty relationships, with selected endpoints.
Choose stewardship explicitly. Preview displays the projected types, canonical
identities and owned fields. Approval compares both the preview digest and policy
version, preventing an unnoticed concurrent policy replacement. Disabled pauses
publication; it does not delete previous external resources.

Preview is local and deterministic. A job captures the complete reviewed source
snapshot projection, mapping version and revision. The execution path checks the
live version/metamodel before writes. It never publishes operation patches or
Kafka payloads. LWW-losing accepted edits therefore cannot overwrite the materialized
winner. No diagram geometry, view membership, clocks or tombstones are sent.
A stale HEAD job is superseded; a tag job continues to use its immutable snapshot.

## Configuration

Extend the target in `server/examples/turboea-integration.example.json`:

```json
"publicationAccess": {
  "enabled": true,
  "publishers": ["named-model-admin"],
  "cardTypes": ["Application"],
  "relationTypes": [],
  "fields": ["name"],
  "acceptUnconditionalWriteRisk": false,
  "completeAttributeReadsApproved": false
}
```

Only fields explicitly mapped as `ARCHIMESH_MASTER` **and** allowed here are
projected. The example maps application names as `TURBO_EA_MASTER`; an administrator
must review that authority before choosing a pilot mapping suitable for creating
new local-owned applications. Mapping versions, types and directions are explicit;
there is no Association fallback or automatic mapping migration.

The verified API has no conditional card PATCH. Existing card updates require
`acceptUnconditionalWriteRisk=true`: the worker compares an approved baseline,
rereads immediately before PATCH, and verifies afterward, but cannot eliminate a
concurrent edit between the last GET and PATCH. Leave the switch false unless
that deployment risk is accepted. Creation still works without it.

Attributes PATCH replaces the entire attributes map. Publishing attributes also
requires `completeAttributeReadsApproved=true`, after verifying that this service
account receives the complete map without redaction. The worker preserves unowned
members of that map; partial/redacted reads cannot establish this guarantee.
Centrally owned fields, status, external IDs and card types are never patched.
Null represents an absent owned value; typed property strings are parsed and
validated, and multiselect values compare as sets.

`app.integrations.publication.worker-enabled` defaults to true but creates no
worker for disabled publication targets. `app.integrations.publication.interval-seconds`
defaults to 300 (range 5–86400). A separate Kafka consumer group
`archimesh-publication-<instanceId>` receives only change signals. Bounded periodic
scans repair missed signals, retention and restarts, and recheck remote drift even
for unchanged local projections. Normal commit/delivery transactions never call
Turbo EA and do not depend on its availability.

## Identities, conflicts and recovery

The schema installer adds v2 constraints for `IntegrationPublicationIdentity` and
`IntegrationIdentityClaim`. These, existing bindings, jobs, policy, resource baselines,
contributions, checkpoints and conflicts remain separate from the model graph:
`sourceModelId`, never `modelId`, and no projection relationships. M2 link/import
and M3 publication claim the same model-entity identity fence. Existing active
bindings reuse their canonical identity; unbound elements reserve a stable issued
URN before remote dispatch. Multiple model occurrences can contribute to one
external resource, while a single explicit steward controls outbound values.
Conflicting stewardship or unequal contributions stop with a recorded conflict.

Cards are created before structural relations. Relations use mapped ordered
endpoints; M3 does not edit relation attributes/descriptions. An existing unbound
remote relation requires explicit ID verification and baseline adoption, protecting
manually managed relations from automatic upsert adoption. No endpoint deletes
remote cards or relations. Removing a selection or a known deleted source marks
its contribution withdrawn after a complete successful reconciliation. Bound remote
404s become orphaned conflicts and are not silently recreated.

Each remote mutation first persists an `IN_FLIGHT` intent. A timeout, lost reply,
5xx or invalid success response retains that intent. Further jobs stop as ambiguous,
preventing a second card POST. After independently locating the resource, use
**Inspect baseline**, review the remote values and **Approve this baseline**.
An uncertain card creation additionally requires the returned canonical external ID
and desired fields to match. This records the verified binding without writing
remotely; queue a new reconciliation afterward. Existing linked card differences
also require explicit initial baseline approval. Later drift blocks PATCH until a
new remote baseline is reviewed. Conflicts stay visible; there is no automatic
last-writer-wins across systems.

Transient safe reads and definitive 429 responses use durable jittered backoff,
respect Retry-After, and stop after four attempts. Restarted RUNNING jobs resume
from their persisted plan and resource intents; interrupted writes require review.
Queue request UUIDs deduplicate retries. Single serialized execution plus the
existing server writer lease orders writes; CAS protects metadata updates.

REST endpoints under `/admin/models/{modelId}/publication/{targetKey}`:

| Method/path | Action |
| --- | --- |
| GET | Policy, latest safe job summary, resource states |
| POST `preview` | PolicySpec → local projection/digest |
| POST `policy` | Approval with policy, expectedVersion, previewDigest |
| POST `run` | Queue RunRequest with UUID requestId and optional previewDigest |
| GET `resource?canonicalId=…&resourceId=…` | Inspect selected resource and remote digest |
| POST `adopt-baseline` | Adoption with canonicalId, resourceId, remoteDigest |

GET `/admin/models/{modelId}/publication` lists authorized target keys. Responses
are non-cacheable, connector errors are sanitized, and policy/run/adoption calls
emit identifier-only audit records. Credentials stay server-side.

Integration state still requires database-native backup. Model packages omit it;
M6 adds portable paused integration restore/reattachment. A restored provenance
property without its registry is rejected rather than minted as a duplicate.
Disable publication before model overwrite, deletion or restore. M3 retains the
independent registry and policies; it does not provide an automatic model-generation
fence or disaster reattachment workflow. No production serializability or automatic
disaster reattachment is claimed.

## Validation

`ArchitectureProjectionTest` verifies typed owned fields, materialized values,
direction, canonical digests, missing entities and tombstones. `TurboEaClientTest`
checks bounded transport, exact write payloads and no implicit POST retry.
`ArchitecturePublicationIT` runs real Neo4j/model/checkpoint code against a persistent
synthetic HTTP repository, including committed writes whose replies are lost.
The ordinary Svelte production build includes the new administration route.

The live write check uses `TurboEaPublicationLocalIT` only when
`RUN_TURBO_EA_WRITE_IT=true`, with `TURBO_EA_BASE_URL` and `TURBO_EA_TOKEN` in the
environment. It creates two synthetic cards, patches one, and creates/reads a
structural relation: use a disposable target only. The connector explicitly uses
HTTP/1.1; the verified direct Uvicorn deployment rejected Java h2c-upgrade POSTs
with HTTP 422, while HTTP/1.1 passed. Decimal parsing preserves precision in typed
properties, remote replacement maps and persisted plans/baselines.

## Verified M3 run — 2026-11-11

| Check | Result |
| --- | --- |
| Hardening/notation gate | PASS. |
| Full plugin/client/server gate | PASS; 57 client tests passed. |
| Svelte production build | PASS; publication route emitted. |
| Final isolated server suite | PASS; 254 discovered, 251 passed, zero failures/errors, three opt-in tests skipped. |
| Publication integration tests | All 12 passed on real Neo4j, including durable ambiguity/restart, LWW winners, tags, drift, shared identities, manual relations, precision, withdrawals and revoked permission. |
| Live Java publication client | PASS against disposable source-built Turbo EA 2.194.0: two created cards, card PATCH, relation POST and verified reads. |

The live deployment reused cached source-built images from checkout
`6d9220e52d47b41f9d79351ec3233ded1f76fdc6` in a separate
`archimesh-turboea-m3` Compose project with loopback-only HTTP and fresh volumes.
Its account/token were synthetic; credentials stayed out of committed files.
That project's containers, volumes and network were removed. Existing Turbo EA
and ArchiMesh data and the adjacent source checkout were not changed.

Machine-local evidence:

- `/tmp/archimesh-m3-fast.log`, `/tmp/archimesh-m3-gate.log`, `/tmp/archimesh-m3-web.log`.
- `server/target/isolated-server-gate/001e0c09-884b-4f3b-ac4f-06eea8460c8f/`,
  with copied Surefire reports, infrastructure logs, cleanup and exit code 0.
- `/tmp/archimesh-m3-turboea/result.json`, `live-write-report.xml`,
  `java-client-test.log` and `cleanup.log`.

The process-crash/fresh-database model drill was not repeated for M3: normal
acceptance, delivery, checkpoint and restore runtime were not changed; the full
isolated suite exercised existing recovery tests and publication/checkpoint
isolation. M2's packaged recovery result remains documented separately. The new
browser workflow was build-verified; an attempted interactive smoke check could
not run because the browser access host was unavailable. The Kafka worker's
separate consumer path is implemented; the durable reconciliation scenarios are
exercised deterministically rather than claiming a production soak test.
