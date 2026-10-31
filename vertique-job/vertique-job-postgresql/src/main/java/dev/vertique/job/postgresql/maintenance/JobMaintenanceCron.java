// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql.maintenance;

import dev.vertique.job.cron.CronJob;
import dev.vertique.job.cron.ExecutionMode;
import dev.vertique.job.cron.OverlapPolicy;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Cron-scheduled implementation of {@link JobMaintenanceContract}. Delegates to
 * {@link JobRetentionService} for the actual logic.
 *
 * <p>{@code purgeFinishedExecutions} runs every 15 minutes on the wall clock
 * ({@code "0 *}{@code /15 * * * *"}) on one node of the cluster. Override the schedule with
 * {@code cron.jobs.job-execution-retention.cron}. Each run deletes in bounded batches until a batch
 * comes back short or the per-run cap is reached (see {@link JobRetentionService}), so the cadence
 * only sets how soon an execution is removed after it passes the retention age.
 *
 * <p>Like the other maintenance cron entry points, this class injects a {@link Provider} so the
 * contributed {@code @Services} object does not force the service and its repository to resolve when
 * the service registry is built.
 */
@Singleton
public final class JobMaintenanceCron implements JobMaintenanceContract {

    private final Provider<JobRetentionService> serviceProvider;

    @Inject
    public JobMaintenanceCron(Provider<JobRetentionService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @CronJob(
            id = "job-execution-retention",
            cron = "0 */15 * * * *",
            mode = ExecutionMode.SINGLE_INSTANCE,
            overlapPolicy = OverlapPolicy.SKIP)
    @Override
    public Future<Void> purgeFinishedExecutions() {
        return serviceProvider.get().purgeFinishedExecutions();
    }
}
