// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.sanitize;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Sanitizer that extends {@link BasicHtmlSanitizer} with safe hyperlinks.
 *
 * <p>Permitted elements and attributes:
 * <ul>
 *   <li>All elements permitted by {@link BasicHtmlSanitizer}</li>
 *   <li>{@code <a href="...">} — hyperlinks with {@code http}, {@code https}, or {@code mailto} schemes only</li>
 * </ul>
 *
 * <p>Links are further hardened with {@code rel="nofollow"} automatically added to every
 * {@code <a>} element to prevent link-juice leakage. Any {@code href} using a non-allowed
 * scheme (e.g., {@code javascript:}) is stripped entirely.
 *
 * <p>The policy factory is a singleton shared across all invocations; the OWASP library guarantees
 * thread safety for policy-based sanitization.
 *
 * <p>This implementation is stateless and thread-safe.
 *
 * @see BasicHtmlSanitizer
 * @see StripAllHtmlSanitizer
 * @see RichTextHtmlSanitizer
 */
public final class LinksHtmlSanitizer implements Sanitizer {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "em", "strong", "b", "i", "ul", "ol", "li", "a")
            .allowUrlProtocols("http", "https", "mailto")
            .allowAttributes("href")
            .onElements("a")
            .requireRelNofollowOnLinks()
            .toFactory();

    /**
     * Constructs a new {@code LinksHtmlSanitizer}.
     */
    public LinksHtmlSanitizer() {}

    /**
     * Sanitizes {@code value} to allow basic formatting and safe hyperlinks.
     *
     * @param value   the raw string value to sanitize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the sanitized HTML with basic formatting and safe links, or {@code null} if the input was {@code null}
     */
    @Override
    public String sanitize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        return POLICY.sanitize(value);
    }
}
