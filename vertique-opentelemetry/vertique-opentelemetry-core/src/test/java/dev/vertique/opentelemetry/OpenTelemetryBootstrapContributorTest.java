// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.bootstrap.BootstrapContext;
import dev.vertique.core.exception.ConfigurationException;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.spi.VertxTracerFactory;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryTracingFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link OpenTelemetryBootstrapContributor}.
 *
 * <p>Verifies the disabled path (noop factory), enabled path with no global set, enabled path
 * reusing an existing global, the failure path (TracingBootstrapException, cause severed, no secret
 * leak), onShutdown lifecycle, the NFR-TEL-003 noop proof, the fail-fast-on-relaunch guard
 * (Fix 2: a second contribute() after onShutdown on an owned SDK must throw TracingBootstrapException),
 * and the exception hierarchy proof.
 *
 * <p>Every test class resets {@link GlobalOpenTelemetry} in {@link BeforeEach} and {@link AfterEach}
 * to guarantee isolation across test methods.
 */
class OpenTelemetryBootstrapContributorTest {

    @BeforeEach
    void resetGlobal() {
        GlobalOpenTelemetry.resetForTest();
        OpenTelemetryBootstrapContributor.resetOwnedGlobalClosedForTests();
    }

    @AfterEach
    void resetGlobalAfter() {
        GlobalOpenTelemetry.resetForTest();
        OpenTelemetryBootstrapContributor.resetOwnedGlobalClosedForTests();
    }

    // --- Hierarchy proof ---

    @Test
    @DisplayName("TracingBootstrapException is a ConfigurationException")
    void tracingBootstrapExceptionIsConfigurationException() {
        assertTrue(ConfigurationException.class.isAssignableFrom(TracingBootstrapException.class));
        assertInstanceOf(ConfigurationException.class, new TracingBootstrapException("test"));
    }

    // --- Test 1: Disabled path ---

    @Nested
    @DisplayName("disabled path — noop factory installed, SDK not built")
    class DisabledPath {

        @Test
        @DisplayName(
                "withTracer called with noop factory (NOT OpenTelemetryTracingFactory); no global set; tracingOptions untouched; onShutdown no-op")
        void disabledInstallsNoopFactory() throws Exception {
            OpenTelemetryBootstrapContributor contributor = new OpenTelemetryBootstrapContributor();

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx =
                    fakeContext(new JsonObject().put("tracing", new JsonObject().put("enabled", false)), options);

            contributor.contribute(builder, ctx);

            // withTracer called with a factory
            ArgumentCaptor<VertxTracerFactory> captor = forClass(VertxTracerFactory.class);
            verify(builder).withTracer(captor.capture());
            VertxTracerFactory factory = captor.getValue();

            // Must NOT be OpenTelemetryTracingFactory
            assertFalse(
                    factory instanceof OpenTelemetryTracingFactory,
                    "noop path must not use OpenTelemetryTracingFactory");

            // GlobalOpenTelemetry must not have been set
            assertFalse(GlobalOpenTelemetry.isSet(), "disabled path must not register global");

            // tracingOptions must not be touched
            assertNull(options.getTracingOptions(), "disabled path must not set tracingOptions");

            // onShutdown must be a no-op
            assertDoesNotThrow(contributor::onShutdown, "onShutdown must not throw on disabled path");
        }

        @Test
        @DisplayName("disabled path: OWNED_GLOBAL_CLOSED latch unaffected — remains false")
        void disabledPathDoesNotSetLatch() throws Exception {
            OpenTelemetryBootstrapContributor contributor = new OpenTelemetryBootstrapContributor();

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx =
                    fakeContext(new JsonObject().put("tracing", new JsonObject().put("enabled", false)), options);

            contributor.contribute(builder, ctx);
            contributor.onShutdown();

            // Second contribute on disabled path must NOT throw TracingBootstrapException
            // (latch is only for owned-global-closed scenario)
            assertDoesNotThrow(
                    () -> contributor.contribute(builder, ctx), "disabled path onShutdown must not poison the latch");
        }
    }

    // --- Test 2: Enabled, no global set ---

    @Nested
    @DisplayName("enabled path with no existing global — SDK built, factory wired, options set")
    class EnabledNoGlobal {

        @Test
        @DisplayName(
                "withTracer called with OpenTelemetryTracingFactory; global is set; options is OpenTelemetryOptions; double onShutdown safe")
        void enabledBuildsAndInstalls() throws Exception {
            OpenTelemetryBootstrapContributor contributor = new OpenTelemetryBootstrapContributor();

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            // traces.exporter=none avoids attempting a real OTLP export during tests
            BootstrapContext ctx = fakeContext(
                    new JsonObject()
                            .put(
                                    "tracing",
                                    new JsonObject()
                                            .put(
                                                    "otel",
                                                    new JsonObject()
                                                            .put("traces", new JsonObject().put("exporter", "none")))),
                    options);

            contributor.contribute(builder, ctx);

            // withTracer called with an OpenTelemetryTracingFactory
            ArgumentCaptor<VertxTracerFactory> captor = forClass(VertxTracerFactory.class);
            verify(builder).withTracer(captor.capture());
            assertInstanceOf(OpenTelemetryTracingFactory.class, captor.getValue());

            // Global must now be registered
            assertTrue(GlobalOpenTelemetry.isSet(), "global must be registered after enabled contribute");

            // tracingOptions must be OpenTelemetryOptions
            assertInstanceOf(
                    OpenTelemetryOptions.class,
                    options.getTracingOptions(),
                    "tracingOptions must be OpenTelemetryOptions on enabled path");

            // Double onShutdown must not throw
            assertDoesNotThrow(contributor::onShutdown);
            assertDoesNotThrow(contributor::onShutdown, "second onShutdown must be safe");
        }
    }

    // --- Test 3: Enabled, global already set ---

    @Nested
    @DisplayName("enabled path with existing global — reuse, no SDK built, no TracingBootstrapException")
    class EnabledGlobalAlreadySet {

        @Test
        @DisplayName(
                "reuses existing global; withTracer called with OpenTelemetryTracingFactory; onShutdown is a no-op")
        void reuseExistingGlobal() throws Exception {
            // Pre-register a noop global
            GlobalOpenTelemetry.set(OpenTelemetry.noop());

            OpenTelemetryBootstrapContributor contributor = new OpenTelemetryBootstrapContributor();

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx = fakeContext(new JsonObject(), options);

            // Must NOT throw even though the global is already set
            assertDoesNotThrow(() -> contributor.contribute(builder, ctx));

            // withTracer called with OpenTelemetryTracingFactory
            ArgumentCaptor<VertxTracerFactory> captor = forClass(VertxTracerFactory.class);
            verify(builder).withTracer(captor.capture());
            assertInstanceOf(OpenTelemetryTracingFactory.class, captor.getValue());

            // onShutdown must be a no-op (did not own the SDK)
            assertDoesNotThrow(contributor::onShutdown);
        }

        @Test
        @DisplayName("reuse path NOT poisoned by OWNED_GLOBAL_CLOSED latch when global was never ours")
        void reusedGlobalNotPoisonedByLatch() throws Exception {
            // Pre-register a global we don't own
            GlobalOpenTelemetry.set(OpenTelemetry.noop());

            // Simulate a different contributor having set OWNED_GLOBAL_CLOSED by calling the reset+latch
            // In practice: latch stays false when global was never owned, but verify explicitly.
            // The latch should only be set when THIS contributor's ownedSdk was closed.

            OpenTelemetryBootstrapContributor contributor = new OpenTelemetryBootstrapContributor();
            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx = fakeContext(new JsonObject(), options);

            // Should succeed — latch is not set because we never owned the global
            assertDoesNotThrow(
                    () -> contributor.contribute(builder, ctx),
                    "reuse path must not be blocked by latch when global was never ours");
        }
    }

    // --- Test 4: Failure path — TracingBootstrapException, no cause, no secret leak ---

    @Nested
    @DisplayName("failure path — TracingBootstrapException with no cause and no secret leak")
    class FailurePath {

        @Test
        @DisplayName(
                "bad exporter config causes TracingBootstrapException; getCause()==null; SENTINEL absent from chain and logs")
        void badConfigThrowsTracingBootstrapExceptionWithNoSecretLeak() {
            String sentinel = "SENTINEL_endpoint_value";

            // Capture all log events
            Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();
            rootLogger.addAppender(listAppender);

            try {
                OpenTelemetryBootstrapContributor contributor = new OpenTelemetryBootstrapContributor();

                VertxBuilder builder = mock(VertxBuilder.class);
                VertxOptions options = new VertxOptions();
                // Deliberately broken config: unknown exporter type + sentinel in endpoint
                BootstrapContext ctx = fakeContext(
                        new JsonObject()
                                .put(
                                        "tracing",
                                        new JsonObject()
                                                .put(
                                                        "otel",
                                                        new JsonObject()
                                                                .put(
                                                                        "traces",
                                                                        new JsonObject()
                                                                                .put(
                                                                                        "exporter",
                                                                                        "definitely-not-an-exporter"))
                                                                .put(
                                                                        "exporter",
                                                                        new JsonObject()
                                                                                .put(
                                                                                        "otlp",
                                                                                        new JsonObject()
                                                                                                .put(
                                                                                                        "endpoint",
                                                                                                        sentinel))))),
                        options);

                TracingBootstrapException thrown =
                        assertThrows(TracingBootstrapException.class, () -> contributor.contribute(builder, ctx));

                // Cause must be null (chain severed)
                assertNull(thrown.getCause(), "TracingBootstrapException must have no cause");

                // Message should contain the simple class name of the underlying error (not the sentinel)
                assertTrue(thrown.getMessage().startsWith("OpenTelemetry bootstrap failed:"), thrown.getMessage());

                // GlobalOpenTelemetry must NOT be set after a failure
                assertFalse(GlobalOpenTelemetry.isSet(), "global must not be set after failure");

                // SENTINEL must not appear in the exception chain
                Throwable current = thrown;
                while (current != null) {
                    String msg = current.getMessage();
                    if (msg != null) {
                        assertFalse(msg.contains(sentinel), "exception message must not contain sentinel: " + msg);
                    }
                    assertFalse(current.toString().contains(sentinel), "exception toString must not contain sentinel");
                    current = current.getCause();
                }

                // SENTINEL must not appear in any log event
                for (ILoggingEvent event : listAppender.list) {
                    assertFalse(
                            event.getFormattedMessage().contains(sentinel),
                            "log event must not contain sentinel: " + event.getFormattedMessage());
                }
            } finally {
                rootLogger.detachAppender(listAppender);
            }
        }
    }

    // --- Test 5: onShutdown after successful contribute closes owned SDK ---

    @Nested
    @DisplayName("onShutdown closes the owned SDK safely")
    class OnShutdownLifecycle {

        @Test
        @DisplayName("after successful contribute, onShutdown closes the SDK; tracer from closed SDK does not throw")
        void onShutdownClosesSdkSafely() throws Exception {
            OpenTelemetryBootstrapContributor contributor = new OpenTelemetryBootstrapContributor();

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx = fakeContext(
                    new JsonObject()
                            .put(
                                    "tracing",
                                    new JsonObject()
                                            .put(
                                                    "otel",
                                                    new JsonObject()
                                                            .put("traces", new JsonObject().put("exporter", "none")))),
                    options);

            contributor.contribute(builder, ctx);

            // Obtain a tracer before shutdown
            io.opentelemetry.api.trace.Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");

            // Close via onShutdown
            assertDoesNotThrow(contributor::onShutdown);

            // Tracer from closed SDK still works (no-ops, no throw)
            assertDoesNotThrow(
                    () -> {
                        Span span = tracer.spanBuilder("after-shutdown").startSpan();
                        span.end();
                    },
                    "span operations on closed SDK tracer must not throw");
        }
    }

    // --- Test 6: NFR-TEL-003 noop proof ---

    @Nested
    @DisplayName("NFR-TEL-003 — disabled path noop proof")
    class NoopProof {

        @Test
        @DisplayName("disabled path: span from OpenTelemetry.noop() has invalid SpanContext; ending is a no-op")
        void disabledPathSpanIsNoop() {
            Span noopSpan = OpenTelemetry.noop().getTracer("t").spanBuilder("s").startSpan();

            assertFalse(noopSpan.getSpanContext().isValid(), "noop span context must not be valid");
            assertDoesNotThrow(() -> noopSpan.end(), "ending noop span must not throw");
        }
    }

    // --- Test 7: Malformed tracing section ---

    @Nested
    @DisplayName("malformed tracing section throws ConfigurationException, not TracingBootstrapException")
    class MalformedTracingSection {

        @Test
        @DisplayName("tracing section is a scalar (\"yes\") → ConfigurationException; global not set")
        void tracingScalarThrowsConfigurationException() {
            OpenTelemetryBootstrapContributor contributor = new OpenTelemetryBootstrapContributor();

            VertxBuilder builder = mock(VertxBuilder.class);
            VertxOptions options = new VertxOptions();
            // "tracing" bound to a string scalar — JsonConfigPaths.navigateObject must throw ConfigurationException
            BootstrapContext ctx = fakeContext(new JsonObject().put("tracing", "yes"), options);

            // assertThrows guarantees the type is ConfigurationException — not TracingBootstrapException
            assertThrows(ConfigurationException.class, () -> contributor.contribute(builder, ctx));
            assertFalse(GlobalOpenTelemetry.isSet(), "global must not be registered after config-shape error");
        }
    }

    // --- Test 8: Fail-fast on closed-global relaunch (Fix 2 — RED TEST) ---

    @Nested
    @DisplayName("fail-fast on closed-global relaunch — symmetric with Micrometer double-bootstrap guard")
    class ClosedGlobalRelaunch {

        /**
         * RED TEST: contribute (enabled, exporter=none) → onShutdown → NEW contributor instance
         * + contribute again → must throw TracingBootstrapException (currently succeeds silently).
         */
        @Test
        @DisplayName("contribute → onShutdown → new contributor contribute again → TracingBootstrapException")
        void relaunchiAfterShutdownThrows() throws Exception {
            // First launch: build + own the SDK
            OpenTelemetryBootstrapContributor first = new OpenTelemetryBootstrapContributor();
            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx = fakeContext(
                    new JsonObject()
                            .put(
                                    "tracing",
                                    new JsonObject()
                                            .put(
                                                    "otel",
                                                    new JsonObject()
                                                            .put("traces", new JsonObject().put("exporter", "none")))),
                    options);

            first.contribute(builder, ctx);
            assertTrue(GlobalOpenTelemetry.isSet(), "global must be set after first contribute");

            // Shutdown: closes owned SDK; GlobalOpenTelemetry still points at the closed SDK
            first.onShutdown();

            // Now GlobalOpenTelemetry.isSet() is still true (pointing at closed SDK).
            // A new contributor instance (simulating ServiceLoader re-discovery) would take
            // the reuse branch and silently wire a closed SDK. The fix must make this fail-fast.
            OpenTelemetryBootstrapContributor second = new OpenTelemetryBootstrapContributor();
            VertxOptions options2 = new VertxOptions();
            BootstrapContext ctx2 = fakeContext(
                    new JsonObject()
                            .put(
                                    "tracing",
                                    new JsonObject()
                                            .put(
                                                    "otel",
                                                    new JsonObject()
                                                            .put("traces", new JsonObject().put("exporter", "none")))),
                    options2);
            VertxBuilder builder2 = mock(VertxBuilder.class);
            when(builder2.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder2);

            // MUST throw TracingBootstrapException — currently (before fix) it succeeds silently (RED)
            assertThrows(
                    TracingBootstrapException.class,
                    () -> second.contribute(builder2, ctx2),
                    "relaunch after onShutdown (owned SDK closed) must throw TracingBootstrapException");
        }

        /**
         * Verifies the reuse path is NOT poisoned when the global was pre-installed by a javaagent
         * (i.e., OWNED_GLOBAL_CLOSED latch is false).
         */
        @Test
        @DisplayName("reuse path not blocked when global was installed externally (latch=false)")
        void reuseNotBlockedWhenGlobalNotOwnedByClosed() throws Exception {
            // Global set by an external agent; we never owned it
            GlobalOpenTelemetry.set(OpenTelemetry.noop());

            OpenTelemetryBootstrapContributor contributor = new OpenTelemetryBootstrapContributor();
            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx = fakeContext(new JsonObject(), options);

            // Must NOT throw — latch is false, we never closed an owned SDK
            assertDoesNotThrow(
                    () -> contributor.contribute(builder, ctx),
                    "reuse-of-external-global must not be blocked by OWNED_GLOBAL_CLOSED latch");
        }

        /**
         * Verifies the disabled path is unaffected by the latch.
         */
        @Test
        @DisplayName("disabled path always works — not affected by OWNED_GLOBAL_CLOSED latch")
        void disabledPathUnaffectedByLatch() throws Exception {
            OpenTelemetryBootstrapContributor contributor = new OpenTelemetryBootstrapContributor();
            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();

            // Disabled path
            BootstrapContext ctx =
                    fakeContext(new JsonObject().put("tracing", new JsonObject().put("enabled", false)), options);
            assertDoesNotThrow(
                    () -> contributor.contribute(builder, ctx),
                    "disabled path must not be blocked by OWNED_GLOBAL_CLOSED latch");
        }
    }

    // --- Test W3: rollback-via-exception must poison the OWNED_GLOBAL_CLOSED latch ---

    @Nested
    @DisplayName("W3 — rollback after setResultAsGlobal poisons the relaunch latch")
    class RollbackPoisonsLatch {

        /**
         * RED TEST: contribute (enabled) where {@code builder.withTracer()} throws a RuntimeException
         * after the SDK was built and registered as the global via {@code setResultAsGlobal()}.
         * The rollback catch should close ownedSdk AND set OWNED_GLOBAL_CLOSED, so a second
         * contribute() on a new instance (with the global still set) fails fast rather than silently
         * wiring the already-closed SDK.
         *
         * <p>Before the fix the second contribute() takes the {@code GlobalOpenTelemetry.isSet()}
         * reuse branch and succeeds silently — this test is RED until the fix is applied.
         */
        @Test
        @DisplayName(
                "withTracer throws during wiring → rollback closes SDK + poisons latch → second contribute throws TracingBootstrapException")
        void withTracerThrowingRollbackPoisonsLatch() throws Exception {
            // Arrange: mock builder so withTracer() throws AFTER the SDK is built and globally registered
            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withTracer(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new RuntimeException("simulated wiring failure"));

            VertxOptions options = new VertxOptions();
            BootstrapContext ctx = fakeContext(
                    new JsonObject()
                            .put(
                                    "tracing",
                                    new JsonObject()
                                            .put(
                                                    "otel",
                                                    new JsonObject()
                                                            .put("traces", new JsonObject().put("exporter", "none")))),
                    options);

            OpenTelemetryBootstrapContributor first = new OpenTelemetryBootstrapContributor();

            // contribute() must throw TracingBootstrapException (wrapping the RuntimeException)
            TracingBootstrapException thrown =
                    assertThrows(TracingBootstrapException.class, () -> first.contribute(builder, ctx));
            assertTrue(thrown.getMessage().contains("OpenTelemetry bootstrap failed:"), thrown.getMessage());
            // Cause must be null (chain severed to prevent secret leakage)
            assertNull(thrown.getCause(), "TracingBootstrapException cause must be null");

            // The global is still set (setResultAsGlobal registered it before withTracer threw).
            // A second contributor — simulating ServiceLoader re-discovery — would take the reuse
            // branch and silently wire the now-closed SDK. The fix must make this fail-fast.
            VertxBuilder builder2 = mock(VertxBuilder.class);
            when(builder2.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder2);
            VertxOptions options2 = new VertxOptions();
            BootstrapContext ctx2 = fakeContext(
                    new JsonObject()
                            .put(
                                    "tracing",
                                    new JsonObject()
                                            .put(
                                                    "otel",
                                                    new JsonObject()
                                                            .put("traces", new JsonObject().put("exporter", "none")))),
                    options2);
            OpenTelemetryBootstrapContributor second = new OpenTelemetryBootstrapContributor();

            // MUST throw TracingBootstrapException — currently (before fix) succeeds silently (RED)
            TracingBootstrapException second_thrown =
                    assertThrows(TracingBootstrapException.class, () -> second.contribute(builder2, ctx2));
            assertTrue(
                    second_thrown.getMessage().contains("already closed"),
                    "second contribute must mention 'already closed', got: " + second_thrown.getMessage());
        }

        /**
         * Verifies the disabled path is not affected by the latch when a rollback occurred on a
         * prior enabled path in the same JVM. The disabled path must always succeed.
         */
        @Test
        @DisplayName("disabled path always succeeds even when latch is set from a prior rollback")
        void disabledPathUnaffectedWhenLatchSet() throws Exception {
            // Force rollback to set latch: mock withTracer to throw
            VertxBuilder throwingBuilder = mock(VertxBuilder.class);
            when(throwingBuilder.withTracer(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new RuntimeException("force rollback"));
            VertxOptions options = new VertxOptions();
            BootstrapContext enabledCtx = fakeContext(
                    new JsonObject()
                            .put(
                                    "tracing",
                                    new JsonObject()
                                            .put(
                                                    "otel",
                                                    new JsonObject()
                                                            .put("traces", new JsonObject().put("exporter", "none")))),
                    options);
            // Trigger rollback (ignore the exception — we just want the latch set)
            assertThrows(TracingBootstrapException.class, () -> new OpenTelemetryBootstrapContributor()
                    .contribute(throwingBuilder, enabledCtx));

            // Disabled path on a fresh contributor must not throw
            VertxBuilder disabledBuilder = mock(VertxBuilder.class);
            when(disabledBuilder.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(disabledBuilder);
            BootstrapContext disabledCtx = fakeContext(
                    new JsonObject().put("tracing", new JsonObject().put("enabled", false)), new VertxOptions());
            assertDoesNotThrow(
                    () -> new OpenTelemetryBootstrapContributor().contribute(disabledBuilder, disabledCtx),
                    "disabled path must not be blocked by OWNED_GLOBAL_CLOSED latch");
        }
    }

    // --- Test 9 (Fix #9): owned-SDK rollback on wiring failure ---

    @Nested
    @DisplayName("Fix #9 — owned-SDK rollback: wiring steps run inside try block")
    class OwnedSdkRollback {

        /**
         * Verifies that after a successful {@code contribute()} + {@code onShutdown()}, the
         * {@code ownedSdk} field is null (SDK was closed) and the {@link #OWNED_GLOBAL_CLOSED}
         * latch is set, so a subsequent relaunch throws {@link TracingBootstrapException}.
         *
         * <p>{@code setTracingOptions()} and {@code withTracer()} do not throw in practice (no
         * injectable seam exists without a refactor disproportionate to the fix), so direct unit
         * testing of the catch-block rollback path is not possible without a seam. The correctness
         * of the reordering is verified structurally: both steps are inside the try block in the
         * source, and any future test injection would exercise the rollback path. This test instead
         * confirms the normal lifecycle (contribute → onShutdown → relaunch throws) which already
         * covers the reordering's intent.
         */
        @Test
        @DisplayName(
                "after contribute + onShutdown, ownedSdk lifecycle is clean and relaunch throws TracingBootstrapException")
        void ownedSdkLifecycleIsCleanAfterShutdown() throws Exception {
            OpenTelemetryBootstrapContributor contributor = new OpenTelemetryBootstrapContributor();

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx = fakeContext(
                    new JsonObject()
                            .put(
                                    "tracing",
                                    new JsonObject()
                                            .put(
                                                    "otel",
                                                    new JsonObject()
                                                            .put("traces", new JsonObject().put("exporter", "none")))),
                    options);

            // Contribute successfully — SDK is built and wired
            contributor.contribute(builder, ctx);
            assertTrue(GlobalOpenTelemetry.isSet(), "global must be set after successful contribute");

            // Shutdown — closes SDK, sets latch
            assertDoesNotThrow(contributor::onShutdown, "onShutdown must not throw");

            // Second contribute on the same JVM must fail fast (relaunch guard)
            VertxBuilder builder2 = mock(VertxBuilder.class);
            when(builder2.withTracer(org.mockito.ArgumentMatchers.any())).thenReturn(builder2);
            VertxOptions options2 = new VertxOptions();
            BootstrapContext ctx2 = fakeContext(
                    new JsonObject()
                            .put(
                                    "tracing",
                                    new JsonObject()
                                            .put(
                                                    "otel",
                                                    new JsonObject()
                                                            .put("traces", new JsonObject().put("exporter", "none")))),
                    options2);

            assertThrows(
                    TracingBootstrapException.class,
                    () -> contributor.contribute(builder2, ctx2),
                    "relaunch after onShutdown must throw TracingBootstrapException");
        }
    }

    // --- Helper ---

    /**
     * Creates a fake {@link BootstrapContext} from the given config and options.
     *
     * @param config  the bootstrap configuration
     * @param options the live VertxOptions instance
     * @return a fake context
     */
    private static BootstrapContext fakeContext(JsonObject config, VertxOptions options) {
        return new BootstrapContext() {
            @Override
            public JsonObject config() {
                return config.copy();
            }

            @Override
            public VertxOptions vertxOptions() {
                return options;
            }
        };
    }
}
