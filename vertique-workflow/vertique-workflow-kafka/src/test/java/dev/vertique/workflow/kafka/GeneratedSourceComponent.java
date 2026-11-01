// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static org.mockito.Mockito.mock;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.kafka.KafkaConsumerBinding;
import dev.vertique.workflow.services.source.WorkflowSignalSourceDelivery;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;

/**
 * A component that names the generated registrations module and nothing of the Kafka transport: no
 * {@code WorkflowKafkaSignalsModule}, and no {@code @Provides} method for the annotated listener or the source it
 * generates. What it supplies is what a node leaves to its application: a delivery double, the correlation factory and
 * the lookup that the source class of the same registrations module takes.
 */
@Singleton
@Component(modules = {GeneratedRegistrationsModule.class, GeneratedSourceComponent.Rig.class})
interface GeneratedSourceComponent {

    /** The bindings the component offers to the consumer runtime. */
    Set<KafkaConsumerBinding<?>> bindings();

    /** What the component leaves to the application. */
    @Module
    final class Rig {

        @Provides
        ShipmentLookup lookup() {
            return new ShipmentLookup.Recording();
        }

        @Provides
        @Singleton
        WorkflowSignalSourceDelivery delivery() {
            return mock(WorkflowSignalSourceDelivery.class);
        }

        @Provides
        @Singleton
        CorrelationContextFactory correlationFactory() {
            return new CorrelationContextFactory(Optional.empty());
        }
    }
}
