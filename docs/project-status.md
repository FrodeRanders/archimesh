# Project handoff - 2026-10-28

Archimesh is at a tested stopping point for the current checkpoint, delivery, recovery, and shared-topic work. This is a development handoff, not a claim of production certification or exhaustive fault coverage.

## Code and branch state

The delivery/recovery work was developed on `feature/delivery-isolation`, based on `main` at `bb5b0f7`, and was fast-forwarded into `main` for this handoff:

- `3964b65` isolates durable delivery by model with fair, bounded workers.
- The subsequent recovery commit contains the process-crash drill, fresh-infrastructure server gate, repository-test isolation, runbook, and updated manual, including this handoff.

Both completed feature branches were merged, pushed, and removed. The shared-topic follow-up was developed directly on `main`. Resume from `main`; check `git status --short --branch` and `git log -5 --oneline` for pending changes, the final commit, and remote synchronization state. Generated test evidence and build outputs are ignored; source, fixtures, build scripts, diagram sources, rendered figures, and the manual PDF are versioned.

## Verified behavior

- Shared-topic server gate: 193 passed, zero failures/errors; the separate process drill was skipped. The 160-model broker regression published 640 events across three fixed topics/nine partitions, preserving each model's operation order and routing every event to its keyed model.
- The development Kafka and Neo4j volumes were deliberately reset with user authorization. Schema was reapplied, Neo4j had zero data nodes, and the three shared application topics were provisioned with one partition each.
- Previous delivery baseline: 188 server tests and 52 client tests passed, including infrastructure and WebSocket coverage. The shared-topic change modifies only the server; its current validation is the 193-test isolated gate above plus packaging and the process drill.
- Shared-topic process drill: accepted-but-unpublished work survived a forced JVM stop and was delivered after Kafka returned. Checkpoint-covered pruning and a later accepted tail survived another forced stop.
- An importer blocked inside a real Neo4j restore transaction was force-stopped. The destination retained no partial model. A subsequent import into that fresh database preserved snapshots, ACLs, tags, checkpoint hash, and pruned-batch deduplication.
- Stale field, delete, property-set, and diagram-membership operations met their restored merge barriers. Editing and rebuild continued successfully from baseline revision 16, through restored revision 18, to revision 23.
- The process drill and fresh server gate cleaned up their own Docker resources. The LaTeX manual builds without unresolved references or overflowing content, and updated pages were visually checked.

Machine-local evidence from the completed runs is under:

```text
server/target/recovery-drill/bdad8b2f-8f3b-44ef-9efd-20660b30c23d/
server/target/isolated-server-gate/8b457b8b-c20e-4c49-9fc8-47e8364e9b31/
```

`mvn clean` can remove those directories. Regenerate evidence using the commands below; the runbook describes reports and cleanup.

## Resume and validate

From the repository root:

```bash
git status --short --branch
./scripts/validate-local.sh fast
./scripts/validate-local.sh gate             # client + server unit gate
./scripts/validate-local.sh server-isolated  # all server tests on fresh Neo4j/Kafka
./scripts/validate-local.sh recovery         # packaged JVM crash / fresh restore drill
bash docs/manual/build.sh
```

Run Maven gates sequentially because they share `server/target/`. The isolated server gate requires Python 3, Java, Maven, Docker, and Compose. Client builds use their pinned Maven wrapper and Archi target-platform bundles. See [recovery drills](recovery-drills.md), [checkpoint maintenance](checkpoint-pruning.md), and [manual build instructions](manual/README.md).

## Known operational limits

The previous per-model layout exhausted the development broker's 128-partition memory budget. The new layout shares three topics keyed by model ID, so adding models no longer consumes partitions. Redpanda and its memory settings are unchanged. The old development Kafka/Neo4j volumes were intentionally discarded and recreated; no compatibility reader remains. Prefer disposable `server-isolated` and `recovery` gates to keep test fixtures out of the fresh development stack. See [Kafka setup](../kafka/kafka.md) for the layout and reset procedure.

Supported deployment remains one authoritative server per Neo4j repository. Every Kafka event uses the model ID as its key; partition counts must remain stable during publishing. Models share partition throughput and topic-wide retention policies. Scheduled checkpoint/pruning maintenance is disabled by default. Large checkpoint capture/restore can pause editing and uses memory proportional to the model. Acceptance receipts and CRDT safety metadata remain retained; checkpoint retention is configurable when scheduled maintenance is enabled.

## Optional next work

The remaining improvements are suitable for a later work session:

1. Measure large-model checkpoint/rebuild memory, latency, and editing pauses.
2. Expose delivery backlog/age, maintenance results/durations, and writer-lease status as operational metrics.
3. Add CI workflows around the now-repeatable client gate, isolated server gate, recovery drill, and manual build.
4. Design CRDT metadata garbage collection only with an explicit protocol for long-offline clients.

There is no requirement to implement those extensions before pausing work on the tested functionality above.
