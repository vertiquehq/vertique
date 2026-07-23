// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Self-test for {@link VertiqueAppExtension}, booting the minimal {@link TestAppComponent} through
 * the extension to prove: a {@code @RegisterExtension}-registered extension boots the app and exposes
 * its handle/component/config (Test A); the framework-owned awaited teardown actually runs the
 * contributed shutdown step (driven explicitly); accessors guard against pre-{@code beforeAll} use;
 * {@link VertiqueAppExtension#httpPort()} fails clearly when the app started no HTTP server (Test
 * B); the owned {@link io.vertx.core.Vertx} is always closed even when application shutdown times
 * out (teardown-path correctness); {@link VertiqueAppExtension#withVertx} uses the supplied
 * {@code Vertx} and does <em>not</em> close it on teardown; a custom
 * {@link VertiqueAppExtension#startTimeout} is honored; and {@link VertiqueAppExtension#config()}
 * defaults to an empty {@link io.vertx.core.json.JsonObject} before start.
 */
class VertiqueAppExtensionTest {

    private static final TestAppComponentFactory FACTORY = new TestAppComponentFactory();

    /**
     * Test A — the extension registered as a {@code @RegisterExtension static final} field: its
     * {@code beforeAll} boots the app before any {@code @Test} runs, so the handle, component, and
     * config accessors are all populated.
     */
    @Nested
    @DisplayName("registered as @RegisterExtension static final — boots the app for the class")
    class RegisteredExtension {

        private static final JsonObject CONFIG = new JsonObject().put("greeting", "hello");

        @RegisterExtension
        static final VertiqueAppExtension app =
                VertiqueAppExtension.forFactory(FACTORY).withConfig(CONFIG);

        @Test
        @DisplayName("handle is non-null after beforeAll")
        void handleIsNonNull() {
            assertNotNull(app.handle(), "handle() should be populated after beforeAll");
        }

        @Test
        @DisplayName("component() returns the built TestAppComponent")
        void componentReturnsBuiltComponent() {
            TestAppComponent component = app.component();
            assertNotNull(component, "component() should return the built component");
            assertSame(
                    app.handle().component(),
                    component,
                    "component() should return the same instance the handle exposes");
        }

        @Test
        @DisplayName("config() equals the configured JsonObject")
        void configEqualsConfigured() {
            assertEquals(CONFIG, app.config(), "config() should return the configured JsonObject");
        }

        @Test
        @DisplayName("vertx() is non-null after beforeAll")
        void vertxIsNonNull() {
            assertNotNull(app.vertx(), "vertx() should be populated after beforeAll");
        }
    }

    /**
     * Drives the extension's {@code beforeAll}/{@code afterAll} explicitly (rather than via JUnit
     * registration) so the test can assert the framework-owned teardown ran the contributed shutdown
     * step — observable after {@code afterAll} returns, without depending on JUnit's afterAll
     * ordering relative to assertions.
     */
    @Test
    @DisplayName("afterAll runs the framework-owned teardown (contributed shutdown step fires)")
    void afterAllRunsTeardown() {
        StubLifecycleModule.shutdownStepRan.set(false);
        VertiqueAppExtension extension = VertiqueAppExtension.forFactory(FACTORY);

        extension.beforeAll(null);
        assertFalse(StubLifecycleModule.shutdownStepRan.get(), "shutdown step must not have run before teardown");

        extension.afterAll(null);
        assertTrue(StubLifecycleModule.shutdownStepRan.get(), "afterAll should have run the contributed shutdown step");
    }

    @Test
    @DisplayName("accessors throw a clear IllegalStateException before beforeAll")
    void accessorsGuardBeforeStart() {
        VertiqueAppExtension extension = VertiqueAppExtension.forFactory(FACTORY);

        IllegalStateException handleEx = assertThrows(IllegalStateException.class, extension::handle);
        assertTrue(
                handleEx.getMessage().contains("not started"),
                "guard message should explain the extension is not started: " + handleEx.getMessage());

        assertThrows(IllegalStateException.class, extension::vertx);
        assertThrows(IllegalStateException.class, extension::component);
        assertThrows(IllegalStateException.class, extension::httpPort);
    }

    @Test
    @DisplayName("config() is valid before beforeAll and defaults to an empty JsonObject")
    void configValidBeforeStart() {
        VertiqueAppExtension defaulted = VertiqueAppExtension.forFactory(FACTORY);
        assertEquals(new JsonObject(), defaulted.config(), "config() should default to an empty JsonObject");

        JsonObject custom = new JsonObject().put("k", "v");
        VertiqueAppExtension configured =
                VertiqueAppExtension.forFactory(FACTORY).withConfig(custom);
        assertEquals(custom, configured.config(), "config() should return the configured value before start");
    }

    /**
     * Test B — {@link VertiqueAppExtension#httpPort()} throws a clear {@link IllegalStateException}
     * when the booted app started no HTTP server (the minimal app has no EDGE {@code HttpVerticle},
     * so no {@code http.port} is published to shared data).
     */
    @Test
    @DisplayName("httpPort() throws a clear error when the app started no HTTP server")
    void httpPortThrowsWhenNoHttpServer() {
        VertiqueAppExtension extension = VertiqueAppExtension.forFactory(FACTORY);
        extension.beforeAll(null);
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class, extension::httpPort);
            assertTrue(
                    ex.getMessage().contains("http.port") && ex.getMessage().contains("HTTP server"),
                    "httpPort() error should name http.port and the missing HTTP server: " + ex.getMessage());
        } finally {
            extension.afterAll(null);
        }
    }

    /**
     * Teardown-path correctness: when application shutdown stalls past the configured timeout,
     * {@code afterAll} must still close the owned {@link Vertx} (no event-loop leak) and propagate
     * the timeout as an {@link IllegalStateException}. Drives the extension explicitly so the
     * behavior can be observed without depending on JUnit's test ordering.
     *
     * <p>The shutdown is stalled by contributing an {@link
     * dev.vertique.core.lifecycle.ApplicationShutdownStep} that returns a future that never
     * completes (via {@link FailingShutdownModule}). Combined with a 200 ms {@code startTimeout},
     * the extension's shutdown await times out and throws. Note: the framework swallows individual
     * step-level failures (via {@code recover()}), so a <em>failed</em> future would not trigger
     * the timeout — only a <em>hanging</em> future does.
     *
     * <p>Proof that Vert.x was closed: after {@code afterAll} throws we call {@link Vertx#close()}
     * on the captured instance a second time. An already-closed Vert.x returns an immediately
     * completed future; we verify this with a tight 1-second {@code get()} timeout.
     */
    @Test
    @DisplayName("afterAll closes the owned Vertx even when application shutdown times out")
    void afterAllClosesVertxWhenShutdownTimesOut() throws Exception {
        FailingShutdownModule.stallShutdown.set(true);
        try {
            VertiqueAppExtension extension = VertiqueAppExtension.forFactory(new FailingShutdownAppComponentFactory())
                    .startTimeout(Duration.ofMillis(200));
            extension.beforeAll(null);

            // Capture the owned Vertx before teardown — extension nulls it out in finally.
            Vertx capturedVertx = extension.vertx();

            // (a) afterAll must throw because the shutdown timed out.
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> extension.afterAll(null));
            assertTrue(
                    ex.getMessage().contains("application shutdown"),
                    "exception should name the timed-out shutdown: " + ex.getMessage());

            // (b) The owned Vertx must have been closed: a second close() on an already-closed
            // Vertx returns an immediately completed future (idempotent no-op in Vert.x).
            capturedVertx.close().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
        } finally {
            FailingShutdownModule.stallShutdown.set(false);
        }
    }

    // --- withVertx(externalVertx): extension uses supplied Vertx and does NOT close it ---

    /**
     * When a caller-owned {@link Vertx} is supplied via {@link VertiqueAppExtension#withVertx},
     * the extension uses that instance for startup and must <em>not</em> close it during teardown.
     * Proves the external-ownership contract: after {@code afterAll} the supplied {@code Vertx} is
     * still alive (a {@code setTimer(1,...)} fires successfully).
     */
    @Test
    @DisplayName("withVertx: extension uses the supplied Vertx and does NOT close it on teardown")
    void withVertx_usesSuppliedVertxAndDoesNotCloseOnTeardown()
            throws Exception { // NOSONAR: test method throws Exception
        Vertx externalVertx = Vertx.vertx();
        try {
            VertiqueAppExtension extension =
                    VertiqueAppExtension.forFactory(FACTORY).withVertx(externalVertx);

            extension.beforeAll(null);
            // The extension's vertx() must be the one we supplied.
            assertSame(externalVertx, extension.vertx(), "extension must expose the supplied Vertx");

            extension.afterAll(null);

            // The external Vertx must still be alive after teardown — prove it by firing a timer and
            // waiting for it to execute. A closed Vertx would not fire the timer handler within the
            // latch timeout. Using CountDownLatch lets the JUnit thread block until the timer fires.
            CountDownLatch timerFired = new CountDownLatch(1);
            externalVertx.setTimer(50, id -> timerFired.countDown());
            boolean fired = timerFired.await(5, TimeUnit.SECONDS);
            assertTrue(fired, "external Vertx must still be alive after afterAll (timer did not fire within 5 s)");
        } finally {
            externalVertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    // --- startTimeout: a custom timeout is honored ---

    /**
     * A custom timeout set via {@link VertiqueAppExtension#startTimeout} is honored: when startup
     * stalls (a shutdown step that never completes is deliberately hung), and the configured timeout
     * is very small (200 ms), the extension's {@code beforeAll}/{@code afterAll} await must surface
     * a clear {@link IllegalStateException} with the configured duration in the message.
     *
     * <p>This test proves the timeout is actually applied: a 200 ms timeout expires before a
     * hung operation; a 30-second default would pass only because the default is not what is being
     * tested here. The stall is triggered on shutdown (to keep {@code beforeAll} clean), combined
     * with a 200 ms configured timeout.
     */
    @Test
    @DisplayName("startTimeout: a custom timeout is honored — a hung shutdown exceeds the timeout and throws")
    void startTimeout_customTimeoutIsHonored() {
        FailingShutdownModule.stallShutdown.set(true);
        try {
            VertiqueAppExtension extension = VertiqueAppExtension.forFactory(new FailingShutdownAppComponentFactory())
                    .startTimeout(Duration.ofMillis(200));

            extension.beforeAll(null);

            // The shutdown will hang; the 200 ms timeout must expire.
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> extension.afterAll(null));

            // The error message must mention the configured duration (as ISO-8601 "PT0.2S") so operators
            // can diagnose the timeout. Duration.toString() emits ISO-8601 format ("PT0.2S" for 200 ms).
            assertTrue(
                    ex.getMessage().contains("PT0.2S") || ex.getMessage().contains("0.2"),
                    "error message should contain the configured timeout duration (PT0.2S): " + ex.getMessage());
        } finally {
            FailingShutdownModule.stallShutdown.set(false);
        }
    }
}
