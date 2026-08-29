// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.resilience;

import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.AttemptCompleted;
import dev.vertique.resilience.spi.event.BulkheadAdmitted;
import dev.vertique.resilience.spi.event.BulkheadQueueTimedOut;
import dev.vertique.resilience.spi.event.BulkheadRejected;
import dev.vertique.resilience.spi.event.CircuitCallRejected;
import dev.vertique.resilience.spi.event.CircuitStateChanged;
import dev.vertique.resilience.spi.event.ExecutionCompleted;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.RetryScheduled;
import dev.vertique.resilience.spi.event.TimeoutTriggered;
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

/** Records the frozen Micrometer meter vocabulary for common resilience events. */
@Slf4j
@Singleton
final class ResilienceMetricsObserver implements ResilienceObserver {

    static final String EXECUTION_METER = "vertique.resilience.execution";
    static final String ATTEMPT_METER = "vertique.resilience.attempt";
    static final String RETRIES_METER = "vertique.resilience.retries";
    static final String TIMEOUTS_METER = "vertique.resilience.timeouts";
    static final String CIRCUIT_TRANSITIONS_METER = "vertique.resilience.circuit.transitions";
    static final String CIRCUIT_REJECTIONS_METER = "vertique.resilience.circuit.rejections";
    static final String BULKHEAD_REJECTIONS_METER = "vertique.resilience.bulkhead.rejections";
    static final String BULKHEAD_QUEUE_WAIT_METER = "vertique.resilience.bulkhead.queue.wait";

    static final String OPERATION_TAG = "operation";
    static final String OUTCOME_TAG = "outcome";
    static final String CIRCUIT_TAG = "circuit";
    static final String FROM_TAG = "from";
    static final String TO_TAG = "to";
    static final String STATE_TAG = "state";
    static final String MODE_TAG = "mode";

    private final MeterRegistry registry;
    private final boolean enabled;

    @Inject
    ResilienceMetricsObserver(MeterRegistry registry, Optional<MetricsConfig> metricsConfig) {
        this.registry = registry;
        this.enabled = metricsConfig.map(MetricsConfig::enabled).orElse(true);
    }

    @Override
    public void onEvent(ResilienceEvent event) {
        if (!enabled) {
            return;
        }
        try {
            switch (event) {
                case ExecutionCompleted completed ->
                    recordTimer(
                            EXECUTION_METER,
                            Tags.of(
                                    OPERATION_TAG,
                                    completed.operationKey(),
                                    OUTCOME_TAG,
                                    enumValue(completed.outcome())),
                            completed.elapsedMs());
                case AttemptCompleted completed ->
                    recordTimer(
                            ATTEMPT_METER,
                            Tags.of(
                                    OPERATION_TAG,
                                    completed.operationKey(),
                                    OUTCOME_TAG,
                                    enumValue(completed.outcome())),
                            completed.elapsedMs());
                case RetryScheduled scheduled ->
                    increment(RETRIES_METER, Tags.of(OPERATION_TAG, scheduled.operationKey()));
                case TimeoutTriggered timeout ->
                    increment(TIMEOUTS_METER, Tags.of(OPERATION_TAG, timeout.operationKey()));
                case CircuitStateChanged transition ->
                    increment(
                            CIRCUIT_TRANSITIONS_METER,
                            Tags.of(
                                    CIRCUIT_TAG,
                                    transition.stateKey(),
                                    FROM_TAG,
                                    enumValue(transition.oldState()),
                                    TO_TAG,
                                    enumValue(transition.newState())));
                case CircuitCallRejected rejection ->
                    increment(
                            CIRCUIT_REJECTIONS_METER,
                            Tags.of(
                                    OPERATION_TAG,
                                    rejection.operationKey(),
                                    CIRCUIT_TAG,
                                    rejection.stateKey(),
                                    STATE_TAG,
                                    enumValue(rejection.currentState())));
                case BulkheadRejected rejection ->
                    increment(
                            BULKHEAD_REJECTIONS_METER,
                            Tags.of(OPERATION_TAG, rejection.operationKey(), MODE_TAG, enumValue(rejection.mode())));
                case BulkheadAdmitted admitted ->
                    recordTimer(
                            BULKHEAD_QUEUE_WAIT_METER,
                            Tags.of(OPERATION_TAG, admitted.operationKey(), OUTCOME_TAG, "admitted"),
                            admitted.queueWaitMs());
                case BulkheadQueueTimedOut timedOut ->
                    recordTimer(
                            BULKHEAD_QUEUE_WAIT_METER,
                            Tags.of(OPERATION_TAG, timedOut.operationKey(), OUTCOME_TAG, "timed-out"),
                            timedOut.queueWaitMs());
                default -> {
                    // Events without a frozen meter mapping are intentionally ignored.
                }
            }
        } catch (Exception e) {
            log.warn("ResilienceMetricsObserver failed: {}", e.getClass().getName());
        }
    }

    private void recordTimer(String name, Tags tags, long elapsedMs) {
        Timer.builder(name).tags(tags).register(registry).record(Duration.ofMillis(elapsedMs));
    }

    private void increment(String name, Tags tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }

    private static String enumValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
