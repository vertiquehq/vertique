<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# OpenTelemetry REST Module

> **Status:** Stable
> **Package:** `dev.vertique.opentelemetry.rest`
> **Artifact:** `vertique-opentelemetry-rest`
> **Depends on:** rest-core, core, codegen-core

Observe-only adapter that enriches the Vert.x-created HTTP server span with route and operationId
attributes for each dispatched REST operation. The module has no compile dependency on
`vertique-opentelemetry-core` types. It uses the OpenTelemetry API only — no SDK in compile scope —
so all span operations are guaranteed no-ops when no SDK is present. No configuration gate is
provided by design: when no recording span is active, the API's built-in no-op implementation
absorbs every call. The module owns no module-local configuration keys. It never modifies the HTTP
request or response, and it never submits audit records.

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

**Observe-only enrichment.** The module contributes an `OperationHandlerContributor`, a
`RequestInterceptor`, and a `RequestCompletionScope` into their respective multibinding sets. No
contribution creates new spans, starts new traces, or raises exceptions toward the request pipeline
— every callback body wraps span operations in a try/catch that logs at WARN and swallows.

**Vert.x creates the HTTP server span; this module enriches it.** The Vert.x OTel tracing
integration (from `io.vertx:vertx-opentelemetry`) creates a server span for every incoming HTTP
request. This module renames that span to `"METHOD /route/template"` and adds the
`http.route` and `vertique.operation.id` attributes once the OpenAPI route is known, plus
`vertique.application.name` (only for operations of a named application).

**Band 300+ runs post-dispatch, post-auth.** The enrichment contributor runs at priority 360 —
after the auth handlers and the authorization contributors. It captures the operation id, route
template, and application name once, at registration, so it does not depend on any other
contributor running first. Requests rejected before operation dispatch (auth failure, 404 routing
miss) never reach this contributor. Those requests keep the default Vert.x-assigned span name and
do not receive the `http.route`, `vertique.operation.id`, or `vertique.application.name`
attributes. Their span is still captured — the outcome interceptor stashes it on the way in, at the
API-router mount — so outcome recording and exemplar attachment still work for them.

**Span-end vs. end-handler ordering.** The Vert.x OTel tracer ends the server span and detaches its
scope synchronously inside `conn.write()` — before any `ctx.addEndHandler` callback fires. As a
result, `Span.current()` returns the no-op span when completion listeners run. In-handler span
recordings (enrichment and the outcome interceptor's `afterResponse` hook) attach to the active
span correctly because those hooks fire pre-write.

**Exemplar attachment during completion dispatch.** A `RequestCompletionScope` contribution bridges
this gap: it retrieves the server span stashed for the request and calls `span.makeCurrent()` before
the completion-listener fan-out, then closes the returned OTel scope after all listeners run. The
ended span's `SpanContext` remains valid after the span is ended, so `makeCurrent()` re-establishes
it as the ambient OTel context; Micrometer exemplar samplers (e.g.
`io.prometheus.metrics.tracer.otel.OpenTelemetrySpanContext`) find it and attach a `trace_id` to
timer samples recorded in `RestRequestCompletedListener` implementations (including
`vertique-micrometer-rest`).

**NFR-TEL-002 note.** `vertique-micrometer-rest` has no compile dependency on `opentelemetry-api`.
The `RequestCompletionScope` SPI lives in `vertique-rest-core` and the implementation lives in
`vertique-opentelemetry-rest`, keeping the micrometer-rest module free of any OTel compile
dependency.

**Inert when no span.** When `Span.current()` is the no-op span (no SDK / no Vert.x tracer), every
enrichment and outcome write is a silent no-op. The module still wires and is safe to install.

---

## Key Classes

### OpenTelemetryRestModule

Abstract Dagger module. Install it explicitly to contribute REST server-span enrichment, outcome
recording, and completion-scope exemplar bridging. It includes `GeneratedRegistrationsModule` for
`@RegisterIntoSet` contributions. It declares no `@BindsOptionalOf` gates and owns no configuration
keys — enrichment is always attempted and silently becomes a no-op when no recording span is
present.

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    OpenTelemetryModule.class,       // vertique-opentelemetry-core: SDK bootstrap
    RestModule.class,                // vertique-rest-jaxrs: JAX-RS routing runtime
    OpenTelemetryRestModule.class,   // vertique-opentelemetry-rest: this module
    AppModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

---

## Span Attributes

| Attribute | Key | Source | Notes |
|-----------|-----|--------|-------|
| Span name | — | `"METHOD /route/template"`, e.g. `"GET /orders/{id}"` | Set at operation dispatch; overwrites Vert.x default |
| `http.route` | `HttpAttributes.HTTP_ROUTE` | OpenAPI path template | Not set when template is null |
| `vertique.operation.id` | `vertique.operation.id` | OpenAPI operationId | Not set when operationId is null |
| `vertique.application.name` | `vertique.application.name` | The operation's application name | Set only for operations of a named REST application; absent otherwise. Distinct from the resource attribute `service.name` |
| `error.type` | `ErrorAttributes.ERROR_TYPE` | Exception simple class name | Set on error-pipeline entry, and on any 4xx or 5xx response carrying the original error |

Outcome recording rules:

| Trigger | Span status | `error.type` attribute |
|---------|-------------|------------------------|
| Error-pipeline entry | `StatusCode.UNSET` (unchanged) | Exception simple class name |
| Response status ≥ 500 | `StatusCode.ERROR` | Original error simple class name, if present |
| Response status 4xx | `StatusCode.UNSET` (unchanged) | Original error simple class name, if present |
| Response status 2xx/3xx | `StatusCode.UNSET` (unchanged) | Not set by the outcome hook |

`Span.recordException` is never called. Exception events are not emitted — only the span status and
`error.type` attribute are written.

---

## Module Dagger Bindings

| Type | Qualifier | Description |
|---|---|---|
| `OperationHandlerContributor` | `@IntoSet` | Post-auth span rename and route/operation attributes |
| `RequestInterceptor` | `@IntoSet` | Early span stash + outcome / `error.type` recording |
| `RequestCompletionScope` | `@IntoSet` | Re-attaches the server span for completion-listener exemplars |

The adapter's SPI contributions are declared on their injectable implementations with
`@RegisterIntoSet`. During the provider build, `vertique-codegen-dagger` emits
`GeneratedRegistrationsModule`, which this module includes explicitly.

---

## Verification

```bash
./mvnw -ntp -pl vertique-opentelemetry/vertique-opentelemetry-rest -am test
```

---

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-rest-core` | Operation contributor, request interceptor, and completion-scope SPIs |
| `vertique-core` | Shared extension ordering |
| `vertique-codegen-core` | Codegen support for generated registrations |
| `io.opentelemetry:opentelemetry-api` | `Span`, `SpanContext`, `StatusCode`, `AttributeKey` |
| `io.opentelemetry:opentelemetry-semconv` | `HttpAttributes`, `ErrorAttributes` |
