// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.workflow;

import dev.vertique.codegen.RegisterIntoSet;
import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.ActorAbsent;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.CommandDeferred;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.CommandSettled;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.LeaseExpired;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.QueueSnapshot;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.TransientRetry;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records workflow executor events as bounded Micrometer meters.
 *
 * <p>Every counter and the settle timer carry only the lower-cased {@code kind} (and, for settled
 * commands, {@code outcome}) tags, bounded to 64 characters. The five queue gauges are untagged and
 * read the latest queue snapshot; the observer holds their state and registers them on the first
 * snapshot.
 *
 * <p>The executor emits queue snapshots from two places and each fills only part of the record: the
 * stuck-command reporter fills the four counts with a usage of {@code 0}, and the notification
 * listener fills the usage with all counts {@code 0}. A snapshot whose four counts are all {@code 0}
 * and whose usage is positive therefore updates the usage only; a snapshot with any non-zero count and
 * a usage of {@code 0} updates the four counts only; any other snapshot updates all five gauges.
 *
 * <p>The observer is fail-open: nothing recorded here can change command execution, and no exception
 * leaves {@link #onEvent}.
 */
@Singleton
@RegisterIntoSet(WorkflowExecutorObserver.class)
public final class WorkflowExecutorMetricsObserver implements WorkflowExecutorObserver {
    private static final String PREFIX = "vertique.workflow.";
    private static final String SETTLED_COUNTER = PREFIX + "commands.settled";
    private static final String DURATION_TIMER = PREFIX + "commands.duration";
    private static final String DEFERRED_COUNTER = PREFIX + "commands.deferred";
    private static final String TRANSIENT_RETRIES_COUNTER = PREFIX + "commands.transient.retries";
    private static final String LEASE_EXPIRED_COUNTER = PREFIX + "commands.lease.expired";
    private static final String ACTOR_ABSENT_COUNTER = PREFIX + "recordings.actor.absent";
    private static final String AVAILABLE_GAUGE = PREFIX + "commands.available";
    private static final String STUCK_GAUGE = PREFIX + "commands.stuck";
    private static final String PARKED_GAUGE = PREFIX + "commands.parked";
    private static final String DEAD_GAUGE = PREFIX + "commands.dead";
    private static final String USAGE_GAUGE = PREFIX + "notification.queue.usage";
    private static final int MAX_TAG_LENGTH = 64;

    private final MeterRegistry registry;
    private final boolean enabled;

    // Strong references: the registry holds gauge state weakly.
    private final AtomicLong available = new AtomicLong();
    private final AtomicLong stuck = new AtomicLong();
    private final AtomicLong parked = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private final AtomicReference<Double> usage = new AtomicReference<>(0.0);
    private volatile boolean gaugesRegistered;

    /**
     * Creates the observer with the application meter registry and optional metrics configuration.
     *
     * <p>When the optional configuration is absent, workflow metrics are enabled by default.
     *
     * @param registry the application-wide meter registry; never {@code null}
     * @param metricsConfig optional metrics configuration; empty means enabled
     */
    @Inject
    public WorkflowExecutorMetricsObserver(MeterRegistry registry, Optional<MetricsConfig> metricsConfig) {
        this.registry = registry;
        this.enabled = metricsConfig.map(MetricsConfig::enabled).orElse(true);
    }

    /**
     * Creates the observer with an explicit metrics configuration.
     *
     * @param registry the application-wide meter registry; never {@code null}
     * @param config the metrics configuration; never {@code null}
     */
    public WorkflowExecutorMetricsObserver(MeterRegistry registry, MetricsConfig config) {
        this(registry, Optional.of(config));
    }

    @Override
    public void onEvent(WorkflowExecutorEvent event) {
        if (!enabled) {
            return;
        }
        try {
            // Exhaustive on purpose: a new event variant must fail compilation here.
            switch (event) {
                case CommandSettled settled -> settled(settled);
                case CommandDeferred deferred -> count(DEFERRED_COUNTER, deferred.kind());
                case TransientRetry retry -> count(TRANSIENT_RETRIES_COUNTER, retry.kind());
                case LeaseExpired expired -> count(LEASE_EXPIRED_COUNTER, expired.kind());
                case ActorAbsent absent -> count(ACTOR_ABSENT_COUNTER, absent.kind());
                case QueueSnapshot snapshot -> snapshot(snapshot);
            }
        } catch (Throwable ignored) {
            // Telemetry must never alter command execution.
        }
    }

    private void settled(CommandSettled settled) {
        String kind = tag(settled.kind());
        String outcome = tag(settled.outcome());
        Counter.builder(SETTLED_COUNTER)
                .tag("kind", kind)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder(DURATION_TIMER)
                .tag("kind", kind)
                .tag("outcome", outcome)
                .register(registry)
                .record(settled.elapsed());
    }

    private void count(String name, String kind) {
        Counter.builder(name).tag("kind", tag(kind)).register(registry).increment();
    }

    private void snapshot(QueueSnapshot snapshot) {
        boolean countsAreZero = snapshot.available() == 0
                && snapshot.stuck() == 0
                && snapshot.parked() == 0
                && snapshot.deadLettered() == 0;
        double snapshotUsage = snapshot.notificationQueueUsage();
        boolean usageOnly = countsAreZero && snapshotUsage > 0;
        boolean countsOnly = !countsAreZero && snapshotUsage == 0;
        if (!usageOnly) {
            available.set(snapshot.available());
            stuck.set(snapshot.stuck());
            parked.set(snapshot.parked());
            deadLettered.set(snapshot.deadLettered());
        }
        if (!countsOnly) {
            usage.set(snapshotUsage);
        }
        registerGauges();
    }

    private void registerGauges() {
        if (gaugesRegistered) {
            return;
        }
        // Registering an existing id returns the existing gauge, so a retry after a failure is safe.
        Gauge.builder(AVAILABLE_GAUGE, available, AtomicLong::doubleValue).register(registry);
        Gauge.builder(STUCK_GAUGE, stuck, AtomicLong::doubleValue).register(registry);
        Gauge.builder(PARKED_GAUGE, parked, AtomicLong::doubleValue).register(registry);
        Gauge.builder(DEAD_GAUGE, deadLettered, AtomicLong::doubleValue).register(registry);
        Gauge.builder(USAGE_GAUGE, usage, AtomicReference::get).register(registry);
        gaugesRegistered = true;
    }

    private static String tag(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.length() <= MAX_TAG_LENGTH ? lower : lower.substring(0, MAX_TAG_LENGTH);
    }
}
