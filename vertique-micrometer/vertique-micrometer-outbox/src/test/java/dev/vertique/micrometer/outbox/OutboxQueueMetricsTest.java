// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.inboxoutbox.OutboxQueueSample;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OutboxQueueMetrics")
class OutboxQueueMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxQueueMetrics metrics = new OutboxQueueMetrics(registry);

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    @DisplayName("no gauge exists before the first sample")
    void noGaugeBeforeFirstSample() {
        assertNull(registry.find(OutboxQueueMetrics.DEPTH).gauge());
        assertNull(registry.find(OutboxQueueMetrics.OLDEST_AGE).gauge());
        assertNull(registry.find(OutboxQueueMetrics.DEAD_LETTERS).gauge());
    }

    @Test
    @DisplayName("a sample sets depth, oldest age in seconds and the dead-letter count")
    void sampleSetsGauges() {
        metrics.record(new OutboxQueueSample(7, Duration.ofMillis(2_500), 3));

        assertEquals(7, gauge(OutboxQueueMetrics.DEPTH));
        assertEquals(2.5, gauge(OutboxQueueMetrics.OLDEST_AGE));
        assertEquals(3, gauge(OutboxQueueMetrics.DEAD_LETTERS));
        assertEquals(
                "seconds",
                registry.get(OutboxQueueMetrics.OLDEST_AGE).gauge().getId().getBaseUnit());
    }

    @Test
    @DisplayName("a later sample replaces all three values and registers each gauge once")
    void laterSampleReplacesValues() {
        metrics.record(new OutboxQueueSample(7, Duration.ofSeconds(5), 3));
        metrics.record(new OutboxQueueSample(0, Duration.ZERO, 1));

        assertEquals(0, gauge(OutboxQueueMetrics.DEPTH));
        assertEquals(0, gauge(OutboxQueueMetrics.OLDEST_AGE));
        assertEquals(1, gauge(OutboxQueueMetrics.DEAD_LETTERS));
        assertEquals(3, registry.getMeters().size());
    }

    @Test
    @DisplayName("the gauges carry no tags")
    void gaugesAreUntagged() {
        metrics.record(new OutboxQueueSample(1, Duration.ZERO, 0));

        registry.getMeters()
                .forEach(meter -> assertEquals(0, meter.getId().getTags().size()));
    }
}
