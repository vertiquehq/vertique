// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.stubs;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the exact source text {@link dev.vertique.codegen.jaxrs.processor.emit.ExecutionPlanEmitter}
 * emits for a {@code POL{n}} or {@code ROUTE_POL} {@code EffectiveInputPolicies} constant
 * initializer, from the simple class names of the resolved canonicalizer/sanitizer chain.
 *
 * <p>Mirrors {@code ExecutionPlanEmitter.buildPoliciesInitializer}/{@code
 * buildRoutePoliciesConstant} (both delegate to the same {@code buildClassList} shape): {@code
 * EffectiveInputPolicies.NONE} when both chains are empty, otherwise {@code new
 * EffectiveInputPolicies(List.of(Canon1.class, ...), List.of(Sanit1.class, ...))} using simple
 * names (JavaPoet's {@code $T} renders the imported simple name in the emitted source, exactly as
 * {@code InputPolicyParityTest} already asserts for individual class literals).
 *
 * <p>Both {@code InputPolicyParityTest} (T016) and {@code JaxRsInvocationPolicyMatrixTest} (T018,
 * issue #379) assert generated {@code POL{n}}/{@code ROUTE_POL} content through this single
 * formatter so the literal shape is never restated as a second literal that could silently drift
 * from the emitter.
 */
public final class PolicyLiteralAssertions {

    private PolicyLiteralAssertions() {}

    /**
     * The literal {@code EffectiveInputPolicies.NONE} snippet emitted when both chains are empty.
     *
     * @return {@code "EffectiveInputPolicies.NONE"}
     */
    public static String none() {
        return "EffectiveInputPolicies.NONE";
    }

    /**
     * The {@code Simple.class} literal snippet for one resolved chain entry.
     *
     * @param simpleName the resolved canonicalizer/sanitizer's simple class name
     * @return {@code "Simple.class"}
     */
    public static String classLiteral(String simpleName) {
        return simpleName + ".class";
    }

    /**
     * The full {@code POL{n}}/{@code ROUTE_POL} initializer snippet for the given resolved chains,
     * exactly as {@code ExecutionPlanEmitter} emits it.
     *
     * @param canonicalizerSimpleNames the resolved canonicalizer chain's simple class names, in
     *                                 order; empty when the canonicalize axis resolved to {@code []}
     * @param sanitizerSimpleNames    the resolved sanitizer chain's simple class names, in order;
     *                                empty when the sanitize axis resolved to {@code []}
     * @return {@code "EffectiveInputPolicies.NONE"} when both chains are empty, otherwise {@code
     *         "new EffectiveInputPolicies(List.of(...), List.of(...))"}
     */
    public static String effectiveInputPolicies(
            List<String> canonicalizerSimpleNames, List<String> sanitizerSimpleNames) {
        if (canonicalizerSimpleNames.isEmpty() && sanitizerSimpleNames.isEmpty()) {
            return none();
        }
        return "new EffectiveInputPolicies(" + classList(canonicalizerSimpleNames) + ", "
                + classList(sanitizerSimpleNames) + ")";
    }

    private static String classList(List<String> simpleNames) {
        return "List.of("
                + simpleNames.stream()
                        .map(PolicyLiteralAssertions::classLiteral)
                        .collect(Collectors.joining(", ")) + ")";
    }
}
