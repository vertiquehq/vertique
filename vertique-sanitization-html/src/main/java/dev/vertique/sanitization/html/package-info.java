// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * HTML {@link dev.vertique.core.sanitization.Sanitizer} implementations built on the OWASP Java
 * HTML Sanitizer library.
 *
 * <p>All sanitizers in this package are stateless, deterministic, and idempotent. They can be
 * referenced by class in {@link dev.vertique.core.sanitization.Sanitize#value()} annotations; the
 * package ships no Dagger module, so the engine creates each class through its public no-arg
 * constructor on first use. They differ in the set of tags and attributes they allow:
 * <ul>
 *   <li>{@link dev.vertique.sanitization.html.StripAllHtmlSanitizer} — strips all HTML</li>
 *   <li>{@link dev.vertique.sanitization.html.BasicHtmlSanitizer} — allows basic inline formatting</li>
 *   <li>{@link dev.vertique.sanitization.html.LinksHtmlSanitizer} — basic formatting plus safe links</li>
 *   <li>{@link dev.vertique.sanitization.html.RichTextHtmlSanitizer} — full CMS-style rich-text subset</li>
 * </ul>
 *
 * @see dev.vertique.core.sanitization.Sanitize
 * @see dev.vertique.core.sanitization.Sanitizer
 */
package dev.vertique.sanitization.html;
