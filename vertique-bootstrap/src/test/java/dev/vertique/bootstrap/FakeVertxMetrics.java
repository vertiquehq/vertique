// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.bootstrap;

import io.vertx.core.spi.metrics.VertxMetrics;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * No-op {@link VertxMetrics} implementation used as a test double.
 *
 * <p>Sets the static {@link #created} flag to {@code true} when instantiated, allowing tests to
 * verify that the factory was actually invoked by Vert.x.
 *
 * <p>All interface methods use the default (no-op) implementations provided by {@link VertxMetrics}.
 */
final class FakeVertxMetrics implements VertxMetrics {

    /**
     * Set to {@code true} the first time a {@link FakeVertxMetrics} instance is constructed.
     * Call {@link #resetCreated()} between tests.
     */
    static final AtomicBoolean created = new AtomicBoolean(false);

    /**
     * Constructs a new instance and sets {@link #created} to {@code true}.
     */
    FakeVertxMetrics() {
        created.set(true);
    }

    /**
     * Resets the {@link #created} flag to {@code false}.
     * Call this in {@code @BeforeEach} to ensure test isolation.
     */
    static void resetCreated() {
        created.set(false);
    }
}
