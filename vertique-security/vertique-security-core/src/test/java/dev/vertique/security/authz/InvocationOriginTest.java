// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InvocationOrigin}.
 *
 * <p>Verifies: happy-path construction; null/blank {@code kind} rejected; null {@code attributes}
 * treated as empty; defensive copy of {@code attributes}; the {@link InvocationOrigin#MAX_ATTRIBUTES}
 * and {@link InvocationOrigin#MAX_VALUE_LENGTH} bounds are enforced; and the {@link
 * InvocationOrigin#of(String)} / {@link InvocationOrigin#unspecified()} factories.
 */
class InvocationOriginTest {

    // --- happy path ---

    @Test
    @DisplayName("constructs InvocationOrigin with kind and attributes")
    void happyPath() {
        InvocationOrigin origin = new InvocationOrigin("rest", Map.of("route", "/api/orders"));

        assertEquals("rest", origin.kind());
        assertEquals("/api/orders", origin.attributes().get("route"));
    }

    @Test
    @DisplayName("constructs InvocationOrigin with null attributes (treated as empty)")
    void happyPathNullAttributes() {
        InvocationOrigin origin = new InvocationOrigin("rest", null);

        assertTrue(origin.attributes().isEmpty());
    }

    // --- kind rejection ---

    @Test
    @DisplayName("rejectsBlankKind: null kind throws NullPointerException")
    void rejectsNullKind() {
        assertThrows(NullPointerException.class, () -> new InvocationOrigin(null, Map.of()));
    }

    @Test
    @DisplayName("rejectsBlankKind: blank kind throws IllegalArgumentException")
    void rejectsBlankKind() {
        assertThrows(IllegalArgumentException.class, () -> new InvocationOrigin("   ", Map.of()));
    }

    @Test
    @DisplayName("rejectsBlankKind: empty kind throws IllegalArgumentException")
    void rejectsEmptyKind() {
        assertThrows(IllegalArgumentException.class, () -> new InvocationOrigin("", Map.of()));
    }

    // --- defensive copy ---

    @Test
    @DisplayName("mutating source attributes map after construction does not affect record")
    void defensivelyCopiesAttributes() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        InvocationOrigin origin = new InvocationOrigin("rest", mutable);
        mutable.put("injected", "evil");

        assertEquals(1, origin.attributes().size(), "attributes must not reflect mutation of source map");
    }

    @Test
    @DisplayName("attributes map returned by accessor is unmodifiable")
    void attributesIsUnmodifiable() {
        InvocationOrigin origin = new InvocationOrigin("rest", Map.of("k", "v"));

        assertThrows(
                UnsupportedOperationException.class, () -> origin.attributes().put("x", "y"));
    }

    @Test
    @DisplayName("attributes instance is not the original map reference")
    void attributesIsDefensiveCopy() {
        Map<String, Object> original = new HashMap<>(Map.of("k", "v"));
        InvocationOrigin origin = new InvocationOrigin("rest", original);

        assertNotSame(original, origin.attributes());
    }

    // --- bounded attributes ---

    @Test
    @DisplayName("attributesBoundedRejectsOversized: more than MAX_ATTRIBUTES entries throws IllegalArgumentException")
    void attributesBoundedRejectsOversizedEntryCount() {
        Map<String, Object> tooMany = new HashMap<>();
        for (int i = 0; i < InvocationOrigin.MAX_ATTRIBUTES + 1; i++) {
            tooMany.put("k" + i, "v");
        }

        assertThrows(IllegalArgumentException.class, () -> new InvocationOrigin("rest", tooMany));
    }

    @Test
    @DisplayName("exactly MAX_ATTRIBUTES entries is accepted")
    void attributesAtBoundIsAccepted() {
        Map<String, Object> atBound = new HashMap<>();
        for (int i = 0; i < InvocationOrigin.MAX_ATTRIBUTES; i++) {
            atBound.put("k" + i, "v");
        }

        InvocationOrigin origin = new InvocationOrigin("rest", atBound);

        assertEquals(InvocationOrigin.MAX_ATTRIBUTES, origin.attributes().size());
    }

    @Test
    @DisplayName("attributesBoundedRejectsOversized: a value whose string form exceeds MAX_VALUE_LENGTH throws "
            + "IllegalArgumentException")
    void attributesBoundedRejectsOversizedValue() {
        String tooLong = "x".repeat(InvocationOrigin.MAX_VALUE_LENGTH + 1);

        assertThrows(IllegalArgumentException.class, () -> new InvocationOrigin("rest", Map.of("k", tooLong)));
    }

    @Test
    @DisplayName("a value whose string form is exactly MAX_VALUE_LENGTH is accepted")
    void valueAtLengthBoundIsAccepted() {
        String atBound = "x".repeat(InvocationOrigin.MAX_VALUE_LENGTH);

        InvocationOrigin origin = new InvocationOrigin("rest", Map.of("k", atBound));

        assertEquals(atBound, origin.attributes().get("k"));
    }

    // --- factories ---

    @Test
    @DisplayName("of(kind) produces an InvocationOrigin with empty attributes")
    void ofFactory() {
        InvocationOrigin origin = InvocationOrigin.of("camel");

        assertEquals("camel", origin.kind());
        assertTrue(origin.attributes().isEmpty());
    }

    @Test
    @DisplayName("unspecifiedFactory: unspecified() produces kind \"unspecified\" with empty attributes")
    void unspecifiedFactory() {
        InvocationOrigin origin = InvocationOrigin.unspecified();

        assertEquals("unspecified", origin.kind());
        assertTrue(origin.attributes().isEmpty());
    }
}
