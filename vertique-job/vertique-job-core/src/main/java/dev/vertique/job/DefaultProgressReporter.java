// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * INTERNAL framework seam — the framework implementation behind a contract this module documents;
 * not an application contract and outside the maturity promise.
 *
 * <p>Thread-safe in-memory implementation of {@link ProgressReporter}.
 *
 * <p>Uses {@link AtomicLong} for numeric counters and a {@link AtomicReference} for the
 * status message to ensure visibility across threads without explicit synchronisation.
 *
 * <p>Instances are created per execution by {@link DefaultJobContext}.
 */
public class DefaultProgressReporter implements ProgressReporter {

    private final AtomicLong total = new AtomicLong(0);
    private final AtomicLong succeeded = new AtomicLong(0);
    private final AtomicLong failed = new AtomicLong(0);
    private final AtomicReference<String> status = new AtomicReference<>();

    @Override
    public void setTotal(long total) {
        this.total.set(total);
    }

    @Override
    public void incrementSucceeded() {
        succeeded.incrementAndGet();
    }

    @Override
    public void incrementSucceeded(long count) {
        succeeded.addAndGet(count);
    }

    @Override
    public void incrementFailed() {
        failed.incrementAndGet();
    }

    @Override
    public void incrementFailed(long count) {
        failed.addAndGet(count);
    }

    @Override
    public void setStatus(String message) {
        status.set(message);
    }

    @Override
    public int percentage() {
        long t = total.get();
        return t > 0 ? (int) ((succeeded.get() + failed.get()) * 100 / t) : 0;
    }

    @Override
    public ProgressSnapshot snapshot() {
        return new ProgressSnapshot(total.get(), succeeded.get(), failed.get(), status.get());
    }
}
