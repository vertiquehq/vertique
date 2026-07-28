---
title: Artifacts
description: Depend on Vertique through its application parent or BOM, compose capability through starters, generate a project from an archetype, and find every artifact's canonical reference.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Artifacts

This page is where to look up how a Vertique application actually depends on the framework: the
public application parent, the BOM for an application that cannot use that parent, the four
starter coordinates, the three archetype coordinates, and `docs/modules.md`, the canonical index
for discovering every other published artifact. It links to each of those; it does not repeat
their own inventories here.

## The application parent

`dev.vertique:vertique-app-parent` is the public Maven boundary a generated application inherits.
This is the parent block from the REST archetype's own template, quoted verbatim
(`${vertiqueVersion}` is the archetype's own placeholder, substituted at generation time by the
`-DvertiqueVersion` property — `VERTIQUE_VERSION` in [Quickstart](quickstart.md) — so a generated
project's actual `pom.xml` carries the literal version there instead):

```xml
<parent>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-app-parent</artifactId>
    <version>${vertiqueVersion}</version>
    <relativePath/>
</parent>
```

Parenting to it gives an application: the Vertique BOM imported into its own dependency
management, so a `dev.vertique` dependency it declares needs no explicit version — exactly as the
REST archetype's own generated `pom.xml` declares `vertique-starter-rest` and `vertique-launcher`
in [Quickstart](quickstart.md); a Java 21 compiler target; Maven Compiler Plugin already
configured with the Dagger annotation processor plus the complete Vertique processor facade
(`vertique-codegen-all`); and pinned Maven Surefire and Failsafe plugin versions. It does not bind
Failsafe's `integration-test`/`verify` goal executions, and it does not configure
`exec-maven-plugin` or `jib-maven-plugin` at all — each archetype's own generated `pom.xml`
declares all three directly, as [Deployment](deployment.md) shows for the packaging and
local-run commands themselves.

## The BOM, for an application that doesn't use the parent

An application that must keep its own parent imports `vertique-bom` directly instead, following
the "Custom parent: BOM plus processor facade" recipe in
[the packaging guide](../../docs/packaging.md), also summarized in this repository's own
[root README](../../README.md#application-maven-setup). Define a `vertique.version` property in
your own `pom.xml` — matching `VERTIQUE_VERSION` from [Quickstart](quickstart.md), the same
convention [REST APIs](rest-apis.md) uses for its OpenAPI build plugin — and import the BOM with
it:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.vertique</groupId>
            <artifactId>vertique-bom</artifactId>
            <version>${vertique.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Importing the BOM on its own manages dependency versions only; it does not configure the compiler
or the Dagger/Vertique annotation processor path the way `vertique-app-parent` does — see the same
recipe in [the packaging guide](../../docs/packaging.md) for the accompanying Maven Compiler
Plugin configuration a custom-parent application still has to add itself.

## Starters

[Application model](application-model.md#what-starters-own) already covers what each starter
aggregate composes and how starters combine; this page only lists where to find each one. Every
group id is `dev.vertique`.

| Starter | Depend on it when | Reference |
|---|---|---|
| `vertique-starter-core` | your application needs the lifecycle foundation with no HTTP, event-bus, or persistence layer | [module reference](../../vertique-starter/vertique-starter-core/src/main/resources/META-INF/vertique/module.md) |
| `vertique-starter-rest` | your application serves an HTTP API through JAX-RS resources | [module reference](../../vertique-starter/vertique-starter-rest/src/main/resources/META-INF/vertique/module.md) |
| `vertique-starter-services` | your application is a headless event-bus service with no HTTP edge | [module reference](../../vertique-starter/vertique-starter-services/src/main/resources/META-INF/vertique/module.md) |
| `vertique-starter-postgresql` | your application needs PostgreSQL pooling and Flyway migration wiring, alongside an application starter rather than in place of one | [module reference](../../vertique-starter/vertique-starter-postgresql/src/main/resources/META-INF/vertique/module.md) |

An application parented on `vertique-app-parent` (or importing `vertique-bom` directly) declares
one or more of these with no explicit version, exactly as [Quickstart](quickstart.md),
[Services](services.md), and [Persistence](persistence.md) each show.

## Archetypes

Each archetype is the generation entry point for one of the starter combinations above. Every
group id is `dev.vertique`, and every one generates the same way: `mvn archetype:generate` with
the archetype's own coordinate plus the generated project's own `groupId`/`artifactId`/package,
exactly as [Quickstart](quickstart.md), [Services](services.md), and
[Persistence](persistence.md) run end to end.

| Archetype | Generates | Reference |
|---|---|---|
| `vertique-archetype-rest` | a REST API application on `vertique-starter-rest` | [Quickstart](quickstart.md), [archetype reference](../../vertique-archetype/vertique-archetype-rest/README.md) |
| `vertique-archetype-services` | a headless event-bus services application on `vertique-starter-services` | [Services](services.md), [archetype reference](../../vertique-archetype/vertique-archetype-services/README.md) |
| `vertique-archetype-rest-postgresql` | a PostgreSQL-backed REST application on `vertique-starter-rest` and `vertique-starter-postgresql` | [Persistence](persistence.md), [archetype reference](../../vertique-archetype/vertique-archetype-rest-postgresql/README.md) |

## Discover every artifact

[`docs/modules.md`](../../docs/modules.md) is the canonical index of every BOM-managed consumable
Vertique artifact: one row per artifact, sorted by artifact id, linking directly to that artifact's
own canonical `module.md` reference — the same kind of reference every "module reference" link
elsewhere on this page and throughout this corpus points at. The application parent, the BOM, and
the three archetypes above each have no canonical `module.md` of their own and are documented on
this page instead. This corpus's own pages walk specific journeys through a subset of that index;
`docs/modules.md` is where to look up any other Vertique artifact by name.

## Compatibility

No separate, named compatibility or support policy is published for Vertique yet. Two signals are
canonically documented today, and this page states only those — it does not infer a broader
guarantee from them:

- **Starter aggregate membership is a release-line compatibility surface.**
  [Application model](application-model.md#what-starters-own) already establishes this for the
  four starters above: which modules a named starter includes, and only those, is something
  consumers may rely on, and a change to it is treated as compatibility-affecting rather than as
  internal refactoring. Each starter's own module reference above states this again directly, for
  example
  [`vertique-starter-core`'s own "Membership is a compatibility surface" section](../../vertique-starter/vertique-starter-core/src/main/resources/META-INF/vertique/module.md#membership-is-a-compatibility-surface).
- **A module's `Status` header signals its maturity.** The four starters above each carry
  `Status: Alpha` in their own module reference today — read that header on any module reference
  before depending on it in a way that assumes stability beyond the compatibility surface above.

## Learn more

- [Packaging guide](../../docs/packaging.md) — the parent and BOM recipes in full, including
  Lombok opt-in and disabling annotation processing.
- [Root README — Application Maven Setup](../../README.md#application-maven-setup)
- [Module index](../../docs/modules.md) — every BOM-managed consumable artifact with a canonical
  module reference, one row each; the parent, BOM, and archetypes are covered above instead.
- [`vertique-starter-core` module reference](../../vertique-starter/vertique-starter-core/src/main/resources/META-INF/vertique/module.md)
- [`vertique-starter-rest` module reference](../../vertique-starter/vertique-starter-rest/src/main/resources/META-INF/vertique/module.md)
- [`vertique-starter-services` module reference](../../vertique-starter/vertique-starter-services/src/main/resources/META-INF/vertique/module.md)
- [`vertique-starter-postgresql` module reference](../../vertique-starter/vertique-starter-postgresql/src/main/resources/META-INF/vertique/module.md)
- [Vertique REST application archetype](../../vertique-archetype/vertique-archetype-rest/README.md)
- [Vertique services application archetype](../../vertique-archetype/vertique-archetype-services/README.md)
- [Vertique REST/PostgreSQL application archetype](../../vertique-archetype/vertique-archetype-rest-postgresql/README.md)
- [Quickstart](quickstart.md)
- [Application model](application-model.md)
- [Services](services.md)
- [Persistence](persistence.md)
- [Documentation overview](index.md)

## Continue reading

- Previous: [Deployment](deployment.md)
- Next: [Documentation overview](index.md)
