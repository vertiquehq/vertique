// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

/**
 * Reports progress for a running job execution.
 *
 * <p>Implementations are thread-safe to allow reporting from multiple threads within the same
 * job. The current snapshot is accessible at any point via {@link #snapshot()}.
 *
 * <p>A {@link DefaultProgressReporter} is created for each execution and made available through
 * the {@link JobContext}. Handlers are not required to report progress; it is always optional.
 */
public interface ProgressReporter {

    /**
     * Sets the total number of work items for this execution.
     *
     * @param total the total item count (must be non-negative)
     */
    void setTotal(long total);

    /**
     * Increments the succeeded item count by one.
     */
    void incrementSucceeded();

    /**
     * Increments the succeeded item count by the given amount.
     *
     * @param count the number to add (must be positive)
     */
    void incrementSucceeded(long count);

    /**
     * Increments the failed item count by one.
     */
    void incrementFailed();

    /**
     * Increments the failed item count by the given amount.
     *
     * @param count the number to add (must be positive)
     */
    void incrementFailed(long count);

    /**
     * Sets a human-readable status message describing current progress.
     *
     * @param message the status message, or {@code null} to clear
     */
    void setStatus(String message);

    /**
     * Returns the current completion percentage.
     *
     * @return completion percentage in the range {@code [0, 100]}, or {@code 0} if total is zero
     */
    int percentage();

    /**
     * Returns an immutable snapshot of the current progress state.
     *
     * @return the current progress snapshot
     */
    ProgressSnapshot snapshot();
}
