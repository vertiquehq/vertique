// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.function.Supplier;

/**
 * Per-parameter context that the {@link ParamConversionResolver} threads through the conversion
 * chain. It carries everything the JAX-RS {@code ParamConverterProvider} bridge and the error policy
 * need — the parameter name, its {@link ParamSource}, the raw and generic target types, the optional
 * collection component type, and a <em>lazy</em> supplier of the parameter's declared annotations.
 *
 * <p>The annotation supplier is "lazy" in that the resolver invokes it solely when at least one
 * JAX-RS {@code ParamConverterProvider} is registered (the native registry path never consults it).
 * Whether invoking it allocates a fresh {@code Annotation[]} depends on the backing
 * {@code ParameterMetadata} implementation: a codegen-emitted, literal-backed view typically closes
 * over an already-materialized array (free to invoke repeatedly), while the reflective
 * {@code dev.vertique.core.codegen.ReflectiveParameterMetadata} view calls
 * {@link java.lang.reflect.AnnotatedElement#getAnnotations()} on each invocation, which re-clones the
 * array. Callers on a hot path should cache the resulting {@link ConversionContext} rather than
 * rebuild it per call (see e.g. {@code ParameterExtractor.scalarContext} and
 * {@code RestClientRequestFactory.scalarContext}).
 *
 * @param paramName       the declared parameter name; never {@code null}
 * @param source          the transport source the parameter is read from; never {@code null}
 * @param rawType         the erased target type to convert to; never {@code null}
 * @param genericType     the parameterized target type, or {@code null} when unavailable
 * @param componentType   the element type for collection-valued parameters, or {@code null}
 * @param annotationsLazy a supplier of the parameter's declared annotations; never {@code null},
 *                        invoked only when a JAX-RS provider is present
 */
public record ConversionContext(
        String paramName,
        ParamSource source,
        Class<?> rawType,
        @Nullable Type genericType,
        @Nullable Class<?> componentType,
        Supplier<Annotation[]> annotationsLazy) {}
