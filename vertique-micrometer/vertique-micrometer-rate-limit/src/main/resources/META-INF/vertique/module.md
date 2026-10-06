<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Micrometer Rate Limit Module

> **Status:** Stable
> **Package:** `dev.vertique.micrometer.ratelimit`
> **Artifact:** `vertique-micrometer-rate-limit`
> **Depends on:** `vertique-rate-limit-core`, `vertique-micrometer-core`, micrometer-core

`vertique-micrometer-rate-limit` is the optional Micrometer adapter for the
provider-neutral rate-limiting runtime. It contributes exactly one
`RateLimitObserver` implementation; the rate-limit runtime stays free of a
Micrometer dependency without it, and installing `vertique-rate-limit-core` alone
contributes no observer and emits no telemetry.

## When To Use It

Install `MicrometerRateLimitModule` alongside `MicrometerModule` and the
application's rate-limit modules (`RateLimitCoreModule`, and
`vertique-rate-limit-redis` for CLUSTERED policies) when decision, rejection, and
failure metrics are wanted:

```java
@Component(modules = {
    VertxModule.class,
    RateLimitCoreModule.class,
    MicrometerModule.class,
    MicrometerRateLimitModule.class
})
interface AppComponent {}
```

## Core Concepts

The adapter is subject to the same redaction constraint as the events it consumes:
no key, identity, or IP material ever reaches a metric tag. `policy` (the event's
`policyName`) is the only per-request-shape dimension it emits, and it joins the
same shared cardinality-guard key union the other Vertique Micrometer adapters use.

## Meters

Prefix: `vertique.ratelimit.`.

| Meter | Type | Tags | Recorded |
|---|---|---|---|
| `vertique.ratelimit.decision` | Timer | `policy`, `outcome`, `mode` | Once per `RateLimitDecisionCompleted` event, regardless of outcome; records the event's backend latency |
| `vertique.ratelimit.rejections` | Counter | `policy` | Once per event where `outcome == QUOTA_EXCEEDED` |
| `vertique.ratelimit.failures` | Counter | `policy`, `code`, `mode` | Once per event where `outcome` is `BACKEND_FAILURE_OPEN` or `BACKEND_FAILURE_CLOSED`; `code` is the event's `failureCode` |

`vertique.ratelimit.rejections` and `vertique.ratelimit.failures` are strict subsets
of the decision timer's count. **No gauges** are registered: isolated runtime scopes
make an aggregate gauge misleading, the same rationale `vertique-micrometer-resilience`
documents for its own meters.

Only the policy name, outcome, mode, and failure code become labels — key, identity,
IP, exception, and message are never labels. This module performs no label validation
of its own: `policy`, `outcome`, `mode`, and `code` are taken from the event as-is, and
the only bound on their cardinality is the shared cardinality guard installed by
`vertique-micrometer-core` (see its `module.md`).

## Configuration

This module owns no configuration keys. It follows the global `metrics.enabled` switch
through the optional `MetricsConfig`: when `MicrometerModule` supplies a `MetricsConfig`,
`metrics.enabled=false` silences the adapter; when no `MetricsConfig` binding exists the
optional is empty and the adapter is enabled.

## Key Classes

### MicrometerRateLimitModule

Dagger `@Module` (public, abstract, not instantiable). It includes the generated
`GeneratedRegistrationsModule`, which contributes one `RateLimitObserver` into
`Set<RateLimitObserver>`, and declares `@BindsOptionalOf MetricsConfig metricsConfig()` so
that observer can inject `Optional<MetricsConfig>` without requiring `MicrometerModule`.
Install it in the application component; it is the module's only application-facing type.

## Module Dagger Bindings

| Binding | Kind | Description |
|---|---|---|
| `MetricsConfig` | `@BindsOptionalOf` | Declared for adapter enablement checks; the adapter can be used with or without `MicrometerModule` as long as a `MeterRegistry` binding is present |
| `RateLimitObserver` | `@RegisterIntoSet` | The metrics observer, wired through the generated `GeneratedRegistrationsModule` this module includes |

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-rate-limit-core` | Provides the `RateLimitObserver` SPI and `RateLimitDecisionCompleted` events |
| `vertique-micrometer-core` | Provides the application `MeterRegistry` and metrics configuration when `MicrometerModule` is installed |
| micrometer-core | Timers, counters, tags, and registry APIs |
