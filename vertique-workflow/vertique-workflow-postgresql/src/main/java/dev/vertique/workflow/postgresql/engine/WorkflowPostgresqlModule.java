// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.DbSchemasModule;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.engine.WorkflowEngineModule;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.CommandWakeups;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.TaskReservations;
import dev.vertique.workflow.engine.spi.TransactionalDefinitionReads;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.history.WorkflowInstanceHistoryService;
import dev.vertique.workflow.postgresql.executor.CommandNotificationListener;
import dev.vertique.workflow.postgresql.executor.PgWorkflowCommandRepository;
import dev.vertique.workflow.postgresql.history.PgWorkflowInstanceHistoryService;
import dev.vertique.workflow.postgresql.query.PgWorkflowInstanceQueryService;
import dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.retention.PgWorkflowRetentionRepository;
import dev.vertique.workflow.postgresql.retention.PgWorkflowRetentionService;
import dev.vertique.workflow.postgresql.tasks.PgTaskReservations;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.query.WorkflowInstanceQueryService;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.retention.WorkflowRetentionService;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;

/**
 * Dagger module for the workflow-postgresql (dialect) layer.
 *
 * <p>{@code include}s the portable {@link WorkflowEngineModule}, which owns the engine-internal
 * bindings (the four ops/callback interfaces bound to the portable engine, the
 * {@code @WorkflowRecorders} multibinding, the {@code @OptionalIntentKinds} default set, the
 * optional {@link dev.vertique.workflow.migration.WorkflowMigrationRegistry} binding, and the
 * engine's {@code core}/{@code context} includes). This module contributes only the dialect-specific
 * bindings: the five repository SPIs, the task/timer/definition-registry stores, the transaction runner, and the
 * query, history and retention services — all bound to their PostgreSQL implementations.
 *
 * <p>Consumers install only {@code WorkflowPostgresqlModule}; the engine wiring arrives transitively
 * through the {@code include}, so application wiring is unchanged.
 *
 * <p>The five repositories, the three stores (task, timer and definition registry) and
 * {@link PgWorkflowRetentionRepository} are each
 * provided as one scoped instance built with the optional {@link DbSchemas} placement, so every
 * workflow table is read and written in the schema mapped for the {@code workflow} instance and
 * resolves through the connection's default schema when no mapping exists. The SPI bindings and
 * the query, retention and recovery services all resolve to those same instances. The module also
 * includes {@link WorkflowMigrationsModule}, which contributes the workflow migration declaration,
 * and {@link DbSchemasModule}, which declares the optional {@link DbSchemas} binding.
 *
 * <p>Applications must also include {@code DbPostgresqlModule}, which supplies the
 * {@link io.vertx.sqlclient.Pool} and {@link PgDbExceptionMapper} that the PostgreSQL repositories
 * and the {@link WorkflowTxRunnerRepository} (which supplies the {@link PgWorkflowTransactionRunner}
 * with its stage-1 exception mapper) depend on. {@code DbModule} binds the {@link DbSchemas}
 * placement from the {@code db.schemas} setting; without it the tables stay unqualified. Only an
 * application that runs the migrations at startup also needs {@code DbFlywayModule}.
 *
 * <p>This module lives in the {@code engine} package to access the package-private dialect runner
 * and repository types it binds.
 */
@Module(
        includes = {
            WorkflowEngineModule.class,
            LoggingContextModule.class,
            WorkflowMigrationsModule.class,
            DbSchemasModule.class
        })
public abstract class WorkflowPostgresqlModule {

    /** The time between two reads of the notification queue usage, in milliseconds. */
    private static final long QUEUE_USAGE_INTERVAL_MS = 5_000;

    // --- Executor wake-up ---

    /**
     * Provides the node's wake-up source: one {@link CommandNotificationListener} shared by the node's executor
     * instances, which opens its connection with the pool's connect options when the first instance starts. A graph
     * without a Vert.x instance, or without connect options, has no listening connection to open and gets a source
     * that never wakes anyone, so its executors poll.
     *
     * @param vertx          the optional Vert.x instance
     * @param connectOptions the pool's connect options, when bound
     * @param pool           the PostgreSQL connection pool
     * @param observers      the executor observers the notification queue usage is reported to
     * @param config         the executor configuration, or empty for the defaults; its poll interval is the
     *                       reconnect interval
     * @return the wake-up source
     */
    @Provides
    @Singleton
    static CommandWakeups commandWakeups(
            Optional<Vertx> vertx,
            Optional<PgConnectOptions> connectOptions,
            Pool pool,
            Set<WorkflowExecutorObserver> observers,
            Optional<WorkflowExecutorConfig> config) {
        if (vertx.isEmpty() || connectOptions.isEmpty()) {
            return CommandWakeups.none();
        }
        return new CommandNotificationListener(
                vertx.get(),
                connectOptions.get(),
                pool,
                observers,
                config.orElseGet(WorkflowExecutorConfig::defaults).pollIntervalMs(),
                QUEUE_USAGE_INTERVAL_MS);
    }

    /**
     * Declares the pool's connect options as an optional binding; {@code DbPostgresqlModule} supplies them.
     *
     * @return the optional connect options declaration
     */
    @BindsOptionalOf
    abstract PgConnectOptions optionalPgConnectOptions();

    // --- Scoped PostgreSQL implementations ---

    /**
     * Provides the {@link PgTimerStore}, applying the schema mapping for the {@code workflow}
     * instance when a {@link DbSchemas} binding exists.
     *
     * @param mapper  the PostgreSQL failure mapper
     * @param schemas the optional schema placement; absent means unqualified table names
     * @return the scoped PostgreSQL timer store
     */
    @Provides
    @Singleton
    static PgTimerStore pgTimerStore(PgDbExceptionMapper mapper, Optional<DbSchemas> schemas) {
        return new PgTimerStore(mapper, schemas.orElseGet(DbSchemas::empty));
    }

    /**
     * Provides the {@link PgTaskStore}, applying the schema mapping for the {@code workflow}
     * instance when a {@link DbSchemas} binding exists.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the PostgreSQL failure mapper
     * @param schemas the optional schema placement; absent means unqualified table names
     * @return the scoped PostgreSQL task store
     */
    @Provides
    @Singleton
    static PgTaskStore pgTaskStore(Pool pool, PgDbExceptionMapper mapper, Optional<DbSchemas> schemas) {
        return new PgTaskStore(pool, mapper, schemas.orElseGet(DbSchemas::empty));
    }

    /**
     * Provides the {@link PgTaskReservations}, applying the schema mapping for the {@code workflow}
     * instance when a {@link DbSchemas} binding exists.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the PostgreSQL failure mapper
     * @param schemas the optional schema placement; absent means unqualified table names
     * @return the scoped PostgreSQL task reservations
     */
    @Provides
    @Singleton
    static PgTaskReservations pgTaskReservations(Pool pool, PgDbExceptionMapper mapper, Optional<DbSchemas> schemas) {
        return new PgTaskReservations(pool, mapper, schemas.orElseGet(DbSchemas::empty));
    }

    /**
     * Provides the {@link PgWorkflowInstanceRepository}, applying the schema mapping for the
     * {@code workflow} instance when a {@link DbSchemas} binding exists.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the PostgreSQL failure mapper
     * @param schemas the optional schema placement; absent means unqualified table names
     * @return the scoped PostgreSQL workflow instance repository
     */
    @Provides
    @Singleton
    static PgWorkflowInstanceRepository pgWorkflowInstanceRepository(
            Pool pool, PgDbExceptionMapper mapper, Optional<DbSchemas> schemas) {
        return new PgWorkflowInstanceRepository(pool, mapper, schemas.orElseGet(DbSchemas::empty));
    }

    /**
     * Provides the {@link PgWorkflowHistoryRepository}, applying the schema mapping for the
     * {@code workflow} instance when a {@link DbSchemas} binding exists.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the PostgreSQL failure mapper
     * @param schemas the optional schema placement; absent means unqualified table names
     * @return the scoped PostgreSQL workflow history repository
     */
    @Provides
    @Singleton
    static PgWorkflowHistoryRepository pgWorkflowHistoryRepository(
            Pool pool, PgDbExceptionMapper mapper, Optional<DbSchemas> schemas) {
        return new PgWorkflowHistoryRepository(pool, mapper, schemas.orElseGet(DbSchemas::empty));
    }

    /**
     * Provides the {@link PgWorkflowDedupRepository}, applying the schema mapping for the
     * {@code workflow} instance when a {@link DbSchemas} binding exists.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the PostgreSQL failure mapper
     * @param schemas the optional schema placement; absent means unqualified table names
     * @return the scoped PostgreSQL workflow dedup repository
     */
    @Provides
    @Singleton
    static PgWorkflowDedupRepository pgWorkflowDedupRepository(
            Pool pool, PgDbExceptionMapper mapper, Optional<DbSchemas> schemas) {
        return new PgWorkflowDedupRepository(pool, mapper, schemas.orElseGet(DbSchemas::empty));
    }

    /**
     * Provides the {@link PgBranchTokenRepository}, applying the schema mapping for the
     * {@code workflow} instance when a {@link DbSchemas} binding exists.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the PostgreSQL failure mapper
     * @param schemas the optional schema placement; absent means unqualified table names
     * @return the scoped PostgreSQL branch-token repository
     */
    @Provides
    @Singleton
    static PgBranchTokenRepository pgBranchTokenRepository(
            Pool pool, PgDbExceptionMapper mapper, Optional<DbSchemas> schemas) {
        return new PgBranchTokenRepository(pool, mapper, schemas.orElseGet(DbSchemas::empty));
    }

    /**
     * Provides the {@link PgJoinStateRepository}, applying the schema mapping for the
     * {@code workflow} instance when a {@link DbSchemas} binding exists.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the PostgreSQL failure mapper
     * @param schemas the optional schema placement; absent means unqualified table names
     * @return the scoped PostgreSQL join-state repository
     */
    @Provides
    @Singleton
    static PgJoinStateRepository pgJoinStateRepository(
            Pool pool, PgDbExceptionMapper mapper, Optional<DbSchemas> schemas) {
        return new PgJoinStateRepository(pool, mapper, schemas.orElseGet(DbSchemas::empty));
    }

    /**
     * Provides the {@link PgWorkflowRetentionRepository}, applying the schema mapping for the
     * {@code workflow} instance when a {@link DbSchemas} binding exists.
     *
     * <p>{@link PgWorkflowRetentionService} archives and purges through this scoped instance.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the PostgreSQL failure mapper
     * @param schemas the optional schema placement; absent means unqualified table names
     * @return the scoped PostgreSQL workflow retention repository
     */
    @Provides
    @Singleton
    static PgWorkflowRetentionRepository pgWorkflowRetentionRepository(
            Pool pool, PgDbExceptionMapper mapper, Optional<DbSchemas> schemas) {
        return new PgWorkflowRetentionRepository(pool, mapper, schemas.orElseGet(DbSchemas::empty));
    }

    /**
     * Provides the {@link PgWorkflowDefinitionRegistryStore}, applying the schema mapping for the
     * {@code workflow} instance when a {@link DbSchemas} binding exists.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the PostgreSQL failure mapper
     * @param config  the optional shared document policy; defaults apply when absent
     * @param schemas the optional schema placement; absent means unqualified table names
     * @return the scoped PostgreSQL definition registry store
     */
    @Provides
    @Singleton
    static PgWorkflowDefinitionRegistryStore pgWorkflowDefinitionRegistryStore(
            Pool pool,
            PgDbExceptionMapper mapper,
            Optional<WorkflowDefinitionsConfig> config,
            Optional<DbSchemas> schemas) {
        return new PgWorkflowDefinitionRegistryStore(pool, mapper, config, schemas.orElseGet(DbSchemas::empty));
    }

    /**
     * Provides the {@link PgWorkflowCommandRepository}, applying the schema mapping for the {@code workflow}
     * instance when a {@link DbSchemas} binding exists.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the PostgreSQL failure mapper
     * @param schemas the optional schema placement; absent means unqualified table names
     * @param recording the optional recording configuration; defaults apply when absent
     * @param propagator the propagator that captures the durable context stored with each recorded command
     * @return the scoped PostgreSQL command repository
     */
    @Provides
    @Singleton
    static PgWorkflowCommandRepository pgWorkflowCommandRepository(
            Pool pool,
            PgDbExceptionMapper mapper,
            Optional<DbSchemas> schemas,
            Optional<WorkflowRecordingConfig> recording,
            DurableContextPropagator propagator) {
        return new PgWorkflowCommandRepository(
                pool,
                mapper,
                schemas.orElseGet(DbSchemas::empty),
                recording.orElseGet(WorkflowRecordingConfig::defaults),
                propagator);
    }

    // --- Store SPI bindings ---

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

    /**
     * Binds {@link PgTaskReservations} as the {@link TaskReservations} implementation for the
     * {@link SqlClient}-typed stack.
     *
     * @param impl the task reservations singleton
     * @return the reservations as the engine SPI interface
     */
    @Binds
    @Singleton
    abstract TaskReservations<SqlClient> bindTaskReservations(PgTaskReservations impl);

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
     * Binds {@link PgWorkflowCommandRepository} as the {@link WorkflowCommandRepository} implementation for the
     * {@link SqlClient}-typed stack.
     *
     * @param impl the command repository singleton
     * @return the repository as the transaction-handle-generic SPI
     */
    @Binds
    @Singleton
    abstract WorkflowCommandRepository<SqlClient> bindCommandRepository(PgWorkflowCommandRepository impl);

    /**
     * Binds {@link PgWorkflowTransactionRunner} as the {@link WorkflowTransactionRunner}
     * implementation for the {@link SqlClient}-typed stack.
     *
     * <p>The runner owns the workflow transaction boundary and the layered DB-to-workflow exception
     * mapping. Its collaborators — {@link WorkflowTxRunnerRepository} (carrying the stage-1
     * {@link PgWorkflowExceptionMapper}) and the stage-2
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
     * Provides the {@link WorkflowInstanceHistoryService}, reading through the scoped history repository and the
     * command table of the same schema placement.
     *
     * @param history the scoped PostgreSQL history repository
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the PostgreSQL failure mapper
     * @param schemas the optional schema placement; absent means unqualified table names
     * @return the history service
     */
    @Provides
    @Singleton
    static WorkflowInstanceHistoryService historyService(
            PgWorkflowHistoryRepository history, Pool pool, PgDbExceptionMapper mapper, Optional<DbSchemas> schemas) {
        return new PgWorkflowInstanceHistoryService(history, pool, mapper, schemas.orElseGet(DbSchemas::empty));
    }

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

    /**
     * Binds {@link PgWorkflowDefinitionRegistryStore} as the {@link WorkflowDefinitionRegistryStore}
     * implementation, using the existing application pool and the scoped store instance.
     *
     * @param store the definition registry store singleton
     * @return the store as the public SPI interface
     */
    @Binds
    @Singleton
    abstract WorkflowDefinitionRegistryStore definitionRegistryStore(PgWorkflowDefinitionRegistryStore store);

    /**
     * Binds {@link PgWorkflowDefinitionRegistryStore} as the engine's reads of definition rows on a caller's
     * transaction, so that recording a start selects its version from the database on the caller's connection.
     *
     * @param store the definition registry store singleton
     * @return the store as the engine-internal read interface
     */
    @Binds
    abstract TransactionalDefinitionReads<SqlClient> definitionReads(PgWorkflowDefinitionRegistryStore store);
}
