// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when a workflow definition id is not registered in the {@code WorkflowRegistry}.
 *
 * <p>This exception is distinct from {@link WorkflowVersionPinUnavailableException}: it is thrown
 * when no version of the definition exists at all, rather than when a specific pinned version is
 * unavailable.
 */
public class WorkflowDefinitionMissingException extends WorkflowNotFoundException {

    /**
     * Creates a new {@code WorkflowDefinitionMissingException} for the given definition id.
     *
     * @param definitionId the id of the definition that was not found
     */
    public WorkflowDefinitionMissingException(String definitionId) {
        super("Workflow definition '" + definitionId + "' is not registered.");
    }
}
