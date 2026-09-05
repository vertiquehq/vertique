# Vertique MCP Server

> **Status:** Stable
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
than zero.** `http.writeIdleTimeoutSeconds` does not qualify on its own: it fires
only while a write is actually in flight, so it cannot reclaim a connection that opens and then never
reads or writes again. Set at least one of the two qualifying `HttpConfig` timeouts for any MCP
deployment — the mount will not start otherwise.

**HTTP/2 residual.** The idle/read timers are connection-level: on a multiplexed HTTP/2
connection, traffic on any sibling stream resets them, so a hung request on such a connection is
**not** reclaimed by these timers (characterized against a real transport; an HTTP/1.1 connection
with the same hung request is reclaimed as documented). Until a per-request deadline exists,
deployments exposing the mount over HTTP/2 should bound streams at a fronting proxy
(per-stream/route timeouts) or restrict the mount to HTTP/1.1.

**Configuration keys are flat under `mcp`.** `McpServerConfig` is bound from the `mcp` section by
Jackson using the field names exactly as declared: `mcp.outputMaxBytes`, `mcp.ingressMaxTokens`,
`mcp.outputMaxTokens`, `mcp.toolsPageSize`, `mcp.toolsTtlMs`, `mcp.bodyTracePolicy`, and
`mcp.jsonProfile`, not dotted nested objects. There is no nested `tools`, `output`, or `json` object.
Ordinary unknown keys remain deliberately forward-compatible and are silently ignored, so use the
declared field names rather than dotted prose spellings.

### MCP tool rate-limit admission

MCP can bind generated tools to the shared rate-limit engine with `mcp.rateLimit`. Its complete
configuration surface is `mcp.rateLimit.defaultPolicy` (absent by default),
`mcp.rateLimit.subject` (defaults to `EFFECTIVE_PRINCIPAL`),
`mcp.rateLimit.anonymous` (defaults to `SHARED_BUCKET`), and the per-generated-tool entries
`mcp.rateLimit.tools.<tool>.policy`, `.subject`, `.anonymous`, and `.cost` (defaults to `1`). A
per-tool `subject` or `anonymous` inherits the corresponding parent value when omitted.

Policy selection uses the generated MCP tool name, never a Java method name or `@RateLimited`
annotation: `tools.<tool>.policy` wins, then `defaultPolicy`, then no admission. In a flat-key
source, bracket-quote a dotted generated tool name so it remains one key, for example
`mcp.rateLimit.tools.[weather.current].policy=mcp-weather`. JSON configuration already represents that dotted name as one object key, for example `"weather.current": { "policy": "mcp-weather" }`.

Ordinary unknown properties under `mcp.rateLimit` are ignored; numeric limits, windows, backend selection, and policy capacities remain shared `rateLimit.*` configuration. Reference the shared policy by name from `mcp.rateLimit` rather than duplicating its settings.

**`mcp.bodyTracePolicy` governs body-borne trace-reference extraction.** Enum
`McpBodyTracePolicy`, `IGNORE` (`@Builder.Default`) or `LINK` — mirroring Vert.x's own `TracingPolicy`
default-off posture. Under the default `IGNORE`, the request body's `params._meta.traceparent`/
`tracestate` fields are never parsed at all: no extraction, no diagnostic, no OpenTelemetry span
link, at zero cost. Set `LINK` only for a deployment behind a header-cleaning gateway or with
first-party callers; see [Body trace-context extraction](#body-trace-context-extraction) for the full
extraction and carriage mechanics.

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

## When To Use It

Install `vertique-mcp-server` when an application should expose an HTTP Model Context Protocol
mount. Include `McpServerModule` in the Dagger component, bind an immutable `McpServerConfig`, and
pair with `vertique-codegen-mcp` so `@McpTool` methods become invokers. Require a direct
`InputObjectProcessor` binding (for example via `SanitizationModule`) and, when the mount is
enabled, a qualifying shared HTTP idle or read timeout.

Omit this module when the process does not serve MCP. Tool annotations and lifecycle SPIs alone live
in `vertique-mcp-core` and do not open a listening endpoint.

---

## Conformance

This module implements exactly 10 of the 37 server-leg scored scenarios in the
upstream Model Context Protocol conformance suite's frozen `2026-07-28` requirement set —
`tools-list`, `tools-call-simple-text`, `tools-call-error`, the standard image/audio/embedded-resource
and mixed-content result shapes, request-scoped progress, `server-stateless`, and
`dns-rebinding-protection` — each run
to zero failures with no expected-failure baseline. The remaining 27 scored scenarios, and protocol
capabilities this module does not implement (tasks, subscriptions, resources, prompts, and result
extensions beyond those standard blocks), are out of scope entirely, not partially implemented. This
module claims conformance only to that scoped ten-scenario partition, never to the full requirement set.

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

A request rejected at admission on its first entry into the mount (HTTP `405`, `403`, `415`, `406`,
or the body-limit `413`) produces no MCP lifecycle event, and completes as an ordinary unclaimed
HTTP request with exactly one `HttpRequestCompletedEvent`.

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
`DISCONNECTED`/`RESET` completion. That abort terminal's `method`/`toolName` report the identity
already legitimately established at the point of settlement — the classified method as soon as
`server/discover`/`tools/list`/`tools/call` is recognized, and, for `tools/call`, the resolved tool
name once a real, registry-validated descriptor is found — falling back to `OTHER`/the bounded
`UNKNOWN` placeholder only when settlement lands before that fact was ever established. The
caller-supplied raw tool-name string is never retained for this purpose, matching the same
never-invent rule the unresolved-name rejection terminal below already follows. The abort terminal's
security snapshot is likewise only the identity MCP itself established: when no scheme is configured,
admit clears any ambient `SecurityRuntime` holder binding (alongside ambient Router user/evidence)
before identity resolution, so a foreign ROOT-middleware snapshot cannot appear on a pre-identity
settlement terminal. MCP arms no
whole-request timer of its own: transport liveness
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
so — like a body-limit rejection — a request that fails admission on its first entry into the mount
produces no lifecycle observation; only an admitted request opens observation.

**Completion events for MCP requests.** A request MCP settles — one it writes a response for, one it
rejects after its completion coordinator exists, one whose response already ended before MCP's
failure handler ran (ends-then-fails: settle without a second write), or one whose lost connection
it settles as disconnected or reset while the request is in the MCP mount — produces only MCP's own
lifecycle events: the observation's terminal and completion events, and the
`McpRequestCompletedListener` callback. MCP claims each such request, so rest-core's completion emitter produces neither a
`RestRequestCompletedEvent` nor an `HttpRequestCompletedEvent` for it, even when the request first
entered another mount and fell through to the MCP mount. REST listeners, and the
`vertique.rest.server.requests` metric, therefore never see those requests; applications observe
them through `McpRequestLifecycleObserver` and `McpRequestCompletedListener`. Failures taken by
another mount, and reroutes by an application `RouteAuthHandler`, work as follows:

- **Not settled by MCP** — a request MCP admits but does not settle completes as an ordinary
  rest-core request with one completion event, and MCP emits no terminal or completion for it. An
  authentication or identity failure taken by the failure handler of another mount that is ordered
  ahead of the MCP mount and matches the request's path produces one `HttpRequestCompletedEvent`. A
  request an application `RouteAuthHandler` reroutes out of the MCP mount, and that completes
  normally on its new target, produces that target's one rest-core completion event: a
  `RestRequestCompletedEvent` for a JAX-RS operation route, an `HttpRequestCompletedEvent`
  otherwise.
- **Rerouted out, then disconnected** — a request rerouted out of the MCP mount to a path no
  operation route matches, whose connection is lost before the response ends, is settled by MCP as
  disconnected or reset, and that MCP completion is its one completion event.
- **Rerouted back in** — a request an application `RouteAuthHandler` reroutes back into the MCP
  mount keeps the one lifecycle observation MCP opened for it; MCP opens no second observation. It
  gets exactly one MCP completion, whether it then completes normally, disconnects, or is reset,
  and rest-core emits no completion event for it. That completion carries the identity established
  on the request's last pass, even when the request authenticates only after the reroute. A request
  that is then rejected at re-admission, for example because the reroute changed its method, owns
  the first pass's coordinator and the writer claims it: it gets one MCP completion and no
  `HttpRequestCompletedEvent`.

**Known limitation: an observation MCP never settles stays open.** For a request MCP admitted but
does not settle (the failure taken by another mount, or the reroute out of the mount that completes
normally, above), MCP emits no terminal or completion, so the lifecycle observation it opened at
`begin`, when the request passed admission and before authentication, is never closed. Observers
therefore see that observation opened with no completion, and the in-flight gauge
`vertique.mcp.server.active` (`vertique-micrometer-mcp`) stays one higher for each such request.
This behavior predates the completion claim, which neither causes nor changes it. Nothing closes such
an abandoned observation today.

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
exactly once, in addition to the exactly-once terminal/completion settlement above. A handler can
call `cancellation.progressReporter().report(progress, total, message)` to emit standard
request-scoped `notifications/progress`; the reporter is a no-op without a client-supplied
`params._meta.progressToken`, and progress is never a partial result. Opaque progress tokens are
retained losslessly, bounded to 4,096 UTF-8 bytes, and are not narrowed to Java numeric primitives.
Progress frames and the terminal frame consume one shared `mcp.outputMaxBytes` response budget, so
repeated progress cannot amplify the response beyond the configured cap. Capacity for the bounded
terminal fallback is protected before progress starts; if the intended terminal frame cannot fit,
the server emits that bounded JSON-RPC `-32603` SSE fallback rather than ending the stream without a
terminal result. The completion
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
for the full output-stage order this cap is one part of. For SSE tool calls, the coordinator accounts
for every progress and terminal frame against the same request-scoped byte budget.

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
identical — fails negotiation rather than matching on the first observed value: a
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

**`mcp.bodyTracePolicy`.** `IGNORE` (`@Builder.Default`, the default) or `LINK`,
mirroring Vert.x's own `TracingPolicy` default-off posture. Under `IGNORE`,
`McpProtocolCodec#extractBodyTraceContext` is never invoked at all: no parsing, no validation, and no
diagnostic logging ever runs for `params._meta.traceparent`/`tracestate`, and
`McpRequestTerminalObservation#linkedTrace()` is always `null`. Only under `LINK` does the extraction
described below run.

`McpProtocolCodec#extractBodyTraceContext` reads this request's optional body
trace reference from `params._meta.traceparent` / `params._meta.tracestate` — the plain,
un-prefixed keys MCP 2026-07-28's `_meta` reserves for W3C trace-context propagation
(OpenTelemetry trace context), deliberately distinct from the `io.modelcontextprotocol/`-prefixed
negotiation keys `validateNegotiation` reads from the same `_meta` object: W3C trace propagation is
a Vertique-owned extension of `_meta`, not an official MCP protocol field. `baggage` is reserved
upstream too but has no consumer here and is never read.

`traceparent` must match the bounded W3C wire format `00-<32 lowercase hex trace id>-<16 lowercase
hex span id>-<2 hex flags>`; `tracestate`, when present, is bounded by
`dev.vertique.core.correlation.TraceReference`'s own rules (non-blank, at most 512 characters,
printable ASCII only) — the framework's single trace-reference type, replacing the deleted MCP-local
`McpTraceContext`. Extraction is total and never fails the request: an absent or non-string
`traceparent`, syntax that does not match the wire format above, an all-zero trace or span id
(rejected by the codec itself, since `TraceReference` carries no W3C hex-format opinion of its own),
or a `tracestate` outside those bounds all yield no body trace context rather than an error response,
each logged once as a bounded, non-leaking DEBUG diagnostic (never the raw `traceparent`/`tracestate`
value) — a client-triggerable event on this anonymous-reachable path, not a framework or application
contract violation, so it does not warrant WARN. When it runs, extraction happens at most once per
request, in `McpRequestDispatcher#dispatch`, independent of negotiation's own outcome — a malformed or
absent body trace reference never affects protocol admission. Every reference this codec produces is
stamped with source label `mcp._meta`.

The extracted, optional `TraceReference` no longer reaches `McpRequestContext` — that record carries no
body-trace component, and no interceptor ever consumed one. It reaches only the terminal observation
`McpCompletionCoordinator` publishes at settlement
(`McpRequestTerminalObservation#linkedTrace()`), captured once via a package-private
`bindLinkedTrace` call from `dispatch` immediately after extraction (or bound `null` outright when
the policy is `IGNORE`). That terminal-carried value is what the `vertique-opentelemetry-mcp`
adapter's `McpServerSpanObserver` reads to add at most one `Span#addLink` for a valid, distinct body
trace reference — a body reference identical to the HTTP `traceparent` header is meant to be a
self-reference and add no link; see that module's own `module.md` for the adapter's linking mechanics
and policy.

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
content as well as discovery and listing payloads. Progress and terminal SSE frames additionally share
the coordinator's request-scoped response budget, including the framing bytes and the protected
terminal-fallback capacity.

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
  registered `@RolesAllowed`/`@RequiresAction` tool with no scheme configured fails the same way, and
  so does a tool whose typed access policy requires a role, a scope, authentication or an action (the
  check reads the policy's requirements, never the closed placeholder the tool's descriptor
  publishes). An unconfigured registry containing only public and/or deny-all tools is allowed. This
  composition validator seam is independent of, and in addition to, the existing per-scheme
  optional-capability check (§4.5): a configured scheme whose selected
  `RouteAuthHandler.createOptionalHandler()` capability is absent still fails composition regardless
  of registry content;
- **an invalid typed access policy** — see [Typed access policies](#typed-access-policies), which
  also lists the action-engine checks an enabled mount applies to a typed action tool.

Every one of these failures raises exactly one bounded startup error naming the offending
configuration key or tool (and, for a typed policy, the policy type where one is known). A registry
with no contributed tools at all is not one of these failures
— composition still succeeds — but, when the mount is enabled (`mcp.enabled=true`), it is logged as
one WARN naming the mount path and both likely causes: `GeneratedMcpToolsModule` not installed in
the application's Dagger component, or `vertique-codegen-mcp` absent from the annotation-processor
path in a pre-facade, off-parent setup. A disabled mount (`mcp.enabled=false`, the default) never
installs any route and its empty registry is therefore never reachable, so this WARN does not fire
for it — an empty registry on an unconfigured, disabled MCP composition is silent, not a startup-log
false positive for the common "MCP not turned on" case. The published registry order never depends
on contribution order, and the
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

The final tail is the `vertique` profile, not the `system` profile installed as the process JSON
codec: MCP is a managed edge, and `vertique` is this framework's opinionated default for managed
edges, while `system` is deliberately unopinionated — the baseline every application in the process
shares as its installed codec. This tail is scoped to the fallback only — it never outranks
`json.jsonProfile`; an application that sets `json.jsonProfile`, including to `system`, always gets
its own configured profile.

The resolved profile governs argument-object *binding* only (a `convertValue` over the already
decoded argument tree); it never governs envelope byte-level parsing, which the MCP envelope codec
performs with its own fixed factory, without comment leniency, regardless of the selected profile.

### JSON mapper safety is the profile's responsibility

A JSON profile exposes an application-owned `ObjectMapper`, and MCP binds a tool's payloads to
whichever profile it selects. Vertique does **not** statically inspect that mapper to prove it safe
for remote input — proving an arbitrary application `ObjectMapper` cannot deserialize an
attacker-chosen type is not a guarantee this framework (or any mainstream one) makes. Instead the
framework-shipped profiles are safe by default, and an application that supplies its own profile for
a remotely reachable tool owns keeping it safe.

The framework profiles (`system`, `vertique`, `vertique-strict`) ship with none of the unsafe
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
  member, no sibling `$ref`, and no `additionalProperties` member of its own; a property-less non-root
  object — a resolved `Map<K,V>` included — is never closed this way: the hardener never treats a
  `Map`'s own absence of a `properties` member as under-description. On the input direction a resolved `Map<K,V>` already carries its own
  `additionalProperties` — `V`'s own schema,
  including a type-use constraint declared on it, or an open schema for an unconstrained `V` — which
  the next rule below respects and never overwrites, exactly like a `@JsonAnySetter` type's own extras;

- an `additionalProperties` the generated document already declares — a value schema, `true`, or
  `false` — is never overwritten, so a type whose extra keys the document publishes (a
  `@JsonAnySetter` type, or a type an application profile fragment describes) keeps accepting those
  keys at the protocol boundary, constrained to the declared value type;
- `additionalProperties` is never placed beside a `$ref`, and a `$ref` is never dereferenced during
  hardening;
- the walk descends a fixed grammar — `properties`, `items`, `additionalProperties`, `prefixItems`,
  `anyOf`, `oneOf`, `allOf`, `$defs` — so a closed polymorphic base is hardened by closing each
  `anyOf`/`oneOf` branch individually, and a declared extras value type is closed like any other
  subschema, so an unknown key inside an extra's object value is rejected at the boundary too;
- each declared parameter's description is attached to its matching root-carrier property only, as a
  separate pass.

Because a non-root object schema carrying a non-empty `properties` member is closed on its own,
closing each `allOf` sibling separately would reject a property published only on another sibling —
the shape Victools leaves for a polymorphic subtype whose discriminator is also a declared property,
or for a `@JsonUnwrapped` child's own leftover `allOf` part. `vertique-json-schema`'s input direction
folds such an unconsolidated `allOf` into one flat `properties` set before this hardening ever runs
(see that module's document, "How an unconsolidated allOf is folded"), so an `inputSchema` this
factory publishes carries a plain, foldable `allOf` part for the hardener to close in isolation only
when the fold was refused (a `type` or `$ref` conflict, or an unsound new key), when it had no
eligible target to begin with, or when it is a plain discriminator part sitting beside a sibling
that carries its own `$ref` — for example `allOf: [{$ref}, {properties: {kind: {const}}}]`, which
arises when the subtype's own schema is a `$defs` reference because the subtype is also used
directly as a field. The `$ref`-carrying sibling is never itself a fold target, so the discriminator
part becomes its own target and nothing folds into it. A part carrying its own `$ref` or its own
`additionalProperties` is never closed by the hardener at all, in isolation or otherwise — the
closure guard's own preconditions above already exclude it, independently of whether
`vertique-json-schema` folded anything. The parts this hardening still closes individually are
exactly three: a refused plain part, a targetless plain part, and a plain discriminator part left
beside an unfoldable `$ref` sibling, each closed exactly as described above.

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

**A published `inputSchema`'s `pattern` keyword may embed an inline ECMA-262 modifier group** (for
example `(?i:...)`) when a constrained member carries a `@Pattern` flag such as
`CASE_INSENSITIVE` — see `vertique-json-schema`'s module document, "Rendering". This server's own
`vertx-json-schema`-backed validator honors that inline form (measured), and it stays authoritative
for every tool call regardless of what an external client does with the published document. A client
that independently validates a tool call's arguments against the published `inputSchema` with its own
ECMA-262 engine may not support an inline modifier group — support for this construct is not
universal across JSON Schema validator implementations — so such a client should not treat its own
pre-flight `pattern` check as a substitute for the server's own validation; only the server's
validation determines whether a call is accepted.

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

`McpServerModule` declares `@BindsOptionalOf Validator`, so composition succeeds whether or
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
- **Complete bounded tool output.** A structured `McpToolResult` is
  normalized exactly once, validated against the tool's advertised output schema, and bounded at
  `mcp.outputMaxBytes` as bytes are produced — see [Bounded output
  pipeline](#bounded-output-pipeline). Standard text, image, audio, resource-link, and embedded-resource
  blocks are also supported in one ordered complete result. Resource-link and embedded-resource URIs
  must be syntactically valid absolute URIs; extensions beyond those blocks remain a later slice.

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
three fields, in order: `protocolVersion`, `registryDigest`, and `lastScannedToolName`. That field
carries one of two mutually exclusive, syntactically disjoint forms: a bounded syntactically
valid tool name, or an opaque `"#<index>"` scan-position anchor — disjoint because the tool-name
grammar never contains `#`.

- **Name form** — used when a page fills by reaching `mcp.toolsPageSize` or the registry is
  exhausted. It names the last emitted, permitted candidate and is only a lexicographic
  resume-position hint: the next page starts at the first registry name strictly greater than it. It
  need not be a current registry member, so decoding does not expose tool-name membership.
- **Position form** — used only when a page's examination budget exhausts before the page fills. It
  carries the index, in the digest-pinned registry order, of the *next unexamined* candidate — never
  the last examined one — so a denied candidate examined right at the budget boundary is never named
  in the cursor. The next page resumes the scan directly at that index.

A forged valid anchor, of either form, may skip entries for its caller, but it cannot include an
unauthorized tool because each examined candidate is reauthorized. The registry digest invalidates
stale cursors across deployments, including binding a position-form anchor to the exact registry
order it was computed against; there is no signature, expiry, attempt count, sentinel, or retry
state.

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
registry-map lookup, while a denied name additionally traversed `McpPolicyEnforcer#decide`. An
unresolved name is therefore evaluated against a synthetic, never-registered `@DenyAll` placeholder
descriptor through the identical decision point, so it is timing-indistinguishable from a real
`@DenyAll` tool: both settle synchronously inside the shared `SecurityPolicyEnforcer`, with no
authorization decision point round trip.

**Threat model, stated honestly.** That synchronous-settlement guarantee does not extend to every
denial shape. A `RESTRICTED` tool's denial resolves through the same enforcer but may reach an
application-supplied, genuinely asynchronous `AuthorizationDecisionPoint` — including a remote one —
whose latency an unknown name's synthetic `@DenyAll` evaluation never pays. A caller with a timing
side channel may therefore distinguish a denied `RESTRICTED` tool name from an unknown name, even
though the response bytes remain identical. Closing that residual gap would require deliberately
padding the fast path's latency to match the slowest configured decision point — a designed
latency-padding feature, not a documentation fix — and is tracked as an accepted residual risk
(issue #420) rather than claimed as delivered here. The terminal event recorded for an unresolved
name always carries the bounded `UNKNOWN` placeholder, never the caller-supplied string — an
unresolved name touches no real
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

**Known limit — no pattern-input bound.** The `Validator` this stage runs is compiled straight
from the tool's generated schema, with none of `vertique-rest-validation`'s pattern-input guard: a
`pattern`, a `patternProperties` key, or a bounded format position (`idn-hostname`, `idn-email`,
`regex`) in a tool's input schema is judged by vertx-json-schema's own expression with no
per-string or per-request length bound (see `vertique-rest-validation`'s own `module.md`, "Pattern
and bounded-format input is bounded"). A long value at such a position therefore reaches that
expression unchecked. Until this stage adopts an equivalent bound, keep a tool's pattern- or
format-bearing input arguments small, or constrain them with `maxLength`.

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

**Security: an unannotated tool is public.** A tool method carrying no authorization annotation — no
`@PermitAll`, `@DenyAll`, `@RolesAllowed`, or `@RequiresAction` — is permitted to every anonymous and
authenticated caller, exactly as an unannotated REST resource method is. The absence of an annotation
is not a safe default for a side-effecting tool; annotate it explicitly with the access requirement
it needs.

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

### Typed access policies

A tool can reference a typed access policy instead of inline security annotations. The policy's
direct requirements — `@PermitAll`, `@DenyAll`, `@RolesAllowed`, `@Authorized` (authentication and
scopes) and `@RequiresAction` — apply to the tool exactly as the same annotations do on a REST
resource method, and a policy can combine roles, scopes and one action. Authoring the policy and the
reference is described by `vertique-codegen-mcp`; this section covers what the server does with it.

**One policy, three consumers.** The registry reads each invoker's `McpToolInvoker#accessPolicy()`
hook exactly once, while it registers the invoker, and retains the policy's validated requirements
beside the descriptor. Startup validation, `tools/list` and `tools/call` all use that retained list;
none of them re-reads the hook or re-resolves the policy per request, and nothing caches a decision.

- **Discovery.** `tools/list` decides every candidate against the typed requirements and hides a tool
  the caller may not use, exactly as it does for descriptor-governed tools. Every restrictive
  evaluation emits one `AuthorizationDecisionEvent` with the MCP tool resource and the MCP origin.
- **Invocation.** `tools/call` decides again with the same requirements. A listing is never a grant:
  a caller whose claims changed since the listing is refused. An unknown tool and a denied tool still
  produce the identical `-32602` response, and a denied tool is never invoked.
- **Mapping.** A public policy runs without a decision and emits no event; a deny policy refuses every
  caller; role, scope and authentication requirements map as they do for REST; a policy that is only
  an action maps to "authenticated caller required" plus that action, the same as a `@RequiresAction`
  tool. The action runs through the one installed core `Authorizer`.
- **Service calls.** Permitting the tool does not permit what it calls. When the tool dispatches into a
  service whose own operation declares a policy, that service gate decides separately and its denial
  stops the effect even though the tool was listed and permitted.

**Startup rules.** The registry refuses to build, naming the tool, when the hook throws or returns
`null`, and naming the tool and the policy type when the policy is malformed, empty or conflicting, or
when an action in it does not parse. A hook that fails with a linkage error, such as a missing class,
is refused the same way. A hand-written invoker that declares a typed policy must publish the closed descriptor access
(`DENY_ALL`, no roles, no action); one that publishes anything else is refused, because a runtime
that reads only the descriptor would enforce that access instead of the policy. The registry builds
whether or not the mount is enabled. A tool with no hook is untouched: its descriptor access governs
it, and a legacy `DENY_ALL` tool with no hook stays denied.

An enabled mount then checks each typed tool's effective requirements:

| Typed tool requires | Needs |
|---|---|
| Public or deny | Nothing |
| Role, scope or authentication | A configured `mcp.authenticationScheme` |
| An action | A scheme, an installed `Authorizer`, an `ActionRegistry`, and an action the registry contains |
| Roles or scopes only | Neither an `Authorizer` nor an `ActionRegistry` |

A typed action tool fails mounting with a distinct message, naming the tool, for an absent
`Authorizer`, an absent `ActionRegistry`, and an action the registry does not contain. A disabled
mount stays inert: none of these checks run. `McpServerModule` declares `ActionRegistry` as an
optional binding, so an application that never installs the authorization engine still composes as
long as it has no typed action tool; include `SecurityAuthzModule` to provide the registry and the
`Authorizer`. A legacy inline `@RequiresAction` tool keeps its existing check, which needs only an
installed `Authorizer`.

**Older runtimes.** A typed invoker's descriptor publishes `DENY_ALL`. A server that predates the
hook reads only that descriptor, so it hides the tool from `tools/list` and refuses it at
`tools/call`; nothing runs. This is a fail-closed fallback for a mixed deployment, not support for
running typed tools on an older server. An invoker compiled before the hook existed links and
behaves exactly as before.

Only direct, runtime-retained requirements count. A requirement reached through a composed or
non-runtime security annotation is unsupported and is never enforced as a requirement.

## Rate-limit admission, origin, and resilience

This section describes the final cross-cutting behavior of the MCP server. The
configuration baseline above is the source for the record shape; this section
documents how the configured admission stage, trusted origin, and opt-in AOP
resilience interact at runtime.

### Rate-limit configuration and precedence

MCP uses the shared `vertique-rate-limit-core` engine. It does not define quota
math, windows, backend selection, capacity, or a second policy registry. The
available keys are:

| Key | Default or requirement | Meaning |
| --- | --- | --- |
| `mcp.rateLimit.defaultPolicy` | absent | Names the default shared policy; absent means no default admission. |
| `mcp.rateLimit.subject` | `EFFECTIVE_PRINCIPAL` | Selects the shared subject mode, including `IP`, `ACTOR_OR_IP`, and `CLIENT_OR_IP`. |
| `mcp.rateLimit.anonymous` | `SHARED_BUCKET` | Selects `SHARED_BUCKET` or `BYPASS` for an unauthenticated caller. |
| `mcp.rateLimit.tools.<tool>.policy` | required in an entry | Names a policy for the generated MCP tool name. |
| `mcp.rateLimit.tools.<tool>.subject` | inherits the parent | Overrides the parent subject for one generated tool. |
| `mcp.rateLimit.tools.<tool>.anonymous` | inherits the parent | Overrides the parent anonymous policy for one generated tool. |
| `mcp.rateLimit.tools.<tool>.cost` | `1`, must be at least `1` | The shared-engine acquisition cost. |

The precedence chain is **tools → default → none**: a matching
`tools.<tool>.policy` wins, then `defaultPolicy`, and otherwise no MCP admission
occurs. The tool key is the generated MCP tool name, never the Java method name.
In a flat-key source, bracket-quote a dotted generated name so its dots remain
part of the key:

```properties
mcp.rateLimit.tools.[weather.current].policy=mcp-weather
```

In JSON, the dotted name remains an ordinary object key:

```json
{"mcp":{"rateLimit":{"tools":{"weather.current":{"policy":"mcp-weather"}}}}}
```

Unknown properties under `mcp.rateLimit.*` are ignored, not rejected, because
the injected `ConfigParser` is lenient. Put policy capacity, refill, failure
mode, and backend settings under the shared `rateLimit.*` configuration; do not
duplicate them under `mcp.rateLimit.*`.

The admission plan is built and validated during Dagger composition, before the
MCP route mounts. These four conditions fail composition with a named
`ConfigurationException`:

| Condition | Result |
| --- | --- |
| A configured tool entry names a tool absent from the generated registry | Configuration failure naming `mcp.rateLimit.tools[<tool>]`. |
| A referenced default or tool policy cannot be resolved by the shared adapter | Configuration failure naming the referenced policy. |
| A policy is referenced while `RateLimitCoreModule`/`RateLimiters` is absent | Configuration failure naming `mcp.rateLimit` and the required core module. |
| An effective tool cost exceeds the resolved policy handle's capacity | Configuration failure naming the tool cost, policy, configured cost, and capacity. |

An enabled/disabled shared engine or policy is not a composition failure; it
produces the runtime `DISABLED` outcome. The runtime outcome mapping is:

| `RateLimitOutcome` or condition | HTTP result | JSON-RPC | MCP terminal result |
| --- | --- | --- | --- |
| `PERMITTED` | continue to the handler | — | request proceeds |
| `QUOTA_EXCEEDED` | `429`, `Retry-After` when supplied, `Cache-Control: no-store` | `-32022` | `REJECTED` / `RATE_LIMIT` |
| `DISABLED` | continue to the handler | — | request proceeds |
| `BACKEND_FAILURE_OPEN` | continue to the handler | — | request proceeds |
| `BACKEND_FAILURE_CLOSED` | `503`, `Cache-Control: no-store` | `-32022` | `REJECTED` / `RATE_LIMIT` |
| synchronous key-derivation throw, failed acquire future, or null decision | `503`, `Cache-Control: no-store` | `-32022` | `FAILED` / `RATE_LIMIT` |

Only `PERMITTED` consumes a token. Unknown and unauthorized tools are rejected
before admission and never consume one. Fixed responses do not disclose policy
names, keys, backend details, or exception text.

`@RateLimited` carries no inline rule or inline numerics; MCP configuration names policies; the
MCP admission precedence is tools → default → none. A method carrying
`@RateLimited` is a separate band-300 AOP aspect and is unrelated to this
configuration-bound MCP admission stage. Binding the same policy through both
paths creates intentional double-charge behavior: one MCP call consumes once in the admission
stage and once in the generated `@RateLimited` aspect. There is no runtime
deduplication. See the [`vertique-aop` module reference](../../../../../../../vertique-aop/src/main/resources/META-INF/vertique/module.md)
for the single authoritative ordering registry; this page does not restate its
bands.

`SHARED_BUCKET` is aggregate anonymous protection, not per-caller isolation:
one high-volume anonymous caller can exhaust the policy for every other
anonymous caller. An application that binds a custom
`RateLimitSubjectResolver` is also responsible for keeping the identity it
resolves congruent with the identity MCP authorization established. The shipped
default resolver reads the same `SecurityContext`; the framework cannot verify
that a custom resolver preserves that relationship.

For caller-separated unauthenticated protection before MCP identity exists, use
the REST edge limiter's trusted `IP` dimension ahead of the MCP mount. The REST
edge limiter is a separate adapter and its denial on a non-JAX-RS MCP mount has
the documented limitation that Vert.x renders its plain-text failure body rather
than an MCP JSON-RPC error. This page does not claim an MCP mount failure
handler for that case.

### Trusted request origin and subjects

The enabled MCP mount reuses the common trusted-proxy-aware
`RequestOriginCapturer`. It captures one immutable `RequestOrigin` after upload
cleanup registration and before cheap admission, `BodyHandler`, authentication,
authorization, or dispatch. The same value is placed under
`RequestOrigin.class.getName()` for identity resolution and credential-rejection
reporting. A terminal event carries that origin independently, so an
authentication rejection retains origin even when its `security` snapshot is
null. When security is present, the terminal origin and `security.origin()` are
the same captured value.

The HTTP request `Origin` header, raw forwarded headers, direct peer values
outside the capturer, request-body fields, MCP responses, metrics, and spans are
not origin sources. Origin is lifecycle and audit metadata only.

The shared runtime owns the origin-aware subject modes; MCP adds no local enum,
parser, resolver, or key framing:

- `IP` uses trusted `RequestOrigin.clientIp()`.
- `ACTOR_OR_IP` uses the authenticated actor and falls back to the trusted client
  IP for a canonical anonymous caller.
- `CLIENT_OR_IP` uses the client facet when present and otherwise falls back to
  the trusted client IP.

`SecurityIdentity.anonymous()` and an empty resolver result are normalized by the
shared adapter to the same anonymous state before `SHARED_BUCKET` or `BYPASS` is
applied. A valid JWT or other configured authentication result remains
authenticated and subject-keyed. Strict `CLIENT` remains fail-closed when an
authenticated identity has no client facet; missing origin for an origin-aware
mode is `SUBJECT_UNRESOLVABLE`, not a raw-header or socket fallback.

### Migration from the never-shipped MCP-001 fields

MCP-001 never shipped its rate-limit fields, so there is no runtime migration or
compatibility shim. These names are documented only for readers of earlier
drafts:

| Never-shipped MCP-001 field | Nearest shipped equivalent |
| --- | --- |
| `maxRequestsPerWindow` | `rateLimit.policies.<name>.algorithm.capacity` |
| `windowMs` | `rateLimit.policies.<name>.algorithm.refill.*` (greedy or interval refill) |
| `maxTrackedPrincipals` | `rateLimit.local.maxTrackedKeys` or `rateLimit.policies.<name>.local.maxTrackedKeys` |

The `McpRequestTerminalEvent.origin` component is likewise an intentional
pre-release public-shape change; no legacy constructor overload is provided.

### Opt-in AOP resilience for MCP tools

MCP resilience is supplied by AOP at the application bean boundary. A public
asynchronous method may carry `@McpTool`, `@Resilient`, and passive declarations
such as `@Timeout`. The application must include the resilience annotation
processor, `ResilienceAopModule` from `vertique-resilience`, and the generated
AOP module. The generated MCP invoker then receives the Dagger-provided AOP
proxy, so its direct method call enters the existing `ResiliencePipeline`.
MCP codegen does not inspect or copy resilience annotations, and the MCP server
does not create an MCP resilience engine, policy namespace, breaker, bulkhead, or
automatic retry behavior.

The resilience boundary starts at the handler invocation, after authorization,
capability checks, input validation/materialization, value observation, and tool
interceptors. A retry repeats only the handler method. `McpTool.idempotentHint`
is informational and does not make retry safe or enable retry. The application
author must explicitly declare retry and own replay safety for side-effecting
tools.

Handler-attempt timeout is not transport liveness. MCP does not create a
whole-request deadline; deployments must configure the shared HTTP idle/read
timeouts, and HTTP/2 stream liveness may still require a fronting proxy. A
handler that accepts `McpCancellationSignal` receives cooperative cancellation,
but the common resilience pipeline does not forcibly cancel an upstream future or
its scheduler. MCP settlement still fences late results after disconnect.

The bounded handler-failure mapping is:

| Cause | Writable HTTP result | JSON-RPC | Terminal classification |
| --- | --- | --- | --- |
| `ResilienceTimeoutException` | `504`, `Cache-Control: no-store` | `-32603`, `Request timed out` | `FAILED` / `TIMEOUT` |
| `ResilienceUnavailableException` | `503`, `Cache-Control: no-store` | `-32603`, `Service unavailable` | `FAILED` / `INTERNAL` |
| `ResiliencePolicyException` or any other cause | existing `500` fallback | existing `-32603`, `Internal error` | existing `FAILED` / `INTERNAL` |

The cause walk is bounded to eight hops and guarded against cycles. Once SSE
headers are committed, HTTP status and headers cannot change; only the bounded
JSON-RPC error payload can carry the selected classification. Operation keys,
policy names, exception text, and resilience-specific protocol codes never cross
the MCP boundary.

---

## Key Classes

### `McpServerModule`

Dagger `@Module` that composes the optional MCP HTTP server: multibinds for lifecycle observers,
completion listeners, request/tool interceptors, and generated tool invokers; optional `Validator`
and `ActionRegistry`; compose validators; tool registry; router mount and related wiring. Install it
explicitly — MCP is never auto-mounted.

### `McpServerConfig`

Immutable configuration deserialized from the flat `mcp` section. Closed type: fields and defaults
are the configuration contract. An enabled mount validates required server identity, mount path,
bounds, and authentication/authorization prerequisites before routes are installed.

### `McpBodyTracePolicy`

Enum for `mcp.bodyTracePolicy`: `IGNORE` (default) or `LINK`. Controls whether body-borne
`params._meta.traceparent` / `tracestate` are extracted for optional OpenTelemetry linking.

---

## Module Dagger Bindings

| Binding | Kind | What it is |
|---|---|---|
| `Set<McpRequestLifecycleObserver>` | `@Multibinds` | Neutral per-request observation |
| `Set<McpRequestCompletedListener>` | `@Multibinds` | Post-transport completion callbacks |
| `Set<McpRequestInterceptor>` | `@Multibinds` | Ordered pre-dispatch rejective interceptors |
| `Set<McpToolInterceptor>` | `@Multibinds` | Ordered post-validation rejective interceptors |
| `Set<McpToolInvoker>` | `@Multibinds` | Generated (or hand-written) tool invokers |
| `Validator` | `@BindsOptionalOf` | Optional Bean Validation for generated `prepare` |
| `ActionRegistry` | `@BindsOptionalOf` | Optional action catalogue for typed-policy tools |
| `McpToolRegistry` | `@Provides` | Immutable registry built from invokers |
| `ComposeValidator` | `@IntoSet` (several) | Profile default + input-processing composition guards |
| `RouterMount` / auth wiring | `@Provides` / `@IntoSet` | HTTP mount and optional scheme identity path |

Applications contribute observers, listeners, and interceptors into the multibinds above. Generated
tools arrive through `GeneratedMcpToolsModule` from `vertique-codegen-mcp`.

---

## Configuration

Keys are flat under `mcp` and map to `McpServerConfig` field names. Ordinary unknown keys are
ignored. Former implementation-era keys (`mcp.requestTimeoutMs`, `mcp.jsonMaxDepth`, …) are unknown
keys, not rejected.

| Key | Default | Notes |
|---|---|---|
| `mcp.enabled` | `false` | Mount installed only when `true` |
| `mcp.mountPath` | `/mcp/*` | One literal path ending in `/*` |
| `mcp.serverName` / `mcp.serverVersion` | — | Required non-blank when enabled |
| `mcp.instructions` | absent | Optional server instructions |
| `mcp.authenticationScheme` | absent | Optional `RouteAuthHandler` scheme name |
| `mcp.jsonProfile` | absent | MCP boundary default profile id (validated even when disabled) |
| `mcp.allowedOrigins` | empty | DNS-rebinding allowlist; empty denies mismatched Origin |
| `mcp.outputMaxBytes` | `2097152` | Shared response/output byte cap |
| `mcp.ingressMaxTokens` | `65536` | Ingress JSON-RPC parser-token budget (1024–262144) |
| `mcp.outputMaxTokens` | `65536` | Structured-output reparse token budget (1024–262144) |
| `mcp.toolsPageSize` | `100` | `tools/list` page size |
| `mcp.toolsTtlMs` | `300000` | Client cache-freshness hint |
| `mcp.bodyTracePolicy` | `IGNORE` | `IGNORE` or `LINK` |

An enabled mount also requires `http.idleTimeoutSeconds` or `http.readIdleTimeoutSeconds` > 0.

---

## Dependencies

| Artifact | Why |
|---|---|
| `vertique-mcp-core` | Tool/lifecycle/interceptor contracts |
| `vertique-core` | Correlation, JSON profiles, compose validation, extension ordering |
| `vertique-input-processing` | Required `InputObjectProcessor` for tool argument pipelines |
| `vertique-json` / `vertique-json-schema` | Profile mappers and tool schema generation |
| `vertique-rest-core` | HTTP config, router mounts, route auth handler SPI |
| `vertique-rest-security` | Identity resolution middleware for optional schemes |

Runtime Bean Validation providers and observability adapters are separate artifacts.

