// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.outbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntryDisposition;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishCompletedEvent;
import dev.vertique.inboxoutbox.OutboxPublishOutcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link OutboxPublishMetricsObserver} swallows an ordinary failure of the meter registry but lets a fatal
 * error ({@link LinkageError} here) reach the caller.
 */
class OutboxPublishMetricsObserverFatalErrorTest {

    private static MeterRegistry registryThrowing(Throwable failure) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                sneakyThrow(failure);
                return MeterFilterReply.NEUTRAL;
            }
        });
        return registry;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @Test
    @DisplayName("a fatal error from the registry reaches the caller")
    void fatalErrorPropagates() {
        var observer = new OutboxPublishMetricsObserver(
                registryThrowing(new NoClassDefFoundError("simulated")), Optional.empty());
        assertThrows(
                NoClassDefFoundError.class, () -> observer.onPublishCompleted(event(), mock(OutboxEnvelope.class)));
    }

    @Test
    @DisplayName("an ordinary failure from the registry is swallowed")
    void ordinaryFailureIsSwallowed() {
        var observer = new OutboxPublishMetricsObserver(
                registryThrowing(new IllegalStateException("simulated")), Optional.empty());
        assertDoesNotThrow(() -> observer.onPublishCompleted(event(), mock(OutboxEnvelope.class)));
    }

    private static OutboxPublishCompletedEvent event() {
        return new OutboxPublishCompletedEvent(
                "42",
                DestinationType.KAFKA,
                "orders/handle-order-placed",
                0,
                20,
                OutboxPublishOutcome.SUCCESS,
                OutboxEntryDisposition.PUBLISHED,
                true,
                null,
                null,
                Duration.ofMillis(3),
                Instant.now());
    }
}
