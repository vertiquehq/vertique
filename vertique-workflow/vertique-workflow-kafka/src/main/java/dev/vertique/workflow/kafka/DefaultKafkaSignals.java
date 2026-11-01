// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.kafka.CommitStrategy;
import dev.vertique.kafka.ErrorStrategy;
import dev.vertique.kafka.KafkaConsumerBinding;
import dev.vertique.kafka.KafkaRecordFilter;
import dev.vertique.kafka.KafkaTerminalFailure;
import dev.vertique.workflow.services.source.AbstractSignalSourceBuilder;
import dev.vertique.workflow.services.source.SignalSourcePlan;
import dev.vertique.workflow.services.source.WorkflowSourceDelivery;
import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * Builds the Kafka consumer binding of a signal source. The shared stages and the construction
 * checks are inherited; this class adds the stages of the topic and assembles the binding.
 *
 * @param <T> the type of the decoded record value
 */
final class DefaultKafkaSignals<T> extends AbstractSignalSourceBuilder<T, KafkaSignals<T>, KafkaConsumerBinding<T>>
        implements KafkaSignals<T> {

    private static final String TRANSPORT = "kafka";

    private final WorkflowSourceDelivery delivery;
    private final Class<T> type;
    private @Nullable String topic;
    private @Nullable String groupId;
    private @Nullable String name;
    private @Nullable KafkaRecordFilter filter;
    private boolean dlqReplay;
    private boolean built;

    DefaultKafkaSignals(WorkflowSourceDelivery delivery, Class<T> type) {
        super(type);
        this.delivery = delivery;
        this.type = type;
    }

    @Override
    public KafkaSignals<T> topic(String topic) {
        requireOpen();
        this.topic = requireText(topic, "topic");
        return this;
    }

    @Override
    public KafkaSignals<T> groupId(String groupId) {
        requireOpen();
        this.groupId = requireText(groupId, "groupId");
        return this;
    }

    @Override
    public KafkaSignals<T> name(String name) {
        requireOpen();
        this.name = requireText(name, "name");
        return this;
    }

    @Override
    public KafkaSignals<T> filter(KafkaRecordFilter filter) {
        requireOpen();
        this.filter = Objects.requireNonNull(filter, "filter");
        return this;
    }

    @Override
    public KafkaSignals<T> dlqReplay() {
        requireOpen();
        this.dlqReplay = true;
        return this;
    }

    @Override
    public KafkaConsumerBinding<T> build() {
        String topic = this.topic;
        String groupId = this.groupId;
        if (topic == null) {
            throw new IllegalStateException("the source names no topic");
        }
        if (groupId == null) {
            throw new IllegalStateException("source on topic '" + topic + "' names no groupId");
        }
        String sourceName = name == null ? groupId + "-" + topic : name;
        SignalSourcePlan<T> plan = plan(sourceName);
        built = true;
        KafkaConsumerBinding.Builder<T> binding = KafkaConsumerBinding.builder(sourceName, type)
                .topic(topic)
                .groupId(groupId)
                .commitStrategy(CommitStrategy.MANUAL)
                .errorStrategy(ErrorStrategy.RETRY)
                .forceStrategies()
                .noHeaderContext()
                .ignoreUndecodable(() -> report(sourceName, Outcome.UNDECODABLE))
                .handler(new KafkaSignalHandler<>(
                        sourceName, plan, delivery, dlqReplay, KafkaCorrelationBaseline.create()));
        KafkaRecordFilter given = filter;
        if (given != null) {
            binding.filter(reporting(sourceName, given));
        }
        return binding.build();
    }

    /** Wraps a filter so that a rejection is reported and a throw is terminal and carries its class only. */
    private KafkaRecordFilter reporting(String sourceName, KafkaRecordFilter given) {
        return (key, headers) -> {
            boolean accepted;
            try {
                accepted = given.accept(key, headers);
            } catch (Exception e) {
                report(sourceName, Outcome.STAGE_FAILED);
                throw new KafkaTerminalFailure(errorTypeOf(e));
            }
            if (!accepted) {
                report(sourceName, Outcome.IGNORED);
            }
            return accepted;
        };
    }

    /** The class name alone: the simple name, or the binary name's last segment for an anonymous class. */
    private static String errorTypeOf(Exception e) {
        Class<?> thrown = e.getClass();
        String simple = thrown.getSimpleName();
        if (!simple.isEmpty()) {
            return simple;
        }
        String binary = thrown.getName();
        return binary.substring(binary.lastIndexOf('.') + 1);
    }

    private void report(String sourceName, Outcome outcome) {
        delivery.report(new SignalSourceEvent(sourceName, TRANSPORT, null, null, null, outcome));
    }

    private void requireOpen() {
        if (built) {
            throw new IllegalStateException("the source is already built; a stage cannot follow build()");
        }
    }

    private static String requireText(String value, String stage) {
        Objects.requireNonNull(value, stage);
        if (value.isBlank()) {
            throw new IllegalArgumentException(stage + " must not be blank");
        }
        return value;
    }
}
