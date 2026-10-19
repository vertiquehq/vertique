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
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
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
 * Integration tests proving the tenant key is stored on workflow instances, inherited by their tasks and
 * usable as an exact-match filter, against a real PostgreSQL instance.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class TenantKeyPersistenceIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_tenant_key")
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
    void instanceTenantRoundTripsAndTasksTakeTheirInstanceTenant() throws Exception {
        // Given an instance with tenant t1 and one with none
        WorkflowInstanceId tenantInstanceId = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId plainInstanceId = new WorkflowInstanceId(UUID.randomUUID());
        await(pool.withTransaction(tx -> instances
                .insert(instance(tenantInstanceId, "t1", T0, null), tx)
                .compose(v -> instances.insert(instance(plainInstanceId, null, T0, null), tx))));

        // And tasks whose own tenant disagrees with their instance, single-path and branch-owned
        UUID tenantSingle = UUID.randomUUID();
        UUID tenantBranch = UUID.randomUUID();
        UUID plainSingle = UUID.randomUUID();
        UUID plainBranch = UUID.randomUUID();

        // When the tasks are inserted through the store
        await(pool.withTransaction(tx -> tasks.insertOpen(task(tenantSingle, tenantInstanceId, null, T0, false), tx)
                .compose(v -> tasks.insertOpen(task(tenantBranch, tenantInstanceId, "t2", T0, true), tx))
                .compose(v -> tasks.insertOpen(task(plainSingle, plainInstanceId, "t2", T0, false), tx))
                .compose(v -> tasks.insertOpen(task(plainBranch, plainInstanceId, "t1", T0, true), tx))));

        // Then rows and records carry exactly the instance's tenant
        assertThat(await(instances.findById(tenantInstanceId)).orElseThrow().tenantKey())
                .isEqualTo("t1");
        assertThat(await(instances.findById(plainInstanceId)).orElseThrow().tenantKey())
                .isNull();
        assertThat(storedTenant("workflow_instances", "id", tenantInstanceId.value()))
                .isEqualTo("t1");
        assertThat(storedTenant("workflow_instances", "id", plainInstanceId.value()))
                .isNull();
        assertThat(await(tasks.findById(tenantSingle, pool)).orElseThrow().tenantKey())
                .isEqualTo("t1");
        assertThat(await(tasks.findById(tenantBranch, pool)).orElseThrow().tenantKey())
                .isEqualTo("t1");
        assertThat(await(tasks.findById(plainSingle, pool)).orElseThrow().tenantKey())
                .isNull();
        assertThat(await(tasks.findById(plainBranch, pool)).orElseThrow().tenantKey())
                .isNull();
        assertThat(storedTenant("workflow_tasks", "task_id", tenantSingle)).isEqualTo("t1");
        assertThat(storedTenant("workflow_tasks", "task_id", tenantBranch)).isEqualTo("t1");
        assertThat(storedTenant("workflow_tasks", "task_id", plainSingle)).isNull();
        assertThat(storedTenant("workflow_tasks", "task_id", plainBranch)).isNull();

        // When the instances are updated through the optimistic update, carrying a conflicting tenant
        WorkflowInstance storedTenantInstance =
                await(instances.findById(tenantInstanceId)).orElseThrow();
        WorkflowInstance storedPlainInstance =
                await(instances.findById(plainInstanceId)).orElseThrow();
        WorkflowInstance changedTenantInstance = withTenant(
                storedTenantInstance.withState("{\"step\":2}").withVersion(1L).withUpdatedAt(T0.plusSeconds(60)), "t9");
        WorkflowInstance changedPlainInstance = withTenant(
                storedPlainInstance.withState("{\"step\":2}").withVersion(1L).withUpdatedAt(T0.plusSeconds(60)), "t9");
        assertThat((int) await(pool.withTransaction(tx -> instances.updateOptimistic(changedTenantInstance, 0L, tx))))
                .isEqualTo(1);
        assertThat((int) await(pool.withTransaction(tx -> instances.updateOptimistic(changedPlainInstance, 0L, tx))))
                .isEqualTo(1);

        // And the tasks go through their normal transitions
        WorkflowActor actor = new WorkflowActor.User("alice");
        await(pool.withTransaction(tx -> tasks.incrementRemindersFiredCount(tenantSingle, tx)));
        await(pool.withTransaction(
                tx -> tasks.markCompleted(tenantSingle, "approve", "\"ok\"", T0.plusSeconds(60), actor, tx)));
        await(pool.withTransaction(
                tx -> tasks.markCancelled(tenantBranch, "withdrawn", T0.plusSeconds(60), actor, tx)));
        await(pool.withTransaction(tx -> tasks.markExpired(plainSingle, T0.plusSeconds(60), tx)));
        await(pool.withTransaction(tx -> tasks.reassign(
                plainBranch, new TaskAssignment.Role("auditors"), actor, "handover", T0.plusSeconds(60), tx)));

        // Then the updates took effect but neither stored tenant changed
        WorkflowInstance updatedTenantInstance =
                await(instances.findById(tenantInstanceId)).orElseThrow();
        assertThat(updatedTenantInstance.stateJson()).contains("\"step\"");
        assertThat(updatedTenantInstance.version()).isEqualTo(1L);
        assertThat(updatedTenantInstance.tenantKey()).isEqualTo("t1");
        assertThat(await(instances.findById(plainInstanceId)).orElseThrow().tenantKey())
                .isNull();
        assertThat(storedTenant("workflow_instances", "id", tenantInstanceId.value()))
                .isEqualTo("t1");
        assertThat(storedTenant("workflow_instances", "id", plainInstanceId.value()))
                .isNull();
        assertThat(await(tasks.findById(tenantSingle, pool)).orElseThrow().tenantKey())
                .isEqualTo("t1");
        assertThat(await(tasks.findById(tenantBranch, pool)).orElseThrow().tenantKey())
                .isEqualTo("t1");
        assertThat(await(tasks.findById(plainSingle, pool)).orElseThrow().tenantKey())
                .isNull();
        assertThat(await(tasks.findById(plainBranch, pool)).orElseThrow().tenantKey())
                .isNull();
        assertThat(storedTenant("workflow_tasks", "task_id", tenantSingle)).isEqualTo("t1");
        assertThat(storedTenant("workflow_tasks", "task_id", tenantBranch)).isEqualTo("t1");
        assertThat(storedTenant("workflow_tasks", "task_id", plainSingle)).isNull();
        assertThat(storedTenant("workflow_tasks", "task_id", plainBranch)).isNull();
    }

    @Test
    void tenantFilterSelectsExactRowsWithKeysetArchiveAndSubjectJoin() throws Exception {
        // Given instances (each with one task) for tenants t1, t2 and none, newest last by minutes
        WorkflowSubjectRef shared = new WorkflowSubjectRef("order", "o-1", null);
        WorkflowSubjectRef other = new WorkflowSubjectRef("invoice", "i-9", null);
        Seeded none0 = seed("none", T0, other);
        Seeded t1a = seed("t1", T0.plusSeconds(60), shared);
        Seeded t1b = seed("t1", T0.plusSeconds(120), shared);
        Seeded t2a = seed("t2", T0.plusSeconds(150), shared);
        Seeded t1c = seed("t1", T0.plusSeconds(180), shared);
        Seeded t1d = seed("t1", T0.plusSeconds(240), shared);
        Seeded none1 = seed("none", T0.plusSeconds(270), shared);
        Seeded t1e = seed("t1", T0.plusSeconds(300), shared);
        Seeded t2b = seed("t2", T0.plusSeconds(360), shared);

        // And two of the t1 instances are archived
        await(pool.query("UPDATE workflow_instances SET archived_at = now() WHERE id IN ('"
                        + t1b.instanceId().value() + "', '" + t1d.instanceId().value() + "')")
                .execute());

        // When instances are queried for tenant t1 with a page of two
        WorkflowInstanceQuery t1Query = WorkflowInstanceQuery.none().withTenantKey("t1");
        PagedResult<WorkflowInstance> t1Page1 = await(instances.findFiltered(t1Query, PageCursor.first(2)));
        PagedResult<WorkflowInstance> t1Page2 =
                await(instances.findFiltered(t1Query, PageCursor.fromToken(t1Page1.nextCursorToken())));

        // Then only live t1 rows return, newest first, across the page boundary
        assertThat(ids(t1Page1.items(), WorkflowInstance::id)).containsExactly(t1e.instanceId(), t1c.instanceId());
        assertThat(t1Page1.hasMore()).isTrue();
        assertThat(ids(t1Page2.items(), WorkflowInstance::id)).containsExactly(t1a.instanceId());
        assertThat(t1Page2.hasMore()).isFalse();
        assertThat(t1Page2.nextCursorToken()).isNull();
        assertThat(t1Page1.items()).extracting(WorkflowInstance::tenantKey).containsOnly("t1");

        // When archived rows are included for tenant t1 with a page of two
        WorkflowInstanceQuery t1ArchivedQuery = t1Query.withIncludeArchived(true);
        PagedResult<WorkflowInstance> archived1 = await(instances.findFiltered(t1ArchivedQuery, PageCursor.first(2)));
        PagedResult<WorkflowInstance> archived2 =
                await(instances.findFiltered(t1ArchivedQuery, PageCursor.fromToken(archived1.nextCursorToken())));
        PagedResult<WorkflowInstance> archived3 =
                await(instances.findFiltered(t1ArchivedQuery, PageCursor.fromToken(archived2.nextCursorToken())));

        // Then every t1 row returns in the unchanged keyset order across both page boundaries
        assertThat(ids(archived1.items(), WorkflowInstance::id)).containsExactly(t1e.instanceId(), t1d.instanceId());
        assertThat(ids(archived2.items(), WorkflowInstance::id)).containsExactly(t1c.instanceId(), t1b.instanceId());
        assertThat(ids(archived3.items(), WorkflowInstance::id)).containsExactly(t1a.instanceId());
        assertThat(archived3.hasMore()).isFalse();

        // When instances are queried with no tenant, and with another tenant or a near-miss value
        PagedResult<WorkflowInstance> unconstrained =
                await(instances.findFiltered(WorkflowInstanceQuery.none(), PageCursor.first(20)));
        PagedResult<WorkflowInstance> unconstrainedWithArchived = await(
                instances.findFiltered(WorkflowInstanceQuery.none().withIncludeArchived(true), PageCursor.first(20)));
        PagedResult<WorkflowInstance> t2Only =
                await(instances.findFiltered(WorkflowInstanceQuery.none().withTenantKey("t2"), PageCursor.first(20)));
        PagedResult<WorkflowInstance> nearMiss =
                await(instances.findFiltered(WorkflowInstanceQuery.none().withTenantKey("T1"), PageCursor.first(20)));
        PagedResult<WorkflowInstance> prefix =
                await(instances.findFiltered(WorkflowInstanceQuery.none().withTenantKey("t"), PageCursor.first(20)));

        // Then a null tenant constrains nothing, as before, and other values match exactly
        assertThat(ids(unconstrained.items(), WorkflowInstance::id))
                .containsExactly(
                        t2b.instanceId(),
                        t1e.instanceId(),
                        none1.instanceId(),
                        t1c.instanceId(),
                        t2a.instanceId(),
                        t1a.instanceId(),
                        none0.instanceId());
        assertThat(unconstrainedWithArchived.items()).hasSize(9);
        assertThat(ids(t2Only.items(), WorkflowInstance::id)).containsExactly(t2b.instanceId(), t2a.instanceId());
        assertThat(nearMiss.items()).isEmpty();
        assertThat(prefix.items()).isEmpty();

        // When tasks are filtered by tenant t1 with a page of two, then with archived instances included
        TaskFilter taskFilter = TaskFilter.empty().withTenantKey("t1");
        PagedResult<TaskRecord> taskPage1 = await(tasks.findByFilter(taskFilter, PageCursor.first(2), pool));
        PagedResult<TaskRecord> taskPage2 =
                await(tasks.findByFilter(taskFilter, PageCursor.fromToken(taskPage1.nextCursorToken()), pool));
        PagedResult<TaskRecord> taskArchived1 =
                await(tasks.findByFilter(taskFilter.withIncludeArchived(true), PageCursor.first(2), pool));
        PagedResult<TaskRecord> taskArchived2 = await(tasks.findByFilter(
                taskFilter.withIncludeArchived(true), PageCursor.fromToken(taskArchived1.nextCursorToken()), pool));
        PagedResult<TaskRecord> taskArchived3 = await(tasks.findByFilter(
                taskFilter.withIncludeArchived(true), PageCursor.fromToken(taskArchived2.nextCursorToken()), pool));

        // Then only t1 tasks of live instances return, then all t1 tasks, in the unchanged keyset order
        assertThat(ids(taskPage1.items(), TaskRecord::taskId)).containsExactly(t1e.taskId(), t1c.taskId());
        assertThat(taskPage1.hasMore()).isTrue();
        assertThat(ids(taskPage2.items(), TaskRecord::taskId)).containsExactly(t1a.taskId());
        assertThat(taskPage2.hasMore()).isFalse();
        assertThat(ids(taskArchived1.items(), TaskRecord::taskId)).containsExactly(t1e.taskId(), t1d.taskId());
        assertThat(ids(taskArchived2.items(), TaskRecord::taskId)).containsExactly(t1c.taskId(), t1b.taskId());
        assertThat(ids(taskArchived3.items(), TaskRecord::taskId)).containsExactly(t1a.taskId());
        assertThat(taskArchived1.items()).extracting(TaskRecord::tenantKey).containsOnly("t1");

        // When tasks are filtered by tenant t1 and a subject, which joins the instance table
        TaskFilter subjectFilter = taskFilter.withSubjectType("order").withSubjectId("o-1");
        PagedResult<TaskRecord> subjectTasks = await(tasks.findByFilter(subjectFilter, PageCursor.first(20), pool));
        PagedResult<TaskRecord> subjectTasksArchived =
                await(tasks.findByFilter(subjectFilter.withIncludeArchived(true), PageCursor.first(20), pool));
        PagedResult<TaskRecord> subjectPage1 = await(tasks.findByFilter(subjectFilter, PageCursor.first(2), pool));
        PagedResult<TaskRecord> subjectPage2 =
                await(tasks.findByFilter(subjectFilter, PageCursor.fromToken(subjectPage1.nextCursorToken()), pool));
        PagedResult<TaskRecord> subjectOnly = await(tasks.findByFilter(
                TaskFilter.empty().withSubjectType("order").withSubjectId("o-1"), PageCursor.first(20), pool));
        PagedResult<TaskRecord> subjectNoTenantMatch =
                await(tasks.findByFilter(subjectFilter.withTenantKey("t3"), PageCursor.first(20), pool));

        // Then the query is not ambiguous, keeps its order, and returns no other tenant's task
        assertThat(ids(subjectTasks.items(), TaskRecord::taskId))
                .containsExactly(t1e.taskId(), t1c.taskId(), t1a.taskId());
        assertThat(ids(subjectTasksArchived.items(), TaskRecord::taskId))
                .containsExactly(t1e.taskId(), t1d.taskId(), t1c.taskId(), t1b.taskId(), t1a.taskId());
        assertThat(ids(subjectPage1.items(), TaskRecord::taskId)).containsExactly(t1e.taskId(), t1c.taskId());
        assertThat(subjectPage1.hasMore()).isTrue();
        assertThat(ids(subjectPage2.items(), TaskRecord::taskId)).containsExactly(t1a.taskId());
        assertThat(subjectTasks.items()).extracting(TaskRecord::tenantKey).containsOnly("t1");
        assertThat(ids(subjectOnly.items(), TaskRecord::taskId))
                .as("without a tenant the same subject also reaches the other tenants")
                .containsExactly(t2b.taskId(), t1e.taskId(), none1.taskId(), t1c.taskId(), t2a.taskId(), t1a.taskId());
        assertThat(subjectNoTenantMatch.items()).isEmpty();

        // When tasks are filtered with no tenant at all
        PagedResult<TaskRecord> allTasks = await(tasks.findByFilter(TaskFilter.empty(), PageCursor.first(20), pool));

        // Then every task of a live instance returns, as before
        assertThat(ids(allTasks.items(), TaskRecord::taskId))
                .containsExactly(
                        t2b.taskId(),
                        t1e.taskId(),
                        none1.taskId(),
                        t1c.taskId(),
                        t2a.taskId(),
                        t1a.taskId(),
                        none0.taskId());
    }

    // --- Fixtures ---

    /** The ids of an instance and of the single task seeded under it. */
    private record Seeded(WorkflowInstanceId instanceId, UUID taskId) {}

    /**
     * Inserts a running instance with one open task, both stamped at {@code at}; the label {@code "none"} stands
     * for no tenant. The task record deliberately carries no tenant of its own.
     */
    private static Seeded seed(String tenantLabel, Instant at, WorkflowSubjectRef subject) throws Exception {
        String tenant = "none".equals(tenantLabel) ? null : tenantLabel;
        WorkflowInstanceId instanceId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        await(pool.withTransaction(tx -> instances
                .insert(instance(instanceId, tenant, at, subject), tx)
                .compose(v -> tasks.insertOpen(task(taskId, instanceId, null, at, false), tx))));
        return new Seeded(instanceId, taskId);
    }

    private static WorkflowInstance instance(
            WorkflowInstanceId id, String tenantKey, Instant at, WorkflowSubjectRef subject) {
        return new WorkflowInstance(
                id,
                "order-fulfillment",
                1L,
                "abc123hash",
                0L,
                WorkflowStatus.RUNNING,
                null,
                subject,
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
                tenantKey);
    }

    private static WorkflowInstance withTenant(WorkflowInstance source, String tenantKey) {
        return new WorkflowInstance(
                source.id(),
                source.definitionId(),
                source.definitionVersion(),
                source.planHash(),
                source.version(),
                source.status(),
                source.businessKey(),
                source.subjectRef(),
                source.currentStepId(),
                source.waitType(),
                source.waitKey(),
                source.waitAuxId(),
                source.stateJson(),
                source.errorType(),
                source.errorMessage(),
                source.createdAt(),
                source.updatedAt(),
                source.metadata(),
                tenantKey);
    }

    private static TaskRecord task(
            UUID taskId, WorkflowInstanceId workflowId, String ownTenantKey, Instant at, boolean branchOwned) {
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
                branchOwned ? UUID.randomUUID() : null,
                branchOwned ? "fork" : null,
                branchOwned ? "branch-a" : null,
                ownTenantKey);
    }

    private static <T, I> List<I> ids(List<T> items, Function<T, I> id) {
        List<I> result = new ArrayList<>();
        for (T item : items) {
            result.add(id.apply(item));
        }
        return result;
    }

    private static String storedTenant(String table, String idColumn, UUID id) throws Exception {
        List<Row> rows = new ArrayList<>();
        await(pool.preparedQuery("SELECT tenant_key FROM " + table + " WHERE " + idColumn + " = $1")
                .execute(Tuple.of(id))
                .onSuccess(rs -> rs.forEach(rows::add)));
        assertThat(rows).as("one %s row for %s", table, id).hasSize(1);
        return rows.get(0).getString("tenant_key");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
