// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.sanitize;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import java.util.List;
import java.util.Set;
import org.owasp.html.HtmlSanitizer;

/**
 * Sanitizer that strips all HTML tags and attributes from a string, returning plain text.
 *
 * <p>Uses OWASP's streaming HTML parser directly so that text nodes are delivered already decoded.
 * This means HTML entities in the source ({@code &amp;}, {@code &lt;}, etc.) are resolved to their
 * plain-text equivalents in the output, and no new entity encoding is introduced.
 *
 * <p>Block-level element boundaries ({@code p}, {@code div}, {@code h1}–{@code h6}, etc.) are
 * preserved as newlines so that the visual paragraph structure of the original markup is
 * approximated in the plain-text output. The result is trimmed of leading and trailing whitespace.
 *
 * <p>Example:
 * <pre>{@code
 * "<p>Hello <b>world</b>!</p>" → "Hello world!"
 * "<script>alert('xss')</script>" → ""
 * "a &amp; b" → "a & b"
 * "<p>Line one</p><p>Line two</p>" → "Line one\nLine two"
 * }</pre>
 *
 * <p>This implementation is stateless and thread-safe.
 *
 * @see BasicHtmlSanitizer
 * @see LinksHtmlSanitizer
 * @see RichTextHtmlSanitizer
 */
public final class StripAllHtmlSanitizer implements Sanitizer {

    // --- Block-level HTML elements ---

    /** HTML elements whose open and close tags produce a newline boundary in plain text. */
    private static final Set<String> BLOCK_ELEMENTS = Set.of(
            "p",
            "div",
            "blockquote",
            "pre",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "li",
            "ul",
            "ol",
            "table",
            "tr",
            "hr",
            "br",
            "dd",
            "dt",
            "dl",
            "section",
            "article",
            "aside",
            "header",
            "footer",
            "nav",
            "main",
            "figure",
            "figcaption",
            "address");

    /**
     * Constructs a new {@code StripAllHtmlSanitizer}.
     */
    public StripAllHtmlSanitizer() {}

    /**
     * Strips all HTML tags from {@code value}, returning plain text.
     *
     * <p>Block-level element boundaries are preserved as newlines. HTML entities in the source are
     * decoded to their plain-text equivalents. The result is trimmed.
     *
     * @param value   the raw string value to sanitize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the plain-text content with all HTML stripped, or {@code null} if the input was
     *     {@code null}
     */
    @Override
    public String sanitize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        PlainTextExtractor extractor = new PlainTextExtractor();
        HtmlSanitizer.sanitize(value, extractor);
        return extractor.toPlainText();
    }

    // --- Plain text extractor ---

    /** HTML elements whose text content should be suppressed in plain-text output. */
    private static final Set<String> SUPPRESSED_ELEMENTS = Set.of("script", "style");

    /**
     * {@link HtmlSanitizer.Policy} implementation that accumulates decoded text nodes into a
     * {@link StringBuilder}, inserting newlines at block-level element boundaries. Text inside
     * {@code <script>} and {@code <style>} elements is suppressed.
     */
    private static final class PlainTextExtractor implements HtmlSanitizer.Policy {

        private final StringBuilder sb = new StringBuilder();
        private boolean pendingNewline = false;
        private int suppressDepth = 0;

        @Override
        public void openDocument() {}

        @Override
        public void closeDocument() {}

        @Override
        public void openTag(String elementName, List<String> attrs) {
            if (SUPPRESSED_ELEMENTS.contains(elementName)) {
                suppressDepth++;
            } else if (BLOCK_ELEMENTS.contains(elementName)) {
                pendingNewline = true;
            }
        }

        @Override
        public void closeTag(String elementName) {
            if (SUPPRESSED_ELEMENTS.contains(elementName)) {
                suppressDepth = Math.max(0, suppressDepth - 1);
            } else if (BLOCK_ELEMENTS.contains(elementName)) {
                pendingNewline = true;
            }
        }

        @Override
        public void text(String textChunk) {
            if (suppressDepth > 0 || textChunk.isEmpty()) {
                return;
            }
            // Skip whitespace-only text nodes between block elements (e.g., inter-tag newlines
            // in pretty-printed HTML like "<p>a</p>\n<p>b</p>")
            if (pendingNewline && textChunk.isBlank()) {
                return;
            }
            if (pendingNewline && sb.length() > 0) {
                sb.append('\n');
            }
            pendingNewline = false;
            sb.append(textChunk);
        }

        /**
         * Returns the accumulated plain text, trimmed of leading and trailing whitespace.
         *
         * @return trimmed plain text
         */
        String toPlainText() {
            return sb.toString().strip();
        }
    }
}
