// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CustomVerificationSource} validation and defensive copy.
 *
 * <p>Verifies: non-null/non-blank {@code customType}; defensive copy of attributes map;
 * null attributes treated as empty map.
 */
class CustomVerificationSourceTest {

    @Test
    @DisplayName("rejects null customType")
    void rejectsNullCustomType() {
        assertThrows(NullPointerException.class, () -> new CustomVerificationSource(null, Map.of()));
    }

    @Test
    @DisplayName("rejects blank customType")
    void rejectsBlankCustomType() {
        assertThrows(IllegalArgumentException.class, () -> new CustomVerificationSource("  ", Map.of()));
    }

    @Test
    @DisplayName("attributes is defensively copied — mutations to original do not affect record")
    void attributesDefensiveCopy() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v1");
        CustomVerificationSource source = new CustomVerificationSource("vendor-x", mutable);

        mutable.put("k", "v2");
        assertEquals("v1", source.attributes().get("k"));
    }

    @Test
    @DisplayName("null attributes treated as empty map")
    void nullAttributesTreatedAsEmpty() {
        CustomVerificationSource source = new CustomVerificationSource("vendor-x", null);

        assertTrue(source.attributes().isEmpty());
    }
}
