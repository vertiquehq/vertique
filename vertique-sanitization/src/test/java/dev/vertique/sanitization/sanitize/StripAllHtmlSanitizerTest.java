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
 * Verifies that {@link StripAllHtmlSanitizer} strips all HTML tags while preserving inner text,
 * decodes HTML entities to plain text, preserves block-element boundaries as newlines, and
 * satisfies idempotency and null-safety contracts.
 */
class StripAllHtmlSanitizerTest {

    private final StripAllHtmlSanitizer sanitizer = new StripAllHtmlSanitizer();
    private final InputValueContext CTX = new InputValueContext(InputLocation.BODY, "text", "text", Object.class);

    @Test
    @DisplayName("strips paragraph and bold tags, preserving text")
    void stripsBasicTags() {
        assertEquals("Hello world!", sanitizer.sanitize("<p>Hello <b>world</b>!</p>", CTX));
    }

    @Test
    @DisplayName("strips script tag and retains no content")
    void stripsScriptTag() {
        // OWASP strips the script element and its text content entirely
        var result = sanitizer.sanitize("<script>alert('xss')</script>", CTX);
        assertEquals("", result);
    }

    @Test
    @DisplayName("strips anchor tag")
    void stripsAnchorTag() {
        var result = sanitizer.sanitize("<a href=\"http://evil.com\">click me</a>", CTX);
        assertEquals(-1, result.indexOf("<a"));
        assertEquals("click me", result);
    }

    @Test
    @DisplayName("plain text passes through unchanged")
    void plainTextPassesThrough() {
        assertEquals("Hello world", sanitizer.sanitize("Hello world", CTX));
    }

    @Test
    @DisplayName("returns empty string for empty input")
    void emptyForEmptyInput() {
        assertEquals("", sanitizer.sanitize("", CTX));
    }

    @Test
    @DisplayName("returns null for null input")
    void nullForNullInput() {
        assertNull(sanitizer.sanitize(null, CTX));
    }

    @Test
    @DisplayName("preserves ampersand in plain text (no entity encoding)")
    void preservesAmpersand() {
        assertEquals("a & b", sanitizer.sanitize("a & b", CTX));
    }

    @Test
    @DisplayName("decodes source HTML entities to plain text")
    void decodesSourceEntities() {
        assertEquals("Tom & Jerry", sanitizer.sanitize("Tom &amp; Jerry", CTX));
    }

    @Test
    @DisplayName("inserts newline at block element boundaries")
    void blockElementBoundaries() {
        assertEquals("Hello\nworld", sanitizer.sanitize("<p>Hello</p><p>world</p>", CTX));
    }

    @Test
    @DisplayName("pretty-printed HTML with inter-tag whitespace does not produce extra blank lines")
    void prettyPrintedHtml() {
        assertEquals("a\nb", sanitizer.sanitize("<p>a</p>\n<p>b</p>", CTX));
    }

    @Test
    @DisplayName("indented block markup produces clean newlines")
    void indentedBlockMarkup() {
        assertEquals(
                "first\nsecond", sanitizer.sanitize("  <div>\n    <p>first</p>\n    <p>second</p>\n  </div>", CTX));
    }

    @Test
    @DisplayName("br tag produces newline")
    void brTag() {
        assertEquals("line1\nline2", sanitizer.sanitize("line1<br>line2", CTX));
    }

    @Test
    @DisplayName("div boundaries produce newlines")
    void divBoundaries() {
        assertEquals("a\nb", sanitizer.sanitize("<div>a</div><div>b</div>", CTX));
    }

    @Test
    @DisplayName("list items produce newlines")
    void listItems() {
        assertEquals("one\ntwo", sanitizer.sanitize("<ul><li>one</li><li>two</li></ul>", CTX));
    }
}
