<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Vertique Codegen All

> **Status:** Beta
> **Artifact:** `vertique-codegen-all`

## Overview

`vertique-codegen-all` is the dependency-only facade for every supported Vertique annotation
processor. Put this single artifact on Maven's annotation-processor path to make all Vertique
processors available without listing each processor leaf separately.

The facade contains no Java source, annotation processor, or service registration of its own. It
is not shaded and does not copy or merge service descriptors. Maven resolves the processor leaves
transitively, and each leaf contributes its own
`META-INF/services/javax.annotation.processing.Processor` registration.

## Processor Leaves

The facade declares these compile dependencies in deterministic order:

1. `vertique-codegen-application`
2. `vertique-codegen-dagger`
3. `vertique-codegen-jaxrs`
4. `vertique-codegen-rest-client`
5. `vertique-codegen-services`
6. `vertique-codegen-kafka`
7. `vertique-codegen-delayed-job`
8. `vertique-codegen-workflow`
9. `vertique-codegen-cron`
10. `vertique-codegen-sanitization`
11. `vertique-codegen-aop`
12. `vertique-codegen-events`
13. `vertique-codegen-cache`

All processors remain non-claiming and ignore compilations that do not use their supported
annotations. The facade adds no runtime Java API, annotations, SPI, or configuration keys.

## Key Classes

None. This artifact intentionally contains no Java classes. Its Maven dependency graph is its
complete behavior.

## Extension Points

Applications inheriting `dev.vertique:vertique-app-parent` receive this facade automatically and
declare only runtime capabilities. Custom-parent applications import `dev.vertique:vertique-bom`
and configure Maven Compiler Plugin with the versionless
`com.google.dagger:dagger-compiler` and `dev.vertique:vertique-codegen-all` paths. Applications
that use Lombok declare Lombok themselves and append its processor path as an explicit opt-in.
The complete recipes and the `maven.compiler.proc=none` escape hatch are documented in
`docs/packaging.md`.

The facade is a closed ledger of Vertique-owned production processors, not a third-party processor
SPI. Additions require updating the ordered dependency list and the facade discovery contract test.

## Dependencies

The thirteen processor leaves above are the facade's complete direct dependency set. Every leaf
excludes transitive `org.projectlombok:lombok`, so resolving the facade cannot activate Lombok's
annotation processor through a processor leaf's runtime dependencies. Applications that use
Lombok declare and append it explicitly.
