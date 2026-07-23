// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native, type-keyed lookup table mapping a target type to its {@link ParamConverter}. The registry
 * is context-free and stable: it combines the framework built-in converters with the application's
 * {@link ParamConverterBinding} contributions, with application bindings overriding built-ins for the
 * same target type.
 *
 * <p>Resolution order for {@link #find(Class)} is: exact-class match, then the {@code Class.isEnum()}
 * synthesis rule, then an empty result. The full conversion chain (JAX-RS provider fallback, error
 * policy) lives in {@code ParamConversionResolver}, not here.
 */
public final class ParamConverterRegistry {

    private final Map<Class<?>, ParamConverter<?>> converters;

    private ParamConverterRegistry(Map<Class<?>, ParamConverter<?>> converters) {
        this.converters = converters;
    }

    /**
     * Builds a registry from the framework built-ins plus the supplied application bindings.
     * Application bindings override built-ins for the same target type. Two application bindings for
     * the same target type are a configuration error.
     *
     * @param appBindings the application-contributed converter bindings; never {@code null}
     * @return a registry combining built-ins and the supplied bindings
     * @throws IllegalStateException if two bindings target the same type
     */
    public static ParamConverterRegistry of(Set<ParamConverterBinding<?>> appBindings) {
        // ConcurrentHashMap so find()'s computeIfAbsent can promote synthesized enum converters into the
        // primary map without a separate lock; application bindings and built-ins are inserted here at
        // construction, so a later enum-synth computeIfAbsent never overwrites an app exact-class binding.
        Map<Class<?>, ParamConverter<?>> converters = new ConcurrentHashMap<>(BuiltinParamConverters.map());

        Map<Class<?>, ParamConverterBinding<?>> seen = new HashMap<>();
        for (ParamConverterBinding<?> binding : appBindings) {
            Class<?> targetType = binding.targetType();
            ParamConverterBinding<?> previous = seen.putIfAbsent(targetType, binding);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate ParamConverterBinding for target type " + targetType.getName());
            }
            // Application binding overrides any framework built-in for the same target type.
            converters.put(targetType, binding.converter());
        }

        return new ParamConverterRegistry(converters);
    }

    /**
     * Finds a converter for the given target type using exact-class then enum-synthesis resolution.
     *
     * @param targetType the type to convert to; never {@code null}
     * @return the resolved converter, or {@link Optional#empty()} if none applies
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Optional<ParamConverter<?>> find(Class<?> targetType) {
        ParamConverter<?> exact = converters.get(targetType);
        if (exact != null) {
            return Optional.of(exact);
        }
        if (targetType.isEnum()) {
            // Synthesize once and promote into the primary map. An app exact-class enum binding was
            // inserted at of() construction, so this computeIfAbsent never overwrites it.
            return Optional.of(converters.computeIfAbsent(targetType, t -> new EnumParamConverter((Class) t)));
        }
        return Optional.empty();
    }
}
