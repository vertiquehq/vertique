// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.core.json.JsonSchemaTypeOverride.Direction;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests proving the {@code vertique-strict} profile's declared JSON Schema override
 * (FR-JSON-089): a single {@code BigDecimal} {@link Direction#BOTH} override whose fragment is
 * built from the shared {@link BigDecimalStrictStringDeserializer} grammar constants — never a
 * second, independently maintained copy of the bound or the pattern — and that the other two
 * built-in profiles declare no overrides at all.
 */
class VertiqueStrictProfileOverrideTest {

    private static JsonMapperProfile strictProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(VertiqueStrictJsonMapperProfile.ID);
    }

    @Test
    @DisplayName("vertique-strict declares exactly one BigDecimal BOTH override built from the serde constants")
    void strictProfileDeclaresBigDecimalBothOverride() {
        // Given: the built-in vertique-strict profile.
        JsonMapperProfile profile = strictProfile();

        // When: its declared schema overrides are read.
        List<JsonSchemaTypeOverride> overrides = profile.jsonSchemaTypeOverrides();

        // Then: exactly one override, for BigDecimal, applying to both directions.
        assertEquals(1, overrides.size(), "vertique-strict must declare exactly one schema override");
        JsonSchemaTypeOverride override = overrides.get(0);
        assertEquals(BigDecimal.class, override.javaType());
        assertEquals(Direction.BOTH, override.direction());

        // And: the fragment's canonical JSON is built from the shared serde constants — the bound
        // (MAX_LENGTH) and the anchored pattern derivation — never a duplicated literal.
        String escapedPattern = BigDecimalStrictStringDeserializer.ANCHORED_PLAIN_DECIMAL_PATTERN.replace("\\", "\\\\");
        String expectedCanonicalJson =
                "{\"format\":\"decimal\",\"maxLength\":" + BigDecimalStrictStringDeserializer.MAX_LENGTH
                        + ",\"pattern\":\"" + escapedPattern + "\",\"type\":\"string\"}";
        assertEquals(expectedCanonicalJson, override.fragment().canonicalJson());
        // Pin the literal form the PRD/plan freeze, so a constant-derivation regression is caught
        // even if MAX_LENGTH or the pattern derivation silently changed underneath the assertion above.
        assertEquals(
                "{\"format\":\"decimal\",\"maxLength\":100,\"pattern\":\"^-?[0-9]+(\\\\.[0-9]+)?$\",\"type\":\"string\"}",
                override.fragment().canonicalJson());
    }

    @Test
    @DisplayName("vertique-strict's declared override list is the same stable instance on repeated calls")
    void strictProfileOverrideListStable() {
        JsonMapperProfile profile = strictProfile();

        List<JsonSchemaTypeOverride> first = profile.jsonSchemaTypeOverrides();
        List<JsonSchemaTypeOverride> second = profile.jsonSchemaTypeOverrides();

        assertSame(first, second, "the same override list instance must be returned every call");
    }

    @Test
    @DisplayName("the vertx and vertique built-in profiles declare no schema overrides")
    void vertxAndVertiqueProfilesDeclareNoOverrides() {
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());

        JsonMapperProfile vertx = registry.profile(VertxJsonMapperProfile.ID);
        JsonMapperProfile vertique = registry.profile(VertiqueJsonMapperProfile.ID);

        assertTrue(vertx.jsonSchemaTypeOverrides().isEmpty(), "vertx profile must declare no schema overrides");
        assertTrue(vertique.jsonSchemaTypeOverrides().isEmpty(), "vertique profile must declare no schema overrides");
    }
}
