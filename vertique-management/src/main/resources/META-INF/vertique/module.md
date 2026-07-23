<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Management Module

> **Status:** Implemented
> **Package:** `dev.vertique.management`
> **Artifact:** `management`
> **Depends on:** core, vertx-web

Provides a dedicated management HTTP server on a separate port (default 9090) for Kubernetes-style health probe endpoints. The management server runs independently of the main `HttpVerticle`, allowing different network policies (e.g., cluster-internal only) for liveness and readiness probes.

Health check SPI types live in the `core` module (`dev.vertique.core.health`) so that other modules (`db-postgresql`, `services`) can contribute checks without depending on `management`. This follows the same pattern as `JsonModule`.

---

## Key Classes

### ManagementVerticle

Vert.x `AbstractVerticle` that creates a lightweight HTTP server with two routes:

| Endpoint | Check set | UP response | DOWN response |
|----------|-----------|-------------|---------------|
| `GET /health/live` | `@Liveness Set<HealthCheck>` | 200 | 503 |
| `GET /health/ready` | `@Readiness Set<HealthCheck>` | 200 | 503 |

**Aggregation rules:**
- All checks run concurrently via `Future.all()`
- Overall status is UP only when ALL individual checks are UP
- Empty check set → UP (nothing to fail)
- Each check has a configurable timeout (default 5 seconds, set via `healthCheckTimeoutSeconds`); timeout counts as DOWN
- Exceptions thrown synchronously inside `check()` are caught and reported as DOWN

**Response format:**

```json
{
  "status": "UP",
  "checks": [
    {"name": "database", "status": "UP"},
    {"name": "services", "status": "UP", "data": {"user-service": "UP"}}
  ]
}
```

The `data` field is omitted from individual check objects when the result has no diagnostic data.

**When `management.enabled` is `false`**, the verticle starts successfully without binding a port.

**Deployment order:** `ManagementVerticle` should be deployed before `HttpVerticle` so that Kubernetes liveness probes are available before the application begins serving traffic.

```java
// In a startup verticle or lifecycle step (config() is pre-resolved):
AppComponent app = DaggerAppComponent.builder()
    .vertxModule(new VertxModule(vertx, config()))
    .build();
// Deploy management first, then HTTP (or use VerticleDeploymentManager for phase ordering)
app.verticleDeploymentManager()
    .deployAll()
    .onSuccess(v -> startPromise.complete())
    .onFailure(startPromise::fail);
```

**Dagger wiring:**

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    ManagementModule.class,   // provides config bindings, includes HealthCheckModule
    RestModule.class,
    AppModule.class,
    ResourceModule.class
})
interface AppComponent {
    ManagementVerticle managementVerticle();
    HttpVerticle httpVerticle();
}
```

### ManagementModule

Abstract Dagger `@Module` that binds `ManagementConfig` from configuration, includes
`HealthCheckModule` for the `@Liveness` / `@Readiness` multibinding sets, and **declares the
`Set<ManagementEndpointContributor>` multibinding** so endpoint contributors (e.g. the Prometheus
scrape endpoint) can mount routes on the management router. It is `abstract` because it hosts a
`@Multibinds` declaration.

```java
@Module(includes = HealthCheckModule.class)
public abstract class ManagementModule {

    @Multibinds
    abstract Set<ManagementEndpointContributor> managementEndpointContributors();

    @Provides @Singleton
    static ManagementConfig managementConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
            JsonConfigPaths.navigateObject(config, "management"), ManagementConfig.class);
    }
}
```

Consumers inject `ManagementConfig` directly and read `config.port()` / `config.enabled()` — there
are no separate `@Named("management.port")` or `@Named("management.enabled")` bindings.

**Bindings provided:**

| Type | Qualifier | Description |
|------|-----------|-------------|
| `ManagementConfig` | — | Full management configuration; consumers call `config.port()` and `config.enabled()` |

### ManagementConfig

Jackson-deserialized configuration value object with builder defaults. Deserialized from the `management` section by `ManagementModule`.

---

## Health Check SPI (core module)

The health check SPI lives in `dev.vertique.core.health` so contributing modules do not need to depend on `management`.

### HealthCheck

SPI interface for health indicators. Implementations report the health of a component and are discovered via Dagger multibinding.

```java
public interface HealthCheck {
    /** Human-readable name; must be unique within its qualifier set. */
    String name();

    /** Performs the check; returns a completed Future with HealthCheckResult. */
    Future<HealthCheckResult> check();
}
```

Implementations should return a completed future with an appropriate `HealthCheckResult` rather than a failed future. If `check()` throws or returns a failed future, `ManagementVerticle` reports it as DOWN with the error message.

### HealthCheckResult

Immutable record carrying the check's status and optional diagnostic data.

```java
public record HealthCheckResult(HealthStatus status, Map<String, Object> data) { ... }
```

**Factory methods:**

| Method | Status | Data |
|--------|--------|------|
| `HealthCheckResult.up()` | UP | empty |
| `HealthCheckResult.up(Map<String,Object>)` | UP | provided map |
| `HealthCheckResult.down()` | DOWN | empty |
| `HealthCheckResult.down(String error)` | DOWN | `{"error": "<error>"}` |
| `HealthCheckResult.down(Map<String,Object>)` | DOWN | provided map |

### HealthStatus

```java
public enum HealthStatus { UP, DOWN }
```

### @Liveness / @Readiness

Dagger qualifier annotations that classify a `HealthCheck` into the liveness or readiness probe set.

```java
@Qualifier @Documented @Retention(RUNTIME)
@Target({METHOD, PARAMETER, FIELD})
public @interface Liveness {}

@Qualifier @Documented @Retention(RUNTIME)
@Target({METHOD, PARAMETER, FIELD})
public @interface Readiness {}
```

**Guidelines for check classification:**
- `@Liveness` — lightweight "is the process alive" checks; must not check external dependencies
- `@Readiness` — dependency checks (database pool, downstream services, event bus consumers)

### HealthCheckModule

Abstract Dagger `@Module` (in `core`) declaring the `@Multibinds` empty sets. Must be included in any component or module that contributes or consumes health checks. `ManagementModule` includes it automatically; `DbPostgresqlModule` and `DispatchModule` also include it.

```java
@Module
public abstract class HealthCheckModule {

    @Multibinds @Liveness
    abstract Set<HealthCheck> livenessChecks();

    @Multibinds @Readiness
    abstract Set<HealthCheck> readinessChecks();
}
```

---

## Extension Points

### ManagementEndpointContributor

SPI for modules that need to mount additional routes on the management HTTP server. Implementations
are gathered via Dagger multibinding and invoked once per server start, in
`OrderedExtension.comparator()` order (ascending phase, then ascending priority, then ascending
`orderKey()`).

```java
public interface ManagementEndpointContributor extends OrderedExtension {
    /** Mounts one or more routes on the management Router. */
    void contribute(Router router);
}
```

**Invariants & Gotchas**

- `contribute()` is called on the management verticle's event loop — the method must not block.
  Route *handlers* added to the router may offload blocking work via `executeBlocking`.
- Health routes (`/health/*`) are mounted **before** contributors are invoked. Because Vert.x
  first-registration-wins semantics apply, re-registering a health path has no effect.
- If `contribute()` throws, management server startup fails immediately: the start-promise is
  failed, no HTTP port is bound, and no further contributors are invoked.
- Contributors are **not** invoked when `management.enabled=false`.
- When two contributors register handlers on the same path, the contributor with the lower
  comparator order (mounted first) wins.
- Lambda or method-reference registrations have a JVM-generated `orderKey` that is not stable
  across compilations. When relative order matters between two lambda contributors, assign them
  distinct `priority()` values or register them as named classes.

**Registering a contributor:**

```java
@Provides @IntoSet
static ManagementEndpointContributor metricsEndpoint(PrometheusHandler handler) {
    return router -> router.get("/metrics").handler(handler);
}
```

To control invocation order relative to other contributors, implement the interface as a named
class and override `phase()` and/or `priority()`:

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

`ManagementModule` declares the empty `@Multibinds` default set so the injection point is always
satisfiable even when no contributors are registered.

---

### `@Liveness Set<HealthCheck>` and `@Readiness Set<HealthCheck>`

Contribute health checks via Dagger multibinding in any `@Module`:

```java
// Liveness: custom process check
@Provides @IntoSet @Liveness
static HealthCheck processCheck(ProcessHealthCheck check) {
    return check;
}

// Readiness: external dependency check
@Provides @IntoSet @Readiness
static HealthCheck cacheCheck(CacheHealthCheck check) {
    return check;
}
```

A contributing module must include `HealthCheckModule` (or any module that transitively includes it) in its `@Module(includes = ...)` declaration.

**Writing a custom HealthCheck:**

```java
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
            boolean connected = cache.ping();
            return Future.succeededFuture(
                connected ? HealthCheckResult.up() : HealthCheckResult.down("ping failed")
            );
        } catch (Exception e) {
            return Future.succeededFuture(HealthCheckResult.down(e.getMessage()));
        }
    }
}
```

---

## Built-In Health Checks

### DatabaseHealthCheck (`db-postgresql`)

Executes `SELECT 1` against the PostgreSQL connection pool. Contributed automatically as `@Readiness` by `DbPostgresqlModule`.

```java
// Automatically contributed by DbPostgresqlModule:
@Provides @IntoSet @Readiness
static HealthCheck databaseHealthCheck(DatabaseHealthCheck check) {
    return check;
}
```

| Property | Value |
|----------|-------|
| Name | `"database"` |
| Qualifier | `@Readiness` |
| Module | `DbPostgresqlModule` |
| Query | `SELECT 1` |

### ServiceSupervisorHealthCheck (`services`)

Aggregates the supervision state of all event bus services managed by `ServiceSupervisor`. Reports DOWN if any supervised service is unavailable (restart budget exhausted). Reports UP when all services are available or when no services are supervised. Contributed automatically as `@Readiness` by `DispatchModule`.

```java
// Automatically contributed by DispatchModule:
@Provides @IntoSet @Readiness
static HealthCheck servicesHealthCheck(ServiceSupervisorHealthCheck check) {
    return check;
}
```

| Property | Value |
|----------|-------|
| Name | `"services"` |
| Qualifier | `@Readiness` |
| Module | `DispatchModule` |
| Data | Per-service status map when DOWN (e.g., `{"user-service": "DOWN"}`) |

---

## Configuration Reference

Configuration is deserialized from the `management` section of the application config via `ManagementConfig`:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `port` | `int` | `9090` | Management HTTP server port |
| `enabled` | `boolean` | `true` | Set to `false` to skip port binding (e.g., in tests) |
| `healthCheckTimeoutSeconds` | `long` | `5` | Per-check timeout in seconds for health probes |

Example in `config/application.json`:

```json
{
  "management": {
    "port": 9090,
    "enabled": true,
    "healthCheckTimeoutSeconds": 10
  }
}
```

**Properties file:** With hierarchical expansion, `management.port=9090` in a `.properties` file is automatically expanded to nested JSON, which `ManagementConfig` handles correctly.

---

## Dependencies

- `dev.vertique:core` — `HealthCheck`, `HealthCheckResult`, `HealthStatus`, `@Liveness`, `@Readiness`, `HealthCheckModule`, `OrderedExtension`, `@VertxConfig`
- `io.vertx:vertx-web` — `Router`, `RoutingContext`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`
- `org.projectlombok:lombok` (provided scope)

---

## Related ADRs

- ADR-0084: Framework Extension-Ordering Contract (Phase Dominates Priority) — establishes the `OrderedExtension` three-tier ordering model (phase → priority → orderKey) used by contributor sorting.
- ADR-0085: OrderedExtension Rolled Out Across Sorted Behavioral SPIs — records the decision to apply `OrderedExtension` uniformly across all sorted SPIs, including this one.
- ADR-0100: Management Endpoint Contribution SPI — records why a multibound `ManagementEndpointContributor` SPI was chosen over per-module standalone servers or mounting operational endpoints on the application router.
