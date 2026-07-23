// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.emit;

import dev.vertique.codegen.CodegenContext;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Shared FQN-resolution helpers used by the four CG-010 emitters. Centralised here so the
 * three places that emit string FQN constants for runtime {@code Class.forName(...)} stay in
 * sync — getting nested-type form ({@code Outer$Inner}) right matters because runtime
 * registries and {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport#resolveClass}
 * pass the strings straight through to {@code Class.forName}.
 */
final class TypeMirrorFqn {

    private TypeMirrorFqn() {}

    /**
     * Returns the erased <b>binary</b> name of {@code type} — i.e. the form accepted by
     * {@link Class#forName(String, boolean, ClassLoader)}. For nested types this is
     * {@code Outer$Inner}, NOT {@code Outer.Inner} (the latter is what
     * {@link TypeElement#getQualifiedName()} returns and is only valid in Java source).
     *
     * @param type the type mirror; {@code null} returns {@code "java.lang.Object"}
     * @param ctx  the codegen context (provides {@code Types} + {@code Elements})
     * @return the binary FQN, or a primitive/void/array string for non-declared types
     */
    static String erasedFqn(TypeMirror type, CodegenContext ctx) {
        if (type == null) {
            return "java.lang.Object";
        }
        TypeMirror erased = ctx.types().erasure(type);
        var element = ctx.types().asElement(erased);
        if (element instanceof TypeElement te) {
            return ctx.elements().getBinaryName(te).toString();
        }
        // Primitive, void, or array — use toString
        return erased.toString();
    }

    /**
     * Returns the binary name of a {@link TypeElement} — i.e. {@code Outer$Inner} form, suitable
     * for {@link Class#forName(String, boolean, ClassLoader)}. Use this when emitting a string
     * constant that names a class type by element rather than by mirror.
     */
    static String binaryName(TypeElement type, CodegenContext ctx) {
        return ctx.elements().getBinaryName(type).toString();
    }
}
