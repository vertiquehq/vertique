// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

/**
 * {@link JoinPolicy} that takes the failure route on the first branch to reach a terminal failure
 * status ({@code FAILED}, {@code CANCELLED}, or {@code EXPIRED}).
 *
 * <p>Sibling branches that are still running, waiting, or pending retry are marked
 * {@code SUPERSEDED}; their open timers and human tasks are cancelled. The join transitions
 * through {@link JoinNode#failureStepId()} (which must be non-null when this policy is used).
 *
 * <p>The plan validator restricts this policy to race-safe branch groups under the same rules as
 * {@link FirstSuccessJoinPolicy}.
 *
 * <p>Marker record — V1 carries no policy-specific state.
 */
public record FirstFailureJoinPolicy() implements JoinPolicy {

    /** Singleton instance. */
    public static final FirstFailureJoinPolicy INSTANCE = new FirstFailureJoinPolicy();
}
