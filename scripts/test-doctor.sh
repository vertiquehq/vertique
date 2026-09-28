#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Regression harness for scripts/doctor.sh.
#
# Each case builds stub java, go, node, npm, curl, and docker executables under
# a temporary directory and runs the doctor with an emptied environment whose
# PATH holds only those stubs plus the few system utilities the doctor uses, and
# whose HOME is the case directory, so the machine's real toolchains and engine
# configuration can never satisfy or break a case. A case declares the outcome
# the doctor is contractually required to produce and a diagnostic it must
# print; a failing case must also report exactly one blocking problem, which
# proves the stubs isolate the check under test.
#
# The doctor still reads the real pom.xml and go.mod for the required versions,
# so stub versions are chosen far from any plausible requirement: 99 passes,
# 11 (Java) and 1.20 (Go) are too old, and 8 predates Java source-file launch.
#
# Container engines are simulated by the curl stub, which answers the doctor's
# /_ping from the case's engine table. Only endpoints registered there answer,
# so an absolute default socket the host machine happens to have
# (/var/run/docker.sock) is refused like any dead endpoint and cannot satisfy a
# case.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
doctor="$script_dir/doctor.sh"

if [[ ! -f "$doctor" ]]; then
    echo "Doctor under test is missing: $doctor" >&2
    exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-doctor-tests-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

bash_bin="$(command -v bash)"

# The only system utilities the doctor and the stubs may resolve from PATH.
system_bin="$work_dir/system-bin"
mkdir -p "$system_bin"
for utility in bash cat dirname head id mktemp rm sed tr uname; do
    ln -s "$(command -v "$utility")" "$system_bin/$utility"
done

unexpected_outcomes=0
executed_cases=0

# --- Stub construction ---

case_root=""

# Starts a new case directory named $1 with an empty stub bin directory and an
# empty engine table.
case_reset() {
    case_root="$work_dir/$1"
    mkdir -p "$case_root/bin"
    : >"$case_root/stub-engines"
}

# Writes executable $1 whose body is the remaining lines of stdin, after a
# prelude assigning the NAME=value pairs given as further arguments.
write_stub() {
    local target="$1"
    shift
    mkdir -p "$(dirname "$target")"
    {
        printf '#!/usr/bin/env bash\n'
        local assignment
        for assignment in "$@"; do
            printf '%s=%q\n' "${assignment%%=*}" "${assignment#*=}"
        done
        cat
    } >"$target"
    chmod +x "$target"
}

# Creates a JDK of feature release $2 at $1 whose java answers the doctor's
# source-file probe, -XshowSettings query, and -version like a real launcher.
# Its user.home is $3 when given, else $HOME; a real JVM reads it from the
# account database, so the two can differ.
make_jdk() {
    write_stub "$1/bin/java" feature="$2" home="$1" user_home="${3-}" <<'STUB'
case "${1-}" in
    -XshowSettings:properties)
        printf '    java.specification.version = %s\n' "$feature" >&2
        ;;
    -version)
        printf 'openjdk version "%s"\n' "$feature" >&2
        ;;
    *.java)
        # Source-file launch exists from JDK 11 onward.
        if ((feature < 11)); then
            printf 'Error: Could not find or load main class %s\n' "$1" >&2
            exit 1
        fi
        printf 'feature=%s\nversion=%s.0.1\nhome=%s\nenv=%s\nuserhome=%s\n' \
            "$feature" "$feature" "$home" "${JAVA_HOME-}" "${user_home:-$HOME}"
        ;;
    *)
        exit 2
        ;;
esac
STUB
}

# Creates a version-manager shim at $1/java that exports JAVA_HOME=$2 before
# starting launcher $3, the way jenv's shim does.
make_java_shim() {
    write_stub "$1/java" exported="$2" launcher="$3" <<'STUB'
export JAVA_HOME="$exported"
exec "$launcher" "$@"
STUB
}

# Creates go reporting version $2 in bin directory $1. Like a real go, it
# reports GOTOOLCHAIN from the environment and defaults to auto.
make_go() {
    write_stub "$1/go" version="$2" <<'STUB'
[[ "${1-}" == env ]] || exit 2
printf '%s\n%s\n' "$version" "${GOTOOLCHAIN:-auto}"
STUB
}

make_node() {
    write_stub "$1/node" version="$2" <<'STUB'
[[ "${1-}" == --version ]] && printf '%s\n' "$version"
STUB
}

make_npm() {
    write_stub "$1/npm" <<'STUB'
[[ "${1-}" == --version ]] && printf '10.9.0\n'
STUB
}

# Creates curl in bin directory $1 answering the doctor's engine pings from the
# case's engine table ($HOME/stub-engines), whose lines read
# "live <endpoint> <Server header>" or "hung <endpoint>"; an endpoint is
# unix://<socket> or http://<host:port>. Anything else is refused like a dead
# socket. A ping without --max-time is rejected, so every case also proves the
# doctor bounds its pings.
make_curl() {
    write_stub "$1/curl" <<'STUB'
socket=""
url=""
max_time=""
while (($#)); do
    case "$1" in
        --unix-socket) socket="$2"; shift ;;
        --max-time) max_time="$2"; shift ;;
        --cacert | --cert | --key) shift ;;
        -*) ;;
        *) url="$1" ;;
    esac
    shift
done
if [[ -z "$max_time" ]]; then
    printf 'curl stub: ping issued without --max-time\n' >&2
    exit 99
fi
if [[ -n "$socket" ]]; then
    endpoint="unix://$socket"
else
    endpoint="${url%/_ping}"
fi
while read -r state candidate server; do
    [[ "$candidate" == "$endpoint" ]] || continue
    case "$state" in
        live)
            printf 'HTTP/1.1 200 OK\r\nServer: %s\r\nContent-Length: 2\r\n\r\nOK' "$server"
            exit 0
            ;;
        hung)
            printf 'curl: (28) Operation timed out after %s000 milliseconds\n' "$max_time" >&2
            exit 28
            ;;
    esac
done <"$HOME/stub-engines"
printf 'curl: (7) Failed to connect to localhost port 80: Could not connect to server\n' >&2
exit 7
STUB
}

# Creates the placeholder file a unix:// endpoint $1 needs to be found, since
# Testcontainers and the doctor only consider sockets that exist.
make_socket_file() {
    case "$1" in
        unix://*)
            mkdir -p "$(dirname "${1#unix://}")"
            : >"${1#unix://}"
            ;;
    esac
}

# Registers an engine answering at endpoint $1 with Server header $2.
engine_live() {
    make_socket_file "$1"
    printf 'live %s %s\n' "$1" "${2:-Docker/99.0.0 (linux)}" >>"$case_root/stub-engines"
}

# Registers an engine at endpoint $1 that never answers.
engine_hung() {
    make_socket_file "$1"
    printf 'hung %s\n' "$1" >>"$case_root/stub-engines"
}

# Creates uname in bin directory $1 reporting operating system $2.
make_uname() {
    write_stub "$1/uname" os="$2" <<'STUB'
printf '%s\n' "$os"
STUB
}

# Creates docker in bin directory $1 whose current context points at $2.
make_docker_context() {
    write_stub "$1/docker" host="$2" <<'STUB'
[[ "${1-} ${2-}" == "context inspect" ]] || exit 2
printf '%s\n' "$host"
STUB
}

# Fills the case bin directory with passing go, node, npm, and curl stubs.
make_healthy_toolchains() {
    make_go "$case_root/bin" go99.0.0
    make_node "$case_root/bin" v99.0.0
    make_npm "$case_root/bin"
    make_curl "$case_root/bin"
}

# Healthy toolchains plus an engine at Docker Desktop's socket, a default
# location Testcontainers finds on both Linux and macOS.
make_healthy_tools() {
    make_healthy_toolchains
    engine_live "unix://$case_root/.docker/run/docker.sock"
}

# --- Runner ---

print_indented_output() {
    printf '%s\n' "$1" | sed 's/^/      | /'
}

# $1 = case name, $2 = expected outcome (pass|fail), $3 = substring the
# doctor's output must contain, $4 = PATH for the doctor; any further arguments
# are extra NAME=value entries for the doctor's otherwise empty environment.
run_case() {
    local case_name="$1"
    local expected_outcome="$2"
    local expected_diagnostic="$3"
    local doctor_path="$4"
    shift 4
    local output
    local status=0
    local observed_outcome

    executed_cases=$((executed_cases + 1))
    if output="$(env -i PATH="$doctor_path" HOME="$case_root" TMPDIR="$case_root" \
        "$@" "$bash_bin" "$doctor" 2>&1)"; then
        observed_outcome=pass
    else
        status=$?
        observed_outcome=fail
    fi

    if [[ "$observed_outcome" != "$expected_outcome" ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-34s expected doctor to %s, but it %sed (exit %d)\n' \
            "$case_name" "$expected_outcome" "$observed_outcome" "$status"
        print_indented_output "$output"
        return 0
    fi

    if [[ "$output" != *"$expected_diagnostic"* ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-34s doctor %sed but never reported %s\n' \
            "$case_name" "$observed_outcome" "$expected_diagnostic"
        print_indented_output "$output"
        return 0
    fi

    if [[ "$observed_outcome" == fail && "$output" != *"Found 1 blocking problem(s)"* ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-34s doctor failed, but not on exactly one check\n' "$case_name"
        print_indented_output "$output"
        return 0
    fi

    local finding
    finding="$(printf '%s\n' "$output" | sed -En 's/^  (FAIL|WARN) +//p' | head -1)"
    printf 'PASS  %-34s %s\n' "$case_name" \
        "${finding:-$(printf '%s\n' "$output" | tail -1)}"
    return 0
}

# --- Cases: Java ---

case_reset healthy-java-on-path
make_jdk "$case_root/jdk" 99
make_healthy_tools
run_case "healthy-java-on-path" pass "All build prerequisites found." \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

# An explicit JAVA_HOME is the launcher Maven uses; java on PATH is ignored.
case_reset healthy-java-home
make_jdk "$case_root/jdk" 99
make_healthy_tools
run_case "healthy-java-home" pass "All build prerequisites found." \
    "$case_root/bin:$system_bin" JAVA_HOME="$case_root/jdk"

# The failure this doctor exists for: the shell's JAVA_HOME is empty, java on
# PATH is a version-manager shim, and the shim exports a JAVA_HOME that does not
# exist (jenv with its version set to "system"). The top-level build still runs;
# only nested Maven builds break.
case_reset shim-exports-missing-java-home
make_jdk "$case_root/jdk" 99
make_java_shim "$case_root/shims" "$case_root/versions/system" "$case_root/jdk/bin/java"
make_healthy_tools
run_case "shim-exports-missing-java-home" fail \
    "runs with JAVA_HOME=$case_root/versions/system, which has no bin/java" \
    "$case_root/shims:$case_root/bin:$system_bin"

# A shim that exports an existing but older JDK than the one it launches
# would make nested builds compile on the older JDK.
case_reset shim-exports-older-java-home
make_jdk "$case_root/jdk" 99
make_jdk "$case_root/old-jdk" 11
make_java_shim "$case_root/shims" "$case_root/old-jdk" "$case_root/jdk/bin/java"
make_healthy_tools
run_case "shim-exports-older-java-home" fail \
    "runs with JAVA_HOME=$case_root/old-jdk, which is Java 11" \
    "$case_root/shims:$case_root/bin:$system_bin"

# A shim that exports the JDK it launches is the healthy version-manager case.
case_reset shim-exports-matching-java-home
make_jdk "$case_root/jdk" 99
make_java_shim "$case_root/shims" "$case_root/jdk" "$case_root/jdk/bin/java"
make_healthy_tools
run_case "shim-exports-matching-java-home" pass "All build prerequisites found." \
    "$case_root/shims:$case_root/bin:$system_bin"

case_reset java-home-without-java
make_healthy_tools
run_case "java-home-without-java" fail \
    "JAVA_HOME=$case_root/missing-jdk has no bin/java" \
    "$case_root/bin:$system_bin" JAVA_HOME="$case_root/missing-jdk"

case_reset java-missing
make_healthy_tools
run_case "java-missing" fail "no java on PATH and JAVA_HOME is not set" \
    "$case_root/bin:$system_bin"

case_reset java-too-old
make_jdk "$case_root/jdk" 11
make_healthy_tools
run_case "java-too-old" fail "JDK 11.0.1 at $case_root/jdk is older than Java" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

case_reset java-without-source-launch
make_jdk "$case_root/jdk" 8
make_healthy_tools
run_case "java-without-source-launch" fail "could not run a Java source file" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

# --- Cases: Go ---

case_reset go-missing
make_jdk "$case_root/jdk" 99
make_healthy_tools
rm "$case_root/bin/go"
run_case "go-missing" fail "go not found on PATH" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

# Go 1.21+ downloads the toolchain go.mod asks for while GOTOOLCHAIN allows it,
# so an older go still runs the tests; that is a warning, not a failure.
case_reset go-old-auto-toolchain
make_jdk "$case_root/jdk" 99
make_healthy_tools
make_go "$case_root/bin" go1.20.0
run_case "go-old-auto-toolchain" pass "download go" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

case_reset go-old-local-toolchain
make_jdk "$case_root/jdk" 99
make_healthy_tools
make_go "$case_root/bin" go1.20.0
run_case "go-old-local-toolchain" fail "go1.20.0 is older than" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin" GOTOOLCHAIN=local

# --- Cases: Node.js ---

case_reset node-too-old
make_jdk "$case_root/jdk" 99
make_healthy_tools
make_node "$case_root/bin" v20.0.0
run_case "node-too-old" fail "node v20.0.0 is older than" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

case_reset npm-missing
make_jdk "$case_root/jdk" 99
make_healthy_tools
rm "$case_root/bin/npm"
run_case "npm-missing" fail "npm not found on PATH" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

# --- Cases: Docker ---

case_reset engine-default-socket
make_jdk "$case_root/jdk" 99
make_healthy_tools
run_case "engine-default-socket" pass \
    "Docker/99.0.0 (linux) at unix://$case_root/.docker/run/docker.sock" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

# DOCKER_HOST outranks every default socket, and the engine behind it is
# reported by its own Server header, here Podman's.
case_reset engine-docker-host-podman
make_jdk "$case_root/jdk" 99
make_healthy_tools
engine_live "unix://$case_root/podman/podman.sock" "Libpod/5.2.0 (linux)"
run_case "engine-docker-host-podman" pass \
    "Libpod/5.2.0 (linux) at unix://$case_root/podman/podman.sock" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin" \
    DOCKER_HOST="unix://$case_root/podman/podman.sock"

# Testcontainers passes over a DOCKER_HOST that does not answer and quietly
# uses the next engine it finds; the tests would run somewhere unintended.
case_reset engine-docker-host-dead-fallback
make_jdk "$case_root/jdk" 99
make_healthy_tools
make_socket_file "unix://$case_root/dead.sock"
run_case "engine-docker-host-dead-fallback" pass \
    "Testcontainers skips DOCKER_HOST=unix://$case_root/dead.sock (nothing is listening)" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin" \
    DOCKER_HOST="unix://$case_root/dead.sock"

case_reset engine-docker-host-missing-socket
make_jdk "$case_root/jdk" 99
make_healthy_toolchains
run_case "engine-docker-host-missing-socket" fail \
    "DOCKER_HOST=unix://$case_root/nowhere.sock: socket does not exist" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin" \
    DOCKER_HOST="unix://$case_root/nowhere.sock"

# docker.host in ~/.testcontainers.properties stands in for DOCKER_HOST, with
# Java properties escaping.
case_reset engine-properties-docker-host
make_jdk "$case_root/jdk" 99
make_healthy_toolchains
engine_live "unix://$case_root/engine.sock"
printf '%s\n' "# written by a tool" "ryuk.disabled=true" \
    "docker.host=unix\\://$case_root/engine.sock" >"$case_root/.testcontainers.properties"
run_case "engine-properties-docker-host" pass \
    "at unix://$case_root/engine.sock" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

# tc.host, which Testcontainers Desktop writes, is a TCP endpoint.
case_reset engine-properties-tc-host
make_jdk "$case_root/jdk" 99
make_healthy_toolchains
engine_live "http://127.0.0.1:41234"
printf '%s\n' "tc.host=tcp\\://127.0.0.1\\:41234" >"$case_root/.testcontainers.properties"
run_case "engine-properties-tc-host" pass \
    "at tcp://127.0.0.1:41234" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

# Testcontainers tries tc.host ahead of DOCKER_HOST whatever their numeric
# priorities say, so an exported DOCKER_HOST is silently shadowed.
case_reset engine-tc-host-ahead-of-docker-host
make_jdk "$case_root/jdk" 99
make_healthy_toolchains
engine_live "http://127.0.0.1:41234"
engine_live "unix://$case_root/engine.sock"
printf '%s\n' "tc.host=tcp\\://127.0.0.1\\:41234" >"$case_root/.testcontainers.properties"
run_case "engine-tc-host-ahead-of-docker-host" pass \
    "Testcontainers uses tc.host=tcp://127.0.0.1:41234 ahead of DOCKER_HOST=unix://$case_root/engine.sock" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin" \
    DOCKER_HOST="unix://$case_root/engine.sock"

# tc.host is read from ~/.testcontainers.properties only; an environment
# variable of that shape is not a Testcontainers setting and must not be used.
case_reset engine-tc-host-env-is-not-a-setting
make_jdk "$case_root/jdk" 99
make_healthy_tools
engine_live "http://127.0.0.1:41234"
run_case "engine-tc-host-env-is-not-a-setting" pass \
    "Docker/99.0.0 (linux) at unix://$case_root/.docker/run/docker.sock" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin" \
    TESTCONTAINERS_TC_HOST="tcp://127.0.0.1:41234"

# Testcontainers honors a cached docker.client.strategy only when it is
# persistable, which the Docker Desktop strategy is not. On Linux the rootless
# socket (priority 81) therefore still wins; on macOS rootless does not apply
# and Docker Desktop's socket is the only candidate.
case_reset engine-cached-desktop-strategy-ignored
make_jdk "$case_root/jdk" 99
make_healthy_toolchains
engine_live "unix://$case_root/xdg/docker.sock" "Rootless/1.0 (linux)"
engine_live "unix://$case_root/.docker/desktop/docker.sock" "Desktop/1.0 (linux)"
printf '%s\n' "docker.client.strategy=org.testcontainers.dockerclient.DockerDesktopClientProviderStrategy" \
    >"$case_root/.testcontainers.properties"
if [[ "$(uname -s)" == Linux ]]; then
    expected_engine="Rootless/1.0 (linux) at unix://$case_root/xdg/docker.sock"
else
    expected_engine="Desktop/1.0 (linux) at unix://$case_root/.docker/desktop/docker.sock"
fi
run_case "engine-cached-desktop-strategy-ignored" pass "$expected_engine" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin" XDG_RUNTIME_DIR="$case_root/xdg"

# Default sockets and the properties file sit under the JVM's user.home, which
# need not be $HOME.
case_reset engine-under-jvm-user-home
make_jdk "$case_root/jdk" 99 "$case_root/jvm-home"
make_healthy_toolchains
engine_live "unix://$case_root/jvm-home/.docker/run/docker.sock"
run_case "engine-under-jvm-user-home" pass \
    "at unix://$case_root/jvm-home/.docker/run/docker.sock" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

# curl cannot reach an ssh endpoint, but Testcontainers tries it and moves on
# if it fails, so the doctor notes it and keeps looking: the live default
# socket below it is found, with a warning that the ssh endpoint ranks first.
case_reset engine-uncheckable-before-fallback
make_jdk "$case_root/jdk" 99
make_healthy_tools
run_case "engine-uncheckable-before-fallback" pass \
    "Testcontainers tries DOCKER_HOST=ssh://builder@remote (curl cannot reach a ssh endpoint) before unix://$case_root/.docker/run/docker.sock" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin" DOCKER_HOST="ssh://builder@remote"

# With nothing else answering, an endpoint the doctor cannot check leaves the
# outcome unknown: a warning, not a failure.
case_reset engine-only-uncheckable
make_jdk "$case_root/jdk" 99
make_healthy_toolchains
run_case "engine-only-uncheckable" pass \
    "Testcontainers would also try DOCKER_HOST=ssh://builder@remote (curl cannot reach a ssh endpoint), which the doctor cannot check" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin" DOCKER_HOST="ssh://builder@remote"

# On Windows Testcontainers falls back to a named pipe curl cannot reach, so
# finding nothing is a warning, not a failure.
case_reset engine-windows-named-pipe
make_jdk "$case_root/jdk" 99
make_healthy_toolchains
make_uname "$case_root/bin" MINGW64_NT-10.0
run_case "engine-windows-named-pipe" pass "a named pipe the doctor cannot check" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

# The false "ok" a plain `docker info` gives: the CLI reaches an engine through
# a Docker context (Colima, Podman machine, ...) that Testcontainers never
# consults. The remedy names the DOCKER_HOST that would expose it.
case_reset engine-only-via-cli-context
make_jdk "$case_root/jdk" 99
make_healthy_toolchains
engine_live "unix://$case_root/.colima/default/docker.sock"
make_docker_context "$case_root/bin" "unix://$case_root/.colima/default/docker.sock"
run_case "engine-only-via-cli-context" fail \
    "export DOCKER_HOST=unix://$case_root/.colima/default/docker.sock" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

case_reset engine-missing
make_jdk "$case_root/jdk" 99
make_healthy_toolchains
run_case "engine-missing" fail "export DOCKER_HOST to its API socket" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

case_reset engine-hung
make_jdk "$case_root/jdk" 99
make_healthy_toolchains
engine_hung "unix://$case_root/hung.sock"
run_case "engine-hung" fail \
    "DOCKER_HOST=unix://$case_root/hung.sock: did not answer within 1s" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin" \
    DOCKER_HOST="unix://$case_root/hung.sock" VERTIQUE_DOCTOR_DOCKER_TIMEOUT_SECONDS=1

# Without curl the engine cannot be pinged; that is unverified, not failed.
case_reset engine-unverifiable-without-curl
make_jdk "$case_root/jdk" 99
make_healthy_tools
rm "$case_root/bin/curl"
run_case "engine-unverifiable-without-curl" pass "curl not found" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

# --- Summary ---

if ((unexpected_outcomes > 0)); then
    printf '\n%d of %d doctor cases produced an unexpected outcome.\n' \
        "$unexpected_outcomes" "$executed_cases" >&2
    exit 1
fi

printf '\nAll %d doctor cases produced the expected outcome.\n' "$executed_cases"
