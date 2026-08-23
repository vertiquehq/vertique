// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.mcp.server.McpServerConfig;
import java.util.Set;

/**
 * Test-only, cross-package construction path for a real {@link McpToolRuntimeFactory}.
 *
 * <p>{@link McpToolRuntimeFactory}'s {@code @Inject} constructor is package-private per the frozen
 * artifact inventory, so a test outside {@code dev.vertique.mcp.server.runtime} — a generated-invoker
 * proof living alongside {@code McpGeneratedHelloToolIT} in {@code dev.vertique.mcp.server}, or a
 * hand-loaded generated invoker's reflective construction in {@code McpToolResultTest} — cannot build
 * one directly. This type is the one public seam that does, mirroring the same package-private
 * fixtures ({@code McpJson005ConsumptionITFixture}, {@code McpJsonProfileITFixture}) this package
 * already uses for its own tests, bound to the minimal framework {@code vertx} profile with no MCP
 * boundary or global default configured.
 */
public final class McpToolRuntimeFactoryTestSupport {

    private McpToolRuntimeFactoryTestSupport() {}

    /**
     * Builds a real {@link McpToolRuntimeFactory} bound to the framework {@code vertx} profile — no
     * application-registered profile, MCP boundary default, or global default configured, so the
     * resolver's reserved tail always applies.
     *
     * @return the composed factory
     */
    public static McpToolRuntimeFactory factory() {
        JsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());
        McpServerConfig mcpConfig =
                McpServerConfig.builder().enabled(true).jsonProfile(null).build();
        return new McpToolRuntimeFactory(registry, JsonConfig.defaults(), mcpConfig);
    }
}
