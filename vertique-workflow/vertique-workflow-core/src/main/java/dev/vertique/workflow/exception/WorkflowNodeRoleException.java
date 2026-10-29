// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Refuses an operation that needs a workflow build on a recording-only node.
 *
 * <p>A node configured with {@code workflow.executor.enabled=false} records and serves queries but loads no workflow
 * class and builds no plan, so document load, publication, reinstatement, {@code WorkflowRegistry.load} and the
 * payload-schema operations are refused on it. The message names the refused operation and the setting, never any
 * document content.
 */
public class WorkflowNodeRoleException extends WorkflowConfigurationException {

    /**
     * Creates the refusal for an operation.
     *
     * @param operation the refused operation, for example {@code "publish"}
     */
    public WorkflowNodeRoleException(String operation) {
        super("Operation '" + operation + "' needs a workflow build and is refused on a recording-only node"
                + " (workflow.executor.enabled=false); run it on an executor node");
    }
}
