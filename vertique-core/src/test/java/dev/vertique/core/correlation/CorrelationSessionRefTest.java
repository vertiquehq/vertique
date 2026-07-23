// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationSessionRef}.
 *
 * <p>Verifies: happy-path construction; null/blank id/kind/source rejection; claimName is
 * nullable; defensive copy of attributes; attributes map is unmodifiable.
 */
class CorrelationSessionRefTest {

    // --- happy path ---

    @Test
    @DisplayName("constructs with all fields including non-null claimName")
    void happyPathWithClaimName() {
        CorrelationSessionRef ref = new CorrelationSessionRef("sess-001", "jwt", "auth-filter", "jti", false, Map.of());
        assertEquals("sess-001", ref.id());
        assertEquals("jwt", ref.kind());
        assertEquals("auth-filter", ref.source());
        assertEquals("jti", ref.claimName());
        assertTrue(ref.attributes().isEmpty());
    }

    @Test
    @DisplayName("constructs with null claimName")
    void happyPathNullClaimName() {
        CorrelationSessionRef ref =
                new CorrelationSessionRef("sess-002", "cookie", "cookie-filter", null, true, Map.of("env", "prod"));
        assertNull(ref.claimName());
        assertTrue(ref.durableSafe());
        assertEquals("prod", ref.attributes().get("env"));
    }

    @Test
    @DisplayName("null attributes map is treated as empty")
    void nullAttributesTreatedAsEmpty() {
        CorrelationSessionRef ref = new CorrelationSessionRef("id", "kind", "source", null, false, null);
        assertTrue(ref.attributes().isEmpty());
    }

    // --- null/blank id rejection ---

    @Test
    @DisplayName("null id throws NullPointerException")
    void nullIdThrowsNpe() {
        assertThrows(
                NullPointerException.class, () -> new CorrelationSessionRef(null, "kind", "source", null, false, null));
    }

    @Test
    @DisplayName("blank id throws IllegalArgumentException")
    void blankIdThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorrelationSessionRef("   ", "kind", "source", null, false, null));
    }

    // --- null/blank kind rejection ---

    @Test
    @DisplayName("null kind throws NullPointerException")
    void nullKindThrowsNpe() {
        assertThrows(
                NullPointerException.class, () -> new CorrelationSessionRef("id", null, "source", null, false, null));
    }

    @Test
    @DisplayName("blank kind throws IllegalArgumentException")
    void blankKindThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorrelationSessionRef("id", "   ", "source", null, false, null));
    }

    // --- null/blank source rejection ---

    @Test
    @DisplayName("null source throws NullPointerException")
    void nullSourceThrowsNpe() {
        assertThrows(
                NullPointerException.class, () -> new CorrelationSessionRef("id", "kind", null, null, false, null));
    }

    @Test
    @DisplayName("blank source throws IllegalArgumentException")
    void blankSourceThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorrelationSessionRef("id", "kind", "   ", null, false, null));
    }

    // --- defensive copy ---

    @Test
    @DisplayName("mutating input map after construction does not affect ref attributes")
    void defensiveCopyOfAttributes() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("key", "value");
        CorrelationSessionRef ref = new CorrelationSessionRef("id", "kind", "source", null, false, mutable);
        mutable.put("extra", "injected");
        assertEquals(1, ref.attributes().size(), "Attributes must not reflect mutation of input map");
    }

    @Test
    @DisplayName("returned attributes map is unmodifiable")
    void attributesMapIsUnmodifiable() {
        CorrelationSessionRef ref = new CorrelationSessionRef("id", "kind", "source", null, false, Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> ref.attributes().put("x", "y"));
    }
}
