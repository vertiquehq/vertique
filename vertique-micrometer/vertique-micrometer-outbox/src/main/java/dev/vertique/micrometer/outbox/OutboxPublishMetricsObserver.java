// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.outbox;

import dev.vertique.codegen.RegisterIntoSet;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishCompletedEvent;
import dev.vertique.inboxoutbox.OutboxPublishObserver;
import dev.vertique.inboxoutbox.OutboxPublishOutcome;
import dev.vertique.micrometer.MetricsConfig;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Locale;
import java.util.Optional;

/**
 * Counts outbox publish attempts as bounded Micrometer counters.
 *
 * <p>Three counters, each incremented once per attempt, tagged with the entry's destination type
 * (lower-case kebab-case, at most 32 characters) and nothing else that varies by entry: no
 * destination, aggregate id, entry id, header, payload, error type or message. {@code failures} adds
 * the attempt outcome, and {@code outcomes} the relay's disposition.
 *
 * <p>The observer is fail-open: no exception leaves {@link #onPublishCompleted}, and it reads
 * nothing from the envelope.
 */
@Singleton
@RegisterIntoSet(OutboxPublishObserver.class)
final class OutboxPublishMetricsObserver implements OutboxPublishObserver {

    static final String ATTEMPTS = "vertique.outbox.publish.attempts";
    static final String FAILURES = "vertique.outbox.publish.failures";
    static final String OUTCOMES = "vertique.outbox.publish.outcomes";
    static final String UNRECORDED = "unrecorded";

    private final MeterRegistry registry;
    private final boolean enabled;

    /**
     * Creates the observer with the application meter registry and optional metrics configuration.
     *
     * @param registry      the application-wide meter registry; never {@code null}
     * @param metricsConfig optional metrics configuration; empty means enabled
     */
    @Inject
    OutboxPublishMetricsObserver(MeterRegistry registry, Optional<MetricsConfig> metricsConfig) {
        this.registry = registry;
        this.enabled = metricsConfig.map(MetricsConfig::enabled).orElse(true);
    }

    @Override
    public void onPublishCompleted(OutboxPublishCompletedEvent event, OutboxEnvelope envelope) {
        if (!enabled) {
            return;
        }
        try {
            String destinationType = kebab(event.destinationType().id());
            registry.counter(ATTEMPTS, "destination.type", destinationType).increment();
            if (event.outcome() != OutboxPublishOutcome.SUCCESS) {
                registry.counter(
                                FAILURES,
                                "destination.type",
                                destinationType,
                                "outcome",
                                kebab(event.outcome().name()))
                        .increment();
            }
            String disposition =
                    event.dispositionRecorded() ? kebab(event.disposition().name()) : UNRECORDED;
            registry.counter(OUTCOMES, "destination.type", destinationType, "outcome", disposition)
                    .increment();
        } catch (Throwable failure) {
            if (isFatal(failure)) {
                throw (Error) failure;
            }
            // Telemetry must never alter relay behaviour.
        }
    }

    @SuppressWarnings("removal")
    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }

    private static String kebab(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
