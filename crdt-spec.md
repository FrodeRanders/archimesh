# CRDT Specification (Archimesh)

## Status
- **Current system**: centralized op-log + server-assigned revision order.
- **CRDT progress**: advanced partial. Core semantic entities and key notation/style fields now use LWW+tombstones by `(lamport, clientId)`.

## Objectives
1. Deterministic convergence for concurrent edits.
2. Idempotent replay across reconnect/offline scenarios.
3. No resurrection from stale updates after deletes.

## Causal Identity
Each op carries:
- `causal.lamport` (monotonic per client)
- `causal.clientId` (stable per client/session)
- `causal.opId` (idempotency identity)

Comparison rule (LWW tuple):
- `(lamportA, clientIdA)` wins over `(lamportB, clientIdB)` iff:
  - `lamportA > lamportB`, or
  - `lamportA == lamportB` and `clientIdA >= clientIdB` (lexicographic)

## CRDT Types by Domain

### 1. Semantic element fields (`name`, `documentation`)
- Type: **LWW Register per field per element**.
- Metadata: field clock map `elementId -> { field -> (lamport, clientId) }`.
- Merge:
  - apply incoming field if incoming tuple wins field clock;
  - otherwise ignore incoming field.

### 2. Diagram notation/style fields
- Type: **LWW Register per field per view object/connection**.
- Implemented for:
  - View objects: geometry and major style/label fields (`x`,`y`,`width`,`height`,`type`,`alpha`,`lineAlpha`,`lineWidth`,`lineStyle`,`textAlignment`,`textPosition`,`gradient`,`iconVisibleState`,`deriveElementLineColor`,`fillColor`,`lineColor`,`font`,`fontColor`,`iconColor`,`imagePath`,`imagePosition`,`name`,`documentation`)
  - Connections: core notation fields (`type`,`nameVisible`,`textAlignment`,`textPosition`,`lineWidth`,`name`,`lineColor`,`font`,`fontColor`,`documentation`,`bendpoints`)

### 3. Delete semantics
- Type: **Observed tombstone with causal clock**.
- Metadata: `elementTombstones[elementId] = (lamport, clientId)`.
- Rules:
  - `DeleteElement`: records/updates tombstone.
  - stale `UpdateElement`/`CreateElement` (older than tombstone) is ignored.
  - newer `CreateElement` may clear tombstone and recreate (explicitly causal-newer).

## Implemented so far

### Client `RemoteOpApplier`
- Added **element field clocks** for semantic LWW on:
  - `UpdateElement.patch.name`
  - `UpdateElement.patch.documentation`
- Added **element tombstones**:
  - `DeleteElement` always records tombstone (even if element already missing).
  - stale `UpdateElement` and stale `CreateElement` are blocked by tombstone.
  - accepted `CreateElement` clears tombstone and seeds field clocks.
- Added property-level LWW+tombstones for `SetProperty`/`UnsetProperty` (per target+key).

### Server `Neo4jRepositoryImpl`
- Added semantic LWW + tombstone handling for:
  - `Element` fields (`name`, `documentation`) with causal clocks.
  - `Relationship` fields (`name`, `documentation`, `sourceId`, `targetId`) with causal clocks.
- Added `View` semantic LWW for:
  - `name`, `documentation`, `notationJson`.
- Added `Connection` notation LWW for fields:
  - `type`, `nameVisible`, `textAlignment`, `textPosition`, `lineWidth`,
  - `name`, `lineColor`, `font`, `fontColor`, `documentation`, `bendpoints`.
- Extended `ViewObject` notation LWW beyond geometry to style/label fields listed above.
- Added tombstone-based stale recreate protection for:
  - `Element`, `Relationship`, `View`, `ViewObject`, `Connection`.
- Extended model cleanup paths to remove all tombstone node families.
- Added property clock metadata (`PropertyClock`) and LWW/tombstone merge for property operations.

### Server `ArchimeshService` + schema contract
- Added strict notation key whitelist validation for:
  - `CreateViewObject`, `UpdateViewObjectOpaque`
  - `CreateConnection`, `UpdateConnectionOpaque`
- `notationJson` with unknown keys is now rejected as `PRECONDITION_FAILED`.
- Updated `schemas/ops.json` with explicit `ViewObjectNotationJson` and `ConnectionNotationJson`
  definitions (`additionalProperties: false`) to match runtime validation.
- Aligned client op emission (`OpMapper`) to include `viewId` on:
  - `UpdateViewObjectOpaque`, `DeleteViewObject`
  - `UpdateConnectionOpaque`, `DeleteConnection`
  so emitted payloads match schema requirements consistently.

## Remaining Work to Reach Full CRDT
### Priority order
P0 means required to claim CRDT correctness for currently-supported ops; P1 means hardening/scalability; P2 means model expressiveness extensions.

1. **P0: Notation-field parity and unknown-key policy**
- Goal: deterministic convergence for all fields emitted by client notation serializers.
- Progress implemented:
  - server runtime validation rejects unknown notation keys for:
    - `CreateViewObject`, `UpdateViewObjectOpaque`,
    - `CreateConnection`, `UpdateConnectionOpaque`.
  - schema contract enforces strict notation objects (`additionalProperties: false`) for:
    - `ViewObjectNotationJson`,
    - `ConnectionNotationJson`.
  - client notation inventory test verifies `OpMapper` emits only whitelisted keys:
    - `client/client-tests/src/test/java/org/gautelis/archimesh/client/OpMapperNotationInventoryTest.java`
  - client notation parity updates consolidated in this repository:
  - canonical notation serializer in `client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/notation/NotationSerializer.java`,
  - `viewId` emission in `client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/emf/OpMapper.java`.
  - added independent parity preflight script:
    - `scripts/check-notation-parity.sh` compares notation key inventory across:
      - schema (`schemas/ops.json`),
      - server runtime whitelist constants (`ArchimeshService`),
      - client inventory test expectations (`OpMapperNotationInventoryTest`).
- Work:
  - Done: canonical field inventory is now aligned across serializer/deserializer, server whitelist, and schema for currently-supported notation fields.
  - Done: each currently-supported `UpdateViewObjectOpaque` and `UpdateConnectionOpaque` notation field has explicit merge behavior (per-field LWW register).
  - Done: unknown-key behavior is explicitly enforced as reject-on-validate (non-whitelisted keys return `PRECONDITION_FAILED`).
  - Done: schema/server/client notation key parity is now checked by a fast standalone script, wired into hardening checks before expensive test runs.
  - Remaining: keep inventory parity checks green as notation fields evolve (treat drift as release-blocking).
  - Remaining: if any future notation field is intentionally non-CRDT, document it explicitly as unsupported rather than implicit.
- Primary files:
  - `client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/notation/NotationSerializer.java`
  - `client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/notation/NotationDeserializer.java`
  - `client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/emf/RemoteOpApplier.java`
  - `client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/emf/OpMapper.java`
  - `server/src/main/java/org/gautelis/archimesh/service/impl/Neo4jRepositoryImpl.java`
  - `server/src/main/java/org/gautelis/archimesh/service/ArchimeshService.java`
  - `schemas/ops.json`
  - `scripts/check-notation-parity.sh`
- Acceptance:
  - no serializer-emitted notation key is “merge-undefined”;
  - concurrent conflicting updates to every supported field converge identically on server snapshot and client apply.

2. **P0: Deterministic multi-client simulation test suite**
- Goal: prove replay/idempotency/order invariants under adversarial delivery.
- Progress implemented:
  - client op emission hardening for relation creation from views:
    - `CreateRelationship` + `CreateConnection` are now emitted in a single `SubmitOps` batch for connection-create flows,
      preventing server precondition races where `CreateConnection.representsId` could be validated before the new relationship is visible.
  - local infra integration now includes an end-to-end convergence test that applies the same logical op-set in:
    - in-order delivery, and
    - out-of-order delivery with duplicate replay,
    then compares canonicalized snapshots for equality.
  - added explicit two-logical-client replay simulation that:
    - replays client A + client B op-sets under in-order vs adversarial (out-of-order + duplicate) delivery,
    - asserts byte-equivalent canonical snapshot output,
    - asserts duplicate replay after convergence is a no-op on materialized canonical state.
  - server join/reconnect unit coverage now asserts deterministic checkout behavior for:
    - `lastSeen < head` with available delta (`CheckoutDelta`),
    - `lastSeen < head` with missing delta (snapshot fallback),
    - `lastSeen == head` (empty delta window).
  - websocket end-to-end reconnect coverage now asserts stale `lastSeenRevision` receives `CheckoutDelta` with expected revision window when op-log history is available.
  - added a single repository CRDT gate entrypoint:
    - `scripts/crdt-gate.sh` runs client+server CRDT-focused suites in one deterministic command.
  - added local gate automation for convergence drift prevention:
    - PR/manual workflow runs `./scripts/crdt-gate.sh`,
    - nightly/manual workflow runs `RUN_LOCAL_INFRA_IT=true ./scripts/crdt-gate.sh` with Docker Kafka/Neo4j.
  - added lightweight preflight hardening contract checks:
    - `scripts/check-crdt-hardening.sh` verifies presence/wiring of core CRDT tests, gate scripts, workflow/job names, and spec references before expensive test execution.
   - live relation-creation hardening complete:
     - `EmfChangeCapture` now gates connection-create submission on ID readiness (`trySendConnectionCreate`) and triggers connection-create retries on endpoint changes.
     - `OpMapper.toCreateConnectionWithRelationshipSubmitOps` now returns `null` unless all involved identifiers are present (connection/view/source/target/relationship and relationship endpoints), preventing malformed placeholder IDs from being emitted.
     - `mapConnectionCreateSubmitOps` now always emits the paired (CreateRelationship + CreateConnection) batch, never standalone. The server treats duplicate CreateRelationship as idempotent (MERGE in Cypher, no existence-based precondition rejection), so the paired batch safely converges even when the relationship was already submitted independently.
     - `pendingConnectionRelationshipIds` suppresses standalone CreateRelationship submission while a connection-create retry is pending, preventing the timing race where a standalone CreateConnection races its own relationship's persistence.
     - sender updates are applied directly in `client/org.gautelis.archimesh.plugin`; no parallel client tree sync is required before rebuild/reinstall.
- Work:
  - Done: simulation coverage now includes at least two logical clients generating the same op-set and replaying in:
    - in-order,
    - out-of-order,
    - duplicated batches,
    - mixed reconnect (snapshot + delta + local outbox replay).
  - Done: assertions now verify canonical snapshot equality, including byte-equivalent canonical JSON for the two-client adversarial replay scenario.
  - Done: CRDT suite is now wired as explicit local gate commands with both fast and local-infra coverage paths.
  - Done: branch-protection guidance now documents required PR gate check (`CRDT Gate / crdt-gate`) and optional nightly signal check in `README.md`.
  - Done: preflight hardening contract check is wired into `scripts/crdt-gate.sh` to fail fast on gate/test/workflow drift.
  - Remaining: keep convergence scenarios in sync as new op types are introduced (especially collection-aware/P2 operations).
- Primary files:
  - `server/src/test/java/org/gautelis/archimesh/service/ArchimeshServiceTest.java`
  - `server/src/test/java/org/gautelis/archimesh/service/impl/LocalInfraIntegrationTest.java`
  - `client/client-tests/src/test/java/org/gautelis/archimesh/client/CrdtEntityMergeTest.java`
  - `client/client-tests/src/test/java/org/gautelis/archimesh/client/CrdtPropertyMergeTest.java`
  - `client/client-tests/src/test/java/org/gautelis/archimesh/client/OpMapperConnectionBatchTest.java`
  - `scripts/crdt-gate.sh`
  - `scripts/check-crdt-hardening.sh`
 - Acceptance:
  - same logical op history always yields byte-equivalent normalized snapshot;
  - duplicate op-batch submission does not mutate final state beyond first accepted application.

3. **P0: Offline outbox/rebase contract formalization**
- Goal: make offline behavior deterministic, bounded, and observable.
- Progress implemented:
  - replay now drains queued ops in strict FIFO order per model with single in-flight send;
  - replayed entries are removed only after matching server `OpsAccepted`;
  - replay send failures retain the failed head entry in place (no tail re-enqueue), preventing reorder on flapping links;
  - while replay is active or queue is non-empty, new submits are queued to preserve ordering;
  - deterministic retry/backoff added (exponential, capped) plus poison-entry drop after max replay attempts;
  - model-switch contract made explicit: replay is model-scoped and cross-model submits are queued until the matching model session is active;
  - replay dequeue now requires server `OpsAccepted` (not just websocket send completion), with ack-timeout retry;
  - `PRECONDITION_FAILED`/`LOCK_CONFLICT` on queued replay now deterministically drop stale/conflicting head intent and surface conflict callback (wired to status-line warning in UI).
  - added reconnect replay test coverage for base-revision rebasing across head advancement (snapshot head then later delta head).
- Work:
  - Done: queue semantics are now specified and implemented for:
    - ordering guarantees (FIFO per model),
    - max queue behavior (bounded queue, drop-oldest),
    - model-switch behavior for queued ops (model-scoped drain),
    - replay retry/backoff without discarding uncommitted work on transport failures.
  - Done: reconciliation invariants for `baseRevision` rebasing are implemented:
    - stale/conflicting queued intent on `PRECONDITION_FAILED`/`LOCK_CONFLICT` is deterministically dropped,
    - conflict is surfaced via explicit callback/UI warning (not silent).
  - Done: invariants are encoded as tests, including deterministic replay for identical head + identical queued ops.
  - Remaining: expand replay/rebase tests when new op domains are introduced (especially non-LWW collection semantics).
- Primary files:
  - `client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/ws/ArchimeshSessionManager.java`
  - `client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/ws/InboundMessageDispatcher.java`
  - `client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/emf/EmfChangeCapture.java`
  - `architecture.md`
- Acceptance:
  - offline/online flapping cannot reorder operations within a model outbox;
  - replay outcome is deterministic for identical server head + identical queued ops.

4. **P1: Clock/tombstone compaction policy**
- Goal: prevent unbounded metadata growth without reintroducing stale resurrection.
- Progress implemented:
  - added admin compaction endpoint (`POST /admin/models/{modelId}/compact`) with run reporting;
  - compaction watermark now derived from committed revision horizon (`latestCommitRevision - retainRevisions`), with head + horizon reported in admin status;
  - first safe compaction pass implemented:
    - stores immutable checksummed raw-graph recovery checkpoints,
    - prunes whole published payload batches covered by a checkpoint and retention policy, in bounded runs,
    - retains acceptance receipts and all `PropertyClock` entries, including orphan safety metadata,
    - retains tombstones intentionally for delete safety.
  - tombstones now persist `updatedRevision` metadata (when assigned revision range is available during apply), and compaction reports tombstones eligible under watermark while still retaining them by policy.
  - compaction now also reports eligible field-clock metadata entries (`*_lamport` on materialized nodes) under watermark while retaining those field clocks for conservative safety.
  - local infra tests now cover compaction delete-safety and head-drift scenarios (watermark follows committed horizon even when head is ahead).
- Work:
  - Done: compaction watermark policy is tied to committed revision horizon (`latestCommitRevision - retainRevisions`).
  - Done: retention policy is defined and implemented for:
    - tombstones (`*Tombstone` nodes) retained for delete safety (eligibility reported),
    - field clocks (`*_lamport`, `*_clientId`) retained conservatively (eligibility reported),
    - property clocks (`PropertyClock`) retained, including orphan clocks.
  - Done: preview/apply controls, checkpoint-plus-tail rebuild, v2 checkpoint export/import, and snapshot fallback after pruning.
  - Done: admin endpoint/reporting exists for compaction runs and reclaimed metadata.
  - Remaining: define a safe metadata garbage-collection protocol, automatic checkpoint retention, and gather long-running workload evidence. Checkpoints and acceptance receipts currently accumulate; bounded payload deletion does not bound total metadata cardinality.
- Primary files:
  - `server/src/main/java/org/gautelis/archimesh/service/impl/Neo4jRepositoryImpl.java`
  - `server/src/main/java/org/gautelis/archimesh/endpoint/AdminEndpoint.java`
  - `server/src/main/java/org/gautelis/archimesh/model/AdminCompactionStatus.java`
  - `neo4j/schema.cypher`
- Acceptance:
  - compaction never allows stale `Create*`/`Update*` to beat a logically newer delete;
  - metadata cardinality stays bounded under long-running edit workloads.

5. **P2: Collection/set semantics beyond LWW scalars**
- Goal: support concurrent edits on unordered collections/advanced metadata safely.
- Progress implemented:
  - explicit inventory pass completed for currently-modeled collection-like domains:
    - supported: connection `bendpoints` as single-field LWW value;
    - explicitly unsupported (not emitted/accepted as CRDT collections): grouped children membership/order, arbitrary notation `props`/`extras`, and generic nested add/remove collections.
  - `notation_mapping.md` now marks these unsupported domains explicitly to avoid implicit CRDT gaps.
  - added reusable OR-Set merge primitives + unit tests in both codepaths:
    - client: `CrdtOrSet` + `CrdtOrSetTest`,
    - server: `CrdtOrSet` + `CrdtOrSetTest`.
  - first collection-aware op slice implemented:
    - schema ops: `AddPropertySetMember`, `RemovePropertySetMember`,
    - server apply: OR-Set-style member clocks (stored via `PropertyClock` namespace) with deterministic materialization to canonical JSON string arrays,
    - client apply: matching OR-Set member merge/materialization logic in `RemoteOpApplier`.
  - second collection-aware op slice implemented:
    - schema ops: `AddViewObjectChildMember`, `RemoveViewObjectChildMember`,
    - server apply: OR-Set-style member clocks (stored via `PropertyClock` namespace) with materialized `(:ViewObject)-[:CHILD_MEMBER]->(:ViewObject)` edges + snapshot export (`viewObjectChildMembers`),
    - client apply: matching OR-Set member merge with deterministic parent rematerialization for child containment.
  - local infra integration coverage added for out-of-order + duplicate replay convergence of property-set member operations.
  - local infra integration coverage added for out-of-order + duplicate replay convergence of view-object child-member operations.
- Work:
  - Done: identify collection domains that cannot be safely modeled as single LWW blobs.
  - Done: OR-Set merge primitives are implemented and wired to property-set membership operations.
  - Done: grouped view-object membership is implemented with dedicated add/remove collection ops.
  - Remaining: extend collection-aware ops to additional collection domains when needed.
- Primary files:
  - `schemas/ops.json`
  - `client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/emf/OpMapper.java`
  - `client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/emf/RemoteOpApplier.java`
  - `client/org.gautelis.archimesh.plugin/src/org/gautelis/archimesh/plugin/emf/CrdtOrSet.java`
  - `server/src/main/java/org/gautelis/archimesh/service/impl/Neo4jRepositoryImpl.java`
  - `server/src/main/java/org/gautelis/archimesh/service/impl/CrdtOrSet.java`
  - `server/src/test/java/org/gautelis/archimesh/service/impl/LocalInfraIntegrationTest.java`
  - `notation_mapping.md`
- Acceptance:
  - concurrent add/remove on target collections converges without lost updates;
  - replay + duplication remains idempotent.

### P2 next concrete slice (new candidate after grouped membership)
- Candidate domain: additional structured notation collections beyond current schema (for example `props`/`extras`) with explicit merge contracts.
- Why next:
  - grouped membership is now covered via dedicated collection ops,
  - remaining unsupported collection-like areas are explicit and bounded.

### Exit criteria for “Full CRDT” milestone
- All supported op fields have explicit merge semantics and tests.
- Deterministic convergence suite passes for reorder/replay/duplication/reconnect cases.
- Offline outbox contract is documented and test-verified.
- Compaction can run in production mode without violating delete safety.
- Any remaining non-CRDT domains are explicitly marked as unsupported (not implicit gaps).

## Compatibility Notes
- This phase is backward-compatible with existing op format.
- Missing `causal` defaults to `(0, "")`, giving deterministic but conservative ordering.

## Commit and replay hardening (2026-10)

- A batch's op-log, projection, revision head and durable publication intent commit atomically.
- Idempotent retries consult persisted commits before state-dependent validation; successful acceptance is private to the submitting session.
- Errors include `opBatchId`; uncorrelated or unrelated errors cannot remove outbox entries.
- Client local writes seed their merge clocks. Own accepted echoes reconcile normally, and observing remote causal metadata advances the outgoing Lamport clock.
- Snapshots include property state, field/member clocks and tombstones; applied revision advances after application, with gaps requesting catch-up.
- Compaction preserves checkpoint-plus-tail recovery and acceptance receipts. Rebuild and v2 import require a valid checkpoint with complete contiguous tail (or full history for v1); projection replacement is transactional. See [checkpoint workflow](docs/checkpoint-pruning.md).
- The supported deployment has one authoritative server, enforced with a renewable database writer lease.
- `scripts/validate-local.sh ws` builds the plugin with its pinned Maven wrapper and executes all client tests, server unit/infrastructure tests, and websocket/authorization integration tests.
