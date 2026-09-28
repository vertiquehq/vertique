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
#   bash scripts/pit-pr-scope.sh --sandbox        # run PIT in a disposable container
#
# The change is the diff from the merge base to the working tree; `git add -N`
# a new file before running, untracked files are not seen. Undetected mutants
# never fail the script; a failed selection or PIT run does. Output goes to
# target/pit-pr/ and, in CI, to the job summary. Working from a fork, pass
# --base upstream/main (or whichever remote tracks the upstream repository).
#
# Mutants execute with real side effects (a mutated guard once deleted a
# module's source tree). Without --sandbox, run this only in a disposable
# checkout or CI, never as root and never in a checkout you cannot restore
# from git. With --sandbox, PIT runs in a throwaway Docker container against
# a snapshot of the working tree (scripts/pit-sandbox.sh).
#
# Environment:
#   PIT_MAX_CLASSES   class budget; larger changes are skipped (default 25)
#   PIT_MVN           Maven launcher (default ./mvnw, or scripts/pit-sandbox.sh with --sandbox)
#   PIT_ALLOW_ROOT=1  run as root anyway
#   PIT_EFFECTIVE_UID test seam replacing `id -u`

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELECTOR="$SCRIPT_DIR/pit-pr-scope.mjs"

base="origin/main"
dry_run=false
sandbox=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)
      [[ $# -ge 2 && -n "$2" ]] || { echo 'pit-pr-scope: --base requires a ref' >&2; exit 1; }
      base="$2"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    --sandbox) sandbox=true; shift ;;
    *) echo "pit-pr-scope: unrecognised argument: $1" >&2; exit 1 ;;
  esac
done

if [[ "$sandbox" != true && "${PIT_EFFECTIVE_UID:-$(id -u)}" == 0 && "${PIT_ALLOW_ROOT:-}" != 1 ]]; then
  echo 'pit-pr-scope: refusing to run as root: mutants run with real side effects (set PIT_ALLOW_ROOT=1 to override)' >&2
  exit 1
fi

cd "$(git rev-parse --show-toplevel)"

max_classes="${PIT_MAX_CLASSES:-25}"
if [[ "$sandbox" == true ]]; then
  mvn="${PIT_MVN:-$SCRIPT_DIR/pit-sandbox.sh}"
else
  mvn="${PIT_MVN:-./mvnw}"
fi
out_dir="target/pit-pr"
mkdir -p "$out_dir"
# Nothing from an earlier run may stand in for this one.
rm -f "$out_dir/changes.diff" "$out_dir/selection.json" "$out_dir/summary.md" "$out_dir/summary.json"

# The base must exist locally; fetch only when it is missing (a CI checkout
# with fetch-depth 0 already has it and may hold no credential to fetch with).
if ! git rev-parse --verify --quiet "$base^{commit}" >/dev/null; then
  git fetch --quiet --no-tags origin "${base#origin/}"
fi
merge_base="$(git merge-base "$base" HEAD)"

# Fixed prefixes and unquoted paths: the parser relies on git's defaults, and
# user settings such as diff.mnemonicPrefix must not change what is selected.
git -c core.quotePath=false diff -U0 --no-color --no-ext-diff --src-prefix=a/ --dst-prefix=b/ \
  "$merge_base" -- '*/src/main/java/*.java' > "$out_dir/changes.diff"

# Captured first: a failure inside a command substitution assignment stops the
# script, whereas a failing process substitution would be ignored.
selection="$(node "$SELECTOR" select --diff "$out_dir/changes.diff" --max-classes "$max_classes" --out "$out_dir/selection.json")"
run=false
modules=""
target_classes=""
while IFS='=' read -r key value; do
  case "$key" in
    run) run="$value" ;;
    modules) modules="$value" ;;
    targetClasses) target_classes="$value" ;;
  esac
done <<< "$selection"

if [[ "$run" == true ]]; then
  # -T 1: one module's PIT at a time. Parallel modules would share the runner's
  # CPUs, stretch test times, and turn survivors into timeouts (counted detected).
  command=("$mvn" -ntp -T 1 -pl "$modules" -am process-test-classes org.pitest:pitest-maven:mutationCoverage
    -Dpitest.skip=false "-DtargetClasses=$target_classes")
  if [[ "$dry_run" == true ]]; then
    # Shell-quoted, so a pasted command cannot expand the `$*` class patterns.
    printf '%q ' "${command[@]}"
    echo
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
