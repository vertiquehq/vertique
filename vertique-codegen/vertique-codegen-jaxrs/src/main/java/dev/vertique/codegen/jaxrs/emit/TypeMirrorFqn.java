// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.emit;

import dev.vertique.codegen.CodegenContext;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeMirror;

/**
 * Shared FQN-resolution helpers used by the four CG-010 emitters. Centralised here so the
 * three places that emit string FQN constants for runtime {@code Class.forName(...)} stay in
 * sync — getting nested-type form ({@code Outer$Inner}) right matters because runtime
 * registries and {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport#resolveClass}
 * pass the strings straight through to the runtime resolver.
 *
 * <p>Two complementary renderings exist, and the choice depends on where the emitted string
 * lands:
 * <ul>
 *   <li>{@link #erasedFqn(TypeMirror, CodegenContext)} — for strings <em>resolved at runtime</em>
 *       (descriptor {@code ParamMeta} types, reflective method lookup, bean-param field types).
 *       Nested base types use the binary {@code Outer$Inner} form.</li>
 *   <li>{@link #erasedSourceFqn(TypeMirror, CodegenContext)} — for strings <em>interpolated into
 *       generated Java source</em> (e.g. a Jackson {@code TypeReference<...>} literal), where the
 *       dotted {@code Outer.Inner} form is the only one that compiles.</li>
 * </ul>
 */
final class TypeMirrorFqn {

    private TypeMirrorFqn() {}

    /**
     * Returns the erased <b>binary</b> name of {@code type} — i.e. the form accepted by the
     * runtime FQN resolvers backing {@link Class#forName(String, boolean, ClassLoader)}. For
     * nested types this is {@code Outer$Inner}, NOT {@code Outer.Inner} (the latter is what
     * {@link TypeElement#getQualifiedName()} returns and is only valid in Java source).
     *
     * <p>Array types are rendered as the <b>binary base name</b> followed by one Java
     * <em>source</em>-form {@code []} pair per dimension — {@code java.lang.String[]},
     * {@code byte[]}, {@code com.example.Outer$Inner[][]}. The mixed form is deliberate: the JVM
     * binary array form ({@code "[Ljava.lang.String;"}) is never emitted, because
     * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport#resolveClass} counts
     * and strips the trailing {@code []} pairs, resolves the base name, and rebuilds the array
     * class via {@link java.lang.reflect.Array#newInstance(Class, int...)}. Only the base name
     * needs to be binary; the brackets are the wire format that resolver expects.
     *
     * <p>Primitives and {@code void} render as their Java keyword ({@code "int"}, {@code "void"}),
     * which the runtime resolvers map without calling {@code Class.forName}.
     *
     * @param type the type mirror; {@code null} returns {@code "java.lang.Object"}
     * @param ctx  the codegen context (provides {@code Types} + {@code Elements})
     * @return the binary FQN, the binary base name plus {@code []} suffixes for arrays, or a
     *         primitive/void keyword
     */
    static String erasedFqn(TypeMirror type, CodegenContext ctx) {
        if (type == null) {
            return "java.lang.Object";
        }
        TypeMirror erased = ctx.types().erasure(type);
        // Array: recurse to the component type so the base name goes through the binary-name
        // branch below (a nested element type must emit Outer$Inner, not the dotted source form
        // TypeMirror.toString() would yield), then re-append one source-form [] per dimension.
        if (erased instanceof ArrayType arrayType) {
            return erasedFqn(arrayType.getComponentType(), ctx) + "[]";
        }
        var element = ctx.types().asElement(erased);
        if (element instanceof TypeElement te) {
            return ctx.elements().getBinaryName(te).toString();
        }
        // Primitive, void, or any other non-declared mirror — use toString
        return erased.toString();
    }

    /**
     * Returns the erased <b>source</b> name of {@code type} — the form that compiles when
     * interpolated into generated Java source. Nested types keep their dotted
     * {@code Outer.Inner} form and arrays keep their trailing {@code []} pairs
     * ({@code com.example.Outer.Inner[]}).
     *
     * <p>Use this — never {@link #erasedFqn(TypeMirror, CodegenContext)} — for a string that the
     * generated code embeds as a type reference rather than resolving at runtime; a {@code $} in
     * such a position is a compile error in the generated file.
     *
     * @param type the type mirror; {@code null} returns {@code "java.lang.Object"}
     * @param ctx  the codegen context (provides {@code Types})
     * @return the erased source-form type name
     */
    static String erasedSourceFqn(TypeMirror type, CodegenContext ctx) {
        if (type == null) {
            return "java.lang.Object";
        }
        return ctx.types().erasure(type).toString();
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
