// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local, runtime-retained <em>type and method</em> marker annotation used by
 * {@code vertique-codegen-jaxrs}'s descriptor annotation parity test
 * ({@code GeneratedDescriptorAnnotationParityTest}) to label each annotation placement with a
 * distinct {@code value()}.
 *
 * <p>It is top-level and {@code public} because the generated-side fixture is compiled from inline
 * sources in another package, which import it. The harness serves only freshly compiled types
 * child-first, so this type is loaded once by the test class loader and both engines' annotation
 * instances share the same {@code Class}; {@link java.lang.annotation.Annotation#equals} can
 * therefore hold across engines.
 *
 * <p>It is deliberately neither {@link java.lang.annotation.Inherited @Inherited} nor
 * {@link java.lang.annotation.Repeatable @Repeatable}: a superclass or interface placement reaches a
 * resource's annotation list only through the engines' hierarchy resolution, never through
 * {@link Class#getAnnotations()} inheritance, and every placement stays a plain instance rather
 * than a container annotation.
 *
 * <p><strong>Retention is {@link RetentionPolicy#RUNTIME RUNTIME}</strong> deliberately: only
 * runtime-retained annotations appear in {@code ResourceMethodMeta.methodAnnotations()} and
 * {@code classAnnotations()}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TestParityMarker {

    /**
     * Returns the placement label, distinct per placement so that hierarchy resolution never
     * deduplicates two placements into one entry.
     *
     * @return the placement label
     */
    String value();
}
