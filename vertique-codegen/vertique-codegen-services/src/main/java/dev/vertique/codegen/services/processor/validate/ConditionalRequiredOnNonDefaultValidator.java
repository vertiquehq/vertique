// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.ServiceAnnotations;
import dev.vertique.codegen.services.processor.scan.ContractModel;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.TypeElement;

/**
 * Group-level validator that enforces FR-CG011-012: in any contract group with more than one
 * candidate <em>and</em> at least one unconditional default, every non-default candidate must
 * declare at least one {@code @ConditionalOnProperty} annotation.
 *
 * <p>When a group has a default (unconditional) implementation, all other candidates must be
 * gated behind a condition so the runtime selection logic can unambiguously pick either the
 * conditional override or the default. A non-conditional, non-default candidate would be
 * semantically equivalent to a second unconditional default — which
 * {@link MultipleUnconditionalImplValidator} already rejects. This validator is the explicit guard
 * for the most common "default + overrides" case, providing a clearer error message.
 *
 * <p>In practice, the two validators are complementary:
 * <ul>
 *   <li>{@link MultipleUnconditionalImplValidator} — fires when ≥2 unconditional exist.</li>
 *   <li>This validator — fires when a non-default candidate lacks {@code @ConditionalOnProperty}
 *       while at least one unconditional default is present.</li>
 * </ul>
 *
 * <p>Single-impl groups and groups with no unconditional default are always valid under this rule.
 */
public final class ConditionalRequiredOnNonDefaultValidator {

    private final CodegenContext ctx;

    /**
     * Constructs the validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ConditionalRequiredOnNonDefaultValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates that every non-default candidate in a multi-impl group with a default implementation
     * declares {@code @ConditionalOnProperty}.
     *
     * <p>Returns {@code true} when:
     * <ul>
     *   <li>the group has only one candidate (single-impl group — trivially valid), or</li>
     *   <li>the group has no unconditional default (all-conditional group — this rule does not
     *       apply), or</li>
     *   <li>every non-default candidate carries a conditional annotation.</li>
     * </ul>
     *
     * <p>Returns {@code false} if any non-default candidate lacks a conditional annotation, having
     * already emitted an {@code ERROR} diagnostic on each violating impl element.
     *
     * @param group the list of validated {@link ContractModel}s for a single contract type;
     *              must not be {@code null}; must contain at least one element
     * @return {@code true} if the group is valid; {@code false} if errors were emitted
     */
    public boolean validate(List<ContractModel> group) {
        if (group.size() <= 1) {
            return true;
        }

        // Pick the first unconditional candidate as the candidate default. If the group has zero
        // unconditional candidates, this rule does not apply (an all-conditional group is valid).
        // If it has more than one, MultipleUnconditionalImplValidator will already have errored on
        // each — but we still want to report the "needs @ConditionalOnProperty" message on every
        // non-conditional non-default candidate so the developer sees both diagnostics together.
        List<TypeElement> unconditionalCandidates = group.stream()
                .map(ContractModel::implType)
                .filter(impl -> !isConditional(impl))
                .collect(Collectors.toList());

        if (unconditionalCandidates.isEmpty()) {
            return true;
        }

        TypeElement contractType = group.get(0).contractType();
        String contractName = contractType.getSimpleName().toString();
        TypeElement defaultImpl = unconditionalCandidates.get(0);
        String defaultName = defaultImpl.getSimpleName().toString();

        // Every non-default candidate must declare @ConditionalOnProperty. By construction
        // (unconditionalCandidates contains all non-conditional impls), every entry in
        // unconditionalCandidates beyond index 0 is a non-default unconditional impl that needs
        // a condition.
        boolean valid = true;
        for (int i = 1; i < unconditionalCandidates.size(); i++) {
            TypeElement impl = unconditionalCandidates.get(i);
            ctx.diagnostics()
                    .error(
                            impl,
                            "Service contract '%s' has a default implementation '%s';"
                                    + " non-default implementation '%s' must declare"
                                    + " @ConditionalOnProperty to be selectable as an override.",
                            contractName,
                            defaultName,
                            impl.getSimpleName().toString());
            valid = false;
        }
        return valid;
    }

    // --- Internal helpers ---

    /**
     * Returns {@code true} if the given type element carries any {@code @ConditionalOnProperty}
     * or {@code @ConditionalOnProperties} annotation mirror.
     *
     * <p>Mirror-based lookup handles the {@code @Repeatable} container form correctly; unlike
     * {@code Element.getAnnotation()}, it does not return {@code null} when only the container
     * annotation is present.
     *
     * @param impl the implementation type element; must not be {@code null}
     * @return {@code true} if a conditional annotation is present; {@code false} otherwise
     */
    private boolean isConditional(TypeElement impl) {
        return AnnotationMirrors.isPresent(impl, ServiceAnnotations.CONDITIONAL_ON_PROPERTY)
                || AnnotationMirrors.isPresent(impl, ServiceAnnotations.CONDITIONAL_ON_PROPERTIES);
    }
}
