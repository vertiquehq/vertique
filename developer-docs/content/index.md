---
title: Vertique developer documentation
description: Understand Vertique's value, install its prerequisites, and find the supported path for building a Vertique application.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Vertique developer documentation

Vertique is an opinionated Java 21 framework for building Vert.x applications. It lets you write
REST APIs using JAX-RS annotations on Vert.x, generates an OpenAPI specification from those
annotations at build time, validates incoming requests by default against JSON Schema synthesized
from the same annotations (OpenAPI-contract request validation against the generated spec is
available as a separate opt-in module), and assembles your application with compile-time Dagger 2
dependency injection instead of a runtime container.

## Why Vertique

- **JAX-RS annotations on Vert.x** — define resources with `@Path`, `@GET`, `@POST`, and the rest
  of the familiar JAX-RS annotation set; Vertique generates the Vert.x routing for you.
- **Build-time OpenAPI generation, annotation-driven request validation by default** — the OpenAPI
  specification is generated from your annotated resources at build time. Incoming requests are
  validated by default against JSON Schema synthesized from those same annotations (see the
  [`vertique-rest-validation` module reference](../../vertique-rest/vertique-rest-validation/src/main/resources/META-INF/vertique/module.md)),
  with contract validation against the generated spec available as an opt-in
  [`vertique-rest-openapi-validation` module](../../vertique-rest/vertique-rest-openapi-validation/src/main/resources/META-INF/vertique/module.md).
  Neither server-side validation strategy validates responses against the OpenAPI contract.
- **Compile-time dependency injection** — application wiring is assembled by Dagger 2 at compile
  time, so there is no runtime classpath scanning or reflection-based container to configure.
- **Composable capability** — REST, services, persistence, jobs, workflows, security, and
  observability are assembled through starters rather than by hand-wiring individual framework
  modules together.

## Prerequisites

- JDK 21
- Apache Maven
- This framework built and installed into your local Maven repository from this repository's own
  source — no published artifacts exist yet; see [Quickstart](quickstart.md) for the one-time
  build step

## The supported path

Vertique applications are not built by hand-assembling individual framework modules one at a
time. The supported path is:

1. **Generate an application from an archetype.** Each archetype produces a working, tested
   application skeleton: a `pom.xml` parented on the application parent, a Dagger application
   component and module, an example resource or service, a JSON configuration file, and an
   integration test.
2. **Compose framework capability through starters.** Each starter publishes exactly one public
   Dagger aggregate that composes a fixed set of framework capabilities behind one class name.
   Your application names the starter it needs instead of assembling that capability's module
   list by hand.

## Next steps

- [Quickstart](quickstart.md) — generate a REST application, run its tests, start it, and call
  its hello endpoint.
- [Application model](application-model.md) — how the launcher, your application's Dagger
  component/module, generated modules, and starters fit together.
- [Configuration](configuration.md) — JSON configuration sources, typed configuration, and
  secret providers.
- [REST APIs](rest-apis.md) — add a JAX-RS resource with request validation and error mapping.
- [Services](services.md) — define and call a service without REST-specific coupling.
- [Persistence](persistence.md) — add PostgreSQL, own your migrations, and manage transactions.
- [Workflows](workflows.md) — choose between durable workflows, jobs, and inbox/outbox.
- [Security](security.md) — baseline authentication, authorization, and safe defaults.
- [Testing](testing.md) — generated-project tests, test utilities, and focused/full Maven
  commands.
- [Deployment](deployment.md) — package, configure, and run a Vertique application.
- [Artifacts](artifacts.md) — the parent, BOM, starters, archetypes, and how to discover them.

## Continue reading

- Next: [Quickstart](quickstart.md)
