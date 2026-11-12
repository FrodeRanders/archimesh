# Federation core — contract library 0.1.0

Dependency-free Java 21 value contracts and pure decisions shared by ArchiMesh and
the independent DataHub–Turbo EA bridge. This directory contains no connector,
credentials, HTTP client, scheduler, database driver, Quarkus, EMF or model engine.

```bash
python3 test.py       # executable scenarios; no Maven or running repositories
python3 build.py      # target/federation-core-0.1.0.jar
```

Both commands work from this directory after extraction from the parent project.
The resulting JAR depends only on Java's base module. ArchiMesh currently compiles
this same source root and runs its scenarios in its server gate; it does not compile
or depend on the bridge subproject. A separately packaged consumer can use the JAR.

## Contracts

| Type | Contract |
| --- | --- |
| `Identity.Repository` / `Resource` | Exact system + logical instance + object/relationship kind + native ID; a hostname is not an identity |
| `Identity.Canonical` | Stable canonical ID and semantic kind; separate from external entity types and ArchiMate types |
| `Identity.Representation` | Native resource and repository entity type, such as DataHub `dataset` versus Turbo EA `DataObject` |
| `Identity.SourceField` | Exact authoritative resource, aspect and field path, independently of transport |
| `IdentityResolver.Registry` | Read-only trusted lookup port; unknown, forged and conflicting origin claims fail closed |
| `FieldRules<T>` | Canonical override before type default; duplicate rules rejected; missing rule remains absent/unmapped |
| `SourceAuthority` | Source/instance/aspect/path ownership, version and mode; repository/shared modes require an owner |
| `ObservationPolicy` | Direct/mirrored observation admission with exact identity, source, proof and baseline checks |

The resource ID limit is 8192 UTF-8 bytes. Identifiers retain exact case and spelling;
they are never truncated, normalized, reconstructed or matched by name. Malformed
Unicode, controls and leading/trailing whitespace are rejected. This bound does
**not** change ArchiMesh's legacy 512-character persisted-ID contract. Storage and
archive migration must be reviewed before longer references can enter that runtime.

The registry port cannot create, merge, alias or reassign identities. Its production
implementation must read the one authoritative registry, including instance and
resource-kind uniqueness. The scenario registry is an immutable test fixture, not
a new production or in-memory identity store. Dataset/product/member relationships
never establish equivalence by themselves.

## Observations and trust

The host authenticates repository reads, checks disclosure/write scopes and supplies
source evidence. A value digest is a lowercase SHA-256 of the host's documented
canonical value encoding; this library does not serialize aspect payloads. Source
revisions are opaque and never sorted across repositories.

The host captures the expected baseline version before reading and uses a durable
compare-and-set when applying an accepted decision. `STALE_BASELINE` stops a read
started against an older baseline. The host must recheck this inside its serialized
acceptance/transaction; a pure admission decision is not a transaction or capability.

Direct source observations may advance the baseline through that acceptance path.
A mirrored observation requires an existing same-canonical binding and a trusted
receipt verifier backed by publication/adoption evidence. An editable remote field
cannot implement that verifier. A mirror can confirm only the exact existing
source revision and digest. Different/unverified evidence requires a fresh direct
source read; it cannot advance a source baseline or establish revision ordering.
The library grants no automatic shared-proposal write permission.

## Integration status

ArchiMesh uses `FieldRules` for policy precedence, and canonical authority overrides
now affect import, local field protection, inbound projection, publication and
central-edit helpers. Source-aware identity/observation contracts are tested here,
but are not yet wired to a DataHub runtime or persisted neutral registry.

See the [D1 implementation status](../../integrations/datahub-turboea/docs/d1-contracts.md)
and [shared ADR](../../docs/adr/0002-three-repository-federation.md). The next step is
the reviewed durable registry/schema/archive transition, followed by connector
dispatch. No existing IDs or archive format are rewritten by this library.
