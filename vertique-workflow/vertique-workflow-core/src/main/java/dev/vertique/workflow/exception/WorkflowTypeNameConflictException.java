// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown at startup when two different classes claim one logical name within the same namespace, or
 * when a logical name equals the binary name of a different indexed class.
 */
public class WorkflowTypeNameConflictException extends WorkflowDefinitionException {

    /**
     * Creates a new {@code WorkflowTypeNameConflictException} with the given message.
     *
     * @param message description of the conflict; names the logical name and both binary class names
     */
    public WorkflowTypeNameConflictException(String message) {
        super(message);
    }
}
