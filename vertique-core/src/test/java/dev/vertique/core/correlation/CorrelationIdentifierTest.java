// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationIdentifier}.
 *
 * <p>Verifies: happy-path construction; {@code null} value/source rejects with NPE;
 * blank value/source rejects with IAE; record equality based on value+source.
 */
class CorrelationIdentifierTest {

    // --- happy path ---

    @Test
    @DisplayName("constructs with valid value and source")
    void happyPath() {
        CorrelationIdentifier id = new CorrelationIdentifier("abc-123", "http-header");
        assertEquals("abc-123", id.value());
        assertEquals("http-header", id.source());
    }

    // --- null rejection ---

    @Test
    @DisplayName("null value throws NullPointerException")
    void nullValueThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new CorrelationIdentifier(null, "source"));
    }

    @Test
    @DisplayName("null source throws NullPointerException")
    void nullSourceThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new CorrelationIdentifier("value", null));
    }

    // --- blank rejection ---

    @Test
    @DisplayName("blank value throws IllegalArgumentException")
    void blankValueThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new CorrelationIdentifier("   ", "source"));
    }

    @Test
    @DisplayName("empty value throws IllegalArgumentException")
    void emptyValueThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new CorrelationIdentifier("", "source"));
    }

    @Test
    @DisplayName("blank source throws IllegalArgumentException")
    void blankSourceThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new CorrelationIdentifier("value", "   "));
    }

    @Test
    @DisplayName("empty source throws IllegalArgumentException")
    void emptySourceThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new CorrelationIdentifier("value", ""));
    }

    // --- equality ---

    @Test
    @DisplayName("records with same value and source are equal")
    void equalityHolds() {
        CorrelationIdentifier a = new CorrelationIdentifier("id-1", "header");
        CorrelationIdentifier b = new CorrelationIdentifier("id-1", "header");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("records with different value are not equal")
    void inequalityOnValue() {
        CorrelationIdentifier a = new CorrelationIdentifier("id-1", "header");
        CorrelationIdentifier b = new CorrelationIdentifier("id-2", "header");
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("records with different source are not equal")
    void inequalityOnSource() {
        CorrelationIdentifier a = new CorrelationIdentifier("id-1", "header");
        CorrelationIdentifier b = new CorrelationIdentifier("id-1", "generated");
        assertNotEquals(a, b);
    }
}
