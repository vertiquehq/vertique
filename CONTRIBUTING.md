<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Contributing to Vertique

Thank you for contributing to Vertique.

## Local verification

Vertique requires Java 21. Before submitting a change, run:

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
