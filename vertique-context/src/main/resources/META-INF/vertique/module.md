<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Context Module (vertique-context)

> **Status:** Implemented
> **Package:** `dev.vertique.context`
> **Artifact:** `vertique-context`
> **Depends on:** vertique-core

Substrate runtime for context propagation. Owns the `ContextHolder` implementation, the typed per-context value map (keyed by FQCN, backed by a single Vert.x `ContextLocal` slot), and the orchestration layer that captures, encodes, carries, and restores typed values across service-dispatch and durable boundaries (Kafka, outbox, delayed jobs, workflow timers).

This module sits between the API/SPI contracts in `vertique-core` and the feature modules that contribute context values (MDC in `vertique-logging`, `SecurityContext` propagation in `vertique-rest-security`). The substrate has no MDC-specific code and no security-specific code — it discovers feature adapters through `ServiceLoader` and feature encoders/decoders through Dagger multibindings.

---

## When To Use It

Every application that needs context propagation across dispatch boundaries must include `ContextRuntimeModule` in its AppComponent. REST applications get it transitively via `RestCoreModule`. Non-REST applications (services, Kafka consumers, workflow, job runners) include it directly alongside `LoggingContextModule` from `vertique-logging`.

```java
// Non-REST AppComponent
@Singleton
@Component(modules = {
    VertxModule.class,
    ContextRuntimeModule.class,    // substrate
    LoggingContextModule.class,    // MDC service-dispatch propagation
    DispatchModule.class,
    // ... application modules
})
interface AppComponent { ... }
```

---

## Core Concepts

### Single slot, FQCN-keyed map

All per-request typed values share one `ContextLocal<Map<String,Object>>` slot registered by `ContextLocalServiceProvider` at Vert.x bootstrap. Values are keyed by their type's fully qualified class name:

```
Vert.x duplicated context
  └─ ContextLocal<Map<String,Object>>   (single substrate slot)
       ├─ "dev.vertique.security.SecurityContext"    → SecurityContext
       ├─ "dev.vertique.logging.MDCContext"               → MDCContext
       ├─ "dev.vertique.core.context.DurablePropagationMetadata" → DurablePropagationMetadata
       └─ <any future typed value>                        → …
```

### Write-guarded, read-lenient

Write operations (`ContextValues.bind`, `MDCContexts.put`, etc.) fail fast with `IllegalStateException` when called outside a duplicated Vert.x context. Read operations (`ContextValues.current`, `MDCContexts.get`) return empty/null when called outside a context. This invariant is enforced in `DefaultContextHolder.requireDuplicatedContextForWrite()`.

### ContextValue marker and write-path enforcement

`ContextValue` (`dev.vertique.core.context.ContextValue`) is a behavior-free marker interface that identifies types eligible to be stored in the holder. The nine framework context types implement it: `CorrelationContext`, `SecurityContext`, `DurablePropagationMetadata`, `LocalizationContext`, `MDCContext`, `JobContext`, `JobDispatchContext`, `KafkaRecordContext`, and `TransactionalMessageContext`. Every `@DispatchContextValue`-annotated type is also a `ContextValue`; the converse is not required.

**Compile-time enforcement (typed entry points):** `ContextHolder.bind`, `ContextValues.bind`/`mutate`/`mutateIfPresent`, and `ContextScopeBinder.bindAll` are all bounded `<T extends ContextValue>`. Passing a non-`ContextValue` type to any of these entry points is a compile error.

**Runtime enforcement (erased entry points):** Two private-helper-based guard points cover paths where compile-time types are lost (wire-decoded values, snapshot restores, durable-boundary reinstalls):

- `installScopedRaw` — shared by `installScoped` (inbound dispatch reinstatement) and `bindSnapshot` (snapshot restore). Performs a null-safe `requireContextValue(key, value)` pre-pass over the entire batch before any mutation. A single rejected entry rejects the whole batch; nothing is installed or cleared on rejection.
- `installScopedAuthoritative` — used by `ContextScopeBinder.bindAllAuthoritative` (durable boundary restoration). Runs the same `requireContextValue` pre-pass over the full `bindings` map and rejects a null entry in `removeKeys`, all before installing or clearing any entry.

`requireContextValue` rejects both a **null key** and a value that is null or not a `ContextValue`, throwing `IllegalArgumentException` that names the offending key/type. A present key mapped to a null value — and a null key itself — are malformed erased input: the pre-pass rejects them before any mutation, and never treats null as a delete. Explicit deletion uses the delete-shaped APIs: `removeKey`, `installScopedAuthoritative(..., removeKeys)`, `ContextScopeBinder.bindAllAuthoritative(..., clearTypes)`.

**Typed vs. erased composite binders:** `ContextScopeBinder.bindAll` takes `Map<Class<? extends ContextValue>, ? extends ContextValue>` — compile-time proof that every value is a `ContextValue`. Because a heterogeneous map cannot carry the full key/value pairing guarantee, `bindAll` also performs a runtime `type.isInstance(value)` pre-pass before installing any binding. `ContextScopeBinder.bindAllAuthoritative` stays `Map<Class<?>, Object>` (its values arrive from erased durable decoders) and is guarded solely by the `installScopedAuthoritative` runtime pre-pass. Both composite binders reject a null `Class` key up front with an `IllegalArgumentException` rather than surfacing a bare `NullPointerException`.

**Read path stays unbounded:** `ContextHolder.current(Class<T>)` deliberately has no `ContextValue` bound so any code (test helpers, introspection utilities, type inspectors) can probe by type without declaring a `ContextValue` dependency.

**Exemptions (intentionally un-guarded):**
- `ContextLocalServiceProvider.deepCopyValue` (`duplicate(true)` duplicator, FR-CTX-206) — re-copies values that already passed a write-path check when originally bound; guarding a copy of a validated value would be redundant.
- `DefaultContextHolder.mutateIfPresentOnContext` — a scope-close escape hatch for already-validated values (e.g., `MDCContexts.MdcKeyScope` restore); it operates on an install-time context, not a bind path.

### Scoped LIFO restoration

`ContextHolder.Scope` is `AutoCloseable`. Binding a value captures the prior value at that key; closing the scope restores it. Multiple scopes can be composed (e.g., `CompositeContextScope`) and always unwind in LIFO order.

### Contribution model

Feature modules contribute context propagation at four levels:

| Level | Mechanism | When to use |
|-------|-----------|-------------|
| 1 | `ContextValueAdapter` via `ServiceLoader` | Deep-copy semantics on `duplicate(true)` — required for mutable values like `MDCContext` |
| 2 | `@Provides @IntoSet ServiceDispatchContextEncoder/Decoder` | Propagate a value through in-process service-dispatch envelopes |
| 3 | `@Provides @IntoSet DurableContextMetadataEncoder/Decoder` | Propagate a value through durable boundaries (Kafka headers, outbox `headers` column) |
| 4 | `@Provides @IntoSet InboundContextInitializer` | Run first-ingress initialization logic on inbound dispatch or durable receive |

Helper factories (`ServiceDispatchCodecs`, `DurableJsonContextCodecs`) cover the common encoder/decoder patterns. Bespoke encoder/decoder classes are only justified when the helper cannot express the filter or schema rules.

---

## Key Classes

### Holder

#### DefaultContextHolder

`@Singleton` implementation of `ContextHolder` (SPI in `vertique-core`). Manages a `ConcurrentHashMap`-backed per-context value map via the registered `ContextLocal` slot. Provides instance methods `current(Class)` and `bind(Class, value)` as well as package-internal static helpers used by `ContextValues` and `InboundDispatchScope`.

Key invariants:
- `bind(Class, value)` is bounded `<T extends ContextValue>` — a compile-time guard. Returns a `ContextHolder.Scope` that captures the prior binding and restores it on `close()`.
- `installScoped(Map)` installs multiple FQCN-keyed values atomically for one logical scope. Delegates to `installScopedRaw`, which runs a `requireContextValue` pre-pass over the entire batch before any mutation — a single rejected entry (non-`ContextValue` or null value) rejects the whole batch.
- `installScopedAuthoritative(Map, Set)` is used at durable boundaries: it installs present keys AND clears absent keys for the scope's lifetime, preventing ambient-context leak into subsequent `capture`/`mergeCaptured` calls. Runs its own `requireContextValue` pre-pass over the `bindings` map before installing any entry.
- `mutateIfPresentOnContext(Context, Class, Consumer)` is a public escape hatch for scope-close paths (e.g., `MDCContexts.MdcKeyScope`) that must restore against the install-time context, not the current context. This is an intentionally un-guarded path — values it operates on were already validated when originally bound.

#### ContextLocalServiceProvider

`VertxServiceProvider` SPI entry loaded automatically at Vert.x bootstrap via `META-INF/services/io.vertx.core.spi.VertxServiceProvider`. Registers the single `ContextLocal<Map<String,Object>>` slot with a deep-copy duplicator. The duplicator is invoked only on `ContextInternal.duplicate(true)` (the framework's per-dispatch copy); it delegates per-value deep-copy to registered `ContextValueAdapter` instances discovered via `ServiceLoader<ContextValueAdapter>`. Values with no registered adapter are stored by reference, which is correct for immutable types.

The adapter maps (`ADAPTERS_BY_TYPE`, `ADAPTERS_BY_FQCN`) are populated once at class-init time and shared read-only with `DefaultContextHolder` via package-private accessors.

**`init()` is idempotent and thread-safe across multiple `Vertx` instances in one JVM.** When a
test suite boots more than one `Vertx` instance in the same JVM (e.g., to exercise different
lifecycle configurations), `ContextLocalServiceProvider.init()` is called once per `Vertx`
creation. The implementation delegates to `DefaultContextHolder.initContextLocalIfAbsent`, which
guards against double-registration with a volatile-read + double-checked synchronized block: the
`ContextLocal` slot is registered exactly once (by the thread that wins the lock); subsequent
`init()` calls from the same or a later `Vertx` instance are no-ops. The adapter maps are
populated at class-init time (before `init()` runs) and are therefore consistent across all `Vertx`
instances.

#### ContextValues

Public static facade over `DefaultContextHolder`. All write methods route through `DefaultContextHolder.requireDuplicatedContextForWrite()`.

```java
// Reads — lenient outside Vert.x; T is unbounded (any type may be probed)
Optional<T>          ContextValues.current(Class<T> type)
ContextSnapshot      ContextValues.snapshot()

// Writes — bounded <T extends ContextValue>; fail fast outside a duplicated context
ContextHolder.Scope  ContextValues.bind(Class<T> type, T value)
void                 ContextValues.mutate(Class<T> type, Supplier<? extends T> init, Consumer<? super T> mutation)
void                 ContextValues.mutateIfPresent(Class<T> type, Consumer<? super T> mutation)
void                 ContextValues.remove(Class<?> type)
ContextHolder.Scope  ContextValues.bindSnapshot(ContextSnapshot snapshot)  // runtime-guarded via installScopedRaw
```

#### ContextSnapshot

`public final class` (not a record) capturing an immutable point-in-time snapshot of all holder entries. Created only by `ContextValues.snapshot()`; consumed only by `ContextValues.bindSnapshot(ContextSnapshot)`. For each entry whose FQCN has a registered `ContextValueAdapter`, the adapter's `snapshot(value)` method produces a frozen form; on restore, `restoreFromSnapshot(frozen)` materialises a fresh live value. Entries with no adapter are stored by reference. `isEmpty()` is the only public inspector.

---

### Scopes

#### ContextScopeBinder

Internal helper that installs multiple values atomically and unwinds them in reverse order on `close()`. Used by `DurableContextPropagator.bindFrom(...)` to install the full decoded durable-metadata set in one scope.

`bindAll(Map<Class<? extends ContextValue>, ? extends ContextValue>)` — compile-time-bounded typed variant. Performs a runtime `type.isInstance(value)` pairing pre-pass before installing any binding (a heterogeneous map cannot carry the full key/value pairing guarantee at compile time).

`bindAllAuthoritative(Map<Class<?>, Object> bindings, Set<Class<?>> clearTypes)` — erased variant for durable-boundary restoration (values arrive from erased decoders). Simultaneously installs present keys and clears absent keys; runtime-guarded via `installScopedAuthoritative`'s `requireContextValue` pre-pass. Used at durable boundaries to prevent ambient-context values from leaking into the scope.

#### CompositeContextScope

`public` scope that composes an array of child scopes and closes them in LIFO order. Most call sites should not use this directly — `InboundExecutionContextScope` and `InboundDispatchScope` return a composite scope that already combines all per-dispatch/per-durable installs plus initializer scopes.

#### InboundDispatchScope

`@Singleton` that installs a sanitized FQCN-keyed dispatch-context map for the lifetime of one service-dispatch call. Used by `ServiceMethodInvoker` in `vertique-services` and by `InboundExecutionContextScope`.

---

### Propagation

#### ServiceDispatchContextRegistry

`@Singleton` that validates and holds the set of `ServiceDispatchContextEncoder<?>`/`ServiceDispatchContextDecoder<?>` contributed via Dagger multibindings. Validates at construction that no two encoders or two decoders claim the same FQCN key.

#### ServiceDispatchContextCapturer

`@Singleton` that iterates registered encoders to encode currently-bound holder values into the FQCN-keyed `dispatchContext` map of an outbound `DispatchEnvelope`. Throws on duplicate-key collision (FR-CTX-063). Used by `DispatchEnvelopeBuilder`.

#### DurableContextMetadataRegistry

`@Singleton` that validates and holds the set of `DurableContextMetadataEncoder<?>`/`DurableContextMetadataDecoder<?>` contributed via Dagger multibindings.

#### DurableContextPropagator

`@Singleton` with three lifecycle methods used by all durable-boundary dispatchers and consumers:

| Method | When used |
|--------|-----------|
| `capture()` | At the durable publish site — encodes currently-bound values to a `DurableMetadata` document |
| `mergeCaptured(DurableMetadata base, String boundary)` | At the durable publish site — merges a base `DurableMetadata` document (e.g., from a prior capture) with the current capture |
| `bindFrom(DurableMetadata metadata, String boundary)` | At the durable receive site — decodes the `DurableMetadata` document and installs all decoded values as an authoritative scope |

#### DurableMetadataHeaderCodec

Utility class (`dev.vertique.core.context`) that projects a `DurableMetadata` document to and from
a flat string-keyed header map (e.g. Kafka record headers). Each namespace maps to exactly one
reserved header named `vertique-<namespace>` whose value is the namespace body as a JSON string.

| Method | Description |
|--------|-------------|
| `toHeaders(DurableMetadata)` | Projects all namespaces to reserved `vertique-*` headers; returns immutable map |
| `fromHeaders(Map<String,String>)` | Reconstructs a `DurableMetadata` from reserved headers; malformed namespace headers skipped |
| `mergeForEgress(appHeaders, context)` | Builds the outbound header set: validates no app header uses `vertique-*`, then overlays projected context headers |
| `isReservedHeader(String)` | Returns `true` when the name starts with `vertique-` |

`mergeForEgress` is the single collision-enforcement point for the `vertique-*` prefix. It is used
by both the direct Kafka producer path and the outbox→Kafka relay so that application headers and
framework context headers can never collide.

---

### Lifecycle

#### InboundExecutionContextScope

`@Singleton` lifecycle helper that combines inbound dispatch/durable binding with fan-out to registered `InboundContextInitializer` instances. Inbound call sites inject this class and use one of its two entry points:

```java
// Service-dispatch boundary
ContextHolder.Scope scope = inboundExecutionContextScope.installDispatch(dispatchContext, boundary);

// Durable boundary (Kafka, outbox)
ContextHolder.Scope scope = inboundExecutionContextScope.installDurable(metadata, boundary);
```

Both methods open the inbound scope first, then invoke each registered initializer in iteration order. If any initializer throws, all previously opened scopes (initializer scopes in LIFO order, then the inbound scope) are closed before the exception propagates — no partial state leaks. The returned composite scope closes in LIFO order on `close()`.

---

### Helpers

#### ServiceDispatchCodecs

Static factory for `ServiceDispatchContextEncoder<T>` and `ServiceDispatchContextDecoder<T>` implementations covering two patterns:

| Pattern | Encoder | Decoder |
|---------|---------|---------|
| Snapshot | `snapshotEncoder(Class<T>, Function<T, ?>)` | `snapshotDecoder(Class<T>, Class<S>, Function<S, T>)` |
| Pass-through (immutable values) | `passThroughEncoder(Class<T>)` | `passThroughDecoder(Class<T>)` |

Use snapshot when the live value is mutable and must be isolated on the wire. Use pass-through when the live value is immutable and can be shared by reference across dispatch boundaries.

```java
// Example: MDCContext (mutable) — snapshot pattern
ServiceDispatchContextEncoder<?> encoder = ServiceDispatchCodecs.snapshotEncoder(
    MDCContext.class,
    live -> new DiagnosticContextSnapshot(live.copy()));

ServiceDispatchContextDecoder<?> decoder = ServiceDispatchCodecs.snapshotDecoder(
    MDCContext.class,
    DiagnosticContextSnapshot.class,
    MDCContext::fromSnapshot);

// Example: SecurityContext (immutable) — pass-through pattern
ServiceDispatchContextEncoder<?> encoder = ServiceDispatchCodecs.passThroughEncoder(SecurityContext.class);
ServiceDispatchContextDecoder<?> decoder = ServiceDispatchCodecs.passThroughDecoder(SecurityContext.class);
```

#### DurableJsonContextCodecs

Static factory for `DurableContextMetadataEncoder<T>` and `DurableContextMetadataDecoder<T>` implementations that serialize/deserialize through Jackson JSON using the Vert.x shared `DatabindCodec.mapper()`. The namespace name is passed as a parameter and becomes the single key in the produced `DurableMetadata` document.

```java
// Example: CorrelationId propagated via durable boundary
DurableContextMetadataEncoder<CorrelationId> encoder = DurableJsonContextCodecs.jsonEncoder(
    CorrelationId.class,
    "correlation",                         // namespace name
    id -> new CorrelationEnvelope(id.value()),
    CorrelationEnvelope.class);

DurableContextMetadataDecoder<CorrelationId> decoder = DurableJsonContextCodecs.jsonDecoder(
    CorrelationId.class,
    "correlation",                         // namespace name
    CorrelationEnvelope.class,
    env -> new CorrelationId(env.value()));
```

---

### Built-ins

#### DurablePropagationMetadataServiceDispatchEncoder / Decoder

Built-in encoder/decoder pair for `DurablePropagationMetadata` (the `DurableMetadata` durable context document view). Registered directly in `ContextRuntimeModule` via `@Provides @IntoSet` so that raw durable metadata travels through in-process service-dispatch hops without needing a feature module to wire it.

---

### Envelope

#### DispatchEnvelopeBuilder

`@Singleton` single construction helper for outbound `DispatchEnvelope` instances. All framework dispatchers (services, job dispatchers, Kafka producers) must construct envelopes through this builder so that registered `ServiceDispatchContextEncoder`s capture currently-bound holder values. Direct `DispatchEnvelope.of(...)` construction bypasses context capture and should only be used in test scenarios where no propagation is required.

```java
@Inject DispatchEnvelopeBuilder envelopeBuilder;

// Outbound dispatch — captures all registered context values
DispatchEnvelope<MyPayload> envelope = envelopeBuilder
    .payload(payload)
    .build();
```

---

### Dagger wiring

#### ContextRuntimeModule

Abstract Dagger `@Module`. Include this in every AppComponent that uses context propagation.

Provides:
- `@Binds ContextHolder ← DefaultContextHolder`
- Five `@Multibinds` empty sets: `Set<ServiceDispatchContextEncoder<?>>`, `Set<ServiceDispatchContextDecoder<?>>`, `Set<DurableContextMetadataEncoder<?>>`, `Set<DurableContextMetadataDecoder<?>>`, `Set<InboundContextInitializer>`
- `@Provides @IntoSet ServiceDispatchContextEncoder<?>` — built-in `DurablePropagationMetadataServiceDispatchEncoder`
- `@Provides @IntoSet ServiceDispatchContextDecoder<?>` — built-in `DurablePropagationMetadataServiceDispatchDecoder`

No feature-specific bindings live in this module. MDC propagation is registered by `LoggingContextModule` in `vertique-logging`. `SecurityContext` propagation is registered by `AuthModule` in `vertique-rest-security`.

---

## Extension Points

### ContextValueAdapter (ServiceLoader)

Provides deep-copy semantics for mutable holder values when a Vert.x context is duplicated with `duplicate(true)`. Discovered from `META-INF/services/dev.vertique.core.context.ContextValueAdapter`. The substrate bootstraps before Dagger — adapters must be pure `ServiceLoader` providers with a public no-arg constructor. The SPI is bounded `<T extends ContextValue>` on its context-type parameter — implementing an adapter for a non-`ContextValue` type is a compile error.

```java
// META-INF/services/dev.vertique.core.context.ContextValueAdapter:
// dev.vertique.logging.MDCContextValueAdapter

public class MDCContextValueAdapter implements ContextValueAdapter<MDCContext> {
    @Override public Class<MDCContext> type() { return MDCContext.class; }
    @Override public Object snapshot(MDCContext value) { return Map.copyOf(value.mapView()); }
    @Override public MDCContext restoreFromSnapshot(Object frozen) {
        return MDCContext.fromSnapshot((Map<String,String>) frozen);
    }
    @Override public MDCContext duplicate(MDCContext value) {
        return MDCContext.fromSnapshot(Map.copyOf(value.mapView()));
    }
}
```

### ServiceDispatchContextEncoder/Decoder (Dagger multibinding)

Both SPIs are bounded `<T extends ContextValue>` on their context-type parameter; only the wire-envelope type parameter stays unbounded. Contribute via `@Provides @IntoSet` on the feature's Dagger module. Use `ServiceDispatchCodecs` unless the encoder/decoder needs filter or schema logic that the helper cannot express.

```java
// In LoggingContextModule (vertique-logging)
@Provides @IntoSet
static ServiceDispatchContextEncoder<?> mdcContextEncoder() {
    return MDCContexts.serviceDispatchEncoder();
}

@Provides @IntoSet
static ServiceDispatchContextDecoder<?> mdcContextDecoder() {
    return MDCContexts.serviceDispatchDecoder();
}
```

### DurableContextMetadataEncoder/Decoder (Dagger multibinding)

Both SPIs are bounded `<T extends ContextValue>` on their context-type parameter; the `DurableMetadata` wire type stays unbounded. Contribute via `@Provides @IntoSet` on the feature's Dagger module. Each encoder and decoder owns exactly one short namespace name returned by `namespace()` (e.g. `"correlation"`, `"localization"`).
Boot-time validation rejects duplicate namespaces across encoders or across decoders.

Encoders return a single-namespace `DurableMetadata` document built with
`DurableMetadata.of(namespace(), body)`. Decoders receive the full `DurableMetadata` document and
read their namespace via `metadata.body(namespace())`.

Use `DurableJsonContextCodecs` for JSON serialization helpers, or implement the SPI directly for
custom filtering or schema-version logic.

### InboundContextInitializer (Dagger multibinding)

Contribute via `@Provides @IntoSet` or `@Binds @IntoSet` on the feature's Dagger module. Called by `InboundExecutionContextScope` at every inbound dispatch and durable receive site, after the inbound scope is opened.

```java
@Provides @IntoSet
static InboundContextInitializer myInitializer(MyService service) {
    return ctx -> {
        // ctx.boundary() identifies the ingress boundary (e.g., "service-dispatch", "kafka")
        MyValue value = service.resolveForBoundary(ctx.boundary());
        return ContextValues.bind(MyValue.class, value);
    };
}
```

---

## Dependencies

| Dependency | Why |
|------------|-----|
| `vertique-core` | Consumes `ContextHolder`, `ContextValueAdapter`, and all SPI contracts from `core.context` |
| `io.vertx:vertx-core` | `ContextLocal`, `VertxServiceProvider`, `ContextInternal.duplicate(true)` |
| `com.google.dagger:dagger` | `ContextRuntimeModule` is a Dagger `@Module`; `DefaultContextHolder`, `DispatchEnvelopeBuilder`, etc. use `@Singleton` and `@Inject` |
| `jakarta.inject` | `@Inject`, `@Singleton` |
| `com.fasterxml.jackson.databind` | `DurableJsonContextCodecs` uses `DatabindCodec.mapper()` for JSON serialization |
| `org.slf4j:slf4j-api` | Logging inside `WarningThrottle` and propagation paths |

---

## Related ADRs

- ADR-0065: Structured durable context metadata (context/delivery separation) — establishes `DurableMetadata`, the namespace model, `DurableMetadataHeaderCodec`'s `mergeForEgress` collision guard, and the outbox `metadata JSONB` / `OutboxMetadata { context, delivery }` split.
- ADR-0068: ContextValue marker + ContextHolder write-path & SPI enforcement — records the decision to introduce `ContextValue` as a behavior-free marker interface, the choice of interface over annotation (enabling both compile-time bounds and cheap `instanceof` runtime checks), the two atomic runtime guard points (`installScopedRaw` + `installScopedAuthoritative`), the null-is-malformed rule, the `<T extends ContextValue>` bounds on the five context-producing SPIs, and the two intentional exemptions (FR-CTX-206 duplicator + `mutateIfPresentOnContext`).
- ADR-0147: Instance-Level Durable Context with Base-Wins/Instance-Fill Binding — the workflow-side extension of the ADR-0065 substrate; records the bind gate, the base-wins/instance-fill merge order, fork absorption, and the duplicated-context break.
