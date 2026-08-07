#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
set -euo pipefail

# Decide whether a pull request touches only documentation, in which case the
# expensive Build and Test job is skipped (a job skipped via `if:` reports its
# required check as satisfied). The workflow delegates here so the decision is
# locally runnable and covered by the release contract tests.
#
# Only an explicit allowlist can skip the build: any path outside it — source,
# poms, workflows, scripts, this file itself — keeps the full build. Packaged
# module.md resources are deliberately on the allowlist: they cannot fail
# compilation, and the push to main after merge always runs the full build, so
# SNAPSHOT publication only ever trails a fully verified SHA.

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

if grep -Evq '^(docs|LICENSES)/|^NOTICE$|\.md$' <<< "$changed"; then
  echo 'docs_only=false'
else
  echo 'docs_only=true'
fi
