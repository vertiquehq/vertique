// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * The character-policy home: the {@link CharacterPolicy} SPI, {@link CharacterPolicyResult},
 * {@link CharacterPolicyBinding} (Dagger multibinding wrapper), the {@link SkipAllowedCharacters}
 * opt-out marker, the built-in policies, and the constraint annotations for character-set validation.
 *
 * <p>Character policies validate that string values contain only characters appropriate
 * for their semantic purpose (e.g., slugs, personal names, addresses). Each policy
 * implements {@link CharacterPolicy} and can be used
 * directly with the {@link AllowedCharacters} annotation.
 *
 * <p>Composed convenience annotations ({@link PersonName},
 * {@link AddressLine}, etc.) wrap commonly used
 * policies as named constraint annotations.
 *
 * @see AllowedCharacters
 * @see CharacterPolicy
 */
package dev.vertique.validation.constraints;
