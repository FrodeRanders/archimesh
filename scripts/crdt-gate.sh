#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_LOCAL_INFRA_IT="${RUN_LOCAL_INFRA_IT:-false}"

SERVER_TESTS="*Test"
CLIENT_TESTS="*Test"

if [[ "$RUN_LOCAL_INFRA_IT" == "true" ]]; then
  SERVER_TESTS="$SERVER_TESTS,*IT"
fi

ensure_local_infra() {
  local neo4j_ok=false
  local kafka_ok=false

  # Check Neo4j Bolt port (subshell suppresses bash /dev/tcp noise)
  if (echo >/dev/tcp/localhost/7687) 2>/dev/null; then
    neo4j_ok=true
  else
    echo "[crdt-gate] Neo4j not reachable at localhost:7687" >&2
  fi

  # Check Kafka broker port
  if (echo >/dev/tcp/localhost/9092) 2>/dev/null; then
    kafka_ok=true
  else
    echo "[crdt-gate] Kafka not reachable at localhost:9092" >&2
  fi

  if $neo4j_ok && $kafka_ok; then
    return 0
  fi

  echo "[crdt-gate] Local infra not running, attempting docker compose up" >&2
  docker compose -f "$ROOT_DIR/docker-compose.yml" up -d

  echo "[crdt-gate] Waiting for Neo4j (bolt://localhost:7687) to become ready" >&2
  local neo4j_user="${NEO4J_USER:-neo4j}"
  local neo4j_pass="${NEO4J_PASSWORD:-devpassword}"
  for i in $(seq 1 30); do
    if docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T neo4j \
        cypher-shell -u "$neo4j_user" -p "$neo4j_pass" "RETURN 1" >/dev/null 2>&1; then
      neo4j_ok=true
      break
    fi
    sleep 2
  done

  if ! $neo4j_ok; then
    echo "[crdt-gate] FAILED: Neo4j did not become ready within 60 s" >&2
    echo "[crdt-gate] Ensure Docker is running and docker compose up succeeds, then retry." >&2
    exit 1
  fi

  echo "[crdt-gate] Applying Neo4j schema" >&2
  docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T neo4j \
    cypher-shell -u "$neo4j_user" -p "$neo4j_pass" < "$ROOT_DIR/neo4j/schema.cypher"

  echo "[crdt-gate] Waiting for Kafka (localhost:9092) to become ready" >&2
  for i in $(seq 1 15); do
    if (echo >/dev/tcp/localhost/9092) 2>/dev/null; then
      kafka_ok=true
      break
    fi
    sleep 2
  done

  if ! $kafka_ok; then
    echo "[crdt-gate] FAILED: Kafka did not become ready within 30 s" >&2
    echo "[crdt-gate] Ensure Docker is running and docker compose up succeeds, then retry." >&2
    exit 1
  fi

  echo "[crdt-gate] Local infra is ready (Neo4j + Kafka)" >&2
}

echo "[crdt-gate] Running hardening contract checks"
"$ROOT_DIR/scripts/check-crdt-hardening.sh"

echo "[crdt-gate] Preparing client target platform bundles"
"$ROOT_DIR/scripts/prepare-client-target-platform.sh"

echo "[crdt-gate] Installing client plugin artifact (required by Archimesh client-tests)"
(
  cd "$ROOT_DIR/client"
  ./mvnw -q -pl org.gautelis.archimesh.plugin -am install -DskipTests
)

echo "[crdt-gate] Running client CRDT tests"
(
  cd "$ROOT_DIR/client/client-tests"
  ../mvnw -q -Dtest="$CLIENT_TESTS" test
)

if [[ "$RUN_LOCAL_INFRA_IT" == "true" ]]; then
  ensure_local_infra
fi

echo "[crdt-gate] Running server CRDT tests"
(
  cd "$ROOT_DIR/server"
  RUN_LOCAL_INFRA_IT="$RUN_LOCAL_INFRA_IT" mvn -q -Dtest="$SERVER_TESTS" test
)

echo "[crdt-gate] PASS"
