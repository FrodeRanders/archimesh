# Archimesh Server (Quarkus)

This module implements the Archimesh server described in the repository architecture and schema documents.

## Includes

- WebSocket endpoint: `ws://localhost:8081/models/{modelId}/stream`
- REST endpoint: `GET http://localhost:8081/models/{modelId}/snapshot`
- REST endpoint: `POST http://localhost:8081/models/{modelId}/rebuild`
- REST endpoint: `GET http://localhost:8081/admin/models/{modelId}/status`
- REST endpoint: `POST http://localhost:8081/admin/models/{modelId}/rebuild-and-status`
- REST endpoint: `GET http://localhost:8081/admin/models/{modelId}/window?limit=25`
- REST endpoint: `GET http://localhost:8081/admin/overview?limit=25`
- REST endpoint: `GET http://localhost:8081/admin/models/{modelId}/integrity`
- REST endpoint: `DELETE http://localhost:8081/admin/models/{modelId}`
- Static admin UI: `http://localhost:8081/admin-ui/`
- Inbound messages: `Join`, `SubmitOps`, `AcquireLock`, `ReleaseLock`, `Presence`
- Outbound messages: `CheckoutSnapshot`, `CheckoutDelta`, `OpsAccepted`, `OpsBroadcast`, `LockEvent`,
  `PresenceBroadcast`, `Error`
- Service interfaces: `ValidationService`, `RevisionService`, `LockService`, `Neo4jRepository`, `KafkaPublisher`,
  `KafkaConsumer`, `SessionRegistry`, `IdempotencyService`
- In-memory core implementations plus concrete Kafka/Neo4j adapters for local development

## Run

```bash
cd server
mvn quarkus:dev
```

## Local integration tests (Kafka + Neo4j)

These tests are optional and disabled by default. They run against local services from `../docker-compose.yml`.

1. Start infrastructure:

```bash
docker compose up -d
docker compose exec -T neo4j cypher-shell -u "${NEO4J_USER:-neo4j}" -p "${NEO4J_PASSWORD:-devpassword}" < neo4j/schema.cypher
```

2. Run tests with flag:

```bash
cd server
RUN_LOCAL_INFRA_IT=true mvn -q test
```

3. Run websocket end-to-end test (two sessions, Kafka fan-out):

```bash
cd server
RUN_LOCAL_INFRA_IT=true RUN_WS_E2E_IT=true mvn -q test -Dtest=WebSocketEndToEndIT
```

Test class:

- `server/src/test/java/org/gautelis/archimesh/service/impl/LocalInfraIntegrationTest.java`
- `server/src/test/java/org/gautelis/archimesh/endpoint/WebSocketEndToEndIT.java`

## Example client message envelope

```json
{
  "type": "SubmitOps",
  "payload": {
    "baseRevision": 0,
    "opBatchId": "11111111-1111-1111-1111-111111111111",
    "actor": {
      "userId": "u1",
      "sessionId": "s1"
    },
    "ops": [
      {
        "type": "UpdateViewObjectOpaque",
        "viewId": "view:v1",
        "viewObjectId": "vo:o1",
        "notationJson": {},
        "causal": {
          "clientId": "s1",
          "lamport": 1234,
          "opId": "11111111-1111-1111-1111-111111111111:0"
        }
      }
    ]
  }
}
```

`opBatchId` idempotency is model-scoped in persistence and replay handling, i.e. keyed by `(modelId, opBatchId)`.

Maintainer invariants:
- materialized entity identity is `(modelId, id)`
- commit/idempotency identity is `(modelId, opBatchId)`
- notation field validation and persisted field clocks must flow through `NotationMetadata`
- repository write paths must fail fast on Neo4j persistence errors
- when `app.authz.enabled=true`, Quarkus acts as the PEP and admin endpoints require the configured admin role
- when model ACLs are configured, per-model read/write/admin decisions come from the ACL, not just global reader/writer roles

## Current behavior notes

- Models must be registered through `POST /admin/models/{modelId}` before clients can use them.
- `Join`, `SubmitOps`, `AcquireLock`, `ReleaseLock`, and `Presence` reject unknown `modelId` values with `MODEL_NOT_FOUND`.
- Models use a linear revision timeline with immutable named tags captured from the current `HEAD` snapshot.
- `Join` and `GET /models/{modelId}/snapshot` accept an optional reference (`HEAD` by default, or a tag name).
- Tagged references are read-only; writes and lock/presence traffic are only allowed on `HEAD`.
- Tag deletion is disabled by default. Re-enable it only with `app.tags.allow-delete=true` if you explicitly want mutable tag administration.
- Prefer descriptive tag names that communicate intent, for example `v1.2`, `milestone-3`, or `approved-2026-03-08`.
- `GET /admin/models/{modelId}/export` returns a package containing model metadata, op-log replay history, current snapshot, and tags.
- `POST /admin/models/import?overwrite=true|false` imports that package. Existing models are rejected unless `overwrite=true`, and overwrite is refused while the model has active sessions.
- Export/import is model-scoped and preserves tags as part of the same linear timeline package.
- The package structure is documented in [EXPORT_FORMAT.md](./EXPORT_FORMAT.md).
- The persisted Neo4j graph structure is documented in [../neo4j/graph-model.md](../neo4j/graph-model.md).
- Model ACLs are managed via `GET/PUT /admin/models/{modelId}/acl`.
- Models created through the admin API seed the creating user as the initial model admin/writer/reader.
- Authorization identity modes:
    - `app.authz.enabled=false` by default
    - `app.identity.mode=bootstrap` by default
    - admin role name defaults to `admin`
    - reader role defaults to `model_reader`
    - writer role defaults to `model_writer`
    - optional provider-role alias lists:
        - `app.authz.admin-role-aliases`
        - `app.authz.reader-role-aliases`
        - `app.authz.writer-role-aliases`
        - these normalize provider-specific group names into the canonical internal roles before the PDP evaluates policy
    - `bootstrap` mode:
        - REST identity comes from `X-Archimesh-User` and `X-Archimesh-Roles`
        - websocket identity comes from query parameters `user` and `roles`
        - intended for local/dev and simple standalone deployment
    - `proxy` mode:
        - REST identity comes from trusted forwarded headers
        - websocket identity comes from trusted forwarded headers captured during the handshake
        - header names default to `X-Forwarded-User` and `X-Forwarded-Roles`
        - override with `app.identity.proxy.user-header` and `app.identity.proxy.roles-header`
        - concrete forwarding examples live in:
- [nginx-proxy-mode.example.conf](./examples/nginx-proxy-mode.example.conf)
        - [caddy-proxy-mode.example.Caddyfile](./examples/caddy-proxy-mode.example.Caddyfile)
        - [traefik-proxy-mode.example.yml](./examples/traefik-proxy-mode.example.yml)
    - `oidc` mode:
        - REST identity comes from the authenticated Quarkus `SecurityContext`
        - websocket identity comes from the authenticated websocket upgrade principal and role checks captured during the handshake
        - use this when Quarkus itself is running with OIDC/JWT or another auth mechanism that establishes a principal and roles
        - no reverse proxy is required in this mode
    - catalog-wide admin actions (`/admin/models`, create, import, overview) still require the global admin role
    - model-scoped admin actions may be performed by a user listed in that model's ACL

- `SubmitOps` runs the pipeline skeleton:
    - validate
    - lock check for opaque notation updates
    - assign revision range
    - append/apply/update head via repository abstraction
    - publish via Kafka abstraction
    - broadcast acceptance (`OpsAccepted`)
- Neo4j implementation writes commit/op log and applies core materialized state operations.
- Rebuild API restores the latest checkpoint and replays its complete op-log tail, or replays the complete log when no checkpoint exists, in one transaction.
- If an op omits `causal`, the server fills it (`clientId`, `lamport`, `opId`) before persisting/broadcasting.
- `CreateViewObject` and `UpdateViewObjectOpaque` now use LWW merge for geometry fields
  (`x`, `y`, `width`, `height`) using tuple `(lamport, clientId)`.
- Kafka publisher emits JSON payloads to:
    - `archimesh.model.<modelId>.ops`
    - `archimesh.model.<modelId>.locks`
    - `archimesh.model.<modelId>.presence`
- Kafka consumer subscribes to `archimesh.model.*.ops` and emits websocket `OpsBroadcast`.
- Kafka consumer also subscribes to `locks` and `presence` topics and emits websocket `LockEvent` / `PresenceBroadcast`.

## Durable operation delivery

The [process recovery drill](../docs/recovery-drills.md) exercises force-stopped packaged servers, durable publication after a broker outage, checkpoint-plus-tail recovery after pruning, and interrupted v2 restore into an empty database. Run `./scripts/validate-local.sh recovery` from the repository root; it creates dedicated Docker resources and writes evidence under `server/target/recovery-drill/`.

The delivery service discovers pending model IDs separately from decoding stored batches. It rotates through sorted model IDs, with a maximum of 100 IDs scanned per pass. A bounded pool handles independent models concurrently; only one queued or running worker may handle a given model. Each visit publishes a limited prefix in revision order, waiting for broker acknowledgment and the database publication flag before sending the next batch.

| Property | Environment variable | Default |
| --- | --- | --- |
| `app.delivery.concurrent-models` | `ARCHIMESH_DELIVERY_CONCURRENT_MODELS` | `4` |
| `app.delivery.batches-per-model` | `ARCHIMESH_DELIVERY_BATCHES_PER_MODEL` | `25` |

Both limits must be positive. More model workers allow healthy models to progress while other sends await broker completion; all occupied slots still impose backpressure. A slow send retains its model slot until completion or shutdown, so later batches cannot overtake it. Lease renewal and selection use separate scheduler threads. Submit bursts coalesce scan requests; the periodic scan also runs once per second.

A publish failure, corrupt/missing payload, or publication-flag write failure stops only that model's visit and applies a one-second retry cooldown. The failed batch and its successors remain pending. Payload identity, range and operation count must agree with the stored receipt. Inspect model-specific delivery warnings and repair invalid stored data before expecting that model to progress; other models continue. Broker-wide or database-wide outages can affect all models. At-least-once delivery and original-range deduplication remain unchanged.

The pending cursor is ephemeral; a server restart scans durable pending work again. Shutdown interrupts worker waits without acknowledging unfinished intent, and an in-progress lease renewal cannot reactivate a stopped dispatcher. Apply `neo4j/schema.cypher` to install the `commit_delivery` index on `(published, modelId, revisionFrom)` for pending queue reads.

## Snapshot / Rebuild API usage

Get canonical snapshot from Neo4j materialized state:

```bash
curl -s "http://localhost:8081/models/demo/snapshot" | jq .
```

Rebuild materialized state from checkpoint plus tail (or complete op-log):

```bash
curl -s -X POST "http://localhost:8081/models/demo/rebuild" | jq .
```

Create a checkpoint, inspect it, and preview/apply covered payload pruning (model-admin access required):

```bash
curl -s -X POST "http://localhost:8081/admin/models/demo/checkpoints" | jq .
curl -s "http://localhost:8081/admin/models/demo/checkpoints/latest" | jq .
curl -s -X POST "http://localhost:8081/admin/models/demo/compact?retainRevisions=1000&dryRun=true" | jq .
curl -s -X POST "http://localhost:8081/admin/models/demo/compact?retainRevisions=1000" | jq .
```

Each applied pruning transaction handles at most `app.compaction.max-batches-per-run` batches (default `100`; `ARCHIMESH_COMPACTION_MAX_BATCHES`). Payloads require checkpoint coverage and confirmed publication. Receipts, clocks, and tombstones remain. Repeated calls resume from durable state. See [checkpoint maintenance](../docs/checkpoint-pruning.md) for recovery, archive requirements, legacy publication handling, and current limits.

For scheduled maintenance, set `ARCHIMESH_CHECKPOINT_MAINTENANCE_ENABLED=true` (default `false`). Defaults are a 300-second interval, a 1000-revision checkpoint threshold, two retained checkpoints, and ten models per pass. The same retention/publication rules apply. Configure the policy and archive requirements using the [scheduler reference](../docs/checkpoint-pruning.md#optional-scheduled-maintenance); enabling it applies to all registered models.

Get aggregated admin status (snapshot counts + consistency):

```bash
curl -s "http://localhost:8081/admin/models/demo/status" | jq .
```

Rebuild and return status in one call:

```bash
curl -s -X POST "http://localhost:8081/admin/models/demo/rebuild-and-status" | jq .
```

Get a model window (status + recent activity + recent op batches):

```bash
curl -s "http://localhost:8081/admin/models/demo/window?limit=25" | jq .
```

Get overview for all known active/recent models:

```bash
curl -s "http://localhost:8081/admin/overview?limit=25" | jq .
```

Dashboard note:

- The admin UI now lives in `admin-web/`.
- Build it with `scripts/build-admin-web.sh`; Quarkus serves the built static app at `/admin-ui/`.
- `/server-window.html` now only redirects to `/admin-ui/`.
- The Svelte app currently implements `Overview` and `Access` as separate routes, with dedicated routes reserved for `Models`, `Versions`, `Sessions`, and `Audit`.
- The admin UI supports bootstrap auth inputs for `X-Archimesh-User` and `X-Archimesh-Roles`, plus an optional bearer token for `oidc` mode.
- When `app.identity.mode=proxy`, access the admin UI through the trusted proxy and let the proxy supply forwarded identity headers instead of using bootstrap inputs.
- Admin auth diagnostics are available at `GET /admin/auth/diagnostics`; the admin UI exposes the same check through the `Auth Check` action and shows the resolved identity mode, subject, and normalized roles for the current request.
- The admin UI poll interval is configurable and persisted in browser storage; set it to `0` to disable auto-refresh entirely.
- When a bearer token is present, admin `401`/`403` failures explain likely token-expiry or missing-role/model-access problems instead of only showing the raw HTTP status.
- Access to admin diagnostics, admin model window reads, and mutating admin actions is also emitted as `admin_audit` INFO logs with a stable JSON payload containing the resolved user id and request context. Rejected admin mutations that return `409` or `400` are audited too.
- Websocket lifecycle events that matter operationally are emitted as `ws_audit` INFO logs with stable JSON payloads:
  - rejected opens
  - successful joins
  - rejected websocket messages
  - closes
- `ws_audit` can be narrowed with `app.audit.websocket.actions` (comma-separated action names). Set it to an empty value to disable websocket audit emission entirely.
- Set `app.audit.websocket.verbose=true` to also emit `WebSocketMessageAccepted` events for accepted `SubmitOps`, `AcquireLock`, `ReleaseLock`, and `Presence` messages.
- Operational guidance for `admin_audit`:
  - ship these JSON lines to a dedicated audit sink or index if you rely on them for operator review
  - keep retention shorter than ordinary application logs if dashboard polling is frequent
  - avoid over-aggressive dashboard refresh intervals in production; repeated `window` reads will produce repeated audit events
  - treat `admin_audit` as machine-readable output and parse the JSON payload instead of depending on free-form message text
  - apply the same structured-log handling to `ws_audit`, and use `app.audit.websocket.actions` to keep websocket audit volume bounded
  - a concrete Vector shipping example lives in [vector-audit-log.example.toml](./examples/vector-audit-log.example.toml)

## Standalone JWT setup

If you want bearer-token auth without a reverse proxy or external OIDC provider, `app.identity.mode=oidc`
works with Quarkus JWT validation directly.

Minimal config:

```properties
app.authz.enabled=true
app.identity.mode=oidc
app.authz.admin-role=admin
app.authz.reader-role=model_reader
app.authz.writer-role=model_writer
app.authz.admin-role-aliases=realm-admin,archimesh-admin
app.authz.reader-role-aliases=realm-viewer,archimesh-reader
app.authz.writer-role-aliases=realm-editor,archimesh-writer
mp.jwt.verify.publickey.location=conf/publicKey.pem
mp.jwt.verify.issuer=https://archimesh.example
```

Operational notes:

- `mp.jwt.verify.publickey.location` should point at the public key used to verify incoming bearer tokens.
- Tokens must carry the user identity in the JWT principal/subject used by Quarkus.
- Role/group membership should map to the same role names used by the PDP:
  - `admin`
  - `model_reader`
  - `model_writer`
- If your IdP emits different group names, configure the alias properties so they normalize into those canonical roles.

For a local dev-only setup, there is a matching example config in
[local-jwt-dev.example.properties](./examples/local-jwt-dev.example.properties).
It uses the repo's checked-in test public key and the matching local issuer `https://archimesh.dev`.

## Minting local dev tokens

If you want prompt-friendly bearer tokens during development, use:

```bash
scripts/mint-dev-jwt.sh --user alice --roles admin,model_writer,model_reader
```

By default the script:

- signs with [privateKey.pem](./src/test/resources/jwt/privateKey.pem)
- emits issuer `https://archimesh.dev`
- emits a `groups` claim from `--roles`
- sets `sub`, `upn`, and `preferred_username` from `--user`
- expires the token after 1 hour

Useful variants:

```bash
scripts/mint-dev-jwt.sh --user bob --roles model_writer --expires-in 600 --print-payload
scripts/mint-dev-jwt.sh --user ci-admin --roles admin --audience archimesh-server
```

Environment overrides:

- `DEV_JWT_PRIVATE_KEY`
- `DEV_JWT_ISSUER`
- `DEV_JWT_EXPIRES_IN`
- `DEV_JWT_AUDIENCE`

This tool is intended for local/dev only. It pairs cleanly with:

```properties
app.authz.enabled=true
app.identity.mode=oidc
quarkus.oidc.enabled=false
mp.jwt.verify.publickey.location=src/test/resources/jwt/publicKey.pem
mp.jwt.verify.issuer=https://archimesh.dev
```
- Model ACLs still apply on top of those roles when a model has an ACL configured.

For local development with `scripts/dev-up.sh`, you do not need to pass the JWT verifier settings separately if
you already choose local OIDC mode. This is enough:

```bash
ARCHIMESH_IDENTITY_MODE=oidc ./scripts/dev-up.sh background
```

`dev-up.sh` will then default:
- `ARCHIMESH_AUTHZ_ENABLED=true`
- `MP_JWT_VERIFY_PUBLICKEY_LOCATION=$REPO_ROOT/server/src/test/resources/jwt/publicKey.pem`
- `MP_JWT_VERIFY_ISSUER=https://archimesh.dev`

If you want a different issuer or key, override those env vars explicitly before starting the server.

Example HTTP call with bearer token:

```bash
curl -s \
  -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/models/demo/snapshot" | jq .
```

Example websocket connection with bearer token on the upgrade request:

```text
GET /models/demo/stream HTTP/1.1
Host: localhost:8081
Upgrade: websocket
Connection: Upgrade
Authorization: Bearer <token>
```

In `oidc` mode the server uses the authenticated Quarkus security context for REST and verifies websocket
bearer tokens from the handshake when the upgraded session does not expose the principal directly.

## External OIDC provider setup

If you already have an external OIDC provider, `app.identity.mode=oidc` can also run with Quarkus OIDC instead
of local JWT verification.

Concrete provider examples:

- [keycloak-oidc.example.properties](./examples/keycloak-oidc.example.properties)
- [auth0-oidc.example.properties](./examples/auth0-oidc.example.properties)

Concrete proxy-mode example:

- [nginx-proxy-mode.example.conf](./examples/nginx-proxy-mode.example.conf)
- [caddy-proxy-mode.example.Caddyfile](./examples/caddy-proxy-mode.example.Caddyfile)
- [traefik-proxy-mode.example.yml](./examples/traefik-proxy-mode.example.yml)

Concrete audit shipping example:

- [vector-audit-log.example.toml](./examples/vector-audit-log.example.toml)

Minimal example:

```properties
app.authz.enabled=true
app.identity.mode=oidc
quarkus.oidc.enabled=true
app.authz.admin-role=admin
app.authz.reader-role=model_reader
app.authz.writer-role=model_writer
app.authz.admin-role-aliases=realm-admin
app.authz.reader-role-aliases=realm-viewer
app.authz.writer-role-aliases=realm-editor

quarkus.oidc.auth-server-url=https://idp.example/realms/archimesh
quarkus.oidc.client-id=archimesh-server
quarkus.oidc.application-type=service
quarkus.oidc.roles.role-claim-path=groups
```

Operational notes:

- Use `quarkus-oidc` when Quarkus should validate provider-issued access tokens directly.
- `quarkus.oidc.enabled=false` is the default in this repo so local JWT verification remains the passive default until you explicitly enable external OIDC.
- Keep the PDP role names stable and use the alias properties to translate provider group names into:
  - `admin`
  - `model_reader`
  - `model_writer`
- For Keycloak specifically, the common role claim layouts are:
  - `groups`
  - `realm_access/roles`
- For Auth0 specifically, common role claim layouts are:
  - `permissions`
  - a custom namespaced claim such as `https://archimesh.example/roles`
- The admin UI can then either:
  - run behind your normal OIDC-authenticated frontend path, or
  - use a bearer token pasted into the `Bearer Token` field
- Websocket clients should send the same bearer token on the upgrade request using `Authorization: Bearer ...`.

## Reverse proxy setup

If you want to run `app.identity.mode=proxy`, the reverse proxy is responsible for authenticating the caller
and forwarding a trusted identity to Quarkus.

Minimal runtime config:

```properties
app.authz.enabled=true
app.identity.mode=proxy
app.identity.proxy.user-header=X-Forwarded-User
app.identity.proxy.roles-header=X-Forwarded-Roles
```

Operational notes:

- The included Nginx, Caddy, and Traefik examples show both:
  - a fixed-header smoke test for local validation
  - the production pattern where an upstream auth layer populates the forwarded headers
- The proxy must forward websocket upgrade requests as well as ordinary REST traffic.
- Do not expose `proxy` mode directly to untrusted clients without a trusted proxy in front of it, because the identity headers are authoritative in this mode.

Get integrity report (missing references/orphans):

```bash
curl -s "http://localhost:8081/admin/models/demo/integrity" | jq .
```

Delete model from server state (development reset):

```bash
curl -s -X DELETE "http://localhost:8081/admin/models/demo" | jq .
```

Force delete even if active sessions exist:

```bash
curl -s -X DELETE "http://localhost:8081/admin/models/demo?force=true" | jq .
```

## Next implementation step

Add integration tests against local Docker Kafka+Neo4j to validate submit -> persist -> publish -> consume -> websocket
fan-out end-to-end.
