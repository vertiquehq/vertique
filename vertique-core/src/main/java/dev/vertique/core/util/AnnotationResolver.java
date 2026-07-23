// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * General-purpose utility for annotation resolution over class hierarchies and meta-annotation
 * graphs.
 *
 * <p>Standard reflection ({@link Method#getAnnotations()}) only returns annotations declared
 * directly on a method or class. This utility provides two complementary resolution strategies:
 *
 * <ol>
 *   <li><b>Hierarchy resolution</b> — {@link #resolveMethodAnnotations} and
 *       {@link #resolveClassAnnotations} walk the full superclass chain and all transitively
 *       reachable interfaces so that annotations placed on abstract base classes or interface
 *       default methods are visible to interceptors and request processing pipelines.</li>
 *   <li><b>Meta-annotation resolution</b> — {@link #findMetaAnnotation} discovers annotations
 *       that are themselves annotated with the target type (composed annotations), supporting
 *       annotation aliasing patterns such as {@code @NormalizedInput} composed from
 *       {@code @Canonicalize(...)}.</li>
 * </ol>
 *
 * <p>Example — hierarchy resolution:
 * <pre>{@code
 * List<Annotation> annotations = AnnotationResolver.resolveMethodAnnotations(method);
 * annotations.stream()
 *     .filter(a -> a instanceof Transactional)
 *     .findFirst()
 *     .ifPresent(a -> ctx.withAttribute("transactional", true));
 * }</pre>
 *
 * <p>Example — meta-annotation lookup:
 * <pre>{@code
 * // @NormalizedInput is meta-annotated with @Canonicalize(TrimCanonicalizer.class)
 * Canonicalize canon = AnnotationResolver.findMetaAnnotation(field, Canonicalize.class);
 * }</pre>
 *
 * <p>Duplicate annotations (same type appearing on both the class and an interface) are
 * deduplicated in hierarchy traversal: the first occurrence (in class-before-interface traversal
 * order) wins.
 */
public final class AnnotationResolver {

    private AnnotationResolver() {}

    /**
     * Resolves all annotations present on a method by walking both the superclass chain and the
     * interface hierarchy of the method's declaring class.
     *
     * <p>Traversal order:
     * <ol>
     *   <li>Annotations directly declared on {@code method}</li>
     *   <li>Annotations from the same method signature in each superclass (bottom-up)</li>
     *   <li>Annotations from the same method signature in each transitively reachable interface
     *       (discovery order from {@link TypeResolver#getAllInterfaces})</li>
     * </ol>
     *
     * <p>If the same annotation type appears multiple times across the hierarchy the first
     * occurrence is retained and later duplicates are silently dropped (via
     * {@link LinkedHashSet} equality).
     *
     * @param method the method whose annotations should be resolved; must not be {@code null}
     * @return an immutable, ordered list of all resolved annotations; never {@code null}
     */
    public static List<Annotation> resolveMethodAnnotations(Method method) {
        Set<Annotation> annotations = new LinkedHashSet<>(List.of(method.getAnnotations()));

        // Walk superclass chain
        Class<?> current = method.getDeclaringClass().getSuperclass();
        while (current != null && current != Object.class) {
            try {
                Method m = current.getMethod(method.getName(), method.getParameterTypes());
                Collections.addAll(annotations, m.getAnnotations());
            } catch (NoSuchMethodException ignored) {
                // Method not declared on this superclass — continue traversal
            }
            current = current.getSuperclass();
        }

        // Walk interface hierarchy
        for (Class<?> iface : TypeResolver.getAllInterfaces(method.getDeclaringClass())) {
            try {
                Method m = iface.getMethod(method.getName(), method.getParameterTypes());
                Collections.addAll(annotations, m.getAnnotations());
            } catch (NoSuchMethodException ignored) {
                // Method not declared on this interface — continue traversal
            }
        }

        return List.copyOf(annotations);
    }

    /**
     * Resolves all annotations present on a class by walking both the superclass chain and the
     * interface hierarchy.
     *
     * <p>Traversal order:
     * <ol>
     *   <li>Annotations directly declared on {@code clazz}</li>
     *   <li>Annotations from each superclass (bottom-up, stopping before {@link Object})</li>
     *   <li>Annotations from each transitively reachable interface (discovery order from
     *       {@link TypeResolver#getAllInterfaces})</li>
     * </ol>
     *
     * <p>Duplicates are dropped in the same way as {@link #resolveMethodAnnotations}: the first
     * occurrence wins.
     *
     * @param clazz the class whose annotations should be resolved; must not be {@code null}
     * @return an immutable, ordered list of all resolved annotations; never {@code null}
     */
    public static List<Annotation> resolveClassAnnotations(Class<?> clazz) {
        Set<Annotation> annotations = new LinkedHashSet<>(List.of(clazz.getAnnotations()));

        // Walk superclass chain
        Class<?> current = clazz.getSuperclass();
        while (current != null && current != Object.class) {
            Collections.addAll(annotations, current.getAnnotations());
            current = current.getSuperclass();
        }

        // Walk interface hierarchy
        for (Class<?> iface : TypeResolver.getAllInterfaces(clazz)) {
            Collections.addAll(annotations, iface.getAnnotations());
        }

        return List.copyOf(annotations);
    }

    /**
     * Resolves all annotations present on a single method parameter by walking the same erased
     * signature on the method's superclass chain and the BFS-ordered interface hierarchy. This is
     * the parameter-level analog of {@link #resolveMethodAnnotations}.
     *
     * <p>Use case: a JAX-RS resource impl class declares
     * {@code public Response get(String id)} and the matching interface method declares
     * {@code Response get(@PathParam("id") @DefaultValue("0") String id)}. Direct
     * {@code parameter.getAnnotations()} on the impl returns nothing for {@code id}; this helper
     * returns the merged {@code @PathParam} + {@code @DefaultValue} array sourced from the
     * interface declaration.
     *
     * <p>Traversal order:
     * <ol>
     *   <li>Annotations directly declared on the parameter at {@code parameterIndex} of {@code method}</li>
     *   <li>Annotations from the same parameter index of the matching method signature in each
     *       superclass (bottom-up)</li>
     *   <li>Annotations from the same parameter index of the matching method signature in each
     *       transitively reachable interface (BFS discovery order from
     *       {@link TypeResolver#getAllInterfaces})</li>
     * </ol>
     *
     * <p>Dedup policy matches {@link #resolveMethodAnnotations} and uses {@link LinkedHashSet}
     * equality, so member-less annotations (or instances with identical member values) collapse to
     * one entry. Same-type instances with differing member values (e.g. an override that supplies a
     * different {@code @DefaultValue("...")}) are all kept; consumers that only care about the first
     * matching annotation per type get override-wins semantics for free because the result preserves
     * direct → super → interface insertion order.
     *
     * @param method         the method whose parameter annotations should be resolved
     * @param parameterIndex the zero-based parameter index
     * @return an immutable array of merged annotations; never {@code null}
     * @throws IndexOutOfBoundsException if {@code parameterIndex} is negative or
     *     {@code >= method.getParameterCount()}
     */
    public static Annotation[] resolveParameterAnnotations(Method method, int parameterIndex) {
        if (parameterIndex < 0 || parameterIndex >= method.getParameterCount()) {
            throw new IndexOutOfBoundsException("parameterIndex %d out of bounds for method %s (parameterCount=%d)"
                    .formatted(parameterIndex, method, method.getParameterCount()));
        }
        Set<Annotation> annotations = new LinkedHashSet<>(List.of(method.getParameterAnnotations()[parameterIndex]));

        // Walk superclass chain
        Class<?> current = method.getDeclaringClass().getSuperclass();
        while (current != null && current != Object.class) {
            try {
                Method m = current.getMethod(method.getName(), method.getParameterTypes());
                Collections.addAll(annotations, m.getParameterAnnotations()[parameterIndex]);
            } catch (NoSuchMethodException ignored) {
                // Method not declared on this superclass — continue traversal
            }
            current = current.getSuperclass();
        }

        // Walk interface hierarchy (BFS, matching TypeResolver.getAllInterfaces)
        for (Class<?> iface : TypeResolver.getAllInterfaces(method.getDeclaringClass())) {
            try {
                Method m = iface.getMethod(method.getName(), method.getParameterTypes());
                Collections.addAll(annotations, m.getParameterAnnotations()[parameterIndex]);
            } catch (NoSuchMethodException ignored) {
                // Method not declared on this interface — continue traversal
            }
        }

        return annotations.toArray(new Annotation[0]);
    }

    // --- Meta-annotation resolution ---

    /**
     * Finds an annotation of the given type on the element, including annotations that are
     * themselves meta-annotated with the target type (composed annotations). Uses recursive
     * traversal with a visited set to handle cyclic meta-annotation graphs.
     *
     * <p>Example: if {@code @NormalizedInput} is meta-annotated with {@code @Canonicalize(...)},
     * then {@code findMetaAnnotation(field, Canonicalize.class)} will find the {@code @Canonicalize}
     * on the {@code @NormalizedInput} annotation.
     *
     * @param element        the annotated element to inspect
     * @param annotationType the annotation type to search for
     * @param <A>            the annotation type
     * @return the first matching annotation, or {@code null} if not found
     */
    public static <A extends Annotation> A findMetaAnnotation(AnnotatedElement element, Class<A> annotationType) {
        // Direct check first
        A direct = element.getAnnotation(annotationType);
        if (direct != null) return direct;
        // Walk meta-annotations recursively
        return findMetaAnnotationRecursive(element.getAnnotations(), annotationType, new HashSet<>());
    }

    /**
     * Searches a pre-resolved annotation list for the given type, including meta-annotations.
     *
     * @param annotations    the annotation list to search
     * @param annotationType the annotation type to find
     * @param <A>            the annotation type
     * @return the first matching annotation, or {@code null} if not found
     */
    public static <A extends Annotation> A findMetaAnnotation(List<Annotation> annotations, Class<A> annotationType) {
        // Direct check first
        for (Annotation ann : annotations) {
            if (annotationType.isInstance(ann)) {
                @SuppressWarnings("unchecked")
                A typed = (A) ann;
                return typed;
            }
        }
        // Walk meta-annotations recursively
        return findMetaAnnotationRecursive(annotations.toArray(new Annotation[0]), annotationType, new HashSet<>());
    }

    /**
     * Recursively walks the meta-annotation graph looking for the target annotation type.
     *
     * <p>JDK built-in annotations ({@code java.lang.annotation.*}) are skipped to avoid
     * infinite recursion. A {@code visited} set prevents re-visiting annotation types already
     * seen in the current traversal path.
     *
     * @param annotations    the annotation array to walk
     * @param annotationType the target annotation type
     * @param visited        set of annotation types already visited in this traversal
     * @param <A>            the annotation type
     * @return the first matching annotation, or {@code null} if not found
     */
    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A findMetaAnnotationRecursive(
            Annotation[] annotations, Class<A> annotationType, Set<Class<? extends Annotation>> visited) {
        for (Annotation ann : annotations) {
            Class<? extends Annotation> annType = ann.annotationType();
            // Skip JDK annotations to avoid infinite recursion through Retention, Target, etc.
            if (annType.getName().startsWith("java.lang.annotation.")) continue;
            if (!visited.add(annType)) continue;

            // Check if this annotation itself is the target type
            if (annotationType.isInstance(ann)) return (A) ann;

            // Check annotations declared on this annotation's type
            A found = annType.getAnnotation(annotationType);
            if (found != null) return found;

            // Recurse deeper into annotations on this annotation's type
            A recursive = findMetaAnnotationRecursive(annType.getAnnotations(), annotationType, visited);
            if (recursive != null) return recursive;
        }
        return null;
    }
}
