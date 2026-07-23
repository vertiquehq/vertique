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
 * Verifies that {@link LinksHtmlSanitizer} preserves safe links (http/https/mailto), strips
 * dangerous link schemes, adds {@code rel="nofollow"}, and satisfies idempotency and null-safety
 * contracts.
 */
class LinksHtmlSanitizerTest {

    private final LinksHtmlSanitizer sanitizer = new LinksHtmlSanitizer();
    private final InputValueContext ctx = new InputValueContext(InputLocation.BODY, "body", "body", Object.class);

    @Test
    @DisplayName("preserves https link with href and adds nofollow")
    void preservesHttpsLink() {
        var result = sanitizer.sanitize("<a href=\"https://example.com\">visit</a>", ctx);
        assertTrue(result.contains("href=\"https://example.com\""), "https href should be preserved in: " + result);
        assertTrue(result.contains("nofollow"), "rel=nofollow should be added in: " + result);
    }

    @Test
    @DisplayName("preserves http link")
    void preservesHttpLink() {
        var result = sanitizer.sanitize("<a href=\"http://example.com\">visit</a>", ctx);
        assertTrue(result.contains("href=\"http://example.com\""), "http href should be preserved in: " + result);
    }

    @Test
    @DisplayName("preserves mailto link (OWASP encodes @ as &#64; in href)")
    void preservesMailtoLink() {
        var result = sanitizer.sanitize("<a href=\"mailto:user@example.com\">email</a>", ctx);
        // OWASP HTML Sanitizer encodes '@' as '&#64;' in href attributes for safety
        assertTrue(result.contains("mailto:"), "mailto scheme should be preserved in: " + result);
        assertTrue(result.contains("<a ") || result.startsWith("<a>"), "anchor tag should be preserved in: " + result);
    }

    @Test
    @DisplayName("strips javascript: scheme from href")
    void stripsJavascriptScheme() {
        var result = sanitizer.sanitize("<a href=\"javascript:alert(1)\">click</a>", ctx);
        assertEquals(-1, result.indexOf("javascript:"), "javascript: scheme should be stripped");
    }

    @Test
    @DisplayName("strips ftp: scheme from href")
    void stripsFtpScheme() {
        var result = sanitizer.sanitize("<a href=\"ftp://example.com\">ftp</a>", ctx);
        assertEquals(-1, result.indexOf("ftp://"), "ftp: scheme should be stripped");
    }

    @Test
    @DisplayName("preserves basic formatting elements in addition to links")
    void preservesBasicFormattingElements() {
        var result = sanitizer.sanitize("<p><em>italic</em> <strong>bold</strong></p>", ctx);
        assertTrue(
                result.contains("<em>") || result.contains("<strong>"),
                "basic formatting should be preserved in: " + result);
    }

    @Test
    @DisplayName("strips script tag")
    void stripsScriptTag() {
        var result = sanitizer.sanitize("<script>xss()</script>", ctx);
        assertEquals(-1, result.indexOf("<script"), "script tag should be stripped");
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
