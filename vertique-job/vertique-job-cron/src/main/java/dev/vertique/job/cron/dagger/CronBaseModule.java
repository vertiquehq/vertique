// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron.dagger;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.job.cron.CronJobRegistrar;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.dagger.JobModule;

/**
 * Shared Dagger base module for cron scheduling.
 *
 * <p>Includes {@link JobModule} to ensure the job interceptor multibinding ({@code Set<JobInterceptor>})
 * is declared. Does not provide {@link dev.vertique.job.cron.CronScheduler} or
 * {@link dev.vertique.job.cron.CronJobRegistrar} — those are provided by either
 * {@link CronModule} (in-memory) or {@link CronPersistenceModule} (DB-backed).
 *
 * <p>Application modules must include exactly one of:
 * <ul>
 *   <li>{@link CronModule} — for in-memory-only operation (no {@link dev.vertique.job.JobRepository}
 *       required; SINGLE_INSTANCE mode not available)</li>
 *   <li>{@link CronPersistenceModule} — for DB-backed operation (requires a
 *       {@link dev.vertique.job.JobRepository} binding; enables SINGLE_INSTANCE mode and
 *       execution tracking)</li>
 * </ul>
 *
 * <p>Both parent modules transitively contribute the {@link CronLifecycleVerticle} via this
 * base module, so including either is sufficient to wire cron startup into the application —
 * no manual {@code scan()}/{@code start()} calls are required.
 */
@Module(includes = JobModule.class)
public abstract class CronBaseModule {

    /**
     * Contributes the cron lifecycle deployment so that including any cron module is sufficient
     * to wire cron startup into the application.
     *
     * @param registrar the cron job registrar that scans the service registry on start
     * @param scheduler the cron scheduler that arms timers on start and cancels them on stop
     * @return a deployment descriptor for the cron lifecycle verticle
     */
    @Provides
    @IntoSet
    static VerticleDeployment cronSchedulerDeployment(CronJobRegistrar registrar, CronScheduler scheduler) {
        return VerticleDeployment.of(
                CronLifecycleVerticle.DEPLOYMENT_NAME,
                () -> new CronLifecycleVerticle(registrar, scheduler),
                LifecyclePhase.SERVICES,
                100);
    }
}
