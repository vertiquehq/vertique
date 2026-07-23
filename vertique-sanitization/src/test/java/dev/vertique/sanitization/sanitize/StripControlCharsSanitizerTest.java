// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.sanitize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link StripControlCharsSanitizer} removes ISO control characters while preserving
 * tab, LF, and CR, and satisfies idempotency and null-safety contracts.
 */
class StripControlCharsSanitizerTest {

    private final StripControlCharsSanitizer sanitizer = new StripControlCharsSanitizer();
    private final InputValueContext ctx = new InputValueContext(InputLocation.BODY, "text", "text", Object.class);

    @Test
    @DisplayName("preserves tab character (U+0009)")
    void preservesTab() {
        assertEquals("a\tb", sanitizer.sanitize("a\tb", ctx));
    }

    @Test
    @DisplayName("preserves line feed (U+000A)")
    void preservesLineFeed() {
        assertEquals("a\nb", sanitizer.sanitize("a\nb", ctx));
    }

    @Test
    @DisplayName("preserves carriage return (U+000D)")
    void preservesCarriageReturn() {
        assertEquals("a\rb", sanitizer.sanitize("a\rb", ctx));
    }

    @Test
    @DisplayName("removes null byte (U+0000)")
    void removesNullByte() {
        assertEquals("ab", sanitizer.sanitize("a\u0000b", ctx));
    }

    @Test
    @DisplayName("removes BEL character (U+0007)")
    void removesBel() {
        assertEquals("ab", sanitizer.sanitize("a\u0007b", ctx));
    }

    @Test
    @DisplayName("removes ESC character (U+001B)")
    void removesEsc() {
        assertEquals("hello", sanitizer.sanitize("\u001Bhello", ctx));
    }

    @Test
    @DisplayName("removes DEL character (U+007F)")
    void removesDel() {
        assertEquals("ab", sanitizer.sanitize("a\u007Fb", ctx));
    }

    @Test
    @DisplayName("removes C1 control character (U+0080)")
    void removesC1Control() {
        assertEquals("ab", sanitizer.sanitize("a\u0080b", ctx));
    }

    @Test
    @DisplayName("clean text passes through unchanged (idempotent)")
    void idempotentOnCleanText() {
        var text = "Hello, world!\nLine two.";
        assertEquals(text, sanitizer.sanitize(text, ctx));
    }

    @Test
    @DisplayName("returns empty string for empty input")
    void emptyForEmptyInput() {
        assertEquals("", sanitizer.sanitize("", ctx));
    }

    @Test
    @DisplayName("returns null for null input")
    void nullForNullInput() {
        assertNull(sanitizer.sanitize(null, ctx));
    }
}
