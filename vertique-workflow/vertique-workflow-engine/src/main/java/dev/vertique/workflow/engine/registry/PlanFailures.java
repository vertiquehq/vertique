// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.registry;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;

/**
 * INTERNAL: classifies the failures of loading a plan.
 *
 * <p>A failure is deterministic when loading again can only fail the same way until the stored row changes: the
 * document does not compile, a recorded name no class resolves, or the rebuilt plan hash differs from the stored one.
 * Those are {@link WorkflowDefinitionException}s, and the plan cache remembers them. An absent row, a failure to read
 * the row, a timeout, a failure that a validator contributed by an application throws without being a definition
 * refusal, and every {@link Error} are never remembered.
 */
public final class PlanFailures {

    private PlanFailures() {}

    /**
     * Whether the plan cache remembers a load that failed with the error.
     *
     * @param error the failure of a load
     * @return {@code true} only for a {@link WorkflowDefinitionException}
     */
    public static boolean isDeterministic(Throwable error) {
        return error instanceof WorkflowDefinitionException;
    }

    /**
     * Whether the failure means the plan cannot run on this node now, which the executor answers by deferring the
     * command, as opposed to a failure that says nothing about the plan, such as an unreadable row.
     *
     * @param error the failure of a load
     * @return {@code true} for an absent plan and for a deterministic failure
     */
    public static boolean isNotRunnable(Throwable error) {
        // WorkflowDefinitionNotFoundException is a WorkflowVersionPinUnavailableException
        return isDeterministic(error) || error instanceof WorkflowVersionPinUnavailableException;
    }
}
