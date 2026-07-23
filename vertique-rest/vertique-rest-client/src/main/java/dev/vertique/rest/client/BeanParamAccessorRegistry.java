// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.core.util.GeneratedNames;
import dev.vertique.rest.client.exception.RestClientConfigurationException;

/**
 * Registry that resolves {@link BeanParamAccessor} instances for {@code @BeanParam} bean types.
 *
 * <p>The registry uses a two-tier resolution strategy:
 *
 * <ol>
 *   <li>Look up a generated accessor via {@code Class.forName} on the FQN derived by
 *       {@link GeneratedNames#companionFqn(Class, String)} (origin package, {@code $}-to-{@code _}
 *       nested flattening) using the bean type's own class loader. If the class is found, it is
 *       instantiated via its public no-arg constructor and cached.
 *   <li>If the lookup results in {@link ClassNotFoundException}, the reflective fallback
 *       ({@link ReflectiveBeanParamAccessor}) is returned instead.
 * </ol>
 *
 * <p>Resolution results are cached via {@link ClassValue}{@code <BeanParamAccessor<?>>}, which is
 * classloader-aware: cache entries are eligible for GC alongside their class loader, eliminating
 * the OSGi/multi-classloader retention concern that a static
 * {@code ConcurrentHashMap<Class<?>, …>} would have. The first resolution per type performs one
 * reflective {@code Class.forName} + {@code newInstance}; all subsequent calls for the same type
 * are O(1) cache hits.
 *
 * <p><strong>Singleton lifecycle:</strong> {@link #shared()} returns a process-wide singleton
 * backed by the same {@link ReflectiveBeanParamAccessor} fallback. Both standalone
 * {@link RestClientBuilder} construction and Dagger-injected construction (via
 * {@link RestClientFactory}) use this shared instance so that the resolution cache is shared
 * across all clients in the JVM.
 *
 * <p>If a generated class is found but cannot be instantiated (e.g. its no-arg constructor
 * throws), a {@link RestClientConfigurationException} is propagated — this indicates a broken
 * generated class and should not be silently swallowed.
 */
public final class BeanParamAccessorRegistry {

    // --- Static singleton ---

    private static final BeanParamAccessorRegistry SHARED =
            new BeanParamAccessorRegistry(new ReflectiveBeanParamAccessor());

    /**
     * Returns the process-wide singleton registry. Both standalone and Dagger-injected
     * {@link RestClientBuilder} paths use this instance so that generated-accessor lookups are
     * cached across all clients.
     *
     * @return the shared registry instance
     */
    public static BeanParamAccessorRegistry shared() {
        return SHARED;
    }

    // --- Instance state ---

    private final ClassValue<BeanParamAccessor<?>> cache = new ClassValue<>() {
        @Override
        protected BeanParamAccessor<?> computeValue(Class<?> type) {
            return doResolve(type);
        }
    };

    private final ReflectiveBeanParamAccessor fallback;

    /**
     * Creates a new registry with the given reflective fallback. Prefer {@link #shared()} for
     * normal use; this constructor is provided for testing with a fresh cache per test.
     *
     * @param fallback the reflective accessor used when no generated accessor exists for a type
     */
    public BeanParamAccessorRegistry(ReflectiveBeanParamAccessor fallback) {
        this.fallback = fallback;
    }

    /**
     * Resolves the {@link BeanParamAccessor} for the given bean type.
     *
     * <p>On first call for a type, attempts to load the generated accessor whose FQN is derived via
     * {@link GeneratedNames#companionFqn(Class, String)} using {@link Class#forName}. On cache hit,
     * returns the cached result immediately without reflection.
     *
     * @param <T> the bean type
     * @param beanType the runtime class of the bean
     * @return the resolved accessor; never {@code null}
     * @throws RestClientConfigurationException if a generated accessor class is found but cannot
     *     be instantiated
     */
    @SuppressWarnings("unchecked")
    public <T> BeanParamAccessor<T> resolve(Class<T> beanType) {
        return (BeanParamAccessor<T>) cache.get(beanType);
    }

    // --- Private resolution ---

    /**
     * Performs the actual lookup: tries {@code Class.forName} on the FQN from
     * {@link GeneratedNames#companionFqn(Class, String)}; returns the fallback on
     * {@link ClassNotFoundException}; wraps any other {@link ReflectiveOperationException},
     * {@link LinkageError}, or wrong-type {@link ClassCastException} in a
     * {@link RestClientConfigurationException}.
     *
     * @param beanType the bean type to resolve
     * @return the resolved accessor; the reflective fallback if no generated class exists
     * @throws RestClientConfigurationException if the generated class exists but cannot be
     *     instantiated
     */
    @SuppressWarnings("unchecked")
    private BeanParamAccessor<?> doResolve(Class<?> beanType) {
        // Derive the generated accessor FQN via the shared helper (origin package, '$'->'_' nested
        // flattening) — the same scheme BeanAccessorEmitter emits and every other runtime companion
        // lookup uses. Example: com.example.Outer$Inner -> com.example.Outer_Inner_BeanParamAccessor.
        String generatedFqn = GeneratedNames.companionFqn(beanType, "_BeanParamAccessor");
        try {
            Class<?> generatedClass = Class.forName(generatedFqn, true, beanType.getClassLoader());
            return (BeanParamAccessor<?>)
                    generatedClass.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            return fallback;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            // Present-but-broken accessor: fail loudly. LinkageError covers a failing static initializer or a
            // link-time NoClassDefFoundError at the eager Class.forName(initialize=true) load step;
            // ClassCastException covers a present companion that does not implement BeanParamAccessor.
            throw new RestClientConfigurationException(
                    "Generated bean accessor %s present but failed to instantiate".formatted(generatedFqn), e);
        }
    }
}
