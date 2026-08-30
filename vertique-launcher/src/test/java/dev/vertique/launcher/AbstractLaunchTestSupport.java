// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import io.vertx.core.Vertx;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared test lifecycle for launch-style tests: {@link VertiqueApplicationLaunchTest},
 * {@link VertiqueApplicationShutdownTest}, {@link VertiqueApplicationBootstrapTest},
 * {@link VertxOptionsOverlayTest}, {@link PlaceholderEndToEndTest}, and
 * {@link RedactionLogCaptureTest}.
 *
 * <p>{@code @BeforeEach}: resets {@link TestContributorState}, {@link FakeVertxMetrics},
 * {@link LauncherStubSourceFactory.State}, {@link NoOpVerticle#startedConfig}, and
 * {@link TestVertiqueApplication#capturedVertx} so each test starts from a clean slate.
 *
 * <p>{@code @AfterEach}: closes any {@link Vertx} instance captured during the test and awaits
 * graceful shutdown for up to 10 seconds. Class-level {@code @Timeout} is declared on each
 * concrete subclass.
 */
abstract class AbstractLaunchTestSupport {

    /** Resets all shared fixture state before each test. */
    @BeforeEach
    void setUp() {
        TestContributorState.reset();
        FakeVertxMetrics.resetCreated();
        LauncherStubSourceFactory.State.reset();
        NoOpVerticle.startedConfig.set(null);
        TestVertiqueApplication.capturedVertx.set(null);
    }

    /**
     * Closes the captured {@link Vertx} instance (if any) and awaits shutdown.
     *
     * @throws Exception if the close future does not complete within 10 seconds
     */
    @AfterEach
    void tearDown() throws Exception {
        Vertx vertx = TestVertiqueApplication.capturedVertx.getAndSet(null);
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
