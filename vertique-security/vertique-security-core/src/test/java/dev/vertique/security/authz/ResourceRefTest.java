// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResourceRef}.
 *
 * <p>Verifies: happy-path construction; null/blank {@code type} rejected; null {@code id} rejected;
 * empty-string {@code id} accepted (means "any resource of this type"); null {@code attributes}
 * treated as empty map; defensive copy of {@code attributes}.
 */
class ResourceRefTest {

    // --- happy path ---

    @Test
    @DisplayName("constructs ResourceRef with valid type and id")
    void happyPath() {
        ResourceRef ref = new ResourceRef("order", "order-123", Map.of("region", "eu-west"));
        assertEquals("order", ref.type());
        assertEquals("order-123", ref.id());
        assertEquals("eu-west", ref.attributes().get("region"));
    }

    @Test
    @DisplayName("empty-string id is accepted — means any resource of this type")
    void emptyIdAccepted() {
        assertDoesNotThrow(() -> new ResourceRef("order", "", Map.of()));
    }

    @Test
    @DisplayName("empty-string id round-trips")
    void emptyIdRoundTrips() {
        ResourceRef ref = new ResourceRef("order", "", Map.of());
        assertEquals("", ref.id());
    }

    // --- null rejection for type ---

    @Test
    @DisplayName("null type throws NullPointerException with message containing \"type\"")
    void nullTypeThrowsNpe() {
        NullPointerException ex =
                assertThrows(NullPointerException.class, () -> new ResourceRef(null, "id-1", Map.of()));
        assertTrue(ex.getMessage().contains("type"), "NPE message should mention 'type' but was: " + ex.getMessage());
    }

    // --- blank rejection for type ---

    @Test
    @DisplayName("empty type throws IllegalArgumentException")
    void emptyTypeThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceRef("", "id-1", Map.of()));
    }

    @Test
    @DisplayName("blank type throws IllegalArgumentException")
    void blankTypeThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceRef("   ", "id-1", Map.of()));
    }

    // --- null rejection for id ---

    @Test
    @DisplayName("null id throws NullPointerException with message containing \"id\"")
    void nullIdThrowsNpe() {
        NullPointerException ex =
                assertThrows(NullPointerException.class, () -> new ResourceRef("order", null, Map.of()));
        assertTrue(ex.getMessage().contains("id"), "NPE message should mention 'id' but was: " + ex.getMessage());
    }

    // --- null attributes treated as empty ---

    @Test
    @DisplayName("null attributes treated as empty map")
    void nullAttributesTreatedAsEmpty() {
        ResourceRef ref = new ResourceRef("order", "id-1", null);
        assertTrue(ref.attributes().isEmpty());
    }

    // --- defensive copy ---

    @Test
    @DisplayName("mutating source attributes map after construction does not affect record")
    void defensivelyCopiesAttributes() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        ResourceRef ref = new ResourceRef("order", "id-1", mutable);
        mutable.put("injected", "evil");
        assertEquals(1, ref.attributes().size(), "Attributes must not reflect mutation of source map");
    }

    @Test
    @DisplayName("attributes map returned by accessor is unmodifiable")
    void attributesIsUnmodifiable() {
        ResourceRef ref = new ResourceRef("order", "id-1", Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> ref.attributes().put("x", "y"));
    }

    @Test
    @DisplayName("attributes instance is not the original map reference")
    void attributesIsDefensiveCopy() {
        Map<String, Object> original = new HashMap<>(Map.of("k", "v"));
        ResourceRef ref = new ResourceRef("order", "id-1", original);
        assertNotSame(original, ref.attributes());
    }
}
