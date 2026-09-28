// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RestServerRequestMetricsListener}.
 *
 * <p>Verifies timer recording with correct tags for success and failure paths, the {@code route} and
 * {@code operation} tags read from the event's {@link RestRequestCompletedEvent#operation() operation},
 * outcome bucketing, negative-duration clamping, registry deduplication, throwing-registry isolation,
 * the {@code metricsEnabled} gate, and the {@code error.type} fallback from {@code failureCode} to
 * {@code wireFailureCode} when no curated failure was recorded.
 *
 * <p>Events carry a test-local {@link RestOperationDescriptor} stub ({@link TestOperation}), one named
 * constant per operation the tests use.
 */
class RestServerRequestMetricsListenerTest {

    // --- Shared fixtures ---

    private static final Instant BASE = Instant.parse("2026-06-12T12:00:00Z");

    /** {@code GET /orders/{id}}, operation {@code getOrder}. */
    private static final RestOperationDescriptor GET_ORDER = new TestOperation("getOrder", "GET", "/orders/{id}");

    /** {@code POST /process}, operation {@code processItem}. */
    private static final RestOperationDescriptor PROCESS_ITEM = new TestOperation("processItem", "POST", "/process");

    /** {@code GET /}, operation {@code op}. */
    private static final RestOperationDescriptor ROOT_OP = new TestOperation("op", "GET", "/");

    /** {@code GET /path}, operation {@code op}. */
    private static final RestOperationDescriptor PATH_OP = new TestOperation("op", "GET", "/path");

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    // --- Helper builders ---

    /**
     * Builds a minimal {@link RestRequestCompletedEvent} with no wire failure recorded.
     *
     * @param method      HTTP method
     * @param operation   the operation the request's route claimed
     * @param statusCode  HTTP response status code
     * @param failureCode failure classification string, or {@code null}
     * @param durationMs  duration in milliseconds (end = start + duration)
     * @return a fully constructed event
     */
    private static RestRequestCompletedEvent event(
            String method, RestOperationDescriptor operation, int statusCode, String failureCode, long durationMs) {
        return event(method, operation, statusCode, failureCode, durationMs, null);
    }

    /**
     * Builds a minimal {@link RestRequestCompletedEvent}, optionally carrying a {@code
     * wireFailureCode} for asserting the {@code error.type} fallback.
     *
     * @param method          HTTP method
     * @param operation       the operation the request's route claimed
     * @param statusCode      HTTP response status code
     * @param failureCode     failure classification string, or {@code null}
     * @param durationMs      duration in milliseconds (end = start + duration)
     * @param wireFailureCode wire-failure classification string, or {@code null}
     * @return a fully constructed event
     */
    private static RestRequestCompletedEvent event(
            String method,
            RestOperationDescriptor operation,
            int statusCode,
            String failureCode,
            long durationMs,
            String wireFailureCode) {
        Instant start = BASE;
        Instant end = start.plusMillis(durationMs);
        return new RestRequestCompletedEvent(
                start,
                end,
                method,
                "/path",
                operation,
                statusCode,
                failureCode,
                null,
                wireFailureCode,
                null,
                null,
                Optional.empty(),
                Map.of());
    }

    /**
     * Builds a {@link #GET_ORDER} event where start == end (zero or negative effective duration).
     *
     * @param start     the start instant
     * @param end       the end instant (may be before start)
     * @param statusCode HTTP response status code
     * @return the event
     */
    private static RestRequestCompletedEvent eventWithTimes(Instant start, Instant end, int statusCode) {
        return new RestRequestCompletedEvent(
                start,
                end,
                "GET",
                "/path",
                GET_ORDER,
                statusCode,
                null,
                null,
                null,
                null,
                null,
                Optional.empty(),
                Map.of());
    }

    /**
     * Looks up the timer by name and all expected tag pairs.
     *
     * @param registry  the meter registry to search
     * @param method    expected {@code method} tag value
     * @param route     expected {@code route} tag value
     * @param operation expected {@code operation} tag value
     * @param status    expected {@code status} tag value (as string)
     * @param outcome   expected {@code outcome} tag value (enum name)
     * @param errorType expected {@code error.type} tag value
     * @return the timer, or {@code null} if not found
     */
    private static Timer findTimer(
            MeterRegistry registry,
            String method,
            String route,
            String operation,
            String status,
            String outcome,
            String errorType) {
        return registry.find(RestServerRequestMetricsListener.METER_NAME)
                .tag(RestServerRequestMetricsListener.TAG_METHOD, method)
                .tag(RestServerRequestMetricsListener.TAG_ROUTE, route)
                .tag(RestServerRequestMetricsListener.TAG_OPERATION, operation)
                .tag(RestServerRequestMetricsListener.TAG_STATUS, status)
                .tag(RestServerRequestMetricsListener.TAG_OUTCOME, outcome)
                .tag(RestServerRequestMetricsListener.TAG_ERROR_TYPE, errorType)
                .timer();
    }

    /**
     * Renders each timer's tags and count, for assertion messages.
     *
     * @param timers the timers to describe
     * @return one {@code [key=value, …] count=n} entry per timer
     */
    private static String describe(Collection<Timer> timers) {
        return timers.stream()
                .map(t -> t.getId().getTags().stream()
                                .map(tag -> tag.getKey() + "=" + tag.getValue())
                                .toList()
                        + " count=" + t.count())
                .toList()
                .toString();
    }

    // =========================================================================
    // Test 1 — success event records timer with correct tags
    // =========================================================================

    @Nested
    @DisplayName("Test 1: success event records timer with correct tags and duration")
    class SuccessEvent {

        @Test
        @DisplayName("GET /orders/{id} 200 → count=1, tags correct, totalTime≈150ms")
        void successEventRecordsTimer() {
            RestServerRequestMetricsListener listener =
                    new RestServerRequestMetricsListener(registry, Optional.empty());

            RestRequestCompletedEvent event = event("GET", GET_ORDER, 200, null, 150);
            listener.onCompleted(event);

            Timer timer = findTimer(registry, "GET", "/orders/{id}", "getOrder", "200", "SUCCESS", "none");
            assertNotNull(timer, "timer with the success event's tags must be present");
            assertEquals(1, timer.count(), "timer must have count=1 after one success event");
            // totalTime in seconds from 150ms → ≈0.15; allow ±5ms tolerance
            assertEquals(
                    0.150,
                    timer.totalTime(java.util.concurrent.TimeUnit.SECONDS),
                    0.005,
                    "totalTime must be approximately 150ms");
        }
    }

    // =========================================================================
    // Test 2 — failure event: outcome=SERVER_ERROR, error.type=class name
    // =========================================================================

    @Nested
    @DisplayName("Test 2: 500 failure event records SERVER_ERROR outcome and error.type from failureCode")
    class FailureEvent {

        @Test
        @DisplayName("POST /process 500 IllegalStateException → outcome=SERVER_ERROR, error.type=IllegalStateException")
        void failureEventRecordsServerError() {
            RestServerRequestMetricsListener listener =
                    new RestServerRequestMetricsListener(registry, Optional.empty());

            RestRequestCompletedEvent event = event("POST", PROCESS_ITEM, 500, "IllegalStateException", 20);
            listener.onCompleted(event);

            Timer timer = findTimer(
                    registry, "POST", "/process", "processItem", "500", "SERVER_ERROR", "IllegalStateException");
            assertNotNull(timer, "timer with the failure event's tags must be present");
            assertEquals(1, timer.count(), "failure timer must have count=1");
        }
    }

    // =========================================================================
    // Test 3 — route and operation tags come from the event's operation (TP-013)
    // =========================================================================

    /**
     * TP-013: the timer's {@code route} and {@code operation} tags are read from the event's
     * {@link RestRequestCompletedEvent#operation() operation}. It replaces the former pre-dispatch
     * case, whose premise, a {@code null} route, no longer exists: every REST event carries the
     * operation its JAX-RS route claimed, and a request no transport claimed yields an HTTP event,
     * which this listener never receives.
     */
    @Nested
    @DisplayName("Test 3: route and operation tags come from the event's operation")
    class OperationTags {

        @Test
        @DisplayName("GET /orders/{id} (getOrder) operation + 200 → one timer: route=/orders/{id}, "
                + "operation=getOrder, outcome=SUCCESS, error.type=none")
        void routeAndOperationTagsComeFromTheOperation() {
            RestServerRequestMetricsListener listener =
                    new RestServerRequestMetricsListener(registry, Optional.empty());

            listener.onCompleted(event("GET", GET_ORDER, 200, null, 5));

            Collection<Timer> timers =
                    registry.find(RestServerRequestMetricsListener.METER_NAME).timers();
            assertEquals(1, timers.size(), "exactly one timer must exist after one event; timers: " + describe(timers));
            Timer timer = findTimer(registry, "GET", "/orders/{id}", "getOrder", "200", "SUCCESS", "none");
            assertNotNull(
                    timer,
                    "the timer must carry route=/orders/{id} and operation=getOrder from the event's operation; "
                            + "timers: " + describe(timers));
            assertEquals(1, timer.count(), "the operation's timer must have count=1");
        }
    }

    // =========================================================================
    // Test 4 — outcome bucket mapping
    // =========================================================================

    @Nested
    @DisplayName("Test 4: outcome bucket mapping from status code")
    class OutcomeBuckets {

        @Test
        @DisplayName("100 → INFORMATIONAL")
        void informational() {
            assertOutcome(100, "INFORMATIONAL");
        }

        @Test
        @DisplayName("204 → SUCCESS")
        void success() {
            assertOutcome(204, "SUCCESS");
        }

        @Test
        @DisplayName("302 → REDIRECTION")
        void redirection() {
            assertOutcome(302, "REDIRECTION");
        }

        @Test
        @DisplayName("404 → CLIENT_ERROR")
        void clientError() {
            assertOutcome(404, "CLIENT_ERROR");
        }

        @Test
        @DisplayName("503 → SERVER_ERROR")
        void serverError() {
            assertOutcome(503, "SERVER_ERROR");
        }

        @Test
        @DisplayName("999 → UNKNOWN")
        void unknown() {
            assertOutcome(999, "UNKNOWN");
        }

        private void assertOutcome(int statusCode, String expectedOutcome) {
            SimpleMeterRegistry r = new SimpleMeterRegistry();
            try {
                RestServerRequestMetricsListener listener = new RestServerRequestMetricsListener(r, Optional.empty());
                RestRequestCompletedEvent ev = event("GET", ROOT_OP, statusCode, null, 10);
                listener.onCompleted(ev);

                Timer t = r.find(RestServerRequestMetricsListener.METER_NAME)
                        .tag(RestServerRequestMetricsListener.TAG_OUTCOME, expectedOutcome)
                        .timer();
                assertEquals(1, t.count(), "status " + statusCode + " must map to outcome " + expectedOutcome);
            } finally {
                r.close();
            }
        }
    }

    // =========================================================================
    // Test 5 — negative duration: end before start → count increments, totalTime 0
    // =========================================================================

    @Nested
    @DisplayName("Test 5: negative duration (end before start) → count=1, totalTime clamped to 0")
    class NegativeDuration {

        @Test
        @DisplayName("end < start → count increments, totalTime=0 (clamped)")
        void negativeDurationClamped() {
            RestServerRequestMetricsListener listener =
                    new RestServerRequestMetricsListener(registry, Optional.empty());

            Instant start = BASE;
            Instant end = start.minusMillis(10); // end before start
            RestRequestCompletedEvent event = eventWithTimes(start, end, 200);
            listener.onCompleted(event);

            Timer timer = registry.find(RestServerRequestMetricsListener.METER_NAME)
                    .tag(RestServerRequestMetricsListener.TAG_STATUS, "200")
                    .timer();
            assertEquals(1, timer.count(), "count must increment even with negative duration");
            assertEquals(
                    0.0,
                    timer.totalTime(java.util.concurrent.TimeUnit.SECONDS),
                    0.0,
                    "totalTime must be clamped to 0 for negative duration");
        }
    }

    // =========================================================================
    // Test 6 — repeated same-tag events → ONE meter, count=2
    // =========================================================================

    @Nested
    @DisplayName("Test 6: two identical-tag events → one timer deduped by registry, count=2")
    class RegistryDedupe {

        @Test
        @DisplayName("two GET 200 events with same tags → single meter with count=2")
        void twoEventsDeduped() {
            RestServerRequestMetricsListener listener =
                    new RestServerRequestMetricsListener(registry, Optional.empty());

            RestRequestCompletedEvent e1 = event("GET", GET_ORDER, 200, null, 100);
            RestRequestCompletedEvent e2 = event("GET", GET_ORDER, 200, null, 50);
            listener.onCompleted(e1);
            listener.onCompleted(e2);

            Timer timer = findTimer(registry, "GET", "/orders/{id}", "getOrder", "200", "SUCCESS", "none");
            assertNotNull(timer, "timer with the two events' shared tags must be present");
            assertEquals(2, timer.count(), "registry must dedup same-tag timer and accumulate count=2");
        }
    }

    // =========================================================================
    // Test 7 — throwing registry → onCompleted does not throw
    // =========================================================================

    @Nested
    @DisplayName("Test 7: throwing registry → onCompleted returns normally without propagating exception")
    class ThrowingRegistry {

        @Test
        @DisplayName("timer() throws RuntimeException → onCompleted returns without throwing")
        void throwingRegistryDoesNotPropagate() {
            MeterRegistry throwingRegistry = new ThrowingMeterRegistry();
            RestServerRequestMetricsListener listener =
                    new RestServerRequestMetricsListener(throwingRegistry, Optional.empty());

            RestRequestCompletedEvent event = event("GET", PATH_OP, 200, null, 10);

            assertDoesNotThrow(
                    () -> listener.onCompleted(event),
                    "onCompleted must swallow exceptions from a misbehaving registry");
        }

        /** A meter registry whose timer creation throws unconditionally. */
        private static final class ThrowingMeterRegistry extends SimpleMeterRegistry {
            @Override
            protected io.micrometer.core.instrument.Timer newTimer(
                    io.micrometer.core.instrument.Meter.Id id,
                    io.micrometer.core.instrument.distribution.DistributionStatisticConfig distributionStatisticConfig,
                    io.micrometer.core.instrument.distribution.pause.PauseDetector pauseDetector) {
                throw new RuntimeException("simulated registry failure");
            }
        }
    }

    // =========================================================================
    // Test 8 — metricsEnabled gate
    // =========================================================================

    @Nested
    @DisplayName("Test 8: metricsEnabled gate")
    class MetricsEnabledGate {

        @Test
        @DisplayName("MetricsConfig.enabled=false → no meters registered, immediate return")
        void disabledSkipsAllMeterWork() {
            RestServerRequestMetricsListener listener = new RestServerRequestMetricsListener(
                    registry, Optional.of(MetricsConfig.builder().enabled(false).build()));

            listener.onCompleted(event("GET", PATH_OP, 200, null, 10));

            assertNull(
                    registry.find(RestServerRequestMetricsListener.METER_NAME).timer(),
                    "no timer must be registered when MetricsConfig.enabled()=false");
        }

        @Test
        @DisplayName("metricsEnabled=Optional.empty() → records (default enabled)")
        void emptyOptionalDefaultsToEnabled() {
            RestServerRequestMetricsListener listener =
                    new RestServerRequestMetricsListener(registry, Optional.empty());

            listener.onCompleted(event("GET", PATH_OP, 200, null, 10));

            Timer timer = registry.find(RestServerRequestMetricsListener.METER_NAME)
                    .tag(RestServerRequestMetricsListener.TAG_STATUS, "200")
                    .timer();
            assertEquals(1, timer.count(), "empty Optional must default to enabled");
        }
    }

    // =========================================================================
    // Test 9 — metricsDisabled_whenConfigEnabledFalse (new MetricsConfig signature)
    // =========================================================================

    @Nested
    @DisplayName("Test 9: Optional.of(MetricsConfig.enabled=false) → no meters")
    class MetricsDisabledWhenConfigEnabledFalse {

        @Test
        @DisplayName("Optional.of(MetricsConfig.enabled=false) → no timer recorded")
        void metricsDisabled_whenConfigEnabledFalse() {
            MetricsConfig config = MetricsConfig.builder().enabled(false).build();
            RestServerRequestMetricsListener listener =
                    new RestServerRequestMetricsListener(registry, Optional.of(config));

            listener.onCompleted(event("GET", PATH_OP, 200, null, 10));

            assertNull(
                    registry.find(RestServerRequestMetricsListener.METER_NAME).timer(),
                    "no timer must be registered when MetricsConfig.enabled()=false");
        }
    }

    // =========================================================================
    // Test 10 — metricsOn_whenAbsent (Optional.empty() → default enabled)
    // =========================================================================

    @Nested
    @DisplayName("Test 10: Optional.empty() → timer recorded (absent → metrics on)")
    class MetricsOnWhenAbsent {

        @Test
        @DisplayName("Optional.empty() → timer count=1 (metrics on by default)")
        void metricsOn_whenAbsent() {
            RestServerRequestMetricsListener listener =
                    new RestServerRequestMetricsListener(registry, Optional.empty());

            listener.onCompleted(event("GET", GET_ORDER, 200, null, 50));

            Timer timer = registry.find(RestServerRequestMetricsListener.METER_NAME)
                    .tag(RestServerRequestMetricsListener.TAG_STATUS, "200")
                    .timer();
            assertEquals(1, timer.count(), "Optional.empty() must default to enabled and record the timer");
        }
    }

    // =========================================================================
    // Test 11 — error.type falls back to wireFailureCode when failureCode is absent (D1=A)
    // =========================================================================

    @Nested
    @DisplayName("Test 11: error.type falls back to wireFailureCode when failureCode is absent")
    class ErrorTypeFallsBackToWireFailureCode {

        @Test
        @DisplayName("200 with null failureCode and non-null wireFailureCode → error.type = wireFailureCode")
        void errorTypeFallsBackToWireFailureCode() {
            RestServerRequestMetricsListener listener =
                    new RestServerRequestMetricsListener(registry, Optional.empty());

            // A truncated-response signature: 200 status, no curated failureCode, but a post-handoff
            // wire failure was recorded.
            RestRequestCompletedEvent event = event("GET", GET_ORDER, 200, null, 10, "ConnectionClosed");
            listener.onCompleted(event);

            Timer timer = registry.find(RestServerRequestMetricsListener.METER_NAME)
                    .tag(RestServerRequestMetricsListener.TAG_STATUS, "200")
                    .tag(RestServerRequestMetricsListener.TAG_ERROR_TYPE, "ConnectionClosed")
                    .timer();
            assertEquals(1, timer.count(), "error.type must fall back to wireFailureCode when failureCode is null");
        }
    }

    // --- Test doubles ---

    /**
     * Test-local {@link RestOperationDescriptor}. Only the identity fields carry values: the listener
     * reads nothing else, so the security policy is {@link SecurityPolicy.None} and every collection is
     * empty.
     *
     * @param operationId   the operation identifier
     * @param httpMethod    the HTTP method
     * @param routeTemplate the route template
     */
    private record TestOperation(String operationId, String httpMethod, String routeTemplate)
            implements RestOperationDescriptor {

        @Override
        public List<String> consumes() {
            return List.of();
        }

        @Override
        public List<String> produces() {
            return List.of();
        }

        @Override
        public SecurityPolicy securityPolicy() {
            return new SecurityPolicy.None();
        }

        @Override
        public List<SecurityRequirementSet> securityRequirementSets() {
            return List.of();
        }

        @Override
        public List<Annotation> methodAnnotations() {
            return List.of();
        }

        @Override
        public List<Annotation> classAnnotations() {
            return List.of();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }
    }
}
