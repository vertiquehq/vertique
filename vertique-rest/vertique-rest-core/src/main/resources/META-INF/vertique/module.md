<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Core Module

> **Status:** Implemented
> **Package:** `dev.vertique.rest.core` (+ 10 sub-packages)
> **Artifact:** `rest-core`
> **Depends on:** core

Extension API for the HTTP layer. Defines all interfaces, annotations, lifecycle hooks, middleware abstractions, and Dagger multibinding declarations used by `rest-jaxrs`, `rest-security`, `audit-rest`, and application modules. Contains no JAX-RS routing runtime — that lives in `rest-jaxrs`. Also owns the REST completion event infrastructure: `RestRequestCompletionEmitter`, `RestRequestCompletedEvent`, `RestRequestCompletedListener`, `RequestCompletionScope`, and `OperationIdCaptureContributor` in `dev.vertique.rest.core.events`.

### Package Layout

| Package | Contents |
|---------|----------|
| `rest.core` | `ProblemDetail`, `ValidationProblemDetail`, `ValidationErrorDetail` (with `args`), `RestValidationException`, `RestConfigurationException` |
| `rest.core.config` | `HttpConfig`, `CorsConfig`, `SslConfig`, `JaxRsConfig`, `DefaultHeadersConfig` |
| `rest.core.dagger` | `RestCoreModule`, `JaxRsResources` |
| `rest.core.lifecycle` | `RouterLifecycleHook` |
| `rest.core.interceptor` | `RequestInterceptor`, `OperationInterceptor`, `ErrorInterceptor`, `OperationContext` |
| `rest.core.middleware` | `Middleware`, `MiddlewareScope`, `RequestContextLifecycle` (+ `Handle`), `MdcKeys`, `ContextualLoggingMiddleware`, `DefaultHeadersMiddleware`, `ContentTypeValidationMiddleware` |
| `rest.core.pagination` | `OffsetPage`, `OffsetPageRequest`, `CursorPage`, `CursorPageRequest`, `CursorCodec`, `PlainCursorCodec`, `InvalidCursorException` |
| `rest.core.request` | `MediaType`, `AcceptNegotiator`, `RequestBodyDecoder`, `RequestParams`, `RequestPreconditions`, `FilePart`; `InputObjectProcessor`, `DefaultInputObjectProcessor`, `EffectiveInputPolicies`, `InputPolicyMetadata` (with nested `FieldPolicyMetadata`), `InputPolicyMetadataResolver` |
| `rest.core.response` | `ResponseProducer`, `ResponseProducerBinding`, `ResponseBodyEncoder`, `ResponseSerializer`, `BufferedBody`, `StreamingBody`, `SerializedBody` |
| `rest.core.router` | `HttpVerticle`, `RouterMount`, `MountMeta`, `MountCustomizer`, `RouterCustomizer`, `OperationHandlerContributor`, `OperationRegistrationContext` |
| `rest.core.context` | `RestContextResolver` (SPI), `RestContextResolution`, `RestContextUnavailableException`, `RestContextTypes`; built-in resolvers: `RoutingContextResolver` (100), `JaxRsSecurityContextResolver` (110), `ContextHolderResolver` (120); `RestContextModule` |
| `rest.core.security` | `Authorized`, `SecurityRuntime`, `SecuritySchemeHandler`, `SecurityPolicy`, `SecurityPolicyValidator`, `SecurityPolicyViolation`, `SecurityPolicyViolationException`, `SecurityPolicyResolver`, `AnnotationSecurityPolicyResolver`, `RouteAuthHandler` |
| `rest.core.sse` | `SseEvent`, `SseChannel`, `SseChannelFactory`, `SseChannelOptions`, `BufferOverflowPolicy` |
| `rest.core.convert` | `ParamConverter` (SPI), `ParamConverterBinding`, `ParamConverterRegistry`, `ParamConversionResolver`, `ConversionContext`, `ParamSource`, `ParamConversionException`, `ParamConverterNotFoundException` |

---

## Overview

`rest-core` is the public contract layer of the HTTP stack:

- `HttpVerticle` — generic HTTP server that composes sub-routers
- `RouterMount` / `MountCustomizer` / `RouterCustomizer` — sub-router composition API
- `Middleware` / `MiddlewareScope` — scoped, ordered request handlers
- `RouterLifecycleHook` — router creation phase hooks
- Interceptors — `RequestInterceptor`, `OperationInterceptor`, `ErrorInterceptor` (with `OperationContext`)
- `OperationHandlerContributor` — per-operation handler injection into the OpenAPI chain
- `ResponseProducer` / `ResponseSerializer` — response pipeline interfaces
- `ProblemDetail` — RFC 9457 error response body
- `SecurityRuntime` / `SecurityPolicyValidator` / `SecuritySchemeHandler` — security SPI types
- `JaxRsResources` — Dagger qualifier for JAX-RS resource multibinding
- `Authorized` — framework security annotation
- `RestCoreModule` — Dagger module declaring all multibindings and defaults

---

## Key Classes

### HttpVerticle

Generic HTTP server verticle that composes a main router from `RouterMount` sub-routers, ROOT-scoped middlewares, and `RouterCustomizer` hooks. Does not know about JAX-RS or OpenAPI — that logic lives in `JaxRsRouterMount` (in `rest-jaxrs`).

**Constructor parameters (all injected via Dagger):**

| Parameter | Type | Description |
|-----------|------|-------------|
| `serverOptions` | `HttpServerOptions` | HTTP server options built from `HttpConfig` by `RestCoreModule` |
| `routerCustomizers` | `Set<RouterCustomizer>` | Main router customization hooks |
| `middlewares` | `Set<Middleware>` | ROOT-scoped request handlers |
| `routerMounts` | `Set<RouterMount>` | Sub-routers to compose |
| `mountCustomizers` | `Set<MountCustomizer>` | Per-mount post-creation hooks |

**Startup sequence:**

```
1. Validate mount paths (must start with /, end with /*, no double slashes)
2. Detect overlapping mount paths (warn, don't fail)
3. Sort mounts in OrderedExtension order: phase → priority → mountPath → orderKey
4. Create mainRouter
5. Mount ROOT-scoped Middlewares (sorted by OrderedExtension.comparator(): phase → priority → orderKey)
6. Run BEFORE_MOUNTS RouterCustomizers (mountPhase() == MountPhase.BEFORE_MOUNTS; ordered within the phase by OrderedExtension.comparator(): phase → priority → orderKey)
7. For each mount: createRouter(vertx) → apply MountCustomizers → mount as sub-router
8. Run AFTER_MOUNTS RouterCustomizers (mountPhase() == MountPhase.AFTER_MOUNTS; ordered within the phase by OrderedExtension.comparator(): phase → priority → orderKey)
9. Start HTTP server on configured port
```

### RouterMount

Interface for providing a sub-router to mount on the main router at a specific path prefix.

```java
public interface RouterMount extends OrderedExtension {
    default String mountPath() { return "/*"; }
    Future<Router> createRouter(Vertx vertx);
    default MountMeta meta() {
        return new MountMeta(getClass().getName(), mountPath(), null, Set.of());
    }
}
```

| Method | Description |
|--------|-------------|
| `mountPath()` | Path prefix; must start with `/` and end with `/*`. Default: `"/*"` |
| `priority()` | Mount order (lower = earlier). Default: `0` — inherited from `OrderedExtension` |
| `createRouter(Vertx)` | Async router creation; `HttpVerticle` fails startup if this future fails |
| `meta()` | Metadata for `MountCustomizer` targeting; override for stable IDs |

Contributed via `Set<RouterMount>` Dagger multibinding.

**Example — simple health check mount:**

```java
@Provides @IntoSet
RouterMount healthMount() {
    return new RouterMount() {
        @Override public String mountPath() { return "/health/*"; }
        @Override public Future<Router> createRouter(Vertx vertx) {
            Router r = Router.router(vertx);
            r.get("/").handler(ctx -> ctx.response().end("OK"));
            return Future.succeededFuture(r);
        }
    };
}
```

### MountMeta

Metadata record describing a `RouterMount`. Used by `MountCustomizer#matches()` to selectively target specific mounts.

```java
public record MountMeta(
    String mountId,              // stable ID (FQCN by default, "jaxrs:/api/*" for JaxRsRouterMount)
    String mountPath,            // path prefix
    @Nullable String openapiPath, // classpath OpenAPI spec, or null for non-JAX-RS mounts
    Set<Class<?>> resourceTypes  // JAX-RS resource classes, or empty for non-JAX-RS mounts
) {}
```

### MountCustomizer

Per-mount customization hook applied after `RouterMount#createRouter()` completes and before the sub-router is mounted on the main router.

```java
public interface MountCustomizer extends OrderedExtension {
    default boolean matches(MountMeta meta) { return true; }
    void customize(Router mountRouter, MountMeta meta);
}
```

**Example — CORS only on JAX-RS mounts:**

```java
@Provides @IntoSet
MountCustomizer apiCors() {
    return new MountCustomizer() {
        @Override public boolean matches(MountMeta meta) {
            return meta.mountId().startsWith("jaxrs:");
        }
        @Override public void customize(Router router, MountMeta meta) {
            router.route().handler(CorsHandler.create().addOrigin("*"));
        }
    };
}
```

### RouterCustomizer

Customize the main Vert.x router. Extends `OrderedExtension`. `MountPhase` controls whether customization runs before or after `RouterMount` sub-routers are mounted:

```java
public interface RouterCustomizer extends OrderedExtension {
    void customize(Router router);
    default MountPhase mountPhase() { return MountPhase.BEFORE_MOUNTS; }

    enum MountPhase {
        BEFORE_MOUNTS,  // before any sub-routers are mounted (default)
        AFTER_MOUNTS    // after all sub-routers are mounted
    }
}
```

`mountPhase()` partitions customizers into BEFORE_MOUNTS and AFTER_MOUNTS groups. Within each mount-phase, ordering follows the `OrderedExtension` order (phase → priority → orderKey). Contributed via `Set<RouterCustomizer>` Dagger multibinding.

**Example — global CORS before mounts:**

```java
@Provides @IntoSet
RouterCustomizer corsCustomizer() {
    return router -> router.route().handler(CorsHandler.create()
        .addOrigin("*")
        .allowedMethod(HttpMethod.GET)
        .allowedMethod(HttpMethod.POST));
}
```

**Example — SPA fallback after mounts:**

```java
@Provides @IntoSet
RouterCustomizer spaFallback() {
    return new RouterCustomizer() {
        @Override public MountPhase mountPhase() { return MountPhase.AFTER_MOUNTS; }
        @Override public void customize(Router router) {
            router.get("/*").handler(StaticHandler.create("webroot"));
        }
    };
}
```

### Middleware

Auto-registered, scoped, ordered request handlers. Extends `Handler<RoutingContext>` and `OrderedExtension`:

```java
public interface Middleware extends Handler<RoutingContext>, OrderedExtension {
    int priority();  // abstract override of OrderedExtension.priority() — every implementation must declare it
    default MiddlewareScope scope() { return MiddlewareScope.ROOT; }
    default String path() { return "/*"; }
}

public enum MiddlewareScope { ROOT, API }
```

- `ROOT` scope: applied to the main router (all requests)
- `API` scope: applied to the OpenAPI sub-router (validated routes only)
- Sorted by `OrderedExtension.comparator()` (phase → priority → orderKey) before mounting
- Contributed via `Set<Middleware>` Dagger multibinding

### ProblemDetail

RFC 9457 Problem Details response body. Used as the default error format for all built-in exception mappers.

**Fields** (all optional, omitted from JSON when `null`):

| Field | Type | Description |
|-------|------|-------------|
| `type` | `String` | URI reference identifying the problem type |
| `title` | `String` | Short human-readable summary |
| `status` | `Integer` | HTTP status code |
| `detail` | `String` | Human-readable explanation of this occurrence |
| `instance` | `String` | URI identifying the specific occurrence |
| extensions | `Map<String, Object>` | RFC 9457 extension members (serialized as inline sibling fields via `@JsonAnyGetter`) |

**Factory methods** (no-builder shorthand):

```java
// type="about:blank", title auto-derived from status code
ProblemDetail.of(404, "Item 123 not found");
ProblemDetail.of(404, "Item 123 not found", "/requests/abc");
```

**Ad-hoc extension fields** via builder:

```java
ProblemDetail problem = ProblemDetail.builder()
    .type("https://api.example.com/problems/validation-failed")
    .title("Validation Failed")
    .status(422)
    .detail(ex.getMessage())
    .extension("errors", ex.getErrors())   // becomes a sibling JSON field
    .extension("field", "email")
    .build();
```

**Typed subclass** via `@SuperBuilder` (compile-time-safe named fields):

```java
@Getter
@SuperBuilder
@Accessors(fluent = true)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonAutoDetect(fieldVisibility = ANY, getterVisibility = NONE)
public class GreetingLimitProblemDetail extends ProblemDetail {

    int limit;

    public static GreetingLimitProblemDetail of(int limit) {
        return GreetingLimitProblemDetail.builder()
            .type("https://api.example.com/problems/greeting-limit-exceeded")
            .title("Greeting Limit Exceeded")
            .status(429)
            .detail("You have exceeded your greeting limit of " + limit)
            .limit(limit)
            .build();
    }
}
```

`ProblemDetail` uses `@Accessors(fluent = true)`, so subclasses must carry the same Jackson annotations to suppress getter-based serialization.

### ValidationProblemDetail

RFC 9457 Problem Details body for structured validation errors. Extends `ProblemDetail` with an `errors` array. Used by `DefaultExceptionMapper` for both `RestValidationException` and `BeanValidationException`.

```java
@Getter @SuperBuilder(toBuilder = true) @Accessors(fluent = true)
@JsonInclude(NON_NULL) @JsonAutoDetect(fieldVisibility = ANY, getterVisibility = NONE)
public class ValidationProblemDetail extends ProblemDetail {
    @Singular List<ValidationErrorDetail> errors;

    // Convenience factory — status 400, type "about:blank", title "Bad Request"
    public static ValidationProblemDetail of(String detail, List<ValidationErrorDetail> errors) { ... }
}
```

**JSON shape:**

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed",
  "errors": [
    { "path": "email", "detail": "must be a well-formed email address", "location": "body", "type": "email" },
    { "path": "limit", "detail": "must be ≤ 100", "location": "query", "type": "max", "args": { "value": 100 } }
  ]
}
```

### ValidationErrorDetail

Describes a single validation error with HTTP location context. Designed to work with both OpenAPI validation and Jakarta Bean Validation. `null` fields are omitted from JSON serialization.

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationErrorDetail(
    String path,                        // violated field path (e.g. "/name", "email")
    String detail,                      // human-readable description of the failure
    @Nullable String location,          // HTTP location: "body", "query", "header", "path", "cookie", "form", "file", or null
    @Nullable String type,              // error classification: "required", "size", "min", "email", "pattern", etc.
    @Nullable Map<String, Object> args  // constraint arguments (e.g. {min: 1, max: 100} for @Size); null when none
) {
    // Simple factory — no location, type, or args
    public static ValidationErrorDetail of(String path, String detail) { ... }
}
```

The `args` field carries constraint arguments extracted from the annotation by the `validation` module's `ViolationArgsInspector` SPI. For standard Jakarta constraints, built-in inspectors populate it automatically:

| Constraint | `args` example |
|---|---|
| `@Size(min=1, max=100)` | `{min: 1, max: 100}` |
| `@Min(value=0)` | `{value: 0}` |
| `@Max(value=255)` | `{value: 255}` |
| `@Pattern(regexp="...")` | `{regexp: "..."}` |
| `@NotNull`, `@NotBlank` | `null` (no meaningful args) |

### RestValidationException

Extends `ValidationException` (from `core.exception`) with a structured list of `ValidationErrorDetail` entries. Thrown by `ConstraintViolationMapper` in `rest-jaxrs` after Bean Validation and mapped to HTTP 400 by `DefaultExceptionMapper`.

```java
public class RestValidationException extends ValidationException {
    public RestValidationException(String message, List<ValidationErrorDetail> errors) { ... }
    public List<ValidationErrorDetail> errors() { ... }  // unmodifiable
}
```

### MediaType

Immutable value object representing an HTTP media type (RFC 9110). Stores type, subtype, parameters (excluding `q`), and quality factor — all lowercase.

```java
public final class MediaType {
    public MediaType(String type, String subtype, Map<String, String> parameters, double qualityFactor) { ... }

    public static MediaType parse(String raw) { ... }  // returns null for null/blank/invalid
    public static MediaType valueOf(String raw) { ... } // alias for parse()

    public String type() { ... }
    public String subtype() { ... }
    public Map<String, String> parameters() { ... }
    public double qualityFactor() { ... }

    public boolean isWildcardType() { ... }
    public boolean isWildcardSubtype() { ... }
    public boolean isCompatible(MediaType other) { ... }  // wildcard-aware
    public int specificity() { ... }     // 0=*/*,  1=type/*, 2=type/subtype, 3=type/subtype+params
    public String withoutParameters() { ... }   // "type/subtype"
}
```

**Compatibility rules for `isCompatible()`:** either side wildcard type → compatible; types differ → not compatible; either side wildcard subtype → compatible; subtypes match → compatible. Parameters are ignored.

**Equality** excludes the quality factor — two media types with different `q` values but identical type/subtype/parameters are equal.

### AcceptNegotiator

Static utility for RFC 9110 Accept header negotiation. Selects the most preferred server-side media type acceptable to the client.

```java
public final class AcceptNegotiator {
    // Returns the best matching server type (lowercase "type/subtype"), or null if no match.
    // Null/blank accept → returns first server type. Empty serverTypes → null.
    public static String negotiate(String acceptHeader, List<String> serverTypes) { ... }

    // Parses Accept header into a sorted list (q-value desc, specificity desc tiebreaker).
    // Entries with q=0 are excluded. Capped at 50 entries.
    public static List<MediaType> parseAcceptHeader(String accept) { ... }
}
```

Used by `ResponsePipeline` (in `rest-jaxrs`) in the unregistered-type fallback to negotiate response content type against `@Produces`, returning 406 when no match is found.

### RequestBodyDecoder

SPI for pluggable request body deserialization. The framework invoker selects the first decoder (by priority) whose `canDecode()` returns `true` and delegates body decoding to it.

```java
public interface RequestBodyDecoder extends OrderedExtension {
    boolean canDecode(Class<?> targetType, String contentType);

    Object decode(RoutingContext ctx, BoundRequest request, Class<?> targetType);
}
```

Contributed via `@Multibinds Set<RequestBodyDecoder>` (declared in `RestCoreModule`). The framework invoker walks decoders in OrderedExtension order (phase → priority → orderKey); the first decoder whose `canDecode()` returns `true` is used. Application decoders default to priority `0` and therefore precede framework defaults at priority `1000`/`1100`. The `BoundRequest` parameter provides the validated/bound request context; decoders read the raw body buffer from it.

**Registration pattern:**

```java
@Provides @IntoSet
static RequestBodyDecoder xmlDecoder(XmlMapper mapper) {
    return new XmlRequestBodyDecoder(mapper);
}
```

### ResourceMethodMeta

Immutable Java record containing all metadata for a single JAX-RS method. Defined in `rest-core` so lifecycle hooks and contributors can inspect method metadata without depending on `rest-jaxrs`:

```java
public record ResourceMethodMeta(
    Object resourceInstance,
    Method method,
    String operationId,
    String httpMethod,
    String path,
    List<ParamMeta> params,
    Class<?> responseBodyType,
    boolean returnsFuture,
    boolean returnsVoid,
    SecurityAnnotations securityAnnotations,
    List<String> consumes,   // from @Consumes; empty = unconstrained
    List<String> produces    // from @Produces; empty = unconstrained
) {
    // Convenience constructor omits componentType (null)
    public record ParamMeta(String name, ParamSource source, Class<?> type, Class<?> componentType) {}

    public enum ParamSource {
        /** Value extracted from a URI path segment. */
        PATH,
        /** Value extracted from a URI query parameter. */
        QUERY,
        /** Value extracted from an HTTP request header. */
        HEADER,
        /** Value extracted from a request cookie. */
        COOKIE,
        /** Value deserialized from the HTTP request body. */
        BODY,
        /**
         * Any {@code @Context}-injected or auto-injectable type resolved through the
         * {@code RestContextResolver} chain by declared type — covers {@code RoutingContext},
         * JAX-RS {@code SecurityContext}, and any {@code ContextValue} (framework
         * {@code SecurityContext}, correlation, localization, application types).
         */
        CONTEXT,
        /** Conditional request preconditions injected as {@code RequestPreconditions}. */
        PRECONDITIONS,
        /** Named form field or file upload from multipart/form-data or application/x-www-form-urlencoded. */
        FORM,
        /** All file uploads as List<FileUpload> (unannotated). */
        FILE_UPLOADS,
        /** All multipart parts as List<EntityPart> (unannotated). */
        ENTITY_PARTS,
        /** Composite parameter object populated from multiple request parameters via {@code @BeanParam}. */
        BEAN_PARAM
    }
}
```

### JaxRsResources

Dagger qualifier annotation for the `Set<Object>` multibinding of JAX-RS resource instances:

```java
@Qualifier
@Retention(RUNTIME)
public @interface JaxRsResources {}
```

### Authorized

Framework security annotation for scope-based authorization (complements standard JAX-RS `@RolesAllowed`):

```java
@Target({METHOD, TYPE})
@Retention(RUNTIME)
public @interface Authorized {
    String[] scopes() default {};
    boolean matchAll() default false;
}
```

Combined `@RolesAllowed` + `@Authorized` = AND semantics (must pass both).

### SecurityRuntime

DI-managed bridge between the Vert.x routing layer and the JAX-RS resource layer. Reads and writes the `SecurityContext` for the current request. Defined in `rest-core`; implemented by `HolderBackedSecurityRuntime` in `rest-security`.

```java
public interface SecurityRuntime {
    /** Returns the current SecurityContext, or null if none is bound. Lenient. */
    SecurityContext current();

    /**
     * Binds the given SecurityContext into the current Vert.x context via ContextValues.
     * Returns a Scope that restores the prior binding on close.
     * Callers MUST register the returned scope with RequestContextLifecycle.Handle.onClose().
     */
    ContextHolder.Scope bindCurrent(SecurityContext context);

    jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure);
}
```

`JaxRsRouterMount.Factory` (in `rest-jaxrs`) accepts `@Nullable SecurityRuntime` — null when security is not configured.

### SecurityPolicyValidator

Startup validation interface for checking security policy consistency across JAX-RS annotations. Defined in `rest-core`; implemented by `DefaultSecurityPolicyValidator` in `rest-security`.

```java
public interface SecurityPolicyValidator {
    List<SecurityPolicyViolation> validate(ResourceMethodMeta meta, RestOperationDescriptor operation);
}
```

```java
public record SecurityPolicyViolation(
    String operationId,
    ViolationType type,
    String message
) {
    public enum ViolationType {
        ANNOTATION_WITHOUT_OPENAPI_SECURITY,
        OPENAPI_SECURITY_WITHOUT_HANDLER,
        CONFLICTING_SEMANTICS
    }
}
```

When a `SecurityPolicyValidator` is present (provided by `AuthModule`), any violation causes `SecurityPolicyViolationException` to be thrown immediately during startup. Omit `AuthModule` to skip validation entirely.

### SecurityPolicyResolver

Interface for resolving a `SecurityPolicy` from JAX-RS security annotations on a resource method. Defined in `rest-core`; implemented by `AnnotationSecurityPolicyResolver` (default) and delegated to by `SecurityPolicyBuilder` in `rest-jaxrs`.

```java
public interface SecurityPolicyResolver {
    SecurityPolicy resolve(ResourceMethodMeta meta);
}
```

`AuthModule` (in `rest-security`) contributes `AnnotationSecurityPolicyResolver` as the default binding. Applications can override by providing a custom `SecurityPolicyResolver` binding.

### AnnotationSecurityPolicyResolver

Default implementation of `SecurityPolicyResolver`, extracted from `SecurityPolicyBuilder`. Reads `@RolesAllowed`, `@PermitAll`, `@DenyAll`, and `@Authorized` annotations from the `ResourceMethodMeta` and constructs the corresponding `SecurityPolicy`.

### RouteAuthHandler

SPI for contributing route-level Vert.x auth handlers. Defined in `rest-core`; contributed via `Set<RouteAuthHandler>` multibinding (declared in `AuthModule` in `rest-security`).

```java
public interface RouteAuthHandler {
    String schemeName();
    Handler<RoutingContext> createHandler();
}
```

The framework selects the handler matching the OpenAPI security scheme name for each route. `JwtAuthModule` (in `rest-auth-jwt`) contributes a `RouteAuthHandler` for the configured JWT bearer scheme.

---

### SecuritySchemeHandler

Extension interface for registering an `AuthenticationHandler` for a named security scheme. Defined in `rest-core`; contributed via `Set<SecuritySchemeHandler>` multibinding (declared in `RestCoreModule`). The framework calls `configure(SecuritySchemeRegistry)` during router creation; it applies the registered handler to every operation whose security requirements reference `schemeName()`.

```java
public interface SecuritySchemeHandler {
    String schemeName();
    void configure(SecuritySchemeRegistry registry);
}
```

`SecuritySchemeRegistry` is the scheme-scoped registration surface; it exposes one method:

```java
public interface SecuritySchemeRegistry {
    void authenticationHandler(AuthenticationHandler handler);
}
```

The handler type is `AuthenticationHandler` (not a bare `Handler<RoutingContext>`) so the framework can compose multiple alternative `@SecurityRequirement`s into a Vert.x `ChainAuthHandler.any()` — an OR across schemes matching the OpenAPI `security` array semantics.

**Registration example:**

```java
@Provides @IntoSet
SecuritySchemeHandler jwtScheme(JWTAuth jwtAuth) {
    return new SecuritySchemeHandler() {
        public String schemeName() { return "bearerAuth"; }
        public void configure(SecuritySchemeRegistry registry) {
            registry.authenticationHandler(JWTAuthHandler.create(jwtAuth));
        }
    };
}
```

### RestConfigurationException

Base `ConfigurationException` (from `core.exception`) for all REST startup and wiring errors. Thrown during module initialization when configuration is invalid.

```
RestConfigurationException (extends ConfigurationException)
├── SecurityPolicyViolationException  — security policy inconsistency detected at startup
├── RouteRegistrationException        — route registration failure (in rest-jaxrs)
└── RestContextUnavailableException   — required @Context parameter unbound at request time
```

`SecurityPolicyViolationException` is thrown by `JaxRsRouteRegistrar` when `SecurityPolicyValidator` detects violations. Contains the list of `SecurityPolicyViolation` records.

---

## Context Resolution (`rest.core.context`)

The `rest.core.context` sub-package defines the SPI and runtime for resolving `@Context`-injectable parameters in JAX-RS resource methods. All context parameters — `RoutingContext`, JAX-RS `SecurityContext`, framework `SecurityContext`, and any `ContextValue` type — are resolved uniformly through a priority-sorted chain of `RestContextResolver` implementations.

### RestContextResolution

`@Singleton` coordinator that drives the resolver chain. Constructed once with the full `Set<RestContextResolver>`; the chain is sorted at construction time and reused for every request.

| Method | Signature | Description |
|--------|-----------|-------------|
| `resolve` | `<T> Optional<T> resolve(Class<T> type, RoutingContext ctx)` | Walks the chain; returns the first non-empty result, or `Optional.empty()` |
| `require` | `<T> T require(Class<T> type, RoutingContext ctx, String resourceClass, String methodName)` | Same as `resolve`, but throws `RestContextUnavailableException` when no resolver matches |

The `require(...)` form is used by the REST dispatch layer for declared `@Context` parameters.

### RestContextUnavailableException

Extends `RestConfigurationException`. Thrown at request dispatch time when `RestContextResolution.require(...)` finds no resolver in the chain for the requested type. Carries the `type`, `resourceClass`, and `methodName` that identify where the missing binding was expected.

### RestContextTypes

Internal (public-but-non-SPI) constants class. Provides FQN string constants (`CONTEXT_VALUE_FQN`, `ROUTING_CONTEXT_FQN`, `JAXRS_SECURITY_CONTEXT_FQN`) used by annotation processors and code-generation paths that work with type mirrors rather than live `Class` objects. Also provides the `isInjectable(Class)` predicate used by the dispatch layer to determine whether a parameter should be resolved from the context chain.

`RESERVED_UNSUPPORTED_JAXRS_FQNS` lists JAX-RS types that are syntactically valid `@Context` targets but are not supported in V1 (e.g. `UriInfo`, `HttpHeaders`). The framework rejects these at startup with a clear error rather than silently injecting `null`.

### RestContextModule

Internal Dagger `@Module` (included by `RestCoreModule`). Declares `@Multibinds Set<RestContextResolver>` and contributes the three built-in `@IntoSet` resolvers. Application code does not reference it directly.

**Built-in resolvers:**

| Resolver | Priority | Handles |
|----------|----------|---------|
| `RoutingContextResolver` | 100 | `RoutingContext` (and subtypes) |
| `JaxRsSecurityContextResolver` | 110 | `jakarta.ws.rs.core.SecurityContext` exactly; delegates to `SecurityRuntime.toJaxRs()`; returns empty when security module is absent |
| `ContextHolderResolver` | 120 | Any `ContextValue` subtype; delegates to `ContextValues.current(type)` |

---

## Pagination

The framework provides two pagination response wrappers and corresponding request parameter objects, covering both offset-based and cursor-based pagination strategies.

### When to Use Offset vs Cursor

| | Offset (`OffsetPage`) | Cursor (`CursorPage`) |
|---|---|---|
| **Navigation** | Random access by page number | Sequential (next/previous only) |
| **Total count** | Exposed (`totalItems`, `totalPages`) | Not exposed |
| **Stable ordering** | Can drift when rows are inserted/deleted | Stable (no index drift) |
| **Performance** | `OFFSET N` scans get slower at depth | Constant cost via keyset pagination |
| **Best for** | Admin UIs, searchable lists with page numbers | Feeds, timelines, high-volume APIs |

### OffsetPage

Generic paginated response wrapper for offset-based collection endpoints.

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OffsetPage<T>(
    List<T> items,
    long totalItems,
    int totalPages,
    int page,
    int pageSize,
    boolean first,
    boolean last
) {}
```

| Field | Description |
|-------|-------------|
| `items` | Items on this page (defensively copied) |
| `totalItems` | Total items across all pages |
| `totalPages` | Total number of pages |
| `page` | 0-based current page index |
| `pageSize` | Items per page |
| `first` | `true` when `page == 0` |
| `last` | `true` when `page >= totalPages - 1` |

**Factory method** — derives `totalPages`, `first`, and `last` automatically:

```java
OffsetPage.of(List<T> items, long totalItems, int page, int pageSize)
```

**Example usage in a JAX-RS resource:**

```java
@GET
@Path("/items")
@Operation(operationId = "listItems")
public Future<OffsetPage<Item>> listItems(OffsetPageRequest page) {
    int pg = page.page(0);
    int ps = page.pageSize(20, 100);
    return repository.findAll(pg, ps)
        .map(result -> OffsetPage.of(result.items(), result.totalCount(), pg, ps));
}
```

**JSON shape:**

```json
{
  "items": [...],
  "totalItems": 243,
  "totalPages": 25,
  "page": 2,
  "pageSize": 10,
  "first": false,
  "last": false
}
```

### OffsetPageRequest

`@RequestParams` record that binds offset pagination query parameters from the HTTP request. No `@BeanParam` annotation is needed on the method parameter.

**Query parameters:** `page` (0-based page number), `size` (page size), `sort` (sort expression)

```java
@RequestParams
public record OffsetPageRequest(
    @QueryParam("page") @Nullable Integer page,
    @QueryParam("size") @Nullable Integer pageSize,
    @QueryParam("sort") @Nullable String sort
) {}
```

| Method | Signature | Description |
|--------|-----------|-------------|
| `page(defaultValue)` | `int page(int defaultValue)` | Page number or default; clamped to `>= 0` |
| `pageSize(defaultValue)` | `int pageSize(int defaultValue)` | Page size or default; clamped to `>= 1` |
| `pageSize(defaultValue, maxValue)` | `int pageSize(int defaultValue, int maxValue)` | Page size clamped to `[1, maxValue]` |
| `sortOrders()` | `List<SortOrder> sortOrders()` | Parses `sort=field,asc,field2,desc` into `SortOrder` list |

`SortOrder` is a nested record: `SortOrder(String field, Direction direction)` where `Direction` is `ASC` or `DESC`. **Security:** field names are not validated — always check against an allowlist before using in queries.

**Example URL:** `GET /items?page=2&size=10&sort=name,asc`

### CursorPage

Generic cursor-paginated response wrapper. Cursor tokens are encoded through a `CursorCodec` before being returned to clients.

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CursorPage<T>(
    List<T> items,
    @Nullable String nextCursor,
    @Nullable String previousCursor
) {}
```

| Field | Description |
|-------|-------------|
| `items` | Items on this page (defensively copied) |
| `nextCursor` | Encoded cursor for the next page; `null` when this is the last page |
| `previousCursor` | Encoded cursor for the previous page; `null` when this is the first page |

Helper methods `hasMore()` and `hasPrevious()` are annotated `@JsonIgnore` — clients detect navigation from cursor field presence.

**Factory methods:**

```java
// With codec — preferred
CursorPage.of(List<T> items, String nextRawCursor, String prevRawCursor, CursorCodec codec)

// Without codec (uses PlainCursorCodec)
CursorPage.of(List<T> items, String nextRawCursor, String prevRawCursor)
```

`nextRawCursor` and `prevRawCursor` are raw backend tokens — the factory encodes them before returning to the client.

**JSON shape:**

```json
{
  "items": [...],
  "nextCursor": "eyJpZCI6NDIsInRzIjoiMjAyNC0wMS0xNSJ9"
}
```

(`previousCursor` is omitted when `null`.)

### CursorPageRequest

`@RequestParams` record that binds cursor pagination query parameters from the HTTP request.

**Query parameters:** `cursor` (encoded cursor token), `pageSize` (page size)

```java
@RequestParams
public record CursorPageRequest(
    @QueryParam("cursor") @Nullable String cursor,
    @QueryParam("pageSize") @Nullable Integer pageSize
) {}
```

| Method | Signature | Description |
|--------|-----------|-------------|
| `decodeCursor(codec)` | `Optional<String> decodeCursor(CursorCodec codec)` | Decodes cursor with the given codec; `Optional.empty()` for first page |
| `decodeCursor()` | `Optional<String> decodeCursor()` | Convenience; uses `PlainCursorCodec` |
| `pageSize(defaultValue)` | `int pageSize(int defaultValue)` | Page size or default; clamped to `>= 1` |
| `pageSize(defaultValue, maxValue)` | `int pageSize(int defaultValue, int maxValue)` | Page size clamped to `[1, maxValue]` |

**Example usage:**

```java
@GET
@Path("/items")
@Operation(operationId = "listItems")
public Future<CursorPage<Item>> listItems(CursorPageRequest pageRequest) {
    int size = pageRequest.pageSize(20, 100);
    PageCursor cursor = pageRequest.decodeCursor(cursorCodec)
        .map(raw -> PageCursor.fromToken(raw).withPageSize(size))
        .orElseGet(() -> PageCursor.first(size));
    return repository.findItems(cursor)
        .map(result -> CursorPage.of(
            result.items(), result.nextCursorToken(), result.previousCursorToken(), cursorCodec));
}
```

---

## Cursor Codec SPI

### CursorCodec

SPI for encoding and decoding cursor tokens. Implementations transform raw backend cursor strings to opaque, URL-safe tokens for clients.

```java
public interface CursorCodec {
    String encode(String rawCursor);
    String decode(String opaqueToken) throws InvalidCursorException;
}
```

**URL-safety contract:** `encode()` SHOULD return only Base64URL characters (`A-Za-z0-9_-`, no padding `=`) so tokens are safe in query parameters and `Link` headers without percent-encoding. `PlainCursorCodec` is an intentional exception.

**No default Dagger binding is provided.** Applications that want DI-managed codec injection add their own `@Provides CursorCodec`. Applications that don't need DI use the convenience no-arg overloads on `CursorPageRequest` and `CursorPage`.

### PlainCursorCodec

Pass-through implementation — `encode()` and `decode()` return the token unchanged. Suitable for development or internal services where token integrity is enforced at the network layer.

```java
// Use the singleton, not new PlainCursorCodec()
PlainCursorCodec.INSTANCE
```

**Warning:** provides no tamper protection. A client can craft arbitrary cursor tokens.

### InvalidCursorException

Thrown by `CursorCodec.decode()` when a token is malformed, tampered with, or expired. Extends `ValidationException` (from `core.exception`), which maps to HTTP 400 via `DefaultExceptionMapper`.

Override the status by registering a custom `ExceptionMapper<InvalidCursorException>` (e.g., map to 404 if a missing cursor is semantically "not found").

### Implementing a Custom Codec

```java
public class HmacCursorCodec implements CursorCodec {
    private final byte[] secret;

    public HmacCursorCodec(byte[] secret) {
        this.secret = secret;
    }

    @Override
    public String encode(String rawCursor) {
        String sig = hmacBase64Url(rawCursor, secret);
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(rawCursor.getBytes(StandardCharsets.UTF_8)) + "." + sig;
    }

    @Override
    public String decode(String opaqueToken) throws InvalidCursorException {
        int dot = opaqueToken.lastIndexOf('.');
        if (dot < 0) throw new InvalidCursorException("Invalid cursor format");
        String encoded = opaqueToken.substring(0, dot);
        String sig = opaqueToken.substring(dot + 1);
        String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        if (!hmacBase64Url(raw, secret).equals(sig)) {
            throw new InvalidCursorException("Cursor signature mismatch");
        }
        return raw;
    }
}
```

Register with Dagger:

```java
@Provides @Singleton
CursorCodec cursorCodec(@VertxConfig JsonObject config) {
    byte[] secret = config.getString("cursor.secret").getBytes(StandardCharsets.UTF_8);
    return new HmacCursorCodec(secret);
}
```

---

## @RequestParams Annotation

`@RequestParams` marks a class or record as a composite request parameter object. When a method parameter's type carries this annotation, the framework automatically populates it from the request — no `@BeanParam` on the method parameter is required.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequestParams {}
```

This is the **class-level** equivalent of JAX-RS `@BeanParam` (which targets method parameters). Fields may carry `@QueryParam`, `@PathParam`, `@HeaderParam`, `@CookieParam`, and `@FormParam`.

**Creating a custom `@RequestParams` record:**

```java
@RequestParams
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemFilter(
        @QueryParam("status") @Nullable String status,
        @QueryParam("tenantId") @Nullable String tenantId,
        @HeaderParam("X-Request-Source") @Nullable String requestSource) {

    /** Returns true when a status filter is applied. */
    public boolean hasStatus() {
        return status != null && !status.isBlank();
    }
}

// Resource method — no @BeanParam needed:
@GET
@Path("/items")
@Operation(operationId = "listItems")
public Future<List<Item>> listItems(ItemFilter filter) {
    // filter.status(), filter.tenantId(), filter.requestSource() are populated
    return itemService.findAll(filter);
}
```

`@DefaultValue` on individual fields is supported (see `dev.vertique:vertique-rest-jaxrs` for the full detection order).

---

## @FilePart Annotation

`@FilePart` declares multipart file constraints on a resource parameter:

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FilePart {
    String[] allowedTypes() default {};
    long maxSizeBytes() default -1;
}
```

The supported shapes are named `@FormParam FileUpload`, named
`@FormParam List<FileUpload>`, and unannotated aggregate `List<FileUpload>`. `EntityPart` is
intentionally excluded because it may represent a text field that the physical-upload gate cannot
observe. Invalid placement, an invalid allowed-type grammar, a size other than `-1` or positive, and
overlapping constrained declarations fail route startup.

Allowed media types are exact `type/subtype` tokens with an optional whole-subtype wildcard such as
`image/*`; they are lowercase-canonicalized in `FilePartDescriptor`. Size enforcement is post-spool:
`HttpConfig.maxBodySize` is the ingress limit, while `maxSizeBytes` is checked after Vert.x writes the
part under `HttpConfig.uploadsDirectory`. See `dev.vertique:vertique-rest-jaxrs`
for runtime matching, errors, and temporary-file lifetime.

---

## Server-Sent Events (SSE)

The `rest.core.sse` sub-package provides the public API for Server-Sent Events endpoints. The runtime implementation lives in `rest-jaxrs`.

### SseEvent

Immutable SSE event with a fluent builder. All fields are optional (null fields are omitted from the wire format).

```java
public final class SseEvent {
    @Nullable String id();       // event ID (sets Last-Event-ID on client)
    @Nullable String event();    // event type name
    @Nullable String data();     // event data payload
    @Nullable String comment();  // SSE comment (lines starting with ":")
    @Nullable Long   retryMs();  // client reconnect interval hint (ms)

    public static Builder builder() { ... }

    public static class Builder {
        public Builder id(String id) { ... }
        public Builder event(String event) { ... }
        public Builder data(String data) { ... }
        public Builder comment(String comment) { ... }
        public Builder retryMs(long retryMs) { ... }
        public SseEvent build() { ... }
    }
}
```

**Convenience factories:**

```java
// Named event
SseEvent.builder().event("job.completed").data("{\"jobId\":\"abc\"}").build()

// Data-only (unnamed event)
SseEvent.builder().data("ping").build()

// Keepalive comment
SseEvent.builder().comment("keepalive").build()
```

### SseChannel

Per-request bridge between async event producers and the SSE response stream. Obtained from `SseChannelFactory`; the resource method returns `channel.stream()` to the framework.

```java
public interface SseChannel {
    /** Send an event to the client. Fails the stream on buffer overflow (policy FAIL). */
    Future<Void> send(SseEvent event);

    /** Complete the stream normally — client will reconnect unless connection is closed. */
    void complete();

    /** Fail the stream with the given cause. */
    void fail(Throwable cause);

    /** Returns the ReadStream<SseEvent> to return from the resource method. */
    ReadStream<SseEvent> stream();
}
```

`send()` is thread-safe and may be called from any thread (Vert.x or virtual). The channel buffers events up to `defaultBufferSize` (configurable); behaviour on overflow is controlled by `BufferOverflowPolicy`.

### SseChannelFactory

Injectable factory for creating `SseChannel` instances. Injected into resource classes via Dagger.

```java
public interface SseChannelFactory {
    /** Create a channel with default config from JaxRsConfig.sse. */
    SseChannel create();

    /** Create a channel with per-request config overrides. */
    SseChannel create(SseChannelOptions options);
}
```

### SseChannelOptions

Per-channel buffer configuration overrides. All fields are optional — absent fields fall back to the global `SseConfig` defaults.

```java
public record SseChannelOptions(
    @Nullable Integer bufferSize,
    @Nullable BufferOverflowPolicy overflowPolicy
) {
    public static SseChannelOptions withBufferSize(int size) { ... }
    public static SseChannelOptions withPolicy(BufferOverflowPolicy policy) { ... }
}
```

### BufferOverflowPolicy

Controls the channel's behaviour when the internal event buffer is full:

```java
public enum BufferOverflowPolicy {
    /** Fail the send() future with an exception. The stream is NOT terminated. */
    FAIL,
    /** Silently drop the oldest buffered event to make room for the new one. */
    DROP_OLDEST
}
```

### SseConfig

Configuration for SSE channels. Read from the `sse` sub-key of `JaxRsConfig` (i.e., `jaxrs.sse.*` in the application config file).

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `keepAliveEnabled` | `boolean` | `true` | Emit periodic `:keepalive` comments |
| `keepAliveIntervalMs` | `long` | `15000` | Interval between keepalive comments (ms) |
| `defaultBufferSize` | `int` | `256` | Default per-channel event buffer capacity |
| `defaultOverflowPolicy` | `BufferOverflowPolicy` | `FAIL` | Default overflow behaviour |

**Example resource:**

```java
@Path("/jobs")
public class JobResource {

    private final JobService jobService;
    @Inject SseChannelFactory sseChannels;

    @Inject
    public JobResource(JobService jobService) {
        this.jobService = jobService;
    }

    @GET
    @Path("/{jobId}/events")
    @Produces("text/event-stream")
    @Operation(operationId = "streamJobEvents")
    public ReadStream<SseEvent> streamJobEvents(
            @PathParam("jobId") String jobId,
            @HeaderParam("Last-Event-ID") @Nullable String lastEventId) {

        SseChannel channel = sseChannels.create();

        jobService.subscribe(jobId, event -> {
            channel.send(SseEvent.builder()
                .id(event.sequenceId())
                .event(event.type())
                .data(Json.encode(event.payload()))
                .build());
            if (event.isFinal()) {
                channel.complete();
            }
        });

        return channel.stream();
    }
}
```

The framework detects `ReadStream<SseEvent>` return types at startup and registers the `SseBodyEncoder` automatically. No additional configuration is required.

---

## Param Conversion (`rest.core.convert`)

Framework-native, symmetric parameter-conversion stack shared by the JAX-RS inbound dispatch path (`rest-jaxrs`) and the REST-client outbound serialization path (`rest-client`). One mechanism converts `@PathParam`/`@QueryParam`/`@HeaderParam`/`@CookieParam`/`@FormParam` values both directions — `String` parsed into a typed value on the way in, and a typed value serialized back to `String` on the way out.

### ParamConverter\<T\>

Per-type SPI. Implementations must be stateless and thread-safe — a single instance is shared across all requests for its target type.

```java
public interface ParamConverter<T> {
    T fromString(String value);
    String toString(T value);
}
```

On a parse failure, an implementation throws any raw `RuntimeException` (e.g. `NumberFormatException`, `DateTimeParseException`, `IllegalArgumentException`). Implementations must **not** construct a `ParamConversionException` themselves — `ParamConversionResolver` is the sole place that wraps a raw failure with parameter context.

### ParamConverterBinding\<T\>

Keyed contribution wiring a `ParamConverter` to the exact target type it handles, contributed via Dagger `@IntoSet`.

```java
public record ParamConverterBinding<T>(Class<T> targetType, ParamConverter<T> converter) {}
```

An application binding for a given type overrides the framework built-in for that same type.

### ParamConverterRegistry

Native, type-keyed, **context-free** lookup table. Built from the framework built-ins overlaid with the application's `ParamConverterBinding` contributions.

```java
public final class ParamConverterRegistry {
    public static ParamConverterRegistry of(Set<ParamConverterBinding<?>> appBindings) { ... }
    public Optional<ParamConverter<?>> find(Class<?> targetType) { ... }
}
```

**Resolution order for `find(Class<?>)`:**

1. Exact-class lookup — application bindings override built-ins for the same target type.
2. If `targetType.isEnum()` and step 1 missed, synthesize an `EnumParamConverter` on demand (via `computeIfAbsent`) and promote it into the map.
3. Otherwise `Optional.empty()`.

Two application bindings for the same target type throw `IllegalStateException` at registry construction (`ParamConverterRegistry.of(...)`).

### ParamConversionResolver

The full conversion chain plus error policy, shared by `ParameterExtractor` (inbound) and `RestClientRequestFactory` / `DefaultRestClientDispatcher` (outbound).

```java
public final class ParamConversionResolver {
    public static ParamConversionResolver of(ParamConverterRegistry registry, Set<ParamConverterProvider> providers) { ... }
    public static ParamConversionResolver builtins() { ... }

    public Object fromString(String value, ConversionContext ctx) { ... }
    public String toString(Object value, ConversionContext ctx) { ... }
    public boolean canResolve(ConversionContext ctx) { ... }
}
```

**Resolution order:** native registry first; only when the JAX-RS `Set<ParamConverterProvider>` is non-empty are the providers consulted (in `@Priority`-ascending order — a provider with no `@Priority` sorts last via `Integer.MAX_VALUE`; the first provider returning a non-null converter wins); otherwise `ParamConverterNotFoundException`. When the provider set is empty, `ConversionContext.annotationsLazy()` is never invoked.

`ParamConversionResolver` is the **sole context-attacher**: every converter — built-in, enum-synthesized, or JAX-RS-provider-backed — throws a raw `RuntimeException` on parse failure, and the resolver wraps it into a `ParamConversionException` carrying the real parameter name, source, and target type. The raw value is never included.

`canResolve(ConversionContext)` walks the full chain (not just the registry) and is used for fail-fast startup/build validation, so a provider-backed type is not wrongly rejected.

`ParamConversionResolver.builtins()` is a convenience factory backed solely by the built-in converters — no app `ParamConverterBinding`s, no `ParamConverterProvider`s. Used by the standalone `RestClientBuilder.create(vertx)` path as the default resolver.

### ConversionContext

Per-parameter context threaded through the conversion chain.

```java
public record ConversionContext(
    String paramName,
    ParamSource source,
    Class<?> rawType,
    @Nullable Type genericType,
    @Nullable Class<?> componentType,
    Supplier<Annotation[]> annotationsLazy
) {}
```

`annotationsLazy` is invoked only when at least one `ParamConverterProvider` is registered — the native registry path never consults it. Callers on a hot path should cache the resulting `ConversionContext` rather than rebuild it per call.

### ParamSource

```java
public enum ParamSource { PATH, QUERY, HEADER, COOKIE, FORM }
```

A deliberately narrow **conversion-source** enum covering only the string-ish transport kinds conversion applies to. It is not a replacement for the richer runtime parameter-source enums (`ResourceMethodMeta.ParamMeta.ParamSource` in `rest-jaxrs`, `ClientParamMeta`'s equivalent in `rest-client`), which also cover `BODY`, `BEAN_PARAM`, `CONTEXT`, file uploads, etc. — those non-convertible sources never produce a `ConversionContext`.

### Built-in converters

`BuiltinParamConverters` (package-private) registers `String`, `boolean`/`Boolean`, `byte`/`Byte`, `short`/`Short`, `int`/`Integer`, `long`/`Long`, `float`/`Float`, `double`/`Double`, `char`/`Character`, `BigInteger`, `BigDecimal`, `UUID`, `URI`, and the `java.time` family: `Instant`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `OffsetTime`, `ZonedDateTime`, `Duration`, `Period`, `Year`, `YearMonth`, `MonthDay`, `ZoneId`, `ZoneOffset`. Both the primitive and boxed `Class` keys are registered so an exact-class lookup succeeds for either. Each parses via the type's canonical `parse`/`valueOf`/constructor and serializes via `Object#toString()` (the ISO/canonical form for every built-in type). Enum conversion is not a map entry — it is handled generically by `ParamConverterRegistry`'s `Class.isEnum()` synthesis rule.

### EnumParamConverter\<E\>

Generic, case-sensitive converter for an arbitrary enum type, synthesized on demand by `ParamConverterRegistry` for any `Class.isEnum()` target with no exact-class binding.

```java
final class EnumParamConverter<E extends Enum<E>> implements ParamConverter<E> {
    public E fromString(String value) { return Enum.valueOf(enumType, value); }  // exact constant-name match
    public String toString(E value) { return value.name(); }                    // ignores overridden toString()
}
```

### Exception model

| Exception | Root | HTTP | Thrown when |
|---|---|---|---|
| `ParamConversionException` | `ValidationException` | 400 | A resolved converter (native, enum-synthesized, or JAX-RS-provider) cannot parse/serialize the value |
| `ParamConverterNotFoundException` | `TechnicalException` | 500 | No converter or provider can satisfy the declared target type — a configuration gap that startup/build validation should have rejected |

Both carry `paramName()`, `source()` (`ParamSource`), and `targetType()` for diagnostics; neither captures the raw value. Both are overridable by an application `ExceptionMapper`.

### Invariants & Gotchas

- `ParamConversionResolver` is the **only** place that builds a `ParamConversionException` — converters must throw raw `RuntimeException`s, never construct the wrapper themselves.
- A duplicate `ParamConverterBinding` for the same target type fails fast at `ParamConverterRegistry.of(...)` construction (`IllegalStateException`), not at first use.
- `ConversionContext.annotationsLazy()` is zero-allocation only when the backing `ParameterMetadata` closes over an already-materialized array (codegen-emitted literal); the reflective `ReflectiveParameterMetadata` variant re-clones the array on every invocation — cache the `ConversionContext` per parameter on hot paths rather than rebuild it.
- Use `resolver.canResolve(ctx)` — never `registry.find(...)` alone — for fail-fast validation, so a provider-backed type isn't wrongly rejected at startup/build.
- `ParamSource` is intentionally narrow; do not extend it to cover non-convertible sources (body, bean-param, context, uploads) — those keep using the richer per-module parameter-source enums.

---

## Extension Points

All extension points are contributed via Dagger multibinding (`@IntoSet`) and declared as `@Multibinds` empty sets in `RestCoreModule`.

### RestContextResolver

SPI for resolving `@Context`-injectable parameters during REST dispatch. Implementations answer one question: "can I supply a value of this type for this request?" The first resolver in the OrderedExtension-sorted chain (phase → priority → orderKey) that returns a non-empty `Optional` wins.

```java
public interface RestContextResolver extends OrderedExtension {
    /** Returns a value of {@code type} for this request, or {@code Optional.empty()} if unhandled. */
    <T> Optional<T> resolve(Class<T> type, RoutingContext ctx);
}
```

**Contract (FR-REST-172):** implementations MUST NOT create, mutate, enrich, replace, or propagate context as a side effect of `resolve`. The method is a pure read.

**Registration via Dagger:**

```java
@Provides @IntoSet
static RestContextResolver tenantContextResolver(TenantContextResolver resolver) {
    return resolver;
}
```

Application resolvers at the default priority `0` run before all framework built-ins (`RoutingContextResolver` at 100, `JaxRsSecurityContextResolver` at 110, `ContextHolderResolver` at 120) and may shadow them for the same type.

#### Invariants & Gotchas

- A resolver that returns `Optional.empty()` is skipped — it is not an error. Only `RestContextResolution.require(...)` treats absence as an error.
- To supply a custom `ContextValue` subtype without writing a resolver, simply bind the value into `ContextHolder` before REST dispatch (via `SecurityRuntime.bindCurrent()` or `ContextHolder.bind()`). `ContextHolderResolver` finds it automatically.
- Ordering follows the OrderedExtension contract (phase → priority → orderKey); the default `orderKey()` is the fully-qualified class name, so the chain order is deterministic across JVM restarts.

### ParamConverter / ParamConverterBinding / ParamConverterProvider

Two ways for an application to plug a custom parameter converter into the conversion stack described in [Param Conversion](#param-conversion-restcoreconvert):

**Native converter** — implement `ParamConverter<T>` and contribute a `ParamConverterBinding<T>` keyed to the exact target type:

```java
@Provides @IntoSet
static ParamConverterBinding<?> instantRangeConverter() {
    return new ParamConverterBinding<>(InstantRange.class, new ParamConverter<InstantRange>() {
        @Override public InstantRange fromString(String value) {
            String[] parts = value.split("\\.\\.", 2);
            return new InstantRange(Instant.parse(parts[0]), Instant.parse(parts[1]));
        }
        @Override public String toString(InstantRange value) {
            return value.start() + ".." + value.end();
        }
    });
}
```

**JAX-RS provider** — implement the standard `jakarta.ws.rs.ext.ParamConverterProvider` SPI and contribute it via `@IntoSet`; this path also receives `ConversionContext`'s lazily-resolved annotations:

```java
@Provides @IntoSet
static ParamConverterProvider myProvider(MyParamConverterProvider provider) {
    return provider;
}
```

Both sets are declared as empty `@Multibinds` in `RestCoreModule`. The native registry is always consulted first; JAX-RS providers are consulted only when the contributed set is non-empty (see [Param Conversion](#param-conversion-restcoreconvert) for the full resolution order).

`RestCoreModule` provides the `@Singleton ParamConverterRegistry` and `@Singleton ParamConversionResolver` built from these multibindings. Both `RestModule` (server, in `rest-jaxrs`) and `RestClientModule` (client, in `rest-client`) include `RestCoreModule`, so an application wiring both halves gets one shared conversion stack — a converter registered once applies identically to inbound JAX-RS parameter binding and outbound REST-client request serialization, with no duplicate-binding conflict.

### RestRequestCompletionEmitter

ROOT `Middleware` in `dev.vertique.rest.core.events` that emits exactly one `RestRequestCompletedEvent` per handled request from the response end handler. It publishes the routing-context keys that other modules read or write without depending on internal key names:

| Constant | Key | Written by | Value |
|----------|-----|------------|-------|
| `KEY_OPERATION_ID` | `rest.events.operationId` | `OperationIdCaptureContributor` | the OpenAPI `operationId` of the matched operation |
| `KEY_ROUTE_TEMPLATE` | `rest.events.routeTemplate` | `OperationIdCaptureContributor` | the OpenAPI path template of the matched operation |
| `KEY_WIRE_FAILURE` | `vertique.rest.core.events.wireFailure` | the response pipeline in `rest-jaxrs` | the `Throwable` that failed the wire write **after** the response was handed off |

`KEY_WIRE_FAILURE` marks a *post-handoff* wire failure — the status and headers (and possibly part of the body) already reached the client before the write failed, as with a truncated stream or a client abort. The marker is written at most once per request: **first writer wins**, so the first observed failure is the one preserved.

Its absence means either that the write completed cleanly, or that the failure surfaced only on the **terminal `end()`** — a buffered `end(buffer)`, a null-entity `end()`, or a stream's final `end()` — and settled after the completion event had already been emitted. Vert.x runs the response end handlers inline before `end()` returns, so such a late-`end()` failure cannot be captured by the exactly-once event; the response pipeline always logs it at `WARN`, and event enrichment on that path is best-effort.

### RestRequestCompletedEvent

Immutable completion event for a terminal HTTP request outcome, emitted exactly once per handled
request by `RestRequestCompletionEmitter`.

| Field | Type | Description |
|-------|------|--------------|
| `startTime` / `endTime` | `Instant` | Request registration / completion observation instants |
| `method` / `path` | `String` | HTTP method name and raw request path |
| `routeTemplate` / `operationId` | `String` (nullable) | OpenAPI path template / operationId; `null` when the request did not reach operation dispatch |
| `statusCode` | `int` | HTTP status code actually sent |
| `failureCode` | `String` (nullable) | Low-cardinality pipeline-mapped failure classification (e.g. the exception's simple class name) |
| `safeFailureMessage` | `String` (nullable) | Curated, bounded human-readable message — NEVER raw exception text or a stack trace |
| `wireFailureCode` | `String` (nullable) | Low-cardinality **post-handoff** wire-failure classification (the failure cause's class simple name, or `ConnectionClosed` per the close-normalization predicate documented above); `null` when no wire failure was *observed* (see the late-`end()` carve-out below); orthogonal to `failureCode` — **a 200-status event carrying a non-null `wireFailureCode` is the truncated-response signature** |
| `securityContextSnapshot` / `correlationContext` | snapshot types (nullable) | Immutable point-in-time snapshots, isolated from later rebind/mutation of the live holder-bound context |
| `origin` | `Optional<RequestOrigin>` | Network-envelope origin; never `null` as an `Optional` |
| `safeAttributes` | `Map<String, Object>` | Additional attributes contributed by the emitter or enrichment hooks; normalized to an unmodifiable copy, never `null` |

`wireFailureCode` is populated by `RestRequestCompletionEmitter.emit()` from two inputs — the
`KEY_WIRE_FAILURE` marker (streaming failures, wins when present) and a failed end-handler
`AsyncResult` (client aborts) — normalized per the close-normalization predicate documented above.
`vertique-micrometer-rest`'s `error.type` tag falls back to it when `failureCode` is absent.

**Late-`end()` carve-out.** Neither input covers a write failure that surfaces *only* on the
terminal `end()` — a buffered `end(buffer)`, a null-entity `end()`, or a stream's final `end()`.
Vert.x runs the response end handlers inline before `end()` returns, so such a failure can settle
after this event was emitted and is therefore not captured by `wireFailureCode`. It is always
logged at `WARN` by the response pipeline in `vertique-rest-jaxrs`; only event enrichment is
best-effort on that path.

### RequestCompletionScope

`Set<RequestCompletionScope>` multibinding (`@Multibinds` in `RestCoreModule`) for establishing one or more ambient scopes around the synchronous completion-listener dispatch loop inside `RestRequestCompletionEmitter`. Multiple integrations may contribute simultaneously.

Integrations implement this interface to re-establish a thread- or context-local at completion time — the canonical use case is re-making the request's traced span current so that Micrometer exemplar samplers can attach a `trace_id` to timer samples recorded in `RestRequestCompletedListener` implementations.

```java
public interface RequestCompletionScope {
    /**
     * Opens a scope for the duration of completion-listener dispatch.
     * Must be cheap, non-blocking, and should not throw.
     * Return {@code () -> {}} when nothing to scope or on any internal error.
     */
    AutoCloseable open(RoutingContext rc);
}
```

**Contract:**
- `open(RoutingContext)` is called once before the first listener dispatches, in iteration order over the set. It must be cheap, non-blocking, and should not throw — if an `Exception` is thrown the emitter logs a WARN (class name only), skips that scope's bracket, and continues with the remaining scopes.
- The returned `AutoCloseable`s are closed in **reverse open order** (last-opened closes first) in a `try/finally` after all listeners and capture coordinators have run. `close()` should also not throw — the emitter guards with its own `try/catch` (WARN + swallow) but good implementations do not rely on that guard.
- Both `open` and `close` run on the Vert.x event loop — do not block.
- `Error`s (e.g. `OutOfMemoryError`) from either `open` or `close` are not caught and propagate as fatal, consistent with standard event-loop practice.

When the set is empty (no contributors installed), `RestRequestCompletionEmitter` behavior is identical to the pre-SPI baseline — no bracket overhead.

**Registration (contribute via `@IntoSet`):**

```java
@Provides
@IntoSet
static RequestCompletionScope myCompletionScope(MyCompletionScope scope) {
    return scope;
}
```

The built-in contributor is `ServerSpanCompletionScope` in `vertique-opentelemetry-rest`, contributed by `OpenTelemetryRestModule` via `@IntoSet`.

#### Invariants & Gotchas

- Failure isolation is per-scope: a throwing `open()` is caught by `RestRequestCompletionEmitter.openScopesQuietly()` (logs at WARN, skips that scope's bracket); remaining scopes are still opened. A throwing `close()` is caught by `closeScopesQuietly()` (WARN + swallow). Listeners always run regardless of scope failures.
- Scopes are opened in iteration order and closed in **reverse** open order, so they bracket correctly (LIFO). Only successfully-opened closeables are tracked for close.
- The entire set is opened once per request completion, not once per listener — the brackets wrap the full fan-out loop including capture coordinators.
- `RoutingContext` is passed to `open()` so the implementation can retrieve previously stashed request-scoped values (e.g. a captured span stored under a routing-context key).

### OperationHandlerContributor

Per-operation handler contributor extension point. Allows modules to inject handlers into the Vert.x OpenAPI route handler chain for each operation, at a specific priority level.

```java
public interface OperationHandlerContributor extends OrderedExtension {
    int priority();
    void contribute(OperationRegistrationContext context);
}
```

**Priority ranges:**
- `0-99`: Pre-authentication handlers
- `100-199`: Authorization handlers
- `200-299`: Context bridging (e.g., SecurityContext)
- `300+`: Post-context handlers

```java
public record OperationRegistrationContext(
    String operationId,
    SecurityPolicy securityPolicy,
    Optional<ActionRef> requiredAction,
    RestOperationDescriptor operation,
    RouteRegistration route
) {}
```

`RestOperationDescriptor` is the transport-neutral operation descriptor (identity, route template, security requirements). `RouteRegistration` is the per-operation handler registration surface — contributors call `route().addHandler(...)` to inject handlers. The context no longer exposes `OpenAPIRoute` or `RouterBuilder`.

**Registration pattern:**

```java
@Provides @IntoSet
OperationHandlerContributor myContributor() {
    return new OperationHandlerContributor() {
        @Override public int priority() { return 250; }

        @Override public void contribute(OperationRegistrationContext ctx) {
            ctx.route().addHandler(rc -> {
                // custom per-operation logic
                rc.next();
            });
        }
    };
}
```

### RouterLifecycleHook

Router creation phase hooks, invoked during `JaxRsRouterMount.createRouter()`. The `beforeAuthSetup` and `afterAuthSetup` hooks receive a transport-neutral `RouterSetup` rather than the Vert.x OpenAPI `RouterBuilder`, so hooks remain decoupled from the validation strategy in use.

```java
public interface RouterLifecycleHook extends OrderedExtension {
    default void beforeAuthSetup(RouterSetup setup) {}
    default void afterAuthSetup(RouterSetup setup) {}
    default void afterRouterCreated(Router router) {}
}
```

`RouterSetup` is a transport-neutral facade over the underlying router that exposes configuration operations without coupling the hook to a specific validation or routing backend. Injected via `Set<RouterLifecycleHook>` multibinding, sorted in OrderedExtension order (phase → priority → orderKey).

### OperationInterceptor

Per-request phase interceptors, chained via Future composition:

```java
public interface OperationInterceptor extends OrderedExtension {
    default Future<Void> beforeOperation(OperationContext ctx) {
        return Future.succeededFuture();
    }
    default <T> Future<T> afterOperation(OperationContext ctx, T result) {
        return Future.succeededFuture(result);
    }
    default <T> Future<T> recoverOperation(OperationContext ctx, Throwable cause) {
        return Future.failedFuture(cause);
    }
}
```

`OperationContext` carries `operationId`, `RoutingContext`, and `ResourceMethodMeta`. Contributed via `Set<OperationInterceptor>` Dagger multibinding.

### ErrorInterceptor

Error mapping phase interceptors, sorted in OrderedExtension order (phase → priority → orderKey):

```java
public interface ErrorInterceptor extends OrderedExtension {
    default Throwable beforeMapping(RoutingContext rc, Throwable throwable) { return throwable; }
    default Response afterMapping(RoutingContext rc, Response response) { return response; }
}
```

`afterMapping` takes and returns `jakarta.ws.rs.core.Response`. Contributed via `Set<ErrorInterceptor>` Dagger multibinding.

### RequestInterceptor

Interceptor for HTTP request/response filtering. Four hook points, all with default no-op implementations. Sorted in OrderedExtension order (phase → priority → orderKey). Contributed via `Set<RequestInterceptor>` Dagger multibinding.

```java
public interface RequestInterceptor extends OrderedExtension {

    // Runs at router level before processing. Failed future short-circuits with error response.
    default Future<Void> beforeRequest(RoutingContext rc) { return Future.succeededFuture(); }

    // Runs after ResponseProducer creates the Response. Async transform — each interceptor
    // receives the previous interceptor's output as a Future.
    default Future<Response> transformResponse(RoutingContext rc, Response response) {
        return Future.succeededFuture(response);
    }

    // Sync observer — fires for both success and error responses after transformResponse chain.
    // Read-only, for metrics/audit. Called on same thread as serialization.
    default void afterResponse(RoutingContext rc, Response response) {}

    // Called by ResponseSerializer before writing to wire. Read-only, for observability.
    default void onSerialize(RoutingContext rc, Response response) {}
}
```

| Hook | Runs at | Semantics |
|------|---------|-----------|
| `beforeRequest` | Router level, before OpenAPI validation | Async, chained — failed future short-circuits the request |
| `transformResponse` | After `ResponseProducer.produce()` | Async, chained — each interceptor transforms the Response |
| `afterResponse` | After `transformResponse` chain — every terminal outcome: success, error, and the bare-metal fallback-500 when `transformResponse` fails catastrophically | Sync observer — for logging, metrics, audit; on the fallback-500 path the `response` argument is a synthetic `500` with no entity |
| `onSerialize` | Inside `ResponseSerializer`, before writing to wire | Read-only — for logging, metrics, audit |

**Well-known context keys** (available on `RoutingContext.data()` during request processing):

| Key constant | Type | When set | Purpose |
|---|---|---|---|
| `RequestInterceptor.ORIGINAL_ERROR_KEY` | `Throwable` | Error pipeline entry | Original cause before any `ErrorInterceptor.beforeMapping` transformation; available in `afterResponse` and `OperationInterceptor` callbacks |
| `RequestInterceptor.VERTX_STATUS_CODE_KEY` | `Integer` | Failure handler, for `HttpException` non-validation errors | HTTP status code from a Vert.x `HttpException` whose cause was unwrapped for `ExceptionMapper` lookup; used by `ErrorPipeline` as fallback when no specific mapper matches |

**Note:** `beforeRequest` is installed at router order `Integer.MIN_VALUE + 1` (after BodyHandler at `Integer.MIN_VALUE`). A failed future is routed through the error pipeline rather than propagated as an uncaught exception.

**Example — request validation + response enrichment in a single interceptor:**

```java
public class DigestFilter implements RequestInterceptor {
    @Inject public DigestFilter() {}

    @Override
    public Future<Void> beforeRequest(RoutingContext rc) {
        String digestHeader = rc.request().getHeader("Digest");
        if (digestHeader == null) {
            return Future.succeededFuture();
        }
        String expected = digestHeader.substring("sha-256=".length());
        String actual = sha256Base64(rc.body().asString());
        if (!expected.equals(actual)) {
            return Future.failedFuture(new IllegalArgumentException("Request body digest mismatch"));
        }
        return Future.succeededFuture();
    }

    @Override
    public Future<Response> transformResponse(RoutingContext rc, Response response) {
        Object entity = response.getEntity();
        if (entity == null || entity instanceof Buffer) {
            return Future.succeededFuture(response);
        }
        byte[] bytes = Json.encode(entity).getBytes(StandardCharsets.UTF_8);
        return Future.succeededFuture(Response.fromResponse(response)
                .header("Digest", "sha-256=" + sha256Base64(bytes))
                .entity(Buffer.buffer(bytes))
                .build());
    }
}
```

Dagger registration:

```java
@Provides @IntoSet
RequestInterceptor digestFilter(DigestFilter filter) { return filter; }
```

### ResponseProducer

Builds a `jakarta.ws.rs.core.Response` from a result value. Producers return a Response object without writing to the wire — serialization is handled by `ResponseSerializer`.

```java
@FunctionalInterface
public interface ResponseProducer<T> {
    Response produce(RoutingContext ctx, T result);
}
```

### ResponseSerializer

Serializes a `jakarta.ws.rs.core.Response` body to the HTTP wire and reports **wire completion** to the caller. Injectable/replaceable via Dagger to support custom serialization formats (CBOR, XML, etc.).

```java
public interface ResponseSerializer {
    Future<Void> serialize(RoutingContext ctx, Response response);
}
```

**Invocation context.** Called by the response pipeline for responses requiring serializer-owned body handling, on the request's event-loop context, after the status code and headers have been written to the routing context's response and after `transformResponse` hooks have run. Normal empty-body and bare fallback paths bypass the serializer entirely; the error fail-open path may retry it exactly once after a synchronous pre-initiation failure (FR-JSON-058A). Implementations must not block the calling thread.

**Completion contract (dual-channel):**

| Channel | Meaning | Caller obligation |
|---------|---------|-------------------|
| Synchronous throw | No write or end was initiated (encode-time failure) | May retry against the same response head (fail-open, FR-JSON-058A) |
| Returned future — success | The response has been fully written and ended | None |
| Returned future — failure | The wire write failed after handoff; zero or more bytes may have been written | Never retry; the caller owns terminal cleanup (the response may still need ending) |

The returned future is never `null` and **may complete on any thread** — callers must not assume context affinity; the framework pipeline redispatches handling onto the request context.

The default implementation (`DefaultResponseSerializer` in `rest-jaxrs`) selects a `ResponseBodyEncoder` for the entity and returns, per branch:
- `null` entity → the future of `response.end()` (no body)
- no matching encoder → the future of `response.end(problemJson)` after switching the response to `500` / `application/problem+json`
- `BufferedBody` → the future of `response.end(buffer)`
- `StreamingBody` → the future of `stream.pipe().endOnFailure(false).to(httpResponse)` — the stream is never buffered (FR-RESTSER-013 / NFR-003) and the serializer never ends the response on pipe failure

All `RequestInterceptor.onSerialize()` hooks are invoked before the body is handed to the wire.

### ResponseProducerBinding

Pairs a `Class<T>` with a `ResponseProducer<T>` for Dagger multibinding contribution. Defined in `rest-core`.

```java
public record ResponseProducerBinding<T>(Class<T> type, ResponseProducer<T> producer) {}
```

Contribute custom producers via `@Provides @IntoSet ResponseProducerBinding<?>` — the same pattern as `ExceptionMapper<?>`:

```java
@Provides @IntoSet
ResponseProducerBinding<?> myProducer() {
    return new ResponseProducerBinding<>(
        MySpecialType.class,
        (ctx, result) -> Response.ok(result.serialize())
                .header("X-Custom", "true")
                .type("application/octet-stream")
                .build()
    );
}
```

`RestModule.responsePipeline()` collects all contributed bindings and registers them with `ResponsePipeline` at startup. Custom producers override the JSON fallback for their registered type. The returned `Response` is passed through `transformResponse` interceptors before serialization.

---

## RequestContextLifecycle

`RequestContextLifecycle` is a ROOT-scoped `Middleware` (priority = `Integer.MIN_VALUE`) that owns all
per-request `ContextHolder.Scope` cleanup. It is the single lifecycle owner for every scope
registered during an HTTP request.

**How it works:**

- Registered first in the middleware chain → under Vert.x Web 5.1.2's **reverse** end-handler
  order, its registered end handler fires **last**, after audit emission, log finalization, and
  every other downstream end handler. Holder-bound values (`SecurityContext`, MDC keys) therefore
  remain accessible to all downstream end handlers until the very end of the request.
- Each middleware that binds a per-request resource hands the returned `ContextHolder.Scope` or
  cleanup `Runnable` to the lifecycle via `Handle.onClose(...)`. No middleware registers its own
  `ctx.addEndHandler(...)` for context cleanup.

**`Handle` API:**

| Method | Semantics |
|--------|-----------|
| `onClose(ContextHolder.Scope)` | Register a scope for LIFO close during cleanup |
| `onClose(Runnable)` | Register a cleanup runnable in LIFO order |
| `afterClose(Runnable)` | Register a task to run after all `onClose` callbacks complete, in FIFO order |
| `completeNow()` | Idempotent explicit completion — required by the WebSocket upgrade path |
| `bindMdc(Map<String,String>)` | Convenience: calls `MDCContexts.bindAll(entries)` and registers the returned scope with `onClose` |

Late registration (calling `onClose` or `afterClose` after `completeNow()` or the end handler fires)
throws `IllegalStateException` immediately — leaks are loud, not silent.

Each `onClose` / `afterClose` entry runs in its own `try/catch`; a failing scope logs at WARN and
does not block the rest of the cleanup sequence.

**Why `completeNow()` exists:** On a successful WebSocket upgrade, Vert.x's
`Http1xServerResponse.completeHandshake()` writes the 101 and marks the response complete *without*
firing the response end handler. The WebSocket upgrade path therefore calls `lifecycle.completeNow()`
to drive the lifecycle synchronously. `completeNow()` is idempotent so a defensive error path
that also triggers an end handler does not re-run any scope or task.

**Typical middleware usage:**

```java
RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(ctx);
lifecycle.onClose(securityRuntime.bindCurrent(sc));             // LIFO; restored at end of request
lifecycle.bindMdc(Map.of("userId", userId, "clientId", cid));  // MDC keys restored at end of request
ctx.next();
```

`RequestContextLifecycle` is contributed to the framework's `Set<Middleware>` multibinding by
`RestCoreModule`.

### MdcKeys

Constants class in `dev.vertique.rest.core.middleware` naming all MDC keys emitted by
framework-owned middlewares. Using these constants prevents key-name drift between
`ContextualLoggingMiddleware`, `IdentityResolutionMiddleware`, and log-aggregation pipelines.

| Constant | Key | Emitter |
|----------|-----|---------|
| `MdcKeys.REQUEST_ID` | `requestId` | `CorrelationIngressMiddleware` (+ `X-Request-Id` response header when configured) |
| `MdcKeys.METHOD` | `method` | `ContextualLoggingMiddleware` |
| `MdcKeys.PATH` | `path` | `ContextualLoggingMiddleware` |
| `MdcKeys.USER_ID` | `userId` | `IdentityResolutionMiddleware` (when userId is present) |
| `MdcKeys.CLIENT_ID` | `clientId` | `IdentityResolutionMiddleware` (when clientId is present) |
| `MdcKeys.AUTH_METHOD` | `authMethod` | `IdentityResolutionMiddleware` (non-anonymous requests only) |

---

## Standard Middlewares

Built-in `Middleware` implementations in `dev.vertique.rest.core.middleware`:

| Class | Scope | Priority | Purpose |
|-------|-------|----------|---------|
| `RequestContextLifecycle` | ROOT | `Integer.MIN_VALUE` | Per-request scope owner; end-handler fires last |
| `CorrelationIngressMiddleware` | ROOT | `RequestContextLifecycle.ORDER + 10` | Builds & binds live `CorrelationContext`; emits configured response headers; applies `REJECT` / `REPLACE_WITH_GENERATED` invalid-value policy |
| `ContextualLoggingMiddleware` | ROOT | 0 | MDC setup for `method` + `path` (request-id is owned by `CorrelationIngressMiddleware`) |
| `DefaultHeadersMiddleware` | ROOT | 10 | Default response headers (Cache-Control, security headers) |
| `ContentTypeValidationMiddleware` | API | 20 | 415 enforcement for POST/PUT/PATCH requests |
| `ContentLengthValidationMiddleware` | API | 30 | 413 enforcement for oversized request bodies |

`ContentTypeValidationMiddleware` accepts: `application/*` (all application subtypes), `multipart/form-data`, `text/*`. Acts as a broad safety net; fine-grained per-route `@Consumes` validation is handled by the OpenAPI router. Skips validation when the request has no body.

`ContextualLoggingMiddleware` binds `method` and `path` into MDC via
`RequestContextLifecycle.Handle.bindMdc(...)` — no direct `addEndHandler` call. Request-id
resolution, the `X-Request-Id` response header, and the `requestId` MDC entry have moved to
`CorrelationIngressMiddleware` (see *Correlation Ingress* below); this middleware no longer reads
or writes any of those values.

### Correlation Ingress

`CorrelationIngressMiddleware` (ROOT, priority `RequestContextLifecycle.ORDER + 10`) builds the live
`CorrelationContext` for the request, binds it on the substrate holder via
`ContextHolder.bind(CorrelationContext.class, ...)`, mirrors the safe-by-default MDC keys
(`requestId`, `correlationId`, `causationId`, `traceId`, `spanId`), and emits configured response
headers before the response is committed. Configured via the `correlation.ingress` section of
the application config (deserialised into `CorrelationIngressConfig`); apps can change header
names, echo flags, causation parsing, and the invalid-value policy (`REPLACE_WITH_GENERATED`
default; `REJECT` returns 400 BadRequest when an inbound header value fails the
`CorrelationHeaderValidator` checks). Protocol headers (e.g. `X-FAPI-Interaction-ID`) plug in via
the `ProtocolCorrelationSpec` (declarative default) / `ProtocolCorrelationContributor` (escape
hatch) multibinds in `CorrelationIngressModule`.

After binding the context and taking the MDC snapshot, the middleware consults an optional
`TraceReferenceResolver` (from `vertique-correlation`): if a resolver is bound and returns a
non-empty `TraceReference`, `CorrelationContextMutator#setTrace` is called to mirror the trace
and span ids into the live context and the `traceId`/`spanId` MDC keys. This step runs *before*
the invalid-header rejection check, so rejection log entries carry trace ids when a resolver is
present. The consult is failure-isolated — a throwing resolver emits one `WARN` log entry and
never affects the request pipeline. When no resolver is on the graph (the default for
applications that do not wire a tracing module), this step is a no-op.

---

## RestCoreModule

`RestCoreModule` is the Dagger `@Module` for `rest-core`. It declares all `@Multibinds` empty sets and default bindings. `RestModule` (in `rest-jaxrs`) includes `RestCoreModule` automatically — applications include `RestModule.class` in their `@Component`.

| Binding | Default |
|---------|---------|
| `@Multibinds Set<RouterCustomizer>` | empty set |
| `@Multibinds Set<RouterLifecycleHook>` | empty set |
| `@Multibinds Set<OperationInterceptor>` | empty set |
| `@Multibinds Set<ErrorInterceptor>` | empty set |
| `@Multibinds Set<RequestInterceptor>` | empty set |
| `@Multibinds Set<Middleware>` | empty set |
| `@Multibinds Set<RouterMount>` | empty set |
| `@Multibinds Set<MountCustomizer>` | empty set |
| `@Multibinds Set<SecuritySchemeHandler>` | empty set |
| `@Multibinds @JaxRsResources Set<Object>` | empty set |
| `@Multibinds Set<RestExceptionMapperCustomizer>` | empty set |
| `@Multibinds Set<ExceptionMapper<?>>` | empty set |
| `@Multibinds Set<RequestCompletionScope>` | empty set (no-op baseline when empty) |
| `@Multibinds Set<OperationHandlerContributor>` | empty set |
| `@Multibinds Set<ResponseProducerBinding<?>>` | empty set |
| `@Multibinds Set<RequestBodyDecoder>` | empty set |
| `@Multibinds Set<ParamConverterBinding<?>>` | empty set |
| `@Multibinds Set<ParamConverterProvider>` | empty set |
| `ParamConverterRegistry` (`@Singleton`) | built-ins + `Set<ParamConverterBinding<?>>` |
| `ParamConversionResolver` (`@Singleton`) | `ParamConverterRegistry` + `Set<ParamConverterProvider>` |
| `JaxRsConfig` | parsed from `"jaxrs"` section; fields: `openapiPath()` (`"openapi.json"`), `basePath()` (`"/*"`), `validationStrategy()` (`"web-validation"`), `validationMode()` (`"aggregate"`) — see `JaxRsConfig`; only `"aggregate"` and `"failFast"` are accepted, any other value fails startup |
| `HttpConfig` | parsed from `"http"` section; includes `maxBodySize()` (`2097152`) and non-blank `uploadsDirectory()` (`"file-uploads"`) used by multipart `BodyHandler`, plus server options such as `port()`, `host()`, and `idleTimeoutSeconds()` |
| `HttpServerOptions` | derived from `HttpConfig.toHttpServerOptions()` |
| `@Multibinds Set<RouteAuthHandler>` | empty set (declared by `AuthModule`) |

---

## Input Processing Types (`rest.core.request`)

These types power the canonicalization and sanitization pipeline for structured request bodies. They are wired by `SanitizationModule` (in `vertique-sanitization`) and invoked by `ParameterExtractor` in `rest-jaxrs`.

### InputObjectProcessor

Interface for applying canonicalization and sanitization to an intermediate map/list body before final DTO materialization. Transform-only — does not invoke Bean Validation.

```java
public interface InputObjectProcessor {
    Object processStructuredBody(
        Object intermediateBody,
        Type targetType,
        EffectiveInputPolicies policies,
        InputLocation location);
}
```

`RestModule` declares `@BindsOptionalOf InputObjectProcessor`. When `SanitizationModule` is included in the Dagger component, this binding resolves to `DefaultInputObjectProcessor`; otherwise no structured body processing occurs.

Custom `RequestBodyDecoder` implementations can invoke `InputObjectProcessor` directly to participate in the same pipeline:

```java
Object intermediate = jsonObject.getMap();
Object processed = processor.processStructuredBody(
        intermediate, MyDto.class, policies, InputLocation.BODY);
MyDto dto = objectMapper.convertValue(processed, MyDto.class);
```

### EffectiveInputPolicies

Immutable record capturing the route-level canonicalizer and sanitizer chains resolved from `@Canonicalize` / `@Sanitize` on the JAX-RS resource class and method.

```java
public record EffectiveInputPolicies(
    List<Class<? extends Canonicalizer>> routeCanonicalizers,
    List<Class<? extends Sanitizer>> routeSanitizers
) {
    /** Empty policies — no route-level processing. */
    public static final EffectiveInputPolicies NONE = ...;

    /** Returns true if both route-level chains are empty. */
    public boolean hasNoRouteChains() { ... }
}
```

Object-level and field-level processors are resolved separately by `InputPolicyMetadataResolver` from the target DTO type. `EffectiveInputPolicies` carries only the route-level chains.

### InputPolicyMetadata

Cached per-type annotation metadata pre-computed from `@Canonicalize`, `@Sanitize`, and skip annotations on the target type and its fields. Used by `DefaultInputObjectProcessor` to avoid per-request reflection.

| Field | Description |
|-------|-------------|
| `objectCanonicalizerChain` | Canonicalizers declared on the DTO type |
| `objectSanitizerChain` | Sanitizers declared on the DTO type |
| `skipCanonicalization` | Whether type has `@SkipCanonicalization` |
| `skipSanitization` | Whether type has `@SkipSanitization` |
| `fields` | Per-field `FieldPolicyMetadata` keyed by JSON property name |

`FieldPolicyMetadata` is a nested record with per-field chains, skip flags, field type, optional nested `InputPolicyMetadata` for recursive traversal, and flags for `isStringType` and `isCollectionOfStrings`.

### InputPolicyMetadataResolver

Resolves and caches `InputPolicyMetadata` per type. Provided as a `@Singleton` by `SanitizationModule`. Supports meta-annotation resolution — annotations composed from `@Canonicalize` / `@Sanitize` are unwrapped automatically.

**Field classification sees through `Optional` and bounded type arguments.** The intermediate wire
value of an `Optional<T>` field is the unwrapped `T`, and a wildcard / type-variable type argument
is erased to its bound before Jackson binds it. The resolver therefore normalizes both away before
deciding a field's shape:

| Declared field type | Nested metadata resolved from |
|---------------------|-------------------------------|
| `Optional<String>` | classified as a `String` field |
| `Optional<NestedDto>` | `NestedDto` |
| `Optional<? extends NestedDto>` | `NestedDto` (wildcard upper bound) |
| `Optional<T>` where `T extends NestedDto` | `NestedDto` (type-variable bound) |
| `Collection<? extends NestedDto>` | `NestedDto` (collection element bound) |
| `Collection<Optional<? extends NestedDto>>` | `NestedDto` |
| `Collection<Optional<String>>` | classified as a collection of strings |
| `Optional<T>` where `T extends A & B` | `A` — javac erases an intersection bound to its leftmost member |
| raw `Optional`, `Optional<?>`, `Optional<? super NestedDto>` | no nested schema — the field falls back to inherited chains only |
| raw `Collection`, `Collection<Object>`, `Collection<?>`, `Collection<? super NestedDto>` | no element schema — inherited chains still reach string elements |

Without this normalization the resolver recurses into `Optional`'s own fields, produces empty
metadata, and returns `null` for the field; `DefaultInputObjectProcessor` then walks the nested map
with `InputPolicyMetadata.EMPTY`, so the nested DTO's own `@Canonicalize` / `@Sanitize` chains never
run. `AnnotationCollector` in `vertique-codegen-sanitization` applies the same rules at APT time, so
the generated and reflective paths classify every row above identically.

---

## Dependencies

- `dev.vertique:core`
- `io.vertx:vertx-core`
- `io.vertx:vertx-web`
- `com.google.dagger:dagger`
- `jakarta.ws.rs:jakarta.ws.rs-api`
- `com.fasterxml.jackson.core:jackson-databind`
- `org.projectlombok:lombok` (provided scope)

---

## Related ADRs

- ADR-0069: REST Context Resolver Chain and Single Context Source — establishes the `RestContextResolver` SPI as the single resolution path for all `@Context`-injectable types; removes the per-type special cases for `RoutingContext`, JAX-RS `SecurityContext`, and framework `SecurityContext` that previously lived in the dispatch layer.
- ADR-0085: OrderedExtension Rolled Out Across Sorted Behavioral SPIs — brings `RouterCustomizer`, `Middleware`, the interceptors/contributors, decoders/encoders, and `RestContextResolver` under the `OrderedExtension` contract (phase → priority → orderKey comparator).
- ADR-0119: Security-Scheme Handler Decoupling from RouterBuilder — establishes `RouteRegistration`, `SecuritySchemeRegistry`, and `RouterSetup` as transport-neutral replacements for `RouterBuilder`/`OpenAPIRoute`, decoupling the extension SPIs from the Vert.x OpenAPI router.
- ADR-0122: BoundRequest as the Neutral Per-Request Binding Model — replaces `ValidatedRequest`/`RequestParameter` with the neutral `BoundRequest` / `RequestValue` surface, decoupling parameter extraction from the validation strategy.
- ADR-0142: Param-Conversion SPI — Registry, Resolver, and ConversionContext — establishes the native `ParamConverterRegistry` / `ParamConversionResolver` / `ConversionContext` stack in `rest.core.convert`, shared symmetrically by the JAX-RS inbound path and the REST-client outbound path.
- ADR-0143: REST Metadata-Record Unification onto `core.codegen` — unifies `ResourceMethodMeta.ParamMeta` and `ClientParamMeta` onto the `core.codegen` `ParameterMetadata` SPI, supplying the literal-backed `annotationsLazy()` bridge that `ConversionContext` consumes for the JAX-RS provider path.
- ADR-0179: File-Part Validation and Content Verifier — governs the `@FilePart` public annotation, post-spool size semantics, and request-owned upload lifetime.

---
