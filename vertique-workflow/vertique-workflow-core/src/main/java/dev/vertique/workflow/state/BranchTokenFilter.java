// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import jakarta.annotation.Nullable;

/**
 * Optional filter for branch-token queries.
 *
 * <p>All fields are nullable; null means "no filter on this dimension". Unfiltered queries return
 * every branch token belonging to the requested workflow instance.
 *
 * @param status when non-null, restricts results to branch tokens with this status
 * @param waitType when non-null, restricts results to branch tokens whose {@code wait_type}
 *     matches
 * @param forkStepId when non-null, restricts results to a specific fork group
 * @param branchId when non-null, restricts results to branch tokens with this branch id. A branch id is unique only
 *     within its fork, so combine it with {@code forkStepId} to select a single branch of an instance that has
 *     several forks using the same branch id
 */
public record BranchTokenFilter(
        @Nullable BranchStatus status,
        @Nullable WaitType waitType,
        @Nullable String forkStepId,
        @Nullable String branchId) {

    /**
     * Creates a filter without a branch id restriction.
     *
     * @param status when non-null, restricts results to branch tokens with this status
     * @param waitType when non-null, restricts results to branch tokens whose {@code wait_type} matches
     * @param forkStepId when non-null, restricts results to a specific fork group
     */
    public BranchTokenFilter(@Nullable BranchStatus status, @Nullable WaitType waitType, @Nullable String forkStepId) {
        this(status, waitType, forkStepId, null);
    }

    /**
     * Returns a filter that matches every branch token for an instance.
     *
     * @return an unrestricted filter
     */
    public static BranchTokenFilter all() {
        return new BranchTokenFilter(null, null, null, null);
    }
}
