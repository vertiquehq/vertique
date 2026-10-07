#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Regression harness for scripts/verify-public-references.sh and for the
# commit-msg hook that delegates to it.
#
# Each case builds a synthetic git repository under a temporary directory,
# commits a baseline, adds the content under test, and runs the verifier (or the
# hook) against it. A case declares the exit status and a diagnostic the tool is
# contractually required to produce; the harness fails when either differs.
#
# This file is exempt from the verifier it tests, because it has to spell out
# the forbidden forms as fixtures.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
verifier="$script_dir/verify-public-references.sh"
hook="$script_dir/commit-msg"
pull_request_wrapper="$script_dir/verify-pull-request-references.sh"

for required in "$verifier" "$hook" "$pull_request_wrapper"; do
    if [[ ! -f "$required" ]]; then
        echo "Script under test is missing: $required" >&2
        exit 1
    fi
done

# Synthetic repositories must not inherit the machine's signing, hook or
# identity configuration: a signing prompt would hang the harness.
export GIT_CONFIG_GLOBAL=/dev/null
export GIT_CONFIG_NOSYSTEM=1

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-public-references-tests-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

unexpected_outcomes=0
executed_cases=0
repo=""

# --- Fixture construction ---

# Starts a fixture repository named $1 with a clean baseline commit tagged base.
repo_reset() {
    repo="$work_dir/$1"
    mkdir -p "$repo"
    git -C "$repo" init -q -b main
    git -C "$repo" config user.name "Harness"
    git -C "$repo" config user.email "harness@example.invalid"
    git -C "$repo" config commit.gpgsign false
    git -C "$repo" config core.hooksPath /nonexistent
    put_file README.md <<'EOF'
# Fixture

Plain documentation.
EOF
    commit_all "chore: baseline"
    git -C "$repo" tag base
}

# Writes the file $1 (relative to the fixture) from standard input.
put_file() {
    mkdir -p "$(dirname "$repo/$1")"
    cat > "$repo/$1"
}

# Commits every change in the fixture with message $1 (a second argument adds a body).
commit_all() {
    git -C "$repo" add -A
    if [[ $# -ge 2 ]]; then
        git -C "$repo" commit -q --allow-empty -m "$1" -m "$2"
    else
        git -C "$repo" commit -q --allow-empty -m "$1"
    fi
}

# Adds a commit holding a clean change, so a case can place violations in
# earlier history than the range under test.
commit_clean_change() {
    put_file "clean-$RANDOM.txt" <<'EOF'
nothing to see
EOF
    commit_all "chore: clean change"
}

# --- Case execution ---

print_indented_output() {
    local output="$1"
    [[ -n "$output" ]] || return 0
    printf '%s\n' "$output" | sed 's/^/        /'
}

# run_case <name> <expected-exit> <expected-diagnostic|-> <forbidden-diagnostic|-> <args...>
# Runs the verifier with <args> from inside the current fixture.
run_case() {
    local case_name="$1"
    local expected_exit="$2"
    local expected_diagnostic="$3"
    local forbidden_diagnostic="$4"
    shift 4
    local output status=0

    executed_cases=$((executed_cases + 1))
    output="$(cd "$repo" && bash "$verifier" "$@" 2>&1)" || status=$?
    judge_case "$case_name" "$expected_exit" "$expected_diagnostic" "$forbidden_diagnostic" "$status" "$output"
}

judge_case() {
    local case_name="$1" expected_exit="$2" expected_diagnostic="$3" forbidden_diagnostic="$4"
    local status="$5" output="$6"

    if [[ "$status" != "$expected_exit" ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-44s expected exit %s, got %s\n' "$case_name" "$expected_exit" "$status"
        print_indented_output "$output"
        return 0
    fi
    if [[ "$expected_diagnostic" != "-" && "$output" != *"$expected_diagnostic"* ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-44s never reported: %s\n' "$case_name" "$expected_diagnostic"
        print_indented_output "$output"
        return 0
    fi
    if [[ "$forbidden_diagnostic" != "-" && "$output" == *"$forbidden_diagnostic"* ]]; then
        unexpected_outcomes=$((unexpected_outcomes + 1))
        printf 'FAIL  %-44s surfaced: %s\n' "$case_name" "$forbidden_diagnostic"
        print_indented_output "$output"
        return 0
    fi
    printf 'ok    %s\n' "$case_name"
}

# Shorthands over a range from the baseline tag to the current head.
expect_clean() {
    local case_name="$1"
    shift
    run_case "$case_name" 0 "PASS:" "FAIL:" "$@"
}

expect_finding() {
    local case_name="$1" diagnostic="$2"
    shift 2
    run_case "$case_name" 1 "$diagnostic" "-" "$@"
}

# --- Added-line scanning: Java comments under src/main ---

repo_reset clean-diff
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

/** Plain behavior description. */
public final class X {
    // Explains the shipped behavior in plain words.
    int value = 1;
}
EOF
put_file docs/guide.md <<'EOF'
# Guide

Shipped behavior only. SHA-256 digests, UTF-8 text and HTTP-2 are fine.
EOF
commit_all "docs: add guide"
expect_clean "clean-diff" --range base..HEAD

repo_reset java-comment-adr
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

// Chosen per ADR-0255 for compatibility.
public final class X {}
EOF
commit_all "feat: add x"
expect_finding "java-line-comment-adr" \
    "FAIL: vertique-x/src/main/java/dev/vertique/X.java:3: adr-citation:" --range base..HEAD

repo_reset java-javadoc-adr-spaced
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

/**
 * Behaves as ADR 0255 describes.
 */
public final class X {}
EOF
commit_all "feat: add x"
expect_finding "java-javadoc-star-adr-spaced" \
    "X.java:4: adr-citation:" --range base..HEAD

repo_reset java-block-comment-delivery
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

/* Repaired in T003. */
public final class X {}
EOF
commit_all "fix: x"
expect_finding "java-block-comment-delivery-id" \
    "X.java:3: delivery-identifier:" --range base..HEAD

repo_reset java-comment-spec-id
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

// Added by security-001-typed-access-policies work.
public final class X {}
EOF
commit_all "feat: x"
expect_finding "java-comment-spec-package-name" \
    "X.java:3: spec-identifier:" --range base..HEAD

repo_reset java-comment-governance-path
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

// Contract lives in docs/specs/foo/contract.md.
public final class X {}
EOF
commit_all "feat: x"
expect_finding "java-comment-governance-path" \
    "X.java:3: vertique-dev-reference:" --range base..HEAD

repo_reset java-comment-worktree
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

// Reproduced under .worktrees/scratch.
public final class X {}
EOF
commit_all "feat: x"
expect_finding "java-comment-worktree-path" \
    "X.java:3: worktree-path:" --range base..HEAD

repo_reset java-comment-contract-section
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

// Mirrors contract §4 of the feature.
public final class X {}
EOF
commit_all "feat: x"
expect_finding "java-comment-contract-section" \
    "X.java:3: contract-section:" --range base..HEAD

repo_reset java-comment-vertique-dev
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

// Tracked in vertiquehq/vertique-dev#12.
public final class X {}
EOF
commit_all "feat: x"
expect_finding "java-comment-vertique-dev" \
    "X.java:3: vertique-dev-reference:" --range base..HEAD

# A string literal in production code is not documentation: decision-record and
# delivery identifiers are not flagged there. The governance repository name is
# flagged everywhere, so a literal naming it still is.
repo_reset java-literal-ids
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

public final class X {
    static final String MESSAGE = "ADR-0255 T003 P02 rest-023 contract §4";
}
EOF
commit_all "feat: x"
expect_clean "java-code-literal-ids-not-flagged" --range base..HEAD

repo_reset java-literal-vertique-dev
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

public final class X {
    static final String URL = "https://github.com/vertiquehq/vertique-dev/issues/1";
}
EOF
commit_all "feat: x"
expect_finding "java-code-literal-vertique-dev-flagged" \
    "X.java:4: vertique-dev-reference:" --range base..HEAD

repo_reset java-literal-vertique-dev-case
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

public final class X {
    static final String URL = "VERTIQUE-DEV";
}
EOF
commit_all "feat: x"
expect_finding "vertique-dev-is-case-insensitive" \
    "X.java:4: vertique-dev-reference:" --range base..HEAD

repo_reset java-main-comment-after-code-line
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

public final class X {
    int field = 1; // ADR-0255
}
EOF
commit_all "feat: x"
expect_clean "java-trailing-comment-is-code-line" --range base..HEAD

# --- Added-line scanning: Markdown, documentation trees, other files ---

repo_reset md-adr
put_file docs/design.md <<'EOF'
# Design

Decided in ADR-0255.
EOF
commit_all "docs: design"
expect_finding "markdown-adr" "docs/design.md:3: adr-citation:" --range base..HEAD

repo_reset md-delivery
put_file README.md <<'EOF'
# Fixture

Plain documentation.
Fixed in T003 and R04.
EOF
commit_all "docs: readme"
expect_finding "markdown-delivery-identifier" "README.md:4: delivery-identifier:" --range base..HEAD

repo_reset md-spec-lower
put_file README.md <<'EOF'
# Fixture

Behavior from rest-023 applies.
EOF
commit_all "docs: readme"
expect_finding "markdown-spec-package-name" "README.md:3: spec-identifier:" --range base..HEAD

repo_reset md-spec-upper
put_file README.md <<'EOF'
# Fixture

Requirement FR-011 and AC-004 hold.
EOF
commit_all "docs: readme"
expect_finding "markdown-upper-case-requirement-ids" "README.md:3: spec-identifier:" --range base..HEAD

repo_reset md-spec-compound
put_file README.md <<'EOF'
# Fixture

See NFR-REL-006 for the budget.
EOF
commit_all "docs: readme"
expect_finding "markdown-compound-requirement-id" "README.md:3: spec-identifier:" --range base..HEAD

repo_reset md-spec-definition
put_file README.md <<'EOF'
# Fixture

Closes EXT-DEF009.
EOF
commit_all "docs: readme"
expect_finding "markdown-definition-id" "README.md:3: spec-identifier:" --range base..HEAD

repo_reset md-spec-short
put_file README.md <<'EOF'
# Fixture

Tracked as mcp-002.
EOF
commit_all "docs: readme"
expect_finding "markdown-short-spec-name" "README.md:3: spec-identifier:" --range base..HEAD

repo_reset md-contract
put_file README.md <<'EOF'
# Fixture

Follows contract §4.
EOF
commit_all "docs: readme"
expect_finding "markdown-contract-section" "README.md:3: contract-section:" --range base..HEAD

repo_reset md-worktree
put_file README.md <<'EOF'
# Fixture

Run it under .worktrees/scratch.
EOF
commit_all "docs: readme"
expect_finding "markdown-worktree-path" "README.md:3: worktree-path:" --range base..HEAD

repo_reset md-vertique-dev
put_file README.md <<'EOF'
# Fixture

Tracked in the Vertique-Dev repository.
EOF
commit_all "docs: readme"
expect_finding "markdown-vertique-dev" "README.md:3: vertique-dev-reference:" --range base..HEAD

repo_reset mdx-adr
put_file site/page.mdx <<'EOF'
# Page

Per ADR-0255.
EOF
commit_all "docs: page"
expect_finding "mdx-adr" "site/page.mdx:3: adr-citation:" --range base..HEAD

repo_reset developer-docs-yaml
put_file developer-docs/nav.yaml <<'EOF'
title: Overview
note: per ADR-0255
EOF
commit_all "docs: nav"
expect_finding "developer-docs-non-markdown-adr" "developer-docs/nav.yaml:2: adr-citation:" --range base..HEAD

repo_reset docs-txt
put_file docs/notes.txt <<'EOF'
phase P02 notes
EOF
commit_all "docs: notes"
expect_finding "docs-tree-text-delivery-id" "docs/notes.txt:1: delivery-identifier:" --range base..HEAD

repo_reset pom-adr
put_file pom.xml <<'EOF'
<project>
    <!-- chosen per ADR-0255 and T003 -->
</project>
EOF
commit_all "build: pom"
expect_clean "pom-comment-ids-not-flagged" --range base..HEAD

repo_reset pom-vertique-dev
put_file pom.xml <<'EOF'
<project>
    <!-- mirrors vertique-dev layout -->
</project>
EOF
commit_all "build: pom"
expect_finding "pom-vertique-dev-flagged" "pom.xml:2: vertique-dev-reference:" --range base..HEAD

repo_reset yaml-vertique-dev
put_file .github/workflows/x.yml <<'EOF'
name: X
# see vertique-dev for the policy
EOF
commit_all "ci: x"
expect_finding "workflow-vertique-dev-flagged" ".github/workflows/x.yml:2: vertique-dev-reference:" --range base..HEAD

repo_reset gitignore-worktrees
put_file .gitignore <<'EOF'
.worktrees/
EOF
commit_all "chore: ignore"
expect_clean "ignore-file-worktree-entry-allowed" --range base..HEAD

repo_reset governance-docs-path-anywhere
put_file scripts/other.sh <<'EOF'
#!/usr/bin/env bash
cat docs/specs/foo/plan.md
EOF
commit_all "build: script"
expect_finding "script-governance-path-flagged" "scripts/other.sh:2: vertique-dev-reference:" --range base..HEAD

repo_reset script-adr-not-flagged
put_file scripts/other.sh <<'EOF'
#!/usr/bin/env bash
# chosen per ADR-0255
EOF
commit_all "build: script"
expect_clean "script-comment-adr-not-flagged" --range base..HEAD

# --- Test sources: proof identifiers allowed, decision records and the governance repository never ---

repo_reset test-source-ids
put_file vertique-x/src/test/java/dev/vertique/XTest.java <<'EOF'
package dev.vertique;

/**
 * Proof for T003 of rest-023 (TP-001, D001).
 */
class XTest {
    // R04 repair proof
}
EOF
commit_all "test: x"
expect_clean "test-source-task-ids-allowed" --range base..HEAD

repo_reset test-source-adr
put_file vertique-x/src/test/java/dev/vertique/XTest.java <<'EOF'
package dev.vertique;

class XTest {
    // Proof per ADR-0255.
}
EOF
commit_all "test: x"
expect_finding "test-source-comment-adr-flagged" "XTest.java:4: adr-citation:" --range base..HEAD

repo_reset test-source-adr-code
put_file vertique-x/src/test/java/dev/vertique/XTest.java <<'EOF'
package dev.vertique;

class XTest {
    @org.junit.jupiter.api.DisplayName("ADR-0255 holds")
    void holds() {}
}
EOF
commit_all "test: x"
expect_finding "test-source-code-adr-flagged" "XTest.java:4: adr-citation:" --range base..HEAD

repo_reset test-source-vertique-dev
put_file vertique-x/src/test/java/dev/vertique/XTest.java <<'EOF'
package dev.vertique;

class XTest {
    // Reads docs/specs/foo/plan.md
}
EOF
commit_all "test: x"
expect_finding "test-source-governance-path-flagged" "XTest.java:4: vertique-dev-reference:" --range base..HEAD

# --- Added versus context ---

repo_reset context-line
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

// Legacy: per ADR-0255.
public final class X {}
EOF
commit_all "feat: legacy"
git -C "$repo" tag legacy
put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

// Legacy: per ADR-0255.
public final class X {
    // Plain new comment.
    int value = 1;
}
EOF
commit_all "feat: add field"
expect_clean "untouched-violating-line-is-context" --range legacy..HEAD

put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

// Legacy: per ADR-0256.
public final class X {
    // Plain new comment.
    int value = 1;
}
EOF
commit_all "feat: renumber"
expect_finding "modified-violating-line-is-flagged" "X.java:3: adr-citation:" --range legacy..HEAD

put_file vertique-x/src/main/java/dev/vertique/X.java <<'EOF'
package dev.vertique;

// Plain description.
public final class X {
    // Plain new comment.
    int value = 1;
}
EOF
commit_all "feat: reword"
expect_clean "rewording-away-the-citation-passes" --range legacy..HEAD

repo_reset deleted-violation
put_file docs/design.md <<'EOF'
# Design

Decided in ADR-0255.
EOF
commit_all "docs: legacy"
git -C "$repo" tag legacy
put_file docs/design.md <<'EOF'
# Design

Decided as described here.
EOF
commit_all "docs: reword"
expect_clean "removing-a-violation-passes" --range legacy..HEAD

repo_reset unchanged-file-with-violation
put_file docs/design.md <<'EOF'
# Design

Decided in ADR-0255.
EOF
commit_all "docs: legacy"
git -C "$repo" tag legacy
put_file docs/other.md <<'EOF'
# Other

Clean.
EOF
commit_all "docs: other"
expect_clean "untouched-file-is-never-read" --range legacy..HEAD

repo_reset three-dot-range
git -C "$repo" checkout -q -b feature
put_file docs/design.md <<'EOF'
# Design

Decided in ADR-0255.
EOF
commit_all "docs: design"
git -C "$repo" checkout -q main
put_file docs/main-only.md <<'EOF'
# Main only

Decided in ADR-0300.
EOF
commit_all "docs: main only"
expect_finding "three-dot-range-scans-head-side" "docs/design.md:3: adr-citation:" --range main...feature
run_case "three-dot-range-ignores-base-side" 1 "-" "main-only.md" --range main...feature

# --- Path and content edge cases ---

repo_reset binary-file
printf 'vertique-dev\0ADR-0255\0' > "$repo/blob.bin"
commit_all "chore: blob"
expect_clean "binary-file-is-skipped" --range base..HEAD

repo_reset spaced-path
put_file "docs/my page.md" <<'EOF'
# Page

Per ADR-0255.
EOF
commit_all "docs: page"
expect_finding "path-with-spaces-is-reported" "docs/my page.md:3: adr-citation:" --range base..HEAD

repo_reset plus-prefixed-line
put_file docs/page.md <<'EOF'
# Page

++ vertique-dev
EOF
commit_all "docs: page"
expect_finding "added-line-that-looks-like-a-file-header" "docs/page.md:3: vertique-dev-reference:" --range base..HEAD

repo_reset multiple-findings
put_file docs/page.md <<'EOF'
# Page

Per ADR-0255.
Clean line.
And vertique-dev again.
EOF
commit_all "docs: page"
expect_finding "line-numbers-of-first-finding" "docs/page.md:3: adr-citation:" --range base..HEAD
expect_finding "line-numbers-of-second-finding" "docs/page.md:5: vertique-dev-reference:" --range base..HEAD
expect_finding "findings-are-counted" "2 private-reference finding(s)." --range base..HEAD
expect_finding "failure-explains-the-fix" "reword each flagged line" --range base..HEAD

repo_reset hunk-offsets
put_file docs/page.md <<'EOF'
line one
line two
line three
line four
line five
EOF
commit_all "docs: page"
git -C "$repo" tag legacy
put_file docs/page.md <<'EOF'
line one
line two changed per ADR-0255
line three
line four
line five plus T003
EOF
commit_all "docs: page edits"
expect_finding "hunk-line-number-first-edit" "docs/page.md:2: adr-citation:" --range legacy..HEAD
expect_finding "hunk-line-number-second-edit" "docs/page.md:5: delivery-identifier:" --range legacy..HEAD

# --- Pattern boundaries: standards that share a shape with identifiers ---

repo_reset false-positives
put_file docs/standards.md <<'EOF'
# Standards

Digest SHA-256, SHA-512, AES-128 and HS-256 signatures use UTF-8 and ISO-8859 text.
HTTP-2 and RFC-793 are protocols; the R2DBC driver and T0034 and ADRIANA are plain words.
Roads, roadmap, Tasks and P950 are fine. A 4-digit id like abc-1234 is not a package id.
See https://example.org/aes-128-gcm/ and sha-256-digest for details.
ECMA-262 regex, IEEE-754 doubles, CWE-863 and CVE-202 are public; a non-200 status is plain prose.
EOF
commit_all "docs: standards"
expect_clean "standards-and-plain-words-not-flagged" --range base..HEAD

repo_reset adjacent-excluded-then-real
put_file docs/standards.md <<'EOF'
# Standards

Use sha-256,rest-023 together.
EOF
commit_all "docs: standards"
expect_finding "identifier-next-to-excluded-standard" "docs/standards.md:3: spec-identifier:" --range base..HEAD

repo_reset adr-boundary
put_file docs/words.md <<'EOF'
# Words

The cadr 0255 and madr-0300 words are not records, but ADR 0255 and adr-0300 are.
EOF
commit_all "docs: words"
expect_finding "adr-word-boundary" "docs/words.md:3: adr-citation:" --range base..HEAD

repo_reset vertique-developer-word
put_file docs/words.md <<'EOF'
# Words

The vertique-developer-docs corpus is public.
EOF
commit_all "docs: words"
expect_clean "longer-word-starting-with-the-repository-name" --range base..HEAD

# --- Exempt files ---

repo_reset exempt-files
put_file scripts/verify-public-references.sh <<'EOF'
# patterns: ADR-0255 T003 rest-023 vertique-dev docs/specs/
EOF
put_file scripts/test-verify-public-references.sh <<'EOF'
# fixtures: ADR-0255 T003 rest-023 vertique-dev docs/specs/
EOF
commit_all "ci: guard"
expect_clean "the-guard-and-its-harness-are-exempt" --range base..HEAD

repo_reset exempt-is-exact
put_file other/scripts/verify-public-references.sh <<'EOF'
# patterns: vertique-dev
EOF
commit_all "ci: copy"
expect_finding "a-same-named-file-elsewhere-is-not-exempt" \
    "other/scripts/verify-public-references.sh:1: vertique-dev-reference:" --range base..HEAD

# --- Range handling ---

repo_reset empty-range
expect_clean "empty-range-is-clean" --range base..HEAD

repo_reset bad-range
run_case "unresolvable-range-fails-closed" 2 "cannot diff range" "PASS:" --range nonexistent..HEAD
run_case "unresolvable-commit-range-fails-closed" 2 "cannot list commits" "PASS:" --commits nonexistent..HEAD

repo_reset usage
run_case "no-mode-is-a-usage-error" 2 "Usage:" "-"
run_case "unknown-argument-is-a-usage-error" 2 "Usage:" "-" --bogus
run_case "range-without-value-is-a-usage-error" 2 "Usage:" "-" --range
run_case "missing-text-file-fails-closed" 2 "no such file" "-" --text "$work_dir/absent.txt"

# --- Commit messages ---

repo_reset commit-clean
commit_all "feat(core): add a plain feature" "The body describes shipped behavior only."
expect_clean "commit-clean-message" --commits base..HEAD

repo_reset commit-subject
commit_all "docs: sync with vertique-dev"
sha="$(git -C "$repo" rev-parse HEAD)"
expect_finding "commit-subject-vertique-dev" "FAIL: commit ${sha:0:12}:1: vertique-dev-reference:" --commits base..HEAD

repo_reset commit-body-adr
commit_all "feat: add thing" "Implements the approach of ADR-0255."
sha="$(git -C "$repo" rev-parse HEAD)"
expect_finding "commit-body-adr" "FAIL: commit ${sha:0:12}:3: adr-citation:" --commits base..HEAD

repo_reset commit-body-delivery
commit_all "feat: add thing" "Completes T003."
expect_finding "commit-body-task-id" "delivery-identifier:" --commits base..HEAD

repo_reset commit-subject-spec
commit_all "feat(rest): rest-023 map values"
expect_finding "commit-subject-spec-package-name" "spec-identifier:" --commits base..HEAD

repo_reset commit-body-governance-path
commit_all "docs: note" "Plan is in docs/adr/0255.md."
expect_finding "commit-body-governance-path" "vertique-dev-reference:" --commits base..HEAD

repo_reset commit-before-range
commit_all "docs: sync with vertique-dev"
git -C "$repo" tag legacy
commit_clean_change
expect_clean "commit-before-the-range-is-not-scanned" --commits legacy..HEAD

repo_reset commit-in-middle
commit_clean_change
commit_all "docs: first" "Mentions ADR-0255."
bad="$(git -C "$repo" rev-parse HEAD)"
commit_clean_change
expect_finding "commit-in-the-middle-of-the-range" "commit ${bad:0:12}" --commits base..HEAD

repo_reset commit-merge-ignored
git -C "$repo" checkout -q -b feature
put_file docs/feature.md <<'EOF'
# Feature

Plain.
EOF
commit_all "docs: feature"
git -C "$repo" checkout -q main
put_file docs/mainline.md <<'EOF'
# Mainline

Plain.
EOF
commit_all "docs: mainline"
git -C "$repo" merge -q --no-ff feature -m "Merge branch 'feature' into vertique-dev-sync"
expect_clean "merge-commit-message-is-ignored" --commits base..HEAD

repo_reset commit-three-dot
git -C "$repo" checkout -q -b feature
commit_all "docs: feature work"
git -C "$repo" checkout -q main
put_file docs/mainline.md <<'EOF'
# Mainline

Plain.
EOF
commit_all "docs: mainline mentions vertique-dev"
expect_clean "three-dot-commit-range-scans-head-side-only" --commits main...feature

# --- Text files (pull request title and description) ---

repo_reset text-clean
cat > "$work_dir/pr-clean.txt" <<'EOF'
Add plain feature

## Summary
- describes shipped behavior
EOF
expect_clean "text-clean" --text "$work_dir/pr-clean.txt"

repo_reset text-findings
printf 'Fix the thing\n\nFollows ADR-0255.\nAlso vertique-dev#12.\nAnd T003, rest-023.\n' \
    > "$work_dir/pr-bad.txt"
expect_finding "text-adr-with-line" "$work_dir/pr-bad.txt:3: adr-citation:" --text "$work_dir/pr-bad.txt"
expect_finding "text-vertique-dev" "$work_dir/pr-bad.txt:4: vertique-dev-reference:" --text "$work_dir/pr-bad.txt"
expect_finding "text-delivery-id" "$work_dir/pr-bad.txt:5: delivery-identifier:" --text "$work_dir/pr-bad.txt"
expect_finding "text-spec-id" "$work_dir/pr-bad.txt:5: spec-identifier:" --text "$work_dir/pr-bad.txt"
expect_finding "text-label-replaces-path" "pull-request:3: adr-citation:" --text "$work_dir/pr-bad.txt" --label pull-request

printf 'Title\n\nlast line without newline mentions vertique-dev' > "$work_dir/pr-no-newline.txt"
expect_finding "text-last-line-without-newline" "pr-no-newline.txt:3: vertique-dev-reference:" \
    --text "$work_dir/pr-no-newline.txt"

: > "$work_dir/pr-empty.txt"
expect_clean "text-empty-file" --text "$work_dir/pr-empty.txt"

repo_reset combined-modes
put_file docs/design.md <<'EOF'
# Design

Decided in ADR-0255.
EOF
commit_all "docs: design" "Also mentions vertique-dev."
expect_finding "combined-range-finding" "docs/design.md:3: adr-citation:" \
    --range base..HEAD --commits base..HEAD --text "$work_dir/pr-clean.txt"
expect_finding "combined-commit-finding" "vertique-dev-reference:" \
    --range base..HEAD --commits base..HEAD --text "$work_dir/pr-clean.txt"

# --- The commit-msg hook ---

# Runs the repository's hook directly against message $2 (written verbatim) in a
# fixture that has no copy of the verifier next to the hook.
run_hook_case() {
    local case_name="$1" expected_exit="$2" expected_diagnostic="$3" forbidden_diagnostic="$4"
    local message="$5"
    local message_file="$work_dir/hook-message.txt"
    local output status=0

    executed_cases=$((executed_cases + 1))
    printf '%s\n' "$message" > "$message_file"
    output="$(cd "$repo" && bash "$hook" "$message_file" 2>&1)" || status=$?
    judge_case "$case_name" "$expected_exit" "$expected_diagnostic" "$forbidden_diagnostic" "$status" "$output"
}

repo_reset hook-direct
run_hook_case "hook-accepts-a-clean-conventional-message" 0 "-" "private" "feat(core): add a plain feature"
run_hook_case "hook-still-rejects-a-non-conventional-message" 1 "Conventional Commits" "-" "added a thing"
run_hook_case "hook-rejects-vertique-dev-in-the-subject" 1 "private governance" "-" \
    "docs: sync with vertique-dev"
run_hook_case "hook-rejects-an-adr-in-the-body" 1 "adr-citation" "-" \
    "$(printf 'feat: add thing\n\nImplements ADR-0255.')"
run_hook_case "hook-rejects-a-task-id-in-the-body" 1 "delivery-identifier" "-" \
    "$(printf 'feat: add thing\n\nCompletes T003.')"
run_hook_case "hook-rejects-a-spec-name-in-the-subject" 1 "spec-identifier" "-" \
    "feat(rest): rest-023 map values"
run_hook_case "hook-names-the-fix" 1 "no suppression" "-" "docs: sync with vertique-dev"
run_hook_case "hook-skips-merge-messages" 0 "-" "private" "Merge branch 'x' into vertique-dev-sync"
run_hook_case "hook-ignores-git-template-comments" 0 "-" "private" \
    "$(printf 'feat: add thing\n\n# On branch vertique-dev-sync\n# modified: docs/specs/x.md')"

# End to end: the hook installed as git does it, with the verifier found through
# the repository's top level because the installed copy lives in the hooks directory.
repo_reset hook-installed
mkdir -p "$repo/scripts" "$repo/.hooks"
cp "$verifier" "$repo/scripts/verify-public-references.sh"
cp "$hook" "$repo/.hooks/commit-msg"
chmod +x "$repo/.hooks/commit-msg"
git -C "$repo" config core.hooksPath "$repo/.hooks"
printf 'plain\n' > "$repo/plain.txt"
git -C "$repo" add -A

executed_cases=$((executed_cases + 1))
status=0
output="$(git -C "$repo" commit -q -m "docs: sync with vertique-dev" 2>&1)" || status=$?
judge_case "installed-hook-blocks-a-private-reference" 1 "private governance" "-" "$status" "$output"

executed_cases=$((executed_cases + 1))
status=0
output="$(git -C "$repo" commit -q -m "docs: add plain file" 2>&1)" || status=$?
judge_case "installed-hook-allows-a-clean-commit" 0 "-" "private governance" "$status" "$output"

# Without the verifier the hook still enforces the format, and stays quiet about references.
repo_reset hook-without-verifier
mkdir -p "$repo/.hooks"
cp "$hook" "$repo/.hooks/commit-msg"
chmod +x "$repo/.hooks/commit-msg"
git -C "$repo" config core.hooksPath "$repo/.hooks"
printf 'plain\n' > "$repo/plain.txt"
git -C "$repo" add -A
executed_cases=$((executed_cases + 1))
status=0
output="$(git -C "$repo" commit -q -m "docs: sync with vertique-dev" 2>&1)" || status=$?
judge_case "hook-without-a-verifier-skips-the-reference-check" 0 "-" "private governance" "$status" "$output"

# --- The pull request wrapper ---

# Runs the wrapper in the current fixture with the base at the baseline tag and
# the head at the current commit. $5 is the title; $6, when given, the body.
run_pull_request_case() {
    local case_name="$1" expected_exit="$2" expected_diagnostic="$3" forbidden_diagnostic="$4"
    local title="$5"
    local output status=0
    local base_sha head_sha

    executed_cases=$((executed_cases + 1))
    base_sha="$(git -C "$repo" rev-parse base)"
    head_sha="$(git -C "$repo" rev-parse HEAD)"
    if [[ $# -ge 6 ]]; then
        output="$(cd "$repo" && BASE_SHA="$base_sha" HEAD_SHA="$head_sha" PR_TITLE="$title" PR_BODY="$6" \
            bash "$pull_request_wrapper" 2>&1)" || status=$?
    else
        output="$(cd "$repo" && env -u PR_BODY BASE_SHA="$base_sha" HEAD_SHA="$head_sha" PR_TITLE="$title" \
            bash "$pull_request_wrapper" 2>&1)" || status=$?
    fi
    judge_case "$case_name" "$expected_exit" "$expected_diagnostic" "$forbidden_diagnostic" "$status" "$output"
}

repo_reset pull-request-clean
put_file docs/page.md <<'EOF'
# Page

Plain.
EOF
commit_all "docs: page"
run_pull_request_case "pull-request-clean" 0 "PASS:" "FAIL:" "docs: add page" "A plain description."
run_pull_request_case "pull-request-without-a-body" 0 "PASS:" "FAIL:" "docs: add page"
run_pull_request_case "pull-request-title-finding" 1 \
    "pull request title and description:1: vertique-dev-reference:" "-" "docs: sync with vertique-dev" "Plain."
run_pull_request_case "pull-request-body-finding" 1 \
    "pull request title and description:5: adr-citation:" "-" "docs: add page" "$(printf 'Summary\n\nPer ADR-0255.')"
# shellcheck disable=SC2016
run_pull_request_case "pull-request-text-is-data-not-shell" 0 "PASS:" "-" \
    'docs: $(touch pwned) `touch pwned` ; touch pwned' '"; touch pwned; echo "'
executed_cases=$((executed_cases + 1))
if [[ -e "$repo/pwned" ]]; then
    unexpected_outcomes=$((unexpected_outcomes + 1))
    printf 'FAIL  %-44s pull request text was executed\n' "pull-request-text-never-executes"
else
    printf 'ok    %s\n' "pull-request-text-never-executes"
fi

repo_reset pull-request-diff-findings
put_file docs/page.md <<'EOF'
# Page

Per ADR-0255.
EOF
commit_all "docs: page" "Completes T003."
run_pull_request_case "pull-request-diff-finding" 1 "docs/page.md:3: adr-citation:" "-" "docs: add page" "Plain."
run_pull_request_case "pull-request-commit-finding" 1 "delivery-identifier:" "-" "docs: add page" "Plain."

executed_cases=$((executed_cases + 1))
status=0
output="$(cd "$repo" && BASE_SHA=main HEAD_SHA=main PR_TITLE=t bash "$pull_request_wrapper" 2>&1)" || status=$?
judge_case "pull-request-rejects-a-non-sha-base" 2 "lowercase full SHA" "-" "$status" "$output"

# --- Summary ---

if (( unexpected_outcomes > 0 )); then
    printf '\n%d of %d cases produced an unexpected outcome.\n' "$unexpected_outcomes" "$executed_cases" >&2
    exit 1
fi

printf '\nAll %d cases produced the expected outcome.\n' "$executed_cases"
