// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local, runtime-retained <em>marker</em> method annotation that is <strong>not</strong> an
 * {@link dev.vertique.aop.Aspect} trigger, used by {@code vertique-codegen-aop} slice-2.1
 * compile-tests.
 *
 * <p>It carries a single {@code String value()} of a {@link #SUPPORTED supported} attribute kind so
 * the generated proxy's nested {@code MethodMetadataImpl} must materialize a {@code Marker$AopLiteral}
 * and return it from {@code findAnnotation}/{@code hasAnnotation} — proving the reflection-free
 * lookup works for a method annotation the proxy did not already materialize for the around-chain
 * (unlike the aspect triggers, whose literals already exist). Its presence alongside an aspect
 * trigger ({@code @TestTimed}) on the same method is what forces the {@code MethodMetadataImpl} to
 * cover non-aspect runtime-retained annotations.
 *
 * <p><strong>Retention is {@link RetentionPolicy#RUNTIME RUNTIME}</strong> deliberately: only
 * runtime-retained method annotations are materialized into literals (FR-013-13). A
 * {@code SOURCE}/{@code CLASS}-retained annotation is invisible at runtime and is not part of the
 * reflection-free metadata surface.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestMarker {

    /** A marker for the supported-attribute-kind note in this fixture's javadoc. */
    String SUPPORTED = "String";

    /**
     * Returns the marker's string value.
     *
     * @return the marker value baked into the generated {@code Marker$AopLiteral} as a {@code String}
     *     constant
     */
    String value();
}
