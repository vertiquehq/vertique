// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when a workflow type name does not match any indexed class.
 *
 * <p>The message echoes the unknown name only when it is a valid name, so a hostile or corrupt value
 * never reaches a log line.
 */
public class WorkflowUnknownTypeNameException extends WorkflowDefinitionException {

    /**
     * Creates a new {@code WorkflowUnknownTypeNameException} with the given message.
     *
     * @param message description of the failure; names the unknown logical name when it is valid
     */
    public WorkflowUnknownTypeNameException(String message) {
        super(message);
    }
}
