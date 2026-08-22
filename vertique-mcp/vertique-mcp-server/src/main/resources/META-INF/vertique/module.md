# Vertique MCP Server

> **Status:** Alpha
> **Package:** `dev.vertique.mcp.server`
> **Artifact:** `vertique-mcp-server`
> **Depends on:** `vertique-mcp-core`, `vertique-core`, `vertique-json`, `vertique-json-schema`,
> `vertique-rest-core`, `vertique-rest-security`

`vertique-mcp-server` composes the optional HTTP Model Context Protocol server. Include
`McpServerModule` explicitly in the application's Dagger component and supply an immutable
`McpServerConfig` binding. Generated tools are registered and bound to their effective JSON profile
during composition (see [Tool runtime](#tool-runtime)); the mount serves `server/discover`, the
bounded, authorized `tools/list` pagination described in
[Authorized tool listing and pagination](#authorized-tool-listing-and-pagination), and the
zero-argument authorized `tools/call` dispatch described in
[Zero-argument tool calls](#zero-argument-tool-calls), and the disconnect/reset/write-failure
cancellation and write-phase settlement described in
[Cancellation and write-phase settlement](#cancellation-and-write-phase-settlement). Parameterized-call
argument processing, tool interceptors, and rich structured output are introduced by their owning
slice.

Configuration is disabled by default. When enabled, `serverName` and `serverVersion` are required,
the mount is one literal path ending in `/*`, and every configured value is validated for range and
consistency at startup, before the router is mounted — so an out-of-range value fails composition
rather than a live request. JSON-RPC envelope parsing is bounded by Jackson's own frozen
`StreamReadConstraints` inside the private
[Bounded JSON-RPC envelope codec](#bounded-json-rpc-envelope-codec) — MCP owns JSON-RPC envelope
semantics, not a second general-purpose JSON resource-limit subsystem, and exposes no configuration
key for it. Request body size is enforced from `http.maxBodySize`, which also bounds the maximum
decodable envelope document length. A configured `jsonProfile` is validated during composition even
if MCP is disabled, preventing a latent invalid deployment configuration.

**Transport liveness is not provided out of the box.** MCP arms no whole-request deadline of its
own (T007 removed the earlier `mcp.requestTimeoutMs`); it relies entirely on the shared `HttpConfig`
idle/read/write timeouts to ever close a stalled or abandoned connection. Those three settings —
`http.idleTimeoutSeconds`, `http.readIdleTimeoutSeconds`, and `http.writeIdleTimeoutSeconds` — all
**default to `0`, which disables them**. A deployment that mounts MCP without setting at least one of
these has no liveness bound at all: a client that stops reading or writing mid-request can hold its
connection, and the MCP request lifecycle observation opened for it, open indefinitely. Set at least
one non-zero `HttpConfig` timeout for any MCP deployment.

**Upgrading past T007:** `mcp.requestTimeoutMs`, `mcp.jsonMaxDepth`, `mcp.jsonMaxPropertiesPerObject`,
`mcp.jsonMaxItemsPerArray`, and `mcp.jsonMaxStringChars` no longer exist. `McpServerConfig` ignores
unknown JSON properties, so a deployment config that still sets any of these five keys loads
successfully but the setting has **no effect** — it is silently dropped, not rejected. An operator who
had tightened any of them (most importantly `requestTimeoutMs`, MCP's only prior deadline) must move
the equivalent protection to `HttpConfig`'s idle/read/write timeouts above; the four JSON-shape limits
have no direct replacement key because they are now Jackson's own frozen `StreamReadConstraints`
inside the envelope codec (see [Bounded JSON-RPC envelope codec](#bounded-json-rpc-envelope-codec)),
not a configurable value.

## Stateless HTTP contract

The mount is stateless and multi-instance: it establishes no session, emits no cookie or affinity
header, and two independently deployed servers share nothing, so a load balancer may route any
request to any instance. Every request is admitted through the fixed pipeline before dispatch:

- **Method** — only `POST` is accepted. `GET`, `DELETE`, and any other method are HTTP `405`.
- **Origin** — a request that carries an `Origin` outside a non-empty `mcp.allowedOrigins` allowlist
  is rejected with HTTP `403` before dispatch. An empty allowlist (the default) imposes no origin
  restriction, and a request with no `Origin` header is never origin-rejected.
- **Content-Type** — a request that carries a `Content-Type` whose media type (parameters such as
  `; charset=utf-8` ignored) is not `application/json` is rejected with HTTP `415`. A request with no
  `Content-Type` header is never media-rejected (present-only, like `Origin`).
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
comes from the shared `HttpConfig` idle/read/write timeouts, so an idle or slow connection is closed
by the shared HTTP layer and reaches MCP through this same disconnect/reset settlement path,
classified as transport cancellation (`McpErrorType.TRANSPORT`) rather than a distinct timeout
outcome. `McpErrorType.TIMEOUT` has no producer in this module; it is retained in the frozen
lifecycle enum purely for enum stability, reserved for a future cross-transport server-operation
`@Timeout` capability. Observer `open`, callback, null-session, and retention failures are isolated
per observer and never change the protocol or business outcome. The method, `Origin`,
`Content-Type`, and `Accept` admission checks all run before the completion coordinator is created,
so — like a body-limit rejection — a request that fails admission produces no lifecycle observation;
only an admitted request opens observation.

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
this: transport liveness stays exclusively with the shared `HttpConfig` idle/read/write timeouts.

## Bounded response output

The response write is bounded by `mcp.output.maxBytes`: serialization streams through a byte-counting
writer that stops the moment the running count would exceed the cap, so an over-cap response is
classified as a bounded internal error and never emitted — the full over-cap byte array is never
materialized. Discovery responses are far below the default cap; the bound exists for the larger
structured outputs introduced by later slices.

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
- invalid UTF-8.

None of these bounds is a consumer-visible configuration key: the four generic JSON-limit properties
and the handcrafted strict JSON reader that used to enforce them were removed in the T007
architecture rebaseline in favor of Jackson's own bounded read constraints. MCP owns JSON-RPC
envelope semantics, not a second general-purpose JSON resource-limit subsystem, and exposes no
public parser API. Tool argument and result values continue to use the existing
`JsonMapperProfile`/`JsonMapperProfileRegistry` contract, unaffected by this codec.

Numeric values keep their exact lexical precision: a 64-bit-overflowing integer such as
`9007199254740993` and a decimal such as `0.10000000000000001` survive decode and canonical
re-encode without lossy `double` rounding, so downstream schema validation sees exactly what the
client sent. Canonical encoding is a compact, insertion-order-preserving re-encode; an
already-compact frame round-trips byte-for-byte. A decimal whose scale magnitude is far beyond any
legitimate value — the vector that would otherwise drive an out-of-memory plain-form encode — is
rejected against a fixed internal hardening bound (retained unchanged from the T003 hardening: a
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
found* (unknown method; original usable id). Header/body-mismatch
(`-32020`) and tool-level authorization (`-32602`) classification belong to the HTTP-contract and
tool-dispatch slices and are not part of this codec. An internal codec failure settles through a
pre-encoded `-32603` *Internal error* response that is written exactly once and never carries the
cause's text, so an internal exception message cannot leak to a client.

The mount handles no file uploads of its own, but it does not rely on that alone: an
application-composed ancestor `BodyHandler` with uploads enabled spools multipart parts to disk
before any MCP handler runs, so such files do exist for the duration of the request. The mount
deletes every request-scoped upload once the request settles — on completion, failure, or connection
reset — so an MCP request leaves no upload file behind after it ends.

The module does not use an MCP Java SDK. It depends on `vertique-mcp-core` for the stable lifecycle
boundary and owns the HTTP/router composition only.

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
returns an immutable binding that privately retains the exact stable mapper. No profile lookup
happens on the request path.

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
configuration key or tool. The published registry order never depends on contribution order, and the
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
5. the reserved `vertx` profile.

The first two tiers are resolved at compile time by `vertique-codegen-mcp`, which rejects a blank
annotation value; the server resolves the remaining tail against the `JsonMapperProfileRegistry`. An
explicitly selected profile that is not registered fails composition, before the router is mounted.
The configured `mcp.jsonProfile` default is validated independently, even when MCP is disabled or
its mount is shadowed — so an invalid deployment configuration cannot lie dormant.

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
rebuilding one per tool.

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

### What is not here yet

This version composes the immutable tool and schema registries, the effective profile, the hardened
startup schema capability, fail-before-mount startup validation for the registry, the bounded,
authorized `tools/list` pagination described below, and the zero-argument authorized `tools/call`
dispatch described in [Zero-argument tool calls](#zero-argument-tool-calls). What is deliberately
still absent arrives with its owning slice:

- **Parameterized tool calls.** The compiled schema registry (`McpSchemaRegistry`) is not yet
  consulted on the `tools/call` request path: argument-schema validation against the compiled
  validators, INP-001 input-policy application, typed parameter materialization, and Bean Validation
  are not wired into dispatch. A call to a tool that declares parameters is dispatched exactly like a
  zero-argument one — whatever `arguments` the request carries reaches the generated invoker's
  `prepare(...)` unvalidated — so only zero-argument tools are a supported, tested surface today.
- **Ordered tool interceptors and opt-in value-observation.** Neither exists yet; every known,
  authorized call reaches the generated invoker directly with no interceptor stage.
- **Rich and structured output.** A tool's `McpToolResult` is published as-is; output-schema
  validation and the single-pass bounded output normalization the frozen pipeline names are not yet
  applied beyond the existing `mcp.output.maxBytes` serialization cap shared with discovery and
  `tools/list`.

## Authorized tool listing and pagination

`tools/list` scans the immutable registry (above) in global name order, starting from an absent
cursor (the beginning) or a validated cursor's anchor, and reauthorizes **every** candidate it
examines through the same `SecurityPolicyEnforcer`/`McpPolicyEnforcer` pair
[Authorization](#authorization) describes — never a cached or assumed result, and never both
`McpPolicyEnforcer#decide` and `#isVisible` for the same candidate, which would double-emit its
authorization event. Examination for one page stops at the first of:

- the page reaching `mcp.tools.pageSize` visible tools;
- examining `4 * mcp.tools.pageSize` candidates — the fixed fan-out bound that caps how many
  authorization evaluations (and, with a remote decision point, network round trips) an
  unauthenticated or narrowly-scoped `tools/list` scan can trigger;
- the registry being exhausted.

A page may therefore be underfilled or empty and still carry a `nextCursor` while unexamined
candidates remain past the budget; only a scan that reaches the registry's end omits it. Only
tools the decision permits are returned — a hidden `@DenyAll` or role-mismatched candidate examined
within the scan window never appears in the page, even though it was authorized.

The cursor is unsigned, non-expiring, opaque base64url (no padding) JSON: the frozen protocol
version, the current registry digest, and the last global-name candidate examined (not merely the
last visible tool). It carries no signature, HMAC, or expiry member — tampering cannot bypass
authorization, since every candidate reached from a resumed cursor is reauthorized exactly like any
other, and the immutable registry digest (not a client-enforceable expiry) invalidates a cursor
across deployments. `McpCursorCodec` bounds the base64url-decoded byte length **before** any JSON
parsing is attempted, so an over-long or expensive-to-parse payload never reaches the parser. A
wrong protocol version, a stale digest, an anchor absent from the current registry, an over-long
payload, or a malformed one all collapse to the same indistinguishable outcome — no field of the
rejection reveals which check failed.

Both `server/discover` and `tools/list` carry the mandatory `ttlMs` (`mcp.tools.ttlMs`) and
`cacheScope=private` cache hints. An invalid cursor is rejected with the exact same externally
indistinguishable `-32602`/`Invalid params` response, and the same HTTP status, that a denied or
unknown tool produces (`McpPolicyEnforcer#unknownOrUnauthorizedError()` mapped through the one
shared `httpStatusFor` factory) — never a distinct code or status that would let a caller
distinguish "malformed cursor" from any other `-32602` cause.

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

**Zero-argument only.** `arguments` — absent, explicit `null`, or a non-object value — normalizes to
the same immutable empty map as `{}` and is handed to the generated invoker unvalidated: no schema
check, no INP-001 processing, no Bean Validation. See
[What is not here yet](#what-is-not-here-yet) for the parameterized-call, interceptor, and rich-output
capabilities this version does not yet provide; cancellation and write-phase settlement (T013) are
described in [Cancellation and write-phase settlement](#cancellation-and-write-phase-settlement).

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

