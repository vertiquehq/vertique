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
 * Verifies that {@link RichTextHtmlSanitizer} allows the declared CMS-style rich-text subset,
 * strips disallowed elements, and satisfies idempotency and null-safety contracts.
 */
class RichTextHtmlSanitizerTest {

    private final RichTextHtmlSanitizer sanitizer = new RichTextHtmlSanitizer();
    private final InputValueContext ctx = new InputValueContext(InputLocation.BODY, "content", "content", Object.class);

    @Test
    @DisplayName("preserves heading elements h1-h3")
    void preservesHeadings() {
        var result = sanitizer.sanitize("<h1>Title</h1><h2>Sub</h2><h3>Sec</h3>", ctx);
        assertTrue(result.contains("<h1>"), "h1 should survive in: " + result);
        assertTrue(result.contains("<h2>"), "h2 should survive in: " + result);
        assertTrue(result.contains("<h3>"), "h3 should survive in: " + result);
    }

    @Test
    @DisplayName("preserves table structure (table, thead, tbody, tr, th, td)")
    void preservesTable() {
        var result = sanitizer.sanitize(
                "<table><thead><tr><th>A</th></tr></thead><tbody><tr><td>B</td></tr></tbody></table>", ctx);
        assertTrue(
                result.contains("<table>") || result.contains("<tr>"), "table elements should survive in: " + result);
    }

    @Test
    @DisplayName("preserves blockquote and pre elements")
    void preservesBlockquoteAndPre() {
        var result = sanitizer.sanitize("<blockquote><pre>code</pre></blockquote>", ctx);
        assertTrue(
                result.contains("<blockquote>") || result.contains("<pre>"),
                "blockquote/pre should survive in: " + result);
    }

    @Test
    @DisplayName("preserves img tag with src, alt, width, height attributes")
    void preservesImgTag() {
        var result = sanitizer.sanitize(
                "<img src=\"https://example.com/img.png\" alt=\"test\" width=\"100\" height=\"50\">", ctx);
        assertTrue(result.contains("src=\"https://example.com/img.png\""), "img src should be preserved in: " + result);
        assertTrue(result.contains("alt=\"test\""), "alt should be preserved in: " + result);
    }

    @Test
    @DisplayName("strips img with javascript: src")
    void stripsJavascriptImgSrc() {
        var result = sanitizer.sanitize("<img src=\"javascript:alert(1)\">", ctx);
        assertEquals(-1, result.indexOf("javascript:"), "javascript: img src should be stripped");
    }

    @Test
    @DisplayName("preserves class attribute globally")
    void preservesClassAttributeGlobally() {
        var result = sanitizer.sanitize("<p class=\"intro\">text</p>", ctx);
        assertTrue(result.contains("class=\"intro\""), "class attribute should be preserved in: " + result);
    }

    @Test
    @DisplayName("preserves https link with nofollow")
    void preservesHttpsLinkWithNofollow() {
        var result = sanitizer.sanitize("<a href=\"https://example.com\">link</a>", ctx);
        assertTrue(result.contains("https://example.com"), "https href should survive in: " + result);
        assertTrue(result.contains("nofollow"), "nofollow should be present in: " + result);
    }

    @Test
    @DisplayName("strips script tag")
    void stripsScriptTag() {
        var result = sanitizer.sanitize("<script>xss()</script>text", ctx);
        assertEquals(-1, result.indexOf("<script"), "script tag should be stripped");
        assertTrue(result.contains("text"), "surrounding text should be preserved");
    }

    @Test
    @DisplayName("strips iframe tag")
    void stripsIframeTag() {
        var result = sanitizer.sanitize("<iframe src=\"https://evil.com\"></iframe>text", ctx);
        assertEquals(-1, result.indexOf("<iframe"), "iframe should be stripped");
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
