// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

/**
 * Identifies the origin of a Kafka producer send so that capture hooks
 * ({@link KafkaProducerCaptureHook}) and downstream adapters can distinguish how a record entered
 * the wire funnel.
 *
 * <p>Every send that converges at the shared wire funnel in {@link KafkaProducerFactory} carries an
 * explicit origin — there is no silent default that could misclassify a send.
 *
 * @see KafkaProducerCaptureHook
 * @see KafkaProducerFactory
 */
public enum KafkaSendOrigin {

    /**
     * The record was sent via a JDK-proxy {@link KafkaProducer @KafkaProducer} interface method.
     *
     * <p>This is the only origin for which {@link KafkaProducerCaptureHook#onSend} receives a
     * non-null {@code producerMethod} — and {@link KafkaProducerSend#operation()} a non-null
     * {@link KafkaProducerOperation} — so downstream adapters can inspect the method's method-level
     * and the producer interface's type-level annotations.
     */
    DIRECT_PRODUCER,

    /**
     * The record originated from the transactional outbox relay — it was stored durably in the
     * outbox table and is now being forwarded to Kafka by the relay poller.
     *
     * <p>The {@code producerMethod} parameter in {@link KafkaProducerCaptureHook#onSend} is always
     * {@code null} for this origin.
     */
    OUTBOX,

    /**
     * The record is a dead-letter publish — a failed consumer record that could not be processed
     * and is being forwarded to the dead-letter topic.
     *
     * <p>The {@code producerMethod} parameter in {@link KafkaProducerCaptureHook#onSend} is always
     * {@code null} for this origin.
     */
    DLQ,

    /**
     * The record was sent by an internal framework component that does not fit any of the
     * above categories.
     *
     * <p>The {@code producerMethod} parameter in {@link KafkaProducerCaptureHook#onSend} is always
     * {@code null} for this origin.
     */
    INTERNAL
}
