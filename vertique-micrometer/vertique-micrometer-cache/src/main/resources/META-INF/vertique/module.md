<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Micrometer Cache Module

> **Status:** Stable
> **Package:** `dev.vertique.micrometer.cache`
> **Artifact:** `vertique-micrometer-cache`
> **Depends on:** `vertique-micrometer-core`, `vertique-cache-core`, micrometer-core

Provides the explicit Micrometer adapter for provider-neutral cache observations. It contributes
one `CacheObserver` through `MicrometerCacheModule`; cache modules remain independent of
Micrometer and applications choose whether to install this adapter. The module owns no
configuration keys: it follows `metrics.enabled` through the optional `MetricsConfig` binding.

## When To Use It

Add `vertique-micrometer-cache` when the application uses `vertique-cache-core` with a local or
Redis provider and wants cache operation and cleanup metrics. Install `MicrometerCacheModule`
alongside `MicrometerModule` and the application's cache modules in the Dagger `@Component`.

The adapter may be installed without `MicrometerModule`, but the application must provide the
`MeterRegistry` binding that the adapter injects. When no `MetricsConfig` binding is present,
cache metrics default to enabled (an absent `MetricsConfig` means enabled). When
`metrics.enabled=false`, the adapter records nothing.

## Core Concepts

The contributed `CacheObserver` consumes the cache-core observation events. Operation observations
are recorded as timers named `cache.<operation>` with bounded `provider`, `cache`, and `outcome`
tags. Cleanup observations use the bounded `profile`, `namespace`, and `outcome` tags and record
these counters:

| Counter | Value recorded |
|---|---|
| `cache.cleanup.runs` | one increment per cleanup outcome |
| `cache.cleanup.scanned` | number of inspected keys |
| `cache.cleanup.deleted` | number of deleted keys |
| `cache.cleanup.backlog` | backlog indicator/count |

`CacheOperationCompleted`, `CacheLateCompletion`, and `CacheCleanupCompleted` events contain
redacted, bounded values supplied by the cache modules. No key, identity, IP address, or exception
message is ever used as a tag. Registry failures are swallowed, so telemetry cannot change cache operations or
Redis cleanup behavior.

## Key Classes

### MicrometerCacheModule

Abstract Dagger module. Install it explicitly to contribute one `CacheObserver` to the
`Set<CacheObserver>` multibinding. The contributed observer records operation timers and cleanup
counters in the injected `MeterRegistry`, is fail-open, and bounds metric dimensions to protect
registry cardinality. The module declares `MetricsConfig` as an optional binding so the adapter
can be used with or without `MicrometerModule`; without that module, the application must
provide the required `MeterRegistry` binding. It owns no configuration keys beyond following
`metrics.enabled`.

## Module Dagger Bindings

| Type | Qualifier | Description |
|---|---|---|
| `MetricsConfig` | optional | Declared for adapter enablement checks |
| `CacheObserver` | `@IntoSet` | Micrometer cache observer |

The adapter's simple SPI contribution is declared on its injectable implementation with
`@RegisterIntoSet`. During the provider build, `vertique-codegen-dagger` emits
`GeneratedRegistrationsModule`, which this module includes explicitly. The generated module contains
only this type adaptation; configuration, registry, and optional bindings remain hand-written.

## Verification

```text
./mvnw -ntp -pl vertique-micrometer/vertique-micrometer-cache -am verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-micrometer-core` | Provides the application `MeterRegistry` and metrics configuration when `MicrometerModule` is installed |
| `vertique-cache-core` | Provides `CacheObserver` and the sealed `dev.vertique.cache.spi.event` vocabulary |
| `micrometer-core` | Supplies timers, counters, tags, and registry APIs |
