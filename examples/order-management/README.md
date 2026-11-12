# Order-management demo

Northstar Trade is a fictional on-premises trading company. Its in-house order
management application coordinates a trade portal, packaged CRM, finance ERP,
warehouse application and analytics. This fixture supplies metadata to DataHub OSS,
Turbo EA and ArchiMesh, plus a native Archi model and small business-record CSVs.
The named COTS products are illustrative; the demo does not install or execute them.
There is no cloud deployment or dependency on agency data or credentials.

Ready-to-open Archi files are included: [viewless source model](models/order-management.archimate)
and [model with a synthesized view](models/order-management-synthesized.archimate).
These are snapshots of the declarative fixture, not exports from an agency repository.

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
artifact; the loader does not install it into ArchiMesh. Publishing diagram edits
can subsequently use the ordinary ArchiMesh plugin workflow.

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

This is a seed-and-verify fixture. It does not implement or prove continuous
DataHub ↔ Turbo EA synchronization, registry convergence, least-privilege connector
permissions, fault recovery across repositories or production disclosure controls.
ArchiMesh integration workers stay disabled in this disposable stack.

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
