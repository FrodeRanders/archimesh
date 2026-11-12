# Federation HTTP / source ports 0.1.0

Reusable Java 21 source observation contracts, exact logical-repository dispatch
and a read-only DataHub OSS Rest.li aspect adapter. No Quarkus, Neo4j, ArchiMesh
model engine, bridge scheduler, search API or repository writes.

```bash
cd shared/federation-http
mvn -q test
mvn -q -DskipTests package
```

The independently built SDK includes the shared core sources and depends on
Jackson 2.21.0. ArchiMesh compiles these same source roots; it does not compile the
standalone bridge. Keep this module and `federation-core` together when extracting
them, or replace the source-root reference with a versioned core artifact.

`SourceReader` exposes selected JSON-pointer fields, explicit present/null state,
lifecycle and observation digests. `ReaderDispatch` selects by exact system and
logical instance and rejects a reader returning another identity or field set.
The host remains responsible for principal authorization, disclosure scope,
semantic mapping, registry trust, baseline CAS and model acceptance.

`DataHubReader` checks a native entity's primary properties aspect before reading
selected optional aspects and status. A missing optional aspect remains absent;
explicit null is different. Missing primary properties, denied access, authentication
failure and source removal are distinct outcomes. Unknown lifecycle stays unknown.
The reader never reconstructs URNs or searches unrestricted indexes. It preserves
all native bytes, accepts only bounded responses, rejects duplicate JSON keys and
redirects, and returns sanitized failures without credentials or remote bodies.
Non-loopback HTTP requires an explicit transport approval; HTTPS validates normally.

Digests use `federation-observation-json-v1`: recursively sorted object keys and
retained array order, including presence markers, exact representation and lifecycle.
The returned `content-sha256:` revision is a **content fingerprint**, not a DataHub
sequence number or a clock. Multi-aspect reads are not an atomic source snapshot.
Fresh reads and host-side CAS remain mandatory; a mirror cannot advance a source
baseline merely by claiming a later timestamp.

An optional read-only deployment test can be selected explicitly with
`-Dtest=DataHubReaderLocalIT`, `RUN_FEDERATION_DATAHUB_IT=true`,
`FEDERATION_DATAHUB_URL` and a server-side `FEDERATION_DATAHUB_TOKEN`. It expects
the isolated Northstar demo fixture. Never substitute agency credentials merely
to run this synthetic deployment check.
