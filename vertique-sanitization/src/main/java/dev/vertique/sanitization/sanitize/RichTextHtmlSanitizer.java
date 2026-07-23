// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.sanitize;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Sanitizer that allows a broad, CMS-style rich-text subset of HTML.
 *
 * <p>This sanitizer is designed for content-editing scenarios such as blog posts, articles, or
 * product descriptions where authors need access to headings, tables, code blocks, and images in
 * addition to basic inline formatting.
 *
 * <p>Permitted elements and attributes:
 * <ul>
 *   <li>Inline: {@code em}, {@code strong}, {@code b}, {@code i}, {@code u}, {@code s},
 *       {@code sub}, {@code sup}, {@code span}, {@code code}</li>
 *   <li>Block: {@code p}, {@code br}, {@code blockquote}, {@code pre}, {@code hr},
 *       {@code div}</li>
 *   <li>Headings: {@code h1}–{@code h6}</li>
 *   <li>Lists: {@code ul}, {@code ol}, {@code li}</li>
 *   <li>Tables: {@code table}, {@code thead}, {@code tbody}, {@code tr}, {@code th}, {@code td}</li>
 *   <li>Links: {@code <a href="...">} with {@code http}, {@code https}, or {@code mailto} schemes;
 *       {@code rel="nofollow"} is added automatically</li>
 *   <li>Images: {@code <img src="..." alt="..." width="..." height="...">} with {@code http} or
 *       {@code https} src URLs</li>
 *   <li>{@code class} attribute globally on all elements</li>
 * </ul>
 *
 * <p><b>Security note:</b> the {@code class} attribute is allowed globally to support
 * CMS editor integration (e.g., syntax highlighting, layout classes). This does not enable
 * script injection, but it can enable CSS-based UI redressing if the consuming page loads
 * attacker-influenced stylesheets. Applications embedding this sanitizer's output must
 * ensure CSS isolation (scoped styles, Shadow DOM, or CSS Modules) or validate permitted
 * class names downstream. Do not render sanitized output in a page with untrusted CSS.
 *
 * <p>The policy factory is a singleton shared across all invocations; the OWASP library guarantees
 * thread safety for policy-based sanitization.
 *
 * <p>This implementation is stateless and thread-safe.
 *
 * @see BasicHtmlSanitizer
 * @see LinksHtmlSanitizer
 * @see StripAllHtmlSanitizer
 */
public final class RichTextHtmlSanitizer implements Sanitizer {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements(
                    "p",
                    "br",
                    "em",
                    "strong",
                    "b",
                    "i",
                    "u",
                    "s",
                    "ul",
                    "ol",
                    "li",
                    "blockquote",
                    "pre",
                    "code",
                    "h1",
                    "h2",
                    "h3",
                    "h4",
                    "h5",
                    "h6",
                    "table",
                    "thead",
                    "tbody",
                    "tr",
                    "th",
                    "td",
                    "a",
                    "img",
                    "hr",
                    "span",
                    "div",
                    "sub",
                    "sup")
            .allowUrlProtocols("http", "https", "mailto")
            .allowAttributes("href")
            .onElements("a")
            .allowAttributes("src", "alt", "width", "height")
            .onElements("img")
            .allowAttributes("class")
            .globally()
            .requireRelNofollowOnLinks()
            .toFactory();

    /**
     * Constructs a new {@code RichTextHtmlSanitizer}.
     */
    public RichTextHtmlSanitizer() {}

    /**
     * Sanitizes {@code value} to allow a rich CMS-style HTML subset.
     *
     * @param value   the raw string value to sanitize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the sanitized rich-text HTML, or {@code null} if the input was {@code null}
     */
    @Override
    public String sanitize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        return POLICY.sanitize(value);
    }
}
