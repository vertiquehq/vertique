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
 * Unit tests for {@link ClientRef}.
 *
 * <p>Verifies: happy-path construction with valid fields; null {@code clientId} and {@code source}
 * rejected with {@link NullPointerException}; blank/empty values rejected with
 * {@link IllegalArgumentException}; null {@code attributes} treated as empty map; defensive copy
 * of {@code attributes}; returned map is unmodifiable; {@code equals}/{@code hashCode}
 * consistency.
 */
class ClientRefTest {

    // --- happy path ---

    @Test
    @DisplayName("constructs ClientRef with valid fields")
    void happyPath() {
        ClientRef ref = new ClientRef("payments-app", "jwt-azp", Map.of("tier", "premium"));
        assertEquals("payments-app", ref.clientId());
        assertEquals("jwt-azp", ref.source());
        assertEquals("premium", ref.attributes().get("tier"));
    }

    @Test
    @DisplayName("constructs ClientRef with empty attributes")
    void happyPathEmptyAttributes() {
        ClientRef ref = new ClientRef("api-gateway", "api-key-registry", Map.of());
        assertEquals("api-gateway", ref.clientId());
        assertEquals("api-key-registry", ref.source());
        assertTrue(ref.attributes().isEmpty());
    }

    // --- null rejection ---

    @Test
    @DisplayName("null clientId throws NullPointerException with message containing \"clientId\"")
    void nullClientIdThrowsNpe() {
        NullPointerException ex =
                assertThrows(NullPointerException.class, () -> new ClientRef(null, "jwt-azp", Map.of()));
        assertTrue(
                ex.getMessage().contains("clientId"),
                "NPE message should mention 'clientId' but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("null source throws NullPointerException with message containing \"source\"")
    void nullSourceThrowsNpe() {
        NullPointerException ex =
                assertThrows(NullPointerException.class, () -> new ClientRef("my-client", null, Map.of()));
        assertTrue(
                ex.getMessage().contains("source"), "NPE message should mention 'source' but was: " + ex.getMessage());
    }

    // --- blank rejection ---

    @Test
    @DisplayName("empty clientId throws IllegalArgumentException")
    void emptyClientIdThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new ClientRef("", "jwt-azp", Map.of()));
    }

    @Test
    @DisplayName("blank clientId throws IllegalArgumentException")
    void blankClientIdThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new ClientRef("   ", "jwt-azp", Map.of()));
    }

    @Test
    @DisplayName("empty source throws IllegalArgumentException")
    void emptySourceThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new ClientRef("my-client", "", Map.of()));
    }

    @Test
    @DisplayName("blank source throws IllegalArgumentException")
    void blankSourceThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new ClientRef("my-client", "  ", Map.of()));
    }

    // --- null attributes treated as empty ---

    @Test
    @DisplayName("null attributes treated as empty map — no NullPointerException thrown")
    void nullAttributesTreatedAsEmpty() {
        ClientRef ref = new ClientRef("client-1", "jwt-azp", null);
        assertTrue(ref.attributes().isEmpty());
    }

    // --- defensive copy ---

    @Test
    @DisplayName("mutating source attributes map after construction does not affect record")
    void defensivelyCopiesAttributes() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        ClientRef ref = new ClientRef("client-1", "jwt-azp", mutable);
        mutable.put("injected", "evil");
        assertEquals(1, ref.attributes().size(), "Attributes must not reflect mutation of source map");
    }

    @Test
    @DisplayName("attributes map returned by accessor is unmodifiable")
    void attributesIsUnmodifiable() {
        ClientRef ref = new ClientRef("client-1", "jwt-azp", Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> ref.attributes().put("x", "y"));
    }

    @Test
    @DisplayName("attributes instance is not the original map reference")
    void attributesIsDefensiveCopy() {
        Map<String, Object> original = new HashMap<>(Map.of("k", "v"));
        ClientRef ref = new ClientRef("client-1", "jwt-azp", original);
        assertNotSame(original, ref.attributes());
    }

    // --- equals / hashCode ---

    @Test
    @DisplayName("two ClientRefs with same fields are equal")
    void equalityHolds() {
        ClientRef a = new ClientRef("c1", "jwt-azp", Map.of("tier", "gold"));
        ClientRef b = new ClientRef("c1", "jwt-azp", Map.of("tier", "gold"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("reflexive equality — record equals itself")
    void reflexiveEquality() {
        ClientRef ref = new ClientRef("c1", "jwt-azp", Map.of());
        assertEquals(ref, ref);
    }
}
