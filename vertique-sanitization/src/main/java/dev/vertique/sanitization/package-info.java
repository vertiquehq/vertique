// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Built-in canonicalizer and sanitizer implementations for Vertique's input processing pipeline.
 *
 * <p>This module provides ready-to-use implementations of the {@link dev.vertique.core.sanitization.Canonicalizer}
 * and {@link dev.vertique.core.sanitization.Sanitizer} contracts defined in {@code vertique-core}. The
 * implementations are registered as Dagger multibindings through {@link dev.vertique.sanitization.SanitizationModule}.
 *
 * <p>Sub-packages:
 * <ul>
 *   <li>{@code canonicalize} — eight built-in canonicalizers (trim, Unicode normalization, case folding,
 *       whitespace collapsing, line-ending normalization, identifier separator removal)</li>
 *   <li>{@code sanitize} — five built-in sanitizers (control character stripping, HTML stripping at
 *       varying permissiveness levels from none to rich-text CMS subset)</li>
 * </ul>
 *
 * <p>Use {@link dev.vertique.sanitization.ProcessorResolver} to resolve canonicalizer and sanitizer
 * instances by class, with three-tier resolution: Dagger binding → reflection → error.
 *
 * @see dev.vertique.sanitization.SanitizationModule
 * @see dev.vertique.sanitization.ProcessorResolver
 */
package dev.vertique.sanitization;
