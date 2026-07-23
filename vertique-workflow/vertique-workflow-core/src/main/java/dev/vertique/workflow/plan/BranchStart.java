// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import java.util.Objects;

/**
 * Declares one branch in a fan-out group on a {@link ForkNode}.
 *
 * <p>A branch is a named entry point into a sub-graph of nodes that runs in parallel with sibling
 * branches. The branch starts at {@code startStepId} and runs to a terminal branch step (a
 * branch-local {@code complete} or {@code fail}). The branch's {@code raceSafety} controls
 * whether the branch may participate in a {@link FirstSuccessJoinPolicy} or
 * {@link FirstFailureJoinPolicy} race join.
 *
 * @param branchId stable, unique-within-fork-group identifier; participates in plan-hash and
 *     persistence
 * @param startStepId step id of the first node in this branch's sub-graph; must resolve to an
 *     in-plan node
 * @param raceSafety race-safety classification governing race-join eligibility (see
 *     {@link RaceSafety})
 */
public record BranchStart(String branchId, String startStepId, RaceSafety raceSafety) {

    /**
     * Compact constructor enforcing non-null fields and non-empty ids.
     */
    public BranchStart {
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(startStepId, "startStepId");
        Objects.requireNonNull(raceSafety, "raceSafety");
        if (branchId.isEmpty()) {
            throw new IllegalArgumentException("branchId must be non-empty");
        }
        if (startStepId.isEmpty()) {
            throw new IllegalArgumentException("startStepId must be non-empty");
        }
    }

    /**
     * Convenience factory creating a {@link RaceSafety#NORMAL} branch.
     *
     * @param branchId branch identifier
     * @param startStepId branch start step id
     * @return a normal-safety branch
     */
    public static BranchStart of(String branchId, String startStepId) {
        return new BranchStart(branchId, startStepId, RaceSafety.NORMAL);
    }
}
