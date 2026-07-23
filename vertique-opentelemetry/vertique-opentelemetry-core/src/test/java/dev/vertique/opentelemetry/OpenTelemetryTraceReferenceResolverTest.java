// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.correlation.TraceReference;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link OpenTelemetryTraceReferenceResolver}.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>A valid active span yields a populated {@link TraceReference} with the span's trace/span ids
 *       and source {@code "opentelemetry"}.</li>
 *   <li>An unsampled-but-valid span (SP-10) still yields a populated reference — ids are
 *       correlatable even when the trace is not exported to the trace store.</li>
 *   <li>No current span yields an empty {@link Optional}.</li>
 *   <li>{@link Span#getInvalid()} made current yields an empty {@link Optional}.</li>
 *   <li>When the OpenTelemetry API throws inside the try-block, the catch block logs only the
 *       exception class name — no throwable proxy, no sentinel message text leaked to the log
 *       appender — matching the log-secrecy posture of {@code SecuritySpanEventObserver}.</li>
 * </ol>
 */
class OpenTelemetryTraceReferenceResolverTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    @BeforeEach
    void resetGlobal() {
        GlobalOpenTelemetry.resetForTest();
    }

    @AfterEach
    void resetGlobalAfter() {
        GlobalOpenTelemetry.resetForTest();
    }

    // =========================================================================
    // Test 1 — valid active span yields a populated TraceReference
    // =========================================================================

    @Nested
    @DisplayName("valid active span returns populated TraceReference")
    class ValidActiveSpan {

        @Test
        @DisplayName("current span's traceId and spanId are reflected in TraceReference with source 'opentelemetry'")
        void activeSpanYieldsPopulatedReference() {
            OpenTelemetryTraceReferenceResolver resolver = new OpenTelemetryTraceReferenceResolver();

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                Optional<TraceReference> result = resolver.currentTrace();

                assertTrue(result.isPresent(), "resolver must return a reference for a valid active span");
                TraceReference ref = result.get();
                assertEquals(
                        span.getSpanContext().getTraceId(),
                        ref.traceId(),
                        "traceId must match the active span's traceId");
                assertEquals(
                        span.getSpanContext().getSpanId(), ref.spanId(), "spanId must match the active span's spanId");
                assertEquals("opentelemetry", ref.source(), "source must be 'opentelemetry'");
            } finally {
                span.end();
            }
        }
    }

    // =========================================================================
    // Test 2 — unsampled-but-valid span (SP-10) still yields a populated reference
    // =========================================================================

    @Nested
    @DisplayName("unsampled-but-valid span (SP-10) returns populated TraceReference")
    class UnsampledButValidSpan {

        @Test
        @DisplayName(
                "unsampled span with valid SpanContext yields populated reference (ids may not resolve in trace store)")
        void unsampledSpanYieldsPopulatedReference() {
            OpenTelemetryTraceReferenceResolver resolver = new OpenTelemetryTraceReferenceResolver();

            // Build a separate SDK with alwaysOff sampler so spans are valid but not sampled
            SdkTracerProvider tracerProvider =
                    SdkTracerProvider.builder().setSampler(Sampler.alwaysOff()).build();
            OpenTelemetrySdk sdk =
                    OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();

            Tracer tracer = sdk.getTracer("test-unsampled");
            Span span = tracer.spanBuilder("unsampled-span").startSpan();

            try {
                // Prove the scenario: span is valid, not sampled
                assertTrue(
                        span.getSpanContext().isValid(),
                        "span context must be valid for this unsampled-but-valid test scenario");
                assertFalse(
                        span.getSpanContext().isSampled(), "span must NOT be sampled to exercise the SP-10 scenario");

                try (var ignored = span.makeCurrent()) {
                    Optional<TraceReference> result = resolver.currentTrace();

                    assertTrue(
                            result.isPresent(),
                            "resolver must return a reference even for an unsampled-but-valid span (SP-10)");
                    TraceReference ref = result.get();
                    assertEquals(
                            span.getSpanContext().getTraceId(),
                            ref.traceId(),
                            "traceId must match the unsampled span's traceId");
                    assertEquals(
                            span.getSpanContext().getSpanId(),
                            ref.spanId(),
                            "spanId must match the unsampled span's spanId");
                    assertEquals("opentelemetry", ref.source(), "source must be 'opentelemetry'");
                }
            } finally {
                span.end();
                sdk.close();
            }
        }
    }

    // =========================================================================
    // Test 3 — no current span yields empty
    // =========================================================================

    @Nested
    @DisplayName("no current span returns empty")
    class NoCurrentSpan {

        @Test
        @DisplayName("when no span is current, resolver returns Optional.empty()")
        void noCurrentSpanYieldsEmpty() {
            OpenTelemetryTraceReferenceResolver resolver = new OpenTelemetryTraceReferenceResolver();

            Optional<TraceReference> result = resolver.currentTrace();

            assertTrue(result.isEmpty(), "resolver must return empty when no span is current");
        }
    }

    // =========================================================================
    // Test 4 — Span.getInvalid() made current yields empty
    // =========================================================================

    @Nested
    @DisplayName("invalid span made current returns empty")
    class InvalidSpanCurrent {

        @Test
        @DisplayName("Span.getInvalid() made current yields Optional.empty()")
        void invalidSpanCurrentYieldsEmpty() {
            OpenTelemetryTraceReferenceResolver resolver = new OpenTelemetryTraceReferenceResolver();

            Span invalidSpan = Span.getInvalid();
            assertNotNull(invalidSpan, "Span.getInvalid() must not return null");
            assertFalse(invalidSpan.getSpanContext().isValid(), "invalid span context must report isValid=false");

            try (var ignored = invalidSpan.makeCurrent()) {
                Optional<TraceReference> result = resolver.currentTrace();

                assertTrue(result.isEmpty(), "resolver must return empty when an invalid span is current");
            }
        }
    }

    // =========================================================================
    // Test 5 — catch block logs class name only: no throwable proxy, no sentinel
    // =========================================================================

    @Nested
    @DisplayName("catch block logs class name only — no throwable proxy, no sentinel leakage")
    class CatchBlockLogging {

        /**
         * Verifies that when the OpenTelemetry API throws inside {@link
         * OpenTelemetryTraceReferenceResolver#currentTrace()}, the catch block:
         * <ul>
         *   <li>Returns {@link Optional#empty()} (never propagates the exception)</li>
         *   <li>Emits a WARN log event whose formatted message does NOT contain the sentinel string
         *       {@code "SENTINEL"} (class name only, not the exception message body)</li>
         *   <li>Emits a log event with a {@code null} throwable proxy (the exception is NOT attached
         *       as a throwable argument — only its class name appears in the text)</li>
         * </ul>
         *
         * <p>This mirrors the log-secrecy assertions in
         * {@code SecuritySpanEventObserverTest.CatchBlockLogging}.
         */
        @Test
        @DisplayName("span.getSpanContext() throws RuntimeException('SENTINEL_secret_xyz') → "
                + "currentTrace returns empty, log has no sentinel text, no throwable proxy")
        void catchBlockLogsClassNameOnlyNoThrowableProxy() {
            OpenTelemetryTraceReferenceResolver resolver = new OpenTelemetryTraceReferenceResolver();

            // Capture logs from the resolver's logger
            Logger resolverLogger = (Logger) LoggerFactory.getLogger(OpenTelemetryTraceReferenceResolver.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            resolverLogger.addAppender(appender);

            try {
                // ThrowingContextSpan.getSpanContext() throws the sentinel RuntimeException,
                // which drives the catch block in currentTrace() without relying on Span.current().
                Span throwingSpan = new ThrowingContextSpan("SENTINEL_secret_xyz");
                try (var ignored = throwingSpan.makeCurrent()) {
                    Optional<TraceReference> result = resolver.currentTrace();

                    assertTrue(result.isEmpty(), "currentTrace must return empty when getSpanContext() throws");
                }

                List<ILoggingEvent> events = appender.list;
                assertFalse(events.isEmpty(), "at least one WARN log event must be emitted");

                for (ILoggingEvent event : events) {
                    // The formatted message must NOT contain the sentinel string
                    String msg = event.getFormattedMessage();
                    assertFalse(
                            msg.contains("SENTINEL"),
                            "log message must not contain sentinel string (raw throwable message leaked); got: " + msg);

                    // No throwable proxy must be attached
                    assertNull(
                            event.getThrowableProxy(),
                            "log event must have null throwable proxy (no exception attached); event message: " + msg);
                }
            } finally {
                resolverLogger.detachAppender(appender);
            }
        }
    }

    // =========================================================================
    // --- Test doubles ---
    // =========================================================================

    /**
     * A {@link Span} test double whose {@link #getSpanContext()} always throws a
     * {@link RuntimeException} with the supplied message.
     *
     * <p>Used to drive the catch block in
     * {@link OpenTelemetryTraceReferenceResolver#currentTrace()} so that log-secrecy assertions
     * can verify no throwable proxy is attached to the log record.
     */
    static final class ThrowingContextSpan implements Span {

        private final String throwMessage;

        /**
         * Constructs the throwing span.
         *
         * @param throwMessage the message used in the thrown {@link RuntimeException}
         */
        ThrowingContextSpan(String throwMessage) {
            this.throwMessage = throwMessage;
        }

        @Override
        public SpanContext getSpanContext() {
            throw new RuntimeException(throwMessage);
        }

        @Override
        public boolean isRecording() {
            return true;
        }

        @Override
        public Span addEvent(String name, Attributes attributes) {
            return this;
        }

        @Override
        public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
            return this;
        }

        @Override
        public <T> Span setAttribute(io.opentelemetry.api.common.AttributeKey<T> key, @Nullable T value) {
            return this;
        }

        @Override
        public Span setStatus(StatusCode statusCode, String description) {
            return this;
        }

        @Override
        public Span recordException(Throwable exception, Attributes additionalAttributes) {
            return this;
        }

        @Override
        public Span updateName(String name) {
            return this;
        }

        @Override
        public void end() {}

        @Override
        public void end(long timestamp, TimeUnit unit) {}
    }
}
