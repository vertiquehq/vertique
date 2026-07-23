// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for {@link ServerSpanCompletionScope}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>RC carrying a valid recording span under {@link RestSpanKeys#SPAN_KEY}: {@code open()}
 *       returns a scope after which {@link Span#current()} equals the captured span, and after
 *       {@code close()} the previous {@link Span#current()} is restored.</li>
 *   <li>RC with no span key: returns a no-op {@link AutoCloseable}, {@link Span#current()}
 *       unchanged, no exception thrown.</li>
 *   <li>RC carrying a non-{@link Span} value: returns a no-op, no exception thrown.</li>
 *   <li>Any exception during open: returns a no-op, no exception propagated.</li>
 * </ol>
 */
class ServerSpanCompletionScopeTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    private final ServerSpanCompletionScope scope = new ServerSpanCompletionScope();

    // --- Helpers ---

    /**
     * Creates a mock {@link RoutingContext} that stores and retrieves values from a map.
     *
     * @param store the backing map for {@code rc.get}/{@code rc.put}
     * @return the configured mock
     */
    private static RoutingContext mockRc(Map<String, Object> store) {
        RoutingContext rc = mock(RoutingContext.class);
        when(rc.get(any(String.class))).thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));
        return rc;
    }

    // --- Test 1: valid recording span re-established as current ---

    @Nested
    @DisplayName("RC with valid recording span")
    class ValidSpan {

        @Test
        @DisplayName("open() makes the captured span current; close() restores the previous span")
        void openMakesSpanCurrentAndCloseRestores() throws Exception {
            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span capturedSpan = tracer.spanBuilder("server-span").startSpan();

            Map<String, Object> store = new HashMap<>();
            store.put(RestSpanKeys.SPAN_KEY, capturedSpan);
            RoutingContext rc = mockRc(store);

            // No span current before open
            Span beforeOpen = Span.current();

            AutoCloseable opened = scope.open(rc);
            assertNotNull(opened, "open() must return a non-null closeable");

            // After open: the captured span must be current
            assertEquals(capturedSpan, Span.current(), "captured span must be current after open()");

            opened.close();
            capturedSpan.end();

            // After close: previous span (no-op) restored
            assertEquals(
                    beforeOpen, Span.current(), "Span.current() must be restored to the pre-open value after close()");
        }

        @Test
        @DisplayName("open() returns an io.opentelemetry.context.Scope (implements AutoCloseable)")
        void openReturnsOtelScope() {
            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span capturedSpan = tracer.spanBuilder("scope-type-check").startSpan();
            capturedSpan.end();

            Map<String, Object> store = new HashMap<>();
            store.put(RestSpanKeys.SPAN_KEY, capturedSpan);
            RoutingContext rc = mockRc(store);

            AutoCloseable result = scope.open(rc);
            assertNotNull(result);
            // io.opentelemetry.context.Scope is AutoCloseable — just verify it is non-null and closes
            assertDoesNotThrow(() -> result.close());
        }
    }

    // --- Test 2: RC with no span key ---

    @Nested
    @DisplayName("RC with no span key")
    class NoSpanKey {

        @Test
        @DisplayName("open() returns a no-op AutoCloseable and Span.current() is unchanged")
        void noSpanKeyReturnsNoOp() throws Exception {
            Map<String, Object> store = new HashMap<>(); // empty — no SPAN_KEY
            RoutingContext rc = mockRc(store);

            Span before = Span.current();
            AutoCloseable result = scope.open(rc);
            assertNotNull(result, "open() must return a non-null closeable even with no span key");
            assertEquals(before, Span.current(), "Span.current() must be unchanged when no span is stashed");
            assertDoesNotThrow(() -> result.close());
            assertEquals(before, Span.current(), "Span.current() must remain unchanged after close()");
        }

        @Test
        @DisplayName("open() does not throw when the routing context carries no span")
        void openDoesNotThrowWithNoSpan() {
            Map<String, Object> store = new HashMap<>();
            RoutingContext rc = mockRc(store);
            assertDoesNotThrow(() -> scope.open(rc));
        }
    }

    // --- Test 3: RC with non-Span value ---

    @Nested
    @DisplayName("RC with non-Span value under SPAN_KEY")
    class NonSpanValue {

        @Test
        @DisplayName("open() returns a no-op when SPAN_KEY holds a non-Span value")
        void nonSpanValueReturnsNoOp() throws Exception {
            Map<String, Object> store = new HashMap<>();
            store.put(RestSpanKeys.SPAN_KEY, "not-a-span");
            RoutingContext rc = mockRc(store);

            Span before = Span.current();
            AutoCloseable result = assertDoesNotThrow(() -> scope.open(rc));
            assertNotNull(result);
            assertEquals(before, Span.current(), "Span.current() must not change for a non-Span value");
            assertDoesNotThrow(() -> result.close());
        }
    }

    // --- Test 4: exception during open (rc.get() throws) ---

    @Nested
    @DisplayName("Exception during open")
    class ExceptionDuringOpen {

        @Test
        @DisplayName("open() returns no-op when rc.get() throws; no exception propagated")
        void rcGetThrowsReturnsNoOp() throws Exception {
            RoutingContext rc = mock(RoutingContext.class);
            when(rc.get(any(String.class))).thenThrow(new RuntimeException("simulated rc.get() failure"));

            AutoCloseable result = assertDoesNotThrow(() -> scope.open(rc));
            assertNotNull(result, "open() must return a non-null closeable even when rc.get() throws");
            assertDoesNotThrow(() -> result.close());
        }
    }
}
