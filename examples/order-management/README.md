# Order-management demo

Northstar Trade is a fictional on-premises trading company. Its in-house order
management application coordinates a trade portal, packaged CRM, finance ERP,
warehouse application and analytics. This fixture supplies metadata to DataHub OSS,
Turbo EA and ArchiMesh, plus a native Archi model and small business-record CSVs.
The named COTS products are illustrative; the demo does not install or execute them.
There is no cloud deployment or dependency on agency data or credentials.

The shared-model starting point is the [ArchiMesh import package](models/archimesh-model.json).
The demo imports it on the server; Archi then opens that model from the server.
The included [viewless Archi file](models/order-management.archimate) and
[Archi file with a synthesized view](models/order-management-synthesized.archimate)
are optional offline examples. Opening either file alone does not connect it to
the server model. All three files come from the synthetic declarative fixture.

## Clean-checkout demo: one command

Install/start Docker with Compose, allocate about 16 GB to Docker, and install
Python 3. From a clean checkout of this repository:

```bash
./scripts/demo.sh up
```

This command downloads a SHA-256-verified Turbo EA source snapshot, builds its
backend/database and a compact web UI, builds ArchiMesh **and its admin UI inside
Docker**, starts DataHub OSS and dedicated model infrastructure, loads the initial
Northstar metadata/model, then verifies API readback and web entry pages. Host Java,
Maven, Node, a sibling Turbo EA checkout and prebuilt application images are not
required. First use needs internet access to public source/package/image registries;
all running services and data remain local. Subsequent builds reuse the local cache.

The command prints the three web URLs and the location of private access details.
It also prints the Archi connection URL, model ID, bootstrap identity and a launch
command that opens the prepared server model. Ports belong to each run and are
allocated dynamically; the default Archi dialog URL on port 8081 is usually a
different development server. Archi does not automatically discover demo servers.
It leaves the demo running. The Turbo EA UI includes its normal catalog/navigation;
its separate DrawIO distribution is omitted from this compact demo image. Archi
models and the synthesized view are supplied separately above. Archi desktop is an
optional client, not installed by the script.

## Open the shared model in Archi

`./scripts/demo.sh up` already imports `archimesh-model.json` through
`POST /admin/models/import?overwrite=false`. ArchiMesh restores the model history
and materialized graph in Neo4j. No client-side `.archimate` file is required.
The command prints the Archi WebSocket base URL, model ID and import-package path;
these are also recorded in the run's `access.json` under `archiConnection`.

With the Archimesh plugin installed:

1. Close open models, quit Archi and use the launch command printed by the script. It supplies the
   demo bootstrap identity and automatically opens the prepared server model.
   The current plugin is required; rebuild/install it after updating this checkout.
2. Alternatively, launch Archi with `ARCHIMESH_BOOTSTRAP_USER=northstar-demo-admin`
   and `ARCHIMESH_BOOTSTRAP_ROLES=admin` in its environment, then choose
   **Tools → Open Archimesh Model from Server…**.
3. Enter the printed **WebSocket Base URL** (`ws://127.0.0.1:<archimesh-port>`,
   without `/admin-ui/`), then click **Load** beside the model selector.
4. Select `northstar-order-management-demo-v1` and reference **HEAD**, then open it.

Leave the bearer token empty for this local bootstrap-mode demo. The plugin sends
the configured identity on catalog requests and the WebSocket handshake. These
explicit bootstrap settings apply to loopback servers only; bearer authentication
takes precedence when supplied. For another Archi executable location, set
`ARCHI_EXECUTABLE` when printing the launch command.

Redisplay the addresses and launch command at any time, even with Docker stopped:

```bash
./scripts/demo.sh connection
./scripts/demo.sh connection --run <run>
```

This reads recorded addresses; `./scripts/demo.sh status` also checks containers.
To stop the separate development stack and resume the existing prepared demo:

```bash
./scripts/dev-down.sh
./scripts/demo.sh start
```

Use `./scripts/demo.sh down` followed by `./scripts/demo.sh start` to stop/resume
the same demo while retaining its model, ports and credentials. `up` creates a
fresh run with new ports and credentials. `dev-up.sh` starts separate model storage
and does not load the demo model or configure a Turbo EA catalog.

The plugin fetches the central snapshot into a server-backed model and connects
for subsequent updates. HEAD is writable; a named tag opens a read-only snapshot.
The demo model has no diagrams: expand its Application, Relations and Technology
folders to inspect its semantic content. A local file with the same content is
not automatically associated with this server timeline.

For manual loading into a separate ArchiMesh environment, open the admin UI's
**Versions → Import / Export**, choose
[`models/archimesh-model.json`](models/archimesh-model.json), and click
**Import Package**. Then open the imported model from Archi as above, using that
environment's URL and authentication. The import package includes the complete
initial operation history, rather than only a graph snapshot. Use the server's
import API/UI so it can maintain revisions and model identity.

If the demo bootstrap has already loaded the model, skip manual import. Leave
overwrite disabled to preserve any subsequent edits. Model export in this same
admin panel produces a package of the current server model for backup/restore.

```bash
./scripts/demo.sh status
./scripts/demo.sh verify
./scripts/demo.sh restart   # verify persistence without reseeding
./scripts/demo.sh down      # retain volumes
./scripts/demo.sh start     # resume the same demo
./scripts/demo.sh down --volumes  # explicitly remove only this run's volumes
./scripts/demo.sh test      # fresh build/seed/restart/verification and scoped cleanup
```

Actions default to the last run created by this bootstrap; `--run <run>` selects
another recorded containerized run. Every `up` creates fresh storage and credentials
without deleting other runs. The previous host-JVM loader below remains available
for development. Use `scripts/demo.sh` to manage runs created by the clean bootstrap.
Build/cache files live under `.run/demo-build/`; runtime state, credentials pointers,
image inventory and verification reports live under `.run/order-management/<run>/`.
Neither directory is committed or mounted from an agency/home credential directory.

## Contents and ownership

| System | Demo content | Authority represented |
| --- | --- | --- |
| DataHub OSS v1.8.0 | 9 datasets with field schemas; 3 data products; Trade domain; 3 synthetic team principals; ownership; dataset lineage; 6 application references | Catalog metadata belongs to DataHub. Application names, descriptions and native source IDs are read back from Turbo EA before projecting them. |
| Turbo EA 2.194.0 | 6 applications (2 in-house, 4 COTS), 6 logical data objects, 6 infrastructure services; 15 CRUD and 12 infrastructure relations | Enterprise applications, architectural access and infrastructure dependencies belong to Turbo EA. |
| Archi/ArchiMesh | 26 elements, 49 relationships; 6 order-management functions, 2 application services; assignments, functional flows, access, realization, serving and deployment associations | Model occurrences and formal relationships belong to Archi/ArchiMesh. The source model contains **no views**. |

The datasets cover customer accounts, product prices, order headers and lines,
accepted-order events, inventory, shipments, invoices and daily booked sales.
Products are **Trade Order Book**, **Fulfilment Operations** and **Sales Performance**.
The sample journey is capture → price validation → stock reservation → fulfilment
→ invoice request, with a separate order-status service for the portal.

Business CSVs have synthetic account names, stable primary/foreign keys, SEK prices,
three orders, six lines and reconcilable invoice/sales totals. They are illustrative
records, **not loaded into the catalog's operational backing databases**. DataHub
stores their metadata, schema, ownership and lineage, rather than transaction rows.

## Generate files without running services

From the repository root:

```bash
python3 examples/order-management/generate.py
python3 -m unittest discover -s examples/order-management -v
```

Files appear in `examples/order-management/generated/` (ignored by Git):

- `metadata.json`: resolved fixture with complete DataHub URNs and canonical references.
- `order-management.archimate`: native Archi model, with no diagrams.
- `archimesh-model.json`: v1 import package with complete operation history.
- `semantic-graph.mmd`: Mermaid projection generated from the formal relationships.
- `data/*.csv`: coherent sample business records matching the catalog schemas.
- `sha256.json`: checksums of those deterministic artifacts.

The fixture source is [scenario.json](scenario.json). The generator uses only
Python's standard library. The example imports no ArchiMesh server code.

## Generate a view from the viewless model

With Java 21+ and an installed Archi 5.8 model SDK:

```bash
python3 examples/order-management/validate_archi.py \
  --plugins /Applications/Archi.app/Contents/Eclipse/plugins
```

Pass your installation's `Eclipse/plugins` directory on other platforms. The helper
checks every endpoint pair against Archi's own ArchiMate relationship matrix,
loads the XML with Archi's EMF resource loader, validates it, then creates
`generated/order-management-synthesized.archimate`. Open that file in Archi to see
the generated **Synthesized order processing (demo)** view: 16 objects and 30
connections derived from the existing semantic graph. Layout is a simple
deterministic arrangement, intended as a starting point for an architect.

The original model remains viewless. The generated view is a separate local Archi
artifact; the loader does not install it into ArchiMesh. To create a shared diagram,
open the server-backed model as above and create the view there. Opening the
synthesized offline file does not publish its diagram to the server.

Access direction is encoded natively in the Archi file. The current ArchiMesh
operation format stores the relationship name and a `demo.accessType` custom
property; this example does not add native access-mode synchronization to the
plugin. Other relationship types and endpoints are stored as formal model data.

## Populate a fresh local demo

Requirements: Python 3 on macOS/Linux, Java 21+, Maven, Docker Compose and roughly
16 GB of Docker memory. DataHub uses the bridge subproject's pinned OSS Compose
baseline. ArchiMesh runs as a local packaged JVM with dedicated Neo4j and Kafka
containers. Turbo EA runs its backend and PostgreSQL containers.

The baseline uses the same locally built Turbo EA images as the M0–M6 probes:

```bash
# Only needed if these two images are not already present.
# ../turbo-ea must be checked out at 6d9220e52d47b41f9d79351ec3233ded1f76fdc6.
docker build --target db -t ghcr.io/vincentmakes/turbo-ea/db:m1-local-6d9220e ../turbo-ea
docker build --target backend -t ghcr.io/vincentmakes/turbo-ea/backend:m1-local-6d9220e ../turbo-ea

python3 examples/order-management/local.py up
```

`up` builds the current server, allocates loopback ports, creates two uniquely
named Compose projects, generates local credentials, seeds the three targets and
verifies readback. It leaves the demo running. `--skip-build` uses an already-built
`server/target/quarkus-app/quarkus-run.jar`.

The printed 12-character run ID scopes every later action. Endpoints, private
credentials, loader journal, server PID/log and verification reports are under
`.run/order-management/<run>/`; DataHub credentials and infrastructure evidence are
under `integrations/datahub-turboea/.run/datahub/it-<run>/`.
Both directories are ignored. `state.json` holds the generated Turbo EA login;
DataHub's synthetic frontend login is `datahub` with the generated password in
`user.props`. Passwords and bearer tokens are not printed by the runner.

DataHub has its own frontend. ArchiMesh's admin UI is available if its assets have
been built as described in [admin-web](../../admin-web/README.md); API/model loading
does not require those assets. The minimal Turbo EA stack exposes the API only;
it does not include Turbo EA's frontend/nginx. This keeps it independent of the
sibling project's UI build. Do not describe this fixture as an end-to-end UI test.
The native DataHub Application aspect API is verified; Application navigation
may be unavailable in the OSS frontend's default feature configuration.

```bash
python3 examples/order-management/local.py verify --run <run>
python3 examples/order-management/local.py restart --run <run>
python3 examples/order-management/local.py down --run <run>
python3 examples/order-management/local.py start --run <run>

# Explicitly delete only this run's volumes.
python3 examples/order-management/local.py down --run <run> --volumes

# Fresh isolated seed/readback/restart-without-reseed check; always cleans its volumes.
python3 examples/order-management/local.py test
```

`restart` stops the JVM, restarts the model database, Turbo EA backend and DataHub
GMS/frontend, starts the JVM and verifies existing data **without reseeding**.
`start` resumes stopped containers with retained volumes. `verify` checks all demo
aspects, card types/names/IDs, relation endpoints/CRUD flags, model endpoints,
operation history, access annotations and graph/commit consistency. Reports include
`verification.json`, `after-restart.json` and `archimesh-verified-export.json`.

If `up` fails, its uniquely named environment is retained for diagnosis. After
fixing an ingest issue, `seed --run <run>` explicitly reseeds this run's DataHub
demo aspects, reuses journaled Turbo EA IDs and imports the model only when absent.
It then verifies the complete fixture; it never overwrites an existing model.
An edited or incompatible fixture is rejected by the Turbo EA journal digest.
A pending/uncertain Turbo EA create halts the loader and requires operator review;
do not delete its journal and retry against the same storage. `down --volumes`
followed by a fresh `up` is the simplest reset for synthetic environments.

## Identity and semantic boundaries

The demo's `urn:demo:northstar:...` references are **synthetic canonical IDs**;
DataHub retains complete native URNs, Turbo EA allocates its own UUIDs, and Archi
elements have stable model IDs. `crosswalk.json` records the observed application
IDs across all three systems and the datasets' **related** logical-data IDs.
The crosswalk is demo evidence, not the federation registry or a write grant.
Turbo EA `external_id` and DataHub custom properties are hints, never upsert keys.
No trusted registry bindings are installed by this loader.

In this demo, DataHub's native Application represents the business asset grouping
for one Turbo EA enterprise application. That is an explicit **demo-only** semantic
profile, not a universal software-service mapping. Logical “Sales order” in Turbo
EA/Archi is related to order tables/topics; those physical datasets have distinct
canonical identities. Data products remain DataHub-native: the loader does not
silently represent a data product as a Turbo EA DataObject.

Technical dataset lineage is specified only for the synthetic event publication
and analytical transformations. DataHub application membership does not imply
PRODUCES/CONSUMES or CRUD. Turbo EA CRUD and Archi Access are separately declared
architectural assertions. See the [authority directions](../../integrations/datahub-turboea/docs/authority-directions.md).

The default `./scripts/demo.sh up` now enables a polling dataset bridge and the
Turbo EA Enterprise Repository catalog. To enable it on an existing demo without
reseeding or replacing the model, run `./scripts/demo.sh enable-live`. If the
existing demo is stopped, first run `./scripts/demo.sh start`.

Create a dataset in this demo DataHub, then open **Tools → Enterprise Repository**
in Archi, connected as `northstar-demo-admin`. Choose target `turboea` and type
`DataHubDataset`, search and import. Discovery and linked-model refresh run every
five seconds; DataHub search indexing can add delay. Import is explicit. Later
source name/description changes reach the linked writable model through normal
accepted operations; historical tags remain unchanged.

The dedicated `DataHubDataset` metamodel type represents technical datasets and
maps to ArchiMate DataObject. It is separate from the seeded logical `DataObject`
assets. Its full DataHub URN and separate Turbo EA UUID bind to one canonical ID
in the shared registry. Curated DataHub description takes precedence over the
ingested description. DataHub owns these fields even when accessed via Turbo EA.
Target drift, source removal or ambiguous writes block further action and require
review; the bridge does not hard-delete assets or infer equivalence by name.

`./scripts/demo.sh live-test` creates an additional probe dataset and model and
checks post-start discovery, explicit import, update/reversion, repeated import
and worker restart. Evidence is saved in `artifacts/live-integration-probe.json`.
`./scripts/demo.sh test` runs this in disposable storage and removes its own stack.

Approval covers all datasets in this dedicated synthetic DataHub instance. Service
sessions are generated into the mode-600 `integration-secrets.env` inside the run
directory. They last one day; `./scripts/demo.sh start` renews them. These demo
accounts have broad permissions and do not establish production disclosure or
least-privilege controls. Use `./scripts/demo.sh connection` for current addresses
and credential file locations; ports vary by run.

Continuous data-product publication and the reverse Turbo EA → DataHub application
lane remain outstanding. The seeded applications/products are still fixture data.
Ordinary model publication is separately disabled in this demo; when configured,
it requires an explicitly approved immutable tag. Central field proposals retain
their separate approval flow.

## Module boundary

The domain fixture, Archi/ArchiMesh artifacts and optional cross-system demo
orchestrator live here. DataHub/Turbo EA-specific seeding and verification live in
[`integrations/datahub-turboea/tools/datahub_local/order_demo.py`](../../integrations/datahub-turboea/tools/datahub_local/order_demo.py),
which imports no ArchiMesh implementation. Ordinary product builds neither start
this demo nor depend on it. Bridge adapter tests still run independently:

```bash
cd integrations/datahub-turboea
python3 -m unittest discover -s tests -v
```
