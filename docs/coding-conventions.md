<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Coding Conventions

This document defines the coding conventions for the vertique project. Both human developers and AI agents should follow these rules.

---

## Formatting

Formatting is enforced by **Spotless Maven Plugin** with **Palantir Java Format**.

| Setting | Value |
|---------|-------|
| Line length | 120 characters |
| Indentation | 4 spaces (no tabs) |
| Method chain split | 80 characters |
| Charset | UTF-8 |
| Line endings | LF |
| Unused imports | Removed automatically |

### Commands

```bash
./mvnw spotless:apply              # auto-fix formatting
./mvnw spotless:check              # check without modifying (CI use)
```

Run `spotless:apply` before committing. The formatter handles line wrapping, indentation, import ordering, and whitespace — do not fight it.

---

## Naming

### Packages

All packages start with `dev.vertique.{module}`. Sub-packages are allowed for logical grouping (for example, `dev.vertique.rest.security`).

### Classes

Use role-indicating suffixes consistently:

| Suffix | Role | Example |
|--------|------|---------|
| `*Module` | Dagger `@Module` | `RestModule`, `AuthModule` |
| `*Component` | Dagger `@Component` | `AppComponent` |
| `*Verticle` | Vert.x verticle | `HttpVerticle`, `MainVerticle` |
| `*Registry` | Hierarchy-aware dispatcher | `ExceptionMapperRegistry` |
| `*Hook` | Lifecycle hook interface | `RouterLifecycleHook` |
| `*Interceptor` | Request/operation/error interceptor interface | `RequestInterceptor`, `OperationInterceptor`, `ErrorInterceptor`, `ServiceInterceptor` |
| `*Middleware` | Router-level `Handler<RoutingContext>` with scope/order | `CorrelationIngressMiddleware` |
| `*Contributor` | `OperationHandlerContributor` impl | `IdentityResolutionContributor` |
| `*Resolver` | Chain-of-responsibility SPI | `SecurityIdentityResolver` |
| `*Mapper` | Exception/failure translator | `FailureMapper`, `DefaultExceptionMapper` |
| `*Producer` | Response type dispatcher | `ResponseProducer` |
| `*Binding` | Dagger multibinding wrapper | `CanonicalizerBinding`, `SanitizerBinding` |
| `*Meta` | Immutable metadata record | `ResourceMethodMeta` |
| `*Bootstrap` | One-shot startup helper | `ConfigBootstrap` |
| `*Config` | Jackson-deserialized config VO | `HelloConfig` |
| `*Violation` | Validation problem record | `SecurityPolicyViolation` |
| `*ExceptionMapper` | JAX-RS `ExceptionMapper<T>` impl | `GreetingLimitExceptionMapper` |
| `*ProblemDetail` | RFC 9457 typed error body | `GreetingLimitProblemDetail` |

### Methods

- `camelCase` throughout
- Factory methods: `of(...)`, `none()`, `jwt()`, `custom(...)`
- `@Provides` methods named after what they return: `failureMapper()`, `httpVerticle()`
- Boolean checks: `hasScope()`, `isSuccess()`, `isFailure()`
- Transformers: `map()`, `flatMap()`, `recover()`, `fold()`
- Hook/interceptor callbacks: `beforeX()`, `afterX()`, `recoverX()`, `transformX()` — e.g., `beforeAuthSetup`, `afterRouterCreated`, `beforeOperation`, `transformResponse`

### Enums

`SCREAMING_SNAKE_CASE` for constants. Each constant should have a brief inline javadoc.

---

## Javadoc

### Policy

Every public class, interface, enum, record, and method must have javadoc. This is enforced by the `code-reviewer` agent.

### Requirements

- `@param` for each parameter
- `@return` for non-void methods
- `@throws` for declared exceptions
- Records: document component params at the class level, not per-component
- Functional interfaces: document the contract and type parameters in the class javadoc

### Style

- `{@link ClassName}` for cross-references
- `{@code literal}` for inline code, `<pre>{@code ...}</pre>` for code blocks
- `<p>` for additional paragraphs
- `<ul>/<li>` for unordered lists, `<ol>/<li>` for ordered
- Non-public methods also get javadoc when non-trivial

### Section comments

Use `// --- Section Title ---` to separate logical groups within a class.

---

## Lombok

Use Lombok sparingly and only for specific patterns:

| Annotation | When to use |
|------------|-------------|
| `@Slf4j` | Logging field on any class that logs |
| `@Getter` | Immutable value types, config objects |
| `@SuperBuilder` | Classes intended for subclassing (e.g., `ProblemDetail`) |
| `@Builder` + `@Jacksonized` | Jackson-deserialized config/DTO classes |
| `@RequiredArgsConstructor` | Classes with final fields and no `@Inject` constructor |
| `@Accessors(fluent = true)` | Fluent getter style (`x()` not `getX()`) |

**Jackson + fluent accessors:** When using `@Accessors(fluent = true)`, Jackson cannot discover fields via getters. Add:
```java
@JsonAutoDetect(fieldVisibility = ANY, getterVisibility = NONE)
```

**`@SuperBuilder` classes** (e.g., `ProblemDetail`) require:
```java
@Getter @SuperBuilder @Accessors(fluent = true) @EqualsAndHashCode
@AllArgsConstructor(access = PROTECTED)
@FieldDefaults(makeFinal = true) @JsonInclude(NON_NULL)
@JsonAutoDetect(fieldVisibility = ANY, getterVisibility = NONE)
```

---

## Dagger Dependency Injection

### Constructor injection

Always use constructor injection with `@Inject`:
```java
@Singleton
public class MyService {
    private final Vertx vertx;

    @Inject
    MyService(Vertx vertx) {
        this.vertx = vertx;
    }
}
```

Never use field injection.

### Modules

- **Abstract** when only declaring `@Multibinds` and `@Provides static` methods
- **Concrete** when the module needs constructor parameters (e.g., `VertxModule`, `ConfigModule`)

### Multibinding

Extension points use `Set<T>` multibinding:
```java
@Provides @IntoSet
static ExceptionMapper<?> myMapper(MyExceptionMapper mapper) {
    return mapper;
}
```

Declare the empty set in the framework module:
```java
@Multibinds
abstract Set<ExceptionMapper<?>> exceptionMappers();
```

### Scope

`@Singleton` goes on the class (not just the `@Provides` method) for singleton components.

---

## Error Handling

### Core module (no HTTP dependency)

Use `FailureTranslator` (no-context, `Throwable → Throwable`) or `ContextAwareFailureTranslator` (with a `String context` second arg) for translation. `FailureMapper` is the concrete, context-aware engine that walks the superclass chain and returns the original throwable if no translator matches; it may be used directly or extended by layer mappers. Layer-specific mappers (`RestExceptionMapper`, `ServiceExceptionMapper`) accept customizers via Dagger `@IntoSet` multibindings.

### REST layer

Throw standard JAX-RS exceptions:
- `jakarta.ws.rs.NotFoundException` → 404
- `jakarta.ws.rs.BadRequestException` → 400
- `jakarta.ws.rs.ForbiddenException` → 403
- `jakarta.ws.rs.NotAuthorizedException` → 401
- `jakarta.ws.rs.WebApplicationException` → custom status

`IllegalArgumentException` is auto-mapped to 400. All unhandled exceptions return 500.

**Custom exception pattern (REST):**
1. Create domain exception (no HTTP dependency)
2. Create `ExceptionMapper<T>` implementation with `@Inject` constructor
3. Optionally create `ProblemDetail` subclass with `@SuperBuilder` for typed error body
4. Register via `@Provides @IntoSet ExceptionMapper<?>` in the app's `ResourceModule`

Error responses use RFC 9457 Problem Details format.

---

## Async Patterns

### Return types in JAX-RS methods

| Return type | Behavior |
|-------------|----------|
| `Future<T>` | Async, T serialized as JSON with 200 |
| `Future<Void>` | Async, 204 No Content |
| `Future<Response>` | Async, custom status/headers/body |
| `T` | Sync, serialized as JSON with 200 |
| `void` | Sync, 204 No Content |
| `Response` | Sync, custom status/headers/body |

### Rules

- Never block the Vert.x event loop
- Use `Future.compose()`, `.map()`, `.recover()` for chaining
- `Future.onFailure()` is a side-effect handler, NOT a failure consumer — the future still propagates failure
- Use `Future.succeededFuture()` and `Future.failedFuture()` for wrapping

---

## Testing

### Naming

| Convention | Scope |
|------------|-------|
| `*Test.java` | Unit tests (Maven Surefire) |
| `*IT.java` | Integration tests (Maven Failsafe, `./mvnw verify`) |

### Unit tests

- Package-private class (no `public` modifier)
- `@DisplayName` on every `@Test` method
- `@Nested` classes for grouping related scenarios
- Class-level javadoc describing what is verified
- `assertInstanceOf()` over `assertTrue(x instanceof Y)`
- `assertSame()` for reference equality
- Static inner classes for test doubles

### Integration tests

- `public` class with `@ExtendWith(VertxExtension.class)`
- `@Timeout(value = 20, unit = TimeUnit.SECONDS)` at class level (mandatory)
- `@BeforeAll static void setUp(Vertx vertx, VertxTestContext ctx)` for deployment
- `RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter())` in `@BeforeAll`
- Do NOT inject `VertxTestContext` into `@Test` methods using synchronous RestAssured — if an assertion fails, `ctx.completeNow()` is never called and the test hangs

### Async unit tests

- `@ExtendWith(VertxExtension.class)` for Vert.x context
- `testContext.succeeding(result -> { ... testContext.completeNow(); })` pattern
- Always call `testContext.completeNow()` at end of assertion block

---

## Collection Processing

**Prefer streams for filter/transform/collect.** When filtering or transforming collections, use `stream().filter().map().collect()` instead of imperative loops with if-guards. Streams make the pipeline intent explicit and reduce mutable-state boilerplate.

```java
// Preferred
return array.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .filter(s -> !s.isBlank())
        .collect(Collectors.toSet());

// Avoid
Set<String> values = new HashSet<>();
for (int i = 0; i < array.size(); i++) {
    Object element = array.getValue(i);
    if (element instanceof String s && !s.isBlank()) {
        values.add(s);
    }
}
return values;
```

---

## Imports

- No wildcard imports (enforced by Spotless)
- Static imports for test assertion methods (`assertThat`, `assertEquals`, `mock`, `when`, etc.)
- Import ordering is managed by the formatter — do not rearrange manually

---

## Commit Messages

Use [Conventional Commits](https://www.conventionalcommits.org/) format:

```
<type>(<scope>): <short description>
```

**Types:** `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `chore`

**Scopes:** module names — `core`, `rest`, `audit`, `config`, `logging`, `rest-openapi-plugin`, `examples`, `bom`
