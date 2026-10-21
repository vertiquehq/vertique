// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskFilter;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests proving the start scope and the definition scope are stored on workflow instances, that a task
 * takes the start scope of its instance, and that the scope is an exact-match filter in keyset order, against a real
 * PostgreSQL instance.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ScopeKeyPersistenceIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_scope_key")
            .withMigration("classpath:db/migration/workflow");

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");

    static Pool pool;
    static PgWorkflowInstanceRepository instances;
    static PgTaskStore tasks;

    @BeforeAll
    static void setUp(Vertx vertx) {
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
        PgDbExceptionMapper mapper = new PgDbExceptionMapper();
        instances = new PgWorkflowInstanceRepository(pool, mapper);
        tasks = new PgTaskStore(pool, mapper);
    }

    @BeforeEach
    void truncateTables() throws Exception {
        await(pool.query("TRUNCATE TABLE workflow_timers, workflow_tasks, workflow_history, workflow_dedup,"
                        + " workflow_branch_tokens, workflow_join_states, workflow_instances"
                        + " RESTART IDENTITY CASCADE")
                .execute());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (pool != null) {
            await(pool.close());
        }
    }

    @Test
    void instanceAndTaskScopesRoundTripAndFilter() throws Exception {
        // Given three instances started in scope acme, one in globex and one in the default scope, all defined in
        // the default scope, newest last (three in acme so that a page of two needs a continuation)
        Seeded acme1 = seed("acme", T0.plusSeconds(60));
        Seeded acme2 = seed("acme", T0.plusSeconds(120));
        Seeded globex = seed("globex", T0.plusSeconds(150));
        Seeded acme3 = seed("acme", T0.plusSeconds(180));
        Seeded standard = seed(WorkflowScopes.DEFAULT, T0.plusSeconds(240));

        // When the rows are read back
        WorkflowInstance acmeRead =
                await(instances.findById(acme1.instanceId())).orElseThrow();
        WorkflowInstance globexRead =
                await(instances.findById(globex.instanceId())).orElseThrow();
        WorkflowInstance standardRead =
                await(instances.findById(standard.instanceId())).orElseThrow();

        // Then each instance returns its start scope and its definition scope, and the columns hold the same values
        assertThat(acmeRead.scopeKey()).isEqualTo("acme");
        assertThat(acmeRead.definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(globexRead.scopeKey()).isEqualTo("globex");
        assertThat(globexRead.definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(standardRead.scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(standardRead.definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(storedScope("workflow_instances", "id", acme1.instanceId().value()))
                .isEqualTo("acme");
        assertThat(storedScope("workflow_instances", "id", globex.instanceId().value()))
                .isEqualTo("globex");
        assertThat(storedScope("workflow_instances", "id", standard.instanceId().value()))
                .isEqualTo("default");
        assertThat(storedColumn(
                        "workflow_instances",
                        "definition_scope_key",
                        "id",
                        acme1.instanceId().value()))
                .isEqualTo("default");

        // And a task takes the start scope of its instance, although its record carried the default scope
        assertThat(await(tasks.findById(acme1.taskId(), pool)).orElseThrow().scopeKey())
                .isEqualTo("acme");
        assertThat(await(tasks.findById(globex.taskId(), pool)).orElseThrow().scopeKey())
                .isEqualTo("globex");
        assertThat(await(tasks.findById(standard.taskId(), pool)).orElseThrow().scopeKey())
                .isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(storedScope("workflow_tasks", "task_id", acme1.taskId())).isEqualTo("acme");
        assertThat(storedScope("workflow_tasks", "task_id", globex.taskId())).isEqualTo("globex");

        // When instances are queried for scope acme with a page of two
        WorkflowInstanceQuery acmeQuery = WorkflowInstanceQuery.none().withScopeKey("acme");
        PagedResult<WorkflowInstance> instancePage1 = await(instances.findFiltered(acmeQuery, PageCursor.first(2)));
        PagedResult<WorkflowInstance> instancePage2 =
                await(instances.findFiltered(acmeQuery, PageCursor.fromToken(instancePage1.nextCursorToken())));

        // Then only acme rows return, newest first, across the page boundary
        assertThat(ids(instancePage1.items(), WorkflowInstance::id))
                .containsExactly(acme3.instanceId(), acme2.instanceId());
        assertThat(instancePage1.hasMore()).isTrue();
        assertThat(ids(instancePage2.items(), WorkflowInstance::id)).containsExactly(acme1.instanceId());
        assertThat(instancePage2.hasMore()).isFalse();
        assertThat(instancePage1.items()).extracting(WorkflowInstance::scopeKey).containsOnly("acme");

        // When tasks are filtered for scope acme with a page of two
        TaskFilter acmeFilter = TaskFilter.empty().withScopeKey("acme");
        PagedResult<TaskRecord> taskPage1 = await(tasks.findByFilter(acmeFilter, PageCursor.first(2), pool));
        PagedResult<TaskRecord> taskPage2 =
                await(tasks.findByFilter(acmeFilter, PageCursor.fromToken(taskPage1.nextCursorToken()), pool));

        // Then only acme tasks return, newest first, across the page boundary
        assertThat(ids(taskPage1.items(), TaskRecord::taskId)).containsExactly(acme3.taskId(), acme2.taskId());
        assertThat(taskPage1.hasMore()).isTrue();
        assertThat(ids(taskPage2.items(), TaskRecord::taskId)).containsExactly(acme1.taskId());
        assertThat(taskPage2.hasMore()).isFalse();
        assertThat(taskPage1.items()).extracting(TaskRecord::scopeKey).containsOnly("acme");

        // When the default scope is queried by value, and no scope is named
        PagedResult<WorkflowInstance> defaultOnly = await(instances.findFiltered(
                WorkflowInstanceQuery.none().withScopeKey(WorkflowScopes.DEFAULT), PageCursor.first(20)));
        PagedResult<WorkflowInstance> everything =
                await(instances.findFiltered(WorkflowInstanceQuery.none(), PageCursor.first(20)));

        // Then the default scope selects its own row only and an absent scope constrains nothing
        assertThat(ids(defaultOnly.items(), WorkflowInstance::id)).containsExactly(standard.instanceId());
        assertThat(everything.items()).hasSize(5);
    }

    // --- Fixtures ---

    /** The ids of an instance and of the single task seeded under it. */
    private record Seeded(WorkflowInstanceId instanceId, UUID taskId) {}

    /**
     * Inserts a running instance started in the given scope and defined in the default scope, with one open task,
     * both stamped at {@code at}. The task record deliberately carries the default scope whatever the instance's.
     */
    private static Seeded seed(String scopeKey, Instant at) throws Exception {
        WorkflowInstanceId instanceId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        await(pool.withTransaction(tx -> instances
                .insert(instance(instanceId, scopeKey, at), tx)
                .compose(v -> tasks.insertOpen(task(taskId, instanceId, at), tx))));
        return new Seeded(instanceId, taskId);
    }

    private static WorkflowInstance instance(WorkflowInstanceId id, String scopeKey, Instant at) {
        return new WorkflowInstance(
                id,
                "order-fulfillment",
                1L,
                "abc123hash",
                0L,
                WorkflowStatus.RUNNING,
                null,
                null,
                "start",
                null,
                null,
                null,
                "{}",
                null,
                null,
                at,
                at,
                null,
                scopeKey,
                WorkflowScopes.DEFAULT);
    }

    private static TaskRecord task(UUID taskId, WorkflowInstanceId workflowId, Instant at) {
        return new TaskRecord(
                taskId,
                workflowId,
                "review",
                new TaskAssignment.Role("reviewers"),
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", "java.lang.String", "next")),
                null,
                null,
                null,
                null,
                null,
                at,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                WorkflowScopes.DEFAULT);
    }

    private static <T, I> List<I> ids(List<T> items, Function<T, I> id) {
        List<I> result = new ArrayList<>();
        for (T item : items) {
            result.add(id.apply(item));
        }
        return result;
    }

    /** Reads the stored start scope of the row. */
    private static String storedScope(String table, String idColumn, UUID id) throws Exception {
        return storedColumn(table, "scope_key", idColumn, id);
    }

    private static String storedColumn(String table, String column, String idColumn, UUID id) throws Exception {
        List<Row> rows = new ArrayList<>();
        await(pool.preparedQuery("SELECT " + column + " FROM " + table + " WHERE " + idColumn + " = $1")
                .execute(Tuple.of(id))
                .onSuccess(rs -> rs.forEach(rows::add)));
        assertThat(rows).as("one %s row for %s", table, id).hasSize(1);
        return rows.get(0).getString(column);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
