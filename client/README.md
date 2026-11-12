# Archimesh Client Workspace

For a connected writable HEAD model, **Tools → Enterprise Repository…** opens the
[M2 approved Turbo EA catalog](../docs/turboea/m2-import.md). Search and preview an
object, then create/reuse a semantic element or link a compatible existing one.
The server must explicitly approve the catalog for the current principal. The
plugin calls only ArchiMesh and receives accepted changes through normal model
synchronization. Use the resulting model-tree element in any ArchiMate view.

This directory now supports a standalone Tycho build for the Archimesh plugin.

## What this build uses

- `client/org.gautelis.archimesh.plugin` as the plugin source.
- Eclipse 2024-06 p2 repository for Eclipse/EMF bundles.
- A local bundle pool in `client/target-platform/plugins` for Archi bundles required by the plugin:
  - `com.archimatetool.model`
  - `com.archimatetool.editor`

The local bundle pool is prepared by copying the required Archi bundles into `client/target-platform/plugins`.
The local bundle pool can be prepared from either:
- an Archi source checkout (`ARCHI_DIR`, default `../archi`), or
- an installed Archi app plugins directory (`ARCHI_APP_PATH` / `ARCHI_APP_PLUGINS_DIR`).

## Commands

- Prepare target bundles:
  - `./scripts/prepare-client-target-platform.sh`
- Build standalone plugin:
  - `./scripts/build-client-standalone.sh`

Run these commands from the repository root. The build prints the full path to
the installable JAR, currently:

```text
client/org.gautelis.archimesh.plugin/target/org.gautelis.archimesh.plugin-5.8.0-SNAPSHOT.jar
```

To install that build into Archi, close Archi and run:

```bash
./scripts/install-archimesh-plugin.sh --no-build
```

Omitting `--no-build` builds the plugin first using the same pinned Maven wrapper.
On macOS, the default destination is
`~/Library/Application Support/Archi/dropins/eclipse/plugins/`; use `--dropins`
to override the dropins root. Restart Archi with `-clean` after installation.
The JAR is a Maven build output in `target/`, which is ignored by Git.

## Notes

- Builds use `client/mvnw`, pinned to Maven 3.9.16 for Tycho compatibility.
- This lets client work live with server code in one repo.
- It does not require syncing plugin sources to another repo.
- If required bundles change in `MANIFEST.MF`, update `scripts/prepare-client-target-platform.sh`.
- Building/running Tycho client builds may require escalated permissions in this environment due to `~/.m2` lock behavior.
- Text fields use JSON escaping for all control characters, including multiline documentation.
  On reconnect, legacy queued payloads with raw control characters are repaired after
  saving an adjacent `.before-json-escaping.bak` backup. Text, operation IDs and queue order are preserved.

## Startup Pull From Server (Optional)

You can opt in to opening an Archimesh model from the central server when Archi starts, without first opening a local `.archimate` file.

You can also open directly from server at runtime using:
- `Tools -> Open Archimesh Model from Server...`

For the order-management demo, this is the normal starting point: the bootstrap
already imports its JSON package on the server. Follow the
[server-first demo workflow](../examples/order-management/README.md#open-the-shared-model-in-archi).
Opening one of the demo `.archimate` files alone creates an offline model and does
not associate it with the server timeline.

For a local bootstrap-mode server that requires authorization, supply both
`ARCHIMESH_BOOTSTRAP_USER` and `ARCHIMESH_BOOTSTRAP_ROLES` when launching Archi
(or Java properties `archimesh.bootstrap.user` and `archimesh.bootstrap.roles`).
The plugin uses these for REST discovery/API calls and WebSocket connections to
loopback hosts. Bearer-token sessions take precedence. The demo prints a complete
launch command with its allocated port: `./scripts/demo.sh connection`.

Or convert the currently active local model to Archimesh-backed collaboration:
- `Tools -> Switch Active Model to Server-Backed...`

The older generic connect command still exists internally, but the Tools menu now exposes the two clearer entry points above:
- open a server-backed model in Archi, or
- switch the current local model over to a server-backed session.

Both runtime dialogs load readable model options from the filtered catalog (`/models`) and only allow selecting an existing model.
Create/rename model IDs in server admin UI first (`/admin`).

Configuration can be provided as Java system properties or environment variables:

- Enable:
  - `archimesh.startup.pull.enabled=true`
  - `ARCHIMESH_STARTUP_PULL_ENABLED=true`
- Required model id:
  - `archimesh.startup.pull.modelId=<model-id>`
  - `ARCHIMESH_STARTUP_PULL_MODEL_ID=<model-id>`
- Optional websocket base URL (default `ws://localhost:8081`):
  - `archimesh.startup.pull.wsBaseUrl=<ws-base-url>`
  - `ARCHIMESH_STARTUP_PULL_WS_BASE_URL=<ws-base-url>`
- Optional actor:
  - `archimesh.startup.pull.userId=<user-id>`
  - `ARCHIMESH_STARTUP_PULL_USER_ID=<user-id>`
  - `archimesh.startup.pull.sessionId=<session-id>`
  - `ARCHIMESH_STARTUP_PULL_SESSION_ID=<session-id>`
- Optional local display name for the opened model:
  - `archimesh.startup.pull.modelName=<name>`
  - `ARCHIMESH_STARTUP_PULL_MODEL_NAME=<name>`

Behavior:
- Runs only when enabled.
- Skips startup pull if one or more models are already open.
- Opens an in-memory model, connects to the Archimesh server, and forces a cold snapshot bootstrap before applying any later deltas.

The Open/Switch/Connect dialogs use the active connection URL when connected,
otherwise this same configured startup URL, then the default on port 8081.
**Resynchronize Model** requests a new snapshot; replacement of the local projection
is guarded so its cleanup does not publish deletions to the shared model.

## Inbound enterprise updates (M4)

Approved central changes use ordinary accepted model operations and existing
Kafka/WebSocket delivery. The semantic element updates under `RemoteApplyGuard`,
including every view that references it. A correlated `EXTERNAL_FIELD_OWNED`
rejection drops that rejected offline outbox batch, surfaces a conflict and
requests a cold checkout; later queued batches remain retained. See the
[M4 policy and recovery workflow](../docs/turboea/m4-inbound.md). The [M5 explicit central-change command](../docs/turboea/m5-central-edits.md)
adds **Enterprise Repository: Propose/change central data...** for linked elements.
It shows authority, baseline, local and central values before confirmation. Requests
use the current ArchiMesh session and require separately enabled requester scopes;
Turbo EA credentials stay on the server. Proposal approval and incoming conflict
resolution are available in the admin UI.

Native Archi property editors remain optimistic. The authoritative server guard
rejects ordinary edits to central/shared fields, including offline batches, and
uses the conflict/cold-checkout path above. The command does not patch EMF directly;
verified changes return through ordinary accepted operations.
