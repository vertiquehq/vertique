// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DelegationReasonCodes}.
 *
 * <p>Verifies that every public {@code String} constant in the vocabulary is non-null and non-blank,
 * and spot-checks the frozen {@code GRANT_LOOKUP_FAILED} wire value mandated by
 * {@link DelegationGrantValidator}'s fail-closed contract.
 */
class DelegationReasonCodesTest {

    @Test
    @DisplayName("every public String constant is non-null and non-blank")
    void constants_notNull() throws IllegalAccessException {
        int count = 0;
        for (Field field : DelegationReasonCodes.class.getDeclaredFields()) {
            if (isPublicStaticFinalString(field)) {
                Object value = field.get(null);
                assertNotNull(value, "constant " + field.getName() + " must not be null");
                assertFalse(((String) value).isBlank(), "constant " + field.getName() + " must not be blank");
                count++;
            }
        }
        // Guard against an empty reflective sweep silently passing.
        assertEquals(5, count, "expected the full delegation reason-code vocabulary to be present");
    }

    @Test
    @DisplayName("spot-checked constants carry their exact stable string values")
    void constants_exactValues() {
        assertEquals("GRANT_VALID", DelegationReasonCodes.GRANT_VALID);
        assertEquals("GRANT_NOT_FOUND", DelegationReasonCodes.GRANT_NOT_FOUND);
        assertEquals("GRANT_EXPIRED", DelegationReasonCodes.GRANT_EXPIRED);
        assertEquals("GRANT_OUT_OF_SCOPE", DelegationReasonCodes.GRANT_OUT_OF_SCOPE);
        assertEquals("GRANT_LOOKUP_FAILED", DelegationReasonCodes.GRANT_LOOKUP_FAILED);
    }

    private static boolean isPublicStaticFinalString(Field field) {
        int mods = field.getModifiers();
        return field.getType() == String.class
                && Modifier.isPublic(mods)
                && Modifier.isStatic(mods)
                && Modifier.isFinal(mods);
    }
}
