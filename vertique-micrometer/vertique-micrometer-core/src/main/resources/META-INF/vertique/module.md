<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Micrometer Core Module

> **Status:** Beta
> **Package:** `dev.vertique.micrometer`
> **Artifact:** `vertique-micrometer-core`
> **Depends on:** core, bootstrap, vertx-micrometer-metrics

Provides a backend-agnostic Micrometer metrics layer for Vertique applications. The module
bootstraps a `CompositeMeterRegistry` before `Vertx` is built, wires it into Vert.x's metrics
subsystem via `MicrometerMetricsFactory`, and exposes it through a Dagger binding so all application
and framework code can inject `MeterRegistry` without knowing which backends are present.

The module does not ship a backend itself — backend modules (e.g., `vertique-micrometer-registry-prometheus`)
contribute implementations of the `MeterRegistryProvider` SPI and are activated simply by being on
the classpath. When no backends are on the classpath, or when `metrics.enabled=false`, the module
is entirely inert: no Vert.x metrics options are set, and the injected `MeterRegistry` is an empty
composite whose recording is a safe no-op.

---

## When To Use It

Add `vertique-micrometer-core` to any application that should emit metrics. Pair it with at least
one backend module (`vertique-micrometer-registry-prometheus`) and install `MicrometerModule` in the
Dagger `@Component`. Add `vertique-micrometer-rest` or `vertique-micrometer-services` to emit
request and dispatch timing from those subsystems.
Cache telemetry is provided by the separate `vertique-micrometer-cache` adapter, which
applications install explicitly when they use cache modules and want cache metrics.

Do not add this module if the application has no need for metrics — the module adds a runtime
cost only if backends are present (zero-backend path is truly a no-op; see Zero-backend cost below).

---

## Core Concepts

**Bootstrap before Vert.x.** Micrometer's registry must be wired into `VertxBuilder.withMetrics()`
before the `Vertx` instance is created. `MicrometerMetricsContributor` is a `VertxBuilderContributor`
discovered via ServiceLoader; it runs in phase `SYSTEM_FIRST` at priority `100` so it executes
before any application-level contributor.

**Composite registry + SPI backends.** All backends are assembled into a single
`CompositeMeterRegistry`. Each backend module implements `MeterRegistryProvider` and registers it
under `META-INF/services`. Providers are loaded by ServiceLoader at contributor startup, sorted by
`OrderedExtension`, validated for unique names, and assembled in order.

**Publish-on-success.** `MeterRegistryHolder.bootstrap(assembly)` is the final, no-throw step of
`contribute()`. If any earlier step fails, every already-created `MeterRegistryBackend` handle is
closed in reverse order, `JvmGcMetrics` is closed, and the composite is closed — the holder is
never touched on failure. Application code that runs before the contributor exits (i.e., in any
earlier `VertxBuilderContributor`) sees an empty composite whose recording is a no-op.

**Cardinality guard.** Before backends or JVM binders are added, `CardinalityGuard` installs one
`MeterFilter.maximumAllowableTags("vertique.", key, cap, denyAndWarnOnce)` filter per entry in the
frozen tag-key list. This bounds cardinality on all `vertique.*` meters at the composite level
before any individual backend sees the meter.

**Secret-safe failures.** `MetricsBootstrapException` carries no cause — only the component name
and failure class simple name. Backend SDK exceptions may embed resolved config values; severing
the cause chain prevents them from reaching the launcher's error log.

---

## Key Classes

### MeterRegistryProvider

ServiceLoader SPI for contributing a backend registry to the composite. Discovered before DI (pre-Vertx).

```java
public interface MeterRegistryProvider extends OrderedExtension {
    /** Unique backend identifier, e.g. "prometheus". Duplicate names fail fast at assembly time. */
    String backendName();

    /**
     * Creates the backend. Receives only the metrics.backends.<backendName()> subtree — never the
     * full config. Must not log or embed backendConfig values in exceptions.
     */
    MeterRegistryBackend create(JsonObject backendConfig) throws Exception;
}
```

Implement and register via `META-INF/services/dev.vertique.micrometer.MeterRegistryProvider`.

### MeterRegistryBackend

Handle returned by `MeterRegistryProvider.create()`. Bundles the registry with its lifecycle teardown.

```java
public interface MeterRegistryBackend extends AutoCloseable {
    MeterRegistry registry();
    @Override void close();   // idempotent; called on normal shutdown AND bootstrap rollback
}
```

`close()` must be idempotent. It is called in reverse creation order on both normal shutdown (via
`MicrometerMetricsContributor.onShutdown()`) and on bootstrap rollback when a later provider fails.

#### Invariants and Gotchas

- `close()` must release not just the `MeterRegistry` but any static state the backend published
  (e.g., `PrometheusBackend` stores the registry in a static field for the scrape endpoint to
  retrieve; `close()` must clear it for correct test isolation).
- Duplicate `backendName()` values across discovered providers cause `MicrometerAssembly.assemble()`
  to fail with `IllegalStateException` before any backend is created. Error message names both
  provider class names only.

### MicrometerAssembly

Package-private. Assembles the `CompositeMeterRegistry` in a defined order:

1. Common-tag filter (service name + extra tags)
2. Cardinality-guard filters (`CardinalityGuard.filters(config.cardinality())`)
3. Backend children in `OrderedExtension` order
4. JVM binders (when `metrics.jvm.enabled=true`): `JvmMemoryMetrics`, `JvmGcMetrics`,
   `JvmThreadMetrics`, `ClassLoaderMetrics`, `ProcessorMetrics`

All state is local during assembly — nothing is published until `MeterRegistryHolder.bootstrap(assembly)`
is called. On failure, already-created backends are closed in reverse order.

`JvmGcMetrics` implements `AutoCloseable`; it is tracked separately so it is closed first on
shutdown and rollback.

### MeterRegistryHolder

Package-private static holder. `registry()` is never null — it returns an empty composite before
bootstrap and the fully assembled composite after.

```java
static CompositeMeterRegistry registry();    // never null
static boolean bootstrapped();
static void bootstrap(MicrometerAssembly);   // called exactly once; throws ISE on second call
static void resetForTests();                 // @AfterEach test isolation
```

#### Invariants and Gotchas

- `bootstrap()` throws `IllegalStateException` on the second call. `contribute()` preflights with
  `bootstrapped()` and throws `MetricsBootstrapException` before assembly begins, so embedded
  multi-launch fails loud rather than silently reusing stale backends.
- `resetForTests()` closes any existing assembly and restores the pre-bootstrap state. Call it from
  `@AfterEach` in any test that exercises the contributor or the holder directly.

### MicrometerMetricsContributor

`VertxBuilderContributor` discovered via ServiceLoader. Phase `SYSTEM_FIRST`, priority `100`.

Active path (metrics enabled + at least one provider present):

1. Validates tag policy (`TagPolicyValidator.validate(config.tags())`) — throws `ConfigurationException`
   on violation (value-free message).
2. Assembles `MicrometerAssembly` with backends, filters, and JVM binders.
3. Builds `MicrometerMetricsOptions` (disabled domains from config, optional label override).
4. Sets options on `ctx.vertxOptions()`.
5. Calls `builder.withMetrics(new MicrometerMetricsFactory(assembly.composite()))`.
6. Calls `MeterRegistryHolder.bootstrap(assembly)` — the final no-throw step.

Inert path: `metrics.enabled=false` or zero providers — returns builder unchanged.

`onShutdown()` closes the assembly (idempotent). The launcher calls `onShutdown()` only for
contributors whose `contribute()` returned successfully, so the assembly is always closed on both
normal stop and subsequent-contributor failures — but never after a failed active-path contribution.

#### Invariants and Gotchas

- The `vertx.options` overlay in the launcher replaces the `VertxOptions` JSON, but the metrics
  **factory** (`withMetrics(...)`) is builder state, not options state, and survives the overlay.
  `MetricsOptions` retains its source JSON across the overlay-triggered rebuild. No action needed.
- `ConfigurationException` from tag validation is re-thrown as-is. All other exceptions are wrapped
  in `MetricsBootstrapException` (cause severed) to prevent backend SDK messages from reaching the
  launcher log.

### MicrometerModule

Dagger `@Module`. Install in the application `@Component`:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    MicrometerModule.class,
    ManagementModule.class,
    RestModule.class,
    AppModule.class
})
interface AppComponent {
    ManagementVerticle managementVerticle();
    HttpVerticle httpVerticle();
    // ...
}
```

Bindings provided:

| Type | Qualifier | Source |
|------|-----------|--------|
| `MeterRegistry` | — | `MeterRegistryHolder.registry()` |
| `MetricsConfig` | — | Deserialized from `metrics` config section |
| `SecurityEventObserver` | `@IntoSet` | `SecurityMetricsObserver` |

Adapter modules gate their hot-path callbacks by declaring `@BindsOptionalOf MetricsConfig` and
injecting `Optional<MetricsConfig>`. They read `.map(MetricsConfig::enabled).orElse(true)` — absent
means `MicrometerModule` is not installed, so the adapter defaults to enabled. When
`metrics.enabled=false`, adapter callbacks return immediately — no tag assembly, no registry lookup.

### SecurityMetricsObserver

Package-private `SecurityEventObserver` contributed into the `SecurityEventsModule` multibinding
(`vertique-security-runtime`) by `MicrometerModule`. Emits the security meters listed below. Guards are two-layered: the
class-level `enabled` boolean (cached at construction) and individual try/catch blocks — exceptions
from the registry are caught, logged at WARN, and swallowed; a succeeded `Future` is always returned.

`method` tags always use `AuthMethod.normalizedKind()` (a bounded `AuthMethodKind` enum value),
never `AuthMethod.id()` (arbitrary custom input).

### CardinalityGuard

Package-private static factory. Produces `MeterFilter` instances applied to the composite before
any backend or binder is added. Guards `vertique.*` meters only (the `"vertique."` prefix argument
in `maximumAllowableTags` handles this).

The frozen `GUARDED_TAG_KEYS` list covers all tag keys planned across V1–V4 adapter phases. Adding
a new `vertique.*` tag key to any adapter requires extending this list — this is a review-enforced
constraint.

### TagPolicyValidator

Package-private static validator. Called at contributor startup on `MetricsConfig.TagsConfig`.
Fails fast with a `ConfigurationException` on the first violation. Messages name the offending
key and violated rule but never any part of the value. Rules:

- Extra map: at most 16 entries
- Key format: `^[a-z][a-z0-9._-]{0,63}$`
- Reserved keys: `service`, any `vertique.` prefix, any cardinality-guarded key
- Secret-like keys: whole-segment match against
  `{password, passwd, secret, token, credential, apikey, authorization, bearer, accesskey, privatekey}`
  after splitting on `[._-]`; adjacent pairs `("api","key")`, `("access","key")`, `("private","key")`
  also rejected
- Value length: at most 256 characters
- Credential-shape values: prefixes `eyJ`, `AKIA`, `ghp_`, `xoxb-`/`xoxp-`, and (case-insensitive)
  `Bearer `/`Basic ` rejected

### MetricsBootstrapException

Unchecked exception thrown by `MicrometerMetricsContributor` when metrics assembly fails. No cause
constructor is provided. The message contains only the component or backend class name to prevent
accidental credential logging.

---

## Security Meters

`SecurityMetricsObserver` emits these meters when `metrics.enabled=true` and `metrics.security.enabled=true`:

| Meter | Type | Tags | Notes |
|-------|------|------|-------|
| `vertique.security.credentials` | Counter | `outcome` ∈ {accepted, rejected}; `method` = `normalizedKind()` | Per authentication attempt |
| `vertique.security.authz.decisions` | Counter | `decision` ∈ {permit, deny} | Per authorization check |
| `vertique.security.channels.active` | Gauge | — | Active persistent channel count; untagged (no type field on `ChannelLifecycleEvent`) |

The `type` dimension on the channels gauge is deferred pending a `ChannelLifecycleEvent` amendment.

Gauge underflow (a `ChannelClosedEvent` when the count is already 0) is clamped at 0 and logged as
a single WARN per gauge instance.

---

## Configuration

All keys live under the `metrics` section.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `metrics.enabled` | boolean | `true` | Master switch. `false` → contributor fully inert, no Vert.x options set. |
| `metrics.jvm.enabled` | boolean | `true` | Bind JVM binders (memory, GC, threads, class loader, processor). |
| `metrics.vertx.httpServer` | boolean | `true` | Enable Vert.x HTTP server metrics. |
| `metrics.vertx.httpClient` | boolean | `true` | Enable Vert.x HTTP client metrics. |
| `metrics.vertx.netServer` | boolean | `true` | Enable Vert.x net server metrics. |
| `metrics.vertx.netClient` | boolean | `true` | Enable Vert.x net client metrics. |
| `metrics.vertx.eventBus` | boolean | `true` | Enable Vert.x event-bus metrics. |
| `metrics.vertx.datagramSocket` | boolean | `true` | Enable Vert.x datagram-socket metrics. |
| `metrics.vertx.namedPools` | boolean | `true` | Enable Vert.x named-pool metrics. |
| `metrics.vertx.labels` | `List<String>` | `null` (Vert.x defaults; `HTTP_ROUTE` off) | Explicit list of `io.vertx.micrometer.Label` names to emit on Vert.x meters. `null` = use Vert.x default set. Empty list = suppress all labels. |
| `metrics.tags.service` | string | `null` | Service name applied to all meters as a `service` common tag. Falls back to `OTEL_SERVICE_NAME` env var, then `"unknown-service"`. Validated at startup. |
| `metrics.tags.extra` | `Map<String,String>` | `{}` | Additional common tags. Max 16 entries. Keys and values validated at startup (see Tag Policy). |
| `metrics.cardinality.maxTagValuesPerKey` | int | `200` | Max distinct values per guarded tag key on `vertique.*` meters. |
| `metrics.cardinality.maxMeters` | int | `0` | Global max meter count across the composite. `0` = unlimited. |
| `metrics.security.enabled` | boolean | `true` | Enable security-event metrics from `SecurityMetricsObserver`. |
| `metrics.backends.<name>.*` | object | `{}` | Per-backend subtree passed to `MeterRegistryProvider.create()`. Each provider receives only its own subtree. |

Example:

```json
{
  "metrics": {
    "enabled": true,
    "jvm": { "enabled": true },
    "tags": {
      "service": "my-api",
      "extra": { "env": "prod", "region": "eu-west-1" }
    },
    "cardinality": {
      "maxTagValuesPerKey": 500
    },
    "backends": {
      "prometheus": {}
    }
  }
}
```

---

## Extension Points

### MeterRegistryProvider

Add a backend to the composite by implementing `MeterRegistryProvider` and registering it under
`META-INF/services/dev.vertique.micrometer.MeterRegistryProvider`.

```java
public final class InfluxMeterRegistryProvider implements MeterRegistryProvider {

    @Override
    public String backendName() {
        return "influx";
    }

    @Override
    public MeterRegistryBackend create(JsonObject backendConfig) {
        InfluxConfig config = key -> backendConfig.getString(key.replace("influx.", ""), null);
        InfluxMeterRegistry registry = new InfluxMeterRegistry(config, Clock.SYSTEM);
        return new MeterRegistryBackend() {
            @Override public MeterRegistry registry() { return registry; }
            @Override public void close() { registry.close(); }
        };
    }
}
```

Configure the backend under `metrics.backends.influx.*` in the application config. The
`backendConfig` argument contains only that subtree.

#### Invariants and Gotchas

- `create()` must not log or embed any `backendConfig` field in exception messages.
- `close()` must release registry resources AND clear any static state the backend published
  (e.g., a scrape-endpoint registry lookup cache), because `close()` is also called on bootstrap
  rollback in test scenarios.
- `backendName()` must be unique across all discovered providers. Duplicate names cause startup
  failure before any backend is created.

---

## Dependencies

- `dev.vertique:vertique-core` — `OrderedExtension`, `ConfigurationException`, `@VertxConfig`,
  `JsonConfigPaths`
- `dev.vertique:vertique-bootstrap` — `VertxBuilderContributor`, `BootstrapContext`
- `io.micrometer:micrometer-core` — `MeterRegistry`, `CompositeMeterRegistry`, `MeterFilter`,
  `JvmGcMetrics` and other binders; pinned at 1.16.6 to match `vertx-micrometer-metrics` 5.1.2
- `io.vertx:vertx-micrometer-metrics` — `MicrometerMetricsFactory`, `MicrometerMetricsOptions`,
  `Label`, `MetricsDomain`
- `io.vertx:vertx-core` — `VertxBuilder`, `VertxOptions`
- `com.google.dagger:dagger`, `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`, `org.projectlombok:lombok` (provided)

---

## Zero-Backend Cost

When no backends are on the classpath (or all are disabled), the injected `MeterRegistry` is an
empty `CompositeMeterRegistry`. Micrometer's empty composite short-circuits all recording
operations to a no-op. This satisfies NFR-TEL-003: the module has no measurable hot-path cost
when metrics are not in use.

Measured on JVM 21 (3 × 1 million counter increment runs):

| Registry | Throughput |
|----------|-----------|
| Empty `CompositeMeterRegistry` | ≈ 3.4–5.3 ns/op |
| `SimpleMeterRegistry` (single real backend) | ≈ 4.8–5.9 ns/op |

The empty-composite path costs the same order as a real registry. Applications that add this
module but never add a backend module incur no measurable overhead.
