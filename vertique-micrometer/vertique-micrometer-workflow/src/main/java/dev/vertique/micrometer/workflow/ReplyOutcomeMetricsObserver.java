// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.workflow;

import dev.vertique.codegen.RegisterIntoSet;
import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.workflow.ops.ReplyOutcomeObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Locale;
import java.util.Optional;

/** Counts reply outcomes without recording values or identifying tags. */
@Singleton
@RegisterIntoSet(ReplyOutcomeObserver.class)
public final class ReplyOutcomeMetricsObserver implements ReplyOutcomeObserver {
    private final MeterRegistry registry;
    private final boolean enabled;

    /** Creates an observer with optional metrics configuration. */
    @Inject
    public ReplyOutcomeMetricsObserver(MeterRegistry registry, Optional<MetricsConfig> config) {
        this.registry = registry;
        this.enabled = config.map(MetricsConfig::enabled).orElse(true);
    }

    /** Creates an observer with explicit metrics configuration. */
    public ReplyOutcomeMetricsObserver(MeterRegistry registry, MetricsConfig config) {
        this(registry, Optional.of(config));
    }

    @Override
    public void onOutcome(Outcome outcome) {
        if (!enabled) return;
        try {
            Counter.builder("vertique.workflow.reply.outcomes")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .register(registry)
                    .increment();
        } catch (Throwable ignored) {
            // Metrics must not affect delivery.
        }
    }
}
