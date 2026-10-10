// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Interceptor SPI for the Kafka consumer pipeline.
 *
 * <p>{@link dev.vertique.kafka.interceptor.KafkaConsumerInterceptor} is the one extension point:
 * its async handlers shape dispatch through a copy-on-write
 * {@link dev.vertique.kafka.interceptor.KafkaDispatchContext}, and its sync observers watch it.
 * {@code onRecordCompleted} is the observer that sees every record the consumer received. It gets a
 * {@link dev.vertique.kafka.interceptor.KafkaConsumerCompletedEvent} of framework facts (a
 * {@link dev.vertique.kafka.interceptor.KafkaConsumerRecordIdentity} and a
 * {@link dev.vertique.kafka.interceptor.KafkaTerminalOutcome}) and a framework-owned
 * {@link dev.vertique.kafka.interceptor.KafkaConsumerRecordView} of the record as received.
 */
package dev.vertique.kafka.interceptor;
