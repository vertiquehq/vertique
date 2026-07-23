// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.aop;

import dev.vertique.core.codegen.MethodMetadata;
import java.lang.annotation.Annotation;

/**
 * Framework-side factory that builds the {@link MethodInterceptor} for a single aspect-annotated
 * method.
 *
 * <p>An {@code AspectProvider} is associated with its aspect annotation purely by the generic type
 * parameter {@code A}: a provider declared as {@code AspectProvider<Timed>} supplies the interceptor
 * for methods carrying {@code @Timed}. The generated proxy constructor injects the provider by its
 * parameterized interface type so Dagger can resolve it from any module that binds it.
 *
 * <p>Both arguments are produced reflection-free by the codegen processor: the {@code target} is a
 * generated {@link MethodMetadata} of constant accessors, and the {@code annotation} is a generated
 * annotation-literal carrying the method's aspect-annotation attribute values.
 *
 * @param <A> the aspect annotation type this provider handles
 */
@FunctionalInterface
public interface AspectProvider<A extends Annotation> {

    /**
     * Builds the interceptor for one aspect-annotated method.
     *
     * @param target the reflection-free metadata of the intercepted method
     * @param annotation the aspect annotation instance present on the method
     * @return the interceptor that applies this aspect to the method
     */
    MethodInterceptor interceptor(MethodMetadata target, A annotation);
}
