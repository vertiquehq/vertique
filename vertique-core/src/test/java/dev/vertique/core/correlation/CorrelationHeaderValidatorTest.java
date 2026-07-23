// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationHeaderValidator}.
 *
 * <p>Verifies RFC 7230 token grammar enforcement for header names and the value allow-list
 * pattern for header values; {@code requireValid*} methods throw {@link IllegalArgumentException}
 * with descriptive messages; {@code sanitizeForResponse} passes through valid values and
 * returns {@link Optional#empty()} for invalid ones.
 */
class CorrelationHeaderValidatorTest {

    // --- Header Name ---

    @Nested
    @DisplayName("HeaderName")
    class HeaderName {

        @Test
        @DisplayName("standard correlation header names are valid")
        void standardCorrelationHeaderNamesValid() {
            assertTrue(CorrelationHeaderValidator.isValidHeaderName("X-Request-Id"));
            assertTrue(CorrelationHeaderValidator.isValidHeaderName("X-Correlation-Id"));
            assertTrue(CorrelationHeaderValidator.isValidHeaderName("X-FAPI-Interaction-ID"));
            assertTrue(CorrelationHeaderValidator.isValidHeaderName("traceparent"));
            assertTrue(CorrelationHeaderValidator.isValidHeaderName("b3"));
        }

        @Test
        @DisplayName("valid token grammar characters accepted")
        void tokenGrammarCharactersAccepted() {
            // RFC 7230 token: !#$%&'*+-.^_`|~0-9A-Za-z
            assertTrue(CorrelationHeaderValidator.isValidHeaderName("!#$%&'*+-.^_`|~0Az9"));
        }

        @Test
        @DisplayName("empty name is invalid")
        void emptyNameInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderName(""));
        }

        @Test
        @DisplayName("blank name is invalid")
        void blankNameInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderName("   "));
        }

        @Test
        @DisplayName("name longer than 64 characters is invalid")
        void tooLongNameInvalid() {
            String longName = "X-".repeat(33); // 66 chars
            assertFalse(CorrelationHeaderValidator.isValidHeaderName(longName));
        }

        @Test
        @DisplayName("name exactly 64 characters is valid")
        void exactly64CharactersValid() {
            String name = "A".repeat(64);
            assertTrue(CorrelationHeaderValidator.isValidHeaderName(name));
        }

        @Test
        @DisplayName("name with space is invalid")
        void spaceInNameInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderName("X-Request Id"));
        }

        @Test
        @DisplayName("name with comma is invalid")
        void commaInNameInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderName("X-Req,Id"));
        }

        @Test
        @DisplayName("name with colon is invalid")
        void colonInNameInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderName("X-Req:Id"));
        }

        @Test
        @DisplayName("name with parentheses is invalid")
        void parenthesesInNameInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderName("X-Req(Id)"));
        }

        @Test
        @DisplayName("null name is invalid (returns false)")
        void nullNameInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderName(null));
        }

        @Test
        @DisplayName("requireValidHeaderName passes for valid name")
        void requireValidHeaderNamePassesForValid() {
            // Must not throw
            CorrelationHeaderValidator.requireValidHeaderName("X-Request-Id");
        }

        @Test
        @DisplayName("requireValidHeaderName throws IAE with descriptive message for invalid name")
        void requireValidHeaderNameThrowsIaeWithMessage() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> CorrelationHeaderValidator.requireValidHeaderName("X-Bad Header"));
            assertTrue(ex.getMessage().contains("X-Bad Header")
                    || ex.getMessage().toLowerCase().contains("header"));
        }
    }

    // --- Header Value ---

    @Nested
    @DisplayName("HeaderValue")
    class HeaderValue {

        @Test
        @DisplayName("UUID-shaped value is valid")
        void uuidShapedValueValid() {
            assertTrue(CorrelationHeaderValidator.isValidHeaderValue("550e8400-e29b-41d4-a716-446655440000"));
        }

        @Test
        @DisplayName("ULID-shaped value is valid")
        void ulidShapedValueValid() {
            // ULID: 26 chars, [0-9A-Z]
            assertTrue(CorrelationHeaderValidator.isValidHeaderValue("01ARZ3NDEKTSV4RRFFQ69G5FAV"));
        }

        @Test
        @DisplayName("alphanumeric with safe special chars is valid")
        void alphanumericWithSafeCharsValid() {
            // Allowed: A-Za-z0-9 . _ ~ : / + = -
            assertTrue(CorrelationHeaderValidator.isValidHeaderValue("abc.DEF_123~:/+=some-value"));
        }

        @Test
        @DisplayName("empty value is invalid")
        void emptyValueInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderValue(""));
        }

        @Test
        @DisplayName("blank value is invalid")
        void blankValueInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderValue("   "));
        }

        @Test
        @DisplayName("value longer than 128 characters is invalid")
        void tooLongValueInvalid() {
            String longValue = "a".repeat(129);
            assertFalse(CorrelationHeaderValidator.isValidHeaderValue(longValue));
        }

        @Test
        @DisplayName("value exactly 128 characters is valid")
        void exactly128CharactersValid() {
            String value = "a".repeat(128);
            assertTrue(CorrelationHeaderValidator.isValidHeaderValue(value));
        }

        @Test
        @DisplayName("value with CR is invalid")
        void crInValueInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderValue("val\rue"));
        }

        @Test
        @DisplayName("value with LF is invalid")
        void lfInValueInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderValue("val\nue"));
        }

        @Test
        @DisplayName("value with NUL is invalid")
        void nulInValueInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderValue("val" + (char) 0x00 + "ue"));
        }

        @Test
        @DisplayName("value with control character is invalid")
        void controlCharInValueInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderValue("val" + (char) 0x07 + "ue"));
        }

        @Test
        @DisplayName("value with non-ASCII character is invalid")
        void nonAsciiInValueInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderValue("valéue"));
        }

        @Test
        @DisplayName("value with space is invalid (outside allowed pattern)")
        void spaceInValueInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderValue("val ue"));
        }

        @Test
        @DisplayName("null value is invalid (returns false)")
        void nullValueInvalid() {
            assertFalse(CorrelationHeaderValidator.isValidHeaderValue(null));
        }

        @Test
        @DisplayName("requireValidHeaderValue passes for valid value")
        void requireValidHeaderValuePassesForValid() {
            // Must not throw
            CorrelationHeaderValidator.requireValidHeaderValue("abc-123");
        }

        @Test
        @DisplayName("requireValidHeaderValue throws IAE with descriptive message for invalid value")
        void requireValidHeaderValueThrowsIaeWithMessage() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> CorrelationHeaderValidator.requireValidHeaderValue("bad\nvalue"));
            assertTrue(ex.getMessage() != null && !ex.getMessage().isEmpty(), "Exception message must be descriptive");
        }
    }

    // --- sanitizeForResponse ---

    @Nested
    @DisplayName("sanitizeForResponse")
    class SanitizeForResponse {

        @Test
        @DisplayName("returns value when valid")
        void returnsValueWhenValid() {
            Optional<String> result = CorrelationHeaderValidator.sanitizeForResponse("abc-123");
            assertTrue(result.isPresent());
            assertEquals("abc-123", result.get());
        }

        @Test
        @DisplayName("returns Optional.empty when value is invalid")
        void returnsEmptyWhenInvalid() {
            Optional<String> result = CorrelationHeaderValidator.sanitizeForResponse("bad\nvalue");
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("returns Optional.empty for null value")
        void returnsEmptyForNull() {
            Optional<String> result = CorrelationHeaderValidator.sanitizeForResponse(null);
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("returns Optional.empty for blank value")
        void returnsEmptyForBlank() {
            Optional<String> result = CorrelationHeaderValidator.sanitizeForResponse("   ");
            assertFalse(result.isPresent());
        }
    }
}
