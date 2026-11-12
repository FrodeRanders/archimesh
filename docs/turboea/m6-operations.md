# M6 — operations, reconciliation and recovery

M6 adds named operator administration at `/admin-ui/integrations`, authenticated
metrics and durable full scans, model incarnation fencing, a versioned integration
archive, and reviewed model attachment/mapping migration. It keeps model packages,
accepted operations and the integration metadata store separate. Normal model
commits perform no external HTTP and acquire no integration coordination lock.

## Deploy and enable

Keep the feature disabled until a deployment's target identity, mapping, service
account visibility and named users are approved. The example in
`server/examples/turboea-integration.example.json` denies catalog access,
publication, inbound synchronization and central edits by default. It also has
`"operators": []`. An operator must be explicitly listed **and** pass normal
ArchiMesh server administrator authorization. Model-admin access alone and an
unlisted global administrator cannot invoke operations endpoints. Production
identity/authentication must be configured using the existing server deployment
instructions; bootstrap headers are intended for trusted development setups.

Deploy the non-secret configuration file and set the existing global integration
properties. Supply Turbo EA credentials only through the named server environment
variable. Add approved operator IDs to the root `operators` array. Set the target
scopes and model approvals described in [M2](m2-import.md), [M3](m3-publication.md),
[M4](m4-inbound.md) and [M5](m5-central-edits.md). A service account is not delegated
user impersonation: approve the actual shared catalog and field visibility before
allowing users to request its data. Do not acknowledge complete attribute reads
for an account that cannot read every attribute in a replacement map.

Configuration is deployed as a file and validated on restart. The operations page
shows mappings, field authority, named scopes, target identities/API versions and
configuration fingerprints; it does not edit credentials or arbitrarily rewrite
mapping rules. Target probes verify the configured API version and current
metamodel. Credentials, token environment names and base URLs are omitted from
operator status responses. Configuration fingerprints include target routing and
all target scopes; set ordering is normalized across process restarts.

The independent metadata constraints are installed lazily when enabled metadata
is first used. `server/src/main/resources/integrations/schema-v3.cypher` assigns a
one-time incarnation token to legacy registered models. New registration and both
model import formats create a fresh token transactionally in the core model
repository. It is excluded from checkpoints, snapshots, tags and model packages.
Restart, ordinary commits and materialized-state rebuild preserve the token.

Upgrade from M1–M5 fails closed for old capabilities: old import journals,
publication approvals/jobs and inbound commands lack the new generation proof.
Existing bindings with no reviewed model scope require operator attachment review,
then fresh inbound/publication approvals. Old request IDs cannot replay old import
intent. Preserve the old metadata and uncertain write markers during migration.
Do not delete journals to make a failed retry pass.

Worker startup flags are independent:

| Environment property | Default when its target scope is enabled |
| --- | --- |
| `ARCHIMESH_PUBLICATION_WORKER_ENABLED` | `true` |
| `ARCHIMESH_INBOUND_WORKER_ENABLED` | `true` |
| `ARCHIMESH_CENTRAL_EDITS_WORKER_ENABLED` | `true` |
| `ARCHIMESH_INTEGRATION_OPERATIONS_WORKER_ENABLED` | `true` when named operators exist |

Check `application.properties` for the matching deployment property names and
bounded polling intervals. A durable runtime pause is separate from these startup
flags and survives restart. Global feature disable also releases ordinary ownership
guards; use **Pause integrations** for maintenance while keeping those guards.

## Runtime administration

The operations page provides:

- Durable global pause and a fresh target-probe/digest review before resume.
- Exact document/state and binding-state counts, verification ages and retry times.
- Paginated redacted diagnostics for jobs, conflicts, inbound state, canonical lanes,
  model scopes, checkpoints and deliveries. Field values appear only in explicit
  review or archive responses.
- A durable, paginated full scan for a selected target.
- Per-model pause and reviewed attachment of the current incarnation.
- Explicit metadata archive export, restore preview and paused restore.

Pause and model replacement try the shared remote-work barrier without waiting
under the model acceptance monitor. A busy response means existing bounded work
must finish before the operator retries; it does not cancel an HTTP mutation.
Publication, central edits, inbound reconciliation, import writes and SSE checkpoint
writes honor this barrier. Ordinary commits and local editing remain independent.
A pause can leave read-only SSE connections alive, but callbacks cannot bypass the
paused acceptance or metadata-write guards.

A full scan rereads every non-prepared bound card, including scopes previously in
error/conflict, without silently resolving local drift. It then schedules a fresh
job for each currently approved publication policy for that target. Stable scan
UUIDs, durable cursors and stable publication request IDs recover a stop between
pages without duplicate remote creates or accepted operations. SSE/Kafka loss and
retention do not require event replay: fresh reads and current snapshots repair
state. New models/bindings inserted behind a cursor can be included by the next
full scan. Prepared import reservations remain visible in the archive and import
journal and require their original recovery path rather than forced activation.

`COMPLETE` means the scan inspected its scopes and queued eligible publication
work. It does not mean every occurrence is synchronized. Its processed/failed and
scheduled counts distinguish inspection from later execution; inspect inbound,
publication and central delivery states for `PAUSED`, `CONFLICT`, `AMBIGUOUS`,
`PARTIAL`, `RETIRED` and `ORPHANED`. Scan failures include occurrences that did not
reach SYNCED and publication policies that could not be scheduled. Retry after
correcting authorization/availability; explicit field adoption still belongs in
the scoped inbound/publication/central-change review pages.

The authenticated Prometheus text endpoint is `/admin/integrations/metrics`.
It requires the same named operator and administrator access. Metrics include:

- `archimesh_integration_paused`.
- `archimesh_integration_documents{kind,state}`.
- `archimesh_integration_bindings{state}`.
- `archimesh_integration_oldest_verification_age_seconds{kind,state}`.
- `archimesh_integration_next_retry_timestamp_seconds{kind,state}`.

Labels exclude model IDs, resource IDs, users and field values. Verification age
measures time since successful verification, not source-event latency: an unchanged
source may produce no events. Old records without derived status columns are
reported as `UNRECORDED` until rewritten. Diagnostics retain their stored state and
codes. These gauges are database observations, not a claim that worker threads or
remote systems are currently healthy; use target probes, timestamps and application
health monitoring too. An observed timestamp accompanies the overview.

## Model overwrite, deletion and attachment

A binding refers to one model incarnation, not just a reused textual model ID.
Delete/replace/import changes the model's incarnation token even while the global
feature is disabled. Stored integration model scopes retain the old token. Old
imports, policies, central jobs, proposal proofs and typed inbound commands are
rejected before new model acceptance or remote dispatch. Existing receipts do not
grant a bypass into another incarnation. No remote card is deleted by model deletion.

After overwrite/restore, an operator reviews the model scope against a current
HEAD snapshot and fresh remote reads. The review checks local entity/type and
provenance markers, exact canonical card type, target/mapping identity, active
binding state, lifecycle, and mapped local/central values. Published relationship
bindings are checked against their current endpoint identities and exact remote
type/direction. Missing/deleted/orphaned/prepared identities block automatic
attachment and require an explicit repair decision. Unpublished publication
identities retain their reserved canonical ID; uncertain create markers remain a
write fence. No name-based identity inference occurs.

The confirmation is bound to generation, snapshot digest/revision, metadata,
scope version and remote observations. The final local check runs under ordinary
model serialization with no HTTP there. Metadata stays independent of the model
projection. Successful attachment clears old pending inbound observations and
baselines, preserves all remote uncertainty fences, and requires field baseline
review and policy reapproval. Local/remote differences cannot be overwritten just
because the model was attached. Old-generation jobs remain stale; new-generation
requests use fresh preview/approval.

A same-generation per-model pause also retains ownership checks. Operator attachment
review is the explicit resume path. If a multi-step metadata operation is interrupted,
retry review/attachment before reapproving policies; the model/core packages and
remote uncertainty records are not discarded.

## Mapping migration

Deploy mapping changes while integrations are paused, retaining a pre-change
configuration and backup. A changed mapping/scope fingerprint invalidates old
publication and inbound approvals; central requests carry their own strict scope
digest. No old publication approval can automatically expand to newly configured
fields.

The attachment review can apply a **compatible metadata version migration** while
globally paused: the current metamodel must validate, canonical resource/card type,
local ArchiMate type, and relationship endpoints/direction must still match. The
review lists old/new mapping versions. Confirmation changes only binding/publication
identity mapping versions and the relevant resource metadata. It clears inbound
baselines and outbound resource baselines and requires new approvals/adoption.
It never retypes a model element, rewires a relationship or changes a canonical ID.
Other models sharing the resource still need their own review and fresh approvals.

Unknown dispatch outcomes block mapping migration. Resolve them under the previous
configuration with the existing read-only outcome/adoption workflow before changing
versions. Unsupported semantic changes remain blocked: create an explicit new
binding/configuration plan, preserve old identities and have a steward approve the
migration. The server does not infer a general schema or target-identity migration.

## Integration backup and restore

Model export packages do not contain integration state. Keep matching model
packages and the integration archive together, or use a consistent database-native
backup that includes both model and integration labels. The archive includes
canonical/external bindings, identity claims, publication identities, contributions,
policies, baselines, journals, jobs, deliveries and uncertain-write fences for the
integration instance. It includes retained data values/proposals and is not encrypted;
store it with the same access controls as a repository backup. It contains no server
credential values and does not serialize configuration secrets.

Portable archive workflow:

1. Quiesce model clients for a consistent set of model packages. Pause integrations;
   export/checkpoint model packages using the existing Versions workflow.
2. Export integration metadata from the operations page. Export requires a quiescent
   pause and captures the independent metadata in one read transaction. Retain the
   configuration/version and archive content digest with the model packages.
3. Start a destination with the same logical ArchiMesh integration `instanceId` and
   matching target configuration, with all worker startup flags disabled initially.
   Install the current core and integration constraints. Do not point two active
   writers at the same remote resources with copied metadata.
4. Restore the model packages using the existing model import path. They receive new
   incarnation tokens. They can also be restored after the integration archive,
   but no integration work may resume during this process.
5. As a named operator, pause the destination, load the integration archive and run
   **Preview paused restore**. The preview checks format, content digest, instance,
   target/configuration fingerprints, bounded whitelisted metadata and an empty
   destination integration scope. Operator control state is never imported.
6. Confirm restore. One Neo4j transaction creates all records or rolls everything
   back on an identity/constraint conflict. Equivalent global canonical/external
   identities may be shared with other integration instances; incompatible ones
   cause rollback. Existing instance metadata is never overwritten or purged.
7. Global pause remains set and restored model scopes are forced paused. Restored
   policies are disabled and restored execution capabilities are quarantined with a
   non-matching generation token, even when the model database itself was retained. Probe the
   configured targets; review/attach each restored model, including any compatible
   mapping migration. Reapprove inbound/publication policies and explicitly review
   changed field baselines. Resume workers only for this reviewed configuration.
8. Verify old ambiguous remote outcomes through the explicit read-only workflow.
   An exact approved remote match may clear the fence; an unconfirmed result stays
   fenced. Old-generation jobs still cannot fan out or issue another PATCH. Current
   inbound reconciliation/new proposals perform reviewed delivery to the new model
   generation. Run full scans, inspect per-model results and confirm tags unchanged.

Restored jobs retain their original content/correlation and archived generation for
audit, but their execution generation is replaced with a quarantine token. New
requests/approvals are required; restoring metadata never revives delegated write
authority. Restored scan jobs remain paused and require a new scan request.

Exports now use `archimesh-integration-v2`, retaining read support for original v1
packages and their format-specific digests. V2 adds native entity IDs, semantic/native
type separation, owner scope and association-proof closure, including identities
with no model occurrences. See the [D1 registry/classification workflow](../../integrations/datahub-turboea/docs/d1-registry-migration.md).
The archive remains limited to 20,000 nodes and 8 MiB of UTF-8
metadata. Restore preserves unknown outcome markers, jobs, receipt correlations and
identity reservations. The digest detects accidental modification, not malicious
operator rewriting; endpoint authorization is the trust boundary. A mismatch of
logical instance, target routing/configuration or manifest is a deliberate stop.
Use a separately reviewed migration or database-native recovery when those identities
must change, or when the bounded archive is too large. Restoring a model under a new
ID does not copy or infer source bindings: it starts unlinked.

Do not automatically delete integration journals, ambiguous intents, fences or
idempotency records to reduce storage. Use database backup/archival and a reviewed
retention policy that preserves identity and recovery proofs. Model-history pruning
remains its existing checkpoint-covered operation and does not prune integration
state. There is no automatic central object deletion or metadata purge in M6.

## API summary

All operations endpoints use normal authentication, require the named operator plus
server admin authorization, and return `Cache-Control: no-store`.

| Under `/admin/integrations` | Purpose |
| --- | --- |
| `GET /` | Runtime pause, target/configuration summaries and exact counts |
| `GET /documents/{kind}?after=…` | Fifty redacted diagnostic rows per page |
| `GET /metrics` | Authenticated Prometheus text gauges |
| `POST /probe`, `/pause`, `/resume` | Probe; versioned pause; digest/version-bound resume |
| `POST /reconcile` | Stable UUID full scan for one target |
| `GET /models/{model}/review` | Fresh model identity/value/mapping review |
| `POST /models/{model}/pause`, `/attach` | Versioned model pause; reviewed attachment/migration |
| `POST /archive/export` | Quiescent bounded metadata archive |
| `POST /archive/preview`, `/archive/restore` | Strict preview and atomic paused restore |

The additional document kinds are `MODEL_SCOPE`, `OPERATIONS` and `RECONCILIATION`.
Structured audit logs record sanitized identifiers/actions/decisions and deny
codes; they do not log archive bodies, proposed values or credentials. The operations
page provides status/control, while existing per-model pages provide detailed
conflict/adoption workflows.

## Troubleshooting

| Diagnostic | Action |
| --- | --- |
| `INTEGRATION_BUSY` / operations conflict | Retry after bounded work finishes. Pause before export/maintenance; do not kill an unknown write to clear a lock. |
| `MODEL_REATTACHMENT_REQUIRED` / `MODEL_GENERATION_CHANGED` | Review current model attachment; reapprove policies. Old queued work cannot be replayed. |
| `PUBLICATION_CONFIGURATION_CHANGED` / inbound configuration mismatch | Review the deployed mapping/scopes and reapprove; never broaden an old approval implicitly. |
| `MIGRATION_REQUIRES_PAUSE` | Pause globally and repeat the current mapping review. |
| `MIGRATION_UNCERTAIN_OUTCOME` | Resolve the retained outcome under the prior configuration before migrating. |
| `ARCHIVE_DESTINATION_NOT_EMPTY` | Use an empty destination scope or database-native recovery. Restore does not overwrite existing integration records. |
| `ARCHIVE_CONFIGURATION_MISMATCH` | Verify logical instance, target routing, scopes and mapping version against the retained configuration. |
| `ARCHIVE_GLOBAL_IDENTITY_CONFLICT` | Investigate competing canonical/resource identity. The restore transaction has rolled back. |
| `BASELINE_APPROVAL_REQUIRED` / mapping baseline review | Inspect baseline/local/remote and explicitly adopt through the scoped page. |
| `AMBIGUOUS` / uncertain canonical lane | Verify read-only; never blindly resend POST/PATCH or delete the fence. |
| `PARTIAL` / paused occurrence | Review that model's ACL, inbound approval and local conflict; retry fan-out without a remote mutation. |

## Validation scope

M6 tests use a persistent synthetic Turbo EA HTTP service, real disposable
Neo4j/Kafka and ordinary model acceptance. They exercise named/operator authorization,
redacted diagnostics/metrics, durable pause with local editing and ownership checks,
v1/v2 model overwrite fencing, stale request refusal, reviewed attachment with
changed local/remote context, compatible mapping migration and uncertain-outcome
refusal, paged scan recovery, and two-model recovery with unchanged tags.

The archive test restores into a second fresh disposable Neo4j database and proves
whole-transaction rollback when a conflicting global identity is encountered after
other records were inserted. It retains an ambiguous committed PATCH, restores model
packages through the core path, then requires paused attachment/baseline review and
read-only outcome verification before both models converge without another PATCH.
The separate packaged JVM crash drill validates shared core process-crash, delayed
Kafka fan-out, pruning and fresh-database model recovery.

Live Turbo EA REST/PATCH/SSE contract probes from M0/M3/M4 remain the verified
connector baseline. Native Archi workbench interaction and a complete live Turbo EA
M6 disaster-recovery exercise remain deployment acceptance checks. Turbo EA's
verified lack of conditional writes still permits races with third-party editors;
M6 does not invent cross-system atomicity or durable SSE replay guarantees.


Verified run (2026-11-12):

| Check | Result |
| --- | --- |
| Hardening/notation contracts | PASS |
| Full plugin/client/server gate | PASS; all 62 client tests passed |
| Isolated server suite | PASS; 309 discovered, 305 passed, zero failures/errors, four opt-in skips |
| M6 infrastructure/recovery cases | All nine passed, including v1/v2 overwrite, migration, fresh metadata DB rollback/restore, two-model convergence and lossless decimal archive round-trip |
| Archive/configuration digest unit cases | All five passed |
| Svelte production build | PASS |
| Safari operations smoke | PASS with synthetic local API responses: pause, reviewed resume, completed scan diagnostics and reviewed attachment with reapproval guidance |
| Packaged JVM crash/fresh-database restore drill | PASS; no cleanup failures |

Machine-local evidence:

- `/tmp/archimesh-m6-server-final.log`, `/tmp/archimesh-m6-fast.log`,
  `/tmp/archimesh-m6-gate.log`, `/tmp/archimesh-m6-web-final.log`,
  `/tmp/archimesh-m6-recovery.log`.
- `server/target/isolated-server-gate/fc09c302-e431-40d7-a160-f27cfbae1771/`,
  including copied Surefire reports, infrastructure/cleanup logs and exit code 0.
- `server/target/recovery-drill/bea4a807-6c68-4198-a72f-55df8aa5d760/report.json`,
  including the packaged application fingerprint and `passed: true`.

The four isolated skips are the separately invoked packaged recovery profile and
three opt-in live Turbo EA probes. Both isolated databases, Kafka and the packaged
drill infrastructure used disposable projects/volumes; their cleanup completed.
Existing development data and the adjacent Turbo EA checkout were not changed.
The synthetic UI fixture used loopback only and was stopped after the smoke check.
