---
title: Vertique developer documentation
description: Understand Vertique's value, install its prerequisites, and find the supported path for building a Vertique application.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Vertique developer documentation

Vertique is an opinionated, agent-native Java 21 framework for building Vert.x microservices. It
combines a disciplined application model — compile-time Dagger assembly, generated wiring, phased
lifecycle, and typed configuration — with composable HTTP, event-bus service, persistence, durable
work, security, messaging, management, and observability capabilities.

The framework is shaped by years of experience building bank-grade microservices. That history is
expressed as concrete behavior: invalid wiring and restrictive security declarations fail early,
durable work has explicit transaction and idempotency boundaries, and every consumable artifact
carries version-matched reference documentation that both developers and coding agents can inspect.

## Why Vertique

- **Explicit application model** — Dagger assembles one application-owned component at compile
  time; generated modules make discovered resources and services visible, while lifecycle phases
  make startup and shutdown ordering explicit.
- **Agent-native, version-matched knowledge** — each consumable JAR carries its own `module.md`.
  The companion [Vertique skills](https://github.com/vertiquehq/vertique-skills) resolve the
  versions in an application's Maven model and read those exact artifact references instead of
  guessing from a different release.
- **Composable microservice capabilities** — REST, services, and PostgreSQL persistence compose
  through starters; security mechanisms, the durable-work families (jobs, workflows, inbox/outbox),
  and observability (metrics, tracing) compose through direct capability modules named in your own
  component instead, since none of them publishes a starter of its own.
- **Fail early at boundaries** — typed configuration validates at startup, generated wiring turns
  structural mistakes into compiler errors, and restrictive authorization declarations without an
  enforcement mechanism fail closed.
- **JAX-RS and OpenAPI at the HTTP edge** — familiar annotations produce Vert.x routing and a
  build-time OpenAPI specification. Request validation is annotation-driven by default, with
  generated-spec contract validation as a separate opt-in module.

## Prerequisites

- JDK 21
- Apache Maven
- This framework built and installed into your local Maven repository from this repository's own
  source — no published artifacts exist yet; see [Quickstart](quickstart.md) for the one-time
  build step

## The supported path

Vertique applications are not built by hand-assembling individual framework modules one at a
time — at least not for the application's foundation. The supported path for the lifecycle, REST,
services, and PostgreSQL-persistence foundation is:

1. **Generate an application from an archetype.** REST, headless services, and REST with PostgreSQL
   are peer entry points. Each archetype produces a working, tested
   application skeleton: a `pom.xml` parented on the application parent, a Dagger application
   component and module, an example resource or service, a JSON configuration file, and an
   integration test.
2. **Compose framework capability through starters.** Each starter publishes exactly one public
   Dagger aggregate that composes a fixed set of framework capabilities behind one class name.
   Your application names the starter it needs instead of assembling that capability's module
   list by hand.

Capability with no starter — security mechanisms, the durable-work families (jobs, workflows,
inbox/outbox), and observability — is composed the other way: as direct framework modules named in
your own component. See [Application model](application-model.md) for how starters and direct
modules fit together, and [Workflows](workflows.md) for a worked example of composing capability
without a starter.

## Next steps

- [Quickstart](quickstart.md) — generate a REST application, run its tests, start it, and call
  its hello endpoint.
- [Core concepts](concepts.md) — the vocabulary behind applications, services, lifecycle,
  generated wiring, durable work, and version-matched agent knowledge.
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
