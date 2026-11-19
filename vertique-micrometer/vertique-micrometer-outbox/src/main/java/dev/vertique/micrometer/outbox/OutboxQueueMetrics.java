// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.outbox;

import dev.vertique.inboxoutbox.OutboxQueueSample;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the three outbox queue gauges and the latest sample they read.
 *
 * <p>The gauges are untagged and registered on the first sample, so a node that has never read the
 * queue publishes no queue meters. A sample replaces all three values; until the next one arrives the
 * gauges keep the last values.
 */
final class OutboxQueueMetrics {

    static final String DEPTH = "vertique.outbox.queue.depth";
    static final String OLDEST_AGE = "vertique.outbox.queue.oldest.age";
    static final String DEAD_LETTERS = "vertique.outbox.dead.letters";

    private final MeterRegistry registry;

    // Strong references: the registry holds gauge state weakly.
    private final AtomicLong due = new AtomicLong();
    private final AtomicLong oldestAgeMs = new AtomicLong();
    private final AtomicLong deadLetters = new AtomicLong();
    private volatile boolean registered;

    OutboxQueueMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Stores the sample and registers the gauges when this is the first one. */
    void record(OutboxQueueSample sample) {
        due.set(sample.due());
        oldestAgeMs.set(sample.oldestDueAge().toMillis());
        deadLetters.set(sample.deadLetter());
        register();
    }

    private void register() {
        if (registered) {
            return;
        }
        // Registering an existing id returns the existing gauge, so a retry after a failure is safe.
        Gauge.builder(DEPTH, due, AtomicLong::doubleValue)
                .description("Outbox entries that are due")
                .register(registry);
        Gauge.builder(OLDEST_AGE, oldestAgeMs, value -> value.doubleValue() / 1000.0)
                .description("How long the oldest due outbox entry has been due")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(DEAD_LETTERS, deadLetters, AtomicLong::doubleValue)
                .description("Outbox entries in the dead-letter state")
                .register(registry);
        registered = true;
    }
}
