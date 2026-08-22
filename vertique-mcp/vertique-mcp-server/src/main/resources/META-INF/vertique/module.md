# Vertique MCP Server

> **Status:** Alpha
> **Package:** `dev.vertique.mcp.server`
> **Artifact:** `vertique-mcp-server`
> **Depends on:** `vertique-mcp-core`, `vertique-core`, `vertique-json`, `vertique-rest-core`,
> `vertique-rest-security`

`vertique-mcp-server` composes the optional HTTP Model Context Protocol server. Include
`McpServerModule` explicitly in the application's Dagger component and supply an immutable
`McpServerConfig` binding. Generated tools are registered and bound to their effective JSON profile
during composition (see [Tool runtime](#tool-runtime)); the mount itself still serves only the
bounded `server/discover` walking skeleton, and protocol tool calls and extension hooks are
introduced by their owning slices.

Configuration is disabled by default. When enabled, `serverName` and `serverVersion` are required,
the mount is one literal path ending in `/*`, and every configured value is validated for range and
consistency at startup, before the router is mounted — so an out-of-range value fails composition
rather than a live request. JSON-RPC envelope parsing is bounded by Jackson's own frozen
`StreamReadConstraints` inside the private
[Bounded JSON-RPC envelope codec](#bounded-json-rpc-envelope-codec) — MCP owns JSON-RPC envelope
semantics, not a second general-purpose JSON resource-limit subsystem, and exposes no configuration
key for it. Request body size is enforced from `http.maxBodySize`, which also bounds the maximum
decodable envelope document length. Transport liveness (idle, read, and write timeouts) is shared
`HttpConfig` behavior; MCP arms no whole-request deadline of its own. A configured `jsonProfile` is
validated during composition even if MCP is disabled, preventing a latent invalid deployment
configuration.

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

### What is not here yet

This version composes the tool registry and the effective profile. Two
things are deliberately still absent, and both arrive with their owning slices:

- **Schema generation.** Descriptors carry a minimal, valid object input schema rather than a
  profile-aware synthesized one, and no output schema is generated. Argument materialization,
  input-policy application, and Bean Validation land with the same work.
- **Tool calls over the wire.** The mount still serves only the bounded `server/discover` walking
  skeleton; `tools/list` and `tools/call` are not exposed.

## Authorization

This authorization mapping is established now, ahead of the `tools/list` and `tools/call` wire
endpoints that will consume it (see [What is not here yet](#what-is-not-here-yet)); it governs their
authorization semantics once those methods are exposed by their owning slices.

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

