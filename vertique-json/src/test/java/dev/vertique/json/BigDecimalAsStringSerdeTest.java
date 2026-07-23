// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the opt-in {@link BigDecimalAsStringSerializer} and
 * {@link BigDecimalStrictStringDeserializer} pair.
 *
 * <p>Registers the pair on a throwaway {@link ObjectMapper} and verifies:
 * <ul>
 *   <li>Serialization writes a JSON string (not a number).</li>
 *   <li>String-to-{@code BigDecimal}-to-string round-trips exactly.</li>
 *   <li>A JSON number input is rejected with {@link MismatchedInputException}.</li>
 *   <li>Invalid scalar tokens ({@code true}, {@code {}}, {@code []}) are rejected with
 *       {@link MismatchedInputException} (not {@link NumberFormatException} or NPE).</li>
 *   <li>A malformed numeric string ({@code "abc"}) is rejected with a clean mapping error.</li>
 *   <li>A whitespace-padded string ({@code " 1.5 "}) is rejected — the deserializer is strict
 *       and does not trim; only exact decimal literals are accepted.</li>
 *   <li>A JSON {@code null} is allowed and yields {@code null} (standard absent-value semantics;
 *       only non-{@code null}, non-string tokens are rejected).</li>
 * </ul>
 */
class BigDecimalAsStringSerdeTest {

    /** A wrapper record used to exercise the registered serde pair. */
    record MoneyWrapper(BigDecimal amount) {}

    private ObjectMapper mapper;

    /**
     * Builds a fresh {@link ObjectMapper} with the opt-in serde pair registered for {@link BigDecimal}.
     */
    @BeforeEach
    void setUp() {
        SimpleModule module = new SimpleModule("BigDecimalStringSerdeModule");
        module.addSerializer(BigDecimal.class, new BigDecimalAsStringSerializer());
        module.addDeserializer(BigDecimal.class, new BigDecimalStrictStringDeserializer());
        mapper = new ObjectMapper().registerModule(module);
    }

    // --- Serialization tests ---

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("BigDecimal serializes as a quoted JSON string (not a number)")
        void serialize_bigDecimal_writesQuotedString() throws Exception {
            String json = mapper.writeValueAsString(new MoneyWrapper(new BigDecimal("1.50")));

            // The output must contain "1.50" as a JSON string (quoted), not a bare number
            assertEquals("{\"amount\":\"1.50\"}", json, "BigDecimal must be serialized as a JSON string");
        }
    }

    // --- Round-trip tests ---

    @Nested
    @DisplayName("String round-trip")
    class RoundTrip {

        @Test
        @DisplayName("\"1.50\" round-trips: string → BigDecimal → \"1.50\" preserving scale")
        void stringRoundTrip_preservesScale() throws Exception {
            MoneyWrapper result = mapper.readValue("{\"amount\":\"1.50\"}", MoneyWrapper.class);
            String serialized = mapper.writeValueAsString(result);

            assertEquals("{\"amount\":\"1.50\"}", serialized, "Round-trip must preserve trailing zero and scale");
        }
    }

    // --- Rejection tests ---

    @Nested
    @DisplayName("Token rejection")
    class TokenRejection {

        @Test
        @DisplayName("JSON number token rejected with MismatchedInputException (string-only)")
        void jsonNumber_rejected_withMismatchedInputException() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"amount\":1.5}", MoneyWrapper.class),
                    "A bare JSON number must be rejected with MismatchedInputException");
        }

        @Test
        @DisplayName("boolean true rejected with MismatchedInputException")
        void booleanTrue_rejected_withMismatchedInputException() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"amount\":true}", MoneyWrapper.class),
                    "boolean true must be rejected with MismatchedInputException");
        }

        @Test
        @DisplayName("JSON object {} rejected with MismatchedInputException")
        void jsonObject_rejected_withMismatchedInputException() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"amount\":{}}", MoneyWrapper.class),
                    "JSON object must be rejected with MismatchedInputException");
        }

        @Test
        @DisplayName("JSON array [] rejected with MismatchedInputException")
        void jsonArray_rejected_withMismatchedInputException() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"amount\":[]}", MoneyWrapper.class),
                    "JSON array must be rejected with MismatchedInputException");
        }

        @Test
        @DisplayName("malformed string \"abc\" rejected with a clean mapping error (not NumberFormatException)")
        void malformedString_rejected_cleanMappingError() {
            // The exception must be MismatchedInputException (or a sub-type that is a Jackson mapping
            // exception), NOT a raw NumberFormatException escaping the deserializer
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"amount\":\"abc\"}", MoneyWrapper.class),
                    "A malformed decimal string must be rejected with MismatchedInputException");
        }

        @Test
        @DisplayName("whitespace-padded string \" 1.5 \" rejected with MismatchedInputException (strict: no trim)")
        void whitespacePaddedString_rejected_withMismatchedInputException() {
            // The deserializer must NOT silently trim the value — " 1.5 " is not a valid
            // BigDecimal literal and must be rejected the same way as any other malformed string.
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"amount\":\" 1.5 \"}", MoneyWrapper.class),
                    "A whitespace-padded decimal string must be rejected with MismatchedInputException");
        }
    }

    // --- Null handling ---

    @Nested
    @DisplayName("Null handling")
    class NullHandling {

        @Test
        @DisplayName("JSON null deserializes to a null BigDecimal (allowed; no MismatchedInputException)")
        void jsonNull_yieldsNull() throws Exception {
            // A JSON null is routed through Jackson's null-value provider, never reaching
            // the strict deserializer's deserialize() — so it is allowed and yields null.
            MoneyWrapper result = mapper.readValue("{\"amount\":null}", MoneyWrapper.class);

            assertNull(result.amount(), "A JSON null must deserialize to a null BigDecimal, not throw");
        }
    }
}
