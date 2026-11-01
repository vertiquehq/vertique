#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Builds the load harness, manages its database container and runs measurements.
#
#   harness.sh build                               package the module, write its classpath and its identity
#   harness.sh pg-up                               create or start the database container (name starts with wf017)
#   harness.sh pg-down                             stop the container; it is never removed
#   harness.sh pg-statements on|off                load or unload pg_stat_statements (restarts the container)
#   harness.sh run <spec.json> <out-dir>           one run from a hand-written specification
#   harness.sh run-point <matrix.json> <point> <replicate> <attempt> <out-dir>
#   harness.sh run-matrix <matrix.json> <out-dir>  every replicate of the matrix's order, retrying an invalid run up to
#                                                  the ceiling the matrix sets
#   harness.sh freeze <matrix.json>                make the matrix read-only and print its hash
#   harness.sh evaluate <run-dir>...               judge finished runs again from their raw files
#   harness.sh report <matrix.json> <out-dir> <run-dir-root>
#
# Every command that starts a run refuses to run when the sources changed since the build, or when the jars are not the
# ones the build recorded (target/identity.json), so a run always names the executable that produced it.
#
# Environment: WF_PG_CONTAINER (default wf017-pg), WF_PG_PORT (default 55417), WF_PG_IMAGE (default postgres:16-alpine),
# WF_QUIET_LOAD (default 4): the one-minute load average run-matrix waits for before each run.
# The script touches only the container it names and only processes the runner started.
set -euo pipefail

MODULE=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ROOT=$(cd "$MODULE/../.." && pwd)
CONTAINER=${WF_PG_CONTAINER:-wf017-pg}
PORT=${WF_PG_PORT:-55417}
IMAGE=${WF_PG_IMAGE:-postgres:16-alpine}
IDENTITY="$MODULE/target/identity.json"

case "$CONTAINER" in
  wf017*) ;;
  *) echo "The container name must start with wf017: $CONTAINER" >&2; exit 2 ;;
esac

classpath() {
  local jar
  jar=$(ls "$MODULE"/target/vertique-example-workflow-load-*.jar 2>/dev/null | grep -v -- '-sources' | head -1)
  [ -n "$jar" ] && [ -f "$MODULE/target/classpath.txt" ] || { echo "run 'harness.sh build' first" >&2; exit 1; }
  echo "$jar:$(cat "$MODULE/target/classpath.txt")"
}

# The state of the sources: the commit, every tracked change and every file that is not tracked and not ignored.
source_state() {
  {
    git -C "$ROOT" rev-parse HEAD
    git -C "$ROOT" diff HEAD --binary | shasum -a 256
    git -C "$ROOT" ls-files --others --exclude-standard -z | (cd "$ROOT" && xargs -0 shasum -a 256 2>/dev/null) | shasum -a 256
  } | shasum -a 256 | cut -d' ' -f1
}

require_identity() {
  [ -f "$IDENTITY" ] || { echo "no $IDENTITY: run 'harness.sh build'" >&2; exit 2; }
  local recorded now
  recorded=$(jq -r .sourceState "$IDENTITY")
  now=$(source_state)
  if [ "$recorded" != "$now" ]; then
    echo "Refusing to run: the sources changed since the build recorded in $IDENTITY ($recorded, now $now). Run 'harness.sh build'." >&2
    exit 2
  fi
}

wait_quiet() {
  local limit=${WF_QUIET_LOAD:-4} i=0 load
  while [ $i -lt 40 ]; do
    load=$(uptime | sed 's/.*averages: //' | awk '{print $1}' | tr -d ',')
    awk -v l="$load" -v q="$limit" 'BEGIN { exit !(l < q) }' && return 0
    sleep 15
    i=$((i + 1))
  done
  echo "the host never became quiet (load $load); the run is judged on its own evidence" >&2
}

java_run() { # java_run <args after LoadRun>
  require_identity
  ulimit -n 10240 2>/dev/null || true
  java -cp "$(classpath)" dev.vertique.examples.workflow.load.harness.run.LoadRun \
    --module-dir "$MODULE" --identity "$IDENTITY" "$@"
}

cmd=${1:-}
shift || true
case "$cmd" in
  build)
    (cd "$ROOT" && ./mvnw -ntp -q -pl examples/vertique-example-workflow-load -am package dependency:build-classpath \
      -DskipTests -Darchetype.test.skip=true -Dmdep.outputFile=target/classpath.txt \
      -Dmdep.includeScope=runtime)
    java -cp "$(classpath)" dev.vertique.examples.workflow.load.harness.run.Identity write "$IDENTITY" "$(source_state)"
    echo "identity: $(jq -r .classpathSha256 "$IDENTITY")"
    ;;
  pg-up)
    if docker inspect "$CONTAINER" >/dev/null 2>&1; then
      docker start "$CONTAINER" >/dev/null
    else
      docker run -d --name "$CONTAINER" -p "127.0.0.1:$PORT:5432" -e POSTGRES_PASSWORD=postgres "$IMAGE" \
        -c shared_buffers=1GB -c max_connections=700 -c max_wal_size=4GB -c work_mem=32MB >/dev/null
    fi
    until docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
    sleep 2
    docker exec "$CONTAINER" psql -U postgres -At -c "select version()" \
      -c "select name || '=' || setting || coalesce(unit, '') from pg_settings where source <> 'default' order by name"
    ;;
  pg-down)
    docker stop "$CONTAINER" >/dev/null
    echo "stopped $CONTAINER"
    ;;
  pg-statements)
    mode=${1:?on or off}
    docker start "$CONTAINER" >/dev/null 2>&1 || true
    until docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
    if [ "$mode" = on ]; then
      docker exec "$CONTAINER" psql -U postgres -At -c "ALTER SYSTEM SET shared_preload_libraries = 'pg_stat_statements'"
    else
      docker exec "$CONTAINER" psql -U postgres -At -c "ALTER SYSTEM RESET shared_preload_libraries"
    fi
    docker restart "$CONTAINER" >/dev/null
    until docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
    docker exec "$CONTAINER" psql -U postgres -At -c "show shared_preload_libraries"
    ;;
  run)
    spec=${1:?spec file} out=${2:?output directory}
    java_run --spec "$spec" --out "$out"
    ;;
  run-point)
    matrix=${1:?matrix file} point=${2:?point} replicate=${3:?replicate} attempt=${4:?attempt} out=${5:?output directory}
    java_run --matrix "$matrix" --point "$point" --replicate "$replicate" --attempt "$attempt" --out "$out"
    ;;
  run-matrix)
    matrix=${1:?matrix file} out=${2:?output directory}
    ceiling=$(jq -r '.thresholds.maxAttemptsPerReplicate // 3' "$matrix")
    jq -r '.order[] | "\(.point) \(.replicate)"' "$matrix" | while read -r point replicate; do
      attempt=1
      while [ "$attempt" -le "$ceiling" ]; do
        wait_quiet
        echo "== $point replicate $replicate attempt $attempt"
        java_run --matrix "$matrix" --point "$point" --replicate "$replicate" --attempt "$attempt" --out "$out" || true
        validity=$(jq -r .validity "$out/$point-r$replicate-a$attempt/result.json" 2>/dev/null || echo missing)
        [ "$validity" = valid ] && break
        attempt=$((attempt + 1))
      done
    done
    ;;
  freeze)
    matrix=${1:?matrix file}
    chmod a-w "$matrix"
    echo "$(shasum -a 256 "$matrix" | cut -d' ' -f1)  $matrix (read-only)"
    ;;
  evaluate)
    exec java -cp "$(classpath)" dev.vertique.examples.workflow.load.harness.eval.RunEvaluator "$@"
    ;;
  report)
    matrix=${1:?matrix file} out=${2:?output directory} runs=${3:?run directory root}
    exec java -cp "$(classpath)" dev.vertique.examples.workflow.load.harness.eval.ReportBuilder "$matrix" "$out" "$runs"
    ;;
  *)
    sed -n 4,26p "${BASH_SOURCE[0]}" >&2
    exit 2
    ;;
esac
