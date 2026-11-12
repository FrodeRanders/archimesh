# DataHub–Turbo EA subproject

This directory owns the standalone bridge work, its development infrastructure,
adapter-specific discovery, tests and operations documentation. Keep new bridge
work here. It must be buildable/testable without an ArchiMesh server, client,
Maven build or running model repository.

The ArchiMesh-facing identity/authority contract is shared. Preserve the stable
canonical IDs and boundaries in `../../docs/adr/0002-three-repository-federation.md`.
Changes to those contracts require compatibility review of both consumers; do not
make ArchiMesh depend on bridge scheduler internals or bootstrap infrastructure.

Run `python3 -m unittest discover -s tests -v` from this directory. For the live D0
gate, run `python3 scripts/local.py test` with Docker available. Only disposable,
uniquely named test volumes may be removed automatically. Never operate on an
agency deployment or unrelated Compose project with the development harness.
