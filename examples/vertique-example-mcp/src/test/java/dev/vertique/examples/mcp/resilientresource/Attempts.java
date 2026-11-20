// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp.resilientresource;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts how many times the flaky resource body ran. */
@Singleton
public final class Attempts {

    private final AtomicInteger count = new AtomicInteger();

    @Inject
    public Attempts() {}

    /**
     * Records one attempt.
     *
     * @return the attempt number, starting at one
     */
    int next() {
        return count.incrementAndGet();
    }

    /**
     * Returns how many attempts ran.
     *
     * @return the attempt count
     */
    public int count() {
        return count.get();
    }

    /** Forgets all recorded attempts. */
    public void reset() {
        count.set(0);
    }
}
