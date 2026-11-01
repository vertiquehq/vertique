// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.kafka.KafkaConsumerBinding;
import dev.vertique.workflow.kafka.SourceScenario.ShipmentDelivered;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves that an application which names only the generated registrations module still receives the Kafka transport's
 * bindings: the generated module includes {@code WorkflowKafkaSignalsModule} through the source type's marker. The
 * component under test is compiled with the code generator on the processor path.
 */
class SourceClassComponentTest {

    @Test
    void componentNamingOnlyTheGeneratedModuleProvidesTheSourcesBinding() {
        // Given a component of the generated registrations module and a test module, with a lookup fake, and no
        // @Provides method for the source class
        ShipmentLookup.Recording lookup = new ShipmentLookup.Recording();
        SourceClassComponent component = DaggerSourceClassComponent.builder()
                .rig(new SourceClassComponent.Rig(lookup))
                .build();

        // When the test reads the component's bindings
        Set<KafkaConsumerBinding<?>> bindings = component.bindings();

        // Then the set holds exactly one binding named for the source, on the source's topic and over the source's
        // message type; the annotated listener of the same test sources adds its own binding to the set
        List<KafkaConsumerBinding<?>> named = bindings.stream()
                .filter(candidate -> ShipmentDeliveredSource.NAME.equals(candidate.name()))
                .toList();
        assertThat(named).hasSize(1);
        KafkaConsumerBinding<?> binding = named.get(0);
        assertThat(binding.topic()).isEqualTo(ShipmentDeliveredSource.TOPIC);
        assertThat(binding.valueType()).isEqualTo(ShipmentDelivered.class);
        // And the component constructed the source with the injected fake
        assertThat(component.sources())
                .filteredOn(ShipmentDeliveredSource.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(ShipmentDeliveredSource.class, source -> assertThat(source.lookup())
                        .isSameAs(lookup));
    }
}
