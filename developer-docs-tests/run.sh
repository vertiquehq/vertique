#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Durable regression suite for developer-docs/verify.sh (the DOCS-001 D2
# fixture-suite contract amendment).
#
# developer-docs/ is the frozen, sixteen-file corpus; this suite lives at the
# sibling path developer-docs-tests/ so verify.sh's own inventory contract
# needs no exemption for it.
#
# Three kinds of case, all invoking the repository's real verify.sh against a
# disposable corpus copy (verify.sh [corpus-root]):
#
#   (a) mutation cases - each case copies developer-docs/ into a fresh
#       temporary directory, applies one small, self-contained mutation
#       in-place, and asserts verify.sh rejects it with the diagnostic that
#       names the specific invariant being proved. Mutations are applied by
#       small inline case bodies rather than stored corpora, so the corpus
#       under test always tracks the real, evergreen developer-docs/ content.
#   (b) static fixtures - for a handful of cases whose proof depends on exact
#       byte content that inline mutation cannot express (CRLF line endings, a
#       link destination with an embedded space, fenced Java text, a TODO
#       marker inside a fence), a small standalone page is committed under
#       fixtures/static/ and swapped into a fresh developer-docs/ copy in
#       place of content/quickstart.md before verify.sh runs.
#   (c) scanner-failure sabotage - see "--- Sabotage cases: validator
#       integrity (round 8) ---" below. One case makes a corpus page
#       unreadable via `chmod 000` and asserts verify.sh reports an
#       internal-scanner-error violation rather than a silent PASS (skipped,
#       with a note, when the suite runs as root, since root ignores file
#       permissions). This single sabotage is the regression proof for BOTH
#       round-8 validator-integrity findings at once, and there is a
#       deliberate decision behind that:
#         - scanner-specific status acceptance - the file is unreadable, not
#           merely empty, so every producer that touches it (sed, awk, grep)
#           exits with a genuine non-zero status, proving strict-mode
#           producers (sed/awk, and the fence-stripping awk stage) and the
#           grep stage alike are never silently treated as "found nothing"
#           (on macOS/BSD grep, an unreadable file already exits 2, itself
#           over grep mode's own max accepted status of 1).
#         - no `pipefail` masking of a producer failure behind a quiet grep
#           consumer - a bare `producer | grep` pipeline's own captured
#           status would be indistinguishable, in exactly this scenario
#           (producer status 2, grep status 1), from a clean "nothing
#           matched" grep run (pipefail reports only the rightmost status).
#           verify.sh's fence-stripped scans instead capture the producer's
#           output first (see stripped_grep_scan in verify.sh) and check its
#           status independently before grep ever runs, so this sabotage
#           exercises exactly the code path the old masking bug lived in. A
#           distinct "producer fails, grep quietly finds nothing" case
#           cannot be constructed independently of this one without editing
#           verify.sh itself: post-fix, grep is never invoked once the
#           producer stage has already failed, so no corpus mutation can
#           reach a quiet grep consumer past a failed producer - the
#           vulnerable bare pipeline the old bug lived in no longer exists
#           in verify.sh to exploit. This note records that decision instead
#           of adding a second, redundant case.
#
# Every case asserts both the exit-code direction (pass/fail) and, for a
# fail case, that the diagnostic contains the expected invariant substring -
# a case that fails for the wrong reason is reported as a suite failure, not
# treated as a pass. A case may also assert the negative: that the combined
# output does NOT contain a given substring, via run_case's optional fifth
# argument - used to prove a rejection stops before a downstream content
# scan ever runs (see the symlink-rejection case below).
#
# Uses only Bash and standard Unix tools (awk, sed, grep, cp, mktemp,
# chmod, id). No network access is performed or required. Bash 3.2
# compatible (no mapfile, no associative arrays, no GNU-only flags) - see
# developer-docs/verify.sh for the same portability discipline this suite
# follows.
#
# Usage: run.sh
#   Always validates this repository's own developer-docs/ corpus, copied
#   into disposable temporary directories for each case; takes no arguments.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/.." && pwd)"
corpus_src="$repository_root/developer-docs"
verifier="$corpus_src/verify.sh"
fixtures_dir="$script_dir/fixtures"

if [[ ! -f "$verifier" ]]; then
  echo "Verifier under test is missing: $verifier" >&2
  exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-developer-docs-tests-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

executed_cases=0
unexpected_outcomes=0

# --- Reporting ---

# Prints each non-empty line of $1 indented, for diagnosing an unexpected
# case outcome.
print_indented_output() {
  local line
  while IFS= read -r line; do
    [[ -n "$line" ]] && printf '        | %s\n' "$line"
  done <<<"$1"
  return 0
}

# Runs verify.sh against $3 and asserts the outcome named case $1 produces:
# $2 is the expected outcome (pass|fail); $4, required for a fail case, is a
# literal substring the combined stdout+stderr output must contain - the
# diagnostic naming the specific invariant this case proves. $5, optional,
# is a literal substring the combined output must NOT contain - used to
# prove a rejection stops before a downstream content scan ever runs (e.g.
# a symlink rejected by the inventory check must never also produce a
# forbidden-token violation sourced from the symlink's own target content).
# A case that exits in the wrong direction, exits fail for a diagnostic
# other than the expected one, or contains the forbidden substring, is
# reported as an unexpected outcome rather than a pass.
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
  if output="$(bash "$verifier" "$target_root" 2>&1)"; then
    observed_outcome=pass
  else
    status=$?
    observed_outcome=fail
  fi

  if [[ "$observed_outcome" != "$expected_outcome" ]]; then
    unexpected_outcomes=$((unexpected_outcomes + 1))
    printf 'FAIL  %-62s expected verifier to %s, but it %sed (exit %d)\n' \
      "$case_name" "$expected_outcome" "$observed_outcome" "$status"
    print_indented_output "$output"
    return 0
  fi

  if [[ -n "$expected_diagnostic" && "$output" != *"$expected_diagnostic"* ]]; then
    unexpected_outcomes=$((unexpected_outcomes + 1))
    printf 'FAIL  %-62s verifier %sed but never reported: %s\n' \
      "$case_name" "$observed_outcome" "$expected_diagnostic"
    print_indented_output "$output"
    return 0
  fi

  if [[ -n "$forbidden_diagnostic" && "$output" == *"$forbidden_diagnostic"* ]]; then
    unexpected_outcomes=$((unexpected_outcomes + 1))
    printf 'FAIL  %-62s verifier %sed but also reported (should not have): %s\n' \
      "$case_name" "$observed_outcome" "$forbidden_diagnostic"
    print_indented_output "$output"
    return 0
  fi

  printf 'ok    %-62s (%s)\n' "$case_name" "$observed_outcome"
  return 0
}

# --- Corpus copy helpers ---

# Starts a new case: creates a fresh copy of developer-docs/ under the work
# directory and sets the globals case_dir (the case's own scratch directory)
# and corpus_root (the corpus copy inside it, what verify.sh validates).
#
# Several pages link out of developer-docs/ into the wider repository tree
# (e.g. content/workflows.md links two directories up to
# vertique-workflow/.../module.md - link contract, see verify.sh). A bare
# `cp -R` into an isolated temporary directory would break every one of those
# owned-but-external targets, since nothing else from the repository is
# there. Instead, case_dir/repo is populated with symlinks to every other
# top-level entry of the real repository root, reproducing the same
# directory depth developer-docs/ has in the real tree, and corpus_root - the
# one thing under test - is a real, independently mutable copy. `find` inside
# check_inventory/check_links/etc. never leaves corpus_root, so the sibling
# symlinks are inert except as link-resolution targets.
new_case_corpus() {
  local label="$1"
  case_dir="$work_dir/$label"
  local repo_overlay="$case_dir/repo"
  mkdir -p "$repo_overlay"

  local entry name
  for entry in "$repository_root"/* "$repository_root"/.[!.]*; do
    [[ -e "$entry" || -L "$entry" ]] || continue
    name="$(basename "$entry")"
    [[ "$name" == "developer-docs" ]] && continue
    ln -s "$entry" "$repo_overlay/$name"
  done

  corpus_root="$repo_overlay/developer-docs"
  cp -R "$corpus_src" "$corpus_root"
}

# --- Mutation helpers ---

# Replaces line $2 of file $1 with content $3.
set_line() {
  local file="$1" line_no="$2" content="$3"
  awk -v n="$line_no" -v repl="$content" 'NR==n{print repl; next} {print}' "$file" >"$file.tmp"
  mv "$file.tmp" "$file"
}

# Deletes line $2 of file $1.
delete_line() {
  local file="$1" line_no="$2"
  awk -v n="$line_no" 'NR!=n' "$file" >"$file.tmp"
  mv "$file.tmp" "$file"
}

# Appends a blank line followed by every remaining argument, one per line, to
# file $1 - each argument is one line of injected prose or fence content.
append_lines() {
  local file="$1"
  shift
  {
    printf '\n'
    printf '%s\n' "$@"
  } >>"$file"
}

# --- Mutation cases: inventory (a) ---

new_case_corpus pristine-corpus-passes
run_case "pristine-corpus-passes" pass "$corpus_root"

new_case_corpus remove-content-page
rm -f "$corpus_root/content/testing.md"
run_case "remove-content-page" fail "$corpus_root" \
  "expected corpus file is missing (inventory contract)"

new_case_corpus add-extra-file-with-space
: >"$corpus_root/content/extra file.md"
run_case "add-extra-file-with-space" fail "$corpus_root" \
  "is not part of the frozen corpus inventory (inventory contract)"

# The symlink target deliberately lives outside corpus_root (inside it would
# itself violate the frozen inventory contract as an extra file) but inside
# case_dir - i.e. one directory above corpus_root, so the symlink's own path
# stays inside the corpus. The target's content carries a TODO token to
# prove the inventory rejection is what stops the symlink from being read at
# all: were it instead silently treated as an ordinary corpus file (the
# bypass this case guards against), the forbidden-token scan would also
# report a violation from this same path - and it must not, since the
# symlink is rejected outright before any content scan ever reaches it. The
# fifth run_case argument makes that "must not" an actual assertion rather
# than only a comment: the combined output must contain zero forbidden-token
# diagnostics naming the TODO token the target carries.
new_case_corpus add-extra-file-as-symlink
printf '%s\n' \
  '# Outside-corpus symlink target' \
  'TODO: this token must never surface as a forbidden-token violation once symlinks are rejected outright by the inventory check.' \
  >"$case_dir/outside-corpus-target.md"
ln -s "$case_dir/outside-corpus-target.md" "$corpus_root/content/extra-symlink.md"
run_case "add-extra-file-as-symlink" fail "$corpus_root" \
  "symlinks are not part of the frozen corpus inventory (inventory contract)" \
  "forbidden token 'TODO'"

# --- Mutation cases: navigation (b) ---

new_case_corpus duplicate-navigation-entry
awk '{print} $0=="  - quickstart.md"{print}' "$corpus_root/navigation.yml" >"$corpus_root/navigation.yml.tmp"
mv "$corpus_root/navigation.yml.tmp" "$corpus_root/navigation.yml"
run_case "duplicate-navigation-entry" fail "$corpus_root" \
  "'pages' must list every page exactly once in the frozen order (navigation contract)"

new_case_corpus reorder-navigation-entries
awk '
  $0=="  - index.md"      { print "  - quickstart.md"; next }
  $0=="  - quickstart.md" { print "  - index.md"; next }
  { print }
' "$corpus_root/navigation.yml" >"$corpus_root/navigation.yml.tmp"
mv "$corpus_root/navigation.yml.tmp" "$corpus_root/navigation.yml"
run_case "reorder-navigation-entries" fail "$corpus_root" \
  "'pages' must list every page exactly once in the frozen order (navigation contract)"

# --- Mutation cases: frontmatter (c) ---
# quickstart.md's frontmatter block is fixed at lines 1-4: '---', 'title:
# ...', 'description: ...', '---'.

new_case_corpus remove-description-key
delete_line "$corpus_root/content/quickstart.md" 3
run_case "remove-description-key" fail "$corpus_root" \
  "must contain exactly one 'description' key (frontmatter contract); found 0"

new_case_corpus empty-quoted-title
set_line "$corpus_root/content/quickstart.md" 2 'title: ""'
run_case "empty-quoted-title" fail "$corpus_root" \
  "frontmatter 'title' must be non-empty (frontmatter contract)"

new_case_corpus title-of-null
set_line "$corpus_root/content/quickstart.md" 2 'title: null'
run_case "title-of-null" fail "$corpus_root" \
  "frontmatter 'title' must be non-empty (frontmatter contract)"

new_case_corpus title-with-trailing-comment
set_line "$corpus_root/content/quickstart.md" 2 'title: "Quickstart" trailing'
run_case "title-with-trailing-comment" fail "$corpus_root" \
  "must not carry a trailing comment (frontmatter contract)"

new_case_corpus unterminated-quoted-title
set_line "$corpus_root/content/quickstart.md" 2 'title: "Quickstart'
run_case "unterminated-quoted-title" fail "$corpus_root" \
  "frontmatter value has an unterminated quoted value, with no matching closing quote (frontmatter contract)"

# --- Mutation cases: links (d) ---

new_case_corpus break-relative-link
link_line_no="$(grep -n 'configuration\.md' "$corpus_root/content/quickstart.md" | head -1 | cut -d: -f1)"
link_line="$(sed -n "${link_line_no}p" "$corpus_root/content/quickstart.md")"
set_line "$corpus_root/content/quickstart.md" "$link_line_no" "${link_line/configuration.md/configuration-broken.md}"
run_case "break-relative-link" fail "$corpus_root" \
  "broken relative link target 'configuration-broken.md' (link contract)"

new_case_corpus add-broken-reference-style-definition
append_lines "$corpus_root/content/quickstart.md" \
  '[broken-ref]: ./this-target-does-not-exist.md'
run_case "add-broken-reference-style-definition" fail "$corpus_root" \
  "broken relative link target './this-target-does-not-exist.md' (link contract)"

# The committed suite otherwise only proves the CommonMark angle-bracket
# destination form for an *inline* `](<target with spaces.md>)` link (see
# the static-angle-bracket-link-with-spaces fixture below); these two cases
# prove the identical destination form on a *reference-style*
# `[label]: <target with spaces>` definition, both when it resolves and when
# it doesn't. The target lives outside corpus_root for the same reason as
# that inline fixture: inside corpus_root it would itself violate the frozen
# inventory contract.
new_case_corpus add-reference-style-definition-with-spaces-pass
printf '%s\n' \
  '# Reference-style target with spaces' \
  '' \
  'Sibling fixture file referenced through a reference-style link definition whose angle-bracket destination contains an embedded space.' \
  >"$case_dir/repo/reference style target.md"
append_lines "$corpus_root/content/quickstart.md" \
  '[reference-style-target-with-spaces]: <../../reference style target.md>'
run_case "add-reference-style-definition-with-spaces-pass" pass "$corpus_root"

new_case_corpus add-reference-style-definition-with-spaces-fail
append_lines "$corpus_root/content/quickstart.md" \
  '[missing-reference-style-target-with-spaces]: <../../this target does not exist.md>'
run_case "add-reference-style-definition-with-spaces-fail" fail "$corpus_root" \
  "broken relative link target '../../this target does not exist.md' (link contract)"

# --- Mutation cases: forbidden tokens (e) ---

new_case_corpus add-todo-token
append_lines "$corpus_root/content/quickstart.md" \
  'Reminder: TODO revisit this before publishing.'
run_case "add-todo-token" fail "$corpus_root" \
  "forbidden token 'TODO' (placeholder contract)"

new_case_corpus add-capitalized-latest-token
append_lines "$corpus_root/content/quickstart.md" \
  'Always deploy the Latest build to staging.'
run_case "add-capitalized-latest-token" fail "$corpus_root" \
  "forbidden token 'Latest' (placeholder contract)"

new_case_corpus add-angle-bracket-placeholder-lowercase-hyphenated
append_lines "$corpus_root/content/quickstart.md" \
  'Replace <example-value> with your own setting.'
run_case "add-angle-bracket-placeholder-lowercase-hyphenated" fail "$corpus_root" \
  "forbidden angle-bracket placeholder '<example-value>' outside an xml fence (placeholder contract)"

new_case_corpus add-angle-bracket-placeholder-allcaps
append_lines "$corpus_root/content/quickstart.md" \
  'Set the <REGION> environment variable before starting.'
run_case "add-angle-bracket-placeholder-allcaps" fail "$corpus_root" \
  "forbidden angle-bracket placeholder '<REGION>' outside an xml fence (placeholder contract)"

new_case_corpus add-angle-bracket-placeholder-camelcase
append_lines "$corpus_root/content/quickstart.md" \
  'Provide <exampleToken> in the request header.'
run_case "add-angle-bracket-placeholder-camelcase" fail "$corpus_root" \
  "forbidden angle-bracket placeholder '<exampleToken>' outside an xml fence (placeholder contract)"

# --- Mutation cases: renderer neutrality (f) ---

new_case_corpus add-renderer-import-line
append_lines "$corpus_root/content/quickstart.md" \
  "import Something from './something';"
run_case "add-renderer-import-line" fail "$corpus_root" \
  "renderer import syntax is forbidden in corpus Markdown (renderer-neutrality contract)"

# --- Mutation cases: fence grammar - invalid opens (g) ---
# Each block's open and close lines share the same malformed shape, so the
# state machine never mistakes the close line for a legitimate bare-``` open
# of an unrelated anonymous fence.

fence_grammar_open_diagnostic="code fences must open with exactly three backticks at column zero, with no leading whitespace and no list/blockquote prefix (corpus grammar)"

new_case_corpus add-fence-opened-with-four-backticks
append_lines "$corpus_root/content/quickstart.md" '````' 'code' '````'
run_case "add-fence-opened-with-four-backticks" fail "$corpus_root" \
  "$fence_grammar_open_diagnostic"

new_case_corpus add-tilde-fence
append_lines "$corpus_root/content/quickstart.md" '~~~' 'code' '~~~'
run_case "add-tilde-fence" fail "$corpus_root" \
  "$fence_grammar_open_diagnostic"

new_case_corpus add-indented-fence-opening
append_lines "$corpus_root/content/quickstart.md" ' ```' 'code' ' ```'
run_case "add-indented-fence-opening" fail "$corpus_root" \
  "$fence_grammar_open_diagnostic"

new_case_corpus add-container-prefixed-fence-list
append_lines "$corpus_root/content/quickstart.md" '- ```' 'code' '- ```'
run_case "add-container-prefixed-fence-list" fail "$corpus_root" \
  "$fence_grammar_open_diagnostic"

new_case_corpus add-container-prefixed-fence-blockquote-no-space
append_lines "$corpus_root/content/quickstart.md" '>```' 'code' '>```'
run_case "add-container-prefixed-fence-blockquote-no-space" fail "$corpus_root" \
  "$fence_grammar_open_diagnostic"

new_case_corpus add-backtick-in-fence-info-string
append_lines "$corpus_root/content/quickstart.md" '```bad`info' 'code' '```bad`info'
run_case "add-backtick-in-fence-info-string" fail "$corpus_root" \
  "$fence_grammar_open_diagnostic"

# --- Mutation cases: fence grammar - unclosed fence (g) ---

new_case_corpus leave-fence-unclosed-at-eof
append_lines "$corpus_root/content/quickstart.md" '```text' 'unterminated fence content'
run_case "leave-fence-unclosed-at-eof" fail "$corpus_root" \
  "code fence is not closed before end of file (corpus grammar)"

# --- Mutation cases: fence grammar - container-prefixed reference definitions (g) ---

reference_definition_indent_diagnostic="reference-style link definitions must start at column zero, not be indented (corpus grammar)"

new_case_corpus add-container-prefixed-reference-definition-dot-ordered-list
append_lines "$corpus_root/content/quickstart.md" \
  '1. [dot-ref]: ./dot-ref-target.md'
run_case "add-container-prefixed-reference-definition-dot-ordered-list" fail "$corpus_root" \
  "$reference_definition_indent_diagnostic"

new_case_corpus add-container-prefixed-reference-definition-paren-ordered-list
append_lines "$corpus_root/content/quickstart.md" \
  '1) [paren-ref]: ./paren-ref-target.md'
run_case "add-container-prefixed-reference-definition-paren-ordered-list" fail "$corpus_root" \
  "$reference_definition_indent_diagnostic"

new_case_corpus add-container-prefixed-reference-definition-blockquote-no-space
append_lines "$corpus_root/content/quickstart.md" \
  '>[bq-ref]: ./bq-ref-target.md'
run_case "add-container-prefixed-reference-definition-blockquote-no-space" fail "$corpus_root" \
  "$reference_definition_indent_diagnostic"

# --- Mutation cases: fence grammar - bare parenthesized link destination (g) ---

new_case_corpus add-bare-inline-link-destination-with-parenthesis
append_lines "$corpus_root/content/quickstart.md" \
  'See [bad link](target(with)paren.md) for details.'
run_case "add-bare-inline-link-destination-with-parenthesis" fail "$corpus_root" \
  "use the angle-bracket destination form '(<target>)' instead (corpus grammar)"

# --- Static fixtures: cases needing exact, committed file content ---

new_case_corpus static-crlf-line-endings
cp "$fixtures_dir/static/crlf-page.md" "$corpus_root/content/quickstart.md"
run_case "static-crlf-line-endings-pass" pass "$corpus_root"

new_case_corpus static-java-fence-passthrough
cp "$fixtures_dir/static/java-fence-passthrough.md" "$corpus_root/content/quickstart.md"
run_case "static-java-fence-imports-generics-broken-link-text-pass" pass "$corpus_root"

new_case_corpus static-todo-inside-fence
cp "$fixtures_dir/static/todo-in-fence.md" "$corpus_root/content/quickstart.md"
run_case "static-todo-inside-fence-still-fails" fail "$corpus_root" \
  "forbidden token 'TODO' (placeholder contract)"

# The angle-bracket destination's target sits two directories above
# content/quickstart.md - one to the corpus root, one more to escape it, i.e.
# case_dir/repo (the same overlay directory new_case_corpus populates with
# repository-root sibling symlinks) - so the sibling file must live outside
# corpus_root entirely (inside it would itself violate the frozen inventory
# contract) but inside that overlay directory.
new_case_corpus static-angle-bracket-link-with-spaces
cp "$fixtures_dir/static/angle-bracket-link-with-spaces.md" "$corpus_root/content/quickstart.md"
printf '%s\n' \
  '# Reference guide' \
  '' \
  'Sibling fixture file referenced through an angle-bracket link destination containing an embedded space.' \
  >"$case_dir/repo/reference guide.md"
run_case "static-angle-bracket-link-with-spaces-resolves-pass" pass "$corpus_root"

# --- Sabotage cases: validator integrity (round 8) ---
# See the "(c) scanner-failure sabotage" entry in this file's header comment
# for what this single case proves (both round-8 findings at once) and why a
# distinct pipeline-masking case is not independently constructible post-fix
# without editing verify.sh itself.

new_case_corpus scanner-failure-fails-closed
if [[ "$(id -u)" == "0" ]]; then
  printf 'skip  %-62s (running as root - chmod 000 does not block root reads)\n' \
    "scanner-failure-fails-closed"
else
  chmod 000 "$corpus_root/content/quickstart.md"
  run_case "scanner-failure-fails-closed" fail "$corpus_root" \
    "internal scanner error (validator integrity)"
  chmod 644 "$corpus_root/content/quickstart.md"
fi

# --- Summary ---

if ((unexpected_outcomes > 0)); then
  printf '\n%d of %d developer-docs-tests cases produced an unexpected outcome.\n' \
    "$unexpected_outcomes" "$executed_cases" >&2
  exit 1
fi

printf '\nAll %d developer-docs-tests cases produced the expected outcome.\n' "$executed_cases"
