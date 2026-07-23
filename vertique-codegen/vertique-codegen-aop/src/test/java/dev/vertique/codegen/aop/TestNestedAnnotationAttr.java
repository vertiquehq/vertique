// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local, runtime-retained method annotation carrying a <strong>nested-annotation</strong> member
 * ({@link TestNestedMember}), used by the {@code vertique-codegen-aop} P2-W3 compile-test.
 *
 * <p>A nested-annotation member is a legal annotation member type that
 * {@link dev.vertique.codegen.meta.AnnotationLiteralEmitter} cannot render into a literal in v1.
 * Without explicit rejection the emitter's {@code valueLiteral} matches none of the
 * String/Class/enum/primitive/array branches and falls through to {@code String.valueOf(raw)},
 * emitting the {@code AnnotationMirror}'s {@code toString()} as broken generated code. The v1 contract
 * is a clean compile <em>error</em> (FR-013-13 / FR-013-09c), never broken code.
 *
 * <p>This fixture is not an {@link dev.vertique.aop.Aspect} trigger; it rides on a method that also
 * carries an aspect trigger so the proxy is generated and the method-annotation materialization is
 * reached.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestNestedAnnotationAttr {

    /**
     * Returns the nested-annotation attribute value.
     *
     * @return a {@link TestNestedMember} — a nested-annotation member kind {@code AnnotationLiteralEmitter}
     *     cannot render, forcing a clean compile error when the proxy must materialize this annotation
     */
    TestNestedMember nested() default @TestNestedMember;
}
