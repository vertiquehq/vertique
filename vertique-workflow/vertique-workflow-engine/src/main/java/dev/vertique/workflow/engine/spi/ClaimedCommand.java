// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.util.UUID;

/**
 * What a claim of a peeked command came to. Usually the claim itself, whose token is the proof of the claim that every
 * later write of the claim presents; a claim that finds the command's instance ended, or its attempts spent, settles
 * the command instead and returns that outcome.
 *
 * <p>INTERNAL: framework wiring between the executor and the command repository.
 *
 * @param commandId  the command's row id
 * @param workflowId the instance the command belongs to
 * @param kind       the command kind, for example {@code START}
 * @param token      the claim token the claim set on the command; {@code null} unless {@code result} is
 *                   {@link Result#CLAIMED}
 * @param attempts   the attempts the command has spent, this claim included
 * @param result     what the claim came to
 * @param leaseLapsed whether the command was {@code CLAIMED} under a lease that had expired when this claim took it,
 *                   that is, whether an earlier claim ended by a crash or a lease overrun
 */
public record ClaimedCommand(
        long commandId,
        WorkflowInstanceId workflowId,
        String kind,
        @Nullable UUID token,
        int attempts,
        Result result,
        boolean leaseLapsed) {

    /** What a claim of a peeked command came to. */
    public enum Result {
        /** The command is claimed: a segment runs for it. */
        CLAIMED,
        /** The command had spent its attempts and was dead-lettered; no segment runs. */
        DEAD_LETTERED,
        /** The command's instance had ended and the command was settled per the outcome table; no segment runs. */
        SETTLED_INSTANCE_ENDED
    }

    /**
     * Creates the outcome of a claim that took the command.
     *
     * @param commandId  the command's row id
     * @param workflowId the instance the command belongs to
     * @param kind       the command kind
     * @param token      the claim token
     * @param attempts   the attempts the command has spent, this claim included
     */
    public ClaimedCommand(long commandId, WorkflowInstanceId workflowId, String kind, UUID token, int attempts) {
        this(commandId, workflowId, kind, token, attempts, Result.CLAIMED, false);
    }

    /**
     * Whether the claim took the command, so that a segment runs for it.
     *
     * @return {@code true} for {@link Result#CLAIMED}
     */
    public boolean isClaim() {
        return result == Result.CLAIMED;
    }
}
