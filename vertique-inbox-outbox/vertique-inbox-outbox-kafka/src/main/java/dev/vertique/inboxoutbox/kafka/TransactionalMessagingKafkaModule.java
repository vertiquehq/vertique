// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module that wires the Kafka adapter for transactional messaging.
 *
 * <p>Contributes a {@link KafkaOutboxDestinationHandler} into the
 * {@link OutboxDestinationHandler} multibinding so the outbox relay can deliver entries with
 * {@link dev.vertique.inboxoutbox.DestinationType#KAFKA} to Kafka topics.
 *
 * <p>Extension points via multibinding:
 * <ul>
 *   <li>{@code Set<KafkaOutboxCaptureHook>} — observe every outbox publish after serialization
 *       and result classification; observer-only, never affects the publish result. The
 *       {@code audit-kafka} adapter contributes one; applications may add more.</li>
 * </ul>
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

    // --- Multibindings ---

    /**
     * Declares the empty-by-default multibinding for outbox publish capture hooks.
     * The {@code audit-kafka} adapter contributes one via {@code @IntoSet}; applications may
     * add more. Observer-only — hooks never affect the publish result or delivery behavior.
     *
     * @return an empty set (elements contributed via {@code @IntoSet})
     */
    @Multibinds
    abstract Set<KafkaOutboxCaptureHook> kafkaOutboxCaptureHooks();

    // --- Providers ---

    /**
     * Provides the singleton {@link KafkaOutboxDestinationHandler}, injecting the full set of
     * registered {@link KafkaOutboxCaptureHook} instances.
     *
     * @param producerFactory the shared Kafka producer factory
     * @param objectMapper    the Jackson object mapper
     * @param captureHooks    the set of outbox capture hooks; observer-only, sorted at construction
     * @return the Kafka outbox destination handler
     */
    @Provides
    @Singleton
    static KafkaOutboxDestinationHandler kafkaOutboxDestinationHandler(
            KafkaProducerFactory producerFactory, ObjectMapper objectMapper, Set<KafkaOutboxCaptureHook> captureHooks) {
        return new KafkaOutboxDestinationHandler(producerFactory, objectMapper, captureHooks);
    }

    /**
     * Contributes the {@link KafkaOutboxDestinationHandler} into the
     * {@link OutboxDestinationHandler} multibinding.
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
