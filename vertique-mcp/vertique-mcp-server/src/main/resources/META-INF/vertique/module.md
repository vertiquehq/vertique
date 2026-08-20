# Vertique MCP Server

> **Status:** Alpha
> **Package:** `dev.vertique.mcp.server`
> **Artifact:** `vertique-mcp-server`
> **Depends on:** `vertique-mcp-core`, `vertique-rest-core`, `vertique-rest-security`

`vertique-mcp-server` composes the optional HTTP Model Context Protocol server. Include
`McpServerModule` explicitly in the application's Dagger component and supply an immutable
`McpServerConfig` binding. The initial server supports only the bounded `server/discover` walking
skeleton; tool registration, calls, and extension hooks are introduced by their owning slices.

Configuration is disabled by default. When enabled, `serverName` and `serverVersion` are required,
the mount is one literal path ending in `/*`, and every request and JSON bound is validated for
range and consistency at startup, before the router is mounted — so an out-of-range value fails
composition rather than a live request. Startup validation is not enforcement: the `allowedOrigins`
allowlist and the JSON bounds (`jsonMaxDepth`, `jsonMaxPropertiesPerObject`, `jsonMaxItemsPerArray`,
`jsonMaxStringChars`) are configured but not yet enforced by this version; Origin rejection and
bounded JSON parsing arrive with the codec and HTTP-contract capabilities. Request body size is
enforced today, from `http.maxBodySize`. A configured `jsonProfile` is validated during composition
even if MCP is disabled, preventing a latent invalid deployment configuration.

The module does not use an MCP Java SDK. It depends on `vertique-mcp-core` for the stable lifecycle
boundary and owns the HTTP/router composition only.
