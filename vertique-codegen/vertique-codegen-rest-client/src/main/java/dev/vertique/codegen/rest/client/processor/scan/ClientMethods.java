// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor.scan;

import dev.vertique.codegen.CodegenContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;

/**
 * The operations of a {@code @RestClient} interface as the generated proxy must implement them:
 * the abstract methods the interface declares and the ones it inherits from its super-interfaces,
 * with a declaration that another method overrides folded into its overrider. A default method is
 * not an operation but does override, and is overridden.
 *
 * <p>This is the compile-time counterpart of the runtime scanner's use of
 * {@code AnnotationResolver.inheritedDeclarations}: a redeclaration such as {@code delete(String)}
 * of {@code Crud<String>} is one operation that reads the annotations of {@code Crud<ID>.delete(ID)},
 * and its types are the ones the interface's hierarchy binds ({@code ID} is {@code String}).
 */
public final class ClientMethods {

    private ClientMethods() {}

    /**
     * One operation of the client interface.
     *
     * @param method the method the proxy implements: a method the interface declares, or an
     *     inherited declaration nothing in the interface overrides
     * @param declarations {@code method} first, then every declaration it overrides, nearest first;
     *     the annotation sources of the operation
     * @param type {@code method} as a member of the client interface, with hierarchy type variables
     *     resolved
     */
    public record ClientMethod(ExecutableElement method, List<ExecutableElement> declarations, ExecutableType type) {}

    /**
     * Collects the operations of {@code clientType}: every abstract instance method declared by the
     * interface or any super-interface, minus the declarations an overriding method (abstract or
     * default) replaces.
     *
     * @param ctx the codegen context
     * @param clientType the {@code @RestClient} interface
     * @return the operations in declaration order, the interface's own methods first
     */
    public static List<ClientMethod> collect(CodegenContext ctx, TypeElement clientType) {
        List<ExecutableElement> all = new ArrayList<>();
        for (TypeElement type : hierarchy(ctx, clientType)) {
            for (Element enclosed : type.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.METHOD && isInstance((ExecutableElement) enclosed)) {
                    all.add((ExecutableElement) enclosed);
                }
            }
        }

        Map<ExecutableElement, List<ExecutableElement>> overridden = new LinkedHashMap<>();
        Set<ExecutableElement> replaced = new LinkedHashSet<>();
        for (ExecutableElement method : all) {
            List<ExecutableElement> declarations = new ArrayList<>();
            declarations.add(method);
            for (ExecutableElement other : all) {
                if (other != method && ctx.elements().overrides(method, other, clientType)) {
                    declarations.add(other);
                    replaced.add(other);
                }
            }
            overridden.put(method, declarations);
        }

        List<ClientMethod> result = new ArrayList<>();
        DeclaredType owner = (DeclaredType) clientType.asType();
        for (ExecutableElement method : all) {
            // A default method is no operation; it takes part above so that it replaces an inherited
            // abstract declaration, and so that an abstract redeclaration still sees the verb of a
            // default one it re-abstracts.
            if (replaced.contains(method) || method.getModifiers().contains(Modifier.DEFAULT)) {
                continue;
            }
            ExecutableType type = (ExecutableType) ctx.types().asMemberOf(owner, method);
            ClientMethod twin = sameSignature(ctx, result, method, type);
            if (twin != null) {
                // The same operation inherited along two paths, with no override between them.
                twin.declarations().addAll(overridden.get(method));
                continue;
            }
            result.add(new ClientMethod(method, new ArrayList<>(overridden.get(method)), type));
        }
        return result;
    }

    private static ClientMethod sameSignature(
            CodegenContext ctx, List<ClientMethod> existing, ExecutableElement method, ExecutableType type) {
        for (ClientMethod candidate : existing) {
            if (candidate.method().getSimpleName().contentEquals(method.getSimpleName())
                    && ctx.types().isSubsignature(candidate.type(), type)
                    && ctx.types().isSubsignature(type, candidate.type())) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isInstance(ExecutableElement method) {
        Set<Modifier> modifiers = method.getModifiers();
        return !modifiers.contains(Modifier.STATIC) && !modifiers.contains(Modifier.PRIVATE);
    }

    /** The interface and every transitive super-interface, nearest first. */
    private static List<TypeElement> hierarchy(CodegenContext ctx, TypeElement clientType) {
        List<TypeElement> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<TypeElement> queue = new ArrayDeque<>();
        queue.add(clientType);
        seen.add(clientType.getQualifiedName().toString());
        while (!queue.isEmpty()) {
            TypeElement type = queue.poll();
            result.add(type);
            for (TypeMirror parent : type.getInterfaces()) {
                if (ctx.types().asElement(parent) instanceof TypeElement parentElement
                        && seen.add(parentElement.getQualifiedName().toString())) {
                    queue.add(parentElement);
                }
            }
        }
        return result;
    }
}
