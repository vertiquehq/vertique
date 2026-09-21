// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.mcp.server.McpServerConfig;
import java.util.Set;

/** Framework wiring for {@link McpJson005ConsumptionIT}: real factory composition only. */
final class McpJson005ConsumptionITFixture {

    private McpJson005ConsumptionITFixture() {}

    /**
     * Builds a real {@link McpToolRuntimeFactory} over the framework's built-in profiles — no MCP
     * boundary or global default configured, so the resolver's tail ({@code vertique}, issue #440)
     * applies. Schema generation is unaffected either way: neither the {@code vertx} nor the {@code
     * vertique} built-in profile declares any {@code jsonSchemaTypeOverrides}.
     *
     * @return the composed factory
     */
    static McpToolRuntimeFactory factory() {
        JsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());
        McpServerConfig mcpConfig =
                McpServerConfig.builder().enabled(true).jsonProfile(null).build();
        return new McpToolRuntimeFactory(registry, JsonConfig.defaults(), mcpConfig, java.util.Optional.empty());
    }
}
