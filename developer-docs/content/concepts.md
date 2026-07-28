---
title: Core concepts
description: Learn the vocabulary behind Vertique applications, services, lifecycle, generated wiring, durable work, and version-matched agent knowledge.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Core concepts

Vertique gives a microservice an explicit structure: one application-owned assembly root, generated
wiring for annotated types, ordered lifecycle phases, and capability modules that remain separate
until the application chooses them. This page defines that vocabulary before the task-oriented
guides use it.

## The application component

Every Vertique application owns one Dagger component annotated with `@VertiqueApp`. The component
is the assembly root: it names the framework starters, direct capability modules, application
modules, and generated modules that exist in that application.

The framework launches and drives the component, but it does not hide it. Dependencies remain
visible in Java, structural wiring errors fail at compile time, and there is no runtime dependency
injection container scanning the classpath.

[Application model](application-model.md) shows the generated component and divides application
ownership from framework ownership.

## Verticles, deployments, and lifecycle

A Vert.x verticle is an independently deployed unit of event-loop or worker execution. Vertique
represents application deployments explicitly and orders them through lifecycle phases:
`CONFIGURE`, `VALIDATE`, `MIGRATE`, `BOOTSTRAP`, `INFRA`, `SERVICES`, `EDGE`, and `AFTER_START`.

This ordering is a correctness boundary, not a naming convention. Configuration and validation run
before migrations, service dispatch is available before an HTTP edge accepts traffic, and shutdown
reverses ownership through the same lifecycle machinery.

Application code contributes deployments and startup steps; the lifecycle runner owns sequencing,
rollback, and shutdown. See the
[`vertique-application` reference](../../vertique-application/src/main/resources/META-INF/vertique/module.md).

## Services

A Vertique service is a typed Java contract whose implementation is invoked over the local Vert.x
event bus. The contract carries stable service and operation identities; the implementation is a
normal injectable class.

The annotation processor validates service shapes and generates registration metadata and Dagger
wiring. At runtime, callers use a typed client created by `ServiceClientFactory`; the current client
is a JDK proxy that captures dispatch context and sends the call through the service transport.
Handlers run on the event loop by default, with explicit worker deployment for genuinely blocking
work.

This makes a service callable from another application capability without an HTTP hop. A JAX-RS
resource can adapt HTTP to a service, but the service contract does not depend on REST.

[Services](services.md) builds a headless application, while the
[`vertique-services` reference](../../vertique-services/src/main/resources/META-INF/vertique/module.md)
defines the complete consumer contract.

## Starters and capability modules

A starter is a supported Dagger aggregate for an application foundation. It gives a stable name to
a tested set of modules:

- `vertique-starter-core` provides the host-neutral lifecycle foundation.
- `vertique-starter-rest` adds an HTTP/JAX-RS edge.
- `vertique-starter-services` adds headless event-bus services.
- `vertique-starter-postgresql` adds pooling and application-owned migrations alongside an
  application starter.

Capabilities without a starter remain direct modules in the application component. Security,
Kafka, observability, workflows, jobs, and inbox/outbox are not silently included merely because an
application chose REST or services.

This boundary keeps composition explicit while avoiding repeated low-level module lists. See
[Artifacts](artifacts.md) for coordinates and [Application model](application-model.md) for the
actual component shapes.

## Code generation

Vertique uses annotation processing to turn source declarations into Dagger-visible wiring and
build-time validation. Generated modules cover surfaces such as application bootstrap factories,
JAX-RS resources, and service contract contributors.

The important mental model is:

```text
application annotations
        │
        ▼
compile-time validation and generated modules
        │
        ▼
application-owned Dagger component
        │
        ▼
explicit runtime lifecycle
```

Generated code does not remove the application component; it feeds it. Naming a generated module
in the component is also a compile-time guard that the corresponding processor ran.

## Configuration

Configuration is read before the final application graph is built. Framework modules parse their
owned sections into typed, validated records at the Dagger boundary so internal code does not
repeatedly traverse raw JSON.

The application owns environment-specific values and secret-provider selection. Vertique owns
source loading, typed parsing, and fail-fast validation for installed capabilities.

[Configuration](configuration.md) explains source resolution and the working-directory contract.

## Durable work

Three families solve different reliability problems:

- a workflow coordinates a long-running sequence with persisted state and recovery;
- a job schedules or retries a durable unit of work; and
- inbox/outbox messaging makes message recording atomic with an application database transaction
  and supports idempotent consumption.

They are not aliases for ordinary service calls. Use an in-memory service call when work only needs
the lifetime of the current process; choose a durable family when state must survive failure or
restart. [Workflows](workflows.md) provides the selection guide and transactional boundaries.

## The management plane

Health, readiness, and other operational endpoints belong to a management surface separate from the
application edge. Generated REST and services applications include management wiring through their
starters, and the default examples expose it on port `9090`.

Liveness answers whether the process can run. Readiness composes capability health — for example,
whether supervised services are available — and answers whether the application should receive
traffic. [Deployment](deployment.md) covers the operational boundary.

## Agent-native knowledge

Agent-native means the framework publishes the context needed to work on the version an application
actually uses. Every consumable module JAR carries its canonical `META-INF/vertique/module.md`, while
cross-module composition is published separately so module facts are not copied into a central
catalog.

The companion [Vertique skills](https://github.com/vertiquehq/vertique-skills) inspect a Maven
project, resolve its Vertique versions, and read documentation from those exact artifacts. Human
developers can follow the same references through the [module index](../../docs/modules.md).

The contract is provenance rather than a claim that an agent is always correct: generated wiring,
typed configuration, compiler failures, and executable tests remain the authority.

## Continue reading

- Previous: [Quickstart](quickstart.md)
- Next: [Application model](application-model.md)
