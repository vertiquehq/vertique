#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
set -euo pipefail

# Decide whether a pull request touches only documentation that no build step
# verifies, in which case the expensive Build and Test job is skipped (a job
# skipped via `if:` reports its required check as satisfied). The workflow
# delegates here so the decision is locally runnable and covered by the
# release contract tests.
#
# The allowlist is deliberately minimal in this repository: most Markdown here
# is EXECUTABLE documentation gated by integration tests —
# StarterFamilyDocumentationContractTest (README.md, docs/architecture.md,
# docs/modules.md, every canonical module.md), CodegenDocumentationPolicyTest
# (README.md, docs/packaging.md, named module.md resources), and the archetype
# contract tests (module READMEs). Skipping the build for those files would
# convert a pre-merge failure into a post-merge red main, which also blocks
# SNAPSHOT publication. Only prose no test reads may skip the build; extend
# the allowlist only after proving nothing in the reactor asserts on the path.

event_name=""
base_sha=""
head_sha=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --event-name) event_name="${2:-}"; shift 2 ;;
    --base-sha) base_sha="${2:-}"; shift 2 ;;
    --head-sha) head_sha="${2:-}"; shift 2 ;;
    *) echo "detect-docs-only-scope: unrecognised argument: $1" >&2; exit 1 ;;
  esac
done

[[ -n "$event_name" ]] || { echo 'detect-docs-only-scope: --event-name is required' >&2; exit 1; }

if [[ "$event_name" != 'pull_request' ]]; then
  # Pushes to main (and every other event) always run the full build.
  echo 'docs_only=false'
  exit 0
fi

[[ "$base_sha" =~ ^[0-9a-f]{40}$ ]] || { echo 'detect-docs-only-scope: --base-sha must be a lowercase full SHA' >&2; exit 1; }
[[ "$head_sha" =~ ^[0-9a-f]{40}$ ]] || { echo 'detect-docs-only-scope: --head-sha must be a lowercase full SHA' >&2; exit 1; }

# Three-dot diff: only the PR side's changes count. An advanced base branch is
# irrelevant here — the post-merge push to main rebuilds everything anyway.
# --no-renames lists both sides of a rename so neither endpoint escapes review.
changed="$(git diff --name-only --no-renames "$base_sha...$head_sha")"

if [[ -z "$changed" ]]; then
  # An empty diff is anomalous; never skip the build on an anomaly.
  echo 'docs_only=false'
  exit 0
fi

# Exit 0: some path is outside the allowlist. Exit 1: all paths allowlisted.
# Anything else is a grep failure and must never skip the build.
status=0
grep -Evq '^(CONTRIBUTING|SECURITY)\.md$|^LICENSES/|^NOTICE$' <<< "$changed" || status=$?
case "$status" in
  0) echo 'docs_only=false' ;;
  1) echo 'docs_only=true' ;;
  *) echo "detect-docs-only-scope: allowlist match failed (grep exit $status)" >&2; exit 1 ;;
esac
