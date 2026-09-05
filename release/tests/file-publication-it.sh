#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# RELEASE-001 S2 — public file-repository staging integration test
# (FR-REL-033, FR-REL-034, FR-REL-042; ADR-0007).
#
# Proves that an isolated build + the repository-owned publication tooling
# stage EXACTLY the derived allowlist into a `file://` repository, with the
# required payload set per packaging and literal consumable POM versions, and
# that final mode refuses any SNAPSHOT input.
#
# Reactor `deploy` is deliberately not exercised: publication eligibility comes
# from release/publication-policy.json, and staging goes through
# maven-deploy-plugin:deploy-file per unit (FR-REL-033).
#
# Usage:
#   bash release/tests/file-publication-it.sh            # full: install + stage + assert
#   VERTIQUE_IT_REUSE_REPO=<dir> bash release/tests/...  # reuse a prior isolated install
#
# Everything happens under a scratch directory; the source worktree must remain
# byte-clean throughout (FR-REL-003).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

FINAL_VERSION="0.2.0"
DEV_VERSION="0.2.0-SNAPSHOT"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/vertique-pub-it.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

LOCAL_REPO="${VERTIQUE_IT_REUSE_REPO:-$WORK_DIR/m2}"
STAGE_REPO="$WORK_DIR/staged"
mkdir -p "$LOCAL_REPO" "$STAGE_REPO"

fail() { echo "file-publication-it: FAIL: $*" >&2; exit 1; }
pass() { echo "file-publication-it: ok - $*"; }

# --- preflight -------------------------------------------------------------
# Checked before the expensive isolated install so a missing entry point fails
# in seconds rather than after a full reactor build.
for required in release/publish-artifacts.sh release/verify-publication.mjs release/publication-policy.json; do
  [[ -f "$required" ]] || fail "missing required entry point: $required"
done
grep -q '<id>release</id>' pom.xml || fail "root pom declares no 'release' profile for sources/Javadoc payloads"

# --- source cleanliness baseline (FR-REL-003) ------------------------------
BASELINE_STATUS="$(git status --porcelain)"

# --- install the reactor into an isolated local repository -----------------
# Tests are PUB-BUILD's job; this stage proves artifact SHAPE, so it skips them
# but must still produce sources and Javadoc payloads.
if [[ -z "${VERTIQUE_IT_REUSE_REPO:-}" ]]; then
  echo "file-publication-it: installing reactor at $FINAL_VERSION into isolated repository (this takes a while)..."
  # -Drevision is the whole point of FR-REL-003: the final version is supplied
  # at build time, so the selected source commit is never edited. Both public
  # parents declare their own <revision>, and a CLI property overrides both.
  # Archetype integration tests generate a project pinned to the development
  # line and build it, so they cannot also run against a final-version staging
  # build. They are functional proof of the archetypes and belong to PUB-BUILD
  # (`clean verify` at the development version); this stage proves artifact
  # SHAPE, so it skips them rather than pinning a version that breaks one mode.
  ./mvnw -ntp -q -B \
    -Dmaven.repo.local="$LOCAL_REPO" \
    -Drevision="$FINAL_VERSION" \
    -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
    -Darchetype.test.skip=true \
    -Prelease \
    clean install \
    || fail "isolated install failed"
fi

# --- stage the derived allowlist into a file:// repository ------------------
bash release/publish-artifacts.sh \
  --local-repository "$LOCAL_REPO" \
  --repository-id vertique-it \
  --target-url "file://$STAGE_REPO" \
  --mode final \
  --version "$FINAL_VERSION" \
  || fail "staging the allowlist to file:// failed"

# --- assertions -------------------------------------------------------------
EXPECTED_GAVS="$(node release/verify-publication.mjs --json | node -e '
  let s=""; process.stdin.on("data",d=>s+=d).on("end",()=>{
    const r=JSON.parse(s); console.log(r.published.length);
  });')"
[[ "$EXPECTED_GAVS" == "93" ]] || fail "expected 93 publishable GAVs, derived $EXPECTED_GAVS"

# deploysExactlyAllowlistedCoordinatesAndClassifiers
node release/verify-publication.mjs \
  --verify-staged "$STAGE_REPO" \
  --version "$FINAL_VERSION" \
  --mode final \
  || fail "deploysExactlyAllowlistedCoordinatesAndClassifiers"
pass "deploysExactlyAllowlistedCoordinatesAndClassifiers"

# containsLiteralConsumablePomVersions — no CI-friendly placeholder may survive
if grep -rl '\${revision}' "$STAGE_REPO" --include='*.pom' >/dev/null 2>&1; then
  grep -rl '\${revision}' "$STAGE_REPO" --include='*.pom' | head -5 >&2
  fail "containsLiteralConsumablePomVersions: unresolved \${revision} in staged POMs"
fi
pass "containsLiteralConsumablePomVersions"

# rejectsSnapshotForFinalMode — a SNAPSHOT anywhere in a staged POM's project,
# parent, dependency, dependencyManagement, plugin, extension or report version
# must abort final staging.
SNAPSHOT_STAGE="$WORK_DIR/staged-snapshot"
mkdir -p "$SNAPSHOT_STAGE"
if bash release/publish-artifacts.sh \
     --local-repository "$LOCAL_REPO" \
     --repository-id vertique-it \
     --target-url "file://$SNAPSHOT_STAGE" \
     --mode final \
     --version "$DEV_VERSION" >/dev/null 2>&1; then
  fail "rejectsSnapshotForFinalMode: final staging accepted a SNAPSHOT version"
fi
pass "rejectsSnapshotForFinalMode"

# overridesRevisionWithoutEditingSource (FR-REL-003) — the final version was
# supplied at build time; the source worktree must be byte-identical.
if [[ "$(git status --porcelain)" != "$BASELINE_STATUS" ]]; then
  git status --porcelain >&2
  fail "overridesRevisionWithoutEditingSource: source worktree was modified"
fi
pass "overridesRevisionWithoutEditingSource"

echo "file-publication-it: PASS"
