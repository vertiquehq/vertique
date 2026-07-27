<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST JAX-RS Module

> **Status:** Implemented
> **Package:** `dev.vertique.rest.jaxrs`
> **Artifact:** `rest-jaxrs`
> **Depends on:** rest-core, core, db-core

JAX-RS routing runtime on top of Vert.x. Maps annotated resource classes to plain Vert.x routes via annotation-synthesized validation, invokes methods via reflection, and dispatches responses. Also provides the error pipeline, `ExceptionMapperRegistry`, `ResponsePipeline` (package-private internal orchestrator), and the minimal JAX-RS `RuntimeDelegate`.

`openapi.json` is documentation-only on the default path (generated at build time by `swagger-maven-plugin-jakarta`). The opt-in `openapi-contract` validation strategy (`vertique-rest-openapi-validation`) is the only mode that loads the contract at runtime.

---

## Overview

`rest-jaxrs` is the execution engine of the HTTP stack:

1. At build time, `swagger-maven-plugin-jakarta` scans JAX-RS annotations and generates `openapi.json` (documentation only)
2. At runtime, `JaxRsRouterMount` creates a plain Vert.x `Router`, configures security scheme handlers, and runs the JAX-RS route registration pipeline
3. `JaxRsRouteRegistrar` maps each resource method to a `ResourceMethodInvoker` keyed by operationId; request validation uses annotation-synthesized schemas (not the generated spec)
4. `ResourceMethodInvoker` handles each request: extracts parameters, invokes the method, serializes the response, and drives the error pipeline on failure

---

## Key Classes

### JaxRsRouterMount

`RouterMount` implementation that wires JAX-RS annotated resource classes into a plain Vert.x sub-router using annotation-synthesized request validation. Created exclusively via its inner `Factory` class. Default priority is `1000`.

`openapi.json` is documentation-only on the default path. The `openapiPath` config field is used only by the opt-in `openapi-contract` validation strategy (see `vertique-rest-openapi-validation`).

**`createRouter()` pipeline:**

```
Select RequestValidationStrategy by id
  → WARN once for this mount if FileContentVerifiers are bound but inactive
  → RequestValidationStrategy.bindToMount(meta)
  → Install BodyHandler (order MIN_VALUE; maxBodySize + uploadsDirectory)
  → Register request-end upload cleanup (order MIN_VALUE + 1)
  → Install RequestInterceptor.beforeRequest() handler (order MIN_VALUE + 2)
  → RouterLifecycleHook.beforeAuthSetup hooks
  → Configure SecuritySchemeHandlers
  → RouterLifecycleHook.afterAuthSetup hooks
  → JaxRsRouteRegistrar.registerAll(resources, router, requestInterceptors, contributors,
                                    errorPipeline, responsePipeline, securityRuntime,
                                    securityPolicyValidator, authEnabled)
    → SecurityPolicyValidator.validate() per operation (if present)
    → RequestValidationStrategy.gateFor() per operation
    → OperationHandlerContributors.contribute() per operation (sorted by OrderedExtension.comparator())
    → ResourceMethodInvoker added per operation (always last)
  → RouterLifecycleHook.afterRouterCreated hooks
  → Mount API-scoped Middlewares (sorted by OrderedExtension.comparator(); Vert.x route order derived from the sorted index)
  → Install router-level failure handler (ErrorPipeline)
```

The `beforeRequest` handler chains all `RequestInterceptor.beforeRequest()` calls sequentially. A failed future from any interceptor routes to the error pipeline instead of propagating as an uncaught error.

Multipart temporary files are request-owned. `BodyHandler` spools them under the non-blank
`http.uploadsDirectory` (default `file-uploads`), then the next root-order handler registers a
`RoutingContext` end handler that calls `cancelAndCleanupFileUploads()`. Cleanup is always enabled
and covers normal completion, failures, connection close, and stream reset. The temporary file stays
available while the response is streaming, but applications that need it afterwards must move or
copy it before completing the response. Retaining a `FileUpload` or file-backed `EntityPart` does not
extend the path's lifetime.

**`meta()` returns:** `MountMeta("jaxrs:" + mountPath, mountPath, openapiPath, resourceTypes)`

**Factory class — holds all shared framework services:**

```java
public static class Factory {
    @Inject
    public Factory(
        Set<RouterLifecycleHook> routerLifecycleHooks,
        Set<OperationInterceptor> operationInterceptors,
        Set<ErrorInterceptor> errorInterceptors,
        Set<Middleware> middlewares,
        Set<OperationHandlerContributor> operationHandlerContributors,
        Set<SecuritySchemeHandler> securitySchemeHandlers,
        Set<RequestInterceptor> requestInterceptors,
        RestExceptionMapper restExceptionMapper,
        ExceptionMapperRegistry exceptionMapperRegistry,
        RestContextResolution restContextResolution,
        @Nullable SecurityPolicyValidator securityPolicyValidator,
        Optional<AuthEnforcementCapability> authEnforcementCapability,
        List<RequestBodyDecoder> sortedDecoders,
        List<ResponseBodyEncoder> sortedEncoders,
        HttpConfig httpConfig,
        JaxRsConfig jaxRsConfig,
        Optional<BeanValidator> beanValidator,
        Optional<InputObjectProcessor> objectProcessor,
        Set<RestServerRequestEvidenceCapturer> evidenceCapturers,
        Set<FileContentVerifier> fileContentVerifiers,
        Set<RequestValidationStrategy> validationStrategies,
        Optional<OperationSchemaSource> operationSchemaSource) { ... }

    // Create with default priority 1000
    public JaxRsRouterMount create(String mountPath, String openapiPath, Set<Object> resources) { ... }

    // Create with explicit priority
    public JaxRsRouterMount create(String mountPath, String openapiPath, Set<Object> resources, int priority) { ... }
}
```

The `Optional<AuthEnforcementCapability>` parameter replaces the former `@Named("auth.enabled")
Optional<Boolean>`. Its presence — not a Boolean value — is the typed signal that the auth
enforcement runtime (`AuthModule`) is installed. See `AuthEnforcementCapability` in
`dev.vertique:vertique-rest-security`.

**Default mount (single-API apps):** `RestModule` provides a default `JaxRsRouterMount` via `@ElementsIntoSet` using the `@JaxRsResources` multibinding and `JaxRsConfig.basePath()` (default `"/*"`). The set is empty (no mount contributed) when `@JaxRsResources` is empty.

**Custom mount (multi-API or path-prefixed):**

```java
// Override jaxrs.basePath to mount under /api/*  (in config/application.json):
// { "jaxrs": { "basePath": "/api/*" } }

// OR: wire two separate JaxRsRouterMounts for multi-API apps
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

### JaxRsRouteRegistrar

Scans JAX-RS annotated classes and registers handlers on the plain Vert.x `Router` via `RouteRegistration`.

**Scanning process:**
1. Iterates over `Set<Object>` resources
2. Finds classes annotated with `@Path`
3. For each public method with an HTTP method annotation (`@GET`, `@POST`, `@PUT`, `@DELETE`, `@PATCH`):
   - Resolves the `operationId` from `@Operation(operationId=...)` or falls back to the method name
   - Builds `ResourceMethodMeta` with method metadata, parameter info, and `@Consumes`/`@Produces` lists (method-level overrides class-level)
   - **Startup validation** (all violations collected and thrown as `RouteRegistrationException` after all resources are scanned):
     - `MULTIPLE_BODY_PARAMS`: more than one unannotated body parameter
     - `FORM_AND_BODY_CONFLICT`: `@FormParam`/file upload params mixed with a body param
     - `INVALID_FILE_PART_DECLARATION`: `@FilePart` is placed on an unsupported type, has invalid `allowedTypes`/`maxSizeBytes`, or overlaps another constrained file declaration
     - `DUPLICATE_OPERATION_ID`: two methods share the same operationId
     - `SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE`: restrictive annotations present but `AuthModule` absent
     - `CONTEXT_PARAM_CONFLICT`: a `@Context`-annotated parameter also carries a JAX-RS value-binding annotation (`@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, `@FormParam`, or `@BeanParam`) — the two are mutually exclusive
     - `UNSUPPORTED_JAXRS_CONTEXT_TYPE`: a `@Context` parameter's declared type is a reserved JAX-RS type not supported in V1 (e.g. `UriInfo`, `HttpHeaders`); fails fast to prevent silent `null` injection
     - `NON_INJECTABLE_CONTEXT_TYPE`: a `@Context` parameter's declared type is neither a built-in injectable type nor a `ContextValue` subtype — would produce a runtime `NullPointerException` without this check
     - `UNRESOLVABLE_PARAM_CONVERTER`: a declared path/query/header/cookie/form parameter's type (or, for a collection-valued parameter, its element type) has no converter resolvable by the `ParamConversionResolver` chain — checked via `resolver.canResolve(...)` for every convertible parameter the descriptor exposes, **including convertible `@BeanParam` fields** (walked separately since the descriptor exposes only top-level params), so a missing converter fails startup rather than surfacing opaquely on first request
   - **If `SecurityPolicyValidator` is present:** validates the operation; any violations immediately throw `SecurityPolicyViolationException` (fail fast)
   - **Invokes each `OperationHandlerContributor`** (sorted by `OrderedExtension.comparator()` — phase → priority → orderKey) passing `OperationRegistrationContext`
   - Creates a `ResourceMethodInvoker` and registers it as the last handler for that operationId

**operationId resolution:**
- Explicitly set: `@Operation(operationId = "getHello")` on the method
- Fallback: the method name itself (e.g., method `getHello()` maps to operationId `getHello`)
- The operationId drives routing but is NOT validated against the generated `openapi.json` at runtime — that file is documentation-only on the default path

### ResourceMethodInvoker

Vert.x `Handler<RoutingContext>` that bridges a routing context to a JAX-RS resource method invocation.

**Constructor parameters** (primary constructor; shorter overloads default the trailing parameters for older call sites):
- `ResourceMethodMeta meta` — method metadata
- `List<OperationInterceptor> interceptors` — sorted per-request interceptors
- `ErrorPipeline errorPipeline` — error mapping pipeline
- `ResponsePipeline responsePipeline` — type-aware response dispatch
- `RestContextResolution restContextResolution` — coordinator for the `@Context` resolver chain
- `List<RequestBodyDecoder> decoders` — priority-sorted request body decoders
- `@Nullable BeanValidator beanValidator` — optional Bean Validation; `null` skips validation
- `@Nullable InputObjectProcessor objectProcessor` — optional canonicalization/sanitization; `null` skips input processing
- `List<RestServerRequestEvidenceCapturer> evidenceCapturers` — pre-sorted evidence capturers; empty list is the no-op default
- `@Nullable ObjectMapper resolvedBodyMapper` — effective request-body mapper resolved once at router-build time; `null` means the `vertx` default body path
- `ParamConversionResolver paramConversionResolver` — the shared `rest-core` conversion resolver, threaded into the `ParameterExtractor` and the per-request `DefaultBoundRequest`

**Request handling flow:**

1. Obtain the `BoundRequest` from the routing context
2. If `@Produces` is declared, store the media type list in `ctx.data()` under key `CTX_KEY_PRODUCES` (`"dev.vertique.produces"`)
3. Build method arguments from parameter metadata:
   - `@PathParam` → bound path parameter via `BoundRequest`
   - `@QueryParam` → bound query parameter via `BoundRequest`
   - `@HeaderParam` → bound header via `BoundRequest`
   - `@CookieParam` → bound cookie via `BoundRequest`
   - `@Context` parameters and unannotated built-in / `ContextValue` types (`CONTEXT` source) → resolved at request time via `RestContextResolution.require(type, ctx, resourceClass, method)` through the `RestContextResolver` chain. Covers `RoutingContext`, `jakarta.ws.rs.core.SecurityContext`, framework `SecurityContext`, and any `ContextValue` subtype. A `@Context` parameter never falls through to body deserialization.
   - `@FormParam("name") FileUpload` → named file upload from `ctx.fileUploads()`
   - `@FormParam("name") EntityPart` → named file upload wrapped as `VertxFileUploadEntityPart`, or text field as `FormFieldEntityPart`
   - `@FormParam("name") List<FileUpload>` → all file uploads with matching name
   - `@FormParam("name") List<EntityPart>` → all file uploads with matching name, each wrapped as `VertxFileUploadEntityPart`
   - `@FormParam("name") String` (or primitive) → text form attribute, coerced to target type
   - Unannotated `List<FileUpload>` → all file uploads from the request
   - Unannotated `List<EntityPart>` → all parts: file uploads as `VertxFileUploadEntityPart`, text fields as `FormFieldEntityPart`
   - Unannotated other type → treat as request body (see body deserialization below)
4. Invoke the method via reflection
5. Handle the return value via `ResponsePipeline`:
   - `Future<T>` → resolve async, then serialize `T` as JSON (200) or send 204 for `Future<Void>`
   - `Future<Response>` / `Response` → extract status, headers, entity and send
   - `T` → serialize as JSON (200)
   - `void` / `null` → send 204 No Content
6. On error → error pipeline (see `ErrorPipeline`)

**Body deserialization** is delegated to the `RequestBodyDecoder` SPI chain (see `dev.vertique:vertique-rest-core`). The invoker iterates the `OrderedExtension.comparator()`-sorted decoder list and calls the first decoder whose `canDecode(targetType, contentType)` returns `true`. Framework defaults:

| Decoder | Priority | Content-Type match | Target Types |
|---|---|---|---|
| `TextRequestBodyDecoder` | 1000 | `text/*` | `String` |
| `BinaryRequestBodyDecoder` | 1000 | `application/octet-stream` | `Buffer`, `byte[]` |
| `FormUrlencodedRequestBodyDecoder` | 1000 | `application/x-www-form-urlencoded` | any POJO (not `String`, `Buffer`, `byte[]`, `JsonObject`) |
| `JsonRequestBodyDecoder` | 1100 (fallback) | null or contains `"json"` | `JsonObject` → raw object; `String` → raw string; other → `JsonObject.mapTo(targetType)` |

Application decoders default to priority `0` and automatically run before all framework defaults. Contribute custom decoders via `@Provides @IntoSet RequestBodyDecoder` in any Dagger module:

```java
@Provides @IntoSet
static RequestBodyDecoder csvDecoder() {
    return new CsvRequestBodyDecoder();
}
```

**Type coercion** for `@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, and text `@FormParam` is delegated to the shared `ParamConversionResolver` (`rest-core`, package `dev.vertique.rest.core.convert`; see `dev.vertique:vertique-rest-core` and ADR-0142) rather than a fixed-table scalar converter:

- `String` and `JsonObject` keep identity fast-paths inside `ParameterExtractor.coerce()`.
- Every other declared type — built-in scalars (`int`/`long`/`float`/`double`/`boolean` and their boxed forms), `UUID`, `java.time` types, `BigDecimal`, enums, and any app-registered `ParamConverterBinding` or JAX-RS `ParamConverterProvider` — is converted via `paramConversionResolver.fromString(value, conversionContext)`.
- A collection-valued parameter (`List<T>`/`Set<T>`/`T[]`) coerces each element individually against the declared component type; a malformed element fails closed with a `ParamConversionException` rather than silently retaining the raw string for the whole collection.
- A value that fails conversion raises `ParamConversionException` (400); a declared type with no resolvable converter at all raises `ParamConverterNotFoundException` (500) — though `JaxRsRouteRegistrar`'s startup validation (`UNRESOLVABLE_PARAM_CONVERTER`) is intended to catch the latter before any request is served.
- `ConversionContext` instances are cached per `ResourceMethodMeta.ParamMeta` for the lifetime of the route's `ParameterExtractor`, so the conversion-context allocation happens at most once per parameter, not per request.
- A JAX-RS `ParamConverterProvider` that inspects *parameter annotations* to decide conversion works identically on runtime-scanned routes and on codegen-generated routes: both `ExecutionPlanEmitter` and `JaxRsDescriptorEmitter` materialize each parameter's runtime-retained annotations into compile-time literals (see `dev.vertique:vertique-codegen-jaxrs` and ADR-0146), so `ConversionContexts.forParamMeta(...).annotationsLazy()` supplies the provider with the real annotation regardless of which path produced the route.

### ResourceMethodMeta and ParamMeta

`ResourceMethodMeta` is the immutable per-method descriptor `JaxRsRouteRegistrar`/`ResourceScanner` build at startup and that `ParameterExtractor`/`ResourceMethodInvoker` consume on every request. Its `ParamMeta` (one per declared method parameter) no longer carries an eager `Annotation[]` field; instead it **composes** a `dev.vertique.core.codegen.ParameterMetadata` view (see `dev.vertique:vertique-codegen-dagger` / ADR-0143 / ADR-0146):

- `ParamMeta.findAnnotation(Class)` / `hasAnnotation(Class)` / `annotationsLazy()` all delegate to the composed `ParameterMetadata`.
- On the **reflective** scan path (`ResourceScanner`), the composed view is `dev.vertique.core.codegen.ReflectiveParameterMetadata` — the same reflective implementation `rest-client`'s scan path uses — backed by the parameter's merged `Annotation[]`.
- On the **codegen** path, both `ExecutionPlanEmitter` and `JaxRsDescriptorEmitter` back the composed view with a literal-first `ParameterMetadata` implementation (one standalone generated class per parameter, mirroring `vertique-codegen-aop`'s `MetadataEmitter`-generated metadata). Each renderable `@Retention(RUNTIME)` parameter annotation becomes a JAX-RS-owned `<Ann>$JaxRsLiteral` constant with no reflection at request time or class-registration time. The namespace prevents a collision with an AOP-owned literal for the same annotation type. If an annotation has an unsupported member shape, or the same annotation type occurs with differing values across merged parameter sources, the generated implementation instead wires `GeneratedJaxRsReflectiveAnnotations` as a lazy per-parameter fallback, as specified by ADR-0146.
- A convenience `ParamMeta(name, source, type, componentType, genericType, defaultValue, Annotation[])` constructor still exists for call sites that build from a raw annotation array (it wraps the array in `dev.vertique.core.codegen.ReflectiveParameterMetadata` via a small internal `AnnotatedElement` adapter, with index `-1`).

`ParameterExtractor.resolveParamPolicies()` resolves `@Canonicalize`/`@Sanitize`/`@Skip*` input policies via `AnnotationResolver.findMetaAnnotation` over `pm.annotationsLazy().get()`, so meta-annotation-aliased policy annotations are honored identically regardless of which `ParameterMetadata` backing is in play. `ConversionContexts.forParamMeta`/`forComponent` similarly source the JAX-RS-provider-bridge annotation array from `pm.annotationsLazy()`.

### Input Processing Pipeline

When `SanitizationModule` is included in the Dagger component, `InputObjectProcessor` is available and the parameter extraction pipeline applies canonicalization and sanitization before Bean Validation.

**Full per-request processing order:**

```
1. Validate: RequestValidationStrategy (annotation-synthesized JSON Schema by default; pluggable)
2. Decode: RequestBodyDecoder chain → intermediate Map/List
3. Canonicalize + Sanitize: InputObjectProcessor applies chains in scope order:
       route-level → object-level → field-level canonicalizers
       route-level → object-level → field-level sanitizers
4. Validate: BeanValidator.validateParameters() (if ValidationModule present)
5. Invoke: resource method called with processed, validated arguments
```

**Processing scope:**

| Scope | Source annotation | Applies to |
|-------|------------------|-----------:|
| Route-level | `@Canonicalize`/`@Sanitize` on resource class or method | All string values in body + scalar params for this route |
| Object-level | `@Canonicalize`/`@Sanitize` on DTO type | All fields of that type |
| Field-level | `@Canonicalize`/`@Sanitize` on field or record component | That specific field only |

Route-level chains are resolved by `ResourceScanner` at startup and stored in `ResourceMethodMeta` as `routeCanonicalizerChain` and `routeSanitizerChain`. Meta-annotations (composed from `@Canonicalize`/`@Sanitize`) are resolved by `AnnotationResolver`.

**Scalar parameter processing** (`@QueryParam`, `@PathParam`, `@HeaderParam`, `@BeanParam` fields) applies route-level chains only. `@SkipCanonicalization`/`@SkipSanitization` on individual parameters opts them out.

**Structured body processing** delegates to `InputObjectProcessor.processStructuredBody()` on the intermediate map before DTO materialization. Object-level and field-level metadata is resolved from the target type by `InputPolicyMetadataResolver` (cached).

When `InputObjectProcessor` is absent (`SanitizationModule` not included), the pipeline skips steps 3 transparently — no code changes required.

### ErrorPipeline

Encapsulates the error mapping pipeline executed on request failure. Used by both `ResourceMethodInvoker` (per-operation) and the router-level failure handler installed by `JaxRsRouterMount`.

**Pipeline stages:**

```
ErrorInterceptor.beforeMapping (chained, Throwable→Throwable)
  → RestExceptionMapper.translate(cause)  (rest-jaxrs, Throwable→Throwable pre-translation)
  → ExceptionMapperRegistry.mapToResponse() (Throwable→jakarta.ws.rs.core.Response)
  → applyVertxStatusCodeFallback()     (if no specific ExceptionMapper matched)
  → ErrorInterceptor.afterMapping (chained, Response→Response)
  → ResponsePipeline.sendResponse() sends the Response
```

**Vert.x status code fallback:** when the failure handler receives a Vert.x `HttpException` that is not a validation error, it unwraps the cause to pass the original exception type to `ExceptionMapperRegistry`. The Vert.x-intended status code is stored in `RoutingContext.data()` under `RequestInterceptor.VERTX_STATUS_CODE_KEY`. If no specific `ExceptionMapper` matches (only the catch-all fired), `applyVertxStatusCodeFallback()` overrides the response status with the stored code, preserving HTTP semantics (e.g., 401/403 from Vert.x auth middleware).

### RestExceptionMapper

REST-layer `Throwable → Throwable` pre-translator. Extends the shared `core.failure.FailureMapper` and is wired from a `Set<RestExceptionMapperCustomizer>` multibinding. Sits between `ErrorInterceptor.beforeMapping` and `ExceptionMapperRegistry` in the error pipeline.

```java
public class RestExceptionMapper extends FailureMapper {
    public Throwable translate(Throwable throwable) { ... }  // inherited
}
```

### RestExceptionMapperCustomizer

Extension point for contributing `Throwable → Throwable` translations to the REST error pipeline. Implement and contribute via Dagger `@IntoSet`:

`RestExceptionMapperCustomizer extends OrderedExtension`. Customizers are applied as an ordered fold — sorted by `OrderedExtension.comparator()` (phase → priority → orderKey) and then called in sequence: a customizer that sorts later wins (its registration overwrites earlier ones for the same exception type). A `SYSTEM_LAST` customizer applies last regardless of numeric priority.

```java
@FunctionalInterface
public interface RestExceptionMapperCustomizer extends OrderedExtension {
    void customize(RestExceptionMapper mapper);
}

// Example:
@Provides @IntoSet
static RestExceptionMapperCustomizer myTranslator() {
    return mapper -> mapper.on(MyInfrastructureException.class,
        ex -> new WebApplicationException("Upstream error", 502, ex));
}
```

`RestModule` declares the `@Multibinds Set<RestExceptionMapperCustomizer>` empty set.

### ExceptionMapperRegistry

Hierarchy-aware `Throwable → jakarta.ws.rs.core.Response` registry using JAX-RS `ExceptionMapper<T>`. Initialized from a `DefaultExceptionMapper` (framework defaults) and a `Set<ExceptionMapper<?>>` (user contributions). User mappers take precedence over framework defaults for the same exception type.

```java
public class ExceptionMapperRegistry {
    public ExceptionMapperRegistry(DefaultExceptionMapper defaults, Set<ExceptionMapper<?>> mappers) { ... }
    public Response toResponse(Throwable throwable) { ... }
    public <T extends Throwable> void register(Class<T> type, ExceptionMapper<T> mapper) { ... }
    public boolean hasSpecificMapper(Class<? extends Throwable> exceptionClass) { ... }
}
```

- Walks the superclass chain to find the most specific registered mapper
- Caches lookups for performance
- Fallback (no mapper found): 500 Problem Detail response
- `hasSpecificMapper()` — returns `true` if a mapper is registered for the given type or a superclass before `Throwable`; used by `ErrorPipeline` to distinguish specific user-contributed mappers from the `DefaultExceptionMapper` catch-all when deciding whether to apply the Vert.x status code fallback

### DefaultExceptionMapper

Framework-internal `ExceptionMapper<Throwable>` with a fluent `.on()` API:

```java
public class DefaultExceptionMapper implements ExceptionMapper<Throwable> {
    public <T extends Throwable> DefaultExceptionMapper on(Class<T> type, ExceptionMapper<T> mapper) { ... }
    @Override public Response toResponse(Throwable throwable) { ... }
}
```

`RestModule` pre-configures it with:

| Exception | Status | Response body |
|-----------|--------|---------------|
| `jakarta.ws.rs.WebApplicationException` | from `getResponse()` | uses the JAX-RS Response directly (entity passthrough if present; else Problem Detail) |
| `RestValidationException` (rest-core) | 400 | `ValidationProblemDetail` with `errors` array |
| `BeanValidationException` (core.validation) | 400 | `ValidationProblemDetail` with violations as `ValidationErrorDetail` (location `null`) |
| `ValidationException` (core.exception) | 400 | Problem Detail |
| `ParamConversionException` (rest-core `convert`) | 400 | Problem Detail — an inbound path/query/header/cookie/form value failed conversion to its declared type (ADR-0142) |
| `ParamConverterNotFoundException` (rest-core `convert`) | 500 | Problem Detail — no converter/provider satisfies a declared parameter type at request time (a misconfiguration that startup validation is intended to catch first) |
| `IllegalArgumentException` | 400 | Problem Detail (backward compat) |
| `UnauthorizedException` (core.exception) | 401 | Problem Detail |
| `ForbiddenException` (core.exception) | 403 | Problem Detail |
| `NotFoundException` (core.exception) | 404 | Problem Detail |
| `ConflictException` (core.exception) | 409 | Problem Detail |
| `UnavailableException` (core.exception) | 503 | Problem Detail |
| `Throwable` (catch-all) | 500 | Problem Detail |

`UnauthorizedException` and `ForbiddenException` are registered in `RestModule` by fully-qualified
name (`dev.vertique.core.exception.UnauthorizedException` / `...ForbiddenException`) to avoid a
compile-time ambiguity with `jakarta.ws.rs.NotAuthorizedException` and
`jakarta.ws.rs.ForbiddenException`. `VertiqueSecurityException` itself has no registered mapper
— a bare instance falls through to the `Throwable → 500` catch-all.

### Bean Validation Integration

Bean Validation is opt-in: include `ValidationModule` from the `validation` module in the Dagger component to activate it. When absent, method parameter validation is silently skipped.

**Enabling validation:**

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    RestModule.class,
    ValidationModule.class,   // activates BeanValidator
    AppModule.class,
    ResourceModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

**How it works:**

`RestModule` declares `@BindsOptionalOf BeanValidator beanValidator()`. When `ValidationModule` is present, `RestModule` receives the `BeanValidator` instance and passes it to each `ResourceMethodInvoker`. When absent, the optional is empty and validation is skipped.

`ResourceMethodInvoker.validateArguments()` runs after `extractArguments()` and before `method.invoke()`:

1. Reads `meta.validationGroups()` — populated from `@ValidateWith` on the resource method, or `null` for the default group
2. Calls `beanValidator.checkParameters(instance, method, args, groups)` to collect `ParameterViolation` records
3. If violations are non-empty, passes them to `ConstraintViolationMapper.toRestValidationException()` and throws the result

**`ConstraintViolationMapper`** maps each `ParameterViolation` to a `ValidationErrorDetail` with an HTTP `location` field inferred from the parameter's `ParamSource`:

| `ParamSource` | HTTP location |
|---|---|
| `BODY` | `"body"` |
| `QUERY` | `"query"` |
| `PATH` | `"path"` |
| `HEADER` | `"header"` |
| `COOKIE` | `"cookie"` |
| `FORM` | `"form"` |
| `BEAN_PARAM` | Resolved by reflecting the bean class field annotations (cached) |
| Other | `null` |

For `BEAN_PARAM` violations, `ConstraintViolationMapper` reflects on the bean class to find the JAX-RS annotation (`@QueryParam`, `@PathParam`, etc.) on the violated field or record component. Results are cached in a `ConcurrentHashMap<Class<?>, Map<String, FieldLocation>>` to eliminate per-request reflection overhead on error paths.

The resulting `RestValidationException` flows into the `DefaultExceptionMapper` mapping:
- `RestValidationException` → 400 `ValidationProblemDetail` (with `errors` array)
- `BeanValidationException` (if thrown directly from service code) → 400 `ValidationProblemDetail` (violations converted to `ValidationErrorDetail` with `null` location)

**Example resource with validation:**

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

**Validation error response:**

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

---

### ExceptionMapperResolver

Package-private utility that resolves the exception type `T` from each `ExceptionMapper<T>` in a set. Uses `dev.vertique.core.util.TypeResolver` to walk the class and interface hierarchy. Mappers whose type cannot be resolved are logged and skipped.

### DefaultResponseSerializer

Concrete implementation of `ResponseSerializer` (defined in `rest-core`). Encodes the entity of a `jakarta.ws.rs.core.Response` and writes it to the HTTP wire, returning the wire-completion future required by the SPI's dual-channel contract.

The status code and the response headers are **already on the wire** when this serializer runs — `ResponsePipeline.applyToWire` writes them before delegating. The serializer only owns the body.

**Pipeline:**
1. **Null entity** — invoke `RequestInterceptor.onSerialize()` with a `null` body, then `response.end()`
2. **Select encoder** — first `ResponseBodyEncoder` in `OrderedExtension.comparator()` order (phase → priority → orderKey) whose `canEncode(entityType, effectiveContentType)` matches; the effective Content-Type is read from the already-written response headers
3. **No encoder matches** — log a warning, switch the response to `500` / `application/problem+json`, invoke `onSerialize()` with the `ProblemDetail` body, and end with the problem JSON
4. **Encode** — `encoder.encode(ctx, response, entity)` produces a `SerializedBody`; an encoder failure propagates as a **synchronous throw** with nothing written (this is what makes the `ResponsePipeline` error fail-open retry safe)
5. **Observe** — invoke all `RequestInterceptor.onSerialize()` hooks with the encoded body (read-only: logging, metrics, audit)
6. **Dispatch to wire** — apply the encoder's Content-Type (only when the response has none) and Content-Length, then write

| Encoded body | Wire write | Returned future |
|--------------|-----------|-----------------|
| `null` entity (step 1) | `response.end()` | the `end()` future |
| No encoder matched (step 3) | `response.end(problemJson)` after status `500` | the `end(String)` future |
| `BufferedBody` | `response.end(buffer)` | the `end(Buffer)` future |
| `StreamingBody` | `stream.pipe().endOnFailure(false).to(response)` | the pipe future |

Framework encoders (all priority `1000`, so an application encoder at the default priority `0` wins): `BufferBodyEncoder`, `ByteArrayBodyEncoder`, `StringBodyEncoder`, `ReadStreamBodyEncoder` (`ReadStream<Buffer>` only), `JsonBodyEncoder` (fallback).

**Streaming failure ownership.** A `StreamingBody` is piped, never buffered (FR-RESTSER-013 / NFR-003), and `endOnFailure(false)` means this serializer does **not** end the response when the pipe fails: the returned future fails with the source or (unwrapped) write cause, and terminal cleanup belongs to the caller observing that future. A successful pipe ends the response and succeeds the future.

`RestModule` provides `DefaultResponseSerializer` as the `ResponseSerializer` binding. Override with a custom `@Provides ResponseSerializer` to use CBOR, XML, or any other format — a custom implementation must honor the same dual-channel contract (see the `ResponseSerializer` section of the `vertique-rest-core` module reference).

### ResponsePipeline

Package-private internal orchestrator for the unified response pipeline. All responses (success and error) flow through the same path: `produce()` → `transformResponse` chain → wire handoff (`applyToWire`) → `afterResponse` observers → wire-completion observation.

```java
class ResponsePipeline {
    Response produce(RoutingContext ctx, Object result) { ... }
    void sendResponse(RoutingContext ctx, Response response) { ... }
    void handle(RoutingContext ctx, Object result) { ... }  // convenience: produce + sendResponse
    void sendFallback500(RoutingContext ctx, Throwable cause) { ... }
}
```

**`produce()` pipeline:**
1. **Find producer** — walk the result's superclass hierarchy for a `ResponseProducer<T>`; if none found, apply **Accept header negotiation** (see below); `null` result → `Response.noContent().build()`
2. **Conditional evaluation** — evaluate `If-None-Match`, `If-Match`, etc. per RFC 9110 §13.2.2, but only when the produced response is **2xx and carries a validator** (an `ETag` or `Last-Modified` header). Both conditions gate the check: a non-2xx response (e.g. a `3xx` redirect or a mapped error) is not a "selected representation" per RFC 9110 §13.2.2, so evaluating preconditions against it would be meaningless at best and actively wrong at worst (an unconditional `If-Match` rewriting an unrelated redirect into a spurious `412`); a validator-less 2xx (e.g. a URL-issuance `200` that carries no `ETag`) has not opted into conditional handling, so it is left untouched rather than spuriously failing an `If-Match` against an absent validator
3. **HEAD stripping** — strip entity from HEAD responses that passed conditional evaluation
4. **Return** the Response (does NOT send)

**`sendResponse()` pipeline (unified for success + error):**
1. **Transform** — chain `RequestInterceptor.transformResponse()` hooks (async, priority-ordered)
2. **Hand off to the wire** — `applyToWire()` writes status + headers, then ends the response (null entity) or delegates the body to `ResponseSerializer.serialize()` (which invokes `onSerialize` hooks). This *initiates* the write and returns its wire-completion future
3. **Observe** — fire `RequestInterceptor.afterResponse()` sync observers (both success and error), **after** the handoff and **before** the wire completes
4. **Observe wire completion** — attach a failure observer to the completion future from step 2

A synchronous throw from step 2 means nothing was written: it routes to `sendFallback500()`, which fires the single `afterResponse` with a synthetic 500 (see the `afterResponse` contract in `dev.vertique:vertique-rest-core`).

**Wire-completion observation (post-handoff failures).** Steps 3 and 4 encode the split between *handoff* and *completion*. `afterResponse` deliberately fires at handoff, while a streamed body may still be in flight, because observers need the routing context and the tracing span to still be active. A failure that surfaces afterwards — a truncated stream, a client abort — is therefore reported through a different channel:

- The request `Context` is captured **before** the handoff. A custom serializer's completion future may settle on any thread, so failure handling is redispatched onto that context (run inline when already on it, or when there is no context).
- The cause is stored on the routing context under `RestRequestCompletionEmitter.KEY_WIRE_FAILURE` (first writer wins) so the completion event can classify it.
- A WARN names the method, path, status, and the cause's **class simple name** only — a wire failure message can echo peer or payload detail — with the full throwable at DEBUG.
- **Termination is pipeline-owned.** The serializer never ends a failed response, so the pipeline ends it if it is not ended already. The `end()` is fully guarded: a declared `Content-Length` that the truncated body no longer satisfies throws `IllegalStateException` synchronously, and ending an already-dead connection fails the returned future; neither escapes.
- Nothing else happens: no bare 500 (the client already has the status line), no `afterResponse` re-fire, and no `sendFallback500()` re-entry.

`sendFallback500()` observes its own `end()` future the same way — a bare-metal 500 that never reached the client is logged and recorded under the same key. On that path the marker may land *after* the completion event was emitted, so logging is guaranteed while event enrichment is best-effort.

**Error fail-open and completion.** `serializeErrorWithFailOpen()` retries only on a *synchronous* throw (nothing written yet, FR-JSON-058A) and returns the completion future of the attempt that actually ran, so the pipeline observes exactly one wire completion per response. A failed completion future is a post-handoff failure and is never retried.

**Accept header negotiation:** when no `ResponseProducer` is registered for the result type, the handler negotiates `Content-Type` using `AcceptNegotiator.negotiate()`:
- Candidates from `@Produces` or `["application/json"]` default
- `406 Not Acceptable` ProblemDetail returned when no match (flows through same `sendResponse` pipeline)

Created per-mount by `JaxRsRouterMount` (not a Dagger singleton). Application extension via `ResponseProducerBinding` multibinding.

Custom producers are contributed via `@Provides @IntoSet ResponseProducerBinding<?>` in any Dagger module — see `ResponseProducerBinding` in `dev.vertique:vertique-rest-core` for the pattern.

### RouteRegistrationException

Thrown during startup when route registration detects violations. Extends `RestConfigurationException` (from `rest-core`) and contains the collected `RouteRegistrationViolation` records. Security-policy inconsistencies use the separate `SecurityPolicyViolationException`.

---

## Server-Sent Events (SSE)

SSE endpoints return `ReadStream<SseEvent>` from a JAX-RS resource method annotated with `@Produces("text/event-stream")`. The framework handles buffering, wire formatting, keepalive, and connection lifecycle automatically.

### Runtime classes (package-private)

| Class | Purpose |
|-------|---------|
| `DefaultSseChannel` | `ReadStream<SseEvent>` implementation with bounded in-memory buffer; created by `DefaultSseChannelFactory` |
| `DefaultSseChannelFactory` | `SseChannelFactory` implementation; reads defaults from `JaxRsConfig.sse()` |
| `SseReadStream` | `ReadStream<Buffer>` adapter that formats `SseEvent` objects into SSE wire format and writes keepalive comments on a periodic timer |
| `SseBodyEncoder` | `ResponseBodyEncoder` at priority 999 that detects `ReadStream<SseEvent>` entity, sets `Content-Type: text/event-stream` and `Cache-Control: no-cache`, and pipes the `SseReadStream` to the HTTP response |

### SSE wire format

Each `SseEvent` is formatted as one or more lines terminated by `\n`, followed by a blank line (`\n`):

```
id: <id>\n          (omitted if id is null)
event: <event>\n    (omitted if event is null)
data: <data>\n      (omitted if data is null)
retry: <ms>\n       (omitted if retryMs is null)
: <comment>\n       (omitted if comment is null)
\n
```

Keepalive comments (`:\n\n`) are emitted at the configured `keepAliveIntervalMs` interval when no data events are sent.

See `examples/vertique-example-sse` for a complete end-to-end example demonstrating `SseChannelFactory`, `Last-Event-ID` replay, terminal event ordering, and connection cleanup.

### Startup validation

`JaxRsRouteRegistrar` validates SSE endpoints at startup:
- Methods returning `ReadStream<SseEvent>` must declare `@Produces("text/event-stream")`
- Methods declaring `@Produces("text/event-stream")` must return `ReadStream<SseEvent>`

Violations are collected and thrown as `RouteRegistrationException` after all resources are scanned.

### `SseBodyEncoder`

```java
// Priority 999 — runs before the JSON fallback encoder
class SseBodyEncoder implements ResponseBodyEncoder {
    @Override public int priority() { return 999; }
    @Override public boolean canEncode(Object entity, String contentType) {
        return entity instanceof ReadStream && "text/event-stream".equals(contentType);
    }
    @Override public void encode(RoutingContext ctx, Object entity, HttpServerResponse response) {
        // Sets headers, creates SseReadStream, pipes to response
    }
}
```

`SseBodyEncoder` is provided as an `@IntoSet ResponseBodyEncoder` binding by `RestModule` when the `SseChannelFactory` binding is present. No application-level wiring is required.

---

## Return Types

| Return Type | HTTP Response |
|------------|---------------|
| `Future<T>` | Async; serializes `T` as JSON with status 200 |
| `Future<Void>` | Async; sends 204 No Content |
| `Future<Response>` | Async; extracts status, headers, entity from `jakarta.ws.rs.core.Response` |
| `T` | Sync; serializes as JSON with status 200 |
| `void` | Sync; sends 204 No Content |
| `Response` | Sync; extracts status, headers, entity from `jakarta.ws.rs.core.Response` |
| `ReadStream<SseEvent>` | Streaming SSE; sets `Content-Type: text/event-stream`, pipes events via `SseBodyEncoder` |

---

## Parameter Extraction

The following table lists all supported parameter forms, in the order `JaxRsRouteRegistrar.resolveParams()` evaluates them:

| Priority | Annotation / Type | Source | Target Type(s) | Notes |
|----------|-------------------|--------|----------------|-------|
| 1 | `@Context` or unannotated built-in / `ContextValue` type | `CONTEXT` — resolver chain | `RoutingContext`, `jakarta.ws.rs.core.SecurityContext`, `dev.vertique.security.SecurityContext`, any `ContextValue` subtype | Resolved via `RestContextResolution.require(type, ctx, ...)` through the `RestContextResolver` chain; never falls through to body deserialization |
| 1 | (type `RequestPreconditions`) | Conditional-request context | `RequestPreconditions` | Auto-injected by type (`PRECONDITIONS` source) |
| 2 | (type annotated with `@RequestParams`) | Composite params | Any `@RequestParams`-annotated record/class | No annotation needed on method param; fields carry JAX-RS param annotations |
| 3 | `@BeanParam` | Composite params | Any class/record | Explicit annotation on method param; backward compatible |
| 4 | `@PathParam("name")` | URL path segment | `String`, `int`, `long`, `float`, `double`, `boolean`, `JsonObject` | `@DefaultValue` supported |
| 4 | `@QueryParam("name")` | Query string | `String`, `int`, `long`, `float`, `double`, `boolean`, `JsonObject` | `@DefaultValue` supported |
| 4 | `@HeaderParam("name")` | HTTP header | `String` | `@DefaultValue` supported |
| 4 | `@CookieParam("name")` | Cookie | `String` | `@DefaultValue` supported |
| 5 | `@FormParam("name")` | Multipart/form field | `FileUpload`, `EntityPart`, `List<FileUpload>`, `List<EntityPart>`, `String`, primitives | `@DefaultValue` supported for text fields |
| 6 | (unannotated, type `List<FileUpload>`) | All uploaded files | `List<FileUpload>` | Auto-detected by type |
| 6 | (unannotated, type `List<EntityPart>`) | All multipart parts | `List<EntityPart>` | Auto-detected by type |
| 7 | (unannotated, any other type) | Request body | POJO, `JsonObject`, `String`, `Buffer` | JSON/text/binary via `RequestBodyDecoder` SPI |

**`@DefaultValue`** provides a fallback string value for `@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, and text `@FormParam` when the parameter is absent from the request. Type coercion applies (e.g., `@DefaultValue("0")` on an `int` parameter).

**`@RequestParams`** is the preferred approach for composite query-parameter objects such as `OffsetPageRequest` and `CursorPageRequest`. It removes the need for `@BeanParam` on each method parameter and allows reusing the same record across multiple endpoints.

---

## Multipart File-Part Validation

`@FilePart` declares post-spool constraints for physical file uploads. It is valid on exactly these resource-parameter shapes:

```java
@FormParam("avatar")
@FilePart(allowedTypes = {"image/png", "image/jpeg"}, maxSizeBytes = 5_000_000)
FileUpload avatar

@FormParam("attachments")
@FilePart(allowedTypes = {"application/pdf"})
List<FileUpload> attachments

@FilePart(maxSizeBytes = 10_000_000)
List<FileUpload> uploads // unannotated aggregate
```

`allowedTypes` is empty by default (any declared media type); `maxSizeBytes` is `-1` by default (no per-part cap). Allowed types use exactly one slash and RFC 7230 token characters. Only a complete subtype wildcard such as `image/*` is accepted; `*/*`, parameters, whitespace, quality factors, extra slashes, and partial wildcards fail route startup. Values are lowercase-canonicalized when `FilePartDescriptor` is built, and `maxSizeBytes` must be `-1` or positive.

The `web-validation` gate matches named parts case-sensitively and validates every same-name physical upload. Aggregate constraints apply only when no named constraint matches. Duplicate constrained declarations that could cover the same upload fail startup. A missing named upload is not a file error; presence constraints come from ordinary parameter validation. Duplicate error paths are occurrence-indexed (`avatar`, `avatar[1]`, ...).

The client declaration must be concrete and parseable; only the configured subtype may be a wildcard. Synchronous file errors use `ValidationErrorDetail.location = "file"`:

| Type | Meaning |
|------|---------|
| `fileContentTypeMissing` | No declared content type |
| `fileContentTypeMalformed` | Unparseable or wildcard declared type |
| `fileContentTypeNotAllowed` | Concrete type does not match `allowedTypes` |
| `fileMaxSize` | Already-spooled file exceeds `maxSizeBytes` |

`@FilePart` is invalid on `EntityPart` and `List<EntityPart>`. An `EntityPart` can be file-backed or text-backed while this gate observes only `RoutingContext.fileUploads()`; accepting the annotation would create a text-part bypass. File-backed entity parts remain eligible for global `FileContentVerifier` extensions through unconstrained descriptors. Multipart text fields are exempt from aggregate file constraints and continue through ordinary form-schema validation.

`JaxRsOperationDescriptor.fileParts()` is an additional immutable validation view; named form file parameters remain in `parameters()`. The public `FilePartDescriptor` constructor enforces its null-or-non-blank part name, media-type grammar, defensive copying/canonicalization, and `-1`-or-positive size invariant.

Per-part size checks happen after `BodyHandler` has written the upload. `http.maxBodySize` is the only ingress limit and returns HTTP 413; `@FilePart.maxSizeBytes` returns a file validation 400 and does not prevent pre-auth disk writes.

---

## JAX-RS Runtime Support

The `dev.vertique.rest.jaxrs.runtime` package provides a minimal `RuntimeDelegate` so `jakarta.ws.rs.core.Response` works without Jersey or RESTEasy on the classpath:

| Class | Purpose |
|-------|---------|
| `SimpleRuntimeDelegate` | `RuntimeDelegate` implementation; installed automatically |
| `SimpleResponseBuilder` | `Response.ResponseBuilder` implementation |
| `SimpleResponse` | `Response` implementation |
| `SimpleStatusType` | `Response.StatusType` implementation |
| `SimpleUriBuilder` | `UriBuilder` implementation |
| `SimpleLink` / `SimpleLinkBuilder` | `Link` / `Link.Builder` implementation |
| `VertxFileUploadEntityPart` | `EntityPart` adapter wrapping a Vert.x `FileUpload`; reads content from the temp file written by `BodyHandler`. `getContent()` is single-use. |
| `FormFieldEntityPart` | `EntityPart` adapter wrapping a text form field value. `getContent()` is single-use. |

`Response.ok(entity).build()`, `Response.status(404).entity(body).type("application/problem+json").build()`, etc. all work out-of-the-box.

---

## Example Resource

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
        String message = String.format(config.hello(), name);
        return Future.succeededFuture(new GreetingResponse(message));
    }

    @POST
    @Operation(operationId = "createItem")
    public Response createItem(CreateItemRequest request) {
        if (request.name() == null) {
            throw new BadRequestException("name is required");
        }
        Item item = itemService.create(request);
        URI location = URI.create("/items/" + item.id());
        return Response.created(location).entity(item).build();
    }
}
```

**Registration in Dagger:**

```java
@Module
public abstract class ResourceModule {
    @Provides @IntoSet @JaxRsResources
    static Object helloResource(HelloResource resource) {
        return resource;
    }
}
```

**Generated auto-wiring:**

Applications inheriting `vertique-app-parent` declare `vertique-rest-jaxrs` as a runtime dependency
and receive the complete processor facade automatically. Custom-parent applications use the BOM
plus `vertique-codegen-all` recipe in `docs/packaging.md`. `vertique-codegen-jaxrs` owns generated
`@Provides @IntoSet @JaxRsResources Object` bindings for `@Path` classes. Include
`GeneratedJaxRsResourcesModule.class` in the `@Component`; annotate a resource with `@NoAutoWire`
to keep its manual binding canonical. Applications using that source-retained opt-out also declare
`vertique-codegen-core` with `provided` scope as documented in `docs/packaging.md`.

**File upload resource example:**

```java
@Path("/uploads")
@Consumes("multipart/form-data")
public class UploadResource {

    // Single named file upload as Vert.x FileUpload
    @POST
    @Path("/file")
    @Operation(operationId = "uploadFile")
    public Future<UploadResult> uploadFile(
            @FormParam("file")
            @FilePart(allowedTypes = {"image/png"}, maxSizeBytes = 5_000_000)
            FileUpload upload,
            @FormParam("description") String description) {
        // Move/copy the request-owned temp file before this response completes if persistence is needed.
        return processFile(upload, description);
    }

    // Single named file upload as JAX-RS EntityPart
    @POST
    @Path("/part")
    @Operation(operationId = "uploadPart")
    public Future<UploadResult> uploadPart(@FormParam("file") EntityPart part) throws IOException {
        try (InputStream in = part.getContent()) {
            return processStream(part.getFileName().orElse("unknown"), in);
        }
    }

    // All multipart parts as List<EntityPart>
    @POST
    @Path("/all-parts")
    @Operation(operationId = "uploadAllParts")
    public Future<List<String>> uploadAllParts(List<EntityPart> parts) {
        List<String> names = parts.stream()
                .map(EntityPart::getName)
                .collect(Collectors.toList());
        return Future.succeededFuture(names);
    }
}
```

**Text body and binary body examples:**

```java
@Path("/data")
public class DataResource {

    // text/plain body → String parameter
    @POST
    @Path("/text")
    @Consumes("text/plain")
    @Produces("text/plain")
    @Operation(operationId = "uploadText")
    public String uploadText(String body) {
        return "received: " + body;
    }

    // application/octet-stream body → Buffer parameter
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

## Profile-Aware Request-Body Parsing

`rest-jaxrs` selects a per-method `ObjectMapper` for JSON request-body parsing at router-build time. The resolved mapper is stashed on the `RoutingContext` ahead of the validation gate so the two parsing steps — raw bytes to `JsonObject`/`JsonArray` and `JsonObject` to POJO — both run through the same profile mapper.

**Precedence (highest first):**

1. `@JsonProfile("id")` on the resource **method**
2. `@JsonProfile("id")` on the resource **class**
3. `JaxRsConfig.jsonProfile()` (config key `jaxrs.jsonProfile`) when non-blank (per-boundary default)
4. `JsonConfig.jsonProfile()` (config key `json.jsonProfile`) when non-blank (global default)
5. `vertx` — the built-in Vert.x codec (unchanged default)

**How it works:**

`RequestBodyProfileResolver` evaluates the precedence at router-build time for each `ResourceMethodMeta`. When the effective id is `vertx`, the resolver returns `null` and the existing body-binding path is byte-for-byte unchanged. For any other id, the resolved `ObjectMapper` is written to the `RoutingContext` under `BoundRequest.KEY_RESOLVED_BODY_MAPPER` (`"vertique.rest.jaxrs.resolvedBodyMapper"`) before the validation gate.

`DefaultBoundRequest` reads `KEY_RESOLVED_BODY_MAPPER` from the routing context and passes it as the `profileMapper` to `bindBody`. When non-null, the profile mapper performs the **first parse** of a JSON-content-type body — deserializing raw bytes to `JsonObject`, `JsonArray`, or a scalar `Object` under that mapper's strict parser features (e.g. `STRICT_DUPLICATE_DETECTION`, `FAIL_ON_TRAILING_TOKENS`), applied uniformly across object, array, and scalar bodies. Only non-JSON content types fall through to the unchanged Vert.x path.

`ProfileBodyMaterialization` handles the **POJO/collection materialization** step: `convertValue(profileMapper, value, targetType)` converts the parsed `JsonObject`/`JsonArray` to the DTO class or collection type. Both helpers wrap any profile-mapper rejection as a `ValidationException("Request body rejected by JSON profile")` — a value-free HTTP 400 that routes through the standard `DefaultExceptionMapper` → `ProblemDetail` pipeline. The original rejection throwable is retained as the `cause` for server-side diagnosis but is never serialized to the client.

An unknown profile id throws `JsonProfileConfigurationException` at **router-build time** (startup), not at the first request.

**Fail-fast validation of the boundary default (`JaxRsDefaultProfileValidator`):**

`JaxRsDefaultProfileValidator` is a `@Singleton ComposeValidator` contributed by `RestModule`. It resolves `JaxRsConfig.jsonProfile()` through the registry at `@Inject` construction time, failing the `VALIDATE` phase immediately when the configured `jaxrs.jsonProfile` names an unknown profile id — independently of whether any resource method is actually deployed.

**Example:**

```java
@Path("/orders")
@JsonProfile("strict")          // class-level default for all methods
public class OrderResource {

    @POST
    @Operation(operationId = "createOrder")
    public Future<Order> createOrder(CreateOrderRequest request) {
        // first parse and POJO materialization both use the "strict" mapper
        return orderService.create(request);
    }

    @PUT
    @Path("/{id}")
    @JsonProfile("lenient")     // method-level override wins
    @Operation(operationId = "updateOrder")
    public Future<Order> updateOrder(@PathParam("id") String id, UpdateOrderRequest request) {
        return orderService.update(id, request);
    }
}
```

**Application-level default via config:**

```json
{
  "jaxrs": {
    "jsonProfile": "strict"
  }
}
```

When `jaxrs.jsonProfile` is set and a method carries no `@JsonProfile`, all request-body parsing for that method uses the configured profile. When both `jaxrs.jsonProfile` and the global `json.jsonProfile` are blank or absent, the effective profile is `vertx`.

---

## Profile-Aware Response Serialization

`JsonBodyEncoder` — the `ResponseBodyEncoder` implementation used for all JSON entity bodies — reads the same `KEY_RESOLVED_BODY_MAPPER` stash that the request path writes. The result is **symmetric**: a resource method's response uses the exact same effective profile as its request.

**Success entities:** when the stash is present, `mapper.writeValueAsString(entity)` is called with the resolved profile mapper. When absent (effective profile is `vertx`), `Json.encode(entity)` is used — byte-for-byte unchanged from the pre-profiling baseline.

**Error / `ProblemDetail` bodies — matched method:** an error raised within a matched resource method flows through the same `JsonBodyEncoder` automatically, using the method's already-stashed mapper.

**Error / `ProblemDetail` bodies — no matched method:** for errors raised before a method match (e.g. pre-routing 404, schema-validation rejection), no method stash exists. The per-route failure handler in `JaxRsRouterMount` resolves the boundary + global default inline (`firstNonBlank(jaxRsConfig.jsonProfile(), jsonConfig.jsonProfile())`) at router-build time and stashes it under `KEY_RESOLVED_BODY_MAPPER` before the error body is serialized. The encoder is unaware of this distinction.

**Fail-open for error bodies (`FR-JSON-058A`):** if a profile mapper throws while serializing an error or `ProblemDetail` body, the framework falls back to the `vertx` mapper (`Json.encode`), preserving the mapped HTTP status code and the `application/problem+json` media type, and logs a WARN. A profile-mapper failure on a **success entity** still propagates as HTTP 500 — the fail-open applies only to the error pipeline. `ResponsePipeline` marks error responses with `KEY_ERROR_RESPONSE` so the wrap is scoped correctly.

**Confinement:** the change is limited to JSON body-string production. Status codes, the RFC 9457 media type, headers, and the exception-mapping chain are untouched.

---

## RestModule (Dagger)

`RestModule` is the main Dagger `@Module` for the JAX-RS runtime. It includes `RestCoreModule` (from `rest-core`) and `JsonRuntimeModule` (from `vertique-json`) automatically:

```java
@Module(includes = {RestCoreModule.class, JsonRuntimeModule.class})
public abstract class RestModule { ... }
```

`JsonRuntimeModule` installs the `JsonMapperProfileRegistry` binding so the per-method profile resolution in `RequestBodyProfileResolver` can look up named mappers at router-build time.

`RestCoreModule` owns the `ParamConverterRegistry` / `ParamConversionResolver` bindings (and the `Set<ParamConverterBinding<?>>` / `Set<ParamConverterProvider>` multibindings) shared with `rest-client`'s `RestClientModule` (see `dev.vertique:vertique-rest-core` and ADR-0142) — `RestModule` itself only contributes the default exception mappers for the two conversion-failure exception types (below).

Additional bindings beyond `RestCoreModule` and `JsonRuntimeModule`:

| Binding | Value |
|---------|-------|
| `RestExceptionMapper` | wired from `Set<RestExceptionMapperCustomizer>` |
| `DefaultExceptionMapper` | pre-configured with built-in mappings, including `ParamConversionException` (400) and `ParamConverterNotFoundException` (500) — see [DefaultExceptionMapper](#defaultexceptionmapper) |
| `ExceptionMapperRegistry` | wired from `DefaultExceptionMapper` + `Set<ExceptionMapper<?>>` |
| `List<RequestInterceptor>` | sorted `Set<RequestInterceptor>` by priority (sorted once, shared by serializer and pipeline) |
| `List<ResponseBodyEncoder>` | sorted `Set<ResponseBodyEncoder>` by priority, then class name |
| `List<RequestBodyDecoder>` | sorted `Set<RequestBodyDecoder>` by priority, then class name |
| `ResponseSerializer` (`DefaultResponseSerializer`) | wired from sorted `List<RequestInterceptor>` and `List<ResponseBodyEncoder>` |
| `ResponsePipeline` | wired from `Set<ResponseProducerBinding<?>>`, sorted interceptors, and `ResponseSerializer` |
| `Set<RequestValidationStrategy>` | `@Multibinds` plus built-in `none`; optional modules contribute `web-validation` and `openapi-contract` |
| `Set<FileContentVerifier>` | empty `@Multibinds` base; applications and `MagicBytesVerifierModule` contribute trusted async verifiers |
| `Set<RouterMount>` (default JAX-RS) | `@ElementsIntoSet`: `JaxRsRouterMount` at `api.basePath`; empty set when `@JaxRsResources` is empty |

Apps use `RestModule.class` in their Dagger `@Component` — this transitively includes `RestCoreModule`:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    RestModule.class,       // rest-jaxrs (includes RestCoreModule from rest-core)
    AuthModule.class,       // rest-security
    SecurityModule.class,   // rest-security
    AppModule.class,
    ResourceModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

Custom exception mappers: implement `jakarta.ws.rs.ext.ExceptionMapper<T>` and contribute via `@Provides @IntoSet ExceptionMapper<?>`:

```java
// Step 1: implement ExceptionMapper<T>
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

// Step 2: contribute via Dagger multibinding
@Provides @IntoSet
ExceptionMapper<?> itemNotFoundMapper(ItemNotFoundMapper mapper) { return mapper; }
```

---

## Dependencies

- `dev.vertique:rest-core`
- `dev.vertique:core`
- `dev.vertique:vertique-json` — `JsonMapperProfileRegistry` and `JsonRuntimeModule`; required for per-method JSON profile resolution
- `io.vertx:vertx-core`
- `io.vertx:vertx-web`
- `com.google.dagger:dagger`
- `jakarta.ws.rs:jakarta.ws.rs-api`
- `com.fasterxml.jackson.core:jackson-databind`
- `org.projectlombok:lombok` (provided scope)

---

## Related ADRs

- ADR-0069: REST Context Resolver Chain and Single Context Source — establishes the `RestContextResolver` SPI as the single resolution path for all `@Context`-injectable types; unifies `RoutingContext`, JAX-RS `SecurityContext`, framework `SecurityContext`, and `ContextValue` subtypes under a single `CONTEXT` `ParamSource`; adds startup validation for `CONTEXT_PARAM_CONFLICT`, `UNSUPPORTED_JAXRS_CONTEXT_TYPE`, and `NON_INJECTABLE_CONTEXT_TYPE`.
- ADR-0084: Framework Extension-Ordering Contract — establishes `OrderedExtension` and `ExtensionPhase` as the canonical ordering contract for framework extensions.
- ADR-0085: OrderedExtension Rolled Out Across Sorted Behavioral SPIs — `RestExceptionMapperCustomizer` and `OperationHandlerContributor` now follow the framework OrderedExtension ordering contract (phase → priority → orderKey).
- ADR-0108: Unified Failure Mapping on a Context-Aware `FailureMapper` — collapses four layer-specific mapper wrappers onto one concrete `core.failure.FailureMapper`; `RestExceptionMapper` now extends it; `map(...)` renamed to `translate(...)`.
- ADR-0119: Security-Scheme Handler Decoupling from RouterBuilder — establishes `RouteRegistration`, `SecuritySchemeRegistry`, and `RouterSetup` as transport-neutral replacements for `RouterBuilder`/`OpenAPIRoute`; decouples `OperationHandlerContributor`, `SecuritySchemeHandler`, and `RouterLifecycleHook` from the Vert.x OpenAPI router.
- ADR-0122: BoundRequest as the Neutral Per-Request Binding Model — replaces `ValidatedRequest`/`RequestParameter` with the neutral `BoundRequest` / `RequestValue` surface; decouples parameter extraction from the validation strategy.
- ADR-0126: REST Profile-Aware Request Body Binding — makes `DefaultBoundRequest` perform the request body's first parse with the per-method resolved JSON profile mapper (`@JsonProfile` → config → `vertx`); the `vertx` path is unchanged and a profile-rejected body becomes a 400 via the standard error pipeline.
- ADR-0136: Global + per-boundary JSON default-profile config tiers — adds the `jaxrs.jsonProfile` per-boundary default and the `json.jsonProfile` global tier to the JAX-RS precedence chain; renames `jaxrs.requestJsonProfile` → `jaxrs.jsonProfile` for symmetry; introduces `JaxRsDefaultProfileValidator` (`ComposeValidator`) for unconditional fail-fast validation of the boundary default.
- ADR-0137: Symmetric JAX-RS request + response JSON profiling — extends profile-awareness to JAX-RS response bodies (success and `ProblemDetail`/error) via the `KEY_RESOLVED_BODY_MAPPER` stash; the per-route failure handler stashes the inline-resolved default for no-method errors; error-body serialization failures fail open to the `vertx` mapper with status and media type preserved.
- ADR-0142: Param-Conversion SPI — Registry, Resolver, and ConversionContext — introduces the shared `ParamConverterRegistry`/`ParamConversionResolver` in `rest-core` that `ParameterExtractor` now uses for every inbound path/query/header/cookie/form coercion, replacing the old scalar-only `ScalarCoercion` and the dead `BuiltInParamConverterProvider`; adds `JaxRsRouteRegistrar`'s startup converter-resolvability check.
- ADR-0143: REST Metadata-Record Unification onto `core.codegen` — migrates `ResourceMethodMeta.ParamMeta` onto the neutral `core.codegen.ParameterMetadata` SPI (`annotationsLazy()` replacing the eager `Annotation[]` field), backed reflectively by `ReflectiveParameterMetadata` on the scan path and by generated literals on the codegen path.
- ADR-0146: jaxrs codegen parity-first parameter annotations — both `ExecutionPlanEmitter` and `JaxRsDescriptorEmitter` materialize each parameter's runtime-retained annotations into compile-time literals, falling back to a lazy per-parameter reflective read for any annotation that cannot be literal-backed, so a codegen route always matches the reflective scan path byte-for-byte; dedups `ReflectiveParameterMetadata` onto the shared `core.codegen` implementation, deleting the jaxrs-local duplicate; removes the startup `WARN` guard that announced the prior gap.
- ADR-0179: File-Part Validation and Content Verifier — governs `@FilePart` descriptor shapes, strategy-scoped verifier execution, reset-safe temporary-file cleanup, and request-bounded file lifetime.
