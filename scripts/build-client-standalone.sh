#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CLIENT_DIR="${CLIENT_DIR:-$ROOT_DIR/client}"
CLIENT_DIR="$(cd "$CLIENT_DIR" && pwd)"
export CLIENT_DIR
source "$ROOT_DIR/scripts/client-plugin-artifact.sh"

"$ROOT_DIR/scripts/prepare-client-target-platform.sh"

(
  cd "$CLIENT_DIR"
  ./mvnw -q -DskipTests package
)

PLUGIN_JAR="$(find_client_plugin_jar "$CLIENT_DIR")"
echo "Standalone client build succeeded. Plugin JAR: $PLUGIN_JAR"
echo "To install without rebuilding: ./scripts/install-archimesh-plugin.sh --no-build --client-dir \"$CLIENT_DIR\""
