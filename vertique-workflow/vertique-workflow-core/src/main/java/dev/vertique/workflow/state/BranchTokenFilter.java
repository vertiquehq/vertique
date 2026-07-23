// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import jakarta.annotation.Nullable;

/**
 * Optional filter for branch-token queries (PRD-WF-002 §7.6).
 *
 * <p>All fields are nullable; null means "no filter on this dimension". Unfiltered queries return
 * every branch token belonging to the requested workflow instance.
 *
 * @param status when non-null, restricts results to branch tokens with this status
 * @param waitType when non-null, restricts results to branch tokens whose {@code wait_type}
 *     matches
 * @param forkStepId when non-null, restricts results to a specific fork group
 */
public record BranchTokenFilter(
        @Nullable BranchStatus status,
        @Nullable WaitType waitType,
        @Nullable String forkStepId) {

    /**
     * Returns a filter that matches every branch token for an instance.
     *
     * @return an unrestricted filter
     */
    public static BranchTokenFilter all() {
        return new BranchTokenFilter(null, null, null);
    }
}
