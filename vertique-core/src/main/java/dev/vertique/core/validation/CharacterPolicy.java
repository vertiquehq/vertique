// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import dev.vertique.core.sanitization.InputValueContext;

/**
 * Defines a character-set validation policy that rejects input containing disallowed characters.
 *
 * <p>Used by the {@code @AllowedCharacters} annotation (defined in the {@code sanitization} module)
 * to enforce that string values contain only the characters permitted by this policy. Implementations
 * should scan the value and return a {@link CharacterPolicyResult} indicating the first violation
 * found, or {@link CharacterPolicyResult#valid()} if the value is fully compliant.
 *
 * <p>Implementations MUST be:
 * <ul>
 *   <li><b>Stateless and thread-safe</b> — the same instance may be called concurrently.</li>
 *   <li><b>Deterministic</b> — identical inputs always produce identical results.</li>
 * </ul>
 *
 * <p>Character policies are registered via Dagger multibinding using {@link CharacterPolicyBinding}.
 *
 * @see CharacterPolicyResult
 * @see CharacterPolicyBinding
 */
public interface CharacterPolicy {

    /**
     * Validates that the given string value contains only permitted characters.
     *
     * @param value   the string value to validate; may be {@code null} (implementations should
     *                treat {@code null} as valid and return {@link CharacterPolicyResult#passed()})
     * @param context contextual metadata about the value's origin within the request
     * @return {@link CharacterPolicyResult#valid()} if all characters are permitted,
     *         or a failed result identifying the first offending character
     */
    CharacterPolicyResult validate(String value, InputValueContext context);
}
