<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Vertique

Vertique is an opinionated Java 21 framework for building Vert.x applications with
compile-time dependency injection, JAX-RS routing, OpenAPI validation, configuration,
security, services, jobs, workflows, Kafka, persistence, and observability.

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
    <version>0.0.0-SNAPSHOT</version> <!-- replace with the released Vertique version -->
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
  headless event-bus services on top of the core starter: the dispatch runtime and management, with
  no HTTP surface.
- **[`vertique-starter-postgresql`](vertique-starter/vertique-starter-postgresql/src/main/resources/META-INF/vertique/module.md)** —
  independent PostgreSQL pooling and application-owned Flyway migration wiring, composed alongside
  an application starter rather than in place of one.

The `vertique-starter` POM aggregator and `vertique-starter-integration-tests` module are internal,
non-consumable reactor infrastructure: neither is published through the BOM, and neither carries a
canonical module reference or a [module index](docs/modules.md) row.

## Documentation

- [Module index](docs/modules.md)
- [Architecture](docs/architecture.md)
- [Coding conventions](docs/coding-conventions.md)
- [Packaging](docs/packaging.md)
- [Workflow guide](docs/workflow.md)

Each published module owns its detailed reference documentation at
`src/main/resources/META-INF/vertique/module.md`.

## Examples

The `examples/` directory contains standalone applications for REST, services,
database access, REST clients, webhooks, server-sent events, WebSockets, events,
localization, AOP, code generation, and workflows.
