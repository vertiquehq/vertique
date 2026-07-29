<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# OpenTelemetry Prometheus Exemplar Bridge Module

> **Status:** Alpha
> **Package:** `dev.vertique.opentelemetry.prometheus`
> **Artifact:** `vertique-opentelemetry-prometheus`
> **Depends on:** opentelemetry-api (library), prometheus-metrics-tracer-otel (library)

Bridges the OpenTelemetry tracing API to the Prometheus exemplar `SpanContext` interface, allowing
Prometheus metric samples to carry the current OTel trace id. The module has no compile dependency
on `vertique-opentelemetry-core` or `vertique-micrometer-registry-prometheus` — it is a leaf
adapter between the two external libraries, independently installable alongside them.

The module does not configure tracing, does not configure metrics, and does not mount any
management endpoint. It provides exactly one Dagger binding: a `SpanContext` singleton that reads
`Span.current()` at exemplar-sample time.

---

## When To Use It

Install `vertique-opentelemetry-prometheus` when an application uses **both** OTel tracing and the
Prometheus registry backend and wants exemplar trace IDs attached to histogram and summary samples.
Three modules must be installed together:

1. `vertique-opentelemetry-core` — bootstraps the OTel SDK and the Vert.x tracer.
2. `vertique-micrometer-registry-prometheus` — provides the Prometheus backend and declares the
   `@BindsOptionalOf SpanContext` seam.
3. `vertique-opentelemetry-prometheus` — satisfies that seam with an OTel-backed implementation.

Set `metrics.prometheus.exemplars.enabled=true` in configuration to activate exemplar rendering.

Do not install this module if the application does not use `vertique-micrometer-registry-prometheus`
— the `SpanContext` binding will be present on the Dagger graph with no consumer.

---

## Core Concepts

**`@BindsOptionalOf` seam.** `MicrometerPrometheusModule` declares `SpanContext` as
`@BindsOptionalOf`. When no module on the Dagger graph provides a `SpanContext` binding, the
optional is empty and exemplars remain inactive regardless of the config flag. When this bridge
module is installed, the optional is present and `PrometheusScrapeEndpoint` wires the deferred
span context to the live implementation at `contribute()` time.

**Lazy, stateless read.** `OpenTelemetrySpanContext` (from `prometheus-metrics-tracer-otel`)
delegates to `Span.current()` lazily each time Prometheus samples an exemplar. No span is captured
or held by the bridge. When no span is current, `getCurrentTraceId()` and `getCurrentSpanId()`
return `null` and no exemplar trace information is emitted.

**Zero vertique core coupling.** The bridge has no compile dependency on
`vertique-opentelemetry-core` or `vertique-micrometer-registry-prometheus`. It depends only on
`io.opentelemetry:opentelemetry-api` (for `Span.current()`, transitively via
`prometheus-metrics-tracer-otel`) and `io.prometheus:prometheus-metrics-tracer-otel`. This preserves
the invariant that neither telemetry core knows about the other.

---

## Key Classes

### OpenTelemetryPrometheusExemplarModule

Abstract Dagger `@Module`. Provides the singleton `SpanContext` that satisfies the
`@BindsOptionalOf` seam in `MicrometerPrometheusModule`.

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    OpenTelemetryModule.class,                    // vertique-opentelemetry-core
    MicrometerModule.class,
    ManagementModule.class,
    MicrometerPrometheusModule.class,             // vertique-micrometer-registry-prometheus
    OpenTelemetryPrometheusExemplarModule.class,  // this module
    AppModule.class
})
interface AppComponent {
    ManagementVerticle managementVerticle();
    HttpVerticle httpVerticle();
}
```

Bindings provided:

| Type | Qualifier | Source |
|------|-----------|--------|
| `io.prometheus.metrics.tracer.common.SpanContext` | `@Singleton` | `new OpenTelemetrySpanContext()` |

The provided `OpenTelemetrySpanContext` reads `Span.current()` lazily at exemplar-sample time. It
satisfies the `@BindsOptionalOf SpanContext` declared by `MicrometerPrometheusModule`.

#### Invariants and Gotchas

- This binding satisfies `MicrometerPrometheusModule`'s optional seam only when both modules are on
  the same Dagger component. Installing this bridge **alone** is harmless and compiles fine — the
  `@Provides SpanContext` is an ordinary binding that does not depend on the `@BindsOptionalOf`
  declaration existing; it is simply never consumed (exemplars off). Likewise, installing the
  Prometheus registry without this bridge leaves the registry's optional empty. The binding takes
  effect only when both are present and `metrics.prometheus.exemplars.enabled=true`.
- Exemplars render only in OpenMetrics 1.0.0 format (`Accept: application/openmetrics-text`). Plain
  Prometheus text 0.0.4 scrapes do not include exemplar data regardless of this binding's presence.
- When no span is current at sample time, all exemplar accessors return safe defaults (`null`
  trace/span id, `false` for `isCurrentSpanSampled()`). The Prometheus exemplar machinery silently
  emits no trace information in that case.
- The module has a private no-arg constructor — it is abstract and cannot be instantiated.

---

## Wiring

Configuration is in the `metrics` section, alongside `vertique-micrometer-registry-prometheus`:

```json
{
  "metrics": {
    "enabled": true,
    "prometheus": {
      "exemplars": {
        "enabled": true
      }
    }
  }
}
```

The config flag `metrics.prometheus.exemplars.enabled=true` is read by `PrometheusScrapeEndpoint`
(in `vertique-micrometer-registry-prometheus`). When `true` and a `SpanContext` binding is present
in the Dagger graph, the endpoint wires the `DeferredSpanContext` to the live implementation.

Exemplar trace IDs are visible in the OpenMetrics scrape output on the management port. See the
exposure guidance in `vertique-micrometer-registry-prometheus`'s documentation (NFR-TEL-005):
never expose the management port to public networks. Exemplar trace and span IDs are per-sample
annotations attached at scrape time — they are not meter tags, so `vertique-micrometer-core`'s
cardinality guard (which bounds distinct tag *values* per key) does not apply to them.

---

## Dependencies

- `io.prometheus:prometheus-metrics-tracer-otel` — `OpenTelemetrySpanContext` (provides the bridge
  implementation; transitively brings `opentelemetry-api` and `prometheus-metrics-tracer-common`)
- `io.prometheus:prometheus-metrics-tracer-common` — `SpanContext` (the interface being provided)
- `com.google.dagger:dagger`, `jakarta.inject:jakarta.inject-api`

No dependency on any `dev.vertique` module.
