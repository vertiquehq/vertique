// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.meta;

import dev.vertique.core.codegen.ParameterMetadata;
import dev.vertique.core.codegen.ReflectiveParameterMetadata;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Metadata for a single parameter of a JAX-RS client interface method.
 *
 * <p>Captures the binding information needed by the proxy invocation handler to extract argument
 * values and map them to the outgoing HTTP request.
 *
 * <p>This record <em>composes</em> a neutral {@link ParameterMetadata} view rather than carrying eager
 * {@code name}/{@code type}/{@code genericType}/{@code index} fields: those are reached through
 * {@link #parameterMetadata()} (or the delegating {@link #name()} / {@link #type()} /
 * {@link #genericType()} / {@link #index()} / {@link #findAnnotation(Class)} /
 * {@link #hasAnnotation(Class)} / {@link #annotationsLazy()} accessors). On the runtime scan path the
 * composed view is a {@link ReflectiveParameterMetadata}; on the codegen path it is the generated,
 * literal-backed implementation. The HTTP-binding fields ({@link #source()},
 * {@link #accessorName()}, {@link #defaultValue()}, {@link #componentType()}, {@link #beanFields()})
 * remain domain fields specific to the REST-client binding model.
 *
 * @param parameterMetadata the composed neutral parameter-metadata view supplying the parameter's
 *     name, type, generic type, index, and annotations; must not be {@code null}
 * @param accessorName the Java field or record-component name used for reflective access on bean
 *     parameters (e.g. {@code "pageSize"} when the Java field is {@code pageSize} but the
 *     annotation says {@code "size"}); {@code null} for top-level method parameters which are
 *     accessed by index
 * @param source where the parameter value is bound in the HTTP request
 * @param componentType the element type for a collection or array parameter (e.g. {@code UUID} for
 *     {@code List<UUID>} or {@code UUID[]}); {@code null} for scalar parameters
 * @param defaultValue the {@code @DefaultValue} string if present; {@code null} otherwise
 * @param beanFields the expanded sub-parameters for {@link ParamSource#BEAN_PARAM} entries; empty
 *     list for all other sources
 */
public record ClientParamMeta(
        ParameterMetadata parameterMetadata,
        @Nullable String accessorName,
        ParamSource source,
        @Nullable Class<?> componentType,
        @Nullable String defaultValue,
        List<ClientParamMeta> beanFields) {

    /**
     * Identifies where a method parameter value is bound in the outgoing HTTP request.
     */
    public enum ParamSource {
        /** Value substituted into a URI path template segment (e.g. {@code /items/{id}}). */
        PATH,
        /** Value appended as a URI query parameter. */
        QUERY,
        /** Value sent as an HTTP request header. */
        HEADER,
        /** Value sent as a request cookie. */
        COOKIE,
        /** Value provides the full absolute request URI, overriding the base URL and path template. */
        URL,
        /** Value serialized as the HTTP request body. */
        BODY,
        /**
         * Composite parameter object whose fields/record-components carry individual JAX-RS
         * parameter annotations. The proxy expands the object into sub-{@link ClientParamMeta}
         * entries stored in {@link #beanFields()}.
         */
        BEAN_PARAM
    }

    /**
     * Convenience constructor for non-bean parameters (no sub-fields, no separate accessor name).
     *
     * @param name the parameter name from the JAX-RS annotation; {@code null} for body params
     * @param source the binding source
     * @param type the raw declared parameter type
     * @param genericType the full generic type; {@code null} for non-body params
     * @param componentType the element type for collection/array params; {@code null} for scalars
     * @param defaultValue the {@code @DefaultValue} string; {@code null} if absent
     * @param index the zero-based parameter index
     * @param annotationSource the element whose annotations back this parameter (the reflected
     *     {@link java.lang.reflect.Parameter}); {@code null} if none
     */
    public ClientParamMeta(
            @Nullable String name,
            ParamSource source,
            Class<?> type,
            @Nullable Type genericType,
            @Nullable Class<?> componentType,
            @Nullable String defaultValue,
            int index,
            @Nullable java.lang.reflect.AnnotatedElement annotationSource) {
        this(
                new ReflectiveParameterMetadata(index, name, type, genericType, annotationSource),
                null,
                source,
                componentType,
                defaultValue,
                List.of());
    }

    /**
     * Convenience constructor for bean sub-field parameters where the wire name and accessor name
     * may differ. Sub-fields are not expanded further (no nested beanFields) and carry no
     * method-parameter position (index {@code -1}).
     *
     * @param name the wire name from the JAX-RS annotation (e.g. {@code "size"})
     * @param accessorName the Java field or record-component name for reflection access (e.g.
     *     {@code "pageSize"}); {@code null} if the same as {@code name}
     * @param source the binding source
     * @param type the raw declared parameter type
     * @param componentType the element type for collection/array fields; {@code null} for scalars
     * @param defaultValue the {@code @DefaultValue} string; {@code null} if absent
     * @param annotationSource the element whose annotations back this field (the {@code Field},
     *     {@code RecordComponent}, or accessor {@code Method}); {@code null} if none
     */
    public ClientParamMeta(
            @Nullable String name,
            @Nullable String accessorName,
            ParamSource source,
            Class<?> type,
            @Nullable Class<?> componentType,
            @Nullable String defaultValue,
            @Nullable java.lang.reflect.AnnotatedElement annotationSource) {
        this(
                new ReflectiveParameterMetadata(-1, name, type, null, annotationSource),
                accessorName,
                source,
                componentType,
                defaultValue,
                List.of());
    }

    // --- composed ParameterMetadata delegation ---

    /**
     * Returns the parameter name, delegating to the composed {@link ParameterMetadata} view.
     *
     * @return the parameter name (from the JAX-RS annotation), or {@code null} for body/URL params
     */
    public String name() {
        return parameterMetadata.name();
    }

    /**
     * Returns the raw declared parameter type, delegating to the composed view.
     *
     * @return the erased parameter type
     */
    public Class<?> type() {
        return parameterMetadata.type();
    }

    /**
     * Returns the full generic parameter type, delegating to the composed view.
     *
     * @return the generic parameter type, or {@code null} when none was captured
     */
    public Type genericType() {
        return parameterMetadata.genericType();
    }

    /**
     * Returns the zero-based position of this parameter in the method signature, delegating to the
     * composed view. Bean sub-field entries carry {@code -1}.
     *
     * @return the parameter index, or {@code -1} for bean sub-fields
     */
    public int index() {
        return parameterMetadata.index();
    }

    /**
     * Looks up an annotation of the given type on the parameter, delegating to the composed view.
     *
     * @param annotationType the annotation type to look up
     * @param <A> the annotation type
     * @return the annotation if present, otherwise {@link Optional#empty()}
     */
    public <A extends Annotation> Optional<A> findAnnotation(Class<A> annotationType) {
        return parameterMetadata.findAnnotation(annotationType);
    }

    /**
     * Reports whether an annotation of the given type is declared on the parameter, delegating to
     * the composed view.
     *
     * @param annotationType the annotation type to test for
     * @return {@code true} if the annotation is present, {@code false} otherwise
     */
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return parameterMetadata.hasAnnotation(annotationType);
    }

    /**
     * Returns a supplier of the parameter's full declared annotation array, delegating to the
     * composed view's {@link ParameterMetadata#annotationsLazy()} bridge.
     *
     * @return a supplier of the parameter's annotations (never {@code null})
     */
    public Supplier<Annotation[]> annotationsLazy() {
        return parameterMetadata.annotationsLazy();
    }
}
