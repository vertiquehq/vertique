// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.outbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntryDisposition;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishCompletedEvent;
import dev.vertique.inboxoutbox.OutboxPublishOutcome;
import dev.vertique.micrometer.MetricsConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OutboxPublishMetricsObserver")
class OutboxPublishMetricsObserverTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxEnvelope envelope = mock(OutboxEnvelope.class);

    private OutboxPublishMetricsObserver observer() {
        return new OutboxPublishMetricsObserver(registry, Optional.empty());
    }

    private static OutboxPublishCompletedEvent event(
            DestinationType type, OutboxPublishOutcome outcome, OutboxEntryDisposition disposition, boolean recorded) {
        return new OutboxPublishCompletedEvent(
                "42",
                type,
                "orders/handle-order-placed",
                0,
                20,
                outcome,
                disposition,
                recorded,
                null,
                "java.io.IOException",
                Duration.ofMillis(3),
                Instant.now());
    }

    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("a successful attempt counts as an attempt and a published outcome, not a failure")
    void successfulAttempt() {
        observer()
                .onPublishCompleted(
                        event(
                                DestinationType.KAFKA,
                                OutboxPublishOutcome.SUCCESS,
                                OutboxEntryDisposition.PUBLISHED,
                                true),
                        envelope);

        assertEquals(1, count(OutboxPublishMetricsObserver.ATTEMPTS, "destination.type", "kafka"));
        assertNull(registry.find(OutboxPublishMetricsObserver.FAILURES).counter());
        assertEquals(
                1, count(OutboxPublishMetricsObserver.OUTCOMES, "destination.type", "kafka", "outcome", "published"));
    }

    @Test
    @DisplayName("a failed attempt counts the attempt outcome as a failure and the relay's disposition as an outcome")
    void failedAttempt() {
        observer()
                .onPublishCompleted(
                        event(
                                DestinationType.DELAYED_JOB,
                                OutboxPublishOutcome.RETRYABLE_FAILURE,
                                OutboxEntryDisposition.DEAD_LETTERED,
                                true),
                        envelope);

        assertEquals(1, count(OutboxPublishMetricsObserver.ATTEMPTS, "destination.type", "delayed-job"));
        assertEquals(
                1,
                count(
                        OutboxPublishMetricsObserver.FAILURES,
                        "destination.type",
                        "delayed-job",
                        "outcome",
                        "retryable-failure"));
        assertEquals(
                1,
                count(
                        OutboxPublishMetricsObserver.OUTCOMES,
                        "destination.type",
                        "delayed-job",
                        "outcome",
                        "dead-lettered"));
    }

    @Test
    @DisplayName("a disposition the repository did not confirm is counted as unrecorded")
    void unrecordedDisposition() {
        observer()
                .onPublishCompleted(
                        event(
                                DestinationType.SERVICE,
                                OutboxPublishOutcome.SUCCESS,
                                OutboxEntryDisposition.PUBLISHED,
                                false),
                        envelope);

        assertEquals(
                1,
                count(OutboxPublishMetricsObserver.OUTCOMES, "destination.type", "service", "outcome", "unrecorded"));
        assertEquals(
                0, count(OutboxPublishMetricsObserver.OUTCOMES, "destination.type", "service", "outcome", "published"));
    }

    @Test
    @DisplayName("a custom destination type id becomes a lower-case kebab-case tag value")
    void customDestinationType() {
        observer()
                .onPublishCompleted(
                        event(
                                DestinationType.of("Camel_Route"),
                                OutboxPublishOutcome.UNRESOLVABLE,
                                OutboxEntryDisposition.DEFERRED,
                                true),
                        envelope);

        assertEquals(1, count(OutboxPublishMetricsObserver.ATTEMPTS, "destination.type", "camel-route"));
        assertEquals(
                1,
                count(
                        OutboxPublishMetricsObserver.FAILURES,
                        "destination.type",
                        "camel-route",
                        "outcome",
                        "unresolvable"));
    }

    @Test
    @DisplayName("no meter carries an entry id, destination, error type or message")
    void tagsAreBounded() {
        observer()
                .onPublishCompleted(
                        event(
                                DestinationType.KAFKA,
                                OutboxPublishOutcome.PERMANENT_FAILURE,
                                OutboxEntryDisposition.DEAD_LETTERED,
                                true),
                        envelope);

        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            assertEquals(
                    true,
                    tag.getKey().equals("destination.type") || tag.getKey().equals("outcome"),
                    "unexpected tag key " + tag.getKey());
        }));
    }

    @Test
    @DisplayName("metrics.enabled=false records nothing")
    void disabledRecordsNothing() {
        MetricsConfig disabled = MetricsConfig.builder().enabled(false).build();

        new OutboxPublishMetricsObserver(registry, Optional.of(disabled))
                .onPublishCompleted(
                        event(
                                DestinationType.KAFKA,
                                OutboxPublishOutcome.SUCCESS,
                                OutboxEntryDisposition.PUBLISHED,
                                true),
                        envelope);

        assertEquals(0, registry.getMeters().size());
    }

    @Test
    @DisplayName("a registry failure does not leave the observer")
    void registryFailureIsSwallowed() {
        MeterRegistry broken = mock(MeterRegistry.class);

        assertDoesNotThrow(() -> new OutboxPublishMetricsObserver(broken, Optional.empty())
                .onPublishCompleted(
                        event(
                                DestinationType.KAFKA,
                                OutboxPublishOutcome.SUCCESS,
                                OutboxEntryDisposition.PUBLISHED,
                                true),
                        envelope));
    }
}
