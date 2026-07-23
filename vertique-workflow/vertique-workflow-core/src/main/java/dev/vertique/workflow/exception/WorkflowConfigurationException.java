// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Workflow semantic root for configuration/contract failures (invalid definition, proxy contract,
 * deployment-state invariant).
 *
 * <p>Thrown when the workflow engine detects a misconfigured or internally inconsistent workflow
 * definition, an unresolvable proxy contract, or a deployment-state invariant violation. Extends
 * {@link ConfigurationException} so framework-level configuration handlers recognise workflow
 * configuration problems without needing workflow-specific imports.
 */
public class WorkflowConfigurationException extends ConfigurationException {

    /**
     * Creates a new {@code WorkflowConfigurationException} with the given message.
     *
     * @param message description of the configuration or contract failure
     */
    public WorkflowConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code WorkflowConfigurationException} with the given message and underlying
     * cause.
     *
     * @param message description of the configuration or contract failure
     * @param cause   the underlying exception that caused this failure
     */
    public WorkflowConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
