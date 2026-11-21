// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts the runs of the {@link ResilientService} implementation body. */
@Singleton
public final class ResilientAttempts {

    private final AtomicInteger runs = new AtomicInteger();

    /** Creates an empty counter. */
    @Inject
    public ResilientAttempts() {}

    /** Resets the counter to zero. */
    public void reset() {
        runs.set(0);
    }

    /**
     * Records one run of the body.
     *
     * @return the number of runs including this one
     */
    public int record() {
        return runs.incrementAndGet();
    }

    /**
     * Returns the number of recorded runs.
     *
     * @return the count
     */
    public int count() {
        return runs.get();
    }
}
