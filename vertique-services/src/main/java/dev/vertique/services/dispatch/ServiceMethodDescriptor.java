// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.dispatch;

import java.lang.reflect.Method;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * Identifies a service method by name, parameter types, and declaring class without
 * holding a direct {@link Method} reference.
 *
 * <p>Supports lazy resolution for server-side reflective invocation via {@link #resolve()}.
 * When created from a {@link Method} via {@link #of(Method)}, the resolved method is
 * pre-cached for zero-overhead invocation. The alternative factory
 * {@link #of(String, List, Class)} supports compile-time code generation where a
 * {@link Method} is not available at construction time.
 *
 * <p>This is a class rather than a record because resolved {@link Method} references are
 * cached in a volatile field for per-invocation performance. Equality is based solely on
 * ({@code name}, {@code parameterTypes}, {@code declaringClass}).
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode(of = {"name", "parameterTypes", "declaringClass"})
public final class ServiceMethodDescriptor {

    private final String name;
    private final List<Class<?>> parameterTypes;
    private final Class<?> declaringClass;

    private volatile Method resolved;

    private ServiceMethodDescriptor(String name, List<Class<?>> parameterTypes, Class<?> declaringClass) {
        this.name = name;
        this.parameterTypes = List.copyOf(parameterTypes);
        this.declaringClass = declaringClass;
    }

    /**
     * Creates a descriptor from a {@link Method}, pre-caching the resolved method.
     *
     * @param method the method to describe
     * @return a descriptor with the method pre-cached
     */
    public static ServiceMethodDescriptor of(Method method) {
        var descriptor = new ServiceMethodDescriptor(
                method.getName(), List.of(method.getParameterTypes()), method.getDeclaringClass());
        descriptor.resolved = method;
        return descriptor;
    }

    /**
     * Creates a descriptor without a pre-resolved {@link Method}.
     *
     * <p>Intended for compile-time code generation where a {@link Method} instance
     * is not available at construction time. The method will be resolved lazily on
     * the first call to {@link #resolve()}.
     *
     * <p>Note: {@link #resolve()} uses {@link Class#getMethod}, which only finds
     * public methods. This is appropriate for contract interfaces and validated
     * handler methods, but will throw {@link IllegalStateException} for non-public methods.
     *
     * @param name the method name
     * @param parameterTypes the method parameter types
     * @param declaringClass the class that declares the method
     * @return a descriptor that will resolve the method lazily
     */
    public static ServiceMethodDescriptor of(String name, List<Class<?>> parameterTypes, Class<?> declaringClass) {
        return new ServiceMethodDescriptor(name, parameterTypes, declaringClass);
    }

    /**
     * Resolves to a {@link Method} for reflective invocation.
     *
     * <p>The resolved method is cached after the first resolution. Thread-safe via
     * volatile field semantics.
     *
     * @return the resolved method
     * @throws IllegalStateException if the method cannot be found on the declaring class
     */
    public Method resolve() {
        Method m = resolved;
        if (m != null) {
            return m;
        }
        try {
            m = declaringClass.getMethod(name, parameterTypes.toArray(Class<?>[]::new));
            resolved = m;
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Method " + name + " not found on " + declaringClass.getName(), e);
        }
    }

    @Override
    public String toString() {
        return declaringClass.getSimpleName() + "." + name;
    }
}
