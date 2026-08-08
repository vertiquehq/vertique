<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Vertique

Vertique is a Vert.x-native Java 21 framework for teams building services that must coordinate
durable work without giving up an explicit, non-blocking architecture. It combines compile-time
application assembly and diagnostics with a contract-based service execution model and a
PostgreSQL-backed durability stack for workflows, jobs, and transactional inbox/outbox messaging.
Application code calls typed Java interfaces while Vertique handles event-bus dispatch and context
propagation. REST, security, configuration, observability, and Kafka integration compose around
that foundation.

## Build

Run the full unit and integration-test suite:

```bash
./mvnw -ntp clean verify
```

Check formatting without changing files:

```bash
./mvnw -ntp -pl '!vertique-app-parent' spotless:check
```

## Application Maven Setup

For a new application, inherit the public application parent and declare only the runtime
capabilities the application uses:

```xml
<parent>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-app-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version> <!-- replace with the released Vertique version -->
    <relativePath/>
</parent>

<dependencies>
    <dependency>
        <groupId>dev.vertique</groupId>
        <artifactId>vertique-application</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.vertique</groupId>
        <artifactId>vertique-rest-jaxrs</artifactId>
    </dependency>
</dependencies>
```

The parent imports the Vertique BOM and supplies Dagger plus the complete Vertique processor
facade. Applications do not select individual processor artifacts. Projects that must retain a
custom parent, applications that explicitly opt in to Lombok, and modules that intentionally
disable annotation processing are covered in the [packaging guide](docs/packaging.md).

## Starter Modules

Each consumable starter publishes exactly one public Dagger aggregate that composes a fixed set of
framework capabilities behind one class name. An application names the starter it needs in its
`@Component` instead of repeating that capability's module list:

- **[`vertique-starter-core`](vertique-starter/vertique-starter-core/src/main/resources/META-INF/vertique/module.md)** —
  host-neutral lifecycle foundation: the Vert.x seam, config parsing, verticle deployment, and core
  lifecycle steps.
- **[`vertique-starter-rest`](vertique-starter/vertique-starter-rest/src/main/resources/META-INF/vertique/module.md)** —
  mechanism-neutral REST composition on top of the core starter: JAX-RS routing, request
  validation, the security runtime, and management.
- **[`vertique-starter-services`](vertique-starter/vertique-starter-services/src/main/resources/META-INF/vertique/module.md)** —
  contract-based service execution on top of the core starter: typed clients, event-bus dispatch,
  managed service verticles, and management.
- **[`vertique-starter-postgresql`](vertique-starter/vertique-starter-postgresql/src/main/resources/META-INF/vertique/module.md)** —
  independent PostgreSQL pooling and application-owned Flyway migration wiring, composed alongside
  an application starter rather than in place of one.

The `vertique-starter` POM aggregator and `vertique-starter-integration-tests` module are internal,
non-consumable reactor infrastructure: neither is published through the BOM, and neither carries a
canonical module reference or a [module index](docs/modules.md) row.

## Application Archetypes

Each application archetype generates a functional starting application on one or more starter
modules via `mvn archetype:generate`; the generated project documents its own `exec:java`,
`verify`, `package`, and `jib:dockerBuild` commands.

- **[`vertique-archetype-rest`](vertique-archetype/vertique-archetype-rest/README.md)** — a REST API
  application on `vertique-starter-rest`.
- **[`vertique-archetype-services`](vertique-archetype/vertique-archetype-services/README.md)** — a
  contract-based services application on `vertique-starter-services`.
- **[`vertique-archetype-rest-postgresql`](vertique-archetype/vertique-archetype-rest-postgresql/README.md)** —
  a PostgreSQL-backed REST application on `vertique-starter-rest` and `vertique-starter-postgresql`.
  Generation needs no Docker; running the generated project's `mvn verify` requires a reachable
  Docker daemon, because its integration test starts a real PostgreSQL container.

Each child README documents the exact `archetype:generate` invocation for its coordinate. The
`vertique-archetype` POM aggregator is internal, non-consumable reactor infrastructure: like the
starter family's own aggregator, it is not published through the BOM and carries no canonical
module reference or [module index](docs/modules.md) row.

## Documentation

- [Developer documentation](https://vertique.dev/docs)
- [Module index](docs/modules.md)
- [Architecture](docs/architecture.md)
- [Coding conventions](docs/coding-conventions.md)
- [Packaging](docs/packaging.md)
- [Workflow guide](docs/workflow.md)

Each published module owns its detailed reference documentation at
`src/main/resources/META-INF/vertique/module.md`.

## Coding agents

The companion [vertique-skills](https://github.com/vertiquehq/vertique-skills) repository ships
agent skills for Claude Code, Codex CLI, GitHub Copilot, and Cursor. Its knowledge skill reads the
canonical `module.md` inside the exact artifact versions your application resolves, so an agent's
answers stay version-matched to your build rather than to whichever documentation is newest.

## Examples

The `examples/` directory contains standalone applications for REST, services,
database access, REST clients, webhooks, server-sent events, WebSockets, events,
localization, AOP, code generation, and workflows.
