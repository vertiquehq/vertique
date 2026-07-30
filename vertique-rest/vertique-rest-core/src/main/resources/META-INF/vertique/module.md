<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Core Module

> **Status:** Implemented
> **Package:** `dev.vertique.rest.core` (+ 17 sub-packages)
> **Artifact:** `vertique-rest-core`
> **Depends on:** core, context, correlation, logging, security-core

`vertique-rest-core` is the extension contract for Vertique's HTTP layer. It owns the HTTP server
verticle and its mount-composition algorithm, the per-request ordering spine, the configuration
objects for the server, and the transport-neutral interfaces that every other REST module and every
application extension registers against — middlewares, interceptors, body decoders and encoders,
context resolvers, parameter converters, security-scheme handlers, and request-completion listeners.

It is not a routing runtime. JAX-RS annotation scanning, request binding, dispatch, and response
serialization live in `dev.vertique:vertique-rest-jaxrs`. This module also carries no OpenAPI
dependency: route registration is expressed through neutral types (`RouterSetup`,
`RouteRegistration`, `SecuritySchemeRegistry`, `RestOperationDescriptor`) so an extension compiled
against it does not bind to a specific contract-validation implementation.

---

## When To Use It

Applications almost never depend on this artifact directly. Including
`dev.vertique.starter.rest.RestApplicationModule` (from `dev.vertique:vertique-starter-rest`) or
`dev.vertique.rest.jaxrs.RestModule` pulls it in transitively, because `RestModule` includes
`RestCoreModule`.

Declare an explicit dependency when you:

- implement a framework extension point — a `Middleware`, interceptor, `RequestBodyDecoder`,
  `ResponseBodyEncoder`, `ParamConverter`, `RestContextResolver`, `OperationHandlerContributor`, or
  `RestRequestCompletedListener`;
- mount a non-JAX-RS sub-router (static assets, a health tree, a hand-written Vert.x router) beside
  the JAX-RS mount via `RouterMount`;
- return `ProblemDetail` bodies, pagination envelopes, or SSE streams from resource methods; or
- build a library that must compile against the REST contract without depending on the JAX-RS
  runtime.

Pairs with `dev.vertique:vertique-rest-jaxrs` (routing and dispatch),
`dev.vertique:vertique-rest-security` (authentication, authorization, identity),
`dev.vertique:vertique-rest-validation` (the default request-validation gate), and
`dev.vertique:vertique-rest-websocket`.

---

## Core Concepts

### Mount composition

One `HttpVerticle` owns the main Vert.x `Router`. Everything reachable over HTTP arrives as a
`RouterMount` — a sub-router attached at a path prefix. The JAX-RS mount is one such mount; static
handlers, health endpoints, or a hand-built router are peers of it, not special cases.

`HttpVerticle` starts in this order, and each step is observable:

1. **Sort mounts** — `phase` → `priority()` → `mountPath()` → `orderKey()`. The `mountPath` tie-break
   sits before `orderKey` so that, at equal phase and priority, `/api/*` mounts ahead of a broader
   `/*` catch-all.
2. **Validate mount paths** — every violation is collected and the start promise fails with one
   aggregated message. Because validation runs *after* the sort, violations are reported in mounted
   order.
3. **Detect overlaps** — duplicate paths and prefix containment log a warning; startup continues.
4. Sort `MountCustomizer`s by the plain `OrderedExtension` comparator.
5. Create the main router and attach every `ROOT`-scoped `Middleware` at its own `path()`.
6. Run `BEFORE_MOUNTS` `RouterCustomizer`s.
7. Create each mount's router **sequentially**, apply every matching `MountCustomizer`, attach as a
   sub-router. Creation is sequential precisely so mount order equals the sorted order.
8. Run `AFTER_MOUNTS` `RouterCustomizer`s.
9. Bind the server, then publish the bound port into
   `vertx.sharedData().getLocalMap("vertique")` under the key `http.port`.

A failed `createRouter(...)` future fails startup.

### Extension ordering

Every ordered extension in this module implements `dev.vertique.core.extension.OrderedExtension` and
sorts by **phase → priority → orderKey**. Phase dominates priority: a `SYSTEM_FIRST` extension always
precedes an `APPLICATION` one regardless of numeric priority. `RouterMount` adds the `mountPath`
tie-break described above; nothing else does.

Two interfaces deliberately re-declare `priority()` as **abstract**, suppressing the
`OrderedExtension` default so no implementation lands in a band by accident:

- `Middleware`
- `OperationHandlerContributor`

### Middleware scope

`Middleware.scope()` selects where a handler is attached:

| Scope | Attached by | Applies to |
|---|---|---|
| `ROOT` (default) | `HttpVerticle`, on the main router at `path()` | every request |
| `API` | the JAX-RS mount in `vertique-rest-jaxrs` | validated JAX-RS routes only |

`HttpVerticle` mounts only `ROOT`. A custom non-JAX-RS `RouterMount` silently drops `API`-scoped
middlewares — if a handler must run for such a mount, give it `ROOT` scope and a narrower `path()`.

### The per-request spine

`RequestContextLifecycle` is a `ROOT` middleware at phase `SYSTEM_FIRST`, priority
`Integer.MIN_VALUE`. It is the single owner of per-request cleanup. It registers exactly one Vert.x
end handler, first — and because Vert.x Web fires `addEndHandler` callbacks in **reverse**
registration order, its cleanup runs **last**. Every value bound during the request (security
context, MDC keys, correlation) therefore stays readable by every other end handler.

The contract that follows from this: **no other component calls `ctx.addEndHandler(...)` for
cleanup.** Register on the `Handle` instead.

```java
RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(ctx);
lifecycle.onClose(securityRuntime.bindCurrent(securityContext)); // closed LIFO
lifecycle.bindMdc(Map.of(MdcKeys.USER_ID, userId));              // MDC scope, closed with the rest
lifecycle.afterClose(() -> metrics.recordRequest());             // runs FIFO, after every onClose
ctx.next();
```

Framework middlewares occupy these positions:

| Middleware | Phase | Priority | Scope |
|---|---|---|---|
| `RequestContextLifecycle` | `SYSTEM_FIRST` | `Integer.MIN_VALUE` | ROOT |
| `RestRequestCompletionEmitter` | default | `RequestContextLifecycle.ORDER + 5` | ROOT |
| `CorrelationIngressMiddleware` | default | `RequestContextLifecycle.ORDER + 10` | ROOT |
| `ContextualLoggingMiddleware` | default | `0` | ROOT |
| `DefaultHeadersMiddleware` | default | `10` | ROOT |
| `ContentTypeValidationMiddleware` | default | `20` | API |

`RequestContextLifecycle.ORDER` and `CorrelationIngressMiddleware.ORDER` are `public` and may be used
as anchors. An application middleware that must observe an authenticated identity belongs at a
positive priority.

### Request completion

Exactly one `RestRequestCompletedEvent` is published per HTTP request, carrying method, path, route
template, operation id, status, timing, an optional security snapshot, an optional correlation
snapshot, and the request origin. Two deliberate properties:

- A **successful protocol upgrade emits no event** — the 101 is written without firing the response
  end handler. A *failed* upgrade does emit, because it takes the normal error path.
- `safeFailureMessage` is always `null`, and `failureCode` is a class simple name only. Raw exception
  messages can carry SQL text, upstream detail, or PII, so they never reach the event.

### Neutral route registration

`OperationHandlerContributor` is how a module injects a handler into a single operation's chain.
Contributors receive an `OperationRegistrationContext` exposing the operation id, the resolved
`SecurityPolicy`, an optional `ActionRef`, the `RestOperationDescriptor`, and a `RouteRegistration`
to append handlers to. The terminal operation invoker is always appended **after** every
contributor.

`RestOperationDescriptor.effectiveSecurityPolicy()` is the always-on fail-closed security gate: it
calls `EffectiveSecurityPolicy.enforceSupportedShape(...)` before folding, so simply reading the
effective policy throws `RestConfigurationException` for an unsupported declaration shape whether or
not the optional validator from `vertique-rest-security` is wired.

---

## Key Classes

### `ProblemDetail`

RFC 9457 problem-details response body — the default error shape for every built-in exception mapper.
All fields are optional and omitted from JSON when `null`. Extension members declared through
`extension(...)` are serialized as sibling JSON fields.

```java
ProblemDetail simple = ProblemDetail.of(404, "Item 123 not found");
ProblemDetail withInstance = ProblemDetail.of(404, "Item 123 not found", "/items/123");

ProblemDetail detailed = ProblemDetail.builder()
        .type("https://api.example.com/problems/quota-exceeded")
        .title("Quota Exceeded")
        .status(429)
        .detail("Daily quota of 100 requests exhausted")
        .extension("limit", 100)
        .extension("resetsAt", "2026-01-01T00:00:00Z")
        .build();
```

`ProblemDetail.of(int, String)` derives `type` `"about:blank"` and a title from the status code
(`ProblemDetail.titleForStatus(int)` exposes the same mapping).

A typed subclass uses `@SuperBuilder` and must repeat the Jackson annotations, because
`ProblemDetail` is `@Accessors(fluent = true)` and getter-based serialization must stay suppressed:

```java
package com.example.api;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.vertique.rest.core.ProblemDetail;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@Accessors(fluent = true)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonAutoDetect(fieldVisibility = ANY, getterVisibility = NONE)
public class QuotaProblemDetail extends ProblemDetail {

    int limit;

    public static QuotaProblemDetail of(int limit) {
        return QuotaProblemDetail.builder()
                .type("https://api.example.com/problems/quota-exceeded")
                .title("Quota Exceeded")
                .status(429)
                .detail("Daily quota of " + limit + " requests exhausted")
                .limit(limit)
                .build();
    }
}
```

### `ValidationProblemDetail` and `ValidationErrorDetail`

`ValidationProblemDetail` extends `ProblemDetail` with an `errors` list.
`ValidationProblemDetail.of(String detail, List<ValidationErrorDetail> errors)` produces a 400
`about:blank` / `"Bad Request"` body.

```java
public record ValidationErrorDetail(
        String path,                        // violated field path, e.g. "email"
        String detail,                      // human-readable failure description
        @Nullable String location,          // "body", "query", "header", "path", "cookie", "form", "file"
        @Nullable String type,              // classification: "required", "size", "min", "pattern", …
        @Nullable Map<String, Object> args  // constraint arguments, e.g. {"min":1,"max":100}
) {
    public static ValidationErrorDetail of(String path, String detail) { … }
}
```

`null` components are omitted from JSON. `args` is populated for standard Jakarta constraints by the
`ViolationArgsInspector` SPI in `dev.vertique:vertique-validation`.

### `RestValidationException`

Extends `dev.vertique.core.exception.ValidationException` with a structured, unmodifiable
`List<ValidationErrorDetail>` (`errors()`). Throw it to produce a 400 with a
`ValidationProblemDetail` body.

### `RequestPreconditions`

Conditional-request evaluation (`If-Match`, `If-None-Match`, `If-Modified-Since`,
`If-Unmodified-Since`) per RFC 9110. Inject it as a resource-method parameter, or build it from a
routing context with `RequestPreconditions.from(ctx)` — the instance is cached per request.

```java
@GET
@Path("/items/{id}")
public Response getItem(@PathParam("id") String id, RequestPreconditions preconditions) {
    Item item = repository.find(id);
    Response notModified = preconditions.evaluate(new EntityTag(item.version()), item.updatedAt());
    if (notModified != null) {
        return notModified;                       // 304 or 412, already built
    }
    return Response.ok(item).tag(new EntityTag(item.version())).build();
}
```

`evaluate(...)` returns `null` when the request should proceed, a 304 for a satisfied
`If-None-Match`/`If-Modified-Since` on GET or HEAD, and a 412 otherwise. `evaluate(Response)` reads
the `ETag` and `Last-Modified` off an already-built response.

### `@RequestParams`

Class-level equivalent of JAX-RS `@BeanParam`. A method parameter whose *type* carries
`@RequestParams` is populated from the request with no annotation on the parameter itself. Fields may
carry `@QueryParam`, `@PathParam`, `@HeaderParam`, `@CookieParam`, `@FormParam`, and `@DefaultValue`.

```java
@RequestParams
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemFilter(
        @QueryParam("status") @Nullable String status,
        @QueryParam("tenantId") @Nullable String tenantId,
        @HeaderParam("X-Request-Source") @Nullable String requestSource) {}

@GET
@Path("/items")
public Future<List<Item>> listItems(ItemFilter filter) { … }
```

### `@FilePart`

Declares multipart upload constraints on a resource parameter.

```java
public @interface FilePart {
    String[] allowedTypes() default {};   // exact "type/subtype" or whole-subtype wildcard "image/*"
    long maxSizeBytes() default -1;       // -1 = unconstrained; otherwise must be positive
}
```

Supported shapes are a named `@FormParam FileUpload`, a named `@FormParam List<FileUpload>`, and an
unannotated aggregate `List<FileUpload>`. `EntityPart` is excluded because a part may be a text field
the upload gate cannot observe. Invalid placement, invalid allowed-type grammar, a size other than
`-1` or positive, and overlapping constrained declarations all fail route startup.

Size enforcement is **post-spool**: `http.maxBodySize` is the ingress size limit that returns 413,
while `maxSizeBytes` is checked after Vert.x has written the part under `http.uploadsDirectory`.
Part *count* is bounded separately at ingress by `http.maxFormFields`, so a request with more parts
than that is rejected during decoding whatever their individual sizes.

### Pagination

Two response envelopes and their matching `@RequestParams` records.

| | Offset (`OffsetPage`) | Cursor (`CursorPage`) |
|---|---|---|
| Navigation | random access by page number | sequential (next/previous) |
| Total count | exposed (`totalItems`, `totalPages`) | not exposed |
| Ordering stability | drifts when rows are inserted or deleted | stable |
| Cost at depth | `OFFSET N` degrades | constant, via keyset pagination |
| Best for | admin UIs, page-numbered lists | feeds, timelines, high-volume APIs |

```java
public record OffsetPage<T>(
        List<T> items, long totalItems, int totalPages, int page, int pageSize,
        boolean first, boolean last) {

    public static <T> OffsetPage<T> of(List<T> items, long totalItems, int page, int pageSize) { … }
}

public record CursorPage<T>(List<T> items, @Nullable String nextCursor, @Nullable String previousCursor) {

    public boolean hasMore();        // @JsonIgnore
    public boolean hasPrevious();    // @JsonIgnore

    public static <T> CursorPage<T> of(
            List<T> items, @Nullable String nextRawCursor, @Nullable String prevRawCursor, CursorCodec codec) { … }
}
```

`OffsetPageRequest` reads `page`, `size`, and `sort`; `CursorPageRequest` reads `cursor` and
`pageSize`. Both clamp defensively — `page(int)` floors at `0`, `pageSize(int)` floors at `1`, and
`pageSize(int, int)` additionally caps at the supplied maximum. `OffsetPageRequest.sortOrders()`
parses `sort=name,desc,createdAt` into `SortOrder(field, ASC|DESC)` entries, where a bare `asc`/`desc`
token applies to the preceding field.

```java
@GET
@Path("/items")
public Future<CursorPage<Item>> listItems(CursorPageRequest request) {
    int size = request.pageSize(20, 100);
    String after = request.decodeCursor(cursorCodec).orElse(null);
    return repository.findAfter(after, size)
            .map(rows -> CursorPage.of(rows.items(), rows.nextKey(), rows.previousKey(), cursorCodec));
}
```

Encode cursors with `HmacCursorCodec` on any endpoint whose cursor encodes internal keys — it signs
the token, supports key rotation by id, and enforces an optional TTL. `PlainCursorCodec.INSTANCE` is
the pass-through default used by the codec-less `CursorPage.of`/`decodeCursor` overloads.

```java
CursorCodec codec = new HmacCursorCodec("2026-01", secret, Duration.ofHours(1));
```

Each `HmacCursorCodec.Key` id must match `[A-Za-z0-9_-]+`, and each secret must be at least 32 bytes
UTF-8 encoded. The first key in the list signs; every key can verify. A tampered, unknown-key, or
expired token raises `InvalidCursorException`.

### Server-Sent Events

`SseChannelFactory` is injectable; the resource method returns `channel.stream()`. The framework
detects a `ReadStream<SseEvent>` return type at startup and installs the SSE encoder — no extra
configuration.

```java
@Path("/jobs")
public class JobResource {

    private final JobService jobs;
    private final SseChannelFactory channels;

    @Inject
    public JobResource(JobService jobs, SseChannelFactory channels) {
        this.jobs = jobs;
        this.channels = channels;
    }

    @GET
    @Path("/{jobId}/events")
    @Produces("text/event-stream")
    public ReadStream<SseEvent> streamJobEvents(@PathParam("jobId") String jobId) {
        SseChannel channel = channels.create();
        channel.onClose(() -> jobs.unsubscribe(jobId));
        jobs.subscribe(jobId, update -> {
            channel.send(SseEvent.builder().id(update.sequence()).event(update.type()).data(update).build());
            if (update.isFinal()) {
                channel.complete();
            }
        });
        return channel.stream();
    }
}
```

```java
public interface SseChannel {
    ReadStream<SseEvent> stream();
    Future<Void> send(SseEvent event);
    Future<Void> send(Object data);
    Future<Void> send(String event, Object data);
    void complete();
    void fail(Throwable cause);
    boolean isClosed();
    SseChannel onClose(Runnable handler);
}
```

`SseEvent` has five optional properties — `id`, `event`, `data`, `comment`, `retryMs` — built with
`SseEvent.builder()`, or `SseEvent.of(Object data)` / `SseEvent.comment(String text)`. `data` is an
arbitrary object serialized by the SSE encoder, not a pre-rendered string.

`create(SseChannelOptions)` overrides the buffer for one channel;
`new SseChannelOptions(int bufferSize, BufferOverflowPolicy overflowPolicy)` requires
`bufferSize >= 1` and a non-null policy, and `SseChannelOptions.defaults()` returns `(256, FAIL)`.
`BufferOverflowPolicy.FAIL` fails the `send(...)` future when the buffer is full;
`DROP_OLDEST` evicts the oldest buffered event instead.

### `MediaType` and `AcceptNegotiator`

`MediaType` is an immutable RFC 9110 media type — type, subtype, parameters (excluding `q`), and
quality factor, all lowercased. `MediaType.parse(String)` (aliased as `valueOf`) returns `null` for
`null`, blank, or malformed input rather than throwing. `isCompatible(MediaType)` is wildcard-aware
and ignores parameters; `specificity()` returns 0 for `*/*`, 1 for `type/*`, 2 for `type/subtype`, and
3 when parameters are present. Equality ignores the quality factor.

`AcceptNegotiator.negotiate(String acceptHeader, List<String> serverTypes)` returns the best matching
server type as `"type/subtype"`, or `null` when nothing matches — the signal a caller turns into a
406. A `null`/blank Accept header yields the first server type; an empty `serverTypes` yields `null`.
`parseAcceptHeader(String)` returns the header sorted by q-value then specificity, both descending,
capped at 50 entries.

### `MdcKeys`

Constants for the MDC keys framework middlewares emit. Use them instead of literals so log pipelines
do not drift.

| Constant | Key | Emitted by |
|---|---|---|
| `REQUEST_ID` | `requestId` | `CorrelationIngressMiddleware` |
| `METHOD` | `method` | `ContextualLoggingMiddleware` |
| `PATH` | `path` | `ContextualLoggingMiddleware` |
| `USER_ID` | `userId` | identity resolution in `vertique-rest-security` |
| `CLIENT_ID` | `clientId` | identity resolution in `vertique-rest-security` |
| `AUTH_METHOD` | `authMethod` | identity resolution in `vertique-rest-security` |

### `@Authorized`

Scope-based authorization, complementing JAX-RS `@RolesAllowed`. Valid on a method or a type;
method-level overrides class-level.

```java
public @interface Authorized {
    String[] scopes() default {};   // empty = authentication only
    boolean matchAll() default true;
}
```

**`matchAll` defaults to `true`** — the principal must hold *every* listed scope. Set
`matchAll = false` for any-of semantics. Combining `@Authorized` with `@RolesAllowed` is AND: both
must pass.

The resolved shape is a `SecurityPolicy` — a sealed interface with `None`, `PermitAll`, `DenyAll`,
`AuthenticatedOnly`, and `Constrained(requiredRoles, requiredScopes, requireAllScopes)` — reachable
from `OperationRegistrationContext.securityPolicy()` and
`RestOperationDescriptor.effectiveSecurityPolicy()`.

### `JaxRsResources`

Dagger qualifier for the `Set<Object>` multibinding of JAX-RS resource instances. Generated resource
registration contributes into it; a hand-wired resource uses it directly.

```java
@Provides
@IntoSet
@JaxRsResources
static Object itemResource(ItemResource resource) {
    return resource;
}
```

---

## Extension Points

All sets below are declared as `@Multibinds` in `RestCoreModule`, so contributing is always
`@Provides @IntoSet` — no set needs to be created first, and an application that contributes nothing
still builds.

### `RouterMount`

Provides a sub-router mounted at a path prefix.

```java
public interface RouterMount extends OrderedExtension {
    default String mountPath() { return "/*"; }
    Future<Router> createRouter(Vertx vertx);
    default MountMeta meta() { return new MountMeta(getClass().getName(), mountPath(), null, Set.of()); }
}
```

`mountPath()` must start with `/` and end with `/*`. `meta()` supplies the identity
`MountCustomizer`s match on: `MountMeta(String mountId, String mountPath, @Nullable String openapiPath,
Set<Class<?>> resourceTypes)`. Override it to publish a stable `mountId`.

```java
@Provides
@IntoSet
static RouterMount staticAssets() {
    return new RouterMount() {
        @Override public String mountPath() { return "/assets/*"; }
        @Override public int priority() { return 100; }
        @Override public Future<Router> createRouter(Vertx vertx) {
            Router router = Router.router(vertx);
            router.route().handler(StaticHandler.create("webroot"));
            return Future.succeededFuture(router);
        }
    };
}
```

### `MountCustomizer`

Runs after a mount's router is created and before it is attached.

```java
public interface MountCustomizer extends OrderedExtension {
    default boolean matches(MountMeta meta) { return true; }
    void customize(Router mountRouter, MountMeta meta);
}
```

```java
@Provides
@IntoSet
static MountCustomizer apiRateLimit(RateLimitHandler handler) {
    return new MountCustomizer() {
        @Override public boolean matches(MountMeta meta) { return meta.mountId().startsWith("jaxrs:"); }
        @Override public void customize(Router router, MountMeta meta) { router.route().handler(handler); }
    };
}
```

### `RouterCustomizer`

Customizes the **main** router. `mountPhase()` partitions customizers into two groups run before and
after all sub-routers are attached; within a group, ordering is the standard comparator.

```java
public interface RouterCustomizer extends OrderedExtension {
    void customize(Router router);
    default MountPhase mountPhase() { return MountPhase.BEFORE_MOUNTS; }

    enum MountPhase { BEFORE_MOUNTS, AFTER_MOUNTS }
}
```

```java
@Provides
@IntoSet
static RouterCustomizer spaFallback() {
    return new RouterCustomizer() {
        @Override public MountPhase mountPhase() { return MountPhase.AFTER_MOUNTS; }
        @Override public void customize(Router router) {
            router.get("/*").handler(StaticHandler.create("webroot").setIndexPage("index.html"));
        }
    };
}
```

Framework CORS is already contributed as a `BEFORE_MOUNTS` customizer driven by the `cors` config
section — configure it rather than adding a second CORS handler.

### `Middleware`

```java
public interface Middleware extends Handler<RoutingContext>, OrderedExtension {
    @Override int priority();                                   // abstract: must be declared
    default MiddlewareScope scope() { return MiddlewareScope.ROOT; }
    default String path() { return "/*"; }
}
```

```java
@Singleton
public final class TenantMiddleware implements Middleware {

    @Inject
    public TenantMiddleware() {}

    @Override
    public int priority() {
        return 50;   // after identity resolution
    }

    @Override
    public void handle(RoutingContext ctx) {
        RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(ctx);
        lifecycle.bindMdc(Map.of("tenantId", ctx.request().getHeader("X-Tenant-Id")));
        ctx.next();
    }
}
```

```java
@Provides
@IntoSet
@Singleton
static Middleware tenantMiddleware(TenantMiddleware middleware) {
    return middleware;
}
```

### `RouterLifecycleHook`

Phase hooks around router construction. Every method has a no-op default.

```java
public interface RouterLifecycleHook extends OrderedExtension {
    default void beforeAuthSetup(RouterSetup setup) {}
    default void afterAuthSetup(RouterSetup setup) {}
    default void afterRouterCreated(Router router) {}
}
```

`RouterSetup` exposes `router()` and `security()` (a `SecuritySchemeRegistry`).

### `OperationHandlerContributor`

Appends a handler to one operation's chain during route registration.

```java
public interface OperationHandlerContributor extends OrderedExtension {
    @Override int priority();                       // abstract: must be declared
    void contribute(OperationRegistrationContext context);
}
```

```java
@Provides
@IntoSet
static OperationHandlerContributor quotaGate(QuotaService quotas) {
    return new OperationHandlerContributor() {
        @Override public int priority() { return 200; }
        @Override public void contribute(OperationRegistrationContext context) {
            String operationId = context.operationId();
            context.route().addHandler(rc -> {
                if (quotas.allows(operationId)) {
                    rc.next();
                } else {
                    rc.fail(429);
                }
            });
        }
    };
}
```

Framework contributors occupy these priorities; choose a band that does not collide, and place any
contributor that reads an authenticated identity above 100:

| Priority | Contributor | Module |
|---:|---|---|
| 40 | action-gate authentication | `vertique-rest-security` |
| 50 | JWT claims validation | `vertique-rest-auth-jwt` |
| 80 | identity resolution | `vertique-rest-security` |
| 100 | authorization | `vertique-rest-security` |
| 350 | operation-id capture | `vertique-rest-core` |
| 360 | server-span enrichment | `vertique-opentelemetry-rest` |

The terminal operation invoker is appended after every contributor, so a contributor always runs
before the resource method.

`OperationRegistrationContext` carries `operationId()`, `securityPolicy()`,
`requiredAction()` (`Optional<ActionRef>`), `operation()` (`RestOperationDescriptor`), and `route()`
(`RouteRegistration`, whose `addHandler(...)` returns itself for chaining).

### `RequestInterceptor`, `OperationInterceptor`, `ErrorInterceptor`

Three pipelines with distinct scopes. Every callback has a default, so implement only what you need.

```java
public interface RequestInterceptor extends OrderedExtension {
    String ORIGINAL_ERROR_KEY = "dev.vertique.rest.originalError";
    String VERTX_STATUS_CODE_KEY = "dev.vertique.rest.vertxStatusCode";

    default void onRequest(RoutingContext rc) {}
    default void onError(RoutingContext rc, Throwable error) {}
    default void onSerialize(RoutingContext rc, Response response, SerializedBody body) {}
    default void afterResponse(RoutingContext rc, Response response) {}
    default Future<Void> beforeRequest(RoutingContext rc) { return Future.succeededFuture(); }
    default Future<Response> transformResponse(RoutingContext rc, Response response) { … }
}

public interface OperationInterceptor extends OrderedExtension {
    default void onOperation(OperationContext ctx) {}
    default void onSuccess(OperationContext ctx, Object result) {}
    default void onError(OperationContext ctx, Throwable cause) {}
    default Future<OperationContext> beforeOperation(OperationContext ctx) { … }
    default Future<Object> afterOperation(OperationContext ctx, Object result) { … }
    default Future<Object> recoverOperation(OperationContext ctx, Throwable cause) { … }
}

public interface ErrorInterceptor extends OrderedExtension {
    default Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) { … }
    default Future<Response> afterMapping(RoutingContext rc, Response response) { … }
}
```

`OperationContext` is immutable — `operationId()`, `routingContext()`, `methodAnnotations()`,
`classAnnotations()`, `attributes()`, the typed lookups `methodAnnotation(Class)` /
`classAnnotation(Class)`, and `withAttribute(String, Object)`, which returns a **new** context.
Return that new instance from `beforeOperation` or the attribute is lost.

`recoverOperation` defaults to re-failing; returning a succeeded future turns a failure into a
result. `ORIGINAL_ERROR_KEY` and `VERTX_STATUS_CODE_KEY` name the routing-context entries that carry
a pre-mapping throwable and a Vert.x-originated status code.

### `RequestBodyDecoder` and `ResponseBodyEncoder`

Content-type-driven body handling. The framework walks each set in `OrderedExtension` order and uses
the first implementation whose `canDecode` / `canEncode` returns `true`.

```java
public interface RequestBodyDecoder extends OrderedExtension {
    boolean canDecode(Class<?> targetType, String contentType);
    default Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType, Type genericType) { … }
    default Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType) { … }
}

public interface ResponseBodyEncoder extends OrderedExtension {
    boolean canEncode(Class<?> entityType, String contentType);
    SerializedBody encode(RoutingContext ctx, Response response, Object entity);
}
```

Both `decode` overloads have defaults that delegate to each other — **override exactly one**, or
every call throws `UnsupportedOperationException`. Override the four-argument form when the target is
generic (`List<Item>`); the three-argument form otherwise.

Framework implementations sit at priorities 999–1100 (`SseBodyEncoder` 999, binary/text/form/stream
1000, JSON 1100). Application implementations default to priority `0` and therefore win by default —
give one a priority above 1100 to act as a fallback instead.

`RequestValue` is the neutral accessor for the bound value (`getString()`, `getJsonObject()`,
`getBuffer()`, `get()`, typed defaults, and `isX()` predicates). `SerializedBody` is sealed over
`BufferedBody(Buffer, String contentType, Long contentLength)` and
`StreamingBody(ReadStream<Buffer>, String contentType, Long contentLength)`.

```java
@Provides
@IntoSet
static RequestBodyDecoder csvDecoder() {
    return new RequestBodyDecoder() {
        @Override public boolean canDecode(Class<?> targetType, String contentType) {
            return contentType != null && contentType.startsWith("text/csv");
        }
        @Override public Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType) {
            return CsvReader.read(body.getBuffer(), targetType);
        }
    };
}
```

A decoder that materializes a DTO from a structured intermediate can route it through the
framework's canonicalization/sanitization traversal by injecting the optional
`InputObjectProcessor` and calling
`processStructuredBody(Object intermediateBody, Type targetType, EffectiveInputPolicies policies,
InputLocation location)` before final binding. The binding is `@BindsOptionalOf`; it resolves only
when `dev.vertique:vertique-sanitization` is on the graph.

### `ResponseProducer` and `ResponseProducerBinding`

Maps a domain return type to a `jakarta.ws.rs.core.Response` before serialization.

```java
@FunctionalInterface
public interface ResponseProducer<T> {
    Response produce(RoutingContext ctx, T result);
}

public record ResponseProducerBinding<T>(Class<T> type, ResponseProducer<T> producer) {}
```

```java
@Provides
@IntoSet
static ResponseProducerBinding<?> createdProducer() {
    return new ResponseProducerBinding<>(
            Created.class,
            (ctx, created) -> Response.created(URI.create(created.location())).entity(created.body()).build());
}
```

The produced `Response` still passes through `transformResponse` interceptors.

### `ResponseSerializer`

```java
public interface ResponseSerializer {
    Future<Void> serialize(RoutingContext ctx, Response response);
}
```

The terminal wire-completion contract. The implementation ships in `vertique-rest-jaxrs`; replace it
only to take over serialization orchestration entirely. It is called on the request's event-loop
context after the status and headers are written and after `transformResponse` hooks have run —
empty-body and bare fallback paths bypass it. An implementation must not block the calling thread.

Completion is dual-channel, and the two channels mean different things:

| Signal | Meaning | Caller behavior |
|---|---|---|
| synchronous throw | nothing was written or ended — an encode-time failure | may retry once against the same response head (fail-open, FR-JSON-058A) |
| returned future succeeds | the response was fully written and ended | done |
| returned future fails | the wire write failed after handoff; zero or more bytes may already be on the wire | never retried; the caller owns terminal cleanup |

The returned future may complete on any thread — do not assume context affinity; the framework
pipeline redispatches handling back onto the request context. A `StreamingBody` must be piped, never
buffered (FR-RESTSER-013 / NFR-003), and the response must not be ended on pipe failure.

### `RestContextResolver`

The single resolution path for `@Context`-injectable resource parameters. Register one to make a new
type injectable.

```java
public interface RestContextResolver extends OrderedExtension {
    int PRIORITY_ROUTING_CONTEXT = 100;
    int PRIORITY_JAXRS_SECURITY_CONTEXT = 110;
    int PRIORITY_CONTEXT_HOLDER = 120;

    <T> Optional<T> resolve(Class<T> type, RoutingContext ctx);
}
```

Built-in resolvers occupy those three priorities: `RoutingContext` and subtypes at 100,
`jakarta.ws.rs.core.SecurityContext` at 110 (empty when no security module is installed), and any
`ContextValue` subtype at 120. A resolver must return `Optional.empty()` for types it does not own —
the first non-empty result wins.

An application resolver takes the default phase and priority `0`, so it already runs **ahead of
every built-in** and may shadow a framework-provided value for the same type. Give it a priority
above 120 to act as a fallback instead.

**Contract (FR-REST-172):** `resolve` is a pure read. An implementation must not create, mutate,
enrich, replace, or propagate context as a side effect — it observes request state and reports back.

`RestContextResolution` is the injectable coordinator: `resolve(Class, RoutingContext)` returns an
`Optional`, and `require(Class, RoutingContext, String resourceClass, String methodName)` throws
`RestContextUnavailableException` when nothing matches. The chain is sorted once at construction.

### `ParamConverter` and `ParamConverterBinding`

Symmetric string conversion for `@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, and
`@FormParam` — the same converter parses inbound values and serializes outbound ones for the REST
client.

```java
public interface ParamConverter<T> {
    T fromString(String value);
    String toString(T value);
}

public record ParamConverterBinding<T>(Class<T> targetType, ParamConverter<T> converter) {}
```

```java
@Provides
@IntoSet
static ParamConverterBinding<?> isbnConverter() {
    return new ParamConverterBinding<>(Isbn.class, new ParamConverter<Isbn>() {
        @Override public Isbn fromString(String value) { return Isbn.parse(value); }
        @Override public String toString(Isbn value) { return value.normalized(); }
    });
}
```

Implementations must be stateless and thread-safe — one instance serves every request. Built-in
converters cover `String`, every primitive and its box, `BigInteger`, `BigDecimal`, `UUID`, `URI`,
and the `java.time` types `Instant`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`,
`OffsetTime`, `ZonedDateTime`, `Duration`, `Period`, `Year`, `YearMonth`, `MonthDay`, `ZoneId`, and
`ZoneOffset`. Any `enum` is converted by name automatically with no registration.

A binding for a built-in type replaces it. Two bindings for the same target type fail component
construction with `IllegalStateException`.

`ParamConversionResolver` tries the native registry first, then any contributed
`jakarta.ws.rs.ext.ParamConverterProvider` (also a `@Multibinds` set), ordered by `@Priority`
ascending. Its `ConversionContext(String paramName, ParamSource source, Class<?> rawType,
@Nullable Type genericType, @Nullable Class<?> componentType, Supplier<Annotation[]> annotationsLazy)`
carries `dev.vertique.rest.core.convert.ParamSource`, whose five constants are `PATH`, `QUERY`,
`HEADER`, `COOKIE`, and `FORM` — the string-ish transport kinds. Do not confuse it with the
same-named but unrelated parameter-source enums in the JAX-RS and REST-client modules.

### `RestRequestCompletedListener` and `RequestCompletionScope`

Observe every completed request from an immutable snapshot, with no routing context in hand.

```java
public interface RestRequestCompletedListener {
    void onCompleted(RestRequestCompletedEvent event);
}

public interface RequestCompletionScope {
    AutoCloseable open(RoutingContext rc);
}
```

```java
@Provides
@IntoSet
static RestRequestCompletedListener requestMetrics(MeterRegistry registry) {
    return event -> registry.counter(
                    "http.server.requests",
                    "method", event.method(),
                    "route", event.routeTemplate() == null ? "unmatched" : event.routeTemplate(),
                    "status", Integer.toString(event.statusCode()))
            .increment();
}
```

```java
public record RestRequestCompletedEvent(
        Instant startTime, Instant endTime,
        String method, String path,
        @Nullable String routeTemplate, @Nullable String operationId,
        int statusCode,
        @Nullable String failureCode, @Nullable String safeFailureMessage, @Nullable String wireFailureCode,
        @Nullable SecurityContextSnapshot securityContextSnapshot,
        @Nullable CorrelationContextSnapshot correlationContext,
        Optional<RequestOrigin> origin,
        Map<String, Object> safeAttributes) {}
```

A `RequestCompletionScope` wraps listener dispatch — scopes open in iteration order and close in
reverse, which is how tracing modules re-establish a span around emission. A listener that throws an
`Exception` is logged at WARN and does not stop the remaining listeners; an `Error` propagates.

The `dev.vertique.rest.core.capture` SPIs (`RestServerRequestEvidenceCapturer`,
`RestRequestCaptureCoordinator`) are the boundary-evidence hooks the audit adapter implements. If you
implement one, keep evidence in an implementation-private, identity-keyed side table — never in
`RoutingContext.data()`, which is keyed by public string constants and is readable and writable by
every component sharing the context.

### `SecuritySchemeHandler` and `RouteAuthHandler`

```java
public interface SecuritySchemeHandler {
    String schemeName();
    void configure(SecuritySchemeRegistry registry);
}

public interface SecuritySchemeRegistry {
    void authenticationHandler(AuthenticationHandler handler);
}
```

The framework applies the registered handler to every operation whose security requirements name
`schemeName()`. The registry takes a Vert.x `AuthenticationHandler` rather than a bare
`Handler<RoutingContext>` so multiple alternative requirements can be composed into a
`ChainAuthHandler.any()` — the OR semantics of an OpenAPI `security` array.

```java
@Provides
@IntoSet
static SecuritySchemeHandler apiKeyScheme(ApiKeyAuthProvider provider) {
    return new SecuritySchemeHandler() {
        @Override public String schemeName() { return "apiKey"; }
        @Override public void configure(SecuritySchemeRegistry registry) {
            registry.authenticationHandler(APIKeyHandler.create(provider));
        }
    };
}
```

`RouteAuthHandler` (`String schemeName()`, `Handler<RoutingContext> createHandler()`) is the
route-level variant; its multibinding is declared by `AuthModule` in `vertique-rest-security`.

`SecurityRuntime`, `SecurityPolicyResolver`, and `SecurityPolicyValidator` are declared here and
implemented in `vertique-rest-security`. Bind your own only to replace framework behavior wholesale.
`SecurityRuntime.bindCurrent(SecurityContext)` returns a `ContextHolder.Scope` that **must** be
registered with `RequestContextLifecycle.Handle.onClose(...)`.

### `ProtocolCorrelationSpec` and `ProtocolCorrelationContributor`

Teach correlation ingress about a protocol-specific header (for example
`X-FAPI-Interaction-ID`). `ProtocolCorrelationSpec` is the declarative route: name the header, the
response and propagation modes, whether the value is durable-safe, and how an inbound value is
accepted. `ProtocolCorrelationContributor` is the imperative escape hatch, resolving a
`ProtocolCorrelationRef` straight from the `HttpServerRequest`. Both are `@Multibinds` sets on
`CorrelationIngressModule`, which `RestCoreModule` includes.

### `CursorCodec`

```java
public interface CursorCodec {
    String encode(String rawCursor);
    String decode(String opaqueToken) throws InvalidCursorException;
}
```

Bind `HmacCursorCodec` (or your own) as a `@Singleton` and pass it to `CursorPage.of(...)` and
`CursorPageRequest.decodeCursor(...)`. It is an ordinary binding, not a multibinding.

---

## Configuration

Three top-level sections are parsed by `RestCoreModule` — `http`, `cors`, and `jaxrs` — plus
`correlation.ingress` by `CorrelationIngressModule`. Every key is optional; omitted keys take the
default below. Unknown keys are ignored except under `jaxrs.defaultHeaders`, where they become
custom response headers.

### `http`

| Key | Default | Constraint / notes |
|---|---:|---|
| `http.port` | `8080` | |
| `http.host` | `"0.0.0.0"` | |
| `http.maxBodySize` | `2097152` | total request-body bytes — exceeding it returns 413 |
| `http.uploadsDirectory` | `"file-uploads"` | must be non-blank; multipart spool directory |
| `http.compressionSupported` | `false` | gzip/deflate responses |
| `http.compressionLevel` | `6` | 1–9 |
| `http.decompressionSupported` | `false` | gzip/deflate request bodies |
| `http.maxHeaderSize` | `8192` | bytes, all headers combined |
| `http.maxInitialLineLength` | `4096` | bytes |
| `http.handle100ContinueAutomatically` | `false` | |
| `http.idleTimeoutSeconds` | `0` | `0` disables |
| `http.readIdleTimeoutSeconds` | `0` | `0` disables |
| `http.writeIdleTimeoutSeconds` | `0` | `0` disables |
| `http.tcpKeepAlive` | `false` | |
| `http.acceptBacklog` | `-1` | `-1` uses the OS default |
| `http.useProxyProtocol` | `false` | read the real client IP from an upstream proxy |
| `http.maxFormAttributeSize` | `8192` | bytes, per URL-encoded form value |
| `http.maxFormFields` | `256` | form parts per request; one shared limit across multipart file parts, multipart text parts, and URL-encoded attributes |

### `http.ssl`

When `enabled` is `false`, every other key in this section is ignored.

| Key | Default | Constraint / notes |
|---|---:|---|
| `http.ssl.enabled` | `false` | |
| `http.ssl.keyStorePath` | *(none)* | keystore file, or the PEM private key |
| `http.ssl.keyStorePassword` | *(none)* | |
| `http.ssl.keyStoreType` | `"JKS"` | `JKS`, `PKCS12`, or `PEM`; any other value falls back to JKS handling |
| `http.ssl.certPath` | *(none)* | PEM certificate; defaults to `keyStorePath` for a combined file |
| `http.ssl.trustStorePath` | *(none)* | client-certificate validation (mTLS) |
| `http.ssl.trustStorePassword` | *(none)* | |
| `http.ssl.trustStoreType` | `"JKS"` | `JKS`, `PKCS12`, or `PEM` |
| `http.ssl.clientAuth` | `"NONE"` | `NONE`, `REQUEST`, or `REQUIRED`; anything else fails startup |
| `http.ssl.enabledProtocols` | `["TLSv1.2","TLSv1.3"]` | |
| `http.ssl.useAlpn` | `false` | required for HTTP/2 |
| `http.ssl.sni` | `false` | |

### `cors`

When `enabled` is `false` (the default) no CORS handler is installed and every other key is ignored.

| Key | Default | Constraint / notes |
|---|---:|---|
| `cors.enabled` | `false` | |
| `cors.origins` | `["*"]` | exact origins or `"*"` |
| `cors.allowedMethods` | `["GET","POST","PUT","DELETE","PATCH","OPTIONS"]` | must be valid HTTP method names |
| `cors.allowedHeaders` | `["*"]` | |
| `cors.exposedHeaders` | `[]` | |
| `cors.allowCredentials` | `false` | |
| `cors.maxAge` | `3600` | seconds a browser may cache a preflight |

### `jaxrs`

| Key | Default | Constraint / notes |
|---|---:|---|
| `jaxrs.basePath` | `"/*"` | mount path of the JAX-RS sub-router |
| `jaxrs.openapiPath` | `"openapi.json"` | classpath spec; only used by the opt-in `openapi-contract` strategy |
| `jaxrs.mediaTypeValidation` | `"WARN"` | `WARN`, `STRICT` (fails startup on the first mismatch), or `OFF` |
| `jaxrs.validationStrategy` | `"web-validation"` | must match a registered strategy id — built-ins are `web-validation`, `none`, `openapi-contract`; an unknown id fails startup |
| `jaxrs.validationMode` | `"aggregate"` | `aggregate` or `failFast` |
| `jaxrs.autoEtag` | `false` | attach a weak ETag derived from the serialized body when none is set |
| `jaxrs.jsonProfile` | *(none)* | must name a registered JSON mapper profile; resolution is method `@JsonProfile` → class `@JsonProfile` → this key → `json.jsonProfile` → the reserved `vertx` profile |

### `jaxrs.defaultHeaders`

Applied to every response. Setting a known key to `null` or an empty string suppresses that header.
Any key that is not one of the five below is emitted verbatim as a custom header, and a custom entry
whose name collides with a known header wins.

| Key | Header | Default |
|---|---|---|
| `cacheControl` | `Cache-Control` | `"no-store"` |
| `contentTypeOptions` | `X-Content-Type-Options` | `"nosniff"` |
| `frameOptions` | `X-Frame-Options` | `"DENY"` |
| `strictTransportSecurity` | `Strict-Transport-Security` | *(not emitted)* |
| `referrerPolicy` | `Referrer-Policy` | *(not emitted)* |

### `jaxrs.sse`

| Key | Default | Constraint / notes |
|---|---:|---|
| `jaxrs.sse.keepAliveEnabled` | `true` | emit a periodic keep-alive comment on idle connections |
| `jaxrs.sse.keepAliveIntervalMs` | `15000` | |
| `jaxrs.sse.defaultBufferSize` | `256` | per-channel buffer when no `SseChannelOptions` are supplied |
| `jaxrs.sse.defaultOverflowPolicy` | `"FAIL"` | `FAIL` or `DROP_OLDEST` |

### `correlation.ingress`

| Key | Default | Constraint / notes |
|---|---:|---|
| `correlation.ingress.requestIdHeader` | `"X-Request-Id"` | must be a valid HTTP header token |
| `correlation.ingress.correlationIdHeader` | `"X-Correlation-Id"` | must be a valid HTTP header token |
| `correlation.ingress.causationIdHeader` | `"X-Causation-Id"` | must be a valid HTTP header token |
| `correlation.ingress.echoRequestId` | `true` | emit the request id as a response header |
| `correlation.ingress.echoCorrelationId` | `false` | emit the correlation id as a response header |
| `correlation.ingress.parseCausationId` | `false` | accept an inbound causation id |
| `correlation.ingress.invalidValuePolicy` | `"REPLACE_WITH_GENERATED"` | `REPLACE_WITH_GENERATED` or `REJECT` (returns 400) |

### Example

```json
{
  "http": {
    "port": 8443,
    "maxBodySize": 4194304,
    "idleTimeoutSeconds": 30,
    "ssl": {
      "enabled": true,
      "keyStorePath": "/etc/certs/server.p12",
      "keyStorePassword": "changeit",
      "keyStoreType": "PKCS12",
      "useAlpn": true
    }
  },
  "cors": {
    "enabled": true,
    "origins": ["https://app.example.com"],
    "allowCredentials": true,
    "allowedHeaders": ["Authorization", "Content-Type"]
  },
  "jaxrs": {
    "basePath": "/api/*",
    "validationMode": "failFast",
    "defaultHeaders": {
      "strictTransportSecurity": "max-age=31536000; includeSubDomains",
      "X-Service-Name": "orders"
    },
    "sse": { "defaultBufferSize": 512, "defaultOverflowPolicy": "DROP_OLDEST" }
  },
  "correlation": {
    "ingress": { "echoCorrelationId": true, "invalidValuePolicy": "REJECT" }
  }
}
```

---

## Failures, Constraints, and Common Mistakes

### Startup failures

`RestConfigurationException` extends `dev.vertique.core.exception.ConfigurationException` and is the
root of this module's wiring failures.

| Failure | Cause |
|---|---|
| `IllegalStateException` failing the start promise | one or more invalid mount paths, reported as a single aggregated message |
| `RestConfigurationException` | invalid `ssl.clientAuth`, blank `http.uploadsDirectory`, an unsupported security declaration shape, an unknown `jaxrs.validationStrategy` |
| `SecurityPolicyViolationException` | security-policy validation found violations; `violations()` lists each with its `operationId` and `ViolationType` |
| `IllegalStateException` at component construction | two `ParamConverterBinding`s claim the same target type |
| `RestContextUnavailableException` | a declared `@Context` parameter has no resolver; carries `type()`, `resourceClass()`, `methodName()` |

Mount paths must be non-blank, start with `/`, end with `/*`, and contain no `//`, `?`, or `#`.
Overlapping or duplicate mount paths only **warn** — the first-mounted router wins and the shadowed
one is silently unreachable, so check startup logs when a route 404s unexpectedly.

Unsupported security shapes are rejected by `EffectiveSecurityPolicy.enforceSupportedShape`: a
multi-scheme AND requirement, scopes declared on an OR alternative, and scopes declared through both
`@Authorized` and `@SecurityRequirement`.

### Request-time failures

| Exception | Extends | Typical mapping |
|---|---|---|
| `RestValidationException` | `ValidationException` | 400 with a `ValidationProblemDetail` body |
| `ParamConversionException` | `ValidationException` | 400; carries `paramName()`, `source()`, `targetType()` |
| `InvalidCursorException` | `ValidationException` | 400 — always the same opaque `"Invalid cursor"` message, whether tampered, unknown-key, or expired |
| `ParamConverterNotFoundException` | `TechnicalException` | 500 — a wiring gap, not a client error |

### Common mistakes

- **Registering your own end handler for cleanup.** `ctx.addEndHandler(...)` for scope teardown
  breaks the reverse-order guarantee that keeps bound values readable. Use
  `RequestContextLifecycle.Handle.onClose(...)` / `afterClose(...)`.
- **Registering on the `Handle` after completion.** `onClose`, `afterClose`, and `bindMdc` throw
  `IllegalStateException` once the lifecycle has closed. Leaks are loud, not silent.
- **Giving a middleware `API` scope on a non-JAX-RS mount.** It is dropped without warning. Only the
  JAX-RS mount honors `MiddlewareScope.API`.
- **Forgetting `priority()`.** `Middleware` and `OperationHandlerContributor` re-declare it as
  abstract; there is no default to inherit.
- **Assuming `@Authorized(scopes = {"a", "b"})` means any-of.** `matchAll` defaults to `true`; that
  declaration requires *both* scopes.
- **Overriding both `RequestBodyDecoder.decode` overloads' defaults with neither.** Implement exactly
  one or every call throws `UnsupportedOperationException`.
- **Expecting an application decoder or encoder to be a fallback.** Application implementations
  default to priority `0` and therefore precede the framework's 999–1100 band. Raise the priority
  above 1100 to sit behind them.
- **Mutating `OperationContext` in place.** `withAttribute(...)` returns a new instance; the original
  is unchanged.
- **Putting sensitive evidence in `RoutingContext.data()`.** That map is keyed by public constants
  and is enumerable and writable by every component sharing the context.
- **Expecting a completion event for a successful protocol upgrade.** There is none; a *failed*
  upgrade does emit one.
- **Persisting a `MediaType.parse(...)` result unchecked.** It returns `null` for malformed input
  instead of throwing, as does `AcceptNegotiator.negotiate(...)` when nothing matches.

---

## Dependencies

| Dependency | Why |
|---|---|
| `dev.vertique:vertique-core` | `OrderedExtension` ordering contract, exception hierarchy, `ConfigParser`, `ContextHolder`, JSON mapper profiles, sanitization SPIs |
| `dev.vertique:vertique-context` | `ContextValues` — the substrate the built-in `@Context` resolver reads |
| `dev.vertique:vertique-correlation` | correlation context, header validation, and the MDC key vocabulary used by ingress |
| `dev.vertique:vertique-logging` | `MDCContexts` scopes bound through `RequestContextLifecycle.Handle.bindMdc` |
| `dev.vertique:vertique-security-core` | `SecurityContext`, its snapshot, `RequestOrigin`, and `ActionRef` authorization references |
| `io.vertx:vertx-web` | `Router`, `RoutingContext`, `AuthenticationHandler`, `CorsHandler` — and, transitively, Vert.x core |
| `com.google.dagger:dagger` | `@Module` / `@Multibinds` declarations for every extension set |
| `jakarta.inject:jakarta.inject-api` | `@Inject` / `@Singleton` on framework components |
| `jakarta.ws.rs:jakarta.ws.rs-api` | `Response`, `EntityTag`, `ExceptionMapper`, `ParamConverterProvider`, parameter annotations |
| `jakarta.annotation:jakarta.annotation-api` | `@Nullable` on API signatures |
| `org.projectlombok:lombok` | `provided` scope — builders and accessors on the config and problem-detail types; not a runtime dependency |

No OpenAPI artifact is declared, by design: route registration is expressed through `RouterSetup`,
`RouteRegistration`, `SecuritySchemeRegistry`, and `RestOperationDescriptor` so extensions compiled
against this module stay independent of any contract-validation implementation.
