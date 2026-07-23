// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.aop.Aspect;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Equal-ordering aspect trigger (paired with {@link TestAlpha}) used to assert the deterministic
 * annotation-FQN tie-break in the generated around-chain. {@code TestBeta} sorts after
 * {@code TestAlpha} by fully-qualified name, so it must appear later in the chain.
 */
@Aspect(ordering = 1500)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestBeta {

    /**
     * Returns the around-chain ordering for this aspect (higher is outermost).
     *
     * @return the ordering weight; defaults to {@code 1500}
     */
    int ordering() default 1500;
}
