// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dagger.Binds;
import dagger.Module;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.workflow.engine.WorkflowEngineModule;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.postgresql.query.PgWorkflowInstanceQueryService;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.retention.PgWorkflowRetentionService;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.query.WorkflowInstanceQueryService;
import dev.vertique.workflow.retention.WorkflowRetentionService;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;

/**
 * Dagger module for the workflow-postgresql (dialect) layer.
 *
 * <p>{@code include}s the portable {@link WorkflowEngineModule}, which owns the engine-internal
 * bindings (the four ops/callback interfaces bound to the portable engine, the
 * {@code @WorkflowRecorders} multibinding, the {@code @OptionalIntentKinds} default set, the
 * optional {@link dev.vertique.workflow.migration.WorkflowMigrationRegistry} binding, and the
 * engine's {@code core}/{@code context} includes). This module contributes only the dialect-specific
 * bindings: the five repository SPIs, the task/timer stores, the transaction runner, and the
 * query/retention services — all bound to their PostgreSQL implementations.
 *
 * <p>Consumers install only {@code WorkflowPostgresqlModule}; the engine wiring arrives transitively
 * through the {@code include}, so application wiring is unchanged.
 *
 * <p>Applications must also include database and connection-pool modules
 * ({@code DbPostgresqlModule}, {@code DbFlywayModule}) so that the {@link io.vertx.sqlclient.Pool}
 * dependency of the PostgreSQL repositories and the {@link WorkflowTxRunnerRepository} (which
 * supplies the {@link PgWorkflowTransactionRunner} with its stage-1 exception mapper) is satisfied.
 *
 * <p>This module lives in the {@code engine} package to access the package-private dialect runner
 * and repository types it binds.
 */
@Module(includes = {WorkflowEngineModule.class, LoggingContextModule.class})
public abstract class WorkflowPostgresqlModule {

    /**
     * Binds {@link PgTimerStore} as the {@link TimerStore} implementation for the
     * {@link SqlClient}-typed stack.
     *
     * @param impl the timer store singleton
     * @return the timer store as the public SPI interface
     */
    @Binds
    @Singleton
    abstract TimerStore<SqlClient> bindTimerStore(PgTimerStore impl);

    /**
     * Binds {@link PgTaskStore} as the {@link TaskStore} implementation for the
     * {@link SqlClient}-typed stack. Storage-only — the engine owns dedup, payload coercion,
     * history append, and instance updates.
     *
     * @param impl the task store singleton
     * @return the task store as the public SPI interface
     */
    @Binds
    @Singleton
    abstract TaskStore<SqlClient> bindTaskStore(PgTaskStore impl);

    // --- Repository SPI bindings ---

    /**
     * Binds {@link PgWorkflowInstanceRepository} as the {@link WorkflowInstanceRepository}
     * implementation for the {@link SqlClient}-typed stack.
     *
     * @param impl the instance repository singleton
     * @return the repository as the transaction-handle-generic SPI
     */
    @Binds
    @Singleton
    abstract WorkflowInstanceRepository<SqlClient> bindInstanceRepository(PgWorkflowInstanceRepository impl);

    /**
     * Binds {@link PgWorkflowHistoryRepository} as the {@link WorkflowHistoryRepository}
     * implementation for the {@link SqlClient}-typed stack.
     *
     * @param impl the history repository singleton
     * @return the repository as the transaction-handle-generic SPI
     */
    @Binds
    @Singleton
    abstract WorkflowHistoryRepository<SqlClient> bindHistoryRepository(PgWorkflowHistoryRepository impl);

    /**
     * Binds {@link PgWorkflowDedupRepository} as the {@link WorkflowDedupRepository} implementation
     * for the {@link SqlClient}-typed stack.
     *
     * @param impl the dedup repository singleton
     * @return the repository as the transaction-handle-generic SPI
     */
    @Binds
    @Singleton
    abstract WorkflowDedupRepository<SqlClient> bindDedupRepository(PgWorkflowDedupRepository impl);

    /**
     * Binds {@link PgBranchTokenRepository} as the {@link BranchTokenRepository} implementation for
     * the {@link SqlClient}-typed stack.
     *
     * @param impl the branch-token repository singleton
     * @return the repository as the transaction-handle-generic SPI
     */
    @Binds
    @Singleton
    abstract BranchTokenRepository<SqlClient> bindBranchTokenRepository(PgBranchTokenRepository impl);

    /**
     * Binds {@link PgJoinStateRepository} as the {@link JoinStateRepository} implementation for the
     * {@link SqlClient}-typed stack.
     *
     * @param impl the join-state repository singleton
     * @return the repository as the transaction-handle-generic SPI
     */
    @Binds
    @Singleton
    abstract JoinStateRepository<SqlClient> bindJoinStateRepository(PgJoinStateRepository impl);

    /**
     * Binds {@link PgWorkflowTransactionRunner} as the {@link WorkflowTransactionRunner}
     * implementation for the {@link SqlClient}-typed stack.
     *
     * <p>The runner owns the workflow transaction boundary and the layered DB-to-workflow exception
     * mapping. Its collaborators — {@link WorkflowTxRunnerRepository} (carrying the stage-1
     * {@link WorkflowPgExceptionMapper}) and the stage-2
     * {@link dev.vertique.workflow.engine.WorkflowExceptionMapper} — are constructed by Dagger via
     * their {@code @Inject} constructors, so no additional bindings are required.
     *
     * @param impl the runner singleton
     * @return the runner as the transaction-handle-generic SPI
     */
    @Binds
    @Singleton
    abstract WorkflowTransactionRunner<SqlClient> bindTransactionRunner(PgWorkflowTransactionRunner impl);

    /**
     * Binds {@link PgWorkflowInstanceQueryService} as the {@link WorkflowInstanceQueryService}
     * implementation.
     *
     * <p>The service delegates directly to {@link dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository},
     * which manages its own connection pool. No transaction wrapper is opened.
     *
     * @param impl the query service singleton
     * @return the query service as the public SPI interface
     */
    @Binds
    @Singleton
    abstract WorkflowInstanceQueryService bindQueryService(PgWorkflowInstanceQueryService impl);

    /**
     * Binds {@link PgWorkflowRetentionService} as the {@link WorkflowRetentionService}
     * implementation.
     *
     * <p>Apps call archive/purge methods on this service from their own scheduled cron tasks.
     * No built-in cleanup verticle is provided.
     *
     * @param impl the retention service singleton
     * @return the retention service as the public SPI interface
     */
    @Binds
    @Singleton
    abstract WorkflowRetentionService bindRetentionService(PgWorkflowRetentionService impl);
}
