// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Reflection-based {@link BeanParamAccessor} that extracts field values using Java reflection.
 *
 * <p>This is the default fallback used by {@link BeanParamAccessorRegistry} when no generated
 * accessor class exists for a bean type. It preserves exactly the same behaviour as the prior
 * {@code RestClientRequestFactory.extractFieldValue} implementation:
 *
 * <ul>
 *   <li>For records: iterates {@link Class#getRecordComponents()}, invokes the matching accessor
 *       method.
 *   <li>For regular classes: walks the superclass chain via {@link Class#getSuperclass()}, using
 *       {@link Class#getDeclaredField(String)}.
 *   <li>Returns {@code null} when a field cannot be found or accessed.
 * </ul>
 *
 * <p><strong>Two-level cache:</strong> reflective resolution results are cached in a
 * {@link ClassValue}{@code <ConcurrentHashMap<String, Resolved>>} structure. The outer
 * {@link ClassValue} ensures per-class maps are GC'd alongside their class loaders, preventing
 * retention in OSGi / multi-classloader environments. The inner {@link ConcurrentHashMap} caches
 * one {@link Resolved} entry per field name so that {@link Method#setAccessible(boolean)} and
 * superclass-chain walking are performed at most once per {@code (Class, fieldName)} pair. Negative
 * (miss) results are cached via the {@link Resolved.Miss} sentinel.
 *
 * <p>The {@link #fieldNames()} method returns an empty list because the reflective accessor
 * resolves names on demand rather than maintaining a static enumeration.
 */
@Slf4j
public class ReflectiveBeanParamAccessor implements BeanParamAccessor<Object> {

    // --- Cache infrastructure ---

    /**
     * Carrier for a resolved (or negatively-resolved) reflection reference.
     *
     * <p>Each of the three implementations encapsulates the minimal state needed to invoke the
     * member on a given bean — the {@link Method} for record components, the {@link Field} for
     * class fields, and a no-op singleton for cache misses.
     */
    sealed interface Resolved permits Resolved.RecordHit, Resolved.ClassHit, Resolved.Miss {

        /**
         * Invokes the underlying member on {@code bean} and returns the value.
         *
         * @param bean the bean or record instance
         * @return the member value, or {@code null} for {@link Miss}
         * @throws ReflectiveOperationException if the reflective invocation fails
         */
        Object invoke(Object bean) throws ReflectiveOperationException;

        /**
         * Cached record-component hit: holds the accessor {@link Method} with
         * {@link Method#setAccessible(boolean)} already applied.
         *
         * @param accessor the record component accessor method (already made accessible)
         */
        record RecordHit(Method accessor) implements Resolved {
            /** {@inheritDoc} */
            @Override
            public Object invoke(Object bean) throws ReflectiveOperationException {
                return accessor.invoke(bean);
            }
        }

        /**
         * Cached class-field hit: holds the {@link Field} with
         * {@link Field#setAccessible(boolean)} already applied.
         *
         * @param field the declared field (already made accessible)
         */
        record ClassHit(Field field) implements Resolved {
            /** {@inheritDoc} */
            @Override
            public Object invoke(Object bean) throws ReflectiveOperationException {
                return field.get(bean);
            }
        }

        /**
         * Sentinel cached for field names that could not be resolved. Prevents repeated superclass
         * walks on subsequent calls for the same unknown name.
         */
        enum Miss implements Resolved {
            /** The singleton miss sentinel. */
            INSTANCE;

            /** {@inheritDoc} */
            @Override
            public Object invoke(Object bean) {
                return null;
            }
        }
    }

    /**
     * Shared production cache: one {@link ConcurrentHashMap} per class, freed when the class
     * loader is GC'd. Inner map is keyed by the Java member name; value is the cached
     * {@link Resolved} carrier.
     *
     * <p>The inner map is bounded in practice by the set of field names callers iterate (taken from
     * {@code ClientParamMeta}), which is a fixed compile-time list per bean type. Callers must not
     * pass attacker-controlled strings as {@code fieldName} or the negative-cache slot will grow
     * unbounded.
     */
    private static final ClassValue<ConcurrentHashMap<String, Resolved>> SHARED_CACHE = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<String, Resolved> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    // --- Constructor ---

    /** Creates a new reflective bean param accessor. */
    public ReflectiveBeanParamAccessor() {}

    // --- BeanParamAccessor implementation ---

    /**
     * Extracts a field value from a bean or record by field/component name using a cached
     * reflective reference.
     *
     * <p>On the first call for a given {@code (beanClass, fieldName)} pair the accessor method or
     * field is resolved via reflection, {@link Method#setAccessible(boolean)} /
     * {@link Field#setAccessible(boolean)} is called once, and the result is stored in
     * {@link #SHARED_CACHE}. All subsequent calls for the same pair use the cached {@link Resolved}
     * with no additional reflection overhead.
     *
     * @param bean the bean or record instance to extract from
     * @param fieldName the Java field or record-component name to access
     * @return the field value, or {@code null} if not accessible or not found
     */
    @Override
    public Object extract(Object bean, String fieldName) {
        Class<?> beanClass = bean.getClass();
        ConcurrentHashMap<String, Resolved> perClass = SHARED_CACHE.get(beanClass);
        Resolved resolved = perClass.get(fieldName);
        if (resolved == null) {
            resolved = perClass.computeIfAbsent(fieldName, k -> resolveFor(beanClass, k));
        }
        try {
            return resolved.invoke(bean);
        } catch (ReflectiveOperationException e) {
            log.debug(
                    "Cached reflective invocation failed for field '{}' on {}",
                    fieldName,
                    beanClass.getSimpleName(),
                    e);
            return null;
        }
    }

    /**
     * Returns an empty list because the reflective accessor resolves field names on demand.
     *
     * @return an empty immutable list
     */
    @Override
    public List<String> fieldNames() {
        return List.of();
    }

    // --- Internal: visible for tests ---

    /**
     * Returns the number of cached {@link Resolved} entries for {@code beanType} in the shared
     * cache. Visible-for-testing only; intended for cache-shape assertions in
     * {@code ReflectiveBeanParamAccessorTest}. Not part of the public API.
     *
     * @param beanType the class whose per-class cache slot to inspect
     * @return the number of distinct field names cached for {@code beanType}
     */
    static int cachedFieldCount(Class<?> beanType) {
        return SHARED_CACHE.get(beanType).size();
    }

    // --- Private resolution ---

    /**
     * Resolves the {@link Resolved} carrier for a given {@code (beanType, fieldName)} pair.
     * Called at most once per pair via {@link ConcurrentHashMap#computeIfAbsent}.
     *
     * <p>For records, iterates {@link Class#getRecordComponents()} looking for a component whose
     * name matches {@code fieldName}; on match, calls {@link Method#setAccessible(boolean)} and
     * returns a {@link Resolved.RecordHit}. For regular classes, walks the superclass chain using
     * {@link Class#getDeclaredField(String)}; on match, calls {@link Field#setAccessible(boolean)}
     * and returns a {@link Resolved.ClassHit}. Returns {@link Resolved.Miss#INSTANCE} on any
     * resolution failure.
     *
     * @param beanType the class to resolve the member for
     * @param fieldName the Java field or record-component name
     * @return the resolved carrier; never {@code null}
     */
    private Resolved resolveFor(Class<?> beanType, String fieldName) {
        if (beanType.isRecord()) {
            return resolveRecord(beanType, fieldName);
        }
        return resolveClass(beanType, fieldName);
    }

    /**
     * Resolves a record-component accessor method by iterating {@link Class#getRecordComponents()}.
     *
     * @param beanType the record class
     * @param fieldName the component name to find
     * @return a {@link Resolved.RecordHit} on success, or {@link Resolved.Miss#INSTANCE}
     */
    private Resolved resolveRecord(Class<?> beanType, String fieldName) {
        try {
            for (RecordComponent component : beanType.getRecordComponents()) {
                if (component.getName().equals(fieldName)) {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    return new Resolved.RecordHit(accessor);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to resolve record component '{}' on {}", fieldName, beanType.getSimpleName(), e);
        }
        return Resolved.Miss.INSTANCE;
    }

    /**
     * Resolves a class field by walking the superclass chain using
     * {@link Class#getDeclaredField(String)}.
     *
     * @param beanType the starting class (usually the runtime type of the bean)
     * @param fieldName the field name to find
     * @return a {@link Resolved.ClassHit} on success, or {@link Resolved.Miss#INSTANCE}
     */
    private Resolved resolveClass(Class<?> beanType, String fieldName) {
        Class<?> current = beanType;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return new Resolved.ClassHit(field);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Exception e) {
                log.debug("Failed to resolve field '{}' on {}", fieldName, beanType.getSimpleName(), e);
                return Resolved.Miss.INSTANCE;
            }
        }
        return Resolved.Miss.INSTANCE;
    }
}
