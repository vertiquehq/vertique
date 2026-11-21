// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.security.PrincipalType;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.engine.WorkflowTestTables;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskFilter;
import dev.vertique.workflow.tasks.TaskPrincipals;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for the {@code assignedTo} filter of {@link PgTaskStore#findByFilter} against a real
 * PostgreSQL instance: the
 * caller's user id, roles and queues in one query, composition with the other filter fields, paging
 * across assignee kinds, the empty page of a principal that is not a user, and the plan of the
 * statement on open tasks.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PgTaskStoreAssignedToIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_tasks_assigned")
            .withMigration("classpath:db/migration/workflow");

    static Vertx vertx;
    static Pool pool;
    static PgTaskStore store;
    static PgWorkflowInstanceRepository instances;

    private static final TaskPrincipals ALICE =
            TaskPrincipals.ofUser("alice", Set.of("approver", "auditor"), Set.of("finance"));

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
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
        store = new PgTaskStore(pool, mapper);
        instances = new PgWorkflowInstanceRepository(pool, mapper);
    }

    @BeforeEach
    void truncate() {
        await(WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_timers, workflow_tasks, workflow_history, workflow_dedup,"
                                + " workflow_instances RESTART IDENTITY CASCADE")
                .execute());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            await(pool.close());
        }
        if (vertx != null) {
            await(vertx.close());
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("returns the tasks of the user id, of any role and of any queue, and nothing else")
    void returnsEveryPrincipalKind() {
        WorkflowInstanceId wf = instance(null);
        UUID own = task(wf, new TaskAssignment.User("alice"));
        UUID approver = task(wf, new TaskAssignment.Role("approver"));
        UUID auditor = task(wf, new TaskAssignment.Role("auditor"));
        UUID finance = task(wf, new TaskAssignment.Queue("finance"));
        task(wf, new TaskAssignment.User("bob"));
        task(wf, new TaskAssignment.Role("admin"));
        task(wf, new TaskAssignment.Queue("legal"));
        // Values of one kind never match another kind.
        task(wf, new TaskAssignment.Queue("approver"));
        task(wf, new TaskAssignment.Role("alice"));
        task(wf, new TaskAssignment.User("finance"));

        assertThat(ids(findAll(ALICE, TaskFilter.empty(), 50)))
                .containsExactlyInAnyOrder(own, approver, auditor, finance);
    }

    @Test
    @DisplayName("agrees with TaskAssignment.matches on every task")
    void agreesWithMatchingRule() {
        WorkflowInstanceId wf = instance(null);
        for (String value : List.of("alice", "approver", "auditor", "finance", "bob", "legal")) {
            task(wf, new TaskAssignment.User(value));
            task(wf, new TaskAssignment.Role(value));
            task(wf, new TaskAssignment.Queue(value));
        }

        List<TaskRecord> all = findAllByFilter(TaskFilter.empty());
        Set<UUID> expected = all.stream()
                .filter(t -> t.assignment().matches(ALICE))
                .map(TaskRecord::taskId)
                .collect(Collectors.toSet());

        assertThat(ids(findAll(ALICE, TaskFilter.empty(), 50)))
                .isEqualTo(expected)
                .hasSize(4);
    }

    @Test
    @DisplayName("combines with the status filter and the other filter fields")
    void combinesWithOtherFields() {
        WorkflowInstanceId wf = instance("invoice");
        WorkflowInstanceId other = instance("order");
        UUID openOwn = task(wf, new TaskAssignment.User("alice"));
        UUID completedRole = task(wf, new TaskAssignment.Role("approver"));
        complete(completedRole);
        UUID otherWorkflow = task(other, new TaskAssignment.Queue("finance"));

        assertThat(ids(findAll(ALICE, TaskFilter.empty().withStatus(TaskStatus.OPEN), 50)))
                .containsExactlyInAnyOrder(openOwn, otherWorkflow);
        assertThat(ids(findAll(ALICE, TaskFilter.empty().withStatus(TaskStatus.COMPLETED), 50)))
                .containsExactly(completedRole);
        assertThat(ids(findAll(ALICE, TaskFilter.empty().withWorkflowId(wf), 50)))
                .containsExactlyInAnyOrder(openOwn, completedRole);
        assertThat(ids(findAll(ALICE, TaskFilter.empty().withSubjectType("order"), 50)))
                .containsExactly(otherWorkflow);
    }

    @Test
    @DisplayName("pages across assignee kinds without losing or repeating a task")
    void pagesAcrossKinds() {
        WorkflowInstanceId wf = instance(null);
        Set<UUID> expected = Set.of(
                task(wf, new TaskAssignment.User("alice")),
                task(wf, new TaskAssignment.Queue("finance")),
                task(wf, new TaskAssignment.Role("approver")),
                task(wf, new TaskAssignment.User("alice")),
                task(wf, new TaskAssignment.Role("auditor")));
        task(wf, new TaskAssignment.User("bob"));

        List<TaskRecord> paged = findAll(ALICE, TaskFilter.empty().withStatus(TaskStatus.OPEN), 2);

        assertThat(paged).hasSize(5);
        assertThat(ids(paged)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a user with no roles and no queues sees only the tasks of its user id")
    void userWithoutRolesOrQueues() {
        WorkflowInstanceId wf = instance(null);
        UUID own = task(wf, new TaskAssignment.User("alice"));
        task(wf, new TaskAssignment.Role("approver"));

        assertThat(ids(findAll(TaskPrincipals.ofUser("alice", Set.of(), Set.of()), TaskFilter.empty(), 50)))
                .containsExactly(own);
    }

    @Test
    @DisplayName("a principal that is not a user gets an empty page, never every task")
    void nonUserGetsEmptyPage() {
        WorkflowInstanceId wf = instance(null);
        task(wf, new TaskAssignment.User("alice"));
        task(wf, new TaskAssignment.Role("approver"));
        task(wf, new TaskAssignment.Queue("finance"));

        for (PrincipalType type : List.of(PrincipalType.SERVICE, PrincipalType.SYSTEM, PrincipalType.ANONYMOUS)) {
            TaskPrincipals principals = new TaskPrincipals("alice", type, ALICE.roles(), ALICE.queues());
            assertThat(findAll(principals, TaskFilter.empty(), 50))
                    .as(type.name())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("excludes tasks of archived instances unless asked")
    void excludesArchived() {
        WorkflowInstanceId wf = instance(null);
        UUID own = task(wf, new TaskAssignment.User("alice"));
        await(pool.preparedQuery("UPDATE workflow_instances SET archived_at = NOW() WHERE id = $1")
                .execute(Tuple.of(wf.value())));

        assertThat(findAll(ALICE, TaskFilter.empty(), 50)).isEmpty();
        assertThat(ids(findAll(ALICE, TaskFilter.empty().withIncludeArchived(true), 50)))
                .containsExactly(own);
    }

    @Test
    @DisplayName("on open tasks the generic plan reads the partial assignee indexes")
    void openTasksUseThePartialIndexes() {
        WorkflowInstanceId wf = instance(null);
        await(pool.preparedQuery("INSERT INTO workflow_tasks"
                        + " (task_id, workflow_id, step_id, status, assignee_type, assignee_key,"
                        + " decisions_snapshot_json, updated_at)"
                        + " SELECT gen_random_uuid(), $1, 'step', 'OPEN',"
                        + " (ARRAY['USER','ROLE','QUEUE'])[1 + i % 3], 'k' || (i % 2000), '[]'::jsonb,"
                        + " NOW() - i * INTERVAL '1 second'"
                        + " FROM generate_series(1, 20000) AS i")
                .execute(Tuple.of(wf.value())));
        await(pool.query("ANALYZE workflow_tasks").execute());

        PgTaskStore.FilterQuery query =
                store.buildQuery(TaskFilter.empty().withStatus(TaskStatus.OPEN).withAssignedTo(ALICE));
        String plan = await(pool.withConnection(conn -> explainGeneric(conn, query)));

        assertThat(plan)
                .contains("idx_workflow_tasks_open_by_user")
                .contains("idx_workflow_tasks_open_by_role")
                .contains("idx_workflow_tasks_open_by_queue");
    }

    // --- Helpers ---

    private static Future<String> explainGeneric(SqlConnection conn, PgTaskStore.FilterQuery query) {
        String statement = query.sql() + " ORDER BY updated_at DESC, task_id ASC LIMIT 21";
        return conn.query("SET plan_cache_mode = force_generic_plan")
                .execute()
                .compose(v -> conn.preparedQuery("EXPLAIN " + statement).execute(Tuple.from(query.params())))
                .map(rows -> {
                    StringBuilder text = new StringBuilder();
                    for (Row row : rows) {
                        text.append(row.getString(0)).append('\n');
                    }
                    return text.toString();
                })
                .eventually(() -> conn.query("RESET plan_cache_mode").execute());
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Set<UUID> ids(List<TaskRecord> records) {
        return records.stream().map(TaskRecord::taskId).collect(Collectors.toSet());
    }

    private static List<TaskRecord> findAll(TaskPrincipals principals, TaskFilter filter, int pageSize) {
        return collect(cursor -> store.findByFilter(filter.withAssignedTo(principals), cursor, pool), pageSize);
    }

    private static List<TaskRecord> findAllByFilter(TaskFilter filter) {
        return collect(cursor -> store.findByFilter(filter, cursor, pool), 100);
    }

    private static List<TaskRecord> collect(Function<PageCursor, Future<PagedResult<TaskRecord>>> query, int pageSize) {
        List<TaskRecord> all = new ArrayList<>();
        PageCursor cursor = PageCursor.first(pageSize);
        for (int guard = 0; guard < 100; guard++) {
            PagedResult<TaskRecord> page = await(query.apply(cursor));
            all.addAll(page.items());
            if (!page.hasMore()) {
                return all;
            }
            cursor = PageCursor.fromToken(page.nextCursorToken());
        }
        throw new AssertionError("paging did not end");
    }

    private static WorkflowInstanceId instance(String subjectType) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstance inst = new WorkflowInstance(
                id,
                "task-def",
                1L,
                "hash-abc",
                0L,
                WorkflowStatus.WAITING,
                null,
                subjectType != null ? new WorkflowSubjectRef(subjectType, "s-1", null) : null,
                "task-step",
                WaitType.TASK,
                null,
                null,
                "{}",
                null,
                null,
                Instant.now(),
                Instant.now(),
                null);
        await(pool.withTransaction(tx -> instances.insert(inst, tx)));
        return id;
    }

    private static UUID task(WorkflowInstanceId workflowId, TaskAssignment assignment) {
        UUID taskId = UUID.randomUUID();
        TaskRecord record = new TaskRecord(
                taskId,
                workflowId,
                "review-step",
                assignment,
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", "java.lang.String", "ship")),
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
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
                null);
        await(pool.withTransaction(tx -> store.insertOpen(record, tx)));
        return taskId;
    }

    private static void complete(UUID taskId) {
        await(pool.withTransaction(tx ->
                store.markCompleted(taskId, "approve", "\"ok\"", Instant.now(), new WorkflowActor.User("alice"), tx)));
    }
}
