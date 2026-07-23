<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# OpenTelemetry Services Module

> **Status:** Alpha
> **Package:** `dev.vertique.opentelemetry.services`
> **Artifact:** `vertique-opentelemetry-services`
> **Depends on:** opentelemetry-api (library), vertique-services

Observe-only adapter that enriches the active OpenTelemetry CONSUMER span on the Vert.x event bus
with service dispatch attributes. The module uses the OpenTelemetry API only — no SDK in compile
scope — so all span operations are guaranteed no-ops when no SDK is present. No configuration gate
is provided by design: enrichment is always attempted and silently becomes a no-op when no recording
span is active.

The module has no compile dependency on `vertique-opentelemetry-core` types. It never modifies the
dispatch outcome, and it never submits audit records.

---

## When To Use It

Install `vertique-opentelemetry-services` alongside `DispatchModule` when distributed tracing is
enabled and service dispatch spans should carry service target and one-way attributes. Pair with
`vertique-opentelemetry-core` to activate the Vert.x OTel tracer integration and the SDK bootstrap.

Without a recorded parent span (Vert.x `DeliveryOptions.PROPAGATE` default), no event-bus spans are
created and the enrichment interceptor is a silent no-op on every dispatch.

---

## Core Concepts

**Consumer-side enrichment only.** No sender-side seam exists in the event bus dispatch pipeline.
The Vert.x OTel tracing integration creates a CONSUMER span on the receiving side when a parent
trace is propagated with the message (propagation mode: `PROPAGATE`, which is the default). The
interceptor enriches that CONSUMER span — not the sender-side span — because `ServiceInterceptor`
callbacks run within the consumer's message handler, where the span is current.

**Producer enrichment is descoped (SP-7).** A sender-side seam would require intercepting the
`EventBus.send/publish` call before the message leaves the event loop. No such seam exists in the
current `ServiceInterceptor` SPI; `onDispatch` fires on the consumer side once the message is
received. Producer-side span enrichment is therefore not supported and is not emitted.

**Spans exist only under a traced parent.** Vert.x's default `PROPAGATE` tracing policy means the
framework creates event-bus spans only when a parent trace is in scope at dispatch time. A dispatch
with no parent span produces zero event-bus spans. The interceptor's `Span.isRecording()` guard
ensures it is a silent no-op in that case.

**`stableTargetId` omission — never `UNKNOWN` on spans.** The `vertique.service.target` attribute
is written only when `ServiceDispatchContext.stableTargetId()` is non-null and non-blank. When the
target id is absent, the attribute is omitted entirely. Writing a sentinel like `"UNKNOWN"` on span
attributes creates unbounded cardinality risk in trace backends and is never done.

**Terminal-outcome recording is best-effort.** `ServiceInterceptor.onTerminalComplete` fires after
the reply is sent to the caller. By that point the Vert.x OTel tracer may have already ended the
CONSUMER span. Writes to an ended span are safe no-ops per the OpenTelemetry API contract; the
status recording is best-effort and may be silently dropped.

---

## Key Classes

### ServiceDispatchSpanEnrichmentInterceptor

`@Singleton` `ServiceInterceptor`. Enriches the active CONSUMER span with service dispatch
attributes on `onDispatch`, and records the terminal outcome on `onTerminalComplete`.

**`onDispatch(ServiceDispatchContext ctx)`**

Called synchronously when the service method is about to be invoked. Sets:
- `ServiceAttributes.SERVICE_ONEWAY` (`"vertique.service.oneway"`, `boolean`) — always written when
  a recording span is current; `true` for `@OneWay` dispatches, `false` for request/reply.
- `ServiceAttributes.SERVICE_TARGET` (`"vertique.service.target"`, `string`) — set to
  `ctx.stableTargetId()` only when the value is non-null and non-blank. Omitted entirely when
  `stableTargetId()` returns `null`.

When no recording span is current (no parent trace or PROPAGATE policy with no parent), both writes
are skipped. Exceptions are swallowed — this observer cannot affect the dispatch outcome.

**`onTerminalComplete(ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime)`**

Called after the terminal dispatch outcome is reached (post-recovery). Records:
- On `result.isFailure()`: sets span status to `StatusCode.ERROR` and sets
  `ErrorAttributes.ERROR_TYPE` (`"error.type"`) to `result.cause().getClass().getSimpleName()` when
  the cause is non-null.
- On success: span status is left `StatusCode.UNSET`; no writes.

`Span.recordException` is never called. Exception events are not emitted — only the status and
`error.type` attribute are written.

Writes to an already-ended span are safe no-ops per the OTel API contract (best-effort contract:
the reply is sent before this hook fires, so the tracer may have already closed the span).

Exceptions are swallowed — this observer cannot affect the dispatch outcome.

```java
// Traced request dispatched from inside a parent span's scope.
// The parent span's context is propagated by Vert.x through the event-bus message headers.
// On the consumer side, the OTel tracer creates a CONSUMER child span.
// ServiceDispatchSpanEnrichmentInterceptor enriches that child span.
Span parentSpan = tracer.spanBuilder("my-operation").startSpan();
try (Scope scope = parentSpan.makeCurrent()) {
    vertx.eventBus()
         .request(address, DispatchEnvelope.of(payload), ENVELOPE_CODEC)
         .onComplete(ar -> { /* ... */ });
}
// The CONSUMER span (on the receiving side) will carry:
//   vertique.service.target = "integration.user-service.get-user"  (if stableTargetId non-null)
//   vertique.service.oneway = false
```

#### Invariants and Gotchas

- Both `onDispatch` and `onTerminalComplete` resolve `Span.current()` independently. The span
  retrieved in `onDispatch` may differ from the one in `onTerminalComplete` if the OTel context
  has changed. Both are guarded on `span.getSpanContext().isValid() && span.isRecording()`.
- When `stableTargetId()` is `null`, the `vertique.service.target` attribute is absent from the
  span. Backends that expect the attribute will not find it; this is intentional to avoid polluting
  span data with a meaningless sentinel.
- `onTerminalComplete` fires after the reply is already sent. The CONSUMER span may already be
  ended by the time this hook runs. The OTel API's no-op contract for ended spans ensures this is
  safe but means the status recording is best-effort, not guaranteed.

---

## Span Attributes

| Attribute | Key | Source | Notes |
|-----------|-----|--------|-------|
| `vertique.service.target` | `AttributeKey.stringKey("vertique.service.target")` | `ServiceDispatchContext.stableTargetId()` | Omitted when `stableTargetId()` is null or blank |
| `vertique.service.oneway` | `AttributeKey.booleanKey("vertique.service.oneway")` | `ServiceDispatchContext.oneWay()` | Always written when a recording span is current |
| `error.type` | `ErrorAttributes.ERROR_TYPE` | `result.cause().getClass().getSimpleName()` | Set only on terminal failure with a non-null cause |

---

## Wiring

`OpenTelemetryServicesModule` is a Dagger `@Module` that contributes one multibinding:

- `ServiceDispatchSpanEnrichmentInterceptor` into `Set<ServiceInterceptor>`

Install it alongside `DispatchModule` and `OpenTelemetryModule`:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    OpenTelemetryModule.class,           // vertique-opentelemetry-core: SDK bootstrap
    DispatchModule.class,                // vertique-services: event bus dispatch
    OpenTelemetryServicesModule.class,   // vertique-opentelemetry-services: this module
    AppModule.class
})
interface AppComponent {
    ServiceVerticle serviceVerticle();
}
```

`OpenTelemetryServicesModule` has no `@BindsOptionalOf` declarations. It compiles and wires
correctly with or without `OpenTelemetryModule` on the Dagger graph — enrichment is always
attempted and silently becomes a no-op when no recording span is present.

---

## Dependencies

- `io.opentelemetry:opentelemetry-api` — `Span`, `SpanContext`, `StatusCode`, `AttributeKey`
- `io.opentelemetry:opentelemetry-semconv` — `ErrorAttributes`
- `dev.vertique:vertique-services` — `ServiceInterceptor`, `ServiceDispatchContext`
- `dev.vertique:vertique-core` — `Result` (via `core.eventbus`)
- `com.google.dagger:dagger`, `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`, `org.projectlombok:lombok` (provided)

---

## Related ADRs

- ADR-0098: Micrometer Facade and Pluggable Registry Backends — establishes the telemetry-module bootstrap pattern and the observe-only, compile-API-only adapter model that this module follows.
- ADR-0101: Trace-Log Correlation via TraceReferenceResolver — establishes the `TraceReferenceResolver` boundary and the validity-only span-context check; the same validity guard (`SpanContext.isValid()`) is used in this module.
