<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Client Module

> **Status:** Implemented
> **Package:** `dev.vertique.rest.client`
> **Artifact:** `rest-client`
> **Depends on:** core

Declarative HTTP client that creates type-safe proxies from JAX-RS-annotated interfaces, backed by Vert.x WebClient. Analogous to Spring `@HttpExchange` / MicroProfile `@RegisterRestClient`. Annotate a Java interface with standard JAX-RS annotations (`@GET`, `@Path`, `@QueryParam`, etc.) and `@RestClient`, then call `RestClientBuilder.create(vertx)` or `factory.builder()` to obtain a JDK proxy that translates method invocations into asynchronous HTTP calls.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.rest.client` | `@RestClient`, `@ExpectedStatus`, `RestClientRetryPolicy`, `DefaultRestClientRetryPolicy`, `RestClientBuilder`, `RestClientFactory`, `RestClientModule`, `RestClientExceptionMapper`, `DefaultRestClientExceptionMapper`, `HttpClientResponse` |
| `dev.vertique.rest.client.exception` | `RestClientException`, `RestClientResponseException`, `RestClientUnavailableException`, `RestClientConnectionException`, `RestClientTimeoutException`, `RestClientConfigurationException` |
| `dev.vertique.rest.client.config` | `RestClientConfig`, `RestClientCircuitBreakerConfig`, `RestClientPoolConfig`, `RestClientRetryConfig` |
| `dev.vertique.core.resilience` | `@CircuitBreaker`, `@Retry`, `@Timeout`, `BackoffStrategy` — shared with `services`; see `dev.vertique:vertique-core` |
| `dev.vertique.rest.client.interceptor` | `RestClientInterceptor`, `RestClientContextCapturer`, `RestClientRequestContext`, `RestClientResponseContext`, `RestClientAttemptCompletion`, `RestClientAttemptTarget` |
| `dev.vertique.rest.client.meta` | `ClientInterfaceScanner`, `ClientMethodMeta`, `ClientParamMeta` |
| `dev.vertique.rest.client.convert` | `ClientConversionContexts` — builds `ConversionContext` instances for the outbound `ParamConversionResolver` (see `dev.vertique:vertique-rest-core`) |
| `dev.vertique.rest.core.convert` | `ParamConversionResolver`, `ParamConverterRegistry`, `ParamConverterBinding`, `ConversionContext`, `ParamSource` — shared with `rest-jaxrs`; see `dev.vertique:vertique-rest-core` |

---

## Typed Config Records (`dev.vertique.rest.client.config`)

The `restClient` section is parsed at the `RestClientModule` / `RestClientFactory` boundary into a `Map<String, RestClientConfig>` index via `RestClientConfig.indexFromConfig`. Module internals depend on this typed index, never on the raw `@VertxConfig JsonObject`.

The `restClient` section is itself the keyed map (client names at the root); there is no wrapper field. `indexFromConfig` calls `parser.parseKeyedObject(section, "name", RestClientConfig.class)` (via the injected `ConfigParser`) to inject each entry key into `RestClientConfig.name()`, then reads the per-entry `webClient` subtree directly from the source section (because a `JsonObject` field cannot round-trip through the isolated config mapper), normalizes its 7 duration keys via `normalizeWebClientKeys`, and attaches the result.

| Record | Fields |
|--------|--------|
| `RestClientConfig` | `name` (injected key), `baseUrl` (nullable), `readTimeoutMs` (nullable, must be > 0), `circuitBreaker` (`RestClientCircuitBreakerConfig`), `pool` (`RestClientPoolConfig`), `webClient` (`@JsonIgnore JsonObject`, normalized at boundary), `retry` (`RestClientRetryConfig`) |
| `RestClientDefaults` | `jsonProfile` (nullable) — carries the `restClient.defaults.jsonProfile` per-boundary default; parsed from the reserved `restClient.defaults` sub-object, which is excluded from the keyed-client map |
| `RestClientCircuitBreakerConfig` | `maxFailures`, `timeoutMs`, `resetTimeoutMs`, `maxRetries` |
| `RestClientPoolConfig` | `http1MaxSize`, `http2MaxSize`, `maxWaitQueueSize`, `eventLoopSize`, `cleanerPeriodMs`, `maxLifetimeSeconds` |
| `RestClientRetryConfig` | `backoffStrategy` (FQCN string) |

**Duration-key normalization:** the 7 framework-normalized field names in the `webClient` bag are renamed to Vert.x-native names at parse time: `connectTimeoutMs`→`connectTimeout`, `idleTimeoutSeconds`→`idleTimeout`, `readIdleTimeoutSeconds`→`readIdleTimeout`, `writeIdleTimeoutSeconds`→`writeIdleTimeout`, `keepAliveTimeoutSeconds`→`keepAliveTimeout`, `http2KeepAliveTimeoutSeconds`→`http2KeepAliveTimeout`, `sslHandshakeTimeoutSeconds`→`sslHandshakeTimeout`. Non-duration fields pass through unchanged.

`RestClientConfig.toString()` redacts `webClient` (recursively, via `ConfigSecretRenderer.redactBag`) and `baseUrl` userinfo/credential query-params (via `ConfigSecretRenderer.redactUri`). The live `webClient()` and `baseUrl()` accessors are untouched.

---

## How It Works

1. Annotate a Java interface with `@RestClient` (and optionally `@CircuitBreaker`, `@Retry`) and declare methods using standard JAX-RS annotations
2. Wire `RestClientModule` in the Dagger `@Component`
3. Inject `RestClientFactory` and call `factory.builder().baseUrl(...).build(ClientInterface.class)`
4. The builder scans the interface with `ClientInterfaceScanner` (cached per interface), configures a `RestClientExceptionMapper`, creates a Vert.x `WebClient`, and returns a JDK proxy backed by `RestClientProxy`
5. Each method invocation builds the request URI, applies parameters and headers, runs interceptors, sends the request, deserializes and validates the response

---

## Request Execution Pipeline

Each method invocation follows this pipeline:

```
1. Build path, query, headers, body from metadata + args
   (path/query/header/cookie values are serialized through the shared ParamConversionResolver)
2. Create RestClientRequestContext (immutable record)
3. [Sync] onRequest observers (fire-and-forget, exceptions swallowed)
4. [Async] beforeRequest handlers (return new context copy; failed future aborts)
5. Build HttpRequest from (possibly rewritten) URI
6. Apply per-method or builder-level timeout
7. Send inside circuit breaker (if configured); retry loop applies here
8. Apply Expectation (method-level @ExpectedStatus or builder default)
9. [Sync] onResponse observers
10. [Async] afterResponse handlers (failed future turns success into error)
11. Handle Optional<T>: 404 → Optional.empty()
12. Deserialize body using per-client ObjectMapper
13. Validate with BeanValidator (if configured)
14. Return successful Future

On any failure (steps 7–13):
15. [Async] recoverRequest handlers (first interceptor that succeeds triggers one recovery retry)
16. [Sync] onError observers
17. [Async] transformError handlers (may replace the error)
18. RestClientExceptionMapper translates transport exceptions to typed subclasses
19. Return failed Future
```

---

## Key Classes

### `@RestClient`

Type-level annotation that marks an interface as a declarative REST client.

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

| Attribute | Default | Description |
|-----------|---------|-------------|
| `name` | `""` | Logical name for config lookup and circuit breaker naming; defaults to interface simple name when blank |
| `value` | `""` | Default base URL (e.g. `http://host:8080`); overridden by `builder.baseUrl(...)` or external config |

### `@CircuitBreaker`

Defined in `dev.vertique.core.resilience`. Enables circuit breaker protection. Can be placed on the interface (shared breaker for all methods) or on an individual method (dedicated breaker, overrides interface-level). Retry behavior is configured separately via `@Retry`.

```java
@RestClient(name = "payment-service")
@CircuitBreaker(maxFailures = 3, resetTimeoutMs = 5_000)
public interface PaymentClient {

    @POST
    @Path("/payments")
    Future<Payment> createPayment(PaymentRequest request);

    @POST
    @Path("/refunds")
    @CircuitBreaker(maxFailures = 1, resetTimeoutMs = 2_000)  // per-method override
    Future<Refund> createRefund(RefundRequest request);
}
```

| Attribute | Default | Description |
|-----------|---------|-------------|
| `timeoutMs` | `30000` | Per-operation timeout; operations that do not complete within this window count as failures |
| `maxFailures` | `5` | Consecutive failures required to open the circuit |
| `resetTimeoutMs` | `10000` | Time before the open circuit transitions to half-open |

**Priority order (highest wins):**
1. External JSON config (`restClient.{name}.methods.{method}.circuitBreaker`)
2. `@CircuitBreaker` on the method
3. `@CircuitBreaker` on the interface
4. Builder-level `circuitBreaker(options)`

### `@Retry`

Defined in `dev.vertique.core.resilience`. Configures per-method or per-interface retry behavior. When placed on a method, it overrides any interface-level `@Retry`. When placed on the interface, it applies as the default for all methods without their own annotation.

```java
@RestClient(name = "user-service")
@Retry(maxRetries = 3)                          // interface-level default
public interface UserClient {

    @GET
    @Path("/{id}")
    Future<UserResponse> getUser(@PathParam("id") String id);

    @POST
    @Path("/bulk")
    @Retry(maxRetries = 5, backoff = AggressiveBackoff.class)  // method override
    Future<List<UserResponse>> bulkCreate(List<UserRequest> requests);

    @DELETE
    @Path("/{id}")
    @Retry(maxRetries = 0)                      // disable retry for this method
    Future<Void> deleteUser(@PathParam("id") String id);
}
```

| Attribute | Default | Description |
|-----------|---------|-------------|
| `maxRetries` | `3` | Maximum retry attempts after initial failure; `maxRetries = 3` means up to 4 total attempts |
| `backoff` | `BackoffStrategy.Default.class` | `BackoffStrategy` class (must have public no-arg constructor); `Default` inherits builder-level strategy |
| `retryOn` | `{}` (empty) | Exception types that trigger a retry; when non-empty, only matching types are retried; when empty, delegates to `RestClientRetryPolicy` |
| `abortOn` | `{}` (empty) | Exception types that immediately abort retries, regardless of `retryOn` or the policy; highest priority |

**Retry decision logic (priority order):**
1. `abortOn` — if the error matches, abort immediately (highest priority)
2. `retryOn` — if non-empty and the error matches, retry
3. If `retryOn` is empty, delegate to the builder-level `RestClientRetryPolicy`

### `BackoffStrategy`

Defined in `dev.vertique.core.resilience`. Functional interface that computes the delay in milliseconds before each retry attempt. The `delay(int retryCount)` method receives the 0-based retry count (0 = evaluating delay before the first retry).

```java
// Custom strategy via no-arg constructor (referenced from @Retry#backoff)
public class AggressiveBackoff implements BackoffStrategy {
    @Override
    public long delay(int retryCount) {
        return Math.min(100L * (long) Math.pow(3, retryCount), 5_000L);
    }
}

@Retry(maxRetries = 5, backoff = AggressiveBackoff.class)
@GET
Future<User> getUser(@PathParam("id") String id);
```

**Built-in factory methods:**

| Factory | Description |
|---------|-------------|
| `BackoffStrategy.exponential(delayMs, multiplier, maxDelayMs)` | Exponential backoff with jitter. Default: 500 ms base, 2.0 multiplier, 30 s cap. Formula: `min(delayMs × multiplier^retryCount, maxDelayMs) + jitter` where jitter ∈ `[0, min(delay, 1000))` |
| `BackoffStrategy.fixed(delayMs)` | Constant delay between every retry |
| `BackoffStrategy.none()` | No delay; retries immediately |
| `BackoffStrategy.Default` | Sentinel class; signals "use builder-level strategy". Never instantiated; `delay()` throws `UnsupportedOperationException` |

### `RestClientRetryPolicy`

Extends core `RetryPolicy`. Determines whether a failed request should be retried. Consulted only when `@Retry#retryOn` is empty. Takes priority below `@Retry#abortOn` and `@Retry#retryOn`.

```java
@FunctionalInterface
public interface RestClientRetryPolicy extends RetryPolicy {
    boolean shouldRetry(Throwable error, int retryCount);
}
```

Custom policy example:

```java
RestClientBuilder.create(vertx)
    .baseUrl("http://user-service:8080")
    // Only retry connection errors, never HTTP errors
    .retryPolicy((error, retryCount) -> error instanceof RestClientConnectionException)
    .build(UserClient.class);
```

### `DefaultRestClientRetryPolicy`

Default implementation that retries common transient failures:

| Condition | Retried |
|-----------|---------|
| `RestClientConnectionException` | Yes — transport-level errors (connection refused, unknown host) |
| `RestClientTimeoutException` | Yes — request timeout exceeded |
| `RestClientResponseException` with status 429 | Yes — rate limited |
| `RestClientResponseException` with status 502 | Yes — bad gateway |
| `RestClientResponseException` with status 503 | Yes — service unavailable |
| `RestClientResponseException` with status 504 | Yes — gateway timeout |
| All other failures (400, 401, 404, 409, etc.) | No — considered permanent |

### `@ExpectedStatus`

Per-method override for response status validation. Takes precedence over any default expectation set on the builder. Two mutually exclusive forms:

```java
// Exact status codes (OR logic)
@POST
@Path("/items")
@ExpectedStatus({200, 201})
Future<Item> createItem(ItemRequest request);

// Range (inclusive min, exclusive max)
@GET
@Path("/{id}")
@ExpectedStatus(min = 200, max = 300)
Future<Item> getItem(@PathParam("id") String id);
```

| Attribute | Default | Description |
|-----------|---------|-------------|
| `value` | `{}` | One or more exact status codes; empty means unused |
| `min` | `-1` | Inclusive lower bound of acceptable range |
| `max` | `-1` | Exclusive upper bound of acceptable range |

### `@Url`

Per-invocation absolute URI override. When a method parameter is annotated with `@Url`, the value replaces the client's base URL (and any `@Path` on the interface/method) for that single call. Any `@QueryParam` parameters on the same method are still appended to the overridden URL. The base URL set on the builder is still required for initialization unless every method on the interface carries a `@Url` parameter, in which case builder-level `baseUrl` validation is skipped.

**Scanner validation rules (enforced at `build()` time):**
1. Parameter type must be `java.net.URI`
2. Mutually exclusive with `@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, and `@BeanParam` on the same parameter
3. `@DefaultValue` is not allowed on a `@Url` parameter
4. `@Path` on the interface or method is not allowed when `@Url` is present on any parameter of that method

**Runtime validation (per invocation):**
- URI must be absolute (scheme present)
- Scheme must be `http` or `https`
- Host must be non-empty
- Fragment (`#...`) is not allowed

```java
@RestClient(name = "dynamic-client")
public interface DynamicClient {

    @GET
    @Produces("application/json")
    Future<UserResponse> getUser(@Url URI serviceUrl, @QueryParam("expand") String expand);

    @POST
    @Consumes("application/json")
    @Produces("application/json")
    Future<UserResponse> createUser(@Url URI serviceUrl, UserRequest body);
}

// Usage — base URL validation is skipped because every method has @Url:
DynamicClient client = factory.builder()
    .build(DynamicClient.class);

client.getUser(URI.create("https://user-service-a.internal/v2/users/123"), "roles");
client.getUser(URI.create("https://user-service-b.internal/v2/users/456"), null);
```

`@QueryParam` values are merged with the query string already present in the `@Url` value.

### `@Timeout`

Defined in `dev.vertique.core.resilience`. Per-method or per-interface timeout override. When placed on the interface, it applies as the default for all methods that do not carry their own `@Timeout`. When placed on a method, it overrides any interface-level default. Takes precedence over the builder-level `readTimeout(long, TimeUnit)` setting. External JSON config has the highest priority and can override this annotation at runtime.

```java
@POST
@Path("/reports/generate")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
Future<Report> generateReport(ReportRequest request);
```

| Attribute | Default | Description |
|-----------|---------|-------------|
| `value` | — | Timeout duration; must be positive |
| `unit` | `MILLISECONDS` | Time unit for `value` |

### `RestClientBuilder`

Fluent builder — the primary API for constructing REST client proxies. Obtain via `RestClientBuilder.create(vertx)` for standalone use, or via `factory.builder()` when using Dagger (which pre-seeds global interceptors, external config, and an optional `BeanValidator`).

Each call to `build(Class)` creates a fresh `WebClient` instance. Builder state is never mutated by `build()`, so the same builder can be reused to create multiple proxies.

**Builder method reference:**

| Method | Description |
|--------|-------------|
| `RestClientBuilder.create(Vertx)` | Static factory; equivalent to `new RestClientBuilder(vertx)` |
| `baseUrl(String)` | Base URL for all requests; overrides `@RestClient#value()` and is itself overridable by external config |
| `readTimeout(long, TimeUnit)` | Default request timeout; overridden per-method by `@Timeout`; default 30 s |
| `objectMapper(ObjectMapper)` | Per-client Jackson mapper; falls back to `DatabindCodec.mapper()` when absent |
| `beanValidator(BeanValidator)` | Validates deserialized response objects; skipped when absent |
| `webClientOptions(WebClientOptions)` | TLS, connect timeout, keep-alive, and other transport settings |
| `poolOptions(PoolOptions)` | HTTP/1 and HTTP/2 connection pool sizing |
| `defaultHeader(String, String)` | Header applied to every request; multiple calls with the same name overwrite |
| `register(RestClientInterceptor)` | Adds a per-client interceptor (in addition to global Dagger interceptors) |
| `registerCapturer(RestClientContextCapturer<?>)` | Adds a per-client system-owned context capturer |
| `exceptionMapper(RestClientExceptionMapper)` | Replaces the entire exception mapper (discards all prior `onFailure` registrations); use when a completely custom strategy is needed |
| `onFailure(Class<T>, FailureTranslator<T>)` | Registers a translator on the current `RestClientExceptionMapper`; overrides defaults for the same type (last registered wins) |
| `expecting(Expectation<HttpResponseHead>)` | Default response expectation; overridden per-method by `@ExpectedStatus` |
| `circuitBreaker(CircuitBreakerOptions)` | Builder-level baseline circuit breaker (lowest priority) |
| `retryPolicy(RestClientRetryPolicy)` | Builder-level retry policy consulted when `@Retry#retryOn` is empty; defaults to `DefaultRestClientRetryPolicy` |
| `backoffStrategy(BackoffStrategy)` | Builder-level backoff strategy used when `@Retry#backoff` is `BackoffStrategy.Default`; defaults to exponential (500 ms, ×2, max 30 s) |
| `jsonProfile(JsonProfileId)` | Selects a named JSON mapper profile by id; resolved at `build()` from the seeded `JsonMapperProfileRegistry`; overridden by explicit `objectMapper(...)` and by external config `jsonProfile` |
| `config(RestClientConfig)` | Typed per-client overrides, parsed from the `restClient.{name}` section at the `RestClientModule` provider boundary |
| `paramConversionResolver(ParamConversionResolver)` | Sets an explicit outbound parameter conversion resolver, bypassing `paramConverterBinding`/`paramConverterProvider` accumulation; `RestClientFactory.builder()` seeds this with the Dagger-provided app-wide resolver |
| `paramConverterBinding(ParamConverterBinding<T>)` | Adds a native typed converter binding for outbound path/query/header/cookie serialization (standalone use; ignored when `paramConversionResolver` is set) |
| `paramConverterProvider(ParamConverterProvider)` | Adds a JAX-RS `ParamConverterProvider` for outbound serialization (standalone use; ignored when `paramConversionResolver` is set) |
| `build(Class<T>)` | Builds and returns the typed proxy |

```java
// Standalone usage
UserClient client = RestClientBuilder.create(vertx)
    .baseUrl("http://user-service:8080")
    .readTimeout(5, TimeUnit.SECONDS)
    .objectMapper(customMapper)
    .register(new LoggingInterceptor())
    .build(UserClient.class);

// Dagger-injected (pre-seeds global interceptors, config, and optional BeanValidator)
UserClient client = factory.builder()
    .baseUrl("http://user-service:8080")
    .build(UserClient.class);
```

### Outbound Parameter Conversion

Outbound `@PathParam`, `@QueryParam`, `@HeaderParam`, and `@CookieParam` values (including their
expanded `@BeanParam` fields) are serialized through the shared `dev.vertique.rest.core.convert.ParamConversionResolver`
(`vertique-rest-core`) instead of a bare `Object.toString()`. The resolver converts built-in types
(`UUID`, `java.time.*`, `BigDecimal`, enums, etc.) and any application-registered converter — see
`dev.vertique:vertique-rest-core` for the full registry/resolver contract. This is the same mechanism
`rest-jaxrs` uses on the inbound side, so a type round-trips identically whether it is read from a
request or sent on one. Collection-valued query/bean-param values are expanded element-by-element,
each element serialized via its component-type `ConversionContext`.

Both proxy flavors serialize through the **same** resolver:

- **JDK reflective proxy** — `RestClientRequestFactory` resolves and caches a `ConversionContext`
  per `ClientParamMeta` (scalar and per-element caches) and calls `resolver.toString(value, ctx)`
  before URL-encoding or setting headers/cookies.
- **Generated static proxy** — calls `DefaultRestClientDispatcher.applyPathParam` /
  `applyQueryParam` / `applyHeaderParam` / `applyCookieParam`, which look up the `ClientParamMeta`
  by wire name and serialize through the identical resolver (the dispatcher is the single outbound
  "chokepoint" — see ADR-0142).

**Resolver source, by construction path:**

| How the client was built | Effective resolver |
|---|---|
| `RestClientBuilder.create(vertx)` (standalone, no `paramConversionResolver`/bindings/providers) | Built-ins-only (`ParamConversionResolver.builtins()`) |
| `RestClientBuilder.create(vertx)` + `paramConverterBinding(...)` / `paramConverterProvider(...)` | Built from the accumulated bindings/providers, insertion order preserved |
| `RestClientBuilder.create(vertx)` + `paramConversionResolver(...)` | The explicit override, used as-is |
| `factory.builder()` (Dagger) | The Dagger-provided app-wide resolver (built-ins + every `ParamConverterBinding`/`ParamConverterProvider` contributed via `RestCoreModule` multibindings), unless overridden by an explicit `paramConversionResolver(...)` call |

#### Invariants & Gotchas

- **Fail-fast at `build()`.** Every declared PATH/QUERY/HEADER/COOKIE parameter (including
  `@BeanParam` fields) is probed against the effective resolver via `resolver.canResolve(ctx)`
  before the proxy is returned. A parameter with no resolvable converter throws
  `RestClientConfigurationException` at `build()` time, not on first invocation.
- **`ClientConversionContexts`** (`dev.vertique.rest.client.convert`) is the client-side mirror of
  `rest-jaxrs`'s `ConversionContexts`: it maps `ClientParamMeta.ParamSource` to the framework-neutral
  `ParamSource` and builds the `ConversionContext` the resolver consumes. Only PATH/QUERY/HEADER/COOKIE
  are conversion-applicable; BODY, BEAN_PARAM, and URL never reach the resolver directly (a
  `@BeanParam`'s individual fields are converted, not the bean itself).
- **`ConversionContext` is cached per parameter, not per call.** Both the JDK reflective path
  (`RestClientRequestFactory`) and the build-time validation loop build a `ConversionContext` once
  per `ClientParamMeta` — `ClientParamMeta` instances are scan-cached and stable for the JVM lifetime,
  so repeated calls to the same method reuse the same context.

### JSON Profile Mapper Precedence

The `ObjectMapper` used for request serialization and response deserialization is resolved **once at `build()` time** from the following precedence chain (highest wins):

1. **Explicit `objectMapper(ObjectMapper)`** on the builder — used directly; the profile registry is never consulted.
2. **External config `restClient.{name}.jsonProfile`** — non-blank string read from application config at the `RestClientModule` boundary.
3. **Builder `jsonProfile(JsonProfileId)`** — set programmatically on the builder.
4. **`@JsonProfile("...")` annotation on the interface (TYPE-level)** — non-blank value; placed on the `@RestClient` interface type, not on individual methods (a method-level `@JsonProfile` on a `@RestClient` interface is rejected at client-build time — see FR-JSON-066 and ADR-0138).
5. **External config `restClient.defaults.jsonProfile`** — the reserved `restClient.defaults` sub-object's per-boundary default, parsed via `ConfigParser` into a typed `RestClientDefaults` record (the `defaults` key is excluded from the keyed-client map).
6. **Global `json.jsonProfile`** — the framework-wide default from the `json` config section (`JsonConfig`).
7. **`vertx` default** — `DatabindCodec.mapper()`, the shared Vert.x global codec mapper (the zero-config default).

**Fail-fast at `build()` time:**

- A non-`vertx` profile selected on a standalone `RestClientBuilder` (no Dagger — no seeded `JsonMapperProfileRegistry`) throws `RestClientConfigurationException` immediately.
- An id the registry does not know throws `JsonProfileConfigurationException` immediately.
- A method-level `@JsonProfile` on a `@RestClient` interface throws `RestClientConfigurationException` immediately (FR-JSON-066).
- Neither failure defers to the first request.

**Codegen static proxies** (`vertique-codegen-rest-client`) inherit the resolved mapper from the dispatcher — no additional wiring is required.

**Example:**

```java
// Interface-level profile via @JsonProfile annotation (TYPE-level only)
@JsonProfile("strict")
@RestClient(name = "inventory-service")
@Path("/inventory")
public interface InventoryClient {
    @GET
    @Path("/{id}")
    Future<Item> getItem(@PathParam("id") String id);
}

// Builder-level profile via fluent API (overrides @JsonProfile)
InventoryClient client = factory.builder()
    .baseUrl("http://inventory-service:8080")
    .jsonProfile(JsonProfileId.of("strict"))
    .build(InventoryClient.class);

// External config (highest among profile sources):
// { "restClient": { "inventory-service": { "jsonProfile": "strict" } } }
```

### `RestClientFactory`

Singleton factory provided by `RestClientModule`. Pre-seeds every builder it creates with:

- Global `RestClientInterceptor` instances from the Dagger `Set<RestClientInterceptor>` multibinding
- Global `RestClientContextCapturer<?>` instances from the Dagger `Set<RestClientContextCapturer<?>>` multibinding
- The `@VertxConfig JsonObject` for `restClient.*` config overrides
- The optional `BeanValidator` if the `validation` module is present

```java
@Inject RestClientFactory clientFactory;

UserClient userClient = clientFactory.builder()
    .baseUrl("http://user-service:8080")
    .readTimeout(5, TimeUnit.SECONDS)
    .build(UserClient.class);
```

`RestClientFactory.create(Class<T>)` is deprecated — use `builder()` instead.

### `HttpClientResponse`

Raw response wrapper for methods that return `Future<HttpClientResponse>`. Use when you need the status code, headers, or raw body rather than automatic JSON deserialization.

```java
@GET
@Path("/{id}")
Future<HttpClientResponse> getRaw(@PathParam("id") String id);

// Usage:
client.getRaw("123").onSuccess(response -> {
    int status = response.statusCode();
    String body = response.bodyAsString();
    MyPojo pojo = response.bodyAs(MyPojo.class, objectMapper);
    List<Item> items = response.bodyAs(new TypeReference<List<Item>>() {}, objectMapper);
});
```

**Methods:** `statusCode()`, `statusMessage()`, `headers()`, `body()`, `bodyAsString()`, `bodyAs(Class<T>, ObjectMapper)`, `bodyAs(TypeReference<T>, ObjectMapper)`.

### Exception Hierarchy

```
core.TechnicalException (→ 500)
└── RestClientException
    └── RestClientResponseException   — HTTP 4xx/5xx or failed ExpectedStatus

core.UnavailableException (→ 503)
└── RestClientUnavailableException
    ├── RestClientConnectionException — connection refused, unknown host, no route, connection reset
    └── RestClientTimeoutException    — request exceeded configured timeout

core.ConfigurationException
└── RestClientConfigurationException  — wiring/startup failure (broken generated class)
```

**Breaking-change note:** `catch (RestClientException)` catches only `RestClientResponseException`.
It does **not** catch `RestClientConnectionException`, `RestClientTimeoutException` (which now extend
`RestClientUnavailableException → core.UnavailableException`), or
`RestClientConfigurationException` (which extends `core.ConfigurationException`). The
semantic-root catch point for transport failures is `RestClientUnavailableException` (or its
`core.UnavailableException` parent); for wiring failures it is `RestClientConfigurationException`
(or its `core.ConfigurationException` parent). See
ADR-0112: Framework Exception Hierarchy and REST Mapping.

**`RestClientResponseException`** carries full request and response contexts:

| Method | Description |
|--------|-------------|
| `statusCode()` | HTTP status code |
| `statusMessage()` | HTTP status message |
| `responseBody()` | Raw response body buffer |
| `headers()` | Response headers |
| `isClientError()` | `true` for 400–499 |
| `isServerError()` | `true` for 500–599 |
| `bodyAs(Class<T>, ObjectMapper)` | Deserialize body as given type |
| `problemDetail(Class<T>, ObjectMapper)` | Deserialize body as RFC 9457 problem detail if `Content-Type` is `application/problem+json` |
| `requestContext()` | `RestClientRequestContext` with client name, method name |
| `responseContext()` | `RestClientResponseContext` with status, headers, body |

```java
client.getProduct("123")
    .recover(err -> {
        if (err instanceof RestClientResponseException e && e.statusCode() == 404) {
            return Future.failedFuture(new ProductNotFoundException("123"));
        }
        return Future.failedFuture(err);
    });
```

### `RestClientExceptionMapper`

Interface that translates transport-level and HTTP-level exceptions to typed application exceptions. Implementations hold a registry of `FailureTranslator` instances keyed by exception type. On translation, the mapper walks the exception class hierarchy to find the most specific registered translator; if none matches, the original throwable is returned unchanged.

```java
public interface RestClientExceptionMapper {
    <T extends Throwable> RestClientExceptionMapper on(Class<T> type, FailureTranslator<T> translator);
    Throwable translate(Throwable exception);
}
```

Every builder starts with a `DefaultRestClientExceptionMapper` instance. Use `builder.onFailure(type, translator)` to layer overrides on top of the defaults, or `builder.exceptionMapper(mapper)` to replace the mapper entirely.

### `DefaultRestClientExceptionMapper`

Default implementation with pre-registered translations for well-known JDK and Vert.x transport exceptions. Backed by the concrete `core.failure.FailureMapper` which walks the superclass hierarchy and caches lookups.

| Input exception | Output exception | Reason |
|----------------|-----------------|--------|
| `ConnectException` | `RestClientConnectionException` | Connection refused |
| `UnknownHostException` | `RestClientConnectionException` | DNS failure |
| `NoRouteToHostException` | `RestClientConnectionException` | Routing failure |
| `SocketException` | `RestClientConnectionException` | OS-level socket error |
| `TimeoutException` (covers Vert.x `NoStackTraceTimeoutException`) | `RestClientTimeoutException` | Request timeout |
| `HttpClosedException` | `RestClientConnectionException` | Empty/reset response |
| `VertxException` | `RestClientException` | Safety net for remaining Vert.x failures |

Additional translators can be registered via `on(Class, FailureTranslator)` at any time. When multiple translators are registered for a type hierarchy, the most specific match wins.

---

## Usage Example

**1. Define the client interface:**

```java
@RestClient(name = "product-service", value = "http://product-service:8080")
@CircuitBreaker(maxFailures = 5)
@Retry(maxRetries = 3)
@Path("/products")
@Produces("application/json")
@Consumes("application/json")
public interface ProductClient {

    @GET
    @ExpectedStatus(min = 200, max = 300)
    Future<List<Product>> listProducts(@QueryParam("category") String category,
                                       @QueryParam("limit") @DefaultValue("20") int limit);

    @GET
    @Path("/{id}")
    Future<Optional<Product>> findProduct(@PathParam("id") String id);  // 404 → Optional.empty()

    @POST
    @ExpectedStatus({200, 201})
    Future<Product> createProduct(CreateProductRequest request);

    @DELETE
    @Path("/{id}")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @Retry(maxRetries = 0)  // disable retry for deletes
    Future<Void> deleteProduct(@PathParam("id") String id);
}
```

**2. Wire `RestClientModule` in the Dagger component:**

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

**3. Create a proxy in an application module:**

The `@Provides @Singleton` method below can be replaced with auto-wiring (see "Auto-wiring opt-in" after this example).

```java
@Module
public class AppModule {

    @Provides
    @Singleton
    static ProductClient productClient(RestClientFactory factory) {
        // factory.builder() pre-seeds global interceptors, @VertxConfig, and optional BeanValidator
        return factory.builder()
            .baseUrl("http://product-service:8080")
            .readTimeout(10, TimeUnit.SECONDS)
            .onFailure(RestClientResponseException.class, ex -> {
                if (ex.statusCode() == 503) {
                    return new ProductServiceUnavailableException(ex);
                }
                return ex;
            })
            .build(ProductClient.class);
    }
}
```

**Auto-wiring opt-in:**

The `vertique-codegen-dagger` annotation processor can generate `@Provides @Singleton {Interface} provideXxx(RestClientFactory factory)` bindings automatically for all `@RestClient`-annotated interfaces found in the compilation unit, using `factory.builder().build(Interface.class)` as the body. This eliminates the manual module above for standard configurations. Add `vertique-codegen-dagger` to `<annotationProcessorPaths>` (use `combine.children="append"` if the parent POM already declares Dagger/Lombok processors) and add `GeneratedRestClientsModule.class` to your `@Component`. For clients that need custom builder options (custom timeout, `onFailure`, per-client `ObjectMapper`), annotate the interface with `@NoAutoWire` and keep the manual `@Provides` method. See `dev.vertique:vertique-codegen-dagger` for setup and migration guidance.

**4. Inject and call the proxy:**

---

## Auto-wired Static Proxies

`vertique-codegen-rest-client` is an optional annotation processor that generates static proxy classes and bean parameter accessors, eliminating per-call reflection on the `@BeanParam` hot path. The runtime selection is transparent — no changes to application code are required beyond adding the processor.

### What Gets Generated

For each `@RestClient` interface in the compilation unit, the processor emits:

- **`{Client}_RestClientProxy`** — a `public final` class implementing the interface. Holds pre-cached `ClientMethodMeta` in `final` fields and calls bean accessor methods directly (no `Method.invoke`).
- **`{Bean}_BeanParamAccessor`** — one per `@BeanParam` type referenced from any interface in the compilation unit. Uses a `switch` expression over field names, calling record accessors, public getters, or direct package-private field access. Deduplicated: one accessor emitted even when two interfaces share a bean type.

### Opt-In: `annotationProcessorPaths`

Add `vertique-codegen-rest-client` to the module's `<annotationProcessorPaths>` block. If a parent POM already declares processor paths (Dagger, Lombok), use `combine.children="append"` to extend rather than replace the parent list:

```xml
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths combine.children="append">
            <path>
                <groupId>dev.vertique</groupId>
                <artifactId>vertique-codegen-rest-client</artifactId>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

`vertique-codegen-core` arrives transitively; it does not need to be listed separately.

### Transparent Runtime Selection

`RestClientBuilder.build()` and `RestClientFactory.builder()` both try `Class.forName(interfaceName + "_RestClientProxy")` before falling back to the JDK proxy. No Dagger wiring changes are needed. The existing `GeneratedRestClientsModule` from `vertique-codegen-dagger` continues to work — its `factory.builder().build(Interface.class)` call picks up the generated proxy automatically via the same `Class.forName` lookup. Removing the processor from `annotationProcessorPaths` reverts to the JDK proxy with no other code changes.

### Pitfalls

- **External `@BeanParam` types skip proxy emission for the entire interface.** If any `@BeanParam` type on a method is from an external JAR (not in the current compilation unit), the processor cannot scan its fields and will not emit `{Client}_RestClientProxy`. A compile-time `WARNING` is emitted. The interface falls back to the JDK proxy at runtime.

- **`@Url` constraints are enforced at compile time.** The processor validates the same rules as the runtime scanner (`URI` type, mutual exclusivity with other param annotations, no `@DefaultValue`, no `@Path` on the method). Violations are compile errors, not warnings.

- **PATH `null` without `@DefaultValue` is fail-fast in generated proxies.** The reflective path historically omitted the segment silently; the generated proxy throws `NullPointerException`. This is intentional — it is safer and more predictable. Add `@DefaultValue` or guard against `null` before calling if this is a concern.

See `dev.vertique:vertique-codegen-rest-client` for the full processor reference, generated class shapes, and additional pitfalls.

```java
@Path("/orders")
public class OrderResource {

    private final ProductClient productClient;

    @Inject
    OrderResource(ProductClient productClient) {
        this.productClient = productClient;
    }

    @POST
    public Future<Response> createOrder(OrderRequest request) {
        return productClient.findProduct(request.productId())
            .compose(optProduct -> {
                if (optProduct.isEmpty()) {
                    throw new NotFoundException("Product not found: " + request.productId());
                }
                return Future.succeededFuture(
                    Response.ok(new Order(optProduct.get(), request.quantity())).build());
            });
    }
}
```

---

## Parameter Support

The proxy supports all standard JAX-RS parameter annotations. The `ParamSource` column is
`ClientParamMeta.ParamSource`; PATH/QUERY/HEADER/COOKIE values are serialized via the shared
`ParamConversionResolver` — see [Outbound Parameter Conversion](#outbound-parameter-conversion):

| Annotation | `ParamSource` | Description |
|------------|---------------|-------------|
| `@PathParam` | `PATH` | Substituted into the URI path template; resolver-serialized, then URL-encoded |
| `@QueryParam` | `QUERY` | Appended as URI query parameters; resolver-serialized; collection-valued params expand element-by-element |
| `@HeaderParam` | `HEADER` | Set as request header; resolver-serialized |
| `@CookieParam` | `COOKIE` | Set as `Cookie` header value; resolver-serialized |
| `@BeanParam` | `BEAN_PARAM` | Composite object; fields/record components expanded to sub-parameters, each resolver-serialized per its own `ParamSource` |
| `@Url` | `URL` | Per-invocation absolute URI override; replaces the base URL + path for that call only |
| (unannotated) | `BODY` | Serialized as request body (JSON, text/plain, or octet-stream based on `@Consumes`) |

`@DefaultValue` is supported for scalar parameters and `@BeanParam` fields. Both POJO fields and record components are supported.

---

## Generic Type Deserialization and Optional

The proxy uses the full generic type from `Future<T>` for Jackson deserialization:

```java
@GET
Future<List<Product>> listProducts();           // → List<Product>

@GET
Future<PagedResult<Product>> searchProducts();  // → PagedResult<Product>

@GET
@Path("/{id}")
Future<Optional<Product>> findProduct(@PathParam("id") String id);  // 404 → Optional.empty()
```

`Future<Optional<T>>` is a first-class return type: a 404 response is automatically translated to `Optional.empty()` without invoking the failure pipeline or any expectation check.

---

## Extension Points

### `RestClientInterceptor` — Request/Response Interceptors

`RestClientInterceptor extends OrderedExtension`. Interceptors are sorted by `OrderedExtension` order (phase ascending, then priority ascending, then orderKey ascending). Application interceptors default to phase `APPLICATION`; framework-owned capturers use `SYSTEM_FIRST`. See ADR-0084: Framework Extension-Ordering Contract for the full ordering contract.

Eight callbacks split into sync observers (fire-and-forget) and async handlers (can affect outcome):

```
Sync observers (exceptions swallowed, cannot abort):
  onRequest(ctx)                                   — after request is built, before sending
  onResponse(request, response)                    — after any HTTP response is received
  onError(request, response, error)                — on any failure
  onAttemptCompleted(request, completion)          — once per physical HTTP attempt, before
                                                     retry/recovery decision (see below)

Async handlers (affect outcome):
  beforeRequest(ctx) → Future<RestClientRequestContext>
      — returns possibly-updated context; failed future aborts the request
  afterResponse(request, response) → Future<Void>
      — may inspect/reject response; failed future = error
  recoverRequest(request, response, error) → Future<RestClientRequestContext>
      — on failure, return succeeded future with new context to trigger one recovery retry;
        return failed future (default) to skip recovery and proceed to transformError
  transformError(request, response, err) → Future<Throwable>
      — may replace the error; the returned throwable propagates to the caller
```

**`onAttemptCompleted`** fires from inside the circuit-breaker send, before the retry/recovery decision, for every physical HTTP attempt that completes — including retried failures and recovery re-dispatches. Exactly one of `completion.response()` / `completion.error()` is non-null. Unlike `onResponse` (fires only when a response is received — never on a transport failure) and `onError` (fires once after all retries), `onAttemptCompleted` sees every wire attempt. Implementations must not block.

**Circuit-breaker caveat:** `onAttemptCompleted` observes physical send completions, not circuit-breaker-level outcomes. If the breaker's own timeout fires before the HTTP send resolves, the hook may fire late. If the circuit is open and the call is short-circuited with no send, the hook does not fire.

`RestClientAttemptCompletion` carries the attempt facts:

```java
public record RestClientAttemptCompletion(
    @Nullable RestClientResponseContext response,  // non-null on any HTTP response
    @Nullable Throwable error,                     // non-null on transport failure
    String callId,                                 // unique per logical client call
    int attemptOrdinal,                            // 1-based attempt number (spans retries + recovery)
    long durationMs,                               // monotonic elapsed time, >= 0
    Instant completedAt,                           // wall-clock instant (use for occurredAt)
    RestClientAttemptTarget target                 // safe-by-type target identity
) {}
```

Invariants enforced by the compact constructor: `callId` non-blank; `attemptOrdinal >= 1`; `durationMs >= 0`; `completedAt` non-null; exactly one of `{response, error}` non-null.

`RestClientAttemptTarget` is a safe-by-type target identity record — it carries only the route-level shape, never the expanded request URI, query string, or concrete path parameters:

```java
public record RestClientAttemptTarget(
    @Nullable String scheme,        // e.g. "https"; null if undetermined
    @Nullable String host,          // target host; null if undetermined
    int port,                       // -1 for the scheme default / undetermined
    @Nullable String pathTemplate   // route template e.g. "/users/{id}"; null if unavailable
) {}
```

**Invocation order per request:**

```
onRequest (sync)
  → beforeRequest chain (async, in priority order)
    → [HTTP send]
      → onResponse (sync)
        → afterResponse chain (async, in priority order)
          → [deserialize + validate]

On failure:
  → recoverRequest chain (first succeeded future triggers one retry; others skipped)
    → [if recovery accepted: restart from HTTP send with new context]
  → onError (sync)
    → transformError chain (async, in priority order)
      → RestClientExceptionMapper
```

**`RestClientRequestContext`** — immutable 7-component record in `beforeRequest`/`onRequest`. Interceptors produce modified copies via `with*` copy-on-write methods. One attribute channel is exposed to application code:

- **`attributes`** — caller-controlled bag; applications and user-defined interceptors use `withAttribute`/`withAttributes` to pass data through the chain.

Framework-level system context (e.g. captured audit snapshots) is managed by `RestClientContextCapturer` and is never placed in the application `attributes` map — see [RestClientContextCapturer](#restclientcontextcapturer--system-capture-spi) below.

| Method | Description |
|--------|-------------|
| `httpMethod()` | HTTP verb string |
| `requestUri()` | Current request URI (includes query string) |
| `withRequestUri(String)` | Returns new context with replaced URI (e.g. for load balancing) |
| `headers()` | Request headers as `MultiMap` (read-only reference) |
| `withHeader(String, String)` | Returns new context with one header added/replaced |
| `withHeaders(MultiMap)` | Returns new context with all headers replaced |
| `body()` | Current request body buffer; `null` for bodyless requests |
| `withBody(Buffer)` | Returns new context with replaced body |
| `clientName()` | Logical client name |
| `methodName()` | Java method name being invoked |
| `attribute(String)` | Read a user-controlled attribute |
| `withAttribute(String, Object)` | Returns new context with user attribute added/replaced |
| `withAttributes(Map)` | Returns new context with all user attributes replaced |

**`RestClientResponseContext`** — immutable record available in `onResponse`/`afterResponse`/`onError`/`transformError`/`recoverRequest`. Construct from a Vert.x response via `RestClientResponseContext.from(HttpResponse<Buffer>)`:

| Method | Description |
|--------|-------------|
| `statusCode()` | HTTP status code |
| `statusMessage()` | HTTP status message |
| `headers()` | Response headers |
| `body()` | Response body buffer (never `null`; empty buffer if no body) |

Register interceptors globally via Dagger multibinding (`@IntoSet`) to apply to all clients, or per-client via `builder.register(interceptor)`:

```java
// phase() defaults to APPLICATION, priority() defaults to 0, orderKey() defaults to class name
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

// In a Dagger module:
@Provides @IntoSet
static RestClientInterceptor bearerTokenInterceptor(BearerTokenInterceptor interceptor) {
    return interceptor;
}
```

**Token refresh via `recoverRequest`:**

```java
public class TokenRefreshInterceptor implements RestClientInterceptor {
    private final TokenProvider tokenProvider;

    @Inject
    TokenRefreshInterceptor(TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public Future<RestClientRequestContext> recoverRequest(
            RestClientRequestContext request,
            @Nullable RestClientResponseContext response,
            Throwable error) {
        // Only recover on 401
        if (response != null && response.statusCode() == 401) {
            return tokenProvider.refreshToken()
                .map(newToken -> request.withHeader("Authorization", "Bearer " + newToken));
        }
        // Decline recovery for other errors
        return Future.failedFuture(error);
    }
}
```

### `RestClientContextCapturer` — System Capture SPI

`RestClientContextCapturer<C> extends OrderedExtension`. A dispatcher-owned SPI for framework modules that need to snapshot system context at dispatch entry and observe each physical attempt with that captured value. The dispatcher calls `captureRequestContext()` exactly once per logical call — **before any application interceptor runs** — holds the returned value in call scope across retries and recovery, and passes it back to `onAttemptCompleted` per physical attempt. The captured value is never placed in the application `attributes` map and cannot be read, replaced, dropped, or forged by application interceptors.

Framework capturers should set `phase()` to `ExtensionPhase.SYSTEM_FIRST`. Application-facing per-attempt observation is handled by `RestClientInterceptor.onAttemptCompleted` (no captured context parameter).

```java
public interface RestClientContextCapturer<C> extends OrderedExtension {

    @Nullable
    C captureRequestContext();

    void onAttemptCompleted(
            @Nullable C capturedContext,
            RestClientRequestContext request,
            RestClientAttemptCompletion completion);
}
```

Register capturers globally via Dagger multibinding (`@IntoSet` into `Set<RestClientContextCapturer<?>>`, declared by `RestClientModule`), or per-client via `builder.registerCapturer(capturer)`:

```java
// In a framework module (the dispatcher captures structurally before the interceptor chain;
// SYSTEM_FIRST orders this capturer among other capturers and marks it as a system extension):
@Provides @IntoSet
static RestClientContextCapturer<?> myContextCapturer(MyContextCapturer capturer) {
    return capturer;
}
```

#### Invariants & Gotchas

- `captureRequestContext()` is called on the caller's Vert.x context — the same thread/context as the application code that triggered the request. This is the correct place to read ambient context values (e.g. `ContextHolder`) that may not be bound after an async hop.
- `onAttemptCompleted` is fire-and-forget; exceptions are swallowed by the dispatcher. Implementations must not block.
- The dispatcher holds the captured value per-call, not per-attempt. A logical call with three attempts passes the same captured value to all three `onAttemptCompleted` invocations.
- An open circuit short-circuits with no send: `captureRequestContext()` is still called, but `onAttemptCompleted` never fires for that call.

### `RestClientExceptionMapper` — Failure Translation

Exception translation is controlled by the `RestClientExceptionMapper` attached to each builder. The default is `DefaultRestClientExceptionMapper`, which pre-registers translations for common network errors. There are two ways to extend it:

**Per-client overrides via `onFailure()`** — registers additional translators on the existing mapper. Last registered wins for a given type (including defaults):

```java
factory.builder()
    .baseUrl("http://user-service:8080")
    .onFailure(RestClientResponseException.class, ex -> {
        if (ex.statusCode() == 404) {
            return new UserNotFoundException("not found");
        }
        return ex;
    })
    .onFailure(RestClientConnectionException.class, ex ->
        new UserServiceUnavailableException("user-service unreachable", ex))
    .build(UserClient.class);
```

**Complete replacement via `exceptionMapper()`** — replaces the entire mapper when a custom strategy is needed. Any prior `onFailure()` registrations are discarded:

```java
RestClientExceptionMapper customMapper = new DefaultRestClientExceptionMapper()
    .on(MyServiceException.class, t -> new DomainException("Service failed", t));

factory.builder()
    .baseUrl("http://my-service:8080")
    .exceptionMapper(customMapper)
    .build(MyServiceClient.class);
```

---

## External JSON Configuration

The builder accepts a typed `RestClientConfig` via `config(RestClientConfig)` that can override builder and annotation settings at runtime. The config is parsed and validated at the `RestClientModule` provider boundary from the `restClient.{name}` section and resolved per client by name (`RestClientFactory` seeds each builder from the parsed `name → RestClientConfig` index). Config has the highest priority for `baseUrl`, `readTimeoutMs`, pool sizing, circuit breaker parameters, and the WebClient options bag.

### External Configuration Reference

Configuration overrides are read from the `restClient.{name}` section of the application config. All fields are optional — when absent, the value from the builder API or annotation is used.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `restClient.{name}.baseUrl` | String | `@RestClient` value | Base URL override |
| `restClient.{name}.readTimeoutMs` | long | 30000 | Per-request timeout in milliseconds |
| `restClient.{name}.webClient.*` | object | — | Vert.x `WebClientOptions` fields (see below); merged on top of builder-level options |
| `restClient.{name}.circuitBreaker.maxFailures` | int | 5 | Failure count before circuit opens |
| `restClient.{name}.circuitBreaker.timeoutMs` | long | -1 | Per-attempt timeout within circuit breaker (ms); -1 = no timeout |
| `restClient.{name}.circuitBreaker.resetTimeoutMs` | long | 10000 | Time in half-open state before retry (ms) |
| `restClient.{name}.circuitBreaker.maxRetries` | int | 3 | Max retry attempts within circuit breaker |
| `restClient.{name}.pool.http1MaxSize` | int | 5 | HTTP/1.1 connection pool size |
| `restClient.{name}.pool.http2MaxSize` | int | 1 | HTTP/2 connection pool size |
| `restClient.{name}.pool.maxWaitQueueSize` | int | -1 | Max requests waiting for a connection; -1 = unbounded |
| `restClient.{name}.pool.eventLoopSize` | int | 0 | Event-loop threads for pool; 0 = reuse current |
| `restClient.{name}.pool.cleanerPeriodMs` | int | 1000 | Pool cleaner run interval (ms); non-positive = disabled |
| `restClient.{name}.pool.maxLifetimeSeconds` | int | 0 | Max connection lifetime (seconds); 0 = no limit |
| `restClient.{name}.retry.backoffStrategy` | String | — | FQCN of `BackoffStrategy` implementation class |
| `restClient.{name}.jsonProfile` | String | — | Named JSON mapper profile id; overrides `@JsonProfile` on the interface and builder-level `jsonProfile(...)` when non-blank |

The `webClient` section uses framework-normalized field names: duration fields carry an explicit unit suffix (`*Ms` for milliseconds, `*Seconds` for seconds). Non-duration fields (booleans, strings, counts) use Vert.x's original names. The config is merged on top of any builder-level `WebClientOptions`, so individual fields can be selectively overridden without discarding the entire baseline.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ssl` | boolean | false | Enable TLS |
| `verifyHost` | boolean | true | Verify server hostname |
| `trustAll` | boolean | false | Trust all certificates (dev only) |
| `keepAlive` | boolean | true | HTTP keep-alive |
| `keepAliveTimeoutSeconds` | int | 60 | Keep-alive timeout (seconds) |
| `connectTimeoutMs` | int | 60000 | TCP connect timeout (ms) |
| `idleTimeoutSeconds` | int | 0 | Idle connection timeout (seconds); 0 = disabled |
| `readIdleTimeoutSeconds` | int | 0 | Read idle timeout (seconds); 0 = disabled |
| `writeIdleTimeoutSeconds` | int | 0 | Write idle timeout (seconds); 0 = disabled |
| `http2KeepAliveTimeoutSeconds` | int | 60 | HTTP/2 keep-alive timeout (seconds) |
| `sslHandshakeTimeoutSeconds` | long | 10 | SSL handshake timeout (seconds) |
| `maxRedirects` | int | 16 | Max HTTP redirects to follow |
| `followRedirects` | boolean | true | Follow HTTP redirects |
| `pemKeyCertOptions` | object | — | PEM client certificate for mTLS |
| `pemKeyCertOptions.keyPath` | String | — | Path to client private key PEM file |
| `pemKeyCertOptions.certPath` | String | — | Path to client certificate PEM file |
| `keyStoreOptions` | object | — | JKS client keystore for mTLS |
| `keyStoreOptions.path` | String | — | Path to JKS keystore file |
| `keyStoreOptions.password` | String | — | Keystore password |
| `pfxKeyCertOptions` | object | — | PKCS12 client keystore for mTLS |
| `pfxKeyCertOptions.path` | String | — | Path to PKCS12 file |
| `pfxKeyCertOptions.password` | String | — | Keystore password |
| `pemTrustOptions` | object | — | PEM trust store (CA certificates) |
| `pemTrustOptions.certPaths` | List\<String\> | — | Paths to trusted CA PEM files |
| `trustStoreOptions` | object | — | JKS trust store |
| `trustStoreOptions.path` | String | — | Path to JKS trust store file |
| `trustStoreOptions.password` | String | — | Trust store password |
| `pfxTrustOptions` | object | — | PKCS12 trust store |
| `pfxTrustOptions.path` | String | — | Path to PKCS12 trust store |
| `pfxTrustOptions.password` | String | — | Trust store password |
| `proxyOptions` | object | — | HTTP/SOCKS proxy configuration |
| `proxyOptions.host` | String | — | Proxy hostname |
| `proxyOptions.port` | int | — | Proxy port |
| `proxyOptions.type` | String | HTTP | Proxy type: `HTTP` or `SOCKS5` |
| `proxyOptions.username` | String | — | Proxy authentication username |
| `proxyOptions.password` | String | — | Proxy authentication password |

**Structure:**

```json
{
  "restClient": {
    "{clientName}": {
      "baseUrl": "http://override-host:8080",
      "readTimeoutMs": 5000,
      "webClient": {
        "connectTimeoutMs": 5000,
        "ssl": true,
        "verifyHost": true,
        "trustAll": false
      },
      "circuitBreaker": {
        "maxFailures": 3,
        "timeoutMs": 10000,
        "resetTimeoutMs": 30000,
        "maxRetries": 1
      },
      "pool": {
        "http1MaxSize": 10,
        "http2MaxSize": 5,
        "maxWaitQueueSize": 100,
        "eventLoopSize": 0,
        "cleanerPeriodMs": 1000,
        "maxLifetimeSeconds": 300
      },
      "retry": {
        "backoffStrategy": "com.example.AggressiveBackoff"
      }
    }
  }
}
```

**`retry` block fields:**

| Field | Type | Description |
|-------|------|-------------|
| `backoffStrategy` | `String` | Fully-qualified class name of a `BackoffStrategy` implementation with a public no-arg constructor; instantiated via reflection at `build()` time |

> The `retry` config block applies **only** `backoffStrategy`. `maxRetries` is configured via the
> `circuitBreaker` block (`circuitBreaker.maxRetries`); `retryOn` / `abortOn` are set on the
> `@Retry` annotation and are not read from external config.

**Mutual TLS (mTLS) example:**

```json
{
  "restClient": {
    "secureClient": {
      "webClient": {
        "ssl": true,
        "pemKeyCertOptions": {
          "keyPath": "/etc/tls/client-key.pem",
          "certPath": "/etc/tls/client-cert.pem"
        },
        "pemTrustOptions": {
          "certPaths": ["/etc/tls/ca-cert.pem"]
        }
      }
    }
  }
}
```

**Proxy example:**

```json
{
  "restClient": {
    "proxiedClient": {
      "webClient": {
        "proxyOptions": {
          "host": "proxy.internal",
          "port": 8080,
          "type": "HTTP"
        }
      }
    }
  }
}
```

The client name is resolved from `@RestClient#name()` or the interface simple name. All fields are optional; missing fields fall through to lower-priority sources.

**Priority hierarchy (highest to lowest):**

| Priority | Source | Applies to |
|----------|--------|-----------|
| 1 (highest) | External JSON config (`restClient.{name}.*`) | `baseUrl`, `readTimeoutMs`, pool, circuit breaker, retry, `webClient` options |
| 2 | `@Retry` on the method | retry behavior |
| 3 | `@Retry` on the interface | retry behavior (default for all methods) |
| 4 | `@CircuitBreaker` annotation (interface or method level) | circuit breaker |
| 5 | Builder settings (`baseUrl()`, `readTimeout(long, TimeUnit)`, `circuitBreaker()`, `retryPolicy()`, `backoffStrategy()`, `webClientOptions()`) | all |
| 6 (lowest) | Defaults (30 s timeout, no circuit breaker, `DefaultRestClientRetryPolicy`, exponential backoff) | all |

---

## Module Dagger Bindings

| Type | Scope | Description |
|------|-------|-------------|
| `RestClientFactory` | `@Singleton` | Creates proxies; pre-seeds builders with global interceptors, global capturers, `@VertxConfig` JsonObject, `JsonMapperProfileRegistry`, the Dagger-provided `ParamConversionResolver`, and optional `BeanValidator` |
| `RestClientDefaultProfileValidator` | `@Singleton` | `ComposeValidator` contributed `@IntoSet`; validates `restClient.defaults.jsonProfile` against the `JsonMapperProfileRegistry` at `@Inject` construction time — unknown id fails the `VALIDATE` phase even when zero clients are registered or when the boundary default is shadowed by per-client config |
| `Set<RestClientInterceptor>` | — | Empty-by-default multibinding; contribute global application interceptors via `@Provides @IntoSet`; sorted by `OrderedExtension` order (phase → priority → orderKey) |
| `Set<RestClientContextCapturer<?>>` | — | Empty-by-default multibinding; contribute framework-owned system capturers via `@Provides @IntoSet`; sorted by `OrderedExtension` order |
| `BeanValidator` (`@BindsOptionalOf`) | — | Optional binding; satisfied by the `validation` module when present; enables response object validation |
| `ParamConversionResolver` | `@Singleton` | Provided by the included `RestCoreModule` (`dev.vertique.rest.core.dagger`); built from `Set<ParamConverterBinding<?>>` + `Set<ParamConverterProvider>` multibindings (also declared by `RestCoreModule`) over the built-in converters; seeded into every builder from `RestClientFactory.builder()` for outbound path/query/header/cookie serialization |

`RestClientModule` is declared as `@Module(includes = {JsonRuntimeModule.class, RestCoreModule.class})`. `JsonRuntimeModule` installs the `JsonMapperProfileRegistry` binding so every builder obtained from `RestClientFactory` can resolve named JSON mapper profiles; `RestCoreModule` installs the `ParamConversionResolver` and its converter multibindings (shared with `rest-jaxrs` via the same module) — see `dev.vertique:vertique-rest-core`.

---

## Dependencies

- `dev.vertique:core` — `Vertx` instance, `FailureMapper` (backing `DefaultRestClientExceptionMapper`), `BeanValidator` (optional via `@BindsOptionalOf`), `OrderedExtension` / `ExtensionPhase` (extension ordering contract), `core.codegen` `MethodMetadata`/`ParameterMetadata`/`ReflectiveMethodMetadata`/`ReflectiveParameterMetadata` SPI composed by `ClientMethodMeta`/`ClientParamMeta`
- `dev.vertique:vertique-rest-core` — `ParamConversionResolver`, `ParamConverterRegistry`, `ConversionContext`, `ParamSource`, `RestCoreModule` (Dagger wiring); the shared outbound parameter-conversion stack also used inbound by `rest-jaxrs`; `RestClientModule` includes `RestCoreModule` automatically — see `dev.vertique:vertique-rest-core`
- `dev.vertique:vertique-json` — `JsonMapperProfileRegistry` and `JsonRuntimeModule`; required for named JSON mapper profile resolution; `RestClientModule` includes `JsonRuntimeModule` automatically
- `io.vertx:vertx-web-client` — `WebClient`, `WebClientOptions`, `PoolOptions`
- `io.vertx:vertx-circuit-breaker` — `CircuitBreaker` for `@CircuitBreaker`-annotated interfaces
- `jakarta.ws.rs:jakarta.ws.rs-api` — JAX-RS annotations (`@Path`, `@GET`, `@QueryParam`, etc.)
- `com.google.dagger:dagger`
- `org.projectlombok:lombok` (provided)

---

## Related ADRs

- ADR-0084: Framework Extension-Ordering Contract — establishes the `OrderedExtension` interface (phase/priority/orderKey) as the canonical ordering contract for framework extensions; `RestClientInterceptor` and `RestClientContextCapturer` are the first consumers.
- ADR-0104: Typed Config Architecture — establishes the boundary-parse model that `RestClientConfig` implements; records the section-root-keyed parse pattern, duration-key normalization, and secret-hygiene for `webClient` and `baseUrl`.
- ADR-0108: Unified Failure Mapping on a Context-Aware `FailureMapper` — collapses four layer-specific mapper wrappers onto one concrete `core.failure.FailureMapper`; `DefaultRestClientExceptionMapper` now composes the unified concrete `FailureMapper`; the `FailureTranslator` SAM and `onFailure(Class, FailureTranslator)` builder API are unchanged.
- ADR-0112: Framework Exception Hierarchy and REST Mapping — establishes the core semantic exception roots and their REST status mappings; defines `RestClientUnavailableException → core.UnavailableException` and `RestClientConfigurationException → core.ConfigurationException` as the correct superclasses; `RestClientException` now covers only response-level failures (`RestClientResponseException`).
- ADR-0125: JSON Mapper Profiles — the `JsonMapperProfile` SPI, `JsonProfileId`, and `JsonMapperProfileRegistry` (contract in `core.json`, runtime in `vertique-json`) that `rest-client` resolves its client mapper from. The **original** 5-level precedence (`objectMapper` → config `jsonProfile` → builder `jsonProfile` → `@JsonProfile` (interface) → `vertx`) — **extended to 7 tiers by ADR-0136** (next bullet) — and the fail-fast at `build()` for unknown ids / standalone-builder-without-registry cases are governed by this profile system.
- ADR-0136: Global + per-boundary JSON default-profile config tiers — adds `restClient.defaults.jsonProfile` as the REST client per-boundary default tier (tier 5 in the 7-level precedence chain) and the `json.jsonProfile` global tier (tier 6); introduces `RestClientDefaultProfileValidator` (`ComposeValidator`) for unconditional fail-fast validation of the boundary default; records how the `restClient.defaults` key is excluded from the keyed-client parse.
- ADR-0138: Harmonized `@JsonProfile` selection surface — establishes that `@JsonProfile` is TYPE-level only on `@RestClient` interfaces; a method-level placement is rejected at client-build time (FR-JSON-066).
- ADR-0142: Param-Conversion SPI — Registry, Resolver, and ConversionContext — establishes the shared `ParamConversionResolver`/`ParamConverterRegistry`/`ConversionContext` stack in `vertique-rest-core` that replaces bare `toString()` outbound serialization; the dispatcher-chokepoint design (`DefaultRestClientDispatcher.apply*Param`) keeps the generated and JDK reflective proxies on one resolver-backed path; build-time `resolver.canResolve` validation backs `RestClientBuilder.build()`'s fail-fast behavior.
- ADR-0143: REST Metadata-Record Unification onto `core.codegen` — migrates `ClientMethodMeta`/`ClientParamMeta` off a live `java.lang.reflect.Method` onto the composed `core.codegen` `MethodMetadata`/`ParameterMetadata` SPI, adding `responseGenericType()` and `annotationsLazy()`; this is what makes rest-client conversion resolver-equivalent to the inbound `rest-jaxrs` path.
