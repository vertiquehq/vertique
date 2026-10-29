// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowStaleAssignmentException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskAssignment;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end integration tests for the human-task engine paths in {@link WorkflowEngineHandle}.
 *
 * <p>Covers: happy-path task completion, actor permit variants, idempotent completion, idempotency
 * conflicts, reassignment semantics, idempotent reassignment, reassignment idempotency conflicts,
 * reassignment-then-completion actor distinction, due-date timer firing, workflow cancellation of a
 * WAITING-on-TASK instance, decision-name dispatch, payload-nullability enforcement, and
 * AssignmentSpec state-resolver fires-once.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineHumanTaskIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_human_task_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgWorkflowInstanceRepository instanceRepo;
    static PgWorkflowHistoryRepository historyRepo;
    static PgTimerStore timerStore;
    /** The due-date timer that the start of the last started instance recorded, or {@code null} when it recorded none. */
    static final AtomicReference<UUID> lastTimerId = new AtomicReference<>();

    // --- Domain types ---

    /**
     * Minimal workflow payload for human-task tests.
     *
     * @param id        unique test identifier
     * @param signerUserId optional user id used by resolver-based assignment tests
     */
    record TaskState(String id, String signerUserId) implements IdempotencyKeyed {

        /**
         * Factory for tests that do not need a signer user id.
         *
         * @param id the test identifier
         * @return a new {@code TaskState} with {@code signerUserId=null}
         */
        static TaskState of(String id) {
            return new TaskState(id, null);
        }

        /**
         * Factory for tests that supply a signer user id to drive the resolver.
         *
         * @param id          the test identifier
         * @param signerUserId the user to assign the task to
         * @return a new {@code TaskState} with the given signer
         */
        static TaskState of(String id, String signerUserId) {
            return new TaskState(id, signerUserId);
        }

        @Override
        public String idempotencyKey() {
            return "human-task-it-" + id;
        }
    }

    // --- Workflow contract markers ---

    interface ReviewContract {}

    interface ReviewDueDateContract {}

    interface ResolverContract {}

    interface VoidDecisionContract {}

    interface StringDecisionContract {}

    // --- Workflow definitions ---

    /**
     * Minimal review workflow: task "review" assigned to role "compliance" with two decisions
     * ("approve" → "ship", "reject" → "notify"). Both "ship" and "notify" are terminal.
     */
    static final WorkflowDefinition<TaskState, ReviewContract> REVIEW_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<ReviewContract> contract() {
            return ReviewContract.class;
        }

        @Override
        public Class<TaskState> stateType() {
            return TaskState.class;
        }

        @Override
        public String definitionId() {
            return "human-task-review-test";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<TaskState> wf) {
            wf.init(TaskState.class, s -> s)
                    .initialStep("review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> new TaskState(s.id(), p))
                    .toStep("ship")
                    .decision("reject", String.class)
                    .onDecision((s, p) -> new TaskState(s.id(), p))
                    .toStep("notify")
                    .build()
                    .complete("ship")
                    .complete("notify");
        }
    };

    /**
     * Review workflow with a 1-hour due-date that expires to "escalate".
     */
    static final WorkflowDefinition<TaskState, ReviewDueDateContract> REVIEW_DUE_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<ReviewDueDateContract> contract() {
            return ReviewDueDateContract.class;
        }

        @Override
        public Class<TaskState> stateType() {
            return TaskState.class;
        }

        @Override
        public String definitionId() {
            return "human-task-due-date-test";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<TaskState> wf) {
            wf.init(TaskState.class, s -> s)
                    .initialStep("review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", String.class)
                    .onDecision((s, p) -> new TaskState(s.id(), p))
                    .toStep("ship")
                    .dueIn(Duration.ofHours(1))
                    .onDue(s -> new TaskState(s.id(), "expired"))
                    .toStepOnDue("escalate")
                    .build()
                    .complete("ship")
                    .complete("escalate");
        }
    };

    /**
     * Workflow where the task is assigned to a user resolved from state via a callback.
     */
    static final AtomicInteger RESOLVER_CALL_COUNT = new AtomicInteger(0);

    static final WorkflowDefinition<TaskState, ResolverContract> RESOLVER_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<ResolverContract> contract() {
            return ResolverContract.class;
        }

        @Override
        public Class<TaskState> stateType() {
            return TaskState.class;
        }

        @Override
        public String definitionId() {
            return "human-task-resolver-test";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<TaskState> wf) {
            wf.init(TaskState.class, s -> s)
                    .initialStep("review")
                    .task("review")
                    .assignToUser(s -> {
                        RESOLVER_CALL_COUNT.incrementAndGet();
                        return s.signerUserId();
                    })
                    .decision("approve", String.class)
                    .onDecision((s, p) -> new TaskState(s.id(), p))
                    .toStep("ship")
                    .build()
                    .complete("ship");
        }
    };

    /**
     * Workflow with a Void-payload decision (no payload required).
     */
    static final WorkflowDefinition<TaskState, VoidDecisionContract> VOID_DECISION_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<VoidDecisionContract> contract() {
            return VoidDecisionContract.class;
        }

        @Override
        public Class<TaskState> stateType() {
            return TaskState.class;
        }

        @Override
        public String definitionId() {
            return "human-task-void-decision-test";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<TaskState> wf) {
            wf.init(TaskState.class, s -> s)
                    .initialStep("review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", Void.class)
                    .onDecision((s, p) -> s)
                    .toStep("ship")
                    .build()
                    .complete("ship");
        }
    };

    /**
     * Workflow with a String-payload decision (payload required).
     */
    static final WorkflowDefinition<TaskState, StringDecisionContract> STRING_DECISION_DEF =
            new WorkflowDefinition<>() {
                @Override
                public Class<StringDecisionContract> contract() {
                    return StringDecisionContract.class;
                }

                @Override
                public Class<TaskState> stateType() {
                    return TaskState.class;
                }

                @Override
                public String definitionId() {
                    return "human-task-string-decision-test";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<TaskState> wf) {
                    wf.init(TaskState.class, s -> s)
                            .initialStep("review")
                            .task("review")
                            .assignToRole("compliance")
                            .decision("approve", String.class)
                            .onDecision((s, p) -> new TaskState(s.id(), p))
                            .toStep("ship")
                            .build()
                            .complete("ship");
                }
            };

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
        instanceRepo = new PgWorkflowInstanceRepository(pool, exMapper);
        historyRepo = new PgWorkflowHistoryRepository(pool, exMapper);
        PgWorkflowDedupRepository dedupRepo = new PgWorkflowDedupRepository(pool, exMapper);
        timerStore = new PgTimerStore(exMapper);

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(REVIEW_DEF);
        registry.register(REVIEW_DUE_DEF);
        registry.register(RESOLVER_DEF);
        registry.register(VOID_DECISION_DEF);
        registry.register(STRING_DECISION_DEF);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                new PgTaskStore(pool, exMapper),
                Clock.systemUTC());

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        lastTimerId.set(null);
        RESOLVER_CALL_COUNT.set(0);
        WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_timers, workflow_tasks, workflow_history, workflow_dedup,"
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
     * Starts a workflow with the given definition id and initial state payload.
     *
     * @param definitionId the workflow definition to start
     * @param state        the initial state (also the start payload)
     * @return a {@link Future} of the new workflow instance id
     */
    private Future<WorkflowInstanceId> startWorkflow(String definitionId, TaskState state) {
        StartCommand cmd = new StartCommand(
                definitionId,
                contractNameOf(definitionId),
                state,
                state.idempotencyKey(),
                null,
                null,
                null,
                null,
                null);
        return engine.start(cmd)
                .compose(id ->
                        engine.executionProbe().awaitIdle(id, IDLE_TIMEOUT).map(idle -> id))
                .compose(id -> TimerCommandRows.allTimerIds(pool, id).map(timerIds -> {
                    // the due-date timer is the only timer these workflows record
                    lastTimerId.set(timerIds.isEmpty() ? null : timerIds.getFirst());
                    return id;
                }));
    }

    /**
     * Returns the contract name of a fixture definition.
     *
     * @param definitionId the id of one of this test's fixture definitions
     * @return the name of the definition's contract
     */
    private static String contractNameOf(String definitionId) {
        return switch (definitionId) {
            case "human-task-review-test" -> ReviewContract.class.getName();
            case "human-task-due-date-test" -> ReviewDueDateContract.class.getName();
            case "human-task-resolver-test" -> ResolverContract.class.getName();
            case "human-task-void-decision-test" -> VoidDecisionContract.class.getName();
            case "human-task-string-decision-test" -> StringDecisionContract.class.getName();
            default -> throw new IllegalArgumentException("unknown fixture definition: " + definitionId);
        };
    }

    /**
     * Reads the task UUID from the {@code workflow_instances.wait_key} column (which holds the
     * task UUID as a string when the instance is WAITING on a TASK).
     *
     * @param workflowId the workflow instance id to inspect
     * @return a {@link Future} containing the task UUID
     */
    private Future<UUID> readTaskId(WorkflowInstanceId workflowId) {
        return pool.withTransaction(tx -> instanceRepo.findById(workflowId, tx)).map(optInst -> {
            assertTrue(optInst.isPresent(), "instance must exist");
            var inst = optInst.get();
            assertEquals(WaitType.TASK, inst.waitType(), "instance must be WAITING on TASK");
            return UUID.fromString(inst.waitKey());
        });
    }

    /**
     * Reads the settled outcome of the workflow's only recorded completion: the command's state and sanitized reason
     * and the status of the task it reserved.
     *
     * @param workflowId the workflow instance id to inspect
     * @return a {@link Future} containing the row with {@code state}, {@code last_error} and {@code task_status}
     */
    private Future<Row> completionOutcome(WorkflowInstanceId workflowId) {
        return pool.preparedQuery("SELECT c.state, c.last_error, t.status AS task_status FROM workflow_commands c"
                        + " JOIN workflow_tasks t ON t.task_id = c.task_id"
                        + " WHERE c.workflow_id = $1 AND c.kind = 'COMPLETION'")
                .execute(Tuple.of(workflowId.value()))
                .map(rows -> rows.iterator().next());
    }

    /**
     * Reads the JSONB column value for the task row identified by the given task UUID.
     *
     * @param taskId the task UUID
     * @param column the JSONB column name (e.g., {@code "completed_by"}, {@code "cancelled_by"})
     * @return a {@link Future} containing the JSONB string, or {@code null} if the column is null
     */
    private Future<String> readTaskJsonbColumn(UUID taskId, String column) {
        String sql = "SELECT " + column + " FROM workflow_tasks WHERE task_id = $1";
        return pool.preparedQuery(sql).execute(Tuple.of(taskId)).map(rs -> {
            var it = rs.iterator();
            assertTrue(it.hasNext(), "task row must exist for task_id=" + taskId);
            Object val = it.next().getValue(column);
            return val == null ? null : val.toString();
        });
    }

    /**
     * Reads the {@code assignee_type} and {@code assignee_key} columns from the task row.
     *
     * @param taskId the task UUID
     * @return a {@link Future} containing a two-element array {@code [assigneeType, assigneeKey]}
     */
    private Future<String[]> readTaskAssignment(UUID taskId) {
        return pool.preparedQuery("SELECT assignee_type, assignee_key FROM workflow_tasks WHERE task_id = $1")
                .execute(Tuple.of(taskId))
                .map(rs -> {
                    var it = rs.iterator();
                    assertTrue(it.hasNext(), "task row must exist");
                    var row = it.next();
                    return new String[] {row.getString("assignee_type"), row.getString("assignee_key")};
                });
    }

    /**
     * Counts the number of history entries of the given type for the workflow instance.
     *
     * @param workflowId the workflow instance id
     * @param entryType  the entry type to count
     * @return a {@link Future} containing the count
     */
    private Future<Long> countHistoryEntries(WorkflowInstanceId workflowId, WorkflowEntryType entryType) {
        return historyRepo.listByInstance(workflowId, pool).map(entries -> entries.stream()
                .filter(e -> e.entryType() == entryType)
                .count());
    }

    /**
     * Finds the first history entry of the given type for the workflow instance.
     *
     * @param workflowId the workflow instance id
     * @param entryType  the entry type to find
     * @return a {@link Future} containing the payload JSON of the first matching entry
     */
    private Future<String> findHistoryPayload(WorkflowInstanceId workflowId, WorkflowEntryType entryType) {
        return historyRepo.listByInstance(workflowId, pool).map(entries -> entries.stream()
                .filter(e -> e.entryType() == entryType)
                .findFirst()
                .map(e -> e.payloadJson())
                .orElse(null));
    }

    /**
     * Parses an actor from a raw JSON string in the canonical {@code {"kind":"...","value":"..."}}
     * shape used by the {@code workflow_tasks} JSONB columns.
     *
     * @param json the actor JSON string; must not be null
     * @return a two-element array {@code [kind, value]}
     */
    private static String[] parseActorKindValue(String json) {
        JsonObject obj = new JsonObject(json);
        return new String[] {obj.getString("kind"), obj.getString("value")};
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    /**
     * Start a review workflow, assert WAITING+TASK, complete with "approve", assert transition to
     * COMPLETED, and verify the task row's assignee columns and completed_by JSONB.
     */
    @Test
    @DisplayName("happy path: task completion advances workflow and stamps completed_by")
    void happyPathCompletion(VertxTestContext ctx) {
        TaskState state = TaskState.of("happy-" + UUID.randomUUID());
        String idemKey = "idem-happy-" + UUID.randomUUID();

        startWorkflow("human-task-review-test", state)
                .compose(workflowId -> {
                    // Verify WAITING state
                    return pool.withTransaction(tx -> instanceRepo.findById(workflowId, tx))
                            .compose(optInst -> {
                                ctx.verify(() -> {
                                    assertTrue(optInst.isPresent());
                                    assertEquals(
                                            WorkflowStatus.WAITING,
                                            optInst.get().status());
                                    assertEquals(WaitType.TASK, optInst.get().waitType());
                                });
                                UUID taskId = UUID.fromString(optInst.get().waitKey());

                                // Verify assignee columns
                                return readTaskAssignment(taskId).compose(assignment -> {
                                    ctx.verify(() -> {
                                        assertEquals("ROLE", assignment[0], "assignee_type must be ROLE");
                                        assertEquals("compliance", assignment[1], "assignee_key must be compliance");
                                    });

                                    // Complete the task
                                    TaskCompletionCommand cmd = new TaskCompletionCommand(
                                            taskId,
                                            "approve",
                                            "approved-payload",
                                            idemKey,
                                            new WorkflowActor.User("alice"),
                                            null);
                                    return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                                            .compose(applied -> engine.executionProbe()
                                                    .awaitIdle(workflowId, IDLE_TIMEOUT)
                                                    .map(idle -> applied))
                                            .compose(result -> {
                                                ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, result));

                                                // Verify workflow is now COMPLETED
                                                return pool.withTransaction(
                                                        tx -> instanceRepo.findById(workflowId, tx));
                                            })
                                            .compose(optUpdated -> {
                                                ctx.verify(() -> {
                                                    assertTrue(optUpdated.isPresent());
                                                    assertEquals(
                                                            WorkflowStatus.COMPLETED,
                                                            optUpdated.get().status());
                                                });

                                                // Verify completed_by JSONB column
                                                return readTaskJsonbColumn(taskId, "completed_by");
                                            })
                                            .compose(completedByJson -> {
                                                ctx.verify(() -> {
                                                    assertNotNull(completedByJson, "completed_by must not be null");
                                                    String[] kv = parseActorKindValue(completedByJson);
                                                    assertEquals("USER", kv[0]);
                                                    assertEquals("alice", kv[1]);
                                                });

                                                // Verify TASK_CREATED and TASK_COMPLETED history entries
                                                return historyRepo.listByInstance(workflowId, pool);
                                            })
                                            .map(entries -> {
                                                ctx.verify(() -> {
                                                    assertTrue(
                                                            entries.stream()
                                                                    .anyMatch(e -> e.entryType()
                                                                            == WorkflowEntryType.TASK_CREATED),
                                                            "must have TASK_CREATED history");
                                                    assertTrue(
                                                            entries.stream()
                                                                    .anyMatch(e -> e.entryType()
                                                                            == WorkflowEntryType.TASK_COMPLETED),
                                                            "must have TASK_COMPLETED history");
                                                    // Verify TASK_COMPLETED payload actor
                                                    String completedPayloadJson = entries.stream()
                                                            .filter(e ->
                                                                    e.entryType() == WorkflowEntryType.TASK_COMPLETED)
                                                            .findFirst()
                                                            .map(e -> e.payloadJson())
                                                            .orElse(null);
                                                    assertNotNull(completedPayloadJson);
                                                    JsonObject payload = new JsonObject(completedPayloadJson);
                                                    JsonObject completedBy = payload.getJsonObject("completedBy");
                                                    assertNotNull(completedBy, "completedBy must be in payload");
                                                    assertEquals("alice", completedBy.getString("userId"));
                                                });
                                                return null;
                                            });
                                });
                            });
                })
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    // =========================================================================
    // Actor permits
    // =========================================================================

    /**
     * Completes tasks in three separate workflow instances with User, Service, and System actors
     * respectively and asserts each round-trips correctly.
     */
    @Test
    @DisplayName("each actor permit (User, Service, System) round-trips correctly through completed_by")
    void eachActorPermitRoundTrips(VertxTestContext ctx) {
        TaskState stateA = TaskState.of("actor-user-" + UUID.randomUUID());
        TaskState stateB = TaskState.of("actor-svc-" + UUID.randomUUID());
        TaskState stateC = TaskState.of("actor-sys-" + UUID.randomUUID());

        startWorkflow("human-task-review-test", stateA)
                .compose(wfA -> readTaskId(wfA).compose(taskIdA -> pool.withTransaction(tx -> engine.taskCompleted(
                                new TaskCompletionCommand(
                                        taskIdA,
                                        "approve",
                                        "p",
                                        "idem-actor-user-" + UUID.randomUUID(),
                                        new WorkflowActor.User("alice"),
                                        null),
                                tx))
                        .compose(applied -> engine.executionProbe()
                                .awaitIdle(wfA, IDLE_TIMEOUT)
                                .map(idle -> applied))
                        .compose(r -> readTaskJsonbColumn(taskIdA, "completed_by"))
                        .map(json -> {
                            ctx.verify(() -> {
                                String[] kv = parseActorKindValue(json);
                                assertEquals("USER", kv[0]);
                                assertEquals("alice", kv[1]);
                            });
                            return null;
                        })))
                .compose(v -> startWorkflow("human-task-review-test", stateB))
                .compose(wfB -> readTaskId(wfB).compose(taskIdB -> pool.withTransaction(tx -> engine.taskCompleted(
                                new TaskCompletionCommand(
                                        taskIdB,
                                        "approve",
                                        "p",
                                        "idem-actor-svc-" + UUID.randomUUID(),
                                        new WorkflowActor.Service("scheduler-1"),
                                        null),
                                tx))
                        .compose(applied -> engine.executionProbe()
                                .awaitIdle(wfB, IDLE_TIMEOUT)
                                .map(idle -> applied))
                        .compose(r -> readTaskJsonbColumn(taskIdB, "completed_by"))
                        .map(json -> {
                            ctx.verify(() -> {
                                String[] kv = parseActorKindValue(json);
                                assertEquals("SERVICE", kv[0]);
                                assertEquals("scheduler-1", kv[1]);
                            });
                            return null;
                        })))
                .compose(v -> startWorkflow("human-task-review-test", stateC))
                .compose(wfC -> readTaskId(wfC).compose(taskIdC -> pool.withTransaction(tx -> engine.taskCompleted(
                                new TaskCompletionCommand(
                                        taskIdC,
                                        "approve",
                                        "p",
                                        "idem-actor-sys-" + UUID.randomUUID(),
                                        new WorkflowActor.System("auto-complete"),
                                        null),
                                tx))
                        .compose(applied -> engine.executionProbe()
                                .awaitIdle(wfC, IDLE_TIMEOUT)
                                .map(idle -> applied))
                        .compose(r -> readTaskJsonbColumn(taskIdC, "completed_by"))
                        .map(json -> {
                            ctx.verify(() -> {
                                String[] kv = parseActorKindValue(json);
                                assertEquals("SYSTEM", kv[0]);
                                assertEquals("auto-complete", kv[1]);
                            });
                            return null;
                        })))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    // =========================================================================
    // Idempotency — completion
    // =========================================================================

    /**
     * Calling {@code taskCompleted} twice with the same command succeeds both times;
     * the second call returns the earlier result, {@link TaskMutationResult#APPLIED} (idempotent retry —
     * the dedup row already exists with a matching fingerprint, so the original completion
     * was already recorded) and only one {@code TASK_COMPLETED} history entry exists.
     */
    @Test
    @DisplayName("idempotent completion: second call with same key+fingerprint returns the earlier result")
    void idempotentCompletion(VertxTestContext ctx) {
        TaskState state = TaskState.of("idem-complete-" + UUID.randomUUID());
        String idemKey = "idem-comp-" + UUID.randomUUID();

        startWorkflow("human-task-review-test", state)
                .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                    TaskCompletionCommand cmd = new TaskCompletionCommand(
                            taskId, "approve", "approved-payload", idemKey, new WorkflowActor.User("alice"), null);

                    return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                            .compose(applied -> engine.executionProbe()
                                    .awaitIdle(workflowId, IDLE_TIMEOUT)
                                    .map(idle -> applied))
                            .compose(firstResult -> {
                                ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, firstResult));
                                return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                                        .compose(applied -> engine.executionProbe()
                                                .awaitIdle(workflowId, IDLE_TIMEOUT)
                                                .map(idle -> applied));
                            })
                            .compose(secondResult -> {
                                ctx.verify(() -> assertEquals(
                                        TaskMutationResult.APPLIED,
                                        secondResult,
                                        "second call with same key+fingerprint must return the earlier result"));
                                return countHistoryEntries(workflowId, WorkflowEntryType.TASK_COMPLETED);
                            })
                            .map(count -> {
                                ctx.verify(() ->
                                        assertEquals(1L, count, "only one TASK_COMPLETED history entry must exist"));
                                return null;
                            });
                }))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    /**
     * Idempotency conflicts: second call with same idempotency key but different mutable input
     * (different decision, payload, or actor) fails with {@link WorkflowIdempotencyConflictException}.
     */
    @Nested
    @DisplayName("Idempotency conflicts — completion")
    class CompletionIdempotencyConflict {

        @Test
        @DisplayName("different decision name with same idempotency key fails with conflict")
        void differentDecisionNameConflicts(VertxTestContext ctx) {
            TaskState state = TaskState.of("conf-dec-" + UUID.randomUUID());
            String idemKey = "idem-conf-dec-" + UUID.randomUUID();

            startWorkflow("human-task-review-test", state)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                        TaskCompletionCommand cmd1 = new TaskCompletionCommand(
                                taskId, "approve", "p", idemKey, new WorkflowActor.User("alice"), null);
                        TaskCompletionCommand cmd2 = new TaskCompletionCommand(
                                taskId, "reject", "p", idemKey, new WorkflowActor.User("alice"), null);

                        return pool.withTransaction(tx -> engine.taskCompleted(cmd1, tx))
                                .compose(applied -> engine.executionProbe()
                                        .awaitIdle(workflowId, IDLE_TIMEOUT)
                                        .map(idle -> applied))
                                .compose(r -> pool.withTransaction(tx -> engine.taskCompleted(cmd2, tx)))
                                .map(r -> {
                                    ctx.verify(() -> ctx.failNow(
                                            new AssertionError("Second call must have failed with conflict")));
                                    return null;
                                });
                    }))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.verify(() -> assertInstanceOf(
                                    WorkflowIdempotencyConflictException.class,
                                    ar.cause(),
                                    "must fail with WorkflowIdempotencyConflictException"));
                            ctx.completeNow();
                        }
                        // If succeeded, the verify() above will have already called failNow()
                    });
        }

        @Test
        @DisplayName("different payload with same idempotency key fails with conflict")
        void differentPayloadConflicts(VertxTestContext ctx) {
            TaskState state = TaskState.of("conf-pay-" + UUID.randomUUID());
            String idemKey = "idem-conf-pay-" + UUID.randomUUID();

            startWorkflow("human-task-review-test", state)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                        TaskCompletionCommand cmd1 = new TaskCompletionCommand(
                                taskId, "approve", "payload-one", idemKey, new WorkflowActor.User("alice"), null);
                        TaskCompletionCommand cmd2 = new TaskCompletionCommand(
                                taskId, "approve", "payload-two", idemKey, new WorkflowActor.User("alice"), null);

                        return pool.withTransaction(tx -> engine.taskCompleted(cmd1, tx))
                                .compose(applied -> engine.executionProbe()
                                        .awaitIdle(workflowId, IDLE_TIMEOUT)
                                        .map(idle -> applied))
                                .compose(r -> pool.withTransaction(tx -> engine.taskCompleted(cmd2, tx)))
                                .map(r -> {
                                    ctx.verify(() -> ctx.failNow(
                                            new AssertionError("Second call must have failed with conflict")));
                                    return null;
                                });
                    }))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.verify(() -> assertInstanceOf(WorkflowIdempotencyConflictException.class, ar.cause()));
                            ctx.completeNow();
                        }
                    });
        }

        @Test
        @DisplayName("different actor with same idempotency key fails with conflict")
        void differentActorConflicts(VertxTestContext ctx) {
            TaskState state = TaskState.of("conf-actor-" + UUID.randomUUID());
            String idemKey = "idem-conf-actor-" + UUID.randomUUID();

            startWorkflow("human-task-review-test", state)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                        TaskCompletionCommand cmd1 = new TaskCompletionCommand(
                                taskId, "approve", "p", idemKey, new WorkflowActor.User("alice"), null);
                        TaskCompletionCommand cmd2 = new TaskCompletionCommand(
                                taskId, "approve", "p", idemKey, new WorkflowActor.User("bob"), null);

                        return pool.withTransaction(tx -> engine.taskCompleted(cmd1, tx))
                                .compose(applied -> engine.executionProbe()
                                        .awaitIdle(workflowId, IDLE_TIMEOUT)
                                        .map(idle -> applied))
                                .compose(r -> pool.withTransaction(tx -> engine.taskCompleted(cmd2, tx)))
                                .map(r -> {
                                    ctx.verify(() -> ctx.failNow(
                                            new AssertionError("Second call must have failed with conflict")));
                                    return null;
                                });
                    }))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.verify(() -> assertInstanceOf(WorkflowIdempotencyConflictException.class, ar.cause()));
                            ctx.completeNow();
                        }
                    });
        }
    }

    // =========================================================================
    // Reassignment
    // =========================================================================

    /**
     * Reassigns a task and verifies the task row columns and {@code TASK_REASSIGNED} history payload.
     */
    @Test
    @DisplayName("reassignment updates assignee columns, reassigned_by, and writes TASK_REASSIGNED history")
    void reassignment(VertxTestContext ctx) {
        TaskState state = TaskState.of("reassign-" + UUID.randomUUID());
        String idemKey = "idem-reassign-" + UUID.randomUUID();

        startWorkflow("human-task-review-test", state)
                .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                    TaskReassignmentCommand reassignCmd = new TaskReassignmentCommand(
                            taskId,
                            new TaskAssignment.Role("legal"),
                            new WorkflowActor.User("manager-bob"),
                            idemKey,
                            "needs specialist");

                    return pool.withTransaction(tx -> engine.taskReassigned(reassignCmd, tx))
                            .compose(result -> {
                                ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, result));
                                return readTaskAssignment(taskId);
                            })
                            .compose(assignment -> {
                                ctx.verify(() -> {
                                    assertEquals("ROLE", assignment[0], "assignee_type must be ROLE after reassign");
                                    assertEquals("legal", assignment[1], "assignee_key must be legal after reassign");
                                });
                                return readTaskJsonbColumn(taskId, "reassigned_by");
                            })
                            .compose(reassignedByJson -> {
                                ctx.verify(() -> {
                                    assertNotNull(reassignedByJson);
                                    String[] kv = parseActorKindValue(reassignedByJson);
                                    assertEquals("USER", kv[0]);
                                    assertEquals("manager-bob", kv[1]);
                                });
                                return findHistoryPayload(workflowId, WorkflowEntryType.TASK_REASSIGNED);
                            })
                            .map(payloadJson -> {
                                ctx.verify(() -> {
                                    assertNotNull(payloadJson, "TASK_REASSIGNED history must exist");
                                    JsonObject payload = new JsonObject(payloadJson);
                                    // oldAssignment was Role("compliance")
                                    JsonObject oldAssignment = payload.getJsonObject("oldAssignment");
                                    assertNotNull(oldAssignment);
                                    assertEquals("compliance", oldAssignment.getString("roleId"));
                                    // newAssignment is Role("legal")
                                    JsonObject newAssignment = payload.getJsonObject("newAssignment");
                                    assertNotNull(newAssignment);
                                    assertEquals("legal", newAssignment.getString("roleId"));
                                    // reassignedBy is User("manager-bob")
                                    JsonObject reassignedBy = payload.getJsonObject("reassignedBy");
                                    assertNotNull(reassignedBy);
                                    assertEquals("manager-bob", reassignedBy.getString("userId"));
                                    // reason
                                    assertEquals("needs specialist", payload.getString("reason"));
                                });
                                return null;
                            });
                }))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    /**
     * Idempotent reassignment: second call with same key returns {@link TaskMutationResult#LOST_TO_RACE}
     * (matching dedup fingerprint — original reassignment already updated the row) and only one
     * {@code TASK_REASSIGNED} history entry exists.
     */
    @Test
    @DisplayName("idempotent reassignment: second call with same key returns LOST_TO_RACE")
    void idempotentReassignment(VertxTestContext ctx) {
        TaskState state = TaskState.of("idem-reassign-" + UUID.randomUUID());
        String idemKey = "idem-reassign-key-" + UUID.randomUUID();

        startWorkflow("human-task-review-test", state)
                .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                    TaskReassignmentCommand cmd = new TaskReassignmentCommand(
                            taskId,
                            new TaskAssignment.Role("legal"),
                            new WorkflowActor.User("manager-bob"),
                            idemKey,
                            "needs specialist");

                    return pool.withTransaction(tx -> engine.taskReassigned(cmd, tx))
                            .compose(firstResult -> {
                                ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, firstResult));
                                return pool.withTransaction(tx -> engine.taskReassigned(cmd, tx));
                            })
                            .compose(secondResult -> {
                                ctx.verify(() -> assertEquals(
                                        TaskMutationResult.LOST_TO_RACE,
                                        secondResult,
                                        "second reassign with same key must be LOST_TO_RACE"));
                                return countHistoryEntries(workflowId, WorkflowEntryType.TASK_REASSIGNED);
                            })
                            .map(count -> {
                                ctx.verify(() ->
                                        assertEquals(1L, count, "only one TASK_REASSIGNED history entry must exist"));
                                return null;
                            });
                }))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    /**
     * Reassignment idempotency conflict: second call with same key but different new assignment
     * fails with {@link WorkflowIdempotencyConflictException}.
     */
    @Test
    @DisplayName("reassignment idempotency conflict: different newAssignment with same key fails")
    void reassignmentIdempotencyConflict(VertxTestContext ctx) {
        TaskState state = TaskState.of("reassign-conflict-" + UUID.randomUUID());
        String idemKey = "idem-reassign-conf-" + UUID.randomUUID();

        startWorkflow("human-task-review-test", state)
                .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                    TaskReassignmentCommand cmd1 = new TaskReassignmentCommand(
                            taskId,
                            new TaskAssignment.Role("legal"),
                            new WorkflowActor.User("manager-bob"),
                            idemKey,
                            "needs specialist");
                    TaskReassignmentCommand cmd2 = new TaskReassignmentCommand(
                            taskId,
                            new TaskAssignment.Role("finance"),
                            new WorkflowActor.User("manager-bob"),
                            idemKey,
                            "needs specialist");

                    return pool.withTransaction(tx -> engine.taskReassigned(cmd1, tx))
                            .compose(r -> pool.withTransaction(tx -> engine.taskReassigned(cmd2, tx)))
                            .map(r -> {
                                ctx.verify(() ->
                                        ctx.failNow(new AssertionError("Second call must have failed with conflict")));
                                return null;
                            });
                }))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.verify(() -> assertInstanceOf(
                                WorkflowIdempotencyConflictException.class,
                                ar.cause(),
                                "must fail with WorkflowIdempotencyConflictException"));
                        ctx.completeNow();
                    }
                });
    }

    /**
     * Reassign with one actor, then complete with a different actor. Final task row carries both
     * {@code reassigned_by} and {@code completed_by} with correct actors.
     */
    @Test
    @DisplayName("reassignment-then-completion: both reassigned_by and completed_by reflect correct actors")
    void reassignmentThenCompletionActorDistinction(VertxTestContext ctx) {
        TaskState state = TaskState.of("reassign-complete-" + UUID.randomUUID());

        startWorkflow("human-task-review-test", state)
                .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                    TaskReassignmentCommand reassignCmd = new TaskReassignmentCommand(
                            taskId,
                            new TaskAssignment.Role("legal"),
                            new WorkflowActor.User("manager-bob"),
                            "idem-reassign-" + UUID.randomUUID(),
                            null);
                    TaskCompletionCommand completeCmd = new TaskCompletionCommand(
                            taskId,
                            "approve",
                            "ok",
                            "idem-complete-" + UUID.randomUUID(),
                            new WorkflowActor.User("alice"),
                            null);

                    return pool.withTransaction(tx -> engine.taskReassigned(reassignCmd, tx))
                            .compose(r -> pool.withTransaction(tx -> engine.taskCompleted(completeCmd, tx)))
                            .compose(applied -> engine.executionProbe()
                                    .awaitIdle(workflowId, IDLE_TIMEOUT)
                                    .map(idle -> applied))
                            .compose(r -> Future.all(
                                    readTaskJsonbColumn(taskId, "reassigned_by"),
                                    readTaskJsonbColumn(taskId, "completed_by")))
                            .map(results -> {
                                ctx.verify(() -> {
                                    String reassignedByJson = results.resultAt(0);
                                    String completedByJson = results.resultAt(1);
                                    assertNotNull(reassignedByJson, "reassigned_by must be set");
                                    assertNotNull(completedByJson, "completed_by must be set");
                                    String[] reassignKv = parseActorKindValue(reassignedByJson);
                                    assertEquals("USER", reassignKv[0]);
                                    assertEquals("manager-bob", reassignKv[1]);
                                    String[] completeKv = parseActorKindValue(completedByJson);
                                    assertEquals("USER", completeKv[0]);
                                    assertEquals("alice", completeKv[1]);
                                });
                                return null;
                            });
                }))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    // =========================================================================
    // Due-date timer
    // =========================================================================

    /**
     * Verifies the due-date path: task row and timer row both exist after start; when the due date
     * passes, the executor fires the due-date command, which expires the task, advances the
     * workflow, and writes a {@code TASK_EXPIRED} history entry.
     */
    @Test
    @DisplayName("due-date: the due-date command expires the task and advances workflow to escalate")
    void dueDateTimerFired(VertxTestContext ctx) {
        TaskState state = TaskState.of("due-date-" + UUID.randomUUID());

        startWorkflow("human-task-due-date-test", state)
                .compose(workflowId -> {
                    UUID timerId = lastTimerId.get();
                    assertNotNull(timerId, "the start must record the due-date timer");

                    // Read the instance to get the taskId from waitKey
                    return pool.withTransaction(tx -> instanceRepo.findById(workflowId, tx))
                            .compose(optInst -> {
                                ctx.verify(() -> {
                                    assertTrue(optInst.isPresent());
                                    assertEquals(WaitType.TASK, optInst.get().waitType());
                                    assertEquals(timerId, optInst.get().waitAuxId(), "waitAuxId must be the timer id");
                                });

                                // The due date passes: the executor fires the due-date command
                                return TimerCommandRows.makeDue(pool, timerId);
                            })
                            .compose(v -> engine.executionProbe().awaitIdle(workflowId, IDLE_TIMEOUT))
                            .compose(idle -> TimerCommandRows.commandState(pool, timerId))
                            .compose(commandState -> {
                                ctx.verify(() -> assertEquals(
                                        "COMPLETE", commandState, "the due-date command must fire and complete"));
                                return pool.withTransaction(tx -> instanceRepo.findById(workflowId, tx));
                            })
                            .compose(optUpdated -> {
                                ctx.verify(() -> {
                                    assertTrue(optUpdated.isPresent());
                                    assertEquals(
                                            WorkflowStatus.COMPLETED,
                                            optUpdated.get().status());
                                });

                                // Verify TASK_EXPIRED history entry
                                return findHistoryPayload(workflowId, WorkflowEntryType.TASK_EXPIRED);
                            })
                            .map(payloadJson -> {
                                ctx.verify(() -> {
                                    assertNotNull(payloadJson, "TASK_EXPIRED history entry must exist");
                                    JsonObject payload = new JsonObject(payloadJson);
                                    // expiredBy is System("due-date-expired")
                                    JsonObject expiredBy = payload.getJsonObject("expiredBy");
                                    assertNotNull(expiredBy);
                                    assertEquals("due-date-expired", expiredBy.getString("reason"));
                                });
                                return null;
                            });
                })
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    // =========================================================================
    // Cancel while WAITING on TASK
    // =========================================================================

    /**
     * Cancelling a workflow while it is WAITING on a TASK marks the task row CANCELLED
     * with {@code cancelled_by = System("workflow-cancelled")} and writes a
     * {@code TASK_CANCELLED} history entry. Any due-date timer is also CANCELLED.
     */
    @Test
    @DisplayName("cancel() on TASK WAITING marks task CANCELLED and writes TASK_CANCELLED history")
    void cancelOnTaskWaitingMarksCancelled(VertxTestContext ctx) {
        TaskState state = TaskState.of("cancel-task-" + UUID.randomUUID());

        startWorkflow("human-task-due-date-test", state)
                .compose(workflowId -> {
                    UUID timerId = lastTimerId.get();
                    assertNotNull(timerId, "due-date timerId must be set");

                    return pool.withTransaction(tx -> instanceRepo.findById(workflowId, tx))
                            .compose(optInst -> {
                                UUID taskId = UUID.fromString(optInst.get().waitKey());

                                return engine.cancel(workflowId, "test cancel")
                                        .compose(v -> readTaskJsonbColumn(taskId, "cancelled_by"))
                                        .compose(cancelledByJson -> {
                                            ctx.verify(() -> {
                                                assertNotNull(cancelledByJson, "cancelled_by must be set");
                                                String[] kv = parseActorKindValue(cancelledByJson);
                                                assertEquals("SYSTEM", kv[0]);
                                                assertEquals("workflow-cancelled", kv[1]);
                                            });
                                            return findHistoryPayload(workflowId, WorkflowEntryType.TASK_CANCELLED);
                                        })
                                        .compose(taskCancelledPayload -> {
                                            ctx.verify(() -> {
                                                assertNotNull(
                                                        taskCancelledPayload, "TASK_CANCELLED history must exist");
                                                JsonObject payload = new JsonObject(taskCancelledPayload);
                                                JsonObject cancelledBy = payload.getJsonObject("cancelledBy");
                                                assertNotNull(cancelledBy);
                                                assertEquals("workflow-cancelled", cancelledBy.getString("reason"));
                                            });
                                            // Verify the due-date timer is also CANCELLED
                                            return pool.preparedQuery(
                                                            "SELECT status FROM workflow_timers WHERE timer_id = $1")
                                                    .execute(Tuple.of(timerId));
                                        })
                                        .map(rs -> {
                                            ctx.verify(() -> {
                                                var it = rs.iterator();
                                                assertTrue(it.hasNext(), "timer row must exist");
                                                assertEquals(
                                                        "CANCELLED",
                                                        it.next().getString("status"),
                                                        "due-date timer must be CANCELLED");
                                            });
                                            return null;
                                        });
                            });
                })
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    // =========================================================================
    // Decision-name dispatch
    // =========================================================================

    /**
     * Completing with "approve" advances to "ship" (COMPLETED); completing a separate instance
     * with "reject" advances to "notify" (COMPLETED). The two steps are verified in separate
     * workflow instances.
     */
    @Test
    @DisplayName("decision-name dispatch: approve→ship, reject→notify in separate instances")
    void decisionNameDispatch(VertxTestContext ctx) {
        TaskState stateApprove = TaskState.of("dec-approve-" + UUID.randomUUID());
        TaskState stateReject = TaskState.of("dec-reject-" + UUID.randomUUID());

        // Start and complete with "approve"
        startWorkflow("human-task-review-test", stateApprove)
                .compose(wfApprove -> readTaskId(wfApprove).compose(taskIdApprove -> pool.withTransaction(
                                tx -> engine.taskCompleted(
                                        new TaskCompletionCommand(
                                                taskIdApprove,
                                                "approve",
                                                "approved",
                                                "idem-dec-approve-" + UUID.randomUUID(),
                                                new WorkflowActor.User("alice"),
                                                null),
                                        tx))
                        .compose(applied -> engine.executionProbe()
                                .awaitIdle(wfApprove, IDLE_TIMEOUT)
                                .map(idle -> applied))
                        .compose(r -> pool.withTransaction(tx -> instanceRepo.findById(wfApprove, tx)))
                        .map(optInst -> {
                            ctx.verify(() -> {
                                assertTrue(optInst.isPresent());
                                assertEquals(
                                        WorkflowStatus.COMPLETED, optInst.get().status());
                            });
                            return null;
                        })))
                // Start and complete with "reject"
                .compose(v -> startWorkflow("human-task-review-test", stateReject))
                .compose(wfReject -> readTaskId(wfReject).compose(taskIdReject -> pool.withTransaction(
                                tx -> engine.taskCompleted(
                                        new TaskCompletionCommand(
                                                taskIdReject,
                                                "reject",
                                                "rejected",
                                                "idem-dec-reject-" + UUID.randomUUID(),
                                                new WorkflowActor.User("alice"),
                                                null),
                                        tx))
                        .compose(applied -> engine.executionProbe()
                                .awaitIdle(wfReject, IDLE_TIMEOUT)
                                .map(idle -> applied))
                        .compose(r -> pool.withTransaction(tx -> instanceRepo.findById(wfReject, tx)))
                        .map(optInst -> {
                            ctx.verify(() -> {
                                assertTrue(optInst.isPresent());
                                assertEquals(
                                        WorkflowStatus.COMPLETED, optInst.get().status());
                            });
                            return null;
                        })))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    // =========================================================================
    // Payload-nullability
    // =========================================================================

    /**
     * Groups for payload nullability enforcement on Void and String declared decisions.
     */
    @Nested
    @DisplayName("Payload-nullability enforcement")
    class PayloadNullability {

        /**
         * Decision declared with {@code Void.class}: completing with {@code null} payload succeeds;
         * completing with a non-null payload is recorded and then rejected by the executor, which reopens the task.
         */
        @Nested
        @DisplayName("Void decision")
        class VoidDecision {

            @Test
            @DisplayName("Void decision with null payload succeeds")
            void voidDecisionNullPayloadSucceeds(VertxTestContext ctx) {
                TaskState state = TaskState.of("void-null-" + UUID.randomUUID());

                startWorkflow("human-task-void-decision-test", state)
                        .compose(workflowId -> readTaskId(workflowId)
                                .compose(taskId -> pool.withTransaction(tx -> engine.taskCompleted(
                                                new TaskCompletionCommand(
                                                        taskId,
                                                        "approve",
                                                        null,
                                                        "idem-void-null-" + UUID.randomUUID(),
                                                        new WorkflowActor.User("alice"),
                                                        null),
                                                tx))
                                        .compose(applied -> engine.executionProbe()
                                                .awaitIdle(workflowId, IDLE_TIMEOUT)
                                                .map(idle -> applied))))
                        .onSuccess(result -> ctx.verify(() -> {
                            assertEquals(TaskMutationResult.APPLIED, result);
                            ctx.completeNow();
                        }))
                        .onFailure(ctx::failNow);
            }

            @Test
            @DisplayName(
                    "Void decision with non-null payload is recorded, rejected by the executor and the task reopens")
            void voidDecisionNonNullPayloadFails(VertxTestContext ctx) {
                TaskState state = TaskState.of("void-nonnull-" + UUID.randomUUID());

                startWorkflow("human-task-void-decision-test", state)
                        .compose(workflowId -> readTaskId(workflowId)
                                .compose(taskId -> pool.withTransaction(tx -> engine.taskCompleted(
                                        new TaskCompletionCommand(
                                                taskId,
                                                "approve",
                                                "unexpected-payload",
                                                "idem-void-nonnull-" + UUID.randomUUID(),
                                                new WorkflowActor.User("alice"),
                                                null),
                                        tx)))
                                .compose(recorded -> engine.executionProbe()
                                        .awaitIdle(workflowId, IDLE_TIMEOUT)
                                        .compose(idle -> completionOutcome(workflowId))
                                        .map(outcome -> Map.entry(recorded, outcome))))
                        .onSuccess(result -> ctx.verify(() -> {
                            assertEquals(TaskMutationResult.APPLIED, result.getKey(), "the completion is recorded");
                            Row outcome = result.getValue();
                            assertEquals("REJECTED", outcome.getString("state"));
                            assertTrue(outcome.getString("last_error").startsWith("DETERMINISTIC_FAILURE"));
                            assertFalse(outcome.getString("last_error").contains("unexpected-payload"));
                            assertEquals("OPEN", outcome.getString("task_status"));
                            ctx.completeNow();
                        }))
                        .onFailure(ctx::failNow);
            }
        }

        /**
         * Decision declared with {@code String.class}: completing with {@code null} payload is recorded and then
         * rejected by the executor, which reopens the task; completing with a non-null payload succeeds.
         */
        @Nested
        @DisplayName("String decision")
        class StringDecision {

            @Test
            @DisplayName("String decision with null payload is recorded, rejected by the executor and the task reopens")
            void stringDecisionNullPayloadFails(VertxTestContext ctx) {
                TaskState state = TaskState.of("str-null-" + UUID.randomUUID());

                startWorkflow("human-task-string-decision-test", state)
                        .compose(workflowId -> readTaskId(workflowId)
                                .compose(taskId -> pool.withTransaction(tx -> engine.taskCompleted(
                                        new TaskCompletionCommand(
                                                taskId,
                                                "approve",
                                                null,
                                                "idem-str-null-" + UUID.randomUUID(),
                                                new WorkflowActor.User("alice"),
                                                null),
                                        tx)))
                                .compose(recorded -> engine.executionProbe()
                                        .awaitIdle(workflowId, IDLE_TIMEOUT)
                                        .compose(idle -> completionOutcome(workflowId))
                                        .map(outcome -> Map.entry(recorded, outcome))))
                        .onSuccess(result -> ctx.verify(() -> {
                            assertEquals(TaskMutationResult.APPLIED, result.getKey(), "the completion is recorded");
                            Row outcome = result.getValue();
                            assertEquals("REJECTED", outcome.getString("state"));
                            assertTrue(outcome.getString("last_error").startsWith("DETERMINISTIC_FAILURE"));
                            assertEquals("OPEN", outcome.getString("task_status"));
                            ctx.completeNow();
                        }))
                        .onFailure(ctx::failNow);
            }

            @Test
            @DisplayName("String decision with non-null payload succeeds")
            void stringDecisionNonNullPayloadSucceeds(VertxTestContext ctx) {
                TaskState state = TaskState.of("str-nonnull-" + UUID.randomUUID());

                startWorkflow("human-task-string-decision-test", state)
                        .compose(workflowId -> readTaskId(workflowId)
                                .compose(taskId -> pool.withTransaction(tx -> engine.taskCompleted(
                                                new TaskCompletionCommand(
                                                        taskId,
                                                        "approve",
                                                        "valid-payload",
                                                        "idem-str-nonnull-" + UUID.randomUUID(),
                                                        new WorkflowActor.User("alice"),
                                                        null),
                                                tx))
                                        .compose(applied -> engine.executionProbe()
                                                .awaitIdle(workflowId, IDLE_TIMEOUT)
                                                .map(idle -> applied))))
                        .onSuccess(result -> ctx.verify(() -> {
                            assertEquals(TaskMutationResult.APPLIED, result);
                            ctx.completeNow();
                        }))
                        .onFailure(ctx::failNow);
            }
        }
    }

    // =========================================================================
    // Resolver fires-once
    // =========================================================================

    /**
     * The state-based assignment resolver fires exactly once per task creation (not on retries,
     * reads, or subsequent operations). Two workflow instances with different {@code signerUserId}
     * values produce correct per-instance task assignments and the resolver counter increments
     * exactly twice.
     */
    @Test
    @DisplayName("AssignmentSpec resolver fires exactly once per task creation across two instances")
    void resolverFiresOnce(VertxTestContext ctx) {
        TaskState stateA = TaskState.of("resolver-a-" + UUID.randomUUID(), "signer-alice");
        TaskState stateB = TaskState.of("resolver-b-" + UUID.randomUUID(), "signer-bob");

        startWorkflow("human-task-resolver-test", stateA)
                .compose(wfA -> {
                    ctx.verify(() -> assertEquals(
                            1, RESOLVER_CALL_COUNT.get(), "resolver must have fired once after first start"));
                    return readTaskId(wfA)
                            .compose(taskIdA -> readTaskAssignment(taskIdA).map(assignment -> {
                                ctx.verify(() -> {
                                    assertEquals("USER", assignment[0], "instance A: assignee_type must be USER");
                                    assertEquals(
                                            "signer-alice",
                                            assignment[1],
                                            "instance A: assignee_key must match signerUserId");
                                });
                                return null;
                            }));
                })
                .compose(v -> startWorkflow("human-task-resolver-test", stateB))
                .compose(wfB -> {
                    ctx.verify(() -> assertEquals(
                            2, RESOLVER_CALL_COUNT.get(), "resolver must have fired exactly twice after second start"));
                    return readTaskId(wfB)
                            .compose(taskIdB -> readTaskAssignment(taskIdB).map(assignment -> {
                                ctx.verify(() -> {
                                    assertEquals("USER", assignment[0], "instance B: assignee_type must be USER");
                                    assertEquals(
                                            "signer-bob",
                                            assignment[1],
                                            "instance B: assignee_key must match signerUserId");
                                });
                                return null;
                            }));
                })
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    // =========================================================================
    // Stale due date
    // =========================================================================

    /**
     * Completing the task first cancels its due date: the timer and its command are cancelled, and a due date that
     * comes due afterwards produces no {@code TASK_EXPIRED} history entry and no workflow transition.
     */
    @Test
    @DisplayName("stale due date: completing the task before the due date cancels it")
    void staleDueDate(VertxTestContext ctx) {
        TaskState state = TaskState.of("stale-due-" + UUID.randomUUID());

        startWorkflow("human-task-due-date-test", state)
                .compose(workflowId -> {
                    UUID timerId = lastTimerId.get();
                    assertNotNull(timerId);

                    return readTaskId(workflowId).compose(taskId -> {
                        // Complete the task first
                        TaskCompletionCommand completeCmd = new TaskCompletionCommand(
                                taskId,
                                "approve",
                                "completed-first",
                                "idem-stale-due-" + UUID.randomUUID(),
                                new WorkflowActor.User("alice"),
                                null);

                        return pool.withTransaction(tx -> engine.taskCompleted(completeCmd, tx))
                                .compose(applied -> engine.executionProbe()
                                        .awaitIdle(workflowId, IDLE_TIMEOUT)
                                        .map(idle -> applied))
                                .compose(r -> {
                                    ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, r));
                                    // The due date is cancelled with the task, and stays so when its time comes
                                    return TimerCommandRows.status(pool, timerId);
                                })
                                .compose(timerStatus -> {
                                    ctx.verify(() -> assertEquals(
                                            "CANCELLED", timerStatus, "completing the task cancels its due date"));
                                    return TimerCommandRows.makeDue(pool, timerId);
                                })
                                .compose(due -> engine.executionProbe().awaitIdle(workflowId, IDLE_TIMEOUT))
                                .compose(idle -> TimerCommandRows.status(pool, timerId))
                                .compose(timerStatus -> {
                                    ctx.verify(() -> assertEquals(
                                            "CANCELLED", timerStatus, "a cancelled due date is not fired when due"));
                                    return countHistoryEntries(workflowId, WorkflowEntryType.TASK_EXPIRED);
                                })
                                .map(count -> {
                                    ctx.verify(() -> assertEquals(0L, count, "no TASK_EXPIRED history must exist"));
                                    return null;
                                });
                    });
                })
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    // =========================================================================
    // expectedAssignment precondition
    // =========================================================================

    /**
     * The {@code expectedAssignment} precondition is compared against the locked task row's
     * current assignment. A mismatch fails the command with
     * {@link WorkflowStaleAssignmentException} and rolls the whole transaction back: the task
     * stays OPEN with its current assignment, the instance stays WAITING, and no history for the
     * rejected command is written.
     */
    @Nested
    @DisplayName("expectedAssignment precondition")
    class ExpectedAssignmentPrecondition {

        private static final TaskAssignment ORIGINAL = new TaskAssignment.Role("compliance");
        private static final TaskAssignment REASSIGNED_TO = new TaskAssignment.User("carol");
        private static final TaskAssignment OTHER = new TaskAssignment.Queue("overflow");

        /** Converts a future that must fail into one that succeeds with its failure cause. */
        private <T> Future<Throwable> failureOf(Future<T> future) {
            return future.<Throwable>transform(ar -> ar.succeeded()
                    ? Future.<Throwable>failedFuture(new AssertionError("command must have failed but succeeded"))
                    : Future.<Throwable>succeededFuture(ar.cause()));
        }

        private Future<String> readTaskStatus(UUID taskId) {
            return pool.preparedQuery("SELECT status FROM workflow_tasks WHERE task_id = $1")
                    .execute(Tuple.of(taskId))
                    .map(rs -> rs.iterator().next().getString("status"));
        }

        private Future<WorkflowStatus> readInstanceStatus(WorkflowInstanceId workflowId) {
            return pool.withTransaction(tx -> instanceRepo.findById(workflowId, tx))
                    .map(opt -> opt.orElseThrow().status());
        }

        private TaskReassignmentCommand reassign(UUID taskId, TaskAssignment target, TaskAssignment expected) {
            return reassign(taskId, target, expected, "idem-reassign-" + UUID.randomUUID());
        }

        private TaskReassignmentCommand reassign(
                UUID taskId, TaskAssignment target, TaskAssignment expected, String idempotencyKey) {
            return new TaskReassignmentCommand(
                    taskId, target, new WorkflowActor.User("manager-bob"), idempotencyKey, null, expected);
        }

        private TaskCompletionCommand complete(UUID taskId, TaskAssignment expected) {
            return complete(taskId, expected, "idem-complete-" + UUID.randomUUID());
        }

        private TaskCompletionCommand complete(UUID taskId, TaskAssignment expected, String idempotencyKey) {
            return new TaskCompletionCommand(
                    taskId, "approve", "ok", idempotencyKey, new WorkflowActor.User("alice"), null, expected);
        }

        @Test
        @DisplayName("completion with a matching expectedAssignment completes the task and the workflow")
        void matchingExpectedAssignmentCompletes(VertxTestContext ctx) {
            TaskState state = TaskState.of("expect-match-" + UUID.randomUUID());

            startWorkflow("human-task-review-test", state)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> pool.withTransaction(
                                    tx -> engine.taskCompleted(complete(taskId, ORIGINAL), tx))
                            .compose(applied -> engine.executionProbe()
                                    .awaitIdle(workflowId, IDLE_TIMEOUT)
                                    .map(idle -> applied))
                            .compose(result -> {
                                ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, result));
                                return Future.all(readTaskStatus(taskId), readInstanceStatus(workflowId));
                            })
                            .map(statuses -> {
                                ctx.verify(() -> {
                                    assertEquals("COMPLETED", statuses.<String>resultAt(0));
                                    assertEquals(WorkflowStatus.COMPLETED, statuses.<WorkflowStatus>resultAt(1));
                                });
                                return null;
                            })))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                        } else {
                            ctx.completeNow();
                        }
                    });
        }

        @Test
        @DisplayName("stale expectedAssignment rejects completion, rolls back, and the same key succeeds corrected")
        void staleExpectedAssignmentRejectsCompletion(VertxTestContext ctx) {
            TaskState state = TaskState.of("expect-stale-complete-" + UUID.randomUUID());
            String idemKey = "idem-stale-complete-" + UUID.randomUUID();

            startWorkflow("human-task-review-test", state)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> pool.withTransaction(
                                    tx -> engine.taskReassigned(reassign(taskId, REASSIGNED_TO, null), tx))
                            .compose(r -> failureOf(pool.withTransaction(
                                    tx -> engine.taskCompleted(complete(taskId, ORIGINAL, idemKey), tx))))
                            .compose(cause -> {
                                ctx.verify(() -> {
                                    WorkflowStaleAssignmentException stale = assertInstanceOf(
                                            WorkflowStaleAssignmentException.class,
                                            cause,
                                            "must fail with WorkflowStaleAssignmentException");
                                    assertEquals(taskId, stale.taskId());
                                    assertEquals(ORIGINAL, stale.expectedAssignment());
                                    assertEquals(REASSIGNED_TO, stale.actualAssignment());
                                });
                                return Future.all(
                                        readTaskStatus(taskId),
                                        readTaskAssignment(taskId),
                                        readTaskJsonbColumn(taskId, "completed_by"),
                                        readInstanceStatus(workflowId),
                                        countHistoryEntries(workflowId, WorkflowEntryType.TASK_COMPLETED));
                            })
                            .compose(after -> {
                                ctx.verify(() -> {
                                    assertEquals("OPEN", after.<String>resultAt(0), "task must stay OPEN");
                                    String[] assignment = after.resultAt(1);
                                    assertEquals("USER", assignment[0], "assignment must remain the new one");
                                    assertEquals("carol", assignment[1], "assignment must remain the new one");
                                    assertNull(after.<String>resultAt(2), "completed_by must not be stamped");
                                    assertEquals(
                                            WorkflowStatus.WAITING,
                                            after.<WorkflowStatus>resultAt(3),
                                            "instance must stay WAITING");
                                    assertEquals(
                                            0L, after.<Long>resultAt(4), "no TASK_COMPLETED history may be written");
                                });
                                // Rollback proof: the rejected attempt must not have left a dedup claim
                                // behind, so the same key resubmitted with the corrected precondition is
                                // a fresh completion rather than a replay.
                                TaskCompletionCommand corrected = complete(taskId, REASSIGNED_TO, idemKey);
                                return pool.withTransaction(tx -> engine.taskCompleted(corrected, tx))
                                        .compose(applied -> engine.executionProbe()
                                                .awaitIdle(workflowId, IDLE_TIMEOUT)
                                                .map(idle -> applied))
                                        .compose(result -> {
                                            ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, result));
                                            return readInstanceStatus(workflowId);
                                        })
                                        .map(status -> {
                                            ctx.verify(() -> assertEquals(WorkflowStatus.COMPLETED, status));
                                            return null;
                                        });
                            })))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                        } else {
                            ctx.completeNow();
                        }
                    });
        }

        @Test
        @DisplayName("completion with null expectedAssignment still completes after a reassignment")
        void nullExpectedAssignmentCompletesAfterReassignment(VertxTestContext ctx) {
            TaskState state = TaskState.of("expect-null-" + UUID.randomUUID());

            startWorkflow("human-task-review-test", state)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> pool.withTransaction(
                                    tx -> engine.taskReassigned(reassign(taskId, REASSIGNED_TO, null), tx))
                            .compose(r -> pool.withTransaction(tx -> engine.taskCompleted(complete(taskId, null), tx)))
                            .compose(applied -> engine.executionProbe()
                                    .awaitIdle(workflowId, IDLE_TIMEOUT)
                                    .map(idle -> applied))
                            .compose(result -> {
                                ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, result));
                                return readInstanceStatus(workflowId);
                            })
                            .map(status -> {
                                ctx.verify(() -> assertEquals(WorkflowStatus.COMPLETED, status));
                                return null;
                            })))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                        } else {
                            ctx.completeNow();
                        }
                    });
        }

        @Test
        @DisplayName("reassignment with a matching expectedAssignment is applied")
        void matchingExpectedAssignmentReassigns(VertxTestContext ctx) {
            TaskState state = TaskState.of("expect-match-reassign-" + UUID.randomUUID());

            startWorkflow("human-task-review-test", state)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> pool.withTransaction(
                                    tx -> engine.taskReassigned(reassign(taskId, REASSIGNED_TO, ORIGINAL), tx))
                            .compose(result -> {
                                ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, result));
                                return Future.all(
                                        readTaskAssignment(taskId),
                                        countHistoryEntries(workflowId, WorkflowEntryType.TASK_REASSIGNED));
                            })
                            .map(after -> {
                                ctx.verify(() -> {
                                    String[] assignment = after.resultAt(0);
                                    assertEquals("USER", assignment[0]);
                                    assertEquals("carol", assignment[1]);
                                    assertEquals(1L, after.<Long>resultAt(1));
                                });
                                return null;
                            })))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                        } else {
                            ctx.completeNow();
                        }
                    });
        }

        @Test
        @DisplayName("stale expectedAssignment rejects reassignment, rolls back, and the same key succeeds corrected")
        void staleExpectedAssignmentRejectsReassignment(VertxTestContext ctx) {
            TaskState state = TaskState.of("expect-stale-reassign-" + UUID.randomUUID());
            String idemKey = "idem-stale-reassign-" + UUID.randomUUID();

            startWorkflow("human-task-review-test", state)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> pool.withTransaction(
                                    tx -> engine.taskReassigned(reassign(taskId, REASSIGNED_TO, null), tx))
                            .compose(r -> failureOf(pool.withTransaction(
                                    tx -> engine.taskReassigned(reassign(taskId, OTHER, ORIGINAL, idemKey), tx))))
                            .compose(cause -> {
                                ctx.verify(() -> {
                                    WorkflowStaleAssignmentException stale = assertInstanceOf(
                                            WorkflowStaleAssignmentException.class,
                                            cause,
                                            "must fail with WorkflowStaleAssignmentException");
                                    assertEquals(taskId, stale.taskId());
                                    assertEquals(ORIGINAL, stale.expectedAssignment());
                                    assertEquals(REASSIGNED_TO, stale.actualAssignment());
                                });
                                return Future.all(
                                        readTaskStatus(taskId),
                                        readTaskAssignment(taskId),
                                        readInstanceStatus(workflowId),
                                        countHistoryEntries(workflowId, WorkflowEntryType.TASK_REASSIGNED));
                            })
                            .compose(after -> {
                                ctx.verify(() -> {
                                    assertEquals("OPEN", after.<String>resultAt(0), "task must stay OPEN");
                                    String[] assignment = after.resultAt(1);
                                    assertEquals("USER", assignment[0], "assignment must still be the first target");
                                    assertEquals("carol", assignment[1], "assignment must still be the first target");
                                    assertEquals(
                                            WorkflowStatus.WAITING,
                                            after.<WorkflowStatus>resultAt(2),
                                            "instance must stay WAITING");
                                    assertEquals(
                                            1L,
                                            after.<Long>resultAt(3),
                                            "only the first reassignment may be in history");
                                });
                                // Rollback proof: the same key resubmitted with the corrected
                                // precondition must be applied, not treated as a replay.
                                return pool.withTransaction(tx -> engine.taskReassigned(
                                                reassign(taskId, OTHER, REASSIGNED_TO, idemKey), tx))
                                        .compose(result -> {
                                            ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, result));
                                            return readTaskAssignment(taskId);
                                        })
                                        .map(assignment -> {
                                            ctx.verify(() -> {
                                                assertEquals("QUEUE", assignment[0]);
                                                assertEquals("overflow", assignment[1]);
                                            });
                                            return null;
                                        });
                            })))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                        } else {
                            ctx.completeNow();
                        }
                    });
        }

        @Test
        @DisplayName("replay with a stale expectedAssignment still resolves through dedup and applies nothing twice")
        void replayWithStaleExpectedAssignmentStillSucceeds(VertxTestContext ctx) {
            TaskState state = TaskState.of("expect-replay-" + UUID.randomUUID());
            String idemKey = "idem-replay-" + UUID.randomUUID();

            startWorkflow("human-task-review-test", state)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> pool.withTransaction(
                                    tx -> engine.taskCompleted(complete(taskId, ORIGINAL, idemKey), tx))
                            .compose(applied -> engine.executionProbe()
                                    .awaitIdle(workflowId, IDLE_TIMEOUT)
                                    .map(idle -> applied))
                            .compose(first -> {
                                ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, first));
                                // Same key, decision, payload and actor; only the precondition differs.
                                return pool.withTransaction(
                                                tx -> engine.taskCompleted(complete(taskId, OTHER, idemKey), tx))
                                        .compose(replayed -> engine.executionProbe()
                                                .awaitIdle(workflowId, IDLE_TIMEOUT)
                                                .map(idle -> replayed));
                            })
                            .compose(replay -> {
                                ctx.verify(() -> assertEquals(
                                        TaskMutationResult.APPLIED,
                                        replay,
                                        "retry must resolve through dedup despite the wrong expectedAssignment"));
                                return Future.all(
                                        readTaskStatus(taskId),
                                        countHistoryEntries(workflowId, WorkflowEntryType.TASK_COMPLETED));
                            })
                            .map(after -> {
                                ctx.verify(() -> {
                                    assertEquals("COMPLETED", after.<String>resultAt(0));
                                    assertEquals(
                                            1L,
                                            after.<Long>resultAt(1),
                                            "the replay must not apply the completion a second time");
                                });
                                return null;
                            })))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                        } else {
                            ctx.completeNow();
                        }
                    });
        }

        @Test
        @DisplayName("reassigning a completed task is a plain conflict, not a stale-assignment failure")
        void terminalTaskRejectsReassignmentAsConflictNotStale(VertxTestContext ctx) {
            TaskState state = TaskState.of("expect-terminal-reassign-" + UUID.randomUUID());

            startWorkflow("human-task-review-test", state)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> pool.withTransaction(
                                    tx -> engine.taskCompleted(complete(taskId, null), tx))
                            .compose(applied -> engine.executionProbe()
                                    .awaitIdle(workflowId, IDLE_TIMEOUT)
                                    .map(idle -> applied))
                            .compose(done -> {
                                ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, done));
                                // OTHER deliberately mismatches the row; the terminal state must win.
                                return failureOf(pool.withTransaction(
                                        tx -> engine.taskReassigned(reassign(taskId, REASSIGNED_TO, OTHER), tx)));
                            })
                            .compose(cause -> {
                                ctx.verify(() -> {
                                    assertInstanceOf(
                                            dev.vertique.workflow.exception.WorkflowConflictException.class,
                                            cause,
                                            "terminal task must fail with WorkflowConflictException");
                                    assertEquals(
                                            dev.vertique.workflow.exception.WorkflowConflictException.class,
                                            cause.getClass(),
                                            "must not be the stale-assignment subtype");
                                    assertTrue(
                                            cause.getMessage().contains("no longer OPEN"),
                                            "message must name the terminal state: " + cause.getMessage());
                                });
                                return Future.all(
                                        readTaskStatus(taskId),
                                        readTaskAssignment(taskId),
                                        countHistoryEntries(workflowId, WorkflowEntryType.TASK_REASSIGNED));
                            })
                            .map(after -> {
                                ctx.verify(() -> {
                                    assertEquals("COMPLETED", after.<String>resultAt(0));
                                    String[] assignment = after.resultAt(1);
                                    assertEquals("ROLE", assignment[0], "assignment must be unchanged");
                                    assertEquals("compliance", assignment[1], "assignment must be unchanged");
                                    assertEquals(0L, after.<Long>resultAt(2), "no reassignment history may exist");
                                });
                                return null;
                            })))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                        } else {
                            ctx.completeNow();
                        }
                    });
        }
    }
}
