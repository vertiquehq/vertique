<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Micrometer REST Module

> **Status:** Alpha
> **Package:** `dev.vertique.micrometer.rest`
> **Artifact:** `vertique-micrometer-rest`
> **Depends on:** io.micrometer:micrometer-core (library), vertique-rest-core

Observe-only REST server metrics adapter. When installed alongside `RestCoreModule` (or `RestModule`)
and `MicrometerModule`, it emits a per-request timer (`vertique.rest.server.requests`) and an
in-flight-requests gauge (`vertique.rest.server.active`) for every completed HTTP server request.

The module has no compile dependency on `vertique-micrometer-core` types. It does, however,
**require a `MeterRegistry` binding on the Dagger graph** — normally supplied by `MicrometerModule`;
without it (or another `MeterRegistry` provider) the component does not compile. Only the
`metrics.enabled` *gate* is optional: the module declares `@BindsOptionalOf MetricsConfig`
independently, so the gate defaults to enabled when `MicrometerModule` is absent. It never
modifies the request or response, and it never submits audit records.

---

## When To Use It

Install `vertique-micrometer-rest` whenever an application exposes an HTTP server via
`RestModule`/`RestCoreModule` and metrics are enabled. Pair it with `vertique-micrometer-core` and
at least one backend module (e.g., `vertique-micrometer-registry-prometheus`) to make the emitted
meters visible. Without a backend the injected `MeterRegistry` is an empty composite whose recording
is a no-op.

---

## Core Concepts

**Observe-only.** The module contributes a `RestRequestCompletedListener` and a `RequestInterceptor`
into their respective multibinding sets. Neither component touches the response or raises exceptions
toward the caller — every callback body is wrapped in a try/catch that logs at WARN and swallows.

**Active gauge design (SP-5 rationale).** The original PRD design paired `onRequest` with
`afterResponse`. That pairing leaks: WebSocket upgrades and bare-metal 500s where the error pipeline
never fires both produce an `onRequest` with no matching `afterResponse`, leaving the gauge
permanently inflated. The implementation instead uses `onRequest` to increment and `rc.addEndHandler`
to decrement. The end-handler fires on every exit path — success, error, and connection close —
making the pair exhaustive.

**WebSocket exclusion.** Upgrade requests (`Upgrade: websocket` header) are excluded from both the
gauge and the timer. WebSocket channel lifetime is owned by the channel lifecycle, not the HTTP
dispatch layer.

**HTTP/2 extended-CONNECT limitation.** HTTP/2 extended-CONNECT (used by gRPC, WebTransport) is not
detected; those requests are counted as regular requests. This is a documented limitation.

**Auth-rejected and pre-dispatch requests.** When a request is rejected before operation dispatch
(auth failure, 404 routing miss), the `route` and `operation` tags carry `UNKNOWN`. Tag enrichment
runs post-auth via `OperationIdCaptureContributor`; tags are unavailable at rejection time.

**Per-event registry lookup (no meter cache, D-L).** Both adapters call
`Timer.builder(...).tags(...).register(registry)` on every event. Micrometer's internal registry
lookup is the cache — this is consistent with Spring Boot Actuator and the Vert.x Micrometer
integration. A separate tuple-keyed adapter cache was evaluated and rejected because the
drop-on-saturation variant silently stops recording real time series once legitimate tag combinations
exceed any fixed cap.

**Zero-overhead when unconfigured.** Before `VertiqueApplication` bootstrap the injected
`MeterRegistry` is an empty composite whose recording is a no-op (NFR-TEL-003). When the optional
`MetricsConfig` binding is absent (i.e., `MicrometerModule` is not installed), both components
default to enabled — they record against whatever registry is injected.

---

## Key Classes

### MicrometerRestModule

Dagger `@Module`. Contributes two bindings:

- `RestServerRequestMetricsListener` into `Set<RestRequestCompletedListener>` — records the
  per-request timer on each completed HTTP request.
- `RestServerActiveRequestsInterceptor` into `Set<RequestInterceptor>` — maintains the
  in-flight-requests gauge.

Also declares `@BindsOptionalOf MetricsConfig metricsConfig()` so both
components can inject `Optional<MetricsConfig>` without a compile dependency on `vertique-micrometer-core`.
When `MicrometerModule` is also installed its `@Provides MetricsConfig` binding satisfies
the optional; when absent the optional is empty and both components default to enabled.

```java
@Component(modules = {
    VertxModule.class,
    RestModule.class,
    MicrometerModule.class,
    MicrometerRestModule.class,
    // ...
})
interface AppComponent { /* ... */ }
```

### RestServerRequestMetricsListener

`@Singleton` `RestRequestCompletedListener`. Records one timer sample per completed HTTP request.
Meter name: `vertique.rest.server.requests`. When `MetricsConfig.enabled()` is `false` (i.e.,
`metrics.enabled=false` in config), returns immediately without recording.

### RestServerActiveRequestsInterceptor

`@Singleton` `RequestInterceptor`. Maintains the in-flight-requests gauge. Meter name:
`vertique.rest.server.active`. The gauge is backed by a `LongAdder` registered at construction time.

On `onRequest`:

1. Skips WebSocket upgrade requests (`Upgrade: websocket` header).
2. Guards against double-increment using a routing-context key
   (`vertique.micrometer.rest.activeRequestCounted`).
3. Increments the `LongAdder`.
4. Registers an idempotent `rc.addEndHandler` using a per-request `AtomicBoolean` that ensures the
   decrement fires at most once, even if the end-handler is invoked multiple times.

#### Invariants and Gotchas

- The gauge is registered at construction time (not lazily); if the registry throws during
  registration the exception is caught, logged at WARN, and the interceptor still starts (with the
  gauge absent).
- `onRequest` must be called from the `RequestInterceptor` multibinding hook in the router pipeline
  (e.g., by `JaxRsRouterMount`). The method is not invoked by the framework outside the HTTP server
  request path.
- The double-entry guard exists because `onRequest` could theoretically be called more than once on
  the same `RoutingContext`; the guard ensures the gauge is incremented at most once per context.

---

## Meters

### `vertique.rest.server.requests` — Timer

Per-request timer. One sample is recorded per `RestRequestCompletedEvent`.

| Tag | Values | Notes |
|-----|--------|-------|
| `method` | HTTP method string, e.g. `GET` | Falls back to `UNKNOWN` when unavailable |
| `route` | OpenAPI path template, e.g. `/orders/{id}` | `UNKNOWN` for auth-rejected and pre-dispatch requests |
| `operation` | OpenAPI operationId | `UNKNOWN` for auth-rejected and pre-dispatch requests |
| `status` | HTTP response status code as a string, e.g. `200` | Always a numeric string |
| `outcome` | Low-cardinality bucket: `INFORMATIONAL`, `SUCCESS`, `REDIRECTION`, `CLIENT_ERROR`, `SERVER_ERROR`, `UNKNOWN` | Derived by integer division of the status code by 100; status 0 or outside 100–599 → `UNKNOWN` |
| `error.type` | `failureCode`, else `wireFailureCode`, else `none` | Simple class name of the pipeline-mapped failure (e.g. `IllegalStateException`); when absent, falls back to the post-handoff wire-failure classification on `RestRequestCompletedEvent` (e.g. `ConnectionClosed`) |

**`error.type` on a 200-status series.** Because `error.type` falls back to `wireFailureCode`, a
timer sample tagged `status=200` MAY carry a non-`none` `error.type` — that combination (`status`
200 with a non-`none` `error.type`) is the truncated-response signature: the client received a 200
response head, but the wire write failed after handoff (a mid-stream failure or a client abort).
See `wireFailureCode` on `RestRequestCompletedEvent` in `vertique-rest-core`'s module reference for
the derivation and the close-normalization predicate.

Outcome mapping (class `HttpOutcome`, package-private):

| Status range | `outcome` value |
|---|---|
| 1xx | `INFORMATIONAL` |
| 2xx | `SUCCESS` |
| 3xx | `REDIRECTION` |
| 4xx | `CLIENT_ERROR` |
| 5xx | `SERVER_ERROR` |
| other / 0 | `UNKNOWN` |

Prometheus rendering: `vertique_rest_server_requests_seconds_count` /
`vertique_rest_server_requests_seconds_sum` / `vertique_rest_server_requests_seconds_bucket`.

### `vertique.rest.server.active` — Gauge

Instantaneous count of in-flight HTTP server requests. Untagged. Backed by a `LongAdder`.

Prometheus rendering: `vertique_rest_server_active`.

---

## Dependencies

- `io.micrometer:micrometer-core` — `MeterRegistry`, `Timer`, `Gauge`, `Tags`; no Vert.x Micrometer
  integration types. This is the only Micrometer dependency; the module has no compile dependency on
  `vertique-micrometer-core`.
- `dev.vertique:vertique-rest-core` — `RestRequestCompletedListener`, `RestRequestCompletedEvent`,
  `RequestInterceptor`, `RestRequestCompletionEmitter` constants.
- `com.google.dagger:dagger`, `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`, `org.projectlombok:lombok` (provided)

---

## Related ADRs

- ADR-0098: Micrometer Facade and Pluggable Registry Backends — establishes the `@BindsOptionalOf` adapter dependency rule (D-J) that allows this module to compile without `vertique-micrometer-core`, and the publish-on-success bootstrap contract that guarantees the injected registry is always non-null.
- ADR-0099: Metric Naming, Tag, and Cardinality Policy — establishes the `vertique.*` naming scheme, the `UNKNOWN`/`none` sentinel convention, the `GUARDED_TAG_KEYS` frozen list that covers `route`, `operation`, `method`, `status`, `outcome`, and `error.type`, and the per-event registry lookup policy (no adapter cache).
