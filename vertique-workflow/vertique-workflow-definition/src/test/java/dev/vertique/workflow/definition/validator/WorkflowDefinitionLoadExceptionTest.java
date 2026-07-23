// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.validator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowDefinitionLoadException}: message content, violations accessor, and
 * basic exception hierarchy.
 */
class WorkflowDefinitionLoadExceptionTest {

    @Test
    @DisplayName("getMessage() returns violations.formatted()")
    void message_equalsViolationsFormatted() {
        List<Violation> list = List.of(
                new Violation("wf-id", 1L, null, null, "DEFINITION_ID_BLANK", "`definitionId` must not be blank"));
        WorkflowDefinitionViolations violations = new WorkflowDefinitionViolations(list);

        WorkflowDefinitionLoadException ex = new WorkflowDefinitionLoadException(violations);

        assertThat(ex.getMessage()).isEqualTo(violations.formatted());
    }

    @Test
    @DisplayName("violations() returns the same aggregate passed to the constructor")
    void violations_returnsSameAggregate() {
        WorkflowDefinitionViolations violations = new WorkflowDefinitionViolations(
                List.of(new Violation("wf-id", 1L, "step-a", null, "STEP_NEXT_UNKNOWN", "step-a next unknown")));

        WorkflowDefinitionLoadException ex = new WorkflowDefinitionLoadException(violations);

        assertThat(ex.violations()).isSameAs(violations);
    }

    @Test
    @DisplayName("extends WorkflowDefinitionException")
    void extendsWorkflowDefinitionException() {
        WorkflowDefinitionLoadException ex = new WorkflowDefinitionLoadException(new WorkflowDefinitionViolations(
                List.of(new Violation("x", 1L, null, null, "DEFINITION_ID_BLANK", "blank"))));

        assertThat(ex).isInstanceOf(dev.vertique.workflow.exception.WorkflowDefinitionException.class);
    }
}
