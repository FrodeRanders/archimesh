#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

"$ROOT_DIR/scripts/prepare-client-target-platform.sh"

(
  cd "$ROOT_DIR/client"
  ./mvnw -q -DskipTests package
)

echo "Standalone client build succeeded: $ROOT_DIR/client/org.gautelis.archimesh.plugin/target"
