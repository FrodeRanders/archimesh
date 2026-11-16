# Governed TurboEA → DataHub Application synchronization

The bridge now supports both source directions without an Archi model or client:

- DataHub datasets **and data products** → approved TurboEA representations.
- Approved TurboEA applications → native OSS DataHub `Application` representations.

DataHub owns catalog identity and selected dataset/product metadata. TurboEA owns
enterprise-application identity and the selected application fields. DataHub may
associate its Application representations with datasets/products independently.
Neither transport direction transfers field authority to the mirror.

## Runtime and identity

The independent standard-library Python worker polls exact approved TurboEA IDs
through the versioned owner API. It needs only an ArchiMesh-scoped identity; it
does not hold TurboEA/DataHub application credentials or a second identity store.
The existing owner in ArchiMesh reads the original source and executes the target
write. Adapters remain in `shared/federation-http`; ArchiMesh does not import the
bridge scheduler. The existing dataset-worker configuration remains compatible.

One registry binds the exact TurboEA card reference and complete DataHub
Application URN to the same `enterprise.application` canonical identity. Existing
source bindings are reused, including bindings already used by model imports.
Unclassified legacy identities require the existing reviewed migration; names,
copied custom properties and `external_id` never establish equivalence.

New Application URNs are stable, opaque `urn:li:application:turboea-<sha256>` values
derived from registry owner, canonical identity and target logical instance.
Source renames and worker restarts reuse the binding. Distinct equally named
applications remain distinct. Reversion A → B → A creates a new intent chained
to the latest verified result, rather than reusing an old write.

Existing DataHub Applications require an explicit reviewed `adoptions` mapping
from source native ID to exact target URN. Before binding, the target must be
active and its managed fields must already match the source. Adoption performs
no HTTP mutation. Different preexisting canonical identities cause a conflict;
there is no automatic merge or name-based discovery. The same conceptual meaning
must be reviewed for the native DataHub Application representation.

## Owned fields and preservation

The application profile owns `applicationProperties.name` and `.description`.
Source mappings must select `/name` and `/description` from the TurboEA card;
both effective model mapping authorities must be `TURBO_EA_MASTER`. A local-only
or DataHub-owned source field cannot be published through this capability.

An optional `lifecycleField` selects another explicitly configured source field.
Its canonical JSON is stored as the string-valued integration-owned custom
property `federation.turboea.lifecycle`. DataHub's native `status.removed` is **not**
an application portfolio lifecycle field. It is initialized to false on creation
only; archived/missing/hidden sources and removed/missing targets block updates,
without cascading deletion, recreation or reactivation.

Before updating, the owner rereads the complete `applicationProperties` aspect,
checks managed-field baselines, and merges only owned values into that aspect.
Unrelated custom properties, `parentApplication`, external links and other members
are preserved and checked at readback. Server-added defaults are allowed. No
dataset/product aspect, ownership aspect, schema, lineage, product membership or
`applications` association is written by the application adapter. Application-only
credentials must also deny dataset/product mutations at the DataHub server.

Associations between applications and data retain their existing DataHub URNs and
semantics. Importing architectural PRODUCES/CONSUMES relationships or managing
these associations from TurboEA needs a separate approved relationship policy.
CMDB/delivery information is upstream input to TurboEA; this increment adds no
CMDB connector.

## Configure and run

Use the extended, disabled-by-default
[`server/examples/federation-sources.example.json`](../../../server/examples/federation-sources.example.json).
The original source scopes and four-/five-argument Java configuration constructors
remain compatible. New `datahubTargets` and `applicationProjections` default to
empty. A disabled application profile does not allocate a target writer or
require its token.

1. Configure a TurboEA source type with semantic kind `enterprise.application`
   and model type `ApplicationComponent`; map name/documentation as described
   above. Review corresponding TurboEA model mapping authorities, including
   documentation, rather than silently changing a local-only field.
2. Configure a DataHub target with a **separate** server-side
   `DATAHUB_APPLICATION_WRITER_TOKEN`. Its logical instance must identify the
   intended DataHub repository. Limit the principal to the approved Application
   audience, with application read and edit rights; do not use the catalog-reader
   token. The owner validates different credential environment names when a
   DataHub source and target share the repository.
3. Set exact source `nativeIds`, named bridge `principals`, optional reviewed
   `adoptions`, a versioned `approvalVersion`, and explicit `approved: true` and
   `acceptUnconditionalWriteRisk: true` only for the reviewed deployment scope.
   Verify that source visibility permits disclosure to the DataHub audience.
4. Enable normal integration/federation configuration and authentication. The
   requester needs `federation_bridge` plus profile membership; a model/global
   administrator alone cannot execute the bridge.
5. Configure the independent worker using
   [`application-bridge.example.json`](../application-bridge.example.json):

   ```bash
   python3 scripts/run_bridge.py --config application-bridge.json --once
   python3 scripts/run_bridge.py --config application-bridge.json
   ```

The worker's `applications` array names a profile and exact `nativeIds` and can
run alone or alongside the existing dataset configuration. An optional `catalogs`
array adds further dataset/product lanes with separate `datahub`, `profile`,
`nativePrefixes` and `externalType` values (`dataset` or `dataProduct`). Each
catalog uses bounded GraphQL pagination; no partial scan is published after a
pagination error. All authoritative reads, reservations and target writes remain
owner-controlled, regardless of worker input.

## Owner API v1 and recovery

All endpoints are authenticated POSTs under `/federation/v1`, return
`Cache-Control: no-store`, and accept no caller-supplied source values or bindings.

| Endpoint | Body | Result |
| --- | --- | --- |
| `application-projections/prepare` | `{profile,nativeId,correlation}`; retained UUID correlation | Source read, authority/scope checks, identity reservation and durable owned-field intent |
| `application-projections/execute` | `{source:{profile,nativeId,correlation},intentId,version}` | Recheck source, policy and target baseline; adopt or dispatch and independently verify |
| `application-projections/verify-outcome` | Same versioned execution shape | Explicit read-only verification of IN_FLIGHT/UNCERTAIN work; never resubmits or repairs an aspect |

Plans contain `intentId`, `version`, `state`, `canonicalId` and `targetId` (the
exact intended Application URN, also before creation). Repeated verified scans
are no-ops but still check the current source and mirror. Manual changes to a
managed target field cause `APPLICATION_TARGET_DRIFT_OR_REMOVED`, including when
the source itself has not changed. Unowned changes are preserved.

Intents reuse the existing target-mutation ledger, writer lease, global pause,
canonical lanes, compare-and-set versions and archive quarantine. The immutable
intent pins approved **owned** values; unrelated aspect members are freshly
merged immediately before dispatch. There is no new model commit engine or
database transaction spanning HTTP calls.

Submission uses synchronous OSS Rest.li metadata change proposals, followed by
independent GET readback. A successful HTTP acknowledgement alone does not mark
delivery verified. Creation writes `applicationProperties` and initial `status`
separately. Lost acknowledgements, partial multi-aspect writes, unavailable or
divergent readback remain uncertain and fence subsequent writes; neither worker
restart nor ordinary execute resubmits them. If both aspects actually committed,
an authorized, explicit `verify-outcome` can finalize the binding. If the source
or policy changed, the review is stale and remains blocked. Partial creation
requires operator investigation and reviewed repair; verification never fills a
missing status or resets an unknown result to “not applied”.

DataHub's verified API profile supplies no conditional aspect-update guarantee.
Fresh reads/baselines/readback detect drift and many races, but cannot prevent a
concurrent update between GET and UPSERT. That is why risk acceptance is explicit.
Changing the set of managed fields or semantic mapping on already bound assets
requires reviewed baseline migration rather than silent field adoption.

## Dataset and data-product lane

The existing projection capability now accepts `dataset` and `dataProduct` profiles
with matching exact URN namespaces and distinct `catalog.dataset` /
`catalog.data-product` identities. Both project name/description and preserve
DataHub authority through TurboEA catalog import/inbound refresh. Source-policy
resolution checks registry provenance and native entity type, even if a reviewed
deployment maps both kinds to the same TurboEA card type.

The demo uses separate `DataHubDataset` and `DataHubDataProduct` types. Both map
to supported ArchiMate `DataObject` occurrences in its deliberately limited pilot
mapping; their canonical kinds and native source identities remain separate.
Product membership remains in DataHub; this increment does not flatten assets
into one dataset or infer architectural relationships from membership/lineage.

## Verification

- `python3 -m unittest discover -s tests -v`: standalone bridge, exact application
  transport, stable correlations/restarts, uncertainty and typed product discovery.
- `mvn -q test` in `shared/federation-http`: narrow Application writes, exact URNs,
  preservation, redirects/denials and credential-safe errors; existing reader tests.
- `bash scripts/run-isolated-server-gate.sh`: real isolated Neo4j/Kafka with
  synthetic TurboEA/DataHub HTTP fixtures, including identity, drift, approval,
  partial/lost writes, read-only recovery, adoption conflicts and products.
- `./scripts/demo.sh test`: actual pinned DataHub OSS/TurboEA, application-only
  credentials, native adoption, source rename/reversion, drift, preserved data
  associations, products/datasets discovered after startup, worker restart and
  full-stack restart without reseeding. Only its own disposable volumes are removed.

Direct DataHub → ArchiMesh model import remains separate unfinished D2 scope.
Deployments still need their own configuration, permissions, mappings and approved
audience; passing synthetic tests does not enable an agency deployment.

### Verified 2026-11-16

| Check | Result |
| --- | --- |
| Standalone Python bridge suite | 23 passed |
| Independent federation HTTP adapter unit tests | 8 passed |
| Example/bootstrap/upgrade tests | 17 passed |
| Isolated full server gate | 363 discovered; 359 passed, zero failures/errors, four opt-in skips |
| Application projection cases in that gate | All nine passed |
| Dataset/product projection cases in that gate | All seven passed |
| Actual OSS DataHub v1.8.0 / TurboEA 2.203.2 demo | Passed run `d3bfde98538b`, including both source directions, worker restart, full-stack restart without reseeding, UI entry points and scoped cleanup |

Retained, ignored local evidence:

- `server/target/isolated-server-gate/05ba8342-a9d3-46d8-961d-3b50229f30a2/`
  contains copied test reports, infrastructure/cleanup logs and exit code zero.
- `.run/order-management/d3bfde98538b/artifacts/live-integration-probe.json`
  records the exact source/canonical/target identities and live assertions for
  dataset import/update, Application adoption/update/drift/permissions and new
  product discovery/update. `after-restart.json` records the retained base model
  and catalog readback. No live Archi desktop interaction was needed.
- The test's DataHub and demo projects were removed with their own volumes.
  No preexisting development deployment was seeded or modified.
