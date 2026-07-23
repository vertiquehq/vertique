// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * Immutable compile-time representation of the resolved JAX-RS resource contract for a single
 * concrete resource class.
 *
 * <p>Built by {@link EffectiveJaxRsContractResolver#resolve(TypeElement)} using the precedence
 * rule (direct → superclass → BFS interfaces). When a class-level conflict is detected (e.g. two
 * implemented interfaces carry conflicting {@code @Path} or security annotations), the resolver
 * emits a compile-time error and returns an instance with an empty {@code methods} list so
 * downstream steps can safely skip the offending resource.
 *
 * @param concreteClass   the concrete class element; never {@code null}
 * @param classPath       the resolved {@code @Path} value at the class level, or {@code null}
 *                        if no effective class-level path was found
 * @param classSecurity   the resolved class-level security contract; never {@code null}
 * @param methods         the resolved method contracts; empty when the resource was skipped due
 *                        to a class-level conflict; never {@code null}
 */
public record EffectiveResourceContract(
        TypeElement concreteClass,
        String classPath,
        EffectiveSecurityContract classSecurity,
        List<EffectiveMethodContract> methods) {

    /**
     * Returns {@code true} when this contract represents a class-level-conflict-skipped resource
     * — that is, when the {@code methods} list is empty due to a conflict error (not just an
     * empty resource class).
     *
     * <p>Callers that only need to distinguish "skip entirely" from "validate" can use this
     * predicate in combination with the {@code classPath} being non-{@code null}: a resource that
     * has no methods but does have a class path has no endpoint methods; a resource that has
     * {@code classPath == null} was skipped for a different reason.
     *
     * @return {@code true} if the methods list is empty
     */
    public boolean hasNoMethods() {
        return methods.isEmpty();
    }
}
