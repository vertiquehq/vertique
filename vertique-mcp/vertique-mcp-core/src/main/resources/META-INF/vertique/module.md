<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# MCP Core

> **Status:** Alpha
> **Package:** `dev.vertique.mcp.annotation`, `dev.vertique.mcp.tool`, `dev.vertique.mcp.lifecycle`,
> `dev.vertique.mcp.interceptor`
> **Artifact:** `vertique-mcp-core`
> **Depends on:** `vertique-core`, `vertique-security-core`, `jakarta.annotation-api`,
> `jakarta.validation-api`, `hibernate-validator`, `org.glassfish.expressly`

`vertique-mcp-core` owns the stable public API for authoring Model Context Protocol tools, plus
Model Context Protocol lifecycle facts and neutral per-request observation. It contains the tool
authoring annotations, the immutable descriptor and invocation contracts, immutable terminal and
completion events, their outcome classifications, and extension interfaces. It has no HTTP router,
protocol parser, handler invocation, or runtime composition.

## Tool authoring

Annotate a `public` method of a dependency-injected `public` type with `@McpTool` to publish it as
a tool, and describe each declared parameter with `@McpToolParam`. Both annotations have `CLASS`
retention and are consumed at compile time by `vertique-codegen-mcp`; nothing scans the classpath
at runtime.

Tool names are explicit, unique, case-sensitive, 1–128 characters, and match `[A-Za-z0-9_.-]+`.
Descriptions are non-blank and at most 4,096 characters; a blank title is omitted from the wire. A
blank `@McpToolParam.name` resolves to the source parameter name. Requiredness is type-derived —
an `Optional<T>` parameter may be absent, every other parameter is required.

`McpCancellationSignal` is the only framework-supplied parameter a tool method may declare. It is
excluded from the input schema and lets a handler stop cooperative work when the request settles as
anything other than a successful write: a client disconnect, a response stream reset, a failed
write, or the shared HTTP layer closing an idle or slow connection (MCP arms no whole-request
timeout of its own). Cancellation is cooperative: the framework cannot stop a handler that ignores
the signal.

`McpToolResult` is the immutable, complete-only result type a handler may return when it needs
explicit text content or a tool execution error; use its `text`, `structured`, and `error`
factories. It carries no partial or intermediate state — a call either produces a settled
`McpToolResult`, or it never produces one at all.

A tool method's declared return type must be one of exactly four supported shapes: a plain `T`, a
`Future<T>`, a handler-authored `McpToolResult<T>`, or a `Future<McpToolResult<T>>`; every other
declared return type (`void`, `Future<Void>`, a raw or wildcard type, an SDK or Reactor type,
`RoutingContext`) is a compile error. `vertique-codegen-mcp` adapts a plain `T` or a resolved
`Future<T>` onto `McpToolResult` for you — a `String` becomes one text content item, any other
value becomes structured content — while a handler-authored `McpToolResult<T>` (sync or via
`Future`) is passed through untouched, including an explicit `isError=true`. A failed `Future`
never reaches a `McpToolResult`; the call settles as a bounded protocol-level error instead.

## Selecting a JSON profile

Tool arguments and structured results are mapped by an effective `JsonMapperProfile`. Select one
with `vertique-core`'s `@JsonProfile` on the tool method or on its declaring type; the method-level
annotation wins. `vertique-codegen-mcp` resolves that choice at compile time and rejects a blank
value there, so an unusable selection never reaches startup.

The full precedence is method `@JsonProfile`, then declaring type `@JsonProfile`, then the MCP
boundary default `mcp.jsonProfile`, then the global default `json.jsonProfile`, then the reserved
`vertx` profile. Everything after the annotations is resolved once during composition by
`vertique-mcp-server`, which also rejects an unknown id before the router is mounted.

Profiles apply only to tool arguments and structured results. Protocol envelopes, the JSON-RPC
codec, and resource limits are profile-independent.

## Descriptor and invocation contracts

`McpToolDescriptor` is the immutable published description of one tool: name, optional title,
description, `McpToolAnnotations` hints, canonical input schema, optional canonical output schema,
and the `McpToolAccess` requirement resolved at compile time. `McpToolAccess` carries one
`McpAccessMode` base policy — `PERMIT_ALL`, `DENY_ALL`, or `RESTRICTED` — with roles and an
optional `ActionRef` that compose with AND under `RESTRICTED`. `McpToolDescriptor.isValidName(String)`
exposes the published `[A-Za-z0-9_.-]{1,128}` name grammar as a reusable predicate — the same rule
the constructor itself enforces — so another type in this module (`McpRequestTerminalEvent`'s compact
constructor bounds any resolved-tool-identity telemetry against it) can validate a candidate name
without duplicating the pattern.

`McpToolInvoker` and `McpPreparedToolCall` are generated-runtime contracts: application code
neither implements nor calls them. `prepare` is the fixed input boundary — the server validates
arguments against the input schema, generated code then applies input policies, materializes typed
parameters through the effective JSON profile, and performs Bean Validation. No prepared call
exists for a failed stage.

`McpBeanValidation`, `McpInputRejectionException`, and `McpValueTrees` are further generated-runtime
support types, public for the same reason `McpToolInvoker` is: `prepare` is generated into an
arbitrary application package, which cannot reach a package-private framework type in a different
module. `McpBeanValidation.validate(carrier)` is the one shared, thread-safe Jakarta Bean Validation
`Validator` every generated `prepare()` calls for its Bean Validation stage.
`McpInputRejectionException` signals that a `tools/call` argument tree failed input-policy
application, materialization, or Bean Validation; its message is always a fixed, non-interpolated
literal, since it is returned to the caller verbatim. `McpValueTrees.deepUnmodifiableMap(map)` builds
the deeply immutable, null-preserving `normalizedArguments` map every generated `PreparedCall`
returns — unlike `Map.copyOf`, an explicit `null` value never throws, and every nested `Map`/`List`
is unmodifiable too, not just the root. Application code neither calls nor throws any of these three
types itself.

## Lifecycle facts

`McpRequestTerminalEvent` records the single logical settlement of a request. Construct it through
its named factories (`success`, `toolError`, `rejected`, `failed`, or `cancelled`) so that outcome,
result type, and error classification remain internally consistent. Its `resultType` is
complete-only: `success` and `toolError` always settle as `McpResultType.COMPLETE`, and `rejected`,
`failed`, and `cancelled` always settle as `McpResultType.NONE` — the enum itself declares no third,
partial state to construct. `McpRequestCompletedEvent` records the later transport outcome after the
response was written, disconnected, reset, or failed.

Both records are immutable, validate their temporal and protocol-state invariants, and retain only
bounded protocol facts. They never carry request bodies, headers, credentials, exception text, or
arbitrary client-provided method or tool names.

## Observation extensions

Contribute `McpRequestLifecycleObserver` through Dagger set multibinding. The server opens one
`McpRequestObservation` per contributed observer for each request, invokes terminal observation at
logical settlement, and invokes completion observation after the transport settles. Observers are
observe-only, synchronous, non-blocking, and failure-isolated by the server runtime.

Applications that only need a post-transport callback can contribute an
`McpRequestCompletedListener`. Listener order is unspecified and listener failures cannot alter a
request outcome.

`McpTraceContext` is the optional normalized W3C trace reference associated with a terminal
observation. It carries no baggage.

## Request interceptor extension

Contribute `McpRequestInterceptor` through Dagger set multibinding to reject a request at the frozen
pre-dispatch stage — after the envelope decodes and identity is established, but before the method
dispatches and before any tool is resolved or argument is processed. `beforeRequest` runs once per
applicable request, on the request's owning Vert.x context, and must not block or return `null`; a
request is rejected only by completing the returned `Future` with a failure. Zero or more
interceptors run in the `OrderedExtension` `phase` → `priority` → `orderKey` order, never Dagger set
iteration order; two interceptors sharing the same `(phase, priority, orderKey)` triple fail startup
naming both classes. Named implementors include a tenant-entitlement guard and a maintenance-window
guard.

`McpRequestContext` is the immutable, payload-free snapshot an interceptor observes: the recognized
method, the always-non-null established `SecurityContext`, and the optional correlation and body
trace context. It exposes no request body, header, or credential accessor, and an interceptor can
never mutate arguments, reorder a fixed stage, or recover a failure another stage produced — it may
only permit or reject.

## Tool interceptor extension

Contribute `McpToolInterceptor` through Dagger set multibinding to reject a `tools/call` invocation
at the frozen post-validation stage — after Bean Validation has already run inside the generated
invoker's `prepare(...)`, but before the generated invocation (`McpPreparedToolCall#invoke()`) ever
runs. `beforeInvocation` runs once per applicable call, on the request's owning Vert.x context, and
must not block or return `null`; a call is rejected only by completing the returned `Future` with a
failure. Zero or more interceptors run in the same `OrderedExtension` `phase` → `priority` →
`orderKey` order the request-interceptor stage uses, never Dagger set iteration order; two
interceptors sharing the same `(phase, priority, orderKey)` triple fail startup naming both classes.
Named implementors include a per-tool entitlement guard and a data-loss-prevention guard. This is the
second and final live interceptor stage; the pre-dispatch `McpRequestInterceptor` stage above runs
earlier, before any tool is resolved.

`McpToolInvocationContext` is the immutable, argument-free snapshot a tool interceptor observes: the
pre-dispatch `McpRequestContext` and the resolved `McpToolDescriptor`. It exposes no accessor for the
raw wire argument tree, the post-processing normalized argument tree, or any invocation result, so a
tool interceptor can reject a call but never observe or mutate the arguments it is guarding.

## Opt-in value observation

`McpToolValueObservation` is a neutral capability a session returned from `McpRequestLifecycleObserver
#open` may additionally implement to receive `onToolInput`/`onToolOutput` — the bounded, normalized
tool argument and result values a plain `McpRequestObservation` session never receives. Least
privilege is structural: the server delivers a value callback only to a session that is an instance
of this interface, so an ordinary metrics or tracing session implementing only `McpRequestObservation`
has no method on its own type capable of receiving an argument or result reference.

`McpToolInputObservation` carries the pre-dispatch `McpToolInvocationContext` and the bounded
`normalizedArguments` tree exactly as `McpPreparedToolCall#normalizedArguments()` produced it — after
schema validation, INP-001 canonicalization and sanitization, materialization, and Bean Validation.
`McpToolOutputObservation` carries the same context and a bounded, schema-valid `normalizedOutput`
value; its dispatch belongs to the output pipeline. Both records deep-copy their value into an
unmodifiable view at every level of its nested `Map`/`List` structure in their compact constructor,
regardless of whether the value handed in was already immutable, and expose no accessor for raw body
bytes, headers, credentials, or exception text.

Values are callback-scoped: the framework retains no reference to a delivered observation or its
value tree once the callback that received it returns. An implementor that keeps a reference beyond
its own callback does so under its own documented obligation — an immutable record cannot revoke
itself; only an installed audit adapter may copy a policy-permitted value into its own private
evidence handle.

## Dependency boundary

This module consumes only the public core correlation snapshot, the security snapshot and
`ActionRef` types, `io.vertx.core.Future`, and the Jakarta Bean Validation API and its default
provider (resolved directly, not through `vertique-validation`, since `McpBeanValidation` needs only
`jakarta.validation.Validator#validate`). Runtime dispatch, HTTP integration, schema generation, tool
registration, authorization enforcement, and observability adapters belong to separately packaged
modules and must not be introduced here.
