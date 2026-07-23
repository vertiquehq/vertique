// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.sanitization;

/**
 * Semantics-preserving normalization of string input.
 *
 * <p>A canonicalizer transforms a string into an equivalent canonical form without changing its
 * meaning. Common examples include Unicode normalization (NFC/NFKC), whitespace collapsing, and
 * locale-aware case folding. Canonicalization is always applied to all matching inputs — it is not
 * opt-in by the caller.
 *
 * <p>Implementations MUST be:
 * <ul>
 *   <li><b>Deterministic</b> — identical inputs always produce identical outputs.</li>
 *   <li><b>Idempotent</b> — applying the canonicalizer twice yields the same result as once.</li>
 *   <li><b>Semantics-preserving</b> — the normalized value is equivalent in meaning to the input.</li>
 *   <li><b>Stateless and thread-safe</b> — the same instance is called concurrently from many threads.</li>
 * </ul>
 *
 * <p>Canonicalizers are registered via Dagger multibinding using {@link CanonicalizerBinding} and
 * referenced by type from the {@link Canonicalize} annotation.
 *
 * @see Canonicalize
 * @see CanonicalizerBinding
 */
@FunctionalInterface
public interface Canonicalizer {

    /**
     * Transforms the given value into its canonical form.
     *
     * @param value   the raw string value to canonicalize; may be {@code null}
     * @param context contextual metadata about the value's origin within the request
     * @return the canonicalized value; implementations may return {@code null} if the input was {@code null}
     */
    String canonicalize(String value, InputValueContext context);
}
