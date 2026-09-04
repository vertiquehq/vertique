// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

import dev.vertique.aop.Aspect;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Activates resilience for a method and optionally selects a named policy tier. */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Aspect(ordering = 50)
public @interface Resilient {

    /**
     * Returns the named policy tier, or an empty string when no named tier is selected.
     *
     * @return the named policy tier
     */
    String policy() default "";
}
