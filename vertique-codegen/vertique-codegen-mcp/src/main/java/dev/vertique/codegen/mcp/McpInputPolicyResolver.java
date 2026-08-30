// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Resolves each {@code @McpTool} parameter's final REST-effective input-policy chain at compile
 * time, mirroring {@code ParameterExtractor.resolveParamPolicies}'s precedence exactly.
 *
 * <p>MCP owns this derivation because {@code vertique-input-processing} publishes
 * {@link dev.vertique.input.processing.EffectiveInputPolicies} but no annotation→policy resolver —
 * REST reflective, REST codegen, and WebSocket each derive their own transport-frozen semantics. This
 * type is MCP's own, deliberately duplicated rather than shared (§4.6).
 *
 * <p>The precedence, applied independently for canonicalizers and sanitizers:
 *
 * <ol>
 *   <li>Method-level {@code @Skip*} → empty chain (opt-out).</li>
 *   <li>Method-level {@code @Canonicalize}/{@code @Sanitize} → the method's declared chain.</li>
 *   <li>Declaring-type-level {@code @Skip*} → empty chain.</li>
 *   <li>Declaring-type-level {@code @Canonicalize}/{@code @Sanitize} → the type's declared chain.</li>
 *   <li>None of the above → empty chain (the "route-level" baseline).</li>
 * </ol>
 *
 * <p>The parameter itself then overrides that baseline the same way: a parameter-level
 * {@code @Skip*} clears the chain, a parameter-level {@code @Canonicalize}/{@code @Sanitize}
 * replaces it, and otherwise the route-level baseline applies unchanged. An element carrying both an
 * additive annotation and its {@code @Skip*} counterpart is a compile error.
 *
 * <p>The resolved chains are emitted as normalized base {@code @Canonicalize}/{@code @Sanitize}
 * annotations on the generated carrier component; the carrier never carries {@code @Skip*}, and the
 * generated invocation-level literal is always {@code EffectiveInputPolicies.NONE}.
 */
final class McpInputPolicyResolver {

    private static final String CANONICALIZE_FQN = "dev.vertique.core.sanitization.Canonicalize";
    private static final String SANITIZE_FQN = "dev.vertique.core.sanitization.Sanitize";
    private static final String SKIP_CANONICALIZATION_FQN = "dev.vertique.core.sanitization.SkipCanonicalization";
    private static final String SKIP_SANITIZATION_FQN = "dev.vertique.core.sanitization.SkipSanitization";

    private final CodegenContext ctx;

    /**
     * Constructs a resolver bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    McpInputPolicyResolver(CodegenContext ctx) {
        this.ctx = ctx;
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
        if (hasConflict(method, CANONICALIZE_FQN, SKIP_CANONICALIZATION_FQN)
                || hasConflict(declaringType, CANONICALIZE_FQN, SKIP_CANONICALIZATION_FQN)
                || hasConflict(param, CANONICALIZE_FQN, SKIP_CANONICALIZATION_FQN)) {
            return Optional.empty();
        }
        if (hasConflict(method, SANITIZE_FQN, SKIP_SANITIZATION_FQN)
                || hasConflict(declaringType, SANITIZE_FQN, SKIP_SANITIZATION_FQN)
                || hasConflict(param, SANITIZE_FQN, SKIP_SANITIZATION_FQN)) {
            return Optional.empty();
        }

        List<TypeMirror> routeCanonicalizers =
                resolveRouteChain(method, declaringType, CANONICALIZE_FQN, SKIP_CANONICALIZATION_FQN);
        List<TypeMirror> routeSanitizers =
                resolveRouteChain(method, declaringType, SANITIZE_FQN, SKIP_SANITIZATION_FQN);
        List<TypeMirror> paramCanonicalizers =
                resolveParamChain(param, CANONICALIZE_FQN, SKIP_CANONICALIZATION_FQN, routeCanonicalizers);
        List<TypeMirror> paramSanitizers =
                resolveParamChain(param, SANITIZE_FQN, SKIP_SANITIZATION_FQN, routeSanitizers);

        return Optional.of(new EffectivePolicies(paramCanonicalizers, paramSanitizers));
    }

    // --- Conflict detection ---

    /**
     * Reports a compile error when {@code element} carries both the additive annotation and its
     * {@code @Skip*} counterpart, which is a self-contradictory declaration.
     */
    private boolean hasConflict(Element element, String additiveFqn, String skipFqn) {
        boolean additive = AnnotationMirrors.isPresent(element, additiveFqn);
        boolean skip = AnnotationMirrors.isPresent(element, skipFqn);
        if (additive && skip) {
            ctx.diagnostics()
                    .error(
                            element,
                            "%s conflicts with %s on %s: an element cannot both declare a policy chain and skip it",
                            simpleAnnotationName(additiveFqn),
                            simpleAnnotationName(skipFqn),
                            element.getSimpleName());
            return true;
        }
        return false;
    }

    private static String simpleAnnotationName(String fqn) {
        return "@" + fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    // --- Route-level (method-over-type) chain resolution ---

    /**
     * Resolves the effective route-level chain — method over declaring type — exactly as
     * {@code EffectiveJaxRsContractResolver.resolveRouteChain} does for REST codegen.
     */
    private List<TypeMirror> resolveRouteChain(
            ExecutableElement method, TypeElement declaringType, String additiveFqn, String skipFqn) {
        if (AnnotationMirrors.isPresent(method, skipFqn)) {
            return List.of();
        }
        Optional<AnnotationMirror> methodAdditive = AnnotationMirrors.findByFqn(method, additiveFqn);
        if (methodAdditive.isPresent()) {
            return readClasses(methodAdditive.get());
        }
        if (AnnotationMirrors.isPresent(declaringType, skipFqn)) {
            return List.of();
        }
        Optional<AnnotationMirror> typeAdditive = AnnotationMirrors.findByFqn(declaringType, additiveFqn);
        return typeAdditive.map(this::readClasses).orElseGet(List::of);
    }

    // --- Parameter-level override ---

    /**
     * Resolves the effective per-parameter chain, starting from the route-level baseline and
     * applying the parameter's own override — exactly as
     * {@code ParameterExtractor.resolveParamPolicies} does at the request boundary.
     */
    private List<TypeMirror> resolveParamChain(
            VariableElement param, String additiveFqn, String skipFqn, List<TypeMirror> routeChain) {
        if (AnnotationMirrors.isPresent(param, skipFqn)) {
            return List.of();
        }
        Optional<AnnotationMirror> paramAdditive = AnnotationMirrors.findByFqn(param, additiveFqn);
        return paramAdditive.map(this::readClasses).orElse(routeChain);
    }

    /**
     * Reads the {@code value()} {@code Class[]} attribute of a {@code @Canonicalize} or
     * {@code @Sanitize} mirror as an ordered list of type mirrors.
     */
    private List<TypeMirror> readClasses(AnnotationMirror mirror) {
        List<TypeMirror> result = new ArrayList<>();
        for (AnnotationValue value : ctx.annotations().attributeArray(mirror, "value")) {
            if (value.getValue() instanceof TypeMirror type) {
                result.add(type);
            }
        }
        return List.copyOf(result);
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
