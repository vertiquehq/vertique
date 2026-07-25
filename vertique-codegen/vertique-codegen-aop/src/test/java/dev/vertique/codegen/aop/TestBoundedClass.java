// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.aop.Aspect;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local aspect trigger carrying a <em>bounded</em> {@code Class} member
 * ({@code Class<? extends Number> value()}), used to exercise the
 * {@link dev.vertique.codegen.meta.AnnotationLiteralEmitter} bounded-{@code Class} attribute path.
 *
 * <p>A bounded-{@code Class} member is a legal aspect-trigger attribute. The generated
 * {@code <Ann>$AopLiteral} accessor must declare the member's <em>declared</em> return type
 * ({@code Class<? extends Number>}) so it correctly overrides the annotation interface method — a
 * normalized {@code Class<?>} accessor is not covariant with {@code Class<? extends Number>} and would
 * not override, so the generated literal source would not compile. Like the other {@code Test*}
 * triggers it is {@link Aspect}-meta-annotated so the processor treats methods carrying it as
 * aspect-intercepted.
 */
@Aspect(ordering = 1000)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestBoundedClass {

    /**
     * Returns the bounded {@code Class} value for this aspect occurrence.
     *
     * @return the selected type, bounded to {@link Number} subtypes; defaults to {@link Integer}
     */
    Class<? extends Number> value() default Integer.class;
}
