// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression;

import dev.vertique.workflow.exception.WorkflowDefinitionException;

/**
 * Thrown by {@link ExpressionProfile#compile(String, ExpressionEnv)} when the source expression
 * fails to parse, type-check, canonicalize, or exceeds a declared profile bound.
 *
 * <p>The {@link #source()} accessor returns the original expression text so callers can include it
 * in structured error messages or {@link dev.vertique.workflow.definition.validator.Violation}
 * entries with accurate file/line context.
 *
 * <p>Extends {@link WorkflowDefinitionException} so that callers catching workflow definition
 * failures do not need a separate catch for expression parsing errors.
 */
public class ExpressionParseException extends WorkflowDefinitionException {

    private final String source;

    /**
     * Constructs an exception with the given message and the original source text.
     *
     * @param message human-readable description of the parse failure; non-null
     * @param source  the original expression text that failed to compile; non-null
     */
    public ExpressionParseException(String message, String source) {
        super(message);
        this.source = source;
    }

    /**
     * Constructs an exception with the given message, source text, and underlying cause.
     *
     * @param message human-readable description of the parse failure; non-null
     * @param source  the original expression text that failed to compile; non-null
     * @param cause   the underlying CEL or validation exception; may be {@code null}
     */
    public ExpressionParseException(String message, String source, Throwable cause) {
        super(message, cause);
        this.source = source;
    }

    /**
     * Returns the original expression text that caused the parse failure.
     *
     * @return the source expression string; never {@code null}
     */
    public String source() {
        return source;
    }
}
