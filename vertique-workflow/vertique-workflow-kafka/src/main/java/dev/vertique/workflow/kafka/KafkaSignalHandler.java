// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.correlation.CorrelationContext;
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
import dev.vertique.workflow.services.source.WorkflowSourceDelivery;
import dev.vertique.workflow.source.SignalMessage;
import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The record handler of a built source: it turns one Kafka record into a {@link SignalMessage} and a
 * {@link SourceEnvelope}, runs them through {@link WorkflowSourceDelivery}, and maps the fate the
 * delivery answers onto the consumer's retry and dead-letter handling.
 *
 * <p>A fate of {@code DONE} completes the record. {@code RETRY} fails it with an ordinary exception,
 * so the binding retries it and then takes its exhausted strategy. {@code TERMINAL} fails it with a
 * {@link KafkaTerminalFailure}, so it takes the exhausted strategy at once. A failure carries an error
 * type only: never a key, a header or a payload.
 *
 * @param <T> the type of the decoded record value
 */
final class KafkaSignalHandler<T> implements KafkaRecordHandler<T> {

    private static final String TRANSPORT = "kafka";

    /** The error type of a record whose dead-letter source headers cannot be read. */
    static final String INVALID_REPLAY_HEADER = "InvalidReplayHeader";

    /** Stands in for an error type that is not a simple token, so it cannot reach a header. */
    static final String INVALID_ERROR_TYPE = "InvalidErrorType";

    /** Stands in for a terminal fate that names no error type. */
    static final String UNSPECIFIED_ERROR_TYPE = "TerminalFailure";

    private static final Pattern ERROR_TYPE_TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.\\-]{0,127}");

    private final String sourceName;
    private final SignalSourcePlan<T> plan;
    private final WorkflowSourceDelivery delivery;
    private final boolean dlqReplay;
    private final KafkaCorrelationBaseline correlation;

    KafkaSignalHandler(
            String sourceName,
            SignalSourcePlan<T> plan,
            WorkflowSourceDelivery delivery,
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
        String dedupKey;
        try {
            dedupKey = defaultDedupKey(message);
        } catch (InvalidReplayHeaderException e) {
            report(Outcome.INVALID_INPUT);
            return Future.failedFuture(new KafkaTerminalFailure(INVALID_REPLAY_HEADER));
        }
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
            case TERMINAL -> Future.failedFuture(new KafkaTerminalFailure(terminalType(disposition.errorType())));
        };
    }

    /** The dedup key of a record on a source that names none: the record's own position, or the replayed one. */
    private String defaultDedupKey(KafkaMessage<T> message) {
        if (dlqReplay) {
            String topic = headerText(message, KafkaDlqHeaders.SOURCE_TOPIC);
            String partition = headerText(message, KafkaDlqHeaders.SOURCE_PARTITION);
            String offset = headerText(message, KafkaDlqHeaders.SOURCE_OFFSET);
            if (topic != null || partition != null || offset != null) {
                if (topic == null || partition == null || offset == null) {
                    throw new InvalidReplayHeaderException();
                }
                return topic + ":" + nonNegativeInt(partition) + ":" + nonNegativeLong(offset);
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

    private static int nonNegativeInt(String text) {
        try {
            int value = Integer.parseInt(text);
            if (value >= 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // falls through to the refusal below; the header's value is not kept
        }
        throw new InvalidReplayHeaderException();
    }

    private static long nonNegativeLong(String text) {
        try {
            long value = Long.parseLong(text);
            if (value >= 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // falls through to the refusal below; the header's value is not kept
        }
        throw new InvalidReplayHeaderException();
    }

    private static String terminalType(@Nullable String errorType) {
        if (errorType == null) {
            return UNSPECIFIED_ERROR_TYPE;
        }
        return isToken(errorType) ? errorType : INVALID_ERROR_TYPE;
    }

    private static @Nullable String retryType(@Nullable String errorType) {
        return errorType != null && isToken(errorType) ? errorType : null;
    }

    /** Whether the text is a simple token that can ride a header: a short name, with no space or control character. */
    static boolean isToken(String text) {
        return ERROR_TYPE_TOKEN.matcher(text).matches();
    }

    /** What the record's own source headers could not be read as; it carries nothing of the headers. */
    private static final class InvalidReplayHeaderException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InvalidReplayHeaderException() {
            super(null, null, false, false);
        }
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
