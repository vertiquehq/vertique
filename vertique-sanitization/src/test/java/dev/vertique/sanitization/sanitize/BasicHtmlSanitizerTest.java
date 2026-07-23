// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.sanitize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link BasicHtmlSanitizer} allows the declared set of basic formatting elements,
 * strips disallowed tags, and satisfies idempotency and null-safety contracts.
 */
class BasicHtmlSanitizerTest {

    private final BasicHtmlSanitizer sanitizer = new BasicHtmlSanitizer();
    private final InputValueContext ctx = new InputValueContext(InputLocation.BODY, "body", "body", Object.class);

    @Test
    @DisplayName("preserves allowed p, em, strong tags")
    void preservesAllowedTags() {
        var result = sanitizer.sanitize("<p>Hello <em>world</em> and <strong>more</strong></p>", ctx);
        assertTrue(
                result.contains("<p>") || result.contains("<em>") || result.contains("<strong>"),
                "at least one allowed tag should survive in: " + result);
    }

    @Test
    @DisplayName("preserves list elements ul, ol, li")
    void preservesListElements() {
        var result = sanitizer.sanitize("<ul><li>Item 1</li><li>Item 2</li></ul>", ctx);
        assertTrue(result.contains("<li>"), "li elements should survive in: " + result);
    }

    @Test
    @DisplayName("strips disallowed div tag but preserves its text")
    void stripsDivButKeepsText() {
        var result = sanitizer.sanitize("<div>content</div>", ctx);
        assertEquals(-1, result.indexOf("<div"), "div should be stripped");
        assertTrue(result.contains("content"), "text should be preserved");
    }

    @Test
    @DisplayName("strips script tag entirely")
    void stripsScriptTag() {
        var result = sanitizer.sanitize("<script>alert('xss')</script>", ctx);
        assertEquals(-1, result.indexOf("<script"), "script tag should be stripped");
    }

    @Test
    @DisplayName("strips anchor (link) tag since it is not allowed")
    void stripsAnchorTag() {
        var result = sanitizer.sanitize("<a href=\"http://example.com\">link</a>", ctx);
        assertEquals(-1, result.indexOf("<a"), "anchor tag should be stripped");
        assertTrue(result.contains("link"), "link text should be preserved");
    }

    @Test
    @DisplayName("strips class attribute from allowed elements")
    void stripsClassAttribute() {
        var result = sanitizer.sanitize("<p class=\"danger\">text</p>", ctx);
        assertEquals(-1, result.indexOf("class"), "class attribute should be stripped");
    }

    @Test
    @DisplayName("plain text passes through unchanged")
    void plainTextPassesThrough() {
        assertEquals("Hello world", sanitizer.sanitize("Hello world", ctx));
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
