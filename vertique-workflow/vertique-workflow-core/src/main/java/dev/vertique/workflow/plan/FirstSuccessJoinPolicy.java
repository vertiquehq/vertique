// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

/**
 * {@link JoinPolicy} that advances the workflow on the first branch to reach {@code COMPLETED}.
 *
 * <p>Sibling branches that are still running, waiting, or pending retry are marked
 * {@code SUPERSEDED}; their open timers and human tasks are cancelled (PRD-WF-002
 * FR-WF-PAR-049/053). Late results from superseded branches are recorded as ignored without
 * mutating workflow state (FR-WF-PAR-052).
 *
 * <p>The plan validator restricts this policy to race-safe branch groups: every branch must
 * declare {@link RaceSafety#CANCEL_SAFE} or {@link RaceSafety#IGNORE_LATE_RESULT_SAFE} and may not
 * contain compensable steps (FR-WF-PAR-011/054).
 *
 * <p>Marker record — V1 carries no policy-specific state.
 */
public record FirstSuccessJoinPolicy() implements JoinPolicy {

    /** Singleton instance. */
    public static final FirstSuccessJoinPolicy INSTANCE = new FirstSuccessJoinPolicy();
}
