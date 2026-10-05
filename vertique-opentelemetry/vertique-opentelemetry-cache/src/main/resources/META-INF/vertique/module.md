<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# OpenTelemetry Cache Module

> **Status:** Stable
> **Package:** `dev.vertique.opentelemetry.cache`
> **Artifact:** `vertique-opentelemetry-cache`
> **Depends on:** `vertique-cache-core`, `vertique-opentelemetry-core`

Provides the optional OpenTelemetry adapter for provider-neutral cache observations. It contributes
one `CacheObserver` through `OpenTelemetryCacheModule`; cache modules remain independent of
OpenTelemetry and applications choose whether to install this adapter.

## When To Use It

Install `OpenTelemetryCacheModule` alongside `OpenTelemetryModule` and the application's cache
modules in the Dagger component when cache operations should be represented as child spans.

The adapter injects the `Tracer` and `TracingConfig` bindings supplied by
`vertique-opentelemetry-core`. When `tracing.enabled=false`, `OpenTelemetryModule` binds a no-op
`Tracer`, so the adapter stays installed but inert: it starts no recording spans and exports
nothing. This module owns no configuration keys of its own.

## Core Concepts

The contributed `CacheObserver` creates spans named `cache.<operation>` with bounded `provider`,
`cache`, and `outcome` attributes plus `duration_ms`. Failed observations mark the span as an error.
Tracer and span failures are swallowed so telemetry cannot alter cache behavior. Cleanup events
remain metrics-only and produce no span. No key, identity, IP address, or exception message is ever
set as a span attribute.

The adapter is deliberately separate from `vertique-opentelemetry-core`: the core module owns SDK
bootstrap, Vert.x tracing, correlation, and security span events, while this module owns the cache
observer contribution.

## Key Classes

### OpenTelemetryCacheModule

Abstract Dagger module. Install it explicitly to contribute one `CacheObserver` to the
`Set<CacheObserver>` multibinding. It declares no other bindings; the application must provide the
`Tracer` and `TracingConfig` bindings, in practice by installing `OpenTelemetryModule`.

## Module Dagger Bindings

| Type | Qualifier | Description |
|---|---|---|
| `CacheObserver` | `@IntoSet` | OpenTelemetry cache observer |

The adapter's simple SPI contribution is declared on its injectable implementation with
`@RegisterIntoSet`. During the provider build, `vertique-codegen-dagger` emits
`GeneratedRegistrationsModule`, which this module includes explicitly. The generated module contains
only this type adaptation; configuration, registry, and optional bindings remain hand-written.

## Verification

```text
./mvnw -ntp -pl vertique-opentelemetry/vertique-opentelemetry-cache -am verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-cache-core` | Provides the cache observer SPI and observations |
| `vertique-opentelemetry-core` | Compile dependency; provides `Tracer` and `TracingConfig` |
