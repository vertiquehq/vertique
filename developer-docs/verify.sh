#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Validates the developer-docs corpus contract defined by DOCS-001:
#
#   (a) inventory   - exactly README.md, navigation.yml, verify.sh, and the
#                      twelve content/*.md pages, no more, no fewer.
#   (b) navigation  - navigation.yml lists every page filename exactly once,
#                      in the frozen order, under a sole `pages:` key.
#   (c) frontmatter - every content/*.md page opens with YAML frontmatter
#                      containing exactly one non-empty `title` and one
#                      non-empty `description`, and no other keys. A scalar
#                      value of `null` (any case, quoted or not) counts as
#                      empty. A scalar must not carry a trailing comment: a
#                      quoted value followed by anything but whitespace, or
#                      an unquoted value containing `#`, is rejected outright
#                      rather than silently accepted or silently emptied.
#   (d) links       - every relative Markdown link target (including targets
#                      that point out of developer-docs/, e.g. into
#                      docs/modules.md), whether an inline `](target)` link
#                      or a reference-style `[label]: target` definition
#                      outside a fenced code block, resolves to a file that
#                      exists on disk from the linking file's own directory.
#                      A CommonMark angle-bracket destination
#                      (`](<target with spaces.md>)`) is recognized in full,
#                      including embedded spaces. Absolute http(s)/mailto/etc
#                      URI-scheme targets are out of scope and never checked.
#   (e) tokens      - no case-insensitive TODO, TBD (singular or plural), or
#                      latest token, and no angle-bracket pseudo-value
#                      placeholder — a lowercase hyphenated form
#                      (<placeholder-name>), an ALL-CAPS form (<VERSION>), or
#                      a lowercase-start camelCase form (<vertiqueVersion>) —
#                      outside a fenced ```xml code block. XML fences are the
#                      only place angle-bracket placeholders are tolerated;
#                      TODO/TBD/latest remain forbidden everywhere, including
#                      inside an xml fence. None of the placeholder forms
#                      match a Java generic such as <String> or <T>.
#   (f) renderer    - no Astro/Starlight/MDX import line or JSX component tag
#                      in the prose of any corpus Markdown file (both scans
#                      skip fenced code blocks of any kind, so a legitimate
#                      ```java import or a `Optional<Response<String>>`
#                      generic is never mistaken for renderer markup), and no
#                      ':::' directive anywhere in the file. Any
#                      locale/version/redirect/sidebar key in frontmatter or
#                      navigation.yml is already rejected by (b)/(c)'s
#                      "no other keys" rule, so it is not re-checked here.
#   (g) corpus grammar - the corpus adopts a deliberately restricted subset
#                      of Markdown so fence- and link-extraction never need a
#                      real parser: every code fence opens with backticks at
#                      column zero (a 1-3-space-indented ``` fence or any
#                      '~~~' fence is rejected outright), every reference-
#                      style link definition (`[label]: target`) starts at
#                      column zero (an indented one is rejected outright
#                      rather than silently skipped), and a bare
#                      (non-angle-bracket) inline link destination must not
#                      contain a literal '(' — use the angle-bracket
#                      destination form (`](<target(with)parens>)`) instead.
#
# Every violation is reported (the run never stops at the first failure);
# the process exit code is non-zero whenever at least one violation fires.
#
# Usage: verify.sh [corpus-root]
#   no argument    - validate this repository's developer-docs/ directory,
#                     resolved from this script's own location.
#   corpus-root    - validate corpus-root as a disposable corpus copy.
#
# Uses only Bash and standard Unix tools (find, grep, sed, awk). No network
# access is performed or required.

set -u

readonly -a PAGE_ORDER=(
  index.md
  quickstart.md
  application-model.md
  configuration.md
  rest-apis.md
  services.md
  persistence.md
  workflows.md
  security.md
  testing.md
  deployment.md
  artifacts.md
)

FAILURES=0

# --- Reporting ---

# Records one violation with a path-specific diagnostic and keeps going.
fail() {
  FAILURES=$((FAILURES + 1))
  echo "FAIL: $1" >&2
}

# --- Corpus root resolution ---

# Resolves the corpus root to validate and sets the global CORPUS_ROOT: the
# given argument (a disposable corpus copy) or, with no argument, this
# script's own directory (the repository's developer-docs/). Invalid usage
# or a non-existent corpus root exits the process directly — this must be
# called as a plain statement, never inside a command substitution, or its
# `exit` would only terminate a subshell instead of the script.
resolve_corpus_root() {
  if [[ $# -gt 1 ]]; then
    echo "Usage: $(basename "${BASH_SOURCE[0]}") [corpus-root]" >&2
    exit 2
  fi

  if [[ $# -eq 1 ]]; then
    if [[ ! -d "$1" ]]; then
      echo "verify.sh: corpus root '$1' is not a directory" >&2
      exit 2
    fi
    CORPUS_ROOT="$(cd "$1" && pwd)"
  else
    CORPUS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  fi
}

# --- (a) Inventory ---

# Reports whether needle exactly equals one element of the remaining
# arguments. Used instead of a `"${arr[*]}"` substring-membership test,
# which is unsafe once an element's name contains a space (a space-joined
# haystack can't tell "a b" as one element from "a" and "b" as two).
array_contains_exact() {
  local needle="$1"
  shift
  local item
  for item in "$@"; do
    [[ "$item" == "$needle" ]] && return 0
  done
  return 1
}

# Confirms the corpus contains exactly the frozen fifteen-file inventory:
# missing files and unexpected extra files are both violations.
check_inventory() {
  local -a expected=(README.md navigation.yml verify.sh)
  local page
  for page in "${PAGE_ORDER[@]}"; do
    expected+=("content/$page")
  done

  # Built with line-safe / NUL-safe read loops rather than unquoted `$(...)`
  # array assignment, so a corpus file whose name contains a space stays one
  # array element instead of being word-split into two (inventory contract).
  local -a expected_sorted=() actual=()
  local f
  while IFS= read -r f; do
    expected_sorted+=("$f")
  done < <(printf '%s\n' "${expected[@]}" | sort)

  while IFS= read -r -d '' f; do
    actual+=("${f#./}")
  done < <(cd "$CORPUS_ROOT" && find . -type f -print0 | sort -z)

  for f in "${expected_sorted[@]}"; do
    if ! array_contains_exact "$f" "${actual[@]}"; then
      fail "$CORPUS_ROOT/$f: expected corpus file is missing (inventory contract)"
    fi
  done
  for f in "${actual[@]}"; do
    if ! array_contains_exact "$f" "${expected_sorted[@]}"; then
      fail "$CORPUS_ROOT/$f: file is not part of the frozen corpus inventory (inventory contract)"
    fi
  done
}

# --- (b) Navigation ---

# Confirms navigation.yml has exactly one top-level key (`pages:`) whose
# list value equals PAGE_ORDER exactly, in order, with no duplicates.
check_navigation() {
  local nav="$CORPUS_ROOT/navigation.yml"
  if [[ ! -f "$nav" ]]; then
    fail "$nav: file is missing; cannot validate the navigation contract"
    return
  fi

  local -a top_level_keys=() pages=()
  local in_pages=false line

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line//[[:space:]]/}" ]] && continue
    [[ "$line" =~ ^[[:space:]]*# ]] && continue

    if [[ "$line" =~ ^[^[:space:]] ]]; then
      top_level_keys+=("$line")
      if [[ "$line" == "pages:" ]]; then
        in_pages=true
      else
        in_pages=false
      fi
      continue
    fi

    if $in_pages; then
      if [[ "$line" =~ ^[[:space:]]*-[[:space:]]+([A-Za-z0-9._-]+)[[:space:]]*$ ]]; then
        pages+=("${BASH_REMATCH[1]}")
      else
        fail "$nav: malformed 'pages' entry (navigation contract): '$line'"
      fi
    fi
  done <"$nav"

  if [[ "${#top_level_keys[@]}" -ne 1 || "${top_level_keys[0]}" != "pages:" ]]; then
    fail "$nav: must contain exactly one top-level key 'pages:' (navigation contract); found: ${top_level_keys[*]:-none}"
  fi

  if [[ "${pages[*]:-}" != "${PAGE_ORDER[*]}" ]]; then
    fail "$nav: 'pages' must list every page exactly once in the frozen order (navigation contract); expected [${PAGE_ORDER[*]}] but found [${pages[*]:-none}]"
  fi
}

# --- (c) Frontmatter ---

# Confirms every content/*.md page opens with YAML frontmatter containing
# exactly one non-empty title and one non-empty description, and no other
# keys.
check_frontmatter() {
  local page f
  for page in "${PAGE_ORDER[@]}"; do
    f="$CORPUS_ROOT/content/$page"
    [[ -f "$f" ]] || continue # already reported as missing by check_inventory
    check_frontmatter_of_file "$f"
  done
}

# Strips one layer of matching surrounding quotes (double or single) from a
# frontmatter scalar value, so a quoted-empty value (`""` or `''`) is treated
# as empty by the non-empty check below rather than as a non-empty two-quote
# literal (frontmatter contract).
strip_frontmatter_quotes() {
  local v="$1"
  local len="${#v}"
  # Positive-length substring (offset 1, length len-2) rather than a
  # negative-length form, so this runs unmodified under bash 3.2 (macOS
  # /bin/bash), which rejects a negative substring length.
  if [[ "$len" -ge 2 && "${v:0:1}" == "${v: -1}" && ("${v:0:1}" == '"' || "${v:0:1}" == "'") ]]; then
    v="${v:1:$((len - 2))}"
  fi
  printf '%s' "$v"
}

# Reports (via exit status) whether a frontmatter scalar's raw, unstripped
# text carries a disallowed trailing comment: a quoted value followed by
# anything but whitespace after its closing quote (most commonly an
# unquoted ' # comment' suffix), or a bare (unquoted) value containing a
# literal '#' anywhere. This corpus's frontmatter never allows a YAML
# comment on a `key: value` line, so either form is rejected outright
# rather than silently accepted or silently normalized to empty
# (frontmatter contract).
frontmatter_scalar_has_trailing_comment() {
  local v="$1" quote rest before trailing
  quote="${v:0:1}"
  if [[ "$quote" == '"' || "$quote" == "'" ]]; then
    rest="${v:1}"
    before="${rest%%"$quote"*}"
    if [[ "$before" == "$rest" ]]; then
      return 1 # unterminated quote; not a trailing-comment case
    fi
    trailing="${rest:$((${#before} + 1))}"
    [[ -n "${trailing//[[:space:]]/}" ]]
    return
  fi
  [[ "$v" == *'#'* ]]
}

# Reports (via exit status) whether a frontmatter scalar value is empty, or
# is the literal `null` (case-insensitive, quoted or not) once one layer of
# matching quotes is stripped — either form makes the value unusable as a
# `title`/`description` (frontmatter contract).
frontmatter_scalar_is_empty_or_null() {
  local stripped lowered
  stripped="$(strip_frontmatter_quotes "$1")"
  if [[ -z "$stripped" ]]; then
    return 0
  fi
  lowered="$(printf '%s' "$stripped" | tr '[:upper:]' '[:lower:]')"
  [[ "$lowered" == "null" ]]
}

# Parses and validates the frontmatter block of a single content page.
check_frontmatter_of_file() {
  local f="$1"
  local first_line close_line
  first_line=$(sed -n '1p' "$f")
  first_line="${first_line%$'\r'}"
  if [[ "$first_line" != "---" ]]; then
    fail "$f: must open with a YAML frontmatter block delimited by '---' (frontmatter contract)"
    return
  fi

  close_line=$(awk '{ sub(/\r$/, "") } NR>1 && $0=="---"{print NR; exit}' "$f")
  if [[ -z "$close_line" ]]; then
    fail "$f: frontmatter block is not closed with a second '---' line (frontmatter contract)"
    return
  fi

  local -a keys=()
  local title_count=0 desc_count=0
  local title_empty=false desc_empty=false
  local line key value

  while IFS= read -r line; do
    line="${line%$'\r'}"
    [[ -z "${line//[[:space:]]/}" ]] && continue
    if [[ "$line" =~ ^([A-Za-z0-9_-]+):[[:space:]]*(.*)$ ]]; then
      key="${BASH_REMATCH[1]}"
      value="${BASH_REMATCH[2]}"
      keys+=("$key")
      if frontmatter_scalar_has_trailing_comment "$value"; then
        fail "$f: frontmatter value must not carry a trailing comment (frontmatter contract): '$line'"
      fi
      case "$key" in
      title)
        title_count=$((title_count + 1))
        frontmatter_scalar_is_empty_or_null "$value" && title_empty=true
        ;;
      description)
        desc_count=$((desc_count + 1))
        frontmatter_scalar_is_empty_or_null "$value" && desc_empty=true
        ;;
      esac
    else
      fail "$f: unparseable frontmatter line (frontmatter contract): '$line'"
    fi
  done < <(sed -n "2,$((close_line - 1))p" "$f")

  local -a other_keys=()
  for key in "${keys[@]}"; do
    [[ "$key" != "title" && "$key" != "description" ]] && other_keys+=("$key")
  done

  if [[ "$title_count" -ne 1 ]]; then
    fail "$f: frontmatter must contain exactly one 'title' key (frontmatter contract); found $title_count"
  elif $title_empty; then
    fail "$f: frontmatter 'title' must be non-empty (frontmatter contract)"
  fi

  if [[ "$desc_count" -ne 1 ]]; then
    fail "$f: frontmatter must contain exactly one 'description' key (frontmatter contract); found $desc_count"
  elif $desc_empty; then
    fail "$f: frontmatter 'description' must be non-empty (frontmatter contract)"
  fi

  if [[ "${#other_keys[@]}" -gt 0 ]]; then
    fail "$f: frontmatter contains forbidden keys (frontmatter contract): ${other_keys[*]}"
  fi
}

# --- (g) Fence grammar ---

# Confirms every code fence in the corpus opens with backticks at column
# zero: a ``` fence indented by 1-3 spaces, or any '~~~' fence (indented or
# not), is rejected outright rather than silently mis-stripped. This lets
# strip_all_fences and strip_xml_fences below legitimately assume every real
# fence is a column-zero backtick fence (corpus grammar).
check_fence_grammar() {
  local f
  while IFS= read -r -d '' f; do
    check_fence_grammar_of_file "$f"
  done < <(find "$CORPUS_ROOT" -name '*.md' -print0)
}

# Scans a single file for non-canonical fence forms and reports each match.
check_fence_grammar_of_file() {
  local f="$1"
  local hit line_no

  while IFS= read -r hit; do
    line_no="${hit%%:*}"
    fail "$f:$line_no: code fences must open with backticks at column zero (corpus grammar)"
  done < <(grep -noE '^ {1,3}```' "$f")

  while IFS= read -r hit; do
    line_no="${hit%%:*}"
    fail "$f:$line_no: '~~~' code fences are forbidden; use backtick fences at column zero (corpus grammar)"
  done < <(grep -noE '^[[:space:]]*~~~' "$f")
}

# --- Fence handling ---

# Strips the content of every fenced code block (any info-string, or none) —
# and its fence marker lines — from a file, replacing them with blank lines
# so downstream line numbers stay aligned with the original file. Used to
# keep prose-only rules (renderer-neutrality, reference-style links) out of
# legitimate code samples such as Java generics or JSON braces. Assumes
# every real fence opens at column zero with backticks — check_fence_grammar
# above rejects any file that violates that assumption.
strip_all_fences() {
  awk '
    BEGIN { in_fence = 0 }
    /^```/ {
      in_fence = !in_fence
      print ""
      next
    }
    { print (in_fence ? "" : $0) }
  ' "$1"
}

# --- (d) Links ---

# Confirms every relative Markdown link target across all corpus Markdown
# files resolves to an existing path from the linking file's own directory.
check_links() {
  local f
  while IFS= read -r -d '' f; do
    check_links_of_file "$f"
  done < <(find "$CORPUS_ROOT" -name '*.md' -print0)
}

# Validates a single relative link target, resolving it from dir and
# reporting a failure for the given file if it doesn't exist. Absolute
# http(s)/mailto/etc URI-scheme targets and pure in-page anchors are out of
# scope and never checked (link contract).
check_link_target() {
  local f="$1" dir="$2" target="$3"
  local path_part resolved
  [[ -z "$target" ]] && return
  [[ "$target" =~ ^[A-Za-z][A-Za-z0-9+.-]*: ]] && return # URI scheme (e.g. https:, mailto:)
  [[ "$target" == \#* ]] && return                        # pure in-page anchor
  path_part="${target%%#*}"
  [[ -z "$path_part" ]] && return
  resolved="$dir/$path_part"
  if [[ ! -e "$resolved" ]]; then
    fail "$f: broken relative link target '$target' (link contract); resolved to '$resolved'"
  fi
}

# Extracts and resolves every relative link target in a single file: both
# inline `](target)` links — including the CommonMark angle-bracket
# destination form `](<target with spaces.md>)` — and reference-style
# `[label]: target` definitions found outside fenced code blocks, which must
# start at column zero (an indented one is rejected as a corpus-grammar
# violation rather than silently skipped). A bare (non-angle-bracket) inline
# destination containing a literal '(' is rejected as a corpus-grammar
# violation rather than silently truncated at the first ')' — such a target
# must use the angle-bracket destination form instead.
check_links_of_file() {
  local f="$1"
  local dir raw target line hit
  dir=$(dirname "$f")

  while IFS= read -r raw; do
    target="${raw#](}"
    if [[ "$target" == '<'*'>' ]]; then
      target="${target#<}"
      target="${target%>}"
    elif [[ "$target" == *'('* ]]; then
      fail "$f: bare link destination contains '(' ('$target'); use the angle-bracket destination form '(<target>)' instead (corpus grammar)"
      continue
    fi
    check_link_target "$f" "$dir" "$target"
  done < <(strip_all_fences "$f" | grep -oE '\]\(<[^>]*>|\]\([^)[:space:]]+')

  while IFS= read -r hit; do
    fail "$f:${hit%%:*}: reference-style link definitions must start at column zero, not be indented (corpus grammar)"
  done < <(strip_all_fences "$f" | grep -noE '^[ ]{1,3}\[[^]]+\]:')

  while IFS= read -r line; do
    [[ "$line" =~ ^\[[^]]+\]:[[:space:]]*(.+)$ ]] || continue
    target="${BASH_REMATCH[1]}"
    if [[ "$target" == '<'*'>' ]]; then
      target="${target#<}"
      target="${target%>}"
    fi
    check_link_target "$f" "$dir" "$target"
  done < <(strip_all_fences "$f" | grep -oE '^\[[^]]+\]:[[:space:]]*<[^>]*>|^\[[^]]+\]:[[:space:]]*[^[:space:]]+')
}

# --- (e) Forbidden tokens ---

# Confirms no forbidden token (TODO, TBD, or their plurals, or latest;
# matched case-insensitively) or angle-bracket placeholder outside a ```xml
# fence appears in corpus Markdown files.
check_forbidden_tokens() {
  local f
  while IFS= read -r -d '' f; do
    check_forbidden_tokens_of_file "$f"
  done < <(find "$CORPUS_ROOT" -name '*.md' -print0)
}

# Strips the content of ```xml fenced code blocks (and fence marker lines)
# from a file, replacing them with blank lines so downstream line numbers
# stay aligned with the original file. Assumes every real fence opens at
# column zero with backticks — check_fence_grammar above rejects any file
# that violates that assumption.
strip_xml_fences() {
  awk '
    BEGIN { in_xml = 0 }
    /^```/ {
      if (in_xml) {
        in_xml = 0
      } else {
        info = $0
        sub(/^```/, "", info)
        gsub(/^[ \t]+|[ \t\r]+$/, "", info)
        if (tolower(info) == "xml") { in_xml = 1 }
      }
      print ""
      next
    }
    { print (in_xml ? "" : $0) }
  ' "$1"
}

# Scans a single file for forbidden tokens and reports each match.
check_forbidden_tokens_of_file() {
  local f="$1"
  local hit line_no token

  # TODO/TBD (singular or plural) and latest are forbidden everywhere,
  # including inside xml fences, case-insensitively (e.g. Latest, TODOs).
  while IFS= read -r hit; do
    line_no="${hit%%:*}"
    token="${hit#*:}"
    fail "$f:$line_no: forbidden token '$token' (placeholder contract)"
  done < <(grep -inoE '\b(TODO|TBD)S?\b|\blatest\b' "$f")

  # Angle-bracket pseudo-value placeholders are forbidden outside xml fences:
  # lowercase-hyphenated (<placeholder-name>), ALL-CAPS (<VERSION>), and
  # lowercase-start camelCase (<vertiqueVersion>). Chosen so none match a Java
  # generic such as <String>, <T>, or <Response<String>>.
  while IFS= read -r hit; do
    line_no="${hit%%:*}"
    token="${hit#*:}"
    fail "$f:$line_no: forbidden angle-bracket placeholder '$token' outside an xml fence (placeholder contract)"
  done < <(strip_xml_fences "$f" | grep -noE '<[a-z][a-z0-9-]*>|<[A-Z][A-Z0-9_]{2,}>|<[a-z][a-z0-9]*[A-Z][A-Za-z0-9]*>')
}

# --- (f) Renderer/site metadata ---

# Confirms no Astro/Starlight/MDX import, ':::' directive, or JSX component
# tag appears in any corpus Markdown file.
check_renderer_metadata() {
  local f
  while IFS= read -r -d '' f; do
    check_renderer_metadata_of_file "$f"
  done < <(find "$CORPUS_ROOT" -name '*.md' -print0)
}

# Scans a single file for renderer-specific syntax and reports each match.
# The import-line and JSX-tag scans are prose-only rules, so they run
# against a fence-stripped view of the file: a legitimate ```java fence
# containing `import dev.vertique...;` or a `Optional<Response<String>>`
# generic must never trip these two rules. The ':::' directive has no
# legitimate code-fence use, so it is still scanned everywhere.
check_renderer_metadata_of_file() {
  local f="$1"
  local hit line_no

  while IFS= read -r hit; do
    line_no="${hit%%:*}"
    fail "$f:$line_no: renderer import syntax is forbidden in corpus Markdown (renderer-neutrality contract)"
  done < <(strip_all_fences "$f" | grep -noE '^[[:space:]]*import[[:space:]]')

  while IFS= read -r hit; do
    line_no="${hit%%:*}"
    fail "$f:$line_no: ':::' directive syntax is forbidden in corpus Markdown (renderer-neutrality contract)"
  done < <(grep -noF ':::' "$f")

  while IFS= read -r hit; do
    line_no="${hit%%:*}"
    fail "$f:$line_no: JSX component tag syntax is forbidden in corpus Markdown (renderer-neutrality contract)"
  done < <(strip_all_fences "$f" | grep -noE '<[A-Z][A-Za-z0-9]*[[:space:]/>]')
}

# --- Main ---

main() {
  resolve_corpus_root "$@"

  check_inventory
  check_navigation
  check_frontmatter
  check_fence_grammar
  check_links
  check_forbidden_tokens
  check_renderer_metadata

  if [[ "$FAILURES" -eq 0 ]]; then
    echo "PASS"
    exit 0
  fi

  echo "FAIL ($FAILURES violation$([[ "$FAILURES" -ne 1 ]] && echo s))"
  exit 1
}

main "$@"
