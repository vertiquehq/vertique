// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when a workflow definition fails plan validation at registration time, or when the engine
 * encounters a structural invariant violation at runtime (e.g. a malformed history payload that
 * cannot be safely decoded for compensation).
 *
 * <p>Common causes include:
 * <ul>
 *   <li>The definition's {@code define(WorkflowBuilder)} method never calls {@code wf.init(...)},
 *       or calls it more than once</li>
 *   <li>The plan contains two {@code WaitSignalNode}s with the same {@code signalName}</li>
 *   <li>The plan references a step id that does not exist</li>
 *   <li>A required history payload cannot be decoded for compensation matching</li>
 * </ul>
 */
public class WorkflowDefinitionException extends WorkflowConfigurationException {

    /**
     * Creates a new {@code WorkflowDefinitionException} with the given message.
     *
     * @param message description of the validation failure; should identify the definition id and
     *     the specific problem
     */
    public WorkflowDefinitionException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code WorkflowDefinitionException} with the given message and cause.
     *
     * @param message description of the validation failure; should identify the affected entry and
     *     the specific problem
     * @param cause the underlying exception that triggered this failure
     */
    public WorkflowDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
