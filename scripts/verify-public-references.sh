#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Keeps private governance references out of this public repository.
#
# The rule. Nowhere in this repository may any of the following appear in
# content that is added or changed: source comments, Javadoc, canonical module
# documents, other documentation, the developer-docs corpus, READMEs, commit
# messages, pull request titles and pull request descriptions:
#
#   - a mention of the private governance repository: its name, a URL or issue
#     reference into it, or a path into its specification or decision-record
#     trees;
#   - a citation of a decision record by number;
#   - a specification, task, phase, repair or decision identifier, whether the
#     short form of a delivery ledger or the name of a specification package.
#
# Public text states the shipped behavior in plain words. Provenance belongs in
# the private governance repository, which a reader of this repository cannot
# open, so a citation can only mislead. There is no escape hatch: no marker
# comment, allowlist file or environment variable disables a finding. Reword the
# text instead.
#
# Legacy occurrences exist, so enforcement is scoped to what a change adds:
#
#   --range <base>..<head>    scan the lines a diff ADDS (also accepts the
#                             three-dot form). Untouched context never counts;
#                             a modified line does, because its new text is added.
#   --commits <base>..<head>  scan the message (subject and body) of every
#                             non-merge commit in the range.
#   --text <file>             scan every line of a text file, such as a pull
#                             request title and description.
#   --label <name>            report --text findings under <name> instead of the
#                             file path (the file is usually a temporary copy).
#
# The modes may be combined in one invocation. Exit 0 means clean, 1 means at
# least one finding, 2 means the invocation or a range could not be evaluated;
# an unreadable range is never treated as clean.
#
# Which lines each pattern sees in --range mode:
#
#   vertique-dev-reference   every added line of every text file.
#   adr-citation             added lines of Markdown, of anything under docs or
#                            developer-docs, and of Java comments under src/main;
#                            plus every added line of Java under src/test.
#   delivery-identifier,
#   spec-identifier,
#   contract-section,
#   worktree-path            added lines of Markdown, of anything under docs or
#                            developer-docs, and of Java comments under src/main.
#                            Test sources may keep identifiers that trace a
#                            proof, and a string literal in production code is
#                            not documentation, so neither is scanned for these.
#
# This script and its test harness are exempt, because they must spell the
# patterns out. Every other path is subject to the rule.

set -euo pipefail

# Byte-wise matching: the patterns are ASCII, and a file in another encoding must
# not make sed or grep abort with an illegal-byte-sequence error.
export LC_ALL=C

# --- Patterns ---
#
# The first three are the patterns scripts/verify-module-docs.sh applies to
# canonical module documents, repeated verbatim so both guards agree on what a
# citation is. Keep them identical when either changes.

private_decision_pattern='(^|[^[:alpha:]])[Aa][Dd][Rr][-[:space:]][0-9]{3,4}'
private_delivery_pattern='(^|[^[:alnum:]_])(T[0-9]{3}|R[0-9]{2}|P[0-9]{2}|D[0-9]{3})([^[:alnum:]_]|$)'
private_contract_section_pattern='(^|[^[:alpha:]])[Cc][Oo][Nn][Tt][Rr][Aa][Cc][Tt][[:space:]]+§[0-9]'

# The governance repository's name (the boundary keeps a longer word that merely
# begins with it from matching) and the two path prefixes its record trees use.
governance_repository_pattern='vertique-dev([^[:alpha:]]|$)|(^|[^[:alnum:]_.-])docs/(specs|adr)/'

# The directory that holds a governance checkout beside the product source.
worktree_path_pattern='(^|[^[:alnum:]_-])\.worktrees/'

# A specification package or requirement id: at least two letters, a hyphen and
# exactly three digits as a whole word, optionally behind more hyphenated
# letters. The upper-case second form covers ids such as a prefixed definition
# number that has no hyphen before its digits. The first form runs over
# lower-cased text, after the standards that share its shape are removed.
spec_identifier_pattern='(^|[^[:alnum:]_])[a-z]{2,}-[0-9]{3}([^[:alnum:]_]|$)'
spec_definition_pattern='(^|[^[:alnum:]_])[A-Z]{2,}-[A-Z]{2,}[0-9]{3}([^[:alnum:]_]|$)'

# Names that precede a three-digit number in ordinary technical prose: digest
# and cipher sizes, signature algorithm names, character sets, published
# standards and weakness catalogs, and prose such as a non-200 status or a
# placeholder id.
spec_identifier_exclusions='sha|aes|rsa|des|md|hs|rs|es|ps|ecdsa|ecdh|hmac|ripemd|blake|chacha|utf|iso|rfc|tls|ssl|ecma|ieee|cwe|cve|non|over|abc'

# --- Arguments ---

range_spec=""
commits_spec=""
text_files=()
text_label=""

usage() {
    {
        printf 'Usage: %s [--range <base>..<head>] [--commits <base>..<head>] [--text <file> [--label <name>]]\n' "$0"
        printf 'At least one mode is required.\n'
    } >&2
}

while (( $# > 0 )); do
    case "$1" in
        --range)
            [[ $# -ge 2 ]] || { usage; exit 2; }
            range_spec="$2"
            shift 2
            ;;
        --commits)
            [[ $# -ge 2 ]] || { usage; exit 2; }
            commits_spec="$2"
            shift 2
            ;;
        --text)
            [[ $# -ge 2 ]] || { usage; exit 2; }
            text_files+=("$2")
            shift 2
            ;;
        --label)
            [[ $# -ge 2 ]] || { usage; exit 2; }
            text_label="$2"
            shift 2
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

if [[ -z "$range_spec" && -z "$commits_spec" && ${#text_files[@]} -eq 0 ]]; then
    usage
    exit 2
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-public-references-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

failures=0
scanned_lines=0
text_inputs=0

# --- Scanning ---

# A scan input is three line-aligned files:
#   text   the content of each line, as written
#   meta   where each line came from (a path and line number, or a commit)
#   class  F: every pattern applies; A: only the decision-record pattern and the
#          governance-repository pattern apply; N: only the latter applies.

# Reports each line of $haystack that matches the extended expression $3 and
# whose class is one of the letters in $5. $1 is the scan-input prefix, $2 the
# pattern name, $4 the case flag (i or s), $6 the haystack (defaults to text).
scan_pattern() {
    local prefix="$1"
    local name="$2"
    local expression="$3"
    local case_flag="$4"
    local applies="$5"
    local haystack="${6:-$prefix.text}"
    local -a options=(-n -E)
    local hit line_number line_class where excerpt

    if [[ "$case_flag" == "i" ]]; then
        options+=(-i)
    fi

    while IFS= read -r hit; do
        [[ -n "$hit" ]] || continue
        line_number="${hit%%:*}"
        line_class="$(sed -n "${line_number}p" "$prefix.class")"
        [[ "$applies" == *"$line_class"* ]] || continue
        where="$(sed -n "${line_number}p" "$prefix.meta")"
        excerpt="$(sed -n "${line_number}p" "$prefix.text" | sed -E 's/^[[:space:]]+//' | cut -c1-160)"
        printf 'FAIL: %s: %s: %s\n' "$where" "$name" "$excerpt" >&2
        failures=$((failures + 1))
    done < <(grep "${options[@]}" -e "$expression" "$haystack" || true)
}

# Runs every pattern over the scan input with prefix $1.
scan_input() {
    local prefix="$1"
    local lowered_exclusions

    scanned_lines=$((scanned_lines + $(wc -l < "$prefix.text" | tr -d '[:space:]')))

    scan_pattern "$prefix" vertique-dev-reference "$governance_repository_pattern" i NAF
    scan_pattern "$prefix" adr-citation "$private_decision_pattern" i AF
    scan_pattern "$prefix" delivery-identifier "$private_delivery_pattern" s F
    scan_pattern "$prefix" contract-section "$private_contract_section_pattern" i F
    scan_pattern "$prefix" worktree-path "$worktree_path_pattern" i F
    scan_pattern "$prefix" spec-identifier "$spec_definition_pattern" s F

    # Lower-case once, then drop the allowed standards. The removal runs twice so
    # two allowed names separated by a single character are both removed.
    lowered_exclusions="s/(^|[^[:alnum:]_])($spec_identifier_exclusions)-[0-9]{3}(\$|[^[:alnum:]_])/\\1x\\3/g"
    tr '[:upper:]' '[:lower:]' < "$prefix.text" \
        | sed -E "$lowered_exclusions" \
        | sed -E "$lowered_exclusions" > "$prefix.lowered"
    scan_pattern "$prefix" spec-identifier "$spec_identifier_pattern" s F "$prefix.lowered"
}

# Builds a scan input for plain text: every line is fully scanned.
# $1 = prefix, $2 = source file, $3 = where-label.
build_text_input() {
    local prefix="$1"
    local source_file="$2"
    local label="$3"

    cp "$source_file" "$prefix.text"
    # A file without a trailing newline would drop its last line from wc and sed.
    if [[ -s "$prefix.text" && "$(tail -c 1 "$prefix.text" | wc -l | tr -d '[:space:]')" == "0" ]]; then
        printf '\n' >> "$prefix.text"
    fi
    awk -v label="$label" '{ printf "%s:%d\n", label, NR }' "$prefix.text" > "$prefix.meta"
    awk '{ print "F" }' "$prefix.text" > "$prefix.class"
}

scan_range() {
    local spec="$1"
    local prefix="$work_dir/range"
    local diff_file="$work_dir/range.diff"

    if ! git -c core.quotepath=false diff -U0 --no-color --no-ext-diff --no-renames \
        --diff-filter=d "$spec" > "$diff_file" 2> "$work_dir/range.err"; then
        printf 'verify-public-references: cannot diff range %s: %s\n' \
            "$spec" "$(cat "$work_dir/range.err")" >&2
        exit 2
    fi

    # Only the ADDED side of each hunk is emitted, with the new file's line
    # number. A "+++" line is a file header only before the first hunk: an added
    # line whose own text begins with two plus signs also starts with them.
    awk -v text="$prefix.text" -v meta="$prefix.meta" -v class="$prefix.class" '
        function line_class(path, content,   java, comment, documentation) {
            java = (path ~ /\.java$/)
            documentation = (path ~ /\.mdx?$/ || path ~ /(^|\/)(developer-docs|docs)\//)
            comment = (content ~ /^[ \t]*(\*|\/\*|\/\/)/)
            if (documentation) { return "F" }
            if (java && path ~ /(^|\/)src\/main\// && comment) { return "F" }
            if (java && path ~ /(^|\/)src\/test\//) { return "A" }
            return "N"
        }
        BEGIN { printf "" > text; printf "" > meta; printf "" > class }
        /^diff --git / { in_header = 1; path = ""; next }
        in_header && /^\+\+\+ / {
            candidate = substr($0, 5)
            sub(/\t.*$/, "", candidate)
            if (candidate == "/dev/null") { path = "" } else {
                sub(/^"/, "", candidate); sub(/"$/, "", candidate); sub(/^b\//, "", candidate)
                path = candidate
            }
            next
        }
        /^@@ / {
            in_header = 0
            hunk = $0
            sub(/^@@ -[0-9,]+ \+/, "", hunk)
            sub(/[ ,].*$/, "", hunk)
            line_number = hunk + 0
            next
        }
        in_header { next }
        /^\+/ {
            if (path != "" && path != "scripts/verify-public-references.sh" \
                && path != "scripts/test-verify-public-references.sh") {
                content = substr($0, 2)
                printf "%s\n", content > text
                printf "%s:%d\n", path, line_number > meta
                printf "%s\n", line_class(path, content) > class
            }
            line_number++
        }
    ' "$diff_file"

    scan_input "$prefix"
}

scan_commits() {
    local spec="$1"
    local base head sha message_file

    # Three dots would select the symmetric difference, which would scan the
    # base side's commits too; only what the head adds is in scope.
    if [[ "$spec" == *...* ]]; then
        base="${spec%%...*}"
        head="${spec#*...}"
        spec="$base..$head"
    fi

    if ! git rev-list --no-merges "$spec" > "$work_dir/commits.list" 2> "$work_dir/commits.err"; then
        printf 'verify-public-references: cannot list commits %s: %s\n' \
            "$spec" "$(cat "$work_dir/commits.err")" >&2
        exit 2
    fi

    while IFS= read -r sha; do
        [[ -n "$sha" ]] || continue
        message_file="$work_dir/message-$sha"
        git log -1 --format=%B "$sha" > "$message_file"
        build_text_input "$work_dir/commit-$sha" "$message_file" "commit ${sha:0:12}"
        scan_input "$work_dir/commit-$sha"
    done < "$work_dir/commits.list"
}

scan_text_file() {
    local file="$1"
    local label="${text_label:-$file}"

    if [[ ! -f "$file" ]]; then
        printf 'verify-public-references: no such file: %s\n' "$file" >&2
        exit 2
    fi
    text_inputs=$((text_inputs + 1))
    build_text_input "$work_dir/text-$text_inputs" "$file" "$label"
    scan_input "$work_dir/text-$text_inputs"
}

if [[ -n "$range_spec" ]]; then
    scan_range "$range_spec"
fi

if [[ -n "$commits_spec" ]]; then
    scan_commits "$commits_spec"
fi

if (( ${#text_files[@]} > 0 )); then
    for text_file in "${text_files[@]}"; do
        scan_text_file "$text_file"
    done
fi

if (( failures > 0 )); then
    {
        printf '\n%d private-reference finding(s).\n' "$failures"
        printf 'Fix: reword each flagged line to state the shipped behavior in plain words, without naming\n'
        printf 'the governance repository, a decision record, or a specification, task, phase, repair or\n'
        printf 'decision identifier. Rewrite a commit message with git commit --amend (or an interactive\n'
        printf 'rebase for older commits) and edit a pull request title or description on the pull request.\n'
        printf 'There is no suppression mechanism.\n'
    } >&2
    exit 1
fi

printf 'PASS: no private governance references in %d scanned line(s)\n' "$scanned_lines"
