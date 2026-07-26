#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Proves exact set parity across the module-documentation surface:
#
#   (a) consumable dev.vertique artifacts managed in vertique-bom/pom.xml
#   (b) consumable dev.vertique artifacts managed in the root pom.xml
#   (c) artifact rows in docs/modules.md
#   (d) existing, non-empty canonical documents at each artifact's
#       <module-source-root>/src/main/resources/META-INF/vertique/module.md
#
# (a) and (c) must be equal sets, each artifact occurring exactly once; (a) must
# be managed by (b); every row in (c) must link directly to a packaged canonical
# document (d). Aggregators, archetypes, integration-test harnesses and
# non-public artifacts must never enter the set, and no index link may point at
# a legacy centralized module document under docs/.
#
# Usage: verify-module-docs.sh [repository-root]
# The repository root defaults to the script's parent directory; an explicit
# root exists so scripts/test-verify-module-docs.sh can drive synthetic fixtures.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if (( $# > 1 )); then
    echo "Usage: $0 [repository-root]" >&2
    exit 2
fi

repository_root_input="${1:-$script_dir/..}"
if [[ ! -d "$repository_root_input" ]]; then
    echo "Repository root does not exist: $repository_root_input" >&2
    exit 1
fi
repository_root="$(cd "$repository_root_input" && pwd)"

index="$repository_root/docs/modules.md"
bom_pom="$repository_root/vertique-bom/pom.xml"
root_pom="$repository_root/pom.xml"
canonical_suffix="/src/main/resources/META-INF/vertique/module.md"

for required_file in "$index" "$bom_pom" "$root_pom"; do
    if [[ ! -f "$required_file" ]]; then
        echo "Required file is missing: ${required_file#"$repository_root"/}" >&2
        exit 1
    fi
done

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-module-docs-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

failures=0

report_failure() {
    printf 'FAIL: %s\n' "$1" >&2
    failures=$((failures + 1))
}

# --- Parsing ---

# Prints the dev.vertique artifactIds managed in a pom's <dependencyManagement>
# section, one per line. Coordinates inside <exclusions> are ignored.
managed_vertique_artifacts() {
    awk '
        /<dependencyManagement>/ { inside = 1; next }
        inside == 0 { next }
        /<\/dependencyManagement>/ { inside = 0; next }
        /<exclusions>/ { excluded = 1 }
        /<\/exclusions>/ { excluded = 0; next }
        excluded { next }
        /<\/dependency>/ { pending = 0; next }
        /<groupId>[[:space:]]*dev\.vertique[[:space:]]*<\/groupId>/ { pending = 1; next }
        pending && /<artifactId>/ {
            value = $0
            sub(/^.*<artifactId>[[:space:]]*/, "", value)
            sub(/[[:space:]]*<\/artifactId>.*$/, "", value)
            if (value != "") { print value }
            pending = 0
        }
    ' "$1"
}

# Prints one tab-separated "artifactId<TAB>link" record per index row. A line
# that carries a module-document link but does not parse as an artifact row is
# emitted as "!unparsed!<TAB>line" so it can be rejected rather than skipped.
index_rows() {
    awk '
        {
            suspicious = ($0 ~ /\]\(/) && ($0 ~ /module\.md/)
            if ($0 !~ /^\|[[:space:]]*`/ || match($0, /`[^`]+`/) == 0) {
                if (suspicious) { print "!unparsed!\t" $0 }
                next
            }
            artifact = substr($0, RSTART + 1, RLENGTH - 2)
            rest = substr($0, RSTART + RLENGTH)
            if (match(rest, /\([^)]*\)/) == 0) {
                print "!unparsed!\t" $0
                next
            }
            print artifact "\t" substr(rest, RSTART + 1, RLENGTH - 2)
        }
    ' "$1"
}

# Prints a Maven module's declared packaging, or nothing when it defaults to jar.
module_packaging() {
    awk '
        /^[[:space:]]*<packaging>[^<]*<\/packaging>[[:space:]]*$/ {
            value = $0
            sub(/^[[:space:]]*<packaging>[[:space:]]*/, "", value)
            sub(/[[:space:]]*<\/packaging>.*$/, "", value)
            print value
            exit
        }
    ' "$1"
}

# Prints why an artifactId may never be BOM-managed or indexed, or returns 1
# when the artifact is a legitimate consumable module.
forbidden_artifact_reason() {
    case "$1" in
        *archetype*)
            printf 'archetype artifacts are not consumable framework modules'
            ;;
        vertique-starter-integration-tests | vertique-codegen-integration-tests)
            printf 'integration-test harness artifacts are not consumable framework modules'
            ;;
        vertique-audit* | vertique-blob* | vertique-camel* | *benchmarks*)
            printf 'non-public artifact'
            ;;
        *)
            return 1
            ;;
    esac
}

# --- Extraction ---

managed_vertique_artifacts "$bom_pom" > "$work_dir/bom-artifacts.txt"
managed_vertique_artifacts "$root_pom" > "$work_dir/root-artifacts.txt"
index_rows "$index" > "$work_dir/index-rows.txt"

if grep -q '^!unparsed!' "$work_dir/index-rows.txt"; then
    while IFS= read -r unparsed_line; do
        report_failure "module index line is not a parseable artifact row: ${unparsed_line#*$'\t'}"
    done < <(grep '^!unparsed!' "$work_dir/index-rows.txt")
fi

awk -F'\t' '$1 != "!unparsed!" { print $1 }' "$work_dir/index-rows.txt" \
    > "$work_dir/index-artifacts.txt"

LC_ALL=C sort "$work_dir/bom-artifacts.txt" > "$work_dir/bom-sorted.txt"
LC_ALL=C sort "$work_dir/root-artifacts.txt" > "$work_dir/root-sorted.txt"
LC_ALL=C sort "$work_dir/index-artifacts.txt" > "$work_dir/index-sorted.txt"
LC_ALL=C sort -u "$work_dir/bom-artifacts.txt" > "$work_dir/bom-unique.txt"
LC_ALL=C sort -u "$work_dir/root-artifacts.txt" > "$work_dir/root-unique.txt"
LC_ALL=C sort -u "$work_dir/index-artifacts.txt" > "$work_dir/index-unique.txt"

if [[ ! -s "$work_dir/index-unique.txt" ]]; then
    report_failure "module index declares no artifact rows"
fi

# --- Uniqueness ---

report_duplicates() {
    local source_label="$1"
    local sorted_file="$2"
    local duplicate

    while IFS= read -r duplicate; do
        [[ -n "$duplicate" ]] || continue
        report_failure "$source_label lists $duplicate more than once"
    done < <(uniq -d "$sorted_file")
}

report_duplicates "module index" "$work_dir/index-sorted.txt"
report_duplicates "vertique-bom/pom.xml" "$work_dir/bom-sorted.txt"
report_duplicates "root pom.xml" "$work_dir/root-sorted.txt"

# --- Set parity ---

report_set_difference() {
    local message="$1"
    local difference_file="$2"
    local artifact

    while IFS= read -r artifact; do
        [[ -n "$artifact" ]] || continue
        report_failure "$artifact $message"
    done < "$difference_file"
}

LC_ALL=C comm -23 "$work_dir/bom-unique.txt" "$work_dir/index-unique.txt" \
    > "$work_dir/bom-not-indexed.txt"
LC_ALL=C comm -13 "$work_dir/bom-unique.txt" "$work_dir/index-unique.txt" \
    > "$work_dir/indexed-not-bom.txt"
LC_ALL=C comm -23 "$work_dir/bom-unique.txt" "$work_dir/root-unique.txt" \
    > "$work_dir/bom-not-root-managed.txt"

report_set_difference "is managed in vertique-bom/pom.xml but has no docs/modules.md row" \
    "$work_dir/bom-not-indexed.txt"
report_set_difference "has a docs/modules.md row but is not managed in vertique-bom/pom.xml" \
    "$work_dir/indexed-not-bom.txt"
report_set_difference "is managed in vertique-bom/pom.xml but not in the root pom.xml dependencyManagement" \
    "$work_dir/bom-not-root-managed.txt"

# --- Artifact eligibility ---

LC_ALL=C sort -u "$work_dir/bom-unique.txt" "$work_dir/index-unique.txt" \
    > "$work_dir/all-artifacts.txt"

while IFS= read -r artifact_id; do
    [[ -n "$artifact_id" ]] || continue
    if reason="$(forbidden_artifact_reason "$artifact_id")"; then
        report_failure "$artifact_id must not be BOM-managed or indexed: $reason"
    fi
done < "$work_dir/all-artifacts.txt"

if grep -Eq 'vertique-(audit|blob|camel)|benchmarks' "$index"; then
    report_failure "module index references a non-public artifact"
    grep -nE 'vertique-(audit|blob|camel)|benchmarks' "$index" >&2 || true
fi

# --- Canonical documents ---

while IFS=$'\t' read -r artifact_id link; do
    [[ -n "$artifact_id" && "$artifact_id" != "!unparsed!" ]] || continue

    if [[ "$link" != ../* ]]; then
        report_failure "$artifact_id links to $link, which is not a repository-relative path"
        continue
    fi

    relative_link="${link#../}"

    if [[ "$relative_link" == docs/* ]]; then
        report_failure "$artifact_id links to the legacy centralized module document $link"
        continue
    fi

    if [[ "$relative_link" != *"$canonical_suffix" ]]; then
        report_failure "$artifact_id links to $link instead of a packaged ..$canonical_suffix document"
        continue
    fi

    module_source_root="${relative_link%"$canonical_suffix"}"
    if [[ -z "$module_source_root" || "$module_source_root" == *..* ]]; then
        report_failure "$artifact_id links to $link, which does not name a module source root"
        continue
    fi

    document="$repository_root/$relative_link"
    if [[ ! -f "$document" ]]; then
        report_failure "$artifact_id has no canonical module document at $relative_link"
        continue
    fi
    if ! grep -q '[^[:space:]]' "$document"; then
        report_failure "$artifact_id has an empty canonical module document at $relative_link"
        continue
    fi

    module_pom="$repository_root/$module_source_root/pom.xml"
    if [[ ! -f "$module_pom" ]]; then
        report_failure "$artifact_id has no owning Maven module at $module_source_root/pom.xml"
        continue
    fi

    packaging="$(module_packaging "$module_pom")"
    case "$packaging" in
        pom)
            report_failure "$artifact_id is a packaging=pom aggregator and must not be BOM-managed or indexed"
            ;;
        maven-archetype)
            report_failure "$artifact_id is an archetype and must not be BOM-managed or indexed"
            ;;
    esac
done < "$work_dir/index-rows.txt"

# --- Verdict ---

if (( failures > 0 )); then
    printf '\n%d module-documentation parity violation(s) found.\n' "$failures" >&2
    exit 1
fi

printf 'PASS: %d consumable artifacts in exact BOM/root-pom/index/document parity\n' \
    "$(wc -l < "$work_dir/index-unique.txt" | tr -d '[:space:]')"
