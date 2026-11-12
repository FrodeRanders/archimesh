#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARCHI_DIR="${ARCHI_DIR:-$ROOT_DIR/../archi}"
ARCHI_APP_PATH="${ARCHI_APP_PATH:-/Applications/Archi.app}"
ARCHI_APP_PLUGINS_DIR="${ARCHI_APP_PLUGINS_DIR:-$ARCHI_APP_PATH/Contents/Eclipse/plugins}"
CLIENT_DIR="${CLIENT_DIR:-$ROOT_DIR/client}"
TP_DIR="$CLIENT_DIR/target-platform/plugins"

mkdir -p "$TP_DIR"

copy_bundle() {
  local bundle="$1"
  local src="$2"
  if [[ ! -f "$src" ]]; then
    echo "Missing required bundle: $src" >&2
    exit 1
  fi
  cp "$src" "$TP_DIR/$bundle.jar"
}

resolve_bundle_path() {
  local bundle="$1"

  # 1) Archi source checkout layout: ../archi/com.archimatetool.<x>/com.archimatetool.<x>.jar
  local source_checkout_path="$ARCHI_DIR/$bundle/$bundle.jar"
  if [[ -f "$source_checkout_path" ]]; then
    echo "$source_checkout_path"
    return 0
  fi

  # 2) Installed Archi app with unpacked plugin dirs:
  #    .../plugins/com.archimatetool.<x>_<version>/<bundle>.jar
  if [[ -d "$ARCHI_APP_PLUGINS_DIR" ]]; then
    local unpacked_bundle_path
    unpacked_bundle_path="$(ls -1d "$ARCHI_APP_PLUGINS_DIR/$bundle"_*/"$bundle".jar 2>/dev/null | head -n1 || true)"
    if [[ -n "$unpacked_bundle_path" && -f "$unpacked_bundle_path" ]]; then
      echo "$unpacked_bundle_path"
      return 0
    fi

    # 3) Installed Archi app with jar plugin layout:
    #    .../plugins/com.archimatetool.<x>_<version>.jar
    local jar_bundle_path
    jar_bundle_path="$(ls -1 "$ARCHI_APP_PLUGINS_DIR/$bundle"_*.jar 2>/dev/null | head -n1 || true)"
    if [[ -n "$jar_bundle_path" && -f "$jar_bundle_path" ]]; then
      echo "$jar_bundle_path"
      return 0
    fi
  fi

  return 1
}

copy_required_bundle() {
  local bundle="$1"
  local resolved
  resolved="$(resolve_bundle_path "$bundle" || true)"
  if [[ -z "$resolved" ]]; then
    cat >&2 <<EOF
Unable to locate required bundle: $bundle
Checked:
  1) ARCHI_DIR checkout: $ARCHI_DIR/$bundle/$bundle.jar
  2) ARCHI app plugins (unpacked): $ARCHI_APP_PLUGINS_DIR/${bundle}_*/$bundle.jar
  3) ARCHI app plugins (jar): $ARCHI_APP_PLUGINS_DIR/${bundle}_*.jar

Set either:
  ARCHI_DIR=/path/to/archi/source
or:
  ARCHI_APP_PATH=/path/to/Archi.app
  ARCHI_APP_PLUGINS_DIR=/path/to/Archi/plugins
EOF
    exit 1
  fi
  copy_bundle "$bundle" "$resolved"
}

# Core Archi bundles required by org.gautelis.archimesh.plugin MANIFEST.MF.
copy_required_bundle "com.archimatetool.model"
copy_required_bundle "com.archimatetool.editor"

echo "Prepared local bundle pool in $TP_DIR"
