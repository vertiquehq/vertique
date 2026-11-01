// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.workflow.services.source.WorkflowSourceDelivery;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;

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

    /**
     * Creates the entry point over the delivery bean that records the signals.
     *
     * @param delivery the delivery bean, which also receives the source's outcome reports
     */
    @Inject
    public WorkflowKafkaSignals(WorkflowSourceDelivery delivery) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
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
        return new DefaultKafkaSignals<>(delivery, type).topic(topic);
    }
}
