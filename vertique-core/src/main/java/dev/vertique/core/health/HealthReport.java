// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import java.util.List;
import java.util.Objects;

/**
 * The verdict of one evaluation of a set of {@link HealthCheck}s: the aggregate {@link HealthStatus}
 * and one {@link Entry} per check, in the order the checks were evaluated.
 *
 * <p>The aggregate status is {@link HealthStatus#UP} only when every entry is {@code UP}; an empty
 * report is {@code UP}. The canonical constructor rejects a status that does not match its entries,
 * so a report can never claim {@code UP} while carrying a {@code DOWN} entry; {@link #of(List)}
 * derives the status instead of asking for it.
 *
 * <p>A report is produced by {@link HealthCheckAggregator#evaluate(java.util.Set)}. It carries each
 * check's {@link HealthCheckResult} as the check produced it, or as the aggregator described the
 * check's failure, and leaves any rendering to the consumer.
 *
 * @param status the aggregate status; never {@code null}
 * @param checks one entry per evaluated check, in evaluation order; unmodifiable, never {@code null}
 *               and never holding {@code null}
 */
public record HealthReport(HealthStatus status, List<Entry> checks) {

    /**
     * One check's contribution to a {@link HealthReport}.
     *
     * @param name   the check's name as resolved by the aggregator: {@link HealthCheck#name()}, or the
     *               implementing class name when that returned {@code null} or threw; never
     *               {@code null}
     * @param result the check's result, or the {@code DOWN} result describing why it produced none;
     *               never {@code null}
     */
    public record Entry(String name, HealthCheckResult result) {

        /**
         * Creates an entry.
         *
         * @param name   the resolved check name; must not be {@code null}
         * @param result the check's result; must not be {@code null}
         * @throws NullPointerException if {@code name} or {@code result} is {@code null}
         */
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(result, "result");
        }

        /**
         * Returns the status of this entry's result.
         *
         * @return {@code result().status()}
         */
        public HealthStatus status() {
            return result.status();
        }
    }

    /**
     * Creates a report, copying the entries and checking that the status matches them.
     *
     * @param status the aggregate status; must not be {@code null}
     * @param checks the entries; must not be {@code null} or hold {@code null}
     * @throws NullPointerException     if {@code status} or {@code checks} is {@code null}, or
     *                                  {@code checks} holds a {@code null} entry
     * @throws IllegalArgumentException if {@code status} is not the status the entries aggregate to
     */
    public HealthReport {
        Objects.requireNonNull(status, "status");
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        HealthStatus aggregate = statusOf(checks);
        if (status != aggregate) {
            throw new IllegalArgumentException(
                    "status " + status + " does not match the entries, which aggregate to " + aggregate);
        }
    }

    /**
     * Creates a report whose status is derived from its entries.
     *
     * @param checks the entries; must not be {@code null} or hold {@code null}
     * @return a report that is {@code UP} only when every entry is {@code UP}
     * @throws NullPointerException if {@code checks} is {@code null} or holds a {@code null} entry
     */
    public static HealthReport of(List<Entry> checks) {
        return new HealthReport(statusOf(Objects.requireNonNull(checks, "checks")), checks);
    }

    /**
     * Returns whether the aggregate status is {@link HealthStatus#UP}.
     *
     * @return {@code true} when every entry is {@code UP}, including when there are none
     */
    public boolean isUp() {
        return status == HealthStatus.UP;
    }

    /**
     * Derives the aggregate status of a list of entries: {@code UP} only when every entry is
     * {@code UP}.
     *
     * @param checks the entries
     * @return the aggregate status
     */
    private static HealthStatus statusOf(List<Entry> checks) {
        for (Entry entry : checks) {
            if (entry.status() != HealthStatus.UP) {
                return HealthStatus.DOWN;
            }
        }
        return HealthStatus.UP;
    }
}
