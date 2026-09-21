// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.mcp.server.McpServerConfig;
import jakarta.annotation.Nullable;
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
 * already uses for its own tests, bound to the framework's built-in profiles with no MCP boundary or
 * global default configured.
 */
public final class McpToolRuntimeFactoryTestSupport {

    private McpToolRuntimeFactoryTestSupport() {}

    /**
     * Builds a real {@link McpToolRuntimeFactory} with no application-registered profile, MCP
     * boundary default, or global default configured, so {@link McpJsonProfileResolver}'s tail always
     * applies — the built-in {@code vertique} profile (issue #440), never the reserved {@code vertx}
     * profile.
     *
     * @return the composed factory
     */
    public static McpToolRuntimeFactory factory() {
        return factory(Set.of(), null);
    }

    /**
     * Builds a real {@link McpToolRuntimeFactory} over the framework's built-in profiles plus the
     * given application-registered profiles, with {@code applicationProfiles} bound to the given MCP
     * boundary default (or the resolver's tail — the built-in {@code vertique} profile, issue #440 —
     * when {@code null}).
     *
     * <p>Used by a proof whose tool needs a profile other than the resolver's own tail — e.g. one
     * explicitly selected to be {@code Optional}-materialization-incapable (contract §4.1), to prove
     * the canary still fails startup for a genuinely incapable profile regardless of which profile the
     * zero-config tail resolves to.
     *
     * @param applicationProfiles the application-registered profiles; must not be {@code null}
     * @param mcpJsonProfile the configured {@code mcp.jsonProfile} default id, or {@code null} for none
     * @return the composed factory
     */
    public static McpToolRuntimeFactory factory(
            Set<JsonMapperProfile> applicationProfiles, @Nullable String mcpJsonProfile) {
        JsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(applicationProfiles);
        McpServerConfig mcpConfig = McpServerConfig.builder()
                .enabled(true)
                .jsonProfile(mcpJsonProfile)
                .build();
        return new McpToolRuntimeFactory(registry, JsonConfig.defaults(), mcpConfig, java.util.Optional.empty());
    }
}
