// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import dev.vertique.core.util.AnnotationResolver;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Static helper that resolves the full <em>merged</em> effective annotation set for a single
 * resource-method parameter — the same set the reflective scan path
 * ({@code ResourceScanner} via {@link AnnotationResolver#resolveParameterAnnotations}) produces,
 * including annotations declared on superclass and interface method overrides.
 *
 * <p>Used by codegen-generated {@code ParameterMetadata} implementations (ADR-0146, parity-first
 * policy) as the lazy reflective fallback for parameters that carry at least one annotation whose
 * member shape the compile-time {@code AnnotationLiteralEmitter} cannot render into a literal (most
 * commonly Swagger's {@code @Parameter}, whose {@code schema} member defaults to a nested
 * {@code @Schema}). The generated metadata materializes the annotations it <em>can</em> as literals
 * and delegates to this helper for the rest, so an annotation-sensitive
 * {@code jakarta.ws.rs.ext.ParamConverterProvider} sees byte-for-byte the same annotation array on a
 * codegen route as on a reflectively-scanned route.
 *
 * <p>Resolution is by fully-qualified name (declaring class, method name, parameter types) so the
 * generated code carries no direct {@code .class} references to potentially-inaccessible types,
 * matching the string-FQN discipline the descriptor emitter already uses. It is invoked lazily (only
 * when {@code annotationsLazy()}/{@code findAnnotation} is actually consulted, which itself only
 * happens when a JAX-RS {@code ParamConverterProvider} is registered), so the reflection cost is off
 * the common request path.
 */
public final class GeneratedJaxRsReflectiveAnnotations {

    private GeneratedJaxRsReflectiveAnnotations() {}

    /**
     * Resolves the merged effective annotation array for the given parameter, delegating to
     * {@link AnnotationResolver#resolveParameterAnnotations(Method, int)} after resolving the
     * declaring {@link Method} by name and parameter-type FQNs.
     *
     * <p>The declaring class and every parameter type are loaded via {@code loaderSource}'s
     * classloader — the generated metadata class, guaranteed co-located with the resource in the same
     * package/module/classloader — so the lookup uses a loader that can see the resource regardless of
     * the thread context classloader (which is not guaranteed to be the application loader in every
     * deployment or test harness). A resolution failure (class or method not found on the classpath)
     * is wrapped in an {@link IllegalStateException}: a generated fallback that cannot resolve its own
     * declaring method is a build/classpath defect, not a recoverable runtime condition.
     *
     * @param loaderSource      a class co-located with the resource (the generated metadata class),
     *                          whose classloader resolves the declaring class and parameter types;
     *                          must not be {@code null}
     * @param declaringClassFqn the fully-qualified name of the class declaring the resource method
     *                          (the concrete resource class); must not be {@code null}
     * @param methodName        the resource method's simple name; must not be {@code null}
     * @param parameterTypeFqns the fully-qualified erased names of the method's parameter types, in
     *                          declaration order, used to disambiguate overloads; must not be
     *                          {@code null}
     * @param parameterIndex    the zero-based index of the parameter whose annotations to resolve
     * @return the merged effective annotation array (concrete + superclass + interface); never
     *         {@code null}
     * @throws IllegalStateException if the declaring class, a parameter type, or the method cannot be
     *                               resolved
     */
    public static Annotation[] mergedParameterAnnotations(
            Class<?> loaderSource,
            String declaringClassFqn,
            String methodName,
            String[] parameterTypeFqns,
            int parameterIndex) {
        ClassLoader cl = loaderSource.getClassLoader();
        if (cl == null) {
            cl = Thread.currentThread().getContextClassLoader();
        }
        try {
            Class<?> declaringClass = loadClass(declaringClassFqn, cl);
            Class<?>[] parameterTypes = new Class<?>[parameterTypeFqns.length];
            for (int i = 0; i < parameterTypeFqns.length; i++) {
                parameterTypes[i] = loadClass(parameterTypeFqns[i], cl);
            }
            Method method = declaringClass.getMethod(methodName, parameterTypes);
            return AnnotationResolver.resolveParameterAnnotations(method, parameterIndex);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Failed to resolve reflective parameter-annotation fallback for %s.%s parameter %d"
                            .formatted(declaringClassFqn, methodName, parameterIndex),
                    e);
        }
    }

    /**
     * Loads a class by FQN, resolving Java primitive names to their {@code Class} constants (a
     * primitive parameter type's erased FQN is e.g. {@code "int"}, which {@code Class.forName} cannot
     * load) and array-typed FQNs to the corresponding array {@code Class}.
     *
     * <p>Array parameter types arrive in Java <em>source</em> form — the format
     * {@code TypeMirrorFqn.erasedFqn} emits, e.g. {@code "java.lang.String[]"}, {@code "int[]"}, or
     * {@code "java.lang.String[][]"} — not the JVM binary form ({@code "[Ljava.lang.String;"}) that
     * {@code Class.forName} would need. Rather than string-mangle to the binary form, each trailing
     * {@code []} pair is stripped to count dimensions, the base component type is resolved (primitive
     * or reference), and {@link java.lang.reflect.Array#newInstance(Class, int...)} produces the array
     * {@code Class} uniformly for primitive arrays, object arrays, and multi-dimensional arrays. This
     * matters because a resource method with any array-typed parameter (even a sibling of the
     * fallback parameter) is looked up by its full parameter-type list, so an array FQN that failed to
     * load would break the fallback for every parameter of the method.
     *
     * @param fqn the fully-qualified (or primitive, or array) type name in source form
     * @param cl  the classloader to use for reference types
     * @return the resolved {@link Class}
     * @throws ClassNotFoundException if a reference (component) type cannot be found
     */
    private static Class<?> loadClass(String fqn, ClassLoader cl) throws ClassNotFoundException {
        // Strip trailing "[]" pairs to count array dimensions, leaving the base component type.
        int dimensions = 0;
        String baseFqn = fqn;
        while (baseFqn.endsWith("[]")) {
            dimensions++;
            baseFqn = baseFqn.substring(0, baseFqn.length() - 2);
        }
        Class<?> baseClass = loadBaseClass(baseFqn, cl);
        if (dimensions == 0) {
            return baseClass;
        }
        // Array.newInstance handles primitive, object, and multi-dimensional arrays uniformly and
        // returns the array Class via getClass() — no JVM binary-name string-mangling required.
        return java.lang.reflect.Array.newInstance(baseClass, new int[dimensions])
                .getClass();
    }

    /**
     * Resolves a non-array base type FQN to its {@link Class}, mapping Java primitive names to their
     * {@code Class} constants and delegating reference types to {@code Class.forName}.
     *
     * @param fqn the non-array fully-qualified (or primitive) type name
     * @param cl  the classloader to use for reference types
     * @return the resolved base {@link Class}
     * @throws ClassNotFoundException if a reference type cannot be found
     */
    private static Class<?> loadBaseClass(String fqn, ClassLoader cl) throws ClassNotFoundException {
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
            default -> Class.forName(fqn, false, cl);
        };
    }
}
