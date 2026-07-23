// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the built-in {@code vertique} opinionated default profile
 * ({@link VertiqueJsonMapperProfile}) as seeded by {@link DefaultJsonMapperProfileRegistry}.
 *
 * <p>Verifies that the registry always seeds a {@code vertique} profile resolvable from an empty
 * application set (FR-JSON-043); that an application profile claiming the reserved {@code vertique}
 * id is rejected at construction (FR-JSON-044); that the {@code vertique} mapper is an independent
 * instance, never the shared Vert.x {@code DatabindCodec.mapper()} (FR-JSON-045); and that the
 * {@code vertique} mapper applies the framework's opinionated JSON defaults without mutating Vert.x's
 * global mapper (NFR-JSON-013).
 */
class VertiqueProfileTest {

    /**
     * Builds an {@link ObjectMapper} that supports the Vert.x JSON types, suitable for an
     * application profile whose mapper must pass the round-trip probe.
     *
     * @return a mapper with {@link VertxJsonSupport#module()} registered
     */
    private static ObjectMapper vertxAwareMapper() {
        return new ObjectMapper().registerModule(VertxJsonSupport.module());
    }

    @Test
    @DisplayName("vertique profile is seeded and resolvable with an empty application set")
    void vertiquePresent_inEmptyAppSet() {
        // Given: a registry built with no application-contributed profiles.
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());

        // When/Then: the reserved vertique id is registered and its mapper resolves non-null.
        assertTrue(
                registry.profileIds().contains(VertiqueJsonMapperProfile.ID),
                "profileIds must contain the reserved vertique id");
        assertNotNull(
                registry.mapper(VertiqueJsonMapperProfile.ID), "vertique profile must resolve to a non-null mapper");
    }

    @Test
    @DisplayName("application profile with reserved vertique id fails construction (FR-JSON-044)")
    void appProfileWithVertiqueId_rejected() {
        // Given: an application profile claiming the reserved vertique id, backed by a vertx-aware
        // mapper so any failure is unambiguously the reservation guard, not a probe failure.
        JsonMapperProfile app = JsonMapperProfiles.of(JsonProfileId.of("vertique"), vertxAwareMapper());

        // When/Then: constructing the registry rejects the reserved-id override.
        assertThrows(
                JsonProfileConfigurationException.class,
                () -> new DefaultJsonMapperProfileRegistry(Set.of(app)),
                "an application profile claiming the reserved vertique id must be rejected");
    }

    @Test
    @DisplayName("vertique mapper is an independent instance, not the shared Vert.x mapper (FR-JSON-045)")
    void vertiqueMapper_isNotSharedVertxMapper() {
        // Given: a registry built with no application-contributed profiles.
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());

        // When/Then: the vertique mapper is a distinct instance from Vert.x's shared DatabindCodec mapper.
        assertNotSame(
                DatabindCodec.mapper(),
                registry.mapper(VertiqueJsonMapperProfile.ID),
                "vertique profile must own an independent mapper, not the shared Vert.x mapper");
    }

    /**
     * Behavioral smoke tests on the resolved {@code vertique} mapper, proving the opinionated
     * defaults (Vert.x JSON support, ISO-8601 {@code java.time}, unknown-enum fallback, {@code NON_NULL}
     * inclusion) are in effect, and that constructing the registry does not mutate Vert.x's global
     * {@code DatabindCodec.mapper()} (NFR-JSON-013).
     */
    @Nested
    @DisplayName("vertique mapper smoke tests (NFR-JSON-013)")
    class SmokeTests {

        /**
         * Resolves the {@code vertique} mapper from a registry built with no application profiles.
         *
         * @return the opinionated {@code vertique} {@link ObjectMapper}
         */
        private ObjectMapper vertiqueMapper() {
            return new DefaultJsonMapperProfileRegistry(Set.of()).mapper(VertiqueJsonMapperProfile.ID);
        }

        @Test
        @DisplayName("null-free JsonObject round-trips with all values intact")
        void jsonObject_roundTrips_valuesIntact() throws Exception {
            // Given: a null-free JsonObject (NON_NULL would drop nulls and break structural equality).
            ObjectMapper vertique = vertiqueMapper();
            JsonObject obj = new JsonObject()
                    .put("string", "value")
                    .put("number", 42)
                    .put("flag", true)
                    .put("nested", new JsonObject().put("inner", "x"));

            // When: the object is serialized and read back through the vertique mapper.
            JsonObject roundTripped = vertique.readValue(vertique.writeValueAsString(obj), JsonObject.class);

            // Then: the decoded object is structurally equal to the original.
            assertEquals(obj, roundTripped);
        }

        @Test
        @DisplayName("null-free JsonArray round-trips with all values intact")
        void jsonArray_roundTrips_valuesIntact() throws Exception {
            // Given: a null-free JsonArray.
            ObjectMapper vertique = vertiqueMapper();
            JsonArray array = new JsonArray().add("scalar").add(7).add(new JsonObject().put("k", "v"));

            // When: the array is serialized and read back through the vertique mapper.
            JsonArray roundTripped = vertique.readValue(vertique.writeValueAsString(array), JsonArray.class);

            // Then: the decoded array is structurally equal to the original.
            assertEquals(array, roundTripped);
        }

        @Test
        @DisplayName("java.time LocalDate serializes as an ISO-8601 string, not a numeric timestamp")
        void localDate_serializesAsIso8601String() throws Exception {
            // Given: the vertique mapper and a fixed LocalDate.
            ObjectMapper vertique = vertiqueMapper();

            // When: the date is serialized.
            String json = vertique.writeValueAsString(LocalDate.of(2024, 1, 15));

            // Then: the output is the quoted ISO-8601 string form, not a numeric array.
            assertEquals("\"2024-01-15\"", json);
        }

        @Test
        @DisplayName("unknown enum string falls back to the @JsonEnumDefaultValue constant")
        void unknownEnumString_fallsBackToDefault() throws Exception {
            // Given: the vertique mapper and a JSON string naming a non-existent enum constant.
            ObjectMapper vertique = vertiqueMapper();

            // When: the unknown value is deserialized into SmokeColor.
            SmokeColor decoded = vertique.readValue("\"PURPLE\"", SmokeColor.class);

            // Then: it falls back to the annotated default constant.
            assertEquals(SmokeColor.UNKNOWN, decoded);
        }

        @Test
        @DisplayName("null-valued field is omitted on serialization (NON_NULL)")
        void nullField_omittedOnSerialize() throws Exception {
            // Given: the vertique mapper and a POJO with one null-valued and one non-null property.
            ObjectMapper vertique = vertiqueMapper();
            NullableHolder holder = new NullableHolder("present", null);

            // When: the POJO is serialized.
            String json = vertique.writeValueAsString(holder);

            // Then: the non-null field is present and the null field name is omitted.
            assertTrue(json.contains("present"), "the non-null property value must be present");
            assertFalse(json.contains("absent"), "the null-valued property name must be omitted under NON_NULL");
        }

        @Test
        @DisplayName("registry construction does not mutate the shared DatabindCodec mapper")
        void registryConstruction_doesNotMutateDatabindCodecMapper() {
            // Given: a rich snapshot of the shared Vert.x mapper's state before constructing the
            // registry. The vertique profile applies its opinionated defaults to its OWN mapper, so
            // none of these may change on DatabindCodec.mapper().
            ObjectMapper shared = DatabindCodec.mapper();
            boolean writeDatesAsTimestamps = shared.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            boolean adjustDatesToContextTimeZone =
                    shared.isEnabled(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
            boolean readUnknownEnumUsingDefault =
                    shared.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
            boolean useBigDecimalForFloats = shared.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            boolean ignoreDuplicateModuleRegistrations =
                    shared.isEnabled(MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS);
            JsonInclude.Include serializationInclusion = shared.getSerializationConfig()
                    .getDefaultPropertyInclusion()
                    .getValueInclusion();
            int registeredModuleCount = shared.getRegisteredModuleIds().size();

            // When: the registry (which seeds the vertique profile) is constructed.
            new DefaultJsonMapperProfileRegistry(Set.of());

            // Then: every snapshotted aspect of the shared mapper is unchanged.
            assertEquals(
                    writeDatesAsTimestamps,
                    shared.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                    "WRITE_DATES_AS_TIMESTAMPS on the shared mapper must be unchanged");
            assertEquals(
                    adjustDatesToContextTimeZone,
                    shared.isEnabled(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE),
                    "ADJUST_DATES_TO_CONTEXT_TIME_ZONE on the shared mapper must be unchanged");
            assertEquals(
                    readUnknownEnumUsingDefault,
                    shared.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE),
                    "READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE on the shared mapper must be unchanged");
            assertEquals(
                    useBigDecimalForFloats,
                    shared.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS),
                    "USE_BIG_DECIMAL_FOR_FLOATS on the shared mapper must be unchanged");
            assertEquals(
                    ignoreDuplicateModuleRegistrations,
                    shared.isEnabled(MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS),
                    "IGNORE_DUPLICATE_MODULE_REGISTRATIONS on the shared mapper must be unchanged");
            assertEquals(
                    serializationInclusion,
                    shared.getSerializationConfig()
                            .getDefaultPropertyInclusion()
                            .getValueInclusion(),
                    "serialization inclusion on the shared mapper must be unchanged");
            assertEquals(
                    registeredModuleCount,
                    shared.getRegisteredModuleIds().size(),
                    "the registered module count on the shared mapper must be unchanged");
        }
    }

    // --- Test fixtures ---

    /**
     * Test enum carrying a Jackson default value, used to prove unknown enum strings fall back to the
     * {@link JsonEnumDefaultValue}-annotated constant under the {@code vertique} defaults.
     */
    enum SmokeColor {
        /** A known colour. */
        RED,
        /** A known colour. */
        GREEN,
        /** The fallback constant returned for any unrecognized enum string. */
        @JsonEnumDefaultValue
        UNKNOWN
    }

    /**
     * Small test POJO with one always-present and one nullable property, used to prove the
     * {@code NON_NULL} serialization inclusion omits null-valued fields.
     *
     * @param present a non-null property value
     * @param absent a nullable property value; when {@code null} its field name must be omitted
     */
    record NullableHolder(String present, String absent) {}
}
