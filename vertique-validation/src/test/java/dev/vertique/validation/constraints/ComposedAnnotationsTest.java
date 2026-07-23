// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that composed constraint annotations ({@link DigitsOnly}, {@link AlphaNumeric},
 * {@link PersonName}, {@link AddressLine}, {@link UnicodePrintableNoEmojiText}) enforce
 * their respective character policies via Jakarta Bean Validation bootstrap.
 */
class ComposedAnnotationsTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Nested
    @DisplayName("@DigitsOnly")
    class DigitsOnlyTests {
        @Test
        @DisplayName("only digits passes")
        void shouldPassDigitsOnly() {
            assertTrue(validator.validate(new DigitsHolder("12345")).isEmpty());
        }

        @Test
        @DisplayName("letters fail")
        void shouldFailLetters() {
            assertFalse(validator.validate(new DigitsHolder("123abc")).isEmpty());
        }

        @Test
        @DisplayName("null passes (not required)")
        void shouldPassNull() {
            assertTrue(validator.validate(new DigitsHolder(null)).isEmpty());
        }
    }

    @Nested
    @DisplayName("@AlphaNumeric")
    class AlphaNumericTests {
        @Test
        @DisplayName("letters and digits pass")
        void shouldPassAlphaNumeric() {
            assertTrue(validator.validate(new AlphaNumericHolder("abc123XYZ")).isEmpty());
        }

        @Test
        @DisplayName("hyphen fails")
        void shouldFailHyphen() {
            assertFalse(validator.validate(new AlphaNumericHolder("abc-123")).isEmpty());
        }

        @Test
        @DisplayName("space fails")
        void shouldFailSpace() {
            assertFalse(validator.validate(new AlphaNumericHolder("abc 123")).isEmpty());
        }
    }

    @Nested
    @DisplayName("@PersonName")
    class PersonNameTests {
        @Test
        @DisplayName("Unicode name passes")
        void shouldPassUnicodeName() {
            assertTrue(validator.validate(new PersonNameHolder("José García")).isEmpty());
        }

        @Test
        @DisplayName("name with apostrophe and hyphen passes")
        void shouldPassNameWithPunctuation() {
            assertTrue(validator.validate(new PersonNameHolder("O'Brien-Smith")).isEmpty());
        }

        @Test
        @DisplayName("digit in name fails")
        void shouldFailDigitInName() {
            assertFalse(validator.validate(new PersonNameHolder("John1")).isEmpty());
        }

        @Test
        @DisplayName("at-sign fails")
        void shouldFailAtSign() {
            assertFalse(validator.validate(new PersonNameHolder("john@doe")).isEmpty());
        }
    }

    @Nested
    @DisplayName("@AddressLine")
    class AddressLineTests {
        @Test
        @DisplayName("street address passes")
        void shouldPassStreetAddress() {
            assertTrue(validator
                    .validate(new AddressLineHolder("123 Main St, Suite #4"))
                    .isEmpty());
        }

        @Test
        @DisplayName("address with slash passes")
        void shouldPassAddressWithSlash() {
            assertTrue(validator.validate(new AddressLineHolder("Floor 3/5")).isEmpty());
        }

        @Test
        @DisplayName("semicolon fails")
        void shouldFailSemicolon() {
            assertFalse(validator.validate(new AddressLineHolder("addr; sql")).isEmpty());
        }
    }

    @Nested
    @DisplayName("@UnicodePrintableNoEmojiText")
    class UnicodePrintableNoEmojiTextTests {
        @Test
        @DisplayName("plain text passes")
        void shouldPassPlainText() {
            assertTrue(validator
                    .validate(new UnicodePrintableNoEmojiTextHolder("Hello World"))
                    .isEmpty());
        }

        @Test
        @DisplayName("emoji fails")
        void shouldFailEmoji() {
            assertFalse(validator
                    .validate(new UnicodePrintableNoEmojiTextHolder("Hello \uD83D\uDE00"))
                    .isEmpty());
        }

        @Test
        @DisplayName("Unicode text without emoji passes")
        void shouldPassUnicodeTextWithoutEmoji() {
            assertTrue(validator
                    .validate(new UnicodePrintableNoEmojiTextHolder("Café résumé"))
                    .isEmpty());
        }
    }

    // --- Test records ---

    record DigitsHolder(@DigitsOnly String value) {}

    record AlphaNumericHolder(@AlphaNumeric String value) {}

    record PersonNameHolder(@PersonName String name) {}

    record AddressLineHolder(@AddressLine String address) {}

    record UnicodePrintableNoEmojiTextHolder(
            @UnicodePrintableNoEmojiText String text) {}
}
