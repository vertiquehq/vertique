// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import java.lang.reflect.Array;

/**
 * Resolves type names emitted by the CG-010 codegen pipeline — including array types in Java
 * <em>source</em> form — to their {@link Class} objects.
 *
 * <p>{@code TypeMirrorFqn.erasedFqn} emits an array parameter's erased type as Java source form
 * ({@code "java.lang.String[]"}, {@code "byte[]"}, {@code "java.lang.String[][]"}), never the JVM
 * binary form ({@code "[Ljava.lang.String;"}) that {@link Class#forName(String, boolean,
 * ClassLoader)} would need. Rather than string-mangle to the binary form, each trailing {@code []}
 * pair is stripped to count dimensions, the base component type is resolved (primitive name or
 * reference type), and {@link Array#newInstance(Class, int...)} produces the array {@code Class}
 * uniformly for primitive arrays, object arrays, and multi-dimensional arrays.
 *
 * <p>This class exists so that the two runtime consumers of codegen-emitted FQN strings —
 * {@link GeneratedJaxRsReflectiveAnnotations} (parameter-type lists for method lookup) and
 * {@link GeneratedJaxRsDescriptorSupport} (descriptor {@code ParamMeta} types) — share one proven
 * implementation. The consumers differ in only one respect, which the {@code initialize} flag
 * preserves: reflective annotation lookup must <em>not</em> trigger static initialization of a
 * parameter type it merely names, while descriptor support has always resolved with
 * initialization enabled.
 */
final class ArrayFqns {

    private ArrayFqns() {}

    /**
     * Resolves a possibly-array type name in Java source form to its {@link Class}.
     *
     * <p>Accepted forms: a binary reference name ({@code com.example.Outer$Inner}), a Java
     * primitive name ({@code "int"}), and either of those with one or more trailing {@code []}
     * pairs ({@code "java.lang.String[]"}, {@code "byte[]"}, {@code "com.example.Outer$Inner[][]"}).
     *
     * <p>{@code initialize} governs the resolution of the <em>base</em> component type, which is
     * always resolved first: an array FQN's base goes through {@code Class.forName(base, initialize,
     * cl)}, so requesting initialization for {@code "com.example.Foo[]"} does initialize
     * {@code Foo}. {@link Array#newInstance(Class, int...)} then produces the array {@code Class}
     * without triggering any initialization of its own.
     *
     * @param fqn        the type name: binary reference name, primitive name, or either with one or
     *                   more trailing {@code []} pairs; must not be {@code null}
     * @param cl         the class loader for reference base types; must not be {@code null}
     * @param initialize whether to initialize the resolved reference base type — {@code true} for
     *                   {@link GeneratedJaxRsDescriptorSupport}, {@code false} for
     *                   {@link GeneratedJaxRsReflectiveAnnotations}
     * @return the resolved {@link Class}; never {@code null}
     * @throws ClassNotFoundException if a reference (component) type cannot be found on {@code cl}
     */
    static Class<?> resolve(String fqn, ClassLoader cl, boolean initialize) throws ClassNotFoundException {
        // Strip trailing "[]" pairs to count array dimensions, leaving the base component type.
        int dimensions = 0;
        String baseFqn = fqn;
        while (baseFqn.endsWith("[]")) {
            dimensions++;
            baseFqn = baseFqn.substring(0, baseFqn.length() - 2);
        }
        Class<?> baseClass = resolveBase(baseFqn, cl, initialize);
        if (dimensions == 0) {
            return baseClass;
        }
        // Array.newInstance handles primitive, object, and multi-dimensional arrays uniformly and
        // returns the array Class via getClass() — no JVM binary-name string-mangling required.
        return Array.newInstance(baseClass, new int[dimensions]).getClass();
    }

    /**
     * Resolves a non-array base type FQN to its {@link Class}, mapping Java primitive names to
     * their {@code Class} constants (which {@link Class#forName(String, boolean, ClassLoader)}
     * cannot load) and delegating reference types to {@code Class.forName}.
     *
     * @param fqn        the non-array fully-qualified (or primitive) type name
     * @param cl         the class loader to use for reference types
     * @param initialize whether to initialize a resolved reference type
     * @return the resolved base {@link Class}
     * @throws ClassNotFoundException if a reference type cannot be found
     */
    private static Class<?> resolveBase(String fqn, ClassLoader cl, boolean initialize) throws ClassNotFoundException {
        return switch (fqn) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "char" -> char.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "void" -> void.class;
            default -> Class.forName(fqn, initialize, cl);
        };
    }
}
