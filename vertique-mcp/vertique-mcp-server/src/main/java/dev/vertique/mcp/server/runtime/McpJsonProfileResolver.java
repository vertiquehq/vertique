// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.util.Strings;
import dev.vertique.json.JsonConfig;
import dev.vertique.mcp.server.McpServerConfig;
import jakarta.annotation.Nullable;

/**
 * Resolves the effective tool-payload JSON profile once, at composition.
 *
 * <p>The precedence is method {@code @JsonProfile}, declaring type {@code @JsonProfile},
 * {@code mcp.jsonProfile}, global {@code json.jsonProfile}, then the {@code vertique} profile
 * (issue #440). The annotation processor has already collapsed the method-over-type selection into
 * one nullable declared literal, so this resolver owns the configured tail. An unknown id fails
 * composition before Router mount; a blank id never reaches composition (it fails compilation).
 *
 * <p><b>Issue #440 scoping.</b> Only the final fallback tier changed — from the reserved {@code
 * vertx} profile (Vert.x's bare {@code DatabindCodec.mapper()}, which cannot serialize an {@code
 * Optional}-typed tool result) to {@code vertique} (this framework's own default profile). This is
 * deliberately <em>not</em> an MCP configuration default: a configuration default would sit ahead of
 * {@code json.jsonProfile} in {@link #configuredDefault()}'s precedence chain and would silently
 * override an application's own explicit global choice. The fallback instead applies only when
 * nothing upstream of it — the per-tool declaration, {@code mcp.jsonProfile}, and {@code
 * json.jsonProfile} — ever selected a profile at all.
 */
final class McpJsonProfileResolver {

    /**
     * The final fallback profile (issue #440): resolved only when the per-tool declaration, {@code
     * mcp.jsonProfile}, and {@code json.jsonProfile} are all unset. Never outranks {@code
     * json.jsonProfile} — see {@link #configuredDefault()}.
     */
    private static final JsonProfileId VERTIQUE_FALLBACK = JsonProfileId.of("vertique");

    private final JsonMapperProfileRegistry profiles;
    private final JsonConfig jsonConfig;
    private final McpServerConfig mcpConfig;

    /**
     * Binds the resolver to the discovered profiles and the two configured default tiers.
     *
     * @param profiles the registry of every discovered JSON mapper profile
     * @param jsonConfig the global JSON configuration carrying {@code json.jsonProfile}
     * @param mcpConfig the MCP server configuration carrying {@code mcp.jsonProfile}
     */
    McpJsonProfileResolver(JsonMapperProfileRegistry profiles, JsonConfig jsonConfig, McpServerConfig mcpConfig) {
        this.profiles = profiles;
        this.jsonConfig = jsonConfig;
        this.mcpConfig = mcpConfig;
    }

    /**
     * Resolves the effective profile of one tool.
     *
     * @param declaredJsonProfile the method- or type-declared profile id the processor emitted, or
     *     {@code null} when the tool declares none
     * @return the effective profile, carrying its stable mapper
     * @throws dev.vertique.core.json.JsonProfileConfigurationException if the selected id names no
     *     registered profile; composition fails before Router mount
     */
    JsonMapperProfile resolve(@Nullable JsonProfileId declaredJsonProfile) {
        JsonProfileId selected = declaredJsonProfile != null ? declaredJsonProfile : configuredDefault();
        return profiles.profile(selected);
    }

    /**
     * Resolves the configured tail of the precedence: MCP boundary, then global, then {@code
     * vertique} (issue #440).
     *
     * <p>A {@code null} or blank configured id means "not set" and inherits the next tier, matching
     * {@code JsonConfig}'s documented semantics; a non-blank unknown id is rejected by the registry.
     * {@code json.jsonProfile} is read here, ahead of the fallback — an application that sets it
     * always gets its own configured profile, never {@link #VERTIQUE_FALLBACK}.
     *
     * @return the configured default id, or {@link #VERTIQUE_FALLBACK} when neither tier is set
     */
    private JsonProfileId configuredDefault() {
        String configured = Strings.firstNonBlank(mcpConfig.jsonProfile(), jsonConfig.jsonProfile());
        return configured != null ? JsonProfileId.of(configured) : VERTIQUE_FALLBACK;
    }
}
