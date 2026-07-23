// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.aop.Aspect;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local aspect <strong>trigger</strong> annotation ({@link Aspect}-meta-annotated) carrying an
 * attribute of an <strong>unsupported</strong> kind (a {@code float} member), used by the
 * {@code vertique-codegen-aop} P2-W2 compile-test.
 *
 * <p>{@code float} is one of the kinds {@link dev.vertique.codegen.meta.AnnotationLiteralEmitter}
 * cannot render (alongside {@code char} and {@code double}). Because this annotation is itself an
 * aspect trigger, the proxy emits a per-aspect {@code <Ann>$Literal} class and a per-occurrence
 * literal <em>instance</em> for it. Without an up-front precheck on the aspect's annotation type, the
 * literal emitter throws {@code UnsupportedOperationException} mid-emission (a processor crash with a
 * stack trace) instead of the clean {@code Diagnostics.error} FR-013-09c / FR-013-13 require.
 */
@Aspect(ordering = 1000)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestUnsupportedAspect {

    /**
     * Returns the aspect's around-chain ordering (higher is outermost).
     *
     * @return the ordering weight; defaults to {@code 1000}
     */
    int ordering() default 1000;

    /**
     * Returns the unsupported-kind attribute value.
     *
     * @return a {@code float} — an attribute kind {@code AnnotationLiteralEmitter} cannot render,
     *     forcing the aspect-literal emission to fail unless the processor prechecks it
     */
    float weight() default 1.0f;
}
