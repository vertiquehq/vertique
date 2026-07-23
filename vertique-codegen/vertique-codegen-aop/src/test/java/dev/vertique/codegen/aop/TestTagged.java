// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.aop.Aspect;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local aspect trigger carrying an <em>array</em>-typed member ({@code String[] tags()}), used
 * to exercise the {@link dev.vertique.codegen.meta.AnnotationLiteralEmitter} array-attribute path
 * and the {@link AopProxyEmitter} per-occurrence literal handling.
 *
 * <p>Stands in for the real {@code @Timed}, whose {@code extraTags()} member is a {@code String[]}.
 * Like the other {@code Test*} triggers in this package it is {@link Aspect}-meta-annotated so the
 * processor treats methods carrying it as aspect-intercepted.
 */
@Aspect(ordering = 1000)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestTagged {

    /**
     * Returns the tag values for this aspect occurrence.
     *
     * @return the array of tag values; defaults to an empty array
     */
    String[] tags() default {};
}
