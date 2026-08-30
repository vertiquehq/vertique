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

# --- Document content rules ---
#
# The rules docs/module-doc-template.md states for a packaged canonical document,
# as patterns the per-document loop below applies. Parity alone let 102 of 115
# documents drift, because a document can satisfy every set comparison in this
# script while telling an application developer something false.

# A published artifact must not reference a private decision record. Both the
# hyphenated form the records themselves use and the spaced form that occurs in
# prose are matched; the leading boundary keeps a word merely ending in "adr"
# from claiming a citation.
private_decision_pattern='(^|[^[:alpha:]])[Aa][Dd][Rr][-[:space:]][0-9]{3,4}'

# Package-local feature delivery identifiers are private provenance just like
# ADR citations. Public reference explains the shipped behavior; it does not
# send application developers into a task, repair, phase, or decision ledger
# that is absent from the artifact.
private_delivery_pattern='(^|[^[:alnum:]_])(T[0-9]{3}|R[0-9]{2}|P[0-9]{2}|D[0-9]{3})([^[:alnum:]_]|$)'

# A bare section number may legitimately cite an external standard. The
# unmistakable "contract §N" form, however, refers to a private feature
# contract that is not packaged with the module document.
private_contract_section_pattern='(^|[^[:alpha:]])[Cc][Oo][Nn][Tt][Rr][Aa][Cc][Tt][[:space:]]+§[0-9]'

# Sections whose subject matter is private decision or roadmap material. The
# heading is rejected on its own: "## Related ADRs" carries that material even
# when it names no record number, so the citation pattern above cannot see it.
forbidden_heading_pattern='^##[[:space:]]+(Related ADRs|Version History|Planned Additions|Changelog)[[:space:]]*$'

# The template mandates the blockquote status header, which is how a reader
# learns whether the module's surface is safe to depend on.
status_header_pattern='^>[[:space:]]*\*\*Status:\*\*[[:space:]]+(Experimental|Alpha|Beta|Implemented|Stable)[[:space:]]*$'

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

report_violations_and_exit() {
    printf '\n%d module-documentation parity violation(s) found.\n' "$failures" >&2
    exit 1
}

# --- Parsing ---

# Both pom parsers below read comment-free input, so a commented-out block can
# never emit coordinates and a quoted coordinate can never claim an identity.
# This awk source is prefixed to each of their programs: strip_comments() removes
# the comment spans on one line and carries an unterminated span into the lines
# that follow.
strip_comments_awk='
    function strip_comments(line,   kept, boundary) {
        kept = ""
        while (line != "") {
            if (in_comment) {
                boundary = index(line, "-->")
                if (boundary == 0) { return kept }
                line = substr(line, boundary + 3)
                in_comment = 0
            } else {
                boundary = index(line, "<!--")
                if (boundary == 0) { return kept line }
                kept = kept substr(line, 1, boundary - 1)
                line = substr(line, boundary + 4)
                in_comment = 1
            }
        }
        return kept
    }
'

# Prints the dev.vertique artifactIds managed in a pom's <dependencyManagement>
# section, one per line. Coordinates inside <exclusions> or inside a comment are
# ignored.
managed_vertique_artifacts() {
    awk "$strip_comments_awk"'
        { $0 = strip_comments($0) }
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

# Prints "!unsupported!<construct>" when the pom at $1 uses markup outside the
# plain element vocabulary the parsers above and below understand, or nothing
# when the whole document is scannable.
#
# This is the single authority on what those parsers may assume, and the main
# script runs it against every pom it scans before any extraction. Both parsers
# would otherwise mis-read these constructs into plausible values: CDATA and
# processing instructions are well-formed XML that Maven happily accepts, so a
# build cannot catch them, and their tag-shaped text reads as real markup —
# coordinates quoted in a BOM usage example become managed entries, and CDATA
# that closes and reopens elements walks the depth count until a quoted
# coordinate surfaces at depth 1. Validating the ENTIRE document also matters:
# extraction stops at the value it wants, so a construct after that value would
# never be examined.
validate_pom_markup() {
    awk "$strip_comments_awk"'
        BEGIN { apostrophe = sprintf("%c", 39) }

        function reject(construct) {
            print "!unsupported!" construct
            exit
        }

        # True when a tag body ends inside a quoted attribute value, which means
        # the ">" it was cut at belongs to that value rather than closing the
        # tag. Tracking the open quote keeps an apostrophe inside a double-quoted
        # value from reading as an unbalanced quote.
        function ends_inside_quote(text,   position, character, quote) {
            quote = ""
            for (position = 1; position <= length(text); position++) {
                character = substr(text, position, 1)
                if (quote == "") {
                    if (character == "\"" || character == apostrophe) { quote = character }
                } else if (character == quote) {
                    quote = ""
                }
            }
            return quote != ""
        }

        function element_name(tag,   name) {
            name = tag
            sub(/^\//, "", name)
            sub(/[[:space:]].*$/, "", name)
            sub(/\/$/, "", name)
            return name
        }

        # Line breaks carry no meaning in XML — a pom opens <project> across two
        # lines — so the comment-free document is joined into a single buffer and
        # scanned as a tag stream rather than line by line.
        { document = document strip_comments($0) " " }

        END {
            if (in_comment) { reject("an unterminated XML comment") }
            if (index(document, "<![CDATA[") > 0) { reject("a CDATA section") }

            # Every chunk after the first opens with a tag body ending at the
            # first ">". Unlike extraction, this loop never stops early.
            chunk_count = split(document, chunk, "<")
            for (position = 2; position <= chunk_count; position++) {
                boundary = index(chunk[position], ">")
                if (boundary == 0) {
                    # Data carrying "<" splits the instruction across chunks, so
                    # the opener never reaches its own ">".
                    if (substr(chunk[position], 1, 1) == "?") {
                        reject("a processing instruction containing markup in its data")
                    }
                    reject("an unterminated tag")
                }
                tag = substr(chunk[position], 1, boundary - 1)

                if (ends_inside_quote(tag)) {
                    reject("a greater-than sign inside a quoted attribute value")
                }

                if (tag ~ /^\?/) {
                    # A well-formed processing instruction ends at "?>", so a
                    # body that does not is one whose data carries markup.
                    if (tag !~ /\?$/) {
                        reject("a processing instruction containing markup in its data")
                    }
                    continue
                }
                if (tag ~ /^!/) { continue }

                name = element_name(tag)
                if (tag ~ /^\//) {
                    if (depth == 0) { reject("a closing tag with no open element") }
                    # Element nesting must balance, or a lost closing tag lets a
                    # truncated coordinate read as a complete one.
                    if (open_element[depth] != name) {
                        reject("an element that is never closed")
                    }
                    depth--
                    continue
                }

                # A self-closing element such as <relativePath/> opens no scope.
                if (tag !~ /\/[[:space:]]*$/) { open_element[++depth] = name }
            }

            if (depth > 0) { reject("an element that is never closed") }
        }
    ' "$1"
}

# Prints the value of the element named $1 declared directly under <project> in
# the pom at $2, or nothing when the pom declares no such element. Callers must
# have cleared the pom through validate_pom_markup first.
#
# Comments are removed and element depth is tracked, so a value quoted inside a
# comment, one nested in <parent>, <dependencies> or a plugin <configuration>,
# and an element spread over several lines can never be mistaken for the
# project's own declaration. A line-oriented scan gets all three wrong, and the
# dangerous direction is silent acceptance: a commented-out copy of a sibling's
# coordinates lets a row claim another module's document, and a commented-out
# <packaging>jar</packaging> lets an aggregator pass as a consumable module.
project_element_value() {
    local element_name="$1"
    local pom="$2"

    awk -v element="$element_name" "$strip_comments_awk"'
        { document = document strip_comments($0) " " }

        END {
            chunk_count = split(document, chunk, "<")
            for (position = 2; position <= chunk_count; position++) {
                boundary = index(chunk[position], ">")
                if (boundary == 0) { break }
                tag = substr(chunk[position], 1, boundary - 1)
                content = substr(chunk[position], boundary + 1)

                if (tag ~ /^[?!]/) { continue }
                if (tag ~ /^\//) {
                    if (depth > 0) { depth-- }
                    continue
                }

                name = tag
                sub(/[[:space:]].*$/, "", name)
                sub(/\/$/, "", name)

                # Depth 1 is the document element, so this is <project>s own
                # declaration. First match wins; an empty one prints nothing and
                # the caller applies its own default.
                if (name == element && depth == 1 && open_element[1] == "project") {
                    sub(/^[[:space:]]+/, "", content)
                    sub(/[[:space:]]+$/, "", content)
                    if (content != "") { print content }
                    exit
                }

                # A self-closing element such as <relativePath/> opens no scope.
                if (tag !~ /\/[[:space:]]*$/) { open_element[++depth] = name }
            }
        }
    ' "$pom"
}

# Reports the unsupported construct a pom parser named in $2 and returns 0 so the
# caller can skip the pom; returns 1 when $2 is a usable value. Refusing to scan
# is always louder than binding a value guessed from markup the parser mis-read.
report_unsupported_pom_construct() {
    local pom_label="$1"
    local parsed_value="$2"

    [[ "$parsed_value" == '!unsupported!'* ]] || return 1
    report_failure "$pom_label cannot be parsed reliably: it contains ${parsed_value#'!unsupported!'}"
}

# Prints a Maven module's declared packaging, or nothing when it defaults to jar.
module_packaging() {
    project_element_value packaging "$1"
}

# Prints a Maven module's own artifactId, or nothing when the pom declares none.
module_artifact_id() {
    project_element_value artifactId "$1"
}

# Prints one "line-number<TAB>path" record per backticked repository-relative
# path in the canonical document at $1.
#
# Only a path anchored at one of the repository's top-level directories is
# extracted. Module documents are full of backticked tokens shaped like paths
# that name nothing on disk — MIME types (`application/json`), classpath
# resources (`META-INF/services/...`), HTTP routes (`services/users/ping`),
# build output (`target/classes/openapi.json`) and Java constructs
# (`try/catch`) — so matching every slash-bearing token would report each of
# them as a broken link and make the rule unusable.
backticked_repository_paths() {
    awk '
        {
            rest = $0
            while (match(rest, /`(docs|examples|scripts)\/[^`[:space:]]+`/) > 0) {
                print NR "\t" substr(rest, RSTART + 1, RLENGTH - 2)
                rest = substr(rest, RSTART + RLENGTH)
            }
        }
    ' "$1"
}

# Reports every line of the canonical document $2, owned by artifact $1, that
# matches the extended regular expression $4 as a violation of the rule named in
# $3. Every match is reported rather than only the first: an author fixing one
# citation would otherwise have to re-run the verifier to discover the next.
report_matching_document_lines() {
    local artifact_id="$1"
    local document="$2"
    local rule_description="$3"
    local pattern="$4"
    local match_case="${5:-insensitive}"
    local match

    local -a grep_options=(-n -E)
    if [[ "$match_case" == "insensitive" ]]; then
        grep_options+=(-i)
    fi

    while IFS= read -r match; do
        [[ -n "$match" ]] || continue
        # grep -n emits "<line-number>:<line>", and the offending line is quoted
        # back so the diagnostic names what to change, not merely where.
        report_failure "$artifact_id $rule_description: ${match#*:} (${document#"$repository_root"/} line ${match%%:*})"
    done < <(grep "${grep_options[@]}" "$pattern" "$document" || true)
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

# Both poms are rejected whole before either is scanned: parity computed from a
# document the parser mis-read is meaningless, so no extraction is attempted and
# the run ends here rather than reporting differences it cannot stand behind.
unscannable=0
if report_unsupported_pom_construct "vertique-bom/pom.xml" "$(validate_pom_markup "$bom_pom")"; then
    unscannable=1
fi
if report_unsupported_pom_construct "pom.xml" "$(validate_pom_markup "$root_pom")"; then
    unscannable=1
fi
if (( unscannable == 1 )); then
    report_violations_and_exit
fi

managed_vertique_artifacts "$bom_pom" > "$work_dir/bom-artifacts.txt"
managed_vertique_artifacts "$root_pom" > "$work_dir/root-artifacts.txt"
index_rows "$index" > "$work_dir/index-rows.txt"

while IFS= read -r unparsed_line; do
    report_failure "module index line is not a parseable artifact row: ${unparsed_line#*$'\t'}"
done < <(grep '^!unparsed!' "$work_dir/index-rows.txt")

awk -F'\t' '$1 != "!unparsed!" { print $1 }' "$work_dir/index-rows.txt" \
    > "$work_dir/index-artifacts.txt"
awk -F'\t' '$1 != "!unparsed!" { print $2 }' "$work_dir/index-rows.txt" \
    > "$work_dir/index-links.txt"

LC_ALL=C sort "$work_dir/bom-artifacts.txt" > "$work_dir/bom-sorted.txt"
LC_ALL=C sort "$work_dir/root-artifacts.txt" > "$work_dir/root-sorted.txt"
LC_ALL=C sort "$work_dir/index-artifacts.txt" > "$work_dir/index-sorted.txt"
LC_ALL=C sort "$work_dir/index-links.txt" > "$work_dir/index-links-sorted.txt"
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

# Each canonical document belongs to exactly one artifact. Without this, two
# rows can share one document and leave the other module undocumented while the
# artifact sets still report exact parity.
report_duplicates "module index canonical link" "$work_dir/index-links-sorted.txt"

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

non_public_matches="$(grep -nE 'vertique-(audit|blob|camel)|benchmarks' "$index" || true)"
if [[ -n "$non_public_matches" ]]; then
    report_failure "module index references a non-public artifact"
    printf '%s\n' "$non_public_matches" >&2
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

    # Content rules. Each is evaluated independently and none of them skips the
    # rest of the row: a drifted document usually breaks several at once, and
    # reporting only the first would hide the others behind the edit that fixes
    # it. Existence and parity are settled above, so a failure here is always a
    # statement about what the document says.
    report_matching_document_lines "$artifact_id" "$document" \
        "cites a private decision record in its canonical module document" \
        "$private_decision_pattern"

    report_matching_document_lines "$artifact_id" "$document" \
        "cites private delivery provenance in its canonical module document" \
        "$private_delivery_pattern" \
        sensitive

    report_matching_document_lines "$artifact_id" "$document" \
        "cites a private contract section in its canonical module document" \
        "$private_contract_section_pattern"

    report_matching_document_lines "$artifact_id" "$document" \
        "has a forbidden section in its canonical module document" \
        "$forbidden_heading_pattern"

    if ! grep -qE "$status_header_pattern" "$document"; then
        report_failure "$artifact_id has no valid \"> **Status:**\" blockquote (Experimental, Alpha, Beta, Implemented, or Stable) in its canonical module document at $relative_link"
    fi

    # A repository-relative path is the one link form a packaged document may use
    # for material that ships nowhere near the artifact, so an unresolved one
    # sends the reader to a file that exists only in another repository.
    while IFS=$'\t' read -r line_number referenced_path; do
        [[ -n "$referenced_path" ]] || continue
        if [[ ! -e "$repository_root/$referenced_path" ]]; then
            report_failure "$artifact_id references a repository path that does not exist in its canonical module document: $referenced_path ($relative_link line $line_number)"
        fi
    done < <(backticked_repository_paths "$document")

    module_pom="$repository_root/$module_source_root/pom.xml"
    if [[ ! -f "$module_pom" ]]; then
        report_failure "$artifact_id has no owning Maven module at $module_source_root/pom.xml"
        continue
    fi

    # Bind the row to its own module: a link that resolves to some other
    # module's document leaves this artifact undocumented even though every
    # existence and parity check above succeeds.
    # Validate before extracting, so the checks below never run against a
    # document this verifier cannot read.
    if report_unsupported_pom_construct "$module_source_root/pom.xml" \
        "$(validate_pom_markup "$module_pom")"; then
        continue
    fi

    owning_artifact_id="$(module_artifact_id "$module_pom")"
    if [[ "$owning_artifact_id" != "$artifact_id" ]]; then
        report_failure "$artifact_id links to a canonical document owned by ${owning_artifact_id:-<no artifactId>} at $module_source_root/pom.xml"
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

# --- Maintainer documents ---

# Maintainer documentation was moved to a separate governance repository, so
# DEVELOPMENT.md is not a document with content rules but a file that must not
# exist here at all. The sweep is repository-wide rather than driven by the index
# above, because a stray copy under a module that has no index row — or under no
# module at all — is exactly the one nothing else would look at. Build output and
# agent scratch space are pruned because neither is repository source.
while IFS= read -r maintainer_document; do
    [[ -n "$maintainer_document" ]] || continue
    report_failure "${maintainer_document#"$repository_root"/} must not exist: maintainer documentation belongs in the private governance repository"
done < <(find "$repository_root" \
    \( -name target -o -name .claude -o -name .git \) -prune -o \
    -type f -name DEVELOPMENT.md -print)

# --- Verdict ---

if (( failures > 0 )); then
    report_violations_and_exit
fi

printf 'PASS: %d consumable artifacts in exact BOM/root-pom/index/document parity\n' \
    "$(wc -l < "$work_dir/index-unique.txt" | tr -d '[:space:]')"
