// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.kafka;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;

/**
 * Dagger module that wires the Kafka adapter for transactional messaging.
 *
 * <p>Contributes a {@link KafkaOutboxDestinationHandler} into the
 * {@link OutboxDestinationHandler} multibinding so the outbox relay can deliver entries with
 * {@link dev.vertique.inboxoutbox.DestinationType#KAFKA} to Kafka topics.
 *
 * <p>This module declares no extension point of its own. A publish attempt is observed at the
 * relay through {@link dev.vertique.inboxoutbox.OutboxPublishObserver}, and the wire bytes of a send
 * through {@link dev.vertique.kafka.producer.KafkaProducerCaptureHook}, which sees an outbox send
 * with origin {@link dev.vertique.kafka.producer.KafkaSendOrigin#OUTBOX} and the entry id as the
 * origin reference.
 *
 * <p>Include this module alongside {@code TransactionalMessagingPostgresqlModule} in your
 * Dagger component to activate Kafka delivery:
 * <pre>{@code
 * @Component(modules = {
 *     DbPostgresqlModule.class,
 *     TransactionalMessagingPostgresqlModule.class,
 *     TransactionalMessagingKafkaModule.class,
 * })
 * public interface AppComponent { ... }
 * }</pre>
 */
@Module
public abstract class TransactionalMessagingKafkaModule {

    // --- Providers ---

    /**
     * Contributes the {@link KafkaOutboxDestinationHandler} into the
     * {@link OutboxDestinationHandler} multibinding. The handler is a singleton built through its
     * {@code @Inject} constructor.
     *
     * @param handler the Kafka outbox destination handler
     * @return the handler registered into the set
     */
    @Provides
    @IntoSet
    static OutboxDestinationHandler kafkaHandler(KafkaOutboxDestinationHandler handler) {
        return handler;
    }
}
