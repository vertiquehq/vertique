// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.ErrorAttributes;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for {@link ServerSpanOutcomeInterceptor}.
 *
 * <p>Verifies OTel HTTP semconv-correct outcome recording:
 * <ol>
 *   <li>{@link ServerSpanOutcomeInterceptor#afterResponse} with status 200 → span status UNSET,
 *       no {@code error.type} attribute.</li>
 *   <li>{@code afterResponse} with status 500 + original-error key →
 *       span status ERROR, {@code error.type} set to the throwable's simple class name.</li>
 *   <li>{@code afterResponse} with status 404 → span status stays UNSET (4xx is not an error per
 *       HTTP semconv — client fault, not server fault).</li>
 *   <li>{@link ServerSpanOutcomeInterceptor#onError} → span status stays {@link StatusCode#UNSET}
 *       (status is decided from the final HTTP status, not eagerly at onError); {@code error.type}
 *       is set from the throwable's simple class name.</li>
 *   <li>{@code onError} followed by {@code afterResponse(404)} → status UNSET, error.type set
 *       (semconv: 4xx leaves status UNSET but may carry error.type from the mapped exception).</li>
 *   <li>Span already ENDED before {@code afterResponse} → no exception; OTel no-ops writes
 *       on ended spans; exported span unchanged.</li>
 *   <li>{@link RestSpanKeys#SPAN_KEY} missing from context → falls back to
 *       {@link Span#current()}; with recording span attrs land; with none → no-op, no exception.</li>
 *   <li>NO {@code recordException} ever: after 500/onError paths, exported span has zero
 *       exception events.</li>
 *   <li>W5: {@link ServerSpanOutcomeInterceptor#onRequest} with a valid recording current span →
 *       {@link RestSpanKeys#SPAN_KEY} is set on the routing context. With no/invalid current span
 *       → SPAN_KEY absent, no throw. With SPAN_KEY already set → not overwritten. With {@code rc.put}
 *       throwing → exception swallowed.</li>
 * </ol>
 *
 * <p>All {@link Response} objects are Mockito mocks to avoid a {@code RuntimeDelegate}
 * dependency — this module depends only on {@code vertique-rest-core}, not {@code vertique-rest-jaxrs}.
 */
class ServerSpanOutcomeInterceptorTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    private final ServerSpanOutcomeInterceptor interceptor = new ServerSpanOutcomeInterceptor();

    // --- Test 6: afterResponse 200 → UNSET, no error.type ---

    @Test
    @DisplayName("afterResponse with status 200 → span status UNSET, no error.type attribute")
    void afterResponse200LeavesSpanUnset() {
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
        Span span = tracer.spanBuilder("test").startSpan();

        Map<String, Object> store = new HashMap<>();
        store.put(RestSpanKeys.SPAN_KEY, span);
        RoutingContext rc = mockRoutingContext(store);
        Response response = mockResponse(200);

        interceptor.afterResponse(rc, response);
        span.end();

        List<SpanData> spans = OTEL.getSpans();
        assertEquals(1, spans.size(), "one span must be exported");
        SpanData spanData = spans.get(0);

        assertEquals(StatusCode.UNSET, spanData.getStatus().getStatusCode(), "status must be UNSET for 200");
        assertNull(
                (Object) spanData.getAttributes().get(ErrorAttributes.ERROR_TYPE),
                "error.type must not be set for 200");
    }

    // --- Test 7: afterResponse 500 + original-error → ERROR, error.type set ---

    @Test
    @DisplayName("afterResponse with status 500 + original-error key → span status ERROR, error.type set")
    void afterResponse500WithOriginalErrorSetsErrorStatus() {
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
        Span span = tracer.spanBuilder("test").startSpan();

        IllegalStateException originalError = new IllegalStateException("boom");
        Map<String, Object> store = new HashMap<>();
        store.put(RestSpanKeys.SPAN_KEY, span);
        store.put(RequestInterceptor.ORIGINAL_ERROR_KEY, originalError);
        RoutingContext rc = mockRoutingContext(store);
        Response response = mockResponse(500);

        interceptor.afterResponse(rc, response);
        span.end();

        List<SpanData> spans = OTEL.getSpans();
        assertEquals(1, spans.size(), "one span must be exported");
        SpanData spanData = spans.get(0);

        assertEquals(StatusCode.ERROR, spanData.getStatus().getStatusCode(), "status must be ERROR for 500");
        assertEquals(
                "IllegalStateException",
                spanData.getAttributes().get(ErrorAttributes.ERROR_TYPE),
                "error.type must be the simple class name of the original error");
    }

    // --- Test 7 continued: no exception events ---

    @Test
    @DisplayName("afterResponse 500 path: exported span has zero exception events (no recordException)")
    void afterResponse500HasNoExceptionEvents() {
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
        Span span = tracer.spanBuilder("test").startSpan();

        IllegalStateException originalError = new IllegalStateException("boom");
        Map<String, Object> store = new HashMap<>();
        store.put(RestSpanKeys.SPAN_KEY, span);
        store.put(RequestInterceptor.ORIGINAL_ERROR_KEY, originalError);
        RoutingContext rc = mockRoutingContext(store);
        Response response = mockResponse(500);

        interceptor.afterResponse(rc, response);
        span.end();

        List<SpanData> spans = OTEL.getSpans();
        assertEquals(1, spans.size());
        assertTrue(spans.get(0).getEvents().isEmpty(), "no exception events must be recorded (no recordException)");
    }

    // --- Test 8: afterResponse 404 → UNSET, no error.type (no ORIGINAL_ERROR_KEY) ---

    @Test
    @DisplayName("afterResponse with status 404 (no ORIGINAL_ERROR_KEY) → span status UNSET, no error.type")
    void afterResponse404LeavesSpanUnset() {
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
        Span span = tracer.spanBuilder("test").startSpan();

        Map<String, Object> store = new HashMap<>();
        store.put(RestSpanKeys.SPAN_KEY, span);
        RoutingContext rc = mockRoutingContext(store);
        Response response = mockResponse(404);

        interceptor.afterResponse(rc, response);
        span.end();

        List<SpanData> spans = OTEL.getSpans();
        assertEquals(1, spans.size(), "one span must be exported");
        SpanData spanData = spans.get(0);
        assertEquals(StatusCode.UNSET, spanData.getStatus().getStatusCode(), "status must be UNSET for 404");
        assertNull(
                (Object) spanData.getAttributes().get(ErrorAttributes.ERROR_TYPE),
                "error.type must not be set when ORIGINAL_ERROR_KEY is absent");
    }

    // --- Test 9: onError — semconv-correct: status UNSET, error.type set ---

    @Nested
    @DisplayName("onError — semconv-correct: span status UNSET, error.type set from error class")
    class OnErrorTests {

        /**
         * OTel HTTP semconv: {@code onError} fires BEFORE the exception is mapped to an HTTP status.
         * Setting ERROR here is premature — the exception may map to 4xx (client fault, not server
         * fault). The fix records only {@code error.type}; status is decided later in
         * {@code afterResponse} from the final HTTP status code.
         *
         * <p><b>Changed assertion (fix #3):</b> previously asserted {@link StatusCode#ERROR};
         * now asserts {@link StatusCode#UNSET}.
         */
        @Test
        @DisplayName("onError alone → span status UNSET (not ERROR), error.type set to error's simple class name")
        void onErrorAloneSetsUnsetStatusAndErrorType() {
            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test").startSpan();

            Map<String, Object> store = new HashMap<>();
            store.put(RestSpanKeys.SPAN_KEY, span);
            RoutingContext rc = mockRoutingContext(store);

            IllegalStateException error = new IllegalStateException("pipeline error");
            interceptor.onError(rc, error);
            span.end();

            List<SpanData> spans = OTEL.getSpans();
            assertEquals(1, spans.size(), "one span must be exported");
            SpanData spanData = spans.get(0);

            // Status must be UNSET — onError fires before mapping; only afterResponse knows the final status
            assertEquals(
                    StatusCode.UNSET,
                    spanData.getStatus().getStatusCode(),
                    "onError alone must leave span status UNSET (status decided from final HTTP status)");
            // error.type must be set — semconv-recommended for error attribution on all status codes
            assertEquals(
                    "IllegalStateException",
                    spanData.getAttributes().get(ErrorAttributes.ERROR_TYPE),
                    "error.type must be set to the simple class name of the error");
        }

        /**
         * Full 4xx flow: {@code onError} records {@code error.type}; {@code afterResponse(404)}
         * leaves status UNSET. Per HTTP semconv, 4xx is a client fault — server span status stays
         * UNSET, but {@code error.type} is still useful for attribution.
         */
        @Test
        @DisplayName("onError(404-mapped exception) + afterResponse(404) → status UNSET, error.type set")
        void onErrorThenAfterResponse404LeavesUnset() {
            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test").startSpan();

            IllegalStateException error = new IllegalStateException("not found upstream");
            Map<String, Object> store = new HashMap<>();
            store.put(RestSpanKeys.SPAN_KEY, span);
            store.put(RequestInterceptor.ORIGINAL_ERROR_KEY, error);
            RoutingContext rc = mockRoutingContext(store);

            interceptor.onError(rc, error);
            interceptor.afterResponse(rc, mockResponse(404));
            span.end();

            List<SpanData> spans = OTEL.getSpans();
            assertEquals(1, spans.size(), "one span must be exported");
            SpanData spanData = spans.get(0);

            assertEquals(
                    StatusCode.UNSET,
                    spanData.getStatus().getStatusCode(),
                    "4xx must leave span status UNSET (client fault per HTTP semconv)");
            assertEquals(
                    "IllegalStateException",
                    spanData.getAttributes().get(ErrorAttributes.ERROR_TYPE),
                    "error.type must be set from ORIGINAL_ERROR_KEY for 4xx");
        }

        @Test
        @DisplayName("onError path: exported span has zero exception events (no recordException)")
        void onErrorHasNoExceptionEvents() {
            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test").startSpan();

            Map<String, Object> store = new HashMap<>();
            store.put(RestSpanKeys.SPAN_KEY, span);
            RoutingContext rc = mockRoutingContext(store);

            interceptor.onError(rc, new RuntimeException("fail"));
            span.end();

            assertTrue(
                    OTEL.getSpans().get(0).getEvents().isEmpty(),
                    "no exception events must be emitted (no recordException)");
        }
    }

    // --- Test 10: span already ENDED before afterResponse ---

    @Test
    @DisplayName("span already ended before afterResponse → no exception, exported span unchanged")
    void endedSpanBeforeAfterResponseNoException() {
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
        Span span = tracer.spanBuilder("test").startSpan();
        span.end(); // end it BEFORE calling afterResponse

        // The span has been exported already; OTel no-ops writes on ended spans
        Map<String, Object> store = new HashMap<>();
        store.put(RestSpanKeys.SPAN_KEY, span);
        RoutingContext rc = mockRoutingContext(store);
        Response response = mockResponse(500);

        // Should not throw; OTel API silently ignores writes on ended spans
        assertDoesNotThrow(() -> interceptor.afterResponse(rc, response));

        // Verify the exported span (ended before afterResponse) has UNSET status —
        // the write was a no-op on the already-ended span
        List<SpanData> spans = OTEL.getSpans();
        assertEquals(1, spans.size(), "one span must be in the exporter");
        assertEquals(StatusCode.UNSET, spans.get(0).getStatus().getStatusCode(), "ended span must be unchanged");
    }

    // --- Test 11: SPAN_KEY missing → fallback to Span.current() ---

    @Nested
    @DisplayName("SPAN_KEY missing from context — fallback to Span.current()")
    class SpanKeyMissing {

        @Test
        @DisplayName("when SPAN_KEY is absent and a recording span is current, attrs land on it")
        void spanKeyMissingFallsBackToCurrentSpan() {
            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test").startSpan();

            // No SPAN_KEY in store — store has only the original error key
            Map<String, Object> store = new HashMap<>();
            store.put(RequestInterceptor.ORIGINAL_ERROR_KEY, new IllegalStateException("boom"));
            RoutingContext rc = mockRoutingContext(store);
            Response response = mockResponse(500);

            try (var ignored = span.makeCurrent()) {
                interceptor.afterResponse(rc, response);
            } finally {
                span.end();
            }

            List<SpanData> spans = OTEL.getSpans();
            assertEquals(1, spans.size());
            SpanData spanData = spans.get(0);

            assertEquals(
                    StatusCode.ERROR,
                    spanData.getStatus().getStatusCode(),
                    "status must be ERROR when falling back to Span.current()");
            assertEquals(
                    "IllegalStateException",
                    spanData.getAttributes().get(ErrorAttributes.ERROR_TYPE),
                    "error.type must be set when falling back to Span.current()");
        }

        @Test
        @DisplayName("when SPAN_KEY is absent and no current span → no-op, no exception")
        void spanKeyMissingNoCurrentSpanIsNoOp() {
            // No SPAN_KEY in store, and no current span
            Map<String, Object> store = new HashMap<>();
            RoutingContext rc = mockRoutingContext(store);
            Response response = mockResponse(500);

            // No span is current; Span.current() returns a noop non-recording span
            assertDoesNotThrow(() -> interceptor.afterResponse(rc, response));

            // Nothing exported
            assertTrue(OTEL.getSpans().isEmpty(), "no spans must be exported");
        }
    }

    // --- W5: onRequest early span capture tests ---

    @Nested
    @DisplayName("W5: onRequest captures server span early for pre-dispatch exemplar coverage")
    class OnRequestTests {

        /**
         * With a valid recording span current, {@code onRequest} must store it under
         * {@link RestSpanKeys#SPAN_KEY} so the completion scope can re-establish it for
         * pre-dispatch requests that never reach the operation handler.
         */
        @Test
        @DisplayName("onRequest with valid recording current span → SPAN_KEY set to that span")
        void onRequestWithValidSpanStoresIt() {
            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test").startSpan();

            Map<String, Object> store = new HashMap<>();
            RoutingContext rc = mockRoutingContext(store);

            try (var ignored = span.makeCurrent()) {
                interceptor.onRequest(rc);
            } finally {
                span.end();
            }

            assertSame(span, store.get(RestSpanKeys.SPAN_KEY), "SPAN_KEY must be the current valid span");
        }

        /**
         * With no current span (noop), {@code onRequest} must not populate {@link RestSpanKeys#SPAN_KEY}
         * and must not throw.
         */
        @Test
        @DisplayName("onRequest with no/invalid current span → SPAN_KEY absent, no throw")
        void onRequestWithNoCurrentSpanDoesNotSetKey() {
            Map<String, Object> store = new HashMap<>();
            RoutingContext rc = mockRoutingContext(store);

            // No span current — Span.current() returns the invalid noop span
            assertDoesNotThrow(() -> interceptor.onRequest(rc));

            assertNull(store.get(RestSpanKeys.SPAN_KEY), "SPAN_KEY must not be set when no valid span is current");
        }

        /**
         * If {@link RestSpanKeys#SPAN_KEY} is already set (e.g. by a higher-priority contributor),
         * {@code onRequest} must NOT overwrite it.
         */
        @Test
        @DisplayName("onRequest when SPAN_KEY already set → not overwritten")
        void onRequestDoesNotOverwriteExistingSpanKey() {
            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span existingSpan = tracer.spanBuilder("existing").startSpan();
            Span currentSpan = tracer.spanBuilder("current").startSpan();

            Map<String, Object> store = new HashMap<>();
            store.put(RestSpanKeys.SPAN_KEY, existingSpan);
            RoutingContext rc = mockRoutingContext(store);

            try (var ignored = currentSpan.makeCurrent()) {
                interceptor.onRequest(rc);
            } finally {
                existingSpan.end();
                currentSpan.end();
            }

            assertSame(existingSpan, store.get(RestSpanKeys.SPAN_KEY), "existing SPAN_KEY must not be overwritten");
        }

        /**
         * If {@code rc.put} throws (e.g. a bug in the routing context implementation), the exception
         * must be swallowed — {@code onRequest} must return normally without propagating.
         */
        @Test
        @DisplayName("onRequest with rc.put throwing → exception swallowed, returns normally")
        void onRequestSwallowsExceptionFromRcPut() {
            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test").startSpan();

            RoutingContext rc = mock(RoutingContext.class);
            when(rc.get(any(String.class))).thenReturn(null);
            doThrow(new RuntimeException("rc.put exploded")).when(rc).put(any(), any());

            try (var ignored = span.makeCurrent()) {
                assertDoesNotThrow(() -> interceptor.onRequest(rc), "onRequest must swallow rc.put exceptions");
            } finally {
                span.end();
            }
        }
    }

    // --- Helpers ---

    /**
     * Creates a mock {@link Response} that returns the given HTTP status code from
     * {@link Response#getStatus()}.
     *
     * <p>Uses Mockito to avoid the {@code RuntimeDelegate} dependency that
     * {@code Response.status(code).build()} requires.
     *
     * @param status the HTTP status code
     * @return a mock response with the given status
     */
    private static Response mockResponse(int status) {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(status);
        return response;
    }

    /**
     * Creates a mock {@link RoutingContext} backed by the provided store for {@code put}/{@code get}
     * calls.
     *
     * @param store the backing map for {@code put} and {@code get} operations
     * @return a configured mock routing context
     */
    private static RoutingContext mockRoutingContext(Map<String, Object> store) {
        RoutingContext rc = mock(RoutingContext.class);
        when(rc.put(any(), any())).thenAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return rc;
        });
        when(rc.get(any(String.class))).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
        return rc;
    }
}
