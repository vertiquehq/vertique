// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Kafka adapter for the transactional messaging (inbox/outbox) module.
 *
 * <p>This package provides:
 * <ul>
 *   <li>{@link dev.vertique.inboxoutbox.kafka.KafkaOutboxDestinationHandler} — delivers outbox
 *       entries with {@link dev.vertique.inboxoutbox.DestinationType#KAFKA} to Kafka topics using
 *       the shared {@link dev.vertique.kafka.producer.KafkaProducerFactory}</li>
 *   <li>{@link dev.vertique.inboxoutbox.kafka.TransactionalMessagingKafkaModule} — Dagger module
 *       that contributes the {@code KafkaOutboxDestinationHandler} into the
 *       {@link dev.vertique.inboxoutbox.OutboxDestinationHandler} multibinding</li>
 * </ul>
 *
 * <p>To enable Kafka delivery for outbox entries, include
 * {@link dev.vertique.inboxoutbox.kafka.TransactionalMessagingKafkaModule} alongside
 * {@code TransactionalMessagingPostgresqlModule} in your Dagger component:
 * <pre>{@code
 * @Component(modules = {
 *     DbPostgresqlModule.class,
 *     TransactionalMessagingPostgresqlModule.class,
 *     TransactionalMessagingKafkaModule.class,
 * })
 * public interface AppComponent { ... }
 * }</pre>
 */
package dev.vertique.inboxoutbox.kafka;
