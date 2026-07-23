// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link PersonNamePolicy} accepts Unicode names across scripts (Latin, CJK,
 * Arabic) with common name punctuation, and rejects control characters and disallowed symbols.
 */
class PersonNamePolicyTest {

    private static final InputValueContext CTX =
            new InputValueContext(InputLocation.BODY, "name", "name", Object.class);

    private final PersonNamePolicy policy = new PersonNamePolicy();

    @Test
    @DisplayName("null value passes")
    void shouldPassNull() {
        assertTrue(policy.validate(null, CTX).valid());
    }

    @Test
    @DisplayName("simple ASCII name passes")
    void shouldPassSimpleName() {
        assertTrue(policy.validate("John Doe", CTX).valid());
    }

    @Test
    @DisplayName("name with hyphen passes")
    void shouldPassNameWithHyphen() {
        assertTrue(policy.validate("Anne-Marie", CTX).valid());
    }

    @Test
    @DisplayName("name with apostrophe passes")
    void shouldPassNameWithApostrophe() {
        assertTrue(policy.validate("O'Brien", CTX).valid());
    }

    @Test
    @DisplayName("name with period passes")
    void shouldPassNameWithPeriod() {
        assertTrue(policy.validate("Dr. Smith", CTX).valid());
    }

    @Test
    @DisplayName("name with comma passes")
    void shouldPassNameWithComma() {
        assertTrue(policy.validate("Smith, John", CTX).valid());
    }

    @Test
    @DisplayName("Latin extended name passes")
    void shouldPassLatinExtendedName() {
        assertTrue(policy.validate("José García", CTX).valid());
    }

    @Test
    @DisplayName("CJK name passes")
    void shouldPassCjkName() {
        assertTrue(policy.validate("田中 太郎", CTX).valid());
    }

    @Test
    @DisplayName("Arabic name passes")
    void shouldPassArabicName() {
        assertTrue(policy.validate("محمد علي", CTX).valid());
    }

    @Test
    @DisplayName("control character fails")
    void shouldFailControlCharacter() {
        assertFalse(policy.validate("John\u0001Doe", CTX).valid());
    }

    @Test
    @DisplayName("at-sign fails")
    void shouldFailAtSign() {
        assertFalse(policy.validate("john@doe", CTX).valid());
    }

    @Test
    @DisplayName("digit fails")
    void shouldFailDigit() {
        assertFalse(policy.validate("John1", CTX).valid());
    }
}
