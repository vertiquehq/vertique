// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql.maintenance;

import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.job.cron.CronJobRegistrar;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.cron.dagger.CronPersistenceMarker;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Startup validator that asserts the application's Dagger graph can schedule the job execution
 * retention job added by {@link JobRetentionModule}.
 *
 * <p>Uses the constructible-as-validation pattern: requesting {@link CronJobRegistrar},
 * {@link CronScheduler} and {@link CronPersistenceMarker} forces Dagger to verify those bindings at
 * code-generation time. {@link CronPersistenceMarker} is bound only by
 * {@link dev.vertique.job.cron.dagger.CronPersistenceModule}; the in-memory
 * {@link dev.vertique.job.cron.dagger.CronModule} supplies the registrar and scheduler too, but with
 * no job repository, so {@code SINGLE_INSTANCE} leader election would only fail at runtime.
 */
@Singleton
public final class JobRetentionComposeValidator implements ComposeValidator {

    /**
     * Validates the graph for the retention job.
     *
     * @param cronJobRegistrar      injected for its binding side effect only
     * @param cronScheduler         injected for its binding side effect only
     * @param cronPersistenceMarker injected for its binding side effect only; bound only by the
     *                              persistence-backed cron module
     */
    @Inject
    public JobRetentionComposeValidator(
            CronJobRegistrar cronJobRegistrar,
            CronScheduler cronScheduler,
            CronPersistenceMarker cronPersistenceMarker) {
        // All parameters are intentionally unused after construction.
    }
}
