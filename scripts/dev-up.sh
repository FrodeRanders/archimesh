#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

NEO4J_USER="${NEO4J_USER:-neo4j}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-devpassword}"
QUARKUS_DEBUG="${QUARKUS_DEBUG:-false}"
RUN_MODE="${1:-foreground}"
ARCHIMESH_IDENTITY_MODE="${ARCHIMESH_IDENTITY_MODE:-bootstrap}"
ARCHIMESH_QUARKUS_OIDC_ENABLED="${ARCHIMESH_QUARKUS_OIDC_ENABLED:-false}"

if [[ "${RUN_MODE}" != "foreground" && "${RUN_MODE}" != "background" ]]; then
  echo "Usage: $(basename "$0") [foreground|background]" >&2
  exit 1
fi

echo "Starting the development stack (separate from the seeded three-system demo)."
echo "For demo addresses and the prepared model: ./scripts/demo.sh connection"
echo "To resume that demo: ./scripts/demo.sh start"

echo "[1/4] Starting Neo4j + Kafka containers"
docker compose -f "${ROOT_DIR}/docker-compose.yml" up -d

echo "[2/4] Waiting for Neo4j to become ready"
for i in {1..30}; do
  if docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T neo4j cypher-shell -u "${NEO4J_USER}" -p "${NEO4J_PASSWORD}" "RETURN 1" >/dev/null 2>&1; then
    break
  fi
  sleep 2
  if [[ "$i" -eq 30 ]]; then
    echo "Neo4j did not become ready in time" >&2
    exit 1
  fi
done

echo "[3/4] Applying Neo4j schema"
docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T neo4j \
  cypher-shell -u "${NEO4J_USER}" -p "${NEO4J_PASSWORD}" < "${ROOT_DIR}/neo4j/schema.cypher"

echo "[4/4] Starting Archimesh server"
if [[ "${ARCHIMESH_IDENTITY_MODE}" == "oidc" && "${ARCHIMESH_QUARKUS_OIDC_ENABLED}" != "true" ]]; then
  export ARCHIMESH_AUTHZ_ENABLED="${ARCHIMESH_AUTHZ_ENABLED:-true}"
  export MP_JWT_VERIFY_PUBLICKEY_LOCATION="${MP_JWT_VERIFY_PUBLICKEY_LOCATION:-${ROOT_DIR}/server/src/test/resources/jwt/publicKey.pem}"
  export MP_JWT_VERIFY_ISSUER="${MP_JWT_VERIFY_ISSUER:-https://archimesh.dev}"
  echo "[auth] Local JWT verification enabled"
  echo "[auth] Issuer: ${MP_JWT_VERIFY_ISSUER}"
  echo "[auth] Public key: ${MP_JWT_VERIFY_PUBLICKEY_LOCATION}"
fi
MAVEN_CMD=(mvn -Ddebug="${QUARKUS_DEBUG}" quarkus:dev -f "${ROOT_DIR}/server/pom.xml")
mkdir -p "${ROOT_DIR}/.run"
if [[ "${RUN_MODE}" == "background" ]]; then
  nohup "${MAVEN_CMD[@]}" > "${ROOT_DIR}/.run/archimesh-server.log" 2>&1 &
  echo $! > "${ROOT_DIR}/.run/archimesh-server.pid"
  echo "background" > "${ROOT_DIR}/.run/archimesh-server.mode"
  echo "Archimesh server started in background"
  echo "PID: $(cat "${ROOT_DIR}/.run/archimesh-server.pid")"
  echo "Logs: ${ROOT_DIR}/.run/archimesh-server.log"
else
  # Track the foreground process as well so scripts/dev-down.sh can stop it from another terminal.
  echo $$ > "${ROOT_DIR}/.run/archimesh-server.pid"
  echo "foreground" > "${ROOT_DIR}/.run/archimesh-server.mode"
  exec "${MAVEN_CMD[@]}"
fi
