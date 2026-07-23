// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

/**
 * Dagger multibinding wrapper that associates a {@link CharacterPolicy} implementation class with
 * its singleton instance.
 *
 * <p>The {@link #type()} acts as the lookup key when the runtime resolves character policies
 * declared in {@code @AllowedCharacters}. Contribute instances via:
 * <pre>{@code
 * @Provides @IntoSet
 * static CharacterPolicyBinding myPolicy(MyCharacterPolicy p) {
 *     return new CharacterPolicyBinding(MyCharacterPolicy.class, p);
 * }
 * }</pre>
 *
 * @param type     the concrete {@link CharacterPolicy} implementation class used as the lookup key
 * @param instance the singleton {@link CharacterPolicy} instance
 */
public record CharacterPolicyBinding(Class<? extends CharacterPolicy> type, CharacterPolicy instance) {}
