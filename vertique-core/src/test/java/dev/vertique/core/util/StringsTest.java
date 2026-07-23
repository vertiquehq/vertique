// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Strings#firstNonBlank(String...)}.
 *
 * <p>Covers: empty varargs, all-null, all-blank, mixed leading null/blank candidates, and
 * first-non-blank-wins ordering.
 */
@DisplayName("Strings.firstNonBlank")
class StringsTest {

    @Test
    @DisplayName("emptyVarargs: no candidates returns null")
    void emptyVarargs() {
        assertNull(Strings.firstNonBlank());
    }

    @Test
    @DisplayName("allNull: all-null candidates returns null")
    void allNull() {
        assertNull(Strings.firstNonBlank(null, null, null));
    }

    @Test
    @DisplayName("allBlank: all-blank candidates returns null")
    void allBlank() {
        assertNull(Strings.firstNonBlank("", "   ", "\t"));
    }

    @Test
    @DisplayName("firstNonBlankWins: returns the first non-null non-blank candidate")
    void firstNonBlankWins() {
        assertEquals("a", Strings.firstNonBlank("a", "b", "c"));
    }

    @Test
    @DisplayName("skipsLeadingNullAndBlank: ignores leading null and blank candidates")
    void skipsLeadingNullAndBlank() {
        assertEquals("winner", Strings.firstNonBlank(null, "", "  ", "winner", "ignored"));
    }

    @Test
    @DisplayName("singleNonBlank: single non-blank value is returned")
    void singleNonBlank() {
        assertEquals("x", Strings.firstNonBlank("x"));
    }

    @Test
    @DisplayName("singleNull: single null returns null")
    void singleNull() {
        assertNull(Strings.firstNonBlank((String) null));
    }
}
