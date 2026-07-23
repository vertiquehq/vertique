// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.JaxRsAnnotations;
import dev.vertique.codegen.jaxrs.EffectiveMethodContract;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ExecutableElement;

/**
 * Validates HTTP verb annotations on JAX-RS resource methods at compile time (CG-009).
 *
 * <p>Covers the seven JAX-RS HTTP verb annotations: {@code @GET}, {@code @POST}, {@code @PUT},
 * {@code @DELETE}, {@code @PATCH}, {@code @HEAD}, and {@code @OPTIONS} — the same set as
 * {@code ResourceScanner.resolveHttpMethod} at runtime.
 *
 * <p>Tier-A parity rule: methods with zero HTTP verb annotations are silently skipped. Runtime
 * {@code ResourceScanner.scanResource} also skips them ({@code continue} past them). This covers
 * helper methods, sub-resource locators (method-level {@code @Path} only), and any other
 * non-endpoint method on the resource class.
 *
 * <p>Tier-B build-time-only guardrail: a method with <em>more than one</em> HTTP verb annotation
 * is an error. Runtime silently picks the first match in declaration order; the extra verb is
 * always a typo.
 *
 * <p>Two APIs are provided: the original element-based API (used by CG-009 tests) and an
 * {@link EffectiveMethodContract}-based API used by the {@code JaxRsPipelineProcessor} (CG-010).
 */
public final class HttpVerbValidator {

    private final CodegenContext ctx;

    /**
     * Creates a new {@code HttpVerbValidator} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public HttpVerbValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    // --- Element-based API (CG-009, preserved for existing tests) ---

    /**
     * Returns the list of HTTP verb annotation FQNs present on the given method, in the same
     * order as {@link JaxRsAnnotations#HTTP_VERBS}. Returns an empty list when no verb is present.
     *
     * <p>Callers should use the returned list to avoid a second scan of the annotation mirrors:
     * pass it to {@link #validate(ExecutableElement, List)} for conflict checking, and check
     * {@code presentVerbs.size() == 1} to determine whether to run the remaining validators.
     *
     * @param method the resource method to inspect; must not be {@code null}
     * @return an ordered list of HTTP verb annotation FQNs present on the method; never {@code null}
     */
    public List<String> presentVerbs(ExecutableElement method) {
        List<String> present = new ArrayList<>();
        for (String fqn : JaxRsAnnotations.HTTP_VERBS) {
            if (AnnotationMirrors.isPresent(method, fqn)) {
                present.add(fqn);
            }
        }
        return present;
    }

    /**
     * Validates that the method does not declare more than one HTTP verb annotation (Tier-B
     * guardrail), given the pre-computed list returned by {@link #presentVerbs(ExecutableElement)}.
     * Methods with zero verbs are silently accepted (Tier-A parity).
     *
     * <p>Emits an {@code ERROR} diagnostic on the method element when more than one verb is present.
     *
     * @param method       the resource method to validate; must not be {@code null}
     * @param presentVerbs the list of verb FQNs on the method, as returned by
     *                     {@link #presentVerbs(ExecutableElement)}; must not be {@code null}
     */
    public void validate(ExecutableElement method, List<String> presentVerbs) {
        if (presentVerbs.size() <= 1) return;
        List<String> simpleNames = new ArrayList<>(presentVerbs.size());
        for (String fqn : presentVerbs) {
            int dot = fqn.lastIndexOf('.');
            simpleNames.add("@" + (dot >= 0 ? fqn.substring(dot + 1) : fqn));
        }
        ctx.diagnostics()
                .error(
                        method,
                        Diagnostics.multipleHttpVerbs(method.getSimpleName().toString(), simpleNames));
    }

    // --- Contract-based API (CG-010) ---

    /**
     * Returns the effective HTTP verb annotation FQN from the resolved method contract as a
     * single-element list, or an empty list when the contract has no HTTP verb (sub-resource
     * locator or non-endpoint method).
     *
     * <p>The effective verb is already resolved by {@code EffectiveJaxRsContractResolver} — this
     * method simply wraps it for compatibility with the pre-scan loop in
     * {@code JaxRsPipelineProcessor}.
     *
     * @param contract the resolved method contract; must not be {@code null}
     * @return a one-element list with the verb FQN, or an empty list; never {@code null}
     */
    public List<String> presentVerbs(EffectiveMethodContract contract) {
        if (contract.httpMethod() == null) {
            return List.of();
        }
        return List.of(contract.httpMethod());
    }

    /**
     * Validates the HTTP verb declaration in the given method contract (Tier-B guardrail).
     *
     * <p>The effective contract already has at most one verb (the resolver picks the first-found
     * win). This overload is provided for structural parity — it only emits an error when
     * the underlying concrete method element itself carries multiple verbs directly (CG-010
     * still flags this as a programmer mistake even when the resolver resolved to one).
     *
     * <p>Emits an {@code ERROR} diagnostic on the concrete method element when multiple verbs are
     * present directly on that element.
     *
     * @param contract the resolved method contract; must not be {@code null}
     */
    public void validate(EffectiveMethodContract contract) {
        // Re-check the concrete method element directly — the resolver resolves to one verb,
        // but the original method may carry multiple verbs (Tier-B guardrail still applies).
        ExecutableElement method = contract.concreteMethod();
        List<String> verbs = presentVerbs(method);
        validate(method, verbs);
    }
}
