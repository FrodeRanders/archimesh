#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARCHIMATE_REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ARCHI_SOURCE_DIR_DEFAULT="${ARCHIMATE_REPO_DIR}/../archi"
CLIENT_DIR_DEFAULT="${ARCHIMATE_REPO_DIR}/client"
BUILD_MODE_DEFAULT="client"

ARCHI_SOURCE_DIR="${ARCHI_SOURCE_DIR:-${ARCHI_SOURCE_DIR_DEFAULT}}"
CLIENT_DIR="${CLIENT_DIR:-${CLIENT_DIR_DEFAULT}}"
BUILD_MODE="${BUILD_MODE:-${BUILD_MODE_DEFAULT}}"
ARCHI_APP_PATH="${ARCHI_APP_PATH:-/Applications/Archi.app}"
ARCHI_INI_PATH="${ARCHI_INI_PATH:-${ARCHI_APP_PATH}/Contents/Eclipse/Archi.ini}"
if [[ "$(uname -s)" == "Darwin" ]]; then
  DROPINS_DIR_DEFAULT="${HOME}/Library/Application Support/Archi/dropins"
else
  DROPINS_DIR_DEFAULT="${HOME}/Archi/dropins"
fi
DROPINS_DIR="${DROPINS_DIR:-${DROPINS_DIR_DEFAULT}}"
PLUGIN_MODULE="org.gautelis.archimesh.plugin"
PLUGIN_ID="org.gautelis.archimesh.plugin"
source "${SCRIPT_DIR}/client-plugin-artifact.sh"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Builds the Archimesh plugin from source and installs it into Archi dropins.
Use --no-build to install the JAR from an earlier build without rebuilding.
Client builds use the workspace's pinned Maven wrapper, just like build-client-standalone.sh.

Options:
  --build-mode <client|archi>
                         Build from client standalone workspace or Archi checkout
                         (default: client)
  --client-dir <path>    Path to client standalone workspace (default: ./client)
  --archi-source <path>   Path to Archi source checkout (default: ../archi)
  --archi-app <path>      Path to Archi.app (default: /Applications/Archi.app)
  --dropins <path>        Dropins root dir (default: ~/Library/Application Support/Archi/dropins on macOS)
  --patch-ini             Set dropins JVM arg in Archi.ini
  --no-prune-duplicates  Do not remove duplicate archimesh jars from common dropins locations
  --clear-runtime-cache   Remove Archi runtime cache (~/Library/Application Support/Archi/config)
  --no-build              Skip Maven build and only copy latest jar
  -h, --help              Show this help

Environment variables (alternative to flags):
  BUILD_MODE, CLIENT_DIR, ARCHI_SOURCE_DIR, ARCHI_APP_PATH, ARCHI_INI_PATH, DROPINS_DIR

Notes:
  --clear-runtime-cache deletes Archi OSGi/runtime cache and should be run with Archi closed.
USAGE
}

PATCH_INI="false"
DO_BUILD="true"
PRUNE_DUPLICATES="true"
CLEAR_RUNTIME_CACHE="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-mode)
      BUILD_MODE="$2"
      shift 2
      ;;
    --client-dir)
      CLIENT_DIR="$2"
      shift 2
      ;;
    --archi-source)
      ARCHI_SOURCE_DIR="$2"
      shift 2
      ;;
    --archi-app)
      ARCHI_APP_PATH="$2"
      ARCHI_INI_PATH="${ARCHI_APP_PATH}/Contents/Eclipse/Archi.ini"
      shift 2
      ;;
    --dropins)
      DROPINS_DIR="$2"
      shift 2
      ;;
    --patch-ini)
      PATCH_INI="true"
      shift
      ;;
    --no-prune-duplicates)
      PRUNE_DUPLICATES="false"
      shift
      ;;
    --clear-runtime-cache)
      CLEAR_RUNTIME_CACHE="true"
      shift
      ;;
    --no-build)
      DO_BUILD="false"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ "${BUILD_MODE}" != "client" && "${BUILD_MODE}" != "archi" ]]; then
  echo "Invalid --build-mode: ${BUILD_MODE} (expected 'client' or 'archi')" >&2
  exit 1
fi

if [[ "${BUILD_MODE}" == "client" ]]; then
  if [[ -z "${CLIENT_DIR}" || ! -d "${CLIENT_DIR}" ]]; then
    echo "Client directory not found: ${CLIENT_DIR}" >&2
    exit 1
  fi
else
  if [[ -z "${ARCHI_SOURCE_DIR}" || ! -d "${ARCHI_SOURCE_DIR}" ]]; then
    echo "Archi source directory not found: ${ARCHI_SOURCE_DIR}" >&2
    exit 1
  fi
fi

if [[ "${DO_BUILD}" == "true" ]]; then
  if [[ "${BUILD_MODE}" == "client" ]]; then
    echo "[build] Building ${PLUGIN_MODULE} from standalone client at ${CLIENT_DIR}"
    CLIENT_DIR="${CLIENT_DIR}" ARCHI_APP_PATH="${ARCHI_APP_PATH}" "${SCRIPT_DIR}/build-client-standalone.sh"
  else
    echo "[build] Building ${PLUGIN_MODULE} from ${ARCHI_SOURCE_DIR}"
    mvn -q -pl "${PLUGIN_MODULE}" -am -DskipTests package -f "${ARCHI_SOURCE_DIR}/pom.xml"
  fi
fi

if [[ "${BUILD_MODE}" == "client" ]]; then
  PLUGIN_JAR="$(find_client_plugin_jar "${CLIENT_DIR}")"
else
  PLUGIN_JAR="$(find_client_plugin_jar "${ARCHI_SOURCE_DIR}")"
fi

if [[ "${PATCH_INI}" == "true" && ! -f "${ARCHI_INI_PATH}" ]]; then
  echo "Archi.ini not found: ${ARCHI_INI_PATH}" >&2
  exit 1
fi

if [[ "${CLEAR_RUNTIME_CACHE}" == "true" ]]; then
  RUNTIME_CACHE_DIR="${HOME}/Library/Application Support/Archi/config"
  echo "[cleanup] Clearing Archi runtime cache at ${RUNTIME_CACHE_DIR}"
  rm -rf "${RUNTIME_CACHE_DIR}"
fi

echo "[artifact] ${PLUGIN_JAR}"
PLUGIN_DROPINS_DIR="${DROPINS_DIR}/eclipse/plugins"
cleanup_archimesh_jars() {
  local dir="$1"
  [[ -d "${dir}" ]] || return 0

  shopt -s nullglob
  local matches=("${dir}/${PLUGIN_ID}"-*.jar)
  shopt -u nullglob
  if [[ ${#matches[@]} -gt 0 ]]; then
    echo "  - Removing ${#matches[@]} old jar(s) from ${dir}"
    rm -f "${matches[@]}"
  fi
}

echo "[cleanup] Removing old plugin jars from target dropins"
mkdir -p "${PLUGIN_DROPINS_DIR}"
cleanup_archimesh_jars "${PLUGIN_DROPINS_DIR}"

if [[ "${PRUNE_DUPLICATES}" == "true" ]]; then
  echo "[cleanup] Pruning duplicate plugin jars from common dropins locations"
  declare -a CANDIDATE_DIRS=(
    "${HOME}/Library/Application Support/Archi/dropins/eclipse/plugins"
    "${HOME}/Archi/dropins/eclipse/plugins"
    "${ARCHI_APP_PATH}/Contents/Eclipse/dropins/eclipse/plugins"
  )
  for candidate in "${CANDIDATE_DIRS[@]}"; do
    if [[ "${candidate}" != "${PLUGIN_DROPINS_DIR}" ]]; then
      cleanup_archimesh_jars "${candidate}"
    fi
  done
else
  echo "[cleanup] Skipping duplicate-prune step (--no-prune-duplicates)"
fi

echo "[install] Installing plugin jar into dropins"
cp -f "${PLUGIN_JAR}" "${PLUGIN_DROPINS_DIR}/"

echo "Installed: ${PLUGIN_DROPINS_DIR}/$(basename "${PLUGIN_JAR}")"

if [[ "${PATCH_INI}" == "true" ]]; then
  echo "[config] Patching Archi.ini at ${ARCHI_INI_PATH}"
  if [[ ! -f "${ARCHI_INI_PATH}" ]]; then
    echo "Archi.ini not found: ${ARCHI_INI_PATH}" >&2
    exit 1
  fi

  DROPINS_ARG="-Dorg.eclipse.equinox.p2.reconciler.dropins.directory=${DROPINS_DIR}"
  TMP_FILE="$(mktemp)"
  awk -v arg="${DROPINS_ARG}" '
    BEGIN { inserted=0 }
    /^-Dorg\.eclipse\.equinox\.p2\.reconciler\.dropins\.directory=/ { next }
    /^-vmargs$/ && inserted==0 { print; print arg; inserted=1; next }
    { print }
    END {
      if(inserted==0) {
        print "-vmargs"
        print arg
      }
    }
  ' "${ARCHI_INI_PATH}" > "${TMP_FILE}"
  cp "${TMP_FILE}" "${ARCHI_INI_PATH}"
  rm -f "${TMP_FILE}"
  echo "Updated dropins arg in Archi.ini"
else
  echo "[config] Skipping Archi.ini patch (use --patch-ini to enable)"
fi

echo "[done] Installation complete"
cat <<NEXT

Next:
  1. Restart Archi once with cache refresh:
       open -a "${ARCHI_APP_PATH}" --args -clean
  2. Verify plugin:
       Help -> About Archi -> Installation Details -> Plug-ins
       Look for: ${PLUGIN_ID}
  3. Tools menu should include:
       Open Archimesh Model from Server...
       Switch Active Model to Server-Backed...
NEXT
