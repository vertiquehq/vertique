// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local, runtime-retained <em>parameter</em> marker annotation that is <strong>not</strong> an
 * {@link dev.vertique.aop.Aspect} trigger, used by {@code vertique-codegen-aop} slice-2.1
 * parameter-level {@code findAnnotation} compile-tests.
 *
 * <p>It is {@link ElementType#PARAMETER PARAMETER}-targeted and carries a single
 * {@code String value()} of a supported attribute kind, so the generated proxy's nested
 * {@code ParameterMetadataImpl} must materialize a {@code TestParamMarker$AopLiteral} and return it
 * from {@code findAnnotation}/{@code hasAnnotation} — proving the reflection-free parameter-level
 * lookup is backed by a generated literal rather than the {@code Optional.empty()}/{@code false}
 * stub the v1 emitter still ships (the analogue of {@link TestMarker} for the method-level surface).
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
     * @return the marker value baked into the generated {@code TestParamMarker$AopLiteral} as a
     *     {@code String} constant
     */
    String value();
}
