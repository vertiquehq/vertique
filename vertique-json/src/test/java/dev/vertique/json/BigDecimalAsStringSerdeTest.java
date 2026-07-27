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
 *   <li>The accepted string grammar is bounded: only plain decimals
 *       ({@code -?[0-9]+(\.[0-9]+)?}) of at most 100 characters are accepted. Exponent notation
 *       ({@code "1e-2000000000"}) is rejected so a short wire literal can never amplify into a
 *       huge-scale {@code BigDecimal}.</li>
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

    // --- Grammar bounds ---

    @Nested
    @DisplayName("Grammar bounds")
    class GrammarBounds {

        /**
         * Builds a {@code MoneyWrapper} JSON document whose {@code amount} is the given raw string.
         *
         * @param literal the raw (already JSON-safe) string content of the {@code amount} field
         * @return the JSON document to feed the mapper
         */
        private String wrap(String literal) {
            return "{\"amount\":\"" + literal + "\"}";
        }

        // --- Rejected: exponent notation (scale amplification) ---

        @Test
        @DisplayName("exponent literal \"1e-2000000000\" rejected (scale amplification bound)")
        void hugeNegativeExponent_rejected() {
            // 14 wire characters would otherwise yield a BigDecimal of scale 2e9, whose
            // toPlainString() re-serialization materializes gigabytes of digits.
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue(wrap("1e-2000000000"), MoneyWrapper.class),
                    "An exponent literal with a huge negative exponent must be rejected");
        }

        @Test
        @DisplayName("exponent literal \"1E+2\" rejected (exponent notation is not in the grammar)")
        void uppercaseExponentWithPlus_rejected() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue(wrap("1E+2"), MoneyWrapper.class),
                    "Uppercase exponent notation must be rejected");
        }

        @Test
        @DisplayName("exponent literal \"1e5\" rejected (exponent notation is not in the grammar)")
        void lowercaseExponent_rejected() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue(wrap("1e5"), MoneyWrapper.class),
                    "Lowercase exponent notation must be rejected");
        }

        // --- Rejected: malformed literals outside the plain-decimal grammar ---

        @Test
        @DisplayName("hex literal \"0x1F\" rejected with MismatchedInputException")
        void hexLiteral_rejected() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue(wrap("0x1F"), MoneyWrapper.class),
                    "A hex literal must be rejected with MismatchedInputException");
        }

        @Test
        @DisplayName("leading plus \"+1.5\" rejected (not in the plain-decimal grammar)")
        void leadingPlus_rejected() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue(wrap("+1.5"), MoneyWrapper.class),
                    "A leading plus sign must be rejected");
        }

        @Test
        @DisplayName("missing integer part \".5\" rejected (not in the plain-decimal grammar)")
        void missingIntegerPart_rejected() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue(wrap(".5"), MoneyWrapper.class),
                    "A literal with no integer part must be rejected");
        }

        @Test
        @DisplayName("trailing point \"1.\" rejected (no fraction digits)")
        void trailingPoint_rejected() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue(wrap("1."), MoneyWrapper.class),
                    "A literal with a decimal point but no fraction digits must be rejected");
        }

        // --- Rejected: over-length literals ---

        @Test
        @DisplayName("101-character literal rejected (max length 100)")
        void overLengthLiteral_rejected() {
            String literal = "1".repeat(101);

            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue(wrap(literal), MoneyWrapper.class),
                    "A literal longer than 100 characters must be rejected");
        }

        // --- Accepted: plain decimals within the bound ---

        @Test
        @DisplayName("\"1.50\" accepted with its scale preserved")
        void plainDecimal_accepted_preservingScale() throws Exception {
            MoneyWrapper result = mapper.readValue(wrap("1.50"), MoneyWrapper.class);

            assertEquals(0, result.amount().compareTo(new BigDecimal("1.50")), "\"1.50\" must parse to 1.50");
            assertEquals(2, result.amount().scale(), "The wire scale must be preserved exactly");
        }

        @Test
        @DisplayName("negative decimal \"-0.001\" accepted")
        void negativeDecimal_accepted() throws Exception {
            MoneyWrapper result = mapper.readValue(wrap("-0.001"), MoneyWrapper.class);

            assertEquals(new BigDecimal("-0.001"), result.amount(), "\"-0.001\" must parse to -0.001");
        }

        @Test
        @DisplayName("integer literal \"0\" accepted")
        void zero_accepted() throws Exception {
            MoneyWrapper result = mapper.readValue(wrap("0"), MoneyWrapper.class);

            assertEquals(new BigDecimal("0"), result.amount(), "\"0\" must parse to 0");
        }

        @Test
        @DisplayName("100-character literal accepted (length boundary)")
        void boundaryLengthLiteral_accepted() throws Exception {
            String literal = "1".repeat(100);

            MoneyWrapper result = mapper.readValue(wrap(literal), MoneyWrapper.class);

            assertEquals(new BigDecimal(literal), result.amount(), "A 100-character literal is at the accepted bound");
        }

        @Test
        @DisplayName("100-character signed fractional literal accepted (length boundary)")
        void boundaryLengthSignedFraction_accepted() throws Exception {
            // 1 sign + 50 integer digits + 1 point + 48 fraction digits = 100 characters
            String literal = "-" + "9".repeat(50) + "." + "9".repeat(48);

            MoneyWrapper result = mapper.readValue(wrap(literal), MoneyWrapper.class);

            assertEquals(new BigDecimal(literal), result.amount(), "A long but legal plain decimal must be accepted");
            assertEquals(48, result.amount().scale(), "The wire scale must be preserved exactly");
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
