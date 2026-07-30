<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# OpenTelemetry REST Module

> **Status:** Alpha
> **Package:** `dev.vertique.opentelemetry.rest`
> **Artifact:** `vertique-opentelemetry-rest`
> **Depends on:** opentelemetry-api (library), opentelemetry-semconv (library), vertique-rest-core

Observe-only adapter that enriches the Vert.x-created HTTP server span with route and operationId
attributes for each dispatched REST operation. The module has no compile dependency on
`vertique-opentelemetry-core` types. It uses the OpenTelemetry API only — no SDK in compile scope —
so all span operations are guaranteed no-ops when no SDK is present. No configuration gate is
provided by design: when no recording span is active, the API's built-in no-op implementation
absorbs every call. The module never modifies the HTTP request or response, and it never submits
audit records.

---

## When To Use It

Install `vertique-opentelemetry-rest` alongside `RestCoreModule` (or `RestModule`) when distributed
tracing is enabled and HTTP server spans should carry OpenAPI route templates and operation ids.
Pair with `vertique-opentelemetry-core` to activate the Vert.x OTel tracer integration and the SDK
bootstrap. Without a recorded parent span the enrichment is a silent no-op on every request.

Do not install this module if no OTel tracer is wired into Vert.x — the span resolution via
`Span.current()` will always return the no-op span and the module has no visible effect. Installing
it is still safe in that case.

---

## Core Concepts

**Observe-only enrichment.** The module contributes three components into their respective
multibinding sets. No component creates new spans, starts new traces, or raises exceptions
toward the request pipeline — every callback body wraps span operations in a try/catch that logs at
WARN and swallows.

**Vert.x creates the HTTP server span; this module enriches it.** The Vert.x OTel tracing
integration (from `io.vertx:vertx-opentelemetry`) creates a server span for every incoming HTTP
request. This module renames that span to `"METHOD /route/template"` and adds the
`http.route` and `vertique.operation.id` attributes once the OpenAPI route is known.

**Band 300+ runs post-dispatch, post-auth.** `ServerSpanEnrichmentContributor` runs at priority 360
— after the auth handlers and after `OperationIdCaptureContributor` at 350. Requests rejected
before operation dispatch (auth failure, 404 routing miss) never reach this contributor. Those
requests keep the default Vert.x-assigned span name and do not receive the `http.route` or
`vertique.operation.id` attributes. Their span is still captured — `ServerSpanOutcomeInterceptor`
stashes it on the way in, at the API-router mount — so outcome recording and exemplar attachment
still work for them.

**Span-end vs. end-handler ordering.** The Vert.x OTel tracer ends the server span and detaches its
scope synchronously inside `conn.write()` — before any `ctx.addEndHandler` callback fires. As a
result, `Span.current()` returns the no-op span when completion listeners run. In-handler span
recordings (by both `ServerSpanEnrichmentContributor` and `ServerSpanOutcomeInterceptor`'s
`afterResponse` hook) attach to the active span correctly because those hooks fire pre-write.

**Exemplar attachment during completion dispatch.** `ServerSpanCompletionScope` (implements
`RequestCompletionScope`, contributed into the `Set<RequestCompletionScope>` multibinding via
`@IntoSet` in `OpenTelemetryRestModule`) bridges this gap: it retrieves the server span stashed
under `RestSpanKeys.SPAN_KEY` and calls `span.makeCurrent()` before the completion-listener
fan-out, then closes the returned OTel scope after all listeners run. The ended span's
`SpanContext` remains valid after the span is ended, so `makeCurrent()` re-establishes it as the
ambient OTel context; Micrometer exemplar samplers (e.g.
`io.prometheus.metrics.tracer.otel.OpenTelemetrySpanContext`) find it and attach a `trace_id` to
timer samples recorded in `RestRequestCompletedListener` implementations (e.g.
`RestServerRequestMetricsListener` in `vertique-micrometer-rest`).

**NFR-TEL-002 note.** `vertique-micrometer-rest` has no compile dependency on `opentelemetry-api`.
The `RequestCompletionScope` SPI lives in `vertique-rest-core` and the implementation lives in
`vertique-opentelemetry-rest`, keeping the micrometer-rest module free of any OTel compile
dependency.

---

## Key Classes

### ServerSpanEnrichmentContributor

`@Singleton` `OperationHandlerContributor`. Priority `360` — one step after
`OperationIdCaptureContributor` at `350`, which guarantees the operationId is captured before this
handler fires.

The contributor's `contribute(OperationRegistrationContext)` method captures the `operationId` and
`routeTemplate` (from `context.operation().routeTemplate()`) at registration
time and closes over them in the handler lambda. At request time the handler:

1. Resolves `Span.current()` and guards on `span.getSpanContext().isValid()`.
2. If the span context is valid: stores the span in the routing context under
   `RestSpanKeys.SPAN_KEY` (`"dev.vertique.opentelemetry.rest.serverSpan"`) for downstream
   interceptors.
3. If the span `isRecording()`: renames it to `rc.request().method().name() + " " + routeTemplate`
   (e.g. `"GET /orders/{id}"`), sets `HttpAttributes.HTTP_ROUTE` to the OpenAPI path template, and
   sets `RestSpanKeys.VERTIQUE_OPERATION_ID` (`"vertique.operation.id"`) to the operationId.
   Attribute writes are conditional on the values being non-null.
4. Always calls `rc.next()` — enrichment failure never breaks the pipeline.

```java
@Component(modules = {
    VertxModule.class,
    OpenTelemetryModule.class,   // vertique-opentelemetry-core
    RestModule.class,
    OpenTelemetryRestModule.class,
    AppModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

#### Invariants and Gotchas

- This contributor is not the only writer of `RestSpanKeys.SPAN_KEY`:
  `ServerSpanOutcomeInterceptor.onRequest` already stashed the span when the request entered the API
  router. Both writers store only when `span.getSpanContext().isValid()` and both resolve
  `Span.current()`, so the key holds the same server span either way. Rely on the key being populated
  for any request that reaches the API router with a valid span — including one rejected before
  dispatch, which this contributor never sees.
- When the key is present, `ServerSpanOutcomeInterceptor` reads the span from routing context data
  instead of making a second `Span.current()` call. If the key is absent (no valid span on the
  request at all), it falls back to `Span.current()`.
- `rc.next()` is called unconditionally outside the try-block. An exception from the enrichment
  code path is logged at WARN and does not prevent the next handler from running.
- The span name update (`updateName`) rewrites whatever name Vert.x initially assigned to the span.
  Calling this contributor from priority 360 means the Vert.x-assigned name is already the HTTP
  method + path; this contributor replaces the path portion with the OpenAPI template.

### ServerSpanOutcomeInterceptor

`@Singleton` `RequestInterceptor`. Records the span outcome (status code and error type) on the
active server span after each HTTP response, and captures the span early so that requests rejected
before dispatch are still covered. It implements three of the SPI's synchronous observer hooks:

- **`onRequest`** — stashes the current span under `RestSpanKeys.SPAN_KEY` as the request enters the
  API router, before auth and before operation dispatch. This is what gives pre-dispatch outcomes
  (401 auth rejects, 404 route misses inside the API router) a usable span, since they never reach
  `ServerSpanEnrichmentContributor`. Skipped when the key is already set or the current span context
  is invalid. Nothing is recorded on the span here.
- **`onError`** — records `error.type` when a failure enters the error pipeline.
- **`afterResponse`** — records the span status, and `error.type`, from the final HTTP status code.

The interceptor never creates or ends a span, and never modifies the request or response.

**Span resolution.** For `onError` and `afterResponse`, the interceptor first looks for a span stored
under `RestSpanKeys.SPAN_KEY` — placed there either by its own `onRequest` hook or by
`ServerSpanEnrichmentContributor` at dispatch. If the key is absent it falls back to `Span.current()`.
When neither yields a valid span context, all operations are no-ops.

**Why pre-write hooks instead of the completion listener (SP-4 rationale).** The `onError` and
`afterResponse` hooks fire while the routing context and span are still active (pre-write,
synchronous). The alternative — a `RestRequestCompletedListener` completion
listener — fires after the response is committed and after the Vert.x tracer closes the span's OTel
scope. A completion listener has no `RoutingContext` and may run after the tracer has already ended
the server span. Using `onError`/`afterResponse` ensures the span is still recording when the
outcome is written.

`afterResponse` fires at *wire handoff*, so a streamed body may still be in flight when the span
outcome is recorded: a response that is later truncated is recorded here as the status the client was
sent. Wire completion is reported instead on `RestRequestCompletedEvent.wireFailureCode`, consumed by
metrics and audit. That does not change the pre-write rationale above — moving this interceptor to the
completion listener to catch truncations would lose the span entirely.

**Outcome recording rules:**

| Trigger | Span status | `error.type` attribute |
|---------|-------------|------------------------|
| `onError(rc, error)` | `StatusCode.UNSET` (unchanged) | `error.getClass().getSimpleName()` |
| `afterResponse` with status ≥ 500 | `StatusCode.ERROR` | Simple class name of throwable at `RequestInterceptor.ORIGINAL_ERROR_KEY`, if present |
| `afterResponse` with status 4xx | `StatusCode.UNSET` (unchanged) | Simple class name of throwable at `RequestInterceptor.ORIGINAL_ERROR_KEY`, if present |
| `afterResponse` with status 2xx/3xx | `StatusCode.UNSET` (unchanged) | Not set by this hook |

**Span status is decided only in `afterResponse`, from the final HTTP status code.** `onError` fires
while the failure is still travelling the error pipeline, before it has been mapped to a status, so
it records `error.type` for attribution and deliberately leaves the status alone — the exception may
map to a 4xx, and a client fault must not mark the server span as an error per HTTP semconv. `error.type`
is written on 4xx as well as 5xx, so a mapped client error is still attributable without the span
being flagged as a server failure.

`Span.recordException` is never called. Exception events are not emitted — only the span status
and `error.type` attribute are written. This is the OTel semantic conventions recommendation for
HTTP server spans (exception detail lives in the status description, not span events).

All operations are guarded against exceptions so that span recording failures never affect the HTTP
response pipeline.

#### Invariants and Gotchas

- `onError` fires when a failure enters the error pipeline. It receives the original throwable
  before any exception mapping. The `error.type` attribute is the raw exception's simple class
  name, not the mapped response type.
- `afterResponse` fires after all response transformations. A 500 response resulting from an
  exception that was mapped by `RestExceptionMapper` still carries the original throwable at
  `RequestInterceptor.ORIGINAL_ERROR_KEY` if the error pipeline preserved it.
- Both outcome hooks may run for the same request (e.g., `onError` fires and then `afterResponse`
  fires with a 500). They do not compete: only `afterResponse` ever calls `setStatus`, so there is no
  second status write to reconcile. `error.type` is written by both, with the same value — `onError`
  uses the raw throwable and `afterResponse` reads that same throwable back from
  `RequestInterceptor.ORIGINAL_ERROR_KEY`.

### ServerSpanCompletionScope

`@Singleton` implementation of `RequestCompletionScope` (from `vertique-rest-core`). Package-private — contributed into the `Set<RequestCompletionScope>` multibinding via `@IntoSet` in `OpenTelemetryRestModule`.

Re-establishes the HTTP server span as the current OTel span for the duration of the completion-listener dispatch loop, so that Micrometer exemplar samplers can attach a `trace_id` to timer samples recorded in `RestRequestCompletedListener` implementations.

**Mechanism.** The Vert.x OTel tracer ends the server span and detaches its scope before any end handler fires. However, the ended span's `SpanContext` remains valid — `span.makeCurrent()` re-attaches it to the OTel context thread-local. The span is stashed in the routing context under `RestSpanKeys.SPAN_KEY` (`"dev.vertique.opentelemetry.rest.serverSpan"`) before the response is sent — by `ServerSpanOutcomeInterceptor.onRequest` when the request enters the API router, and again by `ServerSpanEnrichmentContributor` at dispatch; `ServerSpanCompletionScope.open()` retrieves it from that key.

**`open(RoutingContext)` behavior:**
- Retrieves the value at `RestSpanKeys.SPAN_KEY` from the routing context.
- If the value is a `Span` whose `SpanContext.isValid()` returns `true`: calls `span.makeCurrent()` and returns the resulting OTel `Scope` as the `AutoCloseable`.
- Otherwise (missing key, wrong type, invalid span context, or any exception): returns a no-op `AutoCloseable`.

`close()` delegates to the OTel `Scope.close()`, which pops the span from the OTel context thread-local.

#### Invariants & Gotchas

- The scope is only opened when a valid stashed span is present. Requests rejected before dispatch (auth failure, 404 miss inside the API router) **do** get a real scope: `ServerSpanOutcomeInterceptor.onRequest` stashed the span on the way in, before either rejection could occur.
- The remaining gap is requests short-circuited at the root router, before the API router is reached — those never reach any `RequestInterceptor`, so nothing stashes a span and the completion scope is a no-op. Untraced requests (no valid span) are a no-op for the same reason. In both cases completion listeners still run; only the span context is missing, so their samples carry no exemplar.
- No new span is created or started. The scope merely re-attaches an already-ended span's context.
- Both `open()` and `close()` catch all exceptions and fall back to a no-op, satisfying the `RequestCompletionScope` contract.
- `vertique-micrometer-rest` has no OTel compile dependency; the exemplar bridge is entirely on the otel-rest side.

---

## Span Attributes

| Attribute | Key | Source | Notes |
|-----------|-----|--------|-------|
| Span name | — | `"METHOD /route/template"`, e.g. `"GET /orders/{id}"` | Set by `ServerSpanEnrichmentContributor`; overwrites Vert.x default |
| `http.route` | `HttpAttributes.HTTP_ROUTE` | OpenAPI path template from `AbsoluteOpenAPIPath` | Not set when template is null |
| `vertique.operation.id` | `AttributeKey.stringKey("vertique.operation.id")` | OpenAPI operationId | Not set when operationId is null |
| `error.type` | `ErrorAttributes.ERROR_TYPE` | Exception simple class name | Set on error-pipeline entry, and on any 4xx or 5xx response carrying `RequestInterceptor.ORIGINAL_ERROR_KEY` |

---

## Wiring

`OpenTelemetryRestModule` is a Dagger `@Module` that contributes:

- `ServerSpanEnrichmentContributor` into `Set<OperationHandlerContributor>`
- `ServerSpanOutcomeInterceptor` into `Set<RequestInterceptor>`
- `ServerSpanCompletionScope` into `Set<RequestCompletionScope>` via `@IntoSet` (satisfies the `@Multibinds Set<RequestCompletionScope>` declared in `RestCoreModule`)

Install it alongside `RestModule` (or `RestCoreModule`) and `OpenTelemetryModule`:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    OpenTelemetryModule.class,       // vertique-opentelemetry-core: SDK bootstrap
    RestModule.class,                // vertique-rest-jaxrs: JAX-RS routing runtime
    OpenTelemetryRestModule.class,   // vertique-opentelemetry-rest: this module
    AppModule.class,
    ResourceModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

`OpenTelemetryRestModule` has no `@BindsOptionalOf` declarations. It compiles and wires correctly
with or without `OpenTelemetryModule` on the Dagger graph — enrichment is always attempted and
silently becomes a no-op when no recording span is present.

---

## Dependencies

- `io.opentelemetry:opentelemetry-api` — `Span`, `SpanContext`, `StatusCode`, `AttributeKey`
- `io.opentelemetry:opentelemetry-semconv` — `HttpAttributes`, `ErrorAttributes`
- `dev.vertique:vertique-rest-core` — `OperationHandlerContributor`, `OperationRegistrationContext`,
  `RequestInterceptor`
- `com.google.dagger:dagger`, `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`, `org.projectlombok:lombok` (provided)
