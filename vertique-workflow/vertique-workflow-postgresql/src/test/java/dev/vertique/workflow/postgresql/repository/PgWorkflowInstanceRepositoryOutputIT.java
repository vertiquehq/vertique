// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.engine.WorkflowTestTables;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that {@link PgWorkflowInstanceRepository} persists the declared output of an instance: {@code insert},
 * {@code findById} and {@code findByIdForUpdate} round-trip the two output columns, the {@code updateOptimistic} that
 * completes an instance writes them, and an update that does not complete it leaves a stored output as it was.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowInstanceRepositoryOutputIT {

    private static final long WAIT_SECONDS = 15;
    private static final String OUTPUT_TYPE = "declared.output.Receipt";

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_instance_output")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgWorkflowInstanceRepository repository;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        repository = new PgWorkflowInstanceRepository(pool, new PgDbExceptionMapper());
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables() {
        join(WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances"
                                + " RESTART IDENTITY CASCADE")
                .execute());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("insert, findById and findByIdForUpdate return the output columns of the row")
    void insertAndReadsRoundTripTheOutputColumns() {
        // Given a completed instance that carries an output
        WorkflowInstanceId id = newId();
        WorkflowInstance completed = instance(id, WorkflowStatus.COMPLETED, 0L).withOutput(OUTPUT_JSON, OUTPUT_TYPE);

        // When it is inserted and read back
        join(pool.withTransaction(tx -> repository.insert(completed, tx)));
        WorkflowInstance found = join(repository.findById(id)).orElseThrow();
        WorkflowInstance locked = join(pool.withTransaction(tx -> repository.findByIdForUpdate(id, tx)))
                .orElseThrow();

        // Then both columns come back, the JSON as the same value and the type name as written
        assertThat(new JsonObject(found.outputJson())).isEqualTo(new JsonObject(OUTPUT_JSON));
        assertThat(found.outputTypeName()).isEqualTo(OUTPUT_TYPE);
        assertThat(new JsonObject(locked.outputJson())).isEqualTo(new JsonObject(OUTPUT_JSON));
        assertThat(locked.outputTypeName()).isEqualTo(OUTPUT_TYPE);
    }

    @Test
    @DisplayName("an instance inserted without an output reads back with both output components null")
    void insertWithoutAnOutputReadsBackNull() {
        // Given a running instance with no output
        WorkflowInstanceId id = newId();

        // When it is inserted and read back
        join(pool.withTransaction(tx -> repository.insert(instance(id, WorkflowStatus.RUNNING, 0L), tx)));
        WorkflowInstance found = join(repository.findById(id)).orElseThrow();

        // Then neither output component is set
        assertThat(found.outputJson()).isNull();
        assertThat(found.outputTypeName()).isNull();
    }

    @Test
    @DisplayName("the updateOptimistic that completes an instance writes its output with the new status")
    void completingUpdateWritesTheOutput() {
        // Given a running instance with no output
        WorkflowInstanceId id = newId();
        join(pool.withTransaction(tx -> repository.insert(instance(id, WorkflowStatus.RUNNING, 0L), tx)));
        WorkflowInstance running = join(repository.findById(id)).orElseThrow();

        // When the update to COMPLETED carries an output
        WorkflowInstance completing = running.withVersion(1L)
                .withStatus(WorkflowStatus.COMPLETED)
                .withOutput(OUTPUT_JSON, OUTPUT_TYPE)
                .withUpdatedAt(Instant.now());
        int updated = join(pool.withTransaction(tx -> repository.updateOptimistic(completing, 0L, tx)));

        // Then the row is COMPLETED and holds the output and its type name
        assertThat(updated).isEqualTo(1);
        WorkflowInstance found = join(repository.findById(id)).orElseThrow();
        assertThat(found.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(new JsonObject(found.outputJson())).isEqualTo(new JsonObject(OUTPUT_JSON));
        assertThat(found.outputTypeName()).isEqualTo(OUTPUT_TYPE);
    }

    @Test
    @DisplayName("an update of a running instance that is not the completing one leaves the output columns null")
    void updateOfARunningInstanceLeavesTheOutputNull() {
        // Given a running instance with no output
        WorkflowInstanceId id = newId();
        join(pool.withTransaction(tx -> repository.insert(instance(id, WorkflowStatus.RUNNING, 0L), tx)));
        WorkflowInstance running = join(repository.findById(id)).orElseThrow();

        // When it moves to another step and stays running
        WorkflowInstance moved =
                running.withVersion(1L).withCurrentStepId("next").withUpdatedAt(Instant.now());
        int updated = join(pool.withTransaction(tx -> repository.updateOptimistic(moved, 0L, tx)));

        // Then the step changed and the output columns are still null
        assertThat(updated).isEqualTo(1);
        WorkflowInstance found = join(repository.findById(id)).orElseThrow();
        assertThat(found.currentStepId()).isEqualTo("next");
        assertThat(found.outputJson()).isNull();
        assertThat(found.outputTypeName()).isNull();
    }

    @Test
    @DisplayName("an update that does not complete a completed instance carries the stored output unchanged")
    void updateOfACompletedInstanceCarriesTheStoredOutput() {
        // Given a completed instance whose row holds an output
        WorkflowInstanceId id = newId();
        join(pool.withTransaction(tx -> repository.insert(
                instance(id, WorkflowStatus.COMPLETED, 0L).withOutput(OUTPUT_JSON, OUTPUT_TYPE), tx)));
        WorkflowInstance stored = join(repository.findById(id)).orElseThrow();

        // When a later update, read from the row and changed in an unrelated field, is written
        WorkflowInstance later = stored.withVersion(1L).withUpdatedAt(Instant.now());
        int updated = join(pool.withTransaction(tx -> repository.updateOptimistic(later, 0L, tx)));

        // Then the output and its type name are those that were stored
        assertThat(updated).isEqualTo(1);
        WorkflowInstance found = join(repository.findById(id)).orElseThrow();
        assertThat(found.version()).isEqualTo(1L);
        assertThat(new JsonObject(found.outputJson())).isEqualTo(new JsonObject(OUTPUT_JSON));
        assertThat(found.outputTypeName()).isEqualTo(OUTPUT_TYPE);
    }

    @Test
    @DisplayName("the output column holds its JSON in the same form as the state column")
    void theOutputIsEncodedLikeTheState() {
        // Given a completed instance with a state and an output, both JSON objects
        WorkflowInstanceId id = newId();
        join(pool.withTransaction(tx -> repository.insert(
                instance(id, WorkflowStatus.COMPLETED, 0L).withOutput(OUTPUT_JSON, OUTPUT_TYPE), tx)));

        // When the database is asked for the JSON type of each column
        var row = join(pool.preparedQuery("SELECT jsonb_typeof(state_json) AS state_type,"
                                + " jsonb_typeof(output_json) AS output_type FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next();

        // Then the output is stored the way the state is
        assertThat(row.getString("output_type")).isEqualTo(row.getString("state_type"));
    }

    private static final String OUTPUT_JSON = "{\"orderId\":\"o-1\",\"trackingCode\":\"TRK9\"}";

    private static WorkflowInstanceId newId() {
        return new WorkflowInstanceId(UUID.randomUUID());
    }

    private static WorkflowInstance instance(WorkflowInstanceId id, WorkflowStatus status, long version) {
        Instant now = Instant.now();
        return new WorkflowInstance(
                id,
                "order-fulfillment",
                1L,
                "abc123hash",
                version,
                status,
                null,
                null,
                "start",
                null,
                null,
                null,
                "{}",
                null,
                null,
                now,
                now,
                null);
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future did not complete: " + e, e);
        }
    }
}
