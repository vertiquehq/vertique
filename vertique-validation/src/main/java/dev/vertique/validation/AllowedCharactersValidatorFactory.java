// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import dev.vertique.validation.constraints.AllowedCharactersObjectValidator;
import dev.vertique.validation.constraints.AllowedCharactersValidator;
import dev.vertique.validation.constraints.CharacterPolicyResolver;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;

/**
 * A {@link ConstraintValidatorFactory} that creates {@link AllowedCharactersValidator}
 * and {@link AllowedCharactersObjectValidator} instances with a {@link CharacterPolicyResolver}
 * injected for Dagger-managed character policy resolution.
 *
 * <p>Contributed to the {@code Set<ConstraintValidatorFactory>} multibinding in
 * {@link ValidationModule}, where it participates as tier-2 in the
 * {@link DaggerConstraintValidatorFactory} resolution chain.
 *
 * <p>Returns {@code null} for any validator class other than the two it handles,
 * passing resolution through to the next tier.
 */
class AllowedCharactersValidatorFactory implements ConstraintValidatorFactory {

    private final CharacterPolicyResolver resolver;

    /**
     * Creates a new factory backed by the given character policy resolver.
     *
     * @param resolver the resolver used when creating {@link AllowedCharactersValidator} and
     *                 {@link AllowedCharactersObjectValidator} instances
     */
    AllowedCharactersValidatorFactory(CharacterPolicyResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Creates a validator instance for the given key, or returns {@code null} if this factory
     * does not handle the requested validator class.
     *
     * @param key the validator class to instantiate
     * @param <T> the validator type
     * @return a new validator with the injected resolver, or {@code null} if not handled
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
        if (key == AllowedCharactersValidator.class) {
            return (T) new AllowedCharactersValidator(resolver);
        }
        if (key == AllowedCharactersObjectValidator.class) {
            return (T) new AllowedCharactersObjectValidator(resolver);
        }
        return null;
    }

    /**
     * Releases a validator instance. No-op — validators created by this factory are
     * short-lived and have no resources to release.
     *
     * @param instance the validator instance to release
     */
    @Override
    public void releaseInstance(ConstraintValidator<?, ?> instance) {
        // No-op
    }
}
