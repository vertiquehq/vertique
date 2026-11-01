// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.kafka.KafkaConsumerBinding;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves that a component which names only the generated registrations module receives the binding of an annotated
 * listener, in a set that is not empty: the module registers the generated source, and the Kafka transport module it
 * includes turns the source into a binding. The component under test is compiled with the Kafka and Dagger code
 * generators on the processor path, over a listener that is an interface with no Dagger code.
 */
class GeneratedSourceComponentTest {

    @Test
    void componentOverAnnotatedListenerProvidesTheListenersBindingInANonEmptySet() {
        // Given a component of the generated registrations module and a test module with a delivery double, with no
        // @Provides method for the annotated listener CarrierSignals
        GeneratedSourceComponent component = DaggerGeneratedSourceComponent.create();

        // When the test builds the component and reads its bindings
        Set<KafkaConsumerBinding<?>> bindings = component.bindings();

        // Then the set is not empty, so a transport module that contributed nothing fails here
        assertThat(bindings).isNotEmpty();
        // And it holds exactly one binding of the listener, named carrier-events, on topic carrier.events and group
        // logistics
        assertThat(bindings)
                .filteredOn(binding -> binding.name().equals(CarrierSignals.NAME))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.topic()).isEqualTo("carrier.events");
                    assertThat(binding.groupId()).isEqualTo("logistics");
                });
    }
}
