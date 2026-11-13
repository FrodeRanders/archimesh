# ArchiMesh consumer boundary for DataHub federation

ArchiMesh integrates with DataHub OSS and Turbo EA through shared canonical
identity, authority and repository contracts. The standalone DataHub–Turbo EA
bridge is developed in [`integrations/datahub-turboea/`](../integrations/datahub-turboea/README.md).
Its infrastructure, scheduler, discovery notes and tests belong to that subproject.
Ordinary ArchiMesh builds and gates do not run or build the bridge.

Keep the following responsibilities in the ArchiMesh integration:

- Resolve exact repository references through the shared identity registry before
  creating or reusing a model occurrence.
- Preserve source authority/provenance through direct DataHub import and Turbo EA
  representations. Do not trust editable provenance claims or match by name.
- Apply approved inbound fields through normal durable model acceptance, retaining
  model ACLs, ownership guards, incarnation fencing and immutable historical tags.
- Keep Archi plugin/browser credentials server-side and normal model commits
  independent of repository availability.
- Consume reusable, versioned adapter/contracts only; do not depend on bridge jobs,
  bootstrap infrastructure or its internal operational state.

[ADR 0002](adr/0002-three-repository-federation.md) owns the shared identity and
authority decision. The bridge subproject owns its
[delivery plan](../integrations/datahub-turboea/docs/implementation.md).
The optional [source reader/owner boundary](../integrations/datahub-turboea/docs/d1-owner-boundary.md)
is wired into the runtime; direct DataHub catalog/model import and refresh remain
disabled and unimplemented. The
[shared core](../shared/federation-core/README.md) and canonical authority enforcement
are implemented as the [first D1 increment](../integrations/datahub-turboea/docs/d1-contracts.md).
D0 tooling remains independent. The [durable registry and reviewed semantic
classification](../integrations/datahub-turboea/docs/d1-registry-migration.md) are
also implemented, preserving existing IDs and v1 recovery. Source-read dispatch,
authenticated registration and configured local field protection are now available.
The [target-intent ledger](../integrations/datahub-turboea/docs/d1-target-intents.md)
now supplies durable shared mutation lanes and verified completion. Inbound
baseline policy and governed bridge execution are implemented for the
[dataset projection slice](../integrations/datahub-turboea/docs/dataset-bridge.md).
The default local demo enables that slice; broader production rollout and the
remaining lanes require their own approvals. Application references in DataHub will use Turbo EA as
their source; datasets/products represented in Turbo EA retain DataHub authority.
