// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.schema;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * A step that launches multiple parallel branches, each starting at a specified step.
 *
 * <p>All branches must converge at the join step identified by {@link #join()}.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — unique step identifier within the workflow.
 *   <li>{@code branches} — list of branch descriptors, each identifying a sub-workflow branch.
 *   <li>{@code join} — id of the {@link JoinStep} at which all branches converge.
 *   <li>{@code retryPolicy} — optional retry policy for failed branches; {@code null} if no
 *       retry is configured.
 * </ul>
 */
public record ForkStep(
        String id,
        List<BranchEntry> branches,
        String join,
        @Nullable BranchRetryBlock retryPolicy) implements StepNode {

    /**
     * Describes a single parallel branch in a {@link ForkStep}.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code branchId} — unique identifier for this branch within the fork scope.
     *   <li>{@code startStep} — id of the step where this branch begins execution.
     *   <li>{@code raceSafety} — optional race-safety mode for late-arriving branch results.
     *       One of {@code null} (equivalent to {@code "NORMAL"}), {@code "NORMAL"},
     *       {@code "CANCEL_SAFE"}, or {@code "IGNORE_LATE_RESULT_SAFE"}. The validator
     *       enforces the allowed values.
     * </ul>
     */
    public record BranchEntry(
            String branchId, String startStep, @Nullable String raceSafety) {}

    /**
     * Optional retry policy for branches within a {@link ForkStep}.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code maxAttempts} — maximum number of attempts per branch before the fork fails.
     *   <li>{@code initialDelay} — ISO-8601 Duration string for the delay before the first retry
     *       (e.g., {@code "PT5S"}).
     *   <li>{@code backoff} — retry backoff strategy: {@code "FIXED"} or {@code "EXPONENTIAL"}.
     *       When {@code null}, defaults to {@code "FIXED"}.
     * </ul>
     */
    public record BranchRetryBlock(
            int maxAttempts, String initialDelay, @Nullable String backoff) {}
}
