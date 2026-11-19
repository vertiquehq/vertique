#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Checks Java formatting with the repository formatter.
#
# With FORMAT_BASE_REF set to a pull request's base branch, only the Java files
# that differ from origin/<base> are checked. Every push to main checks every
# file, so files a pull request does not touch are already formatted. A pull
# request that changes the root POM checks every file: the root POM owns the
# formatter version and configuration, so the change can reformat any file.
# Without FORMAT_BASE_REF, every file is checked.
#
# Usage: [FORMAT_BASE_REF=<base branch>] scripts/check-formatting.sh
set -euo pipefail

args=(-ntp spotless:check)
if [[ -n ${FORMAT_BASE_REF:-} ]]; then
  base="origin/${FORMAT_BASE_REF}"
  git rev-parse --verify --quiet "${base}^{commit}" >/dev/null || {
    echo "check-formatting: ${base} is not available; fetch the base branch" >&2
    exit 1
  }
  if git diff --quiet "$base" -- pom.xml; then
    args+=("-DratchetFrom=${base}")
  else
    echo "check-formatting: the root POM changed; checking every file" >&2
  fi
fi
exec ./mvnw "${args[@]}"
