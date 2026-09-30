// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.fallthrough;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The counters the fall-through fixture's request interceptor and middlewares increment: the
 * interceptor's {@code onRequest} and {@code beforeRequest} calls, the {@code API}-scoped
 * middleware's calls, and the {@code ROOT}-scoped middleware's calls. A test owns one instance,
 * hands it to its component, and resets it before each test.
 */
public final class FallThroughCounters {

    private final AtomicInteger onRequest = new AtomicInteger();
    private final AtomicInteger beforeRequest = new AtomicInteger();
    private final AtomicInteger apiMiddleware = new AtomicInteger();
    private final AtomicInteger rootMiddleware = new AtomicInteger();

    /** Creates counters at zero. */
    public FallThroughCounters() {}

    /**
     * A snapshot of the four counts, or the difference of two snapshots.
     *
     * @param onRequest      the interceptor's {@code onRequest} calls
     * @param beforeRequest  the interceptor's {@code beforeRequest} calls
     * @param apiMiddleware  the {@code API}-scoped middleware's calls
     * @param rootMiddleware the {@code ROOT}-scoped middleware's calls
     */
    public record Counts(int onRequest, int beforeRequest, int apiMiddleware, int rootMiddleware) {

        /**
         * Returns this snapshot minus an earlier one.
         *
         * @param earlier the earlier snapshot
         * @return the per-counter difference
         */
        public Counts minus(Counts earlier) {
            return new Counts(
                    onRequest - earlier.onRequest,
                    beforeRequest - earlier.beforeRequest,
                    apiMiddleware - earlier.apiMiddleware,
                    rootMiddleware - earlier.rootMiddleware);
        }
    }

    /**
     * Returns the current counts.
     *
     * @return a snapshot
     */
    public Counts snapshot() {
        return new Counts(onRequest.get(), beforeRequest.get(), apiMiddleware.get(), rootMiddleware.get());
    }

    /** Sets every counter back to zero. */
    public void reset() {
        onRequest.set(0);
        beforeRequest.set(0);
        apiMiddleware.set(0);
        rootMiddleware.set(0);
    }

    void countOnRequest() {
        onRequest.incrementAndGet();
    }

    void countBeforeRequest() {
        beforeRequest.incrementAndGet();
    }

    void countApiMiddleware() {
        apiMiddleware.incrementAndGet();
    }

    void countRootMiddleware() {
        rootMiddleware.incrementAndGet();
    }
}
