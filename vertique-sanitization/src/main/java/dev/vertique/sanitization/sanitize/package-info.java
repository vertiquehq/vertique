// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Built-in {@link dev.vertique.core.sanitization.Sanitizer} implementations.
 *
 * <p>All sanitizers in this package are stateless, deterministic, and idempotent. They can be
 * referenced by class in {@link dev.vertique.core.sanitization.Sanitize#value()} annotations.
 * The {@link dev.vertique.sanitization.SanitizationModule} registers all of them as Dagger
 * multibindings so they are available for injection-based resolution.
 *
 * <p>HTML sanitizers are built on the OWASP Java HTML Sanitizer library and differ in the set of
 * tags and attributes they allow:
 * <ul>
 *   <li>{@link dev.vertique.sanitization.sanitize.StripAllHtmlSanitizer} — strips all HTML</li>
 *   <li>{@link dev.vertique.sanitization.sanitize.BasicHtmlSanitizer} — allows basic inline formatting</li>
 *   <li>{@link dev.vertique.sanitization.sanitize.LinksHtmlSanitizer} — basic formatting plus safe links</li>
 *   <li>{@link dev.vertique.sanitization.sanitize.RichTextHtmlSanitizer} — full CMS-style rich-text subset</li>
 * </ul>
 *
 * @see dev.vertique.core.sanitization.Sanitize
 * @see dev.vertique.core.sanitization.Sanitizer
 */
package dev.vertique.sanitization.sanitize;
