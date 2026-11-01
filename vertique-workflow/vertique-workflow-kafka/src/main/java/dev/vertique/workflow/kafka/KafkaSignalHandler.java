// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.kafka.KafkaDeferredFailure;
import dev.vertique.kafka.KafkaDlqHeaders;
import dev.vertique.kafka.KafkaMessage;
import dev.vertique.kafka.KafkaRecordHandler;
import dev.vertique.kafka.KafkaRecordHeader;
import dev.vertique.kafka.KafkaTerminalFailure;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SystemIdentities;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.workflow.ops.SignalProvenance;
import dev.vertique.workflow.services.source.SignalSourcePlan;
import dev.vertique.workflow.services.source.SourceDisposition;
import dev.vertique.workflow.services.source.SourceEnvelope;
import dev.vertique.workflow.services.source.WorkflowSignalSourceDelivery;
import dev.vertique.workflow.source.SignalMessage;
import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.Optional;

/**
 * The record handler of a built source: it turns one Kafka record into a {@link SignalMessage} and a
 * {@link SourceEnvelope}, runs them through {@link WorkflowSignalSourceDelivery}, and maps the fate the
 * delivery answers onto the consumer's retry and dead-letter handling.
 *
 * <p>A fate of {@code DONE} completes the record. {@code RETRY} fails it with an ordinary exception,
 * so the binding retries it and then takes its exhausted strategy. {@code DEFER} fails it with a
 * {@link KafkaDeferredFailure}, so the binding delivers it again after a backoff without spending a
 * retry. {@code TERMINAL} fails it with a {@link KafkaTerminalFailure}, so it takes the exhausted
 * strategy at once. A failure carries an error type only: never a key, a header or a payload.
 *
 * @param <T> the type of the decoded record value
 */
final class KafkaSignalHandler<T> implements KafkaRecordHandler<T> {

    private static final String TRANSPORT = "kafka";

    /** Stands in for a terminal fate that names no error type. */
    static final String UNSPECIFIED_ERROR_TYPE = "TerminalFailure";

    private final String sourceName;
    private final SignalSourcePlan<T> plan;
    private final WorkflowSignalSourceDelivery delivery;
    private final boolean dlqReplay;
    private final KafkaCorrelationBaseline correlation;

    KafkaSignalHandler(
            String sourceName,
            SignalSourcePlan<T> plan,
            WorkflowSignalSourceDelivery delivery,
            boolean dlqReplay,
            KafkaCorrelationBaseline correlation) {
        this.sourceName = sourceName;
        this.plan = plan;
        this.delivery = delivery;
        this.dlqReplay = dlqReplay;
        this.correlation = correlation;
    }

    @Override
    public Future<Void> handle(KafkaMessage<T> message) {
        if (message.value() == null) {
            report(Outcome.UNDECODABLE);
            return Future.succeededFuture();
        }
        String dedupKey = defaultDedupKey(message);
        String topic = message.topic();
        int partition = message.partition();
        long offset = message.offset();
        SourceEnvelope envelope = new SourceEnvelope(
                TRANSPORT,
                SecurityContexts.system(SystemIdentities.internal("kafka source: " + sourceName)),
                false,
                baseline(message),
                InvocationOrigin.of(DispatchBoundary.KAFKA),
                dedupKey,
                resolvedKey -> SignalProvenance.kafka(sourceName, topic, partition, offset));
        return delivery.deliver(plan, new KafkaSignalMessage<>(message), envelope)
                .compose(KafkaSignalHandler::complete);
    }

    private static Future<Void> complete(SourceDisposition disposition) {
        return switch (disposition.fate()) {
            case DONE -> Future.succeededFuture();
            case RETRY -> Future.failedFuture(new SignalRetryRequested(retryType(disposition.errorType())));
            case DEFER -> Future.failedFuture(new KafkaDeferredFailure(terminalType(disposition.errorType())));
            case TERMINAL -> Future.failedFuture(new KafkaTerminalFailure(terminalType(disposition.errorType())));
        };
    }

    /**
     * The dedup key of a record on a source that names none: the record's own position, or the replayed
     * one. It is {@code null} for a replay record whose source headers cannot be read, so that a
     * {@code .when} or a {@code .dedupBy} stays in charge and only a record that needs the key fails.
     */
    private @Nullable String defaultDedupKey(KafkaMessage<T> message) {
        if (dlqReplay) {
            String topic = headerText(message, KafkaDlqHeaders.SOURCE_TOPIC);
            String partition = headerText(message, KafkaDlqHeaders.SOURCE_PARTITION);
            String offset = headerText(message, KafkaDlqHeaders.SOURCE_OFFSET);
            if (topic != null || partition != null || offset != null) {
                if (topic == null || partition == null || offset == null) {
                    return null;
                }
                Integer replayedPartition = nonNegativeInt(partition);
                Long replayedOffset = nonNegativeLong(offset);
                if (replayedPartition == null || replayedOffset == null) {
                    return null;
                }
                return topic + ":" + replayedPartition + ":" + replayedOffset;
            }
        }
        return message.topic() + ":" + message.partition() + ":" + message.offset();
    }

    private @Nullable CorrelationContext baseline(KafkaMessage<T> message) {
        if (!plan.restoresCorrelationFromHeaders()) {
            return null;
        }
        return correlation.baseline(sourceName, message.headers());
    }

    private void report(Outcome outcome) {
        delivery.report(new SignalSourceEvent(sourceName, TRANSPORT, null, null, null, outcome));
    }

    private static @Nullable String headerText(KafkaMessage<?> message, String name) {
        Optional<KafkaRecordHeader> header = message.headers().lastHeader(name);
        if (header.isEmpty() || !header.get().hasValue()) {
            return null;
        }
        try {
            return header.get().valueAsUtf8();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable Integer nonNegativeInt(String text) {
        try {
            int value = Integer.parseInt(text);
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable Long nonNegativeLong(String text) {
        try {
            long value = Long.parseLong(text);
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String terminalType(@Nullable String errorType) {
        return errorType == null ? UNSPECIFIED_ERROR_TYPE : errorType;
    }

    /** The error type a retry carries, as {@link KafkaTerminalFailure} would keep it, or {@code null} for none. */
    private static @Nullable String retryType(@Nullable String errorType) {
        return errorType == null ? null : new KafkaTerminalFailure(errorType).errorType();
    }

    /** The ordinary failure that makes the binding retry a record; it carries an error type at most. */
    private static final class SignalRetryRequested extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SignalRetryRequested(@Nullable String errorType) {
            super(errorType, null, false, false);
        }
    }

    /** One record, as the source's stages see it. */
    private static final class KafkaSignalMessage<T> implements SignalMessage<T> {

        private final KafkaMessage<T> message;

        KafkaSignalMessage(KafkaMessage<T> message) {
            this.message = message;
        }

        @Override
        public T body() {
            return message.value();
        }

        @Override
        public Optional<String> header(String name) {
            return Optional.ofNullable(headerText(message, name));
        }

        @Override
        public Optional<String> key() {
            return Optional.ofNullable(message.key());
        }
    }
}
