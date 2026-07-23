// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.Result;
import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceDispatchMetricsInterceptor}.
 *
 * <p>Verifies timer recording behaviour for successful, failed, recovered, one-way, and
 * unknown-target dispatches; the negative-duration clamp; the repeated-tag deduplication;
 * the throwing-registry isolation; and the {@code metricsEnabled=false} short-circuit.
 */
class ServiceDispatchMetricsInterceptorTest {

    // --- Fixtures ---

    private static final Instant T0 = Instant.parse("2026-06-13T00:00:00Z");
    private static final Instant T1 = T0.plusMillis(42);

    /**
     * Builds a minimal {@link ServiceDispatchContext} for the given address, stableTargetId, and
     * oneWay flag. All other fields are fixed test values.
     *
     * @param address        the event bus address
     * @param stableTargetId the stable target id, may be {@code null}
     * @param oneWay         fire-and-forget flag
     * @return the built context
     */
    private static ServiceDispatchContext ctx(String address, String stableTargetId, boolean oneWay) {
        return new ServiceDispatchContext(
                address,
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

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    // --- Helper ---

    /**
     * Looks up the timer for the given tag values, or returns {@code null} when not found.
     *
     * @param target    the {@code target} tag value
     * @param outcome   the {@code outcome} tag value
     * @param oneway    the {@code oneway} tag value
     * @param errorType the {@code error.type} tag value
     * @return the matched timer, or {@code null}
     */
    private Timer findTimer(String target, String outcome, String oneway, String errorType) {
        return registry.find(ServiceDispatchMetricsInterceptor.METER_NAME)
                .tag(ServiceDispatchMetricsInterceptor.TAG_TARGET, target)
                .tag(ServiceDispatchMetricsInterceptor.TAG_OUTCOME, outcome)
                .tag(ServiceDispatchMetricsInterceptor.TAG_ONEWAY, oneway)
                .tag(ServiceDispatchMetricsInterceptor.TAG_ERROR_TYPE, errorType)
                .timer();
    }

    // --- Test groups ---

    @Nested
    @DisplayName("Successful dispatch")
    class SuccessfulDispatch {

        @Test
        @DisplayName("timer count=1, tags target/outcome=SUCCESS/oneway=false/error.type=none; duration=end-start")
        void successfulResult() {
            var interceptor = new ServiceDispatchMetricsInterceptor(registry, Optional.empty());
            var ctx = ctx("svc/op", "integration.svc.op", false);
            var result = Result.success("ok");

            interceptor.onTerminalComplete(ctx, result, T0, T1);

            Timer timer = findTimer("integration.svc.op", "SUCCESS", "false", "none");
            assertTrue(timer != null, "expected timer to be registered");
            assertEquals(1, timer.count());
            assertEquals(
                    Duration.between(T0, T1).toNanos(),
                    timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS),
                    1_000_000);
        }
    }

    @Nested
    @DisplayName("Failed dispatch")
    class FailedDispatch {

        @Test
        @DisplayName("outcome=ERROR, error.type=IllegalStateException")
        void failedResult() {
            var interceptor = new ServiceDispatchMetricsInterceptor(registry, Optional.empty());
            var ctx = ctx("svc/op", "integration.svc.op", false);
            var result = Result.failure(new IllegalStateException("boom"));

            interceptor.onTerminalComplete(ctx, result, T0, T1);

            Timer timer = findTimer("integration.svc.op", "ERROR", "false", "IllegalStateException");
            assertTrue(timer != null, "expected error timer to be registered");
            assertEquals(1, timer.count());
        }
    }

    @Nested
    @DisplayName("Recovered dispatch")
    class RecoveredDispatch {

        @Test
        @DisplayName("Result.success(null) after recovery => outcome=SUCCESS, error.type=none")
        void recoveredResult() {
            var interceptor = new ServiceDispatchMetricsInterceptor(registry, Optional.empty());
            var ctx = ctx("svc/op", "integration.svc.op", false);
            // Post-recovery success: Result.success with null payload
            var result = Result.success(null);

            interceptor.onTerminalComplete(ctx, result, T0, T1);

            Timer timer = findTimer("integration.svc.op", "SUCCESS", "false", "none");
            assertTrue(timer != null, "recovered dispatch should count as SUCCESS");
            assertEquals(1, timer.count());
        }
    }

    @Nested
    @DisplayName("One-way dispatch")
    class OneWayDispatch {

        @Test
        @DisplayName("oneWay context => oneway=true tag")
        void oneWayTag() {
            var interceptor = new ServiceDispatchMetricsInterceptor(registry, Optional.empty());
            var ctx = ctx("svc/op", "integration.svc.op", true);
            var result = Result.success("done");

            interceptor.onTerminalComplete(ctx, result, T0, T1);

            Timer timer = findTimer("integration.svc.op", "SUCCESS", "true", "none");
            assertTrue(timer != null, "one-way dispatch should have oneway=true tag");
            assertEquals(1, timer.count());
        }
    }

    @Nested
    @DisplayName("Null stableTargetId")
    class NullTarget {

        @Test
        @DisplayName("null stableTargetId => target=UNKNOWN")
        void nullStableTargetId() {
            var interceptor = new ServiceDispatchMetricsInterceptor(registry, Optional.empty());
            var ctx = ctx("delayed-job/addr", null, false);
            var result = Result.success("ok");

            interceptor.onTerminalComplete(ctx, result, T0, T1);

            Timer timer = findTimer("UNKNOWN", "SUCCESS", "false", "none");
            assertTrue(timer != null, "null stableTargetId should produce target=UNKNOWN tag");
            assertEquals(1, timer.count());
        }
    }

    @Nested
    @DisplayName("Negative duration")
    class NegativeDuration {

        @Test
        @DisplayName("negative duration => count increments, totalTime clamped to 0")
        void negativeDuration() {
            var interceptor = new ServiceDispatchMetricsInterceptor(registry, Optional.empty());
            var ctx = ctx("svc/op", "svc.op", false);
            var result = Result.success("ok");

            // endTime before startTime simulates clock skew
            interceptor.onTerminalComplete(ctx, result, T1, T0);

            Timer timer = findTimer("svc.op", "SUCCESS", "false", "none");
            assertTrue(timer != null, "timer should be registered even for negative duration");
            assertEquals(1, timer.count());
            assertEquals(0.0, timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS), 0.0);
        }
    }

    @Nested
    @DisplayName("Repeated same-tag events")
    class RepeatedEvents {

        @Test
        @DisplayName("two calls with same tags => one meter, count=2")
        void repeatedCallsDeduplicateMeter() {
            var interceptor = new ServiceDispatchMetricsInterceptor(registry, Optional.empty());
            var ctx = ctx("svc/op", "svc.op", false);
            var result = Result.success("ok");

            interceptor.onTerminalComplete(ctx, result, T0, T1);
            interceptor.onTerminalComplete(ctx, result, T0, T1);

            Timer timer = findTimer("svc.op", "SUCCESS", "false", "none");
            assertTrue(timer != null);
            assertEquals(2, timer.count());
        }
    }

    @Nested
    @DisplayName("Throwing registry isolation")
    class ThrowingRegistry {

        @Test
        @DisplayName("throwing registry => no exception escapes onTerminalComplete")
        void throwingRegistryDoesNotPropagate() {
            // A throwing MeterRegistry that throws when a new timer is created
            MeterRegistry throwing = new SimpleMeterRegistry() {
                @Override
                protected Timer newTimer(
                        io.micrometer.core.instrument.Meter.Id id,
                        io.micrometer.core.instrument.distribution.DistributionStatisticConfig
                                distributionStatisticConfig,
                        io.micrometer.core.instrument.distribution.pause.PauseDetector pauseDetector) {
                    throw new RuntimeException("registry is broken");
                }
            };

            var interceptor = new ServiceDispatchMetricsInterceptor(throwing, Optional.empty());
            var ctx = ctx("svc/op", "svc.op", false);
            var result = Result.success("ok");

            assertDoesNotThrow(() -> interceptor.onTerminalComplete(ctx, result, T0, T1));
        }
    }

    @Nested
    @DisplayName("metricsEnabled flag")
    class MetricsEnabledFlag {

        @Test
        @DisplayName("MetricsConfig.enabled=false => zero registry interactions")
        void disabledMeansNoRegistryInteraction() {
            var interceptor = new ServiceDispatchMetricsInterceptor(
                    registry, Optional.of(MetricsConfig.builder().enabled(false).build()));
            var ctx = ctx("svc/op", "svc.op", false);
            var result = Result.success("ok");

            interceptor.onTerminalComplete(ctx, result, T0, T1);

            // No meters should be registered when disabled
            assertEquals(
                    0,
                    registry.getMeters().size(),
                    "no meters should be registered when MetricsConfig.enabled()=false");
        }

        @Test
        @DisplayName("Optional.empty() => records normally (default enabled)")
        void emptyOptionalMeansEnabled() {
            var interceptor = new ServiceDispatchMetricsInterceptor(registry, Optional.empty());
            var ctx = ctx("svc/op", "svc.op", false);
            var result = Result.success("ok");

            interceptor.onTerminalComplete(ctx, result, T0, T1);

            Timer timer = findTimer("svc.op", "SUCCESS", "false", "none");
            assertTrue(timer != null, "Optional.empty() should default to enabled and record");
            assertEquals(1, timer.count());
        }
    }

    // =========================================================================
    // metricsDisabled_whenConfigEnabledFalse — new MetricsConfig signature gate test
    // =========================================================================

    @Nested
    @DisplayName("metricsDisabled_whenConfigEnabledFalse: Optional.of(MetricsConfig.enabled=false) → no meter")
    class MetricsDisabledWhenConfigEnabledFalse {

        @Test
        @DisplayName("Optional.of(MetricsConfig.enabled=false) → no timer recorded")
        void metricsDisabled_whenConfigEnabledFalse() {
            MetricsConfig config = MetricsConfig.builder().enabled(false).build();
            var interceptor = new ServiceDispatchMetricsInterceptor(registry, Optional.of(config));
            var ctx = ctx("svc/op", "svc.op", false);
            var result = Result.success("ok");

            interceptor.onTerminalComplete(ctx, result, T0, T1);

            assertEquals(
                    0, registry.getMeters().size(), "no meters should be recorded when MetricsConfig.enabled()=false");
        }
    }

    // =========================================================================
    // metricsOn_whenAbsent — Optional.empty() → default enabled
    // =========================================================================

    @Nested
    @DisplayName("metricsOn_whenAbsent: Optional.empty() → timer IS recorded")
    class MetricsOnWhenAbsent {

        @Test
        @DisplayName("Optional.empty() → timer count=1 (absent → metrics on)")
        void metricsOn_whenAbsent() {
            var interceptor = new ServiceDispatchMetricsInterceptor(registry, Optional.empty());
            var ctx = ctx("svc/op", "integration.svc.op", false);
            var result = Result.success("ok");

            interceptor.onTerminalComplete(ctx, result, T0, T1);

            Timer timer = findTimer("integration.svc.op", "SUCCESS", "false", "none");
            assertTrue(timer != null, "Optional.empty() must default to enabled and record the timer");
            assertEquals(1, timer.count(), "timer count must be 1 for absent config (metrics on)");
        }
    }
}
