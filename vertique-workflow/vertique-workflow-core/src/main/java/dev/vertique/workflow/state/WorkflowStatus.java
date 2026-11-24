// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

/**
 * Lifecycle status of a {@link WorkflowInstance}.
 *
 * <p>The engine transitions between statuses as follows:
 * <ul>
 *   <li>{@link #RUNNING} — active; engine is processing transitions</li>
 *   <li>{@link #WAITING} — suspended waiting for an external signal, timer, or human task</li>
 *   <li>{@link #COMPLETED} — terminal; reached a {@code CompleteNode}</li>
 *   <li>{@link #FAILED} — reached a {@code FailNode} with no compensable steps; not terminal, because an
 *       operator retry resumes it</li>
 *   <li>{@link #COMPENSATING} — compensation flow in progress after a failure</li>
 *   <li>{@link #COMPENSATED} — terminal; all compensation intents recorded</li>
 *   <li>{@link #CANCELLED} — terminal; cancelled via management API</li>
 *   <li>{@link #EXPIRED} — terminal; reserved: the engine never sets it, though it treats it as ended</li>
 *   <li>{@link #PENDING} — accepted but not yet initialized; not terminal</li>
 * </ul>
 */
public enum WorkflowStatus {
    /** The workflow is actively executing transitions. */
    RUNNING,

    /** The workflow is suspended waiting for a signal, timer, or human task. */
    WAITING,

    /** Terminal: the workflow reached a {@code CompleteNode} successfully. */
    COMPLETED,

    /** The workflow failed with no compensable steps to roll back. Not terminal: an operator retry resumes it. */
    FAILED,

    /** The compensation flow is in progress following a failure. */
    COMPENSATING,

    /** Terminal: all compensation intents have been recorded and the workflow is fully compensated. */
    COMPENSATED,

    /** Terminal: the workflow was cancelled via a management API call. */
    CANCELLED,

    /** Terminal, but reserved: the engine does not set this status, though it treats it as ended. */
    EXPIRED,

    /**
     * The instance row exists but has not been initialized: it has no state document and no
     * current step yet. Not terminal; it counts as live for definition retirement and business-key
     * uniqueness.
     */
    PENDING
}
