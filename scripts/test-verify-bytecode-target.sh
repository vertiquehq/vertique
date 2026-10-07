#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Regression harness for scripts/verify-bytecode-target.sh.
#
# Each case materializes a synthetic tree of minimal class files (the eight
# header bytes the checker reads) plus a root pom.xml under a temporary
# directory, and runs the checker against it. A case declares the outcome the
# checker is contractually required to produce; the harness fails when the
# observed outcome differs, including when the checker passes a tree it must
# reject.
#
# The real repository is deliberately NOT a case here: CI runs the checker
# against the real build output as its own step, immediately after the build.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
verifier="$script_dir/verify-bytecode-target.sh"

if [[ ! -f "$verifier" ]]; then
    echo "Checker under test is missing: $verifier" >&2
    exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-bytecode-target-tests-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

unexpected_outcomes=0
executed_cases=0
fixture_root=""

# Starts a fixture named $1 whose root pom declares <java.version>$2</java.version>.
fixture_reset() {
    fixture_root="$work_dir/$1"
    mkdir -p "$fixture_root"
    printf '<project>\n  <properties>\n    <java.version>%s</java.version>\n  </properties>\n</project>\n' "$2" \
        > "$fixture_root/pom.xml"
}

# Writes a class-file header with major version $2 at path $1 relative to the
# fixture root: magic 0xCAFEBABE, minor 0, then the major as a big-endian short.
fixture_class() {
    local destination="$fixture_root/$1"
    local major="$2"
    mkdir -p "$(dirname "$destination")"
    printf '\xca\xfe\xba\xbe\x00\x00' > "$destination"
    printf "\\x$(printf '%02x' $((major / 256)))\\x$(printf '%02x' $((major % 256)))" >> "$destination"
}

# Runs the checker on the current fixture and compares it with $2 (pass|fail),
# requiring the combined output of a failing run to contain $3 when given.
run_case() {
    local name="$1" expected="$2" needle="${3:-}" output status=0
    executed_cases=$((executed_cases + 1))
    output="$("$verifier" "$fixture_root" 2>&1)" || status=$?
    local observed="pass"
    (( status == 0 )) || observed="fail"
    if [[ "$observed" != "$expected" ]]; then
        printf 'UNEXPECTED: %s: expected %s, observed %s\n%s\n' "$name" "$expected" "$observed" "$output" >&2
        unexpected_outcomes=$((unexpected_outcomes + 1))
        return
    fi
    if [[ -n "$needle" && "$output" != *"$needle"* ]]; then
        printf 'UNEXPECTED: %s: output lacks "%s"\n%s\n' "$name" "$needle" "$output" >&2
        unexpected_outcomes=$((unexpected_outcomes + 1))
        return
    fi
    printf 'ok: %s (%s)\n' "$name" "$expected"
}

fixture_reset on-target 21
fixture_class "mod-a/target/classes/dev/A.class" 65
fixture_class "mod-b/target/classes/dev/B.class" 65
run_case "every class at the Java 21 major" pass "2 classes checked, 0 off"

fixture_reset newer-class 21
fixture_class "mod-a/target/classes/dev/A.class" 65
fixture_class "mod-b/target/classes/dev/Leaked.class" 69
run_case "one class compiled for a newer release" fail "class-file major 69, expected 65"

fixture_reset older-class 21
fixture_class "mod-a/target/classes/dev/Old.class" 64
run_case "a class compiled for an older release" fail "class-file major 64, expected 65"

fixture_reset no-classes 21
mkdir -p "$fixture_root/mod-a/target/classes"
run_case "no compiled classes at all (fails closed)" fail "vacuous"

fixture_reset test-classes-ignored 21
fixture_class "mod-a/target/classes/dev/A.class" 65
fixture_class "mod-a/target/test-classes/dev/ATest.class" 69
run_case "test classes are out of scope" pass

fixture_reset invoker-fixtures-ignored 21
fixture_class "mod-a/target/classes/dev/A.class" 65
fixture_class "mod-a/target/it/fixture/target/classes/dev/F.class" 69
run_case "invoker fixture builds are out of scope" pass

fixture_reset multi-release-ignored 21
fixture_class "mod-a/target/classes/dev/A.class" 65
fixture_class "mod-a/target/classes/META-INF/versions/25/dev/A.class" 69
run_case "multi-release overlays are out of scope" pass

fixture_reset follows-java-version 17
fixture_class "mod-a/target/classes/dev/A.class" 65
run_case "the expected major follows <java.version>" fail "class-file major 65, expected 61"

fixture_reset no-java-version 21
printf '<project/>\n' > "$fixture_root/pom.xml"
fixture_class "mod-a/target/classes/dev/A.class" 65
run_case "a pom without <java.version> is rejected" fail "Could not read <java.version>"

fixture_reset no-pom 21
rm "$fixture_root/pom.xml"
fixture_class "mod-a/target/classes/dev/A.class" 65
run_case "a root without a pom is rejected" fail "No pom.xml"

if (( unexpected_outcomes > 0 )); then
    printf '%d of %d cases produced an unexpected outcome\n' "$unexpected_outcomes" "$executed_cases" >&2
    exit 1
fi
printf 'All %d cases behaved as required\n' "$executed_cases"
