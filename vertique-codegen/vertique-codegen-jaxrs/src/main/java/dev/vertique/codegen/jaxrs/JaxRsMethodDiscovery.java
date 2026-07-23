// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Collects the effective set of resource methods for a JAX-RS resource class by walking the
 * superclass chain, mirroring the semantics of the runtime {@code ResourceScanner.collectMethods}.
 *
 * <p>Extracted from {@code JaxRsResourceProcessor} (CG-010) so that both the CG-009 validator
 * pipeline and the {@link EffectiveJaxRsContractResolver} share the same method-discovery logic.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Start at the resource class itself; walk {@code superclass()} until {@code java.lang.Object}.</li>
 *   <li>On each level, take declared elements of kind {@link ElementKind#METHOD}.</li>
 *   <li>Skip {@code default} interface methods — the runtime does the same.</li>
 *   <li>Deduplicate by {@code name:erasedParam1Type:...} so subclass overrides win (the resource
 *       class is visited first and its version is inserted first into the seen map).</li>
 * </ol>
 *
 * <p><strong>Interface methods are NOT included here.</strong> Interface contributions reach a
 * concrete method only as annotations on the concrete class's matching method — they are not
 * additional methods. The {@link EffectiveJaxRsContractResolver} consults interface annotations
 * when resolving the effective contract for each concrete method.
 */
public final class JaxRsMethodDiscovery {

    private JaxRsMethodDiscovery() {}

    /**
     * Collects all declared resource methods on the given class by walking the superclass chain
     * up to (but not including) {@code java.lang.Object}.
     *
     * <p>Bridge and synthetic methods do not appear at the source level in APT, so no equivalent
     * skip is needed.
     *
     * @param resource the concrete resource type element; must not be {@code null}
     * @param types    the APT {@link Types} utility; must not be {@code null}
     * @return an ordered, deduplicated list of declared resource methods; never {@code null}
     */
    public static List<ExecutableElement> collect(TypeElement resource, Types types) {
        Map<String, ExecutableElement> seen = new LinkedHashMap<>();
        TypeElement current = resource;
        while (current != null
                && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
            for (Element enclosed : current.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) {
                    continue;
                }
                if (!(enclosed instanceof ExecutableElement method)) {
                    continue;
                }
                // Skip default interface methods — runtime ResourceScanner.collectMethods does too
                if (method.getModifiers().contains(Modifier.DEFAULT)) {
                    continue;
                }
                String key = methodKey(method, types);
                // putIfAbsent: the resource class is visited first, so its override wins
                seen.putIfAbsent(key, method);
            }
            TypeMirror superMirror = current.getSuperclass();
            if (superMirror == null) {
                break;
            }
            var superElement = types.asElement(superMirror);
            if (!(superElement instanceof TypeElement te)) {
                break;
            }
            current = te;
        }
        return List.copyOf(seen.values());
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
