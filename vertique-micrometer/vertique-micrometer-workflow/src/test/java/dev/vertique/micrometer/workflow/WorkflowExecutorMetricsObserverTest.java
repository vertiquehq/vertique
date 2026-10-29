// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.ActorAbsent;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.CommandDeferred;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.CommandSettled;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.LeaseExpired;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.QueueSnapshot;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.TransientRetry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorkflowExecutorMetricsObserverTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void settledCommandsAreCountedAndTimedByKindAndOutcome() {
        // Given an observer over a simple registry
        WorkflowExecutorMetricsObserver observer = observer();

        // When it receives two completed starts and one parked signal
        observer.onEvent(new CommandSettled("START", "COMPLETE", Duration.ofMillis(40)));
        observer.onEvent(new CommandSettled("START", "COMPLETE", Duration.ofMillis(40)));
        observer.onEvent(new CommandSettled("SIGNAL", "PARKED", Duration.ofMillis(5)));

        // Then the counters and the timer carry kind and outcome, in lower case, and no other tag
        assertEquals(2.0, counter("vertique.workflow.commands.settled", "kind", "start", "outcome", "complete"));
        assertEquals(1.0, counter("vertique.workflow.commands.settled", "kind", "signal", "outcome", "parked"));
        Timer timer = registry.get("vertique.workflow.commands.duration")
                .tag("kind", "start")
                .tag("outcome", "complete")
                .timer();
        assertEquals(2, timer.count());
        assertEquals(80.0, timer.totalTime(TimeUnit.MILLISECONDS));
        assertEquals(Set.of("kind", "outcome"), tagKeys("vertique.workflow.commands.settled"));
        assertEquals(Set.of("kind", "outcome"), tagKeys("vertique.workflow.commands.duration"));
    }

    @Test
    void deferralsAreCountedByKind() {
        // Given an observer over a simple registry
        WorkflowExecutorMetricsObserver observer = observer();

        // When it receives two deferrals whose running counts are 1 and 2
        observer.onEvent(new CommandDeferred("START", 1));
        observer.onEvent(new CommandDeferred("START", 2));

        // Then the counter counts one per event, not the sum of the running counts
        assertEquals(2.0, counter("vertique.workflow.commands.deferred", "kind", "start"));
    }

    @Test
    void transientRetriesAreCountedByKind() {
        // Given an observer over a simple registry
        WorkflowExecutorMetricsObserver observer = observer();

        // When it receives two transient retries whose running counts are 1 and 2
        observer.onEvent(new TransientRetry("TASK_COMPLETION", 1));
        observer.onEvent(new TransientRetry("TASK_COMPLETION", 2));

        // Then the counter counts one per event
        assertEquals(2.0, counter("vertique.workflow.commands.transient.retries", "kind", "task_completion"));
    }

    @Test
    void leaseExpiriesAreCountedByKind() {
        // Given an observer over a simple registry
        WorkflowExecutorMetricsObserver observer = observer();

        // When it receives one lease expiry
        observer.onEvent(new LeaseExpired("START"));

        // Then the counter counts one
        assertEquals(1.0, counter("vertique.workflow.commands.lease.expired", "kind", "start"));
    }

    @Test
    void queueSnapshotsSetTheQueueGauges() {
        // Given an observer over a simple registry
        WorkflowExecutorMetricsObserver observer = observer();

        // When it receives two queue snapshots
        observer.onEvent(new QueueSnapshot(7, 2, 3, 1, 0.25));
        observer.onEvent(new QueueSnapshot(4, 0, 1, 2, 0.5));

        // Then every gauge reads the latest snapshot, untagged, and each name is registered once
        assertEquals(4.0, gauge("vertique.workflow.commands.available"));
        assertEquals(0.0, gauge("vertique.workflow.commands.stuck"));
        assertEquals(1.0, gauge("vertique.workflow.commands.parked"));
        assertEquals(2.0, gauge("vertique.workflow.commands.dead"));
        assertEquals(0.5, gauge("vertique.workflow.notification.queue.usage"));
        for (String name : List.of(
                "vertique.workflow.commands.available",
                "vertique.workflow.commands.stuck",
                "vertique.workflow.commands.parked",
                "vertique.workflow.commands.dead",
                "vertique.workflow.notification.queue.usage")) {
            assertEquals(1, registry.find(name).gauges().size(), name);
            assertEquals(Set.of(), tagKeys(name), name);
        }
    }

    @Test
    void queueSnapshotsFromEitherEmitterDoNotZeroEachOthersGauges() {
        // Given an observer over a simple registry
        WorkflowExecutorMetricsObserver observer = observer();

        // When it receives a reporter-shaped snapshot (counts only) then a listener-shaped one (usage only)
        observer.onEvent(new QueueSnapshot(7, 2, 3, 1, 0));
        observer.onEvent(new QueueSnapshot(0, 0, 0, 0, 0.4));

        // Then the counts stay and the usage reads the listener value
        assertEquals(7.0, gauge("vertique.workflow.commands.available"));
        assertEquals(2.0, gauge("vertique.workflow.commands.stuck"));
        assertEquals(3.0, gauge("vertique.workflow.commands.parked"));
        assertEquals(1.0, gauge("vertique.workflow.commands.dead"));
        assertEquals(0.4, gauge("vertique.workflow.notification.queue.usage"));

        // When another reporter-shaped snapshot arrives
        observer.onEvent(new QueueSnapshot(4, 0, 1, 2, 0));

        // Then the counts update and the usage stays
        assertEquals(4.0, gauge("vertique.workflow.commands.available"));
        assertEquals(0.0, gauge("vertique.workflow.commands.stuck"));
        assertEquals(1.0, gauge("vertique.workflow.commands.parked"));
        assertEquals(2.0, gauge("vertique.workflow.commands.dead"));
        assertEquals(0.4, gauge("vertique.workflow.notification.queue.usage"));
    }

    @Test
    void absentActorsAreCountedByKind() {
        // Given an observer over a simple registry
        WorkflowExecutorMetricsObserver observer = observer();

        // When it receives two absent actors of START and one of TIMER
        observer.onEvent(new ActorAbsent("START"));
        observer.onEvent(new ActorAbsent("START"));
        observer.onEvent(new ActorAbsent("TIMER"));

        // Then the counter counts them by kind
        assertEquals(2.0, counter("vertique.workflow.recordings.actor.absent", "kind", "start"));
        assertEquals(1.0, counter("vertique.workflow.recordings.actor.absent", "kind", "timer"));
    }

    @Test
    void registryFailureDoesNotEscapeTheObserver() {
        // Given an observer over a registry whose every operation fails
        WorkflowExecutorMetricsObserver observer = new WorkflowExecutorMetricsObserver(
                Mockito.mock(MeterRegistry.class), MetricsConfig.builder().build());

        // When it receives one event of each variant
        // Then every call returns normally
        for (WorkflowExecutorEvent event : oneOfEach()) {
            assertDoesNotThrow(() -> observer.onEvent(event), event.toString());
        }
    }

    @Test
    void recordsNothingWhenMetricsAreDisabledAndBoundsTags() {
        // Given a disabled observer and an enabled one, each over its own registry
        SimpleMeterRegistry disabledRegistry = new SimpleMeterRegistry();
        WorkflowExecutorMetricsObserver disabled = new WorkflowExecutorMetricsObserver(
                disabledRegistry, MetricsConfig.builder().enabled(false).build());
        WorkflowExecutorMetricsObserver enabled = observer();

        // When the disabled one receives every variant and the enabled one a deferral with a 100-character kind
        oneOfEach().forEach(disabled::onEvent);
        enabled.onEvent(new CommandDeferred("K".repeat(100), 1));

        // Then the disabled registry holds no workflow meter and the enabled kind tag is 64 characters
        assertFalse(disabledRegistry.getMeters().stream()
                .anyMatch(meter -> meter.getId().getName().startsWith("vertique.workflow.")));
        Meter deferred = registry.get("vertique.workflow.commands.deferred").meter();
        assertEquals("k".repeat(64), deferred.getId().getTag("kind"));
    }

    private WorkflowExecutorMetricsObserver observer() {
        return new WorkflowExecutorMetricsObserver(
                registry, MetricsConfig.builder().build());
    }

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private Set<String> tagKeys(String name) {
        return registry.get(name).meters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey)
                .collect(Collectors.toSet());
    }

    private static List<WorkflowExecutorEvent> oneOfEach() {
        return List.of(
                new CommandSettled("START", "COMPLETE", Duration.ofMillis(3)),
                new CommandDeferred("START", 1),
                new TransientRetry("START", 1),
                new LeaseExpired("START"),
                new QueueSnapshot(1, 2, 3, 4, 0.5),
                new ActorAbsent("START"));
    }
}
