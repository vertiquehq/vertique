#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Reads a finished run and the database it left, without changing either, and writes the series that show whether the
# settlements per second fall as the due depth grows or only come in waves, what the database spent per instance, and which
# statements dominate.
#
#   scripts/diagnostics/capacity.sh <run-dir> <output-dir>
#
# It issues only SELECT and plain EXPLAIN (GENERIC_PLAN), which does not run the statement. It does not run ANALYZE, EXPLAIN
# ANALYZE or any command that changes a setting, and the database container must be the one the run used.
set -euo pipefail

RUN=${1:?run directory}
OUT=${2:?output directory}
CONTAINER=${WF_PG_CONTAINER:-wf017-pg}
DB=$(jq -r '.resolvedSpecification.database.name' "$RUN/manifest.json")
mkdir -p "$OUT"
q() { docker exec -i "$CONTAINER" psql -U postgres -d "$DB" -v ON_ERROR_STOP=1 -At -F, "$@"; }

T0=$(q -c "select extract(epoch from min(recorded_at)) from workflow_commands where kind = 'START'")
echo "t0 (first start recorded) = $T0" | tee "$OUT/t0.txt"

# Per second since the first start: commands recorded and settled by kind, instances completed.
q -c "
with s as (select generate_series(0, (select ceil(extract(epoch from max(coalesce(settled_at, recorded_at))) - $T0)::int from workflow_commands)) as s),
rec as (select floor(extract(epoch from recorded_at) - $T0)::int as s, kind, count(*) c from workflow_commands group by 1, 2),
set as (select floor(extract(epoch from settled_at) - $T0)::int as s, kind, count(*) c from workflow_commands
        where state = 'COMPLETE' and settled_at is not null group by 1, 2),
cmp as (select floor(extract(epoch from completed_at) - $T0)::int as s, count(*) c from workflow_instances
        where status = 'COMPLETED' group by 1)
select s.s,
  coalesce((select c from rec where rec.s = s.s and kind = 'START'), 0),
  coalesce((select c from rec where rec.s = s.s and kind = 'SIGNAL'), 0),
  coalesce((select c from rec where rec.s = s.s and kind = 'TIMER'), 0),
  coalesce((select c from set where set.s = s.s and kind = 'START'), 0),
  coalesce((select c from set where set.s = s.s and kind = 'SIGNAL'), 0),
  coalesce((select c from set where set.s = s.s and kind = 'TIMER'), 0),
  coalesce((select c from cmp where cmp.s = s.s), 0)
from s order by 1" > "$OUT/db-series.raw"
{ echo "sec,recorded_start,recorded_signal,recorded_timer,settled_start,settled_signal,settled_timer,completed_instances"; cat "$OUT/db-series.raw"; } > "$OUT/db-series.csv"

# Join with the collector's depth and transactions and the container's CPU, on the database clock.
awk -F, -v t0="$T0" '
  BEGIN { OFS = "," }
  FILENAME == ARGV[1] { if (FNR > 1) { sec = $1 + 0; rec[sec] = $2 + $3 + $4; set[sec] = $5 + $6 + $7; comp[sec] = $8; if (sec > maxs) maxs = sec } next }
  FILENAME == ARGV[2] { if (FNR > 1 && $3 == "true") { s = int($5 / 1000 - t0); due[s] = $7; xc[s] = $11 } next }
  FILENAME == ARGV[3] { if (FNR > 1) { s = int($1 / 1000 - t0); cpu[s] = $7 } next }
  END {
    print "sec,recorded_commands,settled_commands,completed_instances,due_depth,xact_per_s,pg_cpu_pct_of_one_core"
    prev = ""
    for (s = 0; s <= maxs; s++) {
      d = (s in xc && (s - 1) in xc) ? xc[s] - xc[s - 1] : ""
      print s, rec[s] + 0, set[s] + 0, comp[s] + 0, (s in due ? due[s] : ""), d, (s in cpu ? cpu[s] : "")
    }
  }' "$OUT/db-series.csv" "$RUN/collector/seconds.csv" "$RUN/collector/vm.csv" > "$OUT/series.csv"

# Five-second buckets, for reading.
awk -F, 'NR > 1 { b = int($1 / 5) * 5; r[b] += $2; s[b] += $3; c[b] += $4; if ($5 != "") { dsum[b] += $5; dn[b]++ }
                  if ($6 != "") { x[b] += $6; xn[b]++ } if ($7 != "") { p[b] += $7; pn[b]++ } if (b > m) m = b }
      END { print "from_s,recorded/s,settled/s,completed/s,mean_due_depth,xact/s,pg_cpu_pct_of_one_core";
            for (b = 0; b <= m; b += 5) printf "%d,%.1f,%.1f,%.1f,%.0f,%.0f,%.0f\n", b, r[b] / 5, s[b] / 5, c[b] / 5, dn[b] ? dsum[b] / dn[b] : 0, xn[b] ? x[b] / xn[b] : 0, pn[b] ? p[b] / pn[b] : 0 }' \
  "$OUT/series.csv" > "$OUT/series-5s.csv"

q -c "select 'instances', status, count(*) from workflow_instances group by 2 order by 2" \
  -c "select 'commands', kind || '/' || state, count(*) from workflow_commands group by 2 order by 2" > "$OUT/final-state.csv"

# Table statistics as the database holds them now, and when they last changed.
q -c "select relname, n_live_tup, n_dead_tup, n_mod_since_analyze, analyze_count, autoanalyze_count, last_analyze, last_autoanalyze,
             vacuum_count, autovacuum_count, last_autovacuum from pg_stat_user_tables
      where relname in ('workflow_commands','workflow_instances','outbox','workflow_command_queues','workflow_history') order by 1" > "$OUT/table-statistics.csv"

if [ -f "$RUN/sql/statements.json" ]; then
  jq -r '.byTotalTime[] | [.calls, (.totalExecMs | floor), (.meanExecMs * 1000 | floor / 1000), .rows, (.query | gsub("[\n\r]+"; " ") | .[0:160])] | @csv' \
    "$RUN/sql/statements.json" > "$OUT/statements-top.csv"
  # Plain EXPLAIN of the statements that matter, as the database would plan them for any parameter. Not run.
  : > "$OUT/explain.txt"
  jq -r '.byTotalTime[0:12][] | select(.query | test("^(SELECT|WITH|UPDATE|INSERT)")) | select(.query | test("pg_stat|pg_locks|clock_timestamp|pg_notification|pg_settings|pg_database|pg_trigger|pg_class") | not) | .query | gsub("[\n\r]+"; " ")' \
    "$RUN/sql/statements.json" | while IFS= read -r statement; do
      echo "-- $statement" >> "$OUT/explain.txt"
      docker exec -i "$CONTAINER" psql -U postgres -d "$DB" -At -c "EXPLAIN (GENERIC_PLAN) $statement" >> "$OUT/explain.txt" 2>&1 || true
      echo >> "$OUT/explain.txt"
    done
fi
echo "written to $OUT"
