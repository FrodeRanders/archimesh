# D1 — shared contracts and canonical authority enforcement

Date: 2026-11-12. Status: first D1 increment implemented; D1 remains in progress.
The runtime DataHub adapter, neutral durable identity migration and independent
bridge execution are not enabled or implemented by this increment.

Update: the [second D1 registry/migration increment](d1-registry-migration.md) now
implements durable neutral bindings, reviewed legacy classification and v2 archives
with v1 recovery. The validation and remaining-work list below describe the first
increment; consult that update for current delivery boundaries.

Later increments add the [authenticated source/owner boundary](d1-owner-boundary.md)
and [durable target intents/shared fences](d1-target-intents.md). Governed bridge
execution and direct DataHub model use remain separate completion gates.

## Delivered

The [shared contract library](../../../shared/federation-core/README.md) is outside
the bridge subproject, with an independent Java 21 test/build and no application
or third-party dependencies. ArchiMesh compiles the same source root, while the
bridge's D0 tooling remains independently runnable without Java, Maven or ArchiMesh.
No bridge scheduler/bootstrap source is added to ArchiMesh's build.

The library separates canonical semantic kind, external entity type and exact
repository reference. Its read-only resolution port rejects unknown/conflicting
origin claims and distinguishes independent instances and object/relationship IDs.
It supplies the shared canonical/type field-rule precedence, source-qualified
authority and a conservative direct/mirror observation gate. Native IDs have an
explicit UTF-8 byte bound and are preserved verbatim.

The existing ArchiMesh authority foundation previously exposed canonical overrides
without enforcing them in its actual mapping consumers. That gap is addressed:

- Import previews use the registered canonical identity to select effective fields.
- Ordinary model acceptance protects the effective canonical-owned fields before
  LWW, regardless of claimed actor or high Lamport clock.
- Inbound field selection and proposal fan-out use the same effective rules.
- Snapshot publication selects only fields effectively owned by ArchiMesh.
- Central preview/proposal/payload/readback uses canonical scope; a newly shared
  field cannot inherit a type-level write-through permission.
- Configuration digests change when overrides change, invalidating earlier
  inbound/publication/central approvals rather than silently widening them.

## Optional legacy Turbo EA mapping configuration

Within the existing target's `mapping`, an optional object can override ownership
of already mapped **local** fields:

```json
{
  "canonicalAuthorities": {
    "urn:agency:asset:4711": {
      "name": "ARCHIMESH_MASTER",
      "documentation": "TURBO_EA_MASTER"
    }
  }
}
```

The rest of the mapping is unchanged: version, native type/field mapping and
catalog/inbound/publication/central scopes remain required. Overrides do not add
remote paths, grant visibility or grant a principal access. Unknown local fields
fail closed when the canonical mapping is resolved. Up to 256 canonical entries
and 32 fields per entry are accepted. Shared central fields still require a
configured proposal mode; unconditional-write/full-attribute-read acknowledgments
remain in force.

This configuration uses the existing Turbo EA authority modes. It does not claim
that Turbo EA is now allowed to master DataHub metadata. The neutral core records
an exact source owner; enabling that owner in production awaits the state/dispatch
work below.

Missing/empty overrides serialize identically to legacy configuration. Existing
configuration digests, canonical/model IDs and v1 archive shape are retained.
Nonempty overrides participate in all three approval digests. Metadata schema,
archive contents and target connector construction have not been migrated.

## Validation

The dependency-free executable scenarios cover direct and mirrored resolution,
dataset/product separation, instance/kind collisions, forged origin, exact long IDs,
malformed Unicode/byte limits, rule precedence/duplicates, owner/aspect mismatch,
stale baseline and mirrors requiring fresh source verification. They also run in
the server's JUnit gate.

Focused tests cover canonical publication selection, central mutation denial,
shared write-through denial and legacy/new approval digest compatibility. The
infrastructure scenario imports a preexisting canonical object, allows its
locally owned name, rejects a spoofed high-clock edit to centrally owned
documentation, applies an approved central documentation refresh and preserves
the local name and historical tag. Run the complete regression via:

```bash
./scripts/validate-local.sh server-isolated
```

Verified isolated run: `1f8c1114-1038-4ce1-b5df-dd7682221a9f`, 315 discovered,
311 passed, zero failures/errors, four skipped deployment-specific probes.
The canonical-authority infrastructure case passed. Archived reports and cleanup
evidence are under the root's ignored `server/target/isolated-server-gate/<run>/`.
The standalone core build/test passed; `jdeps -s` reported only `java.base`.
Root fast hardening/notation checks and all four existing Python tool tests passed.

## Remaining D1 work and runtime boundary

The current ArchiMesh runtime remains the sole owner of its existing integration
metadata and mutation lanes. This increment starts no second writer. A future
bridge worker must use that owner through a versioned, authenticated registry/job
boundary, or move the owner into a separately hosted shared component with an
explicit migration. It must not open an independent registry or write directly to
the same database with its own lane coordination. The exact transport and bridge
permissions are not implemented here.

Before DataHub runtime enablement, D1 must still provide:

1. Atomic model-independent source and target reservations, proof/alias lifecycle,
   bridge approval/job capabilities and durable shared mutation fencing.
2. Neutral stored semantic kind separated from external type, entity/aspect-aware
   resource storage, identifier/property limits and canonical model reuse through
   either external binding.
3. Reviewed paused migration and versioned archive support, retaining old IDs,
   provenance, uncertain outcomes and v1 backup readability.
4. Connector dispatch/configuration and runtime source-authority enforcement using
   those persisted bindings and proofs, including source/disclosure scope filtering.

These are explicit completion gates, not supplied by a set of value classes.
The D0 OSS search-disclosure finding and pre-publication target metamodel review
remain relevant. D2/D3 acceptance and actual DataHub–Turbo EA writes remain later.
