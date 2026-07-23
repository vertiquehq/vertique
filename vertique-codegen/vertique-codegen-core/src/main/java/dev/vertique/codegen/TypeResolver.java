// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * APT-layer type resolution helper providing BFS-based supertype walking and generic type-argument
 * resolution over {@link javax.lang.model.type.TypeMirror} and {@link javax.lang.model.util.Types}.
 *
 * <p>This class mirrors the algorithm used by the runtime {@link dev.vertique.core.util.TypeResolver}
 * but operates entirely in the annotation-processing world ({@link Types} / {@link TypeMirror})
 * rather than on reflective {@link java.lang.reflect.Type} objects. The two implementations are
 * intentionally parallel but separate: do not conflate them.
 *
 * <p>Known limitation (inherited from the runtime resolver): type-variable forwarding through
 * intermediate parameterized types is not supported. For example, given
 * {@code interface Forwarding<T> extends Target<T>} and {@code class Impl implements Forwarding<String>},
 * {@link #resolveTypeArgument} returns {@link Optional#empty()} because it sees {@code Target<T>}
 * (a type variable) rather than a concrete type. Only direct concrete bindings in an
 * {@code extends} or {@code implements} clause are resolved.
 */
public final class TypeResolver {

    private final Types types;
    private final Elements elements;

    /**
     * Constructs a {@code TypeResolver} bound to the given {@link Types} and {@link Elements}
     * utilities from a processing environment.
     *
     * @param types    the type utilities; must not be {@code null}
     * @param elements the element utilities; must not be {@code null}
     */
    public TypeResolver(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
    }

    /**
     * Returns a stream of the direct supertypes (direct superclass plus directly implemented
     * interfaces) of the given type mirror.
     *
     * <p>Delegates to {@link Types#directSupertypes(TypeMirror)}. Returns an empty stream when
     * {@code type} is {@code null} or the compiler returns a {@code null} / empty list.
     *
     * @param type the type mirror to inspect; may be {@code null} (returns empty stream)
     * @return a stream of direct supertypes; never {@code null}
     */
    public Stream<TypeMirror> directSupertypes(TypeMirror type) {
        if (type == null) {
            return Stream.empty();
        }
        var supers = types.directSupertypes(type);
        if (supers == null) {
            return Stream.empty();
        }
        return supers.stream().map(t -> (TypeMirror) t);
    }

    /**
     * Returns a stream of all supertypes reachable from the given type mirror via BFS, deduplicating
     * by erasure name.
     *
     * <p>The BFS mirrors the algorithm in {@link dev.vertique.core.util.TypeResolver#getAllInterfaces}
     * but works over the APT type hierarchy. The starting type itself is included in the results.
     *
     * @param type the type mirror to inspect; must not be {@code null}
     * @return a stream of all supertypes (including the starting type) in BFS discovery order;
     *         never {@code null}
     */
    public Stream<TypeMirror> allSupertypes(TypeMirror type) {
        Set<TypeMirror> visited = new LinkedHashSet<>();
        Set<String> visitedErasures = new HashSet<>();
        Deque<TypeMirror> queue = new ArrayDeque<>();
        queue.add(type);

        while (!queue.isEmpty()) {
            TypeMirror current = queue.poll();
            if (current.getKind() != TypeKind.DECLARED) {
                continue;
            }
            String erasedName = types.erasure(current).toString();
            if (visitedErasures.add(erasedName)) {
                visited.add(current);
                directSupertypes(current).forEach(queue::add);
            }
        }

        return visited.stream();
    }

    /**
     * Resolves the type argument at the given index from a concrete type's parameterized
     * relationship to the given target interface or class.
     *
     * <p>Walks the supertype hierarchy of {@code subject} looking for a parameterized supertype
     * whose erasure matches {@code targetInterface}. When found, returns the type argument at
     * {@code index}.
     *
     * <p>Returns {@link Optional#empty()} when the target is not found, the type argument is a
     * type variable or wildcard, or the index is out of range.
     *
     * @param subject         the type mirror to inspect; must not be {@code null}
     * @param targetInterface the type element representing the interface or class to find in the
     *                        hierarchy; must not be {@code null}
     * @param index           zero-based index of the type argument to retrieve
     * @return an {@link Optional} containing the resolved type argument, or empty if unresolvable
     */
    public Optional<TypeMirror> resolveTypeArgument(TypeMirror subject, TypeElement targetInterface, int index) {
        TypeMirror targetErasure = types.erasure(targetInterface.asType());

        return allSupertypes(subject)
                .filter(t ->
                        t instanceof DeclaredType dt && !dt.getTypeArguments().isEmpty())
                .filter(t -> types.erasure(t).toString().equals(targetErasure.toString()))
                .map(t -> (DeclaredType) t)
                .filter(dt -> index < dt.getTypeArguments().size())
                .map(dt -> dt.getTypeArguments().get(index))
                .filter(arg -> arg.getKind() == TypeKind.DECLARED || arg.getKind() == TypeKind.ARRAY)
                .map(arg -> (TypeMirror) arg)
                .findFirst();
    }

    /**
     * Returns {@code true} if {@code sub} is assignable to the erasure of the given
     * {@code superClass}.
     *
     * <p>Resolves the {@link TypeElement} for {@code superClass} via {@link Elements#getTypeElement},
     * then uses {@link Types#isAssignable} with erasure comparison so that parameterized types
     * are compared by raw type only.
     *
     * @param sub        the candidate subtype; must not be {@code null}
     * @param superClass the class to test assignability against; must not be {@code null}
     * @return {@code true} if {@code sub} is assignable to {@code superClass} after erasure
     */
    public boolean isAssignable(TypeMirror sub, Class<?> superClass) {
        TypeElement superElement = elements.getTypeElement(superClass.getName());
        TypeMirror superErasure = types.erasure(superElement.asType());
        TypeMirror subErasure = types.erasure(sub);
        return types.isAssignable(subErasure, superErasure);
    }
}
