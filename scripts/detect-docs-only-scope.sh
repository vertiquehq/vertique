#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
set -euo pipefail

# Classify pull requests for the full build, Markdown checks, or neither.
# The markdown_only output routes Markdown-only changes to the documentation lane.
# Other changes retain the
# existing allowlist behavior; uncertain paths and non-PR events run the build.
# The workflow delegates classification here for local contract testing.

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
  echo 'markdown_only=false'
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
  echo 'markdown_only=false'
  exit 0
fi

# Exit 0: some path is outside the allowlist. Exit 1: all paths allowlisted.
# Anything else is a grep failure and must never skip the build.
status=0
grep -Evq '^(CONTRIBUTING|SECURITY)\.md$|^LICENSES/|^NOTICE$' <<< "$changed" || status=$?
case "$status" in
  0) docs_only=false ;;
  1) docs_only=true ;;
  *) echo "detect-docs-only-scope: allowlist match failed (grep exit $status)" >&2; exit 1 ;;
esac

markdown_only=false
status=0
grep -Evq '\.md$' <<< "$changed" || status=$?
case "$status" in
  0) ;;
  1) markdown_only=true ;;
  *) echo "detect-docs-only-scope: Markdown match failed (grep exit $status)" >&2; exit 1 ;;
esac
if [[ "$markdown_only" == true ]]; then
  docs_only=false
fi
echo "docs_only=$docs_only"
echo "markdown_only=$markdown_only"
