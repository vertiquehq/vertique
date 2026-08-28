<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Vertique Micrometer Resilience Adapter

> **Status:** Beta
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
