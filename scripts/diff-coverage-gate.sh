#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Diff coverage gate: fails when the lines a change touches are covered below
# the threshold. Reads the merged JaCoCo XML that vertique-coverage-report
# produces during "mvnw verify" and compares against origin/main, so only
# changed lines are gated — legacy code is never punished, new code is always
# measured. Locally runnable with the exact CI semantics:
#
#   ./mvnw -ntp clean verify
#   bash scripts/diff-coverage-gate.sh
#
# Requires python3. The tool is installed into a private venv so nothing
# depends on the runner's (or developer's) global Python environment.

set -euo pipefail

XML="${1:-vertique-coverage-report/target/site/jacoco-aggregate/jacoco.xml}"
COMPARE_BRANCH="${DIFF_COVER_COMPARE_BRANCH:-origin/main}"
# 80 matches the project's standing coverage target (testing.md).
FAIL_UNDER="${DIFF_COVER_FAIL_UNDER:-80}"

if [[ ! -f "$XML" ]]; then
  echo "diff-coverage-gate: aggregate report not found: $XML" >&2
  echo "diff-coverage-gate: run './mvnw -ntp clean verify' first" >&2
  exit 1
fi

# The compare branch must exist locally; a shallow checkout would silently
# diff against the wrong base. Fetch only when it is missing — the CI checkout
# uses fetch-depth 0 (and may hold no credential to fetch with).
if ! git rev-parse --verify --quiet "$COMPARE_BRANCH" >/dev/null; then
  git fetch --quiet --no-tags origin "${COMPARE_BRANCH#origin/}"
fi

VENV="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/diff-cover-venv"
if [[ ! -x "$VENV/bin/diff-cover" ]]; then
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install --quiet 'diff_cover==9.*'
fi

# Paths outside the aggregate (examples, archetype resources, test sources)
# carry no coverage data and are excluded rather than reported as uncovered.
# The JSON report lands next to the aggregate XML; the Coverage Comment
# workflow turns it into a PR comment. diff-cover writes the report before
# applying the threshold, so the report exists even when the gate fails.
"$VENV/bin/diff-cover" "$XML" \
  --compare-branch "$COMPARE_BRANCH" \
  --fail-under "$FAIL_UNDER" \
  --json-report "$(dirname "$XML")/diff-cover.json" \
  --exclude 'examples/**' 'vertique-archetype/**' 'integration-tests/**' '**/src/test/**' '**/src/it/**'
