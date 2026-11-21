// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.kafka;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

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
 * <p>Also provides {@link KafkaOutboxConfig}, read from the {@code inboxOutbox.kafka} section of the
 * application config; an absent section keeps the default forwarded context.
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

    /**
     * Provides {@link KafkaOutboxConfig} as a Dagger {@link Singleton} binding.
     *
     * <p>Deserialised from the {@code inboxOutbox.kafka} section of the application config; an absent
     * section yields the defaults.
     *
     * @param config the application configuration root
     * @param parser the injected config parser
     * @return the Kafka outbox configuration
     * @throws dev.vertique.inboxoutbox.exception.InboxOutboxConfigurationException when
     *     {@code inboxOutbox.kafka.outboundContextNamespaces} holds a blank or repeated name
     */
    @Provides
    @Singleton
    static KafkaOutboxConfig kafkaOutboxConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
                JsonConfigPaths.navigateObject(JsonConfigPaths.navigateObject(config, "inboxOutbox"), "kafka"),
                KafkaOutboxConfig.class);
    }
}
