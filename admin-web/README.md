# Archimesh Admin Web

This is the Svelte-based admin UI for the Archimesh server.

Current status:
- `Overview` is implemented as a real route.
- `Access` is implemented as a real route.
- `Versions` is implemented as a real route.
- `Models` is implemented as a real route.
- `Sessions` is implemented as a real route.
- `Audit` is implemented as a real route.
- `server-window.html` is now only a redirect to `/admin-ui/`.

## Build

From the repo root:

```bash
scripts/build-admin-web.sh
```

That installs frontend dependencies in `admin-web/` and emits the static build into:

- `server/src/main/resources/META-INF/resources/admin-ui`

Quarkus then serves the built app at:

- `/admin-ui/`

## Model maintenance

The Models page shows the latest checkpoint and provides **Create Checkpoint**, **Preview Pruning**, **Prune Covered Payloads**, and rebuild controls. Choose how many recent revisions to retain before previewing or applying. Applied runs are bounded by the server's batch limit; repeat while eligible batches remain. The response explains the checkpoint boundary and actual deletions.

Pruning preserves acceptance receipts and CRDT metadata. Archive a full-history export before pruning if old operation payloads are needed. See the [checkpoint workflow and limits](../docs/checkpoint-pruning.md).
