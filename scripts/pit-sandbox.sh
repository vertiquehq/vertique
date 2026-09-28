#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Runs one Maven invocation inside a disposable Docker container; a drop-in
# for ./mvnw that `scripts/pit-pr-scope.sh --sandbox` uses:
#
#   bash scripts/pit-sandbox.sh -ntp -pl <modules> -am process-test-classes ...
#
# Mutants execute with real side effects (a mutated guard once deleted a
# module's source tree), so they never touch this checkout:
#   - the container gets a snapshot of the working tree (tracked and untracked
#     files, .gitignore respected, uncommitted changes included);
#   - it runs as an unprivileged user with all capabilities dropped and, by
#     default, no network;
#   - Maven writes to a private cache volume and reads this machine's
#     ~/.m2/repository read-only;
#   - only target/pit-reports and target/classes of the -pl modules are copied
#     back, so the changed-line report can read them.
#
# Environment:
#   PIT_SANDBOX_IMAGE      image tag (default derived from the Dockerfile, built on first use)
#   PIT_SANDBOX_M2_VOLUME  private Maven cache volume (default vertique-pit-m2)
#   PIT_SANDBOX_NETWORK    docker network (default none: offline, host cache only)
#   PIT_SANDBOX_MEMORY     memory limit (default 6g)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Tagged by the Dockerfile's content, so an edited Dockerfile builds a fresh image.
image="${PIT_SANDBOX_IMAGE:-vertique-pit-sandbox:$(git hash-object "$SCRIPT_DIR/pit-sandbox/Dockerfile" | cut -c1-12)}"
volume="${PIT_SANDBOX_M2_VOLUME:-vertique-pit-m2}"
network="${PIT_SANDBOX_NETWORK:-none}"
memory="${PIT_SANDBOX_MEMORY:-6g}"

command -v docker >/dev/null 2>&1 || { echo 'pit-sandbox: docker is not installed' >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo 'pit-sandbox: the Docker daemon is not running' >&2; exit 1; }

cd "$(git rev-parse --show-toplevel)"

if ! docker image inspect "$image" >/dev/null 2>&1; then
  docker build --quiet --tag "$image" "$SCRIPT_DIR/pit-sandbox" >&2
fi

# The -pl modules get their reports and classes copied back.
modules=""
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  if [[ "${args[$i]}" == -pl ]]; then modules="${args[$((i + 1))]:-}"; fi
done

offline=()
if [[ "$network" == none ]]; then offline=(-o); fi

# Snapshot the working tree through a temporary index: git add -A honors
# .gitignore, and git archive keeps file modes (mvnw stays executable).
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
GIT_INDEX_FILE="$work/index" git read-tree HEAD
GIT_INDEX_FILE="$work/index" git add -A
tree="$(GIT_INDEX_FILE="$work/index" git write-tree)"

# shellcheck disable=SC2016 # the inner script expands its own variables
git archive --format=tar "$tree" | docker run --rm -i \
  --network "$network" \
  --cap-drop ALL --security-opt no-new-privileges \
  --memory "$memory" --pids-limit 4096 \
  --mount "type=volume,src=$volume,dst=/home/pit/.m2/repository" \
  --mount "type=bind,src=$HOME/.m2/repository,dst=/host-m2,readonly" \
  --mount "type=bind,src=$HOME/.m2/wrapper,dst=/home/pit/.m2/wrapper,readonly" \
  "$image" bash -c '
    set -euo pipefail
    modules="$1"; shift
    tar -xf - -C /work
    ./mvnw "$@" -Dmaven.repo.local.tail=/host-m2 >&2
    paths=()
    IFS=, read -r -a selected <<< "$modules"
    for module in "${selected[@]}"; do
      for dir in "$module/target/pit-reports" "$module/target/classes"; do
        if [[ -d "$dir" ]]; then paths+=("$dir"); fi
      done
    done
    if [[ ${#paths[@]} -gt 0 ]]; then tar -cf - "${paths[@]}"; fi
  ' _ "$modules" ${offline[@]+"${offline[@]}"} "$@" > "$work/results.tar"

if [[ -s "$work/results.tar" ]]; then
  tar -xf "$work/results.tar"
fi
