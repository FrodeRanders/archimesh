# First live dataset slice

The standalone worker discovers DataHub OSS datasets through bounded authenticated
GraphQL pagination, then calls the registry owner's versioned HTTP capability.
It imports no model engine code and keeps no second identity database. Run it with
`python3 scripts/run_bridge.py --config bridge.json` or `Dockerfile.bridge`.
The owner currently runs in ArchiMesh; an Archi model is not required for projection.

## Approval and identity

The owner federation configuration contains explicit `projections`: source/type,
target/type, named principals, dataset URN prefixes, ordered description fields,
`approved`, `acceptUnconditionalWriteRisk` and an `approvalVersion`. Source and
target configurations independently validate credentials, origins and mapping.
The caller must have `federation_bridge` and be listed in the profile. This is a
standing scoped write authorization, separate from ordinary model publication.
Limit the source account and downstream audience to approved disclosure scopes.

The owner reads the original source itself. It never accepts caller-supplied field
values. `POST /federation/v1/projections/prepare` accepts
`{profile,nativeId,correlation}` (correlation is a UUID). It reserves the full
source reference and a canonical `catalog.dataset` identity. It returns
`{intentId,version,state,canonicalId,targetId}`. Execute with
`POST /federation/v1/projections/execute` and `{source,intentId,version}`, where
`source` is that same prepare request. Authentication follows the owner boundary;
bootstrap identity is restricted to loopback. Production uses authenticated bearer
identity. No credentials belong in these request bodies.

The worker keeps a stable correlation per profile/URN. An already verified source
observation is a no-op; source changes create a new intent chained to the last
verified one, including A → B → A changes. Turbo EA allocates its own UUID. The
source URN and target UUID bind atomically to the same canonical identity after
readback. Editable `external_id` values are only provenance hints.

## Writes and uncertainty

The existing target-intent ledger durably records dispatch before HTTP and shares
canonical mutation lanes with model publication and central edits. The owner
rechecks source, approval, writer lease, mapping and target baseline before a
write. CREATE sends the dedicated target type; UPDATE sends only owned name and
description. Unowned attributes are preserved. Target drift blocks publication.
There is no automatic name matching, adoption, hard delete or rename-by-URN merge.

Independent readback is required before verification and binding. A failed or
ambiguous dispatched write becomes UNCERTAIN and blocks another write. Restarting
the worker never blindly reposts it. An operator must investigate the target and
use the reviewed ledger recovery workflow; restoring an archive quarantines
execution capabilities. A PREPARED intent may be cancelled when source/approval
changes before dispatch. Source removal blocks rather than deleting its mirror.

Turbo EA lacks conditional writes in this profile. Explicit risk acceptance is
required; baseline/readback checks detect many races but cannot provide atomic
cross-system transactions or prevent a concurrent remote write between checks.

## ArchiMesh consumer

A dedicated `DataHubDataset` type maps to ArchiMate DataObject, distinct from a
logical business DataObject. Both name and documentation retain `DATAHUB_MASTER`
authority through Turbo EA. Catalog disclosure, preview and inbound refresh verify
registry proof and reread the original DataHub source. Stale/unverified mirrors,
source removal and source outages block managed fields. Local commits remain
independent. Import is explicit and uses the existing occurrence reservation and
normal durable operation acceptance. Subsequent updates reach linked HEAD models;
immutable tags remain unchanged.

The [order demo](../../../examples/order-management/README.md) generates its scoped
configuration, starts the worker and configures model inbound policy. Its synthetic
service accounts and one-day sessions are for local demonstration. Use
`demo.sh start` to renew sessions and `demo.sh connection` to find the current run.
The acceptance probe creates a dataset after startup, imports it, updates and
reverts its name, repeats import and restarts the worker while asserting stable IDs.

Remaining scope: continuous data products, Turbo EA applications → DataHub,
direct DataHub model import, broader lifecycle/reconciliation UX and deployment
specific least-privilege/disclosure approvals. Seeded products/application references
do not prove those lanes.

## Acceptance evidence — 2026-11-13

- Clean `./scripts/demo.sh test`, run `47375833d66d`: passed image builds,
  seed/readback, post-start dataset discovery, explicit model import, source name
  update/reversion, duplicate import, bridge restart and full-stack restart without
  reseeding. Its containers and volumes were removed after verification.
- Standalone bridge unit/HTTP tests: 17 passed. Demo/bootstrap tests: 13 passed.
- Isolated full server gate: 347 discovered, zero failures/errors, four opt-in
  skips. Later publication checks additionally exercise legacy HEAD rejection and
  the reused-tag-name case with deletion explicitly enabled in the test profile:
  all 14 publication tests passed. All six final dataset projection tests passed.
- Existing local demo `705618b4b5b4`: live catalog returned nine datasets and the
  worker reported nine verified, zero blocked. Enabling preserved the original
  26-element, 49-relationship snapshot; its export was saved before activation.
  The final owner image also preserved all nine existing target dataset IDs.

The reused-correlation check requires rejection when a request identifier is
reused for a different source. Distinct requests for equally named source assets
retain different canonical and target IDs. New canonical candidates include the
exact source repository reference; established bindings continue to win lookup.
