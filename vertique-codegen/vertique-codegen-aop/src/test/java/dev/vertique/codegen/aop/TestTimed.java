// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.aop.Aspect;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local aspect trigger annotation used by {@code vertique-codegen-aop} compile-tests.
 *
 * <p>Stands in for the real {@code @Timed} (which arrives in slice 1.3) so these tests exercise the
 * processor without a forward dependency on the micrometer module. It is {@link Aspect}-meta-annotated
 * so the processor treats methods carrying it as aspect-intercepted.
 */
@Aspect(ordering = 1000)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestTimed {

    /**
     * Returns the around-chain ordering for this aspect (higher is outermost).
     *
     * @return the ordering weight; defaults to {@code 1000}
     */
    int ordering() default 1000;
}
