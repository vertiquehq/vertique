#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Regression harness for scripts/build-verdict.sh.
#
# Each case runs the verdict script against a fake Maven command inside a
# temporary repository root and checks the verdict, the exit status, and the
# counted tests. The cases encode the traps the script exists to avoid: nested
# BUILD SUCCESS lines, a run that executed no tests, stale reports from an
# earlier run, and reports from another checkout.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
verdict_script="$script_dir/build-verdict.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/build-verdict-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

failures=0
fake_mvn="$work/fake-mvn.sh"
cat > "$fake_mvn" <<'EOF'
#!/usr/bin/env bash
# Fake Maven: behavior chosen by FAKE_CASE; writes reports under the current directory.
report() { # <name> <tests> <failures> <errors>
    mkdir -p mod/target/surefire-reports
    printf '<testsuite name="%s" tests="%s" errors="%s" skipped="0" failures="%s"></testsuite>\n' \
        "$1" "$2" "$4" "$3" > "mod/target/surefire-reports/TEST-$1.xml"
}
nested() { echo "[INFO] BUILD SUCCESS"; echo "[INFO] Total time:  1.0 s (nested)"; }
case "$FAKE_CASE" in
    pass)        nested; report Alpha 5 0 0
                 printf '[INFO] Reactor Summary for demo:\n[INFO] demo ... SUCCESS\n[INFO] BUILD SUCCESS\n'; exit 0 ;;
    nested-fail) nested; report Alpha 2 0 0
                 printf '[INFO] Reactor Summary for demo:\n[INFO] mod ... FAILURE\n[INFO] other ... SKIPPED\n[INFO] BUILD FAILURE\n[ERROR] Failed to execute goal x\n'; exit 1 ;;
    no-tests)    printf '[INFO] Reactor Summary for demo:\n[INFO] BUILD SUCCESS\n'; exit 0 ;;
    test-fail)   report Alpha 4 2 0
                 printf '[INFO] Reactor Summary for demo:\n[INFO] BUILD FAILURE\n[ERROR] Tests run: 4, Failures: 2\n'; exit 1 ;;
    other-checkout) mkdir -p .worktrees/other/mod/target/surefire-reports
                 printf '<testsuite name="B" tests="9" errors="0" skipped="0" failures="0"></testsuite>\n' \
                     > .worktrees/other/mod/target/surefire-reports/TEST-B.xml
                 printf '[INFO] Reactor Summary for demo:\n[INFO] BUILD SUCCESS\n'; exit 0 ;;
    no-build-line) echo "partial output"; exit 0 ;;
esac
EOF
chmod +x "$fake_mvn"

# run_case <name> <fake-case> <expected-verdict> <expected-exit> <expected-tests> [stale-report] [verdict args...]
run_case() {
    local name="$1" fake="$2" want_verdict="$3" want_rc="$4" want_tests="$5" stale="$6"; shift 6
    local root="$work/$name"
    mkdir -p "$root"
    if [[ "$stale" == "stale" ]]; then
        mkdir -p "$root/mod/target/surefire-reports"
        printf '<testsuite name="Old" tests="7" errors="0" skipped="0" failures="3"></testsuite>\n' \
            > "$root/mod/target/surefire-reports/TEST-Old.xml"
        touch -t 202001010000 "$root/mod/target/surefire-reports/TEST-Old.xml"
    fi
    local out rc
    out="$(BUILD_VERDICT_ROOT="$root" BUILD_VERDICT_MVN="$fake_mvn" FAKE_CASE="$fake" \
        "$verdict_script" --log-dir "$work/$name-log" "$@" verify 2>&1)"
    rc=$?
    local ok=1
    grep -q "^VERDICT: $want_verdict" <<<"$out" || ok=0
    (( rc == want_rc )) || ok=0
    grep -q "^tests (this run, from reports): $want_tests run" <<<"$out" || ok=0
    if (( ok )); then
        echo "PASS: $name"
    else
        echo "FAIL: $name (want $want_verdict, exit $want_rc, $want_tests tests; got exit $rc)"
        sed 's/^/    /' <<<"$out"
        failures=$(( failures + 1 ))
    fi
}

run_case pass            pass           PASS    0 5 none
run_case nested-fail     nested-fail    FAIL    1 2 none
run_case no-tests        no-tests       SUSPECT 0 0 none
run_case no-tests-strict no-tests       SUSPECT 3 0 none --expect-tests
run_case skip-tests      no-tests       PASS    0 0 none -DskipTests
run_case test-fail       test-fail      FAIL    1 4 none
run_case stale-report    no-tests       SUSPECT 0 0 stale
run_case other-checkout  other-checkout SUSPECT 0 0 none
run_case no-build-line   no-build-line  SUSPECT 0 0 none

# Nested BUILD SUCCESS before an outer failure must not read as success.
if grep -q "final build: FAILURE" <(BUILD_VERDICT_ROOT="$work/nested-fail" BUILD_VERDICT_MVN="$fake_mvn" \
        FAKE_CASE=nested-fail "$verdict_script" --log-dir "$work/nested-fail-2" verify 2>&1); then
    echo "PASS: nested success lines are ignored"
else
    echo "FAIL: nested success lines are ignored"; failures=$(( failures + 1 ))
fi

if (( failures > 0 )); then
    echo "$failures case(s) failed" >&2
    exit 1
fi
echo "All cases passed."
