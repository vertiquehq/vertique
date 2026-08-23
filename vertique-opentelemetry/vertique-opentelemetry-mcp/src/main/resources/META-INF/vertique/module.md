<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# OpenTelemetry MCP Module

> **Status:** Alpha
> **Package:** `dev.vertique.mcp.opentelemetry`
> **Artifact:** `vertique-opentelemetry-mcp`
> **Depends on:** io.opentelemetry:opentelemetry-api (library), vertique-mcp-core

Observe-only OpenTelemetry span-enrichment adapter for the MCP server. When installed alongside
`McpServerModule`, it captures the current Vert.x HTTP server span in `open(...)`, retains it across
asynchronous completion, and enriches that exact retained span with bounded MCP attributes. It also
carries a not-yet-fed body-trace-link path — see "At most one body-trace link" below (P05 review
remediation) — and never creates or renames a span.

The module compiles against the OpenTelemetry **API only** — no SDK dependency. Without an
OpenTelemetry SDK installed (or with a no-op `OpenTelemetry` instance and no exporter configured),
every span operation resolves to the API's built-in no-op implementation: enrichment is skipped
entirely and MCP protocol behavior is unaffected either way.

---

## When To Use It

Install `vertique-opentelemetry-mcp` whenever an application exposes the MCP server via
`McpServerModule` and wants its HTTP server spans (produced by Vert.x's own OpenTelemetry tracing
integration) enriched with MCP-specific facts. Omitting this module entirely is also supported:
open-core MCP has no dependency on it, and MCP protocol behavior is identical either way.

---

## Core Concepts

**Capture-at-open, never `Span.current()` at callback time.** `open(...)` runs while the Vert.x HTTP
server span is current — before dispatch, exactly as REST's `ServerSpanEnrichmentContributor`/
`ServerSpanOutcomeInterceptor` capture the span early for the same reason. The observer resolves
`Span.current()` exactly once, inside `open`, and retains that exact `Span` reference (and its
`SpanContext`) on the returned session. The terminal callback later enriches that retained reference
directly — it never re-resolves `Span.current()`. This matters because a terminal callback can be
delivered on a different thread or after the request-owning context has moved on, where
`Span.current()` would return a different span (or the no-op span) rather than the one this request
actually ran under.

**No span is ever created or renamed.** This observer calls no tracer and builds no span. It only
mutates the one span already current when `open` runs — and only when that span's context is valid
(an installed OpenTelemetry SDK with active tracing). Transport status remains owned by Vert.x HTTP
tracing; this observer never calls `Span#setStatus`.

**Enrichment happens at logical settlement (`onTerminal`), not at transport completion.** The
observer implements `onTerminal`, not `onCompleted`: attributes are set once the request settles
logically, before the response is written, mirroring the contract's "sets bounded logical outcome
before the terminal write."

**Bounded attributes only.**

- `rpc.system.name` — always the literal `jsonrpc`.
- `mcp.method.name` — an `McpMethod` name, with `McpMethod.OTHER` remapped to the underscore-prefixed
  literal `_OTHER`, mirroring the sibling Micrometer adapter's `method` tag convention.
- `mcp.protocol.version` — the terminal event's negotiated protocol version (R05, issue #431), set only
  when non-null: a request whose protocol negotiation never completed (rejected at or before
  negotiation) sets no value here at all, rather than a hardcoded or default literal.
- `vertique.mcp.outcome` — the terminal event's `McpOutcome` enum name.
- `vertique.mcp.result.type` — the terminal event's `McpResultType` enum name.
- `gen_ai.tool.name` — set only when the terminal event carries a resolved tool identity (never the
  `UNKNOWN` placeholder).

`rpc.system.name`, `mcp.method.name`, `mcp.protocol.version`, and `gen_ai.tool.name` are experimental
OpenTelemetry semantic-convention names; rather than depending on an incubating semconv artifact, they
are declared as internal, Vertique-owned `AttributeKey` constants on `McpServerSpanObserver`.

**At most one body-trace link, never a child span — path exists but is not yet fed (P05 review
remediation).** When the request's terminal observation carries a non-null `bodyTraceContext` (a
normalized W3C trace reference captured from the request body), the observer converts it into an
OpenTelemetry `SpanContext` and adds exactly one `Span#addLink` when that context is valid and
distinct (different trace id or span id) from the HTTP span's own captured context. A body trace
context identical to the HTTP span's own context is a self-reference and adds no link. Malformed
trace-state data (not well-formed W3C `key=value` entries) is caught and logged at WARN with a
bounded diagnostic — the exception class name only, never the raw trace data — and adds no link;
every other enrichment attribute is still recorded. **`McpCompletionCoordinator` always constructs the
terminal observation with a `null` body trace context today, so this whole path never runs in
practice** — connecting a real source is deferred, because it means accepting a client-supplied trace
reference, and doing so without deliberately deciding how to bound the trust placed in it would open
a trace-correlation-spoofing surface.

**Zero-overhead when unconfigured.** Every operation is guarded by `Span#getSpanContext().isValid()`
(at `open`), `Span#isRecording()` (at enrichment — a late terminal callback can observe a span that
already ended between capture and enrichment; P05 review remediation), and try/catch, so a missing
OpenTelemetry SDK, a no-op `OpenTelemetry` instance, or a throwing OpenTelemetry implementation never
affects MCP request processing.

---

## Key Classes

### McpOpenTelemetryModule

Dagger `@Module`. The only public type in this artifact. Contributes one binding:

- `McpServerSpanObserver` into `Set<McpRequestLifecycleObserver>` — captures and enriches the HTTP
  server span as described above.

```java
@Component(modules = {
    VertxModule.class,
    McpServerModule.class,
    McpOpenTelemetryModule.class,
    // ...
})
interface AppComponent { /* ... */ }
```

### McpServerSpanObserver

`@Singleton`, package-private `McpRequestLifecycleObserver`. `open` captures `Span.current()` and
its `SpanContext` and returns a session retaining both, or a no-op session when no valid span is
current. The session's `onTerminal` enriches the retained span with the bounded attributes above and
adds the optional body-trace link. The session does not override `onCompleted` — no MCP-specific work
happens at transport completion — but it does implement the neutral `McpCompletionScope` capability
(R06, issue #435): `openCompletionScope()` re-makes the retained span current for the framework's
completion dispatch loop, so a co-installed Micrometer observer's timer recording happens with a valid
span current and a registry-level exemplar bridge can attach its trace id. See
"Micrometer exemplar completion scope" below.

**Micrometer exemplar completion scope (R06, issue #435).** The frozen observability contract requires
that "when a sampled HTTP span is current at terminal settlement, the adapter always invokes the
Micrometer exemplar path." Vert.x's OpenTelemetry tracer ends the HTTP server span before any
completion callback runs, so without help `Span.current()` is a no-op span by the time a Micrometer
observer records its timer. `McpServerSpanObserver`'s session implements `McpCompletionScope`
(`vertique-mcp-core`, `dev.vertique.mcp.lifecycle`) — the same opt-in-capability pattern
`McpToolValueObservation` established — so `McpCompletionCoordinator` (`vertique-mcp-server`) opens it
before dispatching `onCompleted` to any observer or listener, and closes it, in reverse order among
every opened scope, only after all of them return. Both the open and the close are per-session
failure-isolated. No OpenTelemetry type crosses into `vertique-mcp-core` or `vertique-micrometer-mcp`
to make this work — the exemplar bridge itself (`OpenTelemetrySpanContext`) is registry-level,
un-MCP-specific plumbing already shared with REST.

---

## Dependencies

- `io.opentelemetry:opentelemetry-api` — `Span`, `SpanContext`, `TraceFlags`, `TraceState`,
  `AttributeKey`; no OpenTelemetry SDK dependency (NFR-TEL-002).
- `dev.vertique:vertique-mcp-core` — `McpRequestLifecycleObserver`, `McpRequestObservation`,
  `McpRequestTerminalObservation`, `McpRequestTerminalEvent`, `McpTraceContext`, and the bounded
  lifecycle enums.
- `com.google.dagger:dagger`, `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`, `org.projectlombok:lombok` (provided)
