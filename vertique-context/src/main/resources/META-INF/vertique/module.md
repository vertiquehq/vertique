<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Context Module (vertique-context)

> **Status:** Implemented
> **Package:** `dev.vertique.context`
> **Artifact:** `vertique-context`
> **Depends on:** `dev.vertique:vertique-core`

Runtime substrate for context propagation. It implements the `ContextHolder` contract declared in `dev.vertique.core.context`, stores typed per-request values in a single Vert.x context-local slot keyed by fully qualified class name, and carries those values across two boundaries: in-process service dispatch (event-bus envelopes) and durable boundaries (Kafka headers, the outbox `metadata` column, delayed jobs, workflow timers).

The substrate contains no feature-specific code. It knows nothing about MDC, security identity, correlation, or localization — each of those is contributed by its own module through the extension points below. Applications interact with it in three ways: reading and binding values through `ContextValues`, constructing outbound envelopes through `DispatchEnvelopeBuilder`, and contributing encoders, decoders, adapters, or initializers for their own context types.

---

## When To Use It

`ContextRuntimeModule` must be in the application component for any code that binds or reads holder values. In practice it is almost always already there: `RestCoreModule` (`dev.vertique:vertique-rest-core`), `DispatchModule` (`dev.vertique:vertique-services`), `KafkaModule` (`dev.vertique:vertique-kafka-core`), the cron and delayed-job modules (`dev.vertique:vertique-job-cron`, `dev.vertique:vertique-job-delayed`), `WorkflowEngineModule` (`dev.vertique:vertique-workflow-engine`), and the transactional-messaging modules all include it transitively.

Include it explicitly only in a component that uses `ContextValues` or `DispatchEnvelopeBuilder` without installing any of those modules — for example a bespoke transport adapter or a focused test component:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    ContextRuntimeModule.class,    // substrate
    LoggingContextModule.class,    // optional: MDC propagation across dispatch
    // ... application modules
})
interface AppComponent { }
```

Adding a *feature* to the propagation pipeline is a separate decision from installing the substrate. MDC propagation needs `LoggingContextModule` (`dev.vertique:vertique-logging`); `SecurityContext` propagation is registered by `AuthModule` (`dev.vertique:vertique-rest-security`); correlation and localization are registered by their own modules.

---

## Core Concepts

### One slot, FQCN-keyed map

All typed per-context values share a single `ContextLocal<Map<String, Object>>` slot that the module registers with Vert.x at bootstrap through the `io.vertx.core.spi.VertxServiceProvider` service file. Each value is keyed by its type's fully qualified class name:

```
Vert.x duplicated context
  └─ ContextLocal<Map<String,Object>>          (one substrate slot)
       ├─ "dev.vertique.security.SecurityContext"                → SecurityContext
       ├─ "dev.vertique.core.context.DurablePropagationMetadata" → DurablePropagationMetadata
       └─ <any registered context value>                         → …
```

Registration is idempotent per JVM, so a test suite that boots several `Vertx` instances still ends up with exactly one slot.

### Write-guarded, read-lenient

Writes (`ContextValues.bind`, `mutate`, `mutateIfPresent`, `remove`, `bindSnapshot`) require an active **duplicated** Vert.x context and throw `IllegalStateException` otherwise — once for "no Vert.x context at all" and once for "a Vert.x context that is not a duplicate". Reads (`ContextValues.current`, `ContextValues.snapshot`) never throw: outside a context they return an empty `Optional` and the empty snapshot.

Every framework transport boundary — event-bus consumers, Kafka record dispatch, the outbox relay, cron and delayed jobs — enters a duplicated context before writing. Application code running on a plain deployment context (a verticle `start` method, a timer callback) must not bind directly.

On the erased install paths a present key mapped to `null` is rejected as malformed rather than treated as a removal, and a `null` key is rejected before any mutation. Validation is a pre-pass over the whole batch, so one bad entry rejects the entire install and nothing is written or cleared. Deletion has its own shapes: `ContextValues.remove(Class<?>)` for a single key, and the `clearTypes` argument of the authoritative durable bind.

### ContextValue and @DispatchContextValue

`dev.vertique.core.context.ContextValue` is a behavior-free marker interface. A type must implement it to be stored in the holder. The typed entry points (`ContextHolder.bind`, `ContextValues.bind` / `mutate` / `mutateIfPresent`) are bounded `<T extends ContextValue>`, so passing an unmarked type is a compile error. Erased entry points — snapshot restore and durable decode, where the compile-time type is gone — run the same check at runtime and throw `IllegalArgumentException` naming the offending key and class.

The read path is deliberately unbounded: `ContextHolder.current(Class<T>)` and `ContextValues.current(Class<T>)` accept any type, so test helpers and introspection utilities can probe by type without declaring a `ContextValue` dependency.

`dev.vertique.core.eventbus.DispatchContextValue` is a narrower, additional marker. Every `@DispatchContextValue`-annotated type is also a `ContextValue`; the annotation additionally declares that a service handler method may take the type as a parameter and have the dispatch framework resolve it from the envelope's dispatch-context map. Annotate a type with it only when handler-parameter injection is wanted — the annotation is not required for propagation.

### Scoped LIFO restoration

`ContextHolder.Scope` extends `AutoCloseable`. Binding captures whatever was previously at that key and restores it on `close()`; a key that was absent before is removed again. Composite scopes returned by the inbound helpers close their constituents in LIFO order, and `close()` is idempotent. Always close in a `try`-with-resources or an equivalent `finally`.

### Two boundaries, four contribution levels

Service dispatch carries values in the `dispatchContext` map inside a `DispatchEnvelope`; durable boundaries carry them in a namespace-partitioned `DurableMetadata` document. The two are independent — a type that must survive a durable hop needs a durable pair even if it already has a service-dispatch pair.

| Level | Mechanism | Use when |
|-------|-----------|----------|
| 1 | `ContextValueAdapter` via `ServiceLoader` | The value is mutable and needs deep-copy on `duplicate(true)`, or snapshot/restore semantics |
| 2 | `@Provides @IntoSet ServiceDispatchContextEncoder` / `Decoder` | The value must cross in-process service-dispatch hops (event bus) |
| 3 | `@Provides @IntoSet DurableContextMetadataEncoder` / `Decoder` | The value must cross a durable boundary (Kafka, outbox, delayed jobs, workflow timers) |
| 4 | `@Provides @IntoSet InboundContextInitializer` | A default must be seeded at first ingress when nothing was decoded |

`ServiceDispatchCodecs` and `DurableJsonContextCodecs` cover the common encoder/decoder shapes. Write a bespoke implementation only when the helper cannot express the required filtering or schema rules.

---

## Key Classes

### ContextValues

The static facade applications use to read and write holder values. Every write routes through the duplicated-context guard.

```java
// Reads — lenient outside Vert.x; T is unbounded
Optional<T>          ContextValues.current(Class<T> type)
ContextSnapshot      ContextValues.snapshot()

// Writes — bounded <T extends ContextValue>; require a duplicated Vert.x context
ContextHolder.Scope  ContextValues.bind(Class<T> type, T value)
void                 ContextValues.mutate(Class<T> type, Supplier<? extends T> init, Consumer<? super T> mutation)
void                 ContextValues.mutateIfPresent(Class<T> type, Consumer<? super T> mutation)
void                 ContextValues.remove(Class<?> type)
ContextHolder.Scope  ContextValues.bindSnapshot(ContextSnapshot snapshot)
```

```java
try (ContextHolder.Scope scope = ContextValues.bind(TenantContext.class, new TenantContext("acme"))) {
    // TenantContext is visible to everything on this duplicated context
}
// prior binding restored here
```

`mutate` initializes through the supplier when the key is absent *or* holds a value of a different type; the supplier must not return `null`.

Injecting `ContextHolder` is the alternative to the static facade and exposes the same read and single-bind operations.

### ContextSnapshot

An opaque, immutable point-in-time copy of every holder entry. Produced by `ContextValues.snapshot()` (or `ContextSnapshot.empty()`), consumed by `ContextValues.bindSnapshot(...)`. `isEmpty()` is the only inspector — there is deliberately no accessor for the underlying map.

For each entry whose type has a registered `ContextValueAdapter`, the adapter's `snapshot(...)` produces a frozen form and `restoreFromSnapshot(...)` materializes a fresh live value on rebind, so the snapshot stays independent of later mutation on either side. Entries without an adapter are carried by reference, which is correct for immutable values.

Use it to carry context onto a thread or callback that is not on the originating dispatch context — take the snapshot while the context is live, rebind it inside the target duplicated context.

### DispatchEnvelopeBuilder

The single construction point for outbound `DispatchEnvelope` instances. Every framework dispatcher builds envelopes through it so that registered service-dispatch encoders capture the currently bound holder values. Constructing an envelope with `DispatchEnvelope.of(...)` directly bypasses context capture.

```java
@Singleton
public class OrderDispatcher {

    private final DispatchEnvelopeBuilder envelopeBuilder;

    @Inject
    public OrderDispatcher(DispatchEnvelopeBuilder envelopeBuilder) {
        this.envelopeBuilder = envelopeBuilder;
    }

    public DispatchEnvelope<OrderPlaced> envelopeFor(OrderPlaced payload) {
        // payload, caller-supplied dispatch-context overrides (FQCN keys), boundary id
        return envelopeBuilder.build(payload, Map.of(), "service-dispatch");
    }
}
```

| Method | Purpose |
|---|---|
| `build(T payload, Map<String,Object> callerOverrides, String boundary)` | Merge caller overrides with encoder output and build the envelope |
| `build(T payload, Map<String,Object> callerOverrides, String boundary, String replyAddress)` | Same, plus a fire-and-report reply address the invoker publishes to instead of replying |
| `DispatchEnvelopeBuilder.forTesting()` | A builder backed by empty registries, for plain-JUnit fixtures with no Dagger graph — never for production |

`callerOverrides` keys **must** be type FQCNs and may be empty, but must not be `null`. A caller key that collides with a key an encoder produces throws `IllegalStateException` (FR-CTX-063); an encoder that returns `null` also throws `IllegalStateException` (FR-CTX-050).

### DurableContextPropagator

The injectable orchestrator for durable-boundary propagation. Producers capture; consumers bind.

| Method | Where it is used |
|---|---|
| `DurableMetadata capture(String boundary)` | Producer side — encode currently bound values into a metadata document |
| `DurableMetadata mergeCaptured(DurableMetadata callerContext, String boundary)` | Producer side — merge caller-supplied metadata with the current capture |
| `DurableMetadata mergeCaptured(..., DurableCarrierDescriptor carrier)` | Same, binding the captured envelope to a specific persisted row |
| `DurableMetadata mergeCaptured(..., DurableCarrierDescriptor carrier, Instant fireTime)` | Same, additionally declaring the row's intended fire time |
| `ContextHolder.Scope bindFrom(DurableMetadata metadata, String boundary)` | Consumer side — decode and install as an authoritative scope |
| `ContextHolder.Scope bindFrom(..., DurableCarrierDescriptor carrier)` | Same, verifying the envelope was signed for that row |
| `Map<String,Object> decodeToDispatchContext(DurableMetadata metadata, String boundary)` | Consumer side on a **non**-duplicated context — decode without touching the holder |
| `DurableMetadata sanitizeInboundCarrier(DurableMetadata carrier)` | Strip authenticated-only namespaces from a sender-supplied carrier before binding it |

**Authoritative binding.** `bindFrom` treats the metadata as the complete snapshot taken at the producer. It always binds `DurablePropagationMetadata` (FR-CTX-141), installs every successfully decoded type, and **clears** every other registered durable type for the scope's lifetime (FR-CTX-178). That is what stops ambient context at the consumer from leaking into a `mergeCaptured` performed inside the scope. Closing the scope restores everything, including the cleared values (FR-CTX-157).

**Decode failures are contained.** A decoder that throws, returns `null`, or reports warnings is logged at WARN (throttled per boundary and decoder) and its type is simply not bound; other decoders proceed normally (FR-CTX-156).

**Choosing between `bindFrom` and `decodeToDispatchContext`.** `bindFrom` must run on a duplicated Vert.x context (FR-CTX-157b) — on a non-duplicated context the holder's write guard throws. A transport whose callback lands on a plain deployment context (an outbox relay, a delayed-job poller) must call `decodeToDispatchContext` instead and merge the returned FQCN-keyed map into `DispatchEnvelopeBuilder.build(...)` as caller overrides; the receiving dispatcher then installs the values on its own duplicated context. Outside any Vert.x context at all, `bindFrom` logs a throttled warning and returns a no-op scope so plain-JUnit fixtures still run.

**Producer collision rule.** `mergeCaptured` throws `IllegalStateException` when a namespace already present in the caller-supplied document is also owned by an encoder whose type is currently bound (FR-CTX-153). When the encoder's type is *not* bound, the caller's namespace passes through unchanged (FR-CTX-154). An encoder must return exactly its own declared namespace, or an empty document to signal "nothing to encode".

### DurableMetadataHeaderCodec

Utility in `dev.vertique.core.context` (shipped in `dev.vertique:vertique-core`) that projects a `DurableMetadata` document to and from a flat string-keyed header map, as used for Kafka record headers. Each namespace becomes exactly one reserved header named `vertique-<namespace>` whose value is that namespace's body as a JSON string.

| Method | Behavior |
|---|---|
| `toHeaders(DurableMetadata)` | Projects every namespace to its reserved header; returns an immutable map |
| `fromHeaders(Map<String,String>)` | Reconstructs a document from reserved headers; malformed namespace headers are skipped |
| `mergeForEgress(Map<String,String> appHeaders, DurableMetadata context)` | Validates that no application header uses the `vertique-` prefix, then overlays the projected context headers |
| `isReservedHeader(String)` | `true` when the name starts with `vertique-` |

`mergeForEgress` is the single enforcement point for the reserved prefix, so application headers and framework context headers can never collide. Build outbound headers through it rather than merging maps by hand.

### InboundExecutionContextScope

The injectable helper a custom inbound boundary uses to install context and run registered initializers in one step. It opens the inbound scope first, then invokes each `InboundContextInitializer` in iteration order.

```java
// Service-dispatch boundary — dispatchContext is the envelope's FQCN-keyed map
try (ContextHolder.Scope scope = inbound.installDispatch(dispatchContext, "service-dispatch")) {
    // handler work
}

// Durable boundary
try (ContextHolder.Scope scope = inbound.installDurable(metadata, "kafka")) {
    // consumer work
}
```

`installDurableAndRun(DurableMetadata, String, Supplier<Future<T>>)` is the asynchronous form: it installs the scope, invokes the supplier inside it, and closes the scope when the returned future settles — on success and on failure. A `RuntimeException` thrown synchronously by the supplier is converted into a failed future after the scope is closed, so the binding cannot leak into the caller.

If any initializer throws, every scope already opened is closed — initializer scopes in LIFO order, then the inbound scope — before the exception propagates, so no partial state escapes.

### ContextRuntimeModule

The Dagger module that wires the substrate. It binds `ContextHolder` to the substrate implementation, declares the five empty multibinding sets (`ServiceDispatchContextEncoder`, `ServiceDispatchContextDecoder`, `DurableContextMetadataEncoder`, `DurableContextMetadataDecoder`, `InboundContextInitializer`), and contributes the built-in service-dispatch encoder/decoder pair for `DurablePropagationMetadata` so raw durable metadata survives in-process hops without any feature module (FR-CTX-143).

No feature-specific bindings live here.

### Invariants & Gotchas

- **Bind only on a duplicated context.** A write from a verticle `start` method, a bare `vertx.setTimer` callback, or a worker thread throws `IllegalStateException`. Enter the framework dispatch path, or capture a `ContextSnapshot` and rebind it where a duplicated context exists.
- **Deep copy happens only on `duplicate(true)`.** Vert.x consults the substrate's duplicator on that call alone. On the ordinary `duplicate(false)` path the duplicated context's slot is empty and the duplicator is never invoked.
- **Mutable values need an adapter.** Without a registered `ContextValueAdapter`, a value is stored, snapshotted, and duplicated by reference. That is correct for immutable types and a cross-dispatch mutation hazard for anything else.
- **Registry validation is a startup gate.** Duplicate dispatch keys, duplicate handled types, duplicate durable namespaces, and a durable namespace that is blank or begins with the reserved `vertique-` prefix all throw `IllegalStateException` while the registry is constructed. The failure surfaces as a Dagger component construction error at startup, never as a first-dispatch surprise.
- **A durable encoder owns exactly one namespace.** Returning a document whose namespace set differs from `namespace()` throws `IllegalStateException`; return `DurableMetadata.empty()` to signal there is nothing to encode.
- **Close every scope.** Scopes restore prior values on `close()` and are idempotent, but a scope that is never closed leaves the binding in place for the rest of the context's life.
- **`DispatchEnvelope.of(...)` skips capture.** Use it only in tests that deliberately need an envelope with no propagated context.

---

## Extension Points

### ContextValueAdapter (ServiceLoader)

Declares snapshot, restore, and deep-copy behavior for one holder value type. The substrate is loaded during Vert.x bootstrap, before Dagger exists, so adapters are discovered through `java.util.ServiceLoader` and must be public classes with a public no-arg constructor and no injected dependencies. The SPI is bounded `<T extends ContextValue>`.

```java
// META-INF/services/dev.vertique.core.context.ContextValueAdapter
// com.example.TenantContextValueAdapter

public final class TenantContextValueAdapter implements ContextValueAdapter<TenantContext> {

    @Override
    public Class<TenantContext> type() {
        return TenantContext.class;
    }

    @Override
    public Object snapshot(TenantContext live) {
        return Map.copyOf(live.attributes());
    }

    @Override
    @SuppressWarnings("unchecked")
    public TenantContext restoreFromSnapshot(Object frozen) {
        return TenantContext.fromAttributes((Map<String, String>) frozen);
    }

    @Override
    public TenantContext duplicate(TenantContext live) {
        return TenantContext.fromAttributes(Map.copyOf(live.attributes()));
    }
}
```

`duplicate` has a default that returns the live value unchanged, which is correct for immutable types; override it for anything mutable. This SPI governs holder lifecycle only — boundary propagation uses the encoder/decoder SPIs below.

### ServiceDispatchContextEncoder / ServiceDispatchContextDecoder (Dagger multibinding)

Carry a value across in-process service-dispatch hops. Both are bounded `<T extends ContextValue>` on the context type; the wire type stays unbounded. `key()` defaults to the context type's FQCN and is what the dispatch-context map is keyed by.

```java
public interface ServiceDispatchContextEncoder<T extends ContextValue> {
    Class<T> type();
    default String key() { return type().getName(); }
    Object encode(T value, ServiceDispatchEncodeContext context);
}

public interface ServiceDispatchContextDecoder<T extends ContextValue> {
    Class<T> type();
    default String key() { return type().getName(); }
    ContextDecodeResult<T> decode(Object value, ServiceDispatchDecodeContext context);
}
```

Use `ServiceDispatchCodecs` rather than implementing the interfaces directly unless filtering or schema logic is required:

| Pattern | Encoder | Decoder |
|---|---|---|
| Snapshot (mutable live value) | `snapshotEncoder(Class<T> type, Function<T,?> snapshotFn)` | `snapshotDecoder(Class<T> type, Class<S> snapshotType, Function<S,T> restoreFn)` |
| Pass-through (immutable live value) | `passThroughEncoder(Class<T> type)` | `passThroughDecoder(Class<T> type)` |

```java
@Provides
@IntoSet
static ServiceDispatchContextEncoder<?> tenantEncoder() {
    return ServiceDispatchCodecs.passThroughEncoder(TenantContext.class);
}

@Provides
@IntoSet
static ServiceDispatchContextDecoder<?> tenantDecoder() {
    return ServiceDispatchCodecs.passThroughDecoder(TenantContext.class);
}
```

An encoder must not return `null` (FR-CTX-050). A decoder returns a `ContextDecodeResult`: `of(value)` on success, `empty()` when there is nothing to decode, or `failure(warnings)` when the wire value is unusable. Prefer a `failure` result over throwing.

### DurableContextMetadataEncoder / DurableContextMetadataDecoder (Dagger multibinding)

Carry a value across durable boundaries. Both are bounded `<T extends ContextValue>` and own exactly one short namespace name (`"correlation"`, `"localization"`, …) returned by `namespace()`; duplicates across encoders or across decoders are rejected at startup.

```java
public interface DurableContextMetadataEncoder<T extends ContextValue> {
    Class<T> type();
    String namespace();
    DurableMetadata encode(T value, DurableEncodeContext context);
}

public interface DurableContextMetadataDecoder<T extends ContextValue> {
    Class<T> type();
    String namespace();
    ContextDecodeResult<T> decode(DurableMetadata metadata, DurableDecodeContext context);
    default boolean acceptsExplicitCarrier() { return true; }
}
```

Encoders return a single-namespace document built with `DurableMetadata.of(namespace(), body)`, where `body` is a `JsonObject`. Decoders receive the **full** document and read their own namespace via `metadata.body(namespace())`, which returns an `Optional<JsonObject>`. A namespace that is blank or begins with the reserved `vertique-` prefix is rejected at startup.

Override `acceptsExplicitCarrier()` to return `false` for a namespace that must only ever arrive from a framework-produced carrier. `DurableContextPropagator.sanitizeInboundCarrier(...)` strips exactly those namespaces from a sender-supplied explicit carrier before it is bound, logging the stripped namespace names — never their bodies.

`DurableJsonContextCodecs` covers JSON serialization through the Vert.x shared databind mapper:

```java
@Provides
@IntoSet
static DurableContextMetadataEncoder<?> tenantDurableEncoder() {
    return DurableJsonContextCodecs.jsonEncoder(
            TenantContext.class,
            "tenant",
            tenant -> new TenantEnvelope(tenant.id()));
}

@Provides
@IntoSet
static DurableContextMetadataDecoder<?> tenantDurableDecoder() {
    return DurableJsonContextCodecs.jsonDecoder(
            TenantContext.class,
            "tenant",
            TenantEnvelope.class,
            env -> new TenantContext(env.id()));
}
```

### InboundContextInitializer (Dagger multibinding)

Seeds a default value at first ingress when nothing was decoded for that concern. Called by the inbound helpers at every service-dispatch and durable-receive site, after the inbound scope opens, and its returned scope is closed with the rest.

```java
public interface InboundContextInitializer {
    ContextHolder.Scope initialize(InboundContextInitializationContext context);
}
```

```java
@Provides
@IntoSet
static InboundContextInitializer tenantDefault(TenantResolver resolver) {
    return ctx -> {
        if (ContextValues.current(TenantContext.class).isPresent()) {
            return () -> {}; // already decoded upstream — do not overwrite
        }
        return ContextValues.bind(TenantContext.class, resolver.forBoundary(ctx.boundary()));
    };
}
```

`context.boundary()` identifies the ingress point (`"service-dispatch"`, `"kafka"`, `"outbox"`, …). An initializer that finds its concern already bound should return a no-op scope rather than overwriting the decoded value. Throwing from `initialize` aborts the whole ingress installation and unwinds every scope already opened.

---

## Dependencies

| Dependency | Why |
|------------|-----|
| `dev.vertique:vertique-core` | `ContextHolder`, `ContextValue`, `ContextValueAdapter`, the encoder/decoder and initializer SPIs, `DurableMetadata`, `DurableMetadataHeaderCodec`, `DispatchEnvelope` |
| `io.vertx:vertx-core` | `ContextLocal`, the `VertxServiceProvider` bootstrap hook, and duplicated-context detection |
| `com.fasterxml.jackson.core:jackson-databind` | JSON serialization in `DurableJsonContextCodecs` |
| `com.google.dagger:dagger` | `ContextRuntimeModule` bindings and the five multibinding sets |
| `jakarta.inject:jakarta.inject-api` | `@Inject`, `@Singleton` |
| `jakarta.annotation:jakarta.annotation-api` | Annotation support on substrate types |
| `org.slf4j:slf4j-api` | Throttled decode-failure and boundary warnings |
