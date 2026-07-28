<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Module Development Doc Template

Use this template for an optional, source-only `DEVELOPMENT.md` at a module root. Create one when a
framework contributor or source agent needs implementation knowledge that would distract an
application developer from using the artifact.

This document is not packaged and is not a second public API reference. Read the module's canonical
`src/main/resources/META-INF/vertique/module.md` first and link to it rather than copying its
examples, configuration tables, or consumer-facing constraints.

---

```markdown
# Developing Module Name

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `src/main/resources/META-INF/vertique/module.md`

One paragraph defining the maintainer problem this document solves and the boundary between public
contract and implementation detail.

---

## Source Map

Name cohesive packages or subsystems and their responsibilities. Do not enumerate every class.

---

## Runtime or Build Flow

Describe the implementation sequence, ownership boundaries, and lifecycle transitions a contributor
must preserve. A small numbered flow or Mermaid diagram is appropriate when order matters.

---

## Load-Bearing Invariants

Document internal constraints that are easy to violate and hard to infer from an isolated class.
State the failure mode and the owning test where practical.

---

## Generated and Hand-Written Boundaries

_(When applicable)_ State which metadata or wiring is generated, which fallback is reflective or
manual, and where parity must be maintained.

---

## Testing

Map behaviors to focused test classes and give the smallest useful Maven command, followed by the
required full-project verification.

---

## Related ADRs

- ADR-NNNN: Title — one-line statement of the decision this module implementation must preserve.
```

## Rules

- `DEVELOPMENT.md` lives at the owning module root and is never copied into
  `src/main/resources`.
- It may name private ADRs textually, but must not link a public packaged document to private
  repository paths.
- Keep it evergreen. History belongs in git and planned behavior belongs in a PRD or issue.
- Do not duplicate the public module reference. Link to it for application-facing API, SPI,
  configuration, examples, and constraints.
- Add it only when the source-maintainer material is substantial enough to justify a separate
  navigation surface.
