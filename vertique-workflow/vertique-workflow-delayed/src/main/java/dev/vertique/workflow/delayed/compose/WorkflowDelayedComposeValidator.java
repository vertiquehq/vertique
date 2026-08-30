// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.compose;

import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.job.JobRepository;
import dev.vertique.job.cron.CronJobRegistrar;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.cron.dagger.CronPersistenceMarker;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireJob;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Startup validator that asserts the application's Dagger graph is correctly composed for
 * workflow timer support.
 *
 * <h2>Validation contract</h2>
 *
 * <p>This validator uses the <em>constructible-as-validation</em> pattern: the act of successfully
 * constructing this object — with {@link WorkflowTimerFireJob}, {@link JobRepository},
 * {@link CronJobRegistrar}, {@link CronScheduler}, and {@link CronPersistenceMarker} all
 * injected — is itself the validation. If any of those bindings is absent from the application
 * graph, Dagger fails at code-generation time rather than at runtime.
 *
 * <p>{@link CronJobRegistrar} and {@link CronScheduler} are required because the recovery loop
 * was migrated from a per-node {@code setPeriodic} verticle to a cluster-singleton
 * {@code @CronJob SINGLE_INSTANCE} method on {@code WorkflowTimerRecoveryCron}. Without
 * the cron infrastructure installed, the application would silently ship without orphan /
 * dead-letter recovery.
 *
 * <p>{@link CronPersistenceMarker} is the only binding that proves
 * {@link dev.vertique.job.cron.dagger.CronPersistenceModule} is installed (not the in-memory
 * {@link dev.vertique.job.cron.dagger.CronModule}). The marker is bound exclusively by the
 * persistence module, so a graph with the in-memory cron flavor cannot satisfy this validator
 * even if {@link CronJobRegistrar}, {@link CronScheduler}, and {@link JobRepository} are all
 * separately satisfied.
 *
 * <p>Because Dagger performs all binding validation at code-generation time, no runtime
 * {@link IllegalStateException} is thrown from this constructor in normal cases. The injected
 * parameters are unused after construction; they exist purely to establish the required dependency
 * edges in the Dagger graph.
 *
 * <h2>How validation runs</h2>
 *
 * <p>The validator is enforced through the recorder constructor:
 * {@link dev.vertique.workflow.delayed.recorder.WorkflowTimerSideEffectRecorder} takes this
 * validator as a required dependency, so Dagger must construct it before the recorder. Because the
 * recorder participates in the {@code @WorkflowRecorders} multibinding consumed by
 * {@code RecorderRouter} inside {@code PgWorkflowEngine}, requesting the engine forces the entire
 * chain — including this validator — to be built at Dagger graph construction time.
 *
 * <p>This validator implements {@link ComposeValidator} so the framework can materialize it in the
 * {@link dev.vertique.core.lifecycle.LifecyclePhase#VALIDATE VALIDATE} lifecycle phase (forcing its
 * construction, hence its validation) without any app code referencing it. The marker adds no
 * methods and does not change how validation runs.
 *
 * @see dev.vertique.workflow.delayed.recorder.WorkflowTimerSideEffectRecorder
 */
@Singleton
public final class WorkflowDelayedComposeValidator implements ComposeValidator {

    /**
     * Validates the application's Dagger graph for workflow timer support.
     *
     * <p>All parameters are required by Dagger:
     * <ul>
     *   <li>{@code fireJob} — proves the {@link WorkflowTimerFireJob} typed proxy is available,
     *       which in turn proves that the delayed-job client factory and its dependencies
     *       ({@link dev.vertique.job.delayed.DelayedJobService}, etc.) are bound.</li>
     *   <li>{@code jobRepository} — proves a {@link JobRepository} implementation is wired (used
     *       by the recovery service to look up job states).</li>
     *   <li>{@code cronJobRegistrar} — proves the cron registrar is on the graph; without it the
     *       {@code @CronJob} on {@code WorkflowTimerRecoveryCron} would not be discovered
     *       and recovery would silently never run.</li>
     *   <li>{@code cronScheduler} — proves the cron scheduler is on the graph; without it the
     *       lifecycle verticle that fires scheduled jobs is missing.</li>
     *   <li>{@code cronPersistenceMarker} — proves
     *       {@link dev.vertique.job.cron.dagger.CronPersistenceModule} (not the in-memory
     *       {@link dev.vertique.job.cron.dagger.CronModule}) is installed. The in-memory module
     *       supplies {@link CronJobRegistrar}/{@link CronScheduler} too, but with a {@code null}
     *       repository internally — so {@code SINGLE_INSTANCE} leader election would only fail
     *       at runtime in {@code CronJobRegistrar.scan()}.</li>
     * </ul>
     *
     * @param fireJob               the typed delayed job client proxy for timer-fire jobs;
     *                              injected for its binding side-effect only
     * @param jobRepository         the job repository implementation; injected for its binding
     *                              side-effect only
     * @param cronJobRegistrar      the cron registrar; injected for its binding side-effect only
     * @param cronScheduler         the cron scheduler; injected for its binding side-effect only
     * @param cronPersistenceMarker the persistence-flavor marker; injected for its binding
     *                              side-effect only
     */
    @Inject
    public WorkflowDelayedComposeValidator(
            WorkflowTimerFireJob fireJob,
            JobRepository jobRepository,
            CronJobRegistrar cronJobRegistrar,
            CronScheduler cronScheduler,
            CronPersistenceMarker cronPersistenceMarker) {
        // All parameters are intentionally unused after construction. Their presence in the
        // constructor forces Dagger to verify that every binding exists in the component graph.
        // This is the constructible-as-validation pattern: if any binding is missing the
        // component fails to compile (Dagger) rather than failing at runtime.
    }
}
