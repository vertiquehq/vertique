// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared test utilities for asserting that no Vert.x event-loop threads are leaked after a
 * bootstrap operation.
 *
 * <p>Two helpers are provided:
 * <ul>
 *   <li>{@link #liveVertxThreadNames()} — snapshot of currently alive {@code vert.x-*} thread
 *       names, used to capture a baseline before the operation under test.</li>
 *   <li>{@link #assertNoLeakedVertxThreads(Set)} — polls until the live count returns to the
 *       baseline, then fails the test if threads are still above the baseline.</li>
 * </ul>
 *
 * <p>Used by {@link BootstrapConfigLoaderTest} and {@link BootstrapConfigLoaderStoresTest}.
 */
final class VertxThreadLeakAssertions {

    private static final long POLL_DEADLINE_MS = 10_000;
    private static final long POLL_INTERVAL_MS = 50;

    private VertxThreadLeakAssertions() {}

    /**
     * Returns the names of all currently alive threads whose names contain {@code "vert.x-"}.
     *
     * @return an immutable snapshot set of thread names
     */
    static Set<String> liveVertxThreadNames() {
        return Arrays.stream(Thread.getAllStackTraces().keySet().toArray(new Thread[0]))
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.contains("vert.x-"))
                .collect(Collectors.toSet());
    }

    /**
     * Polls (up to {@value #POLL_DEADLINE_MS} ms, {@value #POLL_INTERVAL_MS} ms interval) until the
     * number of live {@code vert.x-*} threads drops back to the baseline count, then asserts that no
     * threads were leaked.
     *
     * @param before the baseline snapshot captured before the operation under test
     * @throws InterruptedException if the polling loop is interrupted
     */
    static void assertNoLeakedVertxThreads(Set<String> before) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            Set<String> after = liveVertxThreadNames();
            if (after.size() <= before.size()) {
                return; // no leak
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }

        Set<String> remaining = liveVertxThreadNames();
        assertTrue(
                remaining.size() <= before.size(),
                "Leaked vert.x- threads after operation: " + remaining + " (before: " + before + ")");
    }
}
