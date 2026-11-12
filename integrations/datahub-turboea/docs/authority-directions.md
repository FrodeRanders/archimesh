# Two source-authoritative projection directions

Date: 2026-11-12. Decision: the required bridge flows in both directions. The
enterprise applications referred to in DataHub derive from Turbo EA. Selected
datasets/data products represented in Turbo EA derive from DataHub. Each field
and relationship keeps its own source authority through either transport path.

| Information | Source authority | Governed downstream use |
| --- | --- | --- |
| Enterprise application identity and selected name, description and lifecycle | Turbo EA instance/card | DataHub application references and approved mirrored fields; ArchiMesh application occurrences |
| Dataset/product identity and selected catalog metadata | DataHub instance/exact URN | Turbo EA representations and approved ArchiMesh concepts |
| Dataset schema and technical lineage | DataHub | Selected links/projections with explicit semantics; no automatic architectural-relation conversion |
| Application-to-data architectural relationships | Explicit relationship policy, often the approved Turbo EA architecture | DataHub reference/association only when its meaning matches |
| Dataset-specific documentation/technical ownership | DataHub field/aspect rule | Preserve independently of application business-owner metadata |
| Model occurrences, views, coordinates and collaboration history | ArchiMesh / Archi | Remain model-owned |

This is part of the required D3 delivery. Turbo EA → DataHub application-reference
writes are separate from optional edits back to source-owned dataset metadata or
Turbo EA application fields. Those source edits still require explicit proposals
or write-through policy; a mirrored value never becomes a new source authority.

## Identity and associations

An enterprise application's canonical identity binds its native Turbo EA reference.
If a DataHub object is explicitly reviewed as an equivalent representation, bind
its separate native URN to that canonical identity. Preserve all native IDs. A
dataset referencing the application retains its own dataset canonical identity;
the association does not make the two objects equivalent.

For example, a DataHub dataset may refer to application canonical `A`, whose source
is `(turboea, agency-ea, CARD, application-card-id)`. Refreshing `A`'s name in DataHub
reads Turbo EA, rather than turning the DataHub label into the master. Importing
that application into ArchiMesh resolves `A` again. Names and editable provenance
properties cannot establish equivalence; registry evidence is required.

If DataHub stores only an external application link/reference inside a dataset or
product aspect, record that reference as an association to `A`. Do not bind the
dataset/product's native resource to `A`, and do not invent a DataHub application
URN for an entity that does not exist.

## Choosing the DataHub representation

DataHub's documented [Application concept](https://docs.datahub.com/docs/features/feature-guides/applications)
groups assets around a business purpose and differs from a software service.
Therefore a Turbo EA enterprise application is not automatically semantically
equivalent to a native DataHub Application. Use a reviewed agency profile. Where
the concepts differ, retain separate identities and an explicit reference between
them. External links/source references are a valid first representation.

The pinned OSS `v1.8.0` exposes `applicationProperties` and an `applications` aspect
with native application URN references. The local probe exercises storage/readback,
scoped writes and restart, using a **simulated** Turbo EA source snapshot. This
establishes OSS API feasibility, not a selected semantic mapping, native UI feature
availability, agency-version compatibility or a live Turbo EA connector.

The probe's `bridge.source.*` custom properties preserve source native/canonical
references as hints. They do not create a trusted registry binding. No Cloud API,
Cloud deployment, paid feature enablement or automatic metamodel change is required
by the probe. Recheck the actual agency OSS release before enabling its profile.

## Permissions and field preservation

Use separate lanes and credentials:

- DataHub catalog reader → approved Turbo EA dataset/product projection writer.
- Turbo EA application reader → DataHub application-reference writer restricted to
  selected target objects/aspects/fields.
- Association updates receive their own relationship scope and approval; application
  label projection does not grant general dataset write access.

The synthetic application writer is denied dataset mutations, and the dataset
writer is denied application mutations. Upstream entity permission alone is not a
field-ownership rule. The connector still needs an explicit field/member allowlist,
fresh reads and verified readback, preserving unrelated aspect members and owners.
Conditional-write safety is not inferred from successful UPSERT probes.

Application business owners require a verified principal mapping before assignment
to DataHub ownership URNs. They do not replace dataset technical owners. Lifecycle
and status/removal also require separate rules; missing or hidden applications must
not trigger automatic recreation or cascade dataset deletion.

Check target audience before publishing either direction, including IDs, source
links and counts. Source service-account visibility does not authorize disclosure
to all DataHub/Turbo EA users. Poll both original sources; notifications accelerate
reconciliation but do not establish source ordering or transfer authority.
