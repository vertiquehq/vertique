// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.validator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the human-readable output of
 * {@link WorkflowDefinitionViolations#formatted()}: grouping by definition,
 * ordering of document-level vs step-level violations, and route-index labeling.
 */
class WorkflowDefinitionViolationsFormattedTest {

    @Test
    @DisplayName("empty violations produce an empty string")
    void empty_producesEmptyString() {
        assertThat(new WorkflowDefinitionViolations(List.of()).formatted()).isEmpty();
    }

    @Test
    @DisplayName("single document-level violation is formatted correctly")
    void singleDocLevelViolation() {
        WorkflowDefinitionViolations violations = new WorkflowDefinitionViolations(List.of(new Violation(
                "my-wf", 2L, null, null, "DEFINITION_VERSION_INVALID", "`definitionVersion` must be >= 1, but was 0")));

        String output = violations.formatted();

        assertThat(output).contains("Workflow definition 'my-wf' v2 has 1 violation:");
        assertThat(output).contains("[DEFINITION_VERSION_INVALID]");
        assertThat(output).contains("`definitionVersion` must be >= 1, but was 0");
    }

    @Test
    @DisplayName("step-level violation includes 'step <id>' header")
    void stepLevelViolation_includesStepHeader() {
        WorkflowDefinitionViolations violations = new WorkflowDefinitionViolations(List.of(new Violation(
                "wf", 1L, "my-step", null, "STEP_NEXT_UNKNOWN", "step 'my-step' `next` 'ghost' does not resolve")));

        String output = violations.formatted();

        assertThat(output).contains("step 'my-step':");
        assertThat(output).contains("[STEP_NEXT_UNKNOWN]");
    }

    @Test
    @DisplayName("route-level violation includes 'route #N' suffix")
    void routeLevelViolation_includesRouteIndex() {
        WorkflowDefinitionViolations violations = new WorkflowDefinitionViolations(List.of(new Violation(
                "wf", 1L, "decide", 2, "DECISION_ROUTE_MIXED_PREDICATE", "route #2 declares both when and condition")));

        String output = violations.formatted();

        assertThat(output).contains("step 'decide' route #2:");
        assertThat(output).contains("[DECISION_ROUTE_MIXED_PREDICATE]");
    }

    @Test
    @DisplayName("document-level violations appear before step-level violations")
    void docLevelBeforeStepLevel() {
        List<Violation> list = List.of(
                new Violation("wf", 1L, "step-a", null, "STEP_NEXT_UNKNOWN", "step-a problem"),
                new Violation("wf", 1L, null, null, "DEFINITION_ID_BLANK", "id is blank"));
        WorkflowDefinitionViolations violations = new WorkflowDefinitionViolations(list);

        String output = violations.formatted();

        int idxDoc = output.indexOf("[DEFINITION_ID_BLANK]");
        int idxStep = output.indexOf("[STEP_NEXT_UNKNOWN]");
        assertThat(idxDoc)
                .as("document-level violation must appear before step-level")
                .isLessThan(idxStep);
    }

    @Test
    @DisplayName("multiple violations per step share the same step header")
    void multipleViolationsPerStep_shareStepHeader() {
        List<Violation> list = List.of(
                new Violation("wf", 1L, "manual-review", null, "DECISION_DEFAULT_MISSING", "no default"),
                new Violation(
                        "wf", 1L, "manual-review", null, "TASK_DECISION_APPLICATOR_UNKNOWN", "unknown applicator"));
        WorkflowDefinitionViolations violations = new WorkflowDefinitionViolations(list);

        String output = violations.formatted();

        // The step header must appear exactly once
        long count =
                output.lines().filter(l -> l.contains("step 'manual-review':")).count();
        assertThat(count).as("step header must appear only once").isEqualTo(1L);
        assertThat(output).contains("[DECISION_DEFAULT_MISSING]");
        assertThat(output).contains("[TASK_DECISION_APPLICATOR_UNKNOWN]");
    }

    @Nested
    @DisplayName("violation count noun")
    class ViolationCountNoun {

        @Test
        @DisplayName("uses plural when count > 1")
        void plural() {
            List<Violation> list = List.of(
                    new Violation("wf", 1L, null, null, "DEFINITION_ID_BLANK", "blank"),
                    new Violation("wf", 1L, null, null, "DEFINITION_VERSION_INVALID", "invalid"));
            assertThat(new WorkflowDefinitionViolations(list).formatted()).contains("2 violations");
        }

        @Test
        @DisplayName("uses singular when count == 1")
        void singular() {
            List<Violation> list = List.of(new Violation("wf", 1L, null, null, "DEFINITION_ID_BLANK", "blank"));
            assertThat(new WorkflowDefinitionViolations(list).formatted()).contains("1 violation:");
        }
    }
}
