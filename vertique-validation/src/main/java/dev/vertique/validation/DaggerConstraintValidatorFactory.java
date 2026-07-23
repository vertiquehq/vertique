// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@link ConstraintValidatorFactory} that resolves validators through a three-tier chain:
 * <ol>
 *   <li><b>Dagger-managed validators</b> — individual validators contributed via
 *       {@code Set<ConstraintValidator<?,?>>} multibinding. Looked up by concrete class.
 *       <b>Important:</b> Dagger-managed validators must be stateless — they must not
 *       rely on {@code initialize()} to configure per-annotation state, since the same
 *       instance is shared across all uses of the constraint annotation.</li>
 *   <li><b>Contributed factories</b> — additional {@link ConstraintValidatorFactory} instances
 *       contributed via {@code Set<ConstraintValidatorFactory>} multibinding.
 *       Useful for registering batches of library validators.</li>
 *   <li><b>Reflection fallback</b> — instantiates the validator via no-arg constructor.
 *       Handles all standard Hibernate Validator built-in validators.</li>
 * </ol>
 */
@Slf4j
@Singleton
public class DaggerConstraintValidatorFactory implements ConstraintValidatorFactory {

    private final Map<Class<?>, ConstraintValidator<?, ?>> validatorsByClass;
    private final List<ConstraintValidatorFactory> delegateFactories;

    /**
     * Creates a new factory with Dagger-managed validators and delegate factories.
     *
     * @param validators        individual validators contributed via multibinding
     * @param delegateFactories additional factories contributed via multibinding
     */
    @Inject
    DaggerConstraintValidatorFactory(
            Set<ConstraintValidator<?, ?>> validators, Set<ConstraintValidatorFactory> delegateFactories) {
        this.validatorsByClass = validators.stream()
                .collect(Collectors.toMap(Object::getClass, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Duplicate ConstraintValidator class: "
                            + a.getClass().getName());
                }));
        this.delegateFactories = List.copyOf(delegateFactories);
    }

    /**
     * Resolves a {@link ConstraintValidator} instance through the three-tier chain.
     *
     * @param key the validator class to instantiate
     * @param <T> the validator type
     * @return the validator instance
     */
    @Override
    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
        // Tier 1: Dagger-managed individual validators
        @SuppressWarnings("unchecked")
        T daggerManaged = (T) validatorsByClass.get(key);
        if (daggerManaged != null) {
            return daggerManaged;
        }

        // Tier 2: Contributed factories
        for (ConstraintValidatorFactory factory : delegateFactories) {
            try {
                T result = factory.getInstance(key);
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                log.trace(
                        "Delegate factory {} could not create {}: {}",
                        factory.getClass().getSimpleName(),
                        key.getSimpleName(),
                        e.getMessage());
            }
        }

        // Tier 3: Reflection fallback — new instance each time so HV can call initialize()
        // per annotation descriptor without shared-state bugs. HV caches internally.
        try {
            return key.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new jakarta.validation.ValidationException(
                    "Cannot instantiate ConstraintValidator: " + key.getName(), e);
        }
    }

    /**
     * Releases a validator instance. No-op: Dagger manages lifecycle for DI-managed
     * validators; reflection-created validators are typically stateless.
     *
     * @param instance the validator instance to release
     */
    @Override
    public void releaseInstance(ConstraintValidator<?, ?> instance) {
        // No-op
    }
}
