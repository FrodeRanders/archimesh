# Project handoff - 2026-10-28

Archimesh is at a tested stopping point for the current checkpoint, delivery, and recovery work. This is a development handoff, not a claim of production certification or exhaustive fault coverage.

## Code and branch state

The delivery/recovery work was developed on `feature/delivery-isolation`, based on `main` at `bb5b0f7`, and was fast-forwarded into `main` for this handoff:

- `3964b65` isolates durable delivery by model with fair, bounded workers.
- The subsequent recovery commit contains the process-crash drill, fresh-infrastructure server gate, repository-test isolation, runbook, and updated manual, including this handoff.

Resume from `main` after this handoff. Check `git status --short --branch` and `git log -5 --oneline` for the final commit and remote synchronization state. The merged feature branch can be removed after verifying the push. Generated test evidence and build outputs are ignored; source, fixtures, build scripts, diagram sources, rendered figures, and the manual PDF are versioned.

## Verified behavior

- Full delivery-change gate: 188 server tests and 52 client tests passed, with infrastructure and WebSocket coverage; server packaging passed.
- Fresh-infrastructure server gate: 188 passed, zero failures/errors; the separate process drill was correctly skipped by the ordinary gate.
- Process drill: accepted-but-unpublished work survived a forced JVM stop and was delivered after Kafka returned. Checkpoint-covered pruning and a later accepted tail survived another forced stop.
- An importer blocked inside a real Neo4j restore transaction was force-stopped. The destination retained no partial model. A subsequent import into that fresh database preserved snapshots, ACLs, tags, checkpoint hash, and pruned-batch deduplication.
- Stale field, delete, property-set, and diagram-membership operations met their restored merge barriers. Editing and rebuild continued successfully from baseline revision 16, through restored revision 18, to revision 23.
- The process drill and fresh server gate cleaned up their own Docker resources. The LaTeX manual builds without unresolved references or overflowing content, and updated pages were visually checked.

Machine-local evidence from the completed runs is under:

```text
server/target/recovery-drill/65d04c8f-12e6-4441-b3ca-c807d48365f0/
server/target/isolated-server-gate/aded865e-8b95-4d8e-b100-9cd36931b214/
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

The shared local development broker reached its 128-partition memory limit after repeated tests created model topics. It refuses new topics at that limit. Its topics and storage were preserved; no development data was deleted or broker configuration changed. Prefer the disposable `server-isolated` and `recovery` gates for fresh validation. Before using that shared broker for new models, plan a deliberate test-topic cleanup or capacity adjustment.

Supported deployment remains one authoritative server per Neo4j repository and one Kafka partition per model's ops topic. Scheduled checkpoint/pruning maintenance is disabled by default. Large checkpoint capture/restore can pause editing and uses memory proportional to the model. Acceptance receipts and CRDT safety metadata remain retained; checkpoint retention is configurable when scheduled maintenance is enabled.

## Optional next work

The remaining improvements are suitable for a later work session:

1. Measure large-model checkpoint/rebuild memory, latency, and editing pauses.
2. Expose delivery backlog/age, maintenance results/durations, and writer-lease status as operational metrics.
3. Add CI workflows around the now-repeatable client gate, isolated server gate, recovery drill, and manual build.
4. Design CRDT metadata garbage collection only with an explicit protocol for long-offline clients.

There is no requirement to implement those extensions before pausing work on the tested functionality above.
