#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Regression harness for scripts/doctor.sh.
#
# Each case builds stub java, go, node, npm, and docker executables under a
# temporary directory and runs the doctor with an emptied environment whose PATH
# holds only those stubs plus the few system utilities the doctor uses, so the
# machine's real toolchains can never satisfy or break a case. A case declares
# the outcome the doctor is contractually required to produce and a diagnostic
# it must print; a failing case must also report exactly one blocking problem,
# which proves the stubs isolate the check under test.
#
# The doctor still reads the real pom.xml and go.mod for the required versions,
# so stub versions are chosen far from any plausible requirement: 99 passes,
# 11 (Java) and 1.20 (Go) are too old, and 8 predates Java source-file launch.

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
for utility in bash cat dirname head mktemp rm sed sleep; do
    ln -s "$(command -v "$utility")" "$system_bin/$utility"
done

unexpected_outcomes=0
executed_cases=0

# --- Stub construction ---

case_root=""

# Starts a new case directory named $1 with an empty stub bin directory.
case_reset() {
    case_root="$work_dir/$1"
    mkdir -p "$case_root/bin"
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
make_jdk() {
    write_stub "$1/bin/java" feature="$2" home="$1" <<'STUB'
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
        printf 'feature=%s\nversion=%s.0.1\nhome=%s\nenv=%s\n' \
            "$feature" "$feature" "$home" "${JAVA_HOME-}"
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

# Creates docker in bin directory $1 whose daemon is up, down, or hung.
make_docker() {
    case "$2" in
        up)
            write_stub "$1/docker" <<'STUB'
printf '99.0.0\n'
STUB
            ;;
        down)
            write_stub "$1/docker" <<'STUB'
printf 'Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?\n' >&2
exit 1
STUB
            ;;
        hung)
            # exec keeps the stub a single process, like the real CLI, so the
            # doctor's timeout reaches the process holding its output pipe.
            write_stub "$1/docker" <<'STUB'
exec sleep 30
STUB
            ;;
        deaf)
            # An ignored signal stays ignored across exec, so this hung process
            # survives SIGTERM and only SIGKILL stops it.
            write_stub "$1/docker" <<'STUB'
trap '' TERM
exec sleep 30
STUB
            ;;
    esac
}

# Fills the case bin directory with passing go, node, npm, and docker stubs.
make_healthy_tools() {
    make_go "$case_root/bin" go99.0.0
    make_node "$case_root/bin" v99.0.0
    make_npm "$case_root/bin"
    make_docker "$case_root/bin" up
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

case_reset docker-down
make_jdk "$case_root/jdk" 99
make_healthy_tools
make_docker "$case_root/bin" down
run_case "docker-down" fail "Cannot connect to the Docker daemon" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

case_reset docker-hung
make_jdk "$case_root/jdk" 99
make_healthy_tools
make_docker "$case_root/bin" hung
run_case "docker-hung" fail "the daemon did not answer within 1s" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin" VERTIQUE_DOCTOR_DOCKER_TIMEOUT_SECONDS=1

# A hung CLI that ignores SIGTERM must still be stopped rather than hang the
# doctor.
case_reset docker-hung-ignoring-term
make_jdk "$case_root/jdk" 99
make_healthy_tools
make_docker "$case_root/bin" deaf
run_case "docker-hung-ignoring-term" fail "the daemon did not answer within 1s" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin" VERTIQUE_DOCTOR_DOCKER_TIMEOUT_SECONDS=1

# Testcontainers talks to the daemon without the CLI, so a missing CLI is an
# unverifiable daemon, not a proven failure.
case_reset docker-cli-missing
make_jdk "$case_root/jdk" 99
make_healthy_tools
rm "$case_root/bin/docker"
run_case "docker-cli-missing" pass "docker CLI not found" \
    "$case_root/jdk/bin:$case_root/bin:$system_bin"

# --- Summary ---

if ((unexpected_outcomes > 0)); then
    printf '\n%d of %d doctor cases produced an unexpected outcome.\n' \
        "$unexpected_outcomes" "$executed_cases" >&2
    exit 1
fi

printf '\nAll %d doctor cases produced the expected outcome.\n' "$executed_cases"
