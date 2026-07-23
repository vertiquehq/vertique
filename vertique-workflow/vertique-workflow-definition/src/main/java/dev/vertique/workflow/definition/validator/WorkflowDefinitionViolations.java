// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.validator;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Immutable aggregate of all {@link Violation} entries found during validation of a single
 * {@link dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument}.
 *
 * <p>The validator always returns a {@code WorkflowDefinitionViolations} instance — empty when
 * there are no problems. Callers check {@link #isEmpty()} and throw
 * {@link WorkflowDefinitionLoadException} when the aggregate is non-empty.
 *
 * <p>The {@link #formatted()} method produces a human-readable report grouped first by
 * {@code (definitionId, version)}, then by step (document-level violations first), then by route
 * index. This format is used as the exception message in {@link WorkflowDefinitionLoadException}.
 *
 * @param violations the accumulated violations; must not be null; the constructor stores an
 *     immutable defensive copy
 */
public record WorkflowDefinitionViolations(List<Violation> violations) {

    /**
     * Compact constructor: validates that the violations list is non-null and stores an immutable
     * copy.
     *
     * @throws NullPointerException if {@code violations} is null
     */
    public WorkflowDefinitionViolations {
        Objects.requireNonNull(violations, "violations");
        violations = List.copyOf(violations);
    }

    // --- Query methods ---

    /**
     * Returns {@code true} if there are no violations.
     *
     * @return {@code true} iff the violations list is empty
     */
    public boolean isEmpty() {
        return violations.isEmpty();
    }

    /**
     * Returns the number of violations accumulated.
     *
     * @return non-negative count
     */
    public int size() {
        return violations.size();
    }

    /**
     * Returns an unmodifiable copy of the accumulated violations.
     *
     * @return immutable violation list; never null
     */
    public List<Violation> toList() {
        return violations;
    }

    // --- Formatting ---

    /**
     * Returns a human-readable multi-line report of all violations, grouped by
     * {@code (definitionId, version)} and then by step. Document-level violations (null stepId)
     * appear before step-level violations; route-level violations include the route index.
     *
     * <p>Example output:
     * <pre>{@code
     * Workflow definition 'order-fulfillment' v1 has 3 violations:
     *
     *   [DEFINITION_ID_BLANK] `definitionId` must not be blank
     *
     *   step 'manual-review':
     *     [DECISION_DEFAULT_MISSING] decision step 'manual-review' has no 'default' route
     *     [TASK_DECISION_APPLICATOR_UNKNOWN] decision 'approve' references unknown applicator 'rview.applyApprove'; ...
     *
     *   step 'cancel-order' route #1:
     *     [DECISION_ROUTE_MIXED_PREDICATE] route declares both 'when' and 'condition' — exactly one must be set
     * }</pre>
     *
     * @return formatted multi-line string; never null; may be empty if there are no violations
     */
    public String formatted() {
        if (violations.isEmpty()) {
            return "";
        }

        // Group by (definitionId, version), then step, then routeIndex. Use TreeMap for stable
        // ordering; violations are produced in document order so grouping preserves that ordering
        // within each group.
        Map<String, List<Violation>> byDefinition = violations.stream()
                .collect(Collectors.groupingBy(
                        v -> "'" + v.definitionId() + "' v" + v.definitionVersion(),
                        TreeMap::new,
                        Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Violation>> entry : byDefinition.entrySet()) {
            List<Violation> group = entry.getValue();
            String count = group.size() == 1 ? "1 violation" : group.size() + " violations";
            sb.append("Workflow definition ")
                    .append(entry.getKey())
                    .append(" has ")
                    .append(count)
                    .append(":\n");

            // Partition by null vs non-null stepId; render doc-level violations first
            List<Violation> docLevel =
                    group.stream().filter(v -> v.stepId() == null).toList();
            List<Violation> stepLevel =
                    group.stream().filter(v -> v.stepId() != null).toList();

            for (Violation v : docLevel) {
                sb.append("\n  [")
                        .append(v.code())
                        .append("] ")
                        .append(v.message())
                        .append("\n");
            }

            // Group step-level violations by (stepId, routeIndex)
            Map<String, List<Violation>> byStep = stepLevel.stream()
                    .collect(Collectors.groupingBy(
                            v -> v.stepId() + ":" + v.routeIndex(),
                            // preserve encounter order
                            Collectors.toList()));

            // Re-iterate stepLevel to preserve declaration order across stepIds and routeIndexes
            String lastKey = null;
            for (Violation v : stepLevel) {
                String key = v.stepId() + ":" + v.routeIndex();
                if (!key.equals(lastKey)) {
                    lastKey = key;
                    sb.append("\n");
                    if (v.routeIndex() != null) {
                        sb.append("  step '")
                                .append(v.stepId())
                                .append("' route #")
                                .append(v.routeIndex())
                                .append(":\n");
                    } else {
                        sb.append("  step '").append(v.stepId()).append("':\n");
                    }
                }
                sb.append("    [")
                        .append(v.code())
                        .append("] ")
                        .append(v.message())
                        .append("\n");
            }
        }
        return sb.toString();
    }
}
