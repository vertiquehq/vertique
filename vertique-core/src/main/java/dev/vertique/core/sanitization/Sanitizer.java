// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.sanitization;

/**
 * Opt-in rewriting of string input for narrow, declared use cases.
 *
 * <p>A sanitizer transforms a string in a way that may change its perceived meaning or structure,
 * such as stripping HTML tags or removing control characters. Unlike canonicalization, sanitization
 * is explicitly opted-in by annotating a field, parameter, or type with {@link Sanitize}. It
 * should only be applied where the transformation is clearly desirable and understood by the
 * consumer.
 *
 * <p>Implementations MUST be:
 * <ul>
 *   <li><b>Deterministic</b> — identical inputs always produce identical outputs.</li>
 *   <li><b>Idempotent</b> — applying the sanitizer twice yields the same result as once.</li>
 *   <li><b>Stateless and thread-safe</b> — the same instance is called concurrently from many threads.</li>
 * </ul>
 *
 * <p>Sanitizers are registered via Dagger multibinding using {@link SanitizerBinding} and
 * referenced by type from the {@link Sanitize} annotation.
 *
 * @see Sanitize
 * @see SanitizerBinding
 */
@FunctionalInterface
public interface Sanitizer {

    /**
     * Rewrites the given value according to this sanitizer's rules.
     *
     * @param value   the raw string value to sanitize; may be {@code null}
     * @param context contextual metadata about the value's origin within the request
     * @return the sanitized value; implementations may return {@code null} if the input was {@code null}
     */
    String sanitize(String value, InputValueContext context);
}
