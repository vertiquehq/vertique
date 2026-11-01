// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static dev.vertique.workflow.kafka.SourceFixture.SHIPMENT_DELIVERED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.kafka.KafkaConsumerBinding;
import dev.vertique.workflow.kafka.SourceFixture.DeliveryConfirmed;
import dev.vertique.workflow.kafka.SourceFixture.OrdersContract;
import dev.vertique.workflow.kafka.SourceFixture.ShipmentDelivered;
import dev.vertique.workflow.services.source.WorkflowSourceDelivery;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves how {@code WorkflowKafkaSignalsModule} turns the sources of its set into bindings: it reads the message type
 * from the class's {@code KafkaSignalSource} type argument, and a class that states none fails the build of the
 * contribution, naming the class.
 */
class WorkflowKafkaSignalsModuleTest {

    @Test
    void rawSourceTypeArgumentFailsAtStartupNamingTheClass() {
        // Given a set of sources that holds one declared KafkaSignalSource<ShipmentDelivered> and one raw
        // KafkaSignalSource, behind the module
        RawSourceComponent component = DaggerWorkflowKafkaSignalsModuleTest_RawSourceComponent.create();

        // When the module builds its binding contribution from that set
        // Then building fails and the message names the raw class; the typed source beside it does not make it pass
        assertThatThrownBy(component::bindings).hasMessageContaining("RawSource");
    }

    /** A source that states its message type. */
    static final class TypedSource implements KafkaSignalSource<ShipmentDelivered> {

        @Override
        public void define(KafkaSignals<ShipmentDelivered> source) {
            source.topic(SourceFixture.TOPIC)
                    .groupId(SourceFixture.GROUP)
                    .dedupBy(ShipmentDelivered::orderNumber)
                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                    .key(ShipmentDelivered::carrierRef)
                    .payload(message -> new DeliveryConfirmed(message.carrierRef(), message.note()));
        }
    }

    /** A source whose type argument is missing, so that its message type cannot be read. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class RawSource implements KafkaSignalSource {

        @Override
        public void define(KafkaSignals source) {
            // no stages: the module must refuse the class before it asks for any
        }
    }

    /** The module under test, with the sources it is handed and what it needs of a node. */
    @Singleton
    @Component(modules = {WorkflowKafkaSignalsModule.class, RawSourceComponent.Rig.class})
    interface RawSourceComponent {

        Set<KafkaConsumerBinding<?>> bindings();

        @Module
        abstract class Rig {

            @Provides
            @ElementsIntoSet
            static Set<KafkaSignalSource<?>> sources() {
                return Set.of(new TypedSource(), new RawSource());
            }

            @Provides
            @Singleton
            static WorkflowSourceDelivery delivery() {
                return mock(WorkflowSourceDelivery.class);
            }

            @Provides
            @Singleton
            static CorrelationContextFactory correlationFactory() {
                return new CorrelationContextFactory(Optional.empty());
            }
        }
    }
}
