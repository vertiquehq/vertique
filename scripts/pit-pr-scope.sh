#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# PR-scoped mutation testing (advisory). Mutates only the production classes a
# change adds lines to in the pilot modules (scripts/pit-pr-scope.mjs), runs
# PIT on them, and reports the mutants on added lines that no unit test
# detects. Locally runnable with the exact CI semantics:
#
#   bash scripts/pit-pr-scope.sh                  # against origin/main
#   bash scripts/pit-pr-scope.sh --base <ref>     # against another base
#   bash scripts/pit-pr-scope.sh --dry-run        # print the Maven command only
#
# The change is the diff from the merge base to the working tree. Undetected
# mutants never fail the script; a failed PIT run does. Output goes to
# target/pit-pr/ and, in CI, to the job summary.
#
# Mutants execute with real side effects (a mutated guard once deleted a
# module's source tree), so run this only in a disposable checkout or CI,
# never as root and never in a checkout you cannot restore from git.
#
# Environment:
#   PIT_MAX_CLASSES   class budget; larger changes are skipped (default 25)
#   PIT_MVN           Maven launcher (default ./mvnw)
#   PIT_ALLOW_ROOT=1  run as root anyway
#   PIT_EFFECTIVE_UID test seam replacing `id -u`

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELECTOR="$SCRIPT_DIR/pit-pr-scope.mjs"

base="origin/main"
dry_run=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) base="${2:-}"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    *) echo "pit-pr-scope: unrecognised argument: $1" >&2; exit 1 ;;
  esac
done
[[ -n "$base" ]] || { echo 'pit-pr-scope: --base requires a ref' >&2; exit 1; }

if [[ "${PIT_EFFECTIVE_UID:-$(id -u)}" == 0 && "${PIT_ALLOW_ROOT:-}" != 1 ]]; then
  echo 'pit-pr-scope: refusing to run as root: mutants run with real side effects (set PIT_ALLOW_ROOT=1 to override)' >&2
  exit 1
fi

cd "$(git rev-parse --show-toplevel)"

max_classes="${PIT_MAX_CLASSES:-25}"
mvn="${PIT_MVN:-./mvnw}"
out_dir="target/pit-pr"
mkdir -p "$out_dir"

# The base must exist locally; fetch only when it is missing (a CI checkout
# with fetch-depth 0 already has it and may hold no credential to fetch with).
if ! git rev-parse --verify --quiet "$base^{commit}" >/dev/null; then
  git fetch --quiet --no-tags origin "${base#origin/}"
fi
merge_base="$(git merge-base "$base" HEAD)"

git diff -U0 --no-color --no-ext-diff "$merge_base" -- '*/src/main/java/*.java' > "$out_dir/changes.diff"

run=false
modules=""
target_classes=""
while IFS='=' read -r key value; do
  case "$key" in
    run) run="$value" ;;
    modules) modules="$value" ;;
    targetClasses) target_classes="$value" ;;
  esac
done < <(node "$SELECTOR" select --diff "$out_dir/changes.diff" --max-classes "$max_classes" --out "$out_dir/selection.json")

if [[ "$run" == true ]]; then
  command=("$mvn" -ntp -pl "$modules" -am process-test-classes org.pitest:pitest-maven:mutationCoverage
    -Dpitest.skip=false "-DtargetClasses=$target_classes")
  if [[ "$dry_run" == true ]]; then
    echo "${command[*]}"
    exit 0
  fi
  IFS=',' read -r -a selected_modules <<< "$modules"
  for module in "${selected_modules[@]}"; do
    rm -rf "$module/target/pit-reports"
  done
  if ! "${command[@]}"; then
    echo 'pit-pr-scope: PIT run failed; see the Maven output above' >&2
    exit 1
  fi
fi

node "$SELECTOR" report --diff "$out_dir/changes.diff" --selection "$out_dir/selection.json" --out-dir "$out_dir"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  cat "$out_dir/summary.md" >> "$GITHUB_STEP_SUMMARY"
fi
