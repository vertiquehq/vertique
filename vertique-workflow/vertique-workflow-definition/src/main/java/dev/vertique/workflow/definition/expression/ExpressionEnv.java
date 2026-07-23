// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression;

import java.util.Set;

/**
 * Describes the compile-time environment available to an expression.
 *
 * <p>The workflow state is exposed to CEL as a {@code state} variable typed as
 * {@code map<string, dyn>} — at runtime, a Jackson {@code convertValue} of the state POJO
 * produces the concrete map. Named conditions are declared as boolean variables so expressions
 * that reference them type-check correctly; their runtime values are injected by the caller in the
 * {@code evalEnv} map passed to {@link ExpressionProfile#evaluate}.
 *
 * <p>Construction validates:
 * <ul>
 *   <li>{@code stateType} — non-null</li>
 *   <li>{@code namedConditionIds} — non-null (empty is valid)</li>
 *   <li>{@code exposedMetadataPaths} — non-null (empty is valid; reserved for future use)</li>
 * </ul>
 * Sets are copied defensively so caller mutation after construction does not affect the record.
 *
 * @param stateType           the workflow state class resolved by the validator; used by the
 *                            profile to name-check declared variables
 * @param namedConditionIds   names of named conditions declared in the document; each name is
 *                            exposed as a {@code bool} variable in the CEL environment
 * @param exposedMetadataPaths reserved for future use (v1 — always empty)
 */
public record ExpressionEnv(Class<?> stateType, Set<String> namedConditionIds, Set<String> exposedMetadataPaths) {

    /**
     * Compact canonical constructor — validates and defensively copies sets.
     *
     * @param stateType            non-null workflow state class
     * @param namedConditionIds    non-null set of named-condition identifiers
     * @param exposedMetadataPaths non-null set of exposed metadata paths (reserved; v1 always empty)
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public ExpressionEnv {
        if (stateType == null) {
            throw new IllegalArgumentException("stateType must be non-null");
        }
        if (namedConditionIds == null) {
            throw new IllegalArgumentException("namedConditionIds must be non-null");
        }
        if (exposedMetadataPaths == null) {
            throw new IllegalArgumentException("exposedMetadataPaths must be non-null");
        }
        // Defensive copies so caller mutations after construction have no effect.
        namedConditionIds = Set.copyOf(namedConditionIds);
        exposedMetadataPaths = Set.copyOf(exposedMetadataPaths);
    }
}
