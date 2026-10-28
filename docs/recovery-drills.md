# Process crash and restore drill

The opt-in drill launches the packaged Quarkus application as child JVMs and uses a dedicated Docker Compose project with disposable Neo4j databases and Kafka. It tests recovery through the normal authenticated REST and WebSocket APIs and checks durable state directly in the drill databases. It does not use production failpoints or the development stack's containers, ports, or volumes.

## Run

Use Java 21+, Maven, Docker with Compose, and enough Docker memory for the database, broker, and a server JVM. The images match the local development stack: `neo4j:5.26.0-community` and `redpandadata/redpanda:v24.2.8`. Docker may need to fetch them if they are not cached.

From the repository root:

```bash
./scripts/validate-local.sh recovery

# Equivalent direct command:
bash scripts/run-recovery-drill.sh

# Reuse an already-built server/target/quarkus-app package:
bash scripts/run-recovery-drill.sh --skip-build
```

The wrapper packages the server and runs only `ProcessRecoveryIT` with `RUN_RECOVERY_DRILL=true`. Ordinary test gates skip that test. The drill needs a few minutes: three forced process stops deliberately wait for the real 30-second writer lease to expire, with no lease reset or shortened production lease. The JUnit test has an eight-minute timeout.

The harness chooses unused loopback ports and generates a Docker project name. Child server processes receive explicit drill database/broker addresses and bootstrap admin identity settings. Inherited deployment environment settings are removed from those child JVMs. Scheduled maintenance is disabled so each checkpoint and pruning boundary is explicit.

## Assertions

1. **Accept with Kafka offline.** Submit a model containing semantic entities, properties, membership clocks, folders, diagram objects, a connection, and a delete tombstone. Require `OpsAccepted`, a durable snapshot, and an unpublished commit.
2. **Kill before publication.** Force-stop that server JVM, wait for the database lease to expire, and restart the package against the same database. Require the same snapshot, then start Kafka and require the original pending batch to reach the reconnected client through `OpsBroadcast`. Retrying the original batch must return its original acceptance without changing state.
3. **Kill after pruning.** Tag and checkpoint the model, prune its published prefix, and accept a later tail. Force-stop and restart again. Require identical state, snapshot fallback for a stale reconnect, deduplication of the pruned batch, and a successful checkpoint-plus-tail rebuild.
4. **Interrupt a restore transaction.** Export v2 and start an empty destination Neo4j instance. Hold an uncommitted uniqueness conflict on a checkpoint element so the importer blocks inside raw projection restore, after beginning its model transaction. Observe the blocked transaction and force-stop the importing server. Roll back the blocker and require zero model-scoped nodes, demonstrating that partial restore work did not commit.
5. **Restore and continue.** After the destination lease expires, restart, import the same package, and compare its complete client-facing snapshot and checkpoint hash with the source. Verify the historical tag and old acceptance receipt, submit stale updates/recreates to check clock and tombstone barriers, continue editing, and rebuild again.

The in-flight restore stop is synchronized by a real database lock and observed transaction state. It does not depend on guessing an execution delay. Other stops are made at proven durable boundaries; the drill does not claim to kill at every possible instruction or database failure point.

## Evidence and cleanup

For the ordinary server gate on fresh disposable infrastructure, use
`./scripts/validate-local.sh server-isolated` (or `bash scripts/run-isolated-server-gate.sh`).
It requires Python 3 in addition to the prerequisites above, creates a separate
Neo4j/Kafka project, applies the schema, runs all server unit/infrastructure/WebSocket
tests with an ephemeral HTTP test port, and removes that project's containers and
volumes on exit. It explicitly disables the process drill. Logs and copied Surefire
reports are kept under `server/target/isolated-server-gate/<run-uuid>/`.
This keeps test messages and model fixtures out of the development stack.
Application traffic uses three shared topics, so adding test models no longer adds
partitions. The shared-topic integration test additionally exercises 160 model keys
on three three-partition topics and removes only its own temporary topics on exit.
Client tests remain in the client/full gates.

Repository fixture tests use a test-only Quarkus profile with background delivery
disabled, so the dispatcher cannot consume unpublished rows while those tests inspect
them. API/WebSocket tests and the packaged-process drill use the real dispatcher.

Each run creates an ignored directory:

```text
server/target/recovery-drill/<run-uuid>/
  report.json
  export-v2.json
  expected-snapshot.json
  server-*.log
  docker-*.log
```

The report records milestones, timestamps, image/project context, JVM/application fingerprints, tested revisions, the interrupted restore element, and pass/failure status. Exports and snapshots contain generated fixture data. Logs are retained on failure for inspection. Surefire also records the test result under `server/target/surefire-reports/`.

By default, cleanup force-stops any remaining owned child JVMs and removes only the run's Compose containers and volumes. Existing development services remain available. For diagnosis, set `RECOVERY_DRILL_KEEP_INFRA=true` before running to retain the isolated Docker resources; child JVMs are still stopped. The report gives the project name. To remove retained infrastructure later, use that exact name:

```bash
DRILL_DB_PASSWORD=unused DRILL_KAFKA_PORT=19092 \
  docker compose --project-name <reported-project> \
  --file tests/recovery/compose.yml down --volumes --remove-orphans
```

The placeholder variables satisfy Compose configuration parsing during cleanup; no database login is needed. If the harness itself is killed abruptly, use the reported project name to clean up its resources.

## Scope

This complements the client/server gate and storage fault tests. It covers process termination, broker outage/recovery, pruned history, atomic import rollback, and fresh-database transfer. It does not replace database-native backup validation, disk-full/network-partition testing, long-running load tests, or manual testing in two real Archi workbenches. Performance measurements and operational metrics remain separate work.
