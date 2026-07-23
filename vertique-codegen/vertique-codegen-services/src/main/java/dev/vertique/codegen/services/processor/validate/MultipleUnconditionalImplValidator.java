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
 * Group-level validator that enforces FR-CG011-012 and FR-CG011-016: in any contract group with
 * more than one candidate implementation, <strong>at most one</strong> may lack
 * {@code @ConditionalOnProperty}.
 *
 * <p>If two or more candidates are unconditional (no {@code @ConditionalOnProperty} or
 * {@code @ConditionalOnProperties}), a compile-time {@code ERROR} is emitted on each unconditional
 * impl naming all of the other unconditional impls in the message. This gives the developer a
 * single-pass view of the full conflict without requiring incremental fixes.
 *
 * <p>Single-impl groups are always valid — a lone unconditional implementation is the normal case
 * (it is the default / always-active path). This validator only fires for groups with two or more
 * candidates.
 */
public final class MultipleUnconditionalImplValidator {

    private final CodegenContext ctx;

    /**
     * Constructs the validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public MultipleUnconditionalImplValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates that at most one candidate in the group lacks {@code @ConditionalOnProperty}.
     *
     * <p>Returns {@code true} if the group is valid (zero or one unconditional candidates).
     * Returns {@code false} if two or more are unconditional, having already emitted an
     * {@code ERROR} diagnostic on each violating impl element.
     *
     * @param group the list of validated {@link ContractModel}s for a single contract type;
     *              must not be {@code null}; must contain at least one element
     * @return {@code true} if the group is valid; {@code false} if errors were emitted
     */
    public boolean validate(List<ContractModel> group) {
        if (group.size() <= 1) {
            // Single-impl groups are always valid for this rule
            return true;
        }

        List<TypeElement> unconditional = group.stream()
                .map(ContractModel::implType)
                .filter(impl -> !isConditional(impl))
                .collect(Collectors.toList());

        if (unconditional.size() <= 1) {
            return true;
        }

        // Two or more unconditional impls — emit an error on each one
        TypeElement contractType = group.get(0).contractType();
        String contractName = contractType.getSimpleName().toString();
        String allUnconditionalNames =
                unconditional.stream().map(te -> te.getSimpleName().toString()).collect(Collectors.joining(", "));

        for (TypeElement impl : unconditional) {
            ctx.diagnostics()
                    .error(
                            impl,
                            "Service contract '%s' has multiple unconditional implementations: %s."
                                    + " At most one default implementation is allowed;"
                                    + " annotate the others with @ConditionalOnProperty.",
                            contractName,
                            allUnconditionalNames);
        }

        return false;
    }

    // --- Internal helpers ---

    /**
     * Returns {@code true} if the given type element carries any {@code @ConditionalOnProperty}
     * or {@code @ConditionalOnProperties} annotation mirror.
     *
     * <p>Mirror-based lookup is used (not {@code Element.getAnnotation()}) to correctly handle
     * the {@code @Repeatable} container form, which {@code getAnnotation(ConditionalOnProperty.class)}
     * returns {@code null} for when only the container is present.
     *
     * @param impl the implementation type element; must not be {@code null}
     * @return {@code true} if a conditional annotation is present; {@code false} otherwise
     */
    private boolean isConditional(TypeElement impl) {
        return AnnotationMirrors.isPresent(impl, ServiceAnnotations.CONDITIONAL_ON_PROPERTY)
                || AnnotationMirrors.isPresent(impl, ServiceAnnotations.CONDITIONAL_ON_PROPERTIES);
    }
}
