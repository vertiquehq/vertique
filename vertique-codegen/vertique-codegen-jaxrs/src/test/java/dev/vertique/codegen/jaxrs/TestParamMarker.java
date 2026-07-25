// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local, runtime-retained <em>parameter</em> marker annotation used by
 * {@code vertique-codegen-jaxrs}'s literal-backed parameter-annotation compile-tests
 * ({@code ExecutionPlanEmitterTest}, {@code JaxRsDescriptorEmitterTest}).
 *
 * <p>It is {@link ElementType#PARAMETER PARAMETER}-targeted and carries a single
 * {@code String value()} of a supported attribute kind, so a generated JAX-RS execution plan or
 * descriptor must materialize a {@code TestParamMarker$JaxRsLiteral} and expose it via the parameter's
 * {@code ParameterMetadata.findAnnotation}/{@code hasAnnotation} — proving the literal-backed
 * lookup is genuinely reflection-free rather than a {@code null}/reflective-read placeholder
 * (mirrors {@code vertique-codegen-aop}'s {@code TestParamMarker} fixture for the same purpose).
 *
 * <p><strong>Retention is {@link RetentionPolicy#RUNTIME RUNTIME}</strong> deliberately: only
 * runtime-retained parameter annotations are materialized into literals; a {@code SOURCE}/
 * {@code CLASS}-retained annotation is invisible at runtime and is not part of the reflection-free
 * metadata surface.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestParamMarker {

    /**
     * Returns the marker's string value.
     *
     * @return the marker value baked into the generated {@code TestParamMarker$JaxRsLiteral} as a
     *     {@code String} constant
     */
    String value();
}
