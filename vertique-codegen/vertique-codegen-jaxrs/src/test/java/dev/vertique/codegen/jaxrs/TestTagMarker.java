// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-local, runtime-retained <em>parameter</em> marker annotation whose single {@code String value()}
 * is a fully literalizable member, used by {@code vertique-codegen-jaxrs}'s parameter-annotation
 * parity compile-tests to exercise the same-type / differing-member-value dedup case.
 *
 * <p>Unlike {@link TestParamMarker} — which proves a single materializable annotation is baked into a
 * literal — this marker is declared with <em>different</em> {@code value()}s on a concrete parameter and
 * its interface-override counterpart (e.g. {@code @TestTagMarker("a")} vs {@code @TestTagMarker("b")}).
 * The runtime reflective merge ({@code AnnotationResolver.resolveParameterAnnotations}) dedups by
 * {@code LinkedHashSet<Annotation>} equality (type + member values) and therefore keeps <em>both</em>
 * instances, whereas the all-literalizable codegen fast path dedups by annotation type FQN and would
 * collapse them to one — a parity gap the codegen materializer closes by forcing such a parameter onto
 * the reflective fallback.
 *
 * <p><strong>Retention is {@link RetentionPolicy#RUNTIME RUNTIME}</strong> deliberately: only
 * runtime-retained parameter annotations participate in the reflection-free metadata surface.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestTagMarker {

    /**
     * Returns the tag value distinguishing same-type instances across concrete and override
     * declarations.
     *
     * @return the tag value
     */
    String value();
}
