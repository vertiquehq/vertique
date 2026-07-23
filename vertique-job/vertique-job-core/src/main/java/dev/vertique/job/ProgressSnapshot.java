// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

/**
 * Immutable snapshot of job progress at a point in time.
 *
 * <p>Progress is expressed as counts of total work items, succeeded items, and failed items.
 * An optional human-readable {@code status} message may accompany the snapshot.
 *
 * @param total     total number of work items (0 if not yet known)
 * @param succeeded number of items completed successfully
 * @param failed    number of items that failed processing
 * @param status    optional human-readable status message, or {@code null}
 */
public record ProgressSnapshot(long total, long succeeded, long failed, String status) {

    /** An empty snapshot representing a job that has not yet reported any progress. */
    public static final ProgressSnapshot EMPTY = new ProgressSnapshot(0, 0, 0, null);

    /**
     * Returns the percentage of work completed (succeeded + failed out of total).
     * Returns {@code 0} when {@code total} is zero to avoid division by zero.
     *
     * @return completion percentage in the range {@code [0, 100]}
     */
    public int percentage() {
        return total > 0 ? (int) ((succeeded + failed) * 100 / total) : 0;
    }
}
