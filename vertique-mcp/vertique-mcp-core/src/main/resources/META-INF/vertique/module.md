<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# MCP Core

> **Status:** Stable
> **Package:** `dev.vertique.mcp.annotation`, `dev.vertique.mcp.tool`, `dev.vertique.mcp.lifecycle`,
> `dev.vertique.mcp.interceptor`
> **Artifact:** `vertique-mcp-core`
> **Depends on:** `vertique-core`, `vertique-security-core`, `jakarta.annotation-api`,
> `jakarta.validation-api`

`vertique-mcp-core` owns the stable public API for authoring Model Context Protocol tools, plus
Model Context Protocol lifecycle facts and neutral per-request observation. It contains the tool
authoring annotations, the immutable descriptor and invocation contracts, immutable terminal and
completion events, their outcome classifications, and extension interfaces. It has no HTTP router,
protocol parser, handler invocation, or runtime composition.

## When To Use It

Include `vertique-mcp-core` when an application needs to:

- publish Model Context Protocol tools with `@McpTool` / `@McpToolParam` on dependency-injected types;
- return explicit `McpToolResult` / `McpContent` payloads, or accept `McpCancellationSignal` /
  progress reporting in a tool method;
- contribute `McpRequestInterceptor` / `McpToolInterceptor` guards or
  `McpRequestLifecycleObserver` / `McpRequestCompletedListener` observation;
- read immutable MCP lifecycle facts (`McpRequestTerminalEvent`, `McpRequestCompletedEvent`) from
  those extension points.

Pair with `vertique-codegen-mcp` so annotated tools compile to invokers, and install
`vertique-mcp-server` (`McpServerModule`) for the HTTP mount and runtime composition. Do not add
this artifact alone expecting a listening MCP endpoint.

---

## Tool authoring

Annotate a `public` method of a dependency-injected `public` type with `@McpTool` to publish it as
a tool, and describe each declared parameter with `@McpToolParam`. Both annotations have `CLASS`
retention and are consumed at compile time by `vertique-codegen-mcp`; nothing scans the classpath
at runtime.

Tool names are explicit, unique, case-sensitive, 1–128 characters, and match `[A-Za-z0-9_.-]+`.
Descriptions are non-blank and at most 4,096 characters; a blank title is omitted from the wire. A
blank `@McpToolParam.name` resolves to the source parameter name. Requiredness is type-derived —
an `Optional<T>` parameter may be absent, every other parameter is required.
Tools may declare bounded top-level client capability requirements with
`@McpTool(requiredClientCapabilities = {"sampling"})`. The server checks these after authorization
and before input preparation; a missing capability produces HTTP 400 and JSON-RPC `-32021`. This
metadata declares a prerequisite only and does not implement client-driven sampling.

`McpCancellationSignal` is the only framework-supplied parameter a tool method may declare. It is
excluded from the input schema and lets a handler stop cooperative work when the request settles as
anything other than a successful write: a client disconnect, a response stream reset, a failed
write, the shared HTTP layer closing an idle or slow connection, or the mount's optional request
deadline expiring (MCP arms no whole-request timeout unless one is configured). Its
`progressReporter()` exposes standard request-scoped progress and is a successful no-op when the
request has no progress token. Cancellation is cooperative: the framework
cannot stop a handler that ignores the signal.

`McpToolResult` is the immutable, complete-only result type a handler may return when it needs
explicit content or a tool execution error; use its `text`, `content`, `structured`, and `error`
factories. `McpContent` provides the standard text, image, audio, resource-link, and embedded
resource blocks, preserving the order supplied by the handler. It carries no partial or
intermediate state — a call either produces one settled `McpToolResult`, or it never produces one
at all.

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
`vertique` id — the same managed-edge floor every other JSON boundary falls through to; the process
codec's `system` baseline is reached only by explicit selection at one of the tiers above. Everything
after the annotations is resolved once during composition by `vertique-mcp-server`, which also rejects
an unknown id before the router is mounted.

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

`McpToolInvoker`, `McpPreparedToolCall`, and `McpStructuredOutputWriter` are generated-runtime
contracts: application code does not call them. A hand-written invoker remains supported; one that
declares a typed policy follows the hook contract described below. `prepare` is the fixed input
boundary — the server validates arguments against the input schema, generated code then applies
input policies, materializes typed parameters through the effective JSON profile, and performs Bean
Validation. No prepared call exists for a failed stage. A generated invoker's
`structuredOutputWriter()` exposes only the ability to stream a structured result through the same
stable profile mapper into a server-owned destination; it does not expose the mapper or own output
limits, validation, observation, or terminal encoding.

`McpToolInvoker#accessPolicy()` is the typed-policy hook. It returns
`Optional<Class<? extends AccessPolicy>>` and defaults to empty, which means the access record of the
tool's `McpToolDescriptor` governs it; an invoker compiled before the hook existed therefore keeps
linking and behaving as it did. A generated invoker for a tool that references a policy overrides the
hook with that policy type and publishes a descriptor whose `McpToolAccess` is `DENY_ALL` with no
roles and no action. The placeholder is deliberate: a runtime that reads only the descriptor and
ignores the hook hides and refuses the tool instead of silently dropping requirements it cannot
express. A hand-written invoker may take the same path: implement `accessPolicy()` to return a policy
and publish the `DENY_ALL` descriptor; the server registry validates the returned policy at
registration. An inline-only invoker emits no hook and keeps its descriptor access exactly.

`McpBeanValidation`, `McpInputRejectionException`, and `McpValueTrees` are further generated-runtime
support types, public for the same reason `McpToolInvoker` is: `prepare` is generated into an
arbitrary application package, which cannot reach a package-private framework type in a different
module. `McpBeanValidation.validate(carrier, Optional<Validator>)` is the single seam every generated
`prepare()` calls for its Bean Validation stage, through its constructor-injected
`Optional<jakarta.validation.Validator>`: when the application's Dagger graph binds a `Validator` —
for example, `vertique-mcp-server`'s `@BindsOptionalOf Validator` resolved to an application-bound,
Dagger-aware `Validator` that can construct an `@Inject`-only `ConstraintValidator` — validation runs
through it, and the shared, thread-safe default Jakarta Bean Validation `Validator` is never built.
When the graph binds no `Validator` of its own, the call falls back to that one shared default
instance, built lazily on first use so a deployment with a bound `Validator` never pays its
provider-discovery bootstrap cost.
`McpInputRejectionException` signals that a `tools/call` argument tree failed input-policy
application, materialization, or Bean Validation; its message is always a fixed, non-interpolated
literal, since it is returned to the caller verbatim. `McpValueTrees.deepUnmodifiableMap(map)` builds
the deeply immutable, null-preserving `normalizedArguments` map every generated `PreparedCall`
returns — unlike `Map.copyOf`, an explicit `null` value never throws, and every nested `Map`/`List`
is unmodifiable too, not just the root. `McpRequestView#toolInput()` reports that tree. Application
code neither calls nor throws any of these three types itself.

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

`McpRequestTerminalEvent.protocolVersion` carries the request's negotiated protocol version — never a
hardcoded constant — and is non-null only for a request whose protocol negotiation completed; a request
rejected at or before negotiation carries `null`. When present it is bounded: non-blank, free of
control characters, at most 64 characters. `authorization` is present only after an actual policy
evaluation, and `correlation` is established for every request that reaches the completion coordinator
(a cheap-admission rejection on a first entry, which precedes that point, carries neither).

## Observation extensions

Contribute `McpRequestLifecycleObserver` through Dagger set multibinding. The server opens one
`McpRequestObservation` per contributed observer for each request, invokes terminal observation at
logical settlement, and invokes completion observation after the transport settles. Observers are
observe-only, synchronous, non-blocking, and failure-isolated by the server runtime.

Applications that only need a post-transport callback can contribute an
`McpRequestCompletedListener`. Listener order is unspecified and listener failures cannot alter a
request outcome. Choose the listener when a stateless completion hook suffices — it receives the
same completion event (terminal facts included) with no per-request session object, and, through
the two-argument `onCompleted`, a read-only view of the request's bytes and normalized tool values
(see Completion view below); choose the observer SPI whenever per-request state or the pre-write
terminal callback is needed. The listener mirrors REST's request-completed listener deliberately.

`McpRequestTerminalObservation.linkedTrace` is the optional normalized W3C trace reference associated
with a terminal observation — `dev.vertique.core.correlation.TraceReference`, the framework's single
trace-reference type. It carries no baggage, and is present only when the MCP server module's
`mcp.bodyTracePolicy` is `LINK` and a valid `params._meta.traceparent` was found; `null` under the
default `IGNORE` policy.

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
method, the always-non-null established `SecurityContext`, and the optional correlation. It exposes
no request body, header, or credential accessor, and an interceptor can never mutate arguments,
reorder a fixed stage, or recover a failure another stage produced — it may only permit or reject.
This record no longer carries a body-trace-context component: no interceptor ever consumed one, and
the optional body-borne W3C trace reference now travels solely on the payload-free
`McpRequestTerminalObservation.linkedTrace()` (see Observation extensions above), never on this
pre-dispatch context.

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

## Completion view

`McpRequestCompletedListener` has two `onCompleted` forms. `onCompleted(McpRequestCompletedEvent)` is
the abstract one and receives the payload-free completion facts. `onCompleted(McpRequestCompletedEvent,
McpRequestView)` is a default method that delegates to the one-argument form. The server always calls
the two-argument form, once per request after transport completion, so a listener that needs only the
event implements the one-argument form and a lambda keeps working. A listener that needs the request's
bytes or values overrides the two-argument form and implements the one-argument form as an empty
method; never make the one-argument form delegate back, which would recurse.

`McpRequestView` is a framework-owned, read-only view of one request:

| Accessor | Reports |
|---|---|
| `jsonRpcRequestId()` | The JSON-RPC `id` in its wire textual form; empty when the request carried none or it could not be decoded |
| `requestHeaders()`, `responseHeaders()` | Lower-cased names mapped to their values in wire order; immutable |
| `requestBody()`, `responseBody()` | A `PayloadSource` over the bytes received and the bytes the single terminal writer sent |
| `toolContext()` | The `McpToolInvocationContext` of a `tools/call` that reached a prepared invocation |
| `toolInput()` | The normalized arguments the tool was invoked with, as `McpPreparedToolCall#normalizedArguments()` produced them; not the wire value |
| `toolOutput()` | An `Optional<McpToolOutput>` whose `structuredContent()` is the normalized, schema-valid structured result |

The view is read-only and per listener. Each listener receives its own view over the same bytes,
`PayloadSource` exposes no write path, and every map and tree is unmodifiable, so no listener can
change the response or what another listener reads. The framework copies no body on behalf of a
listener: a listener that keeps a body beyond its callback copies it.

The server binds each part as it handles the request, before any interceptor or observer can alter it:

- **Request headers and body** are bound when the request is admitted, and only when at least one
  completion listener is registered. A decode error or an authentication rejection that reaches a
  completion coordinator therefore carries them.
- **The JSON-RPC id** is bound once the envelope decodes.
- **`toolContext` and `toolInput`** are bound from the prepared call before the tool-interceptor stage.
- **The response** is bound immediately before the terminal write, including the bounded `504` of an
  expired request deadline.
- **`toolOutput`** is present only when the result's own terminal write won settlement. Present does not
  mean the client received it; `McpRequestCompletedEvent#transportOutcome()` carries that.

A part the request never reached is empty: a rejection before a prepared call has an empty
`toolContext` and `toolInput`, and a disconnect before the write has an absent `responseBody`.

The raw request and response carry whatever the caller and the tool put there, `Authorization` and
`Cookie` headers included. The view is not an access-control boundary: every contributed listener
receives it, so contribute a listener that overrides the two-argument form only from code trusted with
that data. `toString()` of the view prints no header, body, or value.

**Migration.** Removal is a break with no deprecation cycle. A type that implements
`McpToolValueObservation` or `McpRawEvidenceObservation`, or that reads `McpToolInputObservation`,
`McpToolOutputObservation`, `McpRequestAdmissionEvidence`, or `McpResponseEvidence`, no longer compiles.
Move to a `McpRequestCompletedListener` that overrides the two-argument `onCompleted`, implements the
one-argument form as an empty method, and reads `request.toolInput()`, `request.toolOutput()`,
`request.requestBody()`, and `request.responseBody()`.

## Opt-in completion scope

`McpCompletionScope` is a neutral capability a session returned from `McpRequestLifecycleObserver
#open` may additionally implement to bracket the completion dispatch loop with an ambient scope — for
example, re-making a captured request span current so a co-installed metrics observer's recording
happens inside it. The server calls `openCompletionScope()` once, before any retained observation's or
completion listener's `onCompleted` runs, and closes the returned `AutoCloseable` once after all of
them return. Both the open and the close are failure-isolated per session, exactly like every other
lifecycle callback, so one misbehaving scope cannot affect another scope, any observer, any listener,
or the request itself. This mirrors the framework's own `RequestCompletionScope` role for REST, but as
an opt-in session capability rather than a separately multibound set, since the completion dispatch
already threads through the per-request `McpRequestObservation` sessions this module owns.

## Key Classes

### Application surface

| Type | Role |
|---|---|
| `@McpTool`, `@McpToolParam` | Compile-time tool publishing; consumed by `vertique-codegen-mcp` |
| `McpToolResult`, `McpContent` | Handler-authored complete-only results and standard content blocks |
| `McpCancellationSignal`, `McpProgressReporter` | Cooperative cancellation and request-scoped progress |
| `McpToolDescriptor`, `McpToolAnnotations`, `McpToolAccess`, `McpAccessMode` | Immutable published tool metadata and access record |
| `McpRequestInterceptor`, `McpToolInterceptor`, `McpRequestContext`, `McpToolInvocationContext` | Fail-closed pre-dispatch and post-validation interceptor SPIs |
| `McpRequestLifecycleObserver`, `McpRequestObservation`, `McpRequestCompletedListener` | Neutral observation SPIs |
| `McpRequestTerminalEvent`, `McpRequestCompletedEvent`, outcome / method / error enums | Immutable lifecycle facts |
| `McpRequestView`, `McpToolOutput` | Read-only completion view of a request's bytes and normalized tool values |
| `McpCompletionScope` | Opt-in session capability on observer sessions |

Tool methods may declare Jakarta security annotations (`@PermitAll`, `@DenyAll`, `@RolesAllowed`,
`@RequiresAction`, `@RequiresPolicy`). An unannotated tool is public. Typed policies use the
generated `McpToolInvoker#accessPolicy()` hook described under Descriptor and invocation contracts.

### Framework seams (INTERNAL)

Application code does not call or implement these. They are public so generated invokers (in
arbitrary application packages) and sibling framework modules can reach them:

| Type | Role |
|---|---|
| `McpToolInvoker`, `McpPreparedToolCall`, `McpStructuredOutputWriter` | Generated-runtime invocation contracts |
| `McpBeanValidation`, `McpInputRejectionException`, `McpValueTrees` | Generated `prepare` support |

---

## Module Dagger Bindings

None. This artifact ships no Dagger `@Module`. Applications install `McpServerModule` from
`vertique-mcp-server` and contribute interceptor / observer multibindings there.

---

## Dependencies

Compile-scope Vertique dependencies: `vertique-core`, `vertique-security-core`. Also
`jakarta.annotation-api` and `jakarta.validation-api` (Bean Validation API only — no provider).

This module consumes only the public core correlation snapshot, the security snapshot and
`ActionRef` types, `io.vertx.core.Future`, and the Jakarta Bean Validation API (resolved directly,
not through `vertique-validation`, since `McpBeanValidation` needs only
`jakarta.validation.Validator#validate`). It declares no Bean Validation provider dependency:
`Validation.buildDefaultValidatorFactory()` discovers one via `ServiceLoader` at runtime,
so a provider (e.g. Hibernate Validator) is a runtime concern of whichever module puts an MCP
application on the classpath — `vertique-mcp-server` — not of this API-only module. Runtime
dispatch, HTTP integration, schema generation, tool registration, authorization enforcement, and
observability adapters belong to separately packaged modules and must not be introduced here.
