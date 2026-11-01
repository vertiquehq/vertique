// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowCorrelationKeyException}: its message names the definition, the step and the reason, it is a
 * final {@link WorkflowDefinitionException} without a cause, and it is not a {@link WorkflowException}.
 */
class WorkflowCorrelationKeyExceptionTest {

    @Test
    @DisplayName("the message names the definition, the step and the reason only")
    void messageNamesDefinitionStepAndReasonOnly() {
        // Given an exception built from the definition orders, the step wait-payment and the reason blank
        WorkflowCorrelationKeyException exception =
                new WorkflowCorrelationKeyException("orders", "wait-payment", "blank");

        // When its message, text and class hierarchy are read
        String message = exception.getMessage();

        // Then the message and the text name all three
        assertThat(message).contains("orders", "wait-payment", "blank");
        assertThat(exception.toString()).contains("orders", "wait-payment", "blank");

        // And the class is final, a definition exception that is not a workflow exception, with no cause
        assertThat(Modifier.isFinal(WorkflowCorrelationKeyException.class.getModifiers()))
                .isTrue();
        assertThat(exception).isInstanceOf(WorkflowDefinitionException.class);
        assertThat(exception).isNotInstanceOf(WorkflowException.class);
        assertThat(exception.getCause()).isNull();
    }
}
