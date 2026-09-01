<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# OpenTelemetry Rate Limit Module

> **Status:** Beta
> **Package:** `dev.vertique.opentelemetry.ratelimit`
> **Artifact:** `vertique-opentelemetry-rate-limit`
> **Depends on:** `vertique-rate-limit-core`, `vertique-opentelemetry-core`

`vertique-opentelemetry-rate-limit` is the optional OpenTelemetry adapter for the
provider-neutral rate-limiting runtime. It contributes exactly one
`RateLimitObserver` implementation; installing `vertique-rate-limit-core` alone
contributes no observer and emits no telemetry.

## When To Use It

Install `OpenTelemetryRateLimitModule` alongside `OpenTelemetryModule` and the
application's rate-limit modules when a completed rate-limit decision should be
represented as its own span.

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
| `vertique-opentelemetry-core` | Provides `Tracer` and tracing configuration |
