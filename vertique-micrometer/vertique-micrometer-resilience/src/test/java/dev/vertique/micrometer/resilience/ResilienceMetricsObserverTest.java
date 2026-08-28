// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.resilience;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.dagger.ResilienceModule;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.AttemptCompleted;
import dev.vertique.resilience.spi.event.AttemptStarted;
import dev.vertique.resilience.spi.event.BulkheadAdmitted;
import dev.vertique.resilience.spi.event.BulkheadMode;
import dev.vertique.resilience.spi.event.BulkheadQueueTimedOut;
import dev.vertique.resilience.spi.event.BulkheadQueued;
import dev.vertique.resilience.spi.event.BulkheadRejected;
import dev.vertique.resilience.spi.event.CircuitCallRejected;
import dev.vertique.resilience.spi.event.CircuitState;
import dev.vertique.resilience.spi.event.CircuitStateChanged;
import dev.vertique.resilience.spi.event.ExecutionCompleted;
import dev.vertique.resilience.spi.event.ExecutionStarted;
import dev.vertique.resilience.spi.event.PolicyEvaluationFailed;
import dev.vertique.resilience.spi.event.ResilienceConcern;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.ResilienceFailureCategory;
import dev.vertique.resilience.spi.event.ResilienceOutcomeCategory;
import dev.vertique.resilience.spi.event.RetryExhausted;
import dev.vertique.resilience.spi.event.RetryScheduled;
import dev.vertique.resilience.spi.event.TimeoutTriggered;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Contract tests for the isolated common-resilience Micrometer adapter. */
class ResilienceMetricsObserverTest {

    private static final String OPERATION = "rest-client:method:" + "a".repeat(64);
    private static final String OTHER_OPERATION = "rest-client:method:" + "b".repeat(64);
    private static final String CIRCUIT = "rest-client:method:" + "c".repeat(64);

    @Test
    @DisplayName("all frozen events map to exact meters, tags, and values without forbidden dimensions")
    void mapsOnlyFrozenEventsAndTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResilienceMetricsObserver observer = new ResilienceMetricsObserver(registry, Optional.empty());

        List<ResilienceEvent> events = List.of(
                new ExecutionStarted(OPERATION, 1, Set.of(ResilienceConcern.RETRY)),
                new ExecutionCompleted(OPERATION, 1, ResilienceOutcomeCategory.SUCCESS, 11),
                new AttemptStarted(OPERATION, 1, 1),
                new AttemptCompleted(OPERATION, 1, 1, ResilienceOutcomeCategory.APPLICATION_FAILURE, 12),
                new RetryScheduled(OPERATION, 1, 1, 2, 13, ResilienceFailureCategory.APPLICATION_FAILURE),
                new RetryExhausted(OPERATION, 1, 2, ResilienceFailureCategory.APPLICATION_FAILURE),
                new PolicyEvaluationFailed(
                        OPERATION,
                        1,
                        ResilienceConcern.RETRY,
                        dev.vertique.resilience.PolicyCallbackKind.BACKOFF,
                        IllegalStateException.class.getName()),
                new TimeoutTriggered(OPERATION, 1, 2, 14),
                new CircuitStateChanged(
                        CIRCUIT, CircuitState.CLOSED, CircuitState.OPEN, 1, Optional.empty(), OptionalLong.empty()),
                new CircuitCallRejected(OPERATION, 1, CIRCUIT, CircuitState.OPEN),
                new BulkheadQueued(OPERATION, 1, 1, 2),
                new BulkheadAdmitted(OPERATION, 1, 15, 1),
                new BulkheadRejected(OPERATION, 1, BulkheadMode.QUEUE, 1, 2, 2),
                new BulkheadQueueTimedOut(OPERATION, 1, 16, 20));

        events.forEach(observer::onEvent);

        assertTimer(
                registry, ResilienceMetricsObserver.EXECUTION_METER, 11, "operation", OPERATION, "outcome", "success");
        assertTimer(
                registry,
                ResilienceMetricsObserver.ATTEMPT_METER,
                12,
                "operation",
                OPERATION,
                "outcome",
                "application-failure");
        assertCounter(registry, ResilienceMetricsObserver.RETRIES_METER, "operation", OPERATION);
        assertCounter(registry, ResilienceMetricsObserver.TIMEOUTS_METER, "operation", OPERATION);
        assertCounter(
                registry,
                ResilienceMetricsObserver.CIRCUIT_TRANSITIONS_METER,
                "circuit",
                CIRCUIT,
                "from",
                "closed",
                "to",
                "open");
        assertCounter(
                registry,
                ResilienceMetricsObserver.CIRCUIT_REJECTIONS_METER,
                "operation",
                OPERATION,
                "circuit",
                CIRCUIT,
                "state",
                "open");
        assertCounter(
                registry, ResilienceMetricsObserver.BULKHEAD_REJECTIONS_METER, "operation", OPERATION, "mode", "queue");
        assertTimer(
                registry,
                ResilienceMetricsObserver.BULKHEAD_QUEUE_WAIT_METER,
                15,
                "operation",
                OPERATION,
                "outcome",
                "admitted");
        assertTimer(
                registry,
                ResilienceMetricsObserver.BULKHEAD_QUEUE_WAIT_METER,
                16,
                "operation",
                OPERATION,
                "outcome",
                "timed-out");

        for (Meter meter : registry.getMeters()) {
            assertTrue(meter.getId().getName().startsWith("vertique.resilience."));
            meter.getId().getTags().forEach(tag -> {
                assertFalse(tag.getKey().equals("executionId"));
                assertFalse(tag.getKey().equals("attemptOrdinal"));
                assertFalse(tag.getKey().equals("exception"));
                assertFalse(tag.getValue().contains("IllegalStateException"));
                assertFalse(tag.getValue().contains("secret"));
            });
        }
        assertEquals(
                8,
                registry.getMeters().stream()
                        .map(meter -> meter.getId().getName())
                        .distinct()
                        .count());
    }

    @Test
    @DisplayName("disabled configuration records no meters")
    void disabledDoesNothing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResilienceMetricsObserver observer = new ResilienceMetricsObserver(
                registry, Optional.of(MetricsConfig.builder().enabled(false).build()));

        observer.onEvent(new ExecutionCompleted(OPERATION, 1, ResilienceOutcomeCategory.SUCCESS, 1));

        assertTrue(registry.getMeters().isEmpty());
    }

    @Test
    @DisplayName("registry failures do not escape the observer")
    void registryFailureIsolated() {
        MeterRegistry registry = Mockito.mock(MeterRegistry.class);
        ResilienceMetricsObserver observer = new ResilienceMetricsObserver(registry, Optional.empty());

        assertDoesNotThrow(() -> observer.onEvent(
                new RetryScheduled(OPERATION, 1, 1, 2, 1, ResilienceFailureCategory.APPLICATION_FAILURE)));
    }

    @Test
    @DisplayName("Dagger contributes exactly one observer with the common resilience runtime")
    void daggerWiring() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            TestComponent component =
                    DaggerResilienceMetricsObserverTest_TestComponent.factory().create(vertx, new JsonObject());
            assertEquals(1, component.observers().size());
            assertTrue(component.observers().iterator().next() instanceof ResilienceMetricsObserver);
            assertNotNull(component.resilience());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("circuit/from/to/mode are covered by the Micrometer cardinality guard")
    void newTagKeysAreCardinalityGuarded() throws Exception {
        Class<?> guard = Class.forName("dev.vertique.micrometer.CardinalityGuard");
        Method filters = guard.getDeclaredMethod("filters", MetricsConfig.CardinalityConfig.class);
        filters.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<io.micrometer.core.instrument.config.MeterFilter> meterFilters =
                (List<io.micrometer.core.instrument.config.MeterFilter>) filters.invoke(
                        null,
                        MetricsConfig.builder()
                                .cardinality(MetricsConfig.CardinalityConfig.builder()
                                        .maxTagValuesPerKey(1)
                                        .build())
                                .build()
                                .cardinality());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        meterFilters.forEach(registry.config()::meterFilter);

        for (String key : List.of("circuit", "from", "to", "mode")) {
            String name = "vertique.resilience.test." + key;
            registry.counter(name, key, "first").increment();
            registry.counter(name, key, "second").increment();
            Counter denied = registry.find(name).tag(key, "second").counter();
            assertTrue(denied == null || denied.count() == 0.0, key + " must be capped");
        }
    }

    private static void assertTimer(MeterRegistry registry, String name, long millis, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        assertNotNull(timer, "timer missing: " + name);
        assertEquals(1, timer.count());
        assertEquals(millis, timer.totalTime(TimeUnit.MILLISECONDS));
    }

    private static void assertCounter(MeterRegistry registry, String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        assertNotNull(counter, "counter missing: " + name);
        assertEquals(1.0, counter.count());
    }

    @Singleton
    @Component(
            modules = {
                ResilienceModule.class,
                dev.vertique.micrometer.MicrometerModule.class,
                MicrometerResilienceModule.class,
                ConfigParsingModule.class
            })
    interface TestComponent {
        Set<ResilienceObserver> observers();

        Resilience resilience();

        @Component.Factory
        interface Factory {
            TestComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }
}
