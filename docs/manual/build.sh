#!/usr/bin/env bash
set -euo pipefail

MANUAL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ $# -gt 1 ]]; then
  echo "Usage: bash docs/manual/build.sh [--render-figures]" >&2
  exit 2
fi
if [[ "${1:-}" == "--render-figures" ]]; then
  bash "$MANUAL_DIR/render-figures.sh"
elif [[ $# -gt 0 ]]; then
  echo "Usage: bash docs/manual/build.sh [--render-figures]" >&2
  exit 2
fi
command -v pdflatex >/dev/null || { echo "pdflatex is required to build the manual." >&2; exit 1; }

BUILD_DIR="$MANUAL_DIR/.build"
mkdir -p "$BUILD_DIR"
cd "$MANUAL_DIR"
# Three passes settle contents, figure references and final page counts.
for pass in 1 2 3; do
  if ! pdflatex -interaction=nonstopmode -halt-on-error -file-line-error \
      -output-directory="$BUILD_DIR" archimesh-manual.tex > "$BUILD_DIR/pass-$pass.log" 2>&1; then
    cat "$BUILD_DIR/pass-$pass.log" >&2
    exit 1
  fi
done
if grep -Eq 'undefined references|Rerun to get|Label\(s\) may have changed|Overfull' "$BUILD_DIR/pass-3.log"; then
  echo "Unresolved references or overflowing content; inspect $BUILD_DIR/pass-3.log" >&2
  exit 1
fi
cp "$BUILD_DIR/archimesh-manual.pdf" "$MANUAL_DIR/archimesh-manual.pdf"
echo "Built $MANUAL_DIR/archimesh-manual.pdf"
