<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Micrometer Cache Module

> **Status:** Beta
> **Package:** `dev.vertique.micrometer.cache`
> **Artifact:** `vertique-micrometer-cache`
> **Depends on:** `vertique-micrometer-core`, `vertique-cache-core`, micrometer-core

Provides the explicit Micrometer adapter for provider-neutral cache observations. It contributes
`CacheMetricsObserver` through `MicrometerCacheModule`; cache modules remain independent of
Micrometer and applications choose whether to install this adapter.

## When To Use It

Add `vertique-micrometer-cache` when the application uses `vertique-cache-core` with a local or
Redis provider and wants cache operation and cleanup metrics. Install `MicrometerCacheModule`
alongside `MicrometerModule` and the application's cache modules in the Dagger `@Component`.

The adapter may be installed without `MicrometerModule`, but the application must provide the
`MeterRegistry` binding that the adapter injects. When no `MetricsConfig` binding is present,
cache metrics default to enabled. When `metrics.enabled=false`, the adapter records nothing.

## Core Concepts

`CacheMetricsObserver` consumes the cache-core `CacheObserver` callbacks. Operation observations
are recorded as timers named `cache.<operation>` with bounded `provider`, `cache`, and `outcome`
tags. Cleanup observations use the bounded `profile`, `namespace`, and `outcome` tags and record
these counters:

| Counter | Value recorded |
|---|---|
| `cache.cleanup.runs` | one increment per cleanup outcome |
| `cache.cleanup.scanned` | number of inspected keys |
| `cache.cleanup.deleted` | number of deleted keys |
| `cache.cleanup.backlog` | backlog indicator/count |

`CacheOperationCompleted`, `CacheLateCompletion`, and `CacheCleanupCompleted` events contain redacted, bounded values supplied by the
cache modules. Registry failures are swallowed, so telemetry cannot change cache operations or
Redis cleanup behavior.

## Key Classes

### MicrometerCacheModule

Abstract Dagger module. Install it explicitly to contribute one `CacheMetricsObserver` to the
`Set<CacheObserver>` multibinding. It declares `MetricsConfig` as an optional binding so the
adapter can be used with or without `MicrometerModule`; without that module, the application
must provide the required `MeterRegistry` binding.

### CacheMetricsObserver

`CacheObserver` implementation that records operation timers and cleanup counters in the injected
`MeterRegistry`. It is fail-open and bounds metric dimensions to protect registry cardinality.

## Module Dagger Bindings

| Type | Qualifier | Description |
|---|---|---|
| `MetricsConfig` | optional | Declared for adapter enablement checks |
| `CacheObserver` | `@IntoSet` | `CacheMetricsObserver` |
The adapter's simple SPI contribution is declared on its injectable implementation with
`@RegisterIntoSet`. During the provider build, `vertique-codegen-dagger` emits
`GeneratedRegistrationsModule`, which this module includes explicitly. The generated module contains
only this type adaptation; configuration, registry, and optional bindings remain hand-written.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-micrometer-core` | Provides the application `MeterRegistry` and metrics configuration when `MicrometerModule` is installed |
| `vertique-cache-core` | Provides `CacheObserver` and the sealed `dev.vertique.cache.spi.event` vocabulary |
| `micrometer-core` | Supplies timers, counters, tags, and registry APIs |
