// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.workflow;

import dev.vertique.codegen.RegisterIntoSet;
import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Locale;
import java.util.Optional;

/**
 * Counts the outcome of every message a correlated signal source receives.
 *
 * <p>The counter {@code vertique.workflow.source.outcomes} carries the tags {@code transport},
 * {@code contract}, {@code signal}, {@code definition} and {@code outcome}, each lower-cased and bounded
 * to 64 characters, with {@code none} for a name the event does not carry. The source's own name, a key,
 * a header and a payload are never tags.
 *
 * <p>The observer is fail-open: nothing recorded here can change a message's fate, and no exception
 * leaves {@link #onOutcome}.
 */
@Singleton
@RegisterIntoSet(SignalSourceObserver.class)
public final class SignalSourceMetricsObserver implements SignalSourceObserver {
    private static final String OUTCOMES_COUNTER = "vertique.workflow.source.outcomes";
    private static final String NONE = "none";
    private static final int MAX_TAG_LENGTH = 64;

    private final MeterRegistry registry;
    private final boolean enabled;

    /**
     * Creates the observer with the application meter registry and optional metrics configuration.
     *
     * <p>When the optional configuration is absent, the metrics are enabled by default.
     *
     * @param registry the application-wide meter registry; never {@code null}
     * @param metricsConfig optional metrics configuration; empty means enabled
     */
    @Inject
    public SignalSourceMetricsObserver(MeterRegistry registry, Optional<MetricsConfig> metricsConfig) {
        this.registry = registry;
        this.enabled = metricsConfig.map(MetricsConfig::enabled).orElse(true);
    }

    /**
     * Creates the observer with an explicit metrics configuration.
     *
     * @param registry the application-wide meter registry; never {@code null}
     * @param config the metrics configuration; never {@code null}
     */
    public SignalSourceMetricsObserver(MeterRegistry registry, MetricsConfig config) {
        this(registry, Optional.of(config));
    }

    @Override
    public void onOutcome(SignalSourceEvent event) {
        if (!enabled) {
            return;
        }
        try {
            Counter.builder(OUTCOMES_COUNTER)
                    .tag("transport", tag(event.transport()))
                    .tag("contract", tag(event.contractName()))
                    .tag("signal", tag(event.signalName()))
                    .tag("definition", tag(event.definitionId()))
                    .tag("outcome", tag(event.outcome().name()))
                    .register(registry)
                    .increment();
        } catch (Throwable ignored) {
            // Telemetry must never alter a message's fate.
        }
    }

    private static String tag(@Nullable String value) {
        if (value == null) {
            return NONE;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.length() <= MAX_TAG_LENGTH ? lower : lower.substring(0, MAX_TAG_LENGTH);
    }
}
