// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.WorkflowExceptionMapper;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for the two-stage layered exception mapping performed by
 * {@link PgWorkflowTransactionRunner} (stage-1 {@link WorkflowPgExceptionMapper} +
 * stage-2 {@link WorkflowExceptionMapper}).
 *
 * <p>Each test runs a real transaction whose body raises a chosen failure, then asserts the
 * exception surfaced through the runner's returned {@link Future} matches the layered policy:
 *
 * <ul>
 *   <li>a {@link dev.vertique.workflow.exception.WorkflowException} from the body passes through
 *       unchanged;</li>
 *   <li>a DB driver exception ({@link PgException}) is first mapped (stage 1) to a
 *       {@link DataAccessException} subtype, then (stage 2) to the expected workflow exception;</li>
 *   <li>an unknown non-workflow {@link RuntimeException} passes through both stages unchanged.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class WorkflowTransactionRunnerLayeredMappingIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_tx_runner_mapping_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowTransactionRunner<SqlClient> runner;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(4))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();

        runner = new PgWorkflowTransactionRunner(
                new WorkflowTxRunnerRepository(pool, new WorkflowPgExceptionMapper()), new WorkflowExceptionMapper());

        ctx.completeNow();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    /**
     * Case (a): a {@link WorkflowInstanceNotFoundException} raised by the transaction body surfaces
     * unchanged — not wrapped in a {@link DataAccessException} or any other type.
     */
    @Test
    @DisplayName("WorkflowException from tx body passes through unchanged")
    void workflowExceptionFromTxBodyPassesThroughUnchanged(VertxTestContext ctx) {
        WorkflowInstanceNotFoundException raised =
                new WorkflowInstanceNotFoundException(new WorkflowInstanceId(UUID.randomUUID()));

        runner.inTransaction(null, tx -> Future.failedFuture(raised))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "transaction must fail");
                    assertSame(raised, ar.cause(), "WorkflowException must pass through unchanged (same instance)");
                    ctx.completeNow();
                }));
    }

    /**
     * Case (b): a deadlock from the driver (PostgreSQL SQL state {@code 40P01}) maps stage-1 to a
     * {@link dev.vertique.db.exception.DeadlockException}, then stage-2 to a retryable
     * {@link WorkflowPersistenceException} whose cause is the {@link DataAccessException}.
     */
    @Test
    @DisplayName("DatabaseException (deadlock) maps to a retryable WorkflowPersistenceException")
    void databaseExceptionMapsToRetryableWorkflowPersistenceException(VertxTestContext ctx) {
        PgException deadlock = new PgException("deadlock detected", "ERROR", "40P01", null);

        runner.inTransaction(null, tx -> Future.<Void>failedFuture(deadlock))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "transaction must fail");
                    WorkflowPersistenceException mapped = assertInstanceOf(
                            WorkflowPersistenceException.class,
                            ar.cause(),
                            "deadlock must map to WorkflowPersistenceException");
                    assertTrue(mapped.retryable(), "deadlock must be retryable");
                    assertInstanceOf(
                            DataAccessException.class,
                            mapped.getCause(),
                            "cause must be the stage-1 DataAccessException");
                    ctx.completeNow();
                }));
    }

    /**
     * Case (b'): a transient DB failure (SQL state class {@code 53}, insufficient resources, which
     * maps stage-1 to a raw {@link dev.vertique.db.exception.TransientDataAccessException}) maps
     * stage-2 to a <em>retryable</em> {@link WorkflowPersistenceException}. Although it is not a
     * deadlock / lock / timeout subtype, it is still a {@code TransientDataAccessException} and is
     * therefore retry-safe.
     */
    @Test
    @DisplayName("transient DataAccessException (class 53) maps to a retryable WorkflowPersistenceException")
    void transientDataAccessExceptionMapsToRetryableWorkflowPersistenceException(VertxTestContext ctx) {
        PgException resourceError = new PgException("disk full", "ERROR", "53100", null);

        runner.inTransaction(null, tx -> Future.<Void>failedFuture(resourceError))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "transaction must fail");
                    WorkflowPersistenceException mapped = assertInstanceOf(
                            WorkflowPersistenceException.class,
                            ar.cause(),
                            "transient DataAccessException must map to WorkflowPersistenceException");
                    assertTrue(mapped.retryable(), "transient persistence failure (class 53) must be retryable");
                    ctx.completeNow();
                }));
    }

    /**
     * Case (b''): an optimistic-locking failure from the driver (SQL state {@code 40001},
     * serialization failure) maps stage-1 to
     * {@link dev.vertique.db.exception.OptimisticLockingFailureException}, then stage-2 to a
     * {@link WorkflowConflictException}.
     */
    @Test
    @DisplayName("OptimisticLockingFailureException maps to a WorkflowConflictException")
    void optimisticLockingFailureMapsToWorkflowConflictException(VertxTestContext ctx) {
        PgException serializationFailure = new PgException("could not serialize access", "ERROR", "40001", null);

        runner.inTransaction(null, tx -> Future.<Void>failedFuture(serializationFailure))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "transaction must fail");
                    assertInstanceOf(
                            WorkflowConflictException.class,
                            ar.cause(),
                            "serialization failure must map to WorkflowConflictException");
                    ctx.completeNow();
                }));
    }

    /**
     * Case (c): an unknown non-workflow {@link RuntimeException} (a programmer error) passes through
     * both stages unchanged — not wrapped in a {@link DataAccessException} or a workflow exception.
     */
    @Test
    @DisplayName("unknown RuntimeException passes through both stages unchanged")
    void unknownRuntimeExceptionPassesThroughBothStagesUnchanged(VertxTestContext ctx) {
        IllegalStateException raised = new IllegalStateException("bug");

        runner.inTransaction(null, tx -> Future.<Void>failedFuture(raised))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "transaction must fail");
                    assertSame(
                            raised, ar.cause(), "unknown RuntimeException must pass through unchanged (same instance)");
                    ctx.completeNow();
                }));
    }
}
