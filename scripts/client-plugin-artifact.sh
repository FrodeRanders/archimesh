#!/usr/bin/env bash
# Shared by the client builder and installer. Print the newest runtime bundle,
# excluding auxiliary Maven artifacts that cannot be installed as the plugin.
find_client_plugin_jar() {
  local workspace="$1" candidate newest=""
  for candidate in "$workspace"/org.gautelis.archimesh.plugin/target/org.gautelis.archimesh.plugin-*.jar; do
    [[ -f "$candidate" ]] || continue
    case "$candidate" in
      *-sources.jar|*-javadoc.jar|*-tests.jar) continue ;;
    esac
    if [[ -z "$newest" || "$candidate" -nt "$newest" ]]; then
      newest="$candidate"
    fi
  done
  if [[ -z "$newest" ]]; then
    echo "Plugin JAR not found under $workspace/org.gautelis.archimesh.plugin/target; run ./scripts/build-client-standalone.sh first." >&2
    return 1
  fi
  printf '%s\n' "$newest"
}
