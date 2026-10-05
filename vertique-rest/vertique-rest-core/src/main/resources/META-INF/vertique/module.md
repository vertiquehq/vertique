<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Core Module

> **Status:** Stable
> **Package:** `dev.vertique.rest.core` (+ 18 sub-packages)
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
  `ResponseBodyEncoder`, `ParamConverter`, `RestContextResolver`, `OperationHandlerContributor`,
  `RestRequestCompletedListener`, or `HttpRequestCompletedListener`;
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
3. **Run composition validators** — every framework composition validator (see Framework seams
   below) runs on the valid, sorted mounts; any violation it returns, or any exception it throws,
   fails the start promise before any mount router is created.
4. **Detect overlaps** — duplicate paths and prefix containment log a warning; startup continues.
5. Sort `MountCustomizer`s by the plain `OrderedExtension` comparator.
6. Create the main router and attach every `ROOT`-scoped `Middleware` at its own `path()`.
7. Run `BEFORE_MOUNTS` `RouterCustomizer`s.
8. Create each mount's router **sequentially**, apply every matching `MountCustomizer`, attach as a
   sub-router. Creation is sequential precisely so mount order equals the sorted order.
9. Run `AFTER_MOUNTS` `RouterCustomizer`s.
10. Bind the server, then publish the bound port into
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

A reroute re-runs every `ROOT` middleware on the same request, this one included. The lifecycle
reuses the request's own `Handle` and registers no second cleanup, so "exactly one end handler per
request" holds across reroutes. Every value bound on any pass, including an identity authenticated
only after a reroute, stays readable by every other end handler until the request ends. A handle
from another request is never reused. A handle closed by `completeNow()` is never reopened: a
reroute after it chains a successor handle, which the same single cleanup closes. A rerouted request
without an inbound `X-Request-Id` gets a generated request id on each pass; its completion event
carries the last pass's id, the same one the echoed response header carries.

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
| `RestRequestCompletionEmitter` | `SYSTEM_FIRST` | `RequestContextLifecycle.ORDER + 5` | ROOT |
| `CorrelationIngressMiddleware` | default | `RequestContextLifecycle.ORDER + 10` | ROOT |
| `ContextualLoggingMiddleware` | default | `0` | ROOT |
| `DefaultHeadersMiddleware` | default | `10` | ROOT |
| `ContentTypeValidationMiddleware` | default | `20` | API |

`RequestContextLifecycle.ORDER` and `CorrelationIngressMiddleware.ORDER` are `public` and may be used
as anchors. An application middleware that must observe an authenticated identity belongs at a
positive priority. `RestRequestCompletionEmitter` runs in `SYSTEM_FIRST` right after
`RequestContextLifecycle`, so an application `ROOT` middleware that ends the response without
calling `next()`, at any priority, still yields exactly one completion event; the phase is a trusted
ordering hint, not a security boundary.

### Request completion

Exactly one completion event is published for each HTTP request that completes through the response
lifecycle, owned by the transport that claimed it:

- A **`RestRequestCompletedEvent`** when a JAX-RS operation route matched. The claim is recorded by
  a platform handler (a Vert.x Web `PlatformHandler`) that the JAX-RS route registrar installs first
  on every operation route, before authentication. So a request denied with 401, 403, 415, or a
  validation 400 on a matched route still carries its non-null `operation()`.
- **Nothing** when another transport claimed the request to emit its own lifecycle events.
- An **`HttpRequestCompletedEvent`** otherwise, carrying the transport facts: method, path, status,
  timing, failure classification, an optional security snapshot, an optional correlation snapshot,
  and the request origin. The REST event carries the same facts plus its `operation()`.

Unclaimed requests are:

- `ROOT` rejections, for example a correlation `REJECT` 400 or a rate-limit 429;
- requests that match no route (404 or 405);
- JAX-RS mount-level rejections before any operation route matched: body 413 or 400,
  request-interceptor rejections, and the API-scope 415;
- MCP admission rejections on a request's first entry into the MCP mount;
- failed WebSocket upgrades.

Entering a JAX-RS mount, or reaching its failure handler, does not claim a request. The last
operation route matched in the current routing pass decides the operation. A reroute clears the
claim, so the rerouted target decides it, and the event keeps the first pass's start time. At
`DEBUG`, `RestRequestCompletionEmitter` logs one line for each request it skips because another
transport claimed it.

The completion state is framework-owned. No `RoutingContext.data()` key exposes it, and writing the
retired `rest.events.*` keys has no effect on the event. `RestRequestCompletionEmitter` holds the
state in its own end handler and emits exactly once whether or not `RequestContextLifecycle` is
mounted.

Two deliberate properties:

- A **successful protocol upgrade emits no event** — the 101 is written without firing the response
  end handler. A *failed* upgrade takes the normal error path and emits an
  `HttpRequestCompletedEvent`.
- `safeFailureMessage` is always `null`, and `failureCode` is a class simple name only, on both
  event types. Raw exception messages can carry SQL text, upstream detail, or PII, so they never
  reach either event.

#### Which completion source do I use?

| To observe | Use | Module |
|---|---|---|
| JAX-RS operations, including requests denied on a matched operation route | `RestRequestCompletedListener` | `vertique-rest-core` |
| HTTP requests no transport claimed (the list above) | `HttpRequestCompletedListener` | `vertique-rest-core` |
| MCP requests | MCP completion observers: `McpRequestLifecycleObserver`, or `McpRequestCompletedListener` for a post-transport callback | `vertique-mcp-core` |
| WebSocket connections after a successful upgrade | WebSocket channel events: `ChannelOpenedEvent` and `ChannelClosedEvent` through `SecurityEventObserver`, for connections registered as channels | `vertique-security-core`; `vertique-rest-websocket` says when a connection is registered |
| Every HTTP request at the transport level, whatever claimed it, as counts and latencies | Vert.x native HTTP server metrics (`metrics.vertx.httpServer`) | `vertique-micrometer-core` |

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

`RestOperationDescriptor` is Stable and grows only through `default` methods, so an implementation
written against an earlier version (such as a test descriptor) keeps compiling and linking.

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

Size enforcement is **post-spool**: ingress body size is bounded by `http.maxBodySize` for every
request and by `http.maxMultipartBodySizeBytes` for `multipart/form-data` (effective limit is the
tighter of the two; exceeding it returns 413, and when `Content-Length` is present Vert.x rejects
before creating upload files). `maxSizeBytes` is checked after Vert.x has written the part under
`http.uploadsDirectory`. Part *count* is bounded separately at ingress by `http.maxFormFields`, so a
request with more parts than that is rejected during decoding whatever their individual sizes.

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

### `@Authorized` ownership

The `@Authorized` annotation is now owned by `vertique-security-core` as
`dev.vertique.security.authz.Authorized`; use that import for supported REST and WebSocket
declarations. Its existing scope matching and role-composition behavior is unchanged. This
owner-authorized 0.x move is a source and binary break for the former
`dev.vertique.rest.core.security.Authorized` import. No deprecated REST alias is provided; update
imports and clean-rebuild with aligned framework and processor versions.

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

In `vertique-rest-jaxrs`, once any Jakarta REST `Application` is declared, this set holds every manual
contribution — selected by an application or not — plus any resource contributed by a module built with
an older processor, which still contributes unconditionally. A resource generated by the current
processor reaches an application's mount only through the declared applications that select it, not
through this set. With no `Application` declared, this set's content is unchanged: generated and
hand-wired resources alike contribute into it exactly as before.

### `@RestApplication`

Declares a named REST application on an interface. It is **Beta** and outside this module's Stable
promise: it may change in a later release, and only with a migration note. Package
`dev.vertique.rest.core.application`.

```java
public @interface RestApplication {
    String name();                       // required
    String path();                       // required; the application's mount path
    Class<?>[] resources() default {};   // listed resources, in the order written
    boolean discover() default false;    // discover resources at startup instead
    String openapiPath() default "";     // "" = the global jaxrs.openapiPath
}
```

It annotates an interface only; the interface is never implemented or instantiated. Exactly one of a
non-empty `resources` and `discover = true` is set, and `discover = true` is permitted only when it
is the compilation unit's sole declaration. `name` must match `[a-z0-9][a-z0-9_-]{0,63}` and must
not be `none` or `null`. `vertique-codegen-jaxrs` validates and registers each declaration at
compile time, and `vertique-rest-jaxrs` composes and mounts the declared applications at runtime.
Composition, membership, the `jaxrs.applications.<name>` configuration, and mount conflicts are
described in the "@RestApplication" section of `dev.vertique:vertique-rest-jaxrs`.

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
Set<Class<?>> resourceTypes, @Nullable String applicationName)`. Override it to publish a stable
`mountId`. `applicationName` is the name of the application the mount serves and is `null` for a mount
that belongs to no named application, including the default `meta()` above.

`applicationName` is a nullable component appended after `resourceTypes`. The four-argument
constructor `MountMeta(mountId, mountPath, openapiPath, resourceTypes)` remains and supplies `null`, so
code that constructs a `MountMeta` with four arguments keeps compiling. A record pattern that
deconstructs `MountMeta` into four components no longer compiles against the five-component record;
read the components through the accessors instead.

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
| 360 | server-span enrichment | `vertique-opentelemetry-rest` |

Route identity for the completion event is recorded by the framework before authentication, not by
a contributor (see Request completion above).

The terminal operation invoker is appended after every contributor, so a contributor always runs
before the resource method.

`OperationRegistrationContext` carries `operationId()`, `securityPolicy()`,
`requiredAction()` (`Optional<ActionRef>`), `operation()` (`RestOperationDescriptor`), and `route()`
(`RouteRegistration`, whose `addHandler(...)` returns itself for chaining).

`RestOperationDescriptor.applicationName()` is a `@Nullable String` default method that returns
`null`. It names the application the operation belongs to or serves, which covers an application
mount's operations and framework synthetic operations; it is `null` for every other operation. It is the
one descriptor accessor exempt from the rule that the identity accessors are never `null`, and existing
`RestOperationDescriptor` implementations keep compiling without overriding it. `operationId()` is
unique within its mount; a contributor or interceptor that keys state per operation and must not
depend on how a runtime treats ids across mounts includes the application name in the key.

Contributors may also run for framework-owned synthetic operations — routes installed outside normal
resource-method discovery. A synthetic route runs the same contributor chain, with the same inputs,
as an equally-secured resource route: the same contributors, in the same order, with the same
effective security policy. A synthetic operation's id lives in the reserved `apidocs:` namespace, its
`operation()` descriptor reports a literal route template with no consumed or produced media types,
and the descriptor's annotations are the synthetic security annotations its effective policy was
built from — so a contributor that reads annotations sees exactly what an equally annotated resource
method would show. No signature, default, or behavior of this interface changes for a synthetic
operation.

A synthetic route renders a failure from its status alone (`ctx.fail(status)` or an
`HttpException`), without the application's exception mapping or interceptors; a contributor
rejecting a synthetic operation fails with an explicit 4xx or 5xx status.

### `RequestInterceptor`, `OperationInterceptor`, `ErrorInterceptor`

Three pipelines with distinct scopes. Every callback has a default, so implement only what you need.

```java
public interface RequestInterceptor extends OrderedExtension {
    String ORIGINAL_ERROR_KEY = "dev.vertique.rest.originalError";

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
`classAnnotations()`, `attributes()`, `operation()`, the typed lookups `methodAnnotation(Class)` /
`classAnnotation(Class)`, and `withAttribute(String, Object)`, which returns a **new** context that
carries every component, `operation` included. Return that new instance from `beforeOperation` or the
attribute is lost.

`operation()` is the operation's `RestOperationDescriptor`, the same instance the operation's
`OperationHandlerContributor`s received as `OperationRegistrationContext.operation()` when the
JAX-RS adapter registers the route. Read the
application name from `ctx.operation().applicationName()`. `operation()` is `null` on a context built
without a descriptor, such as one constructed with the five-argument constructor, so a key that uses it
should handle `null` when the interceptor can see such contexts. An interceptor that keys state per
operation includes the application name, so operations of different applications never share an
entry. On a mount that belongs to no declared application the application name is `null`, so the
key reads `null:<operationId>`, and the cross-mount operation-id uniqueness check runs only when
applications are declared:

```java
public Future<Object> recoverOperation(OperationContext ctx, Throwable cause) {
    return cache.get(ctx.operation().applicationName() + ":" + ctx.operation().operationId())
        .<Object>map(cached -> cached)
        .orElse(Future.failedFuture(cause));
}
```

Each interceptor's `beforeOperation` receives the context the previous one returned. The chain
remembers the `operation()` of the context it starts with. After each `beforeOperation`, when the
returned context's `operation()` is not that same instance (compared by reference), the chain replaces
the returned context with a copy that carries the original operation and keeps every other component,
so every later interceptor and every later hook (`onOperation`, `afterOperation`, `onSuccess`,
`onError`, `recoverOperation`) observes the registration-time operation. The chain restores only the
`operation` component; the `operationId()` of a context an interceptor rebuilt is left as returned, so
read the operation's identity from `ctx.operation()`. A context an interceptor builds with the
five-argument constructor carries a `null` operation, and the chain puts the operation back.

`OperationContext` gains `operation` as a nullable component appended after `attributes`. The
five-argument constructor `OperationContext(operationId, routingContext, methodAnnotations,
classAnnotations, attributes)` remains and supplies `null`, so code that constructs an
`OperationContext` with five arguments keeps compiling. A record pattern that deconstructs
`OperationContext` into five components no longer compiles against the six-component record; read the
components through the accessors instead.

`recoverOperation` defaults to re-failing; returning a succeeded future turns a failure into a
result. `ORIGINAL_ERROR_KEY` names the routing-context entry that carries the pre-mapping throwable.
The Vert.x-originated failure status a router-level failure handler records is **not** part of this
contract — it is an internal handoff owned by `dev.vertique:vertique-rest-jaxrs`; observe the
resulting status on the `Response` instead.

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
`dev.vertique.input.processing.InputObjectProcessor` (from the
`dev.vertique:vertique-input-processing` artifact) and calling
`processInput(Object input, Type targetType, EffectiveInputPolicies policies,
InputLocation location, InputFieldNameResolver nameResolver)` before final binding. The binding is
`@BindsOptionalOf`; it resolves only when `dev.vertique:vertique-sanitization` is on the graph.

The trailing `dev.vertique.core.sanitization.InputFieldNameResolver` is the decoder's own choice,
and there is no overload that omits it. Pass `InputFieldNameResolver.IDENTITY` when the
intermediate's keys are already Java property names — including any call that processes a bare
string, where no object's fields could be renamed. Pass a codec-backed projection when the keys are
wire names the codec renames while binding, such as `JacksonFieldNameResolver` from
`dev.vertique:vertique-json` for a decoder that materializes through an `ObjectMapper`; build it
from the same mapper the decoder will bind with. Choosing `IDENTITY` where the keys are wire names
does not fail the request — it looks each key up under a name the target type never declares, so
the declared chains for those fields do not run. The projection's semantics are documented in the
`vertique-input-processing` reference.

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

The native registry itself is `ParamConverterRegistry`. `ParamConverterRegistry.of(Set)` combines
the built-ins above with the contributed bindings — an application binding wins over a built-in for
the same target type — and `find(Class)` resolves exact class first, then synthesizes an enum
converter, then returns `Optional.empty()`. It is context-free and thread-safe, and it is what a
framework module calls when it needs the same conversion table outside a request.

`ParamConversionResolver` tries the native registry first, then any contributed
`jakarta.ws.rs.ext.ParamConverterProvider` (also a `@Multibinds` set), ordered by `@Priority`
ascending. Its `ConversionContext(String paramName, ParamSource source, Class<?> rawType,
@Nullable Type genericType, @Nullable Class<?> componentType, Supplier<Annotation[]> annotationsLazy)`
carries `dev.vertique.rest.core.convert.ParamSource`, whose five constants are `PATH`, `QUERY`,
`HEADER`, `COOKIE`, and `FORM` — the string-ish transport kinds. Do not confuse it with the
same-named but unrelated parameter-source enums in the JAX-RS and REST-client modules.

**Non-null converter-result contract.** `fromString` never returns `null` for a present transport
string. A native or JAX-RS converter that returns `null` for that present value fails closed with
`ParamConversionException` (400) — the same rule for a submitted value and for a `@DefaultValue`
string routed through the resolver. Absence of a parameter is a caller concern (empty collection,
`null` scalar, or applying a default); it is not expressed by a converter returning `null`. The
exception message names the parameter and target type and never echoes the raw value.

### `RestRequestCompletedListener`, `HttpRequestCompletedListener`, and `RequestCompletionScope`

`RestRequestCompletedListener` observes JAX-RS operations, and `HttpRequestCompletedListener`
observes requests no transport claimed. A request another transport claimed produces neither event.

```java
public interface RestRequestCompletedListener {
    void onCompleted(RestRequestCompletedEvent event);

    default void onCompleted(RestRequestCompletedEvent event, RoutingContext routingContext) {
        onCompleted(event);
    }
}

public interface HttpRequestCompletedListener {
    void onCompleted(HttpRequestCompletedEvent event);

    default void onCompleted(HttpRequestCompletedEvent event, RoutingContext routingContext) {
        onCompleted(event);
    }
}

public interface RequestCompletionScope {
    AutoCloseable open(RoutingContext rc);
}
```

The framework calls each listener's two-argument overload once per request, with the live root
`RoutingContext` of the request; the default delegates to the one-argument method. Each interface
keeps exactly one abstract method, so a listener that needs only the event stays a lambda over
`onCompleted(event)`, like the one below, and receives each event exactly once. Override the
overload when a listener needs per-request state or the live request. An override that does not
delegate never sees the one-argument call.

The overload runs on the thread that ended the response, which is usually, but not always, the
Vert.x event loop. Its context stays the root one for a request a sub-router serves: the
sub-router's route handler receives a different `RoutingContext` wrapper, which shares `request()`,
`response()`, and `data()` with the root. Key per-request state by `routingContext.request()`,
never by the `RoutingContext` object. An implementation must not block, write to the response, call
`next()` or `fail()`, or keep the context after it returns, and it must keep sensitive values out
of the exceptions it throws, as the isolation rules below explain.

A Mockito mock of either listener does not run the default method, so a mock's one-argument method
is never called. A test verifies the two-argument call instead, or creates the mock with
`CALLS_REAL_METHODS`.

```java
@Provides
@IntoSet
static RestRequestCompletedListener requestMetrics(MeterRegistry registry) {
    return event -> registry.counter(
                    "http.server.requests",
                    "method", event.method(),
                    "route", event.operation().routeTemplate(),
                    "status", Integer.toString(event.statusCode()))
            .increment();
}
```

```java
public record RestRequestCompletedEvent(
        Instant startTime, Instant endTime,
        String method, String path,
        RestOperationDescriptor operation,
        int statusCode,
        @Nullable String failureCode, @Nullable String safeFailureMessage, @Nullable String wireFailureCode,
        @Nullable SecurityContextSnapshot securityContextSnapshot,
        @Nullable CorrelationContextSnapshot correlationContext,
        Optional<RequestOrigin> origin,
        Map<String, Object> safeAttributes) {}

public record HttpRequestCompletedEvent(
        Instant startTime, Instant endTime,
        String method, String path,
        int statusCode,
        @Nullable String failureCode, @Nullable String safeFailureMessage, @Nullable String wireFailureCode,
        @Nullable SecurityContextSnapshot securityContextSnapshot,
        @Nullable CorrelationContextSnapshot correlationContext,
        Optional<RequestOrigin> origin,
        Map<String, Object> safeAttributes) {}
```

`operation()` is never `null`. As a Stable promise, `operation()` is the same instance that
`OperationHandlerContributor`s received as `OperationRegistrationContext.operation()` for the
matched route. State a contributor keyed on its descriptor is therefore matched to the event by
identity. Framework-built descriptors compare by identity, so don't key maps on descriptor value
equality.

The two compact `toString` forms differ on purpose, and neither is a parse format:

- `RestRequestCompletedEvent` keeps the record's `RestRequestCompletedEvent[name=value, …]` form and
  renders its operation as `<operationId> <routeTemplate>`, for example `getUser /users/{id}`,
  without calling the descriptor's `toString`.
- A framework-built descriptor, and one from `TestOperationDescriptors` (`vertique-rest-test`),
  renders as `<httpMethod> <routeTemplate> (<operationId>)`, for example
  `GET /users/{id} (getUser)`.

`HttpRequestCompletedEvent` uses the default record `toString`.

Both records evolve under the same rule: new components are only appended, after the last existing
one, and each addition keeps the previous-arity constructor. Record-pattern deconstruction binds
components by position, so it is outside the compatibility promise.

A `RequestCompletionScope` wraps the dispatch of either event type — scopes open in iteration order
and close in reverse, which is how tracing modules re-establish a span around emission. Listeners
are unordered: the framework promises no invocation order, and no implementation may depend on
another's side effects. A listener that throws an `Exception` is logged at WARN and does not stop
the remaining listeners; an `Error` propagates.

The logged failure is what keeps the fan-out diagnosable. The exception, including its message and
any cause, is logged, so none of them may carry credentials, tokens, personal data, or raw request
values. An implementation must therefore keep them out of the exceptions it throws. The obligation
is audit-safe by contract rather than by enforcement, exactly as
`AuthorizationDecision.safeAttributes()` is — the framework does not inspect or scrub what an
implementation throws.

### `SecuritySchemeHandler` and `RouteAuthHandler`

```java
public interface SecuritySchemeHandler {
    String schemeName();
    void configure(SecuritySchemeRegistry registry);
    default Optional<SecuritySchemeDescription> openApiDescription() { return Optional.empty(); }
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
`createHandler()` always retains required-authentication semantics. A transport that supports
anonymous callers alongside authenticated callers must select only a handler that explicitly returns
a non-empty `createOptionalHandler()` result. That handler continues once without changing the user
or authentication evidence when its credentials are absent; it must reject present-but-invalid
credentials through the same verification path as its required handler.

`SecurityRuntime`, `SecurityPolicyResolver`, and `SecurityPolicyValidator` are declared here and
implemented in `vertique-rest-security`. Bind your own only to replace framework behavior wholesale.
`SecurityRuntime.bindCurrent(SecurityContext)` returns a `ContextHolder.Scope` that **must** be
registered with `RequestContextLifecycle.Handle.onClose(...)`.
`SecurityRuntime.clearCurrent()` removes any currently bound `SecurityContext` without restoring a
prior binding — used by trust-boundary clears such as MCP's no-scheme admit path that must discard
ambient holder state before the transport binds its own identity. Prefer `bindCurrent` with
lifecycle-owned scopes for ordinary bind/unbind.

#### Describing a scheme for OpenAPI

A handler may override `openApiDescription()` to describe its scheme; the default returns
`Optional.empty()`, so no existing handler need change. The description is dormant in this module —
nothing here reads it — until a documentation-rendering module turns it into
`components.securitySchemes`.

`SecuritySchemeDescription` is a closed, sealed interface with five kinds, each an immutable final
class built only through its static factories, with `with*` copies. The description types —
`SecuritySchemeDescription`, its kinds, `OAuthFlows`, and `OAuthFlow` — live in
`dev.vertique.rest.core.security.scheme`; `SecuritySchemeHandler` stays in
`dev.vertique.rest.core.security`:

```java
public sealed interface SecuritySchemeDescription permits Http, ApiKey, OAuth2, OpenIdConnect, MutualTls {
    Optional<String> description();
}

Http.bearer(@Nullable String bearerFormat)   // type: http, scheme: bearer
Http.of(String scheme)                       // type: http, any scheme name

ApiKey.header(String name)                   // type: apiKey, in: header
ApiKey.query(String name)                    // type: apiKey, in: query
ApiKey.cookie(String name)                   // type: apiKey, in: cookie

OAuth2.of(OAuthFlows flows)                  // type: oauth2, flows built through OAuthFlows.builder()

OpenIdConnect.of(URI openIdConnectUrl)       // type: openIdConnect

MutualTls.of()                               // type: mutualTLS
```

`OAuthFlows.builder()` sets each of the four flow kinds — `implicit(authorizationUrl, scopes)`,
`password(tokenUrl, scopes)`, `clientCredentials(tokenUrl, scopes)`, and
`authorizationCode(authorizationUrl, tokenUrl, scopes)` — with a typed method so a flow carries
exactly the URLs its type uses, plus `refreshUrl(URI)`, applied to every flow set on the builder
whether called before or after it; `build()` requires at least one flow to have been set.

A description carries only OpenAPI Security Scheme fields — no extension map, no JSON tree. What a
handler puts in those fields is its own responsibility; the type itself checks nothing about token
claims, granted scopes, or any other business meaning.

**Construction rules.** Every factory and `with*` method rejects a `null` argument with
`NullPointerException` and a blank string or relative URI with `IllegalArgumentException`, each
naming the argument. `Http.bearer`'s `bearerFormat` is the one nullable argument across every
factory: `null` yields an empty `bearerFormat()`, while a blank value is rejected. `ApiKey.header`
and `ApiKey.cookie` names must additionally be RFC 9110 tokens — an ASCII letter, a digit, or one of
`` !#$%&'*+-.^_`|~ `` — because a header scheme's name can reach a response's `Vary` header;
`ApiKey.query` names need only be non-blank, since a query parameter reaches no header. Every OAuth
flow URL and the OpenID Connect discovery URL must be absolute; a scope name must be non-blank
though its description may be empty; and `OAuthFlows.builder().build()` throws
`IllegalStateException` when no flow was set.

Each kind implements `equals`, `hashCode`, and a field-only `toString` — equality is by value over
every field.

**Evolution rule (Stable).** New kinds and new optional fields may arrive in later releases; do not
switch exhaustively over the permitted kinds. A new optional field arrives as a new accessor plus a
new `with*` method; a new kind arrives as a new permitted class; every existing factory keeps its
signature.

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

### Framework seams

Eleven public types are named nowhere above because no application uses one — `RestContextMessages`,
`RestContextModule`, `RestContextTypes`, `RequestCompletionRecorder`, `MountCompositionValidator`,
`SecurityRequirementSet`, `AuthEnforcementCapability`, `SecurityPolicyViolation`,
`RequiresActionResolver`, `DeferredCredentialRejectionAuthHandler`, and
`AnnotationSecurityPolicyResolver`. They are public because sibling framework modules call them
across package boundaries: the JAX-RS route registrar, the security enforcement modules, the
WebSocket transport, the OpenTelemetry integration, and the annotation processors that emit against
the same vocabulary.

Their Javadoc marks them INTERNAL and they sit outside this module's compatibility promise. What
this module promises an application is the extension points above, the configuration keys below, and
the documented request and failure behavior.

---

## Configuration

Three top-level sections are parsed by `RestCoreModule` — `http`, `cors`, and `jaxrs` — plus
`correlation.ingress` by `CorrelationIngressModule`. Every key is optional; omitted keys take the
default below. Unknown keys are ignored except under `jaxrs.defaultHeaders`, where they become
custom response headers, and except under `jaxrs.security` and `jaxrs.applications`. The
`jaxrs.applications` section is parsed strictly by `vertique-rest-jaxrs`: a case variant of
`applications` at the `jaxrs` level (such as `Applications`), a non-object section, a non-object
entry, or an entry key other than `openapiPath` fails startup, as does a blank
`openapiPath` (empty or whitespace only; an absent or `null` value is accepted). An entry whose name
matches no declared application also fails startup, reported with the application composition
violations rather than by the section parse. The `jaxrs.security`
exception is a deliberate narrowing of this Stable module's "unknown keys are ignored" rule,
limited to these reserved names: a non-object `jaxrs.security` value (including `null`), an unknown key under it, and — at the
`jaxrs` level — a case variant of `security` or a misplaced `requireExplicitPolicy` (in any case)
each fail startup with a `ConfigurationException` naming the offending keys, sorted, never their
values.

**The keys are the contract.** What this module freezes is the key names below, their types,
defaults, and constraints. The record types the parser binds them to — `HttpConfig`, `SslConfig`,
`SseConfig`, `CorsConfig`, `JaxRsConfig`, `JaxRsSecurityConfig`, `DefaultHeadersConfig`, and
`CorrelationIngressConfig` — are an implementation detail of that parse. An application writes
configuration, not those types, and their shape can change while the keys stay as documented.

### `http`

| Key | Default | Constraint / notes |
|---|---:|---|
| `http.port` | `8080` | |
| `http.host` | `"0.0.0.0"` | |
| `http.maxBodySize` | `2097152` | total request-body bytes — exceeding it returns 413 |
| `http.maxMultipartBodySizeBytes` | `2097152` | must be positive; pre-auth `multipart/form-data` admission ceiling — BodyHandler uses `min(maxBodySize, maxMultipartBodySizeBytes)` and returns 413 when exceeded (Content-Length early reject before spool when present). Raise `maxBodySize` for large non-multipart payloads without widening multipart spool by keeping this tight. Residual: when `http.decompressionSupported` is true, compressed-request expansion is not yet separately bounded beyond this BodyHandler limit |
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
| `jaxrs.basePath` | `"/*"` | mount path of the JAX-RS sub-router; not applied when one or more `@RestApplication` declarations are present — each is mounted at its own `@RestApplication.path` instead |
| `jaxrs.openapiPath` | `"openapi.json"` | classpath spec; used by the opt-in `openapi-contract` strategy, and the shared global contract location for every application that sets neither `@RestApplication.openapiPath` nor `jaxrs.applications.<name>.openapiPath`; `vertique-rest-openapi-docs` refuses to document an application whose strategy resolves operations from that shared global contract |
| `jaxrs.applications` | *(none)* | per-application settings, keyed by application name; parsed strictly by `vertique-rest-jaxrs`; unknown keys fail startup — see that module's reference |
| `jaxrs.mediaTypeValidation` | `"WARN"` | `WARN`, `STRICT` (fails startup on the first mismatch), or `OFF` |
| `jaxrs.validationStrategy` | `"web-validation"` | must match a registered strategy id — built-ins are `web-validation`, `none`, `openapi-contract`; an unknown id fails startup when any JAX-RS mount has resources; a mount with no resources never selects a strategy |
| `jaxrs.validationMode` | `"aggregate"` | `aggregate` or `failFast` |
| `jaxrs.validationPatternMaxChars` | `4096` | at least `1`, else startup fails; the most UTF-16 code units one string value or object key may have when it reaches a `pattern`, `patternProperties`, or pattern-bearing `propertyNames` position, or an `idn-hostname`, `idn-email`, or `regex` format, under the `web-validation` strategy — a longer one is rejected with 400 before that check runs |
| `jaxrs.validationPatternMaxTotalChars` | `262144` | at least `1` and no smaller than `jaxrs.validationPatternMaxChars`, else startup fails; the most UTF-16 code units the strings and keys reaching those positions may add up to in one request — the request is rejected with 400 once the total exceeds it |
| `jaxrs.autoEtag` | `false` | attach a weak ETag derived from the serialized body when none is set |
| `jaxrs.jsonProfile` | *(none)* | must name a registered JSON mapper profile; resolution is method `@JsonProfile` → class `@JsonProfile` → this key → `json.jsonProfile` → the `vertique` floor |
| `jaxrs.security.requireExplicitPolicy` | `false` | boolean; when `true`, every JAX-RS operation must declare an explicit security policy, else startup fails — details in the `vertique-rest-jaxrs` reference |

When `jaxrs.security.requireExplicitPolicy` is `true`, `RestCoreModule` logs one INFO line:
`jaxrs.security.requireExplicitPolicy is enabled: explicit security policies are required for
every JAX-RS operation`. The default `false` logs nothing new.

`jaxrs.security.requireExplicitPolicy` is read only from the nested `jaxrs` → `security` object
shown above. A dotted key placed directly under `jaxrs` — for example
`{"security.requireExplicitPolicy": true}` — is an ordinary unknown key, not one of the reserved
names above, and is silently ignored; it never reaches the opt-in. A flat
`-Djaxrs.security.requireExplicitPolicy` system property or the equivalent environment variable is
not read directly either, because neither source expands a dotted key into nested JSON; such a value
reaches the opt-in only through a `${...}` placeholder written at the nested `requireExplicitPolicy`
position, resolved as described in the `vertique-config-core` reference's placeholder resolution
chain. The INFO line above is the only confirmation that the opt-in resolved to `true`.

`jaxrs.validationPatternMaxChars` and `jaxrs.validationPatternMaxTotalChars` guard
regular-expression evaluation against denial of service, and their defaults reject input: a request
whose strings or keys at those positions exceed either limit receives a 400 even when its schema
would accept the values. An application that must accept longer input there raises the limits.
Only positions carrying a `pattern`, `patternProperties`, or one of the three named formats count,
so a body with none of them — dates, timestamps, or identifiers alone — is never rejected by these
limits. Both values are validated where `RestCoreModule` provides the `jaxrs` configuration, so an
invalid value fails startup whichever validation strategy is selected. The positions, the rejection
details, and the formats left unbounded are described in the `vertique-rest-validation` reference.

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

Custom entries are applied to every response too, including responses of operations that restrict
callers and their problem responses. A targeted caching header such as `CDN-Cache-Control`,
`Surrogate-Control`, `X-Accel-Expires`, or `Expires` set here therefore reaches protected responses,
and a CDN or proxy that obeys it can store them. Do not set such headers here while the application
serves restricted operations or protected documents (the `vertique-rest-openapi-docs` reference
covers protected documents).

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
    "maxMultipartBodySizeBytes": 2097152,
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
| `RestConfigurationException` | invalid `ssl.clientAuth`, blank `http.uploadsDirectory`, non-positive `http.maxMultipartBodySizeBytes`, an unsupported security declaration shape, an unknown `jaxrs.validationStrategy` |
| `ConfigurationException` | an invalid pattern-input limit; the per-string limit is checked first, with the message `jaxrs.validationPatternMaxChars must be at least 1`, then the total, with `jaxrs.validationPatternMaxTotalChars must be at least 1 and no smaller than the per-string pattern limit`; neither message echoes a configured value |
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
| `ParamConversionException` | `ValidationException` | 400; carries `paramName()`, `source()`, `targetType()`. A converter returning null for a present value, or a null or wrong-typed collection element, raises it. The message omits the submitted value. |
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
- **Keying per-operation state on `operationId()` alone.** The id is unique within one mount; add
  `ctx.operation().applicationName()` when the interceptor serves several applications.
- **Destructuring `MountMeta` or `OperationContext` with a record pattern.** Both records gained a
  trailing component; use the accessors.
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
| `dev.vertique:vertique-core` | `OrderedExtension` ordering contract, exception hierarchy, `ConfigParser`, `ContextHolder`, JSON mapper profiles |
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
