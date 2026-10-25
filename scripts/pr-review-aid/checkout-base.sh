#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Add a detached worktree of the comparison base next to the head checkout.
# BASE_SHA is the base branch tip and HEAD_SHA is the pull request head.
# The worktree is the merge base of those two commits, not the base branch
# tip: commits that landed on the base branch after the branch point must
# not be reported as changes the pull request reverted. BASE_DIR is the
# empty destination, usually under the runner temp directory.

set -euo pipefail

if [[ -z "${BASE_SHA:-}" ]]; then
  echo "checkout-base: BASE_SHA is required" >&2
  exit 1
fi
if [[ -z "${HEAD_SHA:-}" ]]; then
  echo "checkout-base: HEAD_SHA is required" >&2
  exit 1
fi
if [[ -z "${BASE_DIR:-}" ]]; then
  echo "checkout-base: BASE_DIR is required" >&2
  exit 1
fi

merge_base="$(git merge-base "$BASE_SHA" "$HEAD_SHA")"
git worktree add --detach "$BASE_DIR" "$merge_base"
