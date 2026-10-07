#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# RELEASE-001 — stages the SNAPSHOT payload from the reactor that required CI
# has just built and tested, so publication downloads it instead of rebuilding.
#
# Run from the repository root after `verify`, with its `target/` directories
# still in place: the install below reuses the compiled classes and only adds
# the release profile's sources and Javadoc JARs. It installs into a private
# copy of the Maven repository, so the payload can only hold this build's
# artifacts and the shared repository (and the cache saved from it) is never
# written. Tests are not rerun: they just ran.
#
# Usage:
#   release/stage-snapshot-payload.sh --out <payload directory> --sha <commit> \
#     [--seed-repository <Maven repository to copy, default ~/.m2/repository>]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

OUT=""
SHA=""
SEED="${HOME}/.m2/repository"

die() { echo "stage-snapshot-payload: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)             OUT="${2:-}";  shift 2 ;;
    --sha)             SHA="${2:-}";  shift 2 ;;
    --seed-repository) SEED="${2:-}"; shift 2 ;;
    *) die "unrecognised argument: $1" ;;
  esac
done

[[ -n "$OUT" ]] || die "--out is required"
[[ -n "$SHA" ]] || die "--sha is required"

cd "$REPO_ROOT"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/vertique-stage-payload.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
M2="$WORK/m2"

# Seeding spares the install a cold download of every dependency.
if [[ -d "$SEED" ]]; then
  cp -R "$SEED" "$M2"
else
  mkdir -p "$M2"
fi

# Whatever an earlier job or test left under the product's own group is not
# this build's output and must never reach the payload.
GROUP_PATH="$(node -p 'require("./release/publication-policy.json").groupIdPrefix.replaceAll(".", "/")')"
[[ -n "$GROUP_PATH" ]] || die "could not read the product group from release/publication-policy.json"
rm -rf "${M2:?}/$GROUP_PATH"

# Tests just ran in this build: -DskipTests does not reach the Invoker
# integration tests, whose output never enters the payload, so they are skipped
# explicitly alongside the archetype ones.
./mvnw -ntp -B -Dmaven.repo.local="$M2" -DskipTests -Darchetype.test.skip=true -Dinvoker.skip=true -Prelease install

node release/snapshot-payload.mjs stage --local-repository "$M2" --sha "$SHA" --out "$OUT"
