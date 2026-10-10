// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HeaderUtils#escapeQuoted(String)}. */
class HeaderUtilsTest {

    @Test
    @DisplayName("backslashes and double quotes are backslash-escaped")
    void escapesBackslashAndQuote() {
        assertEquals("a\\\"b\\\\c", HeaderUtils.escapeQuoted("a\"b\\c"));
    }

    @Test
    @DisplayName("a control character is replaced by an underscore, not deleted")
    void replacesControlCharacterInsteadOfDeletingIt() {
        assertEquals("evil.ph_p", HeaderUtils.escapeQuoted("evil.ph\u0001p"));
        assertNotEquals(HeaderUtils.escapeQuoted("evil.php"), HeaderUtils.escapeQuoted("evil.ph\u0001p"));
    }

    @Test
    @DisplayName("CR, LF, NUL and DEL are each replaced by one underscore and a tab is kept")
    void replacesEachControlCharacterWithOneUnderscore() {
        String value = "a\rb\nc\u0000d\u007fe\tf";
        String escaped = HeaderUtils.escapeQuoted(value);

        assertEquals("a_b_c_d_e\tf", escaped);
        assertEquals(value.length(), escaped.length());
    }

    @Test
    @DisplayName("two values that differ only by an inserted control character stay different")
    void insertedControlCharacterNeverFormsAnotherValue() {
        assertNotEquals(HeaderUtils.escapeQuoted("report.pdf"), HeaderUtils.escapeQuoted("report\u0001.pdf"));
        assertNotEquals(HeaderUtils.escapeQuoted("ab"), HeaderUtils.escapeQuoted("a\u0001b"));
    }
}
