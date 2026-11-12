# DataHub OSS ↔ Turbo EA bridge

An independent subproject within the ArchiMesh repository. Bridge implementation,
DataHub infrastructure, release-specific discovery, synthetic fixtures and tests
live here. ArchiMesh's ordinary builds and release gates do not build or run it.

Current implementation: D0 DataHub OSS development environment and contract probes.
The runtime connector, identity migration and DataHub–Turbo EA bridge are later
stages; this tooling does not enable synchronization or change ArchiMesh models.

## Boundaries

- Keep bridge-specific dependencies, configuration, jobs, operations and documentation
  in this directory. The tooling is self-contained and uses Python's standard library
  plus Docker Compose; it imports no ArchiMesh implementation.
- ArchiMesh consumes versioned repository/identity/authority contracts. It must not
  depend on bridge scheduling, bootstrap scripts or test infrastructure. Any shared
  library should expose only reusable contracts/adapters and be independently built.
- One logical identity registry is shared by both paths. Separate packaging must
  not introduce a second canonical mapping or independent mutation fences.
- A future repository extraction should move this subtree and update contract
  dependencies/links; it should not require moving the ArchiMesh model engine.

See the [implementation stages](docs/implementation.md), [D0 assessment](docs/d0-assessment.md),
[shared identity ADR](../../docs/adr/0002-three-repository-federation.md) and the
[ArchiMesh consumer boundary](../../docs/datahub-integration.md).

## Local commands

From this directory, with Python 3 and Docker Compose available:

```bash
python3 scripts/local.py init
python3 scripts/local.py up
python3 scripts/local.py status
python3 scripts/local.py probe
python3 scripts/local.py down

# Fresh isolated storage and allocated loopback ports; cleanup runs on success/failure.
python3 scripts/local.py test

# Offline harness checks.
python3 -m unittest discover -s tests -v
```

Development endpoints are `http://127.0.0.1:18080` (GMS) and
`http://127.0.0.1:19002` (frontend). Initialization generates mode-0600 credentials
and the synthetic frontend `datahub` password in `.run/datahub/dev/user.props`.
The `.run` directories are ignored. `down` retains development volumes;
`down --volumes` explicitly removes only this subproject's development volumes.

The test command creates a unique `datahub-turboea-it-<run>` project, seeds synthetic
metadata, checks APIs and authentication, restarts GMS/frontend without reseeding,
checks persisted state and removes its own containers/network/volumes. Reports,
schema snapshots, image identities and redacted logs remain in its `.run` directory.
Interrupted tests may leave resources if Docker is unavailable or the process is
force-killed; use the recorded project state to review and clean up that exact run.

The first pull needs network access to the image registries and several GB of disk.
The baseline has been exercised on ARM64 with 16 GB assigned to Docker. Tests do not
require a Turbo EA or ArchiMesh instance at D0. The optional root command
`./scripts/validate-local.sh datahub` delegates here; normal root gates exclude it.

## Pinned baseline and local safety

`compose.json` is an adapted DataHub OSS `v1.8.0` quickstart, with the source URL,
SHA-256 and adaptations recorded in [upstream.json](upstream.json). The agency's
installed version remains unknown. This is a development contract baseline, not a
claim that the agency uses this release or a production deployment prescription.
The upstream license is preserved in [LICENSE.datahub](LICENSE.datahub).

DataHub images and storage dependencies use fixed release tags; each run records
resolved image IDs/digests. To use an internal mirror, configure
`DATAHUB_IMAGE_REGISTRY` in the generated environment file with a reviewed registry
containing the same pinned DataHub images. Storage image references are reviewed
in `compose.json`; floating tags are not supported by this baseline.

Only GMS/frontend ports are published, bound to loopback. Storage and Kafka stay
inside the project network; no host AWS, SSO, home or plugin directories are mounted.
GMS authentication, REST authorization and view authorization are enabled. The only
bind mount is the generated local JAAS credential file. Telemetry/third-party logging
and ingestion UI are disabled; Actions/cloud ingestion are absent.

Probes use locally signed, one-hour synthetic SESSION tokens with this disposable
stack's generated signing key. This is test bootstrap, not the production token flow.
The seeder deactivates the upstream all-users view policy **inside this synthetic
environment**, then grants narrowly selected read/write capabilities to test users.
It cannot accept an external URL or agency credentials. `probe` reseeds the named
development fixture and may reset its synthetic metadata; it is not read-only.

OSS search may expose identifiers and totals even when exact entity reads return
403. Connector disclosure filtering remains required. See the [D0 evidence](docs/d0-contract-evidence.md) for the
actual API and visibility observations; authentication alone does not prove catalog
isolation. No DataHub writes have been enabled for a production connector.
