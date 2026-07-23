// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.codegen;

import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reflection-backed {@link ParameterMetadata} for the runtime (startup-scan) path.
 *
 * <p>Built from the parameter position, name, erased/generic types, and an {@link AnnotatedElement}
 * annotation source — a {@link java.lang.reflect.Parameter} for top-level method parameters, or a
 * bean-field {@link java.lang.reflect.Field} / {@link java.lang.reflect.RecordComponent} / accessor
 * {@link java.lang.reflect.Method} for {@code @BeanParam} sub-fields. The annotation lookups
 * ({@link #findAnnotation(Class)} / {@link #hasAnnotation(Class)}) delegate to
 * {@link AnnotatedElement#getAnnotation(Class)} / {@link AnnotatedElement#isAnnotationPresent(Class)},
 * and {@link #annotationsLazy()} supplies {@link AnnotatedElement#getAnnotations()}. This is the
 * scanner path's shared metadata view — reflective by nature, in contrast to the reflection-free,
 * literal-backed implementation emitted by codegen.
 *
 * <p>The annotation source may be {@code null}; in that case all lookups behave as if no annotations
 * are present and {@link #annotationsLazy()} supplies an empty array.
 *
 * <p>This class is {@code public} so both the JAX-RS scan path and the REST-client scan path can
 * construct it; it is otherwise an internal implementation detail of those scanners.
 */
public final class ReflectiveParameterMetadata implements ParameterMetadata {

    private static final Annotation[] EMPTY = new Annotation[0];

    private final int index;
    private final String name;
    private final Class<?> type;
    private final Type genericType;

    @Nullable
    private final AnnotatedElement annotationSource;

    /**
     * Creates a reflective parameter-metadata view backed by an {@link AnnotatedElement} annotation
     * source.
     *
     * @param index            the zero-based parameter position, or {@code -1} for bean-param fields
     *                         that have no method-parameter position
     * @param name             the parameter name (may be {@code null} for unnamed sources such as body
     *                         or context parameters)
     * @param type             the erased parameter type; must not be {@code null}
     * @param genericType      the generic parameter type, or {@code null} when none was captured
     * @param annotationSource the element whose annotations back the lookups (a {@code Parameter},
     *                         {@code Field}, {@code RecordComponent}, or accessor {@code Method}), or
     *                         {@code null} when no annotation source is available
     */
    public ReflectiveParameterMetadata(
            int index,
            @Nullable String name,
            Class<?> type,
            @Nullable Type genericType,
            @Nullable AnnotatedElement annotationSource) {
        this.index = index;
        this.name = name;
        this.type = type;
        this.genericType = genericType;
        this.annotationSource = annotationSource;
    }

    @Override
    public int index() {
        return index;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Class<?> type() {
        return type;
    }

    @Override
    public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
        return annotationSource == null ? Optional.empty() : Optional.ofNullable(annotationSource.getAnnotation(type));
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> type) {
        return annotationSource != null && annotationSource.isAnnotationPresent(type);
    }

    @Override
    public Type genericType() {
        return genericType;
    }

    @Override
    public Supplier<Annotation[]> annotationsLazy() {
        return () -> annotationSource == null ? EMPTY : annotationSource.getAnnotations();
    }
}
