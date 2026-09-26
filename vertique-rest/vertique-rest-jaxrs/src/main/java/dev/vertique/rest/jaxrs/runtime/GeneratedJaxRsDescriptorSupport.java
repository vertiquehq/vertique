// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.util.AnnotationResolver;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless runtime helper bag exposed to generated JAX-RS resource descriptor and bean-param
 * model implementations.
 *
 * <p>Generated companions created by the CG-010 pipeline reference user-author types via
 * string FQN constants (never {@code .class} literals) to stay compatible with inaccessible
 * or private nested types. This class provides the resolution layer that converts those FQN
 * strings to the corresponding {@link Class} or {@link Method} objects at the first call from
 * each descriptor instance.
 *
 * <p>Primitive type names ({@code "int"}, {@code "long"}, {@code "boolean"}, etc.) are
 * recognised and mapped to the matching {@link Class} without invoking {@link Class#forName},
 * which does not accept primitive type names. Array types arrive in Java <em>source</em> form
 * ({@code "java.lang.String[]"}, {@code "byte[]"}) — the form the emitter produces — and are
 * resolved via {@link ArrayFqns}, since {@code Class.forName} accepts only the JVM binary array
 * form.
 *
 * <p>Caching policy: results are <strong>not</strong> cached at the support level. Each
 * generated descriptor instance is free to cache its own resolutions in its own fields after
 * receiving them from this class. Keeping the support stateless and idempotent simplifies
 * testing and allows the same support instance to be shared across multiple descriptors without
 * locking.
 *
 * <p>Annotation resolution delegates to {@link AnnotationResolver}, which walks the full
 * superclass chain and all transitively reachable interfaces (BFS order). This closes the
 * interface-walk gap for resources whose JAX-RS annotations live on a super-interface rather
 * than on the concrete class.
 *
 * <p>Instantiate directly: {@code new GeneratedJaxRsDescriptorSupport()}.
 */
public final class GeneratedJaxRsDescriptorSupport {

    /**
     * Creates a new support instance. Intended to be called by generated descriptor or
     * bean-param model classes on their first {@code describe()} / {@code fields()} call.
     */
    public GeneratedJaxRsDescriptorSupport() {}

    // --- Type resolution ---

    /**
     * Resolves a single class by its fully-qualified name using the given class loader.
     *
     * <p>Accepts three forms: a binary reference name ({@code com.example.Outer$Inner}), a Java
     * primitive name ({@code "int"}), and — new — an array type in Java <em>source</em> form with
     * one or more trailing {@code []} pairs ({@code "java.lang.String[]"}, {@code "byte[]"},
     * {@code "com.example.Outer$Inner[][]"}). Array FQNs are resolved by counting and stripping the
     * {@code []} pairs and applying {@link java.lang.reflect.Array#newInstance(Class, int...)} to
     * the resolved base type — {@code Class.forName} cannot load the source array form. Reference
     * types are resolved with class initialization enabled, unchanged from before.
     *
     * @param fqn the fully-qualified class name, primitive type name, or source-form array type
     *            name; must not be {@code null}
     * @param cl  the class loader to use for reference types; must not be {@code null}
     * @return the resolved {@link Class}; never {@code null}
     * @throws ClassNotFoundException if the class (or an array's component type) is not found on
     *                                {@code cl}'s classpath
     */
    public Class<?> resolveClass(String fqn, ClassLoader cl) throws ClassNotFoundException {
        return ArrayFqns.resolve(fqn, cl, true);
    }

    /**
     * Resolves an array of classes by their fully-qualified names using the given class loader.
     *
     * <p>Primitive type names are handled without invoking {@link Class#forName}; see
     * {@link #resolveClass(String, ClassLoader)}.
     *
     * @param fqns the fully-qualified class names or primitive type names; must not be
     *             {@code null}
     * @param cl   the class loader to use for non-primitive types; must not be {@code null}
     * @return an array of resolved classes in the same order as {@code fqns}; never {@code null}
     * @throws ClassNotFoundException if any non-primitive entry in {@code fqns} is not found
     */
    public Class<?>[] resolveClasses(String[] fqns, ClassLoader cl) throws ClassNotFoundException {
        Class<?>[] result = new Class<?>[fqns.length];
        for (int i = 0; i < fqns.length; i++) {
            result[i] = resolveClass(fqns[i], cl);
        }
        return result;
    }

    /**
     * Resolves an array of FQNs to a list of {@link Canonicalizer} subclasses using the given
     * class loader.
     *
     * @param fqns the fully-qualified names of canonicalizer implementation classes; must not be
     *             {@code null}
     * @param cl   the class loader to use; must not be {@code null}
     * @return an ordered list of resolved canonicalizer classes; never {@code null}
     * @throws ClassNotFoundException if any class in {@code fqns} is not found
     * @throws ClassCastException     if any resolved class does not implement {@link Canonicalizer}
     */
    @SuppressWarnings("unchecked")
    public List<Class<? extends Canonicalizer>> resolveCanonicalizers(String[] fqns, ClassLoader cl)
            throws ClassNotFoundException {
        List<Class<? extends Canonicalizer>> result = new ArrayList<>(fqns.length);
        for (String fqn : fqns) {
            Class<?> resolved = Class.forName(fqn, true, cl);
            result.add((Class<? extends Canonicalizer>) resolved.asSubclass(Canonicalizer.class));
        }
        return result;
    }

    /**
     * Resolves an array of FQNs to a list of {@link Sanitizer} subclasses using the given
     * class loader.
     *
     * @param fqns the fully-qualified names of sanitizer implementation classes; must not be
     *             {@code null}
     * @param cl   the class loader to use; must not be {@code null}
     * @return an ordered list of resolved sanitizer classes; never {@code null}
     * @throws ClassNotFoundException if any class in {@code fqns} is not found
     * @throws ClassCastException     if any resolved class does not implement {@link Sanitizer}
     */
    @SuppressWarnings("unchecked")
    public List<Class<? extends Sanitizer>> resolveSanitizers(String[] fqns, ClassLoader cl)
            throws ClassNotFoundException {
        List<Class<? extends Sanitizer>> result = new ArrayList<>(fqns.length);
        for (String fqn : fqns) {
            Class<?> resolved = Class.forName(fqn, true, cl);
            result.add((Class<? extends Sanitizer>) resolved.asSubclass(Sanitizer.class));
        }
        return result;
    }

    /**
     * Resolves a resource method by name and erased parameter type FQNs.
     *
     * <p>Parameter types are loaded from the resource type's own class loader so that the
     * resolution works correctly for class loaders that isolate the resource from the support
     * class loader. Primitive type names are handled without invoking {@link Class#forName};
     * see {@link #resolveClass(String, ClassLoader)}.
     *
     * <p>The method is made accessible, exactly as {@code ResourceScanner} does for the methods it
     * discovers, because a route without a generated execution plan dispatches through
     * {@link Method#invoke}. A public method can still be inaccessible from the invoker: an
     * interface {@code default} method declared by a package-private interface has no public
     * bridge in the implementing class, so {@link Class#getMethod} returns the interface's own
     * {@code Method}.
     *
     * @param resourceType  the class that declares or inherits the method; must not be
     *                      {@code null}
     * @param name          the method name; must not be {@code null}
     * @param paramTypeFqns the FQNs of the erased parameter types in declaration order; may be
     *                      empty
     * @return the resolved method; never {@code null}
     * @throws ClassNotFoundException if any parameter type FQN cannot be resolved
     * @throws NoSuchMethodException  if no method with the given name and parameter types exists
     *                                on {@code resourceType}
     */
    public Method resolveMethod(Class<?> resourceType, String name, String... paramTypeFqns)
            throws ClassNotFoundException, NoSuchMethodException {
        ClassLoader cl = resourceType.getClassLoader();
        Class<?>[] paramTypes = resolveClasses(paramTypeFqns, cl);
        Method method = resourceType.getMethod(name, paramTypes);
        method.setAccessible(true);
        return method;
    }

    // --- Effective annotation resolution ---

    /**
     * Returns the merged annotation list for a class, walking the superclass chain and all
     * transitively reachable interfaces in BFS order (via {@link AnnotationResolver}).
     *
     * <p>This is the class-level analog of {@link #effectiveMethodAnnotations(Method)} and
     * ensures that interface-declared class-level annotations (e.g. {@code @Path}, security
     * annotations, {@code @Consumes}) are visible to the descriptor even when the concrete
     * class carries no direct annotations.
     *
     * @param clazz the class to inspect; must not be {@code null}
     * @return an immutable list of all resolved annotations; never {@code null}
     */
    public List<Annotation> effectiveClassAnnotations(Class<?> clazz) {
        return AnnotationResolver.resolveClassAnnotations(clazz);
    }

    /**
     * Returns the merged annotation list for a method, walking the superclass chain and all
     * transitively reachable interfaces in BFS order (via {@link AnnotationResolver}).
     *
     * <p>Annotations declared only on the interface method (e.g. {@code @Operation},
     * {@code @ValidateWith}) are included in the result even when the concrete override has
     * none.
     *
     * @param method the method to inspect; must not be {@code null}
     * @return an immutable list of all resolved annotations; never {@code null}
     */
    public List<Annotation> effectiveMethodAnnotations(Method method) {
        return AnnotationResolver.resolveMethodAnnotations(method);
    }
}
