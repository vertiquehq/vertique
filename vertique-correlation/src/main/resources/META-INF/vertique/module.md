<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# vertique-correlation

> **Status:** Implemented
> **Package:** `dev.vertique.correlation`
> **Artifact:** `vertique-correlation`
> **Depends on:** `vertique-core`, `vertique-context`, `vertique-logging`

Runtime layer for the framework's correlation feature. Owns the holder-bound
`CorrelationContext` implementation, factory, mutator with selective MDC mirroring, default
`CorrelationIdGenerator`, the substrate `ContextValueAdapter` for snapshot/restore/duplicate,
and the Dagger module that wires it all together. The shared value/contract types (e.g.
`TraceReference`, `CorrelationContext`) live in `dev.vertique.core.correlation` (in `vertique-core`);
this module's own runtime types and SPIs (e.g. `CorrelationIdGenerator`, `TraceReferenceResolver`)
live in `dev.vertique.correlation`; REST-specific ingress lives in `dev.vertique.rest.core.correlation`
(in `vertique-rest-core`).

---

## Overview

`CorrelationContext` is the live request-local working state for correlation — exposed as a
public read-only interface in `vertique-core`, backed by the package-private
`MutableCorrelationContext` in this module. Framework enrichers (REST ingress, security,
tracing) extend it in place via the Dagger-injected `CorrelationContextMutator`; immutability
belongs to `CorrelationContextSnapshot`, the boundary type that encoders capture at dispatch
and durable handoffs.

The substrate (`vertique-context`) treats `CorrelationContext` like any other holder value: it
is bound under `CorrelationContext.class.getName()` and snapshot/restored across Vert.x
duplicated contexts via `CorrelationContextValueAdapter`, which is discovered through Java
`ServiceLoader`.

## Key Classes

| Class | Role |
|-------|------|
| `MutableCorrelationContext` (package-private) | Live impl of the public `CorrelationContext` interface. Vert.x duplicated-context confined — plain fields + `ArrayList`/`HashMap`; no synchronisation. `snapshot()` takes defensive `List.copyOf`/`Map.copyOf` so the resulting snapshot is safe to cross threads. Static `fromSnapshot(snapshot)` reconstructs a fresh independent instance; reused by both the factory and the value adapter. |
| `CorrelationContextFactory` (`@Singleton`) | Creates initial `CorrelationContext` instances. Three entry points: `create(requestId, correlationId)` for REST ingress, `seed(boundary)` for first-ingress on non-REST surfaces (mints both ids via the generator, tags `source="seeded:<boundary>"`), `fromSnapshot(snapshot)` for the receive side of a dispatch boundary. Injects `Optional<CorrelationIdGenerator>` so app-supplied overrides win over the framework default. |
| `CorrelationContextMutator` (`@Singleton`) | Framework-only write surface for enriching the already-bound context. **Mirroring setters** (`setCausationId`, `setTrace`) update the live state AND the matching `CorrelationMdcKeys` MDC entry inline. **Non-mirroring setters** (`setSession`, `addProtocolCorrelation`, `putAttribute`) write the live state only — session and protocol refs are not in the V1 safe-by-default mirror set (FR-COR-163/165). Every setter fails fast with `IllegalStateException` when no `CorrelationContext` is bound on the current context. |
| `Uuid4CorrelationIdGenerator` | Default `CorrelationIdGenerator` SPI implementation. Stateless singleton (`INSTANCE`) producing RFC 4122 UUID v4 strings. Used by `CorrelationContextFactory` when no app override is supplied. |
| `CorrelationMdcKeys` | Public string constants for the framework-mirrored MDC keys (`requestId`, `correlationId`, `causationId`, `traceId`, `spanId`) plus the `MIRRORED` set. Lives here (not in `vertique-core`) because MDC is a logging concept that should not leak into the API module. |
| `CorrelationContextValueAdapter` | `ContextValueAdapter<CorrelationContext>` implementation registered via `META-INF/services/dev.vertique.core.context.ContextValueAdapter`. `type()` returns the public interface (matching the holder bind key); `snapshot`/`restoreFromSnapshot` delegate to `MutableCorrelationContext.fromSnapshot`; `duplicate` rebuilds independently so mutations on a Vert.x context duplicate do not bleed into the source. Pure no-arg constructor — no Dagger dependencies (substrate bootstraps adapters before Dagger exists). |
| `CorrelationContextModule` | Dagger module. Declares `@BindsOptionalOf CorrelationIdGenerator` and `@BindsOptionalOf TraceReferenceResolver`, contributes service-dispatch encoder/decoder (via `ServiceDispatchCodecs.snapshotEncoder/Decoder`), the bespoke durable encoder/decoder, and the `CorrelationContextSeeder` `InboundContextInitializer`. Pulled into the REST graph by `RestCoreModule` and into every service-dispatch graph by `DispatchModule`. |
| `TraceReferenceResolver` | SPI interface. See Extension Points below. |

## Extension Points

| Extension | Where to plug in |
|-----------|------------------|
| Custom id generator (ULID / NanoID / etc.) | `@Provides @Singleton CorrelationIdGenerator` in the app's `AppModule`. Wins over the framework default via the `@BindsOptionalOf` indirection. |
| Distributed-trace identity at REST ingress | `@Provides @Singleton TraceReferenceResolver` in the OpenTelemetry integration module. At most one implementation may be on the graph — see below. |
| Custom correlation MDC mirror keys | Not configurable in V1. Future opt-in `CorrelationMdcConfig` (deferred) will broaden the mirror set without breaking V1 semantics. |
| Application-supplied snapshot/restore behavior | Not exposed in V1 — the adapter is wired and bound to `CorrelationContext.class`. Apps that need to customise persistence shape contribute their own `DurableContextMetadataEncoder` / `Decoder` against the substrate's multibind. |

### TraceReferenceResolver

SPI for resolving the active distributed-trace identity during REST request ingress.

```java
public interface TraceReferenceResolver {
    Optional<TraceReference> currentTrace();
}
```

`CorrelationIngressMiddleware` consults the resolver immediately after the `CorrelationContext` is
bound and the initial MDC snapshot is taken — before the invalid-header rejection check, so
rejection log entries also carry trace ids when a resolver is present. When a non-empty
`TraceReference` is returned, the middleware calls `CorrelationContextMutator#setTrace`, which
writes the ids into both the live context and the `traceId`/`spanId` MDC keys for the duration of
the request.

**Resolution via `@BindsOptionalOf`:** `CorrelationContextModule` declares
`@BindsOptionalOf TraceReferenceResolver`. The middleware receives an
`Optional<TraceReferenceResolver>`; when no implementation is on the graph, the optional is empty
and the trace-enrichment step is skipped entirely — no behavior change for applications that do
not wire a tracer.

**Singleton by design:** at most one implementation may be on the graph. Tracing is
OpenTelemetry-only in this framework (see ADR-0098); contributing multiple resolvers would create
an ambiguous binding that Dagger rejects at compile time. Note that
`vertique-opentelemetry-core`'s `OpenTelemetryModule` **already contributes**
`OpenTelemetryTraceReferenceResolver` — an application that installs it must **not** also bind its
own resolver (doing so is the duplicate-binding error above). The registration example below applies
only when wiring a tracer integration that does not already provide one.

**Implementation contract:**

- Must be **cheap and non-blocking** — called on the Vert.x event loop during request ingress.
- SHOULD **never throw** — the caller guards with `try/catch` regardless, but a throwing resolver
  still emits one `WARN` log entry. Prefer returning `Optional.empty()` for "no span" conditions.
- An **empty result** means no active span or an invalid span context; the framework takes no
  action.
- **Sampling stance:** the framework's OpenTelemetry implementation mirrors ids for any *valid*
  span regardless of whether the trace is sampled for export. Log entries remain correlatable even
  when the trace is not visible in the trace store; the ids may not resolve there for unsampled
  traces.

**Registration (OpenTelemetry integration module):**

```java
@Provides @Singleton
TraceReferenceResolver otelTraceReferenceResolver(OpenTelemetry otel) {
    return () -> {
        Span span = Span.current();
        SpanContext sc = span.getSpanContext();
        if (!sc.isValid()) {
            return Optional.empty();
        }
        return Optional.of(new TraceReference(sc.getTraceId(), sc.getSpanId(), "opentelemetry"));
    };
}
```

## Dependencies

- **`vertique-core`** — public correlation API (`CorrelationContext` interface, value records, SPIs) and the substrate `ContextValueAdapter` interface.
- **`vertique-context`** — substrate runtime: `ContextHolder` binding, `ContextValues` static accessor, `ServiceDispatchCodecs`, `CompositeContextScope`, `InboundExecutionContextScope`, etc.
- **`vertique-logging`** — `MDCContexts` for inline MDC mirroring from the mutator.

Consumed by:

- **`vertique-rest-core`** — `CorrelationIngressMiddleware` and the protocol-correlation SPIs.
- `vertique-services` — transitively via `DispatchModule`, which now includes `CorrelationContextModule`. This means every dispatch-using graph (Kafka consumers, scheduled jobs, workflow workers, outbox relays) gets correlation by default without any per-app wiring.
- `vertique-kafka`, `vertique-inbox-outbox-*`, `vertique-job-*`, `vertique-workflow-*` — consume the runtime indirectly through `DispatchModule` and the substrate's `InboundExecutionContextScope`, which invokes the registered `CorrelationContextSeeder` on durable boundaries that arrive without an encoded context (FR-COR-125).

## Cross-dispatch Propagation

`CorrelationContext` is annotated with `@DispatchContextValue` (and a `ContextValue`), so service-handler parameter
injection works whenever a handler runs on the same Vert.x context as the binder
(`ParameterClassifier` routes the parameter to `ParamSource.DISPATCH_CONTEXT`).

For cross-boundary propagation the runtime ships:

- A service-dispatch `ServiceDispatchContextEncoder` / `Decoder` pair (registered via
  `ServiceDispatchCodecs.snapshotEncoder/Decoder`) — snapshots the live context into outgoing
  `DispatchEnvelope.metadata().dispatchContext()` and rebuilds a fresh live one on receive.
- A bespoke `DurableContextMetadataEncoder` / `Decoder` pair that serialises the snapshot as a
  single JSON envelope under the `vertique-correlation` durable key (Context contribution model
  level 4 — bespoke is justified by `durableSafe` filtering, schemaVersion enforcement, and
  header re-validation on decode).
- A `CorrelationContextSeeder` `InboundContextInitializer` that mints a fresh context on every
  inbound boundary (REST, service dispatch, Kafka, outbox relay, delayed-job poll, workflow
  branch / timer recovery) that arrives without one.

## Related ADRs

- ADR-0098: Micrometer Facade and Pluggable Registry Backends — establishes that tracing is OpenTelemetry-only in this framework (non-pluggable); directly governs why `TraceReferenceResolver` is a singular optional binding rather than a multibinding.
