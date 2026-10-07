#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Runs scripts/verify-public-references.sh over everything a pull request
# publishes: the lines its diff adds, its commit messages, and its title and
# description.
#
# The workflow passes the pull request's base and head commits and its title and
# description through environment variables, never through the command line or
# an interpolated script, so attacker-controlled text is only ever data here:
#
#   BASE_SHA, HEAD_SHA   full lowercase commit ids of the pull request's base and head
#   PR_TITLE, PR_BODY    the pull request title and description (the body may be unset or empty)
#
# Run from the repository root with the history of both commits fetched.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

: "${BASE_SHA:?BASE_SHA is required}"
: "${HEAD_SHA:?HEAD_SHA is required}"
: "${PR_TITLE:?PR_TITLE is required}"

[[ "$BASE_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo 'verify-pull-request-references: BASE_SHA must be a lowercase full SHA' >&2; exit 2; }
[[ "$HEAD_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo 'verify-pull-request-references: HEAD_SHA must be a lowercase full SHA' >&2; exit 2; }

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-pull-request-text-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

printf '%s\n\n%s\n' "$PR_TITLE" "${PR_BODY-}" > "$work_dir/pull-request.txt"

bash "$script_dir/verify-public-references.sh" \
    --range "$BASE_SHA...$HEAD_SHA" \
    --commits "$BASE_SHA..$HEAD_SHA" \
    --text "$work_dir/pull-request.txt" --label "pull request title and description"
