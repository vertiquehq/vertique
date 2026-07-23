// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

/**
 * Persistence-form discriminator for {@link dev.vertique.workflow.plan.JoinPolicy}.
 *
 * <p>Stored as the enum's {@link #name()} in {@code workflow_join_states.policy}. The rich sealed
 * {@code JoinPolicy} type is reserved for plan-model usage; this enum exists so the persistence
 * layer doesn't import the sealed hierarchy and so that future quorum/partial-success policies
 * can be added without breaking the persisted column type.
 */
public enum JoinPolicyType {

    /** {@link dev.vertique.workflow.plan.AllRequiredJoinPolicy}. */
    ALL_REQUIRED,

    /** {@link dev.vertique.workflow.plan.FirstSuccessJoinPolicy}. */
    FIRST_SUCCESS,

    /** {@link dev.vertique.workflow.plan.FirstFailureJoinPolicy}. */
    FIRST_FAILURE
}
