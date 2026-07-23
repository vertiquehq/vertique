// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import dev.vertique.core.util.GeneratedNames;
import java.util.Optional;

/**
 * Shared {@link ClassValue}-based lookup machinery for generated companion class registries.
 *
 * <p>Both {@link GeneratedJaxRsDescriptorRegistry} and {@link GeneratedJaxRsBeanParamRegistry}
 * use an identical classloader-driven lookup algorithm: derive the expected companion class FQN
 * from the source type, attempt {@link Class#forName}, instantiate via the no-arg constructor,
 * and cache the result. This helper captures that shared behaviour and the shared
 * {@link LookupResult} sealed type.
 *
 * <p>The {@code ClassValue} cache is classloader-aware: cache entries are eligible for GC
 * alongside their owning class loader, eliminating the multi-classloader retention concern that
 * a static {@code ConcurrentHashMap<Class<?>, ...>} would have.
 *
 * <p>Cache-miss policy:
 * <ul>
 *   <li>{@link ClassNotFoundException} → {@link LookupResult.Missing}: no companion on the
 *       classpath; the caller falls back to the reflective runtime.</li>
 *   <li>Any other {@link ReflectiveOperationException} or unchecked exception thrown from the
 *       generated constructor → {@link LookupResult.Broken}: a broken generated class is a build
 *       defect, not a runtime fallback case. The exception is wrapped, cached, and rethrown on
 *       every retrieval so consumers see the failure immediately without re-doing reflection.</li>
 * </ul>
 *
 * @param <T> the generated companion interface type (e.g.
 *            {@link GeneratedJaxRsResourceDescriptor} or
 *            {@link GeneratedJaxRsBeanParamModel})
 */
final class GeneratedCompanionRegistry<T> {

    private final String suffix;
    private final Class<T> companionInterface;

    private final ClassValue<LookupResult<T>> cache = new ClassValue<>() {
        @Override
        protected LookupResult<T> computeValue(Class<?> type) {
            return classloaderLookup(type);
        }
    };

    /**
     * Creates a registry helper for a specific companion suffix and interface.
     *
     * @param suffix             the suffix appended to the source type's simple name to derive
     *                           the companion class name (e.g. {@code "_JaxRsDescriptor"})
     * @param companionInterface the expected supertype of the companion class; used for the
     *                           checked cast after instantiation
     */
    GeneratedCompanionRegistry(String suffix, Class<T> companionInterface) {
        this.suffix = suffix;
        this.companionInterface = companionInterface;
    }

    /**
     * Looks up the generated companion for the given source type.
     *
     * <p>Results are cached per type in a {@link ClassValue}: the first call performs one
     * {@link Class#forName} + {@link java.lang.reflect.Constructor#newInstance}; all subsequent
     * calls for the same type are O(1) cache hits.
     *
     * @param sourceType the source type to look up; must not be {@code null}
     * @return the resolved companion, or {@link Optional#empty()} when no companion exists
     * @throws RuntimeException if the companion class exists but cannot be instantiated or cast
     */
    @SuppressWarnings("unchecked")
    Optional<T> lookup(Class<?> sourceType) {
        return switch (cache.get(sourceType)) {
            case LookupResult.Found<T>(T companion) -> Optional.of(companion);
            case LookupResult.Missing<T> ignored -> Optional.empty();
            case LookupResult.Broken<T>(RuntimeException error) -> throw error;
        };
    }

    /**
     * Derives the companion FQN from a source type and attempts to load + instantiate it.
     *
     * <p>FQN derivation algorithm (matches codegen-side {@code Identifiers.generatedClassName}):
     * <ul>
     *   <li>package: same as the source type (binary name up to and including the last {@code '.'})
     *   <li>simple name: binary class part (after last {@code '.'}) with {@code '$'} replaced by
     *       {@code '_'} — flattens nested types like {@code Outer$Inner} → {@code Outer_Inner}
     *   <li>suffix: as supplied to the constructor
     * </ul>
     *
     * @param type the source type
     * @return a {@link LookupResult} describing the outcome
     */
    private LookupResult<T> classloaderLookup(Class<?> type) {
        String fqn = derivedFqn(type, suffix);
        try {
            Class<?> generatedClass = Class.forName(fqn, true, type.getClassLoader());
            Object instance = generatedClass.getDeclaredConstructor().newInstance();
            return new LookupResult.Found<>(companionInterface.cast(instance));
        } catch (ClassNotFoundException notFound) {
            return LookupResult.Missing.instance();
        } catch (ClassCastException castEx) {
            return new LookupResult.Broken<>(new RuntimeException(
                    "Generated companion %s found but does not implement expected interface %s"
                            .formatted(fqn, companionInterface.getName()),
                    castEx));
        } catch (ReflectiveOperationException broken) {
            return new LookupResult.Broken<>(new RuntimeException(
                    "Generated companion %s present but failed to instantiate".formatted(fqn), broken));
        } catch (LinkageError broken) {
            // Present-but-broken: a failing static initializer or a link-time NoClassDefFoundError at the eager
            // Class.forName(initialize=true) load step — treat as broken, not missing.
            return new LookupResult.Broken<>(
                    new RuntimeException("Generated companion %s present but failed to load".formatted(fqn), broken));
        }
    }

    /**
     * Derives the expected companion class FQN for the given source type.
     *
     * <p>Package-private so that registry test classes can byte-equality-check the result
     * against the codegen-side {@code Identifiers.generatedClassName(...)}. Delegates to the shared
     * {@link GeneratedNames#companionFqn(Class, String)} so every runtime companion lookup derives the
     * name the same way (origin package, {@code $}-to-{@code _} nested flattening).
     *
     * @param type   the source type
     * @param suffix the companion name suffix (e.g. {@code "_JaxRsDescriptor"})
     * @return the FQN of the expected generated companion class
     */
    static String derivedFqn(Class<?> type, String suffix) {
        return GeneratedNames.companionFqn(type, suffix);
    }

    // --- LookupResult sealed type ---

    /**
     * Cached classloader-lookup result for a single source type.
     *
     * <p>{@link Found} carries the resolved companion; {@link Missing} indicates no companion
     * class on the classpath (reflective fallback); {@link Broken} carries the captured
     * instantiation failure for re-throw on every retrieval — caching the failure avoids
     * re-doing reflection per request when a generated class is structurally invalid.
     *
     * @param <T> the companion interface type
     */
    sealed interface LookupResult<T> {

        /**
         * Successful lookup: the companion was found and instantiated.
         *
         * @param <T>       the companion interface type
         * @param companion the resolved companion instance
         */
        record Found<T>(T companion) implements LookupResult<T> {}

        /**
         * No companion class on the classpath for this source type.
         *
         * <p>Implemented as an enum to avoid allocating a new instance per type miss.
         *
         * @param <T> the companion interface type (unused at runtime; present for type safety)
         */
        final class Missing<T> implements LookupResult<T> {

            private static final Missing<?> INSTANCE = new Missing<>();

            private Missing() {}

            /**
             * Returns the singleton missing sentinel, cast to the required type parameter.
             *
             * @param <T> the companion interface type
             * @return the singleton instance
             */
            @SuppressWarnings("unchecked")
            static <T> Missing<T> instance() {
                return (Missing<T>) INSTANCE;
            }
        }

        /**
         * Companion class found but could not be instantiated or cast.
         *
         * <p>The exception is rethrown on every retrieval so consumers always see the build
         * defect; reflection is not re-attempted after the first failure.
         *
         * @param <T>   the companion interface type
         * @param error the wrapped instantiation or cast failure
         */
        record Broken<T>(RuntimeException error) implements LookupResult<T> {}
    }
}
