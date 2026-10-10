// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.CodegenContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Internal codegen utility for JAX-RS interface/hierarchy walking, not a public API.
 *
 * <p>Provides shared BFS (breadth-first search) traversal over the interface hierarchy of a
 * concrete type element, and the lookup of the declarations a method overrides. These helpers are
 * used by both
 * {@link EffectiveJaxRsContractResolver} and
 * {@link dev.vertique.codegen.jaxrs.processor.validate.ContextParamValidator} to eliminate
 * duplication of the traversal logic.
 *
 * <p>The BFS interface walk mirrors the runtime {@code TypeResolver.getAllInterfaces} algorithm:
 * deque + {@link LinkedHashSet} dedup keyed by binary name. First-found-wins produces the same
 * deterministic outcome as the runtime {@code AnnotationResolver}.
 *
 * <p>All methods are static and require a {@link CodegenContext} as their first argument. This
 * class is non-instantiable.
 */
public final class JaxRsHierarchy {

    private JaxRsHierarchy() {
        // non-instantiable utility class
    }

    // --- Public API ---

    /**
     * Returns all transitively implemented interfaces of the given type element in BFS discovery
     * order, deduplicating by binary name. Mirrors the runtime
     * {@code TypeResolver.getAllInterfaces} algorithm.
     *
     * <p>The walk seeds the BFS deque with the direct interfaces of the given type <em>and</em>
     * its entire superclass chain (stopping before {@code java.lang.Object}). Each interface is
     * then expanded into its own super-interfaces. The resulting list is ordered by first-discovery
     * and contains no duplicates.
     *
     * @param ctx         the shared codegen context; must not be {@code null}
     * @param typeElement the type element to inspect; must not be {@code null}
     * @return an ordered, deduplicated list of interface type elements; never {@code null}
     */
    public static List<TypeElement> allInterfaces(CodegenContext ctx, TypeElement typeElement) {
        List<TypeElement> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<TypeElement> queue = new ArrayDeque<>();

        // Seed with direct interfaces from the class and its superclass chain
        TypeElement current = typeElement;
        while (current != null
                && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
            enqueueDirectInterfaces(ctx, current, queue, seen);
            current = superClass(ctx, current);
        }

        while (!queue.isEmpty()) {
            TypeElement iface = queue.poll();
            result.add(iface);
            // Enqueue super-interfaces of this interface
            enqueueDirectInterfaces(ctx, iface, queue, seen);
        }
        return result;
    }

    /**
     * Returns the interfaces a resource method inherits declarations from, in BFS order.
     *
     * <p>For an inherited interface {@code default} method (enclosed by an interface) that is the
     * default's own super-interface hierarchy — the methods it overrides — which is where the
     * runtime {@code AnnotationResolver} starts when the method's declaring type is an interface.
     * For a method declared by a class it is {@link #allInterfaces(CodegenContext, TypeElement)} of
     * the resource class, matching the runtime walk that passes the resource class as the
     * annotation view: a superclass-declared method still inherits annotations from interfaces the
     * resource implements.
     *
     * @param ctx           the shared codegen context; must not be {@code null}
     * @param method        the resource method; must not be {@code null}
     * @param resourceClass the resource class; must not be {@code null}
     * @return an ordered, deduplicated list of interface type elements; never {@code null}
     */
    static List<TypeElement> interfacesForMethod(
            CodegenContext ctx, ExecutableElement method, TypeElement resourceClass) {
        if (method.getEnclosingElement() instanceof TypeElement owner && owner.getKind() == ElementKind.INTERFACE) {
            return allInterfaces(ctx, owner);
        }
        return allInterfaces(ctx, resourceClass);
    }

    /**
     * Returns the declarations {@code method} overrides, in resolution order: the methods of its
     * declaring class's superclasses, bottom-up, then those of the interfaces from
     * {@link #interfacesForMethod}. A method declared by an interface has no superclass walk, as at
     * runtime. The method itself is not part of the result.
     *
     * @param ctx           the shared codegen context; must not be {@code null}
     * @param method        the method whose overridden declarations are wanted
     * @param resourceClass the resource class the method is a member of
     * @param publicOnly    {@code true} to accept public (and interface) declarations only, the set
     *                      the runtime resolver sees; {@code false} to accept every inherited
     *                      declaration that is not private or static
     * @return the corresponding declarations; never {@code null}
     */
    static List<ExecutableElement> inheritedDeclarations(
            CodegenContext ctx, ExecutableElement method, TypeElement resourceClass, boolean publicOnly) {
        List<ExecutableElement> result = new ArrayList<>();
        if (method.getEnclosingElement() instanceof TypeElement owner && owner.getKind() != ElementKind.INTERFACE) {
            TypeElement current = superClass(ctx, owner);
            while (current != null
                    && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
                result.addAll(findMatchingMethods(ctx, method, current, resourceClass, publicOnly));
                current = superClass(ctx, current);
            }
        }
        for (TypeElement iface : interfacesForMethod(ctx, method, resourceClass)) {
            for (ExecutableElement declaration : findMatchingMethods(ctx, method, iface, resourceClass, publicOnly)) {
                if (!result.contains(declaration)) {
                    result.add(declaration);
                }
            }
        }
        return result;
    }

    /**
     * Finds the methods of {@code type} that {@code method} overrides or implements as a member of
     * {@code resourceClass}.
     *
     * <p>A declaration corresponds when it has the same erased signature (the match that has always
     * applied) or when {@code method} overrides it according to the Java language rules for the
     * resource class, which resolves a type variable bound in the hierarchy: {@code delete(String)}
     * of {@code Users implements Crud<String>} overrides {@code Crud<ID>.delete(ID)}. A private or
     * static declaration is never inherited and never corresponds. All matches are returned, not
     * the first.
     *
     * @param ctx           the shared codegen context; must not be {@code null}
     * @param method        the overriding method; must not be {@code null}
     * @param type          the superclass or interface whose declarations are searched
     * @param resourceClass the resource class the method is a member of
     * @param publicOnly    whether only public (and interface) declarations are accepted
     * @return the matching declarations in declaration order; never {@code null}
     */
    public static List<ExecutableElement> findMatchingMethods(
            CodegenContext ctx,
            ExecutableElement method,
            TypeElement type,
            TypeElement resourceClass,
            boolean publicOnly) {
        // The exact erased matches come first, as at runtime (the exact lookup precedes the
        // binding-aware scan); overrides-only matches follow, each group in declaration order.
        List<ExecutableElement> exact = new ArrayList<>();
        List<ExecutableElement> bound = new ArrayList<>();
        String key = null;
        for (javax.lang.model.element.Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD || !(enclosed instanceof ExecutableElement candidate)) {
                continue;
            }
            if (candidate.equals(method)
                    || !candidate.getSimpleName().contentEquals(method.getSimpleName())
                    || candidate.getParameters().size()
                            != method.getParameters().size()) {
                continue;
            }
            Set<Modifier> modifiers = candidate.getModifiers();
            if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.STATIC)) {
                continue;
            }
            if (publicOnly && type.getKind() != ElementKind.INTERFACE && !modifiers.contains(Modifier.PUBLIC)) {
                continue;
            }
            if (key == null) {
                key = methodKey(ctx, method);
            }
            if (methodKey(ctx, candidate).equals(key)) {
                exact.add(candidate);
            } else if (ctx.elements().overrides(method, candidate, resourceClass)) {
                bound.add(candidate);
            }
        }
        exact.addAll(bound);
        return exact;
    }

    /**
     * Finds the method in the given type that matches the concrete method's erased signature
     * (simple name + erased parameter types), and only that. Used where the question is JVM
     * descriptor shadowing, not inheritance: a type variable bound in the hierarchy does not make
     * two methods the same JVM method.
     *
     * @param ctx            the shared codegen context; must not be {@code null}
     * @param concreteMethod the concrete method to match; must not be {@code null}
     * @param type           the type to search; must not be {@code null}
     * @return the matching method, or {@code null} if not found
     */
    public static ExecutableElement findErasedMatch(
            CodegenContext ctx, ExecutableElement concreteMethod, TypeElement type) {
        String key = methodKey(ctx, concreteMethod);
        for (javax.lang.model.element.Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            if (!(enclosed instanceof ExecutableElement typeMethod)) {
                continue;
            }
            if (methodKey(ctx, typeMethod).equals(key)) {
                return typeMethod;
            }
        }
        return null;
    }

    /**
     * Returns the superclass {@link TypeElement} of the given type, or {@code null} if there is
     * none (e.g. for {@code java.lang.Object} or interfaces).
     *
     * <p>Unlike the {@code ContextParamValidator}-local variant, this method does <em>not</em>
     * special-case {@code java.lang.Object} with an early return — callers that loop up the
     * superclass chain are responsible for guarding against {@code java.lang.Object} in the loop
     * condition. The {@link #allInterfaces} method already guards on Object in its while-loop.
     *
     * @param ctx  the shared codegen context; must not be {@code null}
     * @param type the type element whose superclass to look up; must not be {@code null}
     * @return the superclass element, or {@code null} if the superclass is unresolvable
     */
    public static TypeElement superClass(CodegenContext ctx, TypeElement type) {
        TypeMirror superMirror = type.getSuperclass();
        if (superMirror == null) {
            return null;
        }
        var el = ctx.types().asElement(superMirror);
        return el instanceof TypeElement te ? te : null;
    }

    // --- Private helpers ---

    /**
     * Enqueues the directly-declared interfaces of {@code element} into {@code queue}, skipping
     * any already in {@code seen}.
     *
     * @param ctx     the shared codegen context
     * @param element the type element whose interfaces to enqueue
     * @param queue   the deque to add to
     * @param seen    the set of already-seen binary names
     */
    private static void enqueueDirectInterfaces(
            CodegenContext ctx, TypeElement element, Deque<TypeElement> queue, Set<String> seen) {
        for (TypeMirror ifaceMirror : element.getInterfaces()) {
            TypeElement ifaceEl = ctx.asTypeElement(ifaceMirror).orElse(null);
            if (ifaceEl == null) {
                continue;
            }
            String binaryName = ctx.elements().getBinaryName(ifaceEl).toString();
            if (seen.add(binaryName)) {
                queue.add(ifaceEl);
            }
        }
    }

    /**
     * Builds a deduplication key from a method's simple name and its erased parameter types.
     *
     * @param ctx    the shared codegen context
     * @param method the method element
     * @return the key string
     */
    private static String methodKey(CodegenContext ctx, ExecutableElement method) {
        StringBuilder sb = new StringBuilder(method.getSimpleName().toString());
        for (var param : method.getParameters()) {
            sb.append(':').append(ctx.types().erasure(param.asType()).toString());
        }
        return sb.toString();
    }
}
