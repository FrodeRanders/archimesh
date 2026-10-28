# Kafka topics and partitioning

## Three shared topics

| Topic (default prefix) | Content | Record key |
| --- | --- | --- |
| `archimesh.model.ops` | Committed operation batches | `modelId` |
| `archimesh.model.locks` | Lock events | `modelId` |
| `archimesh.model.presence` | Cursor and selection events | `modelId` |

`KAFKA_TOPIC_PREFIX` changes the common prefix. The publisher and consumer must use the same value. The consumer subscribes to these exact names and routes each record to the sessions for its key. Lock and presence payloads need not carry a model ID; if any payload does carry one, it must match the key. Missing/blank keys and conflicting identities are rejected.

There are **three application partitions total** with one partition per topic, whether there are 1, 100 or 10,000 models. Broker-internal topics such as consumer offsets use additional partitions. Creating/deleting a model does not create/delete topics. The old `<prefix>.<modelId>.<kind>` layout is no longer published or consumed; this change intentionally requires a fresh start rather than compatibility routing.

## Ordering and capacity

All event kinds are keyed by `modelId`. Kafka key partitioning keeps one model in one partition **within each topic**, so multiple models can share a partition without losing their own event order. The durable dispatcher still publishes each model's operation batches in revision order and waits for acknowledgment before the next batch. There is no total order between different models or between ops, locks and presence.

One partition per topic is sufficient for local development. More partitions may be provisioned initially to distribute throughput, while remaining independent of model count. Keep counts stable during publishing: increasing a topic's partition count can move a key to another partition while its old records are still outstanding. Drain traffic/consumers and coordinate a cutover before resizing.

Models now share broker capacity and topic-wide retention/ACL policies. A busy model can delay other models on its partition. Application model ACLs still govern WebSocket subscriptions; Kafka credentials belong to the server, not individual clients. The supported topology remains one authoritative Archimesh server per Neo4j repository.

## Provision the development topics

Start the Redpanda service, then run from the repository root:

```bash
docker compose exec -T kafka rpk topic create archimesh.model.ops --partitions 1 --replicas 1 --brokers localhost:9092
docker compose exec -T kafka rpk topic create archimesh.model.locks --partitions 1 --replicas 1 --brokers localhost:9092
docker compose exec -T kafka rpk topic create archimesh.model.presence --partitions 1 --replicas 1 --brokers localhost:9092
docker compose exec -T kafka rpk topic list --brokers localhost:9092
```

Adjust names if using a different prefix. Auto-creation can create missing topics, but explicit provisioning makes partition counts and policies predictable. The application does not increase existing partition counts.

## Retention

- **ops:** Retain enough for expected consumer outages and lag. Neo4j checkpoints plus the complete operation tail are the recovery source; Kafka is the fan-out path. A client that misses broadcasts resynchronizes through checkout.
- **locks:** Short retention (minutes); leases are process-local and transient.
- **presence:** Very short retention (minutes); cursor and selection events are transient.

These are policy recommendations, not settings automatically applied by the server. Configure broker/topic retention to match the deployment. Keep operation topics on delete-based retention: compaction by `modelId` would discard earlier batches still needed by lagging consumers.

## Fresh development start (destructive)

Stop the Archimesh server first. The following removes **all Kafka messages/offsets and all Neo4j models/history/logs** in this Compose project's volumes. Use it only when discarding the development data intentionally:

```bash
docker compose down --volumes
docker compose up -d neo4j kafka
```

Wait for Neo4j to become ready, reapply `neo4j/schema.cypher` using the credentials from `.env`, and provision the three shared topics above before starting the updated server. See [development setup](../dev-setup.md) for schema commands. Local Archi files and the plugin's persistent outbox are outside these Docker volumes; use a fresh local collaboration session/model when starting over. Do not replay stale offline edits into newly provisioned models unintentionally.

Changing topic names alone does not free old partitions: their topics or broker storage must actually be removed. Do not reuse the old layout with this consumer.

## Verification

`./scripts/validate-local.sh server-isolated` includes `KafkaSharedTopicsIT`: it publishes 640 events for 160 models to three fixed topics with three partitions each, checks per-model operation ordering and keyed fan-out, asserts that no model-specific topics appeared, and deletes its test topics. The packaged-process recovery drill also uses the shared layout, covering durable publication after a broker outage and forced JVM restart.
