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
 * {@code mcp.jsonProfile}, then {@link JsonConfig#effectiveProfile()}. The annotation processor has
 * already collapsed the method-over-type selection into one nullable declared literal, so this
 * resolver owns the configured tail. An unknown id fails composition before Router mount; a blank id
 * never reaches composition (it fails compilation).
 *
 * <p>MCP is a managed edge, so its zero-config tail is the shared effective default for managed
 * edges — {@code vertique}, this framework's opinionated profile — not the process codec's {@code
 * system} baseline: {@code system} is deliberately unopinionated (it is shared, as the installed
 * process codec, by every path in the process, trusted and untrusted alike), while {@code vertique}
 * carries the opinions (null/absent omission, enum-default leniency) this framework wants at a
 * managed boundary by default. An application that sets the global {@code json.jsonProfile} —
 * including to {@code system} — reaches MCP through {@link JsonConfig#effectiveProfile()}; {@code
 * mcp.jsonProfile} still overrides that global choice for MCP alone. {@link #configuredDefault()}
 * therefore resolves {@code mcp.jsonProfile} first and only then delegates to {@code
 * jsonConfig.effectiveProfile()} — never the raw {@link JsonConfig#jsonProfile()} — so the global
 * floor is applied exactly where the shared authority applies it.
 */
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
     * Resolves the configured tail of the precedence: MCP boundary, else the shared effective
     * profile.
     *
     * <p>A blank {@code mcp.jsonProfile} means "not set" and inherits {@link
     * JsonConfig#effectiveProfile()}, matching {@code JsonConfig}'s documented semantics; a
     * non-blank unknown id is rejected by the registry. {@link JsonConfig#effectiveProfile()} — never
     * the raw {@link JsonConfig#jsonProfile()} — already applies the {@code vertique} floor when the
     * global key is unset, so this method never hard-codes that floor itself.
     *
     * @return the configured default id
     */
    private JsonProfileId configuredDefault() {
        String mcpDefault = Strings.firstNonBlank(mcpConfig.jsonProfile());
        return mcpDefault != null ? JsonProfileId.of(mcpDefault) : jsonConfig.effectiveProfile();
    }
}
