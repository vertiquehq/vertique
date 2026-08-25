<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# OpenTelemetry Cache Module

> **Status:** Beta
> **Package:** `dev.vertique.cache.opentelemetry`
> **Artifact:** `vertique-cache-opentelemetry`
> **Depends on:** `vertique-cache-core`, `vertique-opentelemetry-core`

Provides the optional OpenTelemetry adapter for provider-neutral cache observations. Install
`OpenTelemetryCacheModule` alongside `OpenTelemetryModule` and the application's cache modules in
the Dagger component when cache operations should be represented as child spans.

`CacheTracingObserver` creates spans named `cache.<operation>` with bounded `provider`, `cache`, and
`outcome` attributes plus `duration_ms`. Failed observations mark the span as an error. Tracer and
span failures are swallowed so telemetry cannot alter cache behavior.

The adapter is deliberately separate from `vertique-opentelemetry-core`: the core module owns SDK
bootstrap, Vert.x tracing, correlation, and security span events, while this module owns the cache
observer contribution.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-cache-core` | Provides the cache observer SPI and observations |
| `vertique-opentelemetry-core` | Provides `Tracer` and `TracingConfig` |
