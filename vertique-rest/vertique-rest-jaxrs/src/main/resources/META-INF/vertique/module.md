<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST JAX-RS Module

> **Status:** Beta
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
3. Canonicalize  InputObjectProcessor (dev.vertique.input.processing): route-level → object-level
   + Sanitize    → field-level canonicalizers, then route-level → object-level → field-level sanitizers
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

**Policy resolution precedence.** Route- and parameter-level `@Canonicalize`/`@Sanitize` chains are
derived through one shared resolver — the same one every other transport in the framework uses, not a
REST-only algorithm. Precedence is parameter &gt; method &gt; class: a parameter-level skip or declared
chain overrides the route (class/method) chain; on the route itself a method-level skip or declared
chain overrides the class-level one; with nothing declared anywhere the chain is empty.

Each level's view of its own declaration is hierarchy-merged the same way annotation resolution works
everywhere else in the framework: the declaring element first, then the same declaration on each
superclass bottom-up, then on each interface the class transitively implements, with the first
occurrence winning per element. An override may **replace** an inherited chain with a different one —
the override wins — but may never **remove** it by pairing a skip annotation with an inherited
declared chain on the other polarity; that combination is a conflict, not an opt-out.

A conflict — the additive annotation and the skip annotation both present, anywhere in one element's
merged view — fails immediately and names both declaration sites, for example: `Conflicting @Sanitize
(declared on IFoo.bar) and @SkipSanitization (declared on FooImpl.bar) for method FooImpl.bar — an
override cannot remove an inherited policy; remove one of the annotations.` A class- or method-level
conflict fails resource scanning. A method-parameter conflict now fails at scan too, exactly like the
class/method case, instead of silently resolving to an empty chain. A conflict on a `@BeanParam`
field is rejected as well, a step later: it fails while the route's parameter extractor is built
during route registration. Both are startup failures — declaring both `@Sanitize` and
`@SkipSanitization` (or both `@Canonicalize` and `@SkipCanonicalization`) on one parameter or one bean
field never reaches a request.

**Which body shapes step 3 reaches.** A DTO body, a collection or array body, a `String` body, a
form-urlencoded body bound to a POJO, and the schema-free `JsonObject` / `JsonArray` bodies all pass
through the engine — a Vert.x JSON wrapper is already the intermediate the engine walks, so a
declared chain reaches every string leaf in it, at any nesting depth. A schema-free body carries no
declared property set, so only route- and parameter-level chains apply to it and no wire-name
projection is consulted. A payload whose shape does not match the declared wrapper — an object body
on a `JsonArray` parameter, or an array body on a `JsonObject` one — is not processed at all: it
falls through to the decoder chain, which answers a shape mismatch with `null` exactly as it does
without the engine installed. A raw binary body (`byte[]`, `io.vertx.core.buffer.Buffer`) is the one
shape the engine cannot process at all; declaring a chain on one is rejected at startup rather than
skipped (see [Startup failures](#startup-failures)).

A policy declared on a parameter the engine never sees — a `@Context` parameter, a `RequestPreconditions`
parameter, or a raw multipart `FileUpload` / `EntityPart` parameter — is inert and is neither
processed nor reported: those values are not caller-supplied string input the chain model applies to.

**Renamed body fields are covered.** A field-level policy is declared on a Java property, while the
decoded body is keyed by whatever Jackson publishes — `@JsonProperty("user_name")`, a naming
strategy, `@JsonNaming`, a mix-in, or a `@JsonAlias`. The route's resolved body mapper is introspected
at route registration and its projection maps each wire key back onto the Java property whose policies
apply (`JacksonFieldNameResolver`, from `dev.vertique:vertique-json`). A `@Sanitize` on a renamed
field therefore runs exactly as it would on an unrenamed one, with no extra declaration, and nothing
is introspected on the request path.

```java
public record CreateUserRequest(
    @JsonProperty("display_name") @Sanitize(StripAllHtmlSanitizer.class) String displayName
) {}
// {"display_name": "<b>ada</b>"} -> displayName == "ada"
```

**Three shapes a declared policy still does not reach.** A `Map`-typed field, an `Object`-typed
field, and a concrete `@JsonTypeInfo` subtype's own fields all leave the field with its inherited
route- and object-level chains and nothing else. Nothing fails and nothing is logged, so a stranded policy
on one of these is invisible until the value that mattered gets through. A renamed key, a key a codec
promoted out of an `@JsonUnwrapped` member, and a key matched case-insensitively are all covered
now, and a governed field the mapper binds under a different property name — `@Sanitize` on
`streetName` behind `setStreet` — fails registration rather than passing silently. The `vertique-input-processing` reference documents each shape, what still applies, and how to
stay inside the covered set.

### JSON profiles are symmetric

A resource method's request body and its response body use the same effective `ObjectMapper`. The
profile is resolved once at router-build time and reused for both directions; with nothing configured
that is the `vertique` floor, so a zero-config route binds `Optional` and `java.time` components and
renders without `null` fields — see [Configuration](#configuration).

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

There is no public factory overload for a Jakarta REST application's mount: once one or more
`jakarta.ws.rs.core.Application` classes are declared, the framework's application composer creates one
mount per active application instead of any hand-built call above — see
[Jakarta REST Applications](#jakarta-rest-applications).

With no `Application` declared (zero-declaration mode), `RestModule` contributes a default mount at
`jaxrs.basePath` (default `/*`) with `jaxrs.openapiPath` (default `openapi.json`) via
`@ElementsIntoSet`. The set is **empty** — no mount at all — when `@JaxRsResources` is empty. For a
single API, changing the prefix is a config edit:

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

A status outside 400–599 does not reach the client either. `ctx.fail(new HttpException(200))` and
`ctx.fail(200)` both answer **500**: the failure has no cause to map, so its status would become the
response's own, and a problem document under `200 OK` claims nothing went wrong.

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

**Headers when the body is rebuilt.** Rebuilding the body — by this override, or by the `instance`
enrichment every `ProblemDetail` gets — drops the headers your mapper set that describe the *octets*
of the body it authored: `Content-Length`, `Content-Encoding`, `Content-Range`, `ETag`, and the
digest headers (`Content-Digest`, `Repr-Digest`, `Digest`, `Content-MD5`). They would describe bytes
the client never receives. Every other header survives, including `Content-Type`,
`Content-Language`, and response-level headers such as `WWW-Authenticate`, `Retry-After` and
`Allow` — a `WWW-Authenticate` is exactly what a status overridden *to* 401 needs. Set a
representation header on an error response only if the body is one the framework will not touch
(a non-`ProblemDetail` entity, or a `ProblemDetail` whose `instance` you set yourself and whose
status is not overridden).

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

`RequestValidationStrategy` gains a mount-aware `gateFor(op, schemas, MountMeta mount)` default
overload alongside the original 2-arg form. `JaxRsRouteRegistrar` calls only the 3-arg form —
threading the mount's own `MountMeta` (from `JaxRsRouterMount.meta()`) into every gate it builds — so
a strategy overriding only the 2-arg form is unaffected, while one overriding the 3-arg form (e.g. a
per-mount OpenAPI contract) receives the registering mount's metadata for every operation.

`OperationSchemaSource.schemasFor` takes the operation's effective `JsonMapperProfile` alongside the
descriptor — `schemasFor(op, JsonMapperProfile profile)`. `JaxRsRouteRegistrar` resolves that profile
once per operation at router build and passes it with every call. The one-argument form is removed
rather than kept as a default overload, so an existing implementor recompiles once against the
two-argument signature.

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

## Jakarta REST Applications

An application may declare one or more `jakarta.ws.rs.core.Application` subclasses to group resources
into independently mounted APIs, instead of the manual multi-API recipe under
[`JaxRsRouterMount`](#jaxrsroutermount) above. With no `Application` declared, `RestModule` keeps the
default mount described there unchanged.

### Declaring applications

```java
@ApplicationPath("/api/public")
public final class PublicApplication extends Application {
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(CatalogResource.class, CheckoutResource.class);
    }
}

@ApplicationPath("/api/mgmt")
public final class ManagementApplication extends Application {
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(ManagementResource.class);
    }
}
```

Declaring these two classes mounts `CatalogResource` and `CheckoutResource` under `/api/public/*`, and
`ManagementResource` under `/api/mgmt/*`; no default mount is created. Each application's registration
is produced by the annotation processor from its declaring compilation unit — this reference describes
the declared behavior the generated registration produces, not the processor itself.

### Membership

Each declared application is classified by reflection into one of two membership modes:

- **Explicit membership.** An application that overrides `getClasses()` (or `getSingletons()`) selects
  exactly the classes `getClasses()` lists, read once. Each listed class must be a concrete resource
  with an effective `@Path` and must resolve to exactly one generated resource or one manual
  `@JaxRsResources` instance of that same class; zero or more than one match fails startup, naming the
  application and the offending type.
- **Discovery membership.** An application that overrides neither `getClasses()` nor `getSingletons()`
  selects every enabled generated resource and every manual `@JaxRsResources` instance. Discovery is
  allowed only when that application is the sole declared application, whether or not any other
  declared application is currently active; declaring any other application beside it fails startup,
  naming every declared application.

For explicit membership, `getClasses()` may be computed from injected configuration, and every
invocation — computed or fixed — is evaluated at startup. An empty selection fails startup, naming the
application; it never falls back to discovery. A listed class whose generated resource is currently
disabled by its `@ConditionalOnProperty` condition is excluded from the mount silently, without being
instantiated; when every listed class is disabled this way, the application still mounts, with no
resources and no fallback to the default mount.

A manual `@JaxRsResources` instance matches a listed class when it is exactly that class, or a direct
subclass that declares no runtime-retained annotation on itself, its methods, or their parameters, and
adds no interface beyond what the listed class already implements — the shape the framework's own AOP
proxy has. Any other subclass instance matches nothing and is reported by name, naming the subclass and
asking for it to be listed explicitly instead. A resolved generated resource is checked against the
same rule before it is mounted, so a Dagger-substituted instance that adds routes or annotations fails
startup the same way.

Resources within one mount are ordered by fully qualified class name, regardless of `getClasses()`'s
own order.

### Lifecycle and bounded Jakarta compatibility

| Concern | Contract |
| --- | --- |
| `getClasses()` | Supported; read once per composition. A `null` return means empty. |
| `getSingletons()` | Not supported: a non-empty return fails startup, naming the application. |
| `getProperties()` | Never called. |
| Providers/features | A listed type annotated `@jakarta.ws.rs.ext.Provider`, or assignable to `Feature` or `DynamicFeature`, fails startup as an unsupported resource member. |
| Evaluation | Each declared, active application is constructed and evaluated once per `HttpVerticle` composition — never once for the whole process. Every resolution of `Set<RouterMount>` composes again: a component accessor that returns `Set<RouterMount>` constructs every active application and every selected unscoped resource again and repeats the informational and warning lines each composition logs — do not resolve that set outside the verticle. |
| Failure | An evaluation failure fails the deployment before the server starts listening, naming the application class and its `@ApplicationPath`. |

### Threading

`Application` constructors and `getClasses()` run during mount composition, possibly on a Vert.x
event-loop thread, and must not block. No `Application`, manually contributed `@JaxRsResources`
resource, or generated resource catalog entry may depend on `Set<RouterMount>` of the same
component — in zero-declaration mode as well as explicit mode. Doing so re-enters that component's
own mount composition while it is still in progress, and fails startup with a named
`RestConfigurationException` instead of recursing — naming the application under construction when
one is in progress, and otherwise stating that a manually contributed resource or catalog entry
re-entered composition. Composing a *different* component's `Set<RouterMount>` from inside a
resource or an application is unaffected.

### Framework and library modules never declare an application

A framework or library module contributes resources through `@Provides @IntoSet @JaxRsResources`, the
same seam a sibling framework module uses, but never declares a `jakarta.ws.rs.core.Application`.
Declaring one is the deploying application's decision alone, because a single declaration switches the
whole module into explicit mode for every consumer.

### Explicit mode

Once any application is declared — even when every declared application is currently disabled by its
conditions — explicit mode replaces the default mount for the whole module:

- No default mount is created at `jaxrs.basePath`, including when every declared application is
  inactive.
- `jaxrs.basePath` is not applied to any application mount; a non-default value logs one warning that
  it is unused.
- A manual `@JaxRsResources` contribution — including one contributed by a sibling framework module —
  is routed only when an active application selects it.
- With no active application, startup logs one warning naming the enabled generated resources and
  stating that manual `@JaxRsResources` contributions were not resolved.
- With at least one active application, startup logs one warning — only when the list is non-empty —
  naming every enabled generated resource and manual instance that no active application selected,
  worded "not selected by any Application".
- Startup also logs one informational line listing every declared application (its class, its path,
  and whether it is currently active), and one informational line per application mount naming its
  resource classes in mount order.
- Removing every declared application restores the default mount described under
  [`JaxRsRouterMount`](#jaxrsroutermount) above.

### Generated-code contract types

`dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration` and
`GeneratedJaxRsResourceEntry` are INTERNAL generated-code contract types the annotation processor
emits; they are not for hand-written use. `GeneratedJaxRsApplicationRegistration.of(...)` rejects a
registration path that is not in the application path grammar's normalized form with
`IllegalArgumentException`, naming the application class and the rejected path — a fail-closed backstop
for a registration the processor did not produce.

### Mount conflicts

Once any application is declared, two independent checks reject overlapping mounts before any route
installs:

- **Between active applications.** Two active applications whose mount paths overlap — their prefixes
  (each mount path without its trailing `/*`) are equal, or one starts with the other — fail startup
  before either application is constructed, naming both application classes and both paths. An
  application declared at `@ApplicationPath("/")` therefore conflicts with every other active
  application, because `/` is a prefix of everything; `/api/public` and `/api/publicity` do not
  conflict, because neither prefix starts with the other.
- **Between an application and a hand-built JAX-RS mount.** An application mount whose path overlaps a
  hand-built `JaxRsRouterMount`'s path, in either direction, fails startup before any mount router is
  created, naming the application class and both paths. A hand-built JAX-RS mount whose path is a
  router pattern — containing `:`, `{`, or `}` — conflicts with every application mount regardless of
  any literal prefix relation, because the segment it matches is not known until request time.
- **Unaffected.** A non-JAX-RS mount, and a pair of JAX-RS mounts that include no application, keep the
  warning-only overlap detection `HttpVerticle` already performs for every mount.

Only the Dagger-built `HttpVerticle` hosts application mounts. Its composition validators run the
checks above and mark each application mount instance validated only once every check passes for the
whole composition; an `HttpVerticle` built with the public five-argument constructor, or a subclass
that runs no composition validator, refuses to create an application mount's router at all, naming the
application class and stating that `HttpVerticle` must come from Dagger so its composition validators
run first.

### Migrating a root application

An application declared at `@ApplicationPath("/")` mounts at `/*`, the whole path space. Because every
other prefix overlaps it, a root application conflicts with every other active application and every
hand-built JAX-RS mount — both fail startup as described in [Mount conflicts](#mount-conflicts) above.
A non-JAX-RS mount is unaffected and keeps only the warning-only overlap detection. Adding a first
application to a deployment that currently serves a legacy API at the root path therefore switches the
whole module into explicit mode: the root API must move to a non-root path before any other application
can be declared, and every resource that must stay reachable has to be selected by some declared
application.

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
5. `vertique` — the framework's opinionated profile, and the floor every managed edge shares.

Every id resolved above, the reserved `system` included, is resolved to its mapper through the
`JsonMapperProfileRegistry`. An unknown profile id fails at **startup**, not on the first request.
`JaxRsDefaultProfileValidator` additionally resolves `jaxrs.jsonProfile` during the `VALIDATE` phase,
so a bad boundary default fails even when no resource method would have used it.

The keys this resolution reads:

| Key | Floor | Meaning |
|---|---|---|
| `jaxrs.jsonProfile` | none (falls through when blank) | per-boundary default for every route on this mount |
| `json.jsonProfile` | `vertique` | the global default for managed edges — REST bodies, rest-client, Kafka JSON, MCP |
| `json.systemProfile` | `system` | the profile installed as the **process** JSON codec, which every `Json.encode`, `Json.decodeValue`, and `JsonObject` operation runs on. Not a REST key, but it decides which routes take the fast path below |

**The "no override" fast path is an identity rule.** After resolving the effective id to a mapper, the
resolver returns "no override" — leaving the route on the plain Vert.x body and `Json.encode` paths —
**iff that mapper is the very instance the process JSON codec runs on**. It is never keyed on the
profile id. Under the defaults (`json.jsonProfile: vertique`, `json.systemProfile: system`) that means
a route explicitly selecting `system` takes the fast path and every other route binds and renders
through its own profile mapper. Change `json.systemProfile` and the fast path follows it: an explicit
`system` route under `json.systemProfile: vertique` resolves the `system` mapper, which is no longer
the process codec's, so the route binds through `system` rather than silently through `vertique`.

The comparison is captured **once per route, when the router is built** (the `EDGE` startup phase,
after `CONFIGURE` installs the process mapper). Two consequences follow. A custom
`JsonMapperProfileRegistry` that returns a fresh `ObjectMapper` per call never matches, so its routes
never take the fast path — the built-in registry hands out one stable instance per profile. And
swapping the process codec's mapper after routers are built (a second application booting in the same
JVM with the same system profile id) does not retroactively change routes already decided: routes
that resolved to the process codec (the `null` fast path) follow the swap at request time, while routes
that captured a registry mapper keep binding with it — so after such a swap two routes of one mount
can bind with different mappers.

**Rollback.** Setting `json.jsonProfile: system` puts every unannotated route back on the baseline
recipe — no `NON_NULL` omission, no `BigDecimal` floats, no enum-default leniency, and JSON comments
accepted again (the baseline inherits Vert.x's comment tolerance) — while keeping the `Optional` and
`java.time` support the baseline gained. It is the single-key way to undo the
opinionated default for REST without touching resource code.

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

**Requests.** Unless the route took the fast path above, the resolved mapper performs the **first
parse** of a JSON-content-type body — raw bytes to `JsonObject`, `JsonArray`, or a scalar — under that
mapper's parser features (`STRICT_DUPLICATE_DETECTION`, `FAIL_ON_TRAILING_TOKENS`, and so on),
uniformly for object, array, and scalar bodies. It then performs POJO/collection materialization from
the parsed value. A route on the fast path is parsed by the process codec, which is the same mapper
its profile resolved to. Non-JSON content types take the Vert.x path.

A body the route's binding mapper rejects — on the profiled path and on the fast path alike — becomes a
value-free HTTP 400 with the detail `"Request body rejected by JSON profile"`, routed through the
standard problem-detail pipeline. The
original rejection is kept as the exception `cause` for server-side diagnosis and is **never**
serialized to the client.

**Responses.** `JsonBodyEncoder` reads the same resolved mapper, so a method's response uses exactly
the profile its request used. Errors raised inside a matched method use the method's mapper; errors
raised before a method match — a pre-routing 404, a schema rejection — use the boundary-plus-global
default resolved inline at router-build time.

**Fail-open for error bodies.** If a profile mapper throws while serializing an error or
`ProblemDetail` body, serialization falls back to the process JSON codec with the mapped status code
and the `application/problem+json` media type preserved, and logs a WARN. A profile-mapper failure on a
**success** entity still surfaces as HTTP 500 — the fail-open is scoped to the error pipeline.

### Where the request-validation gate and the body binder disagree

The request-validation gate is not profile-aware: `web-validation` synthesizes schemas from the
declared types, `openapi-contract` validates against the mount contract, and neither knows which
profile binds the body. For each behavioral difference between the reserved profiles, the safe
direction is "the gate is at least as strict as the binder". Observed:

| Difference | Gate | `vertique` binder | Direction |
|---|---|---|---|
| Unknown enum string | rejected by the schema `enum` before binding | `@JsonEnumDefaultValue` when declared, else rejected | gate stricter — safe |
| `Optional<T>` property | validated as the plain component type (optionals are flattened) | binds the component, absent stays empty | equivalent |
| `BigDecimal` floats | format-level; the gate is agnostic | `USE_BIG_DECIMAL_FOR_FLOATS` | equivalent |
| `java.time` and `Optional` bodies | accepted as before | now bind instead of failing | strictly a widening of what binds — never a bypass |
| `null` omission | response-side only; no gate applies | omitted (`NON_NULL`) | not gated |
| JSON with `/* */` or `//` comments | accepted (the gate parses through `JsonObject`) | rejected | gate lenient, binder strict — safe; both accept under an explicit `system` |

**`jaxrs.validationStrategy: none` has no gate; the binder is authoritative.** Every one of the rows
above collapses to whatever the effective profile does, including enum leniency: a route bound by
`vertique` (or any enum-lenient profile) accepts an unknown enum string as the type's
`@JsonEnumDefaultValue` with nothing in front of it to reject the value first. Select `system`, a
`vertique-strict`, or a custom profile for a boundary that must reject unknown enum strings without a
gate.

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

Once one or more `jakarta.ws.rs.core.Application` registrations are declared — even when none is
active — `HttpVerticle` additionally rejects a duplicate operationId **across** mounts: two operations
on different `JaxRsRouterMount`s that share an operationId fail startup unless they are the same
operation — the same resource class (an annotation-free AOP proxy counts as its base class), method
name, and parameter types. This is a cross-mount check performed by `HttpVerticle`'s composition
validator, not a `RouteRegistrationViolation`: it throws `IllegalStateException`, naming both methods
and both mounts. The per-mount `DUPLICATE_OPERATION_ID` row above is unaffected — it keeps comparing
only within one mount, and with no application declared it remains the only operationId check that
runs.

`SecurityPolicyViolationException` is thrown immediately when a `SecurityPolicyValidator` is bound and
finds a violation, rather than being collected. `JsonProfileConfigurationException` is thrown at
router-build time for an unknown profile id.

Route registration also gates declared input processing, raising `ConfigurationException` with one
aggregated message naming every offending route:

| Condition | When it is checked |
|---|---|
| A route declares a canonicalizer or sanitizer chain — on the route, on a processed parameter, or inside a processed parameter's type graph — while no `InputObjectProcessor` is bound | only when the binding is absent |
| A `byte[]` or `io.vertx.core.buffer.Buffer` body parameter carries a declared chain | always, bound engine or not |

Both are declarations that provably could not run, and there is no opt-out flag: "declared but not
running" is not a second legitimate mode. The unbound-engine check ignores parameters whose source
never reaches the engine (`@Context`, `RequestPreconditions`, raw multipart), so it never asks for a
module that would change nothing. The binary-body check is independent of the binding because no
Dagger graph can make a chain act on opaque bytes; its message points at `FileContentVerifier` as the
control that does inspect binary content, while stating that `FileContentVerifier` covers multipart
`FileUpload` parts rather than a raw binary body parameter — a pointer, not a drop-in replacement.
Remove the declaration, or add `@SkipCanonicalization` / `@SkipSanitization` to the binary parameter.

When an `InputObjectProcessor` is bound, route registration also composes each route's body wire-name
projection (`JacksonFieldNameResolver`, from `dev.vertique:vertique-json`) against that route's
resolved body mapper. **The engine decides which types get composed**, not the registrar: registration
hands each body parameter's declared type to `InputObjectProcessor.precomputeFieldNameResolution`,
which prepares the resolver for every owner type its own descent may consult for that body. The
postcondition is that no statically knowable owner is left to introspect on the request path — the
`vertique-input-processing` reference documents the owner set and its bounds in full.

Two startup failures follow, both of which previously surfaced per request:

- A type in that set whose projection cannot be composed — two properties claiming one wire name, or
  two properties claiming one `@JsonAlias` — fails router build with `ConfigurationException` naming
  the type and the contested name, including when it is a nested DTO rather than the body type itself.
- A reachable type declaring conflicting policy annotations (on a method, a parameter, or a
  bean-param field) fails router build with
  `IllegalStateException`, because preparing the owner set resolves that type's policy metadata.

Composing at registration is what makes each a startup failure rather than a 500 on every request that
touches the type, and it keeps Jackson bean introspection off the event loop.

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
- **Declaring `@Sanitize`/`@Canonicalize` on a `byte[]` or `Buffer` body.** It fails startup. Both
  chain phases act on string values, and a raw binary body has none — use `FileContentVerifier` on a
  multipart `FileUpload` part when the intent is to inspect uploaded content.
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
| `Set<GeneratedJaxRsApplicationRegistration>` | `@Multibinds`; INTERNAL generated-code contract, populated by the annotation processor with one entry per declared `jakarta.ws.rs.core.Application`; empty by default |
| `Set<GeneratedJaxRsResourceEntry>` | `@Multibinds`; INTERNAL generated-code contract, populated by the annotation processor with one entry per DI-eligible JAX-RS resource; empty by default |
| `Set<RouterMount>` | `@ElementsIntoSet`: with no `Application` declared, the default `JaxRsRouterMount` at `jaxrs.basePath`, empty when `@JaxRsResources` is empty; once an `Application` is declared, one mount per active application instead — see [Jakarta REST Applications](#jakarta-rest-applications) |
| `MountCompositionValidator` (`JaxRsApplicationMountValidator`) | `@IntoSet`; INTERNAL; validates application mounts against hand-built JAX-RS mounts and cross-mount operationIds — see [Mount conflicts](#mount-conflicts) |
| `ComposeValidator` (`JaxRsDefaultProfileValidator`) | `@IntoSet`; fails the `VALIDATE` phase on an unknown `jaxrs.jsonProfile` (`json.systemProfile` is validated earlier, by the `CONFIGURE`-phase install step) |
| `OperationSchemaSource`, `BeanValidator`, `InputObjectProcessor` (`dev.vertique.input.processing.InputObjectProcessor`), `ActionRegistry`, `Authorizer` | `@BindsOptionalOf`; satisfied by `rest-validation`, `validation`, `sanitization`, and `rest-security` respectively |

`dev.vertique.rest.jaxrs.runtime.MagicBytesVerifierModule` is a separate opt-in `@Module` that
contributes the built-in magic-byte `FileContentVerifier`.

---

## Dependencies

| Dependency | Why |
|---|---|
| `dev.vertique:vertique-rest-core` | every extension SPI this runtime consumes, the `http`/`jaxrs` config objects, `ProblemDetail`, `BoundRequest`'s `RequestValue`, the parameter-conversion stack, and `RestCoreModule` |
| `dev.vertique:vertique-input-processing` | the neutral `InputObjectProcessor` / `EffectiveInputPolicies` contracts the body pipeline and the optional sanitization binding are typed against |
| `dev.vertique:vertique-security-core` | `SecurityContext` and the authorization references the security policy resolves against |
| `dev.vertique:vertique-json` | `JsonMapperProfileRegistry`, `JsonConfig` (its `effectiveProfile()` is the `vertique` floor), and `JsonRuntimeModule` for per-method profile resolution; `JacksonFieldNameResolver` — the `dev.vertique.core.sanitization.InputFieldNameResolver` implementation supplying the body wire-name projection input processing keys its policies on |
| `io.swagger.core.v3:swagger-annotations-jakarta` | `@Operation` / `@ApiResponse` read at scan time for the operationId and, at build time, by the spec generator |
| `org.projectlombok:lombok` | `provided` scope — logging and accessors; not a runtime dependency |

Vert.x, Dagger, the JAX-RS API, and Jackson arrive transitively through `vertique-rest-core`.
