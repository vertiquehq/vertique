// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ExpressionParseException} and {@link ExpressionEvaluationException} are
 * rooted in {@link WorkflowDefinitionException} (and transitively in
 * {@link ConfigurationException}), so callers catching workflow definition failures receive
 * expression exceptions without a separate catch clause.
 */
class ExpressionExceptionHierarchyTest {

    @Test
    @DisplayName("ExpressionParseException is a WorkflowDefinitionException")
    void expressionParseExceptionExtendsWorkflowDefinitionException() {
        ExpressionParseException ex = new ExpressionParseException("parse failed", "state.x > 5");

        assertThat(ex).isInstanceOf(WorkflowDefinitionException.class);
    }

    @Test
    @DisplayName("ExpressionParseException is transitively a ConfigurationException")
    void expressionParseExceptionExtendsConfigurationException() {
        ExpressionParseException ex = new ExpressionParseException("parse failed", "state.x > 5");

        assertThat(ex).isInstanceOf(ConfigurationException.class);
    }

    @Test
    @DisplayName("ExpressionParseException(message, source, cause) preserves source and cause")
    void expressionParseExceptionWithCausePreservesSourceAndCause() {
        Throwable cause = new IllegalStateException("underlying");
        ExpressionParseException ex = new ExpressionParseException("parse failed", "state.y == 1", cause);

        assertThat(ex.source()).isEqualTo("state.y == 1");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex).isInstanceOf(WorkflowDefinitionException.class);
    }

    @Test
    @DisplayName("ExpressionEvaluationException is a WorkflowDefinitionException")
    void expressionEvaluationExceptionExtendsWorkflowDefinitionException() {
        ExpressionEvaluationException ex = new ExpressionEvaluationException("eval failed");

        assertThat(ex).isInstanceOf(WorkflowDefinitionException.class);
    }

    @Test
    @DisplayName("ExpressionEvaluationException is transitively a ConfigurationException")
    void expressionEvaluationExceptionExtendsConfigurationException() {
        ExpressionEvaluationException ex = new ExpressionEvaluationException("eval failed");

        assertThat(ex).isInstanceOf(ConfigurationException.class);
    }

    @Test
    @DisplayName("ExpressionEvaluationException(message, cause) preserves cause")
    void expressionEvaluationExceptionWithCausePreservesCause() {
        Throwable cause = new ArithmeticException("division by zero");
        ExpressionEvaluationException ex = new ExpressionEvaluationException("eval failed", cause);

        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex).isInstanceOf(WorkflowDefinitionException.class);
    }
}
