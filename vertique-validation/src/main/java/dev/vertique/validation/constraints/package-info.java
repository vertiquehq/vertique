// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Built-in {@link dev.vertique.core.validation.CharacterPolicy} implementations and
 * constraint annotations for character-set validation.
 *
 * <p>Character policies validate that string values contain only characters appropriate
 * for their semantic purpose (e.g., slugs, personal names, addresses). Each policy
 * implements {@link dev.vertique.core.validation.CharacterPolicy} and can be used
 * directly with the {@link dev.vertique.validation.constraints.AllowedCharacters} annotation.
 *
 * <p>Composed convenience annotations ({@link dev.vertique.validation.constraints.PersonName},
 * {@link dev.vertique.validation.constraints.AddressLine}, etc.) wrap commonly used
 * policies as named constraint annotations.
 *
 * @see dev.vertique.validation.constraints.AllowedCharacters
 * @see dev.vertique.core.validation.CharacterPolicy
 */
package dev.vertique.validation.constraints;
