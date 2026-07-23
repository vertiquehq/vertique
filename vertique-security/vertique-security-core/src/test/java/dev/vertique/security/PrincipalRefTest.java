// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PrincipalRef}.
 *
 * <p>Verifies: happy-path construction for all four {@link PrincipalType} values; null {@code type}
 * rejected with {@link NullPointerException}; null {@code id} rejected with NPE; blank/empty
 * {@code id} rejected with {@link IllegalArgumentException}; null {@code attributes} treated as
 * empty map; defensive copy of {@code attributes}; returned map is unmodifiable;
 * {@code equals}/{@code hashCode} consistency.
 */
class PrincipalRefTest {

    // --- happy path ---

    @Test
    @DisplayName("constructs USER principal with valid fields")
    void happyPathUser() {
        PrincipalRef ref = new PrincipalRef(PrincipalType.USER, "user-123", Map.of("tenant", "acme"));
        assertEquals(PrincipalType.USER, ref.type());
        assertEquals("user-123", ref.id());
        assertEquals("acme", ref.attributes().get("tenant"));
    }

    @Test
    @DisplayName("constructs SERVICE principal with valid fields")
    void happyPathService() {
        PrincipalRef ref = new PrincipalRef(PrincipalType.SERVICE, "svc-payments", Map.of());
        assertEquals(PrincipalType.SERVICE, ref.type());
        assertEquals("svc-payments", ref.id());
    }

    @Test
    @DisplayName("constructs SYSTEM principal with valid fields")
    void happyPathSystem() {
        PrincipalRef ref = new PrincipalRef(PrincipalType.SYSTEM, "system:workflow", Map.of());
        assertEquals(PrincipalType.SYSTEM, ref.type());
        assertEquals("system:workflow", ref.id());
    }

    @Test
    @DisplayName("constructs ANONYMOUS principal with valid fields")
    void happyPathAnonymous() {
        PrincipalRef ref = new PrincipalRef(PrincipalType.ANONYMOUS, "anonymous", Map.of());
        assertEquals(PrincipalType.ANONYMOUS, ref.type());
        assertEquals("anonymous", ref.id());
    }

    // --- null rejection ---

    @Test
    @DisplayName("null type throws NullPointerException with message containing \"type\"")
    void nullTypeThrowsNpe() {
        NullPointerException ex =
                assertThrows(NullPointerException.class, () -> new PrincipalRef(null, "user-1", Map.of()));
        assertTrue(ex.getMessage().contains("type"), "NPE message should mention 'type' but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("null id throws NullPointerException with message containing \"id\"")
    void nullIdThrowsNpe() {
        NullPointerException ex =
                assertThrows(NullPointerException.class, () -> new PrincipalRef(PrincipalType.USER, null, Map.of()));
        assertTrue(ex.getMessage().contains("id"), "NPE message should mention 'id' but was: " + ex.getMessage());
    }

    // --- blank rejection ---

    @Test
    @DisplayName("empty id throws IllegalArgumentException")
    void emptyIdThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new PrincipalRef(PrincipalType.USER, "", Map.of()));
    }

    @Test
    @DisplayName("blank id throws IllegalArgumentException")
    void blankIdThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new PrincipalRef(PrincipalType.USER, "   ", Map.of()));
    }

    // --- null attributes treated as empty ---

    @Test
    @DisplayName("null attributes treated as empty map — no NullPointerException thrown")
    void nullAttributesTreatedAsEmpty() {
        PrincipalRef ref = new PrincipalRef(PrincipalType.USER, "user-1", null);
        assertTrue(ref.attributes().isEmpty());
    }

    // --- defensive copy ---

    @Test
    @DisplayName("mutating source attributes map after construction does not affect record")
    void defensivelyCopiesAttributes() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        PrincipalRef ref = new PrincipalRef(PrincipalType.USER, "user-1", mutable);
        mutable.put("injected", "evil");
        assertEquals(1, ref.attributes().size(), "Attributes must not reflect mutation of source map");
    }

    @Test
    @DisplayName("attributes map returned by accessor is unmodifiable")
    void attributesIsUnmodifiable() {
        PrincipalRef ref = new PrincipalRef(PrincipalType.USER, "user-1", Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> ref.attributes().put("x", "y"));
    }

    @Test
    @DisplayName("attributes instance returned is not the original map reference")
    void attributesIsDefensiveCopy() {
        Map<String, Object> original = new HashMap<>(Map.of("k", "v"));
        PrincipalRef ref = new PrincipalRef(PrincipalType.USER, "user-1", original);
        assertNotSame(original, ref.attributes());
    }

    // --- equals / hashCode ---

    @Test
    @DisplayName("two PrincipalRefs with same fields are equal")
    void equalityHolds() {
        PrincipalRef a = new PrincipalRef(PrincipalType.USER, "user-1", Map.of("k", "v"));
        PrincipalRef b = new PrincipalRef(PrincipalType.USER, "user-1", Map.of("k", "v"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("reflexive equality — record equals itself")
    void reflexiveEquality() {
        PrincipalRef ref = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
        assertEquals(ref, ref);
    }
}
