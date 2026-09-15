#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Applies a version to vertique-app-parent's literal <version>.
#
# vertique-app-parent is a third-party contract: it is published exactly as
# authored, with a literal version and none of the reactor's CI-friendly
# ${revision} machinery, so `-Drevision` cannot reach it. A final build
# therefore applies the final version to this one file in its disposable
# release workspace — the selected source commit itself is never edited
# (FR-REL-003), and the applied version is recorded by the certification. The
# post-release bump keeps the literal in step with the root <revision>.
#
# The replacement is count-checked: exactly two <version>…</version> elements
# hold the current literal — the artifact's own version and the vertique-bom
# import, which cannot use ${project.version} because that interpolates in the
# application declaring this parent — so a drifted POM fails loudly instead of
# being half-rewritten.
#
# Usage:
#   release/set-app-parent-version.sh --version <x.y.z[-SNAPSHOT]>

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM="$REPO_ROOT/vertique-app-parent/pom.xml"

die() { echo "set-app-parent-version: $*" >&2; exit 1; }

VERSION=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="${2:-}"; shift 2 ;;
    *) die "unrecognised argument: $1" ;;
  esac
done
[[ -n "$VERSION" ]] || die "--version is required"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]] || die "version must be x.y.z or x.y.z-SNAPSHOT, got \"$VERSION\""
[[ -f "$POM" ]] || die "missing $POM"

# The artifact's own version is the first <version> after its artifactId.
CURRENT="$(awk '/<artifactId>vertique-app-parent<\/artifactId>/{f=1} f && /<version>/{sub(/.*<version>/,""); sub(/<\/version>.*/,""); print; exit}' "$POM")"
[[ -n "$CURRENT" ]] || die "could not read vertique-app-parent's <version> from $POM"

OCCURRENCES="$(grep -c "<version>$CURRENT</version>" "$POM" || true)"
[[ "$OCCURRENCES" == "2" ]] || die "expected exactly two <version>$CURRENT</version> in $POM (own version + BOM import), found $OCCURRENCES"

if [[ "$CURRENT" == "$VERSION" ]]; then
  echo "set-app-parent-version: vertique-app-parent already at $VERSION"
  exit 0
fi

TMP="$(mktemp "${TMPDIR:-/tmp}/app-parent-pom.XXXXXX")"
sed "s|<version>$CURRENT</version>|<version>$VERSION</version>|" "$POM" > "$TMP"
mv "$TMP" "$POM"
echo "set-app-parent-version: vertique-app-parent $CURRENT -> $VERSION"
