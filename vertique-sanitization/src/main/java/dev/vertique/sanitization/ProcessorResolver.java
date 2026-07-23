// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.CanonicalizerBinding;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SanitizerBinding;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves {@link Canonicalizer} and {@link Sanitizer} instances by their implementation class using
 * a three-tier lookup strategy.
 *
 * <p>Resolution order for each tier:
 * <ol>
 *   <li><b>Dagger binding</b> — looks up the class in the injected {@link CanonicalizerBinding} or
 *       {@link SanitizerBinding} multibinding sets. This is the fast path and the correct choice for
 *       processors with injected dependencies.</li>
 *   <li><b>Reflection fallback</b> — if no binding is registered, attempts to instantiate the class
 *       via its public no-argument constructor. This supports simple stateless implementations that
 *       have no dependencies and were not explicitly registered.</li>
 *   <li><b>Error</b> — throws {@link IllegalStateException} if the class cannot be resolved
 *       by either of the above strategies.</li>
 * </ol>
 *
 * <p>Processors registered via {@link SanitizationModule} are always resolvable through tier 1.
 * Custom processors without Dagger bindings are resolvable through tier 2 as long as they expose
 * a public no-arg constructor.
 */
@Singleton
public class ProcessorResolver {

    private final Map<Class<? extends Canonicalizer>, Canonicalizer> canonicalizersByClass;
    private final Map<Class<? extends Sanitizer>, Sanitizer> sanitizersByClass;

    /**
     * Constructs a {@code ProcessorResolver} from the Dagger multibinding sets.
     *
     * @param canonicalizerBindings the set of registered canonicalizer bindings
     * @param sanitizerBindings     the set of registered sanitizer bindings
     */
    @Inject
    public ProcessorResolver(Set<CanonicalizerBinding> canonicalizerBindings, Set<SanitizerBinding> sanitizerBindings) {
        this.canonicalizersByClass = canonicalizerBindings.stream()
                .collect(Collectors.toMap(CanonicalizerBinding::type, CanonicalizerBinding::instance));
        this.sanitizersByClass = sanitizerBindings.stream()
                .collect(Collectors.toMap(SanitizerBinding::type, SanitizerBinding::instance));
    }

    /**
     * Resolves a {@link Canonicalizer} instance for the given implementation class.
     *
     * <p>First checks the Dagger binding map; if absent, attempts reflective instantiation via
     * the class's public no-arg constructor.
     *
     * @param type the canonicalizer implementation class to resolve
     * @return the resolved {@link Canonicalizer} instance
     * @throws IllegalStateException if the class is not registered and cannot be instantiated
     *                               via a public no-arg constructor
     */
    public Canonicalizer resolveCanonicalizer(Class<? extends Canonicalizer> type) {
        var bound = canonicalizersByClass.get(type);
        if (bound != null) {
            return bound;
        }
        return instantiate(type, "Canonicalizer");
    }

    /**
     * Resolves a {@link Sanitizer} instance for the given implementation class.
     *
     * <p>First checks the Dagger binding map; if absent, attempts reflective instantiation via
     * the class's public no-arg constructor.
     *
     * @param type the sanitizer implementation class to resolve
     * @return the resolved {@link Sanitizer} instance
     * @throws IllegalStateException if the class is not registered and cannot be instantiated
     *                               via a public no-arg constructor
     */
    public Sanitizer resolveSanitizer(Class<? extends Sanitizer> type) {
        var bound = sanitizersByClass.get(type);
        if (bound != null) {
            return bound;
        }
        return instantiate(type, "Sanitizer");
    }

    /**
     * Instantiates {@code type} via its public no-arg constructor.
     *
     * @param type  the class to instantiate
     * @param label human-readable label used in the error message ("Canonicalizer" or "Sanitizer")
     * @return the new instance
     * @throws IllegalStateException if the class has no public no-arg constructor or instantiation fails
     */
    private <T> T instantiate(Class<T> type, String label) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    label + " class " + type.getName()
                            + " is not registered as a Dagger binding and has no public no-arg constructor",
                    e);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "Failed to instantiate " + label + " class " + type.getName() + " via reflection", e);
        }
    }
}
