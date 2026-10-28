#!/usr/bin/env bash
set -euo pipefail

MANUAL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MERMAID_CLI="${MERMAID_CLI:-mmdc}"
command -v "$MERMAID_CLI" >/dev/null || { echo "Mermaid CLI (mmdc) is required to render figures." >&2; exit 1; }
mkdir -p "$MANUAL_DIR/figures"

# PDF preserves vector text and lines. --pdfFit removes browser-page whitespace.
for source in "$MANUAL_DIR"/diagrams/*.mmd; do
  name="$(basename "$source" .mmd)"
  "$MERMAID_CLI" --input "$source" --output "$MANUAL_DIR/figures/$name.pdf" \
    --configFile "$MANUAL_DIR/diagrams/mermaid-config.json" --width 1600 --pdfFit --quiet
done
