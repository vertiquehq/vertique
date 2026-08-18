// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link JsonMapperProfiles#of(JsonProfileId, ObjectMapper, java.util.Collection)}
 * overload, verifying the defensive-copy, null-rejection, and unmodifiable/stable-list contracts
 * (FR-JSON-088), and that the pre-amendment two-argument {@code of(id, mapper)} factory keeps its
 * exact prior empty-overrides behavior.
 */
class JsonMapperProfilesOverridesTest {

    private static final JsonSchemaTypeOverride SAMPLE_OVERRIDE =
            JsonSchemaTypeOverride.both(BigDecimal.class, JsonSchemaFragment.parse("{\"type\":\"string\"}"));

    @Test
    @DisplayName(
            "of(id, mapper, overrides) defensively copies the source collection and exposes an unmodifiable, stable list")
    void ofWithOverridesDefensivelyCopiesAndExposesUnmodifiable() {
        // Given: a mutable source collection carrying one override.
        List<JsonSchemaTypeOverride> source = new ArrayList<>();
        source.add(SAMPLE_OVERRIDE);

        // When: a profile is built from it, then the source collection is mutated afterward.
        JsonMapperProfile profile = JsonMapperProfiles.of(JsonProfileId.of("payments"), new ObjectMapper(), source);
        source.add(JsonSchemaTypeOverride.input(String.class, JsonSchemaFragment.parse("{\"type\":\"string\"}")));

        // Then: the profile's own list is unaffected by the post-construction mutation.
        List<JsonSchemaTypeOverride> overrides = profile.jsonSchemaTypeOverrides();
        assertEquals(
                1, overrides.size(), "the profile's override list must not observe the post-construction mutation");
        assertSame(SAMPLE_OVERRIDE, overrides.get(0));

        // And: the returned list is unmodifiable.
        assertThrows(
                UnsupportedOperationException.class,
                () -> overrides.add(SAMPLE_OVERRIDE),
                "jsonSchemaTypeOverrides() must return an unmodifiable list");

        // And: the same list instance is returned on repeated calls.
        assertSame(overrides, profile.jsonSchemaTypeOverrides(), "the same list instance must be returned every call");
    }

    @Test
    @DisplayName("of(id, mapper, overrides) rejects a null collection and a collection containing a null element")
    void ofWithOverridesRejectsNullCollectionAndNullElement() {
        JsonProfileId id = JsonProfileId.of("payments");
        ObjectMapper mapper = new ObjectMapper();

        assertThrows(NullPointerException.class, () -> JsonMapperProfiles.of(id, mapper, null));

        List<JsonSchemaTypeOverride> withNullElement = new ArrayList<>();
        withNullElement.add(null);
        assertThrows(NullPointerException.class, () -> JsonMapperProfiles.of(id, mapper, withNullElement));
    }

    @Test
    @DisplayName(
            "of(id, mapper) — the pre-amendment two-arg factory — still yields an empty, unmodifiable override list")
    void ofTwoArgBehaviorUnchangedEmptyOverrides() {
        JsonMapperProfile profile = JsonMapperProfiles.of(JsonProfileId.of("payments"), new ObjectMapper());

        List<JsonSchemaTypeOverride> overrides = profile.jsonSchemaTypeOverrides();

        assertTrue(overrides.isEmpty(), "the two-arg factory must keep declaring no overrides");
        assertThrows(
                UnsupportedOperationException.class,
                () -> overrides.add(SAMPLE_OVERRIDE),
                "jsonSchemaTypeOverrides() must return an unmodifiable list even for the two-arg factory");
        assertSame(overrides, profile.jsonSchemaTypeOverrides(), "repeated calls must return the same list instance");
    }
}
