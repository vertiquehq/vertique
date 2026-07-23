// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql.maintenance;

import dev.vertique.job.cron.CronJob;
import dev.vertique.job.cron.ExecutionMode;
import dev.vertique.job.cron.OverlapPolicy;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Cron-scheduled implementation of {@link OutboxMaintenanceContract}. Delegates to
 * {@link OutboxMaintenanceService} for the actual logic.
 *
 * <h2>Cadence</h2>
 *
 * <p>Defaults match the prior {@code OutboxRelay} {@code setPeriodic} <em>periods</em>, but
 * first-fire timing differs because cron expressions are wall-clock-aligned while
 * {@code setPeriodic} is uptime-relative:
 * <ul>
 *   <li>{@code recoverStaleLeases} — {@code "*}{@code /30 * * * * *"} (every 30s on the wall
 *       clock; matches the period of the prior {@code OutboxRelayConfig.leaseTimeoutMs = 30_000}
 *       cadence).</li>
 *   <li>{@code cleanup} — {@code "0 0 *}{@code /6 * * *"} (00:00, 06:00, 12:00, 18:00 UTC; matches
 *       the period of the prior {@code InboxOutboxCleanupConfig.cleanupIntervalHours = 6} cadence,
 *       not its phase — a node booting at 05:55 sees the first cleanup at 06:00 rather than
 *       6h after start). Override via {@code cron.jobs.outbox-cleanup.cron} if a different
 *       schedule is preferred.</li>
 * </ul>
 *
 * <p>Operators tune via {@code cron.jobs.outbox-stale-lease-recovery.cron} and
 * {@code cron.jobs.outbox-cleanup.cron} config (already supported by {@code CronJobRegistrar}).
 *
 * <p><b>leaseTimeoutMs / cron cadence relationship:</b> the prior {@code setPeriodic} design
 * derived its cadence from {@code OutboxRelayConfig.leaseTimeoutMs} so the two were coupled
 * automatically. They are now independent. If an operator overrides
 * {@code inboxOutbox.relay.leaseTimeoutMs}, the matching {@code cron.jobs.outbox-stale-lease-recovery.cron}
 * override should be set in proportion — typically the cron period should be at most
 * {@code leaseTimeoutMs}. A much smaller cron period than {@code leaseTimeoutMs} causes
 * redundant DB updates on rows that are not yet stale; a much larger cron period extends the
 * window in which stale leases linger.
 *
 * <p><b>First-fire latency:</b> on the first boot after upgrade (no {@code job_schedules} row yet)
 * or whenever {@code last_fired_at == null}, the first fire waits up to one cron period —
 * {@code MisfirePolicy.FIRE_NOW} only triggers when an existing schedule's prior fire is overdue.
 * On subsequent restarts {@code MisfirePolicy.FIRE_NOW} (the {@code SINGLE_INSTANCE} default)
 * fires immediately. Both maintenance operations are eventually correct, so the worst-case
 * effect of the upgrade-restart delay is one missed cycle.
 *
 * <h2>Provider injection</h2>
 *
 * <p>This impl is contributed to {@code @Services}, so {@code ServiceContractRegistry}
 * construction would force {@link OutboxMaintenanceService} and its repository dependencies to
 * resolve at registry-build time. There is no known cycle through the {@code @Services} set
 * today — {@link OutboxMaintenanceService} only depends on repositories and configs. Routing
 * through {@link Provider} is defensive symmetry with {@code WorkflowTimerRecoveryServiceImpl}
 * (which has a documented real cycle) and keeps registry build uniformly lightweight; the
 * underlying service is materialised lazily on the first cron fire.
 */
@Singleton
public final class OutboxMaintenanceServiceImpl implements OutboxMaintenanceContract {

    private final Provider<OutboxMaintenanceService> serviceProvider;

    @Inject
    public OutboxMaintenanceServiceImpl(Provider<OutboxMaintenanceService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @CronJob(
            id = "outbox-stale-lease-recovery",
            cron = "*/30 * * * * *",
            mode = ExecutionMode.SINGLE_INSTANCE,
            overlapPolicy = OverlapPolicy.SKIP)
    @Override
    public Future<Void> recoverStaleLeases() {
        return serviceProvider.get().recoverStaleLeases();
    }

    @CronJob(
            id = "outbox-cleanup",
            cron = "0 0 */6 * * *",
            mode = ExecutionMode.SINGLE_INSTANCE,
            overlapPolicy = OverlapPolicy.SKIP)
    @Override
    public Future<Void> cleanup() {
        return serviceProvider.get().cleanup();
    }
}
