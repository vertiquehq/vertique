#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# RELEASE-001 — stages the derived public publication allowlist into a Maven
# repository (FR-REL-030, FR-REL-033, FR-REL-034, FR-REL-042; ADR-0007).
#
# Reactor `deploy` is NOT the publication boundary. The reactor is installed
# into an isolated local repository; this script then selects only the GAVs
# release/publication-policy.json derives and deploys each immutable payload
# unit through the pinned standard Maven Deploy Plugin. Existing aggregator,
# example and test-module deploy defaults therefore cannot decide publication.
#
# Credentials are never accepted as arguments: the target repository's
# credentials come from Maven settings/environment interpolation, keyed by
# --repository-id.
#
# Usage:
#   release/publish-artifacts.sh \
#     --local-repository <dir> \
#     --repository-id <maven server id> \
#     --target-url <deployment URL> \
#     --mode snapshot|final \
#     --version <version> \
#     [--settings <settings.xml>]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DEPLOY_PLUGIN="org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy-file"

LOCAL_REPOSITORY=""
REPOSITORY_ID=""
TARGET_URL=""
MODE="snapshot"
VERSION=""
SETTINGS=""

die() { echo "publish-artifacts: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --local-repository) LOCAL_REPOSITORY="${2:-}"; shift 2 ;;
    --repository-id)    REPOSITORY_ID="${2:-}";    shift 2 ;;
    --target-url)       TARGET_URL="${2:-}";       shift 2 ;;
    --mode)             MODE="${2:-}";             shift 2 ;;
    --version)          VERSION="${2:-}";          shift 2 ;;
    --settings)         SETTINGS="${2:-}";         shift 2 ;;
    *) die "unrecognised argument: $1" ;;
  esac
done

[[ -n "$LOCAL_REPOSITORY" ]] || die "--local-repository is required"
[[ -n "$REPOSITORY_ID" ]]    || die "--repository-id is required"
[[ -n "$TARGET_URL" ]]       || die "--target-url is required"
[[ -n "$VERSION" ]]          || die "--version is required"
[[ "$MODE" == "snapshot" || "$MODE" == "final" ]] || die "--mode must be snapshot or final"

# Fail closed before any deployment: a final release may never be cut from a
# SNAPSHOT version (FR-REL-042).
if [[ "$MODE" == "final" && "$VERSION" == *-SNAPSHOT ]]; then
  die "final mode refuses a SNAPSHOT version \"$VERSION\""
fi
if [[ "$MODE" == "snapshot" && "$VERSION" != *-SNAPSHOT ]]; then
  die "snapshot mode requires a -SNAPSHOT version, got \"$VERSION\""
fi

# Maven runs from a scratch directory (see DEPLOY_DIR), so a relative file: URL
# would stage into that directory and be deleted with it while reporting success.
if [[ "$TARGET_URL" == file:* && "$TARGET_URL" != file:///* ]]; then
  die "--target-url must be an absolute file:/// URL, got \"$TARGET_URL\""
fi

cd "$REPO_ROOT"

MVN="$REPO_ROOT/mvnw"
[[ -x "$MVN" ]] || MVN="mvn"

# The deploy invocations run from a directory outside the repository, so a
# relative --settings keeps meaning what it always meant: relative to the root.
[[ -z "$SETTINGS" || "$SETTINGS" == /* ]] || SETTINGS="$REPO_ROOT/$SETTINGS"

# deploy-file refuses to deploy a file that lives inside the local repository it
# was given ("Cannot deploy artifact from the local repository"), and every
# payload we deploy lives inside the isolated install repository by
# construction. So the deploy invocations get their own local repository, used
# only to resolve the deploy plugin itself — the payload paths are passed
# explicitly and are never resolved from it.
PLUGIN_REPO="$(mktemp -d "${TMPDIR:-/tmp}/vertique-deploy-plugins.XXXXXX")"

# deploy-file needs no project, but Maven started inside the repository still
# builds the reactor model before running it — resolving every import-scoped
# BOM through the empty PLUGIN_REPO and Central alone. A BOM published anywhere
# else (a SNAPSHOT line, or a release Central has not yet synced) then fails
# model building and nothing is deployed. From an empty directory Maven builds
# no model: deploy-file reads only the payload POM named by -DpomFile. It also
# finds no .mvn/maven.config, so anything the deploy needs from it is passed
# explicitly below.
DEPLOY_DIR="$(mktemp -d "${TMPDIR:-/tmp}/vertique-deploy-cwd.XXXXXX")"

# Resolve the immutable payload units. This fails closed if any allowlisted GAV
# is missing a required POM, primary, sources or Javadoc payload.
PLAN_FILE="$(mktemp "${TMPDIR:-/tmp}/vertique-deploy-plan.XXXXXX")"

# Registered after every path exists, so `set -u` cannot trip on an unset name
# while the trap runs.
trap 'rm -rf "$PLAN_FILE" "$PLUGIN_REPO" "$DEPLOY_DIR"' EXIT

node release/verify-publication.mjs \
  --deploy-plan "$LOCAL_REPOSITORY" \
  --version "$VERSION" \
  --mode "$MODE" > "$PLAN_FILE" \
  || die "could not resolve the deploy plan (see errors above)"

UNIT_COUNT="$(wc -l < "$PLAN_FILE" | tr -d ' ')"
[[ "$UNIT_COUNT" -gt 0 ]] || die "deploy plan is empty"
echo "publish-artifacts: deploying $UNIT_COUNT allowlisted GAVs to $REPOSITORY_ID ($MODE)"

# Convert NDJSON to tab-separated fields. Node is already a hard dependency of
# the release tooling, so this adds no new one.
PLAN_TSV="$(node -e '
  const fs = require("node:fs");
  for (const line of fs.readFileSync(process.argv[1], "utf8").split("\n")) {
    if (!line.trim()) continue;
    const u = JSON.parse(line);
    process.stdout.write([
      u.groupId, u.artifactId, u.version, u.packaging,
      u.files.pom ?? "", u.files.jar ?? "", u.files.sources ?? "", u.files.javadoc ?? "",
    ].join("\t") + "\n");
  }
' "$PLAN_FILE")"

deployed=0
while IFS=$'\t' read -r groupId artifactId version packaging pomFile jarFile sourcesFile javadocFile; do
  [[ -n "$groupId" ]] || continue

  # Argument array — never an eval'd command string.
  args=(
    "$DEPLOY_PLUGIN"
    -B -ntp
    # A single transient Bad Gateway from Central (plugin resolution) or the
    # target must not stop the loop part-way through a publication.
    "-Daether.connector.http.retryHandler.serviceUnavailable=429,502,503,504"
    "-Dmaven.repo.local=$PLUGIN_REPO"
    "-DrepositoryId=$REPOSITORY_ID"
    "-Durl=$TARGET_URL"
    "-DpomFile=$pomFile"
    "-DgroupId=$groupId"
    "-DartifactId=$artifactId"
    "-Dversion=$version"
    "-Dpackaging=$packaging"
  )

  if [[ "$packaging" == "pom" ]]; then
    args+=("-Dfile=$pomFile")
  else
    [[ -f "$jarFile" ]] || die "$artifactId: primary JAR missing at $jarFile"
    args+=("-Dfile=$jarFile")
    [[ -f "$sourcesFile" ]] && args+=("-Dsources=$sourcesFile")
    [[ -f "$javadocFile" ]] && args+=("-Djavadoc=$javadocFile")
  fi

  [[ -n "$SETTINGS" ]] && args+=(--settings "$SETTINGS")

  (cd "$DEPLOY_DIR" && "$MVN" "${args[@]}" -q) || die "deploy-file failed for $groupId:$artifactId:$version"
  deployed=$((deployed + 1))
done <<< "$PLAN_TSV"

echo "publish-artifacts: deployed $deployed/$UNIT_COUNT GAVs"
[[ "$deployed" == "$UNIT_COUNT" ]] || die "deployed $deployed of $UNIT_COUNT expected GAVs"
