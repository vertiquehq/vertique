#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Smoke runs of the harness with real node processes. It is a script and not a test because each run takes more than
# a minute, far over the limit of an integration test class, and because the runs must not share a host with a build.
#
#   scripts/smoke.sh [output-dir]
#
# It starts the database container if it is not running (a container whose name starts with wf017), runs the specifications
# in scripts/smoke one after the other, and checks the verdict files with jq. The exit code is the number of failed checks.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
H="$HERE/harness.sh"
OUT=${1:-$(mktemp -d)/smoke}
mkdir -p "$OUT"
failures=0

check() { # check <description> <run> <jq-expression over result.json>
  if jq -e "$3" "$OUT/$2/result.json" >/dev/null 2>&1; then
    echo "ok    $1"
  else
    echo "FAIL  $1   ($2: $3)"
    failures=$((failures + 1))
  fi
}

run() { # run <spec>
  "$H" run "$HERE/smoke/$1.json" "$OUT" >"$OUT/$1.verdict" 2>"$OUT/$1.err" || echo "run $1 exited $?"
}

"$H" pg-up >/dev/null

# 1. An unknown input fails the run before load, with the exit code for a rejected command line, and starts nothing.
"$H" run "$HERE/smoke/unknown-input.json" "$OUT" >/dev/null 2>"$OUT/unknown-input.err"
code=$?
if [ "$code" -eq 2 ] && [ ! -e "$OUT/smoke-unknown-input" ] && grep -q "concurency" "$OUT/unknown-input.err"; then
  echo "ok    an unknown input fails the run before load"
else
  echo "FAIL  an unknown input fails the run before load (exit $code)"; failures=$((failures + 1))
fi

# 2. A healthy run is valid and shaped as the workload says.
run ok
check "a healthy run is valid and completes what it was offered" smoke-ok \
  '.validity == "valid" and .system.drain.completed and (.load.completionRate > 0.99)'
check "every instance completed and every dispatch was published" smoke-ok \
  '(.sql.instancesByStatus | keys) == ["COMPLETED"] and (.sql.outboxByState | keys) == ["PUBLISHED"]'
check "three commands and one dispatch per instance" smoke-ok \
  '(.sql.commandsByKindAndState | to_entries | map(.value) | add) == (3 * .sql.instancesByStatus.COMPLETED) and (.sql.outboxByState.PUBLISHED == .sql.instancesByStatus.COMPLETED)'

# 2b. With the triggers on, sessions of the nodes wait on the notification lock and the watch names the node.
run triggers-on
if jq -e '.samples > 100 and .queryErrors == 0 and .waiterObservations > 0
          and ([.waiterObservationsByApplication | keys[] | select(startswith("load-node-") | not)] | length == 0)' \
    "$OUT/smoke-triggers-on/collector/lockwatch-summary.json" >/dev/null 2>&1; then
  echo "ok    waiters on 1262:0 with the triggers on are attributed to nodes"
else
  echo "FAIL  waiters on 1262:0 with the triggers on are attributed to nodes"; failures=$((failures + 1))
fi
check "waiters on the notification lock are a result and not a validity rule" smoke-triggers-on '.validity == "valid"'

# 3. A database stall leaves the run valid and is reported with the evidence that coincided with it.
run db-stall
check "a database stall leaves the run valid" smoke-db-stall '.validity == "valid"'
check "the stall is reported as a dip with aligned evidence" smoke-db-stall \
  '.stalls.count >= 1 and (.stalls.list | map(select(.kind == "dip")) | length >= 1)'

# 4. A paused driver is late, and the run is invalid.
run driver-pause
check "a paused driver makes the run invalid by the generator rule" smoke-driver-pause \
  '.validity == "invalid" and (.violations | map(.rule) | index("generator.lateness") != null)'

# 5. Paused nodes do not move the schedule.
run node-pause
check "a paused node leaves the run valid" smoke-node-pause '.validity == "valid"'
check "the schedule held while a node was paused" smoke-node-pause \
  '.system.driver.latenessMaxMs < 250 and .system.driver.lateOverThreshold == 0'

# 6. Refusals are counted, replayed in a labelled phase outside the window.
run refusal
check "refusals were counted" smoke-refusal '.system.driver.refused > 0'
check "the refused keys were replayed" smoke-refusal '.system.driver.replay.planned == .system.driver.refused'
replays=$(grep -c ',replay,' "$OUT/smoke-refusal/driver/arrivals.csv" || true)
first_replay=$(awk -F, '$2 == "replay" {print $6; exit}' "$OUT/smoke-refusal/driver/arrivals.csv")
window_end=$(jq '.windowEndSec * 1000' "$OUT/smoke-refusal/driver/summary.json")
recovery=$(jq '.recoverySec * 1000' "$OUT/smoke-refusal/driver/summary.json")
if [ "$replays" -gt 0 ] && awk -v a="$first_replay" -v b="$window_end" -v c="$recovery" 'BEGIN { exit !(a >= b + c) }'; then
  echo "ok    replays are labelled and start after the window and the recovery"
else
  echo "FAIL  replays are labelled and start after the window and the recovery ($replays rows, first at $first_replay)"
  failures=$((failures + 1))
fi

# 7. A killed listener backend is recorded, also when it came back.
run listener-kill
check "a killed workflow listener is recorded as lost and as reconnected" smoke-listener-kill \
  '.listeners.anyLoss and .listeners.anyReconnect and ([.listeners.events[] | select(.channel == "workflow" and .type == "RECONNECTED")] | length == 1)'
check "a killed relay listener is lost for good" smoke-listener-kill \
  '[.listeners.events[] | select(.channel == "outbox" and .type == "LOST")] | length == 1'
check "the node log says the relay lost its connection" smoke-listener-kill \
  '[.listeners.nodeLogFindings[] | select(.kind == "outbox-lost" and .atShutdown == false)] | length >= 1'
check "a listener loss is a result and not a validity rule" smoke-listener-kill '.validity == "valid"'

# 8. A staircase reports every step and a capacity.
run staircase
check "a staircase judges every step" smoke-staircase '.staircase.steps | length == 4'
check "a staircase gives a bracket or a lower bound, not a capacity" smoke-staircase \
  '.staircase.carriedLowerBoundStartsPerSecond != null and (.staircase.kind == "bracket" or .staircase.kind == "atLeast") and (.staircase | has("capacityStartsPerSecond") | not)'

# 9. A run that is not diagnostic carries gate verdicts: overload and the notification lock.
run g1
check "every stall has an unresolved cause" smoke-g1 '[.stalls.list[] | select(.cause != "unresolved")] | length == 0'
check "gate verdicts are reported for a gated run" smoke-g1 '.gates.overload.verdict != null and .gates.claimLatency.verdict != null and .gates.notificationLock.verdict != null'
check "with both triggers off no node session waited on the notification lock" smoke-g1 '.gates.notificationLock.verdict == "pass"'

# 10. The database going away for the rest of the run does not lose the run.
run db-down
"$H" pg-up >/dev/null
check "a database that stays down leaves a valid run with the reason" smoke-db-down \
  '.validity == "valid" and (.sqlUnavailable != null) and (.system.database.longestTimeSinceASuccessfulSampleMs > 3000)'
for f in result.json manifest.json timeline.json; do
  if [ -s "$OUT/smoke-db-down/$f" ]; then echo "ok    $f was written with the database down"; else echo "FAIL  $f was not written"; failures=$((failures + 1)); fi
done
# 11. Every run, whatever happened to it, finished its attempt record.
unfinished=$(for d in "$OUT"/smoke-*/; do jq -r 'select(.status != "finished") | .name' "$d/attempt.json"; done | tr '\n' ' ')
if [ -z "$unfinished" ]; then echo "ok    every attempt record is finished"; else echo "FAIL  unfinished attempt records: $unfinished"; failures=$((failures + 1)); fi

# 12. A matrix run: runs built from one frozen file are comparable, and the report says so.
cp "$HERE/smoke/matrix.json" "$OUT/matrix.json"
"$H" freeze "$OUT/matrix.json" >/dev/null
"$H" run-matrix "$OUT/matrix.json" "$OUT/matrix-runs" >"$OUT/matrix-run.log" 2>&1 || true
"$H" report "$OUT/matrix.json" "$OUT/matrix-report" "$OUT/matrix-runs" >/dev/null 2>&1 || true
if jq -e '.points[0].replicatesCounted == 2 and (.excludedRuns | length) == 0 and .points[0].status == "measured"' \
    "$OUT/matrix-report/report.json" >/dev/null 2>&1; then
  echo "ok    two replicates built from one frozen matrix compare in the report"
else
  echo "FAIL  the matrix report"; failures=$((failures + 1))
fi
if [ "$(jq -r .matrixSha256 "$OUT/matrix-runs/m-on-2-r1-a1/result.json")" = "$(jq -r .matrixSha256 "$OUT/matrix-report/report.json")" ]; then
  echo "ok    the runs carry the hash of the matrix file"
else
  echo "FAIL  the matrix hash"; failures=$((failures + 1))
fi
# A fifth hand-written run of the same replicate is refused once the replicate is valid.
"$H" run-point "$OUT/matrix.json" m-on-2 1 2 "$OUT/matrix-runs" >/dev/null 2>"$OUT/retry.err" && code=0 || code=$?
if [ "$code" -eq 2 ] && grep -q "already has a valid run" "$OUT/retry.err"; then
  echo "ok    a replicate that is valid is not run again"
else
  echo "FAIL  a replicate that is valid is not run again (exit $code)"; failures=$((failures + 1))
fi

echo "$failures check(s) failed; files in $OUT"
exit "$failures"
