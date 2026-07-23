// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression;

import dev.vertique.workflow.exception.WorkflowDefinitionException;

/**
 * Thrown by {@link ExpressionProfile#evaluate(CompiledExpression, java.util.Map)} when the
 * compiled expression fails at evaluation time (e.g., runtime arithmetic error, iteration limit
 * exceeded, or type coercion failure).
 *
 * <p>Parse-time failures throw {@link ExpressionParseException} instead; this exception covers
 * only runtime evaluation errors that cannot be detected at compile time.
 *
 * <p>Extends {@link WorkflowDefinitionException} so that callers catching workflow definition
 * failures do not need a separate catch for expression evaluation errors.
 */
public class ExpressionEvaluationException extends WorkflowDefinitionException {

    /**
     * Constructs an exception with the given message.
     *
     * @param message human-readable description of the evaluation failure; non-null
     */
    public ExpressionEvaluationException(String message) {
        super(message);
    }

    /**
     * Constructs an exception with the given message and underlying cause.
     *
     * @param message human-readable description of the evaluation failure; non-null
     * @param cause   the underlying CEL or runtime exception; may be {@code null}
     */
    public ExpressionEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
