// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.input.processing.InvocationPolicyConflictException;
import dev.vertique.input.processing.apt.ElementInvocationPolicies;
import dev.vertique.input.processing.apt.ElementInvocationPolicies.ElementPolicyChains;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Resolves each {@code @McpTool} parameter's final effective input-policy chain at compile time
 * through the shared {@link ElementInvocationPolicies} adapter — the same hierarchy- and
 * composed-annotation-aware derivation the JAX-RS codegen resolver uses, so MCP cannot drift from
 * it.
 *
 * <p>Route-level chains are resolved first, for the tool method against its declaring type; each
 * parameter's chains are then resolved as an override of that route-level baseline. Both axes —
 * canonicalizers and sanitizers — are resolved independently. An element whose merged view (the
 * declaring site, superclasses, and implemented interfaces) declares both the additive annotation
 * and its {@code @Skip*} counterpart is a compile error: the shared resolver rejects it, and the
 * conflict is reported at the method for a route-level conflict, or at the parameter for a
 * parameter-level conflict.
 *
 * <p>The resolved chains are emitted as normalized base {@code @Canonicalize}/{@code @Sanitize}
 * annotations on the generated carrier component; the carrier never carries {@code @Skip*}, and the
 * generated invocation-level literal is always {@code EffectiveInputPolicies.NONE}.
 */
final class McpInputPolicyResolver {

    private final CodegenContext ctx;

    /**
     * The shared invocation-policy adapter: it owns the hierarchy-merged view of an element (the
     * declaring site, superclasses bottom-up, then interfaces), meta-annotation recursion, and the
     * precedence between the additive and the skip annotation of each axis, so compile-time
     * derivation cannot drift from the reflective runtime's.
     */
    private final ElementInvocationPolicies policies;

    /**
     * Constructs a resolver bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    McpInputPolicyResolver(CodegenContext ctx) {
        this.ctx = ctx;
        this.policies = new ElementInvocationPolicies(ctx.elements(), ctx.types());
    }

    /**
     * Resolves the final effective canonicalizer and sanitizer chains for one tool parameter.
     *
     * @param declaringType the type declaring the tool method
     * @param method        the tool method
     * @param param         the parameter to resolve
     * @return the resolved chains, or empty when a conflicting policy/skip pair was reported
     */
    Optional<EffectivePolicies> resolve(TypeElement declaringType, ExecutableElement method, VariableElement param) {
        ElementPolicyChains route;
        try {
            route = policies.resolveRoute(method, declaringType);
        } catch (InvocationPolicyConflictException e) {
            ctx.diagnostics().error(method, e.getMessage());
            return Optional.empty();
        }

        ElementPolicyChains resolved;
        try {
            resolved = policies.resolveParameter(
                    param, method.getParameters().indexOf(param), method, declaringType, route);
        } catch (InvocationPolicyConflictException e) {
            ctx.diagnostics().error(param, e.getMessage());
            return Optional.empty();
        }

        return Optional.of(new EffectivePolicies(resolved.canonicalizers(), resolved.sanitizers()));
    }

    /**
     * The resolved, final effective input-policy chains for one parameter.
     *
     * @param canonicalizers the ordered canonicalizer chain, base (never {@code @Skip*})
     * @param sanitizers     the ordered sanitizer chain, base (never {@code @Skip*})
     */
    record EffectivePolicies(List<TypeMirror> canonicalizers, List<TypeMirror> sanitizers) {

        /**
         * Canonicalizes the record: collections are defensively copied.
         */
        EffectivePolicies {
            canonicalizers = List.copyOf(canonicalizers);
            sanitizers = List.copyOf(sanitizers);
        }
    }
}
