// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import dev.vertique.core.validation.CharacterPolicy;
import dev.vertique.core.validation.CharacterPolicyBinding;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ValidationException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves {@link CharacterPolicy} instances through a two-tier chain:
 * <ol>
 *   <li><b>Dagger binding</b> — looks up the policy class in the
 *       {@code Set<CharacterPolicyBinding>} multibinding. Returns the singleton instance
 *       registered via Dagger, enabling full dependency injection into the policy.</li>
 *   <li><b>Reflection fallback</b> — instantiates the policy class via its public no-arg
 *       constructor. Handles stateless built-in policies without Dagger wiring.</li>
 * </ol>
 *
 * <p>Throws {@link ValidationException} if neither tier can produce an instance.
 *
 * <p>For programmatic use in application code. The {@link AllowedCharactersValidator}
 * uses reflection directly because it must work without Dagger.
 *
 * @see CharacterPolicy
 * @see CharacterPolicyBinding
 */
@Singleton
public class CharacterPolicyResolver {

    private final Map<Class<? extends CharacterPolicy>, CharacterPolicy> policyByClass;

    /**
     * Creates a new resolver pre-loaded with the contributed Dagger-managed policy instances.
     *
     * @param bindings the set of Dagger-managed policy bindings contributed via multibinding
     */
    @Inject
    public CharacterPolicyResolver(Set<CharacterPolicyBinding> bindings) {
        this.policyByClass = bindings.stream()
                .collect(Collectors.toMap(CharacterPolicyBinding::type, CharacterPolicyBinding::instance));
    }

    /**
     * Resolves a {@link CharacterPolicy} for the given implementation class.
     *
     * <p>Tier 1 returns the Dagger-managed singleton if registered; Tier 2 instantiates
     * a new instance via reflection; otherwise throws.
     *
     * @param policyClass the character policy implementation class to resolve
     * @return the resolved {@link CharacterPolicy} instance
     * @throws ValidationException if the policy cannot be instantiated
     */
    public CharacterPolicy resolve(Class<? extends CharacterPolicy> policyClass) {
        // Tier 1: Dagger binding
        CharacterPolicy bound = policyByClass.get(policyClass);
        if (bound != null) {
            return bound;
        }
        // Tier 2: reflection fallback
        try {
            return policyClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot instantiate CharacterPolicy: " + policyClass.getName(), e);
        }
    }
}
