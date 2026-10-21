#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Runs the Maven wrapper with the given arguments, keeps the full output out of
# the caller's context, and prints a short, machine-checkable verdict.
#
# Why this exists: reading a raw build log, or grepping it ad hoc, has produced
# false results here. A full-reactor log prints several nested "BUILD SUCCESS"
# lines long before the outer build ends, Surefire's .txt summary reports
# "Tests run: 0" for classes whose tests are all in @Nested classes, a selector
# such as -Dtest='A+B' matches nothing and still ends in BUILD SUCCESS, and a
# filesystem search for TEST-*.xml mixes stale reports and other checkouts. This
# script reads only what is reliable: the exit status, the BUILD line after the
# outer "Reactor Summary", and TEST-*.xml reports newer than this run's start.
#
# Usage: build-verdict.sh [--log-dir DIR] [--expect-tests] [--] <Maven arguments>
#
#   --log-dir DIR   write build.log (the full output) under DIR; the default is
#                   a fresh directory under ${TMPDIR:-/tmp}/vertique-build
#   --expect-tests  exit 3 when the build succeeds but executed no tests
#
# Everything after the options is passed to ./mvnw -ntp -B. The exit status is
# Maven's, except exit 3 for --expect-tests. The verdict is printed to stdout:
#
#   VERDICT: PASS | FAIL | SUSPECT
#
# SUSPECT means Maven exited 0 but the run cannot be trusted (no tests executed
# although the arguments did not skip them, or no final BUILD line was found).
#
# Environment (used by scripts/test-build-verdict.sh): BUILD_VERDICT_ROOT is the
# repository root to run in (default: the script's parent directory), and
# BUILD_VERDICT_MVN is the Maven command (default: ./mvnw).

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="${BUILD_VERDICT_ROOT:-$script_dir/..}"
mvn_cmd="${BUILD_VERDICT_MVN:-./mvnw}"
log_dir=""
expect_tests=0

while (( $# > 0 )); do
    case "$1" in
        --log-dir) log_dir="${2:?--log-dir needs a directory}"; shift 2 ;;
        --expect-tests) expect_tests=1; shift ;;
        --) shift; break ;;
        *) break ;;
    esac
done

if (( $# == 0 )); then
    echo "Usage: $0 [--log-dir DIR] [--expect-tests] [--] <Maven arguments>" >&2
    exit 2
fi

root="$(cd "$root" && pwd)"
if [[ -z "$log_dir" ]]; then
    mkdir -p "${TMPDIR:-/tmp}/vertique-build"
    log_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-build/run.XXXXXX")"
else
    mkdir -p "$log_dir"
fi
log="$log_dir/build.log"
marker="$log_dir/start.marker"
: > "$marker"
# Reports are matched by mtime against the marker; keep the marker strictly older
# than anything this run writes even on filesystems with one-second resolution.
sleep 1

start_epoch="$(date +%s)"
(cd "$root" && "$mvn_cmd" -ntp -B "$@") > "$log" 2>&1
rc=$?
end_epoch="$(date +%s)"

# The outer reactor prints "Reactor Summary" once; nested builds do not. Read the
# BUILD line after the last one, or the last BUILD line when there is none.
last_summary="$(grep -n 'Reactor Summary' "$log" | tail -1 | cut -d: -f1)"
if [[ -n "$last_summary" ]]; then
    build_line="$(tail -n +"$last_summary" "$log" | grep -E 'BUILD (SUCCESS|FAILURE)' | tail -1)"
    summary_lines="$(tail -n +"$last_summary" "$log" | grep -E 'SKIPPED|FAILURE' | grep -v 'BUILD FAILURE' | head -15)"
else
    build_line="$(grep -E 'BUILD (SUCCESS|FAILURE)' "$log" | tail -1)"
    summary_lines=""
fi
build_result="none"
[[ "$build_line" == *"BUILD SUCCESS"* ]] && build_result="SUCCESS"
[[ "$build_line" == *"BUILD FAILURE"* ]] && build_result="FAILURE"

# Test totals from reports written during this run only. Worktree and agent
# directories are pruned so another checkout's reports are never counted.
tests=0; failures=0; errors=0; skipped=0; failing_files=""
while IFS= read -r report; do
    [[ -z "$report" ]] && continue
    tag="$(grep -m1 -o '<testsuite [^>]*>' "$report" || true)"
    t="$(sed -n 's/.* tests="\([0-9]*\)".*/\1/p' <<<"$tag")"
    f="$(sed -n 's/.* failures="\([0-9]*\)".*/\1/p' <<<"$tag")"
    e="$(sed -n 's/.* errors="\([0-9]*\)".*/\1/p' <<<"$tag")"
    s="$(sed -n 's/.* skipped="\([0-9]*\)".*/\1/p' <<<"$tag")"
    tests=$(( tests + ${t:-0} )); failures=$(( failures + ${f:-0} ))
    errors=$(( errors + ${e:-0} )); skipped=$(( skipped + ${s:-0} ))
    if (( ${f:-0} + ${e:-0} > 0 )); then
        failing_files+="${report#"$root"/}"$'\n'
    fi
done < <(cd "$root" && find . \( -name .git -o -name .worktrees -o -name .claude -o -name node_modules \) -prune -o \
    -type f -name 'TEST-*.xml' \( -path '*/surefire-reports/*' -o -path '*/failsafe-reports/*' \) \
    -newer "$marker" -print 2>/dev/null | sed "s#^\./#$root/#")

skips_tests=0
for a in "$@"; do
    case "$a" in -DskipTests|-DskipTests=true|-Dmaven.test.skip=true|-DskipITs) skips_tests=1 ;; esac
done

verdict="PASS"
reason=""
if (( rc != 0 )) || [[ "$build_result" == "FAILURE" ]]; then
    verdict="FAIL"
elif [[ "$build_result" == "none" ]]; then
    verdict="SUSPECT"; reason="no final BUILD line found in the log"
elif (( tests == 0 && skips_tests == 0 )); then
    verdict="SUSPECT"; reason="no test report was written during this run (check -Dtest selectors and @Nested classes)"
elif (( failures + errors > 0 )); then
    verdict="FAIL"
fi

head_sha="$(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo unknown)"
dirty="$(git -C "$root" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"

echo "VERDICT: $verdict${reason:+ ($reason)}"
echo "exit status: $rc"
echo "final build: $build_result"
echo "tests (this run, from reports): $tests run, $failures failures, $errors errors, $skipped skipped"
echo "duration: $(( end_epoch - start_epoch )) s"
echo "source: $head_sha, $dirty uncommitted path(s)"
echo "command: $mvn_cmd -ntp -B $*"
echo "log: $log"
if [[ -n "$summary_lines" ]]; then
    echo "reactor (skipped or failed modules):"
    sed 's/^\[INFO\] /  /' <<<"$summary_lines"
fi
if [[ -n "$failing_files" ]]; then
    echo "reports with failures or errors:"
    head -15 <<<"$failing_files" | sed 's/^/  /'
fi
if [[ "$verdict" == "FAIL" ]] && (( failures + errors == 0 )); then
    echo "first errors:"
    grep -E '^\[ERROR\]' "$log" | grep -v -E '^\[ERROR\] *$|Help [0-9]|-> \[Help' | head -10 | cut -c1-220 | sed 's/^/  /'
fi

if (( rc != 0 )); then
    exit "$rc"
fi
if (( expect_tests == 1 )) && (( tests == 0 )); then
    exit 3
fi
exit 0
