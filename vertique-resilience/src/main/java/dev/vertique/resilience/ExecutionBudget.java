// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;

/**
 * Maximum active-execution and queue-wait durations for a resolved policy.
 *
 * @param activeExecution maximum duration spent executing attempts and backoff
 * @param queueWait maximum duration spent waiting for bulkhead admission
 */
public record ExecutionBudget(DurationBound activeExecution, DurationBound queueWait) {

    /** Validates the two independent budget components. */
    public ExecutionBudget {
        Objects.requireNonNull(activeExecution, "activeExecution");
        Objects.requireNonNull(queueWait, "queueWait");
    }

    /**
     * Returns the saturating sum of active execution and queue wait.
     *
     * @return the combined duration bound
     */
    public DurationBound total() {
        if (activeExecution instanceof DurationBound.Unbounded || queueWait instanceof DurationBound.Unbounded) {
            return new DurationBound.Unbounded();
        }
        if (activeExecution instanceof DurationBound.Unknown unknown) {
            return unknown;
        }
        if (queueWait instanceof DurationBound.Unknown unknown) {
            return unknown;
        }

        DurationBound.Known active = (DurationBound.Known) activeExecution;
        DurationBound.Known queue = (DurationBound.Known) queueWait;
        long total = saturatingAdd(active.valueMs(), queue.valueMs());
        return new DurationBound.Known(total, total == Long.MAX_VALUE);
    }

    static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    static long saturatingMultiply(long value, long factor) {
        if (value == 0 || factor == 0) {
            return 0;
        }
        if (value > Long.MAX_VALUE / factor) {
            return Long.MAX_VALUE;
        }
        return value * factor;
    }
}
