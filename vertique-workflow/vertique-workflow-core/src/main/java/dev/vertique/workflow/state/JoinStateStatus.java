// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

/**
 * Persisted lifecycle status of a {@link dev.vertique.workflow.plan.JoinNode}'s join-state row.
 *
 * <p>Stored as the enum's {@link #name()} in {@code workflow_join_states.status}. Once a join
 * leaves {@link #OPEN}, its decision is irreversible: late branch results are recorded as ignored
 * and never reopen the join (PRD-WF-002 FR-WF-PAR-052/055).
 */
public enum JoinStateStatus {

    /** Branches are running; the join policy has not yet been satisfied. */
    OPEN,

    /** The join completed successfully (policy was satisfied with a winning branch / all required). */
    COMPLETED,

    /** The join failed (e.g., a required branch failed under ALL_REQUIRED, or first-failure won). */
    FAILED
}
