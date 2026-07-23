// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

/**
 * Discriminator values for {@link WorkflowInstance#waitType()}.
 *
 * <p>Each constant's {@link #name()} is the string value stored in the {@code wait_type} column of
 * {@code workflow_instances}. Producer sites in the engine use {@code WaitType.X.name()} so that
 * valid wait-type values are defined in one place. The persistence layer remains string-typed.
 */
public enum WaitType {

    /** Instance is waiting for a named external signal. */
    SIGNAL,

    /** Instance is waiting for a timer to fire. */
    TIMER,

    /** Instance is waiting for a human task to be completed. */
    TASK,

    /**
     * Instance is parked at a fan-in {@link dev.vertique.workflow.plan.JoinNode} while its branch
     * tokens run (PRD-WF-002). {@code wait_key} carries the fork step id that owns the join;
     * {@code wait_aux_id} is unused.
     */
    JOIN
}
