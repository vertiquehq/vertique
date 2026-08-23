<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Micrometer MCP Module

> **Status:** Alpha
> **Package:** `dev.vertique.mcp.micrometer`
> **Artifact:** `vertique-micrometer-mcp`
> **Depends on:** io.micrometer:micrometer-core (library), vertique-micrometer-core, vertique-mcp-core

Observe-only Micrometer metrics adapter for the MCP server. When installed alongside
`McpServerModule` and `MicrometerModule`, it records a per-request timer
(`vertique.mcp.server.requests`), an in-flight-requests gauge (`vertique.mcp.server.active`), a
validation-failures counter (`vertique.mcp.validation.failures`), and a per-tool-call timer
(`vertique.mcp.tool.calls`) — all with bounded, low-cardinality tags and no payload, identity, or
trace-id label.

The module compiles against `vertique-micrometer-core` for the `MetricsConfig` type and
`vertique-mcp-core` for the neutral lifecycle SPI. It also **requires a `MeterRegistry` binding on
the Dagger graph** — normally supplied by `MicrometerModule`; without it (or another `MeterRegistry`
provider) the component does not compile. Only the `metrics.enabled` *gate* is optional: the module
declares `@BindsOptionalOf MetricsConfig` independently, so the gate defaults to enabled when
`MicrometerModule` is absent. It never modifies MCP request or response processing, and it never
submits audit records.

---

## When To Use It

Install `vertique-micrometer-mcp` whenever an application exposes the MCP server via
`McpServerModule` and metrics are enabled. Pair it with `vertique-micrometer-core` and at least one
backend module (e.g., `vertique-micrometer-registry-prometheus`) to make the emitted meters visible.
Without a backend the injected `MeterRegistry` is an empty composite whose recording is a no-op.
Omitting this module entirely is also supported: open-core MCP has no dependency on it, and MCP
protocol behavior is identical either way.

---

## Core Concepts

**Observe-only.** The module contributes exactly one `McpRequestLifecycleObserver` into the
multibinding set MCP core drives (T020's `McpCompletionCoordinator`). The observer's callback bodies
are wrapped in try/catch that logs at WARN and swallows, so a misbehaving registry or a throwing
callback never affects MCP request processing.

**Active-gauge design.** `open(...)` increments the gauge; the returned session's `onCompleted`
decrements it. Every opened session receives exactly one completion callback on every settlement
path — including rejection, failure, and cancellation — so the gauge always returns to zero,
matching the contract's "the lifecycle start callback increments the untagged active gauge; post-wire
completion decrements it and records the other instruments." A decrement below zero is clamped at 0
with a one-time WARN, mirroring `vertique-micrometer-core`'s `SecurityMetricsObserver`.

**All recording happens at completion, not at logical settlement.** The observer does not override
`onTerminal`; every instrument reflects the request's actual transport settlement
(`McpRequestCompletedEvent`), not merely its logical outcome.

**Bounded dimensions only (FR-MCP-201, FR-MCP-203).** Every tag value is drawn from a fixed,
low-cardinality domain:

- `method` — an `McpMethod` name, with `McpMethod.OTHER` remapped to the underscore-prefixed
  literal `_OTHER` so an unrecognized client-provided method never looks like a raw client string.
- `tool` — `McpRequestTerminalEvent#toolName()`, which the terminal event's own compact constructor
  already bounds to either the literal `UNKNOWN` or a generated, registered tool name matching
  `[A-Za-z0-9_.-]{1,128}`. An unresolved client-requested tool name never reaches this observer as a
  distinct value — every producer upstream of the lifecycle SPI collapses it to `UNKNOWN` first
  (see `McpRequestTerminalEvent`'s javadoc on its placeholder-descriptor decision point).
- `outcome`, `error.type`, `result.type`, `transport.outcome` — the corresponding enum names, each
  drawn from a small fixed set.

No request ID, correlation ID, trace ID, principal, argument, result, or exception text is ever read
by this class, so none can become a tag (FR-MCP-202). The observer performs no meter-filter
registration of its own; each tag value's domain is bounded structurally by the type it is read
from, independent of any registry-level cardinality guard.

**Zero-overhead when unconfigured.** Before `VertiqueApplication` bootstrap the injected
`MeterRegistry` is an empty composite whose recording is a no-op (NFR-TEL-003). When the optional
`MetricsConfig` binding is absent (i.e., `MicrometerModule` is not installed), the observer defaults
to enabled — it records against whatever registry is injected.

---

## Key Classes

### McpMicrometerModule

Dagger `@Module`. The only public type in this artifact. Contributes one binding:

- `McpServerMetricsObserver` into `Set<McpRequestLifecycleObserver>` — records the four meters
  described above.

Also declares `@BindsOptionalOf MetricsConfig metricsConfig()` so the observer can inject
`Optional<MetricsConfig>` without requiring `MicrometerModule` to be installed. When
`MicrometerModule` is also installed its `@Provides MetricsConfig` binding satisfies the optional;
when absent the optional is empty and the observer defaults to enabled.

```java
@Component(modules = {
    VertxModule.class,
    McpServerModule.class,
    MicrometerModule.class,
    McpMicrometerModule.class,
    // ...
})
interface AppComponent { /* ... */ }
```

### McpServerMetricsObserver

`@Singleton`, package-private `McpRequestLifecycleObserver`. Registers the active-requests gauge at
construction time. On `open`, increments the gauge and returns a session; the session's
`onCompleted` decrements the gauge and records the remaining instruments from the completion event.
When `MetricsConfig.enabled()` is `false` (i.e., `metrics.enabled=false` in config), `open` returns a
no-op session and no meter is ever touched.

---

## Meters

### `vertique.mcp.server.requests` — Timer

Per-request timer. One sample is recorded per `McpRequestCompletedEvent`.

| Tag | Values | Notes |
|-----|--------|-------|
| `method` | `SERVER_DISCOVER`, `TOOLS_LIST`, `TOOLS_CALL`, `_OTHER` | `McpMethod.OTHER` is remapped to `_OTHER` |
| `outcome` | `SUCCESS`, `TOOL_ERROR`, `REJECTED`, `FAILED`, `CANCELLED` | `McpOutcome` enum name |
| `error.type` | `NONE`, `HTTP`, `PROTOCOL`, `AUTHENTICATION`, `AUTHORIZATION`, `INPUT_VALIDATION`, `INPUT_PROCESSING`, `INTERCEPTOR`, `HANDLER`, `OUTPUT_VALIDATION`, `SERIALIZATION`, `TIMEOUT`, `TRANSPORT`, `INTERNAL` | `McpErrorType` enum name |
| `result.type` | `NONE`, `COMPLETE` | `McpResultType` enum name |
| `transport.outcome` | `WRITTEN`, `DISCONNECTED`, `RESET`, `WRITE_FAILED` | `McpTransportOutcome` enum name |

Prometheus rendering: `vertique_mcp_server_requests_seconds_count` /
`vertique_mcp_server_requests_seconds_sum` / `vertique_mcp_server_requests_seconds_bucket`.

### `vertique.mcp.server.active` — Gauge

Instantaneous count of in-flight MCP requests. Untagged.

Prometheus rendering: `vertique_mcp_server_active`.

### `vertique.mcp.validation.failures` — Counter

Incremented once per completed request whose terminal `error.type` is `INPUT_VALIDATION` or
`OUTPUT_VALIDATION`.

| Tag | Values | Notes |
|-----|--------|-------|
| `method` | see above | |
| `tool` | generated tool name, or `UNKNOWN` | `UNKNOWN` for a non-`TOOLS_CALL` request or an unresolved tool |
| `error.type` | `INPUT_VALIDATION` or `OUTPUT_VALIDATION` | the only two values this counter ever carries |

Prometheus rendering: `vertique_mcp_validation_failures_total`.

### `vertique.mcp.tool.calls` — Timer

Recorded only for `TOOLS_CALL` requests, once per completed request.

| Tag | Values | Notes |
|-----|--------|-------|
| `tool` | generated tool name, or `UNKNOWN` | see the `tool` dimension note above |
| `outcome` | see above | |
| `error.type` | see above | |

Prometheus rendering: `vertique_mcp_tool_calls_seconds_count` /
`vertique_mcp_tool_calls_seconds_sum` / `vertique_mcp_tool_calls_seconds_bucket`.

---

## Dependencies

- `io.micrometer:micrometer-core` — `MeterRegistry`, `Timer`, `Tags`; no Vert.x Micrometer
  integration types. This is the only third-party Micrometer artifact on the compile classpath.
- `dev.vertique:vertique-micrometer-core` — `MetricsConfig`, the `metrics.enabled` gate the observer
  injects as `Optional<MetricsConfig>`.
- `dev.vertique:vertique-mcp-core` — `McpRequestLifecycleObserver`, `McpRequestObservation`,
  `McpRequestCompletedEvent`, `McpRequestTerminalEvent`, and the bounded lifecycle enums.
- `com.google.dagger:dagger`, `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`, `org.projectlombok:lombok` (provided)
