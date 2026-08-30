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
fixture_bom_pom_written=0

# Starts a new fixture named $1 under the harness work directory.
fixture_reset() {
    fixture_root="$work_dir/$1"
    mkdir -p "$fixture_root/docs" "$fixture_root/vertique-bom"
    fixture_bom_artifacts=()
    fixture_root_artifacts=()
    fixture_index_rows=()
    fixture_bom_pom_written=0
}

# Writes the fixture's BOM pom verbatim from standard input, replacing the one
# fixture_finish would generate. Used by cases whose BOM needs markup the
# generator cannot express, such as a CDATA usage example or a processing
# instruction. The case is then responsible for declaring every managed
# coordinate the index expects.
fixture_write_bom_pom() {
    mkdir -p "$fixture_root/vertique-bom"
    cat > "$fixture_root/vertique-bom/pom.xml"
    fixture_bom_pom_written=1
}

fixture_add_bom_artifact() {
    fixture_bom_artifacts+=("$1")
}

fixture_add_root_artifact() {
    fixture_root_artifacts+=("$1")
}

# Records a dependency entry that survives only inside a block comment. Maven
# ignores it, so the artifact is not managed and must not reach the parsed set.
fixture_add_commented_bom_artifact() {
    fixture_bom_artifacts+=("!commented!$1")
}

fixture_add_commented_root_artifact() {
    fixture_root_artifacts+=("!commented!$1")
}

# Records an index row: $1 = artifactId, $2 = link target as written in the index.
fixture_add_index_row() {
    fixture_index_rows+=("$1|$2")
}

# Emits a template-compliant canonical document for artifactId $1: the heading
# and the blockquote status header docs/module-doc-template.md mandates.
emit_compliant_document() {
    printf '# %s\n\n' "$1"
    printf '%s\n\n' '> **Status:** Stable'
    printf '%s\n' 'One-paragraph overview of what the module does.'
}

# Writes the canonical document $1 for artifactId $2 as a template-compliant
# baseline followed by the offending content read from standard input. Every
# content-rule fixture therefore differs from a clean document by exactly the one
# rule it is meant to trip, so a case cannot pass for an incidental reason.
write_document_with_violation() {
    local document="$1"
    local artifact_id="$2"

    mkdir -p "$(dirname "$document")"
    {
        emit_compliant_document "$artifact_id"
        printf '\n'
        cat
    } > "$document"
}

# Writes a module's canonical document.
# $1 = module path relative to the fixture root
# $2 = artifactId (document heading)
# $3 = document mode: present|empty|whitespace|missing, or one of the
#      content-rule violations adr-hyphen|adr-space|related-adrs-heading|
#      version-history|planned-additions|missing-status|invalid-status|unresolved-repo-path
fixture_write_document() {
    local module_path="$1"
    local artifact_id="$2"
    local document_mode="$3"
    local document="$fixture_root/$module_path/$canonical_suffix"

    case "$document_mode" in
        present)
            mkdir -p "$(dirname "$document")"
            emit_compliant_document "$artifact_id" > "$document"
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
        adr-hyphen)
            write_document_with_violation "$document" "$artifact_id" <<'DOC'
Dispatch ordering follows ADR-0192.
DOC
            ;;
        adr-space)
            write_document_with_violation "$document" "$artifact_id" <<'DOC'
Dispatch ordering follows ADR 0192.
DOC
            ;;
        related-adrs-heading)
            write_document_with_violation "$document" "$artifact_id" <<'DOC'
## Related ADRs

- Packaged documentation excludes private decision references.
DOC
            ;;
        version-history)
            write_document_with_violation "$document" "$artifact_id" <<'DOC'
## Version History

- Dispatch ordering became deterministic in the previous cycle.
DOC
            ;;
        planned-additions)
            write_document_with_violation "$document" "$artifact_id" <<'DOC'
## Planned Additions

- A batching dispatcher is under consideration.
DOC
            ;;
        missing-status)
            mkdir -p "$(dirname "$document")"
            {
                printf '# %s\n\n' "$artifact_id"
                printf '%s\n' 'One-paragraph overview of what the module does.'
            } > "$document"
            ;;
        invalid-status)
            mkdir -p "$(dirname "$document")"
            {
                printf '# %s\n\n' "$artifact_id"
                printf '%s\n\n' '> **Status:** Maintained'
                printf '%s\n' 'One-paragraph overview of what the module does.'
            } > "$document"
            ;;
        unresolved-repo-path)
            write_document_with_violation "$document" "$artifact_id" <<'DOC'
Packaging rules are described in `docs/nope.md`.
DOC
            ;;
        *)
            echo "Unknown document mode: $document_mode" >&2
            exit 1
            ;;
    esac
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

    fixture_write_document "$module_path" "$artifact_id" "$document_mode"
}

# Writes a module whose pom.xml is read verbatim from standard input, for pom
# shapes the fixed template above cannot express — XML comments and
# <dependencies> blocks that precede the project-level <artifactId>. The
# canonical document is always written in present mode.
# $1 = module path relative to the fixture root
# $2 = artifactId (document heading only; the pom on stdin declares identity)
fixture_write_module_with_pom() {
    local module_path="$1"
    local artifact_id="$2"
    local module_directory="$fixture_root/$module_path"

    mkdir -p "$module_directory"
    cat > "$module_directory/pom.xml"

    fixture_write_document "$module_path" "$artifact_id" present
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

# Emits one managed dev.vertique dependency per argument. An argument prefixed
# with "!commented!" is wrapped in a block comment so the entry is inert.
emit_managed_dependencies() {
    local artifact_id
    local commented
    for artifact_id in "$@"; do
        commented=0
        if [[ "$artifact_id" == '!commented!'* ]]; then
            commented=1
            artifact_id="${artifact_id#'!commented!'}"
            printf '%s\n' '            <!--'
        fi
        printf '%s\n' '            <dependency>'
        printf '%s\n' '                <groupId>dev.vertique</groupId>'
        printf '                <artifactId>%s</artifactId>\n' "$artifact_id"
        # shellcheck disable=SC2016  # ${project.version} is literal pom text
        printf '%s\n' '                <version>${project.version}</version>'
        printf '%s\n' '            </dependency>'
        if (( commented == 1 )); then
            printf '%s\n' '            -->'
        fi
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
    if (( fixture_bom_pom_written == 0 )); then
        write_managed_pom "$fixture_root/vertique-bom/pom.xml" vertique-bom \
            ${fixture_bom_artifacts[@]+"${fixture_bom_artifacts[@]}"}
    fi

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

print_indented_output() {
    local line
    while IFS= read -r line; do
        [[ -n "$line" ]] && printf '        | %s\n' "$line"
    done <<< "$1"
    return 0
}

# $1 = case name, $2 = expected outcome (pass|fail), $3 = repository root,
# $4 = substring the verifier's diagnostics must contain (required for fail cases),
# $5 = substring the verifier's diagnostics must never contain (optional).
# Asserting the diagnostic keeps a case from passing for an incidental reason;
# the forbidden substring pins a value that must not reach any diagnostic at all,
# which a positive assertion alone cannot prove.
run_case() {
    local case_name="$1"
    local expected_outcome="$2"
    local target_root="$3"
    local expected_diagnostic="${4:-}"
    local forbidden_diagnostic="${5:-}"
    local output
    local status=0
    local observed_outcome

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
        print_indented_output "$output"
        return 0
    fi

    if [[ -n "$expected_diagnostic" && "$output" != *"$expected_diagnostic"* ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-34s verifier %sed but never reported %s\n' \
            "$case_name" "$observed_outcome" "$expected_diagnostic"
        print_indented_output "$output"
        return 0
    fi

    if [[ -n "$forbidden_diagnostic" && "$output" == *"$forbidden_diagnostic"* ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-34s verifier %sed but surfaced %s\n' \
            "$case_name" "$observed_outcome" "$forbidden_diagnostic"
        print_indented_output "$output"
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

# A packaged document must not cite a private decision record: the artifact ships
# to application developers, and the decision log is not part of that contract.
# The hyphenated citation is the form the decision records themselves use.
fixture_reset adr-reference-hyphenated
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-delta vertique-delta jar adr-hyphen
fixture_finish
run_case "adr-reference-hyphenated" fail "$fixture_root" \
    "vertique-delta cites a private decision record in its canonical module document: Dispatch ordering follows ADR-0192."

# The same citation written with a space and no hyphen really occurs in prose, so
# matching only the hyphenated form would leave the rule trivially evadable.
fixture_reset adr-reference-spaced
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-delta vertique-delta jar adr-space
fixture_finish
run_case "adr-reference-spaced" fail "$fixture_root" \
    "vertique-delta cites a private decision record in its canonical module document: Dispatch ordering follows ADR 0192."

# A "## Related ADRs" section carries private decision material even when it
# quotes no record number, so the heading itself must be rejected.
fixture_reset related-adrs-section
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-delta vertique-delta jar related-adrs-heading
fixture_finish
run_case "related-adrs-section" fail "$fixture_root" \
    "vertique-delta has a forbidden section in its canonical module document: ## Related ADRs"

# Module documents are evergreen reference: change history lives in git.
fixture_reset version-history-section
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-delta vertique-delta jar version-history
fixture_finish
run_case "version-history-section" fail "$fixture_root" \
    "vertique-delta has a forbidden section in its canonical module document: ## Version History"

# Roadmap material is unshipped behavior and belongs in the private governance
# repository, not in an artifact an application already depends on.
fixture_reset planned-additions-section
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-delta vertique-delta jar planned-additions
fixture_finish
run_case "planned-additions-section" fail "$fixture_root" \
    "vertique-delta has a forbidden section in its canonical module document: ## Planned Additions"

# The template mandates the blockquote status header, which is how a reader learns
# whether the module's surface is safe to depend on.
fixture_reset missing-status-header
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-delta vertique-delta jar missing-status
fixture_finish
run_case "missing-status-header" fail "$fixture_root" \
    'vertique-delta has no valid "> **Status:**" blockquote'

fixture_reset invalid-status-value
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-delta vertique-delta jar invalid-status
fixture_finish
run_case "invalid-status-value" fail "$fixture_root" \
    'vertique-delta has no valid "> **Status:**" blockquote'

# A backticked repository-relative path that resolves to nothing is a link the
# reader cannot follow. The real-repository case below proves the same rule
# accepts the repository paths the shipped documents genuinely reference.
fixture_reset unresolved-repository-path
fixture_add_baseline_artifacts
fixture_add_aligned_artifact vertique-delta vertique-delta jar unresolved-repo-path
fixture_finish
run_case "unresolved-repository-path" fail "$fixture_root" \
    "vertique-delta references a repository path that does not exist in its canonical module document: docs/nope.md"

# Maintainer documentation moved to a private governance repository, so
# DEVELOPMENT.md is not a document whose content is checked but a file that must
# not exist here at all. The rule is repository-wide rather than per-artifact: a
# stray copy under a module that has no index row would otherwise go unseen.
fixture_reset maintainer-document-present
fixture_add_baseline_artifacts
printf '# vertique-alpha internals\n' > "$fixture_root/vertique-alpha/DEVELOPMENT.md"
fixture_finish
run_case "maintainer-document-present" fail "$fixture_root" \
    "vertique-alpha/DEVELOPMENT.md must not exist"

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

# Two aligned artifacts whose index links are crossed: every link resolves to an
# existing, non-empty document, yet neither row documents its own module. The
# verifier must bind each row to the artifactId its link's owning pom declares.
fixture_reset swapped-canonical-links
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-delta
fixture_add_root_artifact vertique-delta
fixture_add_bom_artifact vertique-epsilon
fixture_add_root_artifact vertique-epsilon
fixture_add_index_row vertique-delta "../vertique-epsilon/$canonical_suffix"
fixture_add_index_row vertique-epsilon "../vertique-delta/$canonical_suffix"
fixture_write_module vertique-delta vertique-delta jar present
fixture_write_module vertique-epsilon vertique-epsilon jar present
fixture_finish
run_case "swapped-canonical-links" fail "$fixture_root" \
    "vertique-delta links to a canonical document owned by vertique-epsilon"

# An artifact may occur only once in the index.
fixture_reset duplicate-index-row
fixture_add_baseline_artifacts
fixture_add_index_row vertique-alpha "../vertique-alpha/$canonical_suffix"
fixture_finish
run_case "duplicate-index-row" fail "$fixture_root" \
    "module index lists vertique-alpha more than once"

# Two distinct artifacts pointing at one canonical document, where the second
# module ships no document of its own: per-row existence checks all succeed and
# the artifact sets stay in exact parity, so only link uniqueness can catch that
# vertique-epsilon has no packaged canonical document at all.
fixture_reset duplicate-canonical-link
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-delta
fixture_add_root_artifact vertique-delta
fixture_add_bom_artifact vertique-epsilon
fixture_add_root_artifact vertique-epsilon
fixture_add_index_row vertique-delta "../vertique-delta/$canonical_suffix"
fixture_add_index_row vertique-epsilon "../vertique-delta/$canonical_suffix"
fixture_write_module vertique-delta vertique-delta jar present
fixture_write_module vertique-epsilon vertique-epsilon jar missing
fixture_finish
run_case "duplicate-canonical-link" fail "$fixture_root" \
    "module index canonical link lists ../vertique-delta/$canonical_suffix more than once"

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

# A commented-out copy of a sibling's coordinates must never supply a module's
# identity. The vertique-alpha row links at vertique-beta-core's document, and
# that pom carries vertique-alpha's coordinates inside a multi-line comment
# ahead of its own <artifactId>. Reading the pom line by line binds the row to
# the commented decoy, accepts the mismatched link, and leaves vertique-alpha
# with no canonical document of its own — the acceptance direction of the bug.
fixture_reset commented-decoy-owner
fixture_add_aligned_artifact vertique-gamma vertique-gamma
fixture_add_bom_artifact vertique-alpha
fixture_add_root_artifact vertique-alpha
fixture_add_index_row vertique-alpha "../vertique-beta/vertique-beta-core/$canonical_suffix"
fixture_write_module_with_pom vertique-beta/vertique-beta-core vertique-beta-core <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <!--
    Superseded coordinates, retained for reference:
        <groupId>dev.vertique</groupId>
        <artifactId>vertique-alpha</artifactId>
    -->
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-beta-core</artifactId>
    <packaging>jar</packaging>
</project>
POM
fixture_finish
run_case "commented-decoy-owner-accepted" fail "$fixture_root" \
    "vertique-alpha links to a canonical document owned by vertique-beta-core"

# A schema-valid pom may declare <dependencies> before the project-level
# <artifactId>. The row is correct and the module is genuinely documented, so
# binding the first dependency's artifactId would reject a compliant module.
fixture_reset dependencies-before-artifact-id
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-delta
fixture_add_root_artifact vertique-delta
fixture_add_index_row vertique-delta "../vertique-delta/$canonical_suffix"
fixture_write_module_with_pom vertique-delta vertique-delta <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.vertique</groupId>
    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>example-library</artifactId>
            <version>1.0.0</version>
        </dependency>
    </dependencies>
    <artifactId>vertique-delta</artifactId>
    <packaging>jar</packaging>
</project>
POM
fixture_finish
run_case "dependencies-before-artifact-id" pass "$fixture_root" \
    "PASS: 4 consumable artifacts"

# A multi-line comment recording a former coordinate must not be mistaken for
# the module's identity: the row is correct and the module must be accepted.
fixture_reset multiline-comment-before-artifact-id
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-epsilon
fixture_add_root_artifact vertique-epsilon
fixture_add_index_row vertique-epsilon "../vertique-epsilon/$canonical_suffix"
fixture_write_module_with_pom vertique-epsilon vertique-epsilon <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <!--
    Renamed before the first release; the previous coordinates were
        <artifactId>vertique-epsilon-legacy</artifactId>
    -->
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-epsilon</artifactId>
    <packaging>jar</packaging>
</project>
POM
fixture_finish
run_case "multiline-comment-before-artifact-id" pass "$fixture_root" \
    "PASS: 4 consumable artifacts"

# Packaging must not be read out of a comment either. This module really
# declares <packaging>pom</packaging>, but an earlier comment quotes the jar
# packaging it shipped with before the family gained variants. A line-oriented
# scan binds the commented value and admits an aggregator into the consumable
# set — the same silent-acceptance direction as the commented owner decoy.
fixture_reset commented-decoy-packaging
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-beta
fixture_add_root_artifact vertique-beta
fixture_add_index_row vertique-beta "../vertique-beta/$canonical_suffix"
fixture_write_module_with_pom vertique-beta vertique-beta <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-beta</artifactId>
    <!--
    Published on its own before the family gained variants:
        <packaging>jar</packaging>
    -->
    <packaging>pom</packaging>
</project>
POM
fixture_finish
run_case "commented-decoy-packaging-accepted" fail "$fixture_root" \
    "vertique-beta is a packaging=pom aggregator"

# A plugin may take a <packaging> parameter of its own — maven-install-plugin
# does — and the pom schema lets <build> precede the project's own <packaging>.
# The project is an aggregator, so binding the plugin's nested parameter admits
# it as a consumable jar.
fixture_reset nested-packaging-before-project
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-epsilon
fixture_add_root_artifact vertique-epsilon
fixture_add_index_row vertique-epsilon "../vertique-epsilon/$canonical_suffix"
fixture_write_module_with_pom vertique-epsilon vertique-epsilon <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-epsilon</artifactId>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-install-plugin</artifactId>
                <configuration>
                    <packaging>jar</packaging>
                </configuration>
            </plugin>
        </plugins>
    </build>
    <packaging>pom</packaging>
</project>
POM
fixture_finish
run_case "nested-packaging-before-project" fail "$fixture_root" \
    "vertique-epsilon is a packaging=pom aggregator"

# Commenting a dependency out of both the BOM and the root pom unmanages the
# artifact, but the index row, module directory and canonical document all
# survive the deletion. Reading the poms line by line still emits the commented
# coordinate, so both set-parity comparisons balance and the verifier certifies
# exact parity for an artifact nothing actually manages.
fixture_reset commented-bom-dependency
fixture_add_baseline_artifacts
fixture_add_commented_bom_artifact vertique-zeta
fixture_add_commented_root_artifact vertique-zeta
fixture_add_index_row vertique-zeta "../vertique-zeta/$canonical_suffix"
fixture_write_module vertique-zeta vertique-zeta jar present
fixture_finish
run_case "commented-bom-dependency-with-stale-index-row" fail "$fixture_root" \
    "vertique-zeta has a docs/modules.md row but is not managed in vertique-bom/pom.xml"

# CDATA may carry tag-shaped text. Here it closes <description> and <project>
# and reopens a <project> wrapper, so a depth-tracking scan that treats CDATA as
# markup surfaces the quoted <artifactId> at depth 1 and binds a value that is
# not the module's identity at all. The scanner must refuse the pom instead.
fixture_reset cdata-in-module-pom
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-theta
fixture_add_root_artifact vertique-theta
fixture_add_index_row vertique-theta "../vertique-theta/$canonical_suffix"
fixture_write_module_with_pom vertique-theta vertique-theta <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.vertique</groupId>
    <description><![CDATA[Renamed 1.0 > 2.0. </description></project><project><artifactId>vertique-theta</artifactId>]]></description>
    <artifactId>vertique-theta-real</artifactId>
    <packaging>jar</packaging>
</project>
POM
fixture_finish
run_case "cdata-in-module-pom" fail "$fixture_root" \
    "cannot be parsed reliably: it contains a CDATA section"

# A ">" inside a quoted attribute value is legal XML, but it truncates the tag
# for any scanner that ends a tag at the first ">". The truncated self-closing
# element then reads as an opening tag and inflates the depth count for the rest
# of the pom. The scanner must refuse the pom rather than bind a mis-parsed
# value or silently report no artifactId at all.
fixture_reset quoted-gt-in-attribute
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-eta
fixture_add_root_artifact vertique-eta
fixture_add_index_row vertique-eta "../vertique-eta/$canonical_suffix"
fixture_write_module_with_pom vertique-eta vertique-eta <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.vertique</groupId>
    <build>
        <plugins>
            <plugin>
                <artifactId>maven-checkstyle-plugin</artifactId>
                <configuration>
                    <suppress files="src/generated" message="line is > 120 characters"/>
                </configuration>
            </plugin>
        </plugins>
    </build>
    <artifactId>vertique-eta</artifactId>
    <packaging>jar</packaging>
</project>
POM
fixture_finish
run_case "quoted-gt-in-attribute" fail "$fixture_root" \
    "cannot be parsed reliably: it contains a greater-than sign inside a quoted attribute value"

# A comment that never closes swallows the rest of the pom, so everything after
# it — including the </project> that ends the document — is invisible to the
# parser. The scanner must refuse the pom by name rather than report on the
# truncated fragment it managed to read, and the coordinate quoted inside the
# unterminated comment must not reach any diagnostic.
fixture_reset unterminated-comment
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-iota
fixture_add_root_artifact vertique-iota
fixture_add_index_row vertique-iota "../vertique-iota/$canonical_suffix"
fixture_write_module_with_pom vertique-iota vertique-iota <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-iota</artifactId>
    <packaging>jar</packaging>
    <!-- Superseded coordinates, kept for reference:
        <artifactId>vertique-phantom</artifactId>
        <packaging>pom</packaging>
</project>
POM
fixture_finish
run_case "unterminated-comment-in-module-pom" fail "$fixture_root" \
    "cannot be parsed reliably: it contains an unterminated XML comment" \
    "vertique-phantom"

# A BOM that documents its own usage in a CDATA description is well-formed XML
# that Maven accepts, so the build cannot catch this: the example's coordinates
# read as real managed entries. With a stale index row and the module directory
# and document still on disk, every parity comparison balances and the verifier
# certifies an artifact the BOM does not actually manage.
fixture_reset cdata-in-bom-pom
fixture_add_baseline_artifacts
fixture_add_root_artifact vertique-phantom-bom
fixture_add_index_row vertique-phantom-bom "../vertique-phantom-bom/$canonical_suffix"
fixture_write_module vertique-phantom-bom vertique-phantom-bom jar present
fixture_write_bom_pom <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-bom</artifactId>
    <packaging>pom</packaging>
    <description><![CDATA[
        Import this BOM, then declare modules without versions:
        <dependencyManagement>
            <dependencies>
                <dependency>
                    <groupId>dev.vertique</groupId>
                    <artifactId>vertique-phantom-bom</artifactId>
                </dependency>
            </dependencies>
        </dependencyManagement>
    ]]></description>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>dev.vertique</groupId>
                <artifactId>vertique-alpha</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>dev.vertique</groupId>
                <artifactId>vertique-beta-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>dev.vertique</groupId>
                <artifactId>vertique-gamma</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
POM
fixture_finish
run_case "cdata-in-bom-pom" fail "$fixture_root" \
    "vertique-bom/pom.xml cannot be parsed reliably: it contains a CDATA section"

# A processing instruction's data may carry markup, and Maven ignores the whole
# instruction. A line-oriented scan does not, so the coordinates quoted inside it
# become managed entries backing a stale index row.
fixture_reset pi-markup-in-bom-pom
fixture_add_baseline_artifacts
fixture_add_root_artifact vertique-phantom-pi
fixture_add_index_row vertique-phantom-pi "../vertique-phantom-pi/$canonical_suffix"
fixture_write_module vertique-phantom-pi vertique-phantom-pi jar present
fixture_write_bom_pom <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-bom</artifactId>
    <packaging>pom</packaging>
    <dependencyManagement>
        <dependencies>
            <?release-tooling
                <groupId>dev.vertique</groupId>
                <artifactId>vertique-phantom-pi</artifactId>
            ?>
            <dependency>
                <groupId>dev.vertique</groupId>
                <artifactId>vertique-alpha</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>dev.vertique</groupId>
                <artifactId>vertique-beta-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>dev.vertique</groupId>
                <artifactId>vertique-gamma</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
POM
fixture_finish
run_case "pi-markup-in-bom-pom" fail "$fixture_root" \
    "vertique-bom/pom.xml cannot be parsed reliably: it contains a processing instruction"

# An <artifactId> whose closing tag was lost leaves the element open. The
# line-oriented scan still yields a value for it, so the truncated coordinate
# enters the managed set and backs a stale index row.
fixture_reset unterminated-tag-in-bom-pom
fixture_add_baseline_artifacts
fixture_add_root_artifact vertique-phantom-tag
fixture_add_index_row vertique-phantom-tag "../vertique-phantom-tag/$canonical_suffix"
fixture_write_module vertique-phantom-tag vertique-phantom-tag jar present
fixture_write_bom_pom <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-bom</artifactId>
    <packaging>pom</packaging>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>dev.vertique</groupId>
                <artifactId>vertique-phantom-tag
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>dev.vertique</groupId>
                <artifactId>vertique-alpha</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>dev.vertique</groupId>
                <artifactId>vertique-beta-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>dev.vertique</groupId>
                <artifactId>vertique-gamma</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
POM
fixture_finish
run_case "unterminated-tag-in-bom-pom" fail "$fixture_root" \
    "vertique-bom/pom.xml cannot be parsed reliably: it contains an element that is never closed"

# Both extractions stop at the value they were looking for, so an unsupported
# construct that appears after <artifactId> and <packaging> is never examined and
# the module is accepted on a document the scanner cannot actually read.
fixture_reset unsupported-construct-after-value
fixture_add_baseline_artifacts
fixture_add_bom_artifact vertique-kappa
fixture_add_root_artifact vertique-kappa
fixture_add_index_row vertique-kappa "../vertique-kappa/$canonical_suffix"
fixture_write_module_with_pom vertique-kappa vertique-kappa <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-kappa</artifactId>
    <packaging>jar</packaging>
    <build>
        <plugins>
            <plugin>
                <artifactId>maven-checkstyle-plugin</artifactId>
                <configuration>
                    <suppress files="src/generated" message="line is > 120 characters"/>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
POM
fixture_finish
run_case "unsupported-construct-after-extracted-value" fail "$fixture_root" \
    "vertique-kappa/pom.xml cannot be parsed reliably: it contains a greater-than sign inside a quoted attribute value"

# The real repository must always be in full parity.
run_case "real-repository" pass "$repository_root"

# --- Summary ---

if (( unexpected_outcomes > 0 )); then
    printf '\n%d of %d verifier cases produced an unexpected outcome.\n' \
        "$unexpected_outcomes" "$executed_cases" >&2
    exit 1
fi

printf '\nAll %d verifier cases produced the expected outcome.\n' "$executed_cases"
