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
composition rather than a live request. Startup validation is not enforcement: the `allowedOrigins`
allowlist and the JSON bounds (`jsonMaxDepth`, `jsonMaxPropertiesPerObject`, `jsonMaxItemsPerArray`,
`jsonMaxStringChars`) are configured but not yet enforced by this version; Origin rejection and
bounded JSON parsing arrive with the codec and HTTP-contract capabilities. Request body size is
enforced today, from `http.maxBodySize`. A configured `jsonProfile` is validated during composition
even if MCP is disabled, preventing a latent invalid deployment configuration.

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
it, and `McpToolRuntime` has no public constructor. Per tool it resolves the effective profile,
validates the profile's mapper, and returns an immutable binding that privately retains the exact
stable mapper. No profile lookup happens on the request path.

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

### Profile mapper safety validation

Before any tool's binding is built, the server validates each selected profile mapper together with
every type reachable from that tool's arguments and structured result. Reachability is derived from
the mapper itself — its `TypeFactory` and its effective serialization/deserialization introspection,
mix-ins included — rather than from raw reflection or a hand-maintained type allowlist, so records,
inherited properties, containers, map keys, temporals, and cyclic graphs are all covered.

A reachable type is rejected when it enables Jackson default typing, or when its polymorphism is
anything other than a closed `@JsonTypeInfo(use = Id.NAME)` graph with a finite, unique, explicit
`@JsonSubTypes` logical-name allowlist that Jackson's own resolved subtype mappings agree with.
`Id.CLASS`, `Id.MINIMAL_CLASS`, `Id.CUSTOM`, `Id.DEDUCTION`, custom type resolvers or id resolvers,
duplicate or blank logical names, and unbounded base contracts all fail composition. Raw, wildcard,
type-variable, unresolved, or introspection-failing types fail as bounded composition errors.

Custom serializers and deserializers on otherwise supported types are **trusted application code**
and remain accepted; they are bounded by schema-first input and by size- and schema-checked output.
Vertique does not claim to statically prove them semantically safe. Failure messages name the
profile id, the resolved tool type, and the violated rule, are capped at 1,024 UTF-16 code units,
and never include a payload or serialized value. Profile selection cannot opt out of this check.

### What is not here yet

This version composes the tool registry, the effective profile, and the mapper safety gate. Two
things are deliberately still absent, and both arrive with their owning slices:

- **Schema generation.** Descriptors carry a minimal, valid object input schema rather than a
  profile-aware synthesized one, and no output schema is generated. Argument materialization,
  input-policy application, and Bean Validation land with the same work.
- **Tool calls over the wire.** The mount still serves only the bounded `server/discover` walking
  skeleton; `tools/list` and `tools/call` are not exposed.
