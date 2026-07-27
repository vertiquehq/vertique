// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

    // --- Read-side message hygiene (json-004) ---

    @Nested
    @DisplayName("Read-side rejection message hygiene")
    class ReadRejectionMessageHygiene {

        @Test
        @DisplayName("malformed string \"abc\" rejection message states the rejected length, never echoes the value")
        void malformedString_rejectionMessage_omitsValue() {
            MismatchedInputException ex = assertThrows(
                    MismatchedInputException.class, () -> mapper.readValue("{\"amount\":\"abc\"}", MoneyWrapper.class));

            String message = ex.getMessage();
            assertFalse(message.contains("abc"), "message must never echo the rejected value: " + message);
            assertTrue(message.contains("3"), "message must name the rejected value's length (3): " + message);
        }

        @Test
        @DisplayName("exponent literal \"1e-2000000000\" rejection message states the length, never echoes the value")
        void exponentLiteral_rejectionMessage_omitsValue() {
            MismatchedInputException ex = assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"amount\":\"1e-2000000000\"}", MoneyWrapper.class));

            String message = ex.getMessage();
            assertFalse(
                    message.contains("1e-2000000000"),
                    "message must never echo the rejected exponent literal: " + message);
        }
    }

    // --- Write-side length bound (json-004) ---

    @Nested
    @DisplayName("Write-side length bound")
    class WriteBound {

        @Test
        @DisplayName("101-character plain form (1E+100) rejected on write; message states the bound, not the digits")
        void hugeScale_rejectedOnWrite_messageOmitsDigits() {
            MoneyWrapper huge = new MoneyWrapper(new BigDecimal("1E+100"));

            JsonMappingException ex = assertThrows(
                    JsonMappingException.class,
                    () -> mapper.writeValueAsString(huge),
                    "a 101-character plain-string BigDecimal must be rejected on write");

            String message = ex.getMessage();
            assertTrue(message.contains("100"), "message must name the 100-character bound: " + message);
            assertFalse(
                    message.contains("1" + "0".repeat(100)),
                    "message must never echo the offending value's digits: " + message);
        }

        @Test
        @DisplayName("100-character plain form serializes fine (length boundary)")
        void boundaryLength_serializesFine() throws Exception {
            String literal = "1".repeat(100);
            MoneyWrapper value = new MoneyWrapper(new BigDecimal(literal));

            String json = mapper.writeValueAsString(value);

            assertEquals("{\"amount\":\"" + literal + "\"}", json, "a 100-character plain form must serialize as-is");
        }

        @Test
        @DisplayName("100-character signed fractional form serializes fine (large-precision boundary shape)")
        void boundaryLengthSignedFraction_serializesFine() throws Exception {
            // 1 sign + 50 integer digits + 1 point + 48 fraction digits = 100 characters
            String literal = "-" + "9".repeat(50) + "." + "9".repeat(48);
            MoneyWrapper value = new MoneyWrapper(new BigDecimal(literal));

            String json = mapper.writeValueAsString(value);

            assertEquals(
                    "{\"amount\":\"" + literal + "\"}", json, "a 100-character signed fraction must serialize as-is");
        }

        @Test
        @DisplayName("scale-98/precision-2 shape (\"0.0…01\") is exactly 100 characters and serializes fine")
        void largeScaleShallowPrecision_serializesFine() throws Exception {
            // unscaled value 1, scale 98: "0." (2 chars) + 97 zeros + "1" = 100 characters total.
            BigDecimal value = new BigDecimal(BigInteger.ONE, 98);
            String expectedPlain = value.toPlainString();
            assertEquals(100, expectedPlain.length(), "test fixture sanity check: the plain form must be 100 chars");

            String json = mapper.writeValueAsString(new MoneyWrapper(value));

            assertEquals(
                    "{\"amount\":\"" + expectedPlain + "\"}",
                    json,
                    "a 100-character large-scale shape must serialize as-is");
        }

        @Test
        @Timeout(value = 2, unit = TimeUnit.SECONDS)
        @DisplayName("huge-scale value (scale 2 billion) rejected without materializing toPlainString (bounded time)")
        void hugeScale_rejectedWithoutMaterializing() {
            // unscaled value 1, scale 2_000_000_000: toPlainString() would otherwise materialize
            // ~2 billion characters. The cheap scale/precision pre-check must reject this before
            // ever calling toPlainString(), so this test completes in milliseconds, not minutes.
            BigDecimal pathological = new BigDecimal(BigInteger.ONE, 2_000_000_000);
            MoneyWrapper value = new MoneyWrapper(pathological);

            assertThrows(
                    JsonMappingException.class,
                    () -> mapper.writeValueAsString(value),
                    "a BigDecimal with a 2-billion scale must be rejected before toPlainString() runs");
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
