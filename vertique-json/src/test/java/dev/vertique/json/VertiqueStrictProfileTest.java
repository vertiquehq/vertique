// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.core.json.jackson.JacksonCodec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

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
    @DisplayName("BigDecimal map key \"0.0000001\" serializes in plain form, not scientific notation")
    void bigDecimalMapKey_serializesInPlainForm() throws Exception {
        // Given: the vertique-strict mapper and a map keyed by a small-scale BigDecimal whose
        // default Object.toString() form would be scientific notation ("1E-7").
        ObjectMapper mapper = strictMapper();
        Map<BigDecimal, String> value = Map.of(new BigDecimal("0.0000001"), "x");

        // When: the map is serialized.
        String json = mapper.writeValueAsString(value);

        // Then: the key is written in plain decimal form, matching what the value-side serializer
        // would produce, and re-readable by the profile's own key deserializer.
        assertEquals(
                "{\"0.0000001\":\"x\"}",
                json,
                "the BigDecimal map key must serialize in plain decimal form, not scientific notation");
    }

    @Test
    @DisplayName("BigDecimal map key round-trips through write then read on the same profile mapper")
    void bigDecimalMapKey_writeThenRead_roundTrips() throws Exception {
        // Given: the vertique-strict mapper and a map keyed by a BigDecimal that a naive
        // Object.toString()-based key serializer would restring as scientific notation.
        ObjectMapper mapper = strictMapper();
        Map<BigDecimal, String> original = Map.of(new BigDecimal("0.0000001"), "x");

        // When: the map is written, then read back through the same profile mapper.
        String json = mapper.writeValueAsString(original);
        Map<BigDecimal, String> roundTripped = mapper.readValue(json, new TypeReference<Map<BigDecimal, String>>() {});

        // Then: the profile can read back its own output.
        assertEquals(1, roundTripped.size());
        BigDecimal key = roundTripped.keySet().iterator().next();
        assertEquals(0, key.compareTo(new BigDecimal("0.0000001")), "the round-tripped key must equal 0.0000001");
        assertEquals("x", roundTripped.get(key));
    }

    @Test
    @DisplayName("over-bound BigDecimal map key rejection: this profile's own message names only the bound")
    void bigDecimalMapKey_overBound_profileMessageIsDigitFree() {
        // Given: the vertique-strict mapper and a map keyed by a 200-digit BigDecimal whose
        // plain-string form (precision 200) exceeds the 100-character write-side bound.
        ObjectMapper mapper = strictMapper();
        String digits = "9".repeat(200);
        Map<BigDecimal, String> value = Map.of(new BigDecimal(new BigInteger(digits)), "x");

        // When: writing is rejected.
        JsonProcessingException ex =
                assertThrows(JsonProcessingException.class, () -> mapper.writeValueAsString(value));

        // Then: the portion of the message this class itself produces — everything before Jackson's
        // "(through reference chain: …)" suffix — names the bound and the offending precision, and
        // never echoes the key's digits. This is what proves vertique's own hygiene; it must go red
        // if anyone ever inlines the value into the bound-violation message.
        String message = ex.getMessage();
        String prefix = message.split(" \\(through reference chain:", 2)[0];
        assertTrue(prefix.contains("100"), "message prefix must name the 100-character bound: " + prefix);
        assertTrue(prefix.contains("precision=200"), "message prefix must name the offending precision: " + prefix);
        assertFalse(prefix.contains(digits), "message prefix must never echo the offending key's digits: " + prefix);
        // Length-bound the prefix too: assertFalse(prefix.contains(digits)) alone only rules out the
        // COMPLETE 200-digit key appearing verbatim — a truncated echo of the key's digits would still
        // pass that check. A short, bounded prefix length rules out ANY partial echo regardless of
        // which digit substring might otherwise slip through.
        assertTrue(prefix.length() < 150, "message prefix must stay short and value-free, was: " + prefix);
    }

    @Test
    @DisplayName("over-bound BigDecimal map key rejection: Jackson's reference chain still carries the key text")
    void bigDecimalMapKey_overBound_jacksonAppendsKeyToReferenceChain() {
        // Given: the vertique-strict mapper and a map keyed by a 200-digit BigDecimal whose
        // plain-string form (precision 200) exceeds the 100-character write-side bound.
        ObjectMapper mapper = strictMapper();
        String digits = "9".repeat(200);
        Map<BigDecimal, String> value = Map.of(new BigDecimal(new BigInteger(digits)), "x");

        // When: writing is rejected.
        JsonProcessingException ex =
                assertThrows(JsonProcessingException.class, () -> mapper.writeValueAsString(value));

        // Then: Jackson's MapSerializer wraps the throw under the default WRAP_EXCEPTIONS feature and
        // appends a "(through reference chain: …)" suffix naming the offending key's toString() form —
        // a boundary outside this profile's control. This pins that third-party behavior, so a Jackson
        // upgrade or a future chain-suppression change turns it red.
        String message = ex.getMessage();
        assertTrue(
                message.contains("through reference chain"),
                "message must carry Jackson's reference chain: " + message);
        assertTrue(
                message.contains(digits), "reference chain must carry the offending key's toString() form: " + message);
    }

    @Test
    @DisplayName("a Map with BigDecimal values (not keys) still round-trips")
    void mapWithBigDecimalValues_stillRoundTrips() throws Exception {
        // Given: the vertique-strict mapper and a map with a BigDecimal value (not a key).
        ObjectMapper mapper = strictMapper();
        Map<String, BigDecimal> original = Map.of("x", new BigDecimal("1.50"));

        // When: the map is written, then read back.
        String json = mapper.writeValueAsString(original);
        Map<String, BigDecimal> roundTripped = mapper.readValue(json, new TypeReference<Map<String, BigDecimal>>() {});

        // Then: the value-side round-trip is unaffected by the new key serializer.
        assertEquals(0, roundTripped.get("x").compareTo(new BigDecimal("1.50")));
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

    // --- T008 TP-005 (FR-017, AC-017.1): repeated keys, per mapper and through the process codec ---

    /** A body repeating an identical key, which every JSON parser is free to accept or refuse. */
    private static final String REPEATED_NOTE = "{\"note\":\"a\",\"note\":\"b\"}";

    /** The same shape at a typed property, read with {@code readValue} rather than {@code readTree}. */
    private static final String REPEATED_QUANTITY = "{\"quantity\":1,\"quantity\":2}";

    /** An alias beside its primary spelling: two different keys, so no parser sees a duplicate. */
    private static final String TWO_SPELLINGS = "{\"quantity\":1,\"qty\":2}";

    /**
     * T008 TP-005 (AC-017.1, the direct-read half). {@code vertique-strict}'s mapper enables
     * {@code JsonParser.Feature.STRICT_DUPLICATE_DETECTION}, so it refuses a body repeating an
     * identical key on both read paths, while {@code vertique} and {@code system} accept it and keep
     * the last value.
     *
     * <p>Behavior-change: at T008's parent every built-in mapper accepts both bodies (design proof
     * v4, parser run). The enabled feature is also what FR-016's strict schema rule reads, so a
     * mapper that stopped enabling it would silently turn every {@code oneOf} alias rule back into an
     * {@code anyOf}.
     *
     * @throws Exception when a lenient read fails, which would itself be the finding
     */
    @Test
    @DisplayName("Only vertique-strict rejects a repeated identical key on a direct read")
    void repeatedKeyIsRejectedOnDirectRead() {
        ObjectMapper strict = strictMapper();
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());

        // assertAll, so the strict reds and the lenient characterizations are all observed in one run:
        // the lenient rows are what makes the strict rejection a profile difference rather than a
        // parser-wide one, and a first failure must not hide them.
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> {
            JsonProcessingException repeatedTree = assertThrows(
                    JsonProcessingException.class,
                    () -> strict.readTree(REPEATED_NOTE),
                    "vertique-strict must refuse a repeated identical key on readTree: its mapper is the one a"
                            + " REST route under that profile parses raw body bytes with");
            assertTrue(
                    repeatedTree.getMessage().contains("Duplicate field"),
                    "the rejection must be Jackson's duplicate-field parse error, not a binding failure;" + " message: "
                            + repeatedTree.getMessage());
        });
        checks.add(() -> {
            JsonProcessingException repeatedValue = assertThrows(
                    JsonProcessingException.class,
                    () -> strict.readValue(REPEATED_QUANTITY, AliasedQuantity.class),
                    "vertique-strict must refuse a repeated identical key on readValue too");
            assertTrue(
                    repeatedValue.getMessage().contains("Duplicate field"),
                    "the readValue rejection must also be the duplicate-field parse error; message: "
                            + repeatedValue.getMessage());
        });
        for (JsonProfileId lenientId : List.of(VertiqueJsonMapperProfile.ID, JsonProfileId.SYSTEM)) {
            ObjectMapper lenient = registry.mapper(lenientId);
            checks.add(() -> assertEquals(
                    "b",
                    lenient.readTree(REPEATED_NOTE).get("note").asText(),
                    lenientId.value() + " must accept a repeated key and keep the last value: FR-017 changes"
                            + " vertique-strict alone"));
            checks.add(() -> assertEquals(
                    2,
                    lenient.readValue(REPEATED_QUANTITY, AliasedQuantity.class).quantity,
                    lenientId.value() + " must bind the last repeated value on readValue too"));
        }
        assertAll(checks);
    }

    /**
     * T008 TP-005 (AC-017.1, the two-spellings half). An alias sent beside its primary spelling is
     * two different keys, so no built-in mapper refuses it — which is why the one-spelling rule under
     * {@code vertique-strict} has to be the schema's and cannot be the parser's.
     *
     * <p>Behavior-preservation: green at T008's parent and after it. A mapper that started rejecting
     * the pair would make FR-016's strict rule redundant rather than stricter, and must be
     * re-recorded here rather than relaxed.
     *
     * @throws Exception when a read fails, which is the finding
     */
    @Test
    @DisplayName("Two spellings of one property are not duplicates to any built-in mapper")
    void twoSpellingsAreNotDuplicatesToAnyBuiltInMapper() throws Exception {
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());

        Integer strictBound =
                registry.mapper(VERTIQUE_STRICT_ID).readValue(TWO_SPELLINGS, AliasedQuantity.class).quantity;
        assertNotNull(
                strictBound,
                "vertique-strict must bind a body carrying both spellings: strict duplicate detection compares"
                        + " key names, and 'quantity' and 'qty' are different names");
        for (JsonProfileId lenientId : Set.of(VertiqueJsonMapperProfile.ID, JsonProfileId.SYSTEM)) {
            assertEquals(
                    strictBound,
                    registry.mapper(lenientId).readValue(TWO_SPELLINGS, AliasedQuantity.class).quantity,
                    lenientId.value() + " must bind the two spellings exactly as vertique-strict does: the binder"
                            + " accepts several spellings under every profile, so only the schema rule differs");
        }
    }

    /**
     * T008 TP-005 (AC-017.1, the process-codec half). With {@code vertique-strict} installed as the
     * process JSON codec's mapper — what {@code json.systemProfile: vertique-strict} does — the
     * codec's <em>delegated</em> decode methods reject a repeated identical key, while the streaming
     * {@code JacksonCodec} overloads and the static {@code DatabindCodec} parser helpers keep
     * accepting it. That exclusion is the codec's own documented contract; it is pinned here so a
     * claim of process-wide rejection cannot silently widen (design proof v7,
     * {@code runs/codec-coverage-v7.txt}).
     *
     * <p>Behavior-change for the five delegated entry points, behavior-preservation for the three
     * excluded ones.
     */
    @Test
    @DisplayName("An installed vertique-strict codec rejects a repeated key only in its delegated decode methods")
    void strictSystemProfileRejectsRepeatedKeyInTheCodecsDelegatedDecodeMethods() {
        assertTrue(
                VertiqueJson.ownsCodec(),
                "the framework codec must be the process codec, or installing a mapper proves nothing about"
                        + " Json.* at all");
        Buffer repeated = Buffer.buffer(REPEATED_NOTE);
        try {
            VertiqueJson.install(VERTIQUE_STRICT_ID, strictMapper());
            JacksonCodec codec = (JacksonCodec) Json.CODEC;

            // assertAll, so the five delegated rejections and the three documented exclusions are all
            // observed in one run: the exclusions are the half that keeps the claim from widening.
            assertAll(
                    () -> assertThrows(
                            DecodeException.class,
                            () -> new JsonObject(REPEATED_NOTE),
                            "new JsonObject(String) decodes through the installed mapper and must reject the"
                                    + " repeated key"),
                    () -> assertThrows(
                            DecodeException.class,
                            () -> new JsonObject(repeated),
                            "new JsonObject(Buffer) must reject it too"),
                    () -> assertThrows(
                            DecodeException.class,
                            () -> Json.decodeValue(repeated),
                            "Json.decodeValue(Buffer) is what RequestBody.asJsonObject() calls, and must reject"
                                    + " it"),
                    () -> assertThrows(
                            DecodeException.class,
                            () -> Json.decodeValue(REPEATED_NOTE),
                            "Json.decodeValue(String) must reject it"),
                    () -> assertThrows(
                            DecodeException.class,
                            () -> Json.decodeValue(REPEATED_NOTE, JsonObject.class),
                            "Json.decodeValue(String, Class) must reject it"),
                    () -> assertEquals(
                            "b",
                            ((JsonObject) codec.fromString(REPEATED_NOTE)).getString("note"),
                            "the streaming fromString(String) overload parses with the codec class's own static"
                                    + " factory, not the installed mapper, so it keeps accepting the repeated key"
                                    + " and keeps the last value — the exclusion the codec's contract documents"),
                    () -> assertEquals(
                            "b",
                            ((JsonObject) codec.fromBuffer(repeated)).getString("note"),
                            "the streaming fromBuffer(Buffer) overload is excluded on the same grounds"),
                    () -> assertEquals(
                            "b",
                            ((JsonObject) DatabindCodec.fromParser(
                                            DatabindCodec.createParser(REPEATED_NOTE), Object.class))
                                    .getString("note"),
                            "the static DatabindCodec parser helpers never consult the installed mapper either"));
        } finally {
            VertiqueJson.resetForTests();
        }
    }

    // --- Test fixtures ---

    /**
     * Test bean carrying an aliased {@link Integer} property, used to read a repeated key at a typed
     * position and to read an alias beside its primary spelling.
     *
     * <p>A plain bean rather than a record on purpose: Jackson binds a record through creator
     * properties, and an alias on a creator property with no fallback field or setter is refused with
     * {@code No fallback setter/field defined for creator property}. That is a Jackson limitation on
     * the binding side, not a property of this profile, and it is out of this task's scope — the
     * schema side of a record-component alias is proven by {@code AliasDescriptionTest}.
     */
    static final class AliasedQuantity {

        /** The aliased quantity, bound under either spelling. */
        @JsonAlias("qty")
        public Integer quantity;
    }

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
