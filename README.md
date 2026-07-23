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
./mvnw -ntp spotless:check
```

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
