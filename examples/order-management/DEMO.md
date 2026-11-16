# A 15–20 minute Northstar demo

Start with the information's owner, make one visible change, then follow the same
identity through the other systems. The demo is local and synthetic; no production
setup or agency credentials are needed.

## Prepare once

Prerequisites: Python 3 and Docker with Compose, running with about 16 GB available.
No host Java, Maven, Node or Turbo EA checkout is required.

```bash
./scripts/demo.sh up --open       # build, start, seed, configure, link and publish
```

The script opens DataHub, Turbo EA and the ArchiMesh administration UI and prints
URLs and demo logins. First use downloads pinned dependencies/images; later builds
reuse caches. The new run stays running. To resume it tomorrow:

```bash
./scripts/demo.sh start --open    # same volumes, ports and identities; renew sessions
./scripts/demo.sh connection     # redisplay URLs and logins, no restart
./scripts/demo.sh down           # stop, retaining everything
```

`up` creates a **new** run; `start` resumes the last one. `--run <id>` selects a
particular run. Existing older demos opt in with `./scripts/demo.sh enable-publication`
while running; that rebuilds the current server and prepares this workshop.

In ArchiMesh's bootstrap login/settings, use `northstar-demo-admin` and role
`admin`, as printed. The Publication page uses model
`northstar-order-management-demo-v1`, target `turboea`.

Archi desktop is optional. With the current ArchiMesh plugin installed, use the
launch command printed by `connection` to open the shared model. Leave the bearer
token empty for this local bootstrap setup. Do not open an offline `.archimate`
file and expect it to synchronize automatically. With no desktop client, the
admin UI still shows model snapshots, tags, inbound status and publication evidence.

## Before presenting

```bash
./scripts/demo.sh verify
./scripts/demo.sh publication-status
```

Preparation has already:

- Seeded applications, logical information, functions, infrastructure, datasets and
  products in the appropriate systems.
- Enabled the dataset/product bridge and Turbo EA → DataHub Application lane.
- Linked existing Northstar application/logical-data occurrences through normal
  preview/import/link APIs, reusing observed IDs rather than matching names.
- Imported **Sales order headers** and **Trade Order Book** as separate, already
  linked catalog occurrences. They are distinct from logical **Sales order**.
- Installed the demo-only `NorthstarLogicalAccess` relation type with READ/WRITE
  options and a pinned publication profile. It uses the existing
  `demo.accessType` relationship property.
- Approved an immutable tag and published six derived logical access assertions.
  Application/data cards are references. Existing CRUD relationships stay separate.

Keep all three browser tabs visible. Expand the semantic elements/relationships in
Archi if using it; the initial shared model is viewless. Creating a view is optional.

## The presentation

| Time | Action in its master | What the audience should observe |
| --- | --- | --- |
| 0–2 min | Show the [system diagram](../../docs/ecosystem-dataflows.md) | DataHub owns datasets/products; Turbo EA owns enterprise application fields; ArchiMesh owns formal models/history and architecture contributions. |
| 2–6 min | In **DataHub**, find **Sales order headers**, edit its curated description and save a recognizable sentence | Within polling/indexing delay, the `DataHubDataset` card changes in Turbo EA. The linked catalog DataObject in ArchiMesh/Archi updates through accepted operations, with the same IDs. Its related logical Sales order remains a separate object. |
| 6–8 min | In **DataHub**, optionally edit **Trade Order Book**'s description | Its `DataHubDataProduct` representation and linked model occurrence update, without turning the logical data object into a product. Owners, tags and schemas remain catalog metadata; only configured name/description fields are projected. |
| 8–10 min | In **Turbo EA**, rename **Northstar Order Management** | Its native Application in DataHub follows. Dataset–Application associations remain. The linked application in the shared HEAD model also follows. Revert the name for a repeatable presentation. |
| 10–14 min | In **Archi**, find Access from **Capture sales order** to **Customer account**. Change custom property `demo.accessType` from `Read` to `ReadWrite` | ArchiMesh HEAD changes. Turbo EA's approved logical relation still says READ until a new tag is approved. This path is chosen because it has one function's evidence; changing another path might leave an already-unioned READ/WRITE result unchanged. |
| 14–17 min | Run `./scripts/demo.sh publish`, or preview/tag/approve/run manually in **Publication** | A new immutable tag and pinned profile produce READ+WRITE on the **same** `NorthstarLogicalAccess` relation. Expand evidence to see the function, Assignment and Access IDs. No extra application/data card, CRUD CREATE/DELETE or physical lineage edge appears. |
| 17–20 min | Run `publish` again, inspect an older tag, optionally restart | The unchanged projection retains the same relation IDs. The older tag keeps its historical content. Stop/start retains the whole demo. |

If the DataHub OSS frontend does not expose native Application navigation, show
the Application API observations in `artifacts/live-integration-probe.json` for
that optional step. The dataset/product edits are the primary browser walkthrough.

For the Archi step, change the **custom property** named `demo.accessType`; the
current wire snapshot does not synchronize the native Access dropdown. You can
adjust the native Access display too for visual consistency. Source values are
`Read`, `Write`, `ReadWrite` (or READ, WRITE, READ_WRITE). Set it back to `Read`
and publish another tag to restore the original architectural access.

A manual Publication review follows the same route as the script: create a tag
in Versions, choose the Northstar profile and that tag, select the Order Management
application and six logical DataObjects, preview the paths, approve, then queue.
No direct source relationships or card stewards need selecting for this reference-only
profile. `publish` performs these steps explicitly **for this synthetic demo**;
it does not publish HEAD continuously or change the product's disabled defaults.

## A repeatable automated walkthrough

```bash
./scripts/demo.sh walkthrough
```

This changes the already-linked dataset's curated description in DataHub, waits
for the real bridge/inbound workers, checks the historical tag is unchanged, then
restores the text. It also exercises post-start dataset discovery/import/update,
Application rename/reversion and association preservation, product discovery/update,
worker restart, and repeated profile publication with stable IDs. Source changes
travel through the real APIs/workers; the script does not patch Turbo EA's dataset
mirror or inject model metadata to simulate propagation.

The probe creates additional synthetic datasets/products and a probe model in the
same demo. It restores edited names/descriptions and retains probe artifacts for
inspection. It does not delete or overwrite the shared model. Repeated runs can
therefore leave additional clearly named probe examples.

```bash
./scripts/demo.sh restart         # verify persistence without reseeding
./scripts/demo.sh test            # independent fresh stack, walkthrough, restart, cleanup
```

The dedicated `test` command removes only its own disposable volumes; it does not
replace the current presentation run. For a new presentation baseline use `up`.
Use `down --volumes --run <id>` only when deliberately discarding that run.

## What to point at, rather than just asserting “sync works”

- Compare the DataHub URN, Turbo EA UUID and model element ID. They are different
  identifiers connected by one registry-backed canonical identity.
- Show both logical **Sales order** and technical **Sales order headers**. A
  relation between concepts is not identity equivalence.
- Show two facts separately: the seeded enterprise CRUD relation and the new
  evidenced logical READ/WRITE relation. The projection never infers CREATE/DELETE.
- Show an old tag while HEAD receives updates. Collaboration and historical
  publication are different timelines.
- Show the output's source evidence and the unchanged remote relation ID after a
  second publication, rather than counting matching names.

Machine-readable observations live under `.run/order-management/<run>/artifacts/`:
`master-edit-demo.json`, `live-integration-probe.json`, `publication-preview.json`,
`publication-verification.json`, `publication-probe.json` and restart verification.
`publication-demo.json` records the linked occurrences, source URNs and approved tag.
`access.json` records URLs and credential **locations**, not duplicated passwords.

CMDB/real ERP/CRM applications are not started. They are synthetic application and
infrastructure metadata in this demo; the diagram labels the CMDB connector as
an intended connection. Archi desktop is an installed client, not a container
provisioned by the launcher.
