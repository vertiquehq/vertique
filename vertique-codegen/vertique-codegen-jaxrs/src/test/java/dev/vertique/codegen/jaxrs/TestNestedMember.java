// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Nested annotation type used as an unsupported member of {@link TestUnsupportedMemberMarker}.
 *
 * <p>{@code AnnotationLiteralEmitter} cannot render a nested-annotation member into a compile-time
 * literal (its v1 attribute-kind boundary), so an annotation carrying a member of this type forces
 * the codegen emitters onto the reflective-fallback path (ADR-0146 parity-first policy) — mirroring
 * the real-world Swagger {@code @Parameter} case, whose {@code schema} member defaults to a nested
 * {@code @Schema}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
public @interface TestNestedMember {

    /**
     * Returns the nested member's string value.
     *
     * @return the value
     */
    String value() default "";
}
