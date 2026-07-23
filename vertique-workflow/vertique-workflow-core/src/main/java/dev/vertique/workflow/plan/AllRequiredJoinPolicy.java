// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

/**
 * {@link JoinPolicy} that advances the workflow only after every branch in the fork group reaches
 * {@code COMPLETED}.
 *
 * <p>If any required branch reaches {@code FAILED}, {@code CANCELLED}, or {@code EXPIRED} with no
 * retry budget remaining, the join takes the failure route declared on its
 * {@link JoinNode#failureStepId()}. {@code ALL_REQUIRED} branches may declare compensation;
 * compensation runs in reverse declaration order across siblings (PRD-WF-002 FR-WF-PAR-061..065).
 *
 * <p>Marker record — V1 carries no policy-specific state.
 */
public record AllRequiredJoinPolicy() implements JoinPolicy {

    /** Singleton instance. */
    public static final AllRequiredJoinPolicy INSTANCE = new AllRequiredJoinPolicy();
}
