<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Client Module

> **Status:** Implemented
> **Package:** `dev.vertique.rest.client`
> **Artifact:** `vertique-rest-client`
> **Depends on:** core, rest-core, json

Declarative HTTP client for Vert.x. Annotate a Java interface with standard JAX-RS annotations
(`@GET`, `@Path`, `@QueryParam`, …) plus `@RestClient`, then ask `RestClientBuilder` or the
Dagger-provided `RestClientFactory` for a proxy: every method invocation becomes an asynchronous
HTTP call returning `Future<T>`. The role is the same one Spring's `@HttpExchange` and MicroProfile's
`@RegisterRestClient` play — a typed, annotation-driven front end over a connection-pooled HTTP
client, here Vert.x `WebClient`.

It is not a server-side routing runtime and shares no dispatch code with one; `dev.vertique:vertique-rest-jaxrs`
handles inbound requests. The two do share the parameter-conversion stack from
`dev.vertique:vertique-rest-core`, so a type serializes on the wire the same way it is parsed off it.

---

## When To Use It

Install `vertique-rest-client` when the application calls another HTTP service and you want the call
site to be a typed interface rather than hand-built URIs and `Buffer` parsing. It brings its own
timeouts, retries, circuit breaking, TLS/proxy configuration, and a typed failure taxonomy, so it
usually replaces a hand-rolled `WebClient` wrapper outright.

Add `RestClientModule` to the Dagger `@Component`; it pulls in `RestCoreModule` and `JsonRuntimeModule`
transitively. Pairs with `dev.vertique:vertique-validation` (response bean validation),
`dev.vertique:vertique-json` (named mapper profiles), and the optional annotation processor
`dev.vertique:vertique-codegen-rest-client` (reflection-free static proxies).

Use the Vert.x `WebClient` directly instead when the endpoint has no stable shape to model — an
arbitrary URL fetched from a database, a streaming download, or a protocol that is not
request/response JSON.

---

## Core Concepts

### The interface is the contract

One annotated interface describes one upstream service. Method annotations carry the HTTP verb, the
path template, and the parameter bindings; the return type carries the response shape.

```java
@RestClient(name = "user-service", value = "http://user-service:8080")
@Path("/users")
public interface UserClient {

    @GET
    @Path("/{id}")
    Future<UserResponse> getUser(@PathParam("id") String id);

    @GET
    @Path("/{id}")
    Future<Optional<UserResponse>> findUser(@PathParam("id") String id); // 404 → Optional.empty()

    @POST
    @Consumes("application/json")
    Future<UserResponse> createUser(UserRequest request);

    @DELETE
    @Path("/{id}")
    Future<Void> deleteUser(@PathParam("id") String id);
}
```

Everything else — base URL, timeouts, retry policy, TLS — is layered on at build time by the builder,
by annotations, or by external configuration, in that precedence order.

### Everything resolves at `build()`, nothing at first call

`RestClientBuilder.build(Class)` scans the interface, resolves the effective `ObjectMapper`, resolves
the parameter converters, creates a fresh `WebClient`, and returns the proxy. Any misconfiguration —
an unknown JSON profile id, a parameter type with no converter, a missing base URL — throws there
rather than on the first request. Builder state is never mutated by `build()`, so one builder can
produce several proxies.

### Request pipeline

Each proxied invocation runs the same sequence. Steps 3–4, 9–10, and 15–17 are the interceptor
callbacks described under [Extension Points](#extension-points).

```
 1. Build path, query, headers, and body from method metadata and arguments
    (path/query/header/cookie values go through the shared ParamConversionResolver)
 2. Create the immutable RestClientRequestContext
 3. [sync]  onRequest observers — exceptions swallowed, cannot abort
 4. [async] beforeRequest handlers — return a new context; a failed future aborts
 5. Build the Vert.x HttpRequest from the (possibly rewritten) URI
 6. Apply the effective timeout
 7. Send inside the circuit breaker when configured; the retry loop lives here
 8. Apply the Expectation (@ExpectedStatus, or the builder default)
 9. [sync]  onResponse observers
10. [async] afterResponse handlers — a failed future turns success into error
11. Future<Optional<T>>: a 404 short-circuits to Optional.empty()
12. Deserialize the body with the resolved per-client ObjectMapper
13. Validate the deserialized object with BeanValidator when one is bound

On failure anywhere in 7–13:
14. [async] recoverRequest handlers — the first success triggers exactly one recovery retry
15. [sync]  onError observers
16. [async] transformError handlers — may replace the propagated error
17. RestClientExceptionMapper translates transport failures into the typed hierarchy
```

`onAttemptCompleted` is the exception to this outline: it fires once per *physical* HTTP attempt from
inside the send, before the retry/recovery decision, so it sees attempts that steps 9/15 never
report.

### Proxy flavors are interchangeable

`build()` looks for a generated `{Client}_RestClientProxy` companion on the classpath and instantiates
it when present; otherwise it returns a JDK dynamic proxy. Both flavors run the identical pipeline
above and the identical parameter conversion, so adding or removing
`dev.vertique:vertique-codegen-rest-client` from `annotationProcessorPaths` changes performance, not
behavior — with the two exceptions noted under [Common mistakes](#common-mistakes).

---

## Key Classes

### `@RestClient`

Type-level annotation marking an interface as a declarative REST client.

| Attribute | Default | Description |
|-----------|---------|-------------|
| `name` | `""` | Logical name for config lookup and circuit-breaker naming; falls back to the interface simple name when blank |
| `value` | `""` | Default base URL (e.g. `http://host:8080`); overridden by `builder.baseUrl(...)` and by external config |

### `RestClientBuilder`

The primary construction API. Obtain it with `RestClientBuilder.create(vertx)` standalone, or with
`factory.builder()` under Dagger — the factory pre-seeds global interceptors and capturers, the parsed
`restClient` config index, the JSON profile registry, the application-wide `ParamConversionResolver`,
and the optional `BeanValidator`.

| Method | Description |
|--------|-------------|
| `create(Vertx)` | Static factory; equivalent to `new RestClientBuilder(vertx)` |
| `create(Vertx, Resilience)` | Static factory backed by a supplied, non-owned application resilience runtime |
| `baseUrl(String)` | Base URL for all requests; overrides `@RestClient#value()`, overridden by external config |
| `readTimeout(long, TimeUnit)` | Default request timeout (default 30 s); overridden per method by `@Timeout` |
| `objectMapper(ObjectMapper)` | Explicit Jackson mapper; wins over every JSON-profile source |
| `jsonProfile(JsonProfileId)` | Selects a named mapper profile, resolved at `build()` from the seeded `JsonMapperProfileRegistry` |
| `beanValidator(BeanValidator)` | Validates deserialized response objects; skipped when absent |
| `webClientOptions(WebClientOptions)` | TLS, connect timeout, keep-alive, and other transport settings |
| `poolOptions(PoolOptions)` | HTTP/1 and HTTP/2 connection-pool sizing |
| `defaultHeader(String, String)` | Header applied to every request; repeated names overwrite |
| `register(RestClientInterceptor)` | Adds a per-client interceptor alongside the global Dagger set |
| `registerCapturer(RestClientContextCapturer<?>)` | Adds a per-client system-owned context capturer |
| `exceptionMapper(RestClientExceptionMapper)` | Replaces the whole mapper, discarding prior `onFailure` registrations |
| `onFailure(Class<T>, FailureTranslator<T>)` | Registers one translator on the current mapper; last registration wins per type |
| `expecting(Expectation<HttpResponseHead>)` | Default response expectation; overridden per method by `@ExpectedStatus` |
| `circuitBreaker(CircuitBreakerOptions)` | Builder-level baseline breaker (lowest priority) |
| `retryPolicy(RestClientRetryPolicy)` | Policy consulted when `@Retry#retryOn` is empty; defaults to `DefaultRestClientRetryPolicy` |
| `backoffStrategy(BackoffStrategy)` | Strategy used when `@Retry#backoff` is `BackoffStrategy.Default`; defaults to exponential (500 ms, ×2, cap 30 s) |
| `paramConversionResolver(ParamConversionResolver)` | Explicit outbound resolver; bypasses binding/provider accumulation |
| `paramConverterBinding(ParamConverterBinding<T>)` | Adds a typed converter binding (standalone use; ignored once a resolver override is set) |
| `paramConverterProvider(ParamConverterProvider)` | Adds a JAX-RS provider (standalone use; ignored once a resolver override is set) |
| `beanParamAccessorRegistry(BeanParamAccessorRegistry)` | Overrides the process-wide generated-accessor cache; intended for tests |
| `config(RestClientConfig)` | Typed per-client overrides; an explicit call wins over the name lookup in the seeded index |
| `build(Class<T>)` | Scans, validates, and returns the typed proxy |
| `close()` | Closes builder-created client contexts and WebClients, then the builder-owned runtime; idempotent |

```java
// Standalone
UserClient client = RestClientBuilder.create(vertx)
        .baseUrl("http://user-service:8080")
        .readTimeout(5, TimeUnit.SECONDS)
        .register(new LoggingInterceptor())
        .build(UserClient.class);

// Dagger
@Inject RestClientFactory factory;

UserClient client = factory.builder()
        .baseUrl("http://user-service:8080")
        .build(UserClient.class);
```

Standalone builders own the resilience runtime they create and should be closed when the client
lifetime ends. Builders created with `create(Vertx, Resilience)` and builders returned by
`RestClientFactory` do not own the supplied/application runtime; closing them releases only their
client contexts and WebClients. `close()` is deterministic and idempotent.

### `RestClientFactory`

`@Singleton` provided by `RestClientModule`. `builder()` is the entry point;
`create(Class)` is deprecated and simply delegates to `builder().build(...)`.

### Resilience annotations

`@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`, and `BackoffStrategy` come from
`dev.vertique.resilience.annotation` and `dev.vertique.resilience` in
`dev.vertique:vertique-resilience` and are shared with
`dev.vertique:vertique-services`. All four annotations are valid on the interface (default for every
method) and on a method (overrides the interface default).

**`@CircuitBreaker`**

| Attribute | Default | Description |
|-----------|---------|-------------|
| `timeoutMs` | `30000` | Per-operation timeout; an operation exceeding it counts as a failure |
| `maxFailures` | `5` | Consecutive failures that open the circuit |
| `resetTimeoutMs` | `10000` | Time before an open circuit goes half-open |

```java
@RestClient(name = "payment-service")
@CircuitBreaker(maxFailures = 3, resetTimeoutMs = 5_000)
public interface PaymentClient {

    @POST
    @Path("/payments")
    Future<Payment> createPayment(PaymentRequest request);

    @POST
    @Path("/refunds")
    @CircuitBreaker(maxFailures = 1, resetTimeoutMs = 2_000)   // per-method override
    Future<Refund> createRefund(RefundRequest request);
}
```

**`@Bulkhead`**

| Attribute | Default | Description |
|-----------|---------|-------------|
| `maxConcurrentCalls` | required | Maximum active logical executions |
| `mode` | `REJECT` | Immediate rejection or bounded FIFO queueing |
| `maxQueueSize` | `0` | Waiting-call limit in `QUEUE` mode; must be `1..1024` there |
| `queueTimeoutMs` | `0` | Maximum queue wait in `QUEUE` mode; must be `1..60000` there |

```java
@RestClient(name = "payment-service")
@Bulkhead(maxConcurrentCalls = 10, mode = Bulkhead.Mode.QUEUE, maxQueueSize = 100, queueTimeoutMs = 500)
public interface PaymentClient {
    // one permit covers the complete request, including retries
}
```

**`@Retry`**

| Attribute | Default | Description |
|-----------|---------|-------------|
| `maxRetries` | `3` | Retries after the initial failure; `3` means up to 4 total attempts |
| `backoff` | `BackoffStrategy.Default.class` | Strategy class needing a public no-arg constructor; `Default` inherits the builder-level strategy |
| `retryOn` | `{}` | When non-empty, only these exception types are retried |
| `abortOn` | `{}` | Exception types that abort retrying immediately, outranking everything else |

The retry decision is evaluated in that order: `abortOn` first, then `retryOn`, and only when
`retryOn` is empty does the builder-level `RestClientRetryPolicy` decide.

Named resilience tiers selected with `@Resilient(policy = "...")` are available on Dagger-managed
REST clients when `ResiliencePoliciesModule` is installed. The client-level `restClient.{name}.retry`
configuration remains the higher-precedence transport override. Standalone builders use an empty
named-policy registry and therefore retain their existing behavior. Named policies and all other
resilience configuration are resolved while `build()` constructs the per-method pipelines; an
unknown policy name fails at client build time.

**`@Timeout`** takes `value` (must be positive) and `unit` (default `MILLISECONDS`). It outranks
`readTimeout(long, TimeUnit)`; external config outranks it.

**`BackoffStrategy`** is a functional interface computing the delay before each retry. `delay(int
retryCount)` receives a 0-based count, so `0` is the delay before the first retry.

| Factory | Description |
|---------|-------------|
| `BackoffStrategy.exponential(delayMs, multiplier, maxDelayMs)` | `min(delayMs × multiplier^retryCount, maxDelayMs) + jitter`, jitter ∈ `[0, min(delay, 1000))` |
| `BackoffStrategy.fixed(delayMs)` | Constant delay |
| `BackoffStrategy.none()` | Retry immediately |
| `BackoffStrategy.Default` | Sentinel meaning "use the builder-level strategy"; never instantiated — `delay()` throws `UnsupportedOperationException` |

```java
public class AggressiveBackoff implements BackoffStrategy {
    @Override
    public long delay(int retryCount) {
        return Math.min(100L * (long) Math.pow(3, retryCount), 5_000L);
    }
}
```

### `RestClientRetryPolicy` and `DefaultRestClientRetryPolicy`

```java
@FunctionalInterface
public interface RestClientRetryPolicy extends dev.vertique.resilience.RetryPolicy {
    boolean shouldRetry(Throwable error, int retryCount);
}
```

The default implementation retries `RestClientConnectionException`, `RestClientTimeoutException`, and
`RestClientResponseException` carrying status `429`, `502`, `503`, or `504`. Everything else — 400,
401, 404, 409, and the rest — is treated as permanent.

```java
RestClientBuilder.create(vertx)
        .baseUrl("http://user-service:8080")
        .retryPolicy((error, retryCount) -> error instanceof RestClientConnectionException)
        .build(UserClient.class);
```

### `@ExpectedStatus`

Per-method status validation, outranking any builder-level `expecting(...)`. The two forms are
mutually exclusive.

| Attribute | Default | Description |
|-----------|---------|-------------|
| `value` | `{}` | Exact accepted status codes (OR) |
| `min` | `-1` | Inclusive lower bound of the accepted range |
| `max` | `-1` | Exclusive upper bound of the accepted range |

```java
@POST
@Path("/items")
@ExpectedStatus({200, 201})
Future<Item> createItem(ItemRequest request);

@GET
@Path("/{id}")
@ExpectedStatus(min = 200, max = 300)
Future<Item> getItem(@PathParam("id") String id);
```

### `@Url`

Per-invocation absolute URI override on a `java.net.URI` parameter. The value replaces the base URL
and path for that one call; `@QueryParam` values on the same method are still merged into the query
string already present in the URI. When every method on the interface takes a `@Url` parameter, the
builder skips base-URL validation entirely.

```java
@RestClient(name = "dynamic-client")
public interface DynamicClient {

    @GET
    @Produces("application/json")
    Future<UserResponse> getUser(@Url URI serviceUrl, @QueryParam("expand") String expand);
}

DynamicClient client = factory.builder().build(DynamicClient.class);

client.getUser(URI.create("https://user-service-a.internal/v2/users/123"), "roles");
client.getUser(URI.create("https://user-service-b.internal/v2/users/456"), null);
```

Rules enforced at `build()` (`IllegalArgumentException`, and a compile error when the codegen
processor is on the path):

1. the parameter type is `java.net.URI`;
2. at most one `@Url` parameter per method;
3. `@Url` is mutually exclusive with `@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, and
   `@BeanParam` **on the same parameter**;
4. `@DefaultValue` is not allowed on a `@Url` parameter;
5. a `@Url` method carries no `@Path` — neither method-level nor on the interface — and no
   `@PathParam` parameter, including `@PathParam` fields inside a `@BeanParam`.

Per invocation the URI must be absolute, use scheme `http` or `https`, carry a non-empty host, and
have no fragment; a violation fails the returned future with `RestClientException`.

### `HttpClientResponse`

Raw response wrapper for methods declared as `Future<HttpClientResponse>` — use it when the status,
headers, or unparsed body matter more than automatic deserialization.

**Methods:** `statusCode()`, `statusMessage()`, `headers()`, `body()`, `bodyAsString()`,
`bodyAs(Class<T>, ObjectMapper)`, `bodyAs(TypeReference<T>, ObjectMapper)`.

```java
@GET
@Path("/{id}")
Future<HttpClientResponse> getRaw(@PathParam("id") String id);

client.getRaw("123").onSuccess(response -> {
    int status = response.statusCode();
    String body = response.bodyAsString();
    MyPojo pojo = response.bodyAs(MyPojo.class, objectMapper);
    List<Item> items = response.bodyAs(new TypeReference<List<Item>>() {}, objectMapper);
});
```

### Parameter support

`ClientParamMeta.ParamSource` classifies each declared parameter. PATH, QUERY, HEADER, and COOKIE
values are serialized through the shared `ParamConversionResolver` — see
[Outbound parameter conversion](#outbound-parameter-conversion).

| Annotation | `ClientParamMeta.ParamSource` | Description |
|------------|-------------------------------|-------------|
| `@PathParam` | `PATH` | Substituted into the path template; resolver-serialized, then URL-encoded |
| `@QueryParam` | `QUERY` | Appended to the query string; collection values expand element by element |
| `@HeaderParam` | `HEADER` | Set as a request header |
| `@CookieParam` | `COOKIE` | Set as a `Cookie` header value |
| `@BeanParam` | `BEAN_PARAM` | Composite; fields and record components expand into sub-parameters, each serialized per its own source |
| `@Url` | `URL` | Per-invocation absolute URI override |
| (unannotated) | `BODY` | Serialized as the request body — JSON, `text/plain`, or `application/octet-stream` per `@Consumes` |

`@DefaultValue` applies to scalar parameters and to `@BeanParam` fields. POJO fields and record
components are both supported.

### Return types and `Optional`

The full generic type of `Future<T>` drives Jackson deserialization:

```java
@GET Future<List<Product>> listProducts();            // → List<Product>
@GET Future<PagedResult<Product>> searchProducts();   // → PagedResult<Product>

@GET
@Path("/{id}")
Future<Optional<Product>> findProduct(@PathParam("id") String id);
```

`Future<Optional<T>>` is first class: a 404 becomes `Optional.empty()` without entering the failure
pipeline or the expectation check.

### Outbound parameter conversion

PATH, QUERY, HEADER, and COOKIE values — including the expanded fields of a `@BeanParam` — are
serialized through `dev.vertique.rest.core.convert.ParamConversionResolver` rather than a bare
`toString()`. The resolver handles built-ins (`UUID`, `java.time.*`, `BigDecimal`, enums, …) plus any
application-registered converter; see `dev.vertique:vertique-rest-core` for the registry and resolver
contract. This is the same resolver `dev.vertique:vertique-rest-jaxrs` uses inbound, so a type round-trips
identically in both directions. Collection-valued values are expanded element by element, each
converted against its component type.

Note that `dev.vertique.rest.core.convert.ParamSource` (the resolver's five conversion-applicable
locations: PATH, QUERY, HEADER, COOKIE, FORM) is a different enum from `ClientParamMeta.ParamSource`
above. `dev.vertique.rest.client.convert.ClientConversionContexts` maps between them and builds the
`ConversionContext` the resolver consumes; BODY, BEAN_PARAM, and URL have no mapping and never reach
the resolver directly.

Which resolver a client gets depends on how it was built:

| Construction path | Effective resolver |
|---|---|
| `RestClientBuilder.create(vertx)` with no converter calls | Built-ins only (`ParamConversionResolver.builtins()`) |
| `create(vertx)` + `paramConverterBinding(...)` / `paramConverterProvider(...)` | Built from the accumulated bindings and providers, insertion order preserved |
| `create(vertx)` + `paramConversionResolver(...)` | The explicit override, used as-is |
| `factory.builder()` | The application-wide Dagger resolver — built-ins plus every `ParamConverterBinding` and `ParamConverterProvider` contributed through `RestCoreModule` — unless an explicit `paramConversionResolver(...)` call overrides it |

#### Invariants & Gotchas

- **Fail-fast at `build()`.** Every declared PATH/QUERY/HEADER/COOKIE parameter, `@BeanParam` fields
  included, is probed with `resolver.canResolve(ctx)` before the proxy is returned. An unresolvable
  parameter throws `RestClientConfigurationException` then, never on first invocation.
- **`ConversionContext` is cached per parameter, not per call.** `ClientParamMeta` instances are
  scan-cached for the JVM lifetime, so repeated calls to the same method reuse one context.

### `RestClientExceptionMapper` and `DefaultRestClientExceptionMapper`

```java
public interface RestClientExceptionMapper {
    <T extends Throwable> RestClientExceptionMapper on(Class<T> type, FailureTranslator<T> translator);
    Throwable translate(Throwable exception);
}
```

Translation walks the exception class hierarchy for the most specific registered translator and
returns the original throwable unchanged when none matches. Every builder starts with a
`DefaultRestClientExceptionMapper`, which composes the concrete `dev.vertique.core.failure.FailureMapper`
and pre-registers:

| Input exception | Output exception |
|----------------|-----------------|
| `java.net.ConnectException` | `RestClientConnectionException` |
| `java.net.UnknownHostException` | `RestClientConnectionException` |
| `java.net.NoRouteToHostException` | `RestClientConnectionException` |
| `java.net.SocketException` | `RestClientConnectionException` |
| `io.vertx.core.http.HttpClosedException` | `RestClientConnectionException` |
| `java.util.concurrent.TimeoutException` (covers Vert.x `NoStackTraceTimeoutException`) | `RestClientTimeoutException` |
| `io.vertx.core.VertxException` | `RestClientException` (safety net) |

---

## Extension Points

### `RestClientInterceptor`

```java
public interface RestClientInterceptor extends dev.vertique.core.extension.OrderedExtension { … }
```

Sorted by the framework `OrderedExtension` order — phase ascending, then priority, then `orderKey`.
Application interceptors default to phase `APPLICATION` and priority `0`. Every callback has a no-op
default, so an implementation overrides only what it needs.

```
Sync observers — exceptions swallowed, cannot abort:
  onRequest(ctx)
  onResponse(request, response)
  onError(request, response, error)
  onAttemptCompleted(request, completion)

Async handlers — affect the outcome:
  beforeRequest(ctx)                        → Future<RestClientRequestContext>
  afterResponse(request, response)          → Future<Void>
  recoverRequest(request, response, error)  → Future<RestClientRequestContext>
  transformError(request, response, error)  → Future<Throwable>
```

`beforeRequest` returns the context passed to the next interceptor, so edits accumulate down the
chain; a failed future aborts the request. `afterResponse` turns a success into an error by failing
its future. `recoverRequest` defaults to a failed future (decline); the first interceptor returning a
succeeded future triggers exactly one recovery re-dispatch with its context and the rest are skipped.
`transformError` must return a *succeeded* future carrying the throwable to propagate.

Order per request:

```
onRequest (sync)
  → beforeRequest chain (async, in order)
    → [HTTP send]
      → onResponse (sync)
        → afterResponse chain (async, in order)
          → [deserialize + validate]

On failure:
  → recoverRequest chain (first succeeded future wins; others skipped)
    → [if recovery accepted: restart from the HTTP send with the new context]
  → onError (sync)
    → transformError chain (async, in order)
      → RestClientExceptionMapper
```

**`onAttemptCompleted`** fires from inside the circuit-breaker send, before the retry/recovery
decision, for every physical attempt that completes — retried failures and recovery re-dispatches
included. Exactly one of `completion.response()` / `completion.error()` is non-null. Implementations
must not block.

`RestClientAttemptCompletion` carries the attempt facts:

```java
public record RestClientAttemptCompletion(
        @Nullable RestClientResponseContext response,  // non-null when the attempt got an HTTP response
        @Nullable Throwable error,                     // non-null on transport failure
        String callId,                                 // unique per logical client call
        int attemptOrdinal,                            // 1-based, spans retries and recovery
        long durationMs,                               // monotonic elapsed time, >= 0
        Instant completedAt,                           // wall-clock instant; use for occurredAt
        RestClientAttemptTarget target) {}
```

Its compact constructor enforces: non-blank `callId`; `attemptOrdinal >= 1`; `durationMs >= 0`;
non-null `completedAt` and `target`; exactly one of `{response, error}` non-null.

`RestClientAttemptTarget` is safe by type — route-level shape only, never the expanded URI, query
string, or concrete path parameter values:

```java
public record RestClientAttemptTarget(
        @Nullable String scheme,        // e.g. "https"; null if undetermined
        @Nullable String host,          // null if undetermined
        int port,                       // -1 for the scheme default / undetermined
        @Nullable String pathTemplate) {}  // e.g. "/users/{id}"; null if unavailable
```

**`RestClientRequestContext`** is the immutable 7-component record seen by `onRequest` and
`beforeRequest`. Modify it with the copy-on-write `with*` methods.

| Method | Description |
|--------|-------------|
| `httpMethod()` | HTTP verb |
| `requestUri()` | Current request URI, query string included |
| `withRequestUri(String)` | Copy with a replaced URI (load balancing, rewriting) |
| `headers()` | Request headers; the record copies them defensively on construction |
| `withHeader(String, String)` / `withHeaders(MultiMap)` | Copy with one header set, or all replaced |
| `body()` | Request body buffer; `null` for bodyless requests |
| `withBody(Buffer)` | Copy with a replaced body |
| `clientName()` / `methodName()` | Logical client name and invoked Java method name |
| `attribute(String)` | Read a caller-controlled attribute |
| `withAttribute(String, Object)` / `withAttributes(Map)` | Copy with one attribute set, or all replaced |

The `attributes` bag is caller-controlled and is how application interceptors pass data down the
chain. Framework-level system context is not placed there — see `RestClientContextCapturer` below.

**`RestClientResponseContext`** is available in `onResponse`, `afterResponse`, `onError`,
`transformError`, and `recoverRequest`; build one from a Vert.x response with
`RestClientResponseContext.from(HttpResponse<Buffer>)`. It exposes `statusCode()`, `statusMessage()`,
`headers()`, and `body()` — the body is never `null`, only possibly empty.

Register globally through the Dagger multibinding, or per client with `builder.register(...)`:

```java
public class BearerTokenInterceptor implements RestClientInterceptor {
    private final TokenProvider tokenProvider;

    @Inject
    BearerTokenInterceptor(TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext ctx) {
        return tokenProvider.currentToken()
                .map(token -> ctx.withHeader("Authorization", "Bearer " + token));
    }
}

@Provides @IntoSet
static RestClientInterceptor bearerTokenInterceptor(BearerTokenInterceptor interceptor) {
    return interceptor;
}
```

Token refresh is the canonical `recoverRequest` use:

```java
@Override
public Future<RestClientRequestContext> recoverRequest(
        RestClientRequestContext request,
        @Nullable RestClientResponseContext response,
        Throwable error) {
    if (response != null && response.statusCode() == 401) {
        return tokenProvider.refreshToken()
                .map(newToken -> request.withHeader("Authorization", "Bearer " + newToken));
    }
    return Future.failedFuture(error);   // decline recovery
}
```

### `RestClientContextCapturer`

A dispatcher-owned SPI for framework modules that must snapshot ambient system context at dispatch
entry and observe each physical attempt with it. The dispatcher calls `captureRequestContext()`
exactly once per logical call — **before any application interceptor runs**, on the caller's Vert.x
context — holds the value in call scope across retries and recovery, and hands it back per attempt.
The captured value never enters the application `attributes` map, so application interceptors cannot
read, replace, drop, or forge it.

```java
public interface RestClientContextCapturer<C> extends dev.vertique.core.extension.OrderedExtension {

    @Nullable
    C captureRequestContext();

    void onAttemptCompleted(
            @Nullable C capturedContext,
            RestClientRequestContext request,
            RestClientAttemptCompletion completion);

    default void onAttemptCompleted(
            @Nullable C capturedContext,
            RestClientRequestContext request,
            RestClientAttemptCompletion completion,
            dev.vertique.core.codegen.MethodMetadata operation) {
        onAttemptCompleted(capturedContext, request, completion);
    }
}
```

Override the four-argument variant when the capturer needs annotation-driven behavior: `operation` is
always supplied by the dispatcher from its own client-method metadata, never by an interceptor and
never derived from `RestClientRequestContext` (which deliberately carries no `java.lang.reflect.Method`).
The default delegates to the three-argument form, so existing implementations keep working.

Framework capturers should set `phase()` to `ExtensionPhase.SYSTEM_FIRST`. Capture precedes the
interceptor chain structurally, so the phase orders capturers relative to each other and marks them as
system extensions — it is not what makes capture happen first. Application-facing per-attempt
observation belongs on `RestClientInterceptor.onAttemptCompleted` instead.

```java
@Provides @IntoSet
static RestClientContextCapturer<?> myContextCapturer(MyContextCapturer capturer) {
    return capturer;
}
```

#### Invariants & Gotchas

- `captureRequestContext()` runs on the caller's Vert.x context — the right place to read ambient
  values such as `ContextHolder` that may no longer be bound after an async hop.
- `onAttemptCompleted` is fire-and-forget; the dispatcher swallows exceptions. Do not block.
- The captured value is held per call, not per attempt: three attempts see the same value.
- An open circuit short-circuits with no send, so `captureRequestContext()` still runs but
  `onAttemptCompleted` never fires for that call.
- This capturer's `onAttemptCompleted` and `RestClientInterceptor.onAttemptCompleted` are independent
  observers of one immutable completion; their relative order is unspecified.

### Failure translation

Layer translators onto the existing mapper with `onFailure(...)` — later registrations win for a
given type, defaults included:

```java
factory.builder()
        .baseUrl("http://user-service:8080")
        .onFailure(RestClientResponseException.class, ex ->
                ex.statusCode() == 404 ? new UserNotFoundException("not found") : ex)
        .onFailure(RestClientConnectionException.class, ex ->
                new UserServiceUnavailableException("user-service unreachable", ex))
        .build(UserClient.class);
```

Or replace the mapper wholesale with `exceptionMapper(...)`, which discards every prior `onFailure`
registration:

```java
RestClientExceptionMapper customMapper = new DefaultRestClientExceptionMapper()
        .on(MyServiceException.class, t -> new DomainException("Service failed", t));

factory.builder()
        .baseUrl("http://my-service:8080")
        .exceptionMapper(customMapper)
        .build(MyServiceClient.class);
```

### Static proxy generation

`dev.vertique:vertique-codegen-rest-client` is an optional annotation processor. For each
`@RestClient` interface in the compilation unit it emits a `public final {Client}_RestClientProxy`
implementing the interface with pre-cached method metadata, plus one `{Bean}_BeanParamAccessor` per
referenced `@BeanParam` type (deduplicated across interfaces) so the `@BeanParam` hot path uses direct
accessor calls rather than `Method.invoke`. Selection is transparent — `build()` prefers the companion
when it is on the classpath — so removing the processor reverts to the JDK proxy with no code change.

Applications inheriting `vertique-app-parent` get the processor facade automatically; custom-parent
applications follow the BOM plus `vertique-codegen-all` recipe in `docs/packaging.md`. See
`dev.vertique:vertique-codegen-rest-client` for generated class shapes and the full pitfall list.

### Generated Dagger bindings

`dev.vertique:vertique-codegen-dagger` emits
`@Provides @Singleton {Interface} provideXxx(RestClientFactory factory)` bindings for `@RestClient`
interfaces; include `GeneratedRestClientsModule.class` in the `@Component`. Annotate an interface with
`@NoAutoWire` to keep a hand-written `@Provides` method canonical for a client that needs custom
builder options — applications using that source-retained opt-out also declare
`vertique-codegen-core` with `provided` scope, per `docs/packaging.md`.

```java
@Module
public class AppModule {

    @Provides
    @Singleton
    static ProductClient productClient(RestClientFactory factory) {
        return factory.builder()
                .baseUrl("http://product-service:8080")
                .readTimeout(10, TimeUnit.SECONDS)
                .onFailure(RestClientResponseException.class, ex ->
                        ex.statusCode() == 503 ? new ProductServiceUnavailableException(ex) : ex)
                .build(ProductClient.class);
    }
}
```

---

## Configuration

External configuration is read from the `restClient` section and has the **highest** priority for
`baseUrl`, `readTimeoutMs`, pool sizing, circuit-breaker parameters, the retry backoff strategy, the
JSON profile, and the `webClient` options bag. The section root *is* the keyed map of client name →
config; there is no wrapper field. The reserved key `restClient.defaults` holds boundary-wide defaults
and is never treated as a client name.

The client name comes from `@RestClient#name()`, or the interface simple name when that is blank. All
fields are optional; an absent field falls through to the builder or annotation value.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `restClient.{name}.baseUrl` | String | `@RestClient` value | Base URL override |
| `restClient.{name}.readTimeoutMs` | long | 30000 | Per-request timeout (ms); must be `> 0` |
| `restClient.{name}.jsonProfile` | String | — | Named JSON mapper profile id |
| `restClient.{name}.webClient.*` | object | — | Vert.x `WebClientOptions` fields (below); merged onto builder-level options |
| `restClient.{name}.circuitBreaker.maxFailures` | int | 5 | Failures before the circuit opens |
| `restClient.{name}.circuitBreaker.timeoutMs` | long | -1 | Per-attempt timeout inside the breaker; -1 = none |
| `restClient.{name}.circuitBreaker.resetTimeoutMs` | long | 10000 | Time before half-open (ms) |
| `restClient.{name}.pool.http1MaxSize` | int | 5 | HTTP/1.1 pool size |
| `restClient.{name}.pool.http2MaxSize` | int | 1 | HTTP/2 pool size |
| `restClient.{name}.pool.maxWaitQueueSize` | int | -1 | Requests queued for a connection; -1 = unbounded |
| `restClient.{name}.pool.eventLoopSize` | int | 0 | Event-loop threads for the pool; 0 = reuse current |
| `restClient.{name}.pool.cleanerPeriodMs` | int | 1000 | Pool cleaner interval (ms); non-positive disables |
| `restClient.{name}.pool.maxLifetimeSeconds` | int | 0 | Max connection lifetime (s); 0 = no limit |
| `restClient.{name}.retry.maxRetries` | int | — | Client-level retry count override, from 0 through 100 |
| `restClient.{name}.retry.backoffStrategy` | String | — | FQCN of a `BackoffStrategy` with a public no-arg constructor, instantiated at `build()` |
| `restClient.defaults.jsonProfile` | String | — | Boundary-wide default profile id for every REST client |

> The `retry` block carries `maxRetries` and `backoffStrategy`. `retryOn` and `abortOn` live on
> `@Retry` and are not read from config. `maxRetries` is bounded to 100.

### `webClient` options

Duration fields use the framework's explicit-unit names (`*Ms`, `*Seconds`); non-duration fields keep
Vert.x's own names. The bag is merged onto any builder-level `WebClientOptions`, so individual fields
can be overridden without discarding the baseline.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ssl` | boolean | false | Enable TLS |
| `verifyHost` | boolean | true | Verify the server hostname |
| `trustAll` | boolean | false | Trust all certificates (development only) |
| `keepAlive` | boolean | true | HTTP keep-alive |
| `keepAliveTimeoutSeconds` | int | 60 | Keep-alive timeout |
| `connectTimeoutMs` | int | 60000 | TCP connect timeout |
| `idleTimeoutSeconds` | int | 0 | Idle connection timeout; 0 disables |
| `readIdleTimeoutSeconds` | int | 0 | Read idle timeout; 0 disables |
| `writeIdleTimeoutSeconds` | int | 0 | Write idle timeout; 0 disables |
| `http2KeepAliveTimeoutSeconds` | int | 60 | HTTP/2 keep-alive timeout |
| `sslHandshakeTimeoutSeconds` | long | 10 | TLS handshake timeout |
| `maxRedirects` | int | 16 | Maximum redirects followed |
| `followRedirects` | boolean | true | Follow HTTP redirects |
| `pemKeyCertOptions.keyPath` / `.certPath` | String | — | PEM client key and certificate for mTLS |
| `keyStoreOptions.path` / `.password` | String | — | JKS client keystore for mTLS |
| `pfxKeyCertOptions.path` / `.password` | String | — | PKCS12 client keystore for mTLS |
| `pemTrustOptions.certPaths` | List\<String\> | — | Trusted CA PEM files |
| `trustStoreOptions.path` / `.password` | String | — | JKS trust store |
| `pfxTrustOptions.path` / `.password` | String | — | PKCS12 trust store |
| `proxyOptions.host` / `.port` | String / int | — | Proxy endpoint |
| `proxyOptions.type` | String | HTTP | `HTTP` or `SOCKS5` |
| `proxyOptions.username` / `.password` | String | — | Proxy credentials |

A raw Vert.x duration key is **rejected**, not silently accepted: writing `connectTimeout` instead of
`connectTimeoutMs` (or any of the other six pairs) fails startup with
`RestClientConfigurationException`.

```json
{
  "restClient": {
    "defaults": { "jsonProfile": "strict" },
    "product-service": {
      "baseUrl": "http://override-host:8080",
      "readTimeoutMs": 5000,
      "webClient": {
        "connectTimeoutMs": 5000,
        "ssl": true,
        "verifyHost": true,
        "trustAll": false
      },
      "circuitBreaker": { "maxFailures": 3, "timeoutMs": 10000, "resetTimeoutMs": 30000 },
      "pool": { "http1MaxSize": 10, "http2MaxSize": 5, "maxWaitQueueSize": 100, "maxLifetimeSeconds": 300 },
      "retry": { "maxRetries": 1, "backoffStrategy": "com.example.AggressiveBackoff" }
    },
    "secureClient": {
      "webClient": {
        "ssl": true,
        "pemKeyCertOptions": { "keyPath": "/etc/tls/client-key.pem", "certPath": "/etc/tls/client-cert.pem" },
        "pemTrustOptions": { "certPaths": ["/etc/tls/ca-cert.pem"] }
      }
    },
    "proxiedClient": {
      "webClient": { "proxyOptions": { "host": "proxy.internal", "port": 8080, "type": "HTTP" } }
    }
  }
}
```

### Overall precedence

| Priority | Source | Applies to |
|----------|--------|-----------|
| 1 (highest) | External config `restClient.{name}.*` | `baseUrl`, `readTimeoutMs`, pool, circuit breaker, retry backoff, `webClient`, `jsonProfile` |
| 2 | `@Retry` / `@Timeout` on the method | retry behavior, timeout |
| 3 | `@Retry` / `@Timeout` on the interface | defaults for every method |
| 4 | `@CircuitBreaker` (method, then interface) | circuit breaker |
| 5 | Builder settings | all |
| 6 (lowest) | Defaults — 30 s timeout, no breaker, `DefaultRestClientRetryPolicy`, exponential backoff | all |

### JSON mapper profile

The `ObjectMapper` used for request serialization and response deserialization is resolved **once at
`build()`**, highest first:

1. explicit `objectMapper(ObjectMapper)` on the builder — the profile registry is never consulted;
2. external config `restClient.{name}.jsonProfile` when non-blank;
3. builder `jsonProfile(JsonProfileId)`;
4. `@JsonProfile("...")` on the `@RestClient` interface **type**;
5. external config `restClient.defaults.jsonProfile`;
6. global `json.jsonProfile` from the `json` config section;
7. `vertx` — `DatabindCodec.mapper()`, the zero-config default.

```java
@JsonProfile("strict")
@RestClient(name = "inventory-service")
@Path("/inventory")
public interface InventoryClient {
    @GET
    @Path("/{id}")
    Future<Item> getItem(@PathParam("id") String id);
}

// Builder-level selection overrides the annotation:
InventoryClient client = factory.builder()
        .baseUrl("http://inventory-service:8080")
        .jsonProfile(JsonProfileId.of("strict"))
        .build(InventoryClient.class);
```

Generated static proxies inherit the resolved mapper; no extra wiring is required.

---

## Failures, Constraints, and Common Mistakes

### Exception hierarchy

```
core.TechnicalException (→ 500)
└── RestClientException
    └── RestClientResponseException   — HTTP 4xx/5xx, or a failed @ExpectedStatus check

core.UnavailableException (→ 503)
└── RestClientUnavailableException
    ├── RestClientConnectionException — connection refused, unknown host, no route, reset
    └── RestClientTimeoutException    — the request exceeded its timeout

core.ConfigurationException
└── RestClientConfigurationException  — wiring, config, or build-time failure
```

`catch (RestClientException)` therefore catches **only** `RestClientResponseException`. Transport
failures are caught at `RestClientUnavailableException` (or `core.UnavailableException`); wiring
failures at `RestClientConfigurationException` (or `core.ConfigurationException`).

`RestClientResponseException` carries the full request and response contexts:

| Method | Description |
|--------|-------------|
| `statusCode()` / `statusMessage()` | HTTP status |
| `responseBody()` | Raw body buffer |
| `headers()` | Response headers |
| `isClientError()` / `isServerError()` | `true` for 400–499 / 500–599 |
| `bodyAs(Class<T>, ObjectMapper)` | Deserialize the body as the given type |
| `problemDetail(Class<T>, ObjectMapper)` | `Optional<T>` — present only when `Content-Type` is `application/problem+json` |
| `requestContext()` / `responseContext()` | The `RestClientRequestContext` / `RestClientResponseContext` |

```java
client.getProduct("123")
        .recover(err -> {
            if (err instanceof RestClientResponseException e && e.statusCode() == 404) {
                return Future.failedFuture(new ProductNotFoundException("123"));
            }
            return Future.failedFuture(err);
        });
```

### Build-time failures

| Failure | Cause |
|---|---|
| `IllegalArgumentException` | not an interface; a method that does not return `Future<T>`; no base URL resolvable; any `@Url` placement rule violated |
| `RestClientConfigurationException` | a parameter type with no resolvable converter; a non-`vertx` JSON profile selected on a standalone builder with no registry; a method-level `@JsonProfile` on a `@RestClient` interface; a `retry.backoffStrategy` FQCN that cannot be loaded or instantiated; a raw Vert.x duration key in the `webClient` bag |
| `JsonProfileConfigurationException` | the selected profile id is unknown to the registry |
| `ConfigurationException` | a blank `restClient.{name}` key, or `readTimeoutMs <= 0` |

None of these defer to the first request.

`RestClientDefaultProfileValidator` additionally validates `restClient.defaults.jsonProfile` during
the startup `VALIDATE` phase, so an unknown boundary default fails even when zero clients are
configured or every client overrides it.

### Common mistakes

- **Catching `RestClientException` and expecting transport errors.** It covers response failures only;
  see the hierarchy above.
- **Reading system context inside `beforeRequest`.** By then the dispatch may have hopped contexts.
  Framework modules capture at dispatch entry with `RestClientContextCapturer`.
- **Expecting `onResponse` or `onError` to count attempts.** `onResponse` never fires on a transport
  failure and `onError` fires once after every retry; only `onAttemptCompleted` sees each wire attempt.
- **Expecting `onAttemptCompleted` on an open circuit.** A short-circuited call performs no send, so
  the hook never fires. When the breaker's own timeout is shorter than the HTTP timeout, the hook can
  also fire *after* the caller already saw the breaker failure.
- **Returning a failed future from `transformError`.** It must return a succeeded future carrying the
  throwable to propagate.
- **Setting `objectMapper(...)` and expecting a `jsonProfile` to still apply.** An explicit mapper
  outranks every profile source.
- **Using raw Vert.x duration names in `webClient`.** `connectTimeout`, `idleTimeout`, and their five
  siblings are rejected at startup; use the explicit-unit keys.
- **Assuming the generated proxy behaves identically for a `null` path value.** A `null` PATH
  parameter without `@DefaultValue` throws `NullPointerException` in a generated proxy, where the JDK
  proxy historically dropped the segment. Add `@DefaultValue` or guard before calling.
- **Expecting a generated proxy for an interface with an external `@BeanParam` type.** When a
  `@BeanParam` type lives outside the compilation unit the processor cannot scan its fields, emits a
  compile-time `WARNING`, and skips proxy emission for that whole interface; it falls back to the JDK
  proxy at runtime.

---

## Module Dagger Bindings

`RestClientModule` is declared `@Module(includes = {JsonRuntimeModule.class, RestCoreModule.class})`.
`JsonRuntimeModule` supplies the `JsonMapperProfileRegistry`; `RestCoreModule` supplies the
`ParamConversionResolver` and its converter multibindings, shared with `dev.vertique:vertique-rest-jaxrs`.

| Type | Scope | Description |
|------|-------|-------------|
| `RestClientFactory` | `@Singleton` | Creates builders pre-seeded with global interceptors and capturers, the `restClient` config index, the profile registry, the `restClient.defaults` record, `JsonConfig`, the `ParamConversionResolver`, and the optional `BeanValidator` |
| `Map<String, RestClientConfig>` | `@Singleton` | The typed `restClient.{name}` index parsed at the provider boundary; the only place this module reads the raw `@VertxConfig JsonObject` |
| `RestClientDefaults` | `@Singleton` | The parsed reserved `restClient.defaults` sub-object |
| `BeanParamAccessorRegistry` | `@Singleton` | Process-wide generated-accessor lookup cache shared by DI and standalone clients |
| `ComposeValidator` (`RestClientDefaultProfileValidator`) | `@Singleton` `@IntoSet` | Fails the `VALIDATE` phase on an unknown `restClient.defaults.jsonProfile` |
| `Set<RestClientInterceptor>` | — | Empty-by-default multibinding for global application interceptors |
| `Set<RestClientContextCapturer<?>>` | — | Empty-by-default multibinding for framework-owned system capturers |
| `BeanValidator` | `@BindsOptionalOf` | Satisfied by the validation module when present; enables response validation |

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    RestClientModule.class,
    AppModule.class
})
interface AppComponent {
    RestClientFactory restClientFactory();
}
```

---

## Dependencies

| Dependency | Why |
|---|---|
| `dev.vertique:vertique-core` | `OrderedExtension`/`ExtensionPhase` ordering, the exception hierarchy, `FailureMapper` behind the default exception mapper, `ConfigParser`, `core.codegen` metadata SPI, optional `BeanValidator` |
| `dev.vertique:vertique-resilience` | Canonical timeout, retry, circuit-breaker, and bulkhead annotations plus backoff and retry contracts |
| `dev.vertique:vertique-rest-core` | `ParamConversionResolver`, `ParamConverterRegistry`, `ConversionContext`, `ParamSource`, and `RestCoreModule` — the outbound half of the conversion stack `rest-jaxrs` uses inbound |
| `dev.vertique:vertique-json` | `JsonMapperProfileRegistry`, `JsonConfig`, and `JsonRuntimeModule` for named mapper profiles |
| `io.vertx:vertx-web-client` | `WebClient`, `WebClientOptions`, `PoolOptions` |
| `jakarta.ws.rs:jakarta.ws.rs-api` | The JAX-RS annotations that describe each interface |
| `jakarta.annotation:jakarta.annotation-api` | `@Nullable` on API signatures |
| `com.google.dagger:dagger` | `@Module` / `@Multibinds` declarations |
| `org.projectlombok:lombok` | `provided` scope — accessors and logging; not a runtime dependency |
