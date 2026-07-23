// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.aop.Aspect;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Second test-local aspect trigger with a distinct default {@code ordering()} from {@link TestTimed},
 * used to assert that the generated around-chain is ordered outermost-by-higher-ordering.
 */
@Aspect(ordering = 2000)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestLogged {

    /**
     * Returns the around-chain ordering for this aspect (higher is outermost).
     *
     * @return the ordering weight; defaults to {@code 2000}
     */
    int ordering() default 2000;
}
