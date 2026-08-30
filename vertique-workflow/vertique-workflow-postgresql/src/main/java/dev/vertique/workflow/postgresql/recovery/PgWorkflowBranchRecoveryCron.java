// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.recovery;

import dev.vertique.job.cron.CronJob;
import dev.vertique.job.cron.ExecutionMode;
import dev.vertique.job.cron.OverlapPolicy;
import dev.vertique.workflow.postgresql.engine.PgWorkflowBranchRecoveryService;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Cron-scheduled implementation of {@link WorkflowBranchRecoveryContract}. Delegates to
 * {@link PgWorkflowBranchRecoveryService} for the actual sweep logic.
 *
 * <p>The constructor injects a {@link Provider} of the service rather than the service directly.
 * This is defensive against a conditional Dagger construction-time cycle that closes only when
 * {@code WorkflowServicesModule} is also on the graph: this impl is contributed to
 * {@code @Services}, and {@code PgWorkflowBranchRecoveryService → WorkflowEngine →
 * RecorderRouter → @WorkflowRecorders Set} can reach
 * {@code OutboxSideEffectRecorder → ServiceTargetResolver → ServiceContractRegistry → @Services}.
 * Without {@code WorkflowServicesModule} there is no cycle and {@code Provider<>} simply defers
 * instantiation. The cost of the indirection is one {@code Provider.get()} call per cron fire;
 * binding validity is still enforced at Dagger code-gen, so an AppComponent that installs this
 * cron adapter without {@link dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule}
 * (which provides the sweep service) fails to compile — it does not defer to a runtime cron
 * failure.
 *
 * <p>Operators tune the cadence via {@code cron.jobs.workflow-branch-recovery.*} config.
 *
 * <p><b>First-fire latency:</b> the first reconcile runs up to one cron period (~30s by default)
 * after startup — {@code MisfirePolicy.FIRE_NOW} does not fire when no schedule row exists.
 * Acceptable because recovery only acts on branches whose stale threshold (default 5 minutes)
 * has elapsed, so the worst-case effect is one missed reconcile cycle of an eventually correct
 * sweep.
 */
@Singleton
public final class PgWorkflowBranchRecoveryCron implements WorkflowBranchRecoveryContract {

    private final Provider<PgWorkflowBranchRecoveryService> serviceProvider;
    private final WorkflowBranchRecoveryConfig config;

    /**
     * Creates the cron adapter.
     *
     * @param serviceProvider lazy provider of the sweep service (cycle-breaker)
     * @param config the resolved recovery configuration (batch size + stale threshold)
     */
    @Inject
    public PgWorkflowBranchRecoveryCron(
            Provider<PgWorkflowBranchRecoveryService> serviceProvider, WorkflowBranchRecoveryConfig config) {
        this.serviceProvider = serviceProvider;
        this.config = config;
    }

    @CronJob(
            id = "workflow-branch-recovery",
            cron = "*/30 * * * * *",
            mode = ExecutionMode.SINGLE_INSTANCE,
            overlapPolicy = OverlapPolicy.SKIP)
    @Override
    public Future<Void> reconcile() {
        return serviceProvider
                .get()
                .sweepOnce(config.staleThreshold(), config.batchSize())
                .mapEmpty();
    }
}
