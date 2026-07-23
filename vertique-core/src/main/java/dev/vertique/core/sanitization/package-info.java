// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * HTTP-agnostic canonicalization and sanitization API for string input processing.
 *
 * <p>This package defines the contracts for three related but distinct input-processing concerns:
 * <ul>
 *   <li><b>Canonicalization</b> — semantics-preserving normalization that is always applied
 *       (e.g., Unicode NFC normalization, whitespace collapsing). Declared via
 *       {@link dev.vertique.core.sanitization.Canonicalize} and implemented through
 *       {@link dev.vertique.core.sanitization.Canonicalizer}.</li>
 *   <li><b>Sanitization</b> — opt-in rewriting for declared use cases where the transformation
 *       may change the perceived structure of the value (e.g., stripping HTML tags). Declared via
 *       {@link dev.vertique.core.sanitization.Sanitize} and implemented through
 *       {@link dev.vertique.core.sanitization.Sanitizer}.</li>
 *   <li><b>Character policies</b> — validation that rejects input containing characters outside
 *       an allowed set. Defined in {@link dev.vertique.core.validation} and referenced from this
 *       package via {@link dev.vertique.core.sanitization.InputValueContext}.</li>
 * </ul>
 *
 * <p>Context is conveyed to all processors through
 * {@link dev.vertique.core.sanitization.InputValueContext}, which carries the
 * {@link dev.vertique.core.sanitization.InputLocation}, property path, logical name, and owner type.
 *
 * <p>Built-in canonicalizer and sanitizer implementations live in the {@code sanitization}
 * framework module, not in {@code core}. This package contains only the SPI contracts and
 * annotations that other modules depend on.
 *
 * @see dev.vertique.core.sanitization.Canonicalize
 * @see dev.vertique.core.sanitization.Sanitize
 * @see dev.vertique.core.sanitization.SkipCanonicalization
 * @see dev.vertique.core.sanitization.SkipSanitization
 */
package dev.vertique.core.sanitization;
