// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonProfileId} construction, normalization, equality, and the reserved
 * {@code vertx} constant (FR-JSON-003A).
 */
class JsonProfileIdTest {

    @Test
    @DisplayName("of(value) trims surrounding whitespace from the value")
    void of_trimsSurroundingWhitespace() {
        assertEquals("payments", JsonProfileId.of("  payments  ").value());
    }

    @Test
    @DisplayName("constructor rejects a null value with IllegalArgumentException")
    void constructor_rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> new JsonProfileId(null));
    }

    @Test
    @DisplayName("constructor rejects a blank value with IllegalArgumentException")
    void constructor_rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new JsonProfileId("   "));
    }

    @Test
    @DisplayName("equality is case-sensitive on the trimmed value")
    void equality_isCaseSensitive() {
        assertNotEquals(JsonProfileId.of("Payments"), JsonProfileId.of("payments"));
    }

    @Test
    @DisplayName("VERTX constant has value 'vertx'")
    void vertxConstant_hasValueVertx() {
        assertEquals("vertx", JsonProfileId.VERTX.value());
    }

    @Test
    @DisplayName("equals and hashCode compare by the trimmed value")
    void equalsHashCode_byTrimmedValue() {
        JsonProfileId a = JsonProfileId.of("  x ");
        JsonProfileId b = JsonProfileId.of("x");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
