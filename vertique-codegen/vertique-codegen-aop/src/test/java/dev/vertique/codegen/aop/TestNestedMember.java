// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Test-local runtime-retained nested annotation used as a <em>member type</em> of
 * {@link TestNestedAnnotationAttr}, exercising the {@code vertique-codegen-aop} P2-W3 compile-test.
 *
 * <p>A nested-annotation member is a legal annotation member type, but
 * {@link dev.vertique.codegen.meta.AnnotationLiteralEmitter} cannot materialize it into a literal in
 * v1 — the v1 contract is a clean compile-time <em>rejection</em>, not full nested-annotation
 * support. This nested annotation is not itself an aspect trigger; it only appears as a member value.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface TestNestedMember {

    /**
     * Returns the nested annotation's string value.
     *
     * @return an arbitrary string value; defaults to the empty string
     */
    String value() default "";
}
