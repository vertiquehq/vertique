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
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Internal codegen utility for JAX-RS interface/hierarchy walking, not a public API.
 *
 * <p>Provides shared BFS (breadth-first search) traversal over the interface hierarchy of a
 * concrete type element, and method-matching by erased signature. These helpers are used by both
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
     * Finds the abstract method in the given interface that matches the concrete method's erased
     * signature (simple name + erased parameter types).
     *
     * @param ctx            the shared codegen context; must not be {@code null}
     * @param concreteMethod the concrete method to match; must not be {@code null}
     * @param iface          the interface to search; must not be {@code null}
     * @return the matching interface method, or {@code null} if not found
     */
    public static ExecutableElement findMatchingMethod(
            CodegenContext ctx, ExecutableElement concreteMethod, TypeElement iface) {
        String key = methodKey(ctx, concreteMethod);
        for (javax.lang.model.element.Element enclosed : iface.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            if (!(enclosed instanceof ExecutableElement ifaceMethod)) {
                continue;
            }
            if (methodKey(ctx, ifaceMethod).equals(key)) {
                return ifaceMethod;
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
