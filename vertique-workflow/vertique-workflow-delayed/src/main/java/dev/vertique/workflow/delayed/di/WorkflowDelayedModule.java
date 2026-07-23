// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.job.delayed.DelayedJobClientFactory;
import dev.vertique.job.delayed.dagger.DelayedJobs;
import dev.vertique.services.Services;
import dev.vertique.workflow.delayed.compose.WorkflowDelayedComposeValidator;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireExecutor;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireJob;
import dev.vertique.workflow.delayed.recorder.WorkflowTimerSideEffectRecorder;
import dev.vertique.workflow.delayed.recovery.WorkflowTimerRecoveryConfig;
import dev.vertique.workflow.delayed.recovery.WorkflowTimerRecoveryServiceImpl;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;

/**
 * Dagger module that wires the workflow-delayed layer into the application graph.
 *
 * <p>Provides:
 * <ol>
 *   <li>{@link WorkflowTimerSideEffectRecorder} contributed into the
 *       {@code @WorkflowRecorders Set<WorkflowSideEffectRecorder<SqlClient>>} multibinding so the
 *       workflow engine can route {@link dev.vertique.workflow.sideeffect.IntentKind#WORKFLOW_TIMER}
 *       intents to it.</li>
 *   <li>{@link WorkflowTimerFireExecutor} contributed into the {@code @DelayedJobs Set<Object>}
 *       multibinding so the delayed-job infrastructure registers it as a job handler.</li>
 *   <li>{@link WorkflowTimerFireJob} — the typed delayed-job client proxy created by
 *       {@link DelayedJobClientFactory#create(Class)}.</li>
 *   <li>{@link WorkflowTimerRecoveryServiceImpl} contributed into the {@code @Services Set<Object>}
 *       multibinding so {@code CronJobRegistrar} discovers its {@code @CronJob}-annotated
 *       reconcile method.</li>
 *   <li>{@link WorkflowTimerRecoveryConfig} — default configuration for the recovery service.
 *       Cadence is owned by the cron expression on the impl; only batch size and grace period
 *       are configured here. Providing a competing {@code @Singleton} binding in an app module
 *       fails compilation with a duplicate-binding error.</li>
 * </ol>
 *
 * <p>This module must be included alongside:
 * <ul>
 *   <li>{@link dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule} — provides
 *       {@link dev.vertique.workflow.timer.TimerStore}&lt;{@link SqlClient}&gt; and
 *       {@link dev.vertique.workflow.ops.TransactionalTimerCallbacks}&lt;{@link SqlClient}&gt;</li>
 *   <li>{@link dev.vertique.job.delayed.dagger.DelayedJobModule} — provides
 *       {@link DelayedJobClientFactory} and the {@code @DelayedJobs} multibinding</li>
 *   <li>{@link dev.vertique.job.cron.dagger.CronPersistenceModule} — provides the
 *       {@code CronJobRegistrar} / {@code CronScheduler} required for the
 *       {@code SINGLE_INSTANCE} recovery cron job. {@link dev.vertique.job.cron.dagger.CronModule}
 *       (in-memory cron, no {@code JobRepository}) is not sufficient.</li>
 * </ul>
 */
@Module
public abstract class WorkflowDelayedModule {

    /**
     * Contributes the {@link WorkflowTimerSideEffectRecorder} into the qualified
     * {@code @WorkflowRecorders} set multibinding.
     *
     * @param recorder the timer recorder implementation
     * @return the recorder cast to the interface type
     */
    @Provides
    @Singleton
    @IntoSet
    @WorkflowRecorders
    static WorkflowSideEffectRecorder<SqlClient> timerRecorder(WorkflowTimerSideEffectRecorder recorder) {
        return recorder;
    }

    /**
     * Contributes the {@link WorkflowTimerFireExecutor} into the {@code @DelayedJobs} set
     * multibinding so the delayed-job handler registrar picks it up.
     *
     * @param executor the timer-fire executor implementation
     * @return the executor as a raw {@link Object} (required by the {@code @DelayedJobs} binding
     *         shape — see {@link dev.vertique.job.delayed.dagger.DelayedJobs})
     */
    @Provides
    @Singleton
    @IntoSet
    @DelayedJobs
    static Object timerFireExecutor(WorkflowTimerFireExecutor executor) {
        return executor;
    }

    /**
     * Creates the {@link WorkflowTimerFireJob} typed delayed-job client proxy.
     *
     * <p>The proxy is created once at startup via {@link DelayedJobClientFactory#create(Class)}.
     * It routes all {@code enqueue} calls to the {@link dev.vertique.job.delayed.DelayedJobService}
     * with the {@link dev.vertique.job.delayed.DelayedJobContract} defaults for the
     * {@code "vertique.workflow.timer.fire"} contract.
     *
     * @param factory the client factory used to generate the JDK dynamic proxy
     * @return the singleton {@link WorkflowTimerFireJob} proxy
     */
    @Provides
    @Singleton
    static WorkflowTimerFireJob workflowTimerFireJob(DelayedJobClientFactory factory) {
        return factory.create(WorkflowTimerFireJob.class);
    }

    /**
     * Contributes {@link WorkflowTimerRecoveryServiceImpl} into the {@code @Services} multibinding
     * so {@code CronJobRegistrar} discovers its {@code @CronJob}-annotated reconcile method.
     *
     * @param impl the recovery service implementation
     * @return the impl as a raw {@link Object} (required by the {@code @Services} binding shape)
     */
    @Provides
    @Singleton
    @IntoSet
    @Services
    static Object workflowTimerRecoveryService(WorkflowTimerRecoveryServiceImpl impl) {
        return impl;
    }

    /**
     * Provides the default {@link WorkflowTimerRecoveryConfig} for the recovery service.
     *
     * <p>Hardcodes the batch size and grace-period defaults. The scan cadence is owned by the
     * cron expression on {@code WorkflowTimerRecoveryServiceImpl} and tunable via
     * {@code cron.jobs.workflow-timer-recovery.cron} config. Applications that need different
     * batch/grace settings must fork the module — Dagger does not allow two {@code @Provides}
     * for the same key, so providing a competing {@code @Singleton WorkflowTimerRecoveryConfig}
     * in an app-level module would fail compilation with a duplicate-binding error.
     *
     * @return the default recovery configuration
     */
    @Provides
    @Singleton
    static WorkflowTimerRecoveryConfig recoveryConfig() {
        return WorkflowTimerRecoveryConfig.defaults();
    }

    /**
     * Contributes the {@link WorkflowDelayedComposeValidator} into the
     * {@code Set<ComposeValidator>} multibinding so the framework materializes it in the
     * {@link dev.vertique.core.lifecycle.LifecyclePhase#VALIDATE VALIDATE} lifecycle phase (forcing
     * its construction-time, constructible-as-validation check) with no app code referencing it.
     *
     * <p>Dagger constructs the validator through its {@code @Inject} constructor, which establishes
     * the required dependency edges ({@code WorkflowTimerFireJob}, {@code JobRepository}, cron
     * infrastructure, persistence marker).
     *
     * @param impl the compose validator, constructed via its {@code @Inject} constructor
     * @return the validator as a {@link ComposeValidator}
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator delayedComposeValidator(WorkflowDelayedComposeValidator impl) {
        return impl;
    }
}
