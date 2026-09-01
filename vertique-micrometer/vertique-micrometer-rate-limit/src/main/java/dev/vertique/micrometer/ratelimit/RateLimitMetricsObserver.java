// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.ratelimit;

import dev.vertique.codegen.RegisterIntoSet;
import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.ratelimit.RateLimitOutcome;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.ratelimit.spi.event.RateLimitDecisionCompleted;
import dev.vertique.ratelimit.spi.event.RateLimitEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Records the frozen Micrometer meter vocabulary for completed rate-limit admission decisions
 * (contracts/observability.md, "Micrometer adapter").
 */
@Slf4j
@Singleton
@RegisterIntoSet(RateLimitObserver.class)
final class RateLimitMetricsObserver implements RateLimitObserver {

    static final String DECISION_METER = "vertique.ratelimit.decision";
    static final String REJECTIONS_METER = "vertique.ratelimit.rejections";
    static final String FAILURES_METER = "vertique.ratelimit.failures";

    static final String POLICY_TAG = "policy";
    static final String OUTCOME_TAG = "outcome";
    static final String MODE_TAG = "mode";
    static final String CODE_TAG = "code";

    private static final String UNKNOWN_FAILURE_CODE = "unknown";

    private final MeterRegistry registry;
    private final boolean enabled;

    @Inject
    RateLimitMetricsObserver(MeterRegistry registry, Optional<MetricsConfig> metricsConfig) {
        this.registry = registry;
        this.enabled = metricsConfig.map(MetricsConfig::enabled).orElse(true);
    }

    @Override
    public void onEvent(RateLimitEvent event) {
        if (!enabled) {
            return;
        }
        try {
            switch (event) {
                case RateLimitDecisionCompleted completed -> recordDecision(completed);
                default -> {
                    // Events without a frozen meter mapping are intentionally ignored.
                }
            }
        } catch (Exception e) {
            log.warn("RateLimitMetricsObserver failed: {}", e.getClass().getName());
        }
    }

    private void recordDecision(RateLimitDecisionCompleted completed) {
        RateLimitOutcome outcome = completed.outcome();
        recordTimer(
                DECISION_METER,
                Tags.of(
                        POLICY_TAG,
                        completed.policyName(),
                        OUTCOME_TAG,
                        enumValue(outcome),
                        MODE_TAG,
                        enumValue(completed.mode())),
                completed.backendLatencyNanos());

        if (outcome == RateLimitOutcome.QUOTA_EXCEEDED) {
            increment(REJECTIONS_METER, Tags.of(POLICY_TAG, completed.policyName()));
        }

        if (outcome == RateLimitOutcome.BACKEND_FAILURE_OPEN || outcome == RateLimitOutcome.BACKEND_FAILURE_CLOSED) {
            increment(
                    FAILURES_METER,
                    Tags.of(
                            POLICY_TAG,
                            completed.policyName(),
                            CODE_TAG,
                            completed
                                    .failureCode()
                                    .map(RateLimitMetricsObserver::enumValue)
                                    .orElse(UNKNOWN_FAILURE_CODE),
                            MODE_TAG,
                            enumValue(completed.mode())));
        }
    }

    private void recordTimer(String name, Tags tags, long elapsedNanos) {
        Timer.builder(name).tags(tags).register(registry).record(Duration.ofNanos(elapsedNanos));
    }

    private void increment(String name, Tags tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }

    private static String enumValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
