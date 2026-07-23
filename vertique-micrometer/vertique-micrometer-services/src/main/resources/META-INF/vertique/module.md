<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Micrometer Services Module

> **Status:** Alpha
> **Package:** `dev.vertique.micrometer.services`
> **Artifact:** `vertique-micrometer-services`
> **Depends on:** io.micrometer:micrometer-core (library), vertique-services

Observe-only service dispatch metrics adapter. When installed alongside `DispatchModule` and
`MicrometerModule`, it emits a per-dispatch timer (`vertique.service.dispatch`) for every terminal
service outcome observed on the consumer side.

The module has no compile dependency on `vertique-micrometer-core` types. It does, however,
**require a `MeterRegistry` binding on the Dagger graph** — normally supplied by `MicrometerModule`;
without it (or another `MeterRegistry` provider) the component does not compile. Only the
`metrics.enabled` *gate* is optional: the module declares `@BindsOptionalOf MetricsConfig`
independently, so the gate defaults to enabled when `MicrometerModule` is absent. It never
modifies the dispatch outcome, and it never submits audit records.

---

## When To Use It

Install `vertique-micrometer-services` whenever an application uses service dispatch via
`DispatchModule` and metrics are enabled. Pair it with `vertique-micrometer-core` and at least one
backend module (e.g., `vertique-micrometer-registry-prometheus`) to make the emitted meters visible.
Without a backend the injected `MeterRegistry` is an empty composite whose recording is a no-op.

---

## Core Concepts

**Consumer-side hook.** `ServiceDispatchMetricsInterceptor` implements `ServiceInterceptor` and
fires on the **consumer side** (inside `ServiceMethodInvoker`). There is no sender-side seam in the
services module; all observations happen after the event-bus message is received and processed.

**Terminal outcome, post-recovery.** The interceptor overrides `ServiceInterceptor#onTerminalComplete`
rather than `onComplete`. `onTerminalComplete` fires after the recovery pipeline, so recovered
failures appear as `SUCCESS` — the `outcome` tag reflects the result the caller ultimately receives.
`onComplete` fires before recovery and is not used.

**Reply timing.** The event-bus reply is sent to the caller before `onTerminalComplete` fires
(per the `ServiceMethodInvoker` implementation). Timer duration therefore includes dispatch + handler
execution but the terminal-status annotation is best-effort by construction — the caller cannot
observe the span status.

**Per-event registry lookup (no meter cache, D-L).** The interceptor calls
`Timer.builder(...).tags(...).register(registry)` on every event. Micrometer's internal registry
lookup is the cache. A separate tuple-keyed adapter cache was evaluated and rejected because the
drop-on-saturation variant silently stops recording real time series once legitimate tag combinations
exceed any fixed cap.

**Never-throws.** Every callback body is wrapped in a try/catch that logs at WARN and swallows. A
misbehaving registry can never affect dispatch processing.

**Zero-overhead when unconfigured.** Before `VertiqueApplication` bootstrap the injected
`MeterRegistry` is an empty composite whose recording is a no-op (NFR-TEL-003). When the optional
`MetricsConfig` binding is absent (i.e., `MicrometerModule` is not installed), the interceptor
defaults to enabled.

---

## Key Classes

### MicrometerServicesModule

Dagger `@Module`. Contributes one binding:

- `ServiceDispatchMetricsInterceptor` into `Set<ServiceInterceptor>` — records the per-dispatch
  timer on each terminal service outcome.

Also declares `@BindsOptionalOf MetricsConfig metricsConfig()` so the
interceptor can inject `Optional<MetricsConfig>` without a compile dependency on
`vertique-micrometer-core`. When `MicrometerModule` is also installed its
`@Provides MetricsConfig` binding satisfies the optional; when absent the optional is
empty and the interceptor defaults to enabled.

```java
@Component(modules = {
    VertxModule.class,
    DispatchModule.class,
    MicrometerModule.class,
    MicrometerServicesModule.class,
    // ...
})
interface AppComponent { /* ... */ }
```

### ServiceDispatchMetricsInterceptor

`@Singleton` `ServiceInterceptor`. Records one timer sample per terminal service outcome via
`onTerminalComplete`. Meter name: `vertique.service.dispatch`. When `MetricsConfig.enabled()` is
`false` (i.e., `metrics.enabled=false` in config), returns immediately without recording.

Negative durations (clock skew, test doubles) are clamped to zero before recording.

#### Invariants and Gotchas

- Implements only `onTerminalComplete`; all other `ServiceInterceptor` callbacks use the default
  no-op implementations.
- Recovered failures appear as `outcome=SUCCESS` by contract — `onTerminalComplete` always sees the
  final result after recovery.
- `stableTargetId()` on `ServiceDispatchContext` is `@Nullable`; a null value produces
  `target=UNKNOWN`.

---

## Meters

### `vertique.service.dispatch` — Timer

Per-dispatch timer. One sample is recorded per terminal `ServiceInterceptor#onTerminalComplete`
invocation.

| Tag | Values | Notes |
|-----|--------|-------|
| `target` | Stable dot-delimited target id, e.g. `integration.user-service.get-user` | `UNKNOWN` when `ServiceDispatchContext.stableTargetId()` is `null` |
| `outcome` | `SUCCESS` or `ERROR` | Derived from `Result.isSuccess()` / `Result.isFailure()` on the terminal result (post-recovery) |
| `oneway` | `true` or `false` | `true` for fire-and-forget (`@OneWay`) dispatches |
| `error.type` | Simple class name of the terminal failure cause, e.g. `IllegalArgumentException` | `none` when the outcome is `SUCCESS` |

Outcome convention: a dispatch that failed but was recovered by the service pipeline appears as
`outcome=SUCCESS` because `onTerminalComplete` receives the post-recovery result. Only dispatches
whose terminal result is still a failure produce `outcome=ERROR`.

---

## Dependencies

- `io.micrometer:micrometer-core` — `MeterRegistry`, `Timer`, `Tags`; no Vert.x Micrometer
  integration types. This is the only Micrometer dependency; the module has no compile dependency on
  `vertique-micrometer-core`.
- `dev.vertique:vertique-services` — `ServiceInterceptor`, `ServiceDispatchContext`.
- `dev.vertique:vertique-core` — `Result` (from `core.eventbus`).
- `com.google.dagger:dagger`, `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`, `org.projectlombok:lombok` (provided)

---

## Related ADRs

- ADR-0098: Micrometer Facade and Pluggable Registry Backends — establishes the `@BindsOptionalOf` adapter dependency rule (D-J) that allows this module to compile without `vertique-micrometer-core`, and the publish-on-success bootstrap contract that guarantees the injected registry is always non-null.
- ADR-0099: Metric Naming, Tag, and Cardinality Policy — establishes the `vertique.*` naming scheme, the `UNKNOWN`/`none` sentinel convention, the `GUARDED_TAG_KEYS` frozen list that covers `target`, `outcome`, `oneway`, and `error.type`, and the per-event registry lookup policy (no adapter cache).
