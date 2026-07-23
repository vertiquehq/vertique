// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.bootstrap.BootstrapContext;
import dev.vertique.bootstrap.VertxBuilderContributor;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared mutable state for {@link VertxBuilderContributor} test fixtures.
 *
 * <p>{@link #armThrowing} and {@link #armFakeMetrics} gate behaviour-switching side effects in
 * their respective fixtures. {@link OrderedAlphaContributor} and {@link OrderedBetaContributor}
 * always record their invocations unconditionally — tests that care about a specific subset of
 * invocations should filter {@link #invocations} rather than relying on arm flags.
 *
 * <p>Call {@link #reset()} in {@code @BeforeEach} to isolate tests from each other.
 */
final class TestContributorState {

    /** When {@code true}, {@link ThrowingContributor} throws from {@code contribute}. */
    static volatile boolean armThrowing = false;

    /** When {@code true}, {@link FakeMetricsContributor} mutates {@code VertxOptions} and sets metrics. */
    static volatile boolean armFakeMetrics = false;

    /**
     * Thread-safe list of invocation labels appended by fixtures.
     * Format: {@code "<name>:contribute"} or {@code "<name>:shutdown"}.
     */
    static final List<String> invocations = Collections.synchronizedList(new ArrayList<>());

    /**
     * Holds the last {@link JsonObject} captured from {@link BootstrapContext#config()} by
     * {@link OrderedAlphaContributor} on each {@code contribute} invocation.
     */
    static final AtomicReference<JsonObject> recordedConfig = new AtomicReference<>(null);

    /**
     * Holds the {@link VertxOptions} instance captured from {@link BootstrapContext#vertxOptions()}
     * by {@link OptionsRecordingContributor} on each {@code contribute} invocation.
     * Used to verify whether the same or a different {@link io.vertx.core.VertxOptions} instance
     * was passed through (identity check for the {@code vertx.options} overlay path).
     */
    static final AtomicReference<VertxOptions> recordedVertxOptions = new AtomicReference<>(null);

    /**
     * Holds the {@link VertxOptions#getEventLoopPoolSize()} value captured by
     * {@link OptionsRecordingContributor} during its {@code contribute} invocation.
     * Initialised to {@code -1}; set to the actual pool size on each contribution.
     */
    static final AtomicInteger recordedEventLoopPoolSize = new AtomicInteger(-1);

    /**
     * Resets all arm flags to {@code false}, clears the invocation list, and clears
     * {@link #recordedConfig}, {@link #recordedVertxOptions}, and
     * {@link #recordedEventLoopPoolSize}.
     * Call this in {@code @BeforeEach} to ensure test isolation.
     */
    static void reset() {
        armThrowing = false;
        armFakeMetrics = false;
        invocations.clear();
        recordedConfig.set(null);
        recordedVertxOptions.set(null);
        recordedEventLoopPoolSize.set(-1);
    }

    private TestContributorState() {}
}
