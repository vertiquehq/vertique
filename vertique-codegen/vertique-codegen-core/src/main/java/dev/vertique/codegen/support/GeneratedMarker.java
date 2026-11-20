// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.support;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;

/**
 * The class-file marker every Vertique code generator stamps on what it writes.
 *
 * <p>Emitters add {@link #SPEC} to each top-level and nested type they build, and prefix each
 * method of an inline anonymous class with {@code @$T} bound to {@link #ANNOTATION}. JaCoCo does not
 * carry a class-level marker over to nested or anonymous classes, so each needs its own.
 * The marker sits next to {@code javax.annotation.processing.Generated}, which has
 * {@code SOURCE} retention and never reaches the class file.
 */
public final class GeneratedMarker {

    /** {@code dev.vertique.core.codegen.Generated}, referenced by name to keep the runtime off the processor path. */
    public static final ClassName ANNOTATION = ClassName.get("dev.vertique.core.codegen", "Generated");

    /** The member-less marker annotation, ready to add to a type, method, or constructor. */
    public static final AnnotationSpec SPEC = AnnotationSpec.builder(ANNOTATION).build();

    private GeneratedMarker() {}
}
