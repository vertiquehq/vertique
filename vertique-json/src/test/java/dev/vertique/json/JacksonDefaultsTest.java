// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JacksonDefaults#apply(ObjectMapper)}.
 *
 * <p>Verifies that the four opinionated defaults from PRD §6.1 are applied correctly:
 * (1) unknown-enum fallback enabled, (2) ISO-8601 java.time with offset preserved,
 * (3) BigDecimal for floats, (4) NON_NULL inclusion. Also verifies that BigDecimal serialization
 * stays as a JSON number (stock behavior), JsonObject round-trips correctly, and that Instant
 * output is byte-identical to the raw Vert.x mapper (NFR-JSON-012).
 */
class JacksonDefaultsTest {

    /** A fresh mapper produced by applying the defaults under test. */
    private static ObjectMapper applyDefaults() {
        return JacksonDefaults.apply(new ObjectMapper());
    }

    // --- Feature flag tests ---

    @Nested
    @DisplayName("Feature flags")
    class FeatureFlags {

        @Test
        @DisplayName("READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE is enabled")
        void readUnknownEnumValuesUsingDefaultValue_enabled() {
            ObjectMapper mapper = applyDefaults();

            assertTrue(
                    mapper.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE),
                    "READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE must be enabled");
        }

        @Test
        @DisplayName("USE_BIG_DECIMAL_FOR_FLOATS is enabled")
        void useBigDecimalForFloats_enabled() {
            ObjectMapper mapper = applyDefaults();

            assertTrue(
                    mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS),
                    "USE_BIG_DECIMAL_FOR_FLOATS must be enabled");
        }

        @Test
        @DisplayName("WRITE_DATES_AS_TIMESTAMPS is disabled")
        void writeDatesAsTimestamps_disabled() {
            ObjectMapper mapper = applyDefaults();

            assertFalse(
                    mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                    "WRITE_DATES_AS_TIMESTAMPS must be disabled (ISO-8601 strings out)");
        }

        @Test
        @DisplayName("ADJUST_DATES_TO_CONTEXT_TIME_ZONE is disabled")
        void adjustDatesToContextTimeZone_disabled() {
            ObjectMapper mapper = applyDefaults();

            assertFalse(
                    mapper.isEnabled(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE),
                    "ADJUST_DATES_TO_CONTEXT_TIME_ZONE must be disabled (offsets preserved)");
        }

        @Test
        @DisplayName("serialization inclusion is NON_NULL")
        void serializationInclusion_isNonNull() {
            ObjectMapper mapper = applyDefaults();

            assertEquals(
                    JsonInclude.Include.NON_NULL,
                    mapper.getSerializationConfig()
                            .getDefaultPropertyInclusion()
                            .getValueInclusion(),
                    "Serialization inclusion must be NON_NULL");
        }
    }

    // --- ISO-8601 date serialization tests ---

    @Nested
    @DisplayName("ISO-8601 java.time serialization")
    class IsoDates {

        @Test
        @DisplayName("LocalDate serializes to a textual ISO-8601 date node (yyyy-MM-dd)")
        void localDate_serializesToIso8601() throws Exception {
            ObjectMapper mapper = applyDefaults();
            LocalDate date = LocalDate.of(2024, 1, 15);

            record Wrapper(LocalDate date) {}
            JsonNode node = mapper.readTree(mapper.writeValueAsString(new Wrapper(date)));
            JsonNode dateNode = node.get("date");

            assertTrue(
                    dateNode.isTextual(), "LocalDate must serialize as a textual (string) node, not numeric: " + node);
            assertEquals(
                    "2024-01-15", dateNode.textValue(), "LocalDate must serialize to the exact ISO-8601 date string");
        }

        @Test
        @DisplayName("LocalDateTime serializes to a textual ISO-8601 date-time node")
        void localDateTime_serializesToIso8601() throws Exception {
            ObjectMapper mapper = applyDefaults();
            LocalDateTime dt = LocalDateTime.of(2024, 1, 15, 10, 30, 0);

            record Wrapper(LocalDateTime dt) {}
            JsonNode node = mapper.readTree(mapper.writeValueAsString(new Wrapper(dt)));
            JsonNode dtNode = node.get("dt");

            assertTrue(
                    dtNode.isTextual(),
                    "LocalDateTime must serialize as a textual (string) node, not a numeric array: " + node);
            assertEquals(
                    "2024-01-15T10:30:00",
                    dtNode.textValue(),
                    "LocalDateTime must serialize to the exact ISO-8601 date-time string");
        }

        @Test
        @DisplayName("OffsetDateTime serializes to a textual ISO-8601 node preserving the offset")
        void offsetDateTime_serializesToIso8601() throws Exception {
            ObjectMapper mapper = applyDefaults();
            OffsetDateTime odt = OffsetDateTime.parse("2024-01-15T10:30:00+02:00");

            record Wrapper(OffsetDateTime odt) {}
            JsonNode node = mapper.readTree(mapper.writeValueAsString(new Wrapper(odt)));
            JsonNode odtNode = node.get("odt");

            assertTrue(
                    odtNode.isTextual(),
                    "OffsetDateTime must serialize as a textual (string) node, not numeric: " + node);
            // Parse the emitted string back and assert temporal equality + preserved offset,
            // independent of whether the formatter emits trailing seconds.
            OffsetDateTime parsed = OffsetDateTime.parse(odtNode.textValue());
            assertTrue(parsed.isEqual(odt), "OffsetDateTime ISO string must round-trip to the same instant: " + node);
            assertEquals(
                    "+02:00", parsed.getOffset().getId(), "OffsetDateTime ISO string must preserve the +02:00 offset");
        }

        @Test
        @DisplayName("ZonedDateTime serializes to a textual ISO-8601 node that parses back (FR-JSON Phase 1)")
        void zonedDateTime_serializesToIso8601() throws Exception {
            ObjectMapper mapper = applyDefaults();
            ZonedDateTime zdt = ZonedDateTime.parse("2024-01-15T10:30:00+02:00");

            record Wrapper(ZonedDateTime zdt) {}
            JsonNode node = mapper.readTree(mapper.writeValueAsString(new Wrapper(zdt)));
            JsonNode zdtNode = node.get("zdt");

            assertTrue(
                    zdtNode.isTextual(),
                    "ZonedDateTime must serialize as a textual (string) ISO-8601 node, not numeric: " + node);
            ZonedDateTime parsed = ZonedDateTime.parse(zdtNode.textValue());
            assertTrue(parsed.isEqual(zdt), "ZonedDateTime ISO string must round-trip to the same instant: " + node);
        }
    }

    // --- Offset preservation test ---

    @Nested
    @DisplayName("Offset preservation on deserialization")
    class OffsetPreservation {

        @Test
        @DisplayName("deserializing '2024-01-15T10:30:00+02:00' as OffsetDateTime keeps +02:00")
        void deserializeOffsetDateTime_preservesOriginalOffset() throws Exception {
            ObjectMapper mapper = applyDefaults();

            record Wrapper(OffsetDateTime odt) {}
            Wrapper result = mapper.readValue("{\"odt\":\"2024-01-15T10:30:00+02:00\"}", Wrapper.class);

            assertNotNull(result.odt(), "Deserialized OffsetDateTime must not be null");
            assertEquals(
                    "+02:00",
                    result.odt().getOffset().getId(),
                    "Deserialized OffsetDateTime must preserve the original +02:00 offset");
        }
    }

    // --- BigDecimal serialization test ---

    @Nested
    @DisplayName("BigDecimal serialization stays as JSON number (stock)")
    class BigDecimalSerialization {

        @Test
        @DisplayName("BigDecimal serializes as a JSON number node, not a string")
        void bigDecimal_serializesAsJsonNumber() throws Exception {
            ObjectMapper mapper = applyDefaults();

            record Wrapper(BigDecimal amount) {}
            JsonNode node = mapper.readTree(mapper.writeValueAsString(new Wrapper(new BigDecimal("1.50"))));
            JsonNode amountNode = node.get("amount");

            // Must be a number token (e.g. {"amount":1.50}), not a textual string ({"amount":"1.50"}).
            assertTrue(amountNode.isNumber(), "BigDecimal must serialize as a JSON number node: " + node);
            assertFalse(amountNode.isTextual(), "BigDecimal must NOT serialize as a JSON string node: " + node);
            // Compare by value (compareTo), not by equals: re-parsing the JSON number drops the
            // trailing-zero scale (1.50 -> 1.5), which is a parser normalization artifact, not a
            // serialization defect. The value must still equal the original.
            assertEquals(
                    0,
                    new BigDecimal("1.50").compareTo(amountNode.decimalValue()),
                    "BigDecimal numeric node must equal the original value: " + node);
        }
    }

    // --- JsonObject round-trip test ---

    @Nested
    @DisplayName("JsonObject round-trip support")
    class JsonObjectRoundTrip {

        @Test
        @DisplayName("null-free JsonObject round-trips with values intact")
        void jsonObject_roundTrips() throws Exception {
            ObjectMapper mapper = applyDefaults();

            // Null-free sample (NON_NULL would drop null fields; using non-null values only)
            JsonObject original =
                    new JsonObject().put("name", "acme").put("count", 42).put("active", true);

            String json = mapper.writeValueAsString(original);
            JsonObject roundTripped = mapper.readValue(json, JsonObject.class);

            assertEquals(original, roundTripped, "JsonObject must round-trip with values intact");
        }
    }

    // --- Instant compatibility test (NFR-JSON-012) ---

    @Nested
    @DisplayName("Instant serialization compatibility with the raw Vert.x mapper (NFR-JSON-012)")
    class InstantCompat {

        @Test
        @DisplayName("Instant output equals DatabindCodec.mapper() ISO_INSTANT form")
        void instant_outputMatchesVertxProfileIsoInstantForm() throws Exception {
            ObjectMapper vertiqueMapper = applyDefaults();
            ObjectMapper vertxMapper = DatabindCodec.mapper();

            Instant instant = Instant.parse("2024-01-15T08:30:00Z");

            record Wrapper(Instant ts) {}
            String vertiqueJson = vertiqueMapper.writeValueAsString(new Wrapper(instant));
            String vertxJson = vertxMapper.writeValueAsString(new Wrapper(instant));

            assertEquals(
                    vertxJson,
                    vertiqueJson,
                    "vertique mapper Instant output must equal the vertx mapper's ISO_INSTANT output (NFR-JSON-012)");
        }

        /**
         * Reusable-helper guarantee (FR-JSON-047): when {@code apply()} is invoked on a caller-owned
         * mapper that <em>already</em> had {@link VertxJsonSupport#module()} registered, the final
         * re-registration of {@code VertxModule} must still take effect so Vert.x's serializers stay
         * authoritative.
         *
         * <p><strong>Why this asserts serializer identity rather than output.</strong> Empirically,
         * Vert.x's {@code io.vertx.core.json.jackson.InstantSerializer} (uses
         * {@code DateTimeFormatter.ISO_INSTANT}) and {@code JavaTimeModule}'s
         * {@code com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializer} produce
         * <em>byte-identical</em> output for every reasonable {@link Instant}, including
         * nanosecond-precision values such as {@code 2024-01-15T10:30:00.123456789Z}. Output therefore
         * cannot discriminate which serializer is active, so this test inspects the resolved value
         * serializer's class directly. Under the bug, {@code MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS}
         * (default ON) makes the final {@code registerModule(VertxModule)} a silent no-op, leaving
         * {@code jsr310.ser.InstantSerializer} authoritative — this test fails until the helper forces
         * the Vert.x module's serializers to win.
         */
        @Test
        @DisplayName("apply() on a mapper that already has VertxModule keeps Vert.x's Instant serializer authoritative")
        void apply_onMapperWithVertxModuleAlreadyRegistered_keepsVertxInstantSerializer() throws Exception {
            // Given: a caller-owned mapper that ALREADY registered the Vert.x module before apply().
            ObjectMapper mapper = new ObjectMapper().registerModule(VertxJsonSupport.module());

            // When: the vertique defaults are applied on top.
            JacksonDefaults.apply(mapper);

            // Then: the active Instant serializer is Vert.x's, not JavaTimeModule's jsr310 one.
            JsonSerializer<Object> instantSerializer =
                    mapper.getSerializerProviderInstance().findValueSerializer(Instant.class);
            JsonSerializer<Object> vertxInstantSerializer =
                    DatabindCodec.mapper().getSerializerProviderInstance().findValueSerializer(Instant.class);

            assertEquals(
                    vertxInstantSerializer.getClass(),
                    instantSerializer.getClass(),
                    "After apply(), Vert.x's Instant serializer ("
                            + vertxInstantSerializer.getClass().getName()
                            + ") must be authoritative even when the caller pre-registered VertxModule; got "
                            + instantSerializer.getClass().getName());
            assertEquals(
                    "io.vertx.core.json.jackson.InstantSerializer",
                    instantSerializer.getClass().getName(),
                    "the resolved Instant serializer must be Vert.x's, not JavaTimeModule's jsr310 serializer");
        }
    }

    // --- Dedup-feature restoration (caller's original value preserved) ---

    @Nested
    @DisplayName("IGNORE_DUPLICATE_MODULE_REGISTRATIONS restoration")
    class DedupRestoration {

        @Test
        @DisplayName(
                "apply() restores IGNORE_DUPLICATE_MODULE_REGISTRATIONS to the caller's prior value (false stays false)")
        void apply_preDisabledDedup_staysDisabled() {
            // Given: a caller-owned mapper that DELIBERATELY disabled module-registration dedup.
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS, false);

            // When: the vertique defaults are applied (which toggles the feature internally).
            JacksonDefaults.apply(mapper);

            // Then: the feature is restored to the caller's prior value (false), not clobbered to true.
            assertFalse(
                    mapper.isEnabled(MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS),
                    "apply() must restore IGNORE_DUPLICATE_MODULE_REGISTRATIONS to the caller's prior value (false), "
                            + "not force it back to true");
        }

        @Test
        @DisplayName("apply() leaves IGNORE_DUPLICATE_MODULE_REGISTRATIONS enabled on a default (un-touched) mapper")
        void apply_defaultDedup_staysEnabled() {
            // Given: a fresh mapper where dedup is at its default (ON).
            ObjectMapper mapper = new ObjectMapper();

            // When: the vertique defaults are applied.
            JacksonDefaults.apply(mapper);

            // Then: the feature is restored to its prior (default ON) value.
            assertTrue(
                    mapper.isEnabled(MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS),
                    "apply() must leave IGNORE_DUPLICATE_MODULE_REGISTRATIONS enabled when the caller had it enabled");
        }
    }

    // --- Behavioral tests for the opinionated defaults (PRD Phase 1 ACs) ---

    @Nested
    @DisplayName("Behavioral defaults (PRD Phase 1 acceptance criteria)")
    class BehavioralDefaults {

        @Test
        @DisplayName("unknown properties are rejected (FAIL_ON_UNKNOWN_PROPERTIES left at Jackson's default)")
        void unknownProperties_rejected() {
            ObjectMapper mapper = applyDefaults();

            record Known(int known) {}
            // The payload carries an extra 'unknown' property the target record does not declare.
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"known\":1,\"unknown\":2}", Known.class),
                    "an unknown property must be rejected (FAIL_ON_UNKNOWN_PROPERTIES stays at Jackson's default)");
        }

        @Test
        @DisplayName("unknown enum string WITHOUT @JsonEnumDefaultValue is rejected (fallback is opt-in per-enum)")
        void unknownEnumWithoutDefault_rejected() {
            ObjectMapper mapper = applyDefaults();

            // NoDefaultColor has NO @JsonEnumDefaultValue constant, so the unknown-enum fallback
            // (READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE) has no target and the value is rejected.
            // READ_UNKNOWN_ENUM_VALUES_AS_NULL stays off, so it is NOT coerced to null either.
            assertThrows(
                    InvalidFormatException.class,
                    () -> mapper.readValue("\"PURPLE\"", NoDefaultColor.class),
                    "an unknown enum string must be rejected when the enum has no @JsonEnumDefaultValue constant");
        }

        @Test
        @DisplayName("ZonedDateTime serializes to a textual ISO-8601 string that parses back")
        void zonedDateTime_isoString() throws Exception {
            ObjectMapper mapper = applyDefaults();
            ZonedDateTime zdt = ZonedDateTime.parse("2024-01-15T10:30:00Z");

            record Wrapper(ZonedDateTime zdt) {}
            JsonNode node = mapper.readTree(mapper.writeValueAsString(new Wrapper(zdt)));
            JsonNode zdtNode = node.get("zdt");

            assertTrue(zdtNode.isTextual(), "ZonedDateTime must serialize as a textual ISO-8601 node: " + node);
            ZonedDateTime parsed = ZonedDateTime.parse(zdtNode.textValue());
            assertTrue(parsed.isEqual(zdt), "the emitted ISO-8601 string must parse back to the same instant: " + node);
        }

        @Test
        @DisplayName("a bare decimal JSON number binds to BigDecimal when read as Object (USE_BIG_DECIMAL_FOR_FLOATS)")
        void decimalNumber_bindsToBigDecimal() throws Exception {
            ObjectMapper mapper = applyDefaults();

            // Reading a bare decimal into an untyped Object proves USE_BIG_DECIMAL_FOR_FLOATS:
            // without it, 1.5 would bind to Double, not BigDecimal.
            Object value = mapper.readValue("1.5", Object.class);

            assertInstanceOf(
                    BigDecimal.class,
                    value,
                    "a decimal JSON number read as Object must bind to BigDecimal under USE_BIG_DECIMAL_FOR_FLOATS");
            assertEquals(new BigDecimal("1.5"), value, "the bound BigDecimal must equal the original decimal");
        }
    }

    // --- JDK8 Optional / Stream support tests ---

    @Nested
    @DisplayName("JDK8 Optional support")
    class Jdk8OptionalSupport {

        /** Fixture pairing an optional nickname with a required name. */
        record OptionalCarrier(Optional<String> nickname, String name) {}

        /** Fixture carrying the three scalar {@code Optional*} variants. */
        record ScalarOptionals(OptionalInt i, OptionalLong l, OptionalDouble d) {}

        /**
         * Fixture whose {@code tag} property is annotated {@code @JsonInclude(ALWAYS)}, used to prove
         * a per-property annotation wins over the {@code NON_ABSENT} config override.
         */
        record AlwaysCarrier(
                @JsonInclude(JsonInclude.Include.ALWAYS) Optional<String> tag) {}

        /** Fixture carrying a JDK8 {@link Stream} field. */
        record StreamCarrier(Stream<String> items) {}

        @Test
        @DisplayName("a present Optional<String> serializes as its contained value")
        void presentOptional_serializesAsContainedValue() throws Exception {
            // Given: a mapper with the vertique defaults and a carrier with a present nickname.
            ObjectMapper mapper = applyDefaults();
            OptionalCarrier carrier = new OptionalCarrier(Optional.of("zed"), "acme");

            // When: the carrier is serialized.
            JsonNode node = mapper.readTree(mapper.writeValueAsString(carrier));

            // Then: the nickname property equals the Optional's contained value.
            assertEquals(
                    "zed",
                    node.get("nickname").asText(),
                    "a present Optional<String> must serialize as its contained value: " + node);
        }

        @Test
        @DisplayName("an empty Optional<String> omits the property from the serialized JSON")
        void emptyOptional_propertyOmitted() throws Exception {
            // Given: a carrier with an empty nickname.
            ObjectMapper mapper = applyDefaults();
            OptionalCarrier carrier = new OptionalCarrier(Optional.empty(), "acme");

            // When: the carrier is serialized.
            JsonNode node = mapper.readTree(mapper.writeValueAsString(carrier));

            // Then: the nickname property is omitted, but the required name property remains.
            assertFalse(node.has("nickname"), "an empty Optional must omit its property under NON_ABSENT: " + node);
            assertTrue(node.has("name"), "the required name property must still be present: " + node);
        }

        @Test
        @DisplayName("an empty OptionalInt omits the property from the serialized JSON")
        void emptyOptionalInt_propertyOmitted() throws Exception {
            // Given: a ScalarOptionals fixture with all three Optional* fields empty.
            ObjectMapper mapper = applyDefaults();
            ScalarOptionals scalars =
                    new ScalarOptionals(OptionalInt.empty(), OptionalLong.empty(), OptionalDouble.empty());

            // When: the fixture is serialized.
            JsonNode node = mapper.readTree(mapper.writeValueAsString(scalars));

            // Then: the empty OptionalInt property is omitted.
            assertFalse(node.has("i"), "an empty OptionalInt must omit its property under NON_ABSENT: " + node);
        }

        @Test
        @DisplayName("an empty OptionalLong omits the property from the serialized JSON")
        void emptyOptionalLong_propertyOmitted() throws Exception {
            // Given: a ScalarOptionals fixture with all three Optional* fields empty.
            ObjectMapper mapper = applyDefaults();
            ScalarOptionals scalars =
                    new ScalarOptionals(OptionalInt.empty(), OptionalLong.empty(), OptionalDouble.empty());

            // When: the fixture is serialized.
            JsonNode node = mapper.readTree(mapper.writeValueAsString(scalars));

            // Then: the empty OptionalLong property is omitted.
            assertFalse(node.has("l"), "an empty OptionalLong must omit its property under NON_ABSENT: " + node);
        }

        @Test
        @DisplayName("an empty OptionalDouble omits the property from the serialized JSON")
        void emptyOptionalDouble_propertyOmitted() throws Exception {
            // Given: a ScalarOptionals fixture with all three Optional* fields empty.
            ObjectMapper mapper = applyDefaults();
            ScalarOptionals scalars =
                    new ScalarOptionals(OptionalInt.empty(), OptionalLong.empty(), OptionalDouble.empty());

            // When: the fixture is serialized.
            JsonNode node = mapper.readTree(mapper.writeValueAsString(scalars));

            // Then: the empty OptionalDouble property is omitted.
            assertFalse(node.has("d"), "an empty OptionalDouble must omit its property under NON_ABSENT: " + node);
        }

        @Test
        @DisplayName("present scalar Optionals serialize as their numeric JSON values")
        void presentScalarOptionals_serializeAsNumbers() throws Exception {
            // Given: a ScalarOptionals fixture with all three Optional* fields present.
            ObjectMapper mapper = applyDefaults();
            ScalarOptionals scalars =
                    new ScalarOptionals(OptionalInt.of(7), OptionalLong.of(8L), OptionalDouble.of(1.5));

            // When: the fixture is serialized.
            JsonNode node = mapper.readTree(mapper.writeValueAsString(scalars));

            // Then: each property is the plain numeric JSON value, not a wrapper object.
            assertEquals(7, node.get("i").asInt(), "OptionalInt.of(7) must serialize as the numeric value 7: " + node);
            assertEquals(
                    8L, node.get("l").asLong(), "OptionalLong.of(8) must serialize as the numeric value 8: " + node);
            assertEquals(
                    1.5,
                    node.get("d").asDouble(),
                    0.0001,
                    "OptionalDouble.of(1.5) must serialize as the numeric value 1.5: " + node);
        }

        @Test
        @DisplayName("a missing nickname property binds to an empty Optional, not null")
        void missingProperty_bindsEmptyOptional() throws Exception {
            // Given: a mapper with the vertique defaults.
            ObjectMapper mapper = applyDefaults();

            // When: a JSON payload that omits the nickname property entirely is deserialized.
            OptionalCarrier carrier = mapper.readValue("{\"name\":\"a\"}", OptionalCarrier.class);

            // Then: the missing property binds to Optional.empty(), not a null Optional reference.
            assertEquals(
                    Optional.empty(), carrier.nickname(), "a missing nickname property must bind to Optional.empty()");
        }

        @Test
        @DisplayName("an explicit null nickname property binds to an empty Optional")
        void explicitNullProperty_bindsEmptyOptional() throws Exception {
            // Given: a mapper with the vertique defaults.
            ObjectMapper mapper = applyDefaults();

            // When: a JSON payload with an explicit null nickname property is deserialized.
            OptionalCarrier carrier = mapper.readValue("{\"name\":\"a\",\"nickname\":null}", OptionalCarrier.class);

            // Then: the explicit null binds to Optional.empty(), not a null Optional reference.
            assertEquals(
                    Optional.empty(),
                    carrier.nickname(),
                    "an explicit null nickname property must bind to Optional.empty()");
        }

        @Test
        @DisplayName("@JsonInclude(ALWAYS) on an Optional property wins over the NON_ABSENT config override")
        void jsonIncludeAlways_onProperty_winsOverOverride() throws Exception {
            // Given: a carrier whose tag property is annotated @JsonInclude(ALWAYS) and is empty.
            ObjectMapper mapper = applyDefaults();
            AlwaysCarrier carrier = new AlwaysCarrier(Optional.empty());

            // When: the carrier is serialized.
            JsonNode node = mapper.readTree(mapper.writeValueAsString(carrier));

            // Then: the per-property annotation wins, so the tag property is present with a null value.
            assertTrue(node.has("tag"), "@JsonInclude(ALWAYS) must win over the NON_ABSENT config override: " + node);
            assertTrue(
                    node.get("tag").isNull(),
                    "the ALWAYS-included empty Optional must serialize as JSON null: " + node);
        }

        @Test
        @DisplayName("a Stream<String> field serializes as a JSON array")
        void streamField_serializesAsJsonArray() throws Exception {
            // Given: a carrier whose items field is a JDK8 Stream of two strings.
            ObjectMapper mapper = applyDefaults();
            StreamCarrier carrier = new StreamCarrier(Stream.of("a", "b"));

            // When: the carrier is serialized.
            JsonNode node = mapper.readTree(mapper.writeValueAsString(carrier));
            JsonNode items = node.get("items");

            // Then: the items property is a JSON array containing the streamed elements in order.
            assertTrue(items.isArray(), "a Stream<String> field must serialize as a JSON array: " + node);
            assertEquals(2, items.size(), "the JSON array must contain both streamed elements: " + node);
            assertEquals("a", items.get(0).asText(), "the first array element must be 'a': " + node);
            assertEquals("b", items.get(1).asText(), "the second array element must be 'b': " + node);
        }

        @Test
        @DisplayName("a present Optional round-trips through deserialize then serialize with the same value")
        void optionalRoundTrip_presentValue() throws Exception {
            // Given: a mapper with the vertique defaults.
            ObjectMapper mapper = applyDefaults();

            // When: a JSON payload with a present nickname property is deserialized, then re-serialized.
            OptionalCarrier carrier = mapper.readValue("{\"name\":\"a\",\"nickname\":\"zed\"}", OptionalCarrier.class);
            JsonNode node = mapper.readTree(mapper.writeValueAsString(carrier));

            // Then: the deserialized value is present, and re-serializing reproduces the same value.
            assertEquals(
                    Optional.of("zed"),
                    carrier.nickname(),
                    "the deserialized nickname must be present with value 'zed'");
            assertEquals(
                    "zed",
                    node.get("nickname").asText(),
                    "re-serializing must reproduce the nickname value 'zed': " + node);
        }
    }

    // --- Test fixtures ---

    /**
     * Test enum with <strong>no</strong> {@link JsonEnumDefaultValue} constant, used to prove the
     * unknown-enum fallback is opt-in per-enum: an unknown string is rejected (not silently coerced to
     * a default or to {@code null}) when the enum declares no default constant.
     */
    enum NoDefaultColor {
        /** A known colour. */
        RED,
        /** A known colour. */
        GREEN
    }
}
