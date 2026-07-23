// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Immutable compile-time representation of the resolved JAX-RS method contract for a single
 * resource method.
 *
 * <p>Built by {@link EffectiveJaxRsContractResolver} using the precedence rule (direct →
 * superclass → BFS interfaces). All annotation data is derived from the effective source, not
 * directly from the concrete method.
 *
 * @param concreteMethod      the concrete method element; never {@code null}
 * @param httpMethod          the HTTP verb annotation FQN (e.g. {@code "jakarta.ws.rs.GET"}), or
 *                            {@code null} for sub-resource locators
 * @param methodPath          the value of the method-level {@code @Path}, or {@code null} if absent
 * @param operationId         the {@code @Operation.operationId}, or {@code null} if absent
 * @param consumes            the media types from {@code @Consumes}, or an empty list if absent
 * @param produces            the media types from {@code @Produces}, or an empty list if absent
 * @param methodSecurity      the resolved method-level security contract; never {@code null}
 * @param validationGroups    the validation group type mirrors from {@code @ValidateWith}, or
 *                            {@code null} if the annotation is absent
 * @param params              the resolved parameter contracts in declaration order; never {@code null}
 * @param routeCanonicalizers ordered list of canonicalizer class type mirrors for the route-level
 *                            chain (method-level {@code @Canonicalize}/{@code @SkipCanonicalization}
 *                            overrides class-level; empty when none apply); never {@code null}
 * @param routeSanitizers     ordered list of sanitizer class type mirrors for the route-level chain
 *                            (method-level {@code @Sanitize}/{@code @SkipSanitization} overrides
 *                            class-level; empty when none apply); never {@code null}
 */
public record EffectiveMethodContract(
        ExecutableElement concreteMethod,
        String httpMethod,
        String methodPath,
        String operationId,
        List<String> consumes,
        List<String> produces,
        EffectiveSecurityContract methodSecurity,
        List<TypeMirror> validationGroups,
        List<EffectiveParamContract> params,
        List<TypeMirror> routeCanonicalizers,
        List<TypeMirror> routeSanitizers) {}
