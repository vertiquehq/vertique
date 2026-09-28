<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Contributing to Vertique

Thank you for contributing to Vertique.

## Local verification

The full build and its integration tests need:

- a JDK at `pom.xml`'s `<java.version>` (Java 21) or later;
- Go at the version the MCP Go interop fixture's `go.mod` requires (1.25);
- Node.js 22 or later with npm, for the MCP TypeScript interop and conformance tests;
- a running Docker-compatible engine, for the Testcontainers-based integration tests.

The MCP interop tests download their pinned Go modules and npm packages, so the
full build also needs network access. Check the tools with:

```bash
scripts/doctor.sh
```

It inspects the effective tools rather than any installer or version manager, and
also catches a `JAVA_HOME` that a Java version manager points at a missing JDK,
which otherwise fails only the nested Maven builds of the integration tests.

Before submitting a change, run:

```bash
./mvnw -ntp clean verify
./mvnw -ntp spotless:check
```

Use `./mvnw -ntp spotless:apply` to apply the project formatter.

## Change scope

- Keep module boundaries intact and dependencies explicit.
- Include tests for behavior changes and defect fixes.
- Update the owning module's `src/main/resources/META-INF/vertique/module.md` when
  public behavior, configuration, wiring, or constraints change.
- Keep unrelated refactoring out of the same change.

## Commits and pull requests

Use Conventional Commits:

```text
<type>(<scope>): <description>
```

Supported types are `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `chore`,
`style`, `perf`, `ci`, and `revert`.

Open changes through a pull request. Maintainers may request focused tests,
documentation updates, or a clean full build before merging.

CI skips the full build only for pull requests whose every changed path is
non-executable prose (`CONTRIBUTING.md`, `SECURITY.md`, `LICENSES/`,
`NOTICE`). Everything else — including `README.md`, `docs/`, and packaged
`module.md` resources, which integration tests assert on — runs the full
build and test suite.

## Releases

Tags in this repository do not publish artifacts. Release orchestration and
publication credentials are maintainer-controlled outside this repository.
