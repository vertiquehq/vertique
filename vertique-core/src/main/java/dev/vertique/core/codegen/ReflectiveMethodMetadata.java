// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.codegen;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

/**
 * Reflection-backed {@link MethodMetadata} for the runtime (startup-scan) path.
 *
 * <p>Wraps a live {@link Method} and a pre-resolved list of {@link ParameterMetadata}. All accessors
 * delegate to the reflective {@link Method} ({@link Method#getName()},
 * {@link Method#getDeclaringClass()}, {@link Method#getReturnType()},
 * {@link Method#getParameterTypes()}, {@link Method#getGenericReturnType()},
 * {@link Method#getAnnotation(Class)}, {@link Method#isAnnotationPresent(Class)}) — in contrast to the
 * reflection-free, literal-backed implementation emitted by codegen.
 *
 * <p>This class is {@code public} so the JAX-RS and REST-client scan paths can construct it; it is
 * otherwise an internal implementation detail of those scanners.
 */
public final class ReflectiveMethodMetadata implements MethodMetadata {

    private final Method method;
    private final List<ParameterMetadata> parameters;

    /**
     * Creates a reflective method-metadata view.
     *
     * @param method     the reflective method to wrap; must not be {@code null}
     * @param parameters the pre-resolved parameter metadata, in declaration order; must not be
     *                   {@code null} (an empty list is permitted)
     */
    public ReflectiveMethodMetadata(Method method, List<ParameterMetadata> parameters) {
        this.method = method;
        this.parameters = List.copyOf(parameters);
    }

    @Override
    public String name() {
        return method.getName();
    }

    @Override
    public Class<?> declaringType() {
        return method.getDeclaringClass();
    }

    @Override
    public Class<?> returnType() {
        return method.getReturnType();
    }

    @Override
    public Class<?>[] parameterTypes() {
        return method.getParameterTypes();
    }

    @Override
    public List<ParameterMetadata> parameters() {
        return parameters;
    }

    @Override
    public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
        return Optional.ofNullable(method.getAnnotation(type));
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> type) {
        return method.isAnnotationPresent(type);
    }

    @Override
    public Type genericReturnType() {
        return method.getGenericReturnType();
    }

    @Override
    public Method asMethod() {
        return method;
    }
}
