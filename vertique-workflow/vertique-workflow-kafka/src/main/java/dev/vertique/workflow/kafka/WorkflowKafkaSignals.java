// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.workflow.services.source.WorkflowSourceDelivery;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Optional;

/**
 * Starts the Kafka signal sources of an application.
 *
 * <p>Each source reads one topic and turns its records into correlated workflow signals; see
 * {@link KafkaSignals} for the stages. The binding a source builds always commits manually, retries
 * and forces both strategies, so a record is not lost before its signal is recorded.
 */
@Singleton
public final class WorkflowKafkaSignals {

    private final WorkflowSourceDelivery delivery;
    private final CorrelationContextFactory correlationFactory;

    /**
     * Creates the entry point over the delivery bean that records the signals, seeding a source's
     * correlation with the default correlation id generator. An application that supplies its own
     * generator injects this class, which uses the application's {@link CorrelationContextFactory}.
     *
     * @param delivery the delivery bean, which also receives the source's outcome reports
     */
    public WorkflowKafkaSignals(WorkflowSourceDelivery delivery) {
        this(delivery, new CorrelationContextFactory(Optional.empty()));
    }

    /**
     * Creates the entry point over the delivery bean and the application's correlation factory.
     *
     * @param delivery the delivery bean, which also receives the source's outcome reports
     * @param correlationFactory the factory that seeds the correlation of a source that restores it
     *     from record headers, so the ids come from the application's generator
     */
    @Inject
    public WorkflowKafkaSignals(WorkflowSourceDelivery delivery, CorrelationContextFactory correlationFactory) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.correlationFactory = Objects.requireNonNull(correlationFactory, "correlationFactory");
    }

    /**
     * Starts a source over a topic.
     *
     * <p>The source reads the topic as the consumer group stated by {@link KafkaSignals#groupId}, and
     * is named {@code {groupId}-{topic}} unless {@link KafkaSignals#name} replaces it.
     *
     * @param topic the Kafka topic
     * @param type the type of the decoded record value
     * @param <T> the type of the decoded record value
     * @return the source, with no signal yet
     * @throws IllegalArgumentException if {@code topic} is blank
     */
    public <T> KafkaSignals<T> on(String topic, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return new DefaultKafkaSignals<>(delivery, type, correlationFactory).topic(topic);
    }
}
