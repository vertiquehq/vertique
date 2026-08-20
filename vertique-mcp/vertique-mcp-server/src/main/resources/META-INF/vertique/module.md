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
the mount is one literal path ending in `/*`, and every request and JSON bound is validated for
range and consistency at startup, before the router is mounted — so an out-of-range value fails
composition rather than a live request. The JSON bounds (`jsonMaxDepth`,
`jsonMaxPropertiesPerObject`, `jsonMaxItemsPerArray`, `jsonMaxStringChars`) are enforced by the
strict bounded JSON-RPC codec described in [Strict bounded JSON-RPC codec](#strict-bounded-json-rpc-codec).
The `allowedOrigins` allowlist is configured but not yet enforced by this version; Origin rejection
and the wiring that puts the codec on the live HTTP request path arrive with the HTTP-contract
capability. Request body size is enforced today, from `http.maxBodySize`. A configured `jsonProfile`
is validated during composition even if MCP is disabled, preventing a latent invalid deployment
configuration.

## Strict bounded JSON-RPC codec

Wire decoding is framework-owned and trusts no application mapper. A strict UTF-8, bounded reader
decodes exactly one complete JSON value and rejects — with a classified, bounded outcome and **no
partial value** — any frame that carries:

- **duplicate object keys**, or **trailing tokens** after one complete top-level value;
- nesting deeper than `jsonMaxDepth` (default 64), enforced by an explicit depth counter rather
  than native recursion, so an adversarial deeply-nested frame is bounded rather than able to
  overflow the call stack;
- an object with more members than `jsonMaxPropertiesPerObject` (default 1,000), an array with more
  items than `jsonMaxItemsPerArray` (default 10,000), or a string longer than `jsonMaxStringChars`
  (default 262,144). The string bound is measured in UTF-16 code units (Java `String.length()`), not
  Unicode code points, so a supplementary (astral) code point counts as two toward the bound;
- invalid UTF-8, including lone surrogates.

Numeric values keep their exact lexical precision: a 64-bit-overflowing integer such as
`9007199254740993` and a decimal such as `0.10000000000000001` survive decode and canonical
re-encode without lossy `double` rounding, so downstream schema validation sees exactly what the
client sent. Canonical encoding is a compact, insertion-order-preserving re-encode; an
already-compact frame round-trips byte-for-byte. Precision is preserved within fixed internal
hardening bounds: a numeric token whose lexical length or decimal-scale magnitude is far beyond any
legitimate value — the vectors that would otherwise drive a quadratic big-integer parse or an
out-of-memory plain-form encode — is rejected as a bounded classified outcome rather than
materialized, and an exponent that overflows during materialization is classified rather than
allowed to escape.

Over that reader, the codec validates the final-2026 JSON-RPC request envelope — `jsonrpc` must be
`"2.0"`, `method` must name one of the bounded supported set (`server/discover`, `tools/list`,
`tools/call`), the request `id` must be present and a string or integer (all three supported methods
are requests, never notifications), and `params` must be present and an object (the vendored
final-2026 request schema marks it required for every supported method) — and classifies failures
deterministically to the standard JSON-RPC codes with the standard messages and no `data`:
`-32700` *Parse error* (malformed JSON, or a strict-reader rejection such as a duplicate key or
trailing token; null id), `-32600` *Invalid Request* (bad envelope — wrong version, a missing or
non-string/non-integer id, a missing method, or a missing or non-object `params`; original usable id when the
id itself is a trustworthy string or integer, else null), and `-32601` *Method not found* (unknown
method; original usable id). Header/body-mismatch
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
