# Turbo EA 2.194.0 → 2.203.2 — reviewed local upgrade

Reviewed and executed on 2026-11-13. Turbo EA v2.203.2 is pinned to commit
`2223d50ef53f56ffe367e026c94ab01d70c263b2`, source archive SHA-256
`c0b71ad3e2cff2678d2d623db94ca1fb253b5ef8c1c469f5e85ab7428ed5edc1`.
The original M0–M6 baseline documents remain historical 2.194.0 evidence.

## Contract and migration review

The [upstream release](https://github.com/vincentmakes/turbo-ea/releases/tag/v2.203.2)
and [version comparison](https://github.com/vincentmakes/turbo-ea/compare/v2.194.0...v2.203.2)
were checked directly against the tagged source. The integration's 13 existing
OpenAPI operations retain their request bodies, response declarations, authentication
and existing parameters. GET cards adds an optional `query` parameter. All 12
transitively used schemas are identical. The saved OpenAPI's `info.version` is
`latest`, so it does not establish the running version: `/api/health` does.

Authentication, relations and metamodel route source files, backend configuration,
Dockerfile and license are byte-identical between the inspected releases. Card
querying, archive/delete behavior and document event filtering change elsewhere;
our existing stream endpoint declaration is unchanged. New capabilities include
inventory queries and decision collaboration/history. MCP BPMN import now produces
a draft, with submit/approve as separate actions; our integration does not use MCP.

Seven Alembic migrations (166–172) add query bookmarks, decision/collaboration and
history data, comment permissions and a reset of Initiative cost KPI snapshots.
They must be exercised against a copy before changing a retained database.
No unconditional downgrade of an already migrated database is assumed safe.

The connector retains its exact version check. Demo source/image pins and generated
`expectedApiVersion` now use 2.203.2; no `latest` image tag or version bypass was
introduced. Existing runs keep their recorded version (legacy runs mean 2.194.0)
until `./scripts/demo.sh upgrade` succeeds. Opt-in Java publication/SSE deployment
tests default to 2.203.2; `TURBO_EA_EXPECTED_VERSION` permits an explicit older target.

## Execution and retained identities

The demo's [upgrade command](../../examples/order-management/README.md#upgrade-an-existing-demo)
uses the independent database trial/backup helper in
`integrations/datahub-turboea/tools/datahub_local/turbo_upgrade.py`.
Scheduling, configuration, model and registry handling stay in the optional demo.
The trial helper imports no ArchiMesh implementation and deletes only its own
uniquely generated Compose project and volume.

Backups include PostgreSQL's custom-format dump, attachment tar, model exports,
integration archive and original configuration. Integration export uses the normal
pause/validated-resume contract. A clone is migrated and checked first; only then
are the existing database volume and version configuration upgraded. Failures before
that point resume the old stack. Later failures stop writers and preserve backups
for reviewed recovery. The command does not reseed or create replacement IDs.

PATCH probing permits the server's recomputed `hierarchyLevel`, while requiring
that an omitted test attribute disappears and omitted top-level values survive.
The independent target write-readback test confirms these semantics. `external_id`
still does not deduplicate POST, so durable intent fences remain necessary.

## Acceptance evidence

- `./scripts/demo.sh test`, run `75487b8ba6da`: clean builds, seed/readback, live
  post-start DataHub dataset discovery, explicit import, source update/reversion,
  repeated import, worker restart, whole-stack restart and scoped cleanup passed.
- Existing demo `2610bcd6330b`: migrated clone and in-place database checks passed;
  all 27 existing cards, 27 relations, metamodel identities, one model snapshot and
  canonical/external/model identity bindings were retained.
- Its private backup/report directory is
  `.run/order-management/2610bcd6330b/upgrade-2.203.2-20261113-144840/`.
  `result.json` records the backup checksum and successful probes. Disposable probe
  cards were created only in the clone, never in the retained demo.
- After upgrade, the bridge reported nine verified datasets, zero blocked, and
  no repeated changes. Runtime health reports 2.203.2.
- Java deployment API/metamodel/catalog and publication/readback tests passed on
  separate migrated clones. Authenticated SSE also passed with its explicit
  deployment flag: all three tests ran with zero skips.
- 16 demo/bootstrap tests and 19 independent bridge/tool tests passed. Connection
  output was also checked against the real configuration files: each system's URL,
  username and password appear together. The credentials are not copied into
  `access.json`; its existing private-file references remain.
