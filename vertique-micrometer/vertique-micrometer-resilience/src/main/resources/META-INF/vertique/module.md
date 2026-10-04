<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Vertique Micrometer Resilience Adapter

> **Status:** Stable
> **Package:** `dev.vertique.micrometer.resilience`
> **Artifact:** `vertique-micrometer-resilience`
> **Depends on:** `vertique-resilience`, `vertique-micrometer-core`, Micrometer, Dagger

`dev.vertique:vertique-micrometer-resilience` is an optional Micrometer adapter for the common
resilience runtime. Install `MicrometerResilienceModule` alongside `ResilienceModule` and
`MicrometerModule` in the application Dagger component:

```java
@Component(modules = {
    VertxModule.class,
    ResilienceModule.class,
    MicrometerModule.class,
    MicrometerResilienceModule.class
})
interface AppComponent {}
```

The module contributes exactly one synchronous `ResilienceObserver`. It is enabled by default and
follows `metrics.enabled`; when disabled, it does not register or touch a meter. Registry failures
are isolated and cannot change resilience execution outcomes.

## When To Use It

Install this adapter when an application already uses `vertique-resilience` and wants the fixed
resilience meter map on the shared `MeterRegistry`. Pair it with `vertique-micrometer-core` and at
least one backend (for example `vertique-micrometer-registry-prometheus`) so the meters are visible.
Without a backend the injected registry is an empty composite whose recording is a no-op.

## Key Classes

### MicrometerResilienceModule

Dagger `@Module`. Applications list this class in the `@Component`. It:

- includes the generated `GeneratedRegistrationsModule` that contributes the package-private
  `ResilienceMetricsObserver` into `Set<ResilienceObserver>` via `@RegisterIntoSet`
- declares `@BindsOptionalOf MetricsConfig` so the observer can read `metrics.enabled` whether or
  not `MicrometerModule` is installed (absent optional means enabled)

The observer implementation itself is package-private and not an application-visible type.

## Meters

| Meter | Type | Event | Tags |
|---|---|---|---|
| `vertique.resilience.execution` | timer | `ExecutionCompleted` | `operation`, `outcome` |
| `vertique.resilience.attempt` | timer | `AttemptCompleted` | `operation`, `outcome` |
| `vertique.resilience.retries` | counter | `RetryScheduled` | `operation` |
| `vertique.resilience.timeouts` | counter | `TimeoutTriggered` | `operation` |
| `vertique.resilience.circuit.transitions` | counter | `CircuitStateChanged` | `circuit`, `from`, `to` |
| `vertique.resilience.circuit.rejections` | counter | `CircuitCallRejected` | `operation`, `circuit`, `state` |
| `vertique.resilience.bulkhead.rejections` | counter | `BulkheadRejected` | `operation`, `mode` |
| `vertique.resilience.bulkhead.queue.wait` | timer | `BulkheadAdmitted`, `BulkheadQueueTimedOut` | `operation`, `outcome` |

Timer values are the event durations in milliseconds. Counter events increment once per event.
Enum labels use lowercase kebab-case (`circuit-breaker`, `timed-out`, and so on); queue-wait
outcomes are `admitted` and `timed-out`.

Only validated operation/state keys and closed enum values become labels. Execution IDs, attempt
ordinals, exception classes, queue depth, active count, configured capacities, and request data
are never labels. No gauges are emitted in this version: identical logical keys may exist in
isolated runtime scopes, so an aggregate gauge would imply a false single state.

See the common resilience module reference for the event and lifecycle contract and the Micrometer
core module reference for the shared `vertique.*` cardinality guard. This adapter adds `circuit`,
`from`, `to`, and `mode` to that frozen guarded-key union.

## Configuration

This adapter owns no configuration keys of its own. Enablement follows `metrics.enabled` from
`vertique-micrometer-core` (default enabled when the optional `MetricsConfig` binding is absent).

## Module Dagger Bindings

| Binding | Kind | Description |
|---|---|---|
| `MetricsConfig` | `@BindsOptionalOf` | Declared for adapter enablement checks; absent means enabled |
| `ResilienceObserver` | `@RegisterIntoSet` | The metrics observer, wired through the generated `GeneratedRegistrationsModule` this module includes |

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-resilience` | `ResilienceObserver` SPI and resilience events |
| `vertique-micrometer-core` | Shared `MeterRegistry`, `MetricsConfig`, and `vertique.*` cardinality guard |
| Micrometer | Timers, counters, tags, and registry APIs |
| Dagger | Module and optional-binding wiring |
