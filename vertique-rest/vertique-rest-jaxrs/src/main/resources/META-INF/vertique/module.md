<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST JAX-RS Module

> **Status:** Implemented
> **Package:** `dev.vertique.rest.jaxrs`
> **Artifact:** `vertique-rest-jaxrs`
> **Depends on:** rest-core, security-core, json

The JAX-RS routing runtime. `vertique-rest-jaxrs` turns annotated resource classes into plain Vert.x
routes, extracts and coerces method arguments, invokes the resource method, and dispatches whatever it
returns — an entity, a `jakarta.ws.rs.core.Response`, a `Future`, or an SSE stream — back onto the
wire. It also owns the error pipeline that every REST failure flows through, the JAX-RS
`ExceptionMapper` registry, and a minimal `RuntimeDelegate` so `jakarta.ws.rs.core.Response` works
without Jersey or RESTEasy on the classpath.

It is not the extension contract: `RouterMount`, `Middleware`, the interceptor SPIs, body decoders and
encoders, `RestContextResolver`, and the parameter-conversion stack are all declared in
`dev.vertique:vertique-rest-core`, and this module is one consumer of them. Request *validation* is
likewise a separate concern — the default `web-validation` gate lives in
`dev.vertique:vertique-rest-validation`. On the default path the build-time `openapi.json` is
documentation only; the opt-in `openapi-contract` strategy in
`dev.vertique:vertique-rest-openapi-validation` is the only mode that loads it at runtime.

---

## When To Use It

Include `dev.vertique.rest.jaxrs.RestModule` in the Dagger `@Component` for any application serving
HTTP endpoints from JAX-RS-annotated classes. `RestModule` includes `RestCoreModule` and
`JsonRuntimeModule` transitively, so it is the only REST module most applications name directly.
Applications using `dev.vertique:vertique-starter-rest` get it through
`RestApplicationModule` instead.

Pair it with `dev.vertique:vertique-rest-validation` (request validation and multipart file
constraints — without it, `jaxrs.validationStrategy` has only the built-in `none` strategy to select),
`dev.vertique:vertique-validation` (Bean Validation on method parameters),
`dev.vertique:vertique-rest-security` (authentication and authorization), and
`dev.vertique:vertique-codegen-jaxrs` (generated resource bindings and reflection-free dispatch).

Depend on `dev.vertique:vertique-rest-core` alone instead when writing a library that must compile
against the REST extension contract without pulling in the routing runtime.

---

## Core Concepts

### Routes are registered once, at startup

`JaxRsRouterMount` is a `RouterMount` that builds one plain Vert.x sub-router. During
`createRouter()` it installs the body handler and upload cleanup, runs the router lifecycle hooks,
configures security-scheme handlers, and then hands the resource set to `JaxRsRouteRegistrar`.

For each public method carrying an HTTP-verb annotation the registrar resolves an `operationId`,
builds a `ResourceMethodMeta`, validates the declaration, and installs the per-operation handler
chain:

```
auth handler(s) → @Consumes 415 gate → validation gate → OperationHandlerContributors → ResourceMethodInvoker
```

`OperationHandlerContributor`s are sorted by the framework `OrderedExtension` comparator (phase →
priority → `orderKey`); the invoker is always appended last. Every declaration problem found during
the scan is **collected**, and the whole set is thrown once as `RouteRegistrationException` after all
resources have been scanned — so a bad resource class reports all of its faults in one build cycle,
not one per restart.

The `operationId` comes from `@Operation(operationId = "…")` or falls back to the Java method name. It
identifies the route internally; it is **not** checked against the generated `openapi.json` at
runtime.

### One response path for success and failure

Every response — a returned entity, a mapped exception, a 406 from content negotiation — is produced
as a `jakarta.ws.rs.core.Response` and then sent through one pipeline: `transformResponse`
interceptors → status and headers onto the wire → `ResponseSerializer` for the body →
`afterResponse` observers → wire-completion observation.

Two consequences matter to application code. `afterResponse` fires at **handoff**, while a streamed
body may still be in flight, because observers need the routing context and tracing span still
active. And status plus headers are already on the wire before a `ResponseSerializer` runs — a
serializer owns the body only.

### Per-request processing order

```
1. Validate      RequestValidationStrategy gate (annotation-synthesized schema by default)
2. Decode        RequestBodyDecoder chain → intermediate Map/List
3. Canonicalize  InputObjectProcessor: route-level → object-level → field-level canonicalizers,
   + Sanitize    then route-level → object-level → field-level sanitizers
4. Validate      BeanValidator.validateParameters()   (only when ValidationModule is present)
5. Invoke        the resource method, with processed and validated arguments
```

Step 3 is skipped transparently when `SanitizationModule` is absent and step 4 when
`ValidationModule` is absent; neither requires an application change.

`@Canonicalize` / `@Sanitize` apply at three scopes — on the resource class or method (route level,
covering all string values in the body plus scalar and collection-element parameters), on a DTO type
(object level), and on a field or record component (field level). `@SkipCanonicalization` /
`@SkipSanitization` opt an individual parameter out. Scalar and collection-element parameters receive
route-level chains only.

### JSON profiles are symmetric

A resource method's request body and its response body use the same effective `ObjectMapper`. The
profile is resolved once at router-build time and reused for both directions — see
[Configuration](#configuration).

---

## Key Classes

### `RestModule`

The Dagger entry point, declared `@Module(includes = {RestCoreModule.class, JsonRuntimeModule.class})`.

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    RestModule.class,        // includes RestCoreModule and JsonRuntimeModule
    RestValidationModule.class,
    AuthModule.class,
    SecurityModule.class,
    AppModule.class,
    ResourceModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

Resources reach it through the `@JaxRsResources Set<Object>` multibinding:

```java
@Module
public abstract class ResourceModule {
    @Provides @IntoSet @JaxRsResources
    static Object helloResource(HelloResource resource) {
        return resource;
    }
}
```

`dev.vertique:vertique-codegen-jaxrs` generates those bindings for `@Path` classes as
`GeneratedJaxRsResourcesModule`; include it in the `@Component` and annotate a resource with
`@NoAutoWire` to keep a hand-written binding canonical instead. Applications inheriting
`vertique-app-parent` get the processor facade automatically; custom-parent applications follow the
BOM plus `vertique-codegen-all` recipe in `docs/packaging.md`, and applications using the
source-retained `@NoAutoWire` opt-out additionally declare `vertique-codegen-core` with `provided`
scope.

### `JaxRsRouterMount`

The `RouterMount` implementation, constructed only through its injected inner `Factory`. Default
priority is `1000`; `meta()` reports `MountMeta("jaxrs:" + mountPath, mountPath, openapiPath,
resourceTypes)`.

```java
public static class Factory {
    public JaxRsRouterMount create(String mountPath, String openapiPath, Set<Object> resources);
    public JaxRsRouterMount create(String mountPath, String openapiPath, Set<Object> resources, int priority);
}
```

`RestModule` contributes a default mount at `jaxrs.basePath` (default `/*`) with
`jaxrs.openapiPath` (default `openapi.json`) via `@ElementsIntoSet`. The set is **empty** — no mount at
all — when `@JaxRsResources` is empty. For a single API, changing the prefix is a config edit:

```json
{ "jaxrs": { "basePath": "/api/*" } }
```

For several APIs on one server, inject the factory and contribute mounts explicitly:

```java
@Provides @ElementsIntoSet
static Set<RouterMount> mounts(
        JaxRsRouterMount.Factory factory,
        @V1Resources Set<Object> v1Resources,
        @V2Resources Set<Object> v2Resources) {
    return Set.of(
        factory.create("/v1/*", "openapi-v1.json", v1Resources, 100),
        factory.create("/v2/*", "openapi-v2.json", v2Resources, 200)
    );
}
```

`openapiPath` is consulted only by the opt-in `openapi-contract` validation strategy.

**Multipart temp files are request-owned.** `BodyHandler` spools uploads under `http.uploadsDirectory`
(default `file-uploads`) and cleanup is registered immediately after it, covering normal completion,
failure, connection close, and stream reset. The file stays readable while the response streams, but
an application that needs it afterwards must move or copy it **before** the response completes.
Retaining a `FileUpload` or a file-backed `EntityPart` does not extend the path's lifetime.

### `ResourceMethodMeta`

The immutable per-method descriptor built at startup and consumed on every request. It is the object
an `OperationHandlerContributor` receives through `OperationRegistrationContext`, so `operationId()`
and `securityPolicy()` in particular are read at extension-point call sites.

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
        SecurityPolicy securityPolicy,                          // dev.vertique.rest.core.security
        MediaTypes mediaTypes,                                  // nested record: (List<String> consumes,
                                                                //   List<String> produces); empty = unconstrained
        @Nullable Class<?>[] validationGroups,                  // from @ValidateWith; null = default group
        List<Annotation> methodAnnotations,
        List<Annotation> classAnnotations,
        List<Class<? extends Canonicalizer>> routeCanonicalizerChain,
        List<Class<? extends Sanitizer>> routeSanitizerChain,
        @Nullable ResourceExecutionPlan executionPlan) { … }    // null = reflective dispatch
```

A 16-component convenience constructor omits `executionPlan`. The compact constructor clones
`validationGroups` and copies the four lists, so every component is immutable regardless of what the
caller passes. `methodAnnotations` and `classAnnotations` are resolved through
`dev.vertique.core.util.AnnotationResolver`, which walks the superclass chain and interfaces — an
annotation on an interface method is visible here.

`ParamMeta` describes one declared parameter:

```java
public record ParamMeta(
        String name,
        ParamSource source,                     // ResourceMethodMeta.ParamSource — see the note below
        Class<?> type,
        @Nullable Class<?> componentType,       // element type for collection/array parameters
        @Nullable Type genericType,             // full generic type for body parameters
        @Nullable String defaultValue,          // from @DefaultValue
        ParameterMetadata parameterMetadata) { … }
```

It **composes** `dev.vertique.core.codegen.ParameterMetadata` rather than holding an eager
`Annotation[]`; `findAnnotation(Class)`, `hasAnnotation(Class)`, and `annotationsLazy()` all delegate
to it. A convenience constructor accepting `(name, source, type, componentType, genericType,
defaultValue, Annotation[])` wraps the array in `ReflectiveParameterMetadata` for call sites that
have a raw array. `annotationsLazy()` is what a JAX-RS `ParamConverterProvider` receives, so a
provider that inspects parameter annotations behaves identically on runtime-scanned and
codegen-generated routes.

> **`ParamSource` is an ambiguous simple name across the REST modules.**
> `dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource` has 11 constants — `PATH`, `QUERY`,
> `HEADER`, `COOKIE`, `BODY`, `CONTEXT`, `PRECONDITIONS`, `FORM`, `FILE_UPLOADS`, `ENTITY_PARTS`,
> `BEAN_PARAM`. It is a different enum from `dev.vertique.rest.core.convert.ParamSource` (5
> conversion-applicable constants: `PATH`, `QUERY`, `HEADER`, `COOKIE`, `FORM`) and from
> `dev.vertique.rest.client.meta.ClientParamMeta.ParamSource` in `dev.vertique:vertique-rest-client`
> (7 constants). Always qualify which one you mean.

### `BoundRequest`

The neutral per-request binding surface. The selected `RequestValidationStrategy` builds one and
stashes it on the routing context; `ResourceMethodInvoker` reads it when extracting arguments. A
custom validation strategy is the reason this type is public.

```java
public interface BoundRequest {
    String KEY_META_DATA_BOUND_REQUEST     = "vertique.rest.jaxrs.boundRequest";
    String KEY_RESOLVED_BODY_MAPPER        = "vertique.rest.jaxrs.resolvedBodyMapper";
    String KEY_ERROR_BODY_MAPPER_DECIDED   = "vertique.rest.jaxrs.errorBodyMapperDecided";

    Map<String, RequestValue> pathParameters();
    Map<String, RequestValue> query();
    Map<String, RequestValue> headers();
    Map<String, RequestValue> cookies();
    RequestValue body();
    HttpServerRequest raw();
}
```

`DefaultBoundRequest` is the shared implementation every bundled strategy constructs; binding is
deliberately independent of validation, so a value binds even when a gate would have rejected it.

### `ExceptionMapperRegistry`

Hierarchy-aware `Throwable → jakarta.ws.rs.core.Response` dispatch, assembled from
`DefaultExceptionMapper` plus the application's `Set<ExceptionMapper<?>>`. Application mappers take
precedence over framework defaults for the same type.

```java
public class ExceptionMapperRegistry {
    public ExceptionMapperRegistry(DefaultExceptionMapper defaults, Set<ExceptionMapper<?>> mappers);
    public Response toResponse(Throwable throwable);
    public <T extends Throwable> void register(Class<T> exceptionType, ExceptionMapper<T> mapper);
    public boolean hasSpecificMapper(Class<? extends Throwable> exceptionClass);
}
```

Lookup walks the superclass chain for the most specific registered mapper and caches the result.
`hasSpecificMapper` reports whether anything other than the `Throwable` catch-all matched; the error
pipeline uses it to decide whether to restore a Vert.x-intended status code.

### `DefaultExceptionMapper`

The framework's `ExceptionMapper<Throwable>`, pre-configured by `RestModule`:

| Exception | Status | Body |
|-----------|--------|------|
| `jakarta.ws.rs.WebApplicationException` | from `getResponse()` | the JAX-RS `Response` as-is — entity passthrough when present, else a problem detail |
| `RestValidationException` (rest-core) | 400 | `ValidationProblemDetail` with an `errors` array |
| `BeanValidationException` (core.validation) | 400 | `ValidationProblemDetail`; violations become `ValidationErrorDetail` with `location` `null` |
| `ValidationException` (core.exception) | 400 | `ProblemDetail` |
| `ParamConversionException` (rest-core `convert`) | 400 | `ProblemDetail` — an inbound value failed conversion to its declared type |
| `ParamConverterNotFoundException` (rest-core `convert`) | 500 | `ProblemDetail` — no converter satisfies a declared type at request time |
| `IllegalArgumentException` | 400 | `ProblemDetail` |
| `UnauthorizedException` (core.exception) | 401 | `ProblemDetail` |
| `ForbiddenException` (core.exception) | 403 | `ProblemDetail` |
| `ConflictException` (core.exception) | 409 | `ProblemDetail` |
| `NotFoundException` (core.exception) | 404 | `ProblemDetail` |
| `UnavailableException` (core.exception) | 503 | `ProblemDetail` |
| `Throwable` (catch-all) | 500 | `ProblemDetail` with the fixed detail `"Internal Server Error"` — the exception message is never echoed |

`UnauthorizedException` and `ForbiddenException` are registered by fully-qualified name to avoid
ambiguity with `jakarta.ws.rs.NotAuthorizedException` and `jakarta.ws.rs.ForbiddenException`.
`VertiqueSecurityException` has no mapper of its own and falls through to the catch-all.

### `RestExceptionMapper`

The REST-layer `Throwable → Throwable` pre-translator that runs before the registry. It extends
`dev.vertique.core.failure.FailureMapper` with covariant overrides so registrations chain:

```java
public class RestExceptionMapper extends FailureMapper {
    @Override
    public <T extends Throwable> RestExceptionMapper on(Class<T> type, FailureTranslator<T> translator);
    @Override
    public <T extends Throwable> RestExceptionMapper on(Class<T> type, ContextAwareFailureTranslator<T> translator);
    // Throwable translate(Throwable) inherited from FailureMapper
}
```

`RestModule` wires one instance from the `Set<RestExceptionMapperCustomizer>` multibinding.

### `ErrorPipeline`

The shared failure path, used both per operation and by the router-level failure handler.
`mapToResponse(RoutingContext, Throwable)` returns a `Future<Response>`; it does not send — the
response pipeline does.

```
RequestInterceptor.onError sync observers, with the original cause
  → store the original Throwable under RequestInterceptor.ORIGINAL_ERROR_KEY
  → ErrorInterceptor.beforeMapping (async chain, Throwable → Throwable)
  → RestExceptionMapper.translate(cause)
  → ExceptionMapperRegistry.toResponse(translated)
  → Vert.x status-code fallback (only when no specific mapper matched)
  → ProblemDetail instance enrichment from the request path
  → ErrorInterceptor.afterMapping (async chain, Response → Response)
```

The original cause stays on the routing context for the whole of error processing, so audit and
diagnostic interceptors can still see the root cause after mapping. A `beforeMapping` or
`afterMapping` handler that fails is logged at WARN and its input passes through unchanged — one bad
interceptor cannot break the error path.

**Vert.x status-code fallback.** The router-level failure handler records the status the Vert.x layer
authoritatively decided for a failure, and the error pipeline reconciles it with the mapper's status.
Two failure shapes are recorded:

- a Vert.x `HttpException` that is not a validation error — the cause is unwrapped so the registry sees
  the original exception type;
- `ctx.fail(<4xx>, cause)` where the cause is *not* an `HttpException` — a decoder rejection from the
  body handler, a `415` from content-type validation, a `401` from a JWT claims validator. The cause is
  left as-is, so an `ExceptionMapper` registered for its own type still matches.

The two shapes record different ranges. An `HttpException` carries **400–599**, because its status was
always chosen deliberately by whatever raised it — an `HttpException(503, …)` from a middleware is
recorded as-is. The `ctx.fail(<status>, cause)` shape records **400–499** only: a 5xx there is dropped,
because `ctx.fail(Throwable)` synthesises a 500 indistinguishable from a deliberate
`ctx.fail(500, cause)`. Neither shape records anything below 400 — that is not an error decision.

When no application-contributed `ExceptionMapper` registered for a type **more specific than
`Throwable`** matched, that recorded status replaces the mapped one — preserving, for example, a 401
or 403 raised by Vert.x auth middleware, or the 400 Vert.x determined for a malformed request body.
An application `ExceptionMapper<Throwable>` is a catch-all, not a specific mapper: it still produces
the response, but the recorded status overrides it exactly as it overrides the framework's own
catch-all. Register the mapper for the cause's own type when it must win. Precedence, highest first:

```
application-contributed ExceptionMapper for a type more specific than Throwable
  > recorded Vert.x failure status (the ranges above)
  > framework default mapping (DefaultExceptionMapper)
  > Throwable catch-all (500)
```

**One exception: a recorded 401 never replaces a mapped 403.** Whatever produced the `403` — the
framework's own `ForbiddenException` mapping, or an application `ExceptionMapper<Throwable>` catch-all
that answered `403` for its own denial type — it outranks a recorded `401` and the response is returned
untouched, body included. A `403` is an authorization decision; answering `401` instead tells the client
to authenticate and retry, which no fresh credential can satisfy. It invites a token-refresh loop that
cannot succeed and hides the denial from access logs and SIEM rules that count 403s. The guard keys on
the mapped **status**, not on the exception type or on which mapper produced it, so an application
mapping its own `TenantMismatchException` to `403` is protected exactly as the framework's mapping is.
This is the only guarded pair: every other recorded status supersedes the mapped one per the ordering
above.

**What the override does to the body.** When the Vert.x status *replaces* the mapped one, the
`ProblemDetail` is rebuilt from the new status: `title` is recomputed, `type` is reset to
`about:blank`, `detail` is dropped, and so are typed subclass fields such as
`ValidationProblemDetail.errors[]` and any RFC 9457 extension members — only `instance` carries over. Everything the superseded body held was written for a status that no
longer applies, and on the `ctx.fail(4xx, cause)` path the detail is an arbitrary application
exception's message that must not reach the client. A mapped response carrying a **non-`ProblemDetail`
entity** is left exactly as the mapper authored it, body and `Content-Type` included, so only the
status is reconciled. Register your own `ExceptionMapper` for the cause's type when a specific detail
is required — it outranks the Vert.x status entirely. When the recorded status *agrees* with the mapped
one nothing changes, so a 415 whose detail names the offending content type keeps it.

### `DefaultResponseSerializer`

The `ResponseSerializer` implementation `RestModule` binds. It encodes the entity of a `Response` and
writes it to the wire, returning the wire-completion future the SPI's dual-channel contract requires.
Status and headers are already written when it runs; the serializer owns the body only.

It selects the first `ResponseBodyEncoder` in `OrderedExtension` order whose
`canEncode(entityType, effectiveContentType)` matches, where the effective Content-Type is read from
the already-written headers. When nothing matches it logs a warning and switches the response to `500`
/ `application/problem+json`. A `StreamingBody` is piped with `endOnFailure(false)` and is never
buffered, so a failed pipe leaves termination to the caller observing the returned future.

Override it with a custom `@Provides ResponseSerializer` to emit CBOR, XML, or any other format; a
replacement must honor the same dual-channel contract, documented under `ResponseSerializer` in
`dev.vertique:vertique-rest-core`.

### `RouteRegistrationException`

Thrown at startup when route registration found violations. Extends `RestConfigurationException`
(rest-core) and exposes `violations()` — a `List<RouteRegistrationViolation>` of
`(operationId, ViolationType, message)`. Security-policy inconsistencies use the separate
`SecurityPolicyViolationException` instead, and are thrown immediately rather than collected.

---

## Extension Points

Most REST extension points — `RouterMount`, `MountCustomizer`, `Middleware`, `RouterLifecycleHook`,
`OperationHandlerContributor`, the three interceptor SPIs, `RequestBodyDecoder`,
`ResponseBodyEncoder`, `ResponseProducer`, `ResponseSerializer`, `RestContextResolver`, and the
parameter converters — are declared in `dev.vertique:vertique-rest-core` and documented there. The
ones this module owns are below. `RequestValidationStrategy`, `OperationSchemaSource`, and
`FileContentVerifier` are declared here but documented with their reference implementations in
`dev.vertique:vertique-rest-validation`.

### `RestExceptionMapperCustomizer`

Contributes `Throwable → Throwable` translations to the REST error pipeline.

```java
@FunctionalInterface
public interface RestExceptionMapperCustomizer extends OrderedExtension {
    void customize(RestExceptionMapper mapper);
}
```

Customizers are applied as an ordered fold: sorted by `OrderedExtension.comparator()` (phase →
priority → `orderKey`) and then called in sequence, so a customizer sorting **later wins** — its
registration overwrites an earlier one for the same exception type, and a `SYSTEM_LAST` customizer
applies last regardless of numeric priority.

```java
@Provides @IntoSet
static RestExceptionMapperCustomizer myTranslator() {
    return mapper -> mapper.on(MyInfrastructureException.class,
            ex -> new WebApplicationException("Upstream error", 502, ex));
}
```

`RestModule` declares the multibinding empty by default.

### `jakarta.ws.rs.ext.ExceptionMapper<T>`

Map one exception type straight to a `Response`. Application mappers outrank the framework defaults
for the same type.

```java
public class ItemNotFoundMapper implements ExceptionMapper<ItemNotFoundException> {
    @Inject public ItemNotFoundMapper() {}

    @Override
    public Response toResponse(ItemNotFoundException ex) {
        return Response.status(404)
                .entity(ProblemDetail.of(404, ex.getMessage()))
                .type("application/problem+json")
                .build();
    }
}

@Provides @IntoSet
ExceptionMapper<?> itemNotFoundMapper(ItemNotFoundMapper mapper) { return mapper; }
```

### Framework decoders and encoders

Both SPIs are declared in `dev.vertique:vertique-rest-core`; the implementations below are what
`RestModule` contributes. Application implementations default to priority `0` and therefore run
**before** every framework default — raise the priority above the listed values to sit behind them.

| `RequestBodyDecoder` | Priority | Content-Type match | Target types |
|---|---|---|---|
| `TextRequestBodyDecoder` | 1000 | `text/*` | `String` |
| `BinaryRequestBodyDecoder` | 1000 | `application/octet-stream` | `Buffer`, `byte[]` |
| `FormUrlencodedRequestBodyDecoder` | 1000 | `application/x-www-form-urlencoded` | any POJO (not `String`, `Buffer`, `byte[]`, `JsonObject`) |
| `JsonRequestBodyDecoder` | 1100 (fallback) | absent, or containing `"json"` | `JsonObject` → raw; `String` → raw; other → `JsonObject.mapTo(targetType)` |

| `ResponseBodyEncoder` | Priority | Handles |
|---|---|---|
| `SseBodyEncoder` | 999 | `ReadStream<SseEvent>` with `text/event-stream` |
| `BufferBodyEncoder` | 1000 | `Buffer` |
| `ByteArrayBodyEncoder` | 1000 | `byte[]` |
| `StringBodyEncoder` | 1000 | `String` |
| `ReadStreamBodyEncoder` | 1000 | `ReadStream<Buffer>` |
| `JsonBodyEncoder` | 1100 (fallback) | everything else |

```java
@Provides @IntoSet
static RequestBodyDecoder csvDecoder() {
    return new CsvRequestBodyDecoder();
}
```

---

## JAX-RS Integration

### Return types

| Return type | HTTP response |
|------------|---------------|
| `Future<T>` | async; serializes `T` with status 200 |
| `Future<Void>` | async; 204 No Content |
| `Future<Response>` | async; status, headers, and entity taken from the `Response` |
| `T` | sync; serializes with status 200 |
| `void` | sync; 204 No Content |
| `Response` | sync; status, headers, and entity taken from the `Response` |
| `ReadStream<SseEvent>` | streaming SSE; `Content-Type: text/event-stream` |

When no `ResponseProducer` is registered for the result type, `Content-Type` is negotiated from the
`Accept` header against `@Produces` (or `["application/json"]`), and a `406 Not Acceptable` problem
detail is returned when nothing matches.

Conditional-request evaluation (`If-None-Match`, `If-Match`, RFC 9110 §13.2.2) runs only when the
produced response is **2xx and carries a validator** — an `ETag` or `Last-Modified` header. A non-2xx
response is not a selected representation, and a validator-less 2xx has not opted into conditional
handling; neither is evaluated. HEAD responses that pass evaluation have their entity stripped.

### Parameter extraction

Parameters are matched in this order:

| Order | Annotation / type | Source | Target types | Notes |
|-------|-------------------|--------|--------------|-------|
| 1 | `@Context`, or an unannotated built-in / `ContextValue` type | `CONTEXT` | `RoutingContext`, `jakarta.ws.rs.core.SecurityContext`, `dev.vertique.security.SecurityContext`, any `ContextValue` subtype | Resolved through the `RestContextResolver` chain; never falls through to body deserialization |
| 1 | type `RequestPreconditions` | `PRECONDITIONS` | `RequestPreconditions` | Injected by type |
| 2 | type annotated `@RequestParams` | Composite | any annotated record/class | No annotation needed on the method parameter |
| 3 | `@BeanParam` | Composite | any class/record | Explicit form of the above |
| 4 | `@PathParam` | `PATH` | any type with a resolvable converter | `@DefaultValue` supported; no collection shapes — a path segment is always single-valued |
| 4 | `@QueryParam` / `@HeaderParam` / `@CookieParam` | `QUERY` / `HEADER` / `COOKIE` | any type with a resolvable converter, plus `List<T>`/`Set<T>`/`SortedSet<T>`/`NavigableSet<T>`/`Collection<T>`/`T[]` of one | `@DefaultValue` supported; see [Collection parameter shapes](#collection-parameter-shapes) |
| 5 | `@FormParam` | `FORM` | `FileUpload`, `EntityPart`, `List<FileUpload>`, `List<EntityPart>`, `String`, primitives, and `List<T>`/`Set<T>`/`SortedSet<T>`/`NavigableSet<T>`/`Collection<T>`/`T[]` of a convertible text element type | `@DefaultValue` supported for text fields and collections; see [Collection parameter shapes](#collection-parameter-shapes) |
| 6 | unannotated `List<FileUpload>` | `FILE_UPLOADS` | `List<FileUpload>` | All uploads on the request |
| 6 | unannotated `List<EntityPart>` | `ENTITY_PARTS` | `List<EntityPart>` | All parts; files wrapped as `VertxFileUploadEntityPart`, text fields as `FormFieldEntityPart` |
| 7 | unannotated, any other type | `BODY` | POJO, `JsonObject`, `String`, `Buffer` | Decoded by the `RequestBodyDecoder` chain |

`@RequestParams` is the preferred form for composite query objects such as `OffsetPageRequest` and
`CursorPageRequest`: no per-method annotation, and the record is reusable across endpoints.

**Type coercion** for `PATH`, `QUERY`, `HEADER`, `COOKIE`, and text `FORM` values goes through the
shared `dev.vertique.rest.core.convert.ParamConversionResolver`, not a fixed scalar table:

- `String` and `JsonObject` keep identity fast paths.
- Everything else — boxed and primitive numerics, `boolean`, `UUID`, `java.time` types, `BigDecimal`,
  enums, and any application-registered `ParamConverterBinding` or JAX-RS `ParamConverterProvider` —
  is converted through the resolver.
- A collection-valued parameter — `List<T>`, `Set<T>`, `SortedSet<T>`, `NavigableSet<T>`, `Collection<T>`,
  or `T[]` on `@QueryParam`, `@HeaderParam`, `@CookieParam`, or `@FormParam` — coerces each submitted
  value individually against the declared component type; a malformed element fails closed with
  `ParamConversionException` rather than leaving the whole collection as raw strings. See
  [Collection parameter shapes](#collection-parameter-shapes) for the absence/default/read-only contract.
- A value that fails conversion raises `ParamConversionException` (400). A declared type with no
  resolvable converter raises `ParamConverterNotFoundException` (500) — a wiring gap that startup
  validation is meant to catch first.

### Collection parameter shapes

`@QueryParam`, `@HeaderParam`, `@CookieParam`, and `@FormParam` additionally accept `List<T>`, `Set<T>`,
`SortedSet<T>`, `NavigableSet<T>`, `Collection<T>`, or `T[]` of a convertible element type; `@PathParam`
does not — a path segment is always single-valued.

- **Absent, no `@DefaultValue`** — an empty collection for the five collection interfaces; `null` for
  `T[]` (an array falls outside the Jakarta REST `@DefaultValue` collection rule).
- **Absent, `@DefaultValue` present** — a single-entry collection, or single-element array, holding the
  converted default.
- **Present** — every submitted value converts individually against the component type; `@DefaultValue`
  is ignored once at least one value is present. A cookie carries one value per name, so a present
  collection-declared `@CookieParam` always yields a single-entry collection. `@FormParam` also applies
  its default when the request body is absent or of an unsupported media type.
- **Read-only.** An injected collection is unmodifiable; mutation throws `UnsupportedOperationException`.
  This includes the native `@FormParam List<FileUpload>` / `List<EntityPart>` targets and the unannotated
  aggregates. Arrays stay mutable — no read-only array wrapper exists.
- **Input policies** run per element at the position a scalar parameter of that source would use: for
  `@FormParam` the raw submitted string is canonicalized/sanitized **before** conversion; for
  `@QueryParam`, `@HeaderParam`, and `@CookieParam` the **converted** element is processed, and only
  while it is still a `String`.
- **Ordering** is whatever the transport reported for repeated values — neither Vert.x nor Jakarta REST
  guarantees one, and the framework makes none.
- **Case sensitivity follows the transport.** `@HeaderParam`/`@CookieParam` names match
  case-insensitively; `@PathParam`/`@QueryParam` match case-sensitively.
- **`SortedSet<T>`/`NavigableSet<T>` require an element type comparable to itself.** They materialize as
  a `TreeSet`, which orders by natural ordering, and a parameter declaration cannot supply a
  `Comparator`. This is the application's responsibility and is **not** checked at startup — a
  non-comparable element type mounts cleanly and fails the first request carrying a value with a
  `ClassCastException` (500). Declare `Set<T>`, `List<T>`, or `Collection<T>` instead when the element
  type is not self-comparable.
- **One name, one declaration.** A name is bound once per source and shared by every parameter declaring
  it, so two `@PathParam`/`@QueryParam`/`@HeaderParam`/`@CookieParam` parameters of one name in a method
  must be interchangeable. When they are not, route registration fails with
  `DUPLICATE_PARAM_NAME_MULTIPLICITY_CONFLICT` (see [Startup failures](#startup-failures)) instead of
  mis-binding one of the two per request. Rejected: different multiplicities (one collection-shaped, one
  scalar); different declared types on a scalar pair (`Integer` plus `UUID`); different element types on a
  collection pair (`List<String>` plus `List<UUID>`); and any other difference in binding-affecting
  annotations, including a different `@DefaultValue`. Accepted, as redundant but correct: two identical
  declarations, and two collection shapes over one element type (`List<String>` plus `Set<String>`), since
  each parameter converts its elements and materializes its own declared collection type. `@FormParam` is
  outside the rule entirely, since its values are read per parameter rather than through a shared
  descriptor.
- **Native multipart targets are `List`-restricted.** `FileUpload`/`EntityPart` materialize natively only
  as a scalar target or `List<T>`; any other collection shape of a native target fails route
  registration with `UNSUPPORTED_MULTIPART_COLLECTION_SHAPE` (see [Startup failures](#startup-failures))
  rather than falling through to string conversion.

Request validation (the `RequestValidationStrategy` gate) inspects values exactly as the transport
delivered them; the canonicalization/sanitization chain runs afterwards, during parameter extraction —
for scalars and collection elements alike. A schema `pattern` proves a property of the raw submission,
not of the value the resource method receives.

### `@FilePart` uploads

The annotation and its grammar are declared in `dev.vertique:vertique-rest-core`; the enforcing gate
and its error taxonomy live in `dev.vertique:vertique-rest-validation`. This module's part is
startup-time: `JaxRsRouteRegistrar` rejects an invalid placement, invalid `allowedTypes` or
`maxSizeBytes`, and overlapping constrained declarations with `INVALID_FILE_PART_DECLARATION`, and
exposes the immutable view a validation strategy consumes.

`JaxRsOperationDescriptor.fileParts()` is that view — an additional projection, not a replacement:
named form file parameters also remain in `parameters()`. The public `FilePartDescriptor` constructor
enforces its own invariants (null-or-non-blank part name, media-type grammar with defensive copying
and lowercase canonicalization, and a `-1`-or-positive size).

### Server-sent events

An SSE endpoint returns `ReadStream<SseEvent>` from a method annotated
`@Produces("text/event-stream")`. Inject `SseChannelFactory` (declared in
`dev.vertique:vertique-rest-core`, bound by `RestModule`) and create a channel inside the method; the
framework handles buffering, wire formatting, keepalive, and connection lifecycle. Defaults come from
`jaxrs.sse`.

Each event is written as lines terminated by `\n`, followed by a blank line:

```
id: <id>          (omitted when null)
event: <event>    (omitted when null)
data: <data>      (omitted when null)
retry: <ms>       (omitted when null)
: <comment>       (omitted when null)
```

Keepalive comments (`:\n\n`) are emitted at `jaxrs.sse.keepAliveIntervalMs` while no data events flow.

Startup validation pairs the two halves of the contract: a method returning `ReadStream<SseEvent>`
must declare `@Produces("text/event-stream")`, and a method declaring that media type must return
`ReadStream<SseEvent>`. Either violation is collected into `RouteRegistrationException`.

`examples/vertique-example-sse` is a complete end-to-end example covering `Last-Event-ID` replay,
terminal event ordering, and connection cleanup.

### JAX-RS runtime support

`dev.vertique.rest.jaxrs.runtime` installs a minimal `RuntimeDelegate`, so
`Response.ok(entity).build()`, `Response.status(404).entity(body).type("application/problem+json").build()`,
`UriBuilder`, and `Link` all work with no JAX-RS implementation on the classpath.

Two adapters are visible to resource code:

| Class | Purpose |
|-------|---------|
| `VertxFileUploadEntityPart` | `EntityPart` over a Vert.x `FileUpload`, reading the spooled temp file. `getContent()` is single-use. |
| `FormFieldEntityPart` | `EntityPart` over a text form field value. `getContent()` is single-use. |

### Example resources

```java
@Path("/hello")
public class HelloResource {
    private final HelloConfig config;

    @Inject
    public HelloResource(HelloConfig config) {
        this.config = config;
    }

    @GET
    @Path("/{name}")
    @Operation(operationId = "getHello")
    @ApiResponse(responseCode = "200", description = "Greeting response")
    public Future<GreetingResponse> getHello(@PathParam("name") String name) {
        return Future.succeededFuture(new GreetingResponse(String.format(config.hello(), name)));
    }

    @POST
    @Operation(operationId = "createItem")
    public Response createItem(CreateItemRequest request) {
        if (request.name() == null) {
            throw new BadRequestException("name is required");
        }
        Item item = itemService.create(request);
        return Response.created(URI.create("/items/" + item.id())).entity(item).build();
    }
}
```

Bean Validation is opt-in: adding `ValidationModule` to the component activates
`beanValidator.checkParameters(...)` between argument extraction and method invocation.
`@ValidateWith` selects the validation groups; without it the default group applies.

```java
@Path("/users")
public class UserResource {

    @POST
    @Operation(operationId = "createUser")
    @ValidateWith(Create.class)
    public Future<User> createUser(@Valid CreateUserRequest request) {
        return userService.create(request);
    }

    @GET
    @Path("/search")
    @Operation(operationId = "searchUsers")
    public Future<List<User>> searchUsers(
            @QueryParam("email") @Email String email,
            @QueryParam("limit") @Min(1) @Max(100) int limit) {
        return userService.search(email, limit);
    }
}
```

Violations become a 400 `ValidationProblemDetail`. Each `ValidationErrorDetail` carries an HTTP
`location` inferred from the parameter's source — `body`, `query`, `path`, `header`, `cookie`, `form`,
or, for a `@BeanParam`, the location of the JAX-RS annotation on the violated field or record
component; anything else is `null`.

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed",
  "errors": [
    { "path": "email", "detail": "must be a well-formed email address", "location": "query", "type": "email" },
    { "path": "limit", "detail": "must be less than or equal to 100", "location": "query", "type": "max", "args": { "value": 100 } }
  ]
}
```

File uploads:

```java
@Path("/uploads")
@Consumes("multipart/form-data")
public class UploadResource {

    @POST
    @Path("/file")
    @Operation(operationId = "uploadFile")
    public Future<UploadResult> uploadFile(
            @FormParam("file")
            @FilePart(allowedTypes = {"image/png"}, maxSizeBytes = 5_000_000)
            FileUpload upload,
            @FormParam("description") String description) {
        // Move or copy the request-owned temp file before this response completes if it must persist.
        return processFile(upload, description);
    }

    @POST
    @Path("/part")
    @Operation(operationId = "uploadPart")
    public Future<UploadResult> uploadPart(@FormParam("file") EntityPart part) throws IOException {
        try (InputStream in = part.getContent()) {
            return processStream(part.getFileName().orElse("unknown"), in);
        }
    }

    @POST
    @Path("/all-parts")
    @Operation(operationId = "uploadAllParts")
    public Future<List<String>> uploadAllParts(List<EntityPart> parts) {
        return Future.succeededFuture(parts.stream().map(EntityPart::getName).toList());
    }
}
```

Text and binary bodies:

```java
@Path("/data")
public class DataResource {

    @POST
    @Path("/text")
    @Consumes("text/plain")
    @Produces("text/plain")
    @Operation(operationId = "uploadText")
    public String uploadText(String body) {
        return "received: " + body;
    }

    @POST
    @Path("/binary")
    @Consumes("application/octet-stream")
    @Operation(operationId = "uploadBinary")
    public Future<Void> uploadBinary(Buffer body) {
        return store(body);
    }
}
```

---

## Configuration

This module reads no configuration section of its own; `http` and `jaxrs` are declared and parsed in
`dev.vertique:vertique-rest-core`. The keys it acts on are `jaxrs.basePath`, `jaxrs.openapiPath`,
`jaxrs.sse.*`, `jaxrs.validationStrategy`, and the JSON profile keys below.

### JSON profile selection

The `ObjectMapper` used for a resource method is resolved once at router-build time, highest first:

1. `@JsonProfile("id")` on the resource **method**;
2. `@JsonProfile("id")` on the resource **class**;
3. `jaxrs.jsonProfile` when non-blank (per-boundary default);
4. `json.jsonProfile` when non-blank (global default);
5. `vertx` — the built-in Vert.x codec, and the unchanged default.

An unknown profile id fails at **startup**, not on the first request.
`JaxRsDefaultProfileValidator` additionally resolves `jaxrs.jsonProfile` during the `VALIDATE` phase,
so a bad boundary default fails even when no resource method would have used it.

```java
@Path("/orders")
@JsonProfile("strict")             // class-level default for every method
public class OrderResource {

    @POST
    @Operation(operationId = "createOrder")
    public Future<Order> createOrder(CreateOrderRequest request) {
        return orderService.create(request);
    }

    @PUT
    @Path("/{id}")
    @JsonProfile("lenient")        // method-level override wins
    @Operation(operationId = "updateOrder")
    public Future<Order> updateOrder(@PathParam("id") String id, UpdateOrderRequest request) {
        return orderService.update(id, request);
    }
}
```

```json
{ "jaxrs": { "jsonProfile": "strict" } }
```

**Requests.** When the effective profile is not `vertx`, the resolved mapper performs the **first
parse** of a JSON-content-type body — raw bytes to `JsonObject`, `JsonArray`, or a scalar — under that
mapper's parser features (`STRICT_DUPLICATE_DETECTION`, `FAIL_ON_TRAILING_TOKENS`, and so on),
uniformly for object, array, and scalar bodies. It then performs POJO/collection materialization from
the parsed value. Non-JSON content types take the unchanged Vert.x path.

A body the profile mapper rejects becomes a value-free HTTP 400 with the detail
`"Request body rejected by JSON profile"`, routed through the standard problem-detail pipeline. The
original rejection is kept as the exception `cause` for server-side diagnosis and is **never**
serialized to the client.

**Responses.** `JsonBodyEncoder` reads the same resolved mapper, so a method's response uses exactly
the profile its request used. Errors raised inside a matched method use the method's mapper; errors
raised before a method match — a pre-routing 404, a schema rejection — use the boundary-plus-global
default resolved inline at router-build time.

**Fail-open for error bodies.** If a profile mapper throws while serializing an error or
`ProblemDetail` body, serialization falls back to the `vertx` mapper with the mapped status code and
the `application/problem+json` media type preserved, and logs a WARN. A profile-mapper failure on a
**success** entity still surfaces as HTTP 500 — the fail-open is scoped to the error pipeline.

---

## Failures, Constraints, and Common Mistakes

### Startup failures

`RouteRegistrationException` carries every `RouteRegistrationViolation` found across all resources:

| `ViolationType` | Meaning |
|---|---|
| `DUPLICATE_OPERATION_ID` | two methods resolve to the same operationId within one mount |
| `MULTIPLE_BODY_PARAMS` | more than one unannotated body parameter |
| `FORM_AND_BODY_CONFLICT` | `@FormParam` or file-upload parameters mixed with a body parameter |
| `INVALID_FILE_PART_DECLARATION` | `@FilePart` on an unsupported type, invalid `allowedTypes`/`maxSizeBytes`, or overlapping constrained declarations |
| `UNSUPPORTED_MULTIPART_COLLECTION_SHAPE` | a `@FormParam` collection parameter's element type is a native multipart target (`FileUpload`/`EntityPart`) declared in a shape other than `List` |
| `DUPLICATE_PARAM_NAME_MULTIPLICITY_CONFLICT` | two parameters bind the same name from the same source but cannot share one declaration — incompatible multiplicities (one collection-shaped, one scalar), different scalar types, different collection element types, or different binding-affecting annotations (including `@DefaultValue`); scoped to `@PathParam`/`@QueryParam`/`@HeaderParam`/`@CookieParam` |
| `SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE` | restrictive security annotations present but `AuthModule` absent |
| `CONTEXT_PARAM_CONFLICT` | a `@Context` parameter also carries a JAX-RS value-binding annotation — the two are mutually exclusive |
| `UNSUPPORTED_JAXRS_CONTEXT_TYPE` | a `@Context` parameter declares a reserved JAX-RS type that is not supported (e.g. `UriInfo`, `HttpHeaders`); fails fast instead of injecting `null` |
| `NON_INJECTABLE_CONTEXT_TYPE` | a `@Context` parameter's type is neither a built-in injectable nor a `ContextValue` subtype |
| `REQUIRES_ACTION_INVALID` | a `@RequiresAction` declaration is malformed |
| `REQUIRES_ACTION_POLICY_CONFLICT` | a `@RequiresAction` declaration conflicts with the operation's resolved security policy |
| `UNRESOLVABLE_PARAM_CONVERTER` | a path/query/header/cookie/form parameter type — or a collection's element type, or a convertible `@BeanParam` field — has no converter resolvable by the `ParamConversionResolver` chain |

`SecurityPolicyViolationException` is thrown immediately when a `SecurityPolicyValidator` is bound and
finds a violation, rather than being collected. `JsonProfileConfigurationException` is thrown at
router-build time for an unknown profile id.

### Request-time failures

Any exception a resource method throws or fails its `Future` with enters the error pipeline and is
mapped by `ExceptionMapperRegistry`. Unmapped exceptions become a 500 problem detail whose detail is
the fixed string `"Internal Server Error"` — never the exception message.

A failure that surfaces **after** the response head is on the wire cannot change the status. When a
streamed or buffered body is truncated, the pipeline closes the response in the way that frames the
truncation honestly: a chunked response is ended normally, while a non-chunked response whose declared
`Content-Length` does not match the bytes written is **reset** (RST_STREAM on HTTP/2, connection close
on HTTP/1.1) rather than ended, so a peer observes an incomplete transfer instead of a well-framed
lie. Clients must therefore be prepared for a reset mid-response and must not treat a 200 status line
as proof of a complete body.

### Common mistakes

- **Expecting `openapi.json` to affect routing.** On the default path it is documentation only.
  Runtime contract validation requires the opt-in `openapi-contract` strategy from
  `dev.vertique:vertique-rest-openapi-validation`.
- **Omitting `RestValidationModule` and expecting validation.** Without a module contributing a
  strategy, only the built-in `none` strategy exists and no request gate is installed.
- **Assuming an application decoder or encoder is a fallback.** Application implementations default to
  priority `0`, ahead of every framework default at 999–1100. Raise the priority above 1100 to sit
  behind them.
- **Using a temp upload file after the response completes.** Cleanup is unconditional; move or copy the
  file first. Holding the `FileUpload` or `EntityPart` does not extend its lifetime.
- **Calling `EntityPart.getContent()` twice.** Both adapters are single-use.
- **Putting `@FilePart` on `EntityPart` or `List<EntityPart>`.** It is rejected at startup: an
  `EntityPart` may be text-backed while the gate observes only file uploads, so accepting it would
  create a text-part bypass.
- **Mixing `@Context` with a value-binding annotation.** `CONTEXT_PARAM_CONFLICT` fails the build; the
  two are mutually exclusive by design.
- **Expecting `@FilePart.maxSizeBytes` to prevent a disk write.** It is checked post-spool and returns
  400. The ingress limits are `http.maxBodySize` (total bytes, returns 413) and `http.maxFormFields`
  (part count).
- **Expecting `afterResponse` to mean "the client has the bytes".** It fires at handoff; a streamed
  body may still be in flight. Observe the wire-completion channel for the delivery outcome.
- **Setting `@JsonProfile` on a resource method and expecting the response to keep the class
  profile.** Request and response profiles are symmetric — the method-level override applies to both.

---

## Module Dagger Bindings

Beyond what `RestCoreModule` and `JsonRuntimeModule` contribute:

| Binding | Value |
|---------|-------|
| `RestExceptionMapper` | built by folding `Set<RestExceptionMapperCustomizer>` in `OrderedExtension` order |
| `DefaultExceptionMapper` | pre-configured with the mapping table above |
| `ExceptionMapperRegistry` | `DefaultExceptionMapper` + `Set<ExceptionMapper<?>>` |
| `List<RequestInterceptor>` | `Set<RequestInterceptor>` sorted once and shared by the serializer and the response pipeline |
| `List<ResponseBodyEncoder>` / `List<RequestBodyDecoder>` | sorted once in `OrderedExtension` order |
| `ResponseSerializer` | `DefaultResponseSerializer`, wired from the sorted interceptor and encoder lists |
| `SseChannelFactory` | `@Singleton`; the factory a resource method injects to create an SSE channel |
| `Set<ResponseBodyEncoder>` | the six framework encoders, `SseBodyEncoder` included |
| `Set<RequestBodyDecoder>` | the four framework decoders |
| `Set<RequestValidationStrategy>` | `@Multibinds` plus the built-in `none` strategy, so the set is never empty |
| `Set<FileContentVerifier>` | `@Multibinds`, empty by default |
| `Set<RestExceptionMapperCustomizer>` | `@Multibinds`, empty by default |
| `Set<RouterMount>` | `@ElementsIntoSet`: the default `JaxRsRouterMount` at `jaxrs.basePath`; empty when `@JaxRsResources` is empty |
| `ComposeValidator` (`JaxRsDefaultProfileValidator`) | `@IntoSet`; fails the `VALIDATE` phase on an unknown `jaxrs.jsonProfile` |
| `OperationSchemaSource`, `BeanValidator`, `InputObjectProcessor`, `ActionRegistry`, `Authorizer` | `@BindsOptionalOf`; satisfied by `rest-validation`, `validation`, `sanitization`, and `rest-security` respectively |

`dev.vertique.rest.jaxrs.runtime.MagicBytesVerifierModule` is a separate opt-in `@Module` that
contributes the built-in magic-byte `FileContentVerifier`.

---

## Dependencies

| Dependency | Why |
|---|---|
| `dev.vertique:vertique-rest-core` | every extension SPI this runtime consumes, the `http`/`jaxrs` config objects, `ProblemDetail`, `BoundRequest`'s `RequestValue`, the parameter-conversion stack, and `RestCoreModule` |
| `dev.vertique:vertique-security-core` | `SecurityContext` and the authorization references the security policy resolves against |
| `dev.vertique:vertique-json` | `JsonMapperProfileRegistry`, `JsonConfig`, and `JsonRuntimeModule` for per-method profile resolution |
| `io.swagger.core.v3:swagger-annotations-jakarta` | `@Operation` / `@ApiResponse` read at scan time for the operationId and, at build time, by the spec generator |
| `org.projectlombok:lombok` | `provided` scope — logging and accessors; not a runtime dependency |

Vert.x, Dagger, the JAX-RS API, and Jackson arrive transitively through `vertique-rest-core`.
