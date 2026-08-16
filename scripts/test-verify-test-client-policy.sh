#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Self-test for verify-test-client-policy.sh.
#
# The checker is a CI gate, so a silent regression in it is worse than no gate
# at all: the tree would look compliant while the flake families it exists to
# stop crept back. Every case below pins one behaviour the checker must keep —
# both what it catches and, just as importantly, what it must NOT flag.
#
# Each case builds a synthetic repository root containing one fixture under
# src/test/java and drives the checker against it with an explicit root.
#
# Usage: test-verify-test-client-policy.sh

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/verify-test-client-policy.sh"

if [[ ! -x "$checker" && ! -f "$checker" ]]; then
    echo "Checker not found: $checker" >&2
    exit 1
fi

work_root="$(mktemp -d "${TMPDIR:-/tmp}/vertique-test-client-policy-selftest-XXXXXXXX")"
trap 'rm -rf "$work_root"' EXIT

cases_run=0
cases_failed=0

# Builds a fixture repository root. $1 = case name, $2 = repo-relative java path,
# $3 = file content. Echoes the root.
make_fixture() {
    local name="$1" relative_path="$2" content="$3"
    local root="$work_root/$name"
    mkdir -p "$root/$(dirname "$relative_path")"
    printf '%s\n' "$content" > "$root/$relative_path"
    printf '%s' "$root"
}

# $1 = case name, $2 = expected exit status (0 pass / 1 fail), $3 = root,
# $4 = optional substring the output must contain.
expect() {
    local name="$1" expected_status="$2" root="$3" expected_substring="${4:-}"
    local output status
    cases_run=$((cases_run + 1))
    set +e
    output="$(bash "$checker" "$root" 2>&1)"
    status=$?
    set -e

    if (( status != expected_status )); then
        printf 'FAIL [%s]: expected exit %d, got %d\n%s\n\n' "$name" "$expected_status" "$status" "$output" >&2
        cases_failed=$((cases_failed + 1))
        return
    fi
    if [[ -n "$expected_substring" && "$output" != *"$expected_substring"* ]]; then
        printf 'FAIL [%s]: output did not contain %q\n%s\n\n' "$name" "$expected_substring" "$output" >&2
        cases_failed=$((cases_failed + 1))
        return
    fi
    printf 'ok   %s\n' "$name"
}

std_path="src/test/java/dev/vertique/example/SampleIT.java"
tripwire_path="vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/HttpClientBodyReadRaceIT.java"

# --- Rule 1: unbound client creation --------------------------------------

expect "rule1-unbound-httpclient-is-rejected" 1 \
    "$(make_fixture rule1a "$std_path" '
public class SampleIT {
    void exercise() {
        return vertx.createHttpClient().request(GET, port, "127.0.0.1", "/");
    }
}')" \
    "call chain"

expect "rule1-bound-httpclient-is-accepted" 0 \
    "$(make_fixture rule1b "$std_path" '
public class SampleIT {
    void exercise() {
        client = vertx.createHttpClient();
    }
}')"

expect "rule1-unbound-webclient-create-is-rejected" 1 \
    "$(make_fixture rule1c "$std_path" '
public class SampleIT {
    void exercise() {
        return WebClient.create(vertx).get(port, "127.0.0.1", "/").send();
    }
}')" \
    "call chain"

expect "rule1-unbound-websocket-client-is-rejected" 1 \
    "$(make_fixture rule1d "$std_path" '
public class SampleIT {
    void exercise() {
        return vertx.createWebSocketClient().connect(options);
    }
}')" \
    "call chain"

expect "rule1-declaration-with-type-is-accepted" 0 \
    "$(make_fixture rule1e "$std_path" '
public class SampleIT {
    void exercise() {
        WebClient client = WebClient.create(vertx);
    }
}')"

# --- Rule 2: raw-client send() --------------------------------------------

expect "rule2-raw-send-is-rejected" 1 \
    "$(make_fixture rule2a "$std_path" '
import io.vertx.core.http.HttpClient;
public class SampleIT {
    void exercise() {
        client.request(GET, port, "127.0.0.1", "/").compose(req -> req.send());
    }
}')" \
    "issue #167"

expect "rule2-raw-method-reference-send-is-rejected" 1 \
    "$(make_fixture rule2b "$std_path" '
import io.vertx.core.http.HttpClient;
public class SampleIT {
    void exercise() {
        client.request(options).compose(HttpClientRequest::send);
    }
}')" \
    "issue #167"

# The pre-attach idiom is the sanctioned raw usage: continuation on response()
# before end(), never send(). It must not be flagged.
expect "rule2-preattach-idiom-is-accepted" 0 \
    "$(make_fixture rule2c "$std_path" '
import io.vertx.core.http.HttpClient;
public class SampleIT {
    void exercise() {
        HttpClientRequest request = req;
        Future<HttpResult> result = request.response().compose(response -> response.body());
        request.end();
    }
}')"

# WebClient has its own send(). A migrated file does not import the raw client,
# so rule 2 must not reach it — otherwise the whole migration would be flagged.
expect "rule2-webclient-send-is-out-of-scope" 0 \
    "$(make_fixture rule2d "$std_path" '
import io.vertx.ext.web.client.WebClient;
public class SampleIT {
    void exercise() {
        client = WebClient.create(vertx);
        client.get(port, "127.0.0.1", "/").send().map(response -> response.statusCode());
    }
}')"

# --- Noise stripping -------------------------------------------------------
#
# This policy is explained in javadoc on the very files it governs, so a checker
# that scanned raw text would flag the prose describing the rule.

expect "javadoc-mentioning-send-is-not-flagged" 0 \
    "$(make_fixture noise1 "$std_path" '
import io.vertx.core.http.HttpClient;
/**
 * Attach the continuation to response() before end() rather than req.send(),
 * because a body read attached after .send( is in the race window.
 */
public class SampleIT {
    void exercise() {
        HttpClientRequest request = req;
        request.end();
    }
}')"

expect "line-comment-mentioning-send-is-not-flagged" 0 \
    "$(make_fixture noise2 "$std_path" '
import io.vertx.core.http.HttpClient;
public class SampleIT {
    void exercise() {
        // Deliberately avoids req.send() here; see HttpClientBodyReadRaceIT.
        request.end();
    }
}')"

expect "string-literal-mentioning-send-is-not-flagged" 0 \
    "$(make_fixture noise3 "$std_path" '
import io.vertx.core.http.HttpClient;
public class SampleIT {
    void exercise() {
        String hint = "use .send( only in the tripwire";
        request.end();
    }
}')"

expect "javadoc-mentioning-createHttpClient-is-not-flagged" 0 \
    "$(make_fixture noise4 "$std_path" '
/**
 * Never write vertx.createHttpClient().request(...) — an unbound client
 * cannot be closed.
 */
public class SampleIT {
}')"

# --- The mixed RestAssured / raw-client fixture ----------------------------
#
# One file in the tree mixes RestAssured with the raw client, and its
# RestAssured chains end in .extract().response() — a false friend for any
# response()-based heuristic. Pinned so a future rewrite of the checker that
# keys on response() cannot silently mis-handle it.
expect "mixed-restassured-and-raw-client" 1 \
    "$(make_fixture mixed "$std_path" '
import io.vertx.core.http.HttpClient;
public class SampleIT {
    void restAssuredPart() {
        given().when().get("/jobs").then().extract().response();
    }
    void rawPart() {
        client.request(GET, port, "127.0.0.1", "/events").compose(req -> req.send());
    }
}')" \
    "issue #167"

# --- Allowlist dual lock ---------------------------------------------------

expect "allowlisted-tripwire-at-exact-count-passes" 0 \
    "$(make_fixture allow1 "$tripwire_path" '
import io.vertx.core.http.HttpClient;
public class HttpClientBodyReadRaceIT {
    void lateAttachedBodyReadLosesTheBody() {
        await(client.request(GET, port, "127.0.0.1", "/").compose(HttpClientRequest::send));
    }
}')"

# A second occurrence in the allowlisted file must fail: the exemption is for
# one specific tripwire, not a licence for the file.
expect "allowlisted-file-with-extra-send-fails" 1 \
    "$(make_fixture allow2 "$tripwire_path" '
import io.vertx.core.http.HttpClient;
public class HttpClientBodyReadRaceIT {
    void lateAttachedBodyReadLosesTheBody() {
        await(client.request(GET, port, "127.0.0.1", "/").compose(HttpClientRequest::send));
    }
    void sneakedIn() {
        client.request(GET, port, "127.0.0.1", "/other").compose(req -> req.send());
    }
}')" \
    "dual lock"

# Copying the tripwire elsewhere must not inherit its exemption.
expect "tripwire-copied-to-another-path-is-not-exempt" 1 \
    "$(make_fixture allow3 "src/test/java/dev/vertique/copied/HttpClientBodyReadRaceIT.java" '
import io.vertx.core.http.HttpClient;
public class HttpClientBodyReadRaceIT {
    void exercise() {
        await(client.request(GET, port, "127.0.0.1", "/").compose(HttpClientRequest::send));
    }
}')" \
    "issue #167"

# --- Scope -----------------------------------------------------------------

# A production source carrying both violations must be ignored entirely. The
# fixture also holds one benign test source, because a root with no test
# sources at all is a different condition the checker reports separately.
scope_root="$(make_fixture scope1 "src/main/java/dev/vertique/example/Production.java" '
import io.vertx.core.http.HttpClient;
public class Production {
    void exercise() {
        return vertx.createHttpClient().request(GET, port, "127.0.0.1", "/").compose(req -> req.send());
    }
}')"
mkdir -p "$scope_root/src/test/java/dev/vertique/example"
printf '%s\n' 'public class BenignTest {}' > "$scope_root/src/test/java/dev/vertique/example/BenignTest.java"
expect "main-sources-are-out-of-scope" 0 "$scope_root"

# An empty root is a setup mistake, not a compliant tree — the checker must say
# so rather than silently reporting success.
empty_root="$work_root/empty"
mkdir -p "$empty_root"
expect "root-with-no-test-sources-is-an-error" 1 "$empty_root" "No test sources found"

# --- Rule 2 scope: the spellings that used to evade it ---------------------
#
# An earlier revision matched only the literal `import io.vertx.core.http.HttpClient;`
# line. Each case below passed that revision while carrying the racy idiom.

expect "rule2-wildcard-import-is-in-scope" 1 \
    "$(make_fixture scope_wild "$std_path" '
import io.vertx.core.http.*;
public class SampleIT {
    void exercise() {
        client.request(GET, port, "127.0.0.1", "/").compose(req -> req.send());
    }
}')" \
    "issue #167"

# The realistic evasion: this repo'"'"'s own exempt files import HttpClientResponse
# WITHOUT importing HttpClient, so a future test copying that idiom and later
# regressing to send() would have had no gate coverage at all.
expect "rule2-response-only-import-is-in-scope" 1 \
    "$(make_fixture scope_resp "$std_path" '
import io.vertx.core.http.HttpClientResponse;
public class SampleIT {
    void exercise() {
        client.request(GET, port, "127.0.0.1", "/").compose(req -> req.send());
    }
}')" \
    "issue #167"

expect "rule2-fully-qualified-use-is-in-scope" 1 \
    "$(make_fixture scope_fqn "$std_path" '
public class SampleIT {
    void exercise() {
        io.vertx.core.http.HttpClient c = vertx.createHttpClient();
        c.request(GET, port, "127.0.0.1", "/").compose(req -> req.send());
    }
}')" \
    "issue #167"

# --- Allowlist counts occurrences, not lines -------------------------------
#
# An earlier revision used `grep -c`, which counts matching LINES, so two
# send( calls sharing one line satisfied a lock advertised as counting
# occurrences.
expect "allowlist-counts-occurrences-not-lines" 1 \
    "$(make_fixture allow_sameline "$tripwire_path" '
import io.vertx.core.http.HttpClient;
public class HttpClientBodyReadRaceIT {
    void exercise() {
        { a.compose(req -> req.send()); b.compose(req -> req.send()); }
    }
}')" \
    "dual lock"

# --- Rule 1 must not false-positive on compliant spellings -----------------
#
# An earlier revision keyed on the absence of `=` on the creation line, which
# failed both of these even though the client is bound and closable.

expect "rule1-formatter-wrapped-assignment-is-accepted" 0 \
    "$(make_fixture rule1_wrap "$std_path" '
public class SampleIT {
    void exercise() {
        this.client =
                WebClient.create(vertx, new WebClientOptions().setDefaultHost("127.0.0.1"));
    }
}')"

expect "rule1-collection-binding-is-accepted" 0 \
    "$(make_fixture rule1_coll "$std_path" '
public class SampleIT {
    void exercise() {
        clients.add(vertx.createHttpClient());
    }
}')"

# Documented non-detection: the callee may bind and close it, and this checker
# cannot see across that boundary. Pinned so the limit is deliberate.
expect "rule1-creation-passed-as-argument-is-not-flagged" 0 \
    "$(make_fixture rule1_arg "$std_path" '
public class SampleIT {
    void exercise() {
        register(vertx.createHttpClient());
    }
}')"

# --- Stripper edge cases ---------------------------------------------------
#
# Archetype template tests embed Java source in text blocks, and they are in
# scope. An earlier revision had no `"""` handling, so every line of a block
# was scanned as executable code.
expect "text-block-contents-are-not-scanned" 0 \
    "$(make_fixture strip_text "$std_path" '
import io.vertx.core.http.HttpClient;
public class SampleIT {
    String template = """
        return vertx.createHttpClient().request(GET);
        req.send();
        """;
    void exercise() {
        request.end();
    }
}')"

# A char literal holding a double quote used to open a string that swallowed
# the rest of the line, hiding a real violation after it. Built with a
# double-quoted shell string so the Java char literal '"' survives intact.
char_literal_fixture="
import io.vertx.core.http.HttpClient;
public class SampleIT {
    void exercise() {
        if (c == '\"') { client.request(o).compose(req -> req.send()); }
    }
}"
expect "char-literal-quote-does-not-hide-a-violation" 1 \
    "$(make_fixture strip_char "$std_path" "$char_literal_fixture")" \
    "issue #167"

# --- Summary ---------------------------------------------------------------

printf '\n'
if (( cases_failed > 0 )); then
    printf '%d of %d self-test case(s) failed.\n' "$cases_failed" "$cases_run" >&2
    exit 1
fi
printf 'PASS: all %d self-test cases hold.\n' "$cases_run"
