// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;

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
 * @param migrationVersion     the definition version a {@code MIGRATION} command moves its instance to, or {@code null}
 *                             for every other kind
 * @param migrationScope       the scope of the definition a {@code MIGRATION} command moves its instance to, or
 *                             {@code null} when it names none (the scope the instance is pinned to) and for every other
 *                             kind
 * @param instanceScopeKey     the scope the instance was started in, or {@code null} when the head does not tell
 */
public record CommandHead(
        long commandId,
        WorkflowInstanceId workflowId,
        String kind,
        String definitionScopeKey,
        String definitionId,
        long definitionVersion,
        String planHash,
        boolean instanceEnded,
        @Nullable Long migrationVersion,
        @Nullable String migrationScope,
        @Nullable String instanceScopeKey) {

    /**
     * Creates a head that does not tell the scope the instance was started in.
     *
     * @param commandId          the command's row id
     * @param workflowId         the instance the command belongs to
     * @param kind               the command kind
     * @param definitionScopeKey the scope of the definition the instance is pinned to
     * @param definitionId       the definition id of the instance's pin
     * @param definitionVersion  the definition version of the instance's pin
     * @param planHash           the plan hash of the instance's pin
     * @param instanceEnded      whether the instance has ended
     * @param migrationVersion   the version a {@code MIGRATION} command moves its instance to, or {@code null}
     * @param migrationScope     the scope a {@code MIGRATION} command moves its instance to, or {@code null}
     */
    public CommandHead(
            long commandId,
            WorkflowInstanceId workflowId,
            String kind,
            String definitionScopeKey,
            String definitionId,
            long definitionVersion,
            String planHash,
            boolean instanceEnded,
            @Nullable Long migrationVersion,
            @Nullable String migrationScope) {
        this(
                commandId,
                workflowId,
                kind,
                definitionScopeKey,
                definitionId,
                definitionVersion,
                planHash,
                instanceEnded,
                migrationVersion,
                migrationScope,
                null);
    }

    /**
     * Creates a head that names no migration target.
     *
     * @param commandId          the command's row id
     * @param workflowId         the instance the command belongs to
     * @param kind               the command kind
     * @param definitionScopeKey the scope of the definition the instance is pinned to
     * @param definitionId       the definition id of the instance's pin
     * @param definitionVersion  the definition version of the instance's pin
     * @param planHash           the plan hash of the instance's pin
     * @param instanceEnded      whether the instance has ended
     */
    public CommandHead(
            long commandId,
            WorkflowInstanceId workflowId,
            String kind,
            String definitionScopeKey,
            String definitionId,
            long definitionVersion,
            String planHash,
            boolean instanceEnded) {
        this(
                commandId,
                workflowId,
                kind,
                definitionScopeKey,
                definitionId,
                definitionVersion,
                planHash,
                instanceEnded,
                null,
                null);
    }

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
