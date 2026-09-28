#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Checks that this machine has what the full build (./mvnw clean verify) and its
# integration tests launch, so a missing or misconfigured tool surfaces in
# seconds instead of partway through the build:
#
#   Java     A JDK at pom.xml's <java.version> or later. The launcher is
#            resolved exactly as Maven resolves it ($JAVA_HOME/bin/java, else
#            java on PATH) and then asked which JAVA_HOME its own process sees.
#            Nested Maven builds (the maven-invoker integration tests) inherit
#            that value, and a version-manager launcher can export one that does
#            not exist even while the shell's JAVA_HOME is empty; the top-level
#            build then runs fine while every nested build exits before
#            compiling.
#   Go       The version the MCP Go interop fixture's go.mod requires
#            (McpGoClientInteropIT runs `go run`).
#   Node.js  Node 22 or later plus npm: the MCP TypeScript interop and
#            conformance tests run node, and their fixtures run `npm ci`.
#   Docker   A reachable daemon for the Testcontainers-based integration tests.
#
# Every check inspects the effective tool, never a particular installer or
# version manager, so it applies however each tool was installed.
#
# Usage: scripts/doctor.sh
# Exit status: 0 when nothing blocks the build (warnings allowed), 1 when at
# least one check failed, 2 on a usage error.

set -euo pipefail

usage() {
    printf '%s\n' \
        "Usage: scripts/doctor.sh" \
        "" \
        "Checks the tools ./mvnw clean verify needs: a JDK, Go, Node.js with npm," \
        "and a reachable Docker daemon. Exits 0 when nothing blocks the build and" \
        "1 when a check failed."
}

case "${1-}" in
    "") ;;
    -h | --help)
        usage
        exit 0
        ;;
    *)
        printf 'Unknown argument: %s\n\n' "$1" >&2
        usage >&2
        exit 2
        ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
go_mod="vertique-mcp/vertique-mcp-server/src/test/resources/mcp/clients/go/go.mod"
# CI's setup-node version; the MCP fixture lockfiles pin packages needing >=22.
node_min_major=22
docker_timeout_seconds="${VERTIQUE_DOCTOR_DOCKER_TIMEOUT_SECONDS:-20}"

problems=0
warnings=0

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-doctor-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

# --- Reporting ---

# $1 = status, $2 = tool, $3 = finding, $4 = remedy (optional).
report() {
    printf '  %-4s  %-8s %s\n' "$1" "$2" "$3"
    if [[ -n "${4-}" ]]; then
        printf '                 fix: %s\n' "$4"
    fi
}

report_ok() { report ok "$@"; }

report_warn() {
    warnings=$((warnings + 1))
    report WARN "$@"
}

report_fail() {
    problems=$((problems + 1))
    report FAIL "$@"
}

# --- Helpers ---

# Succeeds when dotted version $1 is at least $2, comparing three numeric
# components; a non-numeric suffix such as the rc1 in 1.25rc1 is ignored.
version_at_least() {
    local -a have want
    IFS=. read -r -a have <<<"$1"
    IFS=. read -r -a want <<<"$2"
    local index have_part want_part
    for index in 0 1 2; do
        have_part="${have[$index]:-0}"
        want_part="${want[$index]:-0}"
        have_part="${have_part%%[!0-9]*}"
        want_part="${want_part%%[!0-9]*}"
        if ((10#${have_part:-0} > 10#${want_part:-0})); then
            return 0
        fi
        if ((10#${have_part:-0} < 10#${want_part:-0})); then
            return 1
        fi
    done
    return 0
}

# Prints directory $1 with every symlink resolved, or nothing when it is absent.
canonical_dir() {
    (cd "$1" 2>/dev/null && pwd -P) || true
}

# Prints the feature release (8, 17, 21, ...) of java launcher $1.
java_feature_of() {
    local spec
    spec="$("$1" -XshowSettings:properties -version 2>&1 \
        | sed -n 's/^ *java\.specification\.version = //p' | head -1)" || true
    spec="${spec#1.}"
    printf '%s' "${spec%%.*}"
}

# Runs "$@" but stops it after $1 seconds, with SIGTERM and then, for a process
# that ignores it, SIGKILL two seconds later; macOS ships no timeout(1). The
# watcher kills its own pending sleep when it is stopped early, so a command
# that finishes in time leaves no stray process behind.
run_with_timeout() {
    local seconds="$1"
    shift
    "$@" &
    local pid=$!
    (
        nap=""
        trap 'kill "$nap" 2>/dev/null; exit 0' TERM
        nap_for() {
            sleep "$1" &
            nap=$!
            wait "$nap"
        }
        nap_for "$seconds"
        kill "$pid" 2>/dev/null || exit 0
        nap_for 2
        kill -KILL "$pid" 2>/dev/null
    ) >/dev/null 2>&1 &
    local watcher=$!
    local status=0
    wait "$pid" || status=$?
    kill "$watcher" 2>/dev/null || true
    wait "$watcher" 2>/dev/null || true
    return "$status"
}

# --- Checks ---

check_java() {
    local required launcher
    required="$(sed -n 's:.*<java.version>\([0-9][0-9]*\)</java.version>.*:\1:p' \
        "$repo_root/pom.xml" | head -1)"
    if [[ -z "$required" ]]; then
        report_fail Java "could not read <java.version> from pom.xml"
        return
    fi

    if [[ -n "${JAVA_HOME:-}" ]]; then
        launcher="$JAVA_HOME/bin/java"
        if [[ ! -x "$launcher" ]]; then
            report_fail Java "JAVA_HOME=$JAVA_HOME has no bin/java, so Maven cannot start" \
                "point JAVA_HOME at a JDK $required or later, or unset it to use java from PATH"
            return
        fi
    elif ! launcher="$(command -v java)"; then
        report_fail Java "no java on PATH and JAVA_HOME is not set" \
            "install a JDK $required or later and put its bin directory on PATH"
        return
    fi

    cat >"$work_dir/DoctorProbe.java" <<'JAVA'
public class DoctorProbe {
    public static void main(String[] args) {
        String javaHome = System.getenv("JAVA_HOME");
        System.out.println("feature=" + Runtime.version().feature());
        System.out.println("version=" + Runtime.version());
        System.out.println("home=" + System.getProperty("java.home"));
        System.out.println("env=" + (javaHome == null ? "" : javaHome));
    }
}
JAVA

    local probe
    if ! probe="$("$launcher" "$work_dir/DoctorProbe.java" 2>"$work_dir/java-probe.err")"; then
        local found
        found="$("$launcher" -version 2>&1 | head -1)" || true
        report_fail Java "$launcher could not run a Java source file (${found:-no version output})" \
            "Maven needs a full JDK $required or later, not a JRE or an older JDK"
        return
    fi

    local feature version home seen
    feature="$(printf '%s\n' "$probe" | sed -n 's/^feature=//p')"
    version="$(printf '%s\n' "$probe" | sed -n 's/^version=//p')"
    home="$(printf '%s\n' "$probe" | sed -n 's/^home=//p')"
    seen="$(printf '%s\n' "$probe" | sed -n 's/^env=//p')"

    if [[ ! "$feature" =~ ^[0-9]+$ ]]; then
        report_fail Java "could not determine the Java version of $launcher"
        return
    fi
    if ((feature < required)); then
        report_fail Java "JDK $version at $home is older than Java $required (pom.xml)" \
            "install a JDK $required or later and select it"
        return
    fi

    # The JVM's own JAVA_HOME is what the forked Maven builds of the invoker
    # integration tests start from, whoever set it.
    if [[ -n "$seen" ]]; then
        if [[ ! -x "$seen/bin/java" ]]; then
            report_fail Java "$launcher runs with JAVA_HOME=$seen, which has no bin/java" \
                "nested Maven builds inherit this JAVA_HOME and exit before compiling; select an installed JDK $required or later in whatever manages your Java, or export JAVA_HOME to one"
            return
        fi
        if [[ "$(canonical_dir "$seen")" != "$(canonical_dir "$home")" ]]; then
            local seen_feature
            seen_feature="$(java_feature_of "$seen/bin/java")"
            if [[ ! "$seen_feature" =~ ^[0-9]+$ ]] || ((seen_feature < required)); then
                report_fail Java "$launcher runs with JAVA_HOME=$seen, which is Java ${seen_feature:-unknown}" \
                    "nested Maven builds would run on that JDK; point JAVA_HOME at a JDK $required or later"
                return
            fi
        fi
    fi

    report_ok Java "JDK $version at $home"
}

check_go() {
    local required go_bin
    required="$(sed -n 's/^go \([0-9][0-9.]*\).*/\1/p' "$repo_root/$go_mod" | head -1)"
    if [[ -z "$required" ]]; then
        report_fail Go "could not read the go directive from $go_mod"
        return
    fi
    if ! go_bin="$(command -v go)"; then
        report_fail Go "go not found on PATH (McpGoClientInteropIT runs go)" \
            "install Go $required or later"
        return
    fi

    # GOENV=off matches the integration test's environment, and the empty work
    # directory keeps a go.mod above the caller from selecting a toolchain.
    local settings
    if ! settings="$(cd "$work_dir" && GOENV=off "$go_bin" env GOVERSION GOTOOLCHAIN 2>/dev/null)"; then
        report_fail Go "$go_bin env failed" "reinstall Go $required or later"
        return
    fi
    local go_version toolchain
    go_version="$(printf '%s\n' "$settings" | sed -n 1p)"
    go_version="${go_version#go}"
    toolchain="$(printf '%s\n' "$settings" | sed -n 2p)"

    if [[ -z "$go_version" ]]; then
        report_fail Go "could not determine the version of $go_bin" "install Go $required or later"
    elif version_at_least "$go_version" "$required"; then
        report_ok Go "go$go_version at $go_bin"
    elif [[ "$toolchain" == *auto ]]; then
        # Go 1.21+ fetches the toolchain a go.mod requests when GOTOOLCHAIN
        # allows it, so the tests still run, but only with network access.
        report_warn Go "go$go_version is older than $required ($go_mod); with GOTOOLCHAIN=$toolchain the MCP tests download go$required on each run" \
            "install Go $required or later to avoid the download"
    else
        report_fail Go "go$go_version is older than $required ($go_mod)" \
            "install Go $required or later"
    fi
}

check_node() {
    local node_bin npm_bin node_version major npm_version
    if ! node_bin="$(command -v node)"; then
        report_fail Node.js "node not found on PATH (the MCP TypeScript tests run node)" \
            "install Node.js $node_min_major or later"
        return
    fi
    node_version="$("$node_bin" --version 2>/dev/null)" || true
    major="${node_version#v}"
    major="${major%%.*}"
    if [[ ! "$major" =~ ^[0-9]+$ ]]; then
        report_fail Node.js "could not determine the version of $node_bin" \
            "install Node.js $node_min_major or later"
        return
    fi
    if ((major < node_min_major)); then
        report_fail Node.js "node $node_version is older than $node_min_major" \
            "install Node.js $node_min_major or later"
        return
    fi
    if ! npm_bin="$(command -v npm)"; then
        report_fail Node.js "npm not found on PATH (the MCP fixtures run npm ci)" \
            "install npm alongside Node.js"
        return
    fi
    npm_version="$("$npm_bin" --version 2>/dev/null)" || true
    report_ok Node.js "node $node_version, npm ${npm_version:-(unknown version)}"
}

check_docker() {
    local docker_bin
    if ! docker_bin="$(command -v docker)"; then
        report_warn Docker "docker CLI not found, so the daemon Testcontainers needs could not be checked" \
            "install the docker CLI, or make sure DOCKER_HOST points at a running daemon"
        return
    fi

    local server_version status=0
    server_version="$(run_with_timeout "$docker_timeout_seconds" \
        "$docker_bin" info --format '{{.ServerVersion}}' 2>"$work_dir/docker.err")" || status=$?
    if ((status == 0)); then
        report_ok Docker "daemon ${server_version:-(unknown version)} reachable"
        return
    fi

    local reason
    reason="$(sed -n '/[^[:space:]]/{p;q;}' "$work_dir/docker.err")"
    if ((status > 128)); then
        reason="the daemon did not answer within ${docker_timeout_seconds}s"
    fi
    report_fail Docker "${reason:-docker info failed with exit status $status}" \
        "start a Docker-compatible engine (Docker Desktop, Colima, OrbStack, Podman, ...); the Testcontainers integration tests need it"
}

# --- Main ---

printf 'Checking build prerequisites for %s\n\n' "$repo_root"
check_java
check_go
check_node
check_docker
printf '\n'

if ((problems > 0)); then
    printf 'Found %d blocking problem(s); ./mvnw clean verify will fail until they are fixed.\n' "$problems"
    exit 1
fi
if ((warnings > 0)); then
    printf 'No blocking problems; see the %d warning(s) above.\n' "$warnings"
else
    printf 'All build prerequisites found.\n'
fi
