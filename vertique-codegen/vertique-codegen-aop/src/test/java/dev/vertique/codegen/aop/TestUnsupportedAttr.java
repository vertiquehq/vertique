// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local, runtime-retained method annotation carrying an attribute of an <strong>unsupported</strong>
 * kind (a {@code float} member), used by {@code vertique-codegen-aop} slice-2.1 compile-tests.
 *
 * <p>{@code float} is one of the kinds {@link dev.vertique.codegen.meta.AnnotationLiteralEmitter}
 * does not support (alongside {@code char} and {@code double}); when the proxy must materialize this
 * annotation into a literal for the reflection-free {@code findAnnotation} surface, the unsupported
 * kind MUST be a compile <em>error</em> (FR-013-13 / FR-013-09c) — never a silent reflective
 * fallback. This fixture is not an {@link dev.vertique.aop.Aspect} trigger: it rides on a method that
 * also carries an aspect trigger so the proxy is generated and the metadata materialization is
 * reached.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestUnsupportedAttr {

    /**
     * Returns the unsupported-kind attribute value.
     *
     * @return a {@code float} — an attribute kind {@code AnnotationLiteralEmitter} cannot render,
     *     forcing a compile error when the proxy must materialize this annotation as a literal
     */
    float weight();
}
