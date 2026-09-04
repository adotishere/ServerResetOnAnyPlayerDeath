#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "$0")"
SERVER_JAR="${SERVER_JAR:-fabric-server-launch.jar}"
JAVA_CMD="${JAVA_CMD:-java}"
JAVA_ARGS="${JAVA_ARGS:--Xms2G -Xmx2G}"
mkdir -p reset-datapacks

get_level_name() {
  local name
  name="$(sed -n 's/^level-name=//p' server.properties 2>/dev/null | tail -n 1)"
  name="${name:-world}"
  case "$name" in
    *..*|*/*|*\\*|"") echo "Unsafe level-name '$name'." >&2; exit 1 ;;
  esac
  printf '%s' "$name"
}

while true; do
  level_name="$(get_level_name)"
  mkdir -p "./$level_name/datapacks"
  cp -a reset-datapacks/. "./$level_name/datapacks/"
  # JAVA_ARGS intentionally supports normal whitespace-separated JVM flags.
  # shellcheck disable=SC2086
  "$JAVA_CMD" $JAVA_ARGS -jar "$SERVER_JAR" nogui || true
  [[ -f server-reset-request.json ]] || break
  grep -q '"requestedBy":"server_reset_hardcore"' server-reset-request.json || {
    echo "Invalid reset marker; refusing to delete the world." >&2
    exit 1
  }

  level_name="$(get_level_name)"
  rm -rf -- "./$level_name"
  rm -f -- server-reset-request.json
  echo "World deleted; restarting server..."
done
