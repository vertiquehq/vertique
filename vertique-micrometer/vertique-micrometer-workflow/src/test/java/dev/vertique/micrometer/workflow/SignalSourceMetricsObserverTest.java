// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SignalSourceMetricsObserver} counts each signal source outcome by transport,
 * contract, signal, definition and outcome, writes {@code none} for a name the event does not
 * carry, and adds no other tag.
 */
class SignalSourceMetricsObserverTest {

    private static final String COUNTER = "vertique.workflow.source.outcomes";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("outcomes are counted by transport, contract, signal, definition and outcome with none for null")
    void outcomesAreCountedByTransportContractSignalDefinitionAndOutcomeWithNoneForNull() {
        // Given an observer over a simple registry
        SignalSourceMetricsObserver observer = new SignalSourceMetricsObserver(
                registry, MetricsConfig.builder().build());

        // When it receives two RECORDED events for one contract, signal and definition,
        // and one UNDECODABLE event with a null contract name, signal name and definition id
        SignalSourceEvent recorded = new SignalSourceEvent(
                "carriers", "kafka", "orders", "shipment-delivered", "order-flow", Outcome.RECORDED);
        observer.onOutcome(recorded);
        observer.onOutcome(recorded);
        observer.onOutcome(new SignalSourceEvent("carriers", "kafka", null, null, null, Outcome.UNDECODABLE));

        // Then the counter reads 2 under the recorded tags
        assertEquals(
                2.0,
                registry.get(COUNTER)
                        .tags(
                                "transport", "kafka",
                                "contract", "orders",
                                "signal", "shipment-delivered",
                                "definition", "order-flow",
                                "outcome", "recorded")
                        .counter()
                        .count());

        // and 1 under the undecodable tags, where every null value is none
        assertEquals(
                1.0,
                registry.get(COUNTER)
                        .tags(
                                "transport", "kafka",
                                "contract", "none",
                                "signal", "none",
                                "definition", "none",
                                "outcome", "undecodable")
                        .counter()
                        .count());

        // and the tag keys are exactly transport, contract, signal, definition and outcome
        Set<String> tagKeys = registry.get(COUNTER).meters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey)
                .collect(Collectors.toSet());
        assertEquals(Set.of("transport", "contract", "signal", "definition", "outcome"), tagKeys);
    }
}
