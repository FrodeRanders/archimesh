# Archimesh Admin Web

This is the Svelte-based admin UI for the Archimesh server.

Current status:
- `Overview` is implemented as a real route.
- `Access` is implemented as a real route.
- `Versions` is implemented as a real route.
- `Models` is implemented as a real route.
- `Sessions` is implemented as a real route.
- `Audit` is implemented as a real route.
- `Publication` supports explicit HEAD/tag selection, policy approval, reconciliation status and remote baseline review. See [M3 configuration and safeguards](../docs/turboea/m3-publication.md).
- `Inbound sync` supports scoped inbound approval/pause, per-binding diagnostics, reconciliation and reviewed acceptance of central values into HEAD. See [M4](../docs/turboea/m4-inbound.md).
- `Central changes` supports explicit request previews, scoped proposal approval, read-only uncertain-outcome verification and per-model incoming conflict resolution. Remote verification and model delivery are displayed separately. See [M5](../docs/turboea/m5-central-edits.md).
- `Integrations` supports named operator pause/resume, target/configuration inspection, gauges and paginated diagnostics, full reconciliation, reviewed model attachment/mapping migration and paused metadata archive restore. See [M6](../docs/turboea/m6-operations.md).
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
