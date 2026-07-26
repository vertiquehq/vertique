#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Regression harness for scripts/verify-module-docs.sh.
#
# Each case materializes a synthetic repository tree (root pom.xml,
# vertique-bom/pom.xml, docs/modules.md, per-module pom.xml + canonical
# module.md) under a temporary directory and invokes the verifier against that
# fixture root. A case declares the outcome the verifier is contractually
# required to produce; the harness fails when the observed outcome differs.
#
# The final case runs the verifier against the real repository root, which must
# always pass.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/.." && pwd)"
verifier="$script_dir/verify-module-docs.sh"

if [[ ! -f "$verifier" ]]; then
    echo "Verifier under test is missing: $verifier" >&2
    exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-module-docs-tests-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

canonical_suffix="src/main/resources/META-INF/vertique/module.md"
unexpected_outcomes=0
executed_cases=0

# --- Fixture construction ---

fixture_root=""
fixture_bom_artifacts=()
fixture_root_artifacts=()
fixture_index_rows=()

# Starts a new fixture named $1 under the harness work directory.
fixture_reset() {
    fixture_root="$work_dir/$1"
    mkdir -p "$fixture_root/docs" "$fixture_root/vertique-bom"
    fixture_bom_artifacts=()
    fixture_root_artifacts=()
    fixture_index_rows=()
}

fixture_add_bom_artifact() {
    fixture_bom_artifacts+=("$1")
}

fixture_add_root_artifact() {
    fixture_root_artifacts+=("$1")
}

# Records an index row: $1 = artifactId, $2 = link target as written in the index.
fixture_add_index_row() {
    fixture_index_rows+=("$1|$2")
}

# Writes a module's pom.xml and canonical document.
# $1 = module path relative to the fixture root
# $2 = artifactId
# $3 = packaging (jar|pom|maven-archetype)
# $4 = document mode (present|empty|whitespace|missing)
fixture_write_module() {
    local module_path="$1"
    local artifact_id="$2"
    local packaging="$3"
    local document_mode="$4"
    local module_directory="$fixture_root/$module_path"
    local document="$module_directory/$canonical_suffix"

    mkdir -p "$module_directory"
    {
        printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
        printf '%s\n' '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        printf '%s\n' '    <modelVersion>4.0.0</modelVersion>'
        printf '%s\n' '    <groupId>dev.vertique</groupId>'
        printf '    <artifactId>%s</artifactId>\n' "$artifact_id"
        printf '    <packaging>%s</packaging>\n' "$packaging"
        printf '%s\n' '</project>'
    } > "$module_directory/pom.xml"

    case "$document_mode" in
        present)
            mkdir -p "$(dirname "$document")"
            {
                printf '# %s\n\n' "$artifact_id"
                printf '%s\n' 'Status: Stable'
            } > "$document"
            ;;
        empty)
            mkdir -p "$(dirname "$document")"
            : > "$document"
            ;;
        whitespace)
            mkdir -p "$(dirname "$document")"
            printf '   \n\n\t\n' > "$document"
            ;;
        missing) ;;
        *)
            echo "Unknown document mode: $document_mode" >&2
            exit 1
            ;;
    esac
}

# Adds an artifact that is in full BOM/root-pom/index/document parity.
# $1 = artifactId, $2 = module path, $3 = packaging (default jar),
# $4 = document mode (default present).
fixture_add_aligned_artifact() {
    local artifact_id="$1"
    local module_path="$2"
    local packaging="${3:-jar}"
    local document_mode="${4:-present}"

    fixture_add_bom_artifact "$artifact_id"
    fixture_add_root_artifact "$artifact_id"
    fixture_add_index_row "$artifact_id" "../$module_path/$canonical_suffix"
    fixture_write_module "$module_path" "$artifact_id" "$packaging" "$document_mode"
}

# Seeds the three-artifact baseline every case starts from.
fixture_add_baseline_artifacts() {
    fixture_add_aligned_artifact vertique-alpha vertique-alpha
    fixture_add_aligned_artifact vertique-beta-core vertique-beta/vertique-beta-core
    fixture_add_aligned_artifact vertique-gamma vertique-gamma
}

# Emits a third-party managed dependency whose exclusion carries a dev.vertique
# coordinate, proving the verifier's pom parser ignores exclusion blocks.
emit_decoy_dependency() {
    cat <<'DECOY'
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>example-library</artifactId>
                <version>1.0.0</version>
                <exclusions>
                    <exclusion>
                        <groupId>dev.vertique</groupId>
                        <artifactId>vertique-excluded-not-managed</artifactId>
                    </exclusion>
                </exclusions>
            </dependency>
DECOY
}

emit_managed_dependencies() {
    local artifact_id
    for artifact_id in "$@"; do
        printf '%s\n' '            <dependency>'
        printf '%s\n' '                <groupId>dev.vertique</groupId>'
        printf '                <artifactId>%s</artifactId>\n' "$artifact_id"
        # shellcheck disable=SC2016  # ${project.version} is literal pom text
        printf '%s\n' '                <version>${project.version}</version>'
        printf '%s\n' '            </dependency>'
    done
}

# Writes an aggregator-style pom with a <dependencyManagement> section.
# $1 = destination, $2 = artifactId, remaining args = managed dev.vertique artifacts.
write_managed_pom() {
    local destination="$1"
    local artifact_id="$2"
    shift 2

    mkdir -p "$(dirname "$destination")"
    {
        printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
        printf '%s\n' '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        printf '%s\n' '    <modelVersion>4.0.0</modelVersion>'
        printf '%s\n' '    <groupId>dev.vertique</groupId>'
        printf '    <artifactId>%s</artifactId>\n' "$artifact_id"
        printf '%s\n' '    <packaging>pom</packaging>'
        printf '%s\n' '    <dependencyManagement>'
        printf '%s\n' '        <dependencies>'
        emit_decoy_dependency
        emit_managed_dependencies "$@"
        printf '%s\n' '        </dependencies>'
        printf '%s\n' '    </dependencyManagement>'
        printf '%s\n' '</project>'
    } > "$destination"
}

# Materializes the fixture's root pom, BOM and module index.
fixture_finish() {
    local row
    local artifact_id
    local link

    write_managed_pom "$fixture_root/pom.xml" vertique-parent \
        ${fixture_root_artifacts[@]+"${fixture_root_artifacts[@]}"}
    write_managed_pom "$fixture_root/vertique-bom/pom.xml" vertique-bom \
        ${fixture_bom_artifacts[@]+"${fixture_bom_artifacts[@]}"}

    {
        printf '%s\n' '<!--'
        printf '%s\n' 'SPDX-FileCopyrightText: 2026 Koivisto Capital Oy'
        printf '%s\n' 'SPDX-License-Identifier: EUPL-1.2'
        printf '%s\n\n' '-->'
        printf '%s\n\n' '# Module documentation'
        printf '%s\n' '| Artifact | Documentation |'
        printf '%s\n' '|---|---|'
        for row in ${fixture_index_rows[@]+"${fixture_index_rows[@]}"}; do
            artifact_id="${row%%|*}"
            link="${row#*|}"
            # shellcheck disable=SC2016  # backticks are literal markdown, not a subshell
            printf '| `%s` | [module.md](%s) |\n' "$artifact_id" "$link"
        done
    } > "$fixture_root/docs/modules.md"
}

# --- Case execution ---

# $1 = case name, $2 = expected outcome (pass|fail), $3 = repository root,
# $4 = substring the verifier's diagnostics must contain (required for fail cases).
# Asserting the diagnostic keeps a case from passing for an incidental reason.
run_case() {
    local case_name="$1"
    local expected_outcome="$2"
    local target_root="$3"
    local expected_diagnostic="${4:-}"
    local output
    local status=0
    local observed_outcome
    local line

    executed_cases=$((executed_cases + 1))
    if output="$("$verifier" "$target_root" 2>&1)"; then
        observed_outcome=pass
    else
        status=$?
        observed_outcome=fail
    fi

    if [[ "$observed_outcome" != "$expected_outcome" ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-34s expected verifier to %s, but it %sed (exit %d)\n' \
            "$case_name" "$expected_outcome" "$observed_outcome" "$status"
        while IFS= read -r line; do
            [[ -n "$line" ]] && printf '        | %s\n' "$line"
        done <<< "$output"
        return 0
    fi

    if [[ -n "$expected_diagnostic" && "$output" != *"$expected_diagnostic"* ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-34s verifier %sed but never reported %s\n' \
            "$case_name" "$observed_outcome" "$expected_diagnostic"
        while IFS= read -r line; do
            [[ -n "$line" ]] && printf '        | %s\n' "$line"
        done <<< "$output"
        return 0
    fi

    if [[ "$observed_outcome" == fail ]]; then
        printf 'PASS  %-34s rejected: %s\n' "$case_name" \
            "$(printf '%s\n' "$output" | sed -n 's/^FAIL: //p' | head -1)"
    else
        printf 'PASS  %-34s accepted: %s\n' "$case_name" \
            "$(printf '%s\n' "$output" | head -1)"
    fi
    return 0
}

# --- Cases ---

# An index/BOM/root-pom/document set in full parity must pass.
fixture_reset aligned
fixture_add_baseline_artifacts
fixture_finish
run_case "aligned-fixture" pass "$fixture_root"

# An artifact managed in the BOM but absent from the index must fail.
fixture_reset bom-only-artifact
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-extra
fixture_add_root_artifact vertique-extra
fixture_finish
run_case "bom-only-artifact" fail "$fixture_root" \
    "vertique-extra is managed in vertique-bom/pom.xml but has no docs/modules.md row"

# An index row without a matching BOM entry must fail.
fixture_reset index-only-artifact
fixture_add_baseline_artifacts
fixture_add_root_artifact vertique-extra
fixture_add_index_row vertique-extra "../vertique-extra/$canonical_suffix"
fixture_write_module vertique-extra vertique-extra jar present
fixture_finish
run_case "index-only-artifact" fail "$fixture_root" \
    "vertique-extra has a docs/modules.md row but is not managed in vertique-bom/pom.xml"

# An index link whose target does not exist must fail.
fixture_reset missing-canonical-doc
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-delta vertique-delta jar missing
fixture_finish
run_case "missing-canonical-doc" fail "$fixture_root" \
    "vertique-delta has no canonical module document"

# A zero-byte canonical document must fail.
fixture_reset empty-canonical-doc
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-delta vertique-delta jar empty
fixture_finish
run_case "empty-canonical-doc" fail "$fixture_root" \
    "vertique-delta has an empty canonical module document"

# A whitespace-only canonical document must fail.
fixture_reset whitespace-canonical-doc
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-delta vertique-delta jar whitespace
fixture_finish
run_case "whitespace-canonical-doc" fail "$fixture_root" \
    "vertique-delta has an empty canonical module document"

# A packaging=pom aggregator must never enter the BOM/index set.
fixture_reset aggregator-row
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-beta vertique-beta pom present
fixture_finish
run_case "aggregator-row" fail "$fixture_root" \
    "vertique-beta is a packaging=pom aggregator"

# An archetype artifact must never enter the BOM/index set.
fixture_reset archetype-row
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-archetype vertique-archetype maven-archetype present
fixture_finish
run_case "archetype-row" fail "$fixture_root" \
    "archetype artifacts are not consumable framework modules"

# The codegen integration-test harness must never enter the BOM/index set.
fixture_reset codegen-integration-tests-row
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-codegen-integration-tests \
    vertique-codegen/vertique-codegen-integration-tests
fixture_finish
run_case "codegen-integration-tests-row" fail "$fixture_root" \
    "vertique-codegen-integration-tests must not be BOM-managed or indexed"

# The starter integration-test harness must never enter the BOM/index set.
fixture_reset starter-integration-tests-row
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-starter-integration-tests \
    vertique-starter/vertique-starter-integration-tests
fixture_finish
run_case "starter-integration-tests-row" fail "$fixture_root" \
    "vertique-starter-integration-tests must not be BOM-managed or indexed"

# A legacy centralized module document under docs/ must fail even though the
# link target resolves.
fixture_reset legacy-centralized-doc-path
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-legacy
fixture_add_root_artifact vertique-legacy
fixture_add_index_row vertique-legacy "../docs/modules/vertique-legacy.md"
fixture_write_module vertique-legacy vertique-legacy jar present
mkdir -p "$fixture_root/docs/modules"
printf '# vertique-legacy\n' > "$fixture_root/docs/modules/vertique-legacy.md"
fixture_finish
run_case "legacy-centralized-doc-path" fail "$fixture_root" \
    "vertique-legacy links to the legacy centralized module document"

# An artifact may occur only once in the index.
fixture_reset duplicate-index-row
fixture_add_baseline_artifacts
fixture_add_index_row vertique-alpha "../vertique-alpha/$canonical_suffix"
fixture_finish
run_case "duplicate-index-row" fail "$fixture_root" \
    "module index lists vertique-alpha more than once"

# A BOM-managed artifact that the root pom does not manage must fail.
fixture_reset root-pom-unmanaged-artifact
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-extra
fixture_add_index_row vertique-extra "../vertique-extra/$canonical_suffix"
fixture_write_module vertique-extra vertique-extra jar present
fixture_finish
run_case "root-pom-unmanaged-artifact" fail "$fixture_root" \
    "vertique-extra is managed in vertique-bom/pom.xml but not in the root pom.xml dependencyManagement"

# The pre-existing private-artifact rejection must be preserved.
fixture_reset private-artifact-row
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-audit-core vertique-audit/vertique-audit-core
fixture_finish
run_case "private-artifact-row" fail "$fixture_root" \
    "vertique-audit-core must not be BOM-managed or indexed: non-public artifact"

# The real repository must always be in full parity.
run_case "real-repository" pass "$repository_root"

# --- Summary ---

if (( unexpected_outcomes > 0 )); then
    printf '\n%d of %d verifier cases produced an unexpected outcome.\n' \
        "$unexpected_outcomes" "$executed_cases" >&2
    exit 1
fi

printf '\nAll %d verifier cases produced the expected outcome.\n' "$executed_cases"
