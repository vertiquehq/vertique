<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Architecture

Vertique is a Java 21 multi-module Maven framework built around Vert.x. It uses
compile-time code generation and Dagger dependency injection to keep application
wiring explicit and reflection-light.

## Dependency direction

Dependencies flow from application-facing capabilities toward smaller foundation
modules:

```text
examples and application composition
        |
REST, services, jobs, workflows, Kafka, observability
        |
security, configuration, persistence, context propagation
        |
core and JSON foundations
```

Foundation modules do not import higher-level capabilities. Adapter modules depend
on the neutral API or SPI owned by the producer module they observe or extend.
The public parent enforces the repository boundary during Maven validation,
including transitive dependencies.

## Compile-time wiring

Annotation processors under `vertique-codegen` generate Dagger bindings, service
dispatch metadata, JAX-RS resource registration, REST client proxies, workflow
metadata, event bindings, and AOP proxies. Generated bindings feed the same Dagger
component graph as hand-written modules, so missing and duplicate bindings fail at
compile time.

## Runtime composition

Applications assemble a root Dagger component from the capabilities they use.
Lifecycle contributions are ordered explicitly for configuration, validation,
infrastructure, service dispatch, and edge transports. Vert.x `Future` values carry
asynchronous completion; blocking work must be placed on an explicit worker
boundary.

Cross-cutting behavior uses neutral, ordered extension points. Producer modules own
their events and SPIs, while metrics, tracing, logging, and application extensions
contribute adapters without reversing dependency direction.

## Module documentation

The [module index](modules.md) links to the canonical reference document packaged
with each consumable artifact. Those documents define public configuration, API and
SPI contracts, lifecycle behavior, dependencies, and verification guidance.
