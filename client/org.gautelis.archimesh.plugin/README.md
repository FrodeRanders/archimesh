# Archimesh plugin for Archi

The plugin connects an Archi model to a server-backed HEAD or read-only tag, captures semantic, folder and diagram changes through EMF, and reconciles accepted operations under a remote-apply guard.

Implemented behavior includes:

- model/tag discovery through the read-authorized catalog endpoints;
- bearer-token authentication for catalog requests and websocket upgrades;
- snapshot and delta checkout, with property values and CRDT merge metadata restored;
- local merge-clock registration and reconciliation of the client's own accepted broadcasts;
- a persistent FIFO outbox for online and offline submissions, removed on matching acceptance;
- correlated conflict handling, capped retry backoff, and preservation of queued work when caches are invalidated;
- short acquire/submit/release leases for notation changes;
- server-backed model lifecycle, local caching, and explicit resynchronization.

See [the client workspace README](../README.md), [architecture](../../architecture.md), and [CRDT specification](../../crdt-spec.md).

Build with `scripts/build-client-standalone.sh` from the repository root. Client builds use the Maven 3.9.16 wrapper in `client/`; the locally installed Maven 3.10 version does not resolve this Tycho build correctly.

The release gate is `scripts/validate-local.sh ws`. It includes all client tests and the local Neo4j/Kafka and websocket integration coverage. A manual two-Archi editing session remains useful for UI and interaction validation.
