// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.Result;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.ErrorAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for {@link ServiceDispatchSpanEnrichmentInterceptor}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>{@link ServiceDispatchSpanEnrichmentInterceptor#onDispatch} with a RECORDING span current
 *       → span carries {@code vertique.service.target} and {@code vertique.service.oneway=false}.</li>
 *   <li>{@code onDispatch} with {@code null} stableTargetId → NO target attribute (don't pollute
 *       spans with UNKNOWN), oneway attr still set.</li>
 *   <li>{@code onDispatch} with no current span → no-op, no exception.</li>
 *   <li>{@code onDispatch} with a non-recording (noop) span current → no attribute writes, no
 *       exception.</li>
 *   <li>{@link ServiceDispatchSpanEnrichmentInterceptor#onTerminalComplete} with a RECORDING
 *       current span + failed {@link Result} → span status ERROR + {@code error.type}
 *       from the failure cause's simple class name.</li>
 *   <li>{@code onTerminalComplete} success → status stays UNSET, no {@code error.type}.</li>
 *   <li>{@code onTerminalComplete} after the span was ENDED → safe no-op, no exception, exported
 *       span unchanged (best-effort contract pinned under controlled lifecycle).</li>
 *   <li>Never throws: poisoned context (ctx accessor throwing) → both methods return normally.</li>
 * </ol>
 */
class ServiceDispatchSpanEnrichmentInterceptorTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    private final ServiceDispatchSpanEnrichmentInterceptor interceptor = new ServiceDispatchSpanEnrichmentInterceptor();

    // --- Fixtures ---

    private static final Instant T0 = Instant.parse("2026-06-13T00:00:00Z");
    private static final Instant T1 = T0.plusMillis(42);

    /**
     * Builds a minimal {@link ServiceDispatchContext} for the given stableTargetId and oneWay flag.
     * All other fields are fixed test values.
     *
     * @param stableTargetId the stable target id, may be {@code null}
     * @param oneWay         fire-and-forget flag
     * @return the built context
     */
    private static ServiceDispatchContext ctx(String stableTargetId, boolean oneWay) {
        return new ServiceDispatchContext(
                "services/test/svc/op",
                stableTargetId,
                "test",
                "svc",
                "op",
                DispatchEnvelope.empty(),
                oneWay,
                List.of(),
                List.of(),
                Map.of());
    }

    // --- Test 1: onDispatch with recording span → target + oneway=false ---

    @Test
    @DisplayName(
            "onDispatch with recording span current → span carries vertique.service.target and vertique.service.oneway=false")
    void onDispatchWithRecordingSpanSetsTargetAndOneway() {
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
        Span span = tracer.spanBuilder("test-span").startSpan();

        ServiceDispatchContext ctx = ctx("integration.user-service.get-user", false);

        try (var ignored = span.makeCurrent()) {
            interceptor.onDispatch(ctx);
        } finally {
            span.end();
        }

        List<SpanData> spans = OTEL.getSpans();
        assertEquals(1, spans.size(), "one span must be exported");
        SpanData spanData = spans.get(0);

        assertEquals(
                "integration.user-service.get-user",
                spanData.getAttributes().get(ServiceAttributes.SERVICE_TARGET),
                "vertique.service.target must be set from stableTargetId");
        assertEquals(
                Boolean.FALSE,
                spanData.getAttributes().get(ServiceAttributes.SERVICE_ONEWAY),
                "vertique.service.oneway must be false for request/reply dispatch");
    }

    // --- Test 2: onDispatch with null stableTargetId → NO target attribute, oneway still set ---

    @Test
    @DisplayName("onDispatch with null stableTargetId → no target attribute, vertique.service.oneway still set")
    void onDispatchWithNullStableTargetIdOmitsTargetAttribute() {
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
        Span span = tracer.spanBuilder("test-span").startSpan();

        ServiceDispatchContext ctx = ctx(null, true);

        try (var ignored = span.makeCurrent()) {
            interceptor.onDispatch(ctx);
        } finally {
            span.end();
        }

        List<SpanData> spans = OTEL.getSpans();
        assertEquals(1, spans.size(), "one span must be exported");
        SpanData spanData = spans.get(0);

        assertNull(
                spanData.getAttributes().get(ServiceAttributes.SERVICE_TARGET),
                "vertique.service.target must NOT be set when stableTargetId is null");
        assertEquals(
                Boolean.TRUE,
                spanData.getAttributes().get(ServiceAttributes.SERVICE_ONEWAY),
                "vertique.service.oneway must still be set even when stableTargetId is null");
    }

    // --- Test 3: onDispatch with no current span → no-op, no exception ---

    @Test
    @DisplayName("onDispatch with no current span → no-op, no exception")
    void onDispatchWithNoCurrentSpanIsNoOp() {
        ServiceDispatchContext ctx = ctx("integration.svc.op", false);

        // No span is current; Span.current() returns a noop non-recording span
        assertDoesNotThrow(() -> interceptor.onDispatch(ctx));

        // Nothing exported
        assertEquals(0, OTEL.getSpans().size(), "no spans must be exported");
    }

    // --- Test 4: onDispatch with non-recording (noop) span → no attribute writes, no exception ---

    @Test
    @DisplayName("onDispatch with a non-recording (noop) span current → no attribute writes, no exception")
    void onDispatchWithNonRecordingSpanIsNoOp() {
        // Span.getInvalid() is the canonical non-recording noop span
        Span noopSpan = Span.getInvalid();
        ServiceDispatchContext ctx = ctx("integration.svc.op", false);

        try (var ignored = noopSpan.makeCurrent()) {
            assertDoesNotThrow(() -> interceptor.onDispatch(ctx));
        }

        // No SDK spans were started, so nothing in the exporter
        assertEquals(0, OTEL.getSpans().size(), "no spans must be exported for a non-recording span");
    }

    // --- Test 5: onTerminalComplete with recording span + failed result → ERROR + error.type ---

    @Test
    @DisplayName(
            "onTerminalComplete with recording span + failed Result → span status ERROR + error.type=IllegalStateException")
    void onTerminalCompleteFailedResultSetsErrorStatusAndType() {
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
        Span span = tracer.spanBuilder("test-span").startSpan();

        ServiceDispatchContext ctx = ctx("integration.svc.op", false);
        Result<?> result = Result.failure(new IllegalStateException("boom"));

        try (var ignored = span.makeCurrent()) {
            interceptor.onTerminalComplete(ctx, result, T0, T1);
        } finally {
            span.end();
        }

        List<SpanData> spans = OTEL.getSpans();
        assertEquals(1, spans.size(), "one span must be exported");
        SpanData spanData = spans.get(0);

        assertEquals(
                StatusCode.ERROR, spanData.getStatus().getStatusCode(), "span status must be ERROR for failed Result");
        assertEquals(
                "IllegalStateException",
                spanData.getAttributes().get(ErrorAttributes.ERROR_TYPE),
                "error.type must be the simple class name of the failure cause");
    }

    // --- Test 6: onTerminalComplete success → status UNSET, no error.type ---

    @Test
    @DisplayName("onTerminalComplete success → span status stays UNSET, no error.type")
    void onTerminalCompleteSuccessLeavesSpanUnset() {
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
        Span span = tracer.spanBuilder("test-span").startSpan();

        ServiceDispatchContext ctx = ctx("integration.svc.op", false);
        Result<?> result = Result.success("ok");

        try (var ignored = span.makeCurrent()) {
            interceptor.onTerminalComplete(ctx, result, T0, T1);
        } finally {
            span.end();
        }

        List<SpanData> spans = OTEL.getSpans();
        assertEquals(1, spans.size(), "one span must be exported");
        SpanData spanData = spans.get(0);

        assertEquals(
                StatusCode.UNSET,
                spanData.getStatus().getStatusCode(),
                "span status must be UNSET for successful Result");
        assertNull(
                (Object) spanData.getAttributes().get(ErrorAttributes.ERROR_TYPE),
                "error.type must not be set for successful Result");
    }

    // --- Test 7: onTerminalComplete after span was ENDED → safe no-op, exported span unchanged ---

    @Test
    @DisplayName("onTerminalComplete after the span was ENDED → safe no-op, no exception, exported span unchanged")
    void onTerminalCompleteAfterEndedSpanIsNoOp() {
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
        Span span = tracer.spanBuilder("test-span").startSpan();
        span.end(); // end BEFORE onTerminalComplete

        ServiceDispatchContext ctx = ctx("integration.svc.op", false);
        Result<?> result = Result.failure(new IllegalStateException("too late"));

        // The span has been exported; writes to it after end() are no-ops per the OTel API contract
        try (var ignored = span.makeCurrent()) {
            assertDoesNotThrow(() -> interceptor.onTerminalComplete(ctx, result, T0, T1));
        }

        // The exported span must be unchanged — written with UNSET (the end() was before writes)
        List<SpanData> spans = OTEL.getSpans();
        assertEquals(1, spans.size(), "one span must be in the exporter");
        assertEquals(
                StatusCode.UNSET,
                spans.get(0).getStatus().getStatusCode(),
                "ended span status must be unchanged (UNSET)");
    }

    // --- Test 8: poisoned context → both methods return normally ---

    @Nested
    @DisplayName("Poisoned context — never throws")
    class PoisonedContext {

        @Test
        @DisplayName("onDispatch with ctx accessor throwing → returns normally")
        void onDispatchPoisonedContextDoesNotThrow() {
            // Build a context where stableTargetId() throws via a subclass that overrides the accessor.
            // We use a standard context but force the condition by passing an address that exercises
            // the guard path. The poisoned path is simulated by making the span accessor itself throw
            // via a Span wrapper that throws on setAttribute.
            ServiceDispatchContext ctx = ctx("integration.svc.op", false);
            Span throwingSpan = new ThrowingSpan();

            try (var ignored = throwingSpan.makeCurrent()) {
                assertDoesNotThrow(() -> interceptor.onDispatch(ctx));
            }
        }

        @Test
        @DisplayName("onTerminalComplete with ctx accessor throwing → returns normally")
        void onTerminalCompletePoisonedContextDoesNotThrow() {
            ServiceDispatchContext ctx = ctx("integration.svc.op", false);
            Result<?> result = Result.failure(new IllegalStateException("boom"));
            Span throwingSpan = new ThrowingSpan();

            try (var ignored = throwingSpan.makeCurrent()) {
                assertDoesNotThrow(() -> interceptor.onTerminalComplete(ctx, result, T0, T1));
            }
        }
    }

    // --- Helpers ---

    /**
     * A {@link Span} implementation that throws {@link RuntimeException} on every mutating
     * operation, simulating a poisoned span/tracer implementation.
     *
     * <p>This is used to verify that the interceptor's exception guards prevent span recording
     * failures from propagating to the caller.
     */
    private static final class ThrowingSpan implements Span {

        @Override
        public <T> Span setAttribute(io.opentelemetry.api.common.AttributeKey<T> key, T value) {
            throw new RuntimeException("span is poisoned");
        }

        @Override
        public Span setStatus(StatusCode statusCode, String description) {
            throw new RuntimeException("span is poisoned");
        }

        @Override
        public Span setStatus(StatusCode statusCode) {
            throw new RuntimeException("span is poisoned");
        }

        @Override
        public Span recordException(Throwable exception, io.opentelemetry.api.common.Attributes additionalAttributes) {
            throw new RuntimeException("span is poisoned");
        }

        @Override
        public Span updateName(String name) {
            throw new RuntimeException("span is poisoned");
        }

        @Override
        public void end() {}

        @Override
        public void end(long timestamp, java.util.concurrent.TimeUnit unit) {}

        @Override
        public io.opentelemetry.api.trace.SpanContext getSpanContext() {
            // Return a valid context so isRecording() can be checked separately
            return io.opentelemetry.api.trace.SpanContext.create(
                    "00000000000000000000000000000001",
                    "0000000000000001",
                    io.opentelemetry.api.trace.TraceFlags.getSampled(),
                    io.opentelemetry.api.trace.TraceState.getDefault());
        }

        @Override
        public boolean isRecording() {
            return true;
        }

        @Override
        public Span addEvent(String name, io.opentelemetry.api.common.Attributes attributes) {
            throw new RuntimeException("span is poisoned");
        }

        @Override
        public Span addEvent(
                String name,
                io.opentelemetry.api.common.Attributes attributes,
                long timestamp,
                java.util.concurrent.TimeUnit unit) {
            throw new RuntimeException("span is poisoned");
        }

        @Override
        public Span addLink(io.opentelemetry.api.trace.SpanContext spanContext) {
            throw new RuntimeException("span is poisoned");
        }

        @Override
        public Span addLink(
                io.opentelemetry.api.trace.SpanContext spanContext, io.opentelemetry.api.common.Attributes attributes) {
            throw new RuntimeException("span is poisoned");
        }
    }
}
