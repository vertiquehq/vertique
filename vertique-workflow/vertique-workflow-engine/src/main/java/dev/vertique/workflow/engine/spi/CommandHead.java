// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;

/**
 * A command an executor could claim now, as peeked: the head of its instance's queue, available and not held back.
 *
 * <p>INTERNAL: framework wiring between the executor and the command repository. The pin fields name the plan the
 * executor makes resident before it claims the command.
 *
 * @param commandId            the command's row id
 * @param workflowId           the instance the command belongs to
 * @param kind                 the command kind, for example {@code START}
 * @param definitionScopeKey   the scope of the definition the instance is pinned to
 * @param definitionId         the definition id of the instance's pin
 * @param definitionVersion    the definition version of the instance's pin
 * @param planHash             the plan hash of the instance's pin
 * @param instanceEnded        whether the instance has ended, so that the claim settles the command without a plan
 */
public record CommandHead(
        long commandId,
        WorkflowInstanceId workflowId,
        String kind,
        String definitionScopeKey,
        String definitionId,
        long definitionVersion,
        String planHash,
        boolean instanceEnded) {

    /**
     * Creates a head whose instance has not ended.
     *
     * @param commandId          the command's row id
     * @param workflowId         the instance the command belongs to
     * @param kind               the command kind
     * @param definitionScopeKey the scope of the definition the instance is pinned to
     * @param definitionId       the definition id of the instance's pin
     * @param definitionVersion  the definition version of the instance's pin
     * @param planHash           the plan hash of the instance's pin
     */
    public CommandHead(
            long commandId,
            WorkflowInstanceId workflowId,
            String kind,
            String definitionScopeKey,
            String definitionId,
            long definitionVersion,
            String planHash) {
        this(commandId, workflowId, kind, definitionScopeKey, definitionId, definitionVersion, planHash, false);
    }
}
