# Turbo EA integration: M2 catalog and single-object import

M2 adds a complete single-card workflow: an authorized architect searches an
approved Turbo EA catalog through ArchiMesh, reviews the mapping and ownership,
and creates, reuses or links a real semantic ArchiMate element. The accepted
operations reach connected clients through the existing durable Kafka/WebSocket
delivery. Diagram placement is a separate Archi action.

The [M1 foundation](m1-foundation.md), [M0 API findings](m0-analysis.md) and
[ADR](../adr/0001-external-repository-integration.md) remain applicable. Integration
and catalog access remain disabled by default. Publication and continuous inbound
updates are not enabled by this milestone.

## Configure an approved shared catalog

The [example target configuration](../../server/examples/turboea-integration.example.json)
now includes `catalogAccess`, initially denied:

```json
"catalogAccess": {
  "approved": false,
  "readers": [],
  "importers": [],
  "cardTypes": ["Application"],
  "fields": ["name"]
}
```

Set `approved` to true only after approving those **whole card types** as a shared
catalog for the listed users. Supply explicit reader and importer principal IDs;
every importer must also be a reader. Types must have configured mappings. Fields
are an independent visibility whitelist of remote paths, such as `name`,
`description` or `attributes.owner`. Name visibility is required. Leaving the
approval or user lists absent gives no catalog access, including for global
ArchiMesh administrators. This preserves existing M1 configurations safely.

Use delegated or appropriately restricted service credentials when a whole type
is not a valid shared scope; M2 does not implement per-card delegated access or
claim that model ACLs grant access to the service account's entire repository.
OIDC or trusted-proxy identity is appropriate for managed deployments. Bootstrap
headers and authorization-disabled mode retain their existing local-development
semantics and are not authenticated production identities.

Model ACL/role authorization is checked independently. Catalog reads require
model read access; imports require model write access and the target's importer
allowlist. The server resolves the principal at the transport boundary and
rechecks model write authorization inside serialized acceptance. Clients cannot
choose the operation actor or submit arbitrary operations through this API.

Set `ARCHIMESH_INTEGRATIONS_ENABLED=true`, provide the configuration path and
server token environment as described in M1. Enabling the foundation alone still
exposes no catalog without the explicit shared scope. No Turbo EA secret or base
URL is sent to the Archi plugin.

## Archi workflow

1. Open a connected, writable ArchiMesh `HEAD` model and choose **Tools → Enterprise
   Repository…**.
2. Select an approved repository and object type, search and page through results.
   Already linked objects are marked. Select an object and choose **Preview selected
   object**.
3. Review the source identity, ArchiMate type, selected initial values and authority.
   Choose **Create a new ArchiMate element**, **Reuse the existing linked element**,
   or **Link** a compatible existing model element.
4. Choose **Use in model**. The server commits the change; normal synchronization
   inserts the element in its model-tree folder. Drag that element into one or more
   ArchiMate views using ordinary Archi commands.

Network requests run off the SWT UI thread. Results update widgets on SWT. The
dialog is bound to the original server, principal, model and attached EMF instance;
changing that context requires reopening it. The command is disabled for a tag,
an unconnected model or no attached model.

Creating imports only explicitly mapped and catalog-approved values. Local-only,
unmapped and proposal-only fields are excluded; name mapping is required.
Linking preserves an existing element's name, documentation and placement. It
adds a normal informational `archimesh.external.canonicalId` property. That
property is also added on create. The separate identity registry is authoritative:
editing or copying a model property cannot establish or redirect a binding.

Imports are accepted server operations, not a separate optimistic local EMF edit
or an undoable remote command. The existing `RemoteOpApplier` creates the concept
under `RemoteApplyGuard`; later local edits and diagram placement keep Archi's
normal capture/command behavior. Nothing is automatically drawn into a view.

For a timeout or transport failure the dialog keeps the same request and offers
**Retry import**. A definitive stale-preview or compatibility rejection permits a
new preview. The server's durable identity reservation also prevents duplicate
elements when a dialog is closed and reopened.

## REST contract

All paths below are under `/models/{modelId}/external-objects`. They use normal
ArchiMesh identity resolution, explicit catalog scope and `Cache-Control: no-store`.

| Method/path | Behavior |
| --- | --- |
| `GET /targets` | Lists only targets and types approved for this user, with import capability. No external call. Disabled integration returns an empty list. |
| `GET /catalog?target=…&type=…&query=…&page=1&pageSize=25` | Searches one explicitly approved type. Returns bounded summaries and the scoped pagination envelope. It never returns raw attributes, `external_id`, `withheld_ids` or unapproved descriptions. |
| `GET /preview?target=…&cardId=…&ref=HEAD` | Verifies the API/metamodel, type/field mapping and active card, then returns selected initial fields/authority, compatible model elements, current binding state, mapping version, HEAD revision and a preview digest. |
| `POST /import` | Typed single-card create/reuse/link command below. Rechecks permissions, preview/model state, identity and operation acceptance. |
| `GET /{elementId}/status` | Returns this model's authorized binding identity, mapping version and status. A missing/mismatched entity or provenance is reported as `DRIFT`, not silently recreated. |

Example import body:

```json
{
  "requestId": "a2b84bcf-bd7f-44df-b534-95486a9bfc97",
  "targetKey": "development",
  "cardId": "<selected-card-id>",
  "ref": "HEAD",
  "mode": "CREATE",
  "elementId": null,
  "previewDigest": "<digest-returned-by-preview>"
}
```

For linking use `mode: "LINK"` and a compatible `elem:…` ID. Compatible choices
are bounded to the first 200 elements in the current model. A stale selection or
an already different canonical binding is rejected. Explicitly targeted links
never silently choose another element. `CREATE` reuses an existing linked
occurrence instead of creating another one. Different cards with the same name
remain separate identities.

The successful response includes `requestId`, `elementId`, `canonicalId`,
`targetKey`, `cardId`, the reserved `opBatchId`, `acceptedRevision` and `reused`.
It acknowledges a durable model commit and verified initial materialized result,
not ongoing agreement with future remote edits or completion in every client.
Retrying a completed request returns its original result after permission and
binding checks, even when Turbo EA is unavailable.

Application-level failures use a bounded `{ "code": "…" }` body. Common statuses are 400 for invalid
requests, 403 for authorization/scope, 404 for unavailable target/type/object,
409 for stale preview, conflicting identities, mapping/field divergence or a
deleted binding, and 502/503 for remote or metadata failures. No remote body,
credential or parser cause is returned. Import audit records contain principal,
model, target/resource identity, request ID and decision status; operation logs
redact external import values.

## Acceptance, identity and recovery

The model service now factors its existing acceptance sequence through a private
response sink. Ordinary WebSocket submissions retain their replies and wire
contract. A typed `ExternalModelImport` uses the same validation, revision,
transaction, receipt and delivery path with a private `SubmissionResult`;
`OpsAccepted` is not broadcast to unrelated clients and no fake session is used.

Before new acceptance, the synchronized service verifies explicit HEAD, current
write access, expected revision, semantic type, entity existence and tombstones.
It constructs clocks above the persisted snapshot's clocks, rejecting overflow,
and then invokes the shared pipeline. Remote HTTP and integration transactions
are outside this model monitor. Turbo EA downtime cannot block ordinary commits.

Create submits `CreateElement`, an explicit `MoveElementToFolder`, the
informational canonical property and any approved additional initial properties
as one normal batch. Folder mapping follows the approved pilot type:
ApplicationComponent → application, BusinessActor/BusinessProcess → business,
Capability → strategy. Link submits only the canonical property operation.
Mapping property aliases cannot overwrite native entity identity, semantic fields,
CRDT metadata or the reserved provenance namespace.

The metadata transaction reserves the canonical/model/external identity and the
winning mode, element ID and batch ID. Concurrent requests share that durable
reservation. `bindingMode` is an additive metadata property; old M1 reservations
without it are read as CREATE. Constraint identities and model formats are
unchanged.

Versioned `JOB` documents retain the exact user request, fingerprint, source
model, actor identity, remote type, immutable operation plan, preview/mapping
version and final outcome. Reusing a request ID with a different user or body is
rejected. A prepared reservation is not an accepted import. Activation requires
the exact durable receipt plus a verified semantic entity, selected values,
provenance and folder placement.

If the coordinator stops after the model commit but before activation/checkpoint,
replaying the original request loads the stored plan, obtains the existing
acceptance receipt, verifies and activates it without another model batch or
remote request. A new request for the same card reuses the same reservation;
independent remote/mapping changes against a pending plan produce a visible
conflict rather than silently replacing accepted intent. The stored request and
audit correlation allow diagnosis/replay. There is no cross-system transaction
or exactly-once claim.

Deleting or changing an imported model element does not delete its Turbo EA
card. Stale or missing entities produce drift and do not trigger automatic
recreation. Existing tags, checkpoints, export/rebuild and pruning continue to
use their existing contracts. Integration state remains outside model exports;
the informational canonical property is ordinary model data and is exported as
such. Restore/reattachment still needs the separate M6 procedure.

## Validation and scope

The supported commands remain sequential:

```bash
./scripts/validate-local.sh fast
./scripts/validate-local.sh gate
./scripts/validate-local.sh server-isolated
./scripts/validate-local.sh recovery
```

New coverage exercises shared-catalog denial/redaction, model ACLs, preview
staleness, immutable tags, compatible links, request correlation, concurrent
imports, same-name distinction, cross-model canonical identity, explicit folder
placement, post-commit coordinator failure, deleted-binding drift, checkpoint
rebuild, remote outage isolation and import fan-out to two real WebSocket
clients. Client HTTP tests verify identity/encoding and stable retry requests.
A headless test applies accepted semantic operations under the remote guard to
real Archi EMF objects, checks the application model-tree folder and references
the same semantic element from two views.

The plugin builds against the existing target platform. The SWT dialog has not
been manually exercised in two running Archi workbenches; the two-client delivery
test uses real WebSockets and the EMF test runs headlessly. Relation import,
multi-card transactions, central write-through, continuous refresh, publication
and ownership enforcement remain subsequent work. M2 intentionally starts with
one card, as specified; it does not turn arbitrary remote relations into generic
Associations or silently enable later milestone features.

## Verified M2 run — 2026-11-11

| Check | Result |
| --- | --- |
| Fast hardening/notation gate | PASS. |
| Full client/server gate | PASS; all 57 client tests passed. |
| Final installed-plugin client suite | PASS; 57 tests, zero failures/errors. |
| Final isolated server gate | PASS; 234 discovered, 232 passed, zero failures/errors. The separate process drill and opt-in live Turbo EA read test were skipped in that gate. |
| M2 REST/import integration tests | All 14 runs passed, including five repetitions of the four-request concurrent import race and real two-client WebSocket fan-out. |
| Packaged crash/restore drill | PASS; delivery retry, forced JVM restarts, checkpoint/pruning, interrupted transactional restore, fresh-database recovery, continued editing and rebuild. |
| Plugin build | PASS against the existing Archi/Eclipse target platform. |

The final concurrency regression distinguishes model/remote preview content from
metadata-only reservation changes. An intermediate run exposed a false stale
preview rejection when another importer reserved the identity; the corrected
fingerprint still fences actual HEAD/remote changes while allowing all concurrent
requests to converge on the same durable element and batch.

Machine-local evidence (build artifacts are not committed):

- `/tmp/archimesh-m2-gate.log`, `/tmp/archimesh-m2-client-final.log` and
  `/tmp/archimesh-m2-client-build-final.log`.
- `server/target/isolated-server-gate/3b82b93e-6221-4d70-91c5-205f59fc440c/`,
  including copied Surefire XML and cleanup logs.
- `server/target/recovery-drill/3ceb4f32-a070-4851-80d0-d05019b7ae9d/report.json`,
  with `passed: true` and the packaged application fingerprint.

All infrastructure runs used dedicated projects and cleaned up their own
resources. The adjacent Turbo EA source and existing development data were not
modified or reset. This milestone used the verified 2.194.0 connector contract
and a synthetic HTTP catalog for the authenticated import tests; it does not
claim a manually exercised production deployment or native Archi UI session.
