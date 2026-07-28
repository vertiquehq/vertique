<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Developer documentation corpus

This directory is the public, renderer-neutral developer documentation corpus for Vertique. It
is a task-oriented journey for application developers building on the framework — not a rendered
website, not a generated API reference, and not a copy of the per-module reference material that
already lives under `docs/` and each artifact's packaged `module.md`. The corpus links to that
reference material; it does not reproduce it.

The corpus is authored in English only for its first version. No translation keys, alternate
language files, locale directories, or locale selectors are added here — that is a rejected
roadmap item, not a deferral.

Rendering, search, and site navigation are out of scope for this corpus. A future initiative
adapts this same content into a rendered site; until then, the corpus is plain Markdown plus two
small contract files (`navigation.yml` and `verify.sh`) that keep it renderer-neutral and
internally consistent.

## Page inventory and navigation policy

The corpus contains exactly these files:

```text
developer-docs/README.md
developer-docs/navigation.yml
developer-docs/verify.sh
developer-docs/content/index.md
developer-docs/content/quickstart.md
developer-docs/content/application-model.md
developer-docs/content/configuration.md
developer-docs/content/rest-apis.md
developer-docs/content/rest-jaxrs-compatibility.md
developer-docs/content/services.md
developer-docs/content/persistence.md
developer-docs/content/workflows.md
developer-docs/content/jobs.md
developer-docs/content/inbox-outbox.md
developer-docs/content/security.md
developer-docs/content/testing.md
developer-docs/content/deployment.md
developer-docs/content/artifacts.md
```

No file may be added to or removed from this list without updating both `navigation.yml` and the
validator's frozen inventory in `verify.sh` — the two must always agree.

`navigation.yml` declares the reading order as a single `pages:` list, naming each page's
filename exactly once, in the order above. It carries nothing else: no site routes, no renderer
component references, no locale prefixes, no version aliases, and no redirects. Any renderer that
adopts this corpus is expected to read `navigation.yml` to build its own table of contents rather
than the corpus defining one for it.

## Frontmatter contract

Every page under `content/` opens with a minimal YAML frontmatter block:

```yaml
---
title: Example page title
description: One sentence describing the page's outcome.
---
```

The block contains exactly one `title` key and exactly one `description` key, both non-empty, and
no other keys. In particular, it never carries a site route, a rendered layout name, a locale, a
version alias, a redirect, or a sidebar-grouping key — those all belong to a renderer's own
configuration, not to this corpus.

## Writing rules and snippet policy

Every page in the corpus follows the same authoring rules:

- Address application developers directly and lead with the outcome the reader gets, not with
  framework internals.
- State which responsibilities belong to the application developer separately from what the
  framework or its generated code wires up automatically.
- Link to the canonical module reference for full API, SPI, and configuration inventories rather
  than reproducing them on the page.
- Give every command its working directory and the observable result a reader should see, so a
  reader can tell whether a step succeeded.
- Reconcile every Java or JSON snippet against a named generated project or a public example under
  `examples/` — a snippet that does not trace to real, running code does not belong in the corpus.
- Use the `VERTIQUE_VERSION` environment variable in `bash` examples and `${vertique.version}` in
  Maven `xml` examples for the framework's version, and only in the way the backing product
  requirements define. A generic, unresolved stand-in value is never acceptable in either kind of
  example — every value shown must be a concrete one that was actually exercised.
- Never mention a private repository path, an internal architecture-decision record or product
  specification, a private artifact coordinate, or proprietary/enterprise implementation detail.
- Keep a bare, single-type-parameter Java generic — a capitalized type name wrapped in angle
  brackets, with no leading lowercase segment — out of unfenced prose entirely, including inside a
  single-backtick inline code span: the renderer-neutrality scan reads that shape as JSX
  component-tag syntax and rejects it. Put the generic inside a fenced code block instead, or
  reword the sentence to describe it without the literal syntax.

The validator (below) enforces the mechanical half of this policy. It also rejects a short list
of reserved English words that signal an unfinished or unpinned draft; their exact spellings live
once in `verify.sh` so this guide is not obligated to repeat them and accidentally trip its own
check. Concretely: no leftover work markers of either the four-letter or three-letter kind, and no
floating "whatever is newest right now" version keyword — every version in every example is a
concrete, reconciled value.

The validator additionally rejects an unresolved angle-bracket placeholder outside a fenced
`xml` code block — a token shaped like an opening angle bracket, a closing angle bracket, and, in
between, either a lowercase hyphenated name, an all-uppercase word of three or more letters, or a
name that starts lowercase and switches to uppercase partway through, standing in for a value the
reader is expected to substitute. None of these three shapes match an ordinary Java generic type
parameter. Real Maven XML markup inside a fenced `xml` block is not affected by this rule, for
example:

```xml
<dependency>
  <groupId>dev.vertique</groupId>
  <artifactId>vertique-starter-rest</artifactId>
</dependency>
```

Outside such a fence, describe a placeholder in words (as this paragraph does) rather than writing
its literal syntax.

### Restricted corpus grammar

The corpus is written in a deliberately restricted subset of Markdown, so the validator's fence
and link extraction never need a real Markdown parser. A few forms that are otherwise valid
Markdown are rejected outright as a grammar violation, with their own diagnostic, rather than
being silently accepted or silently mis-parsed:

- A code fence must open with three backticks at column zero. A fence indented by one to three
  spaces, or a fence that uses tildes instead of backticks, is rejected rather than treated as
  ordinary prose. So is a fence hidden behind one or more container prefixes — a blockquote
  marker, a list marker, or a dot- or paren-delimited ordered-list marker, repeated or nested in
  any combination — rather than treated as ordinary prose.
- A reference-style link definition (`[label]: target`) must likewise start at column zero. An
  indented one is rejected rather than silently skipped, and so is one hidden behind the same
  container-prefix forms described above — a blockquote marker, a list marker, or a dot- or
  paren-delimited ordered-list marker, repeated or nested in any combination — rather than
  silently passed through unresolved.
- A frontmatter `title` or `description` value's surrounding whitespace is ignored before any
  other rule below is applied. A bare (unquoted) value must not contain a literal `#` character —
  quote the value, in either quote style, if it needs one. A value that must start with a literal
  `'` (apostrophe), or that contains a `"` character anywhere, needs the opposite quote style
  instead: wrap a value containing `"` in single quotes, and wrap a value that must start with `'`
  in double quotes — for example, a title starting with an apostrophe is written as a
  double-quoted value with the apostrophe as its first character. Concretely, this restricted
  grammar allows only opposite-quote nesting: a double-quoted value may freely contain `'` but not
  another `"`, and a single-quoted value may freely contain `"` but not another `'`; a value
  needing both quote characters is not representable at all. A quoted value may also contain a
  literal `#`, since the surrounding quotes remove the ambiguity with a trailing comment. No value
  carries a trailing comment after its closing quote, and an opening quote with no matching
  closing quote is rejected outright. A value of `null`, in any case and whether quoted or not, is
  invalid. None of these forms is silently accepted or silently normalized to empty.
- A bare (non-angle-bracket) inline link destination must not contain a literal opening
  parenthesis. Use the CommonMark angle-bracket destination form instead — the same form the
  validator already recognizes in full, including embedded spaces, for a destination that
  contains them.

## Running the validator

`developer-docs/verify.sh` checks the whole contract above: the file inventory, the navigation
list, every page's frontmatter, every relative link — inline or reference-style, including links
that point out of `developer-docs/` into the rest of the repository, such as
`../../docs/modules.md` — the reserved tokens described above, and the absence of any
Astro/Starlight/MDX renderer syntax in a page's prose. It uses only Bash and standard Unix tools,
performs no network access, and reports every violation it finds in one run rather than stopping
at the first.

Run it in either of two modes, both from the repository root:

```bash
# Repository mode: validates this repository's own developer-docs/ directory.
bash developer-docs/verify.sh

# Disposable-corpus mode: validates a standalone copy of the corpus, for
# example one prepared to prove a mutation is correctly rejected.
bash developer-docs/verify.sh /path/to/a/corpus-copy
```

A clean run prints `PASS` and exits with status zero. A failing run prints one `FAIL:` line per
violation, each naming the file (and, where applicable, the line) and the specific contract it
breaks, followed by a summary line reporting how many violations were found, and exits non-zero.

Run the validator before every commit that touches this directory, alongside the project's
regular `./mvnw -ntp spotless:check` and `./mvnw -ntp compile -DskipTests` checks.
