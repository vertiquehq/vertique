// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** JSON profiles, registries and configurations the registration tests run under. */
final class PayloadSchemaTestProfiles {

    /** The reserved profile the generic tests run under. */
    static final JsonConfig VERTIQUE = new JsonConfig(null, "vertique");

    private PayloadSchemaTestProfiles() {}

    /**
     * A registry holding only the built-in profiles.
     *
     * @return the registry
     */
    static JsonMapperProfileRegistry builtIn() {
        return new DefaultJsonMapperProfileRegistry(Set.of());
    }

    /**
     * A registry that additionally holds the given application profiles.
     *
     * @param profiles the application profiles
     * @return the registry
     */
    static JsonMapperProfileRegistry with(JsonMapperProfile... profiles) {
        return new DefaultJsonMapperProfileRegistry(Set.of(profiles));
    }

    /**
     * A configuration whose effective system profile is {@code profileId}.
     *
     * @param profileId the profile id
     * @return the configuration
     */
    static JsonConfig systemProfile(String profileId) {
        return new JsonConfig(null, profileId);
    }

    /**
     * A profile whose mapper is a copy of the built-in system mapper with a snake case naming strategy, which passes
     * the registry's structural probe.
     *
     * @param id the profile id
     * @return the profile
     */
    static JsonMapperProfile snakeCase(String id) {
        ObjectMapper mapper = builtIn()
                .mapper(JsonProfileId.SYSTEM)
                .copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return new FixedProfile(id, mapper, List.of());
    }

    /**
     * A profile that declares the given input type overrides on top of the built-in system mapper.
     *
     * @param id the profile id
     * @param overrides the declared overrides
     * @return the profile
     */
    static JsonMapperProfile withOverrides(String id, List<JsonSchemaTypeOverride> overrides) {
        return new FixedProfile(id, builtIn().mapper(JsonProfileId.SYSTEM).copy(), overrides);
    }

    /**
     * A profile whose override list contains a {@code null} element, which the generator refuses to be built from.
     *
     * @param id the profile id
     * @return the profile
     */
    static JsonMapperProfile withNullOverride(String id) {
        return withOverrides(id, Arrays.asList((JsonSchemaTypeOverride) null));
    }

    /**
     * A profile that replaces {@link UUID} by an object schema whose {@code properties} is a number, which the
     * generator publishes unchanged and the validation engine cannot compile.
     *
     * @param id the profile id
     * @return the profile
     */
    static JsonMapperProfile withUncompilableUuidSchema(String id) {
        return withOverrides(
                id,
                List.of(JsonSchemaTypeOverride.input(
                        UUID.class, JsonSchemaFragment.parse("{\"type\": \"object\", \"properties\": 5}"))));
    }

    private record FixedProfile(String name, ObjectMapper objectMapper, List<JsonSchemaTypeOverride> overrides)
            implements JsonMapperProfile {

        @Override
        public JsonProfileId id() {
            return JsonProfileId.of(name);
        }

        @Override
        public ObjectMapper mapper() {
            return objectMapper;
        }

        @Override
        public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
            return overrides;
        }
    }
}
