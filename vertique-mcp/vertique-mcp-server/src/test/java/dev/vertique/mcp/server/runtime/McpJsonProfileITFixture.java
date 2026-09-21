// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.JsonConfig;
import dev.vertique.mcp.server.McpServerConfig;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Framework wiring for {@link McpJsonProfileIT}: registry and factory composition only. */
final class McpJsonProfileITFixture {

    /** The registered profile id declaring the {@link BigDecimal} string override. */
    static final String STRING_OVERRIDE_PROFILE_ID = "amount-as-string";

    private McpJsonProfileITFixture() {}

    /**
     * Builds a real {@link McpToolRuntimeFactory} over a minimal registry carrying the reserved
     * {@code system} profile, the {@code vertique} profile (issue #440's default fallback, also no
     * overrides), and {@link #STRING_OVERRIDE_PROFILE_ID} (a {@link BigDecimal} input-direction
     * string override).
     *
     * @return the composed factory
     */
    static McpToolRuntimeFactory factory() {
        McpServerConfig mcpConfig =
                McpServerConfig.builder().enabled(true).jsonProfile(null).build();
        return new McpToolRuntimeFactory(new FixtureProfileRegistry(), JsonConfig.defaults(), mcpConfig, java.util.Optional.empty());
    }

    /**
     * The registered profiles this fixture needs: the reserved {@code system} profile (kept for
     * realism, though {@code vertique} — not {@code system} — is the resolver's actual fallback since
     * issue #440), the {@code vertique} fallback itself, and the override.
     */
    private static final class FixtureProfileRegistry implements JsonMapperProfileRegistry {

        private final Map<JsonProfileId, JsonMapperProfile> profilesById = new LinkedHashMap<>();

        private FixtureProfileRegistry() {
            profilesById.put(JsonProfileId.SYSTEM, new NoOverrideProfile(JsonProfileId.SYSTEM));
            JsonProfileId vertiqueId = JsonProfileId.of("vertique");
            profilesById.put(vertiqueId, new NoOverrideProfile(vertiqueId));
            JsonProfileId overrideId = JsonProfileId.of(STRING_OVERRIDE_PROFILE_ID);
            profilesById.put(overrideId, new StringOverrideProfile(overrideId));
        }

        @Override
        public ObjectMapper mapper(JsonProfileId id) {
            return profile(id).mapper();
        }

        @Override
        public JsonMapperProfile profile(JsonProfileId id) {
            JsonMapperProfile profile = profilesById.get(id);
            if (profile == null) {
                throw new JsonProfileConfigurationException(
                        "Unknown JSON profile id '" + (id == null ? null : id.value()) + "'");
            }
            return profile;
        }

        @Override
        public Set<JsonProfileId> profileIds() {
            return Set.copyOf(profilesById.keySet());
        }
    }

    /** A profile declaring no schema-type overrides at all. */
    private static final class NoOverrideProfile implements JsonMapperProfile {

        private final JsonProfileId id;
        private final ObjectMapper mapper = new ObjectMapper();

        private NoOverrideProfile(JsonProfileId id) {
            this.id = id;
        }

        @Override
        public JsonProfileId id() {
            return id;
        }

        @Override
        public ObjectMapper mapper() {
            return mapper;
        }
    }

    /** A profile declaring a {@link BigDecimal} input-direction string override. */
    private static final class StringOverrideProfile implements JsonMapperProfile {

        private final JsonProfileId id;
        private final ObjectMapper mapper = new ObjectMapper();
        private final List<JsonSchemaTypeOverride> overrides = List.of(
                JsonSchemaTypeOverride.input(BigDecimal.class, JsonSchemaFragment.parse("{\"type\":\"string\"}")));

        private StringOverrideProfile(JsonProfileId id) {
            this.id = id;
        }

        @Override
        public JsonProfileId id() {
            return id;
        }

        @Override
        public ObjectMapper mapper() {
            return mapper;
        }

        @Override
        public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
            return overrides;
        }
    }
}
