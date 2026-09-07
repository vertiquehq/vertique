<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Logging Module

> **Status:** Stable
> **Package:** `dev.vertique.logging`
> **Artifact:** `vertique-logging`
> **Depends on:** vertique-context, vertique-core

Owns MDC (Mapped Diagnostic Context) for the framework: the per-request `MDCContext` storage, the `MDCContexts` public facade, the `MDCContextValueAdapter` ServiceLoader entry required by the substrate for deep-copy semantics, and the `LoggingContextModule` that registers MDC propagation through the service-dispatch encoder/decoder pipeline. Also provides two logback appenders that bridge Vert.x's context-local storage and logback's thread-local MDC model. Audit logging is a separate concern, provided by the Vertique audit modules rather than this one.

---

## MDC (Mapped Diagnostic Context)

Standard SLF4J MDC is thread-local, which does not work with Vert.x's event loop model where a single thread handles many requests. This module provides an `MDC` facade backed by `MDCContexts` (in `dev.vertique.logging`).

```java
public final class MDC {
    public static void put(String key, String value) { ... }    // delegates to MDCContexts.put
    public static String get(String key) { ... }                // delegates to MDCContexts.get (lenient)
    public static void remove(String key) { ... }               // delegates to MDCContexts.remove
    public static Map<String, String> getCopyOfContextMap() { ... }  // delegates to MDCContexts.copy (lenient)
    public static void setContextMap(Map<String, String> values) { ... }  // clear + putAll
    public static void clear() { ... }                          // delegates to MDCContexts.clear
    public static void syncToSlf4j() { ... }                    // copies to org.slf4j.MDC (unchanged)
}
```

**The MDC data type lives in this module; the generic storage substrate lives in `vertique-context`.** `MDCContext` (package-private) is the mutable per-request MDC map, defined and owned by this module. Its instance is bound in the substrate holder slot (`ContextLocal<Map<String,Object>>`) owned by `ContextLocalServiceProvider` in `vertique-context`, keyed by `MDCContext.class.getName()`. The slot itself is shared with all other typed context values — the substrate has no MDC-specific code, and the `ContextHolder`/`ServiceDispatchContextEncoder`/`ServiceDispatchContextDecoder` SPI contracts it implements against are declared in `vertique-core`. `MDCContexts` (in `dev.vertique.logging`) is the primary public API; `dev.vertique.logging.MDC` is a thin SLF4J-style delegation facade. `MDCContextValueAdapter` is the `ServiceLoader<ContextValueAdapter>` entry that gives the substrate deep-copy semantics for `MDCContext` on `duplicate(true)`. There is no separate `VertxServiceProvider` in this module (`LoggingServiceProvider` is gone).

**Fail-fast writes.** `MDC.put`, `MDC.remove`, `MDC.clear`, and `MDC.setContextMap` throw
`IllegalStateException` when called outside a duplicated Vert.x context. This matches the substrate's
invariant and prevents silent no-ops that produced invisible MDC data in earlier versions.

**Lenient reads.** `MDC.get` and `MDC.getCopyOfContextMap` return `null` / an empty map when called
outside a Vert.x context.

**Scheduler-thread note.** Cron (`CronJobDispatcher`) and delayed-job (`DelayedJobPoller`) dispatch
paths that run on non-duplicated contexts (the verticle deployment context, not inside a message
consumer callback) use `org.slf4j.MDC` directly for pre-dispatch MDC enrichment. The framework
facade requires a duplicated context; SLF4J MDC is the thread-local fallback that reaches the log
layout on the scheduler thread. Receive-side enrichment (inside event-bus consumer handlers, which
run on duplicated contexts) continues to use the framework facade normally.

**Propagation across event bus.** MDC entries travel through the same
`DispatchMetadata.dispatchContext()` FQCN map that carries every other dispatch context value
(`SecurityContext`, `DurablePropagationMetadata`, etc.). `LoggingContextModule` (in this module)
registers the MDC service-dispatch encoder and decoder via `@Provides @IntoSet`:

- Encoder: `MDCContexts.serviceDispatchEncoder()` — captures the ambient holder-bound `MDCContext`
  as an immutable `DiagnosticContextSnapshot` at dispatch time.
- Decoder: `MDCContexts.serviceDispatchDecoder()` — materialises a fresh `MDCContext` from the
  snapshot on the receive side.

`ServiceMethodInvoker` installs the decoded value via the standard `InboundDispatchScope.install(...)`
call, so MDC binding/restoration rides inside the single dispatch-context scope closed in every
terminal branch — there is no separate `mdcScope` and no MDC capture SPI.

**Default framework MDC fields** (emitted by `ContextualLoggingMiddleware` and
`IdentityResolutionMiddleware` via `RequestContextLifecycle`):
- `requestId` — per-request correlation identifier
- `method` — HTTP method
- `path` — request path
- `userId` — authenticated user identifier (when present)
- `clientId` — authenticated client identifier (when present)
- `authMethod` — authentication method identifier (non-anonymous requests only)

---

## Logback Appenders

The `dev.vertique.logging.logback` package contains two logback appenders. Both require `logback-classic` on the application's classpath — it is an optional compile-time dependency of this module and is not pulled in transitively.

### VertxAwareAppender

Extends `AsyncAppenderBase<ILoggingEvent>`. Enriches events with Vert.x context-local MDC on the caller (event-loop) thread, then queues them for delivery on a daemon worker thread. Actual I/O (console, file, network) happens on the worker thread, so the event loop is never blocked by logging I/O.

**Key behaviour:**

| Property | Default | Description |
|---|---|---|
| `queueSize` | 256 | Capacity of the transfer queue (inherited from `AsyncAppenderBase`) |
| `discardingThreshold` | 20% of `queueSize` | When queue fill exceeds this, low-priority events (TRACE/DEBUG/INFO) are dropped |
| `neverBlock` | `true` | When the queue is full, events are silently discarded rather than blocking the caller |
| `maxFlushTime` | 1000 ms | Grace period during `stop()` to drain remaining events |

`neverBlock` defaults to `true` (unlike logback's own `AsyncAppender`) to protect the event loop from back-pressure stalls. Set it to `false` in XML if blocking is acceptable.

**MDC enrichment mechanism:**

`preprocess()` is overridden to call `event.prepareForDeferredProcessing()` (the base class leaves this as a no-op). `append()` reads `MDC.getCopyOfContextMap()` on the caller thread. If the snapshot is non-empty, the event is wrapped in a `VertxMdcLoggingEvent` that merges the Vert.x MDC values over the SLF4J thread-local MDC (Vert.x values win on key collision). The wrapper travels to the worker thread; all other event methods delegate to the original event.

```xml
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>%d{HH:mm:ss} %-5level [%X{requestId}] %logger{36} - %msg%n</pattern>
    </encoder>
</appender>

<appender name="VERTX" class="dev.vertique.logging.logback.VertxAwareAppender">
    <!-- queueSize and discardingThreshold are inherited from AsyncAppenderBase -->
    <queueSize>512</queueSize>
    <!-- neverBlock defaults to true; set false to allow blocking when queue is full -->
    <neverBlock>true</neverBlock>
    <appender-ref ref="CONSOLE"/>
</appender>

<root level="INFO">
    <appender-ref ref="VERTX"/>
</root>
```

### MarkerAwareAppender

Extends `UnsynchronizedAppenderBase<ILoggingEvent>`. Routes each log event to a named child appender based on the event's SLF4J marker name. Events with no matching marker go to the configured default appender. Exactly one child appender receives each event; this is a router, not a broadcast.

**Marker resolution order for each event:**

1. If the event has no markers, send to the default appender.
2. For each marker in the event's marker list:
   - **Fast path:** look up a child appender whose name equals the marker name directly.
   - **Recursive hierarchy:** if the marker has references, check each attached child appender's name via `Marker.contains(String)` to walk the full containment tree.
3. If no match is found across all markers, send to the default appender.

**Startup validation** — `start()` fails with an error (appender will not activate) if:
- No `<appender-ref>` elements are configured
- `<defaultAppender>` is not set or is empty
- The named default appender is not found among the attached appender-refs
- Any attached appender-ref resolves to `this` (self-recursion guard)

```xml
<appender name="TECH" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%d %-5level %logger - %msg%n</pattern></encoder>
</appender>

<appender name="AUDIT" class="ch.qos.logback.core.FileAppender">
    <file>audit.log</file>
    <encoder><pattern>%d %msg%n</pattern></encoder>
</appender>

<appender name="ROUTER" class="dev.vertique.logging.logback.MarkerAwareAppender">
    <defaultAppender>TECH</defaultAppender>
    <appender-ref ref="TECH"/>
    <appender-ref ref="AUDIT"/>
</appender>
```

Usage: log with `logger.info(MarkerFactory.getMarker("AUDIT"), "user {} logged in", userId)` and the event is routed to the `AUDIT` appender; all other events go to `TECH`.

---

## Combined Configuration Example

The typical setup nests `MarkerAwareAppender` inside `VertxAwareAppender`: the outer appender enriches events with Vert.x MDC and defers I/O to a worker thread; the inner appender routes each event to the appropriate destination by marker.

```xml
<configuration>

    <appender name="TECH" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %X{requestId} %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="AUDIT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} AUDIT %msg%n</pattern>
        </encoder>
    </appender>

    <!-- Routes by marker; TECH is the fallback for unmarked events -->
    <appender name="STDOUT" class="dev.vertique.logging.logback.MarkerAwareAppender">
        <defaultAppender>TECH</defaultAppender>
        <appender-ref ref="TECH"/>
        <appender-ref ref="AUDIT"/>
    </appender>

    <!-- Merges Vert.x context-local MDC, then queues for worker-thread delivery -->
    <appender name="VERTX" class="dev.vertique.logging.logback.VertxAwareAppender">
        <appender-ref ref="STDOUT"/>
    </appender>

    <root level="INFO">
        <appender-ref ref="VERTX"/>
    </root>

</configuration>
```

---

## Key Classes

### MDCContext

Package-private mutable per-request MDC map. Instances are bound in the substrate `ContextHolder`
slot under the `MDCContext.class.getName()` FQCN key. Application code should never reference this
class directly — use `MDCContexts` or `MDC` instead.

### MDCContexts

Public static facade (`dev.vertique.logging.MDCContexts`) for MDC-style diagnostic values stored in
the current Vert.x request context. All write methods fail fast outside a duplicated Vert.x
context; read methods are lenient.

```java
// Writes — fail fast outside a duplicated context
void                 MDCContexts.put(String key, String value)
void                 MDCContexts.putAll(Map<String, String> values)
void                 MDCContexts.remove(String key)
void                 MDCContexts.removeAll(Collection<String> keys)
void                 MDCContexts.clear()
ContextHolder.Scope  MDCContexts.bindAll(Map<String, String> values)  // per-MDC-key snapshot/restore

// Reads — lenient outside Vert.x
String               MDCContexts.get(String key)
Map<String, String>  MDCContexts.copy()

// Service-dispatch propagation wiring (used by LoggingContextModule)
ServiceDispatchContextEncoder<?>  MDCContexts.serviceDispatchEncoder()
ServiceDispatchContextDecoder<?>  MDCContexts.serviceDispatchDecoder()

// Caller-override support for non-duplicated-context dispatch sites (e.g. cron, delayed-job)
String               MDCContexts.holderKey()
Object               MDCContexts.holderValue(Map<String, String> entries)

// Scoped snapshot/restore independent of bindAll's own writes
ContextHolder.Scope  MDCContexts.snapshotKeys(Set<String> keys)
```

`bindAll` snapshots only the MDC keys it touches and restores them in reverse order on scope close.
An empty-map call returns a no-op scope without triggering the duplicated-context guard.

`holderKey()` returns the FQCN key under which the MDC holder value lives in the unified
dispatch-context map; `holderValue(Map<String, String>)` wraps entries as the same
`DiagnosticContextSnapshot` wire form `serviceDispatchEncoder()` produces, for callers composing a
caller-override entry for `DispatchEnvelopeBuilder#build` from a non-duplicated Vert.x context
(cron/delayed-job scheduler threads). `snapshotKeys(Set<String>)` snapshots the current value (or
absence) of each given key and returns a scope that restores exactly those keys on close,
independent of any `put`/`putAll`/`remove`/`removeAll` calls made between snapshot and close — used
by correlation ingress to guarantee framework-mirrored MDC keys are restored at request end.

### MDCContextValueAdapter

`ServiceLoader<ContextValueAdapter>` entry that provides deep-copy semantics for `MDCContext` when
a Vert.x context is duplicated with `duplicate(true)`. Registered in
`META-INF/services/dev.vertique.core.context.ContextValueAdapter`. The substrate
(`ContextLocalServiceProvider` in `vertique-context`) discovers this adapter at bootstrap — no
Dagger wiring required.

### DiagnosticContextSnapshot

Immutable record `(Map<String, String> entries)` — the wire-format MDC value carried in
`DispatchMetadata.dispatchContext()` under the `MDCContext.class.getName()` key.

### MDC

Thin SLF4J-style facade (`dev.vertique.logging.MDC`) over `MDCContexts`; no additional storage. Its
signatures are listed once, under [MDC (Mapped Diagnostic Context)](#mdc-mapped-diagnostic-context)
above. Write methods fail fast outside a duplicated Vert.x context; read methods are lenient.

### LoggingContextModule

Abstract Dagger `@Module` that wires MDC propagation into the context-propagation substrate via
`@Provides @IntoSet` bindings. AppComponents that want MDC entries to propagate through the
service-dispatch carrier pipeline must include `LoggingContextModule.class` alongside
`ContextRuntimeModule.class`:

```java
// Non-REST AppComponent
@Singleton
@Component(modules = {
    VertxModule.class,
    ContextRuntimeModule.class,    // substrate
    LoggingContextModule.class,    // MDC propagation
    // ... other modules
})
interface AppComponent { ... }
```

REST AppComponents include both modules transitively via `RestCoreModule` — no explicit listing
needed.

### VertxAwareAppender and MarkerAwareAppender

Both Logback appenders live in `dev.vertique.logging.logback` and require `logback-classic` on the
classpath (optional dependency). Their behavior, configuration attributes, startup validation, and
marker resolution order are specified once, under [Logback Appenders](#logback-appenders) above.
`VertxAwareAppender` reads the MDC through `MDC.getCopyOfContextMap()` on the caller thread and
needs no per-value SPI; there is no MDC capture SPI to implement.

### Framework seams

`MDCContexts.holderKey()`, `holderValue(Map)`, and `snapshotKeys(Set)` exist for the framework's own
dispatch sites — cron, delayed jobs, correlation and REST ingress — and `MDCContextValueAdapter` is
the substrate's deep-copy hook discovered through `ServiceLoader`. Their Javadoc marks them INTERNAL:
the members and the adapter's shape are outside this module's compatibility promise, while the
adapter's `ServiceLoader` registration itself is promised. The promise covers the `MDC` and
`MDCContexts` read/write surface, `serviceDispatchEncoder()` and `serviceDispatchDecoder()`,
`DiagnosticContextSnapshot`, the two Logback appenders with their configuration attributes, the
`LoggingContextModule` wiring, and the documented MDC behavior.

---

## Dependencies

| Dependency | Scope | Notes |
|---|---|---|
| `dev.vertique:vertique-context` | compile | `ContextValues`, `DefaultContextHolder`, `ContextLocalServiceProvider`, `ServiceDispatchCodecs`, `InboundDispatchScope` — the generic per-context holder slot mechanism this module's `MDCContext` is bound into, and the encoder/decoder wiring the facade uses |
| `dev.vertique:vertique-core` | compile | `ContextHolder`, `ContextHolder.Scope`, `ServiceDispatchContextEncoder/Decoder` SPI contracts |
| `io.vertx:vertx-core` | compile | — |
| `com.google.dagger:dagger` | compile | `LoggingContextModule` |
| `jakarta.inject:jakarta.inject-api` | compile | — |
| `org.slf4j:slf4j-api` | compile | — |
| `org.projectlombok:lombok` | provided | — |
| `ch.qos.logback:logback-classic` | optional | Required at runtime only when using `VertxAwareAppender` or `MarkerAwareAppender` |

---

## Logging Principles

- **Logging is async via worker thread** when using `VertxAwareAppender` — the event-loop thread is never blocked by I/O
- **MDC enrichment happens on the caller thread** before the event is queued, so Vert.x context-local values are safely captured before the event crosses thread boundaries
- **The MDC data type and facade live in this module** (`MDCContexts` / `MDCContext` in `dev.vertique.logging`); `dev.vertique.logging.MDC` is a thin SLF4J-style delegation facade; the generic per-context holder slot `MDCContext` is bound into is provided by `vertique-context`, against `ContextHolder` SPI contracts declared in `vertique-core`
- **Write-path fails fast** outside a duplicated Vert.x context — prevents silent no-ops
- **Scheduler threads use `org.slf4j.MDC` directly** for pre-dispatch enrichment (cron, delayed-job) because the scheduler verticle runs on a non-duplicated context; receive-side enrichment inside consumer handlers uses the framework facade
- **Logback appenders are opt-in** — `logback-classic` is an optional dependency and must be added explicitly by applications that use `VertxAwareAppender` or `MarkerAwareAppender`
