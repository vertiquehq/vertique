// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local, runtime-retained parameter marker annotation carrying a member of an unsupported
 * attribute kind — a nested {@link TestNestedMember} annotation — which {@code AnnotationLiteralEmitter}
 * cannot render into a compile-time literal.
 *
 * <p>Used by the codegen literal-backed-annotation tests to force the reflective-fallback path
 * (ADR-0146 parity-first policy): a parameter carrying this annotation must still compile, and the
 * annotation must remain resolvable via {@code findAnnotation}/{@code annotationsLazy()} on the
 * generated route (through the reflective fallback), byte-for-byte matching the reflective scan path
 * — never silently omitted, never a compile error. Mirrors the real-world Swagger {@code @Parameter}
 * shape (a {@code schema} member defaulting to a nested {@code @Schema}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface TestUnsupportedMemberMarker {

    /**
     * Returns a plain string discriminator (a supported member kind), so the annotation is
     * distinguishable at runtime.
     *
     * @return the marker discriminator
     */
    String value() default "";

    /**
     * Returns the nested-annotation member that makes this annotation unrenderable as a literal.
     *
     * @return the nested member (defaults to an empty {@link TestNestedMember})
     */
    TestNestedMember nested() default @TestNestedMember;
}
