#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Proves that test sources honor the HTTP client policy. Two families of
# CI-only flake motivated it, and each maps to one rule below.
#
# --- Rule 1: every client must be bound ------------------------------------
#
# A client created inline — `vertx.createHttpClient().request(...)` — leaves no
# reference behind, so nothing can ever close it. This is not a style
# preference: an unbound client is unclosable by construction. The pools then
# survive until the owning Vertx is closed, and closing Vertx while requests are
# still in flight throws `VertxException: Pool closed` (issue #330).
#
# Detection is an invariant, not a heuristic: a creation call on a line carrying
# no `=` cannot have been assigned to anything. `client = vertx.createHttpClient()`
# passes; `return vertx.createHttpClient()` does not.
#
# --- Rule 2: no raw-client send() ------------------------------------------
#
# `HttpClientResponse` discards body buffers that arrive before a body handler
# is attached. `request(...).compose(req -> req.send()).compose(resp -> resp.body())`
# attaches the read after the send begins, so under load the whole response can
# arrive unobserved and `body()` completes successfully with zero bytes while
# the status is correct (issue #167).
#
# The repo-wide answer is to use `io.vertx.ext.web.client.WebClient`, which
# aggregates the body before its future resolves and cannot hit this. A test
# that genuinely needs the raw client — wire-level control, mid-stream failure,
# observing the raw client's own instrumentation, SSE-consumer
# representativeness — must use the pre-attach idiom instead: attach the whole
# continuation to `response()` BEFORE `end()`, never `send()`.
#
# So rule 2 fires on any `send(` in a file importing `io.vertx.core.http.HttpClient`.
# WebClient's own `send()` is not in scope, because a migrated file does not
# import the raw client.
#
# --- What rule 2 deliberately does NOT prove -------------------------------
#
# Absence of `send()` is not proof of correct ordering. A file could write
#
#     Future<HttpClientResponse> r = request.response();
#     request.end();
#     ... later ... r.compose(resp -> resp.body())
#
# and pass this gate while recreating the race. That gap is why the DEFAULT
# moved to WebClient rather than relying on this script: the gate is a
# backstop against the common spelling, not a proof of the invariant.
#
# --- The allowlist ---------------------------------------------------------
#
# Exactly one file is permitted to use the unsafe idiom: the tripwire that
# asserts the hazard still exists, so a Vert.x upgrade that fixes the drop
# behaviour is detected rather than silently relied upon.
#
# The entry is a dual lock — exact repo-relative path AND the exact number of
# permitted occurrences. Adding a second `send(` to an allowlisted file fails,
# so the exemption cannot become a hiding place. Copying the file elsewhere
# fails too, since the path will not match.
#
# Usage: verify-test-client-policy.sh [repository-root]
# The root defaults to the script's parent directory; an explicit root exists so
# scripts/test-verify-test-client-policy.sh can drive synthetic fixtures.

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

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-test-client-policy-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

failures=0

report_failure() {
    printf 'FAIL: %s\n' "$1" >&2
    failures=$((failures + 1))
}

# --- Allowlist -------------------------------------------------------------
#
# "<repo-relative path>|<exact permitted send( count>|<reason>"
# Keep this list minimal. A new entry needs a reason a reviewer would accept
# without reading the file.
allowlist=(
    "vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/HttpClientBodyReadRaceIT.java|1|pins the hazard: asserts Vert.x still drops pre-handler body data, so an upgrade that fixes it is detected"
)

allowlisted_count_for() {
    local path="$1" entry
    for entry in "${allowlist[@]}"; do
        if [[ "${entry%%|*}" == "$path" ]]; then
            local rest="${entry#*|}"
            printf '%s' "${rest%%|*}"
            return 0
        fi
    done
    return 1
}

# --- Noise stripping -------------------------------------------------------
#
# Blanks out block comments, line comments and string literals while preserving
# the line count, so reported line numbers stay true and a javadoc sentence
# mentioning send() or createHttpClient() never trips a rule. Javadoc is where
# this policy is explained, so scanning raw text would flag the documentation
# describing the rule it violates.
strip_noise() {
    awk '
        BEGIN { in_block = 0 }
        {
            line = $0
            out = ""
            i = 1
            n = length(line)
            while (i <= n) {
                two = substr(line, i, 2)
                if (in_block) {
                    if (two == "*/") { in_block = 0; i += 2 } else { i++ }
                    continue
                }
                if (two == "/*") { in_block = 1; i += 2; continue }
                if (two == "//") { break }
                ch = substr(line, i, 1)
                if (ch == "\"") {
                    i++
                    while (i <= n) {
                        c = substr(line, i, 1)
                        if (c == "\\") { i += 2; continue }
                        if (c == "\"") { i++; break }
                        i++
                    }
                    continue
                }
                out = out ch
                i++
            }
            print out
        }
    ' "$1"
}

# --- Test source enumeration ----------------------------------------------
#
# Mirrors verify-test-bind-policy.sh: build output, VCS metadata, agent scratch
# space and node_modules are not repository source. Archetype template tests
# under src/main/resources/archetype-resources/**/src/test/java stay in scope.
find "$repository_root" \
    \( -name target -o -name .git -o -name .claude -o -name node_modules \) -prune -o \
    -type f -name '*.java' -path '*/src/test/*' -print \
    | LC_ALL=C sort > "$work_dir/test-sources.txt"

if [[ ! -s "$work_dir/test-sources.txt" ]]; then
    echo "No test sources found under $repository_root" >&2
    exit 1
fi

scanned=0

while IFS= read -r file; do
    relative="${file#"$repository_root"/}"
    stripped="$work_dir/stripped.java"
    strip_noise "$file" > "$stripped"
    scanned=$((scanned + 1))

    # --- Rule 1: unbound client creation ---------------------------------
    #
    # `=` anywhere on the line means the value was bound to something. Its
    # absence means the creation call's result is discarded into a chain.
    while IFS=: read -r line_number content; do
        [[ -z "$line_number" ]] && continue
        if [[ "$content" != *"="* ]]; then
            report_failure "$relative:$line_number: client created inline and never bound, so it can never be closed (issue #330). Assign it to a field or variable and close it before the owning Vertx: ${content#"${content%%[![:space:]]*}"}"
        fi
    done < <(grep -nE '\.(createHttpClient|createWebSocketClient)\(|WebClient\.create\(' "$stripped" || true)

    # --- Rule 2: raw-client send() ---------------------------------------
    if grep -q '^[[:space:]]*import[[:space:]]\+io\.vertx\.core\.http\.HttpClient[[:space:]]*;' "$stripped"; then
        send_count="$(grep -cE '\.send\(|::send' "$stripped" || true)"
        if (( send_count > 0 )); then
            if permitted="$(allowlisted_count_for "$relative")"; then
                if (( send_count != permitted )); then
                    report_failure "$relative: allowlisted for exactly $permitted raw send( occurrence(s) but found $send_count. The allowlist is a dual lock — if this new occurrence is deliberate, update the count in scripts/verify-test-client-policy.sh and say why; otherwise use the pre-attach idiom (attach the response() continuation before end())."
                fi
            else
                grep -nE '\.send\(|::send' "$stripped" | while IFS=: read -r line_number _; do
                    printf 'FAIL: %s:%s: raw HttpClient send() attaches the body read after the send begins, so the response can arrive unobserved and body() yields zero bytes with a correct status (issue #167). Use WebClient, or the pre-attach idiom for an exempt file.\n' "$relative" "$line_number" >&2
                done
                failures=$((failures + send_count))
            fi
        fi
    fi
done < "$work_dir/test-sources.txt"

if (( failures > 0 )); then
    printf '\n%d violation(s) of the test HTTP client policy.\n' "$failures" >&2
    printf 'See .claude/rules/testing.md in the governance repository, section "HTTP clients in tests".\n' >&2
    exit 1
fi

printf 'PASS: %d test source files honor the HTTP client policy.\n' "$scanned"
