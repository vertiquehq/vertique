// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.parser;

import dev.vertique.workflow.exception.WorkflowDefinitionException;

/**
 * Unchecked exception thrown when a workflow definition document cannot be parsed.
 *
 * <p>Wraps Jackson {@code IOException} or {@code JacksonException} failures that arise during
 * YAML or JSON deserialization. The cause carries the full Jackson diagnostic (line number,
 * column, path) so callers can surface actionable error messages to document authors.
 *
 * <p>This class extends {@link WorkflowDefinitionException} so that any {@code catch} block for
 * {@code WorkflowDefinitionException} in the bootstrap or pipeline aggregates parse failures
 * alongside validation violations into a single structured
 * {@link dev.vertique.workflow.definition.validator.WorkflowDefinitionLoadException}.
 */
public class WorkflowDefinitionParseException extends WorkflowDefinitionException {

    /**
     * Constructs a parse exception with a descriptive message and the underlying cause.
     *
     * @param message human-readable description of the parse failure
     * @param cause   the original Jackson (or IO) exception; must not be {@code null}
     */
    public WorkflowDefinitionParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
