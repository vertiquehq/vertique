// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.micrometer.MetricsConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RestServerActiveRequestsInterceptor}.
 *
 * <p>Verifies gauge increment on {@code onRequest}, decrement on end-handler fire, idempotent
 * end-handler, guard-key deduplication for the same routing context, WebSocket upgrade exclusion,
 * throwing-registry isolation, and the {@code metricsEnabled} gate.
 *
 * <p>All tests are RED: the skeleton {@code onRequest} is a no-op. Tests will go green when the
 * real implementation is added in a later slice.
 */
class RestServerActiveRequestsInterceptorTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    // --- Helper: build a mock RoutingContext with a backing map for put/get ---

    /**
     * Creates a mock {@link RoutingContext} backed by a real map for {@code put}/{@code get}
     * calls. The captured end-handler is stored in {@code capturedHandler}.
     *
     * @param capturedHandler reference that receives the registered end handler
     * @param upgradeHeader   value of the {@code Upgrade} request header, or {@code null}
     * @return the mocked routing context
     */
    @SuppressWarnings("unchecked")
    private static RoutingContext mockRc(AtomicReference<Handler<Void>> capturedHandler, String upgradeHeader) {
        RoutingContext rc = mock(RoutingContext.class);
        Map<String, Object> data = new HashMap<>();

        doAnswer(inv -> {
                    data.put(inv.getArgument(0), inv.getArgument(1));
                    return null;
                })
                .when(rc)
                .put(any(String.class), any());

        when(rc.get(any(String.class))).thenAnswer(inv -> data.get(inv.getArgument(0)));

        doAnswer(inv -> {
                    capturedHandler.set((Handler<Void>) inv.getArgument(0));
                    return null;
                })
                .when(rc)
                .addEndHandler(any());

        io.vertx.core.http.HttpServerRequest request = mock(io.vertx.core.http.HttpServerRequest.class);
        when(request.getHeader("Upgrade")).thenReturn(upgradeHeader);
        when(rc.request()).thenReturn(request);

        return rc;
    }

    /**
     * Convenience overload with no upgrade header (normal HTTP request).
     *
     * @param capturedHandler reference that receives the registered end handler
     * @return the mocked routing context
     */
    private static RoutingContext mockRc(AtomicReference<Handler<Void>> capturedHandler) {
        return mockRc(capturedHandler, null);
    }

    /**
     * Returns the current value of the active-requests gauge, or 0.0 if not yet registered.
     *
     * @param registry the meter registry
     * @return the gauge value
     */
    private static double gaugeValue(MeterRegistry registry) {
        Gauge gauge =
                registry.find(RestServerActiveRequestsInterceptor.METER_NAME).gauge();
        return gauge == null ? 0.0 : gauge.value();
    }

    // =========================================================================
    // Test 9 — onRequest increments gauge; end handler decrements it
    // =========================================================================

    @Nested
    @DisplayName("Test 9: onRequest increments active gauge to 1; end handler fires → gauge returns to 0")
    class GaugeLifecycle {

        @Test
        @DisplayName("gauge=1 after onRequest, gauge=0 after end handler fires")
        void gaugeIncrementsAndDecrements() {
            AtomicReference<Handler<Void>> handlerRef = new AtomicReference<>();
            RoutingContext rc = mockRc(handlerRef);
            RestServerActiveRequestsInterceptor interceptor =
                    new RestServerActiveRequestsInterceptor(registry, Optional.empty());

            interceptor.onRequest(rc);
            assertEquals(1.0, gaugeValue(registry), 0.0, "gauge must be 1 after onRequest");

            // fire the captured end handler
            handlerRef.get().handle(null);
            assertEquals(0.0, gaugeValue(registry), 0.0, "gauge must be 0 after end handler fires");
        }
    }

    // =========================================================================
    // Test 10 — end handler fired twice → decrements only once
    // =========================================================================

    @Nested
    @DisplayName("Test 10: end handler fired twice → idempotent, gauge goes to 0 only once")
    class IdempotentEndHandler {

        @Test
        @DisplayName("firing end handler twice → gauge = 0, not -1")
        void endHandlerIsIdempotent() {
            AtomicReference<Handler<Void>> handlerRef = new AtomicReference<>();
            RoutingContext rc = mockRc(handlerRef);
            RestServerActiveRequestsInterceptor interceptor =
                    new RestServerActiveRequestsInterceptor(registry, Optional.empty());

            interceptor.onRequest(rc);
            assertEquals(1.0, gaugeValue(registry), 0.0, "precondition: gauge=1 after onRequest");

            handlerRef.get().handle(null);
            handlerRef.get().handle(null); // fire a second time

            assertEquals(0.0, gaugeValue(registry), 0.0, "gauge must not go negative from double end-handler fire");
        }
    }

    // =========================================================================
    // Test 11 — onRequest called twice on the SAME context (guard key) → increments once
    // =========================================================================

    @Nested
    @DisplayName("Test 11: onRequest called twice on the same routing context → guard key prevents double-increment")
    class GuardKey {

        @Test
        @DisplayName("second onRequest on same context → gauge still = 1 (no double-count)")
        void sameContextNotDoubleIncremented() {
            AtomicReference<Handler<Void>> handlerRef = new AtomicReference<>();
            RoutingContext rc = mockRc(handlerRef);
            RestServerActiveRequestsInterceptor interceptor =
                    new RestServerActiveRequestsInterceptor(registry, Optional.empty());

            interceptor.onRequest(rc);
            interceptor.onRequest(rc); // same routing context, should be ignored

            assertEquals(
                    1.0,
                    gaugeValue(registry),
                    0.0,
                    "gauge must be 1 even when onRequest is called twice for the same routing context");
        }
    }

    // =========================================================================
    // Test 12 — WebSocket upgrade: no increment, no end handler
    // =========================================================================

    @Nested
    @DisplayName("Test 12: WebSocket upgrade request → no gauge increment, no end handler registered")
    class WebSocketUpgrade {

        @Test
        @DisplayName("Upgrade: websocket header → gauge stays at 0, addEndHandler never called")
        void webSocketUpgradeSkipped() {
            AtomicReference<Handler<Void>> handlerRef = new AtomicReference<>();
            RoutingContext rc = mockRc(handlerRef, "websocket");
            RestServerActiveRequestsInterceptor interceptor =
                    new RestServerActiveRequestsInterceptor(registry, Optional.empty());

            interceptor.onRequest(rc);

            assertEquals(0.0, gaugeValue(registry), 0.0, "gauge must not increment for WebSocket upgrade requests");
            verify(rc, never()).addEndHandler(any());
        }
    }

    // =========================================================================
    // Test 13 — throwing registry → no exception escapes
    // =========================================================================

    @Nested
    @DisplayName("Test 13: throwing registry → onRequest returns normally without propagating exception")
    class ThrowingRegistry {

        @Test
        @DisplayName("gauge() throws RuntimeException → onRequest does not propagate")
        void throwingRegistryDoesNotPropagate() {
            MeterRegistry throwingRegistry = new ThrowingMeterRegistry();
            RestServerActiveRequestsInterceptor interceptor =
                    new RestServerActiveRequestsInterceptor(throwingRegistry, Optional.empty());

            AtomicReference<Handler<Void>> handlerRef = new AtomicReference<>();
            RoutingContext rc = mockRc(handlerRef);

            assertDoesNotThrow(
                    () -> interceptor.onRequest(rc), "onRequest must swallow exceptions from a misbehaving registry");
        }

        /** A meter registry whose gauge creation throws unconditionally. */
        private static final class ThrowingMeterRegistry extends SimpleMeterRegistry {
            @Override
            protected <T> io.micrometer.core.instrument.Gauge newGauge(
                    io.micrometer.core.instrument.Meter.Id id,
                    T obj,
                    java.util.function.ToDoubleFunction<T> valueFunction) {
                throw new RuntimeException("simulated registry failure");
            }
        }
    }

    // =========================================================================
    // Test 14 — metricsEnabled=false → no gauge work
    // =========================================================================

    @Nested
    @DisplayName("Test 14: metricsEnabled=false → no gauge work, no end handler registered")
    class MetricsEnabledGate {

        @Test
        @DisplayName("metricsEnabled=false → gauge stays null, addEndHandler never called")
        void disabledSkipsAllGaugeWork() {
            AtomicReference<Handler<Void>> handlerRef = new AtomicReference<>();
            RoutingContext rc = mockRc(handlerRef);
            RestServerActiveRequestsInterceptor interceptor = new RestServerActiveRequestsInterceptor(
                    registry, Optional.of(MetricsConfig.builder().enabled(false).build()));

            interceptor.onRequest(rc);

            assertNull(
                    registry.find(RestServerActiveRequestsInterceptor.METER_NAME)
                            .gauge(),
                    "no gauge must be registered when metricsEnabled=false");
            verify(rc, never()).addEndHandler(any());
        }
    }

    // =========================================================================
    // Test 15 — metricsDisabled_whenConfigEnabledFalse (new MetricsConfig signature)
    // =========================================================================

    @Nested
    @DisplayName("Test 15: Optional.of(MetricsConfig.enabled=false) → no gauge work")
    class MetricsDisabledWhenConfigEnabledFalse {

        @Test
        @DisplayName("Optional.of(MetricsConfig.enabled=false) → no gauge, no end handler")
        void metricsDisabled_whenConfigEnabledFalse() {
            AtomicReference<Handler<Void>> handlerRef = new AtomicReference<>();
            RoutingContext rc = mockRc(handlerRef);
            MetricsConfig config = MetricsConfig.builder().enabled(false).build();
            RestServerActiveRequestsInterceptor interceptor =
                    new RestServerActiveRequestsInterceptor(registry, Optional.of(config));

            interceptor.onRequest(rc);

            assertNull(
                    registry.find(RestServerActiveRequestsInterceptor.METER_NAME)
                            .gauge(),
                    "no gauge must be registered when MetricsConfig.enabled()=false");
            verify(rc, never()).addEndHandler(any());
        }
    }

    // =========================================================================
    // Test 16 — metricsOn_whenAbsent (Optional.empty())
    // =========================================================================

    @Nested
    @DisplayName("Test 16: Optional.empty() → gauge active (default enabled)")
    class MetricsOnWhenAbsent {

        @Test
        @DisplayName("Optional.empty() → gauge increments (metrics on by default)")
        void metricsOn_whenAbsent() {
            AtomicReference<Handler<Void>> handlerRef = new AtomicReference<>();
            RoutingContext rc = mockRc(handlerRef);
            RestServerActiveRequestsInterceptor interceptor =
                    new RestServerActiveRequestsInterceptor(registry, Optional.empty());

            interceptor.onRequest(rc);

            assertEquals(
                    1.0, gaugeValue(registry), 0.0, "gauge must be active when Optional.empty() (absent → metrics on)");
        }
    }
}
