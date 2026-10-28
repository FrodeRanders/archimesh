# Archimesh Client Workspace

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

## Notes

- Builds use `client/mvnw`, pinned to Maven 3.9.16 for Tycho compatibility.
- This lets client work live with server code in one repo.
- It does not require syncing plugin sources to another repo.
- If required bundles change in `MANIFEST.MF`, update `scripts/prepare-client-target-platform.sh`.
- Building/running Tycho client builds may require escalated permissions in this environment due to `~/.m2` lock behavior.

## Startup Pull From Server (Optional)

You can opt in to opening an Archimesh model from the central server when Archi starts, without first opening a local `.archimate` file.

You can also open directly from server at runtime using:
- `Tools -> Open Archimesh Model from Server...`

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
