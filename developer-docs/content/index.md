---
title: Vertique developer documentation
description: Understand Vertique's value, install its prerequisites, and find the supported path for building a Vertique application.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Vertique developer documentation

Vertique is a Vert.x-native Java 21 framework for teams building services that must coordinate
durable work without giving up an explicit, non-blocking architecture. It combines compile-time
application assembly and diagnostics with typed event-bus services and a PostgreSQL-backed
durability stack for workflows, jobs, and transactional inbox/outbox messaging. REST, security,
configuration, and observability compose around that foundation.

Vertique is designed so humans and coding agents work from the same versioned contracts, generated
application model, compile-time guardrails, and executable verification paths. The companion
[vertique-skills](https://github.com/vertiquehq/vertique-skills) repository packages that design
for agent harnesses — Claude Code, Codex CLI, GitHub Copilot, and Cursor — answering module
questions from the canonical reference inside the exact artifact versions your application
resolves, alongside starter and archetype selection guidance. That contract is provenance, not a
claim that an agent is always right — generated wiring, typed configuration, compiler failures,
and executable tests remain the authority. The framework grows
out of years of building production microservices, carried forward into this same explicit,
non-blocking design.

## Why Vertique

- **Failures move earlier.** Dagger resolves your application's object graph at compile time, and
  the framework's own annotation processors emit diagnostics attributed to your source elements —
  a missing binding or a malformed contract fails the build, not the running process. A
  restrictive [security](security.md) annotation with no matching mechanism module fails
  application startup rather than silently allowing the request through, and blocking work needs
  an explicit worker opt-in instead of running on the event loop by accident. These guardrails
  cover the code you write; a module you forget to list in your own
  [application component](application-model.md) is a gap this generation does not yet close.
- **One stateful-service spine.** In their PostgreSQL-backed composition, durable workflows,
  scheduled and delayed jobs, and transactional inbox/outbox messaging (see
  [Workflows](workflows.md)) share one PostgreSQL database (see [Persistence](persistence.md)),
  one Dagger object graph, and the same transaction-consistent
  semantics: outbox delivery is at-least-once, with inbox-side deduplication as the framework's one
  exactly-once effect seam — stated precisely, not oversold. There is no operations UI; these are
  engines and APIs your application composes, not a console handed to you. PostgreSQL is a
  deliberate platform choice, not a constraint discovered later.
- **Ownership stays explicit.** The framework owns lifecycle machinery, integration plumbing, and
  request validation; your [application](application-model.md) owns its own component, its
  deployments, its migrations, its configuration, and where its platform boundaries sit. Every page
  in this documentation is written from that same split, naming exactly which side does the work —
  from [REST APIs](rest-apis.md) and [services](services.md) to [persistence](persistence.md) and
  [workflows](workflows.md).

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

1. **Generate an application from an archetype.** Each archetype produces a working, tested
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
