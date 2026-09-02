// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.ratelimit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.ratelimit.RateLimitAlgorithmType;
import dev.vertique.ratelimit.RateLimitFailureCode;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitOutcome;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.dagger.RateLimitCoreModule;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.ratelimit.spi.event.RateLimitDecisionCompleted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contract tests for the isolated rate-limit Micrometer adapter (contracts/observability.md,
 * "Micrometer adapter").
 */
class RateLimitMetricsObserverTest {

    private static final String POLICY = "search-quota";
    private static final String POLICY_REVISION = "r1";

    private static final Set<String> ALLOWED_TAG_KEYS = Set.of(
            RateLimitMetricsObserver.POLICY_TAG,
            RateLimitMetricsObserver.OUTCOME_TAG,
            RateLimitMetricsObserver.MODE_TAG,
            RateLimitMetricsObserver.CODE_TAG);

    private static final Pattern IP_SHAPED = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private static final String ROW_TIMER = "shouldRecordDecisionTimerForEveryOutcome";
    private static final String ROW_REJECTIONS = "shouldIncrementRejectionsOnlyForQuotaExceeded";
    private static final String ROW_FAILURES = "shouldIncrementFailuresOnlyForBackendFailureOutcomes";
    private static final String ROW_REDACTION = "shouldNeverTagKeyIdentityOrIp";

    private static Stream<String> rows() {
        return Stream.of(ROW_TIMER, ROW_REJECTIONS, ROW_FAILURES, ROW_REDACTION);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("enforces the frozen vertique.ratelimit.* meter mapping and tag redaction")
    void shouldEnforceFrozenMeterMappingAndRedaction(String row) {
        switch (row) {
            case ROW_TIMER -> shouldRecordDecisionTimerForEveryOutcome();
            case ROW_REJECTIONS -> shouldIncrementRejectionsOnlyForQuotaExceeded();
            case ROW_FAILURES -> shouldIncrementFailuresOnlyForBackendFailureOutcomes();
            case ROW_REDACTION -> shouldNeverTagKeyIdentityOrIp();
            default -> fail("unknown row: " + row);
        }
    }

    // --- Row: shouldRecordDecisionTimerForEveryOutcome ---

    private void shouldRecordDecisionTimerForEveryOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitMetricsObserver observer = new RateLimitMetricsObserver(registry, Optional.empty());

        observer.onEvent(decision(RateLimitOutcome.PERMITTED, 1_000_000L));
        observer.onEvent(decision(RateLimitOutcome.QUOTA_EXCEEDED, 2_000_000L));
        observer.onEvent(decision(RateLimitOutcome.BACKEND_FAILURE_OPEN, 3_000_000L));
        observer.onEvent(decision(RateLimitOutcome.BACKEND_FAILURE_CLOSED, 4_000_000L));
        observer.onEvent(decision(RateLimitOutcome.DISABLED, 5_000_000L));

        assertDecisionTimer(registry, RateLimitOutcome.PERMITTED, 1_000_000L);
        assertDecisionTimer(registry, RateLimitOutcome.QUOTA_EXCEEDED, 2_000_000L);
        assertDecisionTimer(registry, RateLimitOutcome.BACKEND_FAILURE_OPEN, 3_000_000L);
        assertDecisionTimer(registry, RateLimitOutcome.BACKEND_FAILURE_CLOSED, 4_000_000L);
        assertDecisionTimer(registry, RateLimitOutcome.DISABLED, 5_000_000L);
        assertEquals(
                5,
                registry.find(RateLimitMetricsObserver.DECISION_METER).timers().size(),
                "exactly one decision timer entry per outcome");
    }

    private static void assertDecisionTimer(MeterRegistry registry, RateLimitOutcome outcome, long expectedNanos) {
        Timer timer = registry.find(RateLimitMetricsObserver.DECISION_METER)
                .tags(
                        RateLimitMetricsObserver.POLICY_TAG,
                        POLICY,
                        RateLimitMetricsObserver.OUTCOME_TAG,
                        enumValue(outcome),
                        RateLimitMetricsObserver.MODE_TAG,
                        enumValue(RateLimitMode.LOCAL))
                .timer();
        assertNotNull(timer, "decision timer missing for outcome " + outcome);
        assertEquals(1, timer.count(), "decision timer count for " + outcome);
        assertEquals(
                (double) expectedNanos, timer.totalTime(TimeUnit.NANOSECONDS), "decision timer latency for " + outcome);
    }

    // --- Row: shouldIncrementRejectionsOnlyForQuotaExceeded ---

    private void shouldIncrementRejectionsOnlyForQuotaExceeded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitMetricsObserver observer = new RateLimitMetricsObserver(registry, Optional.empty());

        for (RateLimitOutcome outcome : RateLimitOutcome.values()) {
            observer.onEvent(decision(outcome, 1_000_000L));
        }

        assertEquals(
                1,
                registry.find(RateLimitMetricsObserver.REJECTIONS_METER)
                        .counters()
                        .size(),
                "exactly one distinct rejections counter must exist after one QUOTA_EXCEEDED event");
        Counter rejections = registry.find(RateLimitMetricsObserver.REJECTIONS_METER)
                .tags(RateLimitMetricsObserver.POLICY_TAG, POLICY)
                .counter();
        assertNotNull(rejections, "rejections counter missing for QUOTA_EXCEEDED");
        assertEquals(1.0, rejections.count(), "rejections counter after one QUOTA_EXCEEDED event");

        double failuresBeforeSecondQuotaExceeded = sumCounters(registry, RateLimitMetricsObserver.FAILURES_METER);

        // Sensitivity: a second QUOTA_EXCEEDED event must move rejections 1 -> 2 while failures
        // stays unchanged, proving the two counters are wired independently.
        observer.onEvent(decision(RateLimitOutcome.QUOTA_EXCEEDED, 1_000_000L));

        assertEquals(2.0, rejections.count(), "rejections counter after a second QUOTA_EXCEEDED event");
        assertEquals(
                failuresBeforeSecondQuotaExceeded,
                sumCounters(registry, RateLimitMetricsObserver.FAILURES_METER),
                "failures counter must be unaffected by a second QUOTA_EXCEEDED event");
    }

    private static double sumCounters(MeterRegistry registry, String name) {
        return registry.find(name).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    // --- Row: shouldIncrementFailuresOnlyForBackendFailureOutcomes ---

    private void shouldIncrementFailuresOnlyForBackendFailureOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitMetricsObserver observer = new RateLimitMetricsObserver(registry, Optional.empty());

        for (RateLimitOutcome outcome : RateLimitOutcome.values()) {
            observer.onEvent(decision(outcome, 1_000_000L));
        }

        assertEquals(
                2,
                registry.find(RateLimitMetricsObserver.FAILURES_METER)
                        .counters()
                        .size(),
                "exactly the two backend-failure outcomes must record a failures counter");
        assertFailuresCounter(registry, RateLimitOutcome.BACKEND_FAILURE_OPEN, RateLimitFailureCode.TIMEOUT);
        assertFailuresCounter(registry, RateLimitOutcome.BACKEND_FAILURE_CLOSED, RateLimitFailureCode.UNAVAILABLE);
    }

    private static void assertFailuresCounter(
            MeterRegistry registry, RateLimitOutcome outcome, RateLimitFailureCode code) {
        Counter failures = registry.find(RateLimitMetricsObserver.FAILURES_METER)
                .tags(
                        RateLimitMetricsObserver.POLICY_TAG,
                        POLICY,
                        RateLimitMetricsObserver.CODE_TAG,
                        enumValue(code),
                        RateLimitMetricsObserver.MODE_TAG,
                        enumValue(RateLimitMode.LOCAL))
                .counter();
        assertNotNull(failures, "failures counter missing for " + outcome);
        assertEquals(1.0, failures.count(), "failures counter for " + outcome);
    }

    // --- Row: shouldNeverTagKeyIdentityOrIp ---

    private void shouldNeverTagKeyIdentityOrIp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitMetricsObserver observer = new RateLimitMetricsObserver(registry, Optional.empty());

        for (RateLimitOutcome outcome : RateLimitOutcome.values()) {
            observer.onEvent(decision(outcome, 1_000_000L));
        }

        assertFalse(
                registry.getMeters().isEmpty(), "fixture must actually record meters for this check to be meaningful");
        for (Meter meter : registry.getMeters()) {
            assertTrue(
                    meter.getId().getName().startsWith("vertique.ratelimit."),
                    "unexpected meter name: " + meter.getId().getName());
            for (Tag tag : meter.getId().getTags()) {
                assertTrue(ALLOWED_TAG_KEYS.contains(tag.getKey()), "unexpected tag key: " + tag.getKey());
                assertFalse(
                        IP_SHAPED.matcher(tag.getValue()).matches(),
                        "tag value must never be IP-shaped (" + tag.getKey() + "=" + tag.getValue() + ")");
                assertFalse(
                        tag.getValue().contains(":"),
                        "tag value must never contain ':' — the RateLimitKey canonical-encoding separator ("
                                + tag.getKey() + "=" + tag.getValue() + ")");
            }
        }
    }

    // --- TP-002 ---

    @Test
    @DisplayName("a registry failure on a later event never escapes the observer or discards earlier meters")
    void shouldIsolateObserverFailureFromAdmission() {
        SecondTimerThrows registry = new SecondTimerThrows();
        RateLimitMetricsObserver observer = new RateLimitMetricsObserver(registry, Optional.empty());

        observer.onEvent(decision(RateLimitOutcome.PERMITTED, 1_000_000L));
        assertDoesNotThrow(() -> observer.onEvent(decision(RateLimitOutcome.QUOTA_EXCEEDED, 2_000_000L)));

        Timer firstTimer = registry.find(RateLimitMetricsObserver.DECISION_METER)
                .tags(
                        RateLimitMetricsObserver.POLICY_TAG,
                        POLICY,
                        RateLimitMetricsObserver.OUTCOME_TAG,
                        enumValue(RateLimitOutcome.PERMITTED),
                        RateLimitMetricsObserver.MODE_TAG,
                        enumValue(RateLimitMode.LOCAL))
                .timer();
        assertNotNull(firstTimer, "the first event's meters must be recorded despite the later registry failure");
        assertEquals(1, firstTimer.count());
    }

    /** Registers timers normally except its second registration, which throws unconditionally. */
    private static final class SecondTimerThrows extends SimpleMeterRegistry {
        private int registrations;

        @Override
        protected Timer newTimer(
                Meter.Id id, DistributionStatisticConfig distributionStatisticConfig, PauseDetector pauseDetector) {
            registrations++;
            if (registrations == 2) {
                throw new RuntimeException("registry failure on the second timer registration");
            }
            return super.newTimer(id, distributionStatisticConfig, pauseDetector);
        }
    }

    // --- Supporting verification ---

    @Test
    @DisplayName("disabled configuration records no meters")
    void disabledDoesNothing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitMetricsObserver observer = new RateLimitMetricsObserver(
                registry, Optional.of(MetricsConfig.builder().enabled(false).build()));

        observer.onEvent(decision(RateLimitOutcome.PERMITTED, 1_000_000L));

        assertTrue(registry.getMeters().isEmpty());
    }

    @Test
    @DisplayName("Dagger contributes exactly one observer with the rate-limit runtime")
    void daggerWiring() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            TestComponent component =
                    DaggerRateLimitMetricsObserverTest_TestComponent.factory().create(vertx, new JsonObject());
            assertEquals(1, component.observers().size());
            assertTrue(component.observers().iterator().next() instanceof RateLimitMetricsObserver);
            assertNotNull(component.rateLimiters());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("policy/code are covered by the Micrometer cardinality guard")
    void policyAndCodeAreCardinalityGuarded() throws Exception {
        Class<?> guard = Class.forName("dev.vertique.micrometer.CardinalityGuard");
        Method filters = guard.getDeclaredMethod("filters", MetricsConfig.CardinalityConfig.class);
        filters.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<MeterFilter> meterFilters = (List<MeterFilter>) filters.invoke(
                null,
                MetricsConfig.builder()
                        .cardinality(MetricsConfig.CardinalityConfig.builder()
                                .maxTagValuesPerKey(1)
                                .build())
                        .build()
                        .cardinality());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        meterFilters.forEach(registry.config()::meterFilter);

        for (String key : List.of("policy", "code")) {
            String name = "vertique.ratelimit.test." + key;
            registry.counter(name, key, "first").increment();
            registry.counter(name, key, "second").increment();
            Counter denied = registry.find(name).tag(key, "second").counter();
            assertTrue(denied == null || denied.count() == 0.0, key + " must be capped");
        }
    }

    // --- Shared fixtures ---

    private static RateLimitDecisionCompleted decision(RateLimitOutcome outcome, long backendLatencyNanos) {
        return new RateLimitDecisionCompleted(
                POLICY,
                POLICY_REVISION,
                RateLimitMode.LOCAL,
                RateLimitAlgorithmType.TOKEN_BUCKET,
                outcome,
                1L,
                10L,
                OptionalLong.of(9L),
                outcome == RateLimitOutcome.QUOTA_EXCEEDED ? Optional.of(Duration.ofSeconds(1)) : Optional.empty(),
                Optional.of(Duration.ofSeconds(30)),
                failureCodeFor(outcome),
                backendLatencyNanos);
    }

    private static Optional<RateLimitFailureCode> failureCodeFor(RateLimitOutcome outcome) {
        return switch (outcome) {
            case BACKEND_FAILURE_OPEN -> Optional.of(RateLimitFailureCode.TIMEOUT);
            case BACKEND_FAILURE_CLOSED -> Optional.of(RateLimitFailureCode.UNAVAILABLE);
            default -> Optional.empty();
        };
    }

    private static String enumValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @Singleton
    @Component(
            modules = {
                RateLimitCoreModule.class,
                dev.vertique.micrometer.MicrometerModule.class,
                MicrometerRateLimitModule.class,
                ConfigParsingModule.class
            })
    interface TestComponent {
        Set<RateLimitObserver> observers();

        RateLimiters rateLimiters();

        @Component.Factory
        interface Factory {
            TestComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }
}
