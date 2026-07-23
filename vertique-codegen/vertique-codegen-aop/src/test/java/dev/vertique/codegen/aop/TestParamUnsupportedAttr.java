// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local, runtime-retained <em>parameter</em> annotation carrying an attribute of an
 * <strong>unsupported</strong> kind (a {@code float} member), used by {@code vertique-codegen-aop}
 * slice-2.1 parameter-level {@code findAnnotation} compile-tests.
 *
 * <p>{@code float} is one of the kinds {@link dev.vertique.codegen.meta.AnnotationLiteralEmitter}
 * does not support (alongside {@code char} and {@code double}); when the proxy must materialize this
 * parameter annotation into a literal for the reflection-free parameter-level {@code findAnnotation}
 * surface, the unsupported kind MUST be a compile <em>error</em> (FR-013-13 / FR-013-09c) — never a
 * silent reflective fallback. This fixture is the parameter-level analogue of
 * {@link TestUnsupportedAttr}; it rides on a parameter of a method that also carries an aspect
 * trigger so the proxy is generated and the parameter-metadata materialization is reached.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestParamUnsupportedAttr {

    /**
     * Returns the unsupported-kind attribute value.
     *
     * @return a {@code float} — an attribute kind {@code AnnotationLiteralEmitter} cannot render,
     *     forcing a compile error when the proxy must materialize this parameter annotation as a
     *     literal
     */
    float weight();
}
