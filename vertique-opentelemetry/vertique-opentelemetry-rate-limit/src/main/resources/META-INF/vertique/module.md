<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# OpenTelemetry Rate Limit Module

> **Status:** Stable
> **Package:** `dev.vertique.opentelemetry.ratelimit`
> **Artifact:** `vertique-opentelemetry-rate-limit`
> **Depends on:** `vertique-rate-limit-core`, opentelemetry-api (library)
> **Runtime pairing:** `vertique-opentelemetry-core` (provides the `Tracer` binding)

`vertique-opentelemetry-rate-limit` is the optional OpenTelemetry adapter for the
provider-neutral rate-limiting runtime. It contributes exactly one
`RateLimitObserver` implementation; installing `vertique-rate-limit-core` alone
contributes no observer and emits no telemetry.

## When To Use It

Install `OpenTelemetryRateLimitModule` alongside `OpenTelemetryModule` and the
application's rate-limit modules when a completed rate-limit decision should be
represented as its own span.

This artifact has no compile-time dependency on `vertique-opentelemetry-core`. The observer
injects an OpenTelemetry API `Tracer`, which the application's component must supply —
in practice by installing `OpenTelemetryModule` from `vertique-opentelemetry-core`.

## Core Concepts

One span, `vertique.ratelimit.decision`, is started and ended synchronously around
each `RateLimitDecisionCompleted` event's observation — the span represents the
already-completed decision, it is not a live wrapper around the `acquire`/`execute`
call. `StatusCode.ERROR` is set on the span only when `outcome` is
`BACKEND_FAILURE_OPEN` or `BACKEND_FAILURE_CLOSED`; `PERMITTED`, `QUOTA_EXCEEDED`,
and `DISABLED` never set `StatusCode.ERROR` — a quota denial is expected policy
behavior, not an error. No key, identity, or IP value is ever set as a span
attribute. The adapter is subject to the same redaction constraint as the events it
consumes; `policy` is the only per-request-shape attribute it emits.

When `tracing.enabled=false`, `OpenTelemetryModule` binds `OpenTelemetry.noop()`, so the
`Tracer` this observer receives is a no-op: the adapter stays installed but inert, starts no
recording spans, and exports nothing. This module owns no configuration keys of its own.

## Spans

| Attribute | Source |
|---|---|
| `policy` | `RateLimitDecisionCompleted.policyName` |
| `outcome` | `RateLimitDecisionCompleted.outcome` |
| `mode` | `RateLimitDecisionCompleted.mode` |
| `duration_ms` | `RateLimitDecisionCompleted.backendLatencyNanos`, converted to milliseconds |

`duration_ms` is a truncating integer conversion (nanoseconds divided into whole
milliseconds), not a rounded or fractional value — a sub-millisecond backend
latency records as `0`.

## Key Classes

### OpenTelemetryRateLimitModule

Dagger `@Module` (public, abstract, not instantiable). It includes the generated
`GeneratedRegistrationsModule`, which contributes the module's span observer into
`Set<RateLimitObserver>`. Install it in the application component alongside a module that
binds `Tracer`; it is the module's only application-facing type.

### RateLimitSpanObserver (INTERNAL)

Package-private `RateLimitObserver` that records the `vertique.ratelimit.decision` span
described in [Spans](#spans). Its Javadoc marks it INTERNAL: it is outside this module's
compatibility promise and may change in any release. Applications obtain it only through
`Set<RateLimitObserver>`, never by constructing or referencing the type.

## Module Dagger Bindings

| Binding | Kind | Description |
|---|---|---|
| `RateLimitObserver` | `@RegisterIntoSet` | The span-recording observer, wired through the generated `GeneratedRegistrationsModule` this module includes |

## Verification

```text
./mvnw -ntp -pl vertique-opentelemetry/vertique-opentelemetry-rate-limit -am verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-rate-limit-core` | Provides the `RateLimitObserver` SPI and `RateLimitDecisionCompleted` events |
| opentelemetry-api | `Tracer`, `Span`, and `StatusCode` APIs |
| `vertique-opentelemetry-core` *(runtime pairing, not a compile dependency)* | Supplies the `Tracer` binding via `OpenTelemetryModule` and the `tracing.enabled` switch; with `tracing.enabled=false` the `Tracer` is a no-op |
