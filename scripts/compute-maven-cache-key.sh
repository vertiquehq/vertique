#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
set -euo pipefail

# Print a stable digest over the reactor's source pom.xml files, for use as a
# Maven dependency cache key in CI.
#
# GitHub's own hashFiles('**/pom.xml') is unsuitable here: it is a live
# expression re-evaluated wherever it appears, and the archetype and invoker
# integration tests generate their own pom.xml files under target/ mid-build.
# Evaluated again after the build, the glob's match set — and therefore the
# hash — has changed, so a restore step's key and a save step's key stop
# matching within the same run. This script computes the digest once, from
# only the checked-out source tree, so it is stable regardless of when or how
# many times it runs in a given workflow.
#
# Usage:
#   scripts/compute-maven-cache-key.sh
#
# Prints `value=<digest>` (GITHUB_OUTPUT key=value form, matching this
# repository's other entry-point scripts) so a workflow step need only
# `>> "$GITHUB_OUTPUT"` the result.

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

digest="$(
  find "$root" -name pom.xml \
      -not -path '*/target/*' \
      -not -path '*/.git/*' \
    | LC_ALL=C sort \
    | xargs shasum -a 256 \
    | shasum -a 256 \
    | cut -d ' ' -f 1
)"

echo "value=$digest"
