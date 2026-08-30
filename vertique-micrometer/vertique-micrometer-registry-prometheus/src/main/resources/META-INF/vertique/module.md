<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Micrometer Registry Prometheus Module

> **Status:** Beta
> **Package:** `dev.vertique.micrometer.prometheus`
> **Artifact:** `vertique-micrometer-registry-prometheus`
> **Depends on:** micrometer-core, management, vertx-web, micrometer-registry-prometheus, prometheus-metrics-tracer-common

Provides a Prometheus backend for the Micrometer metrics layer and mounts a scrape endpoint on the
management HTTP server. Installing this module registers a `PrometheusMeterRegistry` as a backend
inside `vertique-micrometer-core`'s composite, then exposes that registry at a configurable path on
the management port so Prometheus can scrape it.

The module does not produce a Dagger `PrometheusMeterRegistry` binding — there is deliberately
exactly one registry instance, created pre-DI by the ServiceLoader provider and held in a
package-private static holder. Application and framework code always injects the generic
`MeterRegistry` from `MicrometerModule`.

---

## When To Use It

Add `vertique-micrometer-registry-prometheus` to any application that should expose metrics in
Prometheus format. Install `MicrometerPrometheusModule` in the Dagger `@Component` alongside
`MicrometerModule` and `ManagementModule`. The module is inert when `metrics.enabled=false`, when
`VertiqueApplication` is not used to launch the app, or when no management verticle is deployed.

---

## Core Concepts

**Single-registry identity.** Exactly one `PrometheusMeterRegistry` exists per process. It is
created pre-DI by `PrometheusMeterRegistryProvider` (discovered via ServiceLoader), published to
the package-private `PrometheusBackend` static holder, and added to `vertique-micrometer-core`'s
`CompositeMeterRegistry` as a backend child. The handle's `close()` clears the holder — this
makes rollback and test isolation correct without a second construction path.

**Scrape endpoint on the management port only.** `PrometheusScrapeEndpoint` implements
`ManagementEndpointContributor` and mounts the scrape route on the management router at
`#contribute(Router)` time, never on the application HTTP router. The management port is protected
by network policy (NFR-TEL-005) — metric names, label values, and (when enabled) exemplar trace
and span IDs are visible to anyone with access to that port. Never expose the management port on a
public network interface.

**Worker-thread rendering.** Prometheus gauges execute user-provided lambdas at render time. To
prevent those lambdas from blocking the management event loop, `PrometheusScrapeEndpoint` offloads
`PrometheusMeterRegistry.scrape()` to a Vert.x worker thread via
`Vertx.executeBlocking(..., ordered=false)`.

**Exemplars are opt-in.** Exemplars attach trace and span IDs to histogram and summary samples. They
are disabled by default (`metrics.prometheus.exemplars.enabled=false`) because they expose
trace IDs on the management port. When enabled, exemplars render only in OpenMetrics format and
only when an `io.prometheus.metrics.tracer.common.SpanContext` Dagger binding is present — provided
by `vertique-opentelemetry-prometheus` when installed alongside `vertique-opentelemetry-core`.

**Disabled-state matrix.** The scrape endpoint mounts nothing (INFO log) when any of the following
is true: `metrics.enabled=false`; no `MeterRegistryProvider` on the classpath; the application is
not launched via `VertiqueApplication` (contributor never ran). Health endpoints are unaffected in
all cases.

**Cardinality guarding happens upstream.** `vertique-micrometer-core`'s composite-level cardinality
guard (`metrics.cardinality.*`) is installed on the composite registry before this module's backend
is added as a child. A `vertique.*` meter denied by that guard is never created on any child
registry, including this module's `PrometheusMeterRegistry` — the Prometheus scrape output already
reflects the bounded cardinality.

---

## Key Classes

### PrometheusMeterRegistryProvider

`MeterRegistryProvider` implementation registered via
`META-INF/services/dev.vertique.micrometer.MeterRegistryProvider`. Discovered and invoked by
`MicrometerMetricsContributor` before `Vertx` is built.

On `create(JsonObject)`:

1. Creates a `DeferredSpanContext` that can be wired to a live tracing integration later.
2. Creates a `PrometheusMeterRegistry` with `PrometheusConfig.DEFAULT`, a fresh
   `PrometheusRegistry`, and the deferred span context.
3. Publishes both to `PrometheusBackend`.
4. Returns a `MeterRegistryBackend` handle whose `close()` is idempotent and clears the holder.

The `backendConfig` argument (the `metrics.backends.prometheus.*` subtree) is accepted but
currently unused. `metrics.backends.prometheus.*` keys are reserved for future backend options.

#### Invariants and Gotchas

- `close()` clears `PrometheusBackend` atomically via `AtomicBoolean`. This is called on both
  normal shutdown (reverse-order by `MicrometerMetricsContributor.onShutdown()`) and bootstrap
  rollback when a later provider fails. Test suites must call `MeterRegistryHolder.resetForTests()`
  from `@AfterEach` to clear both the composite holder and the `PrometheusBackend` state.
- There is no Dagger `PrometheusMeterRegistry` binding. Code that needs the concrete type for
  testing may access it via `PrometheusBackend.registry()`, which is package-private to
  `dev.vertique.micrometer.prometheus`.

### PrometheusScrapeEndpoint

`@Singleton` `ManagementEndpointContributor`. Mounted into the management router multibinding set
by `MicrometerPrometheusModule`.

`contribute(Router)` behavior:

- Reads `PrometheusBackend.registry()`. If empty, logs at INFO and returns without mounting
  any route.
- If exemplars are enabled (`metrics.prometheus.exemplars.enabled=true`) and a `SpanContext` is
  present in the Dagger graph, wires the `DeferredSpanContext` to the real implementation.
- Mounts `GET {metrics.scrape.path}` on the management router.

Per-request handling:

- Inspects the `Accept` header. If it contains `application/openmetrics-text`, renders in
  OpenMetrics 1.0.0 format (`application/openmetrics-text; version=1.0.0; charset=utf-8`).
  Otherwise renders in Prometheus text 0.0.4 format (`text/plain; version=0.0.4; charset=utf-8`).
- Offloads rendering to a worker thread via `executeBlocking(..., ordered=false)`.
- On render success: responds 200 with the appropriate `Content-Type`.
- On render failure: logs the exception class name at WARN and responds 500 with the constant body
  `"metrics unavailable"`. No exception detail is included in the response.

### DeferredSpanContext

Package-private `SpanContext` implementation that forwards all calls to a delegate once one is
attached via `delegate(SpanContext)`. Before attachment, `getCurrentTraceId()` and
`getCurrentSpanId()` return `null`, `isCurrentSpanSampled()` returns `false`, and
`markCurrentSpanAsExemplar()` is a no-op.

The registry is constructed with this deferred instance so that exemplar support can be enabled
later, at `contribute()` time, without requiring the tracing integration to be present at backend
creation time. The `delegate` field is `volatile` for safe cross-thread publication.

### PrometheusBackend

Package-private static holder. Stores the `PrometheusMeterRegistry` and `DeferredSpanContext` in
`AtomicReference`s so the scrape endpoint and backend provider can access them safely across
threads.

| Method | Description |
|--------|-------------|
| `registry()` | `Optional<PrometheusMeterRegistry>` — empty until `publish()`, empty again after `clear()` |
| `spanContext()` | `Optional<DeferredSpanContext>` — aligned with `registry()` |
| `publish(registry, spanContext)` | Called by `PrometheusMeterRegistryProvider.create()` |
| `clear()` | Called by the backend handle's `close()` on shutdown or rollback |

### MicrometerPrometheusModule

Abstract Dagger `@Module`. Install alongside `MicrometerModule` and `ManagementModule`:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    MicrometerModule.class,
    ManagementModule.class,
    MicrometerPrometheusModule.class,
    AppModule.class
})
interface AppComponent {
    ManagementVerticle managementVerticle();
    HttpVerticle httpVerticle();
}
```

Bindings provided:

| Type | Qualifier | Description |
|------|-----------|-------------|
| `PrometheusScrapeConfig` | `@Singleton` | Deserialized and validated from `metrics` config section |
| `ManagementEndpointContributor` | `@IntoSet` | `PrometheusScrapeEndpoint` contributed to the multibinding set |
| `SpanContext` | `@BindsOptionalOf` | Optional tracing integration; provided by `vertique-opentelemetry-prometheus` when installed |

---

## Configuration

Keys live under the `metrics` section, alongside `vertique-micrometer-core` keys.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `metrics.scrape.path` | string | `"/metrics"` | Path on the management router where Prometheus scrapes. Must match `^/[A-Za-z0-9._/-]*$`, must not contain `*` or `:`, must not equal or start with `/health`. |
| `metrics.prometheus.exemplars.enabled` | boolean | `false` | Opt-in exemplar support. When `true` AND a `SpanContext` binding is present, trace and span IDs are attached to histogram and summary samples. Exemplars render only in OpenMetrics format. |
| `metrics.backends.prometheus.*` | object | `{}` | Reserved for future Prometheus backend options. Currently unused — `PrometheusConfig.DEFAULT` is always applied. |

Example config:

```json
{
  "metrics": {
    "enabled": true,
    "scrape": {
      "path": "/metrics"
    },
    "prometheus": {
      "exemplars": {
        "enabled": false
      }
    }
  }
}
```

### Exposure Guidance (NFR-TEL-005)

The `/metrics` endpoint exists **only on the management port**. The management port is protected by
network policy, not authentication. Metric names, label values, and — when exemplars are enabled —
trace and span IDs are visible to anyone with access to the port. Never expose the management port
to public networks or untrusted network segments.

---

## Extension Points

### Exemplar SpanContext

`MicrometerPrometheusModule` declares `SpanContext` as `@BindsOptionalOf`. When
`vertique-opentelemetry-prometheus` is installed, its `OpenTelemetryPrometheusExemplarModule`
contributes a `SpanContext` binding backed by `OpenTelemetrySpanContext` (from
`io.prometheus:prometheus-metrics-tracer-otel`), which reads `Span.current()` lazily at
exemplar-sample time. When that module is absent, the optional is empty and exemplars remain
inactive regardless of `metrics.prometheus.exemplars.enabled`.

See `dev.vertique:vertique-opentelemetry-prometheus` for installation details.

To wire a custom span context instead:

```java
@Module
public abstract class MyTracingModule {

    @Provides
    @Singleton
    static SpanContext spanContext() {
        return MyCustomSpanContext.INSTANCE;
    }
}
```

Install `MyTracingModule` alongside `MicrometerPrometheusModule` in the `@Component`.

---

## Wiring

`PrometheusMeterRegistryProvider` is auto-discovered via ServiceLoader — no explicit wiring is
needed for the registry creation step. The Dagger module wires the scrape endpoint into the
management router.

**Launch via `VertiqueApplication` is required.** The provider runs inside
`MicrometerMetricsContributor`, which is a `VertxBuilderContributor` discovered by
`VertiqueApplication` before `Vertx` is built. If the app is launched directly (e.g., with
`Vertx.vertx()` in a unit test), the contributor never runs, `PrometheusBackend` stays empty,
and the scrape endpoint silently mounts nothing.

Minimal component for a metrics-enabled application:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    MicrometerModule.class,
    ManagementModule.class,
    MicrometerPrometheusModule.class,
    RestModule.class,
    AppModule.class
})
interface AppComponent {
    ManagementVerticle managementVerticle();
    HttpVerticle httpVerticle();
}
```

---
The adapter's simple SPI contribution is declared on its injectable implementation with
`@RegisterIntoSet`. During the provider build, `vertique-codegen-dagger` emits
`GeneratedRegistrationsModule`, which this module includes explicitly. The generated module contains
only this type adaptation; configuration, registry, and optional bindings remain hand-written.

## Dependencies

- `dev.vertique:vertique-micrometer-core` — `MeterRegistryProvider`, `MeterRegistryBackend`,
  `MicrometerModule`, `MeterRegistryHolder`
- `dev.vertique:vertique-management` — `ManagementEndpointContributor`, `ManagementModule`,
  `ManagementVerticle`
- `io.micrometer:micrometer-registry-prometheus` — `PrometheusMeterRegistry`, `PrometheusConfig`
- `io.prometheus:prometheus-metrics-tracer-common` — `SpanContext` (exemplar bridge interface)
- `io.vertx:vertx-web` — `Router` (management endpoint mounting)
- `com.google.dagger:dagger`, `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`, `org.projectlombok:lombok` (provided)
