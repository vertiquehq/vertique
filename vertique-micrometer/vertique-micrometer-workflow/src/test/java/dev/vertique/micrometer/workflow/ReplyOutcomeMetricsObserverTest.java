// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ReplyOutcomeMetricsObserverTest {
    @Test
    void countsByOutcome() {
        var registry = new SimpleMeterRegistry();
        var observer = new ReplyOutcomeMetricsObserver(
                registry, MetricsConfig.builder().build());
        for (Outcome outcome : Outcome.values()) {
            observer.onOutcome(outcome);
            assertEquals(
                    1.0,
                    registry.get("vertique.workflow.reply.outcomes")
                            .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                            .counter()
                            .count());
        }
        assertEquals(
                Set.of("outcome"),
                registry.get("vertique.workflow.reply.outcomes").meters().stream()
                        .flatMap(m -> m.getId().getTags().stream())
                        .map(tag -> tag.getKey())
                        .collect(Collectors.toSet()));
    }
}
