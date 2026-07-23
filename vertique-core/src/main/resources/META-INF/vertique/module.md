<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Core Module

> **Status:** Implemented
> **Package:** `dev.vertique.core`
> **Artifact:** `core`

---

## Overview

The core module is the foundation of the framework. All other modules depend on it. It provides:

- Dagger module for Vert.x instance and configuration wiring
- Qualifier annotation for typed configuration access
- `FailureMapper` — concrete, hierarchy-aware, context-aware `Throwable → Throwable` translator registry; usable directly by application code and extended by layer-specific mappers

### Package Layout

Security types (`SecurityContext`, `SecurityIdentity`, `AuthMethod`, the authz SPIs, `SecurityEventObserver`, etc.) live in the `vertique-security` family (`dev.vertique.security.*`). See Maven coordinates `dev.vertique:vertique-security-core` and `dev.vertique:vertique-security-runtime`.

| Package | Contents |
|---------|----------|
| `dev.vertique.core` | `VertxModule`, `VertxConfig`, `VertiqueRuntime`, `VertiqueComponentFactory` |
| `dev.vertique.core.failure` | `FailureMapper`, `FailureTranslator`, `ContextAwareFailureTranslator` |
| `dev.vertique.core.exception` | `VertiqueException`, `ValidationException`, `BusinessRuleException`, `ConflictException`, `NotFoundException`, `ConfigurationException`, `TechnicalException`, `UnavailableException` |
| `dev.vertique.core.eventbus` | `DispatchEnvelope<T>`, `DispatchMetadata`, `Result<T>`, `LocalMessageCodec`, `EventBusClient`, `DispatchContextValue`, `EventBusExceptionMapper`, `EventBusTimeoutException`, `EventBusAddressUnavailableException`, `EventBusDispatchException` |
| `dev.vertique.core.context` | API/SPI only — no runtime, no impl, no Dagger module: `ContextValue` (behavior-free marker interface; gates `ContextHolder` write path and the five context-producing SPIs), `ContextHolder` (SPI interface + `Scope`), `ContextScopes` (`noop()` factory), `ContextDecodeResult` + `ContextDecodeWarning`, `ContextValueAdapter` SPI, `DispatchBoundary` (boundary identifier constants), `DurableContextMetadataEncoder`/`Decoder` SPI, `DurableDecodeContext`/`DurableEncodeContext`, `DurablePropagationMetadata` (always-available namespaced `DurableMetadata` view), `DeferredExecutionOrigin` (concrete `ContextValue` record — deferred-execution provenance (`kind` + `reference`) bound by durable job/cron/outbox boundaries so the receive-side identity path can prove deferred execution before minting a service context), `InboundContextInitializer` SPI + `InboundContextInitializationContext`, `ServiceDispatchContextEncoder`/`Decoder` SPI, `ServiceDispatchDecodeContext`/`ServiceDispatchEncodeContext`; runtime impl lives in `vertique-context` |
| `dev.vertique.core.config` | `JsonConfigPaths`, `PropertyCondition`, `ConfigParser` (interface), `@ConfigMapper` (qualifier), `ConfigTreeBuilder`, `ConfigSecretRenderer` |
| `dev.vertique.core.util` | `TypeResolver`, `AnnotationResolver`, `GeneratedNames` |
| `dev.vertique.core.health` | `HealthCheck`, `HealthCheckModule` |
| `dev.vertique.core.json` | `JacksonConfigurer`, `ObjectMapperCustomizer`, `JsonModule`; **profile contracts** (runtime impl is in `vertique-json`): `JsonProfileId` (record value type; reserved `VERTX` constant; non-blank normalized value), `JsonMapperProfile` (SPI interface: `id()` + `mapper()`), `JsonMapperProfileRegistry` (interface: `mapper(JsonProfileId)`, `profile(JsonProfileId)`, `profileIds()`), `@JsonProfile` (`@Target({TYPE, METHOD})` — selects a named profile at a JAX-RS or other framework boundary), `JsonProfileConfigurationException` (extends `ConfigurationException`); **keyed-collection contract**: `@KeyedBy` (moved here from `core.config`; field annotation on `List<T>` record components — see below) |
| `dev.vertique.core.validation` | `BeanValidator`, `ViolationDetail`, `BeanValidationException`, `ParameterViolation`, `ValidateWith`; `CharacterPolicy`, `CharacterPolicyResult`, `CharacterPolicyBinding`, `@SkipAllowedCharacters` |
| `dev.vertique.core.sanitization` | `Canonicalizer`, `Sanitizer`, `@Canonicalize`, `@Sanitize`, `@SkipCanonicalization`, `@SkipSanitization`, `CanonicalizerBinding`, `SanitizerBinding`, `InputValueContext`, `InputLocation` |
| `dev.vertique.core.async` | `Futures` (future-channel utilities: `toFuture`, `reflect`, `settle`, `mapFailure`), `Combinators` (control-flow combinators over pre-ordered lists: `foldSequential`, `recoverFirstWins`, `joinAllSwallow`, `forEachSwallowSync`, `dispatchNoJoin`) |
| `dev.vertique.core.codegen` | `MethodMetadata`, `ParameterMetadata` — neutral, reflection-free metadata SPI for method-AOP and other codegen consumers (see below) |
| `dev.vertique.core.resilience` | `@CircuitBreaker`, `@Retry`, `@Timeout`, `BackoffStrategy`, `RetryPolicy`, `ResilienceAnnotations`, `BackoffStrategyResolver` |
| `dev.vertique.core.extension` | `OrderedExtension` (mix-in interface for deterministic extension ordering), `ExtensionPhase` (`SYSTEM_FIRST`, `APPLICATION`, `SYSTEM_LAST`) |
| `dev.vertique.core.lifecycle` | `LifecyclePhase` (8-value enum: `CONFIGURE`, `VALIDATE`, `MIGRATE`, `BOOTSTRAP`, `INFRA`, `SERVICES`, `EDGE`, `AFTER_START`; `isVerticlePhase()` true for the four-value verticle subset), `LifecycleOrdered` (shared ordering contract: `phase()`, `priority()`, `orderKey()`, `comparator()`), `ApplicationStartupStep` (extends `LifecycleOrdered`; `start() → Future<Void>`), `ApplicationShutdownStep` (extends `LifecycleOrdered`; `stop() → Future<Void>`), `ComposeValidator` (behavior-free marker; modules join the `Set<ComposeValidator>` multibinding to participate in VALIDATE-phase fail-fast wiring checks), `JacksonConfigureStep` (CONFIGURE-phase `ApplicationStartupStep`; delegates to `JacksonConfigurer.configure()`; contributed by `CoreLifecycleStepsModule`), `ComposeValidationStep` (VALIDATE-phase `ApplicationStartupStep`; materializes `Set<ComposeValidator>` at construction, running all constructor-time checks; contributed by `CoreLifecycleStepsModule`), `CoreLifecycleStepsModule` (abstract Dagger `@Module`; declares `@Multibinds Set<ComposeValidator>`; contributes `JacksonConfigureStep` and `ComposeValidationStep` `@IntoSet`) |

---

## Components

### VertxModule

Dagger `@Module` that provides the core Vert.x objects as injectable singletons.

```java
@Module
public class VertxModule {

    public VertxModule(Vertx vertx, @VertxConfig JsonObject jsonConfig) { ... }

    @Provides @Singleton
    public Vertx vertx() { ... }

    @Provides @Singleton
    public @VertxConfig JsonObject config() { ... }

    @Provides @Singleton
    public EventBus eventBus(Vertx vertx) { ... }
}
```

**Bindings provided:**

| Type | Qualifier | Description |
|------|-----------|-------------|
| `Vertx` | -- | The Vert.x instance |
| `JsonObject` | `@VertxConfig` | Application configuration (from `-conf` argument or deployment options) |
| `EventBus` | -- | The Vert.x event bus |

**Usage:**

```java
@Singleton
@Component(modules = { VertxModule.class, AppModule.class })
interface AppComponent {
    Vertx vertx();
    HttpVerticle httpVerticle();
}

// Building the component:
AppComponent component = DaggerAppComponent.builder()
    .vertxModule(new VertxModule(vertx, config()))
    .build();
```

### VertxConfig

Dagger qualifier annotation used to distinguish the application `JsonObject` configuration from other `JsonObject` instances.

```java
@Qualifier
@Retention(RUNTIME)
public @interface VertxConfig {}
```

Usage in Dagger modules — inject `ConfigParser` and parse a section into a typed config record:

```java
@Provides
HelloConfig helloConfig(@VertxConfig JsonObject config, ConfigParser parser) {
    return parser.parse(JsonConfigPaths.navigateObject(config, "hello"), HelloConfig.class);
}
```

The injected `ConfigParser` uses an isolated, coercion-lenient `ObjectMapper` (see `ConfigParser` below). Never use `JsonObject.mapTo` or raw `@Named` scalar reads at the Dagger boundary. The `ConfigParser` binding is provided by `ConfigParsingModule` (in `vertique-config-core`) — include it once in the application `@Component`.

### VertiqueRuntime

Container-neutral, immutable record carrying the two inputs every bootstrap path must provide to build the application's dependency-injection graph: the `Vertx` instance and the root application configuration `JsonObject`.

```java
public record VertiqueRuntime(Vertx vertx, JsonObject config) {
    // compact constructor rejects null
    public static VertiqueRuntime of(Vertx vertx, JsonObject config) { ... }
}
```

`VertiqueRuntime` is the single seam every bootstrap path — standalone launcher or an embedding host bridge — funnels through. It deliberately exposes no Dagger types, no host concept (Spring `ApplicationContext`, Quarkus `Arc`), and no service locator. On the standalone path the values flow straight into `new VertxModule(rt.vertx(), rt.config())`. An embedding host assembles a `VertiqueRuntime` from host-native equivalents and passes it to a `VertiqueComponentFactory`.

#### Invariants & Gotchas

- Both components are required. The compact constructor calls `Objects.requireNonNull` on both; passing `null` for either throws `NullPointerException` immediately.
- `of(vertx, config)` is a static factory delegating to the canonical constructor — it is the preferred construction path so call sites read fluently.

### VertiqueComponentFactory\<C\>

`@FunctionalInterface` supplied by the application or a host bridge. Receives a `VertiqueRuntime` and returns the built Dagger `@Component` of type `C`.

```java
@FunctionalInterface
public interface VertiqueComponentFactory<C> {
    C build(VertiqueRuntime runtime);
}
```

The factory owns the knowledge of which modules to wire. On the standalone path a typical factory constructs `new VertxModule(rt.vertx(), rt.config())` alongside application modules and returns the generated `DaggerXxx` component. An embedding host (Spring, Quarkus) provides a factory that additionally wires typed Dagger adapter modules `@Provides`-ing host beans — there is no host-bean service locator; a missing adapter is a compile-time missing-binding error (see ADR-0127).

**Standalone example:**

```java
VertiqueComponentFactory<AppComponent> factory = rt ->
    DaggerAppComponent.builder()
        .vertxModule(new VertxModule(rt.vertx(), rt.config()))
        .build();
AppComponent component = factory.build(VertiqueRuntime.of(vertx, config()));
```

### FailureMapper

Concrete, hierarchy-aware, context-aware translator registry. Usable directly by application code for ad-hoc contextual translation, **extended** by the REST/services/DB layer mappers (`RestExceptionMapper`, `ServiceExceptionMapper`, `DbExceptionMapper`) — which pre-register translators and may override `fallback` — and **composed** by `DefaultRestClientExceptionMapper` (the REST client owns its mapper per-client rather than subclassing).

```java
public class FailureMapper {
    public FailureMapper() { ... }

    // Register translators
    public <T extends Throwable> FailureMapper on(Class<T> type, FailureTranslator<T> translator) { ... }
    public <T extends Throwable> FailureMapper on(Class<T> type, ContextAwareFailureTranslator<T> translator) { ... }

    // Translate — context is passed to ContextAwareFailureTranslator; plain translators ignore it
    public Throwable translate(Throwable throwable, String context) { ... }
    public Throwable translate(Throwable throwable) { ... }   // context defaults to getMessage()

    // Lookup — public so subclasses and tests can inspect registered translators
    public FailureTranslator<?> findTranslator(Class<? extends Throwable> exceptionClass) { ... }

    // Override point for subclasses — default returns the throwable unchanged
    protected Throwable fallback(Throwable throwable, String context) { ... }
}
```

- Walks the superclass chain to find the most specific registered translator; caches lookups in a `ConcurrentHashMap` for performance.
- At translate time, checks `instanceof ContextAwareFailureTranslator` and passes the context string only to context-aware translators; plain translators simply receive the throwable.
- The two `on(...)` overloads accept both translator flavors without ambiguity — a 2-arg lambda binds to the context-aware overload, a 1-arg lambda to the plain one.
- `fallback` is the result when no translator matches. Default returns the throwable unchanged; `DbExceptionMapper` overrides it to wrap in `DataAccessException`.

#### Invariants & Gotchas

- Registering a translator for a type that already has one is **last-wins** and clears the lookup cache — higher-priority customizers can override framework defaults.
- `translate(Throwable)` dispatches on the runtime type, so behaviour is correct regardless of which `on(...)` overload was used for registration.
- `findTranslator` returns `null` (not a no-op translator) when no translator is found anywhere in the hierarchy up to (but not including) `Object`.

### FailureTranslator

```java
@FunctionalInterface
public interface FailureTranslator<T extends Throwable> {
    Throwable translate(T throwable);
}
```

### ContextAwareFailureTranslator

Context-carrying variant of `FailureTranslator`. The SAM is `translate(T, String context)`, where `context` is a string describing the failed operation (e.g., `"save user"`). Extends `FailureTranslator` so both types share one registry; the inherited no-context `translate(T)` defaults the context to `throwable.getMessage()`.

```java
@FunctionalInterface
public interface ContextAwareFailureTranslator<T extends Throwable> extends FailureTranslator<T> {
    Throwable translate(T throwable, String context);

    @Override
    default Throwable translate(T throwable) {
        return translate(throwable, throwable.getMessage());
    }
}
```

Register with a `FailureMapper` via the context-aware `on(...)` overload:

```java
var mapper = new FailureMapper();
mapper.on(DatabaseException.class, (e, ctx) -> new DataAccessException(ctx, e));
```

---

## Codegen Metadata SPI (`dev.vertique.core.codegen`)

`MethodMetadata` and `ParameterMetadata` are the neutral, reflection-free metadata contracts shared across codegen consumers and reflective runtime scanners — `vertique-aop` (via `vertique-codegen-aop`), `rest-client`, and `rest-jaxrs`. Placing them in `vertique-core` rather than in a codegen or AOP module ensures that any runtime module can declare a dependency on the metadata types without taking an AOP or annotation-processor compile dependency. See ADR-0141 and ADR-0143.

### `MethodMetadata`

Read-only descriptor of an intercepted or scanned method. Backed by either a codegen-generated literal implementation (e.g. a concrete inner class of `Bean$AopProxy`, constructed at compile time) or, for the runtime scanner path, `ReflectiveMethodMetadata` (below).

| Method | Description |
|--------|-------------|
| `name()` | Simple method name |
| `declaringClass()` | The class that declares the method |
| `returnType()` | Erased return type |
| `parameters()` | Ordered list of `ParameterMetadata` |
| `findAnnotation(Class<A>)` | Returns the named annotation literal from the generated literal set, or `null` |
| `hasAnnotation(Class<?>)` | Returns `true` if the annotation type is present on the method |

The reflection-free group (`name()`, `declaringClass()`, `returnType()`, `parameters()`) is guaranteed safe on the critical path with no `reflect-config.json` requirement. The reflective-accessor group (`asMethod()`, `genericReturnType()`, `ParameterMetadata.genericType()`) is present for aspect authors who need full generic-type information; callers that use these methods require a GraalVM `reflect-config.json` entry for the declaring class.

### `ParameterMetadata`

Read-only descriptor of a single method parameter.

| Method | Description |
|--------|-------------|
| `index()` | Zero-based parameter index |
| `name()` | Parameter name (from source; may be synthetic if compiled without `-parameters`) |
| `type()` | Erased parameter type |
| `findAnnotation(Class<A>)` | Returns the named annotation literal, or `null` |
| `hasAnnotation(Class<?>)` | Returns `true` if the annotation type is present |
| `genericType()` | Reflective-accessor group: generic parameter type |
| `annotationsLazy()` | Reflective-accessor group: `Supplier<Annotation[]>` of the parameter's full declared annotation array; `default` returns an empty array |

Parameter-level `findAnnotation`/`hasAnnotation` are literal-backed: `MetadataEmitter` (`vertique-codegen-core`) emits a parameter-level `<Ann>$Literal` constant for each `@Retention(RUNTIME)` parameter annotation and resolves the lookup by `annotationType()` match, performing no reflective read — the same bounded-attribute-kind gate applies to every consumer of `MetadataEmitter`. For the core/AOP consumer path (`vertique-codegen-aop`'s `AopProxyEmitter`, curated author-controlled aspect-trigger annotations), an unsupported attribute kind (`char`/`float`/`double`, a nested-annotation member, or an array of either) is a **compile-time error** — this remains fail-fast and is not softened. The JAX-RS codegen emitters instead fall back to reflection per-parameter for annotations `AnnotationLiteralEmitter` cannot render, rather than failing the build — see ADR-0146. `annotationsLazy()` is the opt-in reflective bridge: it is never called by generated AOP proxy code, and exists so the JAX-RS `ParamConverterProvider` bridge in `ParamConversionResolver` (rest-jaxrs) can materialize the full `Annotation[]` only when a provider set is actually non-empty.

### `ReflectiveMethodMetadata` / `ReflectiveParameterMetadata`

Reflection-backed implementations of `MethodMetadata`/`ParameterMetadata` for the runtime (non-codegen) scanner path — e.g. `rest-client`'s `ClientInterfaceScanner`. `ReflectiveMethodMetadata` wraps a live `Method` and a pre-resolved `List<ParameterMetadata>`; all accessors delegate to `Method` reflection. `ReflectiveParameterMetadata` is built from the parameter position, name, erased/generic type, and a `@Nullable AnnotatedElement` annotation source (a `Parameter` for top-level parameters, or a bean-field `Field`/`RecordComponent`/accessor `Method` for `@BeanParam` sub-fields); a `null` source makes every lookup behave as if no annotations are present.

Both classes are `public` so the JAX-RS and REST-client scan paths can construct them, but they are otherwise an internal implementation detail of those scanners — application code should not implement `MethodMetadata`/`ParameterMetadata` directly. `vertique-rest-jaxrs`'s reflective scan path (`ResourceScanner`) also constructs `ReflectiveParameterMetadata` directly — via a small internal `AnnotatedElement` adapter on `ResourceMethodMeta.ParamMeta`'s `Annotation[]`-based convenience constructor — rather than keeping a separate jaxrs-local copy; the earlier duplicate class (with a different, pre-captured-`Annotation[]` constructor contract) was deleted in favor of this shared core implementation (ADR-0146). This is a distinct reflective path from the jaxrs *codegen* emitters' own per-parameter reflective fallback (`dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsReflectiveAnnotations`, used only when an annotation's members cannot be literal-backed) — `ResourceScanner` is the fully-reflective scan path with no compile-time literals at all, while the codegen emitters are literal-first with reflection reserved for the unliteralizable minority of annotations.

#### Invariants & Gotchas

- Codegen-generated `MethodMetadata`/`ParameterMetadata` implementations (e.g. inner classes of `Bean$AopProxy`) are not hand-authored and must not be implemented by application code.
- Codegen-path `findAnnotation` / `hasAnnotation` query the compile-time-captured annotation literals, not live runtime annotations. An annotation added to a method after compilation (e.g., via a dynamic proxy or bytecode agent) is invisible. The reflective scanner path (`ReflectiveMethodMetadata`/`ReflectiveParameterMetadata`) queries live annotations instead, since it has no compile-time literal set to bake.
- `asMethod()` and `ParameterMetadata.genericType()` use reflection. Calling them on a GraalVM native image requires a `reflect-config.json` entry for the declaring class. The framework's built-in `@Timed` aspect never calls these methods — they exist for advanced user-authored aspects only.
- `annotationsLazy()` defaults to an empty-array supplier so existing `ParameterMetadata` implementations keep compiling without overriding it; only consumers that need the full annotation array (the JAX-RS provider bridge) call it, and only when a provider set is non-empty.

---

## Async Utilities (`dev.vertique.core.async`)

Framework-internal utilities for asynchronous control flow over pre-ordered lists. Both types are
`public final` with private constructors — all methods are static. They are public for cross-module
use; they are **not** part of the extension SPI and are not meant to be called from application
code.

The core principle shared by all combinators and utilities: **the list is accepted pre-ordered and
never reordered**. The caller is responsible for ordering; the utilities preserve that order.
Per-item failure handling (log, metric, continue-vs-propagate) lives in the caller's lambda, not
in the utility — each utility provides a uniform failure channel and routes every failure through
it, but the response to that failure is the caller's.

The interceptor/observer runtime across services, rest (operation, response, and error pipelines),
rest-client, kafka, job, security-event, and audit-sink all delegate to these utilities.

### Futures

Utilities for transforming the Vert.x `Future` failure channel.

| Method | Signature | Contract |
|--------|-----------|----------|
| `toFuture` | `Result<T> → Future<T>` | Converts a `Result` into a Vert.x future: success → `succeededFuture`, failure → `failedFuture`. |
| `reflect` | `Future<T> → Future<Result<T>>` | Folds a failure into the value channel as `Result.failure`; the returned future always succeeds. |
| `settle` | `Collection<Future<T>> → Future<List<Result<T>>>` | Waits for every future to settle, materializing each outcome as a `Result` in input order. Never short-circuits on failure — analogous to `Promise.allSettled`. |
| `mapFailure` | `(Future<T>, Throwable → Throwable) → Future<T>` | Translates the failure of a future without affecting successful values. |

`settle` is the foundation of `Combinators.joinAllSwallow`: it provides the all-settled join
guarantee.

### Combinators

Reusable asynchronous control-flow combinators over pre-ordered lists. Every combinator converts a
synchronous throw from a caller-supplied callback into the same failure channel as an asynchronous
failure, so a raw exception never escapes.

#### `foldSequential`

```java
public static <I, T> Future<T> foldSequential(
        List<I> items, T seed, BiFunction<I, T, Future<T>> step)
```

Sequential value/context fold over a pre-ordered list. `seed` is threaded into the first step;
each step's resulting value is passed to the next. The first failing step short-circuits the fold —
no remaining step is invoked. The continue-vs-propagate policy on failure lives in the caller's
`step` lambda (e.g. calling `.recover(...)` to substitute the prior value); it is not a combinator
concern. An empty list yields a succeeded future carrying `seed`.

Used by: the sequential interceptor folds in rest-jaxrs (operation/response/error pipelines),
rest-client, kafka, and services dispatch.

#### `recoverFirstWins`

```java
public static <I, R> Future<R> recoverFirstWins(
        List<I> items, Throwable error,
        BiFunction<I, Throwable, Future<R>> step,
        Predicate<Throwable> nonRecoverable)
```

Ordered first-wins recovery chain. The current failure is threaded to each recoverer in turn; the
first step that succeeds wins, and all later items are skipped. Before every step — including the
very first — the current failure is tested against `nonRecoverable`; a `true` result short-circuits
the chain immediately. A recoverer that itself fails replaces the current failure with its new
failure, which is then offered to the next recoverer. When no recoverer succeeds the returned
future fails with the latest (last-produced) failure, not the original `error`.

Used by: the first-wins recovery chains in services, rest-jaxrs (error/operation pipelines),
kafka, and rest-client.

#### `joinAllSwallow`

```java
public static <I> Future<Void> joinAllSwallow(
        List<I> items, Function<I, Future<?>> hook, BiConsumer<I, Throwable> onFailure)
```

Launches all hooks and completes only after every one has settled (built on `Futures.settle`).
The returned future always succeeds. Every per-item failure — asynchronous failure, synchronous
throw, or a `null` future returned by `hook` (treated as `NullPointerException`) — is routed to
`onFailure` and never aborts the fan-out or fails the join.

Used by: the wait-for-all fan-outs shared across the interceptor/observer line — `ServiceMethodInvoker`
`afterDispatch` and security-event notification (`SecurityEventEmitter`).

#### `forEachSwallowSync`

```java
public static <I> void forEachSwallowSync(
        List<I> items, Consumer<I> hook, BiConsumer<I, Throwable> onFailure)
```

Synchronous loop over a pre-ordered list. A per-item `Exception` is routed to `onFailure`
(an `Error` propagates) and iteration continues with the next item. Never throws an `Exception`.

Used by: synchronous observer callbacks (e.g. `onComplete`/`onError` fire-and-observe hooks).

#### `dispatchNoJoin`

```java
public static <I> void dispatchNoJoin(
        List<I> items, Function<I, Future<?>> hook, BiConsumer<I, Throwable> onFailure)
```

Fire-and-forget async dispatch. All hooks are launched in list order; this method returns
immediately without joining on their completion. Every per-item failure — asynchronous failure,
synchronous throw, or a `null` future returned by `hook` (treated as `NullPointerException`) — is
routed to `onFailure`. Because there is no join, the caller receives no aggregate completion
signal. This is the no-wait variant of `joinAllSwallow`.

Used by: the audit sink fan-out (`DefaultAuditPipeline`), which must not block the pipeline result.

Used by: fire-and-forget observer hooks (e.g. dispatching async audit events with no back-pressure
requirement).

#### Invariants & Gotchas

- All combinators accept a **pre-ordered `List` and never sort**. The iteration order of the
  supplied list is the execution order. If ordering matters (e.g. `SYSTEM_FIRST` before
  `APPLICATION`), the caller must sort before calling.
- Per-site failure policy — what to log, which metric to increment, whether to continue or
  propagate — lives in the caller's `onFailure` / `step` lambda, not in the combinator. The
  combinators provide uniform failure routing, not failure handling.
- These are framework-internal utilities: `public` for cross-module access, but not part of the
  extension SPI. Application code should use Vert.x `Future` composition directly.

### JsonModule

Abstract Dagger `@Module` that declares the `ObjectMapperCustomizer` multibinding set. Included automatically by `RestCoreModule`. Non-REST applications can include it directly in their `@Component`.

```java
@Module
public abstract class JsonModule {
    @Multibinds
    abstract Set<ObjectMapperCustomizer> objectMapperCustomizers();
}
```

### ObjectMapperCustomizer

`@FunctionalInterface` extension point for customizing Jackson's `ObjectMapper`. Implementations are collected via Dagger `Set<ObjectMapperCustomizer>` multibinding and applied by `JacksonConfigurer` at startup.

`ObjectMapperCustomizer extends OrderedExtension`. Customizers are sorted by `OrderedExtension.comparator()` (phase → priority → orderKey) before application. Lower `priority()` values execute first (default `0`).

```java
@FunctionalInterface
public interface ObjectMapperCustomizer extends OrderedExtension {
    void customize(ObjectMapper mapper);
}
```

**Contributing a customizer:**

```java
// Register Java Time support (priority 0, runs before any higher-priority customizers)
@Provides @IntoSet
static ObjectMapperCustomizer javaTimeSupport() {
    return mapper -> {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    };
}

// Disable unknown property failures (priority 100 = runs after priority-0 customizers)
@Provides @IntoSet
static ObjectMapperCustomizer lenientDeserialization() {
    return new ObjectMapperCustomizer() {
        @Override public void customize(ObjectMapper mapper) {
            mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        }
        @Override public int priority() { return 100; }
    };
}
```

### JacksonConfigurer

`@Singleton` that applies all registered `ObjectMapperCustomizer` instances to `DatabindCodec.mapper()` (the Vert.x shared `ObjectMapper`). Customizers are sorted by `OrderedExtension.comparator()` before application. Idempotent — subsequent calls log a WARN and return immediately.

```java
@Singleton
public class JacksonConfigurer {
    @Inject JacksonConfigurer(Set<ObjectMapperCustomizer> customizers) { ... }
    public void configure() { ... }   // applies customizers to DatabindCodec.mapper()
}
```

**Lifecycle:** Call `configure()` once after the Dagger component is created, before deploying any verticles. This ensures the `ObjectMapper` is fully configured before any JSON serialization or deserialization occurs.

```java
// config() is pre-resolved by VertiqueApplication before the application verticle starts
AppComponent c = DaggerAppComponent.builder()
    .vertxModule(new VertxModule(vertx, config()))
    .build();

c.jacksonConfigurer().configure();           // configure ObjectMapper first
return c.verticleDeploymentManager().deployAll();  // then deploy verticles
```

The `AppComponent` must expose `JacksonConfigurer jacksonConfigurer()` for this call to compile.

> **Note:** When using `@VertiqueApp` on a `@Component extends VertiqueApplicationComponent` with `CoreLifecycleStepsModule`, the `JacksonConfigureStep` runs automatically during the `CONFIGURE` phase — no manual `c.jacksonConfigurer().configure()` call is needed. The manual call above applies to the legacy `MainVerticle` pattern.

---

### Exception Hierarchy (`dev.vertique.core.exception`)

Base exception classes for the unified framework exception hierarchy. All framework modules extend from these roots.

```
VertiqueException (RuntimeException)
├── ValidationException              — invalid input or data; maps to HTTP 400
│   ├── BusinessRuleException        — business-rule / domain-rule violations; maps to HTTP 400
│   │   (extended by WorkflowException in workflow-core)
│   ├── MalformedDurableMetadataException — durable context carrier/namespace body present but not
│   │   a JSON object; thrown by DurableMetadata.fromCarrier / fromJson at decode time
│   └── (extended by DbValidationException in db.exception)
├── ConflictException                — resource state conflict; maps to HTTP 409
│   (extended by WorkflowConflictException in workflow-core)
├── NotFoundException                — resource absent; maps to HTTP 404
│   (extended by WorkflowNotFoundException in workflow-core)
├── ConfigurationException           — startup/wiring/contract errors
│   (extended by RestConfigurationException in rest.core,
│                ServiceConfigurationException in services,
│                WorkflowConfigurationException in workflow-core)
├── TechnicalException               — runtime/infrastructure failures; maps to HTTP 500
│   ├── UnavailableException         — runtime capability currently unavailable; maps to HTTP 503
│   │   (extended by WorkflowUnavailableException in workflow-core,
│   │                ServiceUnavailableException in services)
│   └── (extended by DataAccessException in db.exception)
└── VertiqueSecurityException        — concrete grouping root for security-domain exceptions;
    │                                  not thrown directly; no default REST mapping (falls to 500)
    ├── UnauthorizedException        — authentication required or credential invalid; maps to HTTP 401
    └── ForbiddenException           — authenticated but not authorized; maps to HTTP 403
```

All named roots are **concrete** — they may be thrown directly or subclassed. `VertiqueSecurityException`
is the grouping root for the security family; callers may catch it to handle any security denial, but
application and module code must throw `UnauthorizedException` or `ForbiddenException` rather than
the grouping root directly. Leaf exceptions in application and module code should extend the most
specific applicable semantic root rather than throwing a root type directly when a more precise
subtype exists.

`DefaultExceptionMapper` in `rest-jaxrs` maps core types to HTTP status codes automatically:
- `ValidationException` (and `BusinessRuleException`) → 400
- `ConflictException` → 409
- `NotFoundException` → 404
- `UnauthorizedException` → 401 (registered fully-qualified as `dev.vertique.core.exception.UnauthorizedException`)
- `ForbiddenException` → 403 (registered fully-qualified as `dev.vertique.core.exception.ForbiddenException`, to avoid clash with `jakarta.ws.rs.ForbiddenException`)
- `UnavailableException` → 503
- `Throwable` (fallback) → 500
- `VertiqueSecurityException` itself is **not** mapped — a bare instance yields 500

REST default mappings reference only core, JAX-RS, and REST-owned types — no DB module types.
See ADR-0112 for the full layered
mapping rule, API-semantic-root pattern, and message-sanitization invariant.

---

## Context Propagation (`dev.vertique.core.context`)

This package contains only the **public API and SPI contracts** for context propagation. The runtime
implementation (holder, registries, propagator, lifecycle helpers, encoder/decoder factories) lives
in `vertique-context`. MDC types (`MDCContext`, `MDCContexts`, `DiagnosticContextSnapshot`,
`MDCContextValueAdapter`, `LoggingContextModule`) live in `vertique-logging`.

See Maven coordinate `dev.vertique:vertique-context` for the full substrate reference.

### SPI contracts

| Type | Role |
|---|---|
| `ContextValue` | Behavior-free marker interface that gates the `ContextHolder` write path. All nine framework context types implement it. Every typed write entry point (`ContextHolder.bind`, `ContextValues.bind`/`mutate`/`mutateIfPresent`, `ContextScopeBinder.bindAll`) is bounded `<T extends ContextValue>` at compile time; erased reinstatement paths carry a runtime `requireContextValue` pre-pass. The five context-producing SPIs (`ServiceDispatchContextEncoder/Decoder`, `DurableContextMetadataEncoder/Decoder`, `ContextValueAdapter`) are also bounded `<T extends ContextValue>` on their context-type parameter. |
| `ContextHolder` + `ContextHolder.Scope` | Request-scoped holder SPI. `current(Class)` reads (unbounded — no `ContextValue` required); `bind(Class<T extends ContextValue>, T)` returns an `AutoCloseable` `Scope`. |
| `ContextScopes` | Factory for no-op scopes (`ContextScopes.noop()`). |
| `ContextValueAdapter` | SPI for deep-copy semantics on `duplicate(true)`. Bounded `<T extends ContextValue>` on context-type parameter. Discovered via `ServiceLoader`. |
| `ContextDecodeResult` + `ContextDecodeWarning` | Decode return type carrying `Optional<T>` value plus a list of warnings instead of throwing. |
| `DispatchBoundary` | Constants (`SERVICE_DISPATCH`, `KAFKA`, `OUTBOX`, `OUTBOX_SERVICE`) used as boundary identifiers in encode/decode contexts. |
| `DurableContextMetadataEncoder<T extends ContextValue>` / `Decoder<T extends ContextValue>` | SPI for encoding/decoding typed values to/from a `DurableMetadata` namespaced JSON document. Context-type parameter is bounded `<T extends ContextValue>`; `DurableMetadata` wire type is unbounded. Each encoder declares a `String namespace()` and exchanges `DurableMetadata` via `encode(T, DurableEncodeContext)` / `ContextDecodeResult<T> decode(DurableMetadata, DurableDecodeContext)`. See ADR 0065. |
| `DurableDecodeContext` / `DurableEncodeContext` | Context objects passed to durable encoder/decoder calls. |
| `DurablePropagationMetadata` | Always-available namespaced `DurableMetadata` document view of the durable context for a consumer. Has a built-in service-dispatch encoder/decoder pair (registered in `ContextRuntimeModule`) so it travels across in-process hops. |
| `InboundContextInitializer` SPI + `InboundContextInitializationContext` | SPI called by `InboundExecutionContextScope` at every inbound dispatch and durable receive boundary. |
| `ServiceDispatchContextEncoder<T extends ContextValue>` / `Decoder<T extends ContextValue>` | SPI for encoding/decoding typed values to/from the `DispatchEnvelope.metadata().dispatchContext()` carrier. Context-type parameter is bounded `<T extends ContextValue>`; the wire-envelope type parameter is unbounded. |
| `ServiceDispatchDecodeContext` / `ServiceDispatchEncodeContext` | Context objects passed to service-dispatch encoder/decoder calls. |

### Read-lenient / write-fail-fast invariant

`DefaultContextHolder.requireDuplicatedContextForWrite()` (in `vertique-context`) is the single
enforcement point. All static write helpers in `ContextValues` and `MDCContexts` route through it.
Read helpers (`current`, `snapshot`, `get`, `copy`) tolerate a missing or non-duplicated context.

### Decode-time carrier validation (`DurableMetadata`)

`DurableMetadata` (in `core.context`) rejects a structurally malformed carrier or namespaces
document at decode time rather than deferring the failure to a later, unclassified crash when a
specific decoder or `merge` reads the malformed namespace:

- `fromCarrier(JsonObject)` rejects a carrier whose `context` key is present but whose value is not
  a JSON object.
- `fromJson(JsonObject)` rejects a namespaces document containing any namespace body that is present
  but is not a JSON object.

Both throw `MalformedDurableMetadataException`. Validation is **namespace-body-level only** — it
confirms each namespace's top-level value is a JSON object, not that the object's nested fields
match a particular shape. Validating a namespace body's internal structure is the concern of that
namespace's own `DurableContextMetadataDecoder`, not of `DurableMetadata`.

---

## Bean Validation Types (`dev.vertique.core.validation`)

HTTP-agnostic validation API and result types. Implemented by the `validation` module; consumed by `rest-jaxrs` (REST layer) and can be used directly in service or event-bus handler code.

### BeanValidator

Interface for validating objects and method parameters against Jakarta Bean Validation constraints. Implementations are provided by the `validation` module.

```java
public interface BeanValidator {
    // Validates object against default group; throws BeanValidationException on violation
    <T> void validate(T object);
    <T> void validate(T object, Class<?>... groups);

    // Non-throwing: returns list of violations (empty = valid)
    <T> List<ViolationDetail> check(T object);
    <T> List<ViolationDetail> check(T object, Class<?>... groups);

    // Method-level parameter validation — returns per-parameter violations
    List<ParameterViolation> checkParameters(Object instance, Method method, Object[] args, Class<?>... groups);

    // Throws BeanValidationException if any parameter constraints are violated
    void validateParameters(Object instance, Method method, Object[] args, Class<?>... groups);
}
```

**Service-layer usage:**

```java
@Inject BeanValidator validator;

public void processOrder(OrderRequest request) {
    validator.validate(request); // throws BeanValidationException if invalid
    // ... business logic
}
```

### ViolationDetail

Immutable record describing a single constraint violation. The invalid value is intentionally excluded to prevent leakage of sensitive data.

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViolationDetail(
    String path,                   // property path (e.g. "name", "address.city")
    String message,                // interpolated constraint message
    @Nullable String type,         // classified type: "required", "size", "min", "pattern", etc.
    @Nullable Map<String, Object> args  // constraint arguments (e.g. {min: 1, max: 100} for @Size)
) {
    public static ViolationDetail of(String path, String message) { ... }
}
```

`type` is derived from the constraint annotation by the `validation` module's `ViolationTypeMapping` SPI. `args` is populated by `ViolationArgsInspector`. Both are `null` when the `validation` module is not configured with type/args resolution.

### BeanValidationException

Thrown when Bean Validation constraints are violated. Extends `ValidationException` and maps to HTTP 400 by default.

```java
public class BeanValidationException extends ValidationException {
    public BeanValidationException(String message, List<ViolationDetail> violations) { ... }
    public BeanValidationException(String message, List<ViolationDetail> violations, Throwable cause) { ... }
    public List<ViolationDetail> violations() { ... }  // unmodifiable; defensively copied
}
```

### ParameterViolation

Record pairing a zero-based parameter index with a `ViolationDetail`. Used by `BeanValidator.checkParameters()` so callers (e.g. the REST framework) can map violations to HTTP locations (body, query, header, etc.).

```java
public record ParameterViolation(
    int parameterIndex,   // zero-based; -1 if index could not be determined
    ViolationDetail detail
) {}
```

### @ValidateWith

Method-level annotation specifying which Jakarta Bean Validation groups to apply for that operation. When absent, the default validation group is used. Supported on JAX-RS resource methods and event-bus service methods.

```java
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidateWith {
    Class<?>[] value();  // validation groups; empty array = default group
}
```

**Example:**

```java
interface Create {}
interface Update {}

@POST @Path("/users")
@ValidateWith(Create.class)
public Future<User> createUser(@Valid CreateUserRequest request) { ... }

@PUT @Path("/users/{id}")
@ValidateWith(Update.class)
public Future<User> updateUser(@PathParam("id") String id, @Valid UpdateUserRequest request) { ... }
```

---

## Sanitization Contract Types (`dev.vertique.core.sanitization`)

Annotation model and SPI contracts for input canonicalization and sanitization. These types define the API; implementations live in the `vertique-sanitization` module.

### Canonicalizer

Functional interface for semantics-preserving string normalization. Implementations must be deterministic, idempotent, semantics-preserving, stateless, and thread-safe.

```java
@FunctionalInterface
public interface Canonicalizer {
    String canonicalize(String value, InputValueContext context);
}
```

### Sanitizer

Functional interface for content-removing string transformation (not semantics-preserving). Implementations must be stateless and thread-safe.

```java
@FunctionalInterface
public interface Sanitizer {
    String sanitize(String value, InputValueContext context);
}
```

### @Canonicalize / @Sanitize

Declare an ordered chain of `Canonicalizer` or `Sanitizer` implementations to apply to the annotated element. Applicable to TYPE, FIELD, RECORD_COMPONENT, PARAMETER, METHOD, and ANNOTATION_TYPE (for composed annotations).

```java
// Route-level: applied to all string values in all requests to this resource
@Path("/users")
@Canonicalize({TrimCanonicalizer.class, NfkcCanonicalizer.class})
public class UserResource { ... }

// Field-level
public record CreateUserRequest(
    @Canonicalize(TrimCanonicalizer.class)
    @Sanitize(BasicHtmlSanitizer.class)
    String bio
) {}
```

### @SkipCanonicalization / @SkipSanitization

Marker annotations for opting a field or record component out of inherited processing. Mutually exclusive with `@Canonicalize` / `@Sanitize` on the same element.

### CanonicalizerBinding / SanitizerBinding

Dagger multibinding wrapper records. The `type()` field acts as the lookup key when the runtime resolves processors declared in `@Canonicalize` / `@Sanitize`.

```java
@Provides @IntoSet
static CanonicalizerBinding myCanonicalizer(MyCanonicalizer c) {
    return new CanonicalizerBinding(MyCanonicalizer.class, c);
}

@Provides @IntoSet
static SanitizerBinding mySanitizer(MySanitizer s) {
    return new SanitizerBinding(MySanitizer.class, s);
}
```

### InputValueContext

Immutable record carrying contextual metadata passed to every `Canonicalizer` and `Sanitizer` invocation.

```java
public record InputValueContext(
    InputLocation location,   // HTTP request origin (BODY, QUERY, HEADER, PATH, COOKIE, FORM, BEAN_PARAM)
    String path,              // dot-separated property path (e.g., "address.city")
    String logicalName,       // parameter or field name
    Class<?> ownerType        // declaring class of the field or parameter
) {}
```

### InputLocation

Enum identifying where in the HTTP request a string value originated.

| Constant | Description |
|----------|-------------|
| `BODY` | JSON or structured request body |
| `QUERY` | Query parameter |
| `HEADER` | Request header |
| `PATH` | Path parameter |
| `COOKIE` | Cookie value |
| `FORM` | Form field (form-urlencoded or multipart) |
| `BEAN_PARAM` | Value aggregated via JAX-RS `@BeanParam` container |

---

## Character Policy Types (`dev.vertique.core.validation`)

Character-set validation contracts used by `@AllowedCharacters` (defined in the `validation` module).

### CharacterPolicy

Interface for validating that a string contains only permitted characters. Implementations must be stateless and thread-safe.

```java
public interface CharacterPolicy {
    CharacterPolicyResult validate(String value, InputValueContext context);
}
```

### CharacterPolicyResult

Immutable result record returned by `CharacterPolicy.validate()`. Use static factories:

```java
CharacterPolicyResult.passed()                          // all characters permitted
CharacterPolicyResult.failed(index, codePoint, reason)  // first offending character
```

### CharacterPolicyBinding

Dagger multibinding wrapper for contributing `CharacterPolicy` instances to `ValidationModule`'s resolution chain.

```java
@Provides @IntoSet
static CharacterPolicyBinding myPolicy(MyCharacterPolicy p) {
    return new CharacterPolicyBinding(MyCharacterPolicy.class, p);
}
```

### @SkipAllowedCharacters

Marker annotation for opting a field out of `@AllowedCharacters` validation inherited from the class level.

---

## Resilience Types (`dev.vertique.core.resilience`)

Shared resilience primitives consumed by `rest-client` and `services`. Centralizing these types in `core` avoids duplication and lets other modules reference them without taking a dependency on either consumer module.

### `@CircuitBreaker`

Enables circuit breaker protection on an interface or individual method. Applicable to TYPE and METHOD.

```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CircuitBreaker {
    int maxFailures() default 5;
    long timeoutMs() default -1;       // -1 = inherit consumer module default
    long resetTimeoutMs() default 10_000;
}
```

### `@Retry`

Configures retry behavior on an interface or method. Applicable to TYPE and METHOD.

```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Retry {
    int maxRetries() default 3;
    long delayMs() default 500;
    double backoffMultiplier() default 2.0;
    long maxDelayMs() default 30_000;
    Class<? extends BackoffStrategy> backoff() default BackoffStrategy.Default.class;
    Class<? extends Throwable>[] retryOn() default {};
    Class<? extends Throwable>[] abortOn() default {};
}
```

### `@Timeout`

Per-method (or type-level default) timeout override. Applicable to TYPE and METHOD.

```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Timeout {
    long value();                          // required; must be positive
    TimeUnit unit() default TimeUnit.MILLISECONDS;
}
```

### `BackoffStrategy`

Functional interface that computes the delay in milliseconds before each retry attempt. `delay(int retryCount)` receives the 0-based retry count.

```java
@FunctionalInterface
public interface BackoffStrategy {
    long delay(int retryCount);

    static BackoffStrategy exponential(long delayMs, double multiplier, long maxDelayMs) { ... }
    static BackoffStrategy fixed(long delayMs) { ... }
    static BackoffStrategy none() { ... }

    /** Sentinel used in @Retry#backoff — signals "use the consumer's default strategy". */
    final class Default implements BackoffStrategy {
        @Override public long delay(int retryCount) {
            throw new UnsupportedOperationException("Default is a sentinel, not an executable strategy");
        }
    }
}
```

### `RetryPolicy`

Functional interface that determines whether a failed operation should be retried. Consulted when `@Retry#retryOn` is empty.

```java
@FunctionalInterface
public interface RetryPolicy {
    boolean shouldRetry(Throwable error, int retryCount);
}
```

### `ResilienceAnnotations`

Immutable record that holds the resolved `@CircuitBreaker`, `@Retry`, and `@Timeout` annotation values for a single method. Method-level annotations take precedence over type-level defaults. Used by both `rest-client` (`RestClientBuilder` proxy generation) and `services` (`ServiceRegistrar` contract scanning).

```java
public record ResilienceAnnotations(
    @Nullable CircuitBreaker circuitBreaker,
    @Nullable Retry retry,
    @Nullable Timeout timeout
) {
    /** Resolves annotations for the given method, falling back to the declaring class. */
    public static ResilienceAnnotations resolve(Class<?> type, Method method) { ... }

    public boolean hasAny() { ... }
}
```

### `BackoffStrategyResolver`

Utility for instantiating a `BackoffStrategy` from a `@Retry` annotation. Handles the `Default` sentinel by returning a caller-supplied fallback, and instantiates custom strategies via public no-arg constructor (cached).

---

## Extension Ordering (`dev.vertique.core.extension`)

### `OrderedExtension`

Mix-in interface that any framework extension (interceptor, capturer, observer, contributor) can implement to participate in a deterministic ordering contract. The `RestClientInterceptor` and `RestClientContextCapturer` SPIs in `rest-client` are the first consumers; the contract is designed for framework-wide rollout to other extension sets.

```java
public interface OrderedExtension {

    /** Coarse ordering phase; defaults to {@code APPLICATION}. */
    default ExtensionPhase phase() { return ExtensionPhase.APPLICATION; }

    /** Fine priority within a phase; lower runs first; defaults to 0. */
    default int priority() { return 0; }

    /** Stable tie-break key; defaults to the implementation's fully-qualified class name. */
    default String orderKey() { return getClass().getName(); }

    /** Canonical comparator: phase asc → priority asc → orderKey asc. */
    static Comparator<OrderedExtension> comparator() { ... }
}
```

**Ordering:** phase (declaration order) dominates priority. Within the same phase, lower priority values run first. When phase and priority are equal, `orderKey` (default: FQCN) provides a stable tie-break so sorting is fully deterministic.

### `ExtensionPhase`

```java
public enum ExtensionPhase {
    SYSTEM_FIRST,   // system/platform-owned; runs before all APPLICATION extensions (e.g. context capture)
    APPLICATION,    // application-provided (default)
    SYSTEM_LAST     // system/platform-owned; runs after all APPLICATION extensions (e.g. final observation)
}
```

Application extensions should always default to `APPLICATION` (the interface default). Only system/platform-owned extensions — Vertique modules or trusted application-platform modules — should set `SYSTEM_FIRST` or `SYSTEM_LAST`. The phase is a trusted ordering hint, not a security boundary.

#### Invariants & Gotchas

- Phase dominates priority entirely: an `APPLICATION` extension with `priority = Integer.MIN_VALUE` still runs after every `SYSTEM_FIRST` extension. Do not use priority to emulate cross-phase ordering.
- `orderKey()` defaults to `getClass().getName()`. When two extensions share the same phase and priority, the alphabetically earlier class name runs first — this is stable across JVM restarts.
- Framework-wide rollout is complete: every sorted behavioral extension SPI now implements `OrderedExtension`. See ADR-0085 for the full list of migrated surfaces.

---

## Application Lifecycle Types (`dev.vertique.core.lifecycle`)

### `LifecyclePhase`

Single public lifecycle phase vocabulary for the framework. All lifecycle participants — both
non-verticle startup/shutdown steps and verticle deployments — are ordered by this enum.

The eight values in declaration (ordinal) order:

| Phase | Kind | Purpose |
|-------|------|---------|
| `CONFIGURE` | Non-verticle | Build host/runtime configuration (e.g. install Jackson modules) before any deployment |
| `VALIDATE` | Non-verticle | Validate the assembled configuration and wiring before any deployment |
| `MIGRATE` | Non-verticle | Run data/schema migrations before any deployment |
| `BOOTSTRAP` | Verticle | Framework bootstrapping (codec registration, config watchers) |
| `INFRA` | Verticle | Infrastructure verticles (management, health checks) |
| `SERVICES` | Verticle | Service-layer verticles (event bus dispatch) |
| `EDGE` | Verticle | Edge verticles (HTTP, WebSocket) |
| `AFTER_START` | Non-verticle | Post-start work that runs after all verticles are deployed |

`isVerticlePhase()` returns `true` only for `BOOTSTRAP`, `INFRA`, `SERVICES`, and `EDGE`. A
`VerticleDeployment` constructed with a non-verticle phase is rejected at construction time.

#### Invariants & Gotchas

- Ordinal order is the ordering used by `LifecycleOrdered.comparator()` — do not rely on enum
  names for ordering; rely on the documented declaration order above.
- `LifecyclePhase` is unrelated to `ExtensionPhase`. `ExtensionPhase` (`SYSTEM_FIRST`,
  `APPLICATION`, `SYSTEM_LAST`) is the coarse ordering for framework extension SPIs
  (`OrderedExtension`). The two enums serve different purposes and are used by different
  ordering contracts.

### `LifecycleOrdered`

Shared ordering contract for application-lifecycle participants. Both non-verticle steps
(`ApplicationStartupStep`, `ApplicationShutdownStep`) and the verticle-deployment infrastructure
use this contract.

```java
public interface LifecycleOrdered {
    LifecyclePhase phase();
    default int priority() { return 0; }
    default String orderKey() { return getClass().getName(); }
    static Comparator<LifecycleOrdered> comparator();  // phase → priority → orderKey
}
```

**Ordering:** `phase()` (enum ordinal) dominates `priority()` (lower runs first), then `orderKey()`
provides a stable tie-break. When a participant is registered as a lambda or method reference the
default `orderKey()` returns a synthetic JVM-generated class name that is not stable across
compilations; give such participants distinct `priority()` values or override `orderKey()`.

`LifecycleOrdered` is deliberately **not** related to `OrderedExtension`. Extending
`OrderedExtension` here would produce a return-type clash on `phase()` (`ExtensionPhase` vs
`LifecyclePhase`). See ADR-0129.

### `ApplicationStartupStep`

Non-verticle unit of startup work contributed via Dagger `@IntoSet` multibinding into
`DeployerModule`'s `Set<ApplicationStartupStep>`. Steps are ordered by `LifecycleOrdered.comparator()`
and consumed by the Phase 2 lifecycle runner (`vertique-application`).

```java
public interface ApplicationStartupStep extends LifecycleOrdered {
    Future<Void> start();
}
```

A step typically belongs to one of the non-verticle phases (`CONFIGURE`, `VALIDATE`, `MIGRATE`,
`AFTER_START`). Verticle deployment is handled separately for the verticle-subset phases.

### `ApplicationShutdownStep`

Mirror of `ApplicationStartupStep` for teardown work.

```java
public interface ApplicationShutdownStep extends LifecycleOrdered {
    Future<Void> stop();
}
```

### `ComposeValidator`

Behavior-free marker interface for the constructible-as-validation pattern. A class that
implements `ComposeValidator` and declares its module's required bindings as `@Inject` constructor
parameters proves those bindings are present at construction time.

```java
public interface ComposeValidator {}
```

`ComposeValidationStep` materializes the `Set<ComposeValidator>` multibinding during the
`VALIDATE` phase, forcing Dagger to construct every contributed validator. A missing binding is a
compile error; a violated invariant is an `IllegalStateException` thrown from the constructor.

**Contributing a compose validator:**

```java
@Singleton
public final class MyModuleComposeValidator implements ComposeValidator {
    @Inject
    public MyModuleComposeValidator(MyRequiredService service) {
        Objects.requireNonNull(service, "MyRequiredService must be bound");
    }
}

// In the module:
@Provides @Singleton @IntoSet
static ComposeValidator myModuleValidator(MyModuleComposeValidator v) { return v; }
```

For validators to run, the application `@Component` must include `CoreLifecycleStepsModule` and
the component must be driven by `VertiqueApplicationBootstrap` (in `vertique-application`) or
another lifecycle runner that invokes the VALIDATE-phase steps.

Existing compose validators in the framework:
- `WorkflowReminderComposeValidator` (`vertique-workflow-engine`)
- `WorkflowOutboxComposeValidator` (`vertique-workflow-services`)
- `WorkflowDelayedComposeValidator` (`vertique-workflow-delayed`)
- `WorkflowTasksComposeValidator` (`vertique-workflow-tasks`)
- `WorkflowEventsComposeValidator` (`vertique-workflow-events`)
- `InboxOutboxPostgresqlComposeValidator` (`vertique-inbox-outbox-postgresql`)

### `JacksonConfigureStep`

`@Singleton` `ApplicationStartupStep` for the `CONFIGURE` phase. Calls
`JacksonConfigurer.configure()` to apply all registered `ObjectMapperCustomizer` instances to the
Vert.x shared `ObjectMapper`. Idempotent — subsequent runs are no-ops.

```java
@Override public LifecyclePhase phase() { return LifecyclePhase.CONFIGURE; }
@Override public Future<Void> start() {
    jacksonConfigurer.configure();
    return Future.succeededFuture();
}
```

This step replaces the manual `c.jacksonConfigurer().configure()` call that applications
previously made in `MainVerticle.start()`. It is contributed automatically by
`CoreLifecycleStepsModule`.

### `ComposeValidationStep`

`@Singleton` `ApplicationStartupStep` for the `VALIDATE` phase. Receives `Set<ComposeValidator>`
as a required constructor parameter, which forces Dagger to materialize and construct every
contributed validator — running all constructor-time composition checks — at the time this step
is built (i.e., when the Dagger component is constructed). `start()` is a no-op.

```java
@Override public LifecyclePhase phase() { return LifecyclePhase.VALIDATE; }
@Override public Future<Void> start() { return Future.succeededFuture(); }
```

This step replaces the manual per-app `c.workflowComposeValidator()` accessor pattern.
Contributed automatically by `CoreLifecycleStepsModule`.

### `CoreLifecycleStepsModule`

Abstract Dagger `@Module` that an application `@Component` includes to get the framework's
built-in CONFIGURE and VALIDATE steps automatically.

```java
@Module
public abstract class CoreLifecycleStepsModule {
    @Multibinds abstract Set<ComposeValidator> composeValidators(); // empty-by-default
    // contributes JacksonConfigureStep @IntoSet ApplicationStartupStep
    // contributes ComposeValidationStep @IntoSet ApplicationStartupStep
}
```

- Declares `@Multibinds Set<ComposeValidator>` so a component with no contributed validators
  compiles without error.
- Takes no dependency on `vertique-deploy` or `vertique-application` — the `@IntoSet` contributions
  join whatever `Set<ApplicationStartupStep>` the application's component declares (typically the
  one from `DeployerModule`).

---

## Config Utility Types (`dev.vertique.core.config`)

### JsonConfigPaths

Shared hierarchical-config helper with two methods:

| Method | Semantics |
|--------|-----------|
| `navigateObject(JsonObject root, String... segments)` | Tolerant subtree traversal — returns an empty `JsonObject` when any segment is missing; skips blank/null segments |
| `resolve(JsonObject root, String dottedPath)` | Strict dotted-path lookup; returns a `LookupResult` with `status` (`PRESENT`, `MISSING`, `INVALID_SHAPE`), `value`, and `failingSegment` |

`LookupStatus.INVALID_SHAPE` is returned when traversal crosses a non-`JsonObject` intermediate segment. `PRESENT` is returned for any leaf value including `JsonObject` or `JsonArray` (callers must check for non-scalar leaves when expecting scalar config values).

### ConfigParser

`ConfigParser` is the injectable SPI interface for parsing `JsonObject` configuration sections into typed records. It is the framework's single config-parsing seam: boundary `@Provides` providers **inject** a `ConfigParser` and parse each module's config section through it, so config binds reliably regardless of how any other `ObjectMapper` in the process is configured.

The binding is provided by `ConfigParsingModule` (in `vertique-config-core`). The implementation carries a dedicated, isolated `ObjectMapper` — coercion-lenient and tolerant of unknown properties — that never shares state with Vert.x's `DatabindCodec.mapper()` or any global/REST mapper.

```java
public interface ConfigParser {
    <T> T parse(JsonObject section, Class<T> type);
    <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType);
    <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType,
                                  Map<String, Object> fixedProps);
}
```

| Method | Description |
|--------|-------------|
| `parse(JsonObject section, Class<T> type)` | Parses a config section into a typed record. A `null`/empty section deserializes as the type's default shape. Throws `ConfigurationException` on failure. |
| `parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType)` | Parses a section that is itself a keyed object `{key:{...}}` into a `List<T>`, injecting each entry key into the named identity property of every element. |
| `parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType, Map<String,Object> fixedProps)` | Same, but additionally injects constant `fixedProps` into each element before deserialization — used when a record's compact constructor requires fields beyond the key (e.g. `(type, name)` pair). |

**Usage:**

```java
// Field-keyed collection: @KeyedBy on the List<T> component
// (KafkaConfig declares @KeyedBy("name") List<KafkaConsumerConfig> consumers)
KafkaConfig cfg = parser.parse(
    JsonConfigPaths.navigateObject(config, "kafka"), KafkaConfig.class);

// Section-root-keyed collection: keys are at the section root
List<RestClientConfig> clients = parser.parseKeyedObject(
    JsonConfigPaths.navigateObject(config, "restClient"), "name", RestClientConfig.class);

// With fixed props for a record requiring (type, name) identity
List<ServiceConfig> svcs = parser.parseKeyedObject(
    typeGroup, "name", ServiceConfig.class, Map.of("type", typeKey));
```

#### Invariants & Gotchas

- `ConfigParser` is an **injectable interface** — never call it as a static facade. Inject it from Dagger; the implementation and its mapper live in `vertique-config-core`.
- `parse(null, T)` is safe and returns the type's Jackson default shape. `parseKeyedObject(null, …)` returns `List.of()`.
- Open property bags (Kafka `properties`, Camel endpoint `properties`, `webClient`) must be read directly from the source `JsonObject` and attached afterwards — they cannot be parsed through `ConfigParser` because their schema is not owned by the framework.

### @ConfigMapper

`@ConfigMapper` is a Dagger `@Qualifier` annotation used when an application wants to customize the `ObjectMapper` that backs config parsing. Bind an `@ConfigMapper ObjectMapper` anywhere in the application component; `ConfigParsingModule` reads it via `@BindsOptionalOf` and re-layers the framework's mandatory modules and lenient policy over the override before first use.

```java
@Qualifier
@Retention(RUNTIME)
public @interface ConfigMapper {}
```

The override seam is entirely optional. An application that does not bind `@ConfigMapper ObjectMapper` gets the framework's lenient default mapper automatically. The `@ConfigMapper` mapper is dedicated to and owned by config parsing — the framework finalizes it in place before first use, so an application must not share the same instance concurrently for other purposes.

---

### @KeyedBy

Field annotation (`@Target(ElementType.FIELD)`) in `dev.vertique.core.json` on a `List<T>` record component whose external JSON is a keyed object `{key:{...}}`. The object key is injected into the named identity property of each `T` element during deserialization by `KeyedCollectionDeserializer` (in `vertique-json`). The annotation lives in `core.json` — not `core.config` — so that `vertique-json` can reference it without creating a dependency on `core.config`.

```java
// External JSON: { "consumers": { "orders": {...}, "refunds": {...} } }
// The "orders" / "refunds" keys are injected into KafkaConsumerConfig.name().
record KafkaConfig(@KeyedBy("name") List<KafkaConsumerConfig> consumers) {}
```

The element type `T` must declare a settable property matching `@KeyedBy#value()`. A conflict between an explicit JSON value and the injected key is a `ConfigurationException`; equal values are accepted.

---

### KeyedCollectionDeserializer / KeyedCollectionModule

> **Moved to `vertique-json`** (`dev.vertique.json.keyed`). These types no longer live in `vertique-core`. See Maven coordinate `dev.vertique:vertique-json` for the current reference.

The Jackson mechanism backing `@KeyedBy`. `KeyedCollectionModule` is a `SimpleModule` registered on the config mapper by `DefaultConfigMapper` (in `vertique-config-core`); it installs a `BeanDeserializerModifier` that swaps in a `KeyedCollectionDeserializer` for every bean property annotated with `@KeyedBy`. `KeyedCollectionDeserializer` is a contextual `JsonDeserializer<List<?>>` that reads the keyed JSON object and produces the typed list with each key injected into the configured identity property.

The shared key-injection logic (`KeyedCollectionDeserializer.injectKey`) is also used by `DefaultConfigParser.parseKeyedObject` (in `vertique-config-core`) so both paths enforce identical rules: non-blank keys, object-valued entries, and conflict detection.

---

### ConfigSecretRenderer

Shared, array-aware redactor for log-safe rendering of config that may carry secrets. Used by every config record's `toString()` (Camel `CamelEndpointConfig`, `CamelMessageKeyHashConfig`; REST client `RestClientConfig`) so masking stays in lockstep across modules.

Two entry points:

| Method | Description |
|--------|-------------|
| `ConfigSecretRenderer.redactBag(JsonObject bag)` | Recurses into both `JsonObject` and `JsonArray` at any depth, masking each leaf whose full dotted path contains a secret token (case-insensitive substring match). |
| `ConfigSecretRenderer.redactUri(String uri)` | Masks the authority userinfo (`user:pass` between `//` and `@`) and the value of any credential-bearing query parameter. Best-effort string redaction that never throws. |
| `ConfigSecretRenderer.isSensitivePath(String dottedPath)` | Returns `true` when the lower-cased path contains any of: `password`, `secret`, `token`, `passphrase`, `credential`, `jaas.config`, `user.info`, `private.key`, `keystore`, `truststore`. |

`MASK = "***"` is the substituted value. The input bag is never mutated — masking is applied to a deep copy used only for the returned string.

---

### TypedConfigParser / DefaultConfigMapper

> **Moved to `vertique-config-core`** (`dev.vertique.config.parser`). These types no longer live in `vertique-core`. See Maven coordinate `dev.vertique:vertique-config-core` for the current reference.

`DefaultConfigParser` is the `ConfigParser` implementation in `vertique-config-core`. It uses `DefaultConfigMapper.lenient()` by default, or a finalized application-supplied `@ConfigMapper ObjectMapper` override when one is bound. `DefaultConfigMapper` (also in `vertique-config-core`) is the factory for the isolated config `ObjectMapper` — it registers `Jdk8Module`, `JavaTimeModule`, `KeyedCollectionModule` (from `vertique-json`), and Vert.x's `VertxModule`. Both types are internal implementation details of `vertique-config-core`; application code injects `ConfigParser` only.

### ConfigTreeBuilder

Builds a nested `JsonObject` configuration tree from a flat map of dotted/bracketed keys. This is the inverse of `JsonConfigPaths.navigateObject`: it converts the flat key/value pairs that Spring/Quarkus host bridges expose (`Environment` property names, SmallRye/MicroProfile config keys) into the nested `JsonObject` the framework's `ConfigParser` and `JsonConfigPaths` consume.

```java
public final class ConfigTreeBuilder {
    // Static utility — no instances
    public static JsonObject build(Map<String, String> flatKeys) { ... }
}
```

**The builder is purely syntactic — it does not know target types.** All leaf values are stored as `String`; type coercion is `ConfigParser`'s job downstream.

#### Key Grammar (frozen)

Segments are separated by `.` except inside brackets. Each segment is classified as:

| Form | Interpretation |
|------|---------------|
| Bare segment (including all-digit, e.g. `2026`) | Object key |
| `[N]` where N is a non-negative integer | Array index |
| `[content]` where content is quoted or contains a dot | Literal map key (dots preserved, surrounding quotes stripped) |

**Examples:**

```
a.b=1, a.c=2                            → {"a":{"b":"1","c":"2"}}
years.2026.total=5                      → {"years":{"2026":{"total":"5"}}}
servers[0].host=h, servers[1].host=k   → {"servers":[{"host":"h"},{"host":"k"}]}
audit.bindings[http.server].dim[0]=x   → {"audit":{"bindings":{"http.server":{"dim":["x"]}}}}
tags[0]=a, tags[1]=b                   → {"tags":["a","b"]}
```

#### Fail-Fast Rules (frozen)

- **Array indices** must be contiguous from `0`. A gap, duplicate index, or mixing `[N]` with object keys at the same node throws `ConfigurationException`.
- **Leaf/parent collision** — a path used as both a leaf (`a.b=1`) and a parent (`a.b.c=2`) throws `ConfigurationException`.
- Every error message names the offending **keys/paths only, never values** (secret non-leakage).

#### Invariants & Gotchas

- Input order is irrelevant: keys are processed in a stable sorted order so collision detection is independent of the input map's iteration order.
- Bare numerics (e.g. `2026`) are always object keys. Use `[2026]` (brackets without quotes) only when an array index equal to 2026 is genuinely intended — which is almost never valid.
- A `null` input map throws `ConfigurationException`; a `null` value for a key is stored as JSON `null`.

### PropertyCondition

**Public, stable API.** Immutable record used at runtime by code generated from `vertique-codegen-services` and `vertique-codegen-jaxrs`. Do not use in hand-written Dagger bindings — `@ConditionalOnProperty` is a codegen-only annotation.

```java
public record PropertyCondition(String name, String havingValue, boolean matchIfMissing) {}
```

| Field | Default | Description |
|-------|---------|-------------|
| `name` | — (required) | Dot-delimited config property path (e.g., `"sandboxEnabled"`, `"feature.adminApi.enabled"`) |
| `havingValue` | `"true"` | Expected scalar string value; comparison uses `String.valueOf(value).equals(havingValue)` |
| `matchIfMissing` | `false` | When `true`, a `MISSING` path is treated as a match and evaluation continues |

**`PropertyCondition.matchesAll(JsonObject config, PropertyCondition[] conditions)`** static helper:

- ANDs all conditions in the array
- `MISSING` → returns `false` when `matchIfMissing=false`; otherwise treats that condition as satisfied and continues evaluating the remaining array
- `INVALID_SHAPE` → throws `ConfigurationException`
- Present `JsonObject`/`JsonArray` leaf → throws `ConfigurationException` (non-scalar)
- Empty array → always returns `true`

Generated code references this method by FQN: `dev.vertique.core.config.PropertyCondition.matchesAll(config, X_CONDITIONS)`.

---

## Dependencies

- `io.vertx:vertx-core`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `com.fasterxml.jackson.core:jackson-databind`
- `org.slf4j:slf4j-api`
- `org.projectlombok:lombok` (provided scope)

---

## Event Bus Types (`dev.vertique.core.eventbus`)

### DispatchEnvelope\<T\>

Event-bus carrier for service dispatch. Replaces the pre-substrate `Body<T>` wrapper.

```java
public final class DispatchEnvelope<T> {
    public T payload();
    public DispatchMetadata metadata();
    public Optional<String> replyAddress();

    public static <T> DispatchEnvelope<T> of(T payload, DispatchMetadata metadata);
    public static <T> DispatchEnvelope<T> of(T payload, DispatchMetadata metadata, String replyAddress);
    public static <T> DispatchEnvelope<T> of(T payload);   // empty metadata
    public static DispatchEnvelope<Void> empty();
}
```

No `SecurityContext`-specific construction path: SC is one ordinary FQCN-keyed entry inside `DispatchMetadata.dispatchContext()`. Framework dispatchers MUST construct envelopes through `DispatchEnvelopeBuilder` so registered `ServiceDispatchContextEncoder`s capture currently-bound holder values.

### DispatchMetadata

Typed dispatch context carried inside a `DispatchEnvelope`. The single propagation channel is the
FQCN-keyed `dispatchContext()` map; MDC entries ride alongside `SecurityContext`,
`DurablePropagationMetadata`, etc. under `MDCContext.class.getName()` (wire-format value:
`DiagnosticContextSnapshot`).

```java
public final class DispatchMetadata {
    public Map<String, Object> dispatchContext();
    public <C> Optional<C> context(Class<C> type);

    public static DispatchMetadata of(Map<String, Object> dispatchContext);
    public static DispatchMetadata empty();
}
```

`of(...)` copies the caller map via `Map.copyOf` so subsequent caller mutations cannot change
metadata observed by the receiver across the local event-bus hop.

### Result\<T\>

Sealed success/failure monad for event bus reply handling. Implementations: `Success<T>` and `Failure<T>` records.

```java
public sealed interface Result<T> {
    static <T> Result<T> success(T value);
    static <T> Result<T> failure(Throwable cause);
    <U> Result<U> map(Function<T, U>);
    <U> Result<U> flatMap(Function<T, Result<U>>);
    Result<T> recover(Function<Throwable, T>);
    <U> U fold(Function<T, U> onSuccess, Function<Throwable, U> onFailure);
    boolean isSuccess();
    boolean isFailure();
    Optional<T> toOptional();
}
```

### LocalMessageCodec

Local codecs for `DispatchEnvelope<T>` (registered under the name `dispatch.envelope`) and `Result<T>` (under `dispatch.result`), registered by `ServiceDeploymentManager`. Local event bus delivery avoids serialization overhead entirely.

### EventBusClient

`@Singleton` low-level transport abstraction for the framework's dispatch protocol. Wraps the Vert.x event bus with typed codec wiring and exception translation. Service-aware callers should use `ServiceRequestSender` (in `vertique-services`) rather than this class directly.

```java
@Singleton
public class EventBusClient {

    @Inject
    public EventBusClient(Vertx vertx, EventBusExceptionMapper exceptionMapper) { ... }

    // Request/reply — waits for a Result reply; translates ReplyException via EventBusExceptionMapper
    public Future<Result<?>> request(String address, DispatchEnvelope<?> envelope, long sendTimeoutMs) { ... }

    // Fire-and-forget — no reply expected; uses dispatch.envelope codec with no send timeout
    public void send(String address, DispatchEnvelope<?> envelope) { ... }
}
```

All messages use the `dispatch.envelope` codec registered by `LocalMessageCodec`. Failed `request()` calls translate raw Vert.x `ReplyException`s into typed exceptions via `EventBusExceptionMapper` before failing the future.

### EventBusExceptionMapper

`@Singleton` that translates raw Vert.x `ReplyException` instances into typed event bus exceptions. The set of `ReplyFailure` types is closed (a fixed enum), so this mapper is not extensible.

| ReplyFailure | Translated to |
|---|---|
| `TIMEOUT` | `EventBusTimeoutException` |
| `NO_HANDLERS` | `EventBusAddressUnavailableException` |
| `RECIPIENT_FAILURE` | `EventBusDispatchException` |
| `ERROR` | `EventBusDispatchException` |

Non-`ReplyException` throwables are returned unchanged.

### Event Bus Exception Hierarchy

Transport-level exceptions produced by `EventBusExceptionMapper`. All extend from the core exception hierarchy:

```
TechnicalException
├── EventBusTimeoutException      — TIMEOUT reply; carries address(); subclassed by ServiceTimeoutException
└── EventBusDispatchException     — RECIPIENT_FAILURE or ERROR reply; carries address(); subclassed by ServiceDispatchException

UnavailableException
└── EventBusAddressUnavailableException  — NO_HANDLERS reply; carries address()
```

These are transport exceptions — service-layer callers receive enriched subclasses (`ServiceTimeoutException`, `ServiceDispatchException`, `ServiceUnavailableException`) from `services` that add `contract()` for identifying which service failed.

---

## Related ADRs

- ADR-0129: Single `LifecyclePhase` Vocabulary Supersedes `DeploymentPhase` — establishes `LifecyclePhase` (8 values in `dev.vertique.core.lifecycle`) as the single lifecycle ordering vocabulary for both step and verticle-deployment participants; records the `LifecycleOrdered` contract, the verticle-subset invariant, and why `OrderedExtension` cannot be reused for lifecycle steps.
- ADR-0130: Lifecycle Ownership — Passive Component + Separate Host-Neutral Runner — establishes the passive-component + host-neutral runner split; documents that `ComposeValidator`, `JacksonConfigureStep`, `ComposeValidationStep`, and `CoreLifecycleStepsModule` exist in `dev.vertique.core.lifecycle` so that `vertique-application` has no compile dependency on core internals.
- ADR-0126: VertiqueRuntime — Container-Neutral Graph-Input Seam — establishes `VertiqueRuntime` as the single, host-agnostic carrier of the two framework graph inputs (`Vertx` + config `JsonObject`); records the no-host-concept, no-service-locator constraint and the `VertiqueComponentFactory<C>` contract as the application-supplied factory seam.
- ADR-0127: No Host-Bean Locator — Bridges Use Typed Dagger Modules — establishes that embedding host bridges (Spring, Quarkus) adapt host beans through typed Dagger `@Provides` modules rather than a runtime service locator; a missing adapter is a compile-time missing-binding error, not a runtime failure (FR-APP-004 host-bean adapter convention).
- ADR-0068: ContextValue marker + ContextHolder write-path & SPI enforcement — establishes `ContextValue` as the behavior-free marker interface that gates the `ContextHolder` write path; records the interface-over-annotation choice, the compile-time bounds on typed entry points, the two atomic runtime guard points on erased paths, and the `<T extends ContextValue>` bounds on the five context-producing SPIs in `core.context`.
- ADR-0084: Framework Extension-Ordering Contract — establishes `OrderedExtension` and `ExtensionPhase` (in `dev.vertique.core.extension`) as the canonical ordering contract for framework extensions; defines phase-dominates-priority rule and the `SYSTEM_FIRST`/`APPLICATION`/`SYSTEM_LAST` semantics.
- ADR-0085: OrderedExtension Rolled Out Across Sorted Behavioral SPIs — `ObjectMapperCustomizer` and `SecurityIdentityResolver` now follow the framework OrderedExtension ordering contract; `SecurityIdentityResolver` overrides `orderKey()` to return `id()` so the documented `(priority, id)` tie-break is preserved.
- ADR-0103: Config Regression-Guard Mechanism — establishes review-only (not build-gated) enforcement of config rules, with `code-reviewer` as the mechanical backstop.
- ADR-0104: Typed Config Architecture — establishes the boundary-parse model, `ConfigParser` as the canonical seam, `@KeyedBy` / `KeyedCollectionDeserializer` for keyed-object collections, and the no-`@Named` rule.
- ADR-0134: Injectable ConfigParser + Keyed-Collection Module Split — establishes `ConfigParser` as an injectable interface in `core.config`, moves `@KeyedBy` to `core.json`, relocates `KeyedCollectionModule`/`KeyedCollectionDeserializer` to `vertique-json`, and places the parser impl + `ConfigParsingModule` in `vertique-config-core`; records the `@ConfigMapper` override seam and the `config-core → json → core` dependency direction.
- ADR-0108: Unify exception mapping on a context-aware FailureMapper — establishes `FailureMapper` as the concrete, non-generic shared registry; adds `ContextAwareFailureTranslator` for context-carrying translation; layer mappers now extend rather than wrap `FailureMapper`.
- ADR-0112: Framework Exception Hierarchy and REST Mapping — establishes the full core semantic-root set (`ConflictException`, `NotFoundException`, `BusinessRuleException` added); the API-semantic-root pattern; the three-boundary layered-mapping rule; no-REST→DB-dependency rule; per-module stage-2 mapper ownership; and the message-sanitization invariant.

- ADR-0134: Interceptor/Observer Combinator Kernel — establishes `Combinators` and `Futures` in `dev.vertique.core.async` as the shared, framework-internal kernel for interceptor and observer control-flow patterns; records the pre-ordered-list contract, the caller-owns-failure-policy rule, and the delegation from services, REST, rest-client, kafka, job, security-event, and audit-sink runtimes.
- ADR-0141: Method/Parameter Metadata SPI — establishes `MethodMetadata` and `ParameterMetadata` in `dev.vertique.core.codegen` as the neutral, reflection-free metadata contract shared by AOP proxies and future codegen consumers; explains the placement rationale (runtime types in `vertique-core`, not in codegen or AOP modules) and the reflection-free vs. reflective-accessor group split.
- ADR-0143: REST Metadata-Record Unification onto `core.codegen` — completes ADR-0141: makes parameter-level `findAnnotation`/`hasAnnotation` literal-backed (replacing the v1 always-empty stub), adds `ParameterMetadata.annotationsLazy()`, and introduces `ReflectiveMethodMetadata`/`ReflectiveParameterMetadata` as the reflective backing for the rest-client and jaxrs runtime scanner paths.
- ADR-0146: jaxrs codegen parity-first parameter annotations — adds `MetadataEmitter.emitParameterMetadata`, a standalone (non-nested-in-`MethodMetadata`) `ParameterMetadata` emission entry point consumed by `vertique-codegen-jaxrs`, plus a mixed-mode overload that pairs literal-backed annotations with a lazy per-parameter reflective fallback for any annotation `AnnotationLiteralEmitter` cannot render (`dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsReflectiveAnnotations`), guaranteeing full runtime parity with the reflective scan path; changes the generated `annotationsLazy()` to return a defensive copy rather than a shared backing array, since `ParamConversionResolver` passes it directly to external `ParamConverterProvider`s and the backing field/reflective read is reused across requests on the same generated route; dedups the jaxrs-local `ReflectiveParameterMetadata` onto this module's implementation.

Security-specific ADRs (ADR-0062, 0063, 0064, 0078, 0113, 0114) are documented by Maven coordinates `dev.vertique:vertique-security-core` and `dev.vertique:vertique-security-runtime`.
