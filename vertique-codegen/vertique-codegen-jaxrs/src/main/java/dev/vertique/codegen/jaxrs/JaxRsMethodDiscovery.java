// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.CodegenContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.util.Types;

/**
 * Collects the effective set of resource methods for a JAX-RS resource class, mirroring the
 * semantics of the runtime {@code ResourceScanner.collectMethods}.
 *
 * <p>Extracted from {@code JaxRsResourceProcessor} (CG-010) so that both the CG-009 validator
 * pipeline and the {@link EffectiveJaxRsContractResolver} share the same method-discovery logic.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Start at the resource class itself; walk {@code superclass()} until {@code java.lang.Object}.</li>
 *   <li>On each level, take declared elements of kind {@link ElementKind#METHOD}.</li>
 *   <li>Deduplicate by {@code name:erasedParam1Type:...} so subclass overrides win (the resource
 *       class is visited first and its version is inserted first into the seen map).</li>
 *   <li>Then add every {@code default} method of the transitively implemented interfaces (BFS
 *       order, {@link JaxRsHierarchy#allInterfaces}) that the class inherits: one that no
 *       non-private, non-static method of the superclass chain overrides and no method of a more
 *       specific interface overrides (issue #630).</li>
 * </ol>
 *
 * <p>The override test compares member types of the resource ({@link Types#asMemberOf} plus
 * {@link Types#isSubsignature}), not erased keys: a class that overrides a generic interface
 * default {@code remove(I)} with {@code remove(String)} must shadow it, exactly as the class's
 * bridge method shadows it for the runtime's {@link Class#getMethods()} selection.
 *
 * <p>Interface methods that a class method implements are <strong>not</strong> added as separate
 * methods: interface contributions reach such a method only as annotations, which the
 * {@link EffectiveJaxRsContractResolver} consults when resolving its effective contract. An
 * inherited default method is itself the resource method; its own annotations are its direct
 * annotations.
 */
public final class JaxRsMethodDiscovery {

    private JaxRsMethodDiscovery() {}

    /**
     * Collects the resource methods of the given class: the methods declared along its superclass
     * chain up to (but not including) {@code java.lang.Object}, followed by the interface
     * {@code default} methods it inherits without overriding.
     *
     * <p>Bridge and synthetic methods do not appear at the source level in APT, so no equivalent
     * skip is needed.
     *
     * @param ctx      the shared codegen context; must not be {@code null}
     * @param resource the concrete resource type element; must not be {@code null}
     * @return an ordered, deduplicated list of resource methods; never {@code null}
     */
    public static List<ExecutableElement> collect(CodegenContext ctx, TypeElement resource) {
        Types types = ctx.types();
        Map<String, ExecutableElement> seen = new LinkedHashMap<>();
        TypeElement current = resource;
        while (current != null
                && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
            for (ExecutableElement method : declaredMethods(current)) {
                // putIfAbsent: the resource class is visited first, so its override wins
                seen.putIfAbsent(methodKey(method, types), method);
            }
            current = JaxRsHierarchy.superClass(ctx, current);
        }

        List<ExecutableElement> classMethods = List.copyOf(seen.values());
        List<TypeElement> interfaces = JaxRsHierarchy.allInterfaces(ctx, resource);
        DeclaredType resourceType = (DeclaredType) resource.asType();
        for (TypeElement iface : interfaces) {
            for (ExecutableElement method : declaredMethods(iface)) {
                if (method.getModifiers().contains(Modifier.DEFAULT)
                        && !isOverridden(method, iface, classMethods, interfaces, resourceType, types)) {
                    seen.putIfAbsent(methodKey(method, types), method);
                }
            }
        }
        return List.copyOf(seen.values());
    }

    /**
     * Returns {@code true} when a method of the superclass chain, or of an interface more specific
     * than {@code iface}, overrides the given default method as a member of the resource type.
     *
     * @param defaultMethod the interface default method
     * @param iface         the interface declaring {@code defaultMethod}
     * @param classMethods  the methods collected from the superclass chain
     * @param interfaces    all interfaces the resource implements
     * @param resourceType  the resource type the methods are members of
     * @param types         the APT {@link Types} utility
     * @return whether {@code defaultMethod} is overridden and therefore not inherited
     */
    private static boolean isOverridden(
            ExecutableElement defaultMethod,
            TypeElement iface,
            List<ExecutableElement> classMethods,
            List<TypeElement> interfaces,
            DeclaredType resourceType,
            Types types) {
        for (ExecutableElement candidate : classMethods) {
            if (overrides(candidate, defaultMethod, resourceType, types)) {
                return true;
            }
        }
        for (TypeElement other : interfaces) {
            if (other.equals(iface) || !types.isSubtype(types.erasure(other.asType()), types.erasure(iface.asType()))) {
                continue;
            }
            for (ExecutableElement candidate : declaredMethods(other)) {
                if (overrides(candidate, defaultMethod, resourceType, types)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns {@code true} when {@code candidate} has the same name as {@code overridden} and its
     * signature, as a member of {@code resourceType}, is a subsignature of {@code overridden}'s.
     * Static and private methods never override.
     *
     * @param candidate    the potentially overriding method
     * @param overridden   the potentially overridden method
     * @param resourceType the type both methods are viewed as members of
     * @param types        the APT {@link Types} utility
     * @return whether {@code candidate} overrides {@code overridden}
     */
    private static boolean overrides(
            ExecutableElement candidate, ExecutableElement overridden, DeclaredType resourceType, Types types) {
        if (candidate.getModifiers().contains(Modifier.STATIC)
                || candidate.getModifiers().contains(Modifier.PRIVATE)
                || !candidate.getSimpleName().contentEquals(overridden.getSimpleName())
                || candidate.getParameters().size()
                        != overridden.getParameters().size()) {
            return false;
        }
        ExecutableType candidateType = (ExecutableType) types.asMemberOf(resourceType, candidate);
        ExecutableType overriddenType = (ExecutableType) types.asMemberOf(resourceType, overridden);
        return types.isSubsignature(candidateType, overriddenType);
    }

    /**
     * Returns the methods a type declares directly, in declaration order.
     *
     * @param type the type element
     * @return the declared methods; never {@code null}
     */
    private static List<ExecutableElement> declaredMethods(TypeElement type) {
        List<ExecutableElement> methods = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD && enclosed instanceof ExecutableElement method) {
                methods.add(method);
            }
        }
        return methods;
    }

    /**
     * Builds a deduplication key from the method's simple name and its erased parameter types.
     * Uses erased types to match the runtime {@code ResourceScanner.collectMethods} contract:
     * subclass overrides win, and generic type parameters are stripped so that
     * {@code List<String>} and {@code List<Integer>} deduplicate correctly.
     *
     * @param method the method element to key; must not be {@code null}
     * @param types  the APT {@link Types} utility
     * @return a string of the form {@code "methodName:erasedParam1Type:erasedParam2Type:..."}
     */
    private static String methodKey(ExecutableElement method, Types types) {
        StringBuilder sb = new StringBuilder(method.getSimpleName().toString());
        for (var param : method.getParameters()) {
            sb.append(':').append(types.erasure(param.asType()).toString());
        }
        return sb.toString();
    }
}
