<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Module Doc Template

Use this template when creating a new module doc at the owning consumable module's canonical path, `src/main/resources/META-INF/vertique/module.md`. Add the artifact's direct canonical-resource link to `docs/modules.md` when the artifact or path is new. Not all sections are required — omit empty ones (e.g., skip JAX-RS Integration for non-REST modules).

The audience is an application developer or coding agent using the artifact. Keep framework
implementation topology, maintainer-only invariants, source-navigation notes, test maps, and ADR
traceability out of this packaged document. A complex module may keep those details in a
maintainer document held in the private governance repository, outside this repository entirely.

Module docs are **evergreen reference**: they describe the current state of the module. Do not embed change history, "cycle N additions", or roadmap entries inline.

- **History** belongs in git (`git log -- <canonical module.md path>`).
- **Decisions and planned work** stay in the private governance repository. Public module documents describe only shipped behavior.
- **Source-maintainer guidance** lives in the private governance repository when the module needs
  it. It is never kept in this repository, and consumer guidance is not duplicated into it.

---

```markdown
# Module Name

> **Status:** Experimental | Alpha | Beta | Stable
> **Package:** `dev.vertique.{module}`
> **Artifact:** `vertique-{module}`
> **Depends on:** core, ...

One-paragraph overview of what the module does and the problem it solves. Optionally a second short paragraph clarifying what the module is **not**.

---

## When To Use It

When an application should install this module, and which sibling modules pair with it. Keep this scoped to "what does the reader install and why".

---

## Core Concepts

The mental model: the small set of types, abstractions, and runtime behaviors a user has to internalize. Prefer narrative explanation plus minimal code snippets over exhaustive type catalogs — the source is the source of truth for fields and signatures.

---

## Key Classes

Only the few classes a user actually interacts with. Describe purpose, important methods, and a usage example. Do not enumerate every type in the package; the source already does that.

### ClassName

Purpose, important methods, usage example.

---

## Extension Points

How users extend or customize this module (SPIs, multibindings, recorder/contributor interfaces). Each entry: interface signature, registration mechanism, minimal example.

---

## JAX-RS Integration

_(Only for REST-related modules)_ How JAX-RS annotations or types are used.

---

## Module Dagger Bindings

_(Optional)_ What the module's `@Module` class provides and requires that is not already obvious from the extension points.

---

## Dependencies

Which other framework modules this module depends on, and why each dependency exists.

---

## Rules

- **Section order** is fixed: follow the sequence above.
- **Canonical location** is the owning module's `src/main/resources/META-INF/vertique/module.md`; do not create a centralized module-doc copy or wrapper.
- **Packaged links** may use same-document anchors, external URIs, or module-local files inside the owning `META-INF/vertique/` tree. Use textual Maven coordinates for cross-module references, fully qualified names for source references, and backticked repository-relative paths for repository-only material. Never use `../` traversal.
- **Index changes** are limited to artifact inventory or canonical-path changes; ordinary content edits do not change `docs/modules.md`.
- **No `## Version History`**, **no `## Planned Additions`**, and **no `## Related ADRs`** — internal decisions and plans are kept private.
- **No implementation tour** — package inventories, internal collaborators, generated metadata
  mechanics, and test topology belong in the maintainer document. Mention an implementation type only
  when an application must call, implement, configure, or deliberately replace it.
- **Status header** uses blockquote and one of the controlled vocabulary values:
  `Experimental`, `Alpha`, `Beta`, or `Stable`. The value is a compatibility promise, not a
  progress note; the retired `Implemented` value must not be reintroduced.
- **Evergreen tone** — describe the current behavior. Do not write "as of cycle N" or "added in version X". If a fact will rot the moment the next change lands, it does not belong here.
