// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.recovery;

import dev.vertique.job.cron.CronJob;
import dev.vertique.job.cron.ExecutionMode;
import dev.vertique.job.cron.OverlapPolicy;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Cron-scheduled implementation of {@link WorkflowTimerRecoveryContract}. Delegates to
 * {@link WorkflowTimerRecoveryService} for the actual reconcile logic.
 *
 * <p>The constructor injects a {@link Provider} of the service rather than the service directly.
 * This breaks a Dagger construction-time cycle: this impl is contributed to {@code @Services},
 * so {@code ServiceContractRegistry} construction would otherwise drag
 * {@link WorkflowTimerRecoveryService} (and its dependency on
 * {@link dev.vertique.workflow.delayed.job.WorkflowTimerFireJob}, which in turn flows through
 * {@code DelayedJobClientFactory → DelayedJobService → DelayedJobHandlerRegistrar →
 * ServiceContractRegistry}) into the registry's own construction. Routing through a
 * {@link Provider} keeps the registry build lightweight; the recovery service is materialised
 * lazily on the first cron fire.
 *
 * <p>Operators tune the cadence via {@code cron.jobs.workflow-timer-recovery.*} config.
 *
 * <p><b>First-fire latency:</b> the prior {@code WorkflowTimerRecoveryVerticle} ran one
 * {@code tick()} immediately at startup. The cron-driven path differs:
 * <ul>
 *   <li>On the <em>first boot after upgrading from the verticle</em> (or any other start without a
 *       persisted {@code job_schedules} row, or with {@code last_fired_at == null}), the first
 *       reconcile runs up to one cron period (~30s by default) after startup —
 *       {@code MisfirePolicy.FIRE_NOW} does not fire when no schedule row exists. Acceptable
 *       because recovery only acts on timers aged past {@code WorkflowTimerRecoveryConfig.gracePeriod}
 *       (default 60s), so the worst-case effect is one missed reconcile cycle of an eventually
 *       correct sweep.</li>
 *   <li>On all subsequent restarts, {@code MisfirePolicy.FIRE_NOW} (the {@code SINGLE_INSTANCE}
 *       default) fires immediately if the prior fire is overdue.</li>
 * </ul>
 */
@Singleton
public final class WorkflowTimerRecoveryServiceImpl implements WorkflowTimerRecoveryContract {

    private final Provider<WorkflowTimerRecoveryService> serviceProvider;

    @Inject
    public WorkflowTimerRecoveryServiceImpl(Provider<WorkflowTimerRecoveryService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @CronJob(
            id = "workflow-timer-recovery",
            cron = "*/30 * * * * *",
            mode = ExecutionMode.SINGLE_INSTANCE,
            overlapPolicy = OverlapPolicy.SKIP)
    @Override
    public Future<Void> reconcile() {
        return serviceProvider.get().reconcile();
    }
}
