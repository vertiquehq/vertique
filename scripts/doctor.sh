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
#   Docker   A container engine answering at the endpoint Testcontainers picks.
#            The endpoints are resolved the way Testcontainers 2.x resolves
#            them and pinged over the Docker API, so Docker, Podman, Colima and
#            other compatible engines are all checked alike. Docker CLI
#            contexts are not endpoints Testcontainers uses, so an engine the
#            CLI reaches only through a context is reported with the
#            DOCKER_HOST that would expose it.
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
        "and a container engine Testcontainers can reach. Exits 0 when nothing" \
        "blocks the build and 1 when a check failed."
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
docker_timeout_seconds="${VERTIQUE_DOCTOR_DOCKER_TIMEOUT_SECONDS:-10}"

problems=0
warnings=0
os_name="$(uname -s)"
# The JVM's user.home, which Testcontainers resolves its properties file and
# default sockets against; filled in by the Java probe when it runs. The JVM
# takes it from the account database, so it can differ from $HOME.
jvm_user_home=""

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

# Prints property $2 from $1/.testcontainers.properties with Java properties
# escapes removed, so docker.host=unix\:///path reads as unix:///path.
testcontainers_property() {
    local file="$1/.testcontainers.properties"
    if [[ -n "$1" && -f "$file" ]]; then
        sed -n "/^[[:space:]]*${2//./\\.}[[:space:]]*[=:]/{s/^[^=:]*[=:][[:space:]]*//;p;q;}" "$file" \
            | tr -d '\r' | sed 's/\\\(.\)/\1/g'
    fi
}

# Prints the default endpoint of Testcontainers strategy $1 (a class simple
# name) under home directory $2, or nothing when that strategy does not apply.
default_endpoint() {
    local socket
    case "$1" in
        RootlessDockerClientProviderStrategy)
            [[ "$os_name" == Linux ]] || return 0
            for socket in "${XDG_RUNTIME_DIR:+$XDG_RUNTIME_DIR/docker.sock}" \
                "$2/.docker/run/docker.sock" "/run/user/$(id -u)/docker.sock"; do
                if [[ -n "$socket" && -e "$socket" ]]; then
                    printf 'unix://%s\n' "$socket"
                    return 0
                fi
            done
            ;;
        UnixSocketClientProviderStrategy)
            if [[ "$os_name" == Linux || "$os_name" == Darwin ]] && [[ -e /var/run/docker.sock ]]; then
                printf 'unix:///var/run/docker.sock\n'
            fi
            ;;
        DockerDesktopClientProviderStrategy)
            [[ "$os_name" == Linux || "$os_name" == Darwin ]] || return 0
            for socket in "$2/.docker/desktop/docker.sock" "$2/.docker/run/docker.sock"; do
                if [[ -e "$socket" ]]; then
                    printf 'unix://%s\n' "$socket"
                    return 0
                fi
            done
            ;;
    esac
}

# Prints "origin endpoint" lines for the container engine endpoints
# Testcontainers 2.x tries, in the order of its
# DockerClientProviderStrategy.getFirstValidStrategy, with $1 as the JVM's
# user.home. It uses the first endpoint that connects and answers:
#   1. tc.host in ~/.testcontainers.properties (Testcontainers Desktop writes
#      it; there is no environment variable for it)
#   2. DOCKER_HOST, else docker.host in ~/.testcontainers.properties
#   3. the strategy cached as docker.client.strategy after an earlier success,
#      honored only when it is persistable: the rootless or
#      /var/run/docker.sock strategy (a cached Docker Desktop one is ignored)
#   4. the defaults by priority: rootless docker.sock on Linux (81),
#      /var/run/docker.sock (80), Docker Desktop's socket (79)
# Default sockets that do not exist are omitted, as Testcontainers omits them.
engine_candidates() {
    local home="$1" value
    value="$(testcontainers_property "$home" tc.host)" || true
    if [[ -n "$value" ]]; then
        printf 'tc.host %s\n' "$value"
    fi
    if [[ -n "${DOCKER_HOST:-}" ]]; then
        printf 'DOCKER_HOST %s\n' "$DOCKER_HOST"
    else
        value="$(testcontainers_property "$home" docker.host)" || true
        if [[ -n "$value" ]]; then
            printf 'docker.host %s\n' "$value"
        fi
    fi

    local cached="${TESTCONTAINERS_DOCKER_CLIENT_STRATEGY:-${DOCKER_CLIENT_STRATEGY:-}}"
    if [[ -z "$cached" ]]; then
        cached="$(testcontainers_property "$home" docker.client.strategy)" || true
    fi
    case "${cached##*.}" in
        RootlessDockerClientProviderStrategy | UnixSocketClientProviderStrategy) ;;
        *) cached="" ;;
    esac
    local strategy
    for strategy in "${cached##*.}" RootlessDockerClientProviderStrategy \
        UnixSocketClientProviderStrategy DockerDesktopClientProviderStrategy; do
        value="$(default_endpoint "$strategy" "$home")"
        if [[ -n "$value" ]]; then
            printf 'default %s\n' "$value"
        fi
    done
}

# Pings engine endpoint $1 through the Docker API's /_ping, which Docker,
# Podman and other compatible engines all serve. On success prints the engine's
# Server header, such as "Docker/29.1.0 (linux)" or "Libpod/5.2.0 (linux)".
# Otherwise prints why and returns 1 when the endpoint failed, or 2 when it is
# a transport curl cannot reach: a Windows named pipe, or another scheme such
# as ssh. Testcontainers tries those itself and moves on when they fail, so the
# outcome for them is unknown rather than failed.
ping_endpoint() {
    local endpoint="$1"
    case "$endpoint" in
        unix://*)
            local socket="${endpoint#unix://}"
            if [[ ! -e "$socket" ]]; then
                printf 'socket does not exist'
                return 1
            fi
            set -- --unix-socket "$socket" http://localhost/_ping
            ;;
        tcp://* | http://* | https://*)
            local address="${endpoint#*://}"
            address="${address%%/*}"
            if [[ "$endpoint" == https://* || "${DOCKER_TLS_VERIFY:-}" == 1 ]]; then
                local certs="${DOCKER_CERT_PATH:-${HOME:-}/.docker}"
                set -- --cacert "$certs/ca.pem" --cert "$certs/cert.pem" --key "$certs/key.pem" \
                    "https://$address/_ping"
            else
                set -- "http://$address/_ping"
            fi
            ;;
        *)
            printf 'curl cannot reach a %s endpoint' "${endpoint%%://*}"
            return 2
            ;;
    esac

    local response status=0
    response="$(curl --silent --show-error --include --max-time "$docker_timeout_seconds" \
        "$@" 2>&1 | tr -d '\r')" || status=$?
    case "$status" in
        0) ;;
        7)
            printf 'nothing is listening'
            return 1
            ;;
        28)
            printf 'did not answer within %ss' "$docker_timeout_seconds"
            return 1
            ;;
        *)
            printf '%s' "$(printf '%s\n' "$response" | sed -n '/^curl: /{s/^curl: ([0-9]*) //;p;q;}')"
            return 1
            ;;
    esac

    local status_line server
    status_line="$(printf '%s\n' "$response" | sed -n 1p)"
    if [[ "$status_line" != HTTP/*" 200"* ]]; then
        printf 'answered %s' "$status_line"
        return 1
    fi
    server="$(printf '%s\n' "$response" \
        | sed -n '/^[Ss][Ee][Rr][Vv][Ee][Rr]:/{s/^[^:]*:[[:space:]]*//;p;q;}')"
    printf '%s' "${server:-an engine}"
}

# Prints the endpoint of the Docker CLI's current context, when there is a CLI.
docker_context_host() {
    if command -v docker >/dev/null 2>&1; then
        docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true
    fi
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
        System.out.println("userhome=" + System.getProperty("user.home"));
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
    jvm_user_home="$(printf '%s\n' "$probe" | sed -n 's/^userhome=//p')"

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
    if ! command -v curl >/dev/null 2>&1; then
        report_warn Docker "curl not found, so the container engine Testcontainers would use could not be checked" \
            "install curl"
        return
    fi

    # Walk the endpoints the way Testcontainers does and take the first that
    # answers. Configured endpoints passed over on the way, or ranked below
    # the one taken, still matter: the tests then run against an engine other
    # than the one that setting names. An endpoint curl cannot reach is noted
    # and passed over too, since Testcontainers moves on if it fails.
    local home="${jvm_user_home:-${HOME:-}}"
    local candidates origin endpoint name result status
    local tried="" failures="" skipped="" shadowed="" unchecked=""
    local chosen="" chosen_name="" chosen_engine=""
    candidates="$(engine_candidates "$home")"
    while read -r origin endpoint; do
        if [[ -z "$endpoint" || " $tried " == *" $endpoint "* ]]; then
            continue
        fi
        tried="$tried $endpoint"
        name="$endpoint"
        if [[ "$origin" != default ]]; then
            name="$origin=$endpoint"
        fi
        if [[ -n "$chosen" ]]; then
            if [[ "$origin" != default ]]; then
                shadowed="${shadowed:+$shadowed, }$name"
            fi
            continue
        fi
        status=0
        result="$(ping_endpoint "$endpoint")" || status=$?
        if ((status == 2)); then
            unchecked="${unchecked:+$unchecked, }$name ($result)"
            continue
        fi
        if ((status == 0)); then
            chosen="$endpoint"
            chosen_name="$name"
            chosen_engine="$result"
            continue
        fi
        failures="${failures:+$failures; }$name: $result"
        if [[ "$origin" != default ]]; then
            skipped="${skipped:+$skipped, }$name ($result)"
        fi
    done <<<"$candidates"

    if [[ -n "$chosen" ]]; then
        report_ok Docker "$chosen_engine at $chosen"
        if [[ -n "$unchecked" ]]; then
            report_warn Docker "Testcontainers tries $unchecked before $chosen; if that answers, the tests use it instead" \
                "confirm which engine that endpoint reaches, or unset it"
        fi
        if [[ -n "$skipped" ]]; then
            report_warn Docker "Testcontainers skips $skipped and uses $chosen instead" \
                "correct or unset that setting if $chosen is not the engine the tests should use"
        fi
        if [[ -n "$shadowed" ]]; then
            report_warn Docker "Testcontainers uses $chosen_name ahead of $shadowed" \
                "remove $chosen_name if the tests should use $shadowed"
        fi
        return
    fi

    local finding remedy context_host context_engine
    if [[ -n "$failures" ]]; then
        finding="no container engine answered where Testcontainers looks: $failures"
    elif [[ -n "$unchecked" ]]; then
        finding="no container engine the doctor can reach is configured or found"
    else
        finding="no container engine where Testcontainers looks: DOCKER_HOST is unset and no docker.sock exists in its default locations"
    fi
    remedy="start a Docker-compatible engine, or export DOCKER_HOST to its API socket, which Podman, Colima and similar engines need (https://java.testcontainers.org/supported_docker_environment/)"
    # A CLI that works through a Docker context is the usual way this check
    # and a plain `docker info` disagree: Testcontainers ignores contexts.
    context_host="$(docker_context_host)"
    if [[ -n "$context_host" && " $tried " != *" $context_host "* ]] \
        && context_engine="$(ping_endpoint "$context_host")"; then
        remedy="the docker CLI reaches $context_engine at $context_host through its current context, which Testcontainers ignores; export DOCKER_HOST=$context_host"
    fi
    # An endpoint curl cannot reach may still work for Testcontainers, so an
    # otherwise unanswered search is not proof of failure.
    if [[ -n "$unchecked" ]]; then
        report_warn Docker "$finding; Testcontainers would also try $unchecked, which the doctor cannot check" \
            "$remedy"
        return
    fi
    if [[ "$os_name" != Linux && "$os_name" != Darwin ]]; then
        # Elsewhere (Windows) Testcontainers falls back to a named pipe curl
        # cannot reach, which likewise leaves the outcome unknown.
        report_warn Docker "$finding, and Testcontainers' default on $os_name is a named pipe the doctor cannot check" \
            "$remedy"
        return
    fi
    report_fail Docker "$finding" "$remedy"
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
