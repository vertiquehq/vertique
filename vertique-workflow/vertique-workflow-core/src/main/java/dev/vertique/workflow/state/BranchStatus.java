// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

/**
 * Persisted execution status of a fork-group branch.
 *
 * <p>Defined per PRD-WF-002 §A.4.2. Mapped to the {@code workflow_branch_tokens.status} column as
 * the enum's {@link #name() name}. The instance-level {@link WorkflowStatus} is unrelated:
 * branches and instances each own their own concurrency token.
 */
public enum BranchStatus {

    /** A branch transition is actively being applied or is eligible to continue immediately. */
    RUNNING,

    /** A branch is durably waiting on a signal, task, or timer. */
    WAITING,

    /** A branch reached its terminal success step. */
    COMPLETED,

    /** A branch reached terminal failure and is not retry-eligible. */
    FAILED,

    /**
     * A branch failed with a retryable orchestration error and has {@code next_retry_at} set; the
     * recovery loop will resume it once the timestamp is in the past.
     */
    RETRY_SCHEDULED,

    /** A branch was cancelled by workflow/group cancellation (e.g., operator action). */
    CANCELLED,

    /**
     * A branch lost a race join after the join decision was made. Distinct from
     * {@link #CANCELLED}; reserved for FIRST_SUCCESS / FIRST_FAILURE losing branches.
     */
    SUPERSEDED,

    /** A branch expired through a branch timer or deadline. */
    EXPIRED,

    /** A branch's compensation pipeline is in progress. */
    COMPENSATING,

    /** A branch's compensation pipeline completed. */
    COMPENSATED
}
