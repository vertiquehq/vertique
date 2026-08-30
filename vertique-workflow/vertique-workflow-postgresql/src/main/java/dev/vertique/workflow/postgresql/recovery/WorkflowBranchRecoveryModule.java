// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.recovery;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.job.cron.dagger.CronPersistenceMarker;
import dev.vertique.services.Services;
import jakarta.inject.Singleton;

/**
 * Opt-in Dagger module that wires the cluster-singleton branch-recovery {@code @CronJob}
 * (PRD-WF-002 §A.4.3, FR-WF-PAR-039).
 *
 * <p>Composing this module makes branch recovery sweep due {@code RETRY_SCHEDULED} branches and
 * demote stale {@code RUNNING} branches every 30 seconds (default). An application that uses
 * fan-out / fan-in workflows ({@code wf.fork(...).join(...)}) MUST include this module — without
 * it, stalled branch tokens never recover after process restart or transient orchestration
 * failure. Single-path workflows do not need it.
 *
 * <p>Keeping this binding outside {@link dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule}
 * preserves the established opt-in convention used by the sibling
 * {@code WorkflowDelayedModule}, {@code WorkflowTasksModule}, {@code WorkflowEventsModule}, etc.:
 * the base persistence module stays cron-free so unit tests and dev composers that don't need
 * cluster-singleton recovery can still wire {@code WorkflowPostgresqlModule} alone.
 *
 * <p>This module must be included alongside:
 * <ul>
 *   <li>{@link dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule} — provides the
 *       underlying {@code PgWorkflowBranchRecoveryService} the cron adapter delegates to.</li>
 *   <li>{@link dev.vertique.job.cron.dagger.CronPersistenceModule} — provides the
 *       {@code CronJobRegistrar}, {@code CronScheduler}, and {@link CronPersistenceMarker}
 *       required for the {@code SINGLE_INSTANCE} recovery cron job.
 *       {@link dev.vertique.job.cron.dagger.CronModule} (in-memory cron, no
 *       {@code JobRepository}) is not sufficient — the {@code CronPersistenceMarker} guard on
 *       {@link #workflowBranchRecoveryService(PgWorkflowBranchRecoveryCron, CronPersistenceMarker)}
 *       fails compilation if only the in-memory module is installed.</li>
 * </ul>
 */
@Module
public abstract class WorkflowBranchRecoveryModule {

    /**
     * Contributes {@link PgWorkflowBranchRecoveryCron} into the {@code @Services}
     * multibinding so {@code CronJobRegistrar} discovers its {@code @CronJob}-annotated reconcile
     * method.
     *
     * <p>The unused {@link CronPersistenceMarker} parameter is the compose-time guard: the marker
     * is bound only by {@code CronPersistenceModule}, so any AppComponent that installs this
     * module along with {@code CronModule} (in-memory cron, no leader-election repository)
     * instead of {@code CronPersistenceModule} fails to compile. The marker has no transitive
     * dependency on {@code CronJobRegistrar}, {@code ServiceContractRegistry}, or the
     * {@code @Services} set itself, so this guard does not close a Dagger construction cycle.
     *
     * @param impl                  the branch-recovery cron adapter
     * @param cronPersistenceMarker the persistence-flavour marker; consumed for its binding
     *                              side-effect only
     * @return the impl as a raw {@link Object} (required by the {@code @Services} binding shape)
     */
    @Provides
    @Singleton
    @IntoSet
    @Services
    static Object workflowBranchRecoveryService(
            PgWorkflowBranchRecoveryCron impl,
            @SuppressWarnings("unused") CronPersistenceMarker cronPersistenceMarker) {
        return impl;
    }

    /**
     * Provides the default {@link WorkflowBranchRecoveryConfig} for the branch-recovery service.
     *
     * <p>Hardcodes the batch size and stale-threshold defaults. The scan cadence is owned by the
     * cron expression on {@code PgWorkflowBranchRecoveryCron} and tunable via
     * {@code cron.jobs.workflow-branch-recovery.cron} config. Applications that need different
     * batch/stale settings must fork the module — Dagger does not allow two {@code @Provides} for
     * the same key, so providing a competing {@code @Singleton WorkflowBranchRecoveryConfig} in
     * an app-level module would fail compilation with a duplicate-binding error. Tracked as
     * <a href="https://github.com/vertiquehq/vertique/issues/58">issue #58</a> for a future
     * config-driven override.
     *
     * @return the default recovery configuration
     */
    @Provides
    @Singleton
    static WorkflowBranchRecoveryConfig branchRecoveryConfig() {
        return WorkflowBranchRecoveryConfig.defaults();
    }
}
