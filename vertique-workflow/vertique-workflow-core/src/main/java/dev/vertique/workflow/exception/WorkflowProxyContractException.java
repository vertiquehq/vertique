// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when a workflow contract interface fails validation during proxy creation by
 * {@code WorkflowClientFactory}.
 *
 * <p>This exception is thrown at proxy-creation time (not at invocation time), so contract
 * violations are detected eagerly at application startup.
 *
 * <p>Common causes include:
 * <ul>
 *   <li>Missing {@code @WorkflowContract} annotation</li>
 *   <li>Referenced definition id or version not registered</li>
 *   <li>Wrong return type on a {@code @WorkflowStart} or {@code @WorkflowSignal} method</li>
 *   <li>Missing idempotency key or dedup key source</li>
 *   <li>Signal name not in the plan</li>
 *   <li>Duplicate signal name across two methods</li>
 *   <li>Conflicting annotations on a single method</li>
 * </ul>
 */
public class WorkflowProxyContractException extends WorkflowConfigurationException {

    /**
     * Creates a new {@code WorkflowProxyContractException} with the given message.
     *
     * @param message description of the contract violation; should identify the contract interface
     *     and the offending method
     */
    public WorkflowProxyContractException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code WorkflowProxyContractException} with the given message and cause.
     *
     * @param message description of the contract violation
     * @param cause the underlying exception that triggered this validation failure
     */
    public WorkflowProxyContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
