#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -gt 0 ]]; then
  echo "Usage: bash scripts/run-isolated-server-gate.sh" >&2
  exit 2
fi
RUN_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
PROJECT="archimesh-server-gate-${RUN_ID:0:8}"
RUN_DIR="$ROOT_DIR/server/target/isolated-server-gate/$RUN_ID"
mkdir -p "$RUN_DIR"
printf '%s\n' "$PROJECT" > "$RUN_DIR/project.txt"
export DRILL_DB_PASSWORD="gate-$RUN_ID"
export DRILL_KAFKA_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
COMPOSE=(docker compose --project-name "$PROJECT" --file "$ROOT_DIR/tests/recovery/compose.yml")
TESTS_STARTED=false

cleanup() {
  result=$?
  trap - EXIT
  if [[ "$TESTS_STARTED" == true && -d "$ROOT_DIR/server/target/surefire-reports" ]]; then
    cp -R "$ROOT_DIR/server/target/surefire-reports" "$RUN_DIR/surefire-reports" || result=1
  fi
  "${COMPOSE[@]}" logs --no-color > "$RUN_DIR/infrastructure.log" 2>&1 || true
  if ! "${COMPOSE[@]}" down --volumes --remove-orphans > "$RUN_DIR/cleanup.log" 2>&1; then
    echo "Isolated gate cleanup failed; inspect $RUN_DIR/cleanup.log" >&2
    result=1
  fi
  printf '%s\n' "$result" > "$RUN_DIR/exit-code.txt"
  if [[ "$result" == 0 ]]; then echo "[isolated-server-gate] PASS"; fi
  echo "[isolated-server-gate] Artifacts: $RUN_DIR"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "[isolated-server-gate] Starting fresh Neo4j and Kafka in $PROJECT"
"${COMPOSE[@]}" up -d source-db kafka > "$RUN_DIR/startup.log" 2>&1
ready=false
for attempt in {1..60}; do
  if "${COMPOSE[@]}" exec -T source-db cypher-shell -u neo4j -p "$DRILL_DB_PASSWORD" 'RETURN 1' > "$RUN_DIR/readiness.log" 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo "Neo4j did not become ready; inspect $RUN_DIR" >&2
  exit 1
fi
"${COMPOSE[@]}" exec -T source-db cypher-shell -u neo4j -p "$DRILL_DB_PASSWORD" \
  < "$ROOT_DIR/neo4j/schema.cypher" > "$RUN_DIR/schema.log" 2>&1
export NEO4J_URI="bolt://$("${COMPOSE[@]}" port source-db 7687)"
export NEO4J_USER=neo4j
export NEO4J_PASSWORD="$DRILL_DB_PASSWORD"
export KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$DRILL_KAFKA_PORT"
export KAFKA_CONSUMER_GROUP_ID="$PROJECT"
export RUN_LOCAL_INFRA_IT=true
export RUN_WS_E2E_IT=true
unset RUN_RECOVERY_DRILL
cd "$ROOT_DIR/server"
echo "[isolated-server-gate] Running server unit, infrastructure and WebSocket tests"
TESTS_STARTED=true
mvn -q '-Dtest=*Test,*IT' -Dquarkus.http.test-port=0 test 2>&1 | tee "$RUN_DIR/tests.log"
