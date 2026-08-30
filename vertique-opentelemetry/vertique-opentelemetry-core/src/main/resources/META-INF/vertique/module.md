<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# OpenTelemetry Core Module

> **Status:** Alpha
> **Package:** `dev.vertique.opentelemetry`
> **Artifact:** `vertique-opentelemetry-core`
> **Depends on:** core, correlation, bootstrap, security-core

Provides opt-in distributed tracing for Vertique applications using the OpenTelemetry SDK. The
module bootstraps an `OpenTelemetrySdk` before `Vertx` is created (or reuses an existing global
installed by a javaagent), wires the Vert.x tracing subsystem, bridges trace ids into the framework's
`CorrelationContext` for trace–log correlation, emits security lifecycle events as span events, and
exposes `OpenTelemetry`, `Tracer`, and `TraceReferenceResolver` through Dagger bindings.

The module does not own metrics collection (meters belong to `dev.vertique:vertique-micrometer-core`)
and does not own log-output bridging (that is `dev.vertique:vertique-logging`'s responsibility). It
owns the OTel SDK lifecycle, the Vert.x tracer installation, and the correlation seam. It has **no
dependency on any Prometheus or metrics-backend library**.

---

## When To Use It

Add `vertique-opentelemetry-core` to any application that should emit distributed traces, and install
`OpenTelemetryModule` in the Dagger `@Component`. The Vert.x tracer and the correlation bridge
activate automatically when both `OpenTelemetryBootstrapContributor` (discovered via `ServiceLoader`
from this artifact) and `OpenTelemetryModule` are present.

Do not add this module if the application has no need for distributed tracing. With
`tracing.enabled=false` the module is inert: a no-op `VertxTracerFactory.NOOP` is installed and Dagger
receives `OpenTelemetry.noop()`.

Optional siblings, each independently installable:

| Artifact | Adds |
|---|---|
| `dev.vertique:vertique-opentelemetry-rest` | Route and `operationId` attributes on the Vert.x-created HTTP server span |
| `dev.vertique:vertique-opentelemetry-services` | Service-dispatch attributes on the event-bus CONSUMER span |
| `dev.vertique:vertique-opentelemetry-prometheus` | OTel trace ids as exemplars on Prometheus samples. Pair with `dev.vertique:vertique-micrometer-registry-prometheus` and set `metrics.prometheus.exemplars.enabled=true` — this exposes trace and span ids on the management port's scrape endpoint. |

---

## Core Concepts

**Bootstrap before Vert.x.** The OTel SDK must exist before the `Vertx` instance is created so the
Vert.x tracing subsystem can be wired in. `OpenTelemetryBootstrapContributor` is a
`VertxBuilderContributor` discovered via `ServiceLoader`; it runs in phase `SYSTEM_FIRST` at priority
`110`, after the Micrometer metrics contributor at priority `100`. An application contributor that
must observe the tracer wiring should order itself after that.

**Reuse-or-provision.** On the enabled path the contributor checks `GlobalOpenTelemetry.isSet()`. When
an existing global is present — typically installed by a javaagent — it is reused as-is and the whole
`tracing.otel.*` configuration subtree is **silently ignored**; configure the agent through its own
mechanism instead. When no global exists, the contributor provisions one via
`AutoConfiguredOpenTelemetrySdk` and owns its shutdown. **The framework never closes a global it did
not create.**

**`GlobalOpenTelemetry` is the canonical instance.** There is no framework-owned holder type. The
contributor registers the SDK as the global on a successful build, and Dagger binds
`GlobalOpenTelemetry.getOrNoop()` at component-construction time — which is after the contributor
ran, so the global is set.

**Span context propagation is Vert.x-owned.** Installing the tracer makes `vertx-opentelemetry`
instrument HTTP client/server exchanges and event-bus request/reply, so an HTTP SERVER span, an
event-bus CONSUMER span, and their children share a trace with the caller's span as parent. This
module contributes no span *names* of its own — span naming and kind are decided by
`vertx-opentelemetry` and the OTel semantic conventions it applies. What this module adds to spans is
listed under **Emitted Telemetry**.

**Trace–log correlation.** `OpenTelemetryTraceReferenceResolver` reads `Span.current()` and returns a
`TraceReference` for any valid span context; the REST ingress middleware calls
`CorrelationContextMutator.setTrace(...)`, which mirrors `traceId` and `spanId` into the live
`CorrelationContext` and its MDC for the duration of the request. Ids are mirrored **regardless of
sampling** — see **Failures, Constraints, and Common Mistakes**.

**Security span events.** When `OpenTelemetryModule` and the security runtime's `SecurityEventsModule`
are installed in the same Dagger component, security lifecycle events are recorded as span *events* on
the current active span — never as new child spans, which keeps the operation cheap and leaves the
trace tree unchanged.

---

## Key Classes

### OpenTelemetryModule

The Dagger `@Module` to install:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    MicrometerModule.class,       // if metrics are also enabled
    OpenTelemetryModule.class,
    RestModule.class,
    AppModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

Bindings provided:

| Type | Qualifier | Value |
|---|---|---|
| `TracingConfig` | — | The `tracing` config section, parsed through the injected `ConfigParser` |
| `OpenTelemetry` | — | `OpenTelemetry.noop()` when `tracing.enabled=false`; otherwise `GlobalOpenTelemetry.getOrNoop()` |
| `Tracer` | — | `openTelemetry.getTracer("dev.vertique")` |
| `TraceReferenceResolver` | — | The built-in OTel resolver; satisfies the `@BindsOptionalOf` declaration in `CorrelationContextModule` |
| `SecurityEventObserver` | `@IntoSet` | The built-in span-event observer, contributed to the `Set<SecurityEventObserver>` multibinding declared by `SecurityEventsModule` in `dev.vertique:vertique-security-runtime` |

When `OpenTelemetryModule` is absent from the graph, the `TraceReferenceResolver` optional is empty
and trace ids do not flow into `CorrelationContext`.

### OpenTelemetryBootstrapContributor

Public `VertxBuilderContributor`, registered by this artifact in
`META-INF/services/dev.vertique.bootstrap.VertxBuilderContributor`. Applications do not construct or
register it; the entry that matters to an application is its ordering — `SYSTEM_FIRST` / priority
`110` — and its shutdown ownership: `onShutdown()` closes the SDK **only** if this contributor built
it. `OpenTelemetrySdk.close()` blocks for up to 10 s and is idempotent.

### TracingConfig

The typed view of the `tracing` section (`@Builder @Jacksonized`, fluent accessors), injectable
anywhere in the graph. It models `enabled` and the nested `security.spanEvents` flag only; the
`tracing.otel` subtree is read raw and forwarded to autoconfigure, so it is deliberately not modeled
here. Unknown properties are ignored, and an explicit JSON `null` for `security` is treated as absent
so the nested defaults survive.

### TracingBootstrapException

Extends `dev.vertique.core.exception.ConfigurationException`. Thrown by the bootstrap contributor when
SDK provisioning fails, or when tracing is relaunched in a JVM whose owned global SDK was already
closed. **It has no cause constructor by design**: third-party SDK exceptions can embed hostnames,
credentials, and endpoint URLs in their message or cause chain, and the launcher logs the full chain.
The message carries only the failing class's simple name or a structural description.

---

## Emitted Telemetry

The security observer adds **span events** to the current recording span.

| Trigger | Span event name | Attributes |
|---|---|---|
| Credential accepted | `vertique.security.credential.accepted` | `vertique.auth.method` |
| Credential rejected | `vertique.security.credential.rejected` | `vertique.auth.method`, `vertique.auth.reason` |
| Authorization decided | `vertique.security.authz.decision` | `vertique.authz.decision`, `vertique.authz.reason` |
| Channel opened | `vertique.security.channel.opened` | none |
| Channel identity refreshed | `vertique.security.channel.refreshed` | none |
| Channel closed | `vertique.security.channel.closed` | none |

| Attribute key | Value contract |
|---|---|
| `vertique.auth.method` | `AuthMethod.normalizedKind().name()`, or `"unknown"` when the method or its kind is absent or blank. Never `AuthMethod.id()` — that is arbitrary caller input and would create unbounded cardinality. |
| `vertique.auth.reason`, `vertique.authz.reason` | The reason code when it matches `[A-Z0-9_]{1,64}`; otherwise `"OTHER"`. Null and blank codes also become `"OTHER"`. |
| `vertique.authz.decision` | `"permit"` or `"deny"`. |

Channel identifiers are deliberately excluded from every attribute set, to limit cardinality and to
avoid emitting session-tracking data into a trace backend.

Emission is gated twice: on `tracing.enabled && tracing.security.spanEvents` (read once at
construction) and on `Span.current().isRecording()`. Every observer method returns a succeeded
`Future` and swallows any OTel API failure with a WARN, so a tracing fault can never affect the
security pipeline.

---

## Extension Points

### TraceReferenceResolver

SPI declared in `dev.vertique:vertique-correlation` and resolved through
`@BindsOptionalOf TraceReferenceResolver` in `CorrelationContextModule`:

```java
Optional<TraceReference> currentTrace();
```

`OpenTelemetryModule` provides the built-in OTel implementation **unconditionally**, and at most one
implementation may be on the graph — adding a second provider alongside `OpenTelemetryModule` is a
duplicate Dagger binding. To install a custom resolver (for example to suppress unsampled ids), **omit
`OpenTelemetryModule`**, wire the SDK and the other OTel bindings yourself, and provide your own:

```java
package com.example.app;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.correlation.TraceReference;
import dev.vertique.correlation.TraceReferenceResolver;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.inject.Singleton;
import java.util.Optional;

@Module
public abstract class SampledOnlyTracingModule {

    @Provides
    @Singleton
    static TraceReferenceResolver traceReferenceResolver() {
        return () -> {
            SpanContext sc = Span.current().getSpanContext();
            if (!sc.isValid() || !sc.isSampled()) {
                return Optional.empty();
            }
            return Optional.of(new TraceReference(sc.getTraceId(), sc.getSpanId(), "custom"));
        };
    }
}
```

#### Invariants & Gotchas

- The resolver is invoked on the Vert.x event loop during request ingress; it must be cheap and
  non-blocking.
- A resolver should never throw. The caller guards with `try`/`catch` (WARN, then continue), but a
  throwing resolver still emits one WARN line per request.
- `TraceReference` is `record TraceReference(String traceId, @Nullable String spanId, String source)`
  and lives in `dev.vertique:vertique-core`; `traceId` and `source` must be non-null.
- The `@BindsOptionalOf` model means the binding is *absent*, not null, when no module supplies it —
  there is no NPE risk in the ingress path.

---

## Configuration

All keys live under the `tracing` section. The `tracing.otel.*` subtree is flattened to `otel.*` and
handed to autoconfigure as its **lowest**-precedence layer, so environment variables and system
properties always win. The whole subtree is **inert on the reuse path**.

| Key | Type | Default | Description |
|---|---|---|---|
| `tracing.enabled` | boolean | `true` | Master switch. `false` installs the NOOP tracer and binds `OpenTelemetry.noop()`. |
| `tracing.security.spanEvents` | boolean | `true` | Emit the security span events listed above. |
| `tracing.otel.*` | object | — | Flattened to `otel.*` and supplied to `AutoConfiguredOpenTelemetrySdk`. |

Two `otel.*` properties are seeded before the flatten step and can be overridden by it:
`otel.metrics.exporter=none` (meters are Micrometer-owned) and `otel.logs.exporter=none`.

**Service-name resolution**, first non-blank wins:

1. `tracing.otel.service.name` — via the flatten step, which overwrites the seed.
2. `metrics.tags.service` from the root config.
3. The literal `"unknown-service"`.

`OTEL_SERVICE_NAME` overrides all three through autoconfigure's own precedence.

```json
{
  "tracing": {
    "enabled": true,
    "security": { "spanEvents": true },
    "otel": {
      "exporter": {
        "otlp": {
          "endpoint": "http://jaeger:4318",
          "protocol": "http/protobuf"
        }
      },
      "traces.sampler": "parentbased_traceidratio",
      "traces.sampler.arg": "0.1"
    }
  }
}
```

`exporter.otlp.endpoint` becomes `otel.exporter.otlp.endpoint`; `OTEL_EXPORTER_OTLP_ENDPOINT` still
takes precedence over it.

---

## Failures, Constraints, and Common Mistakes

- **A config section that is present but not a JSON object aborts startup.** `tracing`,
  `tracing.otel`, `metrics`, and `metrics.tags` are each traversed with a tolerant navigator that
  throws `ConfigurationException` when the key is bound to a non-object. That exception passes through
  the contributor untouched, so a config-shape error stays distinguishable from an SDK failure.
- **SDK provisioning failure aborts startup with no cause chain.** The failure surfaces as
  `TracingBootstrapException` naming only the underlying exception's simple class name. If a failing
  exporter endpoint needs diagnosis, reproduce it outside the launcher — the message will not carry
  the endpoint.
- **A key cannot be both a leaf and a parent in the `tracing.otel` subtree.** The flattener descends
  into nested objects and stringifies leaves, so `{"traces": {"sampler": "…"}}` and
  `{"traces": {"sampler": {"arg": "…"}}}` cannot coexist — JSON forbids the duplicate key. Where OTel
  defines both `otel.x.y` and `otel.x.y.z`, write **dotted leaf keys** as in the example above.
- **Relaunching tracing in one JVM is unsupported.** Once this process has built and closed an owned
  global SDK, a subsequent `contribute()` on the enabled path fails fast with
  `TracingBootstrapException` rather than wiring a closed SDK. `GlobalOpenTelemetry` is a JVM-wide
  singleton that cannot be safely re-registered. This affects embedded and multi-launch test hosts,
  not ordinary applications.
- **On the reuse path, `tracing.otel.*` silently does nothing.** The contributor logs one INFO line
  when it reuses a global. If exporter settings appear to be ignored, check for a javaagent first.
- **Unsampled trace ids still reach the logs.** Ids are mirrored for any structurally valid
  `SpanContext`, sampled or not, so log entries remain correlatable with each other. Those ids may not
  resolve to a record in Jaeger, Zipkin, or another backend when sampling dropped the trace; that is
  expected. Suppressing them requires a custom `TraceReferenceResolver` (see Extension Points).
- **Correlation ids reach log *output* only via the logging module.** The correlation MDC is Vert.x
  context-local storage, not thread-local SLF4J MDC. This module guarantees `traceId` and `spanId` are
  present in the correlation context; bridging that to log output is
  `dev.vertique:vertique-logging`'s job.
- **Two `TraceReferenceResolver` providers is a Dagger compile error**, not a runtime override. Omit
  `OpenTelemetryModule` rather than trying to outrank its binding.

---
The adapter's simple SPI contribution is declared on its injectable implementation with
`@RegisterIntoSet`. During the provider build, `vertique-codegen-dagger` emits
`GeneratedRegistrationsModule`, which this module includes explicitly. The generated module contains
only this type adaptation; configuration, registry, and optional bindings remain hand-written.

## Dependencies

- `dev.vertique:vertique-core` — `ExtensionPhase`, `@VertxConfig`, `ConfigParser`, `JsonConfigPaths`,
  `ConfigurationException`, and the `TraceReference` value type
- `dev.vertique:vertique-correlation` — the `TraceReferenceResolver` SPI and the
  `@BindsOptionalOf` declaration in `CorrelationContextModule` that this module satisfies
- `dev.vertique:vertique-bootstrap` — `VertxBuilderContributor`, `BootstrapContext`
- `dev.vertique:vertique-security-core` — `SecurityEventObserver` and the security lifecycle event
  types recorded as span events
- `io.opentelemetry:opentelemetry-api` — `OpenTelemetry`, `Tracer`, `Span`, `SpanContext`,
  `Attributes`, `GlobalOpenTelemetry`
- `io.opentelemetry:opentelemetry-sdk` — `OpenTelemetrySdk`
- `io.opentelemetry:opentelemetry-sdk-extension-autoconfigure` — `AutoConfiguredOpenTelemetrySdk`
- `io.opentelemetry:opentelemetry-exporter-otlp` — runtime scope; the default OTLP exporter
  autoconfigure resolves
- `io.vertx:vertx-opentelemetry` — `OpenTelemetryOptions`, `OpenTelemetryTracingFactory`, and the
  auto-registered `VertxContextStorageProvider`
- `io.vertx:vertx-core` — `VertxBuilder`, `VertxTracerFactory`, `JsonObject`, `Future`
- `com.google.dagger:dagger`, `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`, `org.projectlombok:lombok` (provided)
