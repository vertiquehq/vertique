// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.sanitize;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Sanitizer that allows a small set of basic inline formatting HTML elements and strips everything
 * else.
 *
 * <p>The following elements are permitted (no attributes allowed):
 * <ul>
 *   <li>{@code <p>} — paragraph</li>
 *   <li>{@code <br>} — line break</li>
 *   <li>{@code <em>}, {@code <i>} — italic / emphasis</li>
 *   <li>{@code <strong>}, {@code <b>} — bold / strong</li>
 *   <li>{@code <ul>}, {@code <ol>}, {@code <li>} — unordered and ordered lists</li>
 * </ul>
 *
 * <p>All other tags are stripped. Disallowed attributes on allowed elements are also stripped.
 * This policy is suitable for comment fields and short descriptions where only minimal formatting
 * is expected.
 *
 * <p>The policy factory is a singleton shared across all invocations; the OWASP library guarantees
 * thread safety for policy-based sanitization.
 *
 * <p>This implementation is stateless and thread-safe.
 *
 * @see StripAllHtmlSanitizer
 * @see LinksHtmlSanitizer
 * @see RichTextHtmlSanitizer
 */
public final class BasicHtmlSanitizer implements Sanitizer {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "em", "strong", "b", "i", "ul", "ol", "li")
            .toFactory();

    /**
     * Constructs a new {@code BasicHtmlSanitizer}.
     */
    public BasicHtmlSanitizer() {}

    /**
     * Sanitizes {@code value} to allow only basic inline formatting HTML elements.
     *
     * @param value   the raw string value to sanitize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the sanitized HTML with only basic formatting retained, or {@code null} if the input was {@code null}
     */
    @Override
    public String sanitize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        return POLICY.sanitize(value);
    }
}
