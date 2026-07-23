// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation that marks a user-defined annotation as an aspect.
 *
 * <p>Placing {@code @Aspect} on an annotation type (for example {@code @Timed})
 * tells the {@code vertique-codegen-aop} processor that methods carrying that annotation should be
 * wrapped by an {@link AspectProvider}-produced {@link MethodInterceptor}. The aspect annotation and
 * its provider are associated by the provider's generic type parameter ({@code AspectProvider<A>});
 * there is no aspect {@code kind}/category in v1 — every aspect is a uniform around-interceptor.
 *
 * <p>{@link #ordering()} determines the position of the aspect in the around-chain. A higher value
 * is <em>outermost</em> (it wraps lower-ordered aspects). When two aspects share the same ordering,
 * the tie is broken deterministically by the fully-qualified name of the aspect annotation, so the
 * generated chain order is stable and reproducible. This ordering is resolved by the processor at
 * compile time; the runtime nester applies the resulting array as-is.
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Aspect {

    /**
     * Returns the ordering of this aspect in the around-chain.
     *
     * <p>Higher means outermost (wraps lower-ordered aspects); ties are broken deterministically by
     * the aspect annotation's fully-qualified name.
     *
     * @return the ordering weight; defaults to {@code 1000}
     */
    int ordering() default 1000;
}
