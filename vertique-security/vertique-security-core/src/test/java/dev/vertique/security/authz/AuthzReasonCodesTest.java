// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthzReasonCodes}.
 *
 * <p>Verifies that every public {@code String} constant in the stable reason-code vocabulary is
 * non-null and non-blank, and spot-checks that a representative subset carries its exact frozen
 * value. These constants are part of the published authorization contract (emitted on events and
 * returned in error responses), so their wire values must not drift.
 */
class AuthzReasonCodesTest {

    // --- non-null / non-blank invariant over the whole vocabulary ---

    @Test
    @DisplayName("every public String constant is non-null and non-blank")
    void constants_notNull() throws IllegalAccessException {
        int count = 0;
        for (Field field : AuthzReasonCodes.class.getDeclaredFields()) {
            if (isPublicStaticFinalString(field)) {
                Object value = field.get(null);
                assertNotNull(value, "constant " + field.getName() + " must not be null");
                assertFalse(((String) value).isBlank(), "constant " + field.getName() + " must not be blank");
                count++;
            }
        }
        // Guard against an empty reflective sweep silently passing.
        assertEquals(15, count, "expected the full stable reason-code vocabulary to be present");
    }

    // --- spot-check exact frozen values ---

    @Test
    @DisplayName("spot-checked constants carry their exact stable string values")
    void constants_lowercaseOrUnderscore() {
        assertEquals("PERMITTED", AuthzReasonCodes.PERMITTED);
        assertEquals("ACTION_NOT_ALLOWED", AuthzReasonCodes.ACTION_NOT_ALLOWED);
        assertEquals("ACTION_NOT_REGISTERED", AuthzReasonCodes.ACTION_NOT_REGISTERED);
        assertEquals("ROLE_MISSING", AuthzReasonCodes.ROLE_MISSING);
        assertEquals("DENY_ALL", AuthzReasonCodes.DENY_ALL);
        assertEquals("ROLE_POLICY_MISSING", AuthzReasonCodes.ROLE_POLICY_MISSING);
        assertEquals("POLICY_NOT_FOUND", AuthzReasonCodes.POLICY_NOT_FOUND);
        assertEquals("INTERNAL_AUTHZ_ERROR", AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
        assertEquals("AUTHORITY_RESOLUTION_FAILED", AuthzReasonCodes.AUTHORITY_RESOLUTION_FAILED);
        assertEquals("STEP_UP_REQUIRED", AuthzReasonCodes.STEP_UP_REQUIRED);
    }

    private static boolean isPublicStaticFinalString(Field field) {
        int mods = field.getModifiers();
        return field.getType() == String.class
                && Modifier.isPublic(mods)
                && Modifier.isStatic(mods)
                && Modifier.isFinal(mods);
    }
}
