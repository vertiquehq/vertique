# Vertique MCP Server

> **Status:** Alpha
> **Package:** `dev.vertique.mcp.server`
> **Artifact:** `vertique-mcp-server`
> **Depends on:** `vertique-mcp-core`, `vertique-core`, `vertique-input-processing`, `vertique-json`,
> `vertique-json-schema`, `vertique-rest-core`, `vertique-rest-security`

`vertique-mcp-server` composes the optional HTTP Model Context Protocol server. Include
`McpServerModule` explicitly in the application's Dagger component and supply an immutable
`McpServerConfig` binding. Generated tools are registered and bound to their effective JSON profile
during composition (see [Tool runtime](#tool-runtime)); the mount serves `server/discover`, the
bounded, authorized `tools/list` pagination described in
[Authorized tool listing and pagination](#authorized-tool-listing-and-pagination), and the
zero-argument authorized `tools/call` dispatch described in
[Zero-argument tool calls](#zero-argument-tool-calls), the fixed request-time input pipeline for
parameterized calls described in
[Request-time input pipeline](#request-time-input-pipeline), the disconnect/reset/write-failure
cancellation and write-phase settlement described in
[Cancellation and write-phase settlement](#cancellation-and-write-phase-settlement), the ordered,
fail-closed pre-dispatch request-interceptor stage described in
[Request interceptor stage](#request-interceptor-stage), the ordered, fail-closed
post-validation tool-interceptor stage described in
[Tool interceptor stage](#tool-interceptor-stage), the opt-in, capability-gated `onToolInput`/
`onToolOutput` value-observation callbacks described in
[Value observation stage](#value-observation-stage), and the single-pass bounded output
normalization, output-schema validation, and observation placement described in
[Bounded output pipeline](#bounded-output-pipeline).

Configuration is disabled by default. When enabled, `serverName` and `serverVersion` are required,
the mount is one literal path ending in `/*`, and every configured value is validated for range and
consistency at startup, before the router is mounted — so an out-of-range value fails composition
rather than a live request. JSON-RPC envelope parsing is bounded by Jackson's own frozen
`StreamReadConstraints` inside the private
[Bounded JSON-RPC envelope codec](#bounded-json-rpc-envelope-codec) — MCP owns JSON-RPC envelope
semantics, not a second general-purpose JSON resource-limit subsystem, and exposes no per-constraint
generic JSON configuration keys for it. Request body size is enforced from `http.maxBodySize`, which
also bounds the maximum decodable envelope document length. A configured `jsonProfile` is validated during composition even
if MCP is disabled, preventing a latent invalid deployment configuration.

**Transport liveness is not provided out of the box, and startup enforces that at least one qualifying
bound exists.** MCP arms no whole-request deadline of its own; it relies entirely on the shared
`HttpConfig` idle/read timeouts to ever close a stalled or abandoned connection.
`http.idleTimeoutSeconds` and `http.readIdleTimeoutSeconds` both **default to `0`, which disables
them**. Left unset, a hanging request-interceptor, a hanging tool-interceptor, a hanging tool handler,
or a client that simply stops reading mid-response could hold its connection, and the MCP request
lifecycle observation opened for it, open indefinitely — reachable by an unauthenticated caller
against any `@PermitAll` tool. `McpServerConfigValidator` closes this at composition: **an enabled MCP
mount refuses to start unless `http.idleTimeoutSeconds` or `http.readIdleTimeoutSeconds` is greater
than zero.** `http.writeIdleTimeoutSeconds` does not qualify on its own (repair task R33): it fires
only while a write is actually in flight, so it cannot reclaim a connection that opens and then never
reads or writes again. Set at least one of the two qualifying `HttpConfig` timeouts for any MCP
deployment — the mount will not start otherwise.

**Configuration keys are flat under `mcp`.** `McpServerConfig` is bound from the `mcp` section by
Jackson using the field names exactly as declared: `mcp.outputMaxBytes`, `mcp.ingressMaxTokens`,
`mcp.outputMaxTokens`, `mcp.toolsPageSize`, `mcp.toolsTtlMs`, and `mcp.jsonProfile`, not dotted
nested objects. There is no nested `tools`, `output`, or `json` object. Ordinary unknown keys remain
deliberately forward-compatible and are silently ignored, so use the declared field names rather
than dotted prose spellings.

**Ingress and output token budgets are independent.** `mcp.ingressMaxTokens` is the parser-token
budget for one incoming JSON-RPC envelope, and `mcp.outputMaxTokens` is the parser-token budget for
one structured-output normalization. Each defaults to 65,536 and accepts the inclusive range
1,024–262,144; neither has an unlimited mode, and changing one does not change the other. These
token budgets are distinct from the encoded byte caps: `http.maxBodySize` remains the independent
ingress body-size and maximum-decodable-document limit, while `mcp.outputMaxBytes` remains the
response/output byte limit.

**Both token budgets are actively enforced.** `McpRequestDispatcher` passes the validated scalar
`config.ingressMaxTokens()` to `McpProtocolCodec`, which passes that same scalar to
`McpEnvelopeJsonCodec` as Jackson's `maxTokenCount`; it is neither derived from `http.maxBodySize`
nor replaced by a fixed internal ingress limit. Token exhaustion is a malformed JSON-RPC frame:
HTTP `400` with JSON-RPC code `-32700`, before dispatch. Independently, the dispatcher applies
`config.outputMaxTokens()` to the reparse of bytes already bounded by `mcp.outputMaxBytes`; there is
no separate fixed node or parser-token limit.

**Former implementation-era keys are ordinary unknown keys.** `mcp.requestTimeoutMs`,
`mcp.jsonMaxDepth`, `mcp.jsonMaxPropertiesPerObject`, `mcp.jsonMaxItemsPerArray`,
`mcp.jsonMaxStringChars`, and `mcp.toolsListDeadlineMs` were implementation-era spellings that never
shipped in any release; supplying any of them is silently ignored exactly like any other unrecognized
key (see the forward-compatibility note above), not rejected. Where their underlying concern still
matters, it moved elsewhere rather than surviving as an MCP setting: whole-request liveness is
`http.idleTimeoutSeconds` / `http.readIdleTimeoutSeconds` / `http.writeIdleTimeoutSeconds`; JSON-shape
limits are Jackson's own frozen `StreamReadConstraints` inside the private envelope codec (see
[Bounded JSON-RPC envelope codec](#bounded-json-rpc-envelope-codec)); and the `toolsListDeadlineMs`
concern is owned jointly by per-decision authorization timeouts and the shared HTTP liveness
settings, with no direct replacement MCP setting.

## Stateless HTTP contract

The mount is stateless and multi-instance: it establishes no session, emits no cookie or affinity
header, and two independently deployed servers share nothing, so a load balancer may route any
request to any instance. Every request is admitted through the fixed pipeline before dispatch:

- **Method** — only `POST` is accepted. `GET`, `DELETE`, and any other method are HTTP `405`.
- **Origin** — deny-by-default: a request that carries an `Origin` not literally contained in
  `mcp.allowedOrigins` is rejected with HTTP `403` before dispatch. An empty allowlist (the default)
  therefore rejects **every** present `Origin` rather than imposing no restriction — the MCP HTTP
  transport spec requires Origin validation specifically so a locally bound, unconfigured MCP server
  is not reachable from an arbitrary browser page or a DNS-rebound name. A request with no `Origin`
  header (every non-browser client) is never origin-rejected.
- **Content-Type** — mandatory: every admitted request is a `POST` carrying the protocol's required
  JSON-RPC body, so an absent `Content-Type` is rejected with HTTP `415` exactly like a present one
  whose media type (parameters such as `; charset=utf-8` ignored) is not `application/json`. Admitting
  an absent `Content-Type` would reopen the CORS simple-request path (a cross-origin `Blob` with an
  empty type, or `navigator.sendBeacon`, both send none).
- **Accept** — a request that carries an `Accept` admitting none of `application/json`,
  `text/event-stream`, `application/*`, or `*/*` is rejected with HTTP `406`. A request with no
  `Accept` header is never media-rejected. Discovery always answers `application/json`, so a client
  that accepts `application/json`, `text/event-stream`, or both receives the JSON discovery result.
- **Body limit** — a body larger than `http.maxBodySize` is a bounded HTTP failure, not a protocol
  result.
- **Session headers** — the stateless protocol has no session concept, so an unsupported session
  header (for example `Mcp-Session-Id`) is ignored and the request stays bounded.

Every admitted body is decoded exactly once through the strict codec — the single envelope
authority — and `server/discover` is routed on that decoded result like every other method, so it
requires a schema-valid `params` (carrying the protocol version and client capabilities) just as the
rest of the supported set does. A `server/discover` frame that omits `params` is an invalid envelope,
not an admitted discovery result. Protocol failures use the final-spec JSON-RPC codes and their
mapped HTTP status: a malformed frame is `-32700` (HTTP `400`), an invalid envelope — including a
paramless `server/discover` — is `-32600` (HTTP `400`), and an unknown method is `-32601`
(HTTP `404`). None of these admission or protocol failures invokes a tool.

## Request lifecycle and exactly-once settlement

Every admitted request opens neutral lifecycle observation (`McpRequestLifecycleObserver`) before
authentication, and settles through a completion coordinator that captures the request-owning Vert.x
context and redispatches every off-context completion onto it. Settlement is **first-observed-wins**
and delivers **exactly one terminal event followed by exactly one completion event** on every
path — a successful write, a client disconnect, or a response-stream reset. The terminal always
precedes the completion, and any later signal after the first settlement wins is suppressed, so a
disconnect that races a late handler result cannot produce a second terminal. A disconnect or reset
before the first response byte records an uncommitted (`responseCommitted=false`)
`DISCONNECTED`/`RESET` completion. MCP arms no whole-request timer of its own: transport liveness
comes from the shared `HttpConfig` idle/read/write timeouts — guaranteed armed for every mount that
actually starts by the startup gate described above — so an idle or slow connection is closed by the
shared HTTP layer and reaches MCP through this same disconnect/reset settlement path, classified as
transport cancellation (`McpErrorType.TRANSPORT`) rather than a distinct timeout outcome. `McpErrorType.TIMEOUT` has no producer in this module; it is retained in the frozen
lifecycle enum purely for enum stability, reserved for a future cross-transport server-operation
`@Timeout` capability. Observer `open`, callback, null-session, and retention failures are isolated
per observer and never change the protocol or business outcome — including a `StackOverflowError` from
an observer's `open` or from any `onToolInput`/`onToolOutput`/`onTerminal`/`onCompleted` callback,
which is isolated exactly like a `RuntimeException`: a deeply recursive application
callback can no longer abort the coordinator's construction or strand the completion behind a
half-published terminal. **This changed one observable outcome:** a `StackOverflowError` from a
capable session's `onToolInput` used to escape the coordinator and degrade the whole `tools/call` to
a bounded `500`, while a `RuntimeException` from the same callback was isolated and the call
succeeded. Both are now isolated and the call succeeds, which is what "never change the protocol or
business outcome" always said. A failure the *framework* hits while building an observation — as
opposed to one an observer throws — still degrades to the bounded internal-error response.

**A disconnect or reset also runs the ordinary request-scoped cleanup.** Settlement is
driven from the routing context's own end handler rather than from the response close/exception
handlers. Those two are single-slot, and Vert.x Web installs its own there to drive every registered
end handler, so registering on them replaced them and silently disabled the framework's request
cleanup on this mount for exactly the paths it matters on: nothing registered with
`RequestContextLifecycle` — the correlation binding, any other holder binding, or the mount's own
request-scoped upload cleanup — ran when a client vanished mid-request. It does now. Transport
outcome classification also became more accurate as a result: an orderly client disconnect records
`DISCONNECTED` where it previously recorded `RESET`. The method, `Origin`,
`Content-Type`, and `Accept` admission checks all run before the completion coordinator is created,
so — like a body-limit rejection — a request that fails admission produces no lifecycle observation;
only an admitted request opens observation.

**Completion scope bracketing.** Immediately before the completion coordinator
dispatches the one completion event to every retained observation and completion listener, it opens
every retained session's `McpCompletionScope` — an opt-in capability a session returned from
`McpRequestLifecycleObserver#open` may additionally implement (see `vertique-mcp-core`'s "Opt-in
completion scope") — and closes every opened scope, in reverse open order, only after every observer
and listener has returned. Both the open and the close are per-session failure-isolated, exactly like
every other lifecycle callback, so a misbehaving scope affects neither the request, another scope, nor
any observer or listener. The motivating consumer is `vertique-opentelemetry-mcp`'s span observer,
which re-establishes its captured span as current for this window so a co-installed Micrometer
observer's timer recording carries a valid span for a registry-level exemplar bridge to attach.

## Cancellation and write-phase settlement

A disconnect, a stream reset, or a failed terminal write fires the request's `McpCancellationSignal`
exactly once, in addition to the exactly-once terminal/completion settlement above. The completion
coordinator owns this signal and fires it from the same first-observed-wins guard that governs
completion: a genuinely successful write never fires it, but every other settlement path does,
including the stalled-write recovery below. Every `tools/call` invocation receives this signal through
`McpToolInvoker#prepare`; a cooperative handler may register `cancellation.cancelled()` to stop early,
but cancellation remains cooperative only — the framework cannot forcibly stop a handler that ignores
it, and a late result from a handler that never checked or never stopped is still suppressed at the
write boundary (below), never delivered to a client whose connection is already gone.

The successful-write path is two-phase: the terminal publishes before the byte write begins and the
completion publishes only after it resolves. A slow client can leave that write pending indefinitely —
this is not bounded by a request timer, since MCP arms none of its own — so a disconnect or reset
signal that arrives while a write is still pending drives that same write's completion directly with
the signal's own transport outcome (`DISCONNECTED` or `RESET`) instead of being dropped, guarded
exactly-once by the same completion latch; a write that does genuinely resolve afterward is then a
suppressed no-op. A write whose own transport future fails settles directly as `WRITE_FAILED`,
recording the response's real commit state (`headWritten()` at settlement time) rather than a value
inferred from the write's success flag. No MCP-owned whole-request deadline is introduced by any of
this: transport liveness stays exclusively with the shared `HttpConfig` idle/read/write timeouts,
guaranteed armed for every mount that actually starts by the startup gate described above.

## Bounded response output

The response write is bounded by `mcp.outputMaxBytes`: serialization streams through a byte-counting
writer that stops the moment the running count would exceed the cap, so an over-cap response is
classified as a bounded internal error and never emitted — the full over-cap byte array is never
materialized. Discovery and `tools/list` responses are far below the default cap; a `tools/call`
structured result is bounded the same way — see [Bounded output pipeline](#bounded-output-pipeline)
for the full output-stage order this cap is one part of.

Every JSON-RPC error response — a negotiation-mismatch `-32020`, an official-params or
unknown-or-unauthorized `-32602`, an interceptor rejection, an ordinary envelope-decode failure
(`-32700`/`-32600`/`-32601`), and every internal-error fallback — serializes through this exact same
capped mechanism, never a separate unrestricted encode measured only after the fact. The one
unbounded element any of these shapes can carry is the echoed request `id` (bounded only by the
envelope codec's own frozen `maxStringLength`,
far above this cap's floor): when even the id-bearing shape would exceed `mcp.outputMaxBytes`, the
response degrades to the minimal id-less generic internal-error shape instead — itself encoded through
the same capped writer — so a client that sent an oversized id receives a bounded response with a
`null` id rather than its own id ever being echoed back in an oversized payload.

## Bounded JSON-RPC envelope codec

Wire decoding is framework-owned and trusts no application mapper. A package-private
`McpEnvelopeJsonCodec`, built on Jackson's own `StreamReadConstraints`, decodes exactly one complete
JSON value and rejects — with a classified, bounded outcome and **no partial value** — any frame that
carries:

- **duplicate object keys** (`StreamReadFeature.STRICT_DUPLICATE_DETECTION`), or **trailing tokens**
  after one complete top-level value (`DeserializationFeature.FAIL_ON_TRAILING_TOKENS`);
- nesting deeper than 1,000 levels (`maxNestingDepth`), a numeric token longer than 1,000 characters
  (`maxNumberLength`), a string longer than 20,000,000 characters (`maxStringLength`), or a property
  name longer than 50,000 characters (`maxNameLength`) — Jackson's own default values, restated
  explicitly so the codec never silently inherits a changed upstream default;
- a document larger than the effective `http.maxBodySize` in bytes (`maxDocumentLength`) — the one
  Jackson default (unlimited) this codec narrows, read from the shared `HttpConfig` rather than a
  separate MCP configuration key;
- more than the configured `mcp.ingressMaxTokens` JSON tokens (`maxTokenCount`). This is independent
  of the `http.maxBodySize` document-length limit: byte length and parser-token count bound different
  properties, and either may reject first. The dispatcher-to-protocol-to-envelope scalar seam preserves
  the configured token value unchanged, so there is no byte-derived ratio or fixed ingress fallback;
- invalid UTF-8.

The generic limits above are not consumer-visible configuration keys: the four generic JSON-limit
properties and the handcrafted strict JSON reader that used to enforce them were removed in favor
of Jackson's own bounded read constraints. MCP owns JSON-RPC
envelope semantics, not a second general-purpose JSON resource-limit subsystem, and exposes no
public parser API. Tool argument and result values continue to use the existing
`JsonMapperProfile`/`JsonMapperProfileRegistry` contract, unaffected by this codec.

Numeric values keep their exact lexical precision: a 64-bit-overflowing integer such as
`9007199254740993` and a decimal such as `0.10000000000000001` survive decode and canonical
re-encode without lossy `double` rounding, so downstream schema validation sees exactly what the
client sent. Canonical encoding is a compact, insertion-order-preserving re-encode; an
already-compact frame round-trips byte-for-byte. A decimal whose scale magnitude is far beyond any
legitimate value — the vector that would otherwise drive an out-of-memory plain-form encode — is
rejected against a fixed internal hardening bound (a
decimal whose scale magnitude exceeds 9,999) rather than materialized into a value the encoder could
later choke on.

Over that codec, `McpProtocolCodec` validates the final-2026 JSON-RPC request envelope — `jsonrpc`
must be `"2.0"`, `method` must name one of the bounded supported set (`server/discover`,
`tools/list`, `tools/call`), the request `id` must be present and a string or integer (all three
supported methods are requests, never notifications), and `params` must be present and an object
(the vendored final-2026 request schema marks it required for every supported method) — and
classifies failures deterministically to the standard JSON-RPC codes with the standard messages and
no `data`: `-32700` *Parse error* (malformed JSON, or an envelope-codec rejection such as a duplicate
key or trailing token; null id), `-32600` *Invalid Request* (bad envelope — wrong version, a missing
or non-string/non-integer id, a missing method, or a missing or non-object `params`; original usable
id when the id itself is a trustworthy string or integer, else null), and `-32601` *Method not
found* (unknown method; original usable id). Unknown-or-unauthorized tool classification (`-32602`)
belongs to the tool-dispatch slice and is not part of envelope decoding. An internal codec failure
settles through a pre-encoded `-32603` *Internal error* response that is written exactly once and
never carries the cause's text, so an internal exception message cannot leak to a client.

Once a decode succeeds, `McpProtocolCodec#validateOfficialParams` validates `params` against the
unmodified pinned official per-method schema (`schema/2026-07-28/schema.json` — `RequestParams` for
`server/discover`, `PaginatedRequestParams` for `tools/list`, `CallToolRequestParams` for
`tools/call`). A schema-invalid `cursor` or `name`, or a present non-object `tools/call.arguments`,
is rejected as HTTP 400 JSON-RPC `-32602` *Invalid params* through the same bounded, capped JSON
writer every other terminal response uses. This official request-shape check runs strictly before
header/body or Phase-1 negotiation, request interceptors, method dispatch, tool lookup,
authorization, application input processing, or SSE selection. The pinned schema document ships in
this module's own resources (not test-only), so this validation is available in production as
shipped, not merely in the test tree.

Only after official params validation succeeds does `McpProtocolCodec#validateNegotiation` validate
protocol negotiation. `MCP-Protocol-Version` and
`Mcp-Method` are required on every supported request and must agree with
`params._meta`'s `io.modelcontextprotocol/protocolVersion` and the envelope's `method`.
`Mcp-Name` is required and compared with `params.name` only for `tools/call`; `server/discover` and
`tools/list` have no name-shaped identifier, so they accept either an absent or unsolicited
`Mcp-Name`. Any one of these routing headers sent more than once — even with every occurrence
identical — fails negotiation rather than matching on the first observed value (repair task R33): a
duplicated header is rejected to prevent intermediary/backend header-desync, where a proxy or gateway
forwards a different one of the duplicated values than the one this codec observed. The per-method
`_meta` shape and supported protocol version must satisfy Phase-1 policy,
and `tools/call.params` must carry neither reserved multi-round-trip field
`inputResponses`/`requestState`. A missing applicable header, header/body disagreement, or Phase-1
negotiation-policy violation is exclusively `-32020` *Header/body mismatch*, mapped to HTTP 400
through the bounded, capped JSON writer. Negotiation still completes before the request-interceptor
stage below and every later application stage — see
[Request interceptor stage](#request-interceptor-stage).

The mount handles no file uploads of its own, but it does not rely on that alone: an
application-composed ancestor `BodyHandler` with uploads enabled spools multipart parts to disk
before any MCP handler runs, so such files do exist for the duration of the request. The mount
deletes every request-scoped upload once the request settles — on completion, failure, or connection
reset — so an MCP request leaves no upload file behind after it ends.

The module does not use an MCP Java SDK. It depends on `vertique-mcp-core` for the stable lifecycle
boundary and owns the HTTP/router composition only.

## Body trace-context extraction

Repair task R39. `McpProtocolCodec#extractBodyTraceContext` reads this request's optional body
trace reference from `params._meta.traceparent` / `params._meta.tracestate` — the plain,
un-prefixed keys MCP 2026-07-28's `_meta` reserves for W3C trace-context propagation
(OpenTelemetry trace context), deliberately distinct from the `io.modelcontextprotocol/`-prefixed
negotiation keys `validateNegotiation` reads from the same `_meta` object: W3C trace propagation is
a Vertique-owned extension of `_meta`, not an official MCP protocol field. `baggage` is reserved
upstream too but has no consumer here and is never read.

`traceparent` must match the bounded W3C wire format `00-<32 lowercase hex trace id>-<16 lowercase
hex span id>-<2 hex flags>`; `tracestate`, when present, is bounded by `McpTraceContext`'s own rules
(non-blank, at most 512 characters, printable ASCII only). Extraction is total and never fails the
request: an absent or non-string `traceparent`, syntax that does not match the wire format above, an
all-zero trace or span id, or a `tracestate` outside those bounds all yield no body trace context
rather than an error response, each logged once as a bounded, non-leaking WARN diagnostic (never the
raw `traceparent`/`tracestate` value). Extraction runs at most once per request, in
`McpRequestDispatcher#dispatch`, independent of negotiation's own outcome — a malformed or absent
body trace reference never affects protocol admission.

The extracted, optional `McpTraceContext` reaches every consumer of this request's
`McpRequestContext` — both the pre-dispatch [request interceptor stage](#request-interceptor-stage)
and the post-validation [tool interceptor stage](#tool-interceptor-stage) — and the terminal
observation `McpCompletionCoordinator` publishes at settlement
(`McpRequestTerminalObservation#bodyTraceContext`), captured once via a package-private
`bindBodyTraceContext` call from `dispatch` immediately after extraction. That terminal-carried
value is what the `vertique-opentelemetry-mcp` adapter's `McpServerSpanObserver` reads to add at
most one `Span#addLink` for a valid, distinct body trace reference — a body reference identical to
the HTTP `traceparent` header is meant to be a self-reference and add no link; see that module's own
`module.md` for the adapter's linking mechanics and policy.

## Request interceptor stage

Once — and only once — the envelope decodes successfully, its official per-method `params` schema
validates, *and* protocol negotiation passes does `McpRequestDispatcher` run the ordered, fail-closed
pre-dispatch `McpRequestInterceptor` stage: after those protocol-
boundary stages, before the method dispatches to `server/discover`, `tools/list`, or `tools/call`,
and before any tool is resolved, authorized, or passed to the application input pipeline. A decode
failure never reaches this stage; it settles through
[Bounded JSON-RPC envelope codec](#bounded-json-rpc-envelope-codec) exactly as before. An official
params failure returns HTTP 400 JSON-RPC `-32602` *Invalid params* before this stage, while a
header/body or Phase-1 negotiation failure returns HTTP 400 JSON-RPC `-32020` *Header/body mismatch*.
Neither this interceptor stage nor anything after it observes either rejected request.

Contribute `McpRequestInterceptor` through Dagger set multibinding (`McpServerModule`). The
dispatcher sorts and validates the contributed set exactly once, at construction — never per request
and never falling back to Dagger set iteration order — using the same `OrderedExtension`
`phase` → `priority` → `orderKey` comparator every ordered extension in this framework shares. Two
interceptors sharing the same `(phase, priority, orderKey)` triple fail startup, naming both
conflicting implementation classes, rather than silently picking one order.

Each permitted interceptor runs sequentially in that frozen order, on the request's owning Vert.x
context, observing an `McpRequestContext` that carries the recognized method, the always-non-null
established `SecurityContext`, and the optional correlation and body trace context — no request body,
header, or credential accessor exists, so an interceptor can reject a request but never observe or
mutate its payload. A synchronous `beforeRequest` throw, a `null` returned `Future`, or a `Future`
that resolves failed all reject fail-closed the same way: the remaining interceptors and the method
dispatch never run, and the caller receives the bounded, non-leaking `-32001`/`Request rejected`
JSON-RPC response (HTTP `403`) — never an exception message, never which interceptor rejected. A
zero-interceptor composition is valid and always permits.

## Tool interceptor stage

Once a `tools/call` invocation has passed schema validation (stage 1) and the generated invoker's
`prepare(...)` has returned — meaning stages 2–4 of the [Request-time input
pipeline](#request-time-input-pipeline), including Bean Validation, already succeeded —
`McpRequestDispatcher` runs the ordered, fail-closed post-validation `McpToolInterceptor` stage
strictly before the generated invocation (`McpPreparedToolCall#invoke()`) ever
runs. This is the second and final live interceptor stage; the pre-dispatch
[Request interceptor stage](#request-interceptor-stage) above already ran, earlier, before any tool
was resolved.

Contribute `McpToolInterceptor` through Dagger set multibinding (`McpServerModule`). The dispatcher
sorts and validates the contributed set exactly once, at construction — never per request and never
falling back to Dagger set iteration order — reusing the same `OrderedExtension`
`phase` → `priority` → `orderKey` comparator and duplicate-order-key startup failure the request-
interceptor stage establishes.

Each permitted interceptor runs sequentially in that frozen order, on the request's owning Vert.x
context, observing an `McpToolInvocationContext` that carries the pre-dispatch `McpRequestContext`
and the resolved `McpToolDescriptor` — no raw wire argument, normalized argument, or invocation-
result accessor exists, so an interceptor can reject a call but never observe or mutate the
arguments it is guarding. A synchronous `beforeInvocation` throw, a `null` returned `Future`, or a
`Future` that resolves failed all reject fail-closed the same way: the remaining interceptors and the
generated invocation never run. Because SSE was already unconditionally selected before invocation
began (see [Zero-argument tool calls](#zero-argument-tool-calls)), a rejection here settles through
the same bounded, SSE-framed, text-only `isError=true` `CallToolResult` that a stage 1 schema
rejection and a stage 2–4 rejection already use — never a JSON-RPC protocol error, and never an
interceptor class name, reason, or exception text. A zero-interceptor composition is valid and always
permits.

The wire bytes are identical across all three rejection stages, but the internal lifecycle
classification is not: a stage 1 schema rejection and a stage 2–4 `McpInputRejectionException`
settle as a completed `McpOutcome.TOOL_ERROR` (`McpErrorType.INPUT_VALIDATION` and
`McpErrorType.INPUT_PROCESSING` respectively), while a tool-interceptor rejection settles as
`McpOutcome.REJECTED`/`McpErrorType.INTERCEPTOR`/`resultType=NONE` — because no handler ever ran, not
because the call failed after running one. An observer distinguishing "the input was rejected," "the
handler ran and returned an error," and "the call was rejected before the handler ever ran" reads this
from the terminal event's `errorType`/`outcome`, never from the response body, which cannot make that
distinction.

## Value observation stage

Once the generated invoker's `prepare(...)` has returned — meaning stages 1–4 of the [Request-time
input pipeline](#request-time-input-pipeline), including Bean Validation, already succeeded —
`McpRequestDispatcher` delivers an `McpToolInputObservation` through
`McpCompletionCoordinator#publishToolInput`, strictly before the [Tool
interceptor stage](#tool-interceptor-stage) runs. Delivery is capability-gated: the coordinator
delivers `onToolInput` only to a retained session that is an instance of `McpToolValueObservation`,
never to a plain `McpRequestObservation` session — an ordinary metrics or tracing session never
receives an argument or result reference through any callback. The coordinator declares no field for
the observation; the reference exists only on the publish call's stack and each capable session's
synchronous callback frame, and is unreachable through the coordinator once every `onToolInput` call
has returned.

The gate is checked *before* the observation is even constructed, not merely before delivery:
`McpCompletionCoordinator#hasValueObservers()` is computed once at construction from the opened
session set, and `McpRequestDispatcher` calls it before building an `McpToolInputObservation` or
`McpToolOutputObservation` at all. Both compact constructors deep-copy their entire value tree
unconditionally, so a request with no capable session in this composition never pays that copy for an
attacker-sized argument or result tree.

The delivered `normalizedArguments` is exactly `McpPreparedToolCall#normalizedArguments()`, deep-copied
into an unmodifiable view at every nesting level by `McpToolInputObservation`'s compact constructor.
This is the whole of the framework's enforceable claim: nothing prevents a session from retaining the
reference it is handed past its own callback — an immutable record cannot revoke itself — so
callback-scoped use remains a documented obligation on implementors.

`onToolOutput` is delivered the same way, through `McpCompletionCoordinator#publishToolOutput`,
strictly after the [Bounded output pipeline](#bounded-output-pipeline) has normalized and validated
the result, successfully encoded the bounded terminal envelope, **and** the write has won logical
settlement — never merely after validation. On the written path the callback therefore follows the
terminal event, and a result superseded by a disconnect or reset settlement produces no output
callback: an observer only ever receives a value that also reached the wire.
It fires for every completed result — success or tool error alike — carrying the normalized structured
value (`@Nullable`, absent for a text-only result); a schema-invalid value, or a value the byte or
token cap rejects, never reaches this callback. Delivery is capability-gated identically to
`onToolInput`, and the coordinator retains no reference to the output observation or its value once
every `onToolOutput` call has returned.

## Bounded output pipeline

Every completed `tools/call` result is normalized exactly once, bounded by
`mcp.outputMaxBytes` as bytes are produced and by `mcp.outputMaxTokens` while those bytes are reparsed,
validated against the tool's advertised output schema, encoded into the bounded terminal envelope,
handed to the single terminal writer ([Cancellation and
write-phase settlement](#cancellation-and-write-phase-settlement)), and offered to the opt-in
`onToolOutput` observation only once that writer has won settlement — in that fixed order,
introducing no second streaming, writing, completion, or settlement path.

Every successfully transported `tools/call` result carries the final-protocol discriminator
`resultType: "complete"`, including a handler result whose `isError` value is `true`. A protocol-level
failure remains a JSON-RPC error and carries no tool-result discriminator.

`McpRequestDispatcher` converts a handler's structured result (`McpToolResult#structuredContent()`)
to its bounded, JSON-compatible canonical shape (`Map`/`List`/scalar) exactly once per call; that one
normalized value is reused for output-schema validation, the `onToolOutput` observation, and the wire
embed — nothing re-serializes the original application object a second time. A tool that declares no
output schema, or a text-only/structured-content-free result, is trivially valid: there is nothing to
normalize or validate.

**A structured result with no handler-authored text also carries one canonical-JSON text item**, for
backwards compatibility with clients that read only `content` (the upstream MCP `CallToolResult`
SHOULD, 2026-07-28). When `McpToolResult#textContent()` is empty and structured content is present,
the wire `content` array gets exactly one synthesized `{"type":"text",...}` item whose text is the
canonical JSON serialization of that same already-normalized value — never a second serialization of
the raw application object — so it cannot drift from the `structuredContent` member it mirrors. A
handler that supplies its own explicit text alongside structured content is left exactly as authored:
no canonical text is ever appended when `textContent()` is already non-empty. The duplicated text is
ordinary response content, so it is bounded by the same `mcp.outputMaxBytes` terminal-message cap
described below; a structured value that alone fits comfortably under the cap can still push the
complete response over it once its canonical text duplicate is counted.

**The `mcp.outputMaxBytes` cap independently bounds both output representations.** Normalization
serializes a handler's raw structured value exactly once through the generated invoker's effective-
profile `McpStructuredOutputWriter` into the same byte-counting sink (`CappedOutputStream`, via
`encodeCapped`) every terminal writer uses, aborting the moment the running byte count would exceed
the cap — before a full `Map`/`List` tree is ever built. The resulting bounded byte array, never the
raw value again, is then parsed back into that canonical tree. Hand-written framework fixtures use
the neutral compatibility writer. This keeps the "normalized exactly once" guarantee
`McpOutputPipelineIT#shouldNormalizeAndValidateAStructuredResultOnce` pins on real emitted output: an
independent byte-counting probe ahead of an otherwise-unbounded conversion was evaluated and rejected
earlier, because a probe-then-convert shape would serialize the handler's raw value twice; reusing
`encodeCapped` as the sole serialization step avoids that by construction. The complete terminal
message is bounded the same way, by the same mechanism, when the normalized value is re-embedded into
the full envelope.

**The reparse has independent configured byte and token bounds.** `mcp.outputMaxBytes` stops the sole
raw-value serialization as bytes are produced and also becomes the decoder's document-length limit.
`mcp.outputMaxTokens` independently becomes that decoder's parser-token limit; it is not derived from
the byte cap, and no additional fixed node limit is layered beside it. Token exhaustion emits a
bounded JSON-RPC `-32603` response, no partial success and no `onToolOutput` observation, while the
terminal event is classified exactly as `McpErrorType.SERIALIZATION`.

Finite JSON numbers retain their canonical representation through the reparse: scale-sensitive
`BigDecimal` values and large finite decimals reach the wire unchanged. Java `Float`/`Double` NaN and
infinity values are rejected during the sole serialization pass rather than being converted into
quoted strings.

The output-value observation (`onToolOutput`) is published only after the terminal envelope has been
successfully encoded **and** the write has won logical settlement — never before. A capable session
can therefore never observe a structured value the wire cap, the output-schema check, or a competing
disconnect settlement would still suppress: both halves of the cap, the schema check, and the
settlement race always resolve before `publishToolOutput` is ever reached.
`McpOutputPipelineIT#shouldBoundNormalizationAndNotifyOnlyAfterBothChecks` proves this two ways — an
application value whose own size exceeds the cap aborts during normalization, well before the value's
full extent is visited, and a value that only exceeds the cap once fully enveloped is never published
either, isolating the observation-ordering guarantee from normalization boundedness.

A structured result that fails its own declared output schema never reaches the wire and never
reaches a session: it is rejected before the `onToolOutput` observation fires and before any response
byte is produced, settling as a bounded internal error (`McpErrorType.OUTPUT_VALIDATION`, JSON-RPC
`-32603`) through the same non-leaking degrade-to-id-less shape used elsewhere for a serialization or
handler failure — carrying no schema keyword, property, or value detail. Serialization and reparse
failures — byte or token exhaustion, a cyclic or non-finite value, or normalization recursion — use
the bounded internal wire response and terminal type `McpErrorType.SERIALIZATION`. Output-schema
failure remains `McpErrorType.OUTPUT_VALIDATION`; observer and other downstream callback failures
remain `McpErrorType.INTERNAL`. Every path settles rather than stranding the request with no response,
terminal, or completion.

The response write itself is bounded exactly like discovery and `tools/list` ([Bounded response
output](#bounded-response-output)): serialization streams to the same byte-counting sink that aborts
the moment the running count would exceed `mcp.outputMaxBytes`, so an over-cap structured result is
classified as `SERIALIZATION` before its full byte array is ever materialized, covering structured
content as well as discovery and listing payloads.

## Tool runtime

Tools reach the server as generated Dagger multibindings, never by scanning. Install the
`GeneratedMcpToolsModule` that `vertique-codegen-mcp` writes into the application's component, and
the server injects the resulting `McpToolInvoker` and `McpToolDescriptor` sets.

The generated registry is built **once**, during composition, from that contributed set. It is
ordered and immutable, and a duplicate tool name fails startup naming the duplicate rather than
producing a registry with one of the two tools silently dropped. Registry construction performs no
reflection over tool methods and no classpath scanning; a generated invoker calls its application
method directly.

`McpToolRuntimeFactory` is the single construction path for a generated tool's descriptor and
profile binding. It is a generated-runtime contract: application code neither calls nor implements
it, and `McpToolRuntime` has no public constructor. Per tool it resolves the effective profile and
returns an immutable binding that privately retains the exact stable mapper. Generated invokers
publish that binding only as an `McpStructuredOutputWriter`, allowing the dispatcher to stream a
structured value into its own capped destination without exposing or mutating the mapper. No profile
lookup happens on the request path.

### Immutable registry and startup validation

The contributed invoker set is composed into one immutable, global-name-ordered tool registry and
one compiled schema registry, both owned per deployed server Vert.x `Context` — one Dagger graph per
verticle instance in this framework's stateless multi-instance deployment model yields exactly that.
Composition fails before any route mounts for any of these:

- **a duplicate tool name** — two contributions publishing the same name;
- **an unsupported or uncompilable schema** — a descriptor whose input or output schema JSON-005
  cannot generate, or the compiled `vertx-json-schema` validator cannot accept;
- **a restricted registry with no configured authentication scheme** — when the server is enabled
  with no `mcp.authenticationScheme`, the endpoint establishes only a canonical anonymous identity, so
  every registered tool must be reachable without authentication (public or unreachable); a
  registered `@RolesAllowed`/`@RequiresAction` tool with no scheme configured fails the same way. An
  unconfigured registry containing only public and/or deny-all tools is allowed. This composition
  validator seam is independent of, and in addition to, the existing per-scheme optional-capability
  check (§4.5): a configured scheme whose selected `RouteAuthHandler.createOptionalHandler()`
  capability is absent still fails composition regardless of registry content.

Every one of these failures raises exactly one bounded startup error naming the offending
configuration key or tool. A registry with no contributed tools at all is not one of these failures
— composition still succeeds — but it is unconditionally logged as one WARN naming the mount path
and both likely causes: `GeneratedMcpToolsModule` not installed in the application's Dagger
component, or `vertique-codegen-mcp` absent from the annotation-processor path in a pre-facade,
off-parent setup. The published registry order never depends on contribution order, and the
registry exposes a stable digest — computed from the exact tool name and schema content of every
entry, in global name order — that the `tools/list` cursor codec binds to invalidate a stale cursor
across deployments (see
[Authorized tool listing and pagination](#authorized-tool-listing-and-pagination)). Neither the
registry nor the schema registry is mutated after composition; `tools/list` scans the registry
read-only on every request, and a known, authorized `tools/call` resolves through this same registry
(see [Zero-argument tool calls](#zero-argument-tool-calls)).

### Effective profile resolution

The effective tool-payload profile is resolved once per tool at composition time, in this order:

1. the tool method's `@JsonProfile`;
2. the declaring type's `@JsonProfile`;
3. the MCP boundary default `mcp.jsonProfile`;
4. the global default `json.jsonProfile`;
5. the `vertique` profile (issue #440).

The first two tiers are resolved at compile time by `vertique-codegen-mcp`, which rejects a blank
annotation value; the server resolves the remaining tail against the `JsonMapperProfileRegistry`. An
explicitly selected profile that is not registered fails composition, before the router is mounted.
The configured `mcp.jsonProfile` default is validated independently, even when MCP is disabled or
its mount is shadowed — so an invalid deployment configuration cannot lie dormant.

The final tail is the `vertique` profile, never the reserved `vertx` profile: `vertx`'s
`DatabindCodec`-backed mapper registers no `Jdk8Module`, so it cannot correctly materialize an
`Optional<T>` tool parameter, while `vertique` does. This tail is scoped to the fallback only — it
never outranks `json.jsonProfile`; an application that sets `json.jsonProfile` always gets its own
configured profile.

### JSON mapper safety is the profile's responsibility

A JSON profile exposes an application-owned `ObjectMapper`, and MCP binds a tool's payloads to
whichever profile it selects. Vertique does **not** statically inspect that mapper to prove it safe
for remote input — proving an arbitrary application `ObjectMapper` cannot deserialize an
attacker-chosen type is not a guarantee this framework (or any mainstream one) makes. Instead the
framework-shipped profiles are safe by default, and an application that supplies its own profile for
a remotely reachable tool owns keeping it safe.

The framework profiles (`vertx`, `vertique`, `vertique-strict`) ship with none of the unsafe
configurations below, so the zero-config path is safe. When you author your own profile for an MCP
tool, do **not** enable, on its mapper, any configuration that lets the wire choose the concrete
Java type to instantiate:

- **Jackson default typing** — `activateDefaultTyping(...)` / `enableDefaultTyping(...)` in any
  form. This is the single setting that turns a mapper into a polymorphic-deserialization (gadget)
  vector for every type; leave it off.
- **Open polymorphism** on any type reachable from a tool's arguments — `@JsonTypeInfo` with
  `Id.CLASS`, `Id.MINIMAL_CLASS`, `Id.CUSTOM`, `Id.DEDUCTION`, or a custom
  `@JsonTypeResolver`/`@JsonTypeIdResolver`. Prefer a closed `@JsonTypeInfo(use = Id.NAME)` with a
  finite, explicit `@JsonSubTypes` allowlist so the wire can only select a class you named.
- **`@JsonTypeInfo(defaultImpl = …)`, `addAbstractTypeMapping(…)`, or a
  `DeserializationProblemHandler`** that resolves an unknown or absent type id to a class outside
  that allowlist.

Custom serializers and deserializers on otherwise-supported types are ordinary trusted application
code and need no special treatment. If a tool has no application-supplied profile, it inherits a
framework profile and this section does not apply.

### Startup schema assembly and hardening

`McpToolRuntimeFactory.create(...)` consumes `vertique-json-schema`'s
`AnnotationJsonSchemaGenerator.forInputProfile(profile)` / `forOutputProfile(profile)` — never
`withVictoolsDefaults()` and never a directly configured Victools instance. For each distinct
effective profile used by a tool, the factory creates or reuses one generator per direction, never
rebuilding one per tool. Direction-appropriate Jackson introspection supplies the mapper's external
property names, including mapper-level naming strategies and mix-ins, so the published schema and
the mapper used at runtime describe the same wire shape.

The generated input schema is then hardened at the protocol argument-object boundary, document-driven
and never type-graph-driven:

- the root carrier object is closed unconditionally — including a zero-argument carrier — with
  `additionalProperties: false`, so a zero-arg tool rejects arbitrary arguments;
- a non-root object schema is closed the same way exactly when it declares a non-empty `properties`
  member and no sibling `$ref`; a property-less non-root object — a resolved `Map<K,V>` included —
  stays open and schema-unconstrained for values;
- `additionalProperties` is never placed beside a `$ref`, and a `$ref` is never dereferenced during
  hardening;
- the walk descends a fixed grammar — `properties`, `items`, `prefixItems`, `anyOf`, `oneOf`, `allOf`,
  `$defs` — so a closed polymorphic base is hardened by closing each `anyOf`/`oneOf` branch
  individually;
- each declared parameter's description is attached to its matching root-carrier property only, as a
  separate pass.

After hardening, the document is re-serialized deterministically (recursive UTF-16 key ordering,
compact encoding) through MCP's own package-private writer — never through JSON-005's canonicalizer,
which is package-private inside `vertique-json-schema`, and never through a profile's payload mapper.
A declared structured-output schema is published exactly as JSON-005 generates it: output is
server-produced and carries no argument-object boundary to close. None of this schema work happens on
the request path; it runs exactly once per tool, during composition.

Because `vertx-json-schema` publishes no cross-`Context` concurrency guarantee for a compiled
validator, one compiled, immutable validator set exists per deployed server Vert.x `Context` — one
Dagger graph per verticle instance in this framework's stateless multi-instance deployment model
yields exactly that. No validator instance is ever shared across two contexts, and no schema
compilation occurs on the request path.

### Mandatory input-processing binding

Every `McpServerModule` composition requires a direct, non-`Optional`
`dev.vertique.input.processing.InputObjectProcessor` binding — install a module that provides one
(for example `SanitizationModule`) alongside `McpServerModule`. This is enforced at Dagger
compile time, not merely at runtime: `McpInputProcessingCompositionValidator`, a package-private
`ComposeValidator` contributed unconditionally by `McpServerModule`, requires `InputObjectProcessor`
in its own `@Inject` constructor (the established *constructible-as-validation* pattern this module
already uses for `McpJsonProfileDefaultValidator`), so a composition that omits the binding fails
Dagger code generation before any route mounts — regardless of how many tools are registered or
whether any of them declares a canonicalization/sanitization policy. MCP never calls
`InputObjectProcessor.declaresPolicies(Type)` to weaken this into an optional, tools-dependent
requirement the way `vertique-rest-jaxrs` does; the binding is unconditional here.

This binding is consulted on the request path by [Request-time input
pipeline](#request-time-input-pipeline): stages 2–4 of that fixed order run inside a generated
invoker's `prepare(...)`, which calls `InputObjectProcessor.processInput(...)` exactly once per
call. Its startup purpose remains the fail-fast guarantee above regardless of what the currently
registered tool set declares, and it freezes the `McpPreparedToolCall` boundary
([Zero-argument tool calls](#zero-argument-tool-calls) describes the interface): the prepared call a
generated invoker's `prepare(...)` returns exposes exactly `normalizedArguments()` and `invoke()` —
the descriptor, cancellation signal, effective mapper, and generated input carrier are closure-captured
implementation detail, never a getter or any other public member.

### Optional Bean Validation `Validator` binding

`McpServerModule` declares `@BindsOptionalOf Validator` (R38/W7), so composition succeeds whether or
not the application's own Dagger graph binds a `jakarta.validation.Validator`. Every generated
invoker's constructor additionally accepts `Optional<Validator>` and threads it into stage 4 of the
[Request-time input pipeline](#request-time-input-pipeline) through
`McpBeanValidation.validate(carrier, validator)`: when the graph binds a `Validator` — for example an
application-composed, Dagger-aware one whose `ConstraintValidatorFactory` can resolve an
`@Inject`-only `ConstraintValidator` — tool-input Bean Validation runs through it; when the graph
binds none, behavior is byte-for-byte identical to `vertique-mcp-core`'s zero-config
`McpBeanValidation` default. This module adds `hibernate-validator` and `org.glassfish.expressly` as
runtime-scoped dependencies (not compile) so that zero-config default has a Jakarta Bean Validation
provider on the classpath of every MCP application, even one that composes no `Validator` binding of
its own — `vertique-mcp-core` itself declares only the Bean Validation API at compile scope.

### What is not here yet

This version composes the immutable tool and schema registries, the effective profile, the hardened
startup schema capability, fail-before-mount startup validation for the registry, the mandatory
input-processing composition binding described above, the bounded, authorized `tools/list` pagination
described below, the zero-argument authorized `tools/call` dispatch described in
[Zero-argument tool calls](#zero-argument-tool-calls), and the fixed request-time input pipeline for
parameterized calls described in
[Request-time input pipeline](#request-time-input-pipeline). What is deliberately still absent arrives
with its owning slice:

- **Opt-in value-observation input and output callbacks.** `onToolInput` and
  `onToolOutput` are each delivered, only to a capability-implementing session, through the [Value
  observation stage](#value-observation-stage). Both live interceptor stages exist: the pre-dispatch
  request-interceptor stage described in [Request interceptor stage](#request-interceptor-stage), and
  the post-validation tool-interceptor stage described in
  [Tool interceptor stage](#tool-interceptor-stage).
- **Single-pass bounded structured output.** A structured `McpToolResult` is
  normalized exactly once, validated against the tool's advertised output schema, and bounded at
  `mcp.outputMaxBytes` as bytes are produced — see [Bounded output
  pipeline](#bounded-output-pipeline). Rich (non-scalar-graph) result shapes beyond this remain a
  later slice.

## Authorized tool listing and pagination

`tools/list` scans the immutable registry (above) in global name order, starting from an absent
cursor (the beginning) or a validated cursor's anchor, and reauthorizes **every** candidate it
examines through the same `SecurityPolicyEnforcer`/`McpPolicyEnforcer` pair
[Authorization](#authorization) describes — never a cached or assumed result, and never both
`McpPolicyEnforcer#decide` and `#isVisible` for the same candidate, which would double-emit its
authorization event. Examination for one page stops at the first of:

- the page reaching `mcp.toolsPageSize` visible tools;
- examining `4 * mcp.toolsPageSize` candidates — the fixed fan-out bound that caps how many
  authorization evaluations (and, with a remote decision point, network round trips) an
  unauthenticated or narrowly-scoped `tools/list` scan can trigger;
- the registry being exhausted.

A page may therefore be underfilled or empty and still carry a `nextCursor` while candidates remain.
Only an ordinary authorization deny is filtered: a hidden `@DenyAll` or role-mismatched candidate
never appears, and the scan continues. A deny whose reason code is
`AuthzReasonCodes.INTERNAL_AUTHZ_ERROR` instead means authorization infrastructure could not decide;
the entire request fails with HTTP 500 and the bounded JSON-RPC `-32603` internal error. It returns
no partial tools, cache hint, or cursor, and records the terminal lifecycle outcome as
`McpErrorType.AUTHORIZATION`.

The listing has no aggregate deadline. Its only bounds are the existing per-decision authorization
timeout, the candidate-examination budget above, the shared `HttpConfig` liveness timeouts, and
request cancellation. A disconnect marks the request cancelled, prevents further candidate
evaluations, and ignores an in-flight decision when it later settles; it sends no late response. The
authorization SPI remains cooperative: MCP does not claim to forcibly cancel the in-flight operation.

The cursor is an unsigned, non-expiring, unpadded base64url encoding of canonical JSON with exactly
three fields, in order: `protocolVersion`, `registryDigest`, and `lastScannedToolName`. The anchor is
a bounded syntactically valid tool name and only a lexicographic resume-position hint: the next page
starts at the first registry name strictly greater than it. It need not be a current registry member,
so decoding does not expose tool-name membership. A forged valid anchor may skip entries for its
caller, but it cannot include an unauthorized tool because each examined candidate is reauthorized.
The registry digest invalidates stale cursors across deployments; there is no signature, expiry,
attempt count, sentinel, or retry state.

The decoder rejects an encoded token longer than the exact unpadded-base64 representation of its
2,048-byte budget before decoding, bounds decoded bytes again before parsing, and rejects an invalid
protocol version, digest, encoding, field set, or anchor syntax as the same bounded
`-32602`/`Invalid params` outcome used for unknown or denied tools. It exposes no rejection detail. A
`nextCursor` is emitted only after at least one candidate was examined and candidates remain.

Discovery advertises `capabilities.tools={}` for every enabled server, including when the immutable
registry or a caller's authorized view is empty. The capability declares that the tools operation
family is implemented; it does not disclose registry contents or bypass per-candidate authorization.
`listChanged` is absent because the registry is immutable, and no deferred capability family is
advertised.

Successful identity-filtered
`server/discover` and `tools/list` results carry `ttlMs` (`mcp.toolsTtlMs`), `cacheScope=private`,
`Cache-Control: private, no-store`, and `Vary: Authorization`; failed listing and cursor paths do
not present a cacheable partial page.

## Zero-argument tool calls

A known, authorized zero-argument `tools/call` resolves through the same immutable registry
`tools/list` scans, reauthorizes the resolved descriptor through the same
`McpPolicyEnforcer#decide`/`SecurityPolicyEnforcer` pair [Authorization](#authorization) describes —
never a cached or assumed result — and, only once permitted, invokes the resolved tool's generated
invoker directly: no reflection, no scanning, the same `McpToolInvoker#prepare`/
`McpPreparedToolCall#invoke` calls a Dagger-composed application would make.

**Unknown and denied are the same response, and neither reaches SSE.** A structurally missing/blank
tool name, an unresolved name, and a denied decision all settle through the exact same code path
`tools/list` uses for an invalid cursor: byte-identical `-32602`/`Invalid params` JSON, the same HTTP
status, no tool ever invoked. This holds regardless of which of the three causes produced it — an
unknown name and a `@DenyAll` tool are externally indistinguishable, matching the `tools/list`
guarantee above.

The bytes are identical, but the *path* to them used to differ: an unknown name resolved from a plain
registry-map lookup, while a denied name additionally traversed `McpPolicyEnforcer#decide`. With an
application-supplied asynchronous policy decision point, that gap is measurable and would let a caller
recover the same existence oracle the shared `-32602` response exists to close by timing the response
instead of reading it. An unresolved name is therefore evaluated against a synthetic, never-registered
`@DenyAll` placeholder descriptor through the identical decision point, so both paths carry the same
asynchronous latency shape. The terminal event recorded for an unresolved name always carries the
bounded `UNKNOWN` placeholder, never the caller-supplied string — an unresolved name touches no real
`McpToolDescriptor`, so nothing would otherwise bound it before it reached every lifecycle observer
and listener as internal telemetry except the wire's own very large string limit.

**SSE selection precedes invocation, unconditionally.** Only once a call is both known and
authorized does the dispatcher select request-scoped SSE (`Content-Type: text/event-stream`,
`X-Accel-Buffering: no`) — as the first step, before the generated invoker's `prepare(...)` is ever
called, and independently of how invocation later resolves. A synchronous `prepare`/`invoke` throw
and a failed invocation future both settle through the bounded pre-encoded internal-error fallback,
still SSE-framed: once SSE is selected the response never falls back to JSON. No response byte is
written until the complete SSE message — one `event: message` / `data:` block carrying the full
JSON-RPC response — is ready; header mutation alone reaches no byte onto the wire because Vert.x
defers sending them until the first `write`/`end`, and this dispatcher's only write is that one
complete, already-framed message.

At this point, `arguments` is either absent or an object: the unmodified official per-method schema
rejects explicit `null` and every other non-object as HTTP 400 JSON-RPC `-32602` *Invalid params*
before request interceptors, lookup, authorization, or SSE selection. An absent value normalizes to
an immutable empty map; a present object is converted to its map representation and then validated
against the tool's application schema in stage 1 of the
[Request-time input pipeline](#request-time-input-pipeline) below. For a zero-argument tool, absence
or `{}` satisfies the trivial empty-object schema. See
[Value observation stage](#value-observation-stage) for the
opt-in `onToolInput`/`onToolOutput` capability this version delivers, and [Bounded output
pipeline](#bounded-output-pipeline) for the output-side normalization, validation, and observation
order; cancellation and write-phase settlement are described in
[Cancellation and write-phase settlement](#cancellation-and-write-phase-settlement), and the
post-validation tool-interceptor stage is described in
[Tool interceptor stage](#tool-interceptor-stage).

## Request-time input pipeline

Every parameterized `tools/call` runs a fixed, fail-closed order before the
application handler ever runs:

1. **Schema validation.** `McpRequestDispatcher` validates `arguments` against the compiled `Validator`
   `McpSchemaRegistry` ([Tool runtime](#tool-runtime)) already compiled for this tool at composition —
   no schema is generated, hardened, or compiled on the request path. A rejection here means the
   generated invoker's `prepare(...)` is never called. This stage is also the legitimate request-time
   replacement for the withdrawn startup polymorphism check: an unknown polymorphic discriminator
   fails here, before invocation, exactly like an unknown property on a closed schema.
2. **INP-001 canonicalization and sanitization**, applied by the generated invoker's `prepare(...)` at
   `InputLocation.PAYLOAD` — never `InputLocation.BODY`, at every traversal depth. A custom
   `Canonicalizer`/`Sanitizer`/`CharacterPolicy` that branches on `InputLocation.BODY` silently covers
   nothing on MCP; there is no BODY compatibility mode.
3. **Materialization** through the effective JSON profile mapper — the same mapper
   [Tool runtime](#tool-runtime) resolved for this tool's schema.
4. **Bean Validation** on the materialized carrier.

A failure at any of the four stages yields the same bounded outcome: one text-only, `isError=true`
`CallToolResult` — never a JSON-RPC protocol error, and never a detail of which schema keyword,
policy, or constraint failed. Stage 1 failures are written directly by the dispatcher; a stage 2–4
failure is signalled by the generated invoker throwing the public
`dev.vertique.mcp.tool.McpInputRejectionException` (public, not package-private here, because
`prepare()` is generated into an arbitrary application package that cannot reach a package-private
type in this module), which the dispatcher maps to the identical bounded outcome — so a
caller cannot tell which of the four stages rejected a call from the response shape. Only after all
four stages succeed does the dispatcher deliver the [Value observation stage](#value-observation-stage)
`onToolInput` callback and then run the [Tool interceptor stage](#tool-interceptor-stage); only once
every tool interceptor permits does it call `invoke()`.

## Authorization

`tools/list` (see [Authorized tool listing and pagination](#authorized-tool-listing-and-pagination))
and the zero-argument `tools/call` above (see
[Zero-argument tool calls](#zero-argument-tool-calls)) are this mapping's two wire consumers, and
reuse it unchanged.

Each generated tool declares its access requirement — unannotated, `@PermitAll`, `@DenyAll`,
`@RolesAllowed`, `@RequiresAction`, or `@RolesAllowed` plus `@RequiresAction` — exactly as a REST
resource method does, and the server evaluates it through the same `SecurityPolicyEnforcer`
instance the REST authorization contributor composes. MCP adds no parallel authorization
architecture, no separate decision engine, and no separate policy model: it reuses the same
selected `AuthorizationDecisionPoint` and the same core `Authorizer` REST uses, supplying only its
own `ResourceRef("mcp-tool", <toolName>, {})` and `InvocationOrigin.of(DispatchBoundary.MCP)`. An
application `AuthorizationDecisionPoint`, `AuthorizationPolicy`, or `Authorizer` override therefore
applies to MCP tools as well as REST resources, and a custom implementation may legitimately return
a different decision per transport.

The mapping from a tool's declared access to its effective authorization result is frozen:

| Tool declaration | Coarse gate | Fine gate | Effective result |
|---|---|---|---|
| Unannotated or `@PermitAll` | None | None | Public to anonymous and authenticated callers; no decision event |
| `@DenyAll` | Static deny | None | Excluded from `tools/list`; a direct `tools/call` does not invoke it and returns the externally indistinguishable unknown-or-unauthorized `-32602` response |
| `@RolesAllowed` | Direct role claim check | None | Permitted when the authenticated caller has an allowed role |
| `@RequiresAction` | Authenticated caller required | Existing core `Authorizer` | Permitted when the role-to-policy-to-action decision permits |
| `@RolesAllowed` plus `@RequiresAction` | Direct role claim check | Existing core `Authorizer` | Permitted only when both gates permit |

Denial and absence are externally indistinguishable — an unknown tool name and a tool the caller may
not use both resolve to the same `-32602` response, with no detail identifying which — and a denied
tool is never invoked. Every restrictive evaluation emits exactly one combined
`AuthorizationDecisionEvent`.
