<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Management Module

> **Status:** Beta
> **Package:** `dev.vertique.management`
> **Artifact:** `vertique-management`
> **Depends on:** `dev.vertique:vertique-core`, `io.vertx:vertx-web`

Runs a dedicated management HTTP server on its own port (default 9090) that serves Kubernetes-style liveness and readiness probes and hosts operational endpoints contributed by other modules. The management server is separate from the application's `HttpVerticle`, so probes and metrics can be placed behind different network policy (cluster-internal only, for example) from the application's traffic.

The health-check SPI itself lives in `dev.vertique.core.health`, not in this package. That placement lets any module contribute a check without taking a dependency on `dev.vertique:vertique-management`.

---

## When To Use It

Install this module when the application must expose health probes or an operational endpoint such as a Prometheus scrape route. Applications built on `dev.vertique:vertique-starter-rest` or `dev.vertique:vertique-starter-services` already have `ManagementModule` in the component graph; everything else adds it explicitly.

The starters compose the bindings but do **not** deploy the verticle — the application owns its deployment entry. Register `ManagementVerticle` in the `INFRA` lifecycle phase so probes answer before the `EDGE`-phase HTTP server starts accepting traffic:

```java
@Module
public class AppModule {

    @Provides
    @IntoSet
    static VerticleDeployment managementVerticle(Provider<ManagementVerticle> provider) {
        return VerticleDeployment.of("management", provider::get, LifecyclePhase.INFRA);
    }

    @Provides
    @IntoSet
    static VerticleDeployment httpVerticle(Provider<HttpVerticle> provider) {
        return VerticleDeployment.of("http", provider::get, LifecyclePhase.EDGE);
    }
}
```

`VerticleDeployment` and the deployment manager that consumes the set come from `dev.vertique:vertique-deploy`; `LifecyclePhase` comes from `dev.vertique:vertique-core`.

---

## Core Concepts

### Two probe endpoints, two check sets

| Endpoint | Check set | Status 200 | Status 503 |
|----------|-----------|------------|------------|
| `GET /health/live` | `@Liveness Set<HealthCheck>` | all checks UP | any check DOWN |
| `GET /health/ready` | `@Readiness Set<HealthCheck>` | all checks UP | any check DOWN |

Classify a check by the qualifier you contribute it under. `@Liveness` answers "is this process still functioning" and must not touch external dependencies — a liveness failure normally causes a restart. `@Readiness` answers "can this process serve traffic right now" and is where database pools, downstream services, and event-bus consumers belong.

### Aggregation rules

- Every check in the set runs concurrently; the endpoint waits for all of them to settle, whatever their outcome. The one exception is a check that throws an `Error` while starting — see the last bullet.
- The overall status is UP only when every individual check is UP.
- An empty check set is UP with an empty `checks` array.
- Each check is bounded by a per-check timeout (`healthCheckTimeoutSeconds`, default 5); a timed-out check counts as DOWN.
- A check that returns a failed future, or that throws an **exception** synchronously from `check()`, is reported as DOWN carrying the throwable's message — or its fully qualified class name when the throwable has no message. A check that throws an `Error` rather than an exception from `check()` or `name()` is not attributable to one entry and degrades the whole probe to the terse `{"status":"DOWN","checks":[]}` body. Because checks are started in sequence, such an `Error` also prevents the checks after it from starting at all and abandons those already in flight.

### Response format

```json
{
  "status": "UP",
  "checks": [
    {"name": "database", "status": "UP"},
    {"name": "services", "status": "UP", "data": {"user-service": "UP"}}
  ]
}
```

`data` is omitted from an individual check object when that result carries no diagnostic data. The response content type is `application/json`.

---

## Key Classes

### ManagementVerticle

The Vert.x verticle that owns the management server. It is `@Inject`-constructible, so applications obtain it from Dagger and hand it to their deployment entry rather than constructing it directly.

When `management.enabled` is `false` the verticle starts successfully, binds no port, and invokes no endpoint contributors — which is the usual configuration for unit tests and for environments where probes are handled outside the process.

The server binds `management.port` on the interface named by `management.host`, which defaults to `0.0.0.0` — every interface. Set it to `127.0.0.1` to keep the management server reachable only from inside the host, for example in tests and on developer machines where an ephemeral port on a wildcard bind would otherwise be exposed to the local network. A host that cannot be resolved fails the verticle's deployment rather than falling back to the wildcard address.

After a successful bind, the resolved port is published into the Vert.x shared local map `vertique` under the key `management.port`. Configure `port: 0` and read that entry to discover the ephemeral port in tests:

```java
int boundPort = (int) vertx.sharedData().getLocalMap("vertique").get("management.port");
```

### ManagementConfig

Typed configuration object deserialized from the `management` configuration section. Inject it wherever the bind address or enablement flag is needed and read `config.port()`, `config.host()`, `config.enabled()`, and `config.healthCheckTimeoutSeconds()`. There are no separate scalar bindings for these values.

### ManagementModule

The Dagger module to install. It provides `ManagementConfig`, includes `HealthCheckModule` from `dev.vertique:vertique-core` so the `@Liveness` / `@Readiness` sets resolve, and declares the `Set<ManagementEndpointContributor>` multibinding so the injection point is satisfiable with no contributors registered.

| Type | Qualifier | Notes |
|------|-----------|-------|
| `ManagementConfig` | — | Parsed from the `management` section; defaults applied for absent fields |

---

## Health Check SPI

These types live in `dev.vertique.core.health` and are re-exported through this module's dependency on `dev.vertique:vertique-core`.

### HealthCheck

```java
public interface HealthCheck {
    /** Human-readable name; must be unique within its qualifier set. */
    String name();

    /** Performs the check. */
    Future<HealthCheckResult> check();
}
```

Return a *completed* future carrying an UP or DOWN `HealthCheckResult` rather than a failed future. A failed future is still reported as DOWN, but the diagnostic it carries is only the raw throwable message — or the throwable's class name when it has no message.

```java
@Singleton
public class CacheHealthCheck implements HealthCheck {

    private final Cache cache;

    @Inject
    public CacheHealthCheck(Cache cache) {
        this.cache = cache;
    }

    @Override
    public String name() {
        return "cache";
    }

    @Override
    public Future<HealthCheckResult> check() {
        try {
            return Future.succeededFuture(
                    cache.ping() ? HealthCheckResult.up() : HealthCheckResult.down("ping failed"));
        } catch (Exception e) {
            return Future.succeededFuture(HealthCheckResult.down(e));
        }
    }
}
```

### HealthCheckResult

```java
public record HealthCheckResult(HealthStatus status, Map<String, Object> data) { }
```

The canonical constructor normalizes a `null` data map to empty and takes an unmodifiable defensive copy, so the returned map is never `null` and never mutable.

| Factory | Status | Data |
|--------|--------|------|
| `HealthCheckResult.up()` | UP | empty |
| `HealthCheckResult.up(Map<String,Object>)` | UP | provided map |
| `HealthCheckResult.down()` | DOWN | empty |
| `HealthCheckResult.down(String error)` | DOWN | `{"error": "<error>"}`, or empty when `error` is `null` |
| `HealthCheckResult.down(Throwable cause)` | DOWN | `{"error": "<cause.getMessage()>"}`, falling back to the throwable's fully qualified class name when it has no message or `getMessage()` itself throws an exception; `cause` must not be `null` |
| `HealthCheckResult.down(Map<String,Object>)` | DOWN | provided map |

`HealthStatus` is a two-constant enum, `UP` and `DOWN`.

### @Liveness / @Readiness

Dagger qualifier annotations (`@Qualifier`, runtime-retained, valid on methods, parameters, and fields) that place a contributed `HealthCheck` into the liveness or the readiness set.

### HealthCheckModule

The abstract Dagger module in `dev.vertique.core.health` that declares both `@Multibinds` sets. Any module that contributes or consumes health checks must include it — directly or transitively. `ManagementModule` includes it, as do the Dagger modules of `dev.vertique:vertique-db-postgresql` and `dev.vertique:vertique-services`.

### Invariants & Gotchas

- **Prefer `down(Throwable)` over `down(String)` when you hold the failure.** `down(String)` treats a `null` message as "no diagnostic available" and yields a DOWN result with empty data, so `down(e.getMessage())` silently loses every trace of a message-less exception. `down(e)` falls back to the throwable's class name and keeps a diagnostic in the probe response — it does so for any failure whose `getMessage()` throws an exception, so mapping such a failure with `.otherwise(HealthCheckResult::down)` does not turn into a second, unrelated failure. A `getMessage()` that throws an `Error` rather than an exception still propagates.
- **Health check `data` must be flat, JSON-shaped and acyclic.** Values are serialized into the probe response, so a map or collection that refers back to itself — or that nests deeper than roughly 1000 levels — cannot be rendered. Such a check is reported DOWN, and data deep enough to break the response as a whole degrades the probe to a terse `{"status":"DOWN","checks":[]}` body that names no check at all. Put identifiers, counts and short strings in `data`; keep object graphs out of it.
- **`name()` must be unique within its qualifier set.** Names are the JSON keys in the probe response; duplicates produce two entries that operators cannot tell apart. The framework does not reject a collision.
- **A check's work counts against the probe's latency.** All checks in a set run concurrently, but the endpoint responds only after the slowest one settles or times out, so `healthCheckTimeoutSeconds` is effectively the probe's worst-case latency.
- **Never block the event loop inside `check()`.** Offload blocking work with `vertx.executeBlocking` and return the resulting future.

---

## Extension Points

### ManagementEndpointContributor

SPI for modules that mount additional routes on the management server. Implementations are collected via Dagger multibinding and invoked once per management server start, in `OrderedExtension.comparator()` order — ascending phase, then ascending `priority()`, then ascending `orderKey()`.

```java
public interface ManagementEndpointContributor extends OrderedExtension {
    /** Mounts one or more routes on the management Router. */
    void contribute(Router router);
}
```

Register a lambda when order does not matter:

```java
@Provides
@IntoSet
static ManagementEndpointContributor metricsEndpoint(PrometheusHandler handler) {
    return router -> router.get("/metrics").handler(handler);
}
```

Implement the interface as a named class when it does, overriding `phase()` and/or `priority()`, and bind it with `@Binds @IntoSet`:

```java
@Singleton
public class MetricsEndpointContributor implements ManagementEndpointContributor {

    private final PrometheusHandler handler;

    @Inject
    public MetricsEndpointContributor(PrometheusHandler handler) {
        this.handler = handler;
    }

    @Override
    public int priority() {
        return 10; // run after default-priority contributors
    }

    @Override
    public void contribute(Router router) {
        router.get("/metrics").handler(handler);
    }
}
```

#### Invariants & Gotchas

- `contribute()` runs on the management verticle's event loop during start — it must not block. Route *handlers* it registers may offload blocking work with `executeBlocking`.
- The `/health/live` and `/health/ready` routes are mounted **before** any contributor runs. Vert.x matches the first registered route, so re-registering a health path has no effect.
- Between two contributors that register the same path, the one sorted first wins for the same reason.
- A `RuntimeException` thrown from `contribute()` fails the management server start promise immediately: no port is bound and no further contributor runs.
- Contributors are not invoked at all when `management.enabled=false`.
- A lambda or method-reference contributor gets a JVM-generated default `orderKey()` that is not stable across compilations. When relative order between two lambda contributors matters, give them distinct `priority()` values or register them as named classes.

### `@Liveness Set<HealthCheck>` and `@Readiness Set<HealthCheck>`

Contribute a check from any Dagger module that includes `HealthCheckModule`:

```java
// Liveness: lightweight in-process check
@Provides
@IntoSet
@Liveness
static HealthCheck processCheck(ProcessHealthCheck check) {
    return check;
}

// Readiness: external dependency check
@Provides
@IntoSet
@Readiness
static HealthCheck cacheCheck(CacheHealthCheck check) {
    return check;
}
```

---

## Built-In Readiness Checks

Two framework modules contribute a readiness check automatically when installed. Neither requires application wiring.

| Name | Contributed by | Reports DOWN when |
|------|----------------|-------------------|
| `database` | `dev.vertique:vertique-db-postgresql` | `SELECT 1` against the connection pool fails |
| `services` | `dev.vertique:vertique-services` | any supervised event-bus service is unavailable |

The `services` check carries a per-service `data` map (`{"user-service": "UP"}`) on both UP and DOWN results whenever at least one service is supervised; with no supervised services it reports UP with no data.

---

## Configuration

Deserialized from the `management` section into `ManagementConfig`. Every field is optional.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `port` | `int` | `9090` | Management HTTP server port; `0` binds an ephemeral port |
| `host` | `String` | `"0.0.0.0"` | Network interface to bind; `"127.0.0.1"` restricts the server to loopback; must be non-blank |
| `enabled` | `boolean` | `true` | `false` skips port binding and contributor invocation |
| `healthCheckTimeoutSeconds` | `long` | `5` | Per-check timeout for both probe endpoints; must be positive |

```json
{
  "management": {
    "port": 9090,
    "host": "0.0.0.0",
    "enabled": true,
    "healthCheckTimeoutSeconds": 10
  }
}
```

`healthCheckTimeoutSeconds` and `host` are validated when the verticle starts, not when configuration is parsed: a zero or negative timeout, or an explicitly `null` or blank `management.host`, fails the management verticle's deployment with `IllegalArgumentException` rather than at config-load time. Unknown properties in the `management` section are ignored.

With hierarchical property expansion, `management.port=9090` in a `.properties` source expands to the same nested object.

---

## Dependencies

| Dependency | Why |
|------------|-----|
| `dev.vertique:vertique-core` | `HealthCheck`, `HealthCheckResult`, `HealthStatus`, `@Liveness`, `@Readiness`, `HealthCheckModule`, `OrderedExtension`, `ConfigParser`, `@VertxConfig` |
| `io.vertx:vertx-web` | `Router` and `RoutingContext` for the management server |
| `com.google.dagger:dagger` | `ManagementModule` bindings and multibinding declarations |
| `jakarta.inject:jakarta.inject-api` | `@Inject`, `@Singleton` |
| `org.slf4j:slf4j-api` | Startup and failure logging |
| `org.projectlombok:lombok` | Compile-scoped; `ManagementConfig` builder and logging field |
