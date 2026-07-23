// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskFilter;
import dev.vertique.workflow.tasks.TaskReassignmentResult;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import dev.vertique.workflow.tasks.TaskTransition;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link PgTaskStore} against a real PostgreSQL instance.
 *
 * <p>Covers: {@code insertOpen}/{@code findById} round-trip; {@code markCompleted},
 * {@code markCancelled}, and {@code markExpired} CAS transitions; {@code reassign} semantics;
 * {@code findByFilter} with multiple predicates and keyset pagination; CASCADE delete; and
 * pair-validity CHECK constraint enforcement.
 *
 * <p>Each test truncates all relevant tables in {@link #truncateTables}.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgTaskStoreIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_tasks")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgTaskStore store;
    static dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository instanceRepo;

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
        PgDbExceptionMapper exMapper = new PgDbExceptionMapper();
        store = new PgTaskStore(pool, exMapper);
        instanceRepo = new dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository(pool, exMapper);
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_timers, workflow_tasks, workflow_history, workflow_dedup,"
                        + " workflow_instances RESTART IDENTITY CASCADE")
                .execute()
                .onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Helpers ---

    /**
     * Inserts a minimal {@code workflow_instances} row to satisfy the FK.
     *
     * @param workflowId the parent instance id
     * @param subjectType optional subject type, or {@code null}
     * @param subjectId optional subject id, or {@code null}
     * @return a {@link Future} that completes when the row is inserted
     */
    private Future<Void> seedInstance(WorkflowInstanceId workflowId, String subjectType, String subjectId) {
        WorkflowInstance inst = new WorkflowInstance(
                workflowId,
                "task-def",
                1L,
                "hash-abc",
                0L,
                WorkflowStatus.WAITING,
                null,
                subjectType != null ? new WorkflowSubjectRef(subjectType, subjectId, null) : null,
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
        return pool.withTransaction(tx -> instanceRepo.insert(inst, tx));
    }

    private Future<Void> seedInstance(WorkflowInstanceId workflowId) {
        return seedInstance(workflowId, null, null);
    }

    /**
     * Builds a minimal open {@link TaskRecord} for the given workflow instance.
     *
     * @param taskId the task UUID
     * @param workflowId the parent workflow instance id
     * @param assignment the task assignment
     * @return a ready-to-insert {@link TaskRecord}
     */
    private static TaskRecord openTask(UUID taskId, WorkflowInstanceId workflowId, TaskAssignment assignment) {
        List<TaskDecisionDescriptor> decisions = List.of(
                new TaskDecisionDescriptor("approve", "java.lang.String", "ship"),
                new TaskDecisionDescriptor("reject", "java.lang.String", "notify"));
        return new TaskRecord(
                taskId,
                workflowId,
                "review-step",
                assignment,
                TaskStatus.OPEN,
                decisions,
                null, // dueAt
                null, // dueDateTimerId
                null, // completedAt
                null, // cancelledAt
                null, // expiredAt
                Instant.now(), // updatedAt
                null, // decisionName
                null, // decisionPayloadJson
                null, // completedBy
                null, // cancelledBy
                null, // reassignedBy
                null, // cancellationReason
                null, // reassignmentReason
                null, // subjectVersionAtCreation
                null, // branchTokenId
                null, // forkStepId
                null); // branchId
    }

    // =========================================================================
    // insertOpen + findById round-trip
    // =========================================================================

    @Nested
    @DisplayName("insertOpen + findById round-trip")
    class InsertFindRoundTrip {

        @Test
        @DisplayName("insertOpen + findById round-trips all fields when fully populated")
        void roundTripAllFieldsPopulated(VertxTestContext ctx) {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();
            TaskAssignment assignment = new TaskAssignment.Role("compliance");
            TaskRecord record = openTask(taskId, workflowId, assignment);

            seedInstance(workflowId)
                    .compose(v -> pool.withTransaction(tx -> store.insertOpen(record, tx)))
                    .compose(v -> pool.withTransaction(tx -> store.findById(taskId, tx)))
                    .onSuccess(opt -> ctx.verify(() -> {
                        assertTrue(opt.isPresent(), "findById must return the inserted record");
                        TaskRecord found = opt.get();
                        assertEquals(taskId, found.taskId());
                        assertEquals(workflowId, found.workflowId());
                        assertEquals("review-step", found.stepId());
                        assertEquals(TaskStatus.OPEN, found.status());
                        assertInstanceOf(TaskAssignment.Role.class, found.assignment());
                        assertEquals("compliance", ((TaskAssignment.Role) found.assignment()).roleId());
                        assertEquals(2, found.decisions().size());
                        assertNull(found.completedAt(), "completedAt must be null for OPEN task");
                        assertNull(found.cancelledAt(), "cancelledAt must be null for OPEN task");
                        assertNull(found.expiredAt(), "expiredAt must be null for OPEN task");
                        assertNull(found.completedBy(), "completedBy must be null for OPEN task");
                        assertNull(found.cancelledBy(), "cancelledBy must be null for OPEN task");
                        assertNull(found.reassignedBy(), "reassignedBy must be null for fresh task");
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("insertOpen + findById round-trips when all optional audit columns are null")
        void roundTripAllNullOptionalAuditColumns(VertxTestContext ctx) {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();
            TaskRecord record = openTask(taskId, workflowId, new TaskAssignment.Queue("inbox"));

            seedInstance(workflowId)
                    .compose(v -> pool.withTransaction(tx -> store.insertOpen(record, tx)))
                    .compose(v -> pool.withTransaction(tx -> store.findById(taskId, tx)))
                    .onSuccess(opt -> ctx.verify(() -> {
                        assertTrue(opt.isPresent());
                        TaskRecord found = opt.get();
                        assertNull(found.dueAt(), "dueAt should be null");
                        assertNull(found.dueDateTimerId(), "dueDateTimerId should be null");
                        assertNull(found.decisionName(), "decisionName should be null");
                        assertNull(found.decisionPayloadJson(), "decisionPayloadJson should be null");
                        assertNull(found.cancellationReason(), "cancellationReason should be null");
                        assertNull(found.reassignmentReason(), "reassignmentReason should be null");
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }
    }

    // =========================================================================
    // markCompleted
    // =========================================================================

    @Nested
    @DisplayName("markCompleted CAS transitions")
    class MarkCompleted {

        @Test
        @DisplayName(
                "markCompleted returns APPLIED first call; second call returns LOST_TO_COMPLETED; persists audit columns")
        void markCompletedCasAndPersistence(VertxTestContext ctx) {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();
            WorkflowActor completedBy = new WorkflowActor.User("alice");
            Instant now = Instant.now();

            seedInstance(workflowId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskId, workflowId, new TaskAssignment.Role("r")), tx)))
                    .compose(v -> pool.withTransaction(
                            tx -> store.markCompleted(taskId, "approve", "\"ok\"", now, completedBy, tx)))
                    .compose(firstResult -> {
                        assertEquals(TaskTransition.APPLIED, firstResult, "first markCompleted must return APPLIED");
                        return pool.withTransaction(
                                tx -> store.markCompleted(taskId, "approve", "\"ok\"", now, completedBy, tx));
                    })
                    .compose(secondResult -> {
                        assertEquals(
                                TaskTransition.LOST_TO_COMPLETED,
                                secondResult,
                                "second markCompleted must return LOST_TO_COMPLETED");
                        return pool.withTransaction(tx -> store.findById(taskId, tx));
                    })
                    .onSuccess(opt -> ctx.verify(() -> {
                        assertTrue(opt.isPresent());
                        TaskRecord found = opt.get();
                        assertEquals(TaskStatus.COMPLETED, found.status());
                        assertEquals("approve", found.decisionName());
                        assertNotNull(found.decisionPayloadJson());
                        assertNotNull(found.completedAt());
                        assertInstanceOf(WorkflowActor.User.class, found.completedBy());
                        assertEquals("alice", ((WorkflowActor.User) found.completedBy()).userId());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("markCompleted with Service actor persists SERVICE kind")
        void markCompletedWithServiceActor(VertxTestContext ctx) {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();
            WorkflowActor actor = new WorkflowActor.Service("my-service");

            seedInstance(workflowId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskId, workflowId, new TaskAssignment.User("u")), tx)))
                    .compose(v -> pool.withTransaction(
                            tx -> store.markCompleted(taskId, "approve", null, Instant.now(), actor, tx)))
                    .compose(v -> pool.withTransaction(tx -> store.findById(taskId, tx)))
                    .onSuccess(opt -> ctx.verify(() -> {
                        assertTrue(opt.isPresent());
                        assertInstanceOf(WorkflowActor.Service.class, opt.get().completedBy());
                        assertEquals(
                                "my-service", ((WorkflowActor.Service) opt.get().completedBy()).serviceId());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("markCompleted with System actor persists SYSTEM kind")
        void markCompletedWithSystemActor(VertxTestContext ctx) {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();
            WorkflowActor actor = new WorkflowActor.System("auto-approve");

            seedInstance(workflowId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskId, workflowId, new TaskAssignment.User("u")), tx)))
                    .compose(v -> pool.withTransaction(
                            tx -> store.markCompleted(taskId, "approve", null, Instant.now(), actor, tx)))
                    .compose(v -> pool.withTransaction(tx -> store.findById(taskId, tx)))
                    .onSuccess(opt -> ctx.verify(() -> {
                        assertTrue(opt.isPresent());
                        assertInstanceOf(WorkflowActor.System.class, opt.get().completedBy());
                        assertEquals(
                                "auto-approve",
                                ((WorkflowActor.System) opt.get().completedBy()).reason());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }
    }

    // =========================================================================
    // markCancelled
    // =========================================================================

    @Nested
    @DisplayName("markCancelled CAS transitions")
    class MarkCancelled {

        @Test
        @DisplayName(
                "markCancelled returns APPLIED first call; second call returns LOST_TO_CANCELLED; persists audit columns")
        void markCancelledCasAndPersistence(VertxTestContext ctx) {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();
            WorkflowActor cancelledBy = new WorkflowActor.System("workflow-cancelled");

            seedInstance(workflowId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskId, workflowId, new TaskAssignment.Role("r")), tx)))
                    .compose(v -> pool.withTransaction(
                            tx -> store.markCancelled(taskId, "workflow-cancelled", Instant.now(), cancelledBy, tx)))
                    .compose(firstResult -> {
                        assertEquals(TaskTransition.APPLIED, firstResult, "first markCancelled must return APPLIED");
                        return pool.withTransaction(
                                tx -> store.markCancelled(taskId, "retry", Instant.now(), cancelledBy, tx));
                    })
                    .compose(secondResult -> {
                        assertEquals(
                                TaskTransition.LOST_TO_CANCELLED,
                                secondResult,
                                "second markCancelled must return LOST_TO_CANCELLED");
                        return pool.withTransaction(tx -> store.findById(taskId, tx));
                    })
                    .onSuccess(opt -> ctx.verify(() -> {
                        assertTrue(opt.isPresent());
                        TaskRecord found = opt.get();
                        assertEquals(TaskStatus.CANCELLED, found.status());
                        assertNotNull(found.cancelledAt());
                        assertEquals("workflow-cancelled", found.cancellationReason());
                        assertInstanceOf(WorkflowActor.System.class, found.cancelledBy());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }
    }

    // =========================================================================
    // markExpired
    // =========================================================================

    @Nested
    @DisplayName("markExpired CAS transitions")
    class MarkExpired {

        @Test
        @DisplayName(
                "markExpired returns APPLIED first call; second call returns LOST_TO_EXPIRED; does not touch cancelled_by/completed_by")
        void markExpiredCasAndPersistence(VertxTestContext ctx) {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();

            seedInstance(workflowId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskId, workflowId, new TaskAssignment.Role("r")), tx)))
                    .compose(v -> pool.withTransaction(tx -> store.markExpired(taskId, Instant.now(), tx)))
                    .compose(firstResult -> {
                        assertEquals(TaskTransition.APPLIED, firstResult, "first markExpired must return APPLIED");
                        return pool.withTransaction(tx -> store.markExpired(taskId, Instant.now(), tx));
                    })
                    .compose(secondResult -> {
                        assertEquals(
                                TaskTransition.LOST_TO_EXPIRED,
                                secondResult,
                                "second markExpired must return LOST_TO_EXPIRED");
                        return pool.withTransaction(tx -> store.findById(taskId, tx));
                    })
                    .onSuccess(opt -> ctx.verify(() -> {
                        assertTrue(opt.isPresent());
                        TaskRecord found = opt.get();
                        assertEquals(TaskStatus.EXPIRED, found.status());
                        assertNotNull(found.expiredAt());
                        // markExpired must NOT touch cancelled_by_* or completed_by_*
                        assertNull(found.cancelledBy(), "cancelledBy must remain null after markExpired");
                        assertNull(found.completedBy(), "completedBy must remain null after markExpired");
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }
    }

    // =========================================================================
    // reassign
    // =========================================================================

    @Nested
    @DisplayName("reassign transitions")
    class Reassign {

        @Test
        @DisplayName("reassign returns Applied with old/new/actor/reason populated; persists new assignment columns")
        void reassignAppliedAndPersisted(VertxTestContext ctx) {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();
            TaskAssignment original = new TaskAssignment.Role("compliance");
            TaskAssignment newAssignment = new TaskAssignment.User("alice");
            WorkflowActor by = new WorkflowActor.User("manager-bob");

            seedInstance(workflowId)
                    .compose(v ->
                            pool.withTransaction(tx -> store.insertOpen(openTask(taskId, workflowId, original), tx)))
                    .compose(v -> pool.withTransaction(
                            tx -> store.reassign(taskId, newAssignment, by, "needs-alice", Instant.now(), tx)))
                    .compose(result -> {
                        ctx.verify(() -> {
                            assertInstanceOf(
                                    TaskReassignmentResult.Applied.class, result, "reassign must return Applied");
                            TaskReassignmentResult.Applied applied = (TaskReassignmentResult.Applied) result;
                            assertEquals(taskId, applied.taskId());
                            assertEquals(workflowId, applied.workflowId());
                            assertInstanceOf(TaskAssignment.Role.class, applied.oldAssignment());
                            assertInstanceOf(TaskAssignment.User.class, applied.newAssignment());
                            assertInstanceOf(WorkflowActor.User.class, applied.reassignedBy());
                            assertEquals("needs-alice", applied.reason());
                        });
                        return pool.withTransaction(tx -> store.findById(taskId, tx));
                    })
                    .onSuccess(opt -> ctx.verify(() -> {
                        assertTrue(opt.isPresent());
                        TaskRecord found = opt.get();
                        assertInstanceOf(TaskAssignment.User.class, found.assignment());
                        assertEquals("alice", ((TaskAssignment.User) found.assignment()).userId());
                        assertInstanceOf(WorkflowActor.User.class, found.reassignedBy());
                        assertEquals("manager-bob", ((WorkflowActor.User) found.reassignedBy()).userId());
                        assertEquals("needs-alice", found.reassignmentReason());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("reassign returns LostToTerminal when task is already COMPLETED")
        void reassignLostToTerminalWhenCompleted(VertxTestContext ctx) {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();

            seedInstance(workflowId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskId, workflowId, new TaskAssignment.Role("r")), tx)))
                    .compose(v -> pool.withTransaction(tx -> store.markCompleted(
                            taskId, "approve", null, Instant.now(), new WorkflowActor.User("u"), tx)))
                    .compose(v -> pool.withTransaction(tx -> store.reassign(
                            taskId,
                            new TaskAssignment.Role("new-r"),
                            new WorkflowActor.User("mgr"),
                            null,
                            Instant.now(),
                            tx)))
                    .onSuccess(result -> ctx.verify(() -> {
                        assertInstanceOf(TaskReassignmentResult.LostToTerminal.class, result);
                        TaskReassignmentResult.LostToTerminal ltt = (TaskReassignmentResult.LostToTerminal) result;
                        assertEquals(TaskTransition.LOST_TO_COMPLETED, ltt.transition());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("blank reason is normalized to NULL on disk")
        void blankReasonNormalizedToNull(VertxTestContext ctx) {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();

            seedInstance(workflowId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskId, workflowId, new TaskAssignment.Role("r")), tx)))
                    .compose(v -> pool.withTransaction(tx -> store.reassign(
                            taskId,
                            new TaskAssignment.Role("q"),
                            new WorkflowActor.User("mgr"),
                            "   ",
                            Instant.now(),
                            tx)))
                    .compose(v -> pool.withTransaction(tx -> store.findById(taskId, tx)))
                    .onSuccess(opt -> ctx.verify(() -> {
                        assertTrue(opt.isPresent());
                        assertNull(opt.get().reassignmentReason(), "blank reason must be stored as null");
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }
    }

    // =========================================================================
    // findByFilter
    // =========================================================================

    @Nested
    @DisplayName("findByFilter")
    class FindByFilter {

        /**
         * Regression for the cycle-3 review finding: {@code findByFilter} must execute on the
         * caller-supplied {@code SqlClient tx} so that read-your-own-writes works inside a single
         * {@code withTransaction(...)} block. If the impl silently routes through the pool, the
         * just-inserted task is invisible (separate connection, no commit yet).
         */
        @Test
        @DisplayName("findByFilter sees rows inserted earlier in the same transaction (read-your-own-writes)")
        void readYourOwnWritesSameTransaction(VertxTestContext ctx) {
            WorkflowInstanceId wfId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();
            String marker = "rywo-" + UUID.randomUUID();

            seedInstance(wfId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskId, wfId, new TaskAssignment.User(marker)), tx)
                                    .compose(x -> store.findByFilter(
                                            TaskFilter.empty().withAssigneeUser(marker), PageCursor.first(10), tx))))
                    .onSuccess(result -> ctx.verify(() -> {
                        assertEquals(
                                1,
                                result.items().size(),
                                "findByFilter must see the just-inserted row inside the same tx");
                        assertEquals(taskId, result.items().get(0).taskId());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("findByFilter with assigneeUser filters correctly")
        void filterByAssigneeUser(VertxTestContext ctx) {
            WorkflowInstanceId wfId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskAlice = UUID.randomUUID();
            UUID taskBob = UUID.randomUUID();

            seedInstance(wfId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskAlice, wfId, new TaskAssignment.User("alice")), tx)
                                    .compose(x -> store.insertOpen(
                                            openTask(taskBob, wfId, new TaskAssignment.User("bob")), tx))))
                    .compose(v -> pool.withTransaction(tx ->
                            store.findByFilter(TaskFilter.empty().withAssigneeUser("alice"), PageCursor.first(10), tx)))
                    .onSuccess(result -> ctx.verify(() -> {
                        assertEquals(1, result.items().size(), "should return only alice's task");
                        assertEquals(taskAlice, result.items().get(0).taskId());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("findByFilter with assigneeRole filters correctly")
        void filterByAssigneeRole(VertxTestContext ctx) {
            WorkflowInstanceId wfId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskCompliance = UUID.randomUUID();
            UUID taskLegal = UUID.randomUUID();

            seedInstance(wfId)
                    .compose(v -> pool.withTransaction(tx -> store.insertOpen(
                                    openTask(taskCompliance, wfId, new TaskAssignment.Role("compliance")), tx)
                            .compose(x ->
                                    store.insertOpen(openTask(taskLegal, wfId, new TaskAssignment.Role("legal")), tx))))
                    .compose(v -> pool.withTransaction(tx -> store.findByFilter(
                            TaskFilter.empty().withAssigneeRole("compliance"), PageCursor.first(10), tx)))
                    .onSuccess(result -> ctx.verify(() -> {
                        assertEquals(1, result.items().size(), "should return only compliance role task");
                        assertEquals(taskCompliance, result.items().get(0).taskId());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("findByFilter with assigneeQueue filters correctly")
        void filterByAssigneeQueue(VertxTestContext ctx) {
            WorkflowInstanceId wfId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskQ1 = UUID.randomUUID();
            UUID taskQ2 = UUID.randomUUID();

            seedInstance(wfId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskQ1, wfId, new TaskAssignment.Queue("inbox")), tx)
                                    .compose(x -> store.insertOpen(
                                            openTask(taskQ2, wfId, new TaskAssignment.Queue("outbox")), tx))))
                    .compose(v -> pool.withTransaction(tx -> store.findByFilter(
                            TaskFilter.empty().withAssigneeQueue("inbox"), PageCursor.first(10), tx)))
                    .onSuccess(result -> ctx.verify(() -> {
                        assertEquals(1, result.items().size(), "should return only inbox queue task");
                        assertEquals(taskQ1, result.items().get(0).taskId());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("findByFilter with status=OPEN returns only open tasks")
        void filterByStatusOpen(VertxTestContext ctx) {
            WorkflowInstanceId wfId = new WorkflowInstanceId(UUID.randomUUID());
            UUID openTask = UUID.randomUUID();
            UUID completedTask = UUID.randomUUID();

            seedInstance(wfId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(openTask, wfId, new TaskAssignment.Role("r")), tx)
                                    .compose(x -> store.insertOpen(
                                            openTask(completedTask, wfId, new TaskAssignment.Role("r")), tx))))
                    .compose(v -> pool.withTransaction(tx -> store.markCompleted(
                            completedTask, "approve", null, Instant.now(), new WorkflowActor.User("u"), tx)))
                    .compose(v -> pool.withTransaction(tx -> store.findByFilter(
                            TaskFilter.empty().withStatus(TaskStatus.OPEN), PageCursor.first(10), tx)))
                    .onSuccess(result -> ctx.verify(() -> {
                        assertEquals(1, result.items().size(), "only the open task should be returned");
                        assertEquals(openTask, result.items().get(0).taskId());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("findByFilter with dueBefore filters by due_at")
        void filterByDueBefore(VertxTestContext ctx) {
            WorkflowInstanceId wfId = new WorkflowInstanceId(UUID.randomUUID());
            UUID dueSoonTask = UUID.randomUUID();
            UUID dueLaterTask = UUID.randomUUID();

            Instant pastDue = Instant.now().minusSeconds(60);
            Instant futureDue = Instant.now().plusSeconds(3600);

            // Build records with due dates directly to test filter
            List<TaskDecisionDescriptor> decisions =
                    List.of(new TaskDecisionDescriptor("approve", "java.lang.String", "ship"));
            TaskRecord recordSoon = new TaskRecord(
                    dueSoonTask,
                    wfId,
                    "step",
                    new TaskAssignment.Role("r"),
                    TaskStatus.OPEN,
                    decisions,
                    pastDue,
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
                    null, // branchTokenId
                    null, // forkStepId
                    null); // branchId
            TaskRecord recordLater = new TaskRecord(
                    dueLaterTask,
                    wfId,
                    "step",
                    new TaskAssignment.Role("r"),
                    TaskStatus.OPEN,
                    decisions,
                    futureDue,
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
                    null, // branchTokenId
                    null, // forkStepId
                    null); // branchId

            seedInstance(wfId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(recordSoon, tx).compose(x -> store.insertOpen(recordLater, tx))))
                    .compose(v -> pool.withTransaction(tx -> store.findByFilter(
                            TaskFilter.empty().withDueBefore(Instant.now()), PageCursor.first(10), tx)))
                    .onSuccess(result -> ctx.verify(() -> {
                        assertEquals(1, result.items().size(), "only the past-due task should be returned");
                        assertEquals(dueSoonTask, result.items().get(0).taskId());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("findByFilter with subjectType+subjectId uses JOIN to workflow_instances")
        void filterBySubjectTypeAndId(VertxTestContext ctx) {
            WorkflowInstanceId wfIdOrder = new WorkflowInstanceId(UUID.randomUUID());
            WorkflowInstanceId wfIdShipment = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskOrder = UUID.randomUUID();
            UUID taskShipment = UUID.randomUUID();

            seedInstance(wfIdOrder, "Order", "order-42")
                    .compose(v -> seedInstance(wfIdShipment, "Shipment", "ship-99"))
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskOrder, wfIdOrder, new TaskAssignment.Role("r")), tx)
                                    .compose(x -> store.insertOpen(
                                            openTask(taskShipment, wfIdShipment, new TaskAssignment.Role("r")), tx))))
                    .compose(v -> pool.withTransaction(tx -> store.findByFilter(
                            TaskFilter.empty().withSubjectType("Order").withSubjectId("order-42"),
                            PageCursor.first(10),
                            tx)))
                    .onSuccess(result -> ctx.verify(() -> {
                        assertEquals(1, result.items().size(), "only the Order/order-42 task should be returned");
                        assertEquals(taskOrder, result.items().get(0).taskId());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("keyset pagination: page through more than pageSize tasks with no overlap and full coverage")
        void keysetPaginationNoDuplicatesFullCoverage(VertxTestContext ctx) {
            WorkflowInstanceId wfId = new WorkflowInstanceId(UUID.randomUUID());
            int total = 7;
            int pageSize = 3;

            // Insert total tasks
            Future<Void> insertChain = seedInstance(wfId);
            for (int i = 0; i < total; i++) {
                UUID tid = UUID.randomUUID();
                insertChain = insertChain.compose(v -> pool.withTransaction(
                        tx -> store.insertOpen(openTask(tid, wfId, new TaskAssignment.Role("r")), tx)));
            }

            insertChain
                    .compose(v -> {
                        // Collect all pages
                        List<TaskRecord> allItems = new ArrayList<>();
                        return collectPages(TaskFilter.empty(), PageCursor.first(pageSize), allItems)
                                .map(v2 -> allItems);
                    })
                    .onSuccess(allItems -> ctx.verify(() -> {
                        assertEquals(total, allItems.size(), "should return all " + total + " tasks");
                        Set<UUID> ids = new HashSet<>();
                        for (TaskRecord r : allItems) {
                            assertTrue(ids.add(r.taskId()), "duplicate task_id found: " + r.taskId());
                        }
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        /**
         * Recursively pages through all results and collects them.
         *
         * @param filter the task filter to apply on each page
         * @param cursor the page cursor for this call
         * @param acc the accumulator list for collected items
         * @return a {@link Future} that completes when all pages are consumed
         */
        private Future<Void> collectPages(TaskFilter filter, PageCursor cursor, List<TaskRecord> acc) {
            return pool.withTransaction(tx -> store.findByFilter(filter, cursor, tx))
                    .compose(page -> {
                        acc.addAll(page.items());
                        if (page.nextCursorToken() != null) {
                            return collectPages(filter, PageCursor.fromToken(page.nextCursorToken()), acc);
                        }
                        return Future.<Void>succeededFuture();
                    });
        }
    }

    // =========================================================================
    // CASCADE on parent delete
    // =========================================================================

    @Test
    @DisplayName("CASCADE on parent delete: findById returns empty after workflow_instances row deleted")
    void cascadeOnParentDeleteRemovesTaskRow(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(
                        tx -> store.insertOpen(openTask(taskId, workflowId, new TaskAssignment.Role("r")), tx)))
                .compose(v -> pool.preparedQuery("DELETE FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(workflowId.value()))
                        .mapEmpty())
                .compose(v -> pool.withTransaction(tx -> store.findById(taskId, tx)))
                .onSuccess(opt -> ctx.verify(() -> {
                    assertFalse(opt.isPresent(), "task row must be gone after parent CASCADE delete");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // Note: cycle 3 stores actor identity as a single JSONB column ({"kind","value"}) per
    // workflow-design feedback — there are no kind/value column-pair CHECK constraints to
    // validate. Schema-level validation lives in the JSONB writer (see actorJson() in
    // PgTaskStore) which can never produce an inconsistent shape; round-trip coverage is in
    // markCompleted/markCancelled/reassign tests above.

    // =========================================================================
    //                             incrementRemindersFiredCount (cycle 4)
    // =========================================================================

    @Nested
    @DisplayName("incrementRemindersFiredCount")
    class IncrementRemindersFiredCount {

        @Test
        @DisplayName("returns post-increment counts 1, 2, 3 for an OPEN task")
        void incrementsOnOpenTask(VertxTestContext ctx) {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();

            seedInstance(workflowId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskId, workflowId, new TaskAssignment.User("alice")), tx)))
                    .compose(v -> pool.withTransaction(tx -> store.incrementRemindersFiredCount(taskId, tx)))
                    .compose(first -> {
                        ctx.verify(() -> assertEquals(Optional.of(1), first));
                        return pool.withTransaction(tx -> store.incrementRemindersFiredCount(taskId, tx));
                    })
                    .compose(second -> {
                        ctx.verify(() -> assertEquals(Optional.of(2), second));
                        return pool.withTransaction(tx -> store.incrementRemindersFiredCount(taskId, tx));
                    })
                    .onSuccess(third -> ctx.verify(() -> {
                        assertEquals(Optional.of(3), third);
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("returns Optional.empty when the task is COMPLETED (no row matches the OPEN guard)")
        void returnsEmptyOnCompleted(VertxTestContext ctx) {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID taskId = UUID.randomUUID();

            seedInstance(workflowId)
                    .compose(v -> pool.withTransaction(
                            tx -> store.insertOpen(openTask(taskId, workflowId, new TaskAssignment.User("alice")), tx)))
                    .compose(v -> pool.withTransaction(tx -> store.markCompleted(
                            taskId,
                            "approve",
                            "{}",
                            Instant.now(),
                            new dev.vertique.workflow.actor.WorkflowActor.User("alice"),
                            tx)))
                    .compose(transition -> {
                        ctx.verify(() -> assertEquals(TaskTransition.APPLIED, transition));
                        return pool.withTransaction(tx -> store.incrementRemindersFiredCount(taskId, tx));
                    })
                    .onSuccess(opt -> ctx.verify(() -> {
                        assertEquals(Optional.empty(), opt);
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("returns Optional.empty when the task does not exist")
        void returnsEmptyOnMissingTask(VertxTestContext ctx) {
            UUID missingTaskId = UUID.randomUUID();
            pool.withTransaction(tx -> store.incrementRemindersFiredCount(missingTaskId, tx))
                    .onSuccess(opt -> ctx.verify(() -> {
                        assertEquals(Optional.empty(), opt);
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }
    }
}
