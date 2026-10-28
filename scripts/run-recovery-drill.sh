#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
case "${1:-}" in
  '') ;;
  --skip-build) ;;
  *) echo "Usage: bash scripts/run-recovery-drill.sh [--skip-build]" >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then
  echo "Usage: bash scripts/run-recovery-drill.sh [--skip-build]" >&2
  exit 2
fi

cd "$ROOT_DIR/server"
if [[ "${1:-}" != "--skip-build" ]]; then
  mvn -q -DskipTests package
fi
exec env RUN_RECOVERY_DRILL=true mvn -q -Dtest=ProcessRecoveryIT test
