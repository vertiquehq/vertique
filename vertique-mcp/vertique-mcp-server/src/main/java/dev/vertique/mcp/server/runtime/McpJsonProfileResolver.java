// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonConfig;
import dev.vertique.mcp.server.McpServerConfig;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Resolves the effective tool-payload JSON profile once, at composition.
 *
 * <p>The precedence is method {@code @JsonProfile}, declaring type {@code @JsonProfile},
 * {@code mcp.jsonProfile}, global {@code json.jsonProfile}, then the reserved {@code vertx} profile.
 * The annotation processor has already collapsed the method-over-type selection into one nullable
 * declared literal, so this resolver owns the configured tail. An unknown id fails composition before
 * Router mount; a blank id never reaches composition (it fails compilation).
 */
@Singleton
final class McpJsonProfileResolver {

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
    @Inject
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
     * Resolves the configured tail of the precedence: MCP boundary, then global, then {@code vertx}.
     *
     * <p>A {@code null} or blank configured id means "not set" and inherits the next tier, matching
     * {@code JsonConfig}'s documented semantics; a non-blank unknown id is rejected by the registry.
     *
     * @return the configured default id, or the reserved {@code vertx} id when neither tier is set
     */
    private JsonProfileId configuredDefault() {
        String boundaryDefault = mcpConfig.jsonProfile();
        if (boundaryDefault != null && !boundaryDefault.isBlank()) {
            return JsonProfileId.of(boundaryDefault);
        }
        String globalDefault = jsonConfig.jsonProfile();
        if (globalDefault != null && !globalDefault.isBlank()) {
            return JsonProfileId.of(globalDefault);
        }
        return JsonProfileId.VERTX;
    }
}
