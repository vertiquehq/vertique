#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Regression harness for scripts/verify-test-bind-policy.sh.
#
# Each case materializes a synthetic source tree of *.java files under a
# temporary directory and invokes the checker against that fixture root. A case
# declares the outcome the checker is contractually required to produce; the
# harness fails when the observed outcome differs — including when the checker
# misses a malformed fixture or flags a compliant one.
#
# The real repository is deliberately NOT a case here: CI runs the checker
# against the real tree as its own step immediately after this harness, so a
# real-tree violation surfaces there without masking the harness verdict.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
verifier="$script_dir/verify-test-bind-policy.sh"

if [[ ! -f "$verifier" ]]; then
    echo "Checker under test is missing: $verifier" >&2
    exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-test-bind-policy-tests-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

unexpected_outcomes=0
executed_cases=0

# --- Fixture construction ---

fixture_root=""

# Starts a new fixture named $1 under the harness work directory.
fixture_reset() {
    fixture_root="$work_dir/$1"
    mkdir -p "$fixture_root"
}

# Writes a fixture source file at path $1 relative to the fixture root, reading
# its content from standard input.
fixture_write_source() {
    local destination="$fixture_root/$1"
    mkdir -p "$(dirname "$destination")"
    cat > "$destination"
}

# Every case includes this compliant baseline so a fail case proves the checker
# flagged the one malformed file rather than everything it saw, and so a pass
# case scans a representative compliant shape of each pattern.
fixture_write_compliant_baseline() {
    fixture_write_source "vertique-alpha/src/test/java/PinnedListenTest.java" <<'JAVA'
class PinnedListenTest {
    void boot(io.vertx.core.Vertx vertx) {
        vertx.createHttpServer().requestHandler(r -> {}).listen(0, "127.0.0.1");
    }
}
JAVA

    fixture_write_source "vertique-alpha/src/test/java/PinnedWireMockTest.java" <<'JAVA'
class PinnedWireMockTest {
    static final Object wm = wireMockConfig().dynamicPort().bindAddress("127.0.0.1");
}
JAVA

    fixture_write_source "vertique-alpha/src/test/java/PinnedJsonPortTest.java" <<'JAVA'
class PinnedJsonPortTest {
    void config() {
        new JsonObject().put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"));
    }
}
JAVA

    fixture_write_source "vertique-alpha/src/test/java/PinnedJsonPortAdjacentLineTest.java" <<'JAVA'
class PinnedJsonPortAdjacentLineTest {
    void config() {
        new JsonObject()
                .put("port", 0)
                .put("host", "127.0.0.1");
    }
}
JAVA

    fixture_write_source "vertique-alpha/src/test/java/PinnedBuilderPortTest.java" <<'JAVA'
class PinnedBuilderPortTest {
    void config() {
        ManagementConfig.builder()
                .port(0)
                .host("127.0.0.1")
                .build();
    }
}
JAVA

    # The MultipartPartCountLimitIT shape: an options-based setPort(0) bind whose
    # .host( pin sits many lines away in the same file. Pattern 4 is file-level,
    # so this must pass; a windowed check would false-positive here.
    fixture_write_source "vertique-alpha/src/test/java/PinnedFarHostOptionsTest.java" <<'JAVA'
class PinnedFarHostOptionsTest {
    void boot(io.vertx.core.Vertx vertx) {
        HttpConfig httpConfig = HttpConfig.builder()
                .host("127.0.0.1")
                .maxFormAttributeSize(8192)
                .maxHeaderSize(8192)
                .compressionSupported(false)
                .decompressionSupported(false)
                .idleTimeoutSeconds(30)
                .build();
        vertx.createHttpServer(httpConfig.toHttpServerOptions().setPort(0))
                .requestHandler(r -> {})
                .listen();
    }
}
JAVA

    # The HttpVerticleTest shape: setPort(0) pinned through setHost( on the same
    # options chain. setHost( does not match the .host( regex, so pattern 4 must
    # recognize it as a pin in its own right.
    fixture_write_source "vertique-alpha/src/test/java/PinnedSetHostOptionsTest.java" <<'JAVA'
class PinnedSetHostOptionsTest {
    void boot(io.vertx.core.Vertx vertx) {
        vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0))
                .requestHandler(r -> {})
                .listen();
    }
}
JAVA
}

# --- Case execution ---

print_indented_output() {
    local line
    while IFS= read -r line; do
        [[ -n "$line" ]] && printf '        | %s\n' "$line"
    done <<< "$1"
    return 0
}

# $1 = case name, $2 = expected outcome (pass|fail), $3 = scan root,
# $4 = substring the checker's diagnostics must contain (required for fail cases),
# $5 = substring the checker's diagnostics must never contain (optional).
# Asserting the diagnostic keeps a case from passing for an incidental reason;
# the forbidden substring pins a value that must not reach any diagnostic at
# all, which a positive assertion alone cannot prove.
run_case() {
    local case_name="$1"
    local expected_outcome="$2"
    local target_root="$3"
    local expected_diagnostic="${4:-}"
    local forbidden_diagnostic="${5:-}"
    local output
    local status=0
    local observed_outcome

    executed_cases=$((executed_cases + 1))
    if output="$("$verifier" "$target_root" 2>&1)"; then
        observed_outcome=pass
    else
        status=$?
        observed_outcome=fail
    fi

    if [[ "$observed_outcome" != "$expected_outcome" ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-38s expected checker to %s, but it %sed (exit %d)\n' \
            "$case_name" "$expected_outcome" "$observed_outcome" "$status"
        print_indented_output "$output"
        return 0
    fi

    if [[ -n "$expected_diagnostic" && "$output" != *"$expected_diagnostic"* ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-38s checker %sed but never reported %s\n' \
            "$case_name" "$observed_outcome" "$expected_diagnostic"
        print_indented_output "$output"
        return 0
    fi

    if [[ -n "$forbidden_diagnostic" && "$output" == *"$forbidden_diagnostic"* ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-38s checker %sed but surfaced %s\n' \
            "$case_name" "$observed_outcome" "$forbidden_diagnostic"
        print_indented_output "$output"
        return 0
    fi

    if [[ "$observed_outcome" == fail ]]; then
        printf 'PASS  %-38s rejected: %s\n' "$case_name" \
            "$(printf '%s\n' "$output" | sed -n 's/^FAIL: //p' | head -1)"
    else
        printf 'PASS  %-38s accepted: %s\n' "$case_name" \
            "$(printf '%s\n' "$output" | head -1)"
    fi
    return 0
}

# --- Cases ---

# A tree containing only compliant shapes of every pattern must pass, and the
# summary must count all seven baseline files.
fixture_reset compliant-tree
fixture_write_compliant_baseline
run_case "compliant-tree" pass "$fixture_root" \
    "PASS: 7 test source files honor the loopback bind policy"

# Pattern 1: a bare .listen(0) with no host argument must be flagged.
fixture_reset bare-listen
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/test/java/BareListenTest.java" <<'JAVA'
class BareListenTest {
    void boot(io.vertx.core.Vertx vertx) {
        vertx.createHttpServer().requestHandler(r -> {}).listen(0).map(HttpServer::actualPort);
    }
}
JAVA
run_case "bare-listen" fail "$fixture_root" \
    "BareListenTest.java line 3 binds .listen(0) with no host" \
    "PinnedListenTest.java"

# Pattern 2: wireMockConfig() in a file with no bindAddress( anywhere must be
# flagged as a file-level violation.
fixture_reset wiremock-unpinned
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/test/java/UnpinnedWireMockTest.java" <<'JAVA'
class UnpinnedWireMockTest {
    static final Object wm = wireMockConfig().dynamicPort();
}
JAVA
run_case "wiremock-unpinned" fail "$fixture_root" \
    "UnpinnedWireMockTest.java calls wireMockConfig() but never bindAddress(" \
    "PinnedWireMockTest.java"

# Pattern 3: .put("port", 0) with no .put("host", ...) in the five-line window
# must be flagged.
fixture_reset json-port-unpinned
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/test/java/UnpinnedJsonPortTest.java" <<'JAVA'
class UnpinnedJsonPortTest {
    void config() {
        new JsonObject().put("http", new JsonObject().put("port", 0));
    }
}
JAVA
run_case "json-port-unpinned" fail "$fixture_root" \
    'UnpinnedJsonPortTest.java line 3 sets .put("port", 0) with no .put("host", ...)' \
    "PinnedJsonPortTest.java"

# The window is exactly two lines on either side: a host pinned three lines
# after the port is outside it and must be flagged. This pins the heuristic's
# boundary so a silent widening cannot pass unnoticed.
fixture_reset json-port-host-outside-window
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/test/java/HostOutsideWindowTest.java" <<'JAVA'
class HostOutsideWindowTest {
    void config() {
        new JsonObject()
                .put("port", 0)
                .put("path", "/health")
                .put("enabled", true)
                .put("host", "127.0.0.1");
    }
}
JAVA
run_case "json-port-host-outside-window" fail "$fixture_root" \
    'HostOutsideWindowTest.java line 4 sets .put("port", 0)'

# Pattern 3, builder form: .port(0) with no .host(...) in the window must be
# flagged.
fixture_reset builder-port-unpinned
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/test/java/UnpinnedBuilderPortTest.java" <<'JAVA'
class UnpinnedBuilderPortTest {
    void config() {
        ManagementConfig config = ManagementConfig.builder().port(0).build();
    }
}
JAVA
run_case "builder-port-unpinned" fail "$fixture_root" \
    "UnpinnedBuilderPortTest.java line 3 sets .port(0) with no .host(...)" \
    "PinnedBuilderPortTest.java"

# Pattern 4: an options-based setPort(0) bind in a file with no .host(,
# setHost(, or bindAddress( anywhere must be flagged as a file-level violation.
# The forbidden diagnostic proves the compliant far-host baseline file is not
# swept up by the file-level check.
fixture_reset options-port-unpinned
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/test/java/UnpinnedOptionsPortTest.java" <<'JAVA'
class UnpinnedOptionsPortTest {
    void boot(io.vertx.core.Vertx vertx) {
        vertx.createHttpServer(new HttpServerOptions().setPort(0))
                .requestHandler(r -> {})
                .listen();
    }
}
JAVA
run_case "options-port-unpinned" fail "$fixture_root" \
    "UnpinnedOptionsPortTest.java calls setPort(0) but never .host( or setHost(" \
    "PinnedFarHostOptionsTest.java"

# Pattern 4 does not accept WireMock's bindAddress( as an options-server pin:
# a file whose WireMock is correctly pinned but whose setPort(0) options server
# has no .host(/setHost( pin must still be flagged.
fixture_reset options-port-wiremock-pinned
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/test/java/WireMockPinnedOptionsUnpinnedTest.java" <<'JAVA'
class WireMockPinnedOptionsUnpinnedTest {
    static final Object wm = wireMockConfig().dynamicPort().bindAddress("127.0.0.1");

    void boot(io.vertx.core.Vertx vertx) {
        vertx.createHttpServer(new HttpServerOptions().setPort(0))
                .requestHandler(r -> {})
                .listen();
    }
}
JAVA
run_case "options-port-wiremock-pinned" fail "$fixture_root" \
    "WireMockPinnedOptionsUnpinnedTest.java calls setPort(0) but never .host( or setHost(" \
    "PinnedFarHostOptionsTest.java"

# Pattern 5: a wildcard literal in setHost( position must be flagged even
# though the file pins a host (pattern 4 is satisfied — by the wildcard).
fixture_reset wildcard-sethost
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/test/java/WildcardSetHostTest.java" <<'JAVA'
class WildcardSetHostTest {
    void boot(io.vertx.core.Vertx vertx) {
        vertx.createHttpServer(new HttpServerOptions().setHost("0.0.0.0").setPort(0))
                .requestHandler(r -> {})
                .listen();
    }
}
JAVA
run_case "wildcard-sethost" fail "$fixture_root" \
    'WildcardSetHostTest.java line 3 sets the wildcard interface "0.0.0.0" in setter position'

# Pattern 5, JSON form: .put("host", "0.0.0.0") must be flagged even though it
# satisfies pattern 3's host-in-window requirement.
fixture_reset wildcard-json-host
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/test/java/WildcardJsonHostTest.java" <<'JAVA'
class WildcardJsonHostTest {
    void config() {
        new JsonObject().put("http", new JsonObject().put("port", 0).put("host", "0.0.0.0"));
    }
}
JAVA
run_case "wildcard-json-host" fail "$fixture_root" \
    'WildcardJsonHostTest.java line 3 sets the wildcard interface "0.0.0.0" in setter position'

# Pattern 5, builder form: .host("0.0.0.0") must be flagged even though it
# satisfies pattern 3's host-in-window requirement — by the wildcard.
fixture_reset wildcard-builder-host
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/test/java/WildcardBuilderHostTest.java" <<'JAVA'
class WildcardBuilderHostTest {
    void config() {
        ManagementConfig.builder().port(0).host("0.0.0.0").build();
    }
}
JAVA
run_case "wildcard-builder-host" fail "$fixture_root" \
    'WildcardBuilderHostTest.java line 3 sets the wildcard interface "0.0.0.0" in setter position'

# Pattern 5, WireMock form: bindAddress("0.0.0.0") must be flagged even though
# it satisfies pattern 2's bindAddress( requirement — by the wildcard.
fixture_reset wildcard-bind-address
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/test/java/WildcardBindAddressTest.java" <<'JAVA'
class WildcardBindAddressTest {
    static final Object wm = wireMockConfig().dynamicPort().bindAddress("0.0.0.0");
}
JAVA
run_case "wildcard-bind-address" fail "$fixture_root" \
    'WildcardBindAddressTest.java line 2 sets the wildcard interface "0.0.0.0" in setter position'

# Pattern 5, listen form: a .listen(...) call carrying the wildcard literal
# must be flagged. .listen(0, "0.0.0.0") has a host argument, so pattern 1
# never matches it — only the pattern-5 listen alternative catches it.
fixture_reset wildcard-listen
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/test/java/WildcardListenTest.java" <<'JAVA'
class WildcardListenTest {
    void boot(io.vertx.core.Vertx vertx) {
        vertx.createHttpServer().requestHandler(r -> {}).listen(0, "0.0.0.0");
    }
}
JAVA
run_case "wildcard-listen" fail "$fixture_root" \
    'WildcardListenTest.java line 3 sets the wildcard interface "0.0.0.0" in setter position' \
    "PinnedListenTest.java"

# Pattern 5 anchors on setter-call shapes: a getter assertion that mentions
# "0.0.0.0" in argument position (assertEquals) must not be flagged.
fixture_reset getter-assertion-compliant
fixture_write_compliant_baseline
fixture_write_source "vertique-alpha/src/test/java/WildcardGetterAssertionTest.java" <<'JAVA'
class WildcardGetterAssertionTest {
    void verifyDefault(ManagementConfig config) {
        assertEquals("0.0.0.0", config.host());
    }
}
JAVA
run_case "getter-assertion-compliant" pass "$fixture_root" \
    "PASS: 8 test source files honor the loopback bind policy" \
    "WildcardGetterAssertionTest.java"

# Archetype template tests live under src/main/resources/archetype-resources
# and must be scanned like any other test source: a violation there must be
# flagged even though the path runs through src/main/resources.
fixture_reset archetype-template-unpinned
fixture_write_compliant_baseline
fixture_write_source \
    "vertique-archetype/vertique-archetype-rest/src/main/resources/archetype-resources/src/test/java/ApplicationIT.java" <<'JAVA'
class ApplicationIT {
    void config() {
        new JsonObject().put("http", new JsonObject().put("port", 0));
    }
}
JAVA
run_case "archetype-template-unpinned" fail "$fixture_root" \
    'archetype-resources/src/test/java/ApplicationIT.java line 3 sets .put("port", 0)'

# Build output is not repository source: a violation under a target/ directory
# must not be flagged, so the tree still passes and the file reaches no
# diagnostic at all.
fixture_reset target-output-ignored
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/target/generated-test-sources/src/test/java/GeneratedListenTest.java" <<'JAVA'
class GeneratedListenTest {
    void boot(io.vertx.core.Vertx vertx) {
        vertx.createHttpServer().listen(0);
    }
}
JAVA
run_case "target-output-ignored" pass "$fixture_root" \
    "PASS: 7 test source files honor the loopback bind policy" \
    "GeneratedListenTest.java"

# Production sources are out of scope: the same violation under src/main/java
# must not be flagged.
fixture_reset main-source-ignored
fixture_write_compliant_baseline
fixture_write_source "vertique-beta/src/main/java/ProductionServer.java" <<'JAVA'
class ProductionServer {
    void boot(io.vertx.core.Vertx vertx) {
        vertx.createHttpServer().listen(0);
    }
}
JAVA
run_case "main-source-ignored" pass "$fixture_root" \
    "PASS: 7 test source files honor the loopback bind policy" \
    "ProductionServer.java"

# A root with no test sources at all is a mis-pointed scan, not a clean tree.
fixture_reset no-test-sources
mkdir -p "$fixture_root/vertique-alpha/src/main/java"
run_case "no-test-sources" fail "$fixture_root" \
    "No test sources found under"

# --- Summary ---

if (( unexpected_outcomes > 0 )); then
    printf '\n%d of %d checker cases produced an unexpected outcome.\n' \
        "$unexpected_outcomes" "$executed_cases" >&2
    exit 1
fi

printf '\nAll %d checker cases produced the expected outcome.\n' "$executed_cases"
