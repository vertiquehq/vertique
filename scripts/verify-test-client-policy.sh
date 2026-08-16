#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Proves that test sources honor the HTTP client policy. Two families of
# CI-only flake motivated it, and each maps to one rule below.
#
# --- Rule 1: a client must not be created into a chain --------------------
#
# `vertx.createHttpClient().request(...)` discards the client reference into a
# method chain, so nothing can ever close it. The pools then survive until the
# owning Vertx is closed, and closing Vertx while requests are still in flight
# throws `VertxException: Pool closed` (issue #330).
#
# Detection keys on the chain, not on the absence of an assignment. An earlier
# revision flagged any creation on a line carrying no `=`, which was wrong in
# both directions: it failed a formatter-wrapped `this.client =\n
# WebClient.create(...)` and a perfectly closable `clients.add(vertx.
# createHttpClient())`, while passing `if (x == y) use(vertx.createHttpClient())`.
# Chaining is the unambiguous defect: the value is consumed by the call that
# follows it and never bound anywhere.
#
# Passing a fresh client as an argument is NOT flagged. The callee may well
# bind and close it, and this checker cannot see across that boundary.
#
# --- Rule 2: no raw-client send() ------------------------------------------
#
# `HttpClientResponse` discards body buffers that arrive before a body handler
# is attached. `request(...).compose(req -> req.send()).compose(resp -> resp.body())`
# attaches the read after the send begins, so under load the whole response can
# arrive unobserved and `body()` completes successfully with zero bytes while
# the status is correct (issue #167).
#
# The repo-wide answer is `io.vertx.ext.web.client.WebClient`, which aggregates
# the body before its future resolves. A test that genuinely needs the raw
# client must use the pre-attach idiom: attach the whole continuation to
# `response()` BEFORE `end()`, never `send()`.
#
# Scope is any test source that references a raw client type — by explicit
# import, by wildcard import, or fully qualified. Matching only the exact
# `import io.vertx.core.http.HttpClient;` line was evadable two ways, and the
# second is the realistic one: this repo's own exempt files import
# `HttpClientResponse` without importing `HttpClient`, so a future test that
# copies that idiom, obtains its client from a helper, and later regresses to
# `send()` would have had zero gate coverage.
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
# Exactly one file may use the unsafe idiom: the tripwire asserting the hazard
# still exists, so a Vert.x upgrade that fixes the drop behaviour is detected
# rather than silently relied upon.
#
# The entry is a dual lock — exact repo-relative path AND the exact number of
# permitted occurrences. Occurrences are counted per match, not per line: an
# earlier revision counted matching lines, so two `send(` calls sharing one
# line slipped through the lock it advertised.
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
# "<repo-relative path>|<exact permitted send( occurrence count>|<reason>"
# Keep this minimal. A new entry needs a reason a reviewer would accept without
# reading the file.
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
# Blanks out block comments, line comments, string literals, text blocks and
# character literals while preserving the line count, so reported line numbers
# stay true. This is not incidental: the policy is explained in javadoc on the
# very files it governs, so scanning raw text would flag the prose describing
# the rule it enforces.
#
# Text blocks matter because archetype template tests under
# src/main/resources/archetype-resources/**/src/test/java are in scope, and
# embedded Java source is exactly where a `"""` block appears. Character
# literals matter for the narrower `'"'` case, which would otherwise open a
# string that swallows the rest of the line.
strip_noise() {
    awk '
        BEGIN { in_block = 0; in_text_block = 0 }
        {
            line = $0
            out = ""
            i = 1
            n = length(line)
            while (i <= n) {
                three = substr(line, i, 3)
                two = substr(line, i, 2)
                ch = substr(line, i, 1)

                if (in_text_block) {
                    if (three == "\"\"\"") { in_text_block = 0; i += 3 } else { i++ }
                    continue
                }
                if (in_block) {
                    if (two == "*/") { in_block = 0; i += 2 } else { i++ }
                    continue
                }
                if (three == "\"\"\"") { in_text_block = 1; i += 3; continue }
                if (two == "/*") { in_block = 1; i += 2; continue }
                if (two == "//") { break }

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
                if (ch == "'\''") {
                    i++
                    while (i <= n) {
                        c = substr(line, i, 1)
                        if (c == "\\") { i += 2; continue }
                        if (c == "'\''") { i++; break }
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

# --- Rule 1 detection ------------------------------------------------------
#
# Slurps the stripped source so a chain broken across lines is still seen as
# one expression, finds each client creation, balances parentheses to its end,
# then reports it only when the next non-whitespace character is a `.`.
chained_creations() {
    awk '
        {
            content = content $0 "\n"
        }
        END {
            n = length(content)
            start = 1
            while (1) {
                best = 0
                # Earliest creation call at or after "start".
                split("createHttpClient(,createWebSocketClient(,WebClient.create(", needles, ",")
                for (k in needles) {
                    p = index(substr(content, start), needles[k])
                    if (p > 0) {
                        abs = start + p - 1
                        if (best == 0 || abs < best) {
                            best = abs
                            best_len = length(needles[k])
                        }
                    }
                }
                if (best == 0) { break }

                # Balance parentheses from the opening paren of the creation call.
                i = best + best_len - 1
                depth = 0
                while (i <= n) {
                    c = substr(content, i, 1)
                    if (c == "(") { depth++ }
                    else if (c == ")") {
                        depth--
                        if (depth == 0) { break }
                    }
                    i++
                }

                # Next non-whitespace character after the call decides it.
                j = i + 1
                while (j <= n && substr(content, j, 1) ~ /[ \t\r\n]/) { j++ }
                if (substr(content, j, 1) == ".") {
                    prefix = substr(content, 1, best)
                    line_number = split(prefix, tmp, "\n")
                    print line_number
                }
                start = best + best_len
            }
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

    # --- Rule 1: client created straight into a chain --------------------
    while IFS= read -r line_number; do
        [[ -z "$line_number" ]] && continue
        report_failure "$relative:$line_number: client created directly into a call chain, so the reference is discarded and it can never be closed (issue #330). Bind it to a field or variable and close it before the owning Vertx."
    done < <(chained_creations "$stripped")

    # --- Rule 2: raw-client send() ---------------------------------------
    #
    # Any reference to a raw client type puts the file in scope: explicit
    # import of HttpClient/HttpClientRequest/HttpClientResponse, a wildcard
    # import of the package, or fully-qualified use.
    if grep -qE '^[[:space:]]*import[[:space:]]+io\.vertx\.core\.http\.(HttpClient|HttpClientRequest|HttpClientResponse|\*)[[:space:]]*;' "$stripped" \
        || grep -qE '\bio\.vertx\.core\.http\.HttpClient(Request|Response)?\b' "$stripped"; then
        # `|| true` is load-bearing: grep exits 1 when nothing matches, and
        # under `set -o pipefail` that would abort the whole scan silently —
        # reporting a clean tree by dying before reaching any later file.
        send_count="$({ grep -oE '\.send\(|::send' "$stripped" || true; } | wc -l | tr -d '[:space:]')"
        if (( send_count > 0 )); then
            if permitted="$(allowlisted_count_for "$relative")"; then
                if (( send_count != permitted )); then
                    report_failure "$relative: allowlisted for exactly $permitted raw send( occurrence(s) but found $send_count. The allowlist is a dual lock — if this new occurrence is deliberate, update the count in scripts/verify-test-client-policy.sh and say why; otherwise use the pre-attach idiom (attach the response() continuation before end())."
                fi
            else
                while IFS=: read -r line_number _; do
                    [[ -z "$line_number" ]] && continue
                    printf 'FAIL: %s:%s: raw HttpClient send() attaches the body read after the send begins, so the response can arrive unobserved and body() yields zero bytes with a correct status (issue #167). Use WebClient, or the pre-attach idiom for an exempt file.\n' "$relative" "$line_number" >&2
                done < <(grep -nE '\.send\(|::send' "$stripped")
                failures=$((failures + send_count))
            fi
        fi
    fi
done < "$work_dir/test-sources.txt"

if (( failures > 0 )); then
    printf '\n%d violation(s) of the test HTTP client policy.\n' "$failures" >&2
    printf 'See .claude/rules/testing.md in the governance repository, section "HTTP clients in tests: WebClient by default".\n' >&2
    exit 1
fi

printf 'PASS: %d test source files honor the HTTP client policy.\n' "$scanned"
