// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.jackson.DatabindCodec;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the built-in {@code vertique-strict} profile as seeded by
 * {@link DefaultJsonMapperProfileRegistry}.
 *
 * <p>The {@code vertique-strict} profile layers a strict-decimal, strict-string configuration on top
 * of the {@code vertique} opinionated defaults: {@link BigDecimal} serializes/deserializes as a
 * scale-preserving JSON <em>string</em> (via {@link BigDecimalAsStringSerializer} /
 * {@link BigDecimalStrictStringDeserializer}), {@code USE_BIG_DECIMAL_FOR_FLOATS} is disabled so
 * <em>untyped</em> decimals stay JSON numbers, and {@link String} properties reject scalar coercion
 * (via {@link StrictStringDeserializer}). Verifies the profile is seeded and resolvable with an empty
 * application set, that its mapper is an independent instance, and the strict-decimal/strict-string
 * behaviors, alongside the inherited {@code vertique} defaults (ISO-8601 dates, unknown-enum
 * fallback, {@code NON_NULL} inclusion).
 */
class VertiqueStrictProfileTest {

    private static final JsonProfileId VERTIQUE_STRICT_ID = JsonProfileId.of("vertique-strict");

    private static ObjectMapper strictMapper() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).mapper(VERTIQUE_STRICT_ID);
    }

    @Test
    @DisplayName("vertique-strict profile is seeded and resolvable with an empty application set")
    void registryResolvesVertiqueStrict_withEmptyAppSet() {
        // Given: a registry built with no application-contributed profiles.
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());

        // When: the vertique-strict mapper is resolved.
        ObjectMapper mapper = registry.mapper(VERTIQUE_STRICT_ID);

        // Then: it resolves non-null and is a distinct instance from the shared Vert.x mapper and
        // from the vertique profile's own mapper.
        assertTrue(
                registry.profileIds().contains(VERTIQUE_STRICT_ID),
                "profileIds must contain the reserved vertique-strict id");
        assertNotNull(mapper, "vertique-strict profile must resolve to a non-null mapper");
        assertNotSame(DatabindCodec.mapper(), mapper, "vertique-strict must not be the shared Vert.x mapper");
        assertNotSame(
                registry.mapper(VertiqueJsonMapperProfile.ID),
                mapper,
                "vertique-strict must own an independent mapper from the vertique profile");
    }

    @Test
    @DisplayName("BigDecimal round-trips as a scale-preserving JSON string")
    void bigDecimal_roundTripsAsScalePreservingString() throws Exception {
        // Given: the vertique-strict mapper and a record carrying a BigDecimal.
        ObjectMapper mapper = strictMapper();
        Price price = new Price(new BigDecimal("1.50"));

        // When: the record is serialized.
        String json = mapper.writeValueAsString(price);
        JsonNode tree = mapper.readTree(json);

        // Then: the amount property is a JSON string, not a number.
        assertTrue(tree.get("amount").isTextual(), "amount must be serialized as a JSON string: " + json);
        assertEquals("1.50", tree.get("amount").asText());

        // When: the JSON is deserialized back.
        Price roundTripped = mapper.readValue(json, Price.class);

        // Then: the value and its exact scale are preserved.
        assertEquals(0, price.amount().compareTo(roundTripped.amount()));
        assertEquals(2, roundTripped.amount().scale(), "the wire scale must be preserved exactly");
    }

    @Test
    @DisplayName("BigDecimal as a JSON number is rejected (string-only)")
    void bigDecimalJsonNumber_rejected() {
        // Given: the vertique-strict mapper and a JSON document carrying amount as a bare number.
        ObjectMapper mapper = strictMapper();

        // When/Then: deserialization is rejected — only a JSON string is accepted for BigDecimal.
        assertThrows(
                MismatchedInputException.class,
                () -> mapper.readValue("{\"amount\":1.5}", Price.class),
                "a bare JSON number must be rejected for a BigDecimal property under vertique-strict");
    }

    @Test
    @DisplayName("int-to-String coercion is rejected (strict string deserializer)")
    void intToStringCoercion_rejected() {
        // Given: the vertique-strict mapper and a record with a String property.
        ObjectMapper mapper = strictMapper();

        // When/Then: a JSON number targeting a String property is rejected, not coerced.
        assertThrows(
                MismatchedInputException.class,
                () -> mapper.readValue("{\"name\":42}", Named.class),
                "int-to-String coercion must be rejected under vertique-strict");
    }

    @Test
    @DisplayName("untyped decimal re-serializes as a JSON number (USE_BIG_DECIMAL_FOR_FLOATS disabled)")
    void untypedDecimal_reserializesAsNumber() throws Exception {
        // Given: the vertique-strict mapper and an untyped JSON document with a decimal value.
        ObjectMapper mapper = strictMapper();

        // When: the document is read into an untyped Map (no declared BigDecimal target type).
        Map<String, Object> decoded = mapper.readValue("{\"x\":1.5}", new TypeReference<Map<String, Object>>() {});
        String reserialized = mapper.writeValueAsString(decoded);
        JsonNode tree = mapper.readTree(reserialized);

        // Then: the value re-serializes as a JSON number, not a string — proving
        // USE_BIG_DECIMAL_FOR_FLOATS is disabled for untyped binding under this profile.
        assertTrue(tree.get("x").isNumber(), "untyped decimal must re-serialize as a JSON number: " + reserialized);
        assertFalse(
                tree.get("x").isTextual(), "untyped decimal must NOT re-serialize as a JSON string: " + reserialized);
    }

    @Test
    @DisplayName("present Optional<BigDecimal> serializes as a JSON string")
    void optionalBigDecimal_present_serializesAsString() throws Exception {
        // Given: the vertique-strict mapper and a record with a present Optional<BigDecimal>.
        ObjectMapper mapper = strictMapper();
        MaybePrice value = new MaybePrice(Optional.of(new BigDecimal("2.00")), "label");

        // When: the record is serialized.
        JsonNode tree = mapper.readTree(mapper.writeValueAsString(value));

        // Then: the present amount is a JSON string.
        assertTrue(tree.get("amount").isTextual(), "a present Optional<BigDecimal> must serialize as a string");
        assertEquals("2.00", tree.get("amount").asText());
    }

    @Test
    @DisplayName("empty Optional<BigDecimal> is omitted on serialization")
    void optionalBigDecimal_empty_omitted() throws Exception {
        // Given: the vertique-strict mapper and a record with an empty Optional<BigDecimal>.
        ObjectMapper mapper = strictMapper();
        MaybePrice value = new MaybePrice(Optional.empty(), "label");

        // When: the record is serialized.
        String json = mapper.writeValueAsString(value);

        // Then: the amount property name is omitted entirely.
        assertFalse(json.contains("amount"), "an empty Optional<BigDecimal> property must be omitted: " + json);
        assertTrue(json.contains("label"), "the non-empty label property must be present: " + json);
    }

    @Test
    @DisplayName("java.time LocalDate serializes as an ISO-8601 string (inherited vertique default)")
    void inheritedDefaults_localDate_serializesAsIso8601String() throws Exception {
        ObjectMapper mapper = strictMapper();

        String json = mapper.writeValueAsString(LocalDate.of(2024, 1, 15));

        assertEquals("\"2024-01-15\"", json);
    }

    @Test
    @DisplayName("unknown enum string falls back to @JsonEnumDefaultValue (inherited vertique default)")
    void inheritedDefaults_unknownEnum_fallsBackToDefault() throws Exception {
        ObjectMapper mapper = strictMapper();

        VertiqueProfileTest.SmokeColor decoded = mapper.readValue("\"PURPLE\"", VertiqueProfileTest.SmokeColor.class);

        assertEquals(VertiqueProfileTest.SmokeColor.UNKNOWN, decoded);
    }

    @Test
    @DisplayName("null-valued field is omitted on serialization (inherited NON_NULL default)")
    void inheritedDefaults_nullField_omittedOnSerialize() throws Exception {
        ObjectMapper mapper = strictMapper();
        Named holder = new Named(null);

        String json = mapper.writeValueAsString(holder);

        assertFalse(json.contains("name"), "the null-valued property name must be omitted under NON_NULL: " + json);
    }

    @Test
    @DisplayName("exponent-notation decimal string is rejected (grammar bound applies)")
    void exponentString_rejected() {
        ObjectMapper mapper = strictMapper();

        assertThrows(
                MismatchedInputException.class,
                () -> mapper.readValue("{\"amount\":\"1e5\"}", Price.class),
                "exponent notation must be rejected by the strict decimal grammar under vertique-strict");
    }

    @Test
    @DisplayName("BigDecimal map key with exponent notation is rejected (bounded, value-free error)")
    void bigDecimalMapKey_exponentNotation_rejected() {
        // Given: the vertique-strict mapper and a JSON object whose only key is an exponent-notation
        // literal — the same amplification shape the value-side grammar bound rejects.
        ObjectMapper mapper = strictMapper();

        // When/Then: binding the object to Map<BigDecimal, String> is rejected, and the rejection
        // message never echoes the offending key text.
        JsonProcessingException ex = assertThrows(
                JsonProcessingException.class,
                () -> mapper.readValue("{\"1e-2000000000\":\"x\"}", new TypeReference<Map<BigDecimal, String>>() {}),
                "an exponent-notation BigDecimal map key must be rejected under vertique-strict");
        assertFalse(
                ex.getMessage().contains("1e-2000000000"),
                "the rejection message must never echo the offending map key: " + ex.getMessage());
    }

    @Test
    @DisplayName("BigDecimal map key \"1.50\" binds with its scale preserved")
    void bigDecimalMapKey_plainDecimal_accepted() throws Exception {
        // Given: the vertique-strict mapper and a JSON object with a plain-decimal key.
        ObjectMapper mapper = strictMapper();

        // When: the object is bound to Map<BigDecimal, String>.
        Map<BigDecimal, String> decoded =
                mapper.readValue("{\"1.50\":\"x\"}", new TypeReference<Map<BigDecimal, String>>() {});

        // Then: the key parses to 1.50 with its wire scale preserved.
        assertEquals(1, decoded.size());
        BigDecimal key = decoded.keySet().iterator().next();
        assertEquals(0, key.compareTo(new BigDecimal("1.50")), "the map key must parse to 1.50");
        assertEquals(2, key.scale(), "the wire scale of the map key must be preserved exactly");
    }

    @Test
    @DisplayName("registry construction does not mutate the shared DatabindCodec mapper")
    void registryConstruction_doesNotMutateDatabindCodecMapper() {
        // Given: a snapshot of the shared Vert.x mapper's registered module count before construction.
        ObjectMapper shared = DatabindCodec.mapper();
        int registeredModuleCount = shared.getRegisteredModuleIds().size();

        // When: the registry (which seeds vertique-strict) is constructed.
        new DefaultJsonMapperProfileRegistry(Set.of());

        // Then: the shared mapper's registered module count is unchanged.
        assertEquals(
                registeredModuleCount,
                shared.getRegisteredModuleIds().size(),
                "the registered module count on the shared mapper must be unchanged");
    }

    // --- Test fixtures ---

    /**
     * Test record carrying a {@link BigDecimal} property, used to prove the strict decimal
     * string serde pair.
     *
     * @param amount the decimal amount
     */
    record Price(BigDecimal amount) {}

    /**
     * Test record carrying a {@link String} property, used to prove strict-string
     * (no-coercion) deserialization.
     *
     * @param name the string value
     */
    record Named(String name) {}

    /**
     * Test record carrying an {@link Optional}-wrapped {@link BigDecimal} alongside a plain
     * {@link String}, used to prove present/empty optional-decimal serialization behavior.
     *
     * @param amount the optional decimal amount
     * @param label a plain, always-present string property
     */
    record MaybePrice(Optional<BigDecimal> amount, String label) {}
}
