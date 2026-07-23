<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# OpenTelemetry Core Module

> **Status:** Alpha
> **Package:** `dev.vertique.opentelemetry`
> **Artifact:** `vertique-opentelemetry-core`
> **Depends on:** core, correlation, bootstrap

Provides opt-in distributed tracing for Vertique applications using the OpenTelemetry SDK. The
module bootstraps an `OpenTelemetrySdk` before `Vertx` is created (or reuses an existing global
installed by a javaagent), wires the Vert.x tracing subsystem, bridges trace ids into the
framework's `CorrelationContext` for trace–log correlation, emits security lifecycle events as span
events, and exposes the `Tracer` and `TraceReferenceResolver` through Dagger bindings.

The module does not own metrics collection (meters belong to `vertique-micrometer-core`) and does
not own log-output bridging (that is `vertique-logging`'s responsibility). It owns the OTel SDK
lifecycle, the Vert.x tracer installation, and the correlation seam.

This module has **no dependency on any Prometheus or metrics-backend library**. OTel-backed
Prometheus exemplars are provided by the separate `vertique-opentelemetry-prometheus` bridge module
(see `dev.vertique:vertique-opentelemetry-prometheus`).

---

## When To Use It

Add `vertique-opentelemetry-core` to any application that should emit distributed traces. Install
`OpenTelemetryModule` in the Dagger `@Component`. The Vert.x tracer and correlation bridge
activate automatically when both `OpenTelemetryBootstrapContributor` (discovered via ServiceLoader)
and `OpenTelemetryModule` are present.

Pair with `vertique-micrometer-registry-prometheus` and `vertique-opentelemetry-prometheus`, and set
`metrics.prometheus.exemplars.enabled=true`, when trace-id exemplars on Prometheus metrics are
desired (opt-in; exposes trace/span ids on the management port's scrape endpoint).

Do not add this module if the application has no need for distributed tracing — when
`tracing.enabled=false` the module is completely inert (a no-op `VertxTracerFactory.NOOP` is
installed and Dagger receives `OpenTelemetry.noop()`).

---

## Core Concepts

**Bootstrap before Vert.x.** The OTel SDK must be available before the `Vertx` instance is
created so the Vert.x tracing subsystem can be wired in. `OpenTelemetryBootstrapContributor` is a
`VertxBuilderContributor` discovered via ServiceLoader; it runs in phase `SYSTEM_FIRST` at priority
`110` (after the Micrometer metrics contributor at priority `100`).

**Reuse-or-provision.** On the enabled path the contributor checks `GlobalOpenTelemetry.isSet()`.
If an existing global is present (e.g. from a javaagent), it is reused and `tracing.otel.*`
configuration is silently ignored. If no global exists the contributor provisions one via
`AutoConfiguredOpenTelemetrySdk` and owns its shutdown. The framework never closes a reused global.

**No framework holder.** `GlobalOpenTelemetry` is the canonical OTel instance. No
`OpenTelemetryHolder` exists. The bootstrap contributor registers the SDK as the global via
`setResultAsGlobal()` on the autoconfigure build, and Dagger binds `GlobalOpenTelemetry.getOrNoop()`
at component construction time (which is after the contributor ran, so the global is set).

**Trace–log correlation.** The correlation bridge connects the OTel active span to the framework's
`CorrelationContext`. `OpenTelemetryTraceReferenceResolver` reads `Span.current()` and returns a
`TraceReference` for any valid span context; `CorrelationIngressMiddleware` calls
`CorrelationContextMutator.setTrace(...)` to mirror `traceId` and `spanId` into MDC. Ids are
mirrored regardless of sampling — see [SP-10 caveat](#tracelog-correlation) below.

**Security span events.** `SecuritySpanEventObserver` contributes into the `SecurityEventsModule`
`Set<SecurityEventObserver>` multibinding (`vertique-security-runtime`) via `OpenTelemetryModule`. When both modules are installed in the same Dagger
component, security lifecycle events (credential acceptance/rejection, authorization decisions,
channel lifecycle) are recorded as span events on the current active span.

---

## Key Classes

### OpenTelemetryBootstrapContributor

`VertxBuilderContributor` discovered via ServiceLoader. Phase `SYSTEM_FIRST`, priority `110`.

**Disabled path** (`tracing.enabled=false`): installs `VertxTracerFactory.NOOP` via
`builder.withTracer(...)` to explicitly defeat the Vert.x OTel integration's ServiceLoader
auto-discovery. No SDK is built and no global is registered.

**Enabled path — reuse existing global**: when `GlobalOpenTelemetry.isSet()` is `true`, the
contributor calls `GlobalOpenTelemetry.get()` and wires that instance into the Vert.x tracer. The
`tracing.otel.*` configuration subtree is silently ignored. The contributor does not close the
reused global on shutdown.

**Enabled path — provision new SDK**: when `GlobalOpenTelemetry.isSet()` is `false`:

1. Calls `AutoConfiguredOpenTelemetrySdk.builder()` with `OtelConfigProperties.properties(config)`
   as the lowest-precedence property supplier, `disableShutdownHook()`, and `setResultAsGlobal()`.
2. Sets `OpenTelemetryOptions` on `ctx.vertxOptions()` and wires
   `new OpenTelemetryTracingFactory(sdk)` into the builder.
3. Stores the `OpenTelemetrySdk` in `ownedSdk` for shutdown.

`onShutdown()` calls `ownedSdk.close()` (blocking up to 10 s, idempotent) if `ownedSdk` is
non-null. A reused global is never closed.

Any exception from `AutoConfiguredOpenTelemetrySdk` is wrapped in `TracingBootstrapException` with
no cause attached (secret safety — see below).

#### Invariants and Gotchas

- The contributor runs before the Dagger component is constructed. The global is set by the time
  `OpenTelemetryModule` provides `GlobalOpenTelemetry.getOrNoop()`.
- `onShutdown()` is called only for contributors whose `contribute()` returned successfully. On the
  provision path, `ownedSdk` is set only after `build()` succeeds; a failed build never sets the
  global (autoconfigure's contract) and `ownedSdk` remains null, so `onShutdown()` is a safe no-op.
- On the reuse path, `tracing.otel.*` config is inert. Operators configuring a javaagent-managed
  SDK must use the agent's own mechanism.

### OpenTelemetryModule

Dagger `@Module`. Install in the application `@Component`:

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

| Type | Qualifier | Source |
|------|-----------|--------|
| `TracingConfig` | — | Deserialized from `tracing` config section via `JsonConfigPaths.navigateObject` |
| `OpenTelemetry` | — | `OpenTelemetry.noop()` when disabled; `GlobalOpenTelemetry.getOrNoop()` when enabled |
| `Tracer` | — | `openTelemetry.getTracer("dev.vertique")` |
| `TraceReferenceResolver` | — | `OpenTelemetryTraceReferenceResolver` (satisfies `@BindsOptionalOf` in `CorrelationContextModule`) |
| `SecurityEventObserver` | `@IntoSet` | `SecuritySpanEventObserver` (contributed to `SecurityEventsModule` multibinding in `vertique-security-runtime`) |

The `TraceReferenceResolver` binding satisfies the `@BindsOptionalOf` declaration in
`CorrelationContextModule`. When `OpenTelemetryModule` is absent from the graph, the optional is
empty and trace ids do not flow into `CorrelationContext`.

### OpenTelemetryTraceReferenceResolver

Package-private `@Singleton` implementation of `TraceReferenceResolver` (SPI in
`vertique-correlation`). Called by `CorrelationIngressMiddleware` on the Vert.x event loop during
request ingress.

`currentTrace()` reads `Span.current()`, extracts its `SpanContext`, and returns a
`TraceReference(traceId, spanId, "opentelemetry")` when `SpanContext.isValid()` is `true`.
Sampling is not checked — see [correlation bridge behavior](#tracelog-correlation) below.
Any exception from the OTel API is caught, logged at WARN, and `Optional.empty()` is returned so
the ingress middleware degrades silently.

### OtelConfigProperties

Package-private static utility. Builds the `otel.*` property map supplied to
`AutoConfiguredOpenTelemetrySdkBuilder.addPropertiesSupplier()` as the lowest-precedence layer;
environment variables and system properties always override it.

**Property construction order:**

1. Seeds `otel.metrics.exporter=none` and `otel.logs.exporter=none` — metrics are
   Micrometer-owned; logs are not managed by this module.
2. Seeds `otel.service.name` from the first non-blank of: `metrics.tags.service` from root config,
   or the literal `"unknown-service"`. The `OTEL_SERVICE_NAME` environment variable overrides this
   via autoconfigure's own precedence.
3. Flattens the `tracing.otel` subtree recursively: nested `JsonObject` values are joined with
   `.`; leaf values are stringified; all keys are prefixed with `otel.`. Flattened entries
   overwrite seeds on collision, so explicit config beats seeded defaults.

#### Invariants and Gotchas

- This utility is only consulted on the provision path. On the reuse path the entire `tracing.otel`
  subtree is ignored.

### TracingConfig

Jackson-deserialized config VO (`@Builder @Jacksonized`). Deserialized from the `tracing` section:

```json
{
  "tracing": {
    "enabled": true,
    "security": { "spanEvents": true },
    "otel": { ... }
  }
}
```

The `tracing.otel` subtree is read raw by `OtelConfigProperties` and is not modeled here. Unknown
properties are silently ignored.

Nested `SecurityConfig` controls `spanEvents` (default `true`).

### SecuritySpanEventObserver

Package-private `@Singleton` `SecurityEventObserver` contributed via `OpenTelemetryModule`.
Records security lifecycle events as span events on the current active span (`Span.current()`),
gated on `Span.isRecording()`. Events are added to the current span — never as new child spans —
to keep the operation cheap and avoid polluting the trace tree.

Gating is two-layered: the class-level `enabled` boolean (cached at construction from
`config.enabled() && config.security().spanEvents()`) and per-method `try/catch` blocks. A
`Future.succeededFuture()` is always returned so the security pipeline is never affected by a
tracing failure.

**Span events and attributes:**

| Method | Span event name | Attributes |
|--------|----------------|------------|
| `onCredentialAccepted` | `vertique.security.credential.accepted` | `vertique.auth.method` = normalized kind (or `"unknown"`) |
| `onCredentialRejected` | `vertique.security.credential.rejected` | `vertique.auth.method`, `vertique.auth.reason` (normalized) |
| `onAuthorizationDecided` | `vertique.security.authz.decision` | `vertique.authz.decision` (`"permit"` or `"deny"`), `vertique.authz.reason` (normalized) |
| `onChannelLifecycle` (opened) | `vertique.security.channel.opened` | — (no attributes; channel id excluded) |
| `onChannelLifecycle` (refreshed) | `vertique.security.channel.refreshed` | — |
| `onChannelLifecycle` (closed) | `vertique.security.channel.closed` | — |

`vertique.auth.method` is always `AuthMethod.normalizedKind().name()` — never `AuthMethod.id()`,
which is arbitrary custom input and would create unbounded cardinality.

Reason codes are normalized by `normalizeReason(String)`: codes matching `^[A-Z0-9_]{1,64}$` are
returned as-is; null, blank, or non-matching codes are replaced with `"OTHER"`.

Channel identifiers are excluded from span attributes to limit cardinality and avoid leaking
session-tracking data.

### TracingBootstrapException

Unchecked exception thrown by `OpenTelemetryBootstrapContributor` when SDK provisioning fails.
No cause constructor is provided. The message contains only the failing class's simple name.

This is intentional for secret safety: SDK exceptions from `AutoConfiguredOpenTelemetrySdk` may
embed configuration values (hostnames, credentials, endpoint URLs) in their message or cause chain.
Severing the cause chain at this boundary prevents accidental credential logging — the launcher
logs the full exception chain, so any cause attached here would be emitted.

---

## Bootstrap Flow

```
VertiqueApplication.launch()
  └─ OpenTelemetryBootstrapContributor.contribute(builder, ctx)
       │
       ├─ tracing.enabled=false?
       │    └─ builder.withTracer(VertxTracerFactory.NOOP)   // defeat ServiceLoader
       │
       ├─ GlobalOpenTelemetry.isSet()?
       │    └─ openTelemetry = GlobalOpenTelemetry.get()     // reuse; tracing.otel.* inert
       │
       └─ else (provision)
            ├─ AutoConfiguredOpenTelemetrySdk.builder()
            │    .addPropertiesSupplier(() → OtelConfigProperties.properties(config))
            │    .disableShutdownHook()
            │    .setResultAsGlobal()
            │    .build().getOpenTelemetrySdk()
            ├─ ownedSdk = sdk                                // contributor owns shutdown
            └─ openTelemetry = sdk
       │
       └─ (enabled paths)
            ├─ ctx.vertxOptions().setTracingOptions(new OpenTelemetryOptions())
            └─ builder.withTracer(new OpenTelemetryTracingFactory(openTelemetry))

Dagger component construction (after contributor)
  └─ OpenTelemetryModule.openTelemetry(config)
       ├─ enabled=false → OpenTelemetry.noop()
       └─ enabled=true  → GlobalOpenTelemetry.getOrNoop()

VertiqueApplication.stop()
  └─ OpenTelemetryBootstrapContributor.onShutdown()
       └─ if ownedSdk != null → ownedSdk.close()            // reused global: never closed
```

---

## Trace–Log Correlation

`CorrelationIngressMiddleware` (in `vertique-rest-core`) consults the optional
`TraceReferenceResolver` binding after `snapshotKeys(MIRRORED)`. When
`OpenTelemetryTraceReferenceResolver.currentTrace()` returns a `TraceReference`, the middleware
calls `CorrelationContextMutator.setTrace(...)`, which writes `traceId` and `spanId` into both the
live `CorrelationContext` and MDC for the duration of the request.

**Validity-only condition (SP-10):** Ids are mirrored for any `SpanContext` where
`SpanContext.isValid()` is `true`, regardless of whether the trace is sampled for export.
Unsampled traces have structurally valid trace and span ids that can appear in log MDC. The ids
may not resolve to a trace record in Jaeger/Zipkin/etc. when sampling dropped the trace; this is
expected and documented behavior. Applications that need to suppress unsampled ids may provide an
alternative `TraceReferenceResolver` binding.

Note: `MDCContexts` in Vert.x is context-local storage (`ContextLocal`), not thread-local SLF4J
MDC. Bridging context-local MDC to log-output MDC is `vertique-logging`'s responsibility; this
module only ensures `traceId` and `spanId` are present in the correlation context.

---

## Configuration

All keys live under the `tracing` section. The `tracing.otel.*` subtree is flattened to `otel.*`
and passed to autoconfigure at the lowest precedence — environment variables and system properties
always win. The `tracing.otel.*` subtree is **inert on the reuse path** (when a global already
exists at bootstrap time).

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `tracing.enabled` | boolean | `true` | Master switch. `false` → contributor installs NOOP tracer; Dagger receives `OpenTelemetry.noop()`. |
| `tracing.security.spanEvents` | boolean | `true` | Emit security lifecycle span events via `SecuritySpanEventObserver`. |
| `tracing.otel.*` | object | — | Flattened to `otel.*` and supplied to autoconfigure at lowest precedence. See below. |

**Service name resolution.** `otel.service.name` is seeded in this order:
1. `tracing.otel.service.name` (explicit, from the `tracing.otel` subtree via the flatten step)
2. `metrics.tags.service` from root config
3. `"unknown-service"`

The `OTEL_SERVICE_NAME` environment variable overrides all of these via autoconfigure's own
precedence.

**`tracing.otel.*` examples** (inert on the reuse path):

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
      "traces": {
        "sampler": "parentbased_traceidratio",
        "sampler": {
          "arg": "0.1"
        }
      }
    }
  }
}
```

The `tracing.otel` subtree key `exporter.otlp.endpoint` becomes `otel.exporter.otlp.endpoint`
after flattening. Environment variable `OTEL_EXPORTER_OTLP_ENDPOINT` always takes precedence.

---

## Prometheus Exemplar Bridge

OTel-backed Prometheus exemplars are provided by the separate `vertique-opentelemetry-prometheus`
module, not by this module. Install `OpenTelemetryPrometheusExemplarModule` alongside this module
and `vertique-micrometer-registry-prometheus`, and set `metrics.prometheus.exemplars.enabled=true`,
to attach OTel trace ids to Prometheus histogram and summary samples.

See `dev.vertique:vertique-opentelemetry-prometheus` for details and
ADR-0102 for why the bridge lives in a
separate module rather than here.

---

## Extension Points

### TraceReferenceResolver

SPI in `vertique-correlation`. Resolved via `@BindsOptionalOf Optional<TraceReferenceResolver>` in
`CorrelationContextModule`. Exactly one implementation may be on the Dagger graph.

`OpenTelemetryModule` **unconditionally** provides the built-in OTel implementation. Because exactly
one implementation may be on the graph, an application cannot add a second provider alongside
`OpenTelemetryModule` — that is a duplicate Dagger binding. To use a custom resolver (e.g. suppress
unsampled ids), **omit `OpenTelemetryModule`** and wire the SDK plus the other OTel bindings
yourself, then provide your own:

```java
@Provides
@Singleton
static TraceReferenceResolver traceReferenceResolver() {
    return () -> {
        Span span = Span.current();
        SpanContext sc = span.getSpanContext();
        // Only mirror sampled traces
        if (!sc.isValid() || !sc.isSampled()) {
            return Optional.empty();
        }
        return Optional.of(new TraceReference(sc.getTraceId(), sc.getSpanId(), "custom"));
    };
}
```

#### Invariants and Gotchas

- The resolver is invoked on the Vert.x event loop; it must be cheap and non-blocking.
- The resolver should never throw. The caller guards with `try/catch` (WARN, continue), but a
  throwing resolver still emits a WARN log entry per request.
- Exactly one binding. The `@BindsOptionalOf` model means the binding is absent, not null, when
  `OpenTelemetryModule` is not installed — no NPE risk.

---

## Dependencies

- `dev.vertique:vertique-core` — `ExtensionPhase`, `@VertxConfig`, `JsonConfigPaths`
- `dev.vertique:vertique-correlation` — `TraceReferenceResolver`, `TraceReference`
  (SPI; declared `@BindsOptionalOf` by `CorrelationContextModule`)
- `dev.vertique:vertique-bootstrap` — `VertxBuilderContributor`, `BootstrapContext`
- `io.opentelemetry:opentelemetry-api` — `OpenTelemetry`, `Tracer`, `Span`, `SpanContext`,
  `GlobalOpenTelemetry`
- `io.opentelemetry:opentelemetry-sdk` — `OpenTelemetrySdk`
- `io.opentelemetry:opentelemetry-sdk-extension-autoconfigure` — `AutoConfiguredOpenTelemetrySdk`
- `io.vertx:vertx-opentelemetry` — `OpenTelemetryOptions`, `OpenTelemetryTracingFactory`,
  `VertxContextStorageProvider` (auto-registers)
- `com.google.dagger:dagger`, `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`, `org.projectlombok:lombok` (provided)

---

## Related ADRs

- ADR-0098: Micrometer Facade and Pluggable Registry Backends — establishes the telemetry-module bootstrap pattern; the OTel contributor follows the same `VertxBuilderContributor` + secret-safe exception-wrapping model.
- ADR-0101: Trace-Log Correlation via TraceReferenceResolver — establishes why `TraceReferenceResolver` is in `vertique-correlation`, why ids are mirrored for unsampled traces, and why there is no `OpenTelemetryHolder`.
- ADR-0102: OpenTelemetry→Prometheus Exemplar Bridge as a Dedicated Module — records why the Prometheus exemplar `SpanContext` is not provided by this module, and establishes `vertique-opentelemetry-prometheus` as the correct host.
