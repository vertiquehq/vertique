<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Micrometer REST Module

> **Status:** Stable
> **Package:** `dev.vertique.micrometer.rest`
> **Artifact:** `vertique-micrometer-rest`
> **Depends on:** micrometer-core, rest-core, core, codegen-core

Observe-only REST server metrics adapter. When installed alongside `RestCoreModule` (or `RestModule`)
and `MicrometerModule`, it emits a per-request timer (`vertique.rest.server.requests`) that covers
every JAX-RS operation request, and an in-flight-requests gauge (`vertique.rest.server.active`).

The module compiles against `vertique-micrometer-core` for the `MetricsConfig` type. It also
**requires a `MeterRegistry` binding on the Dagger graph** — normally supplied by `MicrometerModule`;
without it (or another `MeterRegistry` provider) the component does not compile. Only the
`metrics.enabled` *gate* is optional: the module declares `@BindsOptionalOf MetricsConfig`
independently, so the gate defaults to enabled when `MicrometerModule` is absent. It never
modifies the request or response, and it never submits audit records. It owns no module-local
configuration keys beyond following `metrics.enabled`.

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
into their respective multibinding sets. Neither contribution touches the response or raises
exceptions toward the caller — every callback body is wrapped in a try/catch that logs at WARN and
swallows.

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

**Auth-rejected and pre-dispatch requests.** A request denied after it matched a JAX-RS operation
route (401, 403, 415, or a validation 400) carries its real `route` and `operation` tags, because
the framework records the operation before authentication. A request that no JAX-RS operation
claimed is not timed: `ROOT` rejections, 404/405 routing misses, JAX-RS mount-level rejections
before any operation route matched, MCP requests, and failed WebSocket upgrades. The timer therefore
has no `UNKNOWN` `route` or `operation` series. For transport-wide counts, use Vert.x's native HTTP
server metrics (`metrics.vertx.httpServer` in `vertique-micrometer-core`, default `true` when its
Vert.x metrics are enabled).

**Per-event registry lookup (no meter cache, D-L).** Both adapters call
`Timer.builder(...).tags(...).register(registry)` on every event. Micrometer's internal registry
lookup is the cache — this is consistent with Spring Boot Actuator and the Vert.x Micrometer
integration. A separate tuple-keyed adapter cache was evaluated and rejected because the
drop-on-saturation variant silently stops recording real time series once legitimate tag combinations
exceed any fixed cap.

**Zero-overhead when unconfigured.** Before `VertiqueApplication` bootstrap the injected
`MeterRegistry` is an empty composite whose recording is a no-op (NFR-TEL-003). When the optional
`MetricsConfig` binding is absent (i.e., `MicrometerModule` is not installed), both contributions
default to enabled — they record against whatever registry is injected.

---

## Key Classes

### MicrometerRestModule

Abstract Dagger module. Install it explicitly to contribute one `RestRequestCompletedListener` and
one `RequestInterceptor` into their multibinding sets. The listener records the per-request timer on
each JAX-RS operation completion; the interceptor maintains the in-flight-requests gauge. The module
declares `@BindsOptionalOf MetricsConfig` so the `metrics.enabled` gate works with or without
`MicrometerModule`; when that optional is empty, recording defaults to enabled. The application must
still provide a `MeterRegistry` binding (normally from `MicrometerModule`).

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

---

## Meters

### `vertique.rest.server.requests` — Timer

Per-request timer. One sample is recorded per `RestRequestCompletedEvent`: JAX-RS operations and
framework synthetic operations (see **API document reads** below).

| Tag | Values | Notes |
|-----|--------|-------|
| `method` | HTTP method string, e.g. `GET` | Falls back to `UNKNOWN` when unavailable |
| `route` | OpenAPI path template, e.g. `/orders/{id}` | The operation's route template, `event.operation().routeTemplate()`; never `UNKNOWN` |
| `operation` | OpenAPI operationId | The operation's operationId, `event.operation().operationId()`; never `UNKNOWN` |
| `status` | HTTP response status code as a string, e.g. `200` | Always a numeric string |
| `outcome` | Low-cardinality bucket: `INFORMATIONAL`, `SUCCESS`, `REDIRECTION`, `CLIENT_ERROR`, `SERVER_ERROR`, `UNKNOWN` | Derived by integer division of the status code by 100; status 0 or outside 100–599 → `UNKNOWN` |
| `error.type` | `failureCode`, else `wireFailureCode`, else `none` | Simple class name of the pipeline-mapped failure (e.g. `IllegalStateException`); when absent, falls back to the post-handoff wire-failure classification on `RestRequestCompletedEvent` (e.g. `ConnectionClosed`) |

All tags above are part of `vertique-micrometer-core`'s growth-only cardinality-guarded tag-key set (it only grows; entries are never removed or reordered) —
each key is capped at `metrics.cardinality.maxTagValuesPerKey` distinct values (default `200`)
across the composite. See `vertique-micrometer-core`'s module reference for the guard mechanism.

**API document reads.** A read of a protected API document, and its `401` or `403` denial,
completes as a REST operation completion whose operation is a framework synthetic operation. It is
timed in `vertique.rest.server.requests` with `operation` `apidocs:<name>:json` or
`apidocs:<name>:yaml` and `route` `/<name>/openapi.json` or `/<name>/openapi.yaml`: the descriptor's
route template, the document path relative to the documentation prefix (`apidocs.path`), which it
does not include. `method` is the request's method, so a `HEAD` read is tagged `HEAD`. A public
document read is claimed by no operation route, completes as an `HttpRequestCompletedEvent`, and is
not timed there. Vert.x's native HTTP server metrics count document reads with every other request.

**No application tag yet.** The timer does not carry the application name (`rest.application`).
When one resource is mounted by more than one application with the same operationId and route, both
mounts produce identical tags and their series merge until the meter adds the `rest.application`
tag.

**`error.type` on a 200-status series.** Because `error.type` falls back to `wireFailureCode`, a
timer sample tagged `status=200` MAY carry a non-`none` `error.type` — that combination (`status`
200 with a non-`none` `error.type`) is the truncated-response signature: the client received a 200
response head, but the wire write failed after handoff (a mid-stream failure or a client abort).
The fallback inherits `wireFailureCode`'s coverage limit: a write failure that surfaces only on the
terminal `end()` (buffered, null-entity, or a stream's final `end()`) can settle after the
completion event was emitted, so `error.type` stays `none` for it even though the response pipeline
logs it. See `wireFailureCode` on `RestRequestCompletedEvent` in `vertique-rest-core`'s module
reference for the derivation, the close-normalization predicate, and the late-`end()` carve-out.

Outcome mapping:

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

## Module Dagger Bindings

| Type | Qualifier | Description |
|---|---|---|
| `MetricsConfig` | optional (`@BindsOptionalOf`) | Declared for the `metrics.enabled` gate |
| `RestRequestCompletedListener` | `@IntoSet` | Per-request timer contribution |
| `RequestInterceptor` | `@IntoSet` | In-flight gauge contribution |

The adapter's SPI contributions are declared on their injectable implementations with
`@RegisterIntoSet`. During the provider build, `vertique-codegen-dagger` emits
`GeneratedRegistrationsModule`, which this module includes explicitly. The generated module contains
only those type adaptations; the optional `MetricsConfig` binding remains hand-written.

---

## Verification

```bash
./mvnw -ntp -pl vertique-micrometer/vertique-micrometer-rest -am test
```

---

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-micrometer-core` | `MetricsConfig` / `metrics.enabled` gate |
| `vertique-rest-core` | Completion listener and request interceptor SPIs |
| `vertique-core` | `OrderedExtension` |
| `vertique-codegen-core` | Codegen support for generated registrations |
| `io.micrometer:micrometer-core` | `MeterRegistry`, `Timer`, `Gauge`, `Tags` |
