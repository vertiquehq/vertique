#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Validates the developer-docs corpus contract defined by DOCS-001:
#
#   (a) inventory   - exactly README.md, navigation.yml, verify.sh, and the
#                      thirteen content/*.md pages, no more, no fewer.
#   (b) navigation  - navigation.yml lists every page filename exactly once,
#                      in the frozen order, under a sole `pages:` key.
#   (c) frontmatter - every content/*.md page opens with YAML frontmatter
#                      containing exactly one non-empty `title` and one
#                      non-empty `description`, and no other keys. Leading
#                      and trailing whitespace around a scalar value is
#                      trimmed before any other rule below is applied, so a
#                      stray trailing space never masks an otherwise-invalid
#                      value. A scalar value of `null` (any case, quoted or
#                      not, once trimmed) counts as empty. A bare (unquoted)
#                      value must not contain a literal `#` or a `"`
#                      character (quote the value if it needs either) — a
#                      leading `'` is reserved quoting syntax (see below), so
#                      a bare value may only carry a `'` elsewhere in the
#                      value, never as its first character; a value wrapped
#                      in one matching pair of `"` may contain an embedded
#                      `'` but not another `"`, and a value wrapped in one
#                      matching pair of `'` may contain an embedded `"` but
#                      not another `'` (opposite-quote nesting only — a value
#                      needing both quote characters is not representable in
#                      this restricted grammar); a quoted value may contain a
#                      literal `#`, since the surrounding quotes remove the
#                      ambiguity with a trailing comment; an opening quote
#                      with no matching closing quote is rejected outright
#                      rather than accepted as a non-empty value. None of
#                      these forms is silently accepted or silently
#                      normalized to empty.
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
#                      real parser, enforced by a single fence state machine
#                      shared by grammar checking and stripping (so a
#                      grammar decision and a stripping decision can never
#                      disagree): every code fence opens with exactly three
#                      backticks at column zero, optionally followed by an
#                      info string that must not itself contain a backtick.
#                      Four or more backticks at column zero, any '~~~'
#                      fence, a fence whose info string contains a backtick
#                      (e.g. ` ```foo`bar `), and a backtick/tilde fence
#                      indented by any leading whitespace or hidden behind
#                      one or more container prefixes (a blockquote `>`, a
#                      list marker `-`/`*`/`+`, or a dot- or paren-delimited
#                      ordered-list marker such as `1.` or `1)`, repeated or
#                      nested in any combination, e.g. `- ``` ` or
#                      `> > ``` `) are all rejected outright rather than
#                      silently accepted or mis-stripped; a fence-like line
#                      inside an already-open fence (e.g. a nested ` ```lang `
#                      line) is rejected rather than treated as a new fence,
#                      and an unclosed fence at end of file is rejected.
#                      Every reference-style link definition
#                      (`[label]: target`) starts at column zero (an indented
#                      one, or one hidden behind the same container-prefix
#                      forms described above, repeated or nested in any
#                      combination, is rejected outright rather than silently
#                      skipped or silently passed through unresolved), and a
#                      bare (non-angle-bracket) inline link destination must
#                      not contain a literal '(' — use the angle-bracket
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
#
# Validator integrity: every check below that scans corpus content through
# an external find/grep/sed/awk producer captures that producer's own exit
# status and validates it via check_scan_status, which never fails open
# into an empty result set and a false PASS. Status 1 as a legitimate
# "found nothing" result is accepted ONLY for a grep consumer
# (check_scan_status's "grep" mode) - find/sed/awk have no such convention
# of their own (find enumerates and exits 0 even when nothing matched;
# sed/awk exit 0 whether or not a pattern matched or a line was printed),
# so check_scan_status's "strict" mode accepts status 0 only for those
# producers; any other status is a scanner failure in its own right. A
# two-stage "producer | grep" scan is never validated as a single pipeline
# status, even with `pipefail` enabled below: pipefail surfaces only the
# rightmost non-zero exit code, which can mask a producer failure behind a
# grep exit of 0 or 1 (e.g. producer=2, grep=1 -> pipeline status 1,
# indistinguishable from a clean "nothing matched" grep run). Every such
# scan instead runs through stripped_grep_scan, which captures the
# producer's own output first and checks its status in "strict" mode
# before ever handing that output to grep, whose own status is then
# checked independently in "grep" mode. `pipefail` stays enabled as a
# general-purpose default for any future pipeline this script adds, not
# because either scan stage above still depends on it.

set -u
set -o pipefail

readonly -a PAGE_ORDER=(
  index.md
  quickstart.md
  concepts.md
  application-model.md
  configuration.md
  rest-apis.md
  rest-jaxrs-compatibility.md
  services.md
  persistence.md
  workflows.md
  jobs.md
  inbox-outbox.md
  security.md
  testing.md
  deployment.md
  artifacts.md
)

FAILURES=0

# --- Reporting ---

# Records one violation with a path-specific diagnostic and keeps going.
# $1 routinely embeds raw corpus content (a frontmatter line, a link
# target, a matched token, or - via a NUL-delimited find(1) enumeration - a
# corpus path segment) verbatim, so control characters - an ANSI escape
# sequence, a stray NUL - are stripped before the message ever reaches a CI
# log, rather than trusting corpus content not to spoof or corrupt terminal
# output. A path is not guaranteed single-line the way an extracted content
# line is: NUL is the only byte find(1) -print0 refuses to put in a
# filename, so a legally NUL-delimited enumeration result may still carry a
# literal LF or CR. Left untouched - preserved, as an earlier revision of
# this function did on the theory that no diagnostic embeds them - that
# lets a maliciously or accidentally named corpus file inject fabricated
# "FAIL: ..." lines into the CI log (log-injection contract). CR and LF are
# therefore folded to a single space each, a visible, single-line-safe
# substitute, rather than preserved or silently deleted; tab is still
# preserved, since unlike CR/LF it never introduces a new output line. The
# violation is still counted before sanitizing, so a sanitizer hiccup can
# never itself cause a violation to go unreported.
fail() {
  FAILURES=$((FAILURES + 1))
  local msg
  msg="$(printf '%s' "$1" | tr '\r\n' '  ' | tr -d '\000-\010\013\014\016-\037')"
  echo "FAIL: $msg" >&2
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

# --- Scanner integrity ---

# Confirms a scan's exit status - already captured by the caller into $2 -
# is a "clean" outcome for the KIND of producer that generated it, per the
# scanner-specific mode named by $4 (round-8 review finding: status
# acceptance must be scanner-specific, not a single blanket rule):
#   grep   - 0 (something matched) or 1 (grep's own "nothing matched"
#            convention - a legitimate empty result, not a tool failure).
#   strict - 0 only. find, sed, and awk have no "nothing matched"
#            convention of their own: find enumerates (an empty match set
#            is still exit 0), and sed/awk exit 0 whether or not a pattern
#            matched or a line was printed. Any non-zero status from one of
#            these producers - unlike grep's status 1 - is the producing
#            tool itself failing (a bad flag, an unreadable path, an
#            interpreter error), never a legitimate empty result. Accepting
#            status 1 from a strict producer would fail open into an empty
#            result set and a silent PASS exactly when the producer itself
#            is broken.
# An unrecognized $4 is itself treated as a scanner-integrity failure
# rather than silently accepted as a passthrough. The diagnostic keeps the
# fixed substring "internal scanner error (validator integrity)" contiguous
# (the per-call $3 description is appended after it, not spliced into the
# middle) so it stays a single stable, greppable marker regardless of which
# scan tripped it. $1 is the file or corpus root the scan covered, folded
# into the violation's path prefix. Returns success when the caller should
# go on to consume the scan's captured output, and failure when the caller
# must skip it - the violation has already been recorded.
check_scan_status() {
  local subject="$1" status="$2" desc="$3" mode="$4"
  local max_ok_status
  case "$mode" in
  grep) max_ok_status=1 ;;
  strict) max_ok_status=0 ;;
  *)
    fail "$subject: internal scanner error (validator integrity) while scanning for $desc: unrecognized check_scan_status mode '$mode'"
    return 1
    ;;
  esac
  if [[ "$status" -gt "$max_ok_status" ]]; then
    fail "$subject: internal scanner error (validator integrity) while scanning for $desc"
    return 1
  fi
  return 0
}

# Enumerates every corpus Markdown file, NUL-delimited, for one of the four
# content-scan checks below (fence grammar, links, forbidden tokens,
# renderer metadata). `-type f` excludes symlinks - already rejected
# outright by check_inventory below - so a symlinked extra corpus entry is
# never followed and read as if it were a real corpus file. Captures the
# match list into the global array MD_FILES and validates find's own exit
# status via check_scan_status in "strict" mode - find has no grep-style
# "nothing matched" convention of its own (an empty match set is still exit
# 0), so any non-zero status here is find itself failing, not a legitimate
# empty corpus; that failure must not fail open into a silent "found
# nothing, so nothing to check" PASS. $1 is a short description of the
# calling check, folded into the violation message on failure. MD_FILES is
# left empty on failure, so a caller that loops over it unconditionally
# naturally performs zero further scanning.
find_corpus_markdown_files() {
  local desc="$1"
  local tmp status
  tmp="$(mktemp "${TMPDIR:-/tmp}/vertique-verify-find.XXXXXX")"
  find "$CORPUS_ROOT" -name '*.md' -type f -print0 >"$tmp"
  status=$?
  MD_FILES=()
  if check_scan_status "$CORPUS_ROOT" "$status" "$desc" strict; then
    while IFS= read -r -d '' f; do
      MD_FILES+=("$f")
    done <"$tmp"
  fi
  rm -f "$tmp"
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

# Confirms the corpus contains exactly the frozen sixteen-file inventory:
# missing files and unexpected extra files are both violations. Also
# confirms the corpus contains no symlinks at all: a symlinked corpus entry
# would otherwise pass this same-name inventory check trivially while its
# target - possibly outside the corpus, or even outside the repository - is
# what the content scans below actually read (see the `-type f` on their
# `find` calls), an inventory bypass a real file never has (inventory
# contract).
check_inventory() {
  local -a expected=(README.md navigation.yml verify.sh)
  local page
  for page in "${PAGE_ORDER[@]}"; do
    expected+=("content/$page")
  done

  # Built with line-safe / NUL-safe read loops rather than unquoted `$(...)`
  # array assignment, so a corpus file whose name contains a space stays one
  # array element instead of being word-split into two (inventory contract).
  #
  # Neither `find` below is wrapped with check_scan_status, unlike the scan
  # drivers hardened elsewhere in this script: both enumerate the same
  # CORPUS_ROOT, so a `find` failure broken enough to affect one (e.g. an
  # unreadable directory) affects the other identically. A failed `actual`
  # enumeration already fails closed on its own - it comes back empty, so
  # every expected file is reported missing below - and that same guaranteed
  # non-zero FAILURES count is what makes a simultaneous, silent `symlinks`
  # enumeration failure harmless too: the overall run cannot come back PASS
  # regardless of whether the symlink check itself finds anything.
  local -a expected_sorted=() actual=() symlinks=()
  local f
  while IFS= read -r f; do
    expected_sorted+=("$f")
  done < <(printf '%s\n' "${expected[@]}" | sort)

  while IFS= read -r -d '' f; do
    actual+=("${f#./}")
  done < <(cd "$CORPUS_ROOT" && find . -type f -print0 | sort -z)

  while IFS= read -r -d '' f; do
    symlinks+=("${f#./}")
  done < <(cd "$CORPUS_ROOT" && find . -type l -print0 | sort -z)

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
  # Guarded on a non-empty check first: under bash 3.2's `set -u`,
  # `"${symlinks[@]}"` on a genuinely empty (but declared) array is treated
  # as an unbound-variable reference rather than an empty expansion, and a
  # clean corpus - the overwhelmingly common case - legitimately has zero
  # symlinks (see the same guard on `find_corpus_markdown_files`'s callers
  # below, needed for the identical reason).
  if [[ "${#symlinks[@]}" -gt 0 ]]; then
    for f in "${symlinks[@]}"; do
      fail "$CORPUS_ROOT/$f: symlinks are not part of the frozen corpus inventory (inventory contract)"
    done
  fi
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

# Trims ASCII leading and trailing whitespace from a frontmatter scalar's
# raw captured text. Applied before every other frontmatter scalar rule
# (quote-stripping, the unterminated-quote check, the empty/null check) so
# a stray trailing space never masks an otherwise-invalid value — for
# example `title: "" ` and `description: NuLl ` must both still fail
# (frontmatter contract). Uses only parameter expansion (no external
# process, no negative substring length) so this runs unmodified under
# bash 3.2 (macOS /bin/bash).
trim_frontmatter_value() {
  local v="$1"
  v="${v#"${v%%[![:space:]]*}"}"
  v="${v%"${v##*[![:space:]]}"}"
  printf '%s' "$v"
}

# Validates a single frontmatter scalar's already-captured raw text against
# the corpus's restricted quoting grammar in one pass, reporting the
# outcome via three globals (bash 3.2 has no local nameref/associative-array
# return):
#   FRONTMATTER_SCALAR_OK    - true or false
#   FRONTMATTER_SCALAR_DIAG  - violation diagnostic when not OK
#   FRONTMATTER_SCALAR_VALUE - the fully-unwrapped value when OK, for the
#                              caller's empty/null check
#
# Surrounding whitespace is trimmed first (see trim_frontmatter_value). A
# value whose first character is a quote (`"` or `'`) is a quoted value: an
# opening quote with no matching closing quote is rejected outright as
# unterminated rather than accepted as a non-empty value; anything but
# whitespace after the closing quote is a disallowed trailing comment; and
# — this restricted grammar has no escape-sequence support and allows only
# opposite-quote nesting — a double-quoted value must not itself contain
# another `"` character but may freely contain `'`, and a single-quoted
# value must not itself contain another `'` character but may freely
# contain `"`; a value needing both quote characters is not representable.
# A quoted value may also contain a literal `#` (the closing quote already
# removes any comment ambiguity). A value that does not start with a quote
# is bare: it must not contain a literal `#` (quote the value if it needs
# one) or a `"` character. A bare value may contain a `'` (apostrophe)
# freely anywhere EXCEPT as its very first character — this corpus's real
# prose routinely uses English contractions/possessives (e.g.
# "application's"), and a non-leading apostrophe is never ambiguous with
# this grammar's quoting; a leading `'` is reserved quoting syntax (it
# always opens a single-quoted value), so a bare value that must start with
# a literal apostrophe has to be wrapped in double quotes instead (e.g.
# `"'90s migration"`).
validate_frontmatter_scalar() {
  local raw="$1"
  local v quote rest before trailing
  v="$(trim_frontmatter_value "$raw")"

  quote="${v:0:1}"
  if [[ -n "$v" && ("$quote" == '"' || "$quote" == "'") ]]; then
    rest="${v:1}"
    before="${rest%%"$quote"*}"
    if [[ "$before" == "$rest" ]]; then
      FRONTMATTER_SCALAR_OK=false
      FRONTMATTER_SCALAR_DIAG="frontmatter value has an unterminated quoted value, with no matching closing quote (frontmatter contract)"
      return
    fi
    trailing="${rest:$((${#before} + 1))}"
    if [[ -n "${trailing//[[:space:]]/}" ]]; then
      FRONTMATTER_SCALAR_OK=false
      FRONTMATTER_SCALAR_DIAG="frontmatter value must not carry a trailing comment (frontmatter contract)"
      return
    fi
    FRONTMATTER_SCALAR_OK=true
    FRONTMATTER_SCALAR_VALUE="$before"
    return
  fi

  if [[ "$v" == *'#'* ]]; then
    FRONTMATTER_SCALAR_OK=false
    FRONTMATTER_SCALAR_DIAG="frontmatter value must not carry a trailing comment (frontmatter contract); quote the value if it needs a literal '#'"
    return
  fi

  if [[ "$v" == *'"'* ]]; then
    FRONTMATTER_SCALAR_OK=false
    FRONTMATTER_SCALAR_DIAG="quoted frontmatter values must not contain embedded quote characters (corpus grammar)"
    return
  fi

  FRONTMATTER_SCALAR_OK=true
  FRONTMATTER_SCALAR_VALUE="$v"
}

# Reports (via exit status) whether an already-validated, fully-unwrapped
# frontmatter scalar value is empty, or is the literal `null`
# (case-insensitive) — either form makes the value unusable as a
# `title`/`description` (frontmatter contract).
frontmatter_value_is_empty_or_null() {
  local v="$1" lowered
  if [[ -z "$v" ]]; then
    return 0
  fi
  lowered="$(printf '%s' "$v" | tr '[:upper:]' '[:lower:]')"
  [[ "$lowered" == "null" ]]
}

# Parses and validates the frontmatter block of a single content page. Each
# of the three sed/awk extractions below (opening delimiter, closing
# delimiter, body lines) is captured into a variable first and its exit
# status checked via check_scan_status in "strict" mode, rather than
# trusted implicitly - sed and awk have no grep-style "nothing matched"
# convention of their own (both exit 0 whether or not a line matched or was
# printed), so any non-zero status from one of them is the tool itself
# failing, never a legitimate empty result; the same validator-integrity
# treatment applied to the other scan drivers in this script.
check_frontmatter_of_file() {
  local f="$1"
  local first_line close_line status
  first_line=$(sed -n '1p' "$f")
  status=$?
  check_scan_status "$f" "$status" "the frontmatter opening delimiter" strict || return
  first_line="${first_line%$'\r'}"
  if [[ "$first_line" != "---" ]]; then
    fail "$f: must open with a YAML frontmatter block delimited by '---' (frontmatter contract)"
    return
  fi

  close_line=$(awk '{ sub(/\r$/, "") } NR>1 && $0=="---"{print NR; exit}' "$f")
  status=$?
  check_scan_status "$f" "$status" "the frontmatter closing delimiter" strict || return
  if [[ -z "$close_line" ]]; then
    fail "$f: frontmatter block is not closed with a second '---' line (frontmatter contract)"
    return
  fi

  local -a keys=()
  local title_count=0 desc_count=0
  local title_empty=false desc_empty=false
  local line key value body

  body=$(sed -n "2,$((close_line - 1))p" "$f")
  status=$?
  check_scan_status "$f" "$status" "the frontmatter body" strict || return

  if [[ -n "$body" ]]; then
    while IFS= read -r line; do
      line="${line%$'\r'}"
      [[ -z "${line//[[:space:]]/}" ]] && continue
      if [[ "$line" =~ ^([A-Za-z0-9_-]+):[[:space:]]*(.*)$ ]]; then
        key="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        keys+=("$key")

        # Count the key regardless of scalar validity, so an invalid value
        # reports exactly one violation (its own) rather than also tripping
        # the "found 0" key-count check below.
        case "$key" in
        title) title_count=$((title_count + 1)) ;;
        description) desc_count=$((desc_count + 1)) ;;
        esac

        validate_frontmatter_scalar "$value"
        if ! $FRONTMATTER_SCALAR_OK; then
          fail "$f: $FRONTMATTER_SCALAR_DIAG: '$line'"
          continue
        fi

        case "$key" in
        title)
          frontmatter_value_is_empty_or_null "$FRONTMATTER_SCALAR_VALUE" && title_empty=true
          ;;
        description)
          frontmatter_value_is_empty_or_null "$FRONTMATTER_SCALAR_VALUE" && desc_empty=true
          ;;
        esac
      else
        fail "$f: unparseable frontmatter line (frontmatter contract): '$line'"
      fi
    done <<<"$body"
  fi

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

# --- (g) Fence grammar and stripping (shared state machine) ---

# A single fence state machine, shared by grammar checking and both
# stripped views below (strip_all_fences, strip_xml_fences), so a grammar
# decision and a stripping decision can never disagree — the round-3 review
# finding this replaces was exactly that gap: the old grammar check and the
# old stripping awk scripts encoded slightly different assumptions about
# what counts as a fence. Applied line by line, outside a fence:
#   - a line matching exactly three backticks at column zero, optionally
#     followed by an info string that itself contains no backtick
#     (` ``` ` or ` ```lang `), opens a fence (valid).
#   - a fence-like line that is NOT a valid opening — four or more
#     backticks at column zero, any '~~~' fence, a backtick fence whose info
#     string itself contains a backtick (e.g. ` ```foo`bar `), or a
#     backtick/tilde fence indented by any leading whitespace or hidden
#     behind one or more container prefixes (a blockquote `>`, a list
#     marker `-`/`*`/`+`, or a dot- or paren-delimited ordered-list marker
#     such as `1.` or `1)`, repeated or nested in any combination, e.g.
#     `- ``` ` or `> > ``` `) — is a grammar violation; the line is treated
#     as ordinary prose and the state stays outside.
#   - every other line is ordinary prose.
# Inside a fence:
#   - a bare ``` (exactly three backticks, nothing else, column zero)
#     closes the fence (valid).
#   - a line starting with three backticks plus trailing text (would look
#     like a nested opening) is a grammar violation — this restricted
#     grammar has no nested fences; the state stays inside.
#   - every other line (including indented backticks/tildes, or a ``` with
#     leading spaces) is content: never flagged by the grammar scan, never
#     toggles state.
# An unclosed fence at end of file is a grammar violation.
#
# `mode` selects the output:
#   grammar    - one "LINENO<TAB>message" per violation, nothing else
#   strip_all  - every fence marker line and fence-interior line (content)
#                is blanked; everything else is printed unchanged, so line
#                numbers stay aligned with the original file
#   strip_xml  - as strip_all, but the interior content of a non-```xml```
#                fence is left in place; only a ```xml``` fence's interior
#                (and every fence's marker lines) are blanked
fence_state_machine() {
  local f="$1" mode="$2"
  awk -v mode="$mode" '
    function is_open(l) { return (l ~ /^```[^`]*$/) }
    function is_close(l) { return (l == "```") }
    function is_nested(l) { return (l ~ /^```.+$/) }
    function is_bad_outside(l) {
      if (l ~ /^```/) return 1
      if (l ~ /^~~~/) return 1
      if (l ~ /^[[:space:]]+(```|~~~)/) return 1
      if (l ~ /^([[:space:]]*(>[[:space:]]*|[-*+][[:space:]]+|[0-9]+[.)][[:space:]]+))+(```|~~~)/) return 1
      return 0
    }
    BEGIN { in_fence = 0; in_xml = 0 }
    {
      line = $0
      sub(/\r$/, "", line)

      if (!in_fence) {
        if (is_open(line)) {
          in_fence = 1
          info = line
          sub(/^```/, "", info)
          gsub(/^[ \t]+|[ \t]+$/, "", info)
          in_xml = (tolower(info) == "xml")
          if (mode == "strip_all" || mode == "strip_xml") print ""
          next
        }
        if (is_bad_outside(line)) {
          if (mode == "grammar") {
            print NR "\tcode fences must open with exactly three backticks at column zero, with no leading whitespace and no list/blockquote prefix (corpus grammar)"
          } else {
            print $0
          }
          next
        }
        if (mode != "grammar") print $0
        next
      }

      if (is_close(line)) {
        in_fence = 0
        if (mode == "strip_all" || mode == "strip_xml") print ""
        in_xml = 0
        next
      }
      if (is_nested(line) && mode == "grammar") {
        print NR "\tfence-like content inside an open fence is not representable in the restricted grammar (corpus grammar)"
        next
      }
      if (mode == "strip_all") { print ""; next }
      if (mode == "strip_xml") { print (in_xml ? "" : $0); next }
      next
    }
    END {
      if (in_fence && mode == "grammar") {
        print NR "\tcode fence is not closed before end of file (corpus grammar)"
      }
    }
  ' "$f"
}

# Confirms every code fence in the corpus is representable in the
# restricted fence grammar (see fence_state_machine above).
check_fence_grammar() {
  local f
  find_corpus_markdown_files "corpus Markdown files (fence grammar)"
  # Guarded on a non-empty check: under bash 3.2's `set -u`, expanding
  # "${MD_FILES[@]}" when the array is genuinely empty (e.g. after a `find`
  # failure already reported by find_corpus_markdown_files above) is an
  # unbound-variable error, not a zero-iteration loop.
  if [[ "${#MD_FILES[@]}" -gt 0 ]]; then
    for f in "${MD_FILES[@]}"; do
      check_fence_grammar_of_file "$f"
    done
  fi
}

# Scans a single file for fence-grammar violations, via the shared state
# machine, and reports each one. The state machine's own awk invocation is
# captured and status-checked via check_scan_status in "strict" mode before
# its output is trusted (awk has no grep-style "nothing matched"
# convention - it exits 0 whether or not a violation was printed - so any
# non-zero status is awk itself failing, not a clean file), rather than
# consumed straight off a process substitution whose exit status would
# otherwise go unexamined.
check_fence_grammar_of_file() {
  local f="$1"
  local hit line_no msg output status

  output="$(fence_state_machine "$f" grammar)"
  status=$?
  check_scan_status "$f" "$status" "fence grammar" strict || return
  [[ -z "$output" ]] && return

  while IFS= read -r hit; do
    line_no="${hit%%$'\t'*}"
    msg="${hit#*$'\t'}"
    fail "$f:$line_no: $msg"
  done <<<"$output"
}

# --- Fence handling ---

# Strips the content of every fenced code block (any info-string, or none) —
# and its fence marker lines — from a file, replacing them with blank lines
# so downstream line numbers stay aligned with the original file. Used to
# keep prose-only rules (renderer-neutrality, reference-style links) out of
# legitimate code samples such as Java generics or JSON braces. Derived from
# fence_state_machine above, so this can never disagree with what
# check_fence_grammar accepts as a real fence.
strip_all_fences() {
  fence_state_machine "$1" strip_all
}

# --- Producer+grep two-stage scan helper (validator integrity) ---

# Runs a fence-stripped view of file $1 through a grep filter, checking each
# stage's own exit status independently rather than as a single "producer |
# grep" pipeline status - the round-8 review finding this helper fixes: even
# with `pipefail` enabled, a pipeline's captured status reflects only the
# rightmost non-zero exit code, so a broken producer (status 2) feeding a
# grep that finds nothing (status 1) yields a pipeline status of 1 -
# indistinguishable from a clean "nothing matched" grep run, and the
# producer's own failure silently slips past a grep-mode check_scan_status
# call. This helper instead captures the producer's output into a temp file
# first, validates ITS status in check_scan_status's "strict" mode (fences
# are stripped by fence_state_machine, an awk producer with no grep-style
# "nothing matched" convention of its own), and only then greps that temp
# file, validating grep's own status independently in "grep" mode.
#
# $2 selects the fence-stripped view: "all" for strip_all_fences (blanks
# every fence's content), "xml" for strip_xml_fences (blanks only ```xml```
# fence content). $3 is the stripping-stage description folded into a
# scanner-failure diagnostic; $4 is the grep-stage description. Every
# remaining argument ($5+) is passed verbatim to grep as its flags and
# pattern.
#
# Reports the outcome via two globals (bash 3.2 has no local nameref):
#   STRIPPED_GREP_OK     - true when both stages cleared check_scan_status
#                           (an empty grep result is still OK; only a
#                           scanner failure at either stage is not - that
#                           failure has already been recorded via fail())
#   STRIPPED_GREP_OUTPUT - grep's own captured output when OK; empty
#                           otherwise, so a caller that reads it
#                           unconditionally on a failed scan just sees no
#                           matches rather than stale content from a
#                           previous call
stripped_grep_scan() {
  local f="$1" strip_kind="$2" strip_desc="$3" grep_desc="$4"
  shift 4
  local tmp status
  tmp="$(mktemp "${TMPDIR:-/tmp}/vertique-verify-stripped.XXXXXX")"

  if [[ "$strip_kind" == "xml" ]]; then
    strip_xml_fences "$f" >"$tmp"
  else
    strip_all_fences "$f" >"$tmp"
  fi
  status=$?

  STRIPPED_GREP_OK=false
  STRIPPED_GREP_OUTPUT=""
  if check_scan_status "$f" "$status" "$strip_desc" strict; then
    STRIPPED_GREP_OUTPUT="$(grep "$@" "$tmp")"
    status=$?
    if check_scan_status "$f" "$status" "$grep_desc" grep; then
      STRIPPED_GREP_OK=true
    else
      STRIPPED_GREP_OUTPUT=""
    fi
  fi

  rm -f "$tmp"
}

# --- (d) Links ---

# Confirms every relative Markdown link target across all corpus Markdown
# files resolves to an existing path from the linking file's own directory.
check_links() {
  local f
  find_corpus_markdown_files "corpus Markdown files (links)"
  # See the identical guard in check_fence_grammar above for why this is
  # needed (bash 3.2's `set -u` + an empty array expansion).
  if [[ "${#MD_FILES[@]}" -gt 0 ]]; then
    for f in "${MD_FILES[@]}"; do
      check_links_of_file "$f"
    done
  fi
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
# start at column zero (an indented one, or one hidden behind one or more
# container prefixes — a blockquote `>`, a list marker `-`/`*`/`+`, or a
# dot- or paren-delimited ordered-list marker such as `1.` or `1)` —
# repeated or nested in any combination, such as `> [label]: target`,
# `- [label]: target`, `1) [label]: target`, or `> > [label]: target`, is
# rejected as a corpus-grammar violation rather than silently skipped or
# silently passed through unresolved). A bare (non-angle-bracket) inline
# destination containing a literal '(' is rejected as a corpus-grammar
# violation rather than silently truncated at the first ')' — such a target
# must use the angle-bracket destination form instead. Each of the three
# fence-stripped-then-grepped extractor scans below runs through
# stripped_grep_scan, which checks the stripping stage (an awk producer) and
# the grep stage independently in check_scan_status's "strict" and "grep"
# modes respectively, rather than trusting a single "producer | grep"
# pipeline status that `pipefail` alone cannot make scanner-specific (see
# stripped_grep_scan's own comment for why).
check_links_of_file() {
  local f="$1"
  local dir raw target line hit output
  dir=$(dirname "$f")

  stripped_grep_scan "$f" all "fence-stripped view (inline link destinations)" \
    "inline link destinations" -oE '\]\(<[^>]*>|\]\([^)[:space:]]+'
  if $STRIPPED_GREP_OK; then
    output="$STRIPPED_GREP_OUTPUT"
    if [[ -n "$output" ]]; then
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
      done <<<"$output"
    fi
  fi

  stripped_grep_scan "$f" all "fence-stripped view (indented reference-style link definitions)" \
    "indented reference-style link definitions" \
    -noE '^[[:space:]]+\[[^]]+\]:|^([[:space:]]*(>[[:space:]]*|[-*+][[:space:]]+|[0-9]+[.)][[:space:]]+))+\[[^]]+\]:'
  if $STRIPPED_GREP_OK; then
    output="$STRIPPED_GREP_OUTPUT"
    if [[ -n "$output" ]]; then
      while IFS= read -r hit; do
        fail "$f:${hit%%:*}: reference-style link definitions must start at column zero, not be indented (corpus grammar)"
      done <<<"$output"
    fi
  fi

  stripped_grep_scan "$f" all "fence-stripped view (reference-style link definitions)" \
    "reference-style link definitions" \
    -oE '^\[[^]]+\]:[[:space:]]*<[^>]*>|^\[[^]]+\]:[[:space:]]*[^[:space:]]+'
  if $STRIPPED_GREP_OK; then
    output="$STRIPPED_GREP_OUTPUT"
    if [[ -n "$output" ]]; then
      while IFS= read -r line; do
        [[ "$line" =~ ^\[[^]]+\]:[[:space:]]*(.+)$ ]] || continue
        target="${BASH_REMATCH[1]}"
        if [[ "$target" == '<'*'>' ]]; then
          target="${target#<}"
          target="${target%>}"
        fi
        check_link_target "$f" "$dir" "$target"
      done <<<"$output"
    fi
  fi
}

# --- (e) Forbidden tokens ---

# Confirms no forbidden token (TODO, TBD, or their plurals, or latest;
# matched case-insensitively) or angle-bracket placeholder outside a ```xml
# fence appears in corpus Markdown files.
check_forbidden_tokens() {
  local f
  find_corpus_markdown_files "corpus Markdown files (forbidden tokens)"
  # See the identical guard in check_fence_grammar above for why this is
  # needed (bash 3.2's `set -u` + an empty array expansion).
  if [[ "${#MD_FILES[@]}" -gt 0 ]]; then
    for f in "${MD_FILES[@]}"; do
      check_forbidden_tokens_of_file "$f"
    done
  fi
}

# Strips the content of ```xml fenced code blocks (and every fence's marker
# lines) from a file, replacing them with blank lines so downstream line
# numbers stay aligned with the original file. Derived from
# fence_state_machine above, so this can never disagree with what
# check_fence_grammar accepts as a real fence.
strip_xml_fences() {
  fence_state_machine "$1" strip_xml
}

# Scans a single file for forbidden tokens and reports each match. The
# first scan is a plain grep over the whole file (no producer stage), so
# its own exit status is checked via check_scan_status in "grep" mode
# directly. The second scan runs a fence-stripped view through grep, via
# stripped_grep_scan, which checks the stripping stage and the grep stage
# independently in "strict" and "grep" mode respectively.
check_forbidden_tokens_of_file() {
  local f="$1"
  local hit line_no token output status

  # TODO/TBD (singular or plural) and latest are forbidden everywhere,
  # including inside xml fences, case-insensitively (e.g. Latest, TODOs).
  output="$(grep -inoE '\b(TODO|TBD)S?\b|\blatest\b' "$f")"
  status=$?
  if check_scan_status "$f" "$status" "forbidden tokens" grep; then
    if [[ -n "$output" ]]; then
      while IFS= read -r hit; do
        line_no="${hit%%:*}"
        token="${hit#*:}"
        fail "$f:$line_no: forbidden token '$token' (placeholder contract)"
      done <<<"$output"
    fi
  fi

  # Angle-bracket pseudo-value placeholders are forbidden outside xml fences:
  # lowercase-hyphenated (<placeholder-name>), ALL-CAPS (<VERSION>), and
  # lowercase-start camelCase (<vertiqueVersion>). Chosen so none match a Java
  # generic such as <String>, <T>, or <Response<String>>.
  stripped_grep_scan "$f" xml "fence-stripped view (angle-bracket placeholders)" \
    "angle-bracket placeholders" \
    -noE '<[a-z][a-z0-9-]*>|<[A-Z][A-Z0-9_]{2,}>|<[a-z][a-z0-9]*[A-Z][A-Za-z0-9]*>'
  if $STRIPPED_GREP_OK; then
    output="$STRIPPED_GREP_OUTPUT"
    if [[ -n "$output" ]]; then
      while IFS= read -r hit; do
        line_no="${hit%%:*}"
        token="${hit#*:}"
        fail "$f:$line_no: forbidden angle-bracket placeholder '$token' outside an xml fence (placeholder contract)"
      done <<<"$output"
    fi
  fi
}

# --- (f) Renderer/site metadata ---

# Confirms no Astro/Starlight/MDX import, ':::' directive, or JSX component
# tag appears in any corpus Markdown file.
check_renderer_metadata() {
  local f
  find_corpus_markdown_files "corpus Markdown files (renderer metadata)"
  # See the identical guard in check_fence_grammar above for why this is
  # needed (bash 3.2's `set -u` + an empty array expansion).
  if [[ "${#MD_FILES[@]}" -gt 0 ]]; then
    for f in "${MD_FILES[@]}"; do
      check_renderer_metadata_of_file "$f"
    done
  fi
}

# Scans a single file for renderer-specific syntax and reports each match.
# The import-line and JSX-tag scans are prose-only rules, so they run
# against a fence-stripped view of the file, via stripped_grep_scan (which
# checks the stripping stage and the grep stage independently in "strict"
# and "grep" mode respectively): a legitimate ```java fence containing
# `import dev.vertique...;` or a `Optional<Response<String>>` generic must
# never trip these two rules. The ':::' directive has no legitimate
# code-fence use, so it is still scanned everywhere via a plain grep (no
# producer stage), whose own exit status is checked via check_scan_status
# in "grep" mode directly.
check_renderer_metadata_of_file() {
  local f="$1"
  local hit line_no output status

  stripped_grep_scan "$f" all "fence-stripped view (renderer import syntax)" \
    "renderer import syntax" -noE '^[[:space:]]*import[[:space:]]'
  if $STRIPPED_GREP_OK; then
    output="$STRIPPED_GREP_OUTPUT"
    if [[ -n "$output" ]]; then
      while IFS= read -r hit; do
        line_no="${hit%%:*}"
        fail "$f:$line_no: renderer import syntax is forbidden in corpus Markdown (renderer-neutrality contract)"
      done <<<"$output"
    fi
  fi

  output="$(grep -noF ':::' "$f")"
  status=$?
  if check_scan_status "$f" "$status" "':::' directive syntax" grep; then
    if [[ -n "$output" ]]; then
      while IFS= read -r hit; do
        line_no="${hit%%:*}"
        fail "$f:$line_no: ':::' directive syntax is forbidden in corpus Markdown (renderer-neutrality contract)"
      done <<<"$output"
    fi
  fi

  stripped_grep_scan "$f" all "fence-stripped view (JSX component tag syntax)" \
    "JSX component tag syntax" -noE '<[A-Z][A-Za-z0-9]*[[:space:]/>]'
  if $STRIPPED_GREP_OK; then
    output="$STRIPPED_GREP_OUTPUT"
    if [[ -n "$output" ]]; then
      while IFS= read -r hit; do
        line_no="${hit%%:*}"
        fail "$f:$line_no: JSX component tag syntax is forbidden in corpus Markdown (renderer-neutrality contract)"
      done <<<"$output"
    fi
  fi
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
