<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Core Module

> **Status:** Stable
> **Package:** `dev.vertique.core`
> **Artifact:** `vertique-core`
> **Depends on:** nothing — this is the framework root

`vertique-core` is the vocabulary every other Vertique module is written against. It supplies the
Vert.x dependency-injection seam, the framework exception hierarchy, the typed-configuration
contract, the extension-ordering and lifecycle contracts, the event-bus dispatch types, and the
shared annotation vocabularies for validation, sanitization, and JSON profiles.

Core is almost entirely *declaration*. Most of what it defines is implemented elsewhere: the
context holder ships in `dev.vertique:vertique-context`, the config parser in
`dev.vertique:vertique-config-core`, bean validation in `dev.vertique:vertique-validation`, health
aggregation in `dev.vertique:vertique-management`. Depending on core gives an application the
contracts and the annotations, not the runtime behind them.

---

## When To Use It

Every Vertique application already depends on `vertique-core` transitively — it arrives with any
starter or feature module. Depend on it **directly** when:

- you are writing a Dagger module that binds `Vertx`, `@VertxConfig JsonObject`, or `EventBus`;
- you throw or catch framework exceptions and want the semantic roots that drive HTTP mapping;
- you parse a configuration section into a typed record through `ConfigParser`;
- you implement a framework extension point declared here — a health check, a lifecycle step, an
  `ObjectMapper` customizer, or a canonicalizer;
- you annotate an operation with `@ValidateWith`, `@Canonicalize`, `@Sanitize`, or `@JsonProfile`.

Pair core with the module that implements the contract you are using: `vertique-config-core` for
`ConfigParser`, `vertique-validation` for `BeanValidator`, `vertique-json` for the JSON profile
registry, `vertique-context` and `vertique-correlation` for context propagation,
`vertique-management` for health-check aggregation, `vertique-services` for typed event-bus service
calls.

---

## Core Concepts

### The graph-input seam

Every bootstrap path funnels two values into the application's Dagger graph: the `Vertx` instance
and the root configuration `JsonObject`. `VertiqueRuntime` carries exactly those two, and
`VertiqueComponentFactory<C>` turns them into the built component. Nothing host-specific crosses
this seam — no `ApplicationContext`, no service locator. A host that embeds Vertique adapts its own
beans through ordinary typed Dagger modules, so a missing adapter is a compile-time missing-binding
error rather than a runtime lookup failure.

`VertxModule` is the Dagger module on the other side of the seam: constructed with the runtime's two
values, it publishes them plus the event bus and the low-level event-bus client.

### Configuration is parsed at the boundary

Configuration is read only in `@Provides` methods, which parse a section into a typed record whose
compact constructor validates it — so a bad value fails Dagger component construction rather than
first use. Module internals depend on the typed record, never on the raw `JsonObject`. See
[Configuration](#configuration).

### Two ordering contracts, deliberately unrelated

`OrderedExtension` orders behavioral extensions (interceptors, customizers, observers).
`LifecycleOrdered` orders lifecycle participants (startup and shutdown steps, verticle
deployments). Both sort **phase ascending → priority ascending → `orderKey` ascending**, and both
expose a static `comparator()`. They are separate interfaces because their `phase()` methods return
different enums — `ExtensionPhase` and `LifecyclePhase`.

The rules an extension author must know:

- **Phase dominates priority absolutely.** An `APPLICATION` extension with
  `priority = Integer.MIN_VALUE` still runs after every `SYSTEM_FIRST` extension. Do not try to
  emulate cross-phase ordering with priority.
- **Lower `priority()` runs first**; the default is `0`.
- **`orderKey()` defaults to `getClass().getName()`.** It is the final tie-break, so ordering is
  deterministic across JVM restarts only when the key is unique per registered instance. Two
  instances of the same class tie; a lambda or method-reference registration gets a synthetic
  class name that is not stable across compilations. Give such participants distinct `priority()`
  values or override `orderKey()`.
- **`ExtensionPhase` is not a security boundary.** Any module on the classpath can declare
  `SYSTEM_FIRST`. The phase confers ordering, not privilege.

### Lifecycle phases

`LifecyclePhase` is the single phase vocabulary, in declaration (ordinal) order:

| Phase | Kind | Purpose |
|---|---|---|
| `CONFIGURE` | Non-verticle | Build runtime configuration (e.g. install Jackson modules) before any deployment |
| `VALIDATE` | Non-verticle | Validate assembled configuration and wiring before any deployment |
| `MIGRATE` | Non-verticle | Run data/schema migrations before any deployment |
| `BOOTSTRAP` | Verticle | Framework bootstrapping (codec registration, config watchers) |
| `INFRA` | Verticle | Infrastructure verticles (management, health endpoints) |
| `SERVICES` | Verticle | Service-layer verticles (event-bus dispatch) |
| `EDGE` | Verticle | Edge verticles (HTTP, WebSocket) |
| `AFTER_START` | Non-verticle | Post-start work, after all verticles are deployed |

`isVerticlePhase()` is `true` only for `BOOTSTRAP`, `INFRA`, `SERVICES`, and `EDGE`.

Startup **interleaves per phase**: for each phase in order, that phase's startup steps run
sequentially in comparator order and fail-fast, and only then are that phase's verticles deployed.
A step declared in `SERVICES` therefore runs *before* the `SERVICES` verticles, not after them.
Shutdown runs the shutdown steps of the phases whose startup completed, in reversed comparator
order, best-effort.

### Exception semantics

Framework and application exceptions extend a **semantic root** from `dev.vertique.core.exception`
rather than raw `RuntimeException`. The root chosen is what determines the HTTP status a REST
boundary produces, so picking the most specific applicable root is the whole mechanism — see
[Failures, Constraints, and Common Mistakes](#failures-constraints-and-common-mistakes).

### Context values

A value that must ride along with a request or a durable dispatch implements the marker interface
`ContextValue`. That marker gates the write path: `ContextHolder.bind` is
`<T extends ContextValue>`, while `current(Class<T>)` is deliberately unbounded so anything stored
can be read back. The five context-producing SPIs carry the same bound on their context-type
parameter. The holder implementation and the encoder/decoder registries ship in
`dev.vertique:vertique-context`.

---

## Key Classes

### `VertxModule`

Dagger `@Module` constructed with the Vert.x instance and the root configuration; list it on the
application `@Component` and supply it through the generated builder. Bindings provided:

| Type | Qualifier | Description |
|---|---|---|
| `Vertx` | — | The Vert.x instance passed at construction |
| `JsonObject` | `@VertxConfig` | The root application configuration |
| `EventBus` | — | `vertx.eventBus()` |
| `EventBusExceptionMapper` | — | Translates Vert.x `ReplyException`s into typed exceptions |
| `EventBusClient` | — | Low-level dispatch transport |

### `VertiqueRuntime` and `VertiqueComponentFactory<C>`

```java
public record VertiqueRuntime(Vertx vertx, JsonObject config) {
    public static VertiqueRuntime of(Vertx vertx, JsonObject config);
}

@FunctionalInterface
public interface VertiqueComponentFactory<C> {
    C build(VertiqueRuntime runtime);
}

VertiqueComponentFactory<AppComponent> factory = rt -> DaggerAppComponent.builder()
        .vertxModule(new VertxModule(rt.vertx(), rt.config()))
        .build();

AppComponent component = factory.build(VertiqueRuntime.of(vertx, config));
```

Both record components are required: the compact constructor calls `Objects.requireNonNull` on
each, so a `null` throws at construction rather than surfacing later as a missing binding. Prefer
the static `of` factory, which delegates to the canonical constructor.

### `FailureMapper`

Concrete, hierarchy-aware, context-aware `Throwable → Throwable` translator registry. Usable
directly for ad-hoc translation, and the base class the layer mappers in `dev.vertique:vertique-rest-jaxrs`,
`dev.vertique:vertique-services`, and `dev.vertique:vertique-db-core` extend.

```java
public class FailureMapper {
    public FailureMapper();

    public <T extends Throwable> FailureMapper on(Class<T> type, FailureTranslator<T> translator);
    public <T extends Throwable> FailureMapper on(Class<T> type, ContextAwareFailureTranslator<T> translator);

    public Throwable translate(Throwable throwable, String context);
    public Throwable translate(Throwable throwable);   // context defaults to throwable.getMessage()

    public FailureTranslator<?> findTranslator(Class<? extends Throwable> exceptionClass);

    protected Throwable fallback(Throwable throwable, String context);
}
```

```java
var mapper = new FailureMapper();
mapper.on(DatabaseException.class, (e, ctx) -> new DataAccessException(ctx, e));

Throwable translated = mapper.translate(sqlFailure, "save user");
```

#### Invariants & Gotchas

- Lookup walks the **superclass** chain and stops below `Object`, so the most specific registered
  translator wins. Interfaces are not consulted.
- Registration is **last-wins** and clears the whole lookup cache, which is what lets a later, more
  specific registration override an already-resolved broader one.
- `findTranslator` returns `null` — not a no-op translator — when nothing in the hierarchy matches.
  `fallback` is what `translate` returns instead; the default returns the throwable unchanged.
- `translate` dispatches on the runtime type, so behavior is identical regardless of which `on(...)`
  overload registered the translator. A two-argument lambda binds to the context-aware overload; a
  one-argument lambda binds to the plain one.

### `Result<T>`

Sealed success/failure monad used for event-bus replies. Implementations are the records
`Result.Success<T>` and `Result.Failure<T>`.

```java
public sealed interface Result<T> {
    static <T> Result<T> success(T value);
    static <T> Result<T> failure(Throwable cause);

    boolean isSuccess();
    boolean isFailure();
    T get();                       // throws NoSuchElementException on a failure
    Optional<T> toOptional();
    Throwable cause();             // null on a success
    <U> Result<U> map(Function<T, U> mapper);
    <U> Result<U> flatMap(Function<T, Result<U>> mapper);
    Result<T> recover(Function<Throwable, T> recoveryFn);
    <U> U fold(Function<T, U> onSuccess, Function<Throwable, U> onFailure);
}
```

`map`, `flatMap`, and `recover` catch an `Exception` thrown by the supplied function and return it
as a `Failure`. `Result.failure(null)` is rejected.

### `DispatchEnvelope<T>` and `DispatchMetadata`

The event-bus carrier for framework dispatch and its typed context map.

```java
public final class DispatchEnvelope<T> {
    public static <T> DispatchEnvelope<T> of(T payload, DispatchMetadata metadata);
    public static <T> DispatchEnvelope<T> of(T payload, DispatchMetadata metadata, String replyAddress);
    public static <T> DispatchEnvelope<T> of(T payload);        // empty metadata
    public static DispatchEnvelope<Void> empty();

    public T payload();
    public DispatchMetadata metadata();
    public Optional<String> replyAddress();
}

public final class DispatchMetadata {
    public static DispatchMetadata of(Map<String, Object> dispatchContext);
    public static DispatchMetadata empty();

    public Map<String, Object> dispatchContext();
    public <C> Optional<C> context(Class<C> type);
}
```

The dispatch context is keyed by fully-qualified type name; `context(Class)` is the typed read.
`DispatchMetadata.of` copies the caller map, so later caller mutations cannot change what the
receiver observes across the local hop. Annotating a type `@DispatchContextValue` (in
`dev.vertique.core.eventbus`) makes it injectable as a service-handler method parameter.

Application code should not build envelopes by hand — use the typed client in
`dev.vertique:vertique-services`, which captures currently-bound context values automatically.

### `EventBusClient`

`@Singleton` low-level transport over the framework's dispatch codecs, exposing
`Future<Result<?>> request(String address, DispatchEnvelope<?> envelope, long sendTimeoutMs)` and
`void send(String address, DispatchEnvelope<?> envelope)`. Failed requests are translated through
`EventBusExceptionMapper` before the future fails.

This is a transport primitive, not an application API. Prefer the typed service client in
`dev.vertique:vertique-services`, which adds supervisor checks, effective-timeout computation, and
service-specific failure enrichment.

### `BeanValidator` and validation result types

HTTP-agnostic validation contract; the implementation ships in `dev.vertique:vertique-validation`.

```java
public interface BeanValidator {
    <T> void validate(T object);
    <T> void validate(T object, Class<?>... groups);

    <T> List<ViolationDetail> check(T object);
    <T> List<ViolationDetail> check(T object, Class<?>... groups);

    List<ParameterViolation> checkParameters(Object instance, Method method, Object[] args, Class<?>... groups);
    void validateParameters(Object instance, Method method, Object[] args, Class<?>... groups);
}
```

`validate` and `validateParameters` throw `BeanValidationException` on violation; `check` and
`checkParameters` return the violations instead, empty meaning valid.

| Type | Shape |
|---|---|
| `ViolationDetail` | `record (String path, String message, @Nullable String type, @Nullable Map<String, Object> args)`, plus `of(path, message)` |
| `ParameterViolation` | `record (int parameterIndex, ViolationDetail detail)` |
| `BeanValidationException` | extends `ValidationException`; `violations()` is defensively copied and unmodifiable |

`ViolationDetail` deliberately excludes the invalid value so a violation can be serialized without
leaking input. `type` and `args` are populated by the validation module and are `null` when it is
not configured to resolve them.

`@ValidateWith(Class<?>[] value)` selects Bean Validation groups for a single method; the default
group applies when it is absent.

### `MethodMetadata` and `ParameterMetadata`

Reflection-free metadata contracts shared by codegen consumers and runtime scanners. An aspect or
interceptor author consumes them; application code does not implement them — generated code and the
framework's own scanners provide the implementations.

`MethodMetadata`:

| Method | Returns | Group |
|---|---|---|
| `name()` | `String` | reflection-free |
| `declaringType()` | `Class<?>` | reflection-free |
| `returnType()` | `Class<?>` | reflection-free |
| `parameterTypes()` | `Class<?>[]` | reflection-free |
| `parameters()` | `List<ParameterMetadata>` | reflection-free |
| `findAnnotation(Class<A>)` | `Optional<A>` | reflection-free |
| `hasAnnotation(Class<? extends Annotation>)` | `boolean` | reflection-free |
| `genericReturnType()` | `Type` | reflective |
| `asMethod()` | `Method` | reflective |

`ParameterMetadata`:

| Method | Returns | Group |
|---|---|---|
| `index()` | `int` | reflection-free |
| `name()` | `String` | reflection-free |
| `type()` | `Class<?>` | reflection-free |
| `findAnnotation(Class<A>)` | `Optional<A>` | reflection-free |
| `hasAnnotation(Class<? extends Annotation>)` | `boolean` | reflection-free |
| `genericType()` | `Type` | reflective |
| `annotationsLazy()` | `Supplier<Annotation[]>` | reflective; `default` returns an empty array |

#### Invariants & Gotchas

- The reflection-free group — including both annotation lookups — is safe on the critical path and
  needs no GraalVM `reflect-config.json` entry. The reflective group does: calling
  `asMethod()`, `genericReturnType()`, `ParameterMetadata.genericType()`, or `annotationsLazy()` in
  a native image requires a reflection entry for the declaring class.
- On the codegen path, annotation lookups query compile-time-captured annotation literals, not live
  runtime annotations. An annotation added after compilation — by a dynamic proxy or a bytecode
  agent — is invisible. Only `@Retention(RUNTIME)` annotations are ever visible.
- `findAnnotation` returns `Optional`, never `null`. Both `findAnnotation` and `hasAnnotation` take
  an annotation-typed `Class` (`Class<A extends Annotation>` and `Class<? extends Annotation>`).
- `parameterTypes()` gives the erased signature without materializing `ParameterMetadata` objects;
  `parameters()` gives the full per-parameter view.

### Resilience vocabulary

The resilience annotation vocabulary and its declaration metadata are provided by
`dev.vertique:vertique-resilience`. Core deliberately remains independent of that artifact;
applications using `@Retry`, `@Timeout`, or `@CircuitBreaker` should depend on the resilience module
directly or through the module that enforces those declarations.

### `CorrelationContext`

Read-only view of the correlation identifiers bound to the current execution. It is a
`ContextValue` and is marked `@DispatchContextValue`, so it propagates across service dispatch.

```java
public interface CorrelationContext extends ContextValue {
    CorrelationIdentifier requestId();
    CorrelationIdentifier correlationId();
    @Nullable CorrelationIdentifier causationId();
    @Nullable TraceReference trace();
    List<ProtocolCorrelationRef> protocolCorrelations();
    @Nullable CorrelationSessionRef session();
    Map<String, String> attributes();
    CorrelationContextSnapshot snapshot();

    static CorrelationContext unbound();
}
```

`CorrelationIdentifier` is a `record (String value, String source)`; both components must be
non-blank. `CorrelationContext.unbound()` is the sentinel returned outside a correlated execution —
its `requestId()`/`correlationId()` carry the value `"unavailable"` from source `"unbound"`, so
reading correlation off the request path never throws.

The runtime that binds this context, and the `CorrelationIdGenerator` SPI it uses, ship in
`dev.vertique:vertique-correlation`. An application overrides ID generation by binding its own
`CorrelationIdGenerator` — that module declares the binding as optional.

### `PayloadSource` and `PayloadSources`

Transport-agnostic description of a request or response body, used where a module must inspect a
payload without depending on REST or audit types.

```java
public interface PayloadSource {
    PayloadKind kind();                    // ABSENT | BUFFERED | STREAMING
    Optional<String> contentType();
    OptionalLong declaredLength();
    Optional<Buffer> bufferedView();
    Optional<InputStream> bufferedStream();
    byte[] copyPrefix(int maxBytes);
}

PayloadSource absent    = PayloadSources.absent();
PayloadSource buffered  = PayloadSources.buffered(bytes, "application/json");
PayloadSource streaming = PayloadSources.streaming("application/octet-stream", declaredLength);
```

#### Invariants & Gotchas

- **No defensive copy anywhere.** `buffered(byte[], …)` captures the array reference; a caller that
  needs immutability must copy before calling the factory. Mutating the array afterwards changes
  what readers observe.
- `bufferedView()` is zero-copy only for a `Buffer`-backed source. For `byte[]` and `ByteBuffer`
  backings it lazily materializes and copies, so a hot path that only reads bytes should prefer
  `bufferedStream()`.
- `copyPrefix` is the one accessor that always copies; it exists for diagnostics, not for the data
  path.

### JSON profiles

A named `ObjectMapper` selected at a framework boundary, so one application can serialize different
surfaces under different Jackson policies.

```java
public record JsonProfileId(String value) {
    public static final JsonProfileId VERTX;     // "vertx"
    public static JsonProfileId of(String value);
}

public interface JsonMapperProfileRegistry {
    ObjectMapper mapper(JsonProfileId id);
    JsonMapperProfile profile(JsonProfileId id);
    Set<JsonProfileId> profileIds();
    default void validateConfigured(@Nullable String profileId);
}
```

`@JsonProfile("name")` is `@Target({TYPE, METHOD})` and selects a profile at a boundary that
supports it. Which placements a boundary accepts is the boundary's own contract: REST resources and
MCP tools accept both TYPE and METHOD with method-level overriding type-level, while `rest-client`
interfaces and Kafka listeners/producers accept TYPE only. `JsonProfileId` trims its value and
rejects `null` or blank. Looking up an unknown id throws `JsonProfileConfigurationException`, which
extends `ConfigurationException`. The registry implementation ships in `dev.vertique:vertique-json`.

### JSON schema overrides on a profile

A profile may declare, per Java class, the JSON Schema fragment describing the wire form its mapper
actually produces or accepts — so a generated schema matches the profile's serialization instead of
the Java type's default shape.

```java
public final class JsonSchemaFragment {
    public static JsonSchemaFragment parse(String schemaJson);
    public String canonicalJson();
}

public final class JsonSchemaTypeOverride {
    public enum Direction { INPUT, OUTPUT, BOTH }

    public static JsonSchemaTypeOverride input(Class<?> javaType, JsonSchemaFragment fragment);
    public static JsonSchemaTypeOverride output(Class<?> javaType, JsonSchemaFragment fragment);
    public static JsonSchemaTypeOverride both(Class<?> javaType, JsonSchemaFragment fragment);

    public Class<?> javaType();
    public Direction direction();
    public JsonSchemaFragment fragment();
}
```

```java
JsonSchemaTypeOverride.both(
        BigDecimal.class,
        JsonSchemaFragment.parse(
                """
                {"type":"string","format":"decimal","maxLength":100}
                """));
```

`parse` takes a non-null, non-blank JSON **object** holding one self-contained Draft 2020-12
fragment. It orders object keys recursively by `String.compareTo` (never reordering arrays), retains
only the canonical compact string, and returns an immutable value; `canonicalJson()` returns that
string and two fragments are equal when their canonical JSON is equal. Parsing is syntactic plus
bounded structural validation — the consuming validator still compiles the completed schema.

`parse` rejects — with an `IllegalArgumentException` whose bounded message names the violated rule
and never echoes the fragment — a `null` or blank input, malformed JSON, a non-object root, and any
occurrence of `$schema`, `$id`, `$anchor`, `$dynamicAnchor`, `$ref`, `$dynamicRef`, or `$defs`. The
schema generator owns the document dialect and definition graph, so a fragment-local reference would
change meaning once embedded. That rejection is **structural and deliberately over-broad**: those key
names are rejected at any depth, including where one is merely a property name inside a `properties`
object.

`JsonSchemaTypeOverride` requires non-null arguments and describes one **exact raw class** — no
assignability or subtype matching, and never a map key. An override for `BigDecimal` reaches a
`BigDecimal` property and the element of a resolved `List<BigDecimal>`, but not a `BigDecimal`
subclass nor the key type of `Map<BigDecimal, String>`. `INPUT` and `OUTPUT` select one generation
direction; `BOTH` selects both, and conflicts with a direction-specific declaration for the same
class. The generator that consumes these values ships in `dev.vertique:vertique-json-schema`.

---

## Extension Points

Every extension below is contributed through Dagger multibinding unless stated otherwise.

### `ObjectMapperCustomizer`

Customizes the Vert.x shared `ObjectMapper` at startup. Extends `OrderedExtension`, so customizers
apply in comparator order.

```java
@FunctionalInterface
public interface ObjectMapperCustomizer extends OrderedExtension {
    void customize(ObjectMapper mapper);
}
```

```java
@Provides @IntoSet
static ObjectMapperCustomizer javaTimeSupport() {
    return mapper -> mapper.registerModule(new JavaTimeModule());
}
```

A lambda takes the defaults (`APPLICATION` phase, priority `0`); implement the interface explicitly
to override `phase()`, `priority()`, or `orderKey()`.

The empty-by-default `Set<ObjectMapperCustomizer>` is declared by `JsonModule`, which
`CoreLifecycleStepsModule` already includes. `JacksonConfigurer` applies the set exactly once; a
second `configure()` call logs a warning and returns.

### `HealthCheck`

Reports the health of one component. Classify each check with the `@Liveness` or `@Readiness`
qualifier; `HealthCheckModule` declares both empty-by-default sets so a module can contribute
without depending on `dev.vertique:vertique-management`, which is what reads them.

```java
public interface HealthCheck {
    String name();
    Future<HealthCheckResult> check();
}
```

```java
public final class DatabaseHealthCheck implements HealthCheck {
    @Inject public DatabaseHealthCheck() {}

    @Override public String name() { return "database"; }

    @Override public Future<HealthCheckResult> check() {
        return Future.succeededFuture(HealthCheckResult.up(Map.of("pool", "ready")));
    }
}

@Provides @IntoSet @Readiness
static HealthCheck databaseHealth(DatabaseHealthCheck check) {
    return check;
}
```

`HealthCheckResult` is a `record (HealthStatus status, Map<String, Object> data)` with the factories
`up()`, `up(Map)`, `down()`, `down(String error)`, `down(Throwable cause)`, and `down(Map)`; `data`
is copied and never `null`. `down(String)` treats a `null` error as "no message" and yields empty
data rather than throwing, so `down(throwable.getMessage())` is safe for a message-less exception.
`down(Throwable)` puts the throwable's message under the `error` key, falling back to its fully
qualified class name when the message is `null` — and equally when `getMessage()` itself throws an
exception, so a failure that cannot describe itself still yields a `DOWN` result instead of a
second failure. It rejects a `null` cause with a `NullPointerException`. Return a completed future
carrying a `DOWN` result rather than a failed future — a failed future or a thrown exception is
still reported as `DOWN`, but with the error text instead of your diagnostic data. `name()` must be
unique within its qualifier set.

### `ApplicationStartupStep` and `ApplicationShutdownStep`

Non-verticle units of startup and teardown work.

```java
public interface ApplicationStartupStep extends LifecycleOrdered {
    Future<Void> start();
}

public interface ApplicationShutdownStep extends LifecycleOrdered {
    Future<Void> stop();
}
```

```java
@Singleton
public final class SchemaMigrationStep implements ApplicationStartupStep {
    private final Migrator migrator;

    @Inject public SchemaMigrationStep(Migrator migrator) { this.migrator = migrator; }

    @Override public LifecyclePhase phase() { return LifecyclePhase.MIGRATE; }

    @Override public Future<Void> start() { return migrator.migrate(); }
}

@Provides @Singleton @IntoSet
static ApplicationStartupStep schemaMigration(SchemaMigrationStep step) {
    return step;
}
```

A step normally declares one of the non-verticle phases — `CONFIGURE`, `VALIDATE`, `MIGRATE`, or
`AFTER_START`. The multibinding sets are declared by `dev.vertique:vertique-deploy` and driven by
`dev.vertique:vertique-application`; core contributes into them without depending on either.

### `ComposeValidator`

Behavior-free marker for the constructible-as-validation pattern: a class that declares its
module's required bindings as `@Inject` constructor parameters proves those bindings exist the
moment it is constructed.

```java
public interface ComposeValidator {}
```

```java
@Singleton
public final class MyModuleComposeValidator implements ComposeValidator {
    @Inject public MyModuleComposeValidator(MyRequiredService service) {
        if (service.mode() != Mode.READY) {
            throw new IllegalStateException("MyRequiredService must be configured in READY mode");
        }
    }
}

@Provides @Singleton @IntoSet
static ComposeValidator myModuleValidator(MyModuleComposeValidator v) {
    return v;
}
```

`ComposeValidationStep` materializes the whole set during the `VALIDATE` phase, which constructs
every contributed validator. A missing binding is a Dagger compile error; a violated invariant is
whatever the constructor throws, reported as a failed `VALIDATE`-phase startup. For validators to
run, the application component must include `CoreLifecycleStepsModule` and be driven by the standard
lifecycle runner in `dev.vertique:vertique-application`.

### `Canonicalizer` and `Sanitizer`

String normalization (semantics-preserving) and content removal (not semantics-preserving) applied
to inbound values. Implementations must be stateless and thread-safe; a `Canonicalizer` must also be
deterministic and idempotent.

```java
@FunctionalInterface
public interface Canonicalizer {
    String canonicalize(String value, InputValueContext context);
}

@FunctionalInterface
public interface Sanitizer {
    String sanitize(String value, InputValueContext context);
}
```

Register each implementation as a binding record keyed by its own type — the `type()` component is
the lookup key used to resolve the classes named in `@Canonicalize` / `@Sanitize`. Declare the chain
with those annotations, both `@Target({TYPE, FIELD, RECORD_COMPONENT, PARAMETER, METHOD,
ANNOTATION_TYPE})`, so they also compose into custom annotations:

```java
@Provides @IntoSet
static CanonicalizerBinding trimCanonicalizer(TrimCanonicalizer c) {
    return new CanonicalizerBinding(TrimCanonicalizer.class, c);
}

@Path("/users")                                            // type-level: applies to the whole resource
@Canonicalize({TrimCanonicalizer.class, NfkcCanonicalizer.class})
public class UserResource { }

public record CreateUserRequest(                           // component-level
        @Canonicalize(TrimCanonicalizer.class)
        @Sanitize(BasicHtmlSanitizer.class)
        String bio) {}
```

A `Sanitizer` is registered the same way, through `SanitizerBinding`.

`@SkipCanonicalization` and `@SkipSanitization` opt an element out of processing inherited from an
enclosing type. `InputValueContext` is a `record (InputLocation location, String path, String
logicalName, Class<?> ownerType)`; `InputLocation` is `PATH`, `QUERY`, `HEADER`, `COOKIE`, `FORM`,
`BODY`, `BEAN_PARAM`, `PAYLOAD`. `PAYLOAD` marks values from message or protocol payloads (e.g.
WebSocket messages); REST request bodies remain `BODY`. A custom `Canonicalizer`, `Sanitizer`, or
`CharacterPolicy` that branches on `InputLocation.BODY` must also handle `PAYLOAD` to keep
covering message-oriented inputs — WebSocket messages reported `BODY` before `PAYLOAD` existed.

`path` and `logicalName` are deliberately different views of the same value. `path` is the **wire**
path — the dot-separated keys as the caller sent them — so a diagnostic points at what actually
arrived. `logicalName` is the **Java** property name whenever the value came from a property the
processing engine matched, and the wire name otherwise. A `Canonicalizer` or `Sanitizer` that
branches on `logicalName` is therefore branching on the Java name, not on a `@JsonProperty`-renamed
key; branch on `path` when the wire form is what matters. Collection elements carry the element path
in both components.

### `InputFieldNameResolver`

Codec-neutral projection from a **wire** property name to the **Java** property name that declares
its input policies.

```java
@FunctionalInterface
public interface InputFieldNameResolver {

    /** Returns every wire name unchanged, for transports that do not rename. */
    InputFieldNameResolver IDENTITY = (ownerType, wireName) -> wireName;

    String logicalName(Class<?> ownerType, String wireName);

    /** Composes one owner type's projection at registration; no-op unless overridden. */
    default void precompute(Class<?> ownerType) {}
}
```

Policies declared with `@Canonicalize` / `@Sanitize` are keyed by the Java property name, but an
intermediate parsed from the wire is keyed by whatever the codec published —
`@JsonProperty("user_name")`, a `SNAKE_CASE` naming strategy, or a `@JsonAlias`. Without a
projection between the two, a policy declared on a renamed field silently never runs. This contract
is the seam that closes that gap, and declaring it here — beside `InputLocation`, `Canonicalizer`
and `Sanitizer` — is what keeps both `vertique-input-processing` and `vertique-sanitization` free of
any codec dependency: each codec-backed implementation lives in the module that already owns that
codec (`JacksonFieldNameResolver` in `dev.vertique:vertique-json`).

Four properties are part of the contract:

| Property | Contract |
|---|---|
| Direction | Wire → Java, the only direction able to express an alias's many-to-one mapping |
| Totality | An unrecognized `wireName` — an undeclared extra key, a `Map`-typed field's key, or a name that is already the Java property name — is returned **unchanged**; an implementation never returns `null` and never throws |
| Threading and cost | Stateless (or effectively immutable), reentrant, consulted once per intermediate key on the request path — it must not block and should serve every call from a precomputed projection |
| Warm-up | `precompute(Class)` composes one owner type's projection ahead of the request path; it defaults to a no-op, so a resolver that needs no per-class state is unaffected. The input-processing engine calls it at registration for every owner type it may consult, and an implementation may fail fast there — the failure is deliberately a startup failure rather than a per-request one |

Use `InputFieldNameResolver.IDENTITY` for any transport whose intermediate keys are already Java
property names, including every call site that processes a bare `String`, where there is no object
whose fields could be renamed.

### `CharacterPolicy`

Validates that a string contains only permitted characters.

```java
public interface CharacterPolicy {
    CharacterPolicyResult validate(String value, InputValueContext context);
}
```

```java
@Provides @IntoSet
static CharacterPolicyBinding asciiPolicy(AsciiCharacterPolicy p) {
    return new CharacterPolicyBinding(AsciiCharacterPolicy.class, p);
}
```

Return `CharacterPolicyResult.passed()` or
`CharacterPolicyResult.failed(index, codePoint, reason)` naming the first offending character.
`@SkipAllowedCharacters` opts an element out of character validation inherited from its type.

When a policy runs from Bean Validation (the `@AllowedCharacters` constraint), its
`InputValueContext` is synthetic: `location()` is always `BODY` whatever the value's real origin,
and `ownerType()` is always `Object.class`. Validation sees the value after it has been separated
from its transport provenance, and one validation pass can cover values from several locations at
once, so no accurate location exists to report. Do not branch on `location()` in a policy.
Canonicalizers and sanitizers run earlier and do receive accurate provenance.

### Resilience primitives

`dev.vertique:vertique-resilience` also owns the canonical `BackoffStrategy` and `RetryPolicy`, the
small contracts used by consumer modules to calculate retry delays and determine retry eligibility.

### `FailureTranslator` and `ContextAwareFailureTranslator`

```java
@FunctionalInterface
public interface FailureTranslator<T extends Throwable> {
    Throwable translate(T throwable);
}

@FunctionalInterface
public interface ContextAwareFailureTranslator<T extends Throwable> extends FailureTranslator<T> {
    Throwable translate(T throwable, String context);

    @Override
    default Throwable translate(T throwable) {
        return translate(throwable, throwable.getMessage());
    }
}
```

Both register on a `FailureMapper` through `on(...)`. Layer-specific customizer SPIs — which
contribute translators into the framework's REST, service, and database mappers through Dagger — are
declared by those modules; see `dev.vertique:vertique-rest-jaxrs`, `dev.vertique:vertique-services`,
and `dev.vertique:vertique-db-core`.

### `JsonMapperProfile`

Contributes one named `ObjectMapper` profile.

```java
public interface JsonMapperProfile {
    JsonProfileId id();
    ObjectMapper mapper();
    default List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides();
}
```

The registry that collects profiles ships in `dev.vertique:vertique-json`; contribute a profile
through that module's multibinding. `JsonProfileId.VERTX` (`"vertx"`) is reserved for the Vert.x
shared mapper.

`jsonSchemaTypeOverrides()` declares the schema overrides described under
[JSON schema overrides on a profile](#json-schema-overrides-on-a-profile). It defaults to an empty
list, so a profile that only implements `id()` and `mapper()` stays valid. An implementation that
overrides it returns the same stable, non-null, unmodifiable list on every call and defensively
copies any caller-supplied collection.

### Context-propagation SPIs

Implement these to make a typed value travel across a dispatch or durable boundary. Every one is
bounded `<T extends ContextValue>` on its context type; the registries that collect them live in
`dev.vertique:vertique-context`.

| SPI | Contract |
|---|---|
| `ServiceDispatchContextEncoder<T>` | `Class<T> type()`, `String key()` (defaults to the FQCN), `Object encode(T, ServiceDispatchEncodeContext)` |
| `ServiceDispatchContextDecoder<T>` | `Class<T> type()`, `String key()`, `ContextDecodeResult<T> decode(Object, ServiceDispatchDecodeContext)` |
| `DurableContextMetadataEncoder<T>` | `Class<T> type()`, `String namespace()`, `DurableMetadata encode(T, DurableEncodeContext)` |
| `DurableContextMetadataDecoder<T>` | `Class<T> type()`, `String namespace()`, `ContextDecodeResult<T> decode(DurableMetadata, DurableDecodeContext)`, `default boolean acceptsExplicitCarrier()` |
| `ContextValueAdapter<T>` | `Class<T> type()`, `Object snapshot(T)`, `T restoreFromSnapshot(Object)`, `default T duplicate(T)` |
| `InboundContextInitializer` | `ContextHolder.Scope initialize(InboundContextInitializationContext)` |

```java
public record TenantContext(String tenantId) implements ContextValue {}

public final class TenantDispatchEncoder implements ServiceDispatchContextEncoder<TenantContext> {
    @Inject public TenantDispatchEncoder() {}

    @Override public Class<TenantContext> type() { return TenantContext.class; }

    @Override public Object encode(TenantContext value, ServiceDispatchEncodeContext context) {
        return value.tenantId();
    }
}
```

Decoders report problems by returning `ContextDecodeResult.failure(warnings)` rather than throwing;
the record is `(Optional<T> value, List<ContextDecodeWarning> warnings)` with the factories
`empty()`, `of(value)`, and `failure(warnings)`. `ContextDecodeWarning` is a
`record (String key, String value, String reason)`.

`DispatchBoundary` supplies the boundary identifiers an encoder or decoder can branch on:
`MCP`, `SERVICE_DISPATCH`, `KAFKA`, `OUTBOX`, `OUTBOX_SERVICE`, `DELAYED_JOB`, `WORKFLOW`, `CAMEL`.

---

## Configuration

`vertique-core` reads no configuration section of its own. It defines the contract every other
module's configuration is parsed through.

### The boundary

The root configuration object is bound once, qualified `@VertxConfig`, by `VertxModule`. Read it
only in a `@Provides` method, navigate to your section, and parse it through an injected
`ConfigParser`:

```java
@Provides
@Singleton
static HelloConfig helloConfig(@VertxConfig JsonObject config, ConfigParser parser) {
    return parser.parse(JsonConfigPaths.navigateObject(config, "hello"), HelloConfig.class);
}
```

The `ConfigParser` binding is provided by `ConfigParsingModule` in
`dev.vertique:vertique-config-core`; include it once in the application component. Never use
`JsonObject.mapTo` or a hand-rolled `ObjectMapper` at this boundary — the config mapper is isolated
from the Vert.x and REST mappers and is deliberately coercion-lenient, so `"port": "9090"` binds
whether or not another mapper in the process has been tuned strict.

### `ConfigParser`

| Method | Behavior |
|---|---|
| `<T> T parse(JsonObject section, Class<T> type)` | Parses one section into a typed record. A `null`/empty section yields the type's default shape. Throws `ConfigurationException` on failure. |
| `<T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType)` | Parses a section that *is* a keyed object `{key:{…}}`, injecting each entry key into the named identity property. |
| `<T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps)` | Same, plus constant properties injected into every element before deserialization — needed when a record's compact constructor requires fields beyond the key. |

```java
// Field-keyed: @KeyedBy on the List<T> component of the parsed record
KafkaConfig kafka = parser.parse(JsonConfigPaths.navigateObject(config, "kafka"), KafkaConfig.class);

// Section-root-keyed: the keys are the section itself
List<RestClientConfig> clients = parser.parseKeyedObject(
        JsonConfigPaths.navigateObject(config, "restClient"), "name", RestClientConfig.class);

// With fixed props for a record whose identity is a (type, name) pair
List<ServiceConfig> services =
        parser.parseKeyedObject(typeGroup, "name", ServiceConfig.class, Map.of("type", typeKey));
```

`parse(null, T)` is safe and returns the type's Jackson default shape; `parseKeyedObject(null, …)`
returns `List.of()`. Open property bags whose schema the framework does not own must be read
directly from the source `JsonObject` and attached afterwards.

### `@KeyedBy`

Field annotation (`@Target(FIELD)`, in `dev.vertique.core.json`) on a `List<T>` record component
whose external JSON is a keyed object. The object key is injected into the named identity property
of each element.

```java
// External JSON: { "consumers": { "orders": {...}, "refunds": {...} } }
record KafkaConfig(@KeyedBy("name") List<KafkaConsumerConfig> consumers) {}
```

The element type must declare a settable property matching the annotation value. A conflict between
an explicit JSON value and the injected key is a `ConfigurationException`; equal values are
accepted. The deserializer backing it ships in `dev.vertique:vertique-json`.

### `JsonConfigPaths`

| Method | Semantics |
|---|---|
| `navigateObject(JsonObject root, String... segments)` | Tolerant subtree traversal: returns an empty `JsonObject` when a segment key is absent, and skips blank/`null` segments. **Throws `ConfigurationException`** when a segment key is present but bound to a non-object — a scalar, an array, or explicit JSON `null`. |
| `resolve(JsonObject root, String dottedPath)` | Strict dotted-path lookup returning a `LookupResult(status, value, path, failingSegment)`. |

`LookupStatus` is `PRESENT`, `MISSING`, or `INVALID_SHAPE`. `INVALID_SHAPE` means traversal crossed
a non-object intermediate segment. `PRESENT` covers any leaf, including a `JsonObject` or
`JsonArray` — a caller expecting a scalar must check.

### `@ConfigMapper`

Dagger `@Qualifier` for overriding the `ObjectMapper` that backs config parsing. Bind an
`@ConfigMapper ObjectMapper` anywhere in the application component; the config module reads it
optionally and re-layers the framework's mandatory modules and lenient policy over it before first
use.

The seam is entirely optional — an application that binds nothing gets the framework's lenient
default. Because the framework finalizes the supplied mapper in place, do not share that instance
concurrently for any other purpose.

### `ConfigSecretRenderer`

Shared redactor so every module's config `toString()` masks the same things. `MASK` is `"***"`, and
the input is never mutated — masking is applied to a copy used only for the returned string.

| Method | Behavior |
|---|---|
| `isSensitivePath(String dottedPath)` | `true` when the lower-cased path contains `password`, `secret`, `token`, `passphrase`, `credential`, `jaas.config`, `user.info`, `private.key`, `keystore`, or `truststore`; or when its separator-stripped, lower-cased form contains `apikey`, `accesskey`, or `secretkey`. |
| `redactBag(JsonObject bag)` | Renders a bag as a log-safe `String`, descending through nested objects *and* arrays and masking every leaf whose full dotted path is sensitive. |
| `redactUri(String uri)` | Masks authority userinfo (`user:pass` between `//` and `@`) and the value of any credential-bearing query parameter. Best-effort; never throws. |

Matching is substring-based in both forms, so `ssl.trustStoreOptions.password` and `aws.access-key`
are recognized at any depth.

### `ConfigTreeBuilder`

Builds the nested `JsonObject` the framework consumes from the flat dotted/bracketed keys an
embedding host exposes. `build(Map<String, String> flatKeys)` is purely syntactic — every leaf is
stored as a `String`, and type coercion is `ConfigParser`'s job downstream.

| Segment form | Interpretation |
|---|---|
| Bare segment, including all-digit (`2026`) | Object key |
| `[N]`, N a non-negative integer | Array index |
| `[content]`, quoted or containing a dot | Literal map key (dots preserved, surrounding quotes stripped) |

```
a.b=1, a.c=2                           → {"a":{"b":"1","c":"2"}}
years.2026.total=5                     → {"years":{"2026":{"total":"5"}}}
servers[0].host=h, servers[1].host=k   → {"servers":[{"host":"h"},{"host":"k"}]}
audit.bindings[http.server].dim[0]=x   → {"audit":{"bindings":{"http.server":{"dim":["x"]}}}}
```

Fail-fast rules, all raising `ConfigurationException`: array indices must be contiguous from `0`
with no gap, duplicate, or mixing of `[N]` with object keys at the same node; a path used as both a
leaf and a parent collides; a `null` input map is rejected. Every message names offending
keys/paths only, never values. Input order is irrelevant — keys are processed in a stable sorted
order, so collision detection does not depend on map iteration order. A `null` value for a key is
stored as JSON `null`.

### `PropertyCondition`

Immutable record evaluated at runtime by code generated from `@ConditionalOnProperty`. It is public
and stable, but it is not for hand-written Dagger bindings — the annotation is codegen-only.

```java
public record PropertyCondition(String name, String havingValue, boolean matchIfMissing) {
    public static boolean matchesAll(JsonObject config, PropertyCondition[] conditions);
}
```

| Component | Annotation default | Meaning |
|---|---|---|
| `name` | — (required) | Dotted config property path, e.g. `feature.adminApi.enabled` |
| `havingValue` | `"true"` | Expected scalar; compared with `String.valueOf(value).equals(havingValue)` |
| `matchIfMissing` | `false` | When `true`, a missing path counts as a match |

`matchesAll` ANDs the array and returns `true` for an empty or `null` array. A `MISSING` path
returns `false` unless `matchIfMissing` is set, in which case evaluation continues. An
`INVALID_SHAPE` path, or a present leaf that is a `JsonObject`/`JsonArray`, throws
`ConfigurationException`.

---

## Failures, Constraints, and Common Mistakes

### Exception hierarchy

```
VertiqueException (RuntimeException)
├── ValidationException                     — invalid input or data
│   ├── BusinessRuleException               — business/domain-rule violation
│   │   └── DurableEncodeRejectedException  — a durable-context encoder rejected the whole
│   │                                         capture/merge operation
│   └── MalformedDurableMetadataException   — durable carrier or namespace body present but not a
│                                             JSON object
├── ConflictException                       — resource state conflict
├── NotFoundException                       — resource absent
├── ConfigurationException                  — startup/wiring/contract error
├── TechnicalException                      — runtime/infrastructure failure
│   └── UnavailableException                — capability currently unavailable
└── VertiqueSecurityException               — grouping root for the security family
    ├── UnauthorizedException               — authentication required or credential invalid
    └── ForbiddenException                  — authenticated but not authorized
```

Every root is **concrete** — it may be thrown directly or subclassed. The one exception is
`VertiqueSecurityException`: catch it to handle any security denial, but throw
`UnauthorizedException` or `ForbiddenException` instead of the grouping root. Prefer the most
specific applicable root; a new module exception should never extend raw `RuntimeException`.

### HTTP mapping at a REST boundary

The default mapper in `dev.vertique:vertique-rest-jaxrs` registers these core types:

| Exception | Status |
|---|---:|
| `ValidationException` (and `BusinessRuleException`, `BeanValidationException`) | 400 |
| `UnauthorizedException` | 401 |
| `ForbiddenException` | 403 |
| `NotFoundException` | 404 |
| `ConflictException` | 409 |
| `UnavailableException` | 503 |
| `Throwable` (fallback) | 500 |

Mapping is hierarchy-aware, so a subclass inherits its nearest registered ancestor's status.
`TechnicalException`, `ConfigurationException`, and a bare `VertiqueSecurityException` have **no**
explicit registration and therefore reach the `Throwable` fallback as 500. `ForbiddenException` is
registered by fully-qualified name to avoid clashing with `jakarta.ws.rs.ForbiddenException`. The
REST defaults reference only core, JAX-RS, and REST-owned types — a database exception that escapes
a repository surfaces as 500, so translate it to a core semantic type at your service boundary.

### Event-bus transport failures

`EventBusExceptionMapper` translates Vert.x `ReplyException`s; the set of reply failures is a fixed
enum, so this mapper is not extensible. Non-`ReplyException` throwables pass through unchanged.

| Reply failure | Translated to | Root |
|---|---|---|
| `TIMEOUT` | `EventBusTimeoutException` | `TechnicalException` |
| `NO_HANDLERS` | `EventBusAddressUnavailableException` | `UnavailableException` |
| `RECIPIENT_FAILURE`, `ERROR` | `EventBusDispatchException` | `TechnicalException` |

Each carries `address()`. Service-layer callers receive enriched subclasses from
`dev.vertique:vertique-services` that add the failing contract.

### Resilience annotation semantics

The resilience annotation semantics, declaration fields, and retry bounds are documented by
`dev.vertique:vertique-resilience` and the consumer modules that enforce them. Core does not own the
runtime or annotation vocabulary.

### Constraints and common mistakes

- **Do not read the root `@VertxConfig JsonObject` outside a boundary provider.** Module internals
  take typed config records. Do not disambiguate same-typed bindings with `@Named` — declare a
  dedicated `@Qualifier`.
- **Do not use priority to emulate cross-phase ordering.** Phase dominates priority absolutely in
  both ordering contracts.
- **Give lambda-registered extensions a stable identity.** The default `orderKey()` of a lambda or
  method reference is a synthetic class name that is not stable across compilations, so ties break
  unpredictably. Override `orderKey()` or assign distinct `priority()` values.
- **The dispatch codecs are local-only.** `LocalMessageCodec` throws
  `UnsupportedOperationException` from both wire methods, so a clustered event bus fails loudly
  rather than degrading silently; `transform` returns the object reference unchanged, which means
  no defensive copy occurs on a local hop. Registration of the `dispatch.envelope` and
  `dispatch.result` codec names is owned by whichever deployment path an application installs —
  `dev.vertique:vertique-services`, `dev.vertique:vertique-kafka-core`, or
  `dev.vertique:vertique-job-delayed` — each tolerating an already-registered name. Core itself
  registers neither, so `EventBusClient` is usable only once one of those modules has deployed.
- **`PayloadSources` never copies.** Copy the array yourself before calling `buffered(byte[], …)`
  if you need immutability, and prefer `bufferedStream()` over `bufferedView()` on hot paths for
  non-`Buffer` backings.
- **A `VerticleDeployment` requires a verticle phase.** Constructing one with `CONFIGURE`,
  `VALIDATE`, `MIGRATE`, or `AFTER_START` is rejected at construction time.
- **Do not call `JacksonConfigurer.configure()` yourself.** With `CoreLifecycleStepsModule` in the
  component, `JacksonConfigureStep` already runs it during `CONFIGURE`. The configurer is
  idempotent, so a second call logs a warning and returns without applying anything.
- **Do not implement `MethodMetadata` or `ParameterMetadata` in application code.** They are
  provided by generated code and by the framework's own scanners.
- **Never log a secret-bearing config record without redaction.** Route the value through
  `ConfigSecretRenderer` in the record's `toString()` rather than relying on `@JsonIgnore`, which on
  a record would also block deserialization and make the value unreadable from configuration.

---

## Dependencies

`vertique-core` depends on no other Vertique module — it is the root of the dependency graph, and
every compile-scope dependency it declares lands on every consumer's classpath.

| Dependency | Why |
|---|---|
| `io.vertx:vertx-core` | `Vertx`, `EventBus`, `Future`, `Buffer`, `JsonObject` — the substrate every contract here is expressed in |
| `com.google.dagger:dagger` | `@Module`, `@Provides`, `@Multibinds` for `VertxModule`, `JsonModule`, `HealthCheckModule`, `CoreLifecycleStepsModule` |
| `jakarta.inject:jakarta.inject-api` | `@Inject`, `@Singleton`, `@Qualifier`, `Provider` on the injectable types and qualifiers |
| `jakarta.annotation:jakarta.annotation-api` | `@Nullable` on nullable record components, SPI parameters, and return values |
| `jakarta.ws.rs:jakarta.ws.rs-api` | Declared at compile scope; core's own sources do not reference it, so every consumer receives the JAX-RS API on its classpath transitively |
| `com.fasterxml.jackson.core:jackson-databind` | `ObjectMapper` on the JSON customization SPI, the profile contracts, and the serialization annotations on result records |
| `org.slf4j:slf4j-api` | Logging in `JacksonConfigurer` and the other core collaborators |
| `org.projectlombok:lombok` | `provided` scope, so it never reaches runtime; core's own sources do not use it |
