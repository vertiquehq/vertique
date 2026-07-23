// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.PrincipalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PrincipalKey} — the capability-minimized durable principal key consumed by
 * {@link PrincipalAuthorityResolver} (PRD identity-002 §14.3 Phase-2 Appendix, A8/FR-ID-CA-012).
 */
class PrincipalKeyTest {

    @Test
    @DisplayName("a blank id is rejected with IllegalArgumentException")
    void blankIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PrincipalKey(PrincipalType.USER, "   "));
        assertThrows(IllegalArgumentException.class, () -> new PrincipalKey(PrincipalType.USER, ""));
    }

    @Test
    @DisplayName("a null id is rejected with NullPointerException")
    void nullIdRejected() {
        NullPointerException ex =
                assertThrows(NullPointerException.class, () -> new PrincipalKey(PrincipalType.USER, null));
        assertTrue(ex.getMessage().contains("id"), "NPE message should mention 'id' but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("a null type is rejected with NullPointerException")
    void nullTypeRejected() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> new PrincipalKey(null, "user-1"));
        assertTrue(ex.getMessage().contains("type"), "NPE message should mention 'type' but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("two keys with the same (type, id) are equal; a differing type or id is not")
    void equalityByTypeAndId() {
        PrincipalKey a = new PrincipalKey(PrincipalType.USER, "user-1");
        PrincipalKey b = new PrincipalKey(PrincipalType.USER, "user-1");
        PrincipalKey differentId = new PrincipalKey(PrincipalType.USER, "user-2");
        PrincipalKey differentType = new PrincipalKey(PrincipalType.SERVICE, "user-1");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, differentId);
        assertNotEquals(a, differentType);
    }
}
