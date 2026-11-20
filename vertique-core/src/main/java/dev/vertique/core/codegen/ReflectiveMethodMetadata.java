// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.codegen;

import jakarta.annotation.Nullable;
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

    @Nullable
    private final List<Annotation> annotations;

    /**
     * Creates a reflective method-metadata view.
     *
     * @param method     the reflective method to wrap; must not be {@code null}
     * @param parameters the pre-resolved parameter metadata, in declaration order; must not be
     *                   {@code null} (an empty list is permitted)
     */
    public ReflectiveMethodMetadata(Method method, List<ParameterMetadata> parameters) {
        this(method, parameters, null);
    }

    /**
     * Creates a reflective method-metadata view whose annotation lookups see {@code annotations}
     * instead of the annotations declared on {@code method} alone, for a method that inherits the
     * annotations of the declarations it overrides. The first annotation of a type wins, so the
     * list should run from the method's own annotations to the most distant declaration.
     *
     * @param method      the reflective method to wrap; must not be {@code null}
     * @param parameters  the pre-resolved parameter metadata, in declaration order; must not be
     *                    {@code null}
     * @param annotations the merged annotations lookups resolve against, or {@code null} to use
     *                    the annotations declared on {@code method}
     */
    public ReflectiveMethodMetadata(
            Method method, List<ParameterMetadata> parameters, @Nullable List<Annotation> annotations) {
        this.method = method;
        this.parameters = List.copyOf(parameters);
        this.annotations = annotations == null ? null : List.copyOf(annotations);
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
        if (annotations == null) {
            return Optional.ofNullable(method.getAnnotation(type));
        }
        for (Annotation annotation : annotations) {
            if (annotation.annotationType() == type) {
                return Optional.of(type.cast(annotation));
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> type) {
        return annotations == null
                ? method.isAnnotationPresent(type)
                : annotations.stream().anyMatch(annotation -> annotation.annotationType() == type);
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
