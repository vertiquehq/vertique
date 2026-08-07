#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Proves that test sources honor the loopback bind policy: a test that opens a
# listening socket must pin it to an explicit host (127.0.0.1) instead of the
# wildcard interface. Wildcard test binds trigger macOS firewall prompts, expose
# ephemeral test servers to the network, and have produced CI-only flakes.
#
# Five violation patterns are scanned, all heuristic and tuned so that a false
# positive never occurs on a compliant tree while a false negative merely leaves
# one bind for review to catch:
#
#   1. A bare `.listen(0)` call with no second argument. Two-argument forms such
#      as `.listen(0, "127.0.0.1")` pass.
#   2. A file that calls `wireMockConfig()` but nowhere calls `bindAddress(`.
#      WireMock binds the wildcard interface unless bindAddress pins it, and the
#      bind address is configured once per file, so this is a file-level check.
#   3. A config chain that selects a dynamic port without pinning a host:
#        - `.put("port", 0)` where neither the same line nor the two lines on
#          either side contain `.put("host"`, and
#        - `.port(0)` (ManagementConfig.builder()-style chains) where the same
#          five-line window contains no `.host(`.
#      The two-line window is a deliberate heuristic: formatted builder chains
#      put the host call on the same or an adjacent line, so a host further away
#      is treated as absent. False negatives are acceptable here — a chain that
#      pins its host three lines away simply goes unflagged.
#   4. A file that calls `setPort(0)` (HttpServerOptions-style dynamic-port
#      selection) but nowhere calls `.host(` or `setHost(`. WireMock's
#      `bindAddress(` is not accepted as a pin here: it cannot pin a Vert.x
#      options-based server, and pattern 2 owns the WireMock pairing.
#      Like pattern 2 this is a file-level check, not a windowed one: an
#      options-based bind may build its config far from the bind site (in
#      MultipartPartCountLimitIT the `.host(` pin sits 12 lines from the
#      `setPort(0)` call), so a window would false-positive on compliant files.
#   5. A wildcard literal in setter or bind-call position anywhere in a test
#      source: `.host("0.0.0.0")`, `.setHost("0.0.0.0")`,
#      `.put("host", "0.0.0.0")`, `bindAddress("0.0.0.0")`, or any
#      `.listen(...)` call carrying the literal among its arguments, e.g.
#      `.listen(0, "0.0.0.0")` (whitespace variance around the arguments is
#      allowed). The regexes anchor on the setter- and bind-call shapes, so a
#      getter assertion such as `assertEquals("0.0.0.0", config.host())` never
#      matches — the literal sits in assertEquals' argument position, not a
#      setter or bind call.
#      This closes the narrow accidental copy-paste case; the general
#      value-aware check (a wildcard reaching a bind through a variable or
#      constant) remains deferred to vertiquehq/vertique-dev#170.
#
# Scope: every *.java file under a src/test directory, build output (target/)
# excluded. Archetype template tests live at
# src/main/resources/archetype-resources/**/src/test/java and must comply like
# any other test source; their embedded src/test path segment keeps them in
# scope, and the self-test pins that inclusion with a fixture.
#
# Known scope exemption: test-support servers that live under src/main — e.g.
# examples/vertique-example-rest-client's MockServerVerticle — are production
# sources to this scan and are never visited. Their bind pinning relies on
# code review, not on this checker.
#
# Usage: verify-test-bind-policy.sh [repository-root]
# The repository root defaults to the script's parent directory; an explicit
# root exists so scripts/test-verify-test-bind-policy.sh can drive synthetic
# fixtures.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if (( $# > 1 )); then
    echo "Usage: $0 [repository-root]" >&2
    exit 2
fi

repository_root_input="${1:-$script_dir/..}"
if [[ ! -d "$repository_root_input" ]]; then
    echo "Repository root does not exist: $repository_root_input" >&2
    exit 1
fi
repository_root="$(cd "$repository_root_input" && pwd)"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-test-bind-policy-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

failures=0

report_failure() {
    printf 'FAIL: %s\n' "$1" >&2
    failures=$((failures + 1))
}

# --- Violation patterns ---

# Pattern 1: `.listen(0)` closing immediately after the port, so no host
# argument can follow. `.listen(0, "127.0.0.1")` carries a comma before the
# closing parenthesis and never matches.
bare_listen_pattern='\.listen\([[:space:]]*0[[:space:]]*\)'

# Pattern 5: a wildcard literal in setter or bind-call position. The
# alternation anchors on the setter- and bind-call shapes — `.host(`,
# `.setHost(`, `bindAddress(`, `.put("host", ...)`, and a `.listen(...)` call
# carrying the literal among its arguments (e.g. `.listen(0, "0.0.0.0")`) — so
# a getter assertion that merely mentions "0.0.0.0"
# (e.g. assertEquals("0.0.0.0", config.host())) never matches.
wildcard_setter_pattern='(\.host|\.setHost|bindAddress)\([[:space:]]*"0\.0\.0\.0"[[:space:]]*\)|\.put\([[:space:]]*"host"[[:space:]]*,[[:space:]]*"0\.0\.0\.0"[[:space:]]*\)|\.listen\([^)]*"0\.0\.0\.0"'

# Pattern 3 lives inside unpinned_port_selectors below: dynamic-port selectors
# whose host must be pinned in the same five-line window (the line itself plus
# two lines on either side). The regexes are written directly in the awk
# program, because `awk -v` reinterprets backslash escapes and would silently
# corrupt a pattern handed over as a shell variable.

# --- Test source enumeration ---

# Build output is pruned; agent scratch space and VCS metadata are not
# repository source. Everything else under a src/test directory is in scope,
# which deliberately includes archetype template tests under
# src/main/resources/archetype-resources/**/src/test/java.
find "$repository_root" \
    \( -name target -o -name .git -o -name .claude -o -name node_modules \) -prune -o \
    -type f -name '*.java' -path '*/src/test/*' -print \
    | LC_ALL=C sort > "$work_dir/test-sources.txt"

if [[ ! -s "$work_dir/test-sources.txt" ]]; then
    echo "No test sources found under $repository_root" >&2
    exit 1
fi

# --- Per-file scan ---

# Prints one "line-number<TAB>kind<TAB>line" record per unpinned dynamic-port
# selector in the file at $1, where kind is "json" or "builder". The whole file
# is buffered so the window can look behind and ahead of the flagged line.
unpinned_port_selectors() {
    awk '
        function window_has(center, pattern,   scan, low, high) {
            low = center - 2 < 1 ? 1 : center - 2
            high = center + 2 > NR ? NR : center + 2
            for (scan = low; scan <= high; scan++) {
                if (line[scan] ~ pattern) { return 1 }
            }
            return 0
        }

        { line[NR] = $0 }

        END {
            json_host = "\\.put\\(\"host\""
            builder_host = "\\.host\\("
            for (current = 1; current <= NR; current++) {
                if (line[current] ~ /\.put\("port",[[:space:]]*0\)/ && !window_has(current, json_host)) {
                    print current "\tjson\t" line[current]
                }
                if (line[current] ~ /\.port\(0\)/ && !window_has(current, builder_host)) {
                    print current "\tbuilder\t" line[current]
                }
            }
        }
    ' "$1"
}

# Strips leading and trailing whitespace so a diagnostic quotes the statement,
# not its indentation.
trim() {
    local value="$1"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "$value"
}

scanned_files=0

while IFS= read -r source_file; do
    [[ -n "$source_file" ]] || continue
    scanned_files=$((scanned_files + 1))
    relative_path="${source_file#"$repository_root"/}"

    # Pattern 1: every bare .listen(0) call site is reported.
    while IFS= read -r match; do
        [[ -n "$match" ]] || continue
        report_failure "$relative_path line ${match%%:*} binds .listen(0) with no host; pin the loopback interface, e.g. .listen(0, \"127.0.0.1\"): $(trim "${match#*:}")"
    done < <(grep -nE "$bare_listen_pattern" "$source_file" || true)

    # Pattern 2: a WireMock server configured anywhere in the file must have its
    # bind address pinned somewhere in the same file.
    if grep -q 'wireMockConfig()' "$source_file" && ! grep -q 'bindAddress(' "$source_file"; then
        report_failure "$relative_path calls wireMockConfig() but never bindAddress(; pin the WireMock bind with .bindAddress(\"127.0.0.1\")"
    fi

    # Pattern 4: an options-based dynamic-port selection anywhere in the file
    # must have its host pinned somewhere in the same file. File-level, like
    # pattern 2: the pin may legitimately sit far from the bind site. WireMock's
    # bindAddress( does not count as a pin — it cannot pin a Vert.x
    # options-based server (pattern 2 owns the WireMock pairing).
    if grep -q 'setPort(0)' "$source_file" \
            && ! grep -q '\.host(' "$source_file" \
            && ! grep -q 'setHost(' "$source_file"; then
        report_failure "$relative_path calls setPort(0) but never .host( or setHost(; pin the loopback interface, e.g. .setHost(\"127.0.0.1\")"
    fi

    # Pattern 5: a setter-position wildcard literal is a violation anywhere,
    # regardless of what else the file pins.
    while IFS= read -r match; do
        [[ -n "$match" ]] || continue
        report_failure "$relative_path line ${match%%:*} sets the wildcard interface \"0.0.0.0\" in setter position; bind the loopback interface \"127.0.0.1\" instead: $(trim "${match#*:}")"
    done < <(grep -nE "$wildcard_setter_pattern" "$source_file" || true)

    # Pattern 3: dynamic-port selectors with no pinned host in the window.
    while IFS=$'\t' read -r line_number kind offending_line; do
        [[ -n "$line_number" ]] || continue
        if [[ "$kind" == json ]]; then
            report_failure "$relative_path line $line_number sets .put(\"port\", 0) with no .put(\"host\", ...) on the same or the two adjacent lines; pin .put(\"host\", \"127.0.0.1\"): $(trim "$offending_line")"
        else
            report_failure "$relative_path line $line_number sets .port(0) with no .host(...) on the same or the two adjacent lines; pin .host(\"127.0.0.1\"): $(trim "$offending_line")"
        fi
    done < <(unpinned_port_selectors "$source_file")
done < "$work_dir/test-sources.txt"

# --- Verdict ---

if (( failures > 0 )); then
    printf '\n%d test bind-policy violation(s) found.\n' "$failures" >&2
    exit 1
fi

printf 'PASS: %d test source files honor the loopback bind policy\n' "$scanned_files"
