# DataHub D0 — self-hosted OSS assessment and development plan

Date: 2026-11-12. Status: discovery plan; connector and runnable Compose environment
not implemented or verified yet. This records the deployment constraint and the
first concrete work package, rather than claiming a completed DataHub integration.

The scope now also includes this project's responsibility for a model-independent
DataHub–Turbo EA bridge. See the [modular federation assignment](../archimesh-datahub-turboea-integration-codex.md)
and [ADR 0002](../adr/0002-three-repository-federation.md) for shared identity,
authority, module boundaries and the round-trip acceptance contract. The D0–D4
sequence below is elaborated there; D0 implementation remains the next step.

## Deployment constraint

The target is the agency's locally deployed DataHub open-source offering. Cloud
hosting, Cloud APIs and Cloud-only features are outside this integration's scope.
Development and integration tests will use Docker Compose, consistent with the
other ArchiMesh components. The agency's exact installed version/API contract is
not yet known. No locally running Docker containers were found during this review;
no assumption is made that the agency deployment is on this developer machine.

Use a pinned supported release matching the agency deployment when its version is
available. The official v1.8.0 generated Compose manifest was inspected as a concrete
reference, not selected as the agency's version or asserted to be the latest release.
Avoid moving `master`, `latest` and unpinned quickstart image tags in committed tests.

## Concrete local Compose setup to implement

Keep the DataHub stack separate from the existing root Compose project:

| Concern | Development/test decision |
| --- | --- |
| Services | DataHub GMS, frontend, system-update job, and the release's supported database, search and messaging dependencies |
| Project ownership | Dedicated `archimesh-datahub-dev`; integration runs use a unique `archimesh-datahub-it-<run>` project |
| Networks and volumes | Project-scoped; no fixed upstream global network/volume names; no reuse of ArchiMesh Kafka or Neo4j volumes |
| Host ports | Loopback only; proposed dev GMS `18080`, frontend `19002`; tests use allocated loopback ports |
| Internal storage/broker ports | Not published to the host by default |
| Credentials | Generated disposable development secrets in an ignored mode-0600 env file; no agency credentials in fixtures |
| Authentication | Enable and test authenticated GMS access; separate reader and writer test capabilities |
| Telemetry and cloud hooks | Disable available telemetry/analytics; omit cloud ingestion and Actions plugins from the initial stack |
| Host directories | No home-directory, AWS, SSO-cache or other host credential mounts |
| External downloads | Pin/review Compose and image versions; allow internal registry mirrors for agency development environments |
| Fixtures | Synthetic metadata only; no copied agency datasets, SQL, schema samples or credentials |
| Lifecycle | Repeatable start, health, seed, inspect, stop and project-scoped cleanup; never a global Docker prune |

The upstream v1.8.0 manifest exposes GMS on 8080 and Kafka on 9092 by default,
which conflicts with existing project services. It also defines fixed global
network/volume names, host plugin/AWS directory mounts and disabled GMS authentication.
These are concrete reasons to adapt the release's Compose stack instead of running
an unmodified quickstart beside ArchiMesh. Its system-update initialization job
and dependency health/completion ordering must be retained.

The supported storage/search/messaging combination must be checked against the
chosen release. The inspected manifest uses MySQL, OpenSearch and Kafka; its graph
service uses the search backend and does not require sharing ArchiMesh's Neo4j.
Docker Compose is the development/test topology. The agency's operational hosting,
SSO/TLS, backup and ingress remain its separately managed self-hosted deployment.

Do not share ArchiMesh's Kafka merely because DataHub also uses Kafka: schemas,
bootstrap settings, internal topics, lifecycle and authorization are independent.
A future event consumer can subscribe using an explicitly scoped connection and
consumer group after its contract is verified.

## Federation model

| System | Authority |
| --- | --- |
| DataHub | Selected data-product/dataset identity and governed metadata, schemas and technical lineage |
| Turbo EA | Enterprise application/capability identity and approved enterprise architecture representations |
| ArchiMesh / Archi | Formal ArchiMate concepts, views, collaboration, model history and explicitly owned architectural relationships |

Retain a shared canonical identity, with a separate external binding for each
system and deployment. A DataHub resource key includes its instance identity and
exact URN. Do not truncate, reconstruct or match URNs by display name. Do not
replace existing Turbo EA canonical IDs to introduce a third repository.

Read a selected DataHub entity into a correctly typed ArchiMate concept through
normal accepted model operations. A Turbo EA card can represent the same canonical
object, but neither that card nor an Archi model becomes the authority for
DataHub-owned fields. Start with a read-only DataHub connection, and add central
writes only as separately approved scope.

DataHub entity/aspect/relationship semantics need explicit adapters. Its technical
lineage is not automatically an ArchiMate relationship, and enterprise
PRODUCES/CONSUMES statements are not automatically technical transformation lineage.
DataHub's Application concept must not be assumed equivalent to a Turbo EA
application or an ArchiMate ApplicationComponent. No Cloud Application feature is
needed for the proposed OSS pilot.

## Refactoring required before a connector

The existing integration has reusable identity reservations, baselines, durable
jobs, ordinary external-change acceptance, generation fencing, scoped policies,
readback verification and recovery concepts. Its runtime is still specialized:

1. `IntegrationConfiguration.Target` directly embeds `TurboEaMapping`; runtime
   clients are constructed as `TurboEaClient`. Introduce connector dispatch and
   connector-specific configuration without changing existing Turbo EA defaults.
2. `ResourceKind` is CARD/RELATION and import assumptions pair cards with model
   elements. Add entity/aspect-aware resource descriptions rather than calling
   every DataHub asset a card.
3. `CanonicalObject.kind` currently carries the Turbo EA type, and import reservation
   compares it directly with the incoming remote type. Separate canonical semantics
   from each external system's entity type, so `dataProduct` and a differently named
   Turbo EA representation can share identity safely.
4. Runtime field guards/projection consult type-level Turbo EA mappings and the
   `TURBO_EA_MASTER` enum. Use explicit source/instance/aspect/field authority and
   wire canonical overrides into enforcement, retaining fail-closed behavior.
5. DataHub relationships are often references inside aspects, rather than standalone
   relation resources with their own server-issued ID. Define relationship keys,
   direction, ownership and preservation of other references accordingly.
6. Review the current shared 512-character identity bound and property/payload
   limits against real URNs and aspects. Do not truncate identifiers or import
   complete schemas into bounded model properties by default.
7. Version metadata/archive extensions deliberately. Preserve v1 backup readability,
   old IDs, ambiguity markers and paused migration; do not invalidate recovery proofs
   merely by generalizing the connector.

All Turbo EA regression cases must remain green through this refactoring. The
Archi plugin continues to call ArchiMesh, keeping repository credentials server-side.

## D0 contract-verification checklist

After the local Compose stack is running, capture:

- Exact OSS version, release/image references and exposed OpenAPI/GraphQL schema.
- GMS/frontend authentication paths; tested read/search behavior for allowed and
  forbidden assets using scoped principals.
- Available dataset and data-product entities/aspects in that OSS release. Feature
  availability must come from the release and running instance, not Cloud examples.
- Exact URNs, environment/platform identity, pagination, soft deletion and lifecycle.
- Effective name/description/ownership precedence, including curated versus ingested
  aspects where applicable; aspect null/removal and patch/replacement behavior.
- Mutation acknowledgement versus durable storage/readback behavior, retries,
  authorization errors and unsupported conditional-write assumptions.
- Event availability and retention. Use notifications as invalidation hints and
  retain periodic authoritative reconciliation.

A Metadata Change Proposal is a technical mutation envelope. It is not equivalent
to the human proposal/approval workflow implemented in ArchiMesh M5. Initial writes,
if introduced later, should use authenticated GMS APIs rather than producing directly
to DataHub's internal mutation topic.

## Staged delivery

| Stage | Result |
| --- | --- |
| D0 | Isolated pinned OSS Compose stack, synthetic fixture, API/auth/aspect evidence and ADR |
| D1 | Connector-neutral runtime/configuration/authority and compatible metadata migration, with Turbo EA regression coverage |
| D2 | Read-only DataHub catalog, preview, import/link and inbound refresh through normal model acceptance |
| D3 | Shared canonical identity and governed DataHub-to-Turbo EA representations, with explicit semantic relation mapping |
| D4 | Optional approved DataHub writes, aspect-safe reconciliation, event acceleration, audit and recovery drills |

The first acceptance scenario should use one synthetic data asset/product referenced
from two model HEADs and two views. A DataHub-owned name/description change must
reach those models through accepted operations while preserving old tags; ordinary
local writes to those fields must be rejected. No DataHub write permission is needed
for that initial read-only connector pilot.

D0 is complete only after Compose bring-up, synthetic API/auth checks and a clean
restart/reconciliation probe pass. The current document completes the scope and
code-boundary assessment; it does not claim those runtime checks have happened.

## Primary references reviewed

- [Official Docker deployment instructions](https://github.com/datahub-project/datahub/blob/master/docker/README.md).
- [Official quickstart instructions](https://github.com/datahub-project/datahub/blob/master/docs/quickstart.md).
- [Pinned v1.8.0 Compose reference](https://raw.githubusercontent.com/datahub-project/datahub/v1.8.0/docker/quickstart/docker-compose.quickstart-profile.yml).
- [Metadata model: entities, aspects, relationships and URNs](https://docs.datahub.com/docs/metadata-modeling/metadata-model).
- [APIs and SDKs, including async mutation acknowledgement](https://docs.datahub.com/docs/api/datahub-apis).
- [Authorization policies](https://docs.datahub.com/docs/authorization/policies).
- [Application semantics and availability caveats](https://docs.datahub.com/docs/features/feature-guides/applications).

The upstream default branch is moving documentation. Re-check the selected release
and running OSS instance before turning examples into exact connector contracts.
