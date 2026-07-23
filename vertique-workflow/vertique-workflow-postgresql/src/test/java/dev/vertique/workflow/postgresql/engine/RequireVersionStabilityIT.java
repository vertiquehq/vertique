// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.vertique.workflow.exception.WorkflowStaleSubjectVersionException;
import dev.vertique.workflow.exception.WorkflowSubjectVersionUnavailableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
 * Integration tests for the {@code requireVersionStability} engine path in {@link WorkflowEngineHandle}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Happy path: snapshot written at creation, completion with matching reviewed version
 *       succeeds, history records {@code reviewedSubjectVersion}.</li>
 *   <li>Mismatch: completion with wrong reviewed version fails with
 *       {@link WorkflowStaleSubjectVersionException}; task stays OPEN.</li>
 *   <li>Missing reviewed version: null on a stability-required task fails with
 *       {@link WorkflowStaleSubjectVersionException}.</li>
 *   <li>Null snapshot during start tx: fails with
 *       {@link WorkflowSubjectVersionUnavailableException}; no instance row inserted.</li>
 *   <li>Null snapshot after committed wait: signal-driven transition fails; workflow stays at
 *       prior step.</li>
 *   <li>Path never reached: stability-required task on unreached branch does NOT block
 *       workflows that route around it.</li>
 *   <li>Disabled ignores reviewed version: non-stability task accepts any reviewed value.</li>
 *   <li>History records reviewed version: {@code TASK_COMPLETED} payload carries the
 *       caller-supplied value.</li>
 *   <li>Idempotent retry: same key + same reviewed version → LOST_TO_RACE; only one history
 *       entry.</li>
 *   <li>Idempotency conflict: same key + different reviewed version →
 *       {@link WorkflowIdempotencyConflictException}.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class RequireVersionStabilityIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_version_stability_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgWorkflowInstanceRepository instanceRepo;
    static PgWorkflowHistoryRepository historyRepo;
    static PgTimerStore timerStore;

    /** Last timer id recorded by the fake recorder (set by fakeTimerRecorder). */
    static final AtomicReference<UUID> lastTimerId = new AtomicReference<>();

    // --- Domain types ---

    /**
     * Minimal workflow state for version-stability tests.
     *
     * @param id unique test identifier used as idempotency key suffix
     */
    record StabilityState(String id) implements IdempotencyKeyed {

        /**
         * Factory.
         *
         * @param id the test identifier
         * @return a new {@code StabilityState}
         */
        static StabilityState of(String id) {
            return new StabilityState(id);
        }

        @Override
        public String idempotencyKey() {
            return "stability-it-" + id;
        }
    }

    // --- Workflow contract markers ---

    interface StabilityRequiredContract {}

    interface StabilityNotRequiredContract {}

    interface SignalThenStabilityContract {}

    interface DecisionBranchContract {}

    // --- Workflow definitions ---

    /**
     * Minimal workflow with a stability-required task reachable directly from start:
     * {@code start → review-task (requireVersionStability) → done}.
     */
    static final WorkflowDefinition<StabilityState, StabilityRequiredContract> STABILITY_REQUIRED_DEF =
            new WorkflowDefinition<>() {
                @Override
                public Class<StabilityRequiredContract> contract() {
                    return StabilityRequiredContract.class;
                }

                @Override
                public Class<StabilityState> stateType() {
                    return StabilityState.class;
                }

                @Override
                public String definitionId() {
                    return "stability-required-def";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<StabilityState> wf) {
                    wf.init(StabilityState.class, s -> s)
                            .initialStep("review-task")
                            .task("review-task")
                            .assignToRole("editors")
                            .requireVersionStability()
                            .decision("approve", Void.class)
                            .onDecision((s, p) -> s)
                            .toStep("done")
                            .build()
                            .complete("done");
                }
            };

    /**
     * Minimal workflow with a task that does NOT require version stability:
     * {@code start → review-task → done}.
     */
    static final WorkflowDefinition<StabilityState, StabilityNotRequiredContract> STABILITY_NOT_REQUIRED_DEF =
            new WorkflowDefinition<>() {
                @Override
                public Class<StabilityNotRequiredContract> contract() {
                    return StabilityNotRequiredContract.class;
                }

                @Override
                public Class<StabilityState> stateType() {
                    return StabilityState.class;
                }

                @Override
                public String definitionId() {
                    return "stability-not-required-def";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<StabilityState> wf) {
                    wf.init(StabilityState.class, s -> s)
                            .initialStep("review-task")
                            .task("review-task")
                            .assignToRole("editors")
                            .decision("approve", Void.class)
                            .onDecision((s, p) -> s)
                            .toStep("done")
                            .build()
                            .complete("done");
                }
            };

    /**
     * Workflow where a signal-wait precedes the stability-required task:
     * {@code start → wait-signal → review-task (requireVersionStability) → done}.
     *
     * <p>Used to test that the stability guard runs in the post-commit signal-driven transition,
     * not at start time.
     */
    static final WorkflowDefinition<StabilityState, SignalThenStabilityContract> SIGNAL_THEN_STABILITY_DEF =
            new WorkflowDefinition<>() {
                @Override
                public Class<SignalThenStabilityContract> contract() {
                    return SignalThenStabilityContract.class;
                }

                @Override
                public Class<StabilityState> stateType() {
                    return StabilityState.class;
                }

                @Override
                public String definitionId() {
                    return "signal-then-stability-def";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<StabilityState> wf) {
                    wf.init(StabilityState.class, s -> s)
                            .initialStep("wait-signal")
                            .waitForSignal("wait-signal", "advance", StabilityState.class)
                            .onSignal((s, payload) -> payload)
                            .toStepOnSignal("review-task")
                            .build()
                            .task("review-task")
                            .assignToRole("editors")
                            .requireVersionStability()
                            .decision("approve", Void.class)
                            .onDecision((s, p) -> s)
                            .toStep("done")
                            .build()
                            .complete("done");
                }
            };

    /**
     * Workflow with a decision node routing to either a stability-required task (branch A) or
     * directly to complete (branch B). Used to prove per-task semantics: branch A's task does not
     * block workflows that take branch B.
     *
     * <p>Structure: {@code start → decision ("a"→review-task, "b"→done) → review-task
     * (requireVersionStability) → done}.
     */
    static final WorkflowDefinition<StabilityState, DecisionBranchContract> DECISION_BRANCH_DEF =
            new WorkflowDefinition<>() {
                @Override
                public Class<DecisionBranchContract> contract() {
                    return DecisionBranchContract.class;
                }

                @Override
                public Class<StabilityState> stateType() {
                    return StabilityState.class;
                }

                @Override
                public String definitionId() {
                    return "decision-branch-stability-def";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<StabilityState> wf) {
                    wf.init(StabilityState.class, s -> s)
                            .initialStep("choose")
                            .decide("choose", s -> {
                                // Route based on id prefix: "branch-a-*" → "review-task"; else → "done"
                                return s.id().startsWith("branch-a-") ? "review-task" : "done";
                            })
                            .task("review-task")
                            .assignToRole("editors")
                            .requireVersionStability()
                            .decision("approve", Void.class)
                            .onDecision((s, p) -> s)
                            .toStep("done")
                            .build()
                            .complete("done");
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

        // Minimal no-op timer recorder (no timers are needed in these tests).
        WorkflowSideEffectRecorder<SqlClient> noopTimerRecorder = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.WORKFLOW_TIMER;
            }

            @Override
            public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                return Future.succeededFuture(RecorderResult.empty());
            }
        };

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(STABILITY_REQUIRED_DEF);
        registry.register(STABILITY_NOT_REQUIRED_DEF);
        registry.register(SIGNAL_THEN_STABILITY_DEF);
        registry.register(DECISION_BRANCH_DEF);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(noopTimerRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                new PgTaskStore(pool, exMapper),
                Clock.systemUTC());

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        lastTimerId.set(null);
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
     * Starts a workflow with the given definition id, initial state, and subject ref.
     *
     * @param definitionId the workflow definition to start
     * @param state        the initial state
     * @param subjectRef   the subject ref to associate with the workflow; may be null
     * @return a {@link Future} of the new workflow instance id
     */
    private Future<WorkflowInstanceId> startWorkflow(
            String definitionId, StabilityState state, WorkflowSubjectRef subjectRef) {
        StartCommand cmd = new StartCommand(definitionId, state, state.idempotencyKey(), null, subjectRef);
        return engine.start(cmd);
    }

    /**
     * Reads the task UUID from the {@code workflow_instances.wait_key} column.
     *
     * @param workflowId the workflow instance id
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
     * Reads the {@code subject_version_at_creation} column from the task row.
     *
     * @param taskId the task UUID
     * @return a {@link Future} containing the version string, or {@code null} if null
     */
    private Future<String> readSubjectVersionAtCreation(UUID taskId) {
        String sql = "SELECT subject_version_at_creation FROM workflow_tasks WHERE task_id = $1";
        return pool.preparedQuery(sql).execute(Tuple.of(taskId)).map(rs -> {
            var it = rs.iterator();
            assertTrue(it.hasNext(), "task row must exist for task_id=" + taskId);
            return it.next().getString("subject_version_at_creation");
        });
    }

    /**
     * Reads the task status from the task row.
     *
     * @param taskId the task UUID
     * @return a {@link Future} containing the status string (e.g., {@code "OPEN"})
     */
    private Future<String> readTaskStatus(UUID taskId) {
        return pool.preparedQuery("SELECT status FROM workflow_tasks WHERE task_id = $1")
                .execute(Tuple.of(taskId))
                .map(rs -> {
                    var it = rs.iterator();
                    assertTrue(it.hasNext(), "task row must exist");
                    return it.next().getString("status");
                });
    }

    /**
     * Counts workflow_instances rows.
     *
     * @return a {@link Future} containing the row count
     */
    private Future<Long> countInstances() {
        return pool.query("SELECT COUNT(*) AS c FROM workflow_instances")
                .execute()
                .map(rs -> rs.iterator().next().getLong("c"));
    }

    /**
     * Counts history entries of the given type for the workflow instance.
     *
     * @param workflowId the workflow instance id
     * @param entryType  the history entry type to count
     * @return a {@link Future} containing the count
     */
    private Future<Long> countHistoryEntries(WorkflowInstanceId workflowId, WorkflowEntryType entryType) {
        return historyRepo.listByInstance(workflowId, pool).map(entries -> entries.stream()
                .filter(e -> e.entryType() == entryType)
                .count());
    }

    /**
     * Finds the JSONB payload of the first history entry of the given type.
     *
     * @param workflowId the workflow instance id
     * @param entryType  the history entry type to search for
     * @return a {@link Future} containing the payload JSON string, or {@code null} if not found
     */
    private Future<String> findHistoryPayload(WorkflowInstanceId workflowId, WorkflowEntryType entryType) {
        return historyRepo.listByInstance(workflowId, pool).map(entries -> entries.stream()
                .filter(e -> e.entryType() == entryType)
                .findFirst()
                .map(e -> e.payloadJson())
                .orElse(null));
    }

    // =========================================================================
    // Happy Path
    // =========================================================================

    /**
     * Verifies the happy path: snapshot written at task creation, completion with matching reviewed
     * version succeeds, and history records the reviewed version.
     */
    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("snapshot written at creation; completion with matching reviewedSubjectVersion succeeds")
        void happyPath(VertxTestContext ctx) {
            StabilityState state = StabilityState.of("happy-" + UUID.randomUUID());
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-1", "v3");
            String idemKey = "idem-happy-" + UUID.randomUUID();

            startWorkflow("stability-required-def", state, ref)
                    .compose(workflowId -> readTaskId(workflowId)
                            .compose(taskId ->
                                    // Assert snapshot written correctly via SQL.
                                    readSubjectVersionAtCreation(taskId).compose(snapshot -> {
                                        ctx.verify(() -> assertEquals("v3", snapshot, "snapshot must be v3"));

                                        // Complete with matching reviewed version.
                                        TaskCompletionCommand cmd = new TaskCompletionCommand(
                                                taskId,
                                                "approve",
                                                null,
                                                idemKey,
                                                new WorkflowActor.User("alice"),
                                                "v3");
                                        return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                                                .compose(result -> {
                                                    ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, result));
                                                    // Workflow must now be COMPLETED.
                                                    return pool.withTransaction(
                                                            tx -> instanceRepo.findById(workflowId, tx));
                                                })
                                                .compose(optInst -> {
                                                    ctx.verify(() -> {
                                                        assertTrue(optInst.isPresent());
                                                        assertEquals(
                                                                WorkflowStatus.COMPLETED,
                                                                optInst.get().status());
                                                    });
                                                    // TASK_COMPLETED history must contain reviewedSubjectVersion.
                                                    return findHistoryPayload(
                                                            workflowId, WorkflowEntryType.TASK_COMPLETED);
                                                })
                                                .map(payloadJson -> {
                                                    ctx.verify(() -> {
                                                        assertNotNull(payloadJson, "TASK_COMPLETED history must exist");
                                                        JsonObject payload = new JsonObject(payloadJson);
                                                        assertEquals(
                                                                "v3",
                                                                payload.getString("reviewedSubjectVersion"),
                                                                "history payload must record reviewedSubjectVersion=v3");
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
    }

    // =========================================================================
    // Version mismatch failures
    // =========================================================================

    /**
     * Tests for cases where the caller's reviewed version does not match the snapshot.
     */
    @Nested
    @DisplayName("reviewed version mismatch")
    class ReviewedVersionMismatch {

        @Test
        @DisplayName(
                "reviewedSubjectVersion=v4 vs snapshot=v3 fails with WorkflowStaleSubjectVersionException; task stays OPEN")
        void reviewedMismatch(VertxTestContext ctx) {
            StabilityState state = StabilityState.of("mismatch-" + UUID.randomUUID());
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-2", "v3");
            String idemKey = "idem-mismatch-" + UUID.randomUUID();

            startWorkflow("stability-required-def", state, ref)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                        TaskCompletionCommand cmd = new TaskCompletionCommand(
                                taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), "v4");
                        return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                                .map(result -> {
                                    ctx.verify(() ->
                                            ctx.failNow(new AssertionError("Must have failed with stale version")));
                                    return null;
                                })
                                .recover(t ->
                                        // Verify exception type.
                                        Future.succeededFuture(t)
                                                .compose(ex -> {
                                                    ctx.verify(() -> assertInstanceOf(
                                                            WorkflowStaleSubjectVersionException.class,
                                                            ex,
                                                            "must fail with WorkflowStaleSubjectVersionException"));
                                                    // Task must still be OPEN.
                                                    return readTaskStatus(taskId);
                                                })
                                                .compose(status -> {
                                                    ctx.verify(() ->
                                                            assertEquals("OPEN", status, "task must remain OPEN"));
                                                    // No TASK_COMPLETED history.
                                                    return countHistoryEntries(
                                                            workflowId, WorkflowEntryType.TASK_COMPLETED);
                                                })
                                                .map(count -> {
                                                    ctx.verify(() -> assertEquals(
                                                            0L, count, "no TASK_COMPLETED history must exist"));
                                                    return null;
                                                }));
                    }))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                        } else {
                            ctx.completeNow();
                        }
                    });
        }

        @Test
        @DisplayName(
                "null reviewedSubjectVersion on stability-required task fails with WorkflowStaleSubjectVersionException")
        void missingReviewed(VertxTestContext ctx) {
            StabilityState state = StabilityState.of("missing-" + UUID.randomUUID());
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-3", "v3");
            String idemKey = "idem-missing-" + UUID.randomUUID();

            startWorkflow("stability-required-def", state, ref)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                        // Caller forgot to supply reviewedSubjectVersion.
                        TaskCompletionCommand cmd = new TaskCompletionCommand(
                                taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), null);
                        return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                                .map(result -> {
                                    ctx.verify(() ->
                                            ctx.failNow(new AssertionError("Must have failed with stale version")));
                                    return null;
                                })
                                .recover(t -> Future.succeededFuture(t)
                                        .compose(ex -> {
                                            ctx.verify(
                                                    () -> assertInstanceOf(
                                                            WorkflowStaleSubjectVersionException.class,
                                                            ex,
                                                            "null reviewedSubjectVersion must fail with WorkflowStaleSubjectVersionException"));
                                            return readTaskStatus(taskId);
                                        })
                                        .compose(status -> {
                                            ctx.verify(() -> assertEquals("OPEN", status, "task must remain OPEN"));
                                            return countHistoryEntries(workflowId, WorkflowEntryType.TASK_COMPLETED);
                                        })
                                        .map(count -> {
                                            ctx.verify(() -> assertEquals(
                                                    0L,
                                                    count,
                                                    "no TASK_COMPLETED history must exist after null mismatch"));
                                            return null;
                                        }));
                    }))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                        } else {
                            ctx.completeNow();
                        }
                    });
        }
    }

    // =========================================================================
    // Null snapshot failures
    // =========================================================================

    /**
     * Tests for cases where the workflow's subject ref has no version at task-creation time.
     */
    @Nested
    @DisplayName("null snapshot failures")
    class NullSnapshotFailures {

        @Test
        @DisplayName(
                "null subjectRef.version during start tx fails with WorkflowSubjectVersionUnavailableException; no instance row inserted")
        void nullSnapshotFailsAtTaskCreationDuringStartTx(VertxTestContext ctx) {
            StabilityState state = StabilityState.of("null-snap-start-" + UUID.randomUUID());
            // subjectRef with null version — fails per-task stability guard during start tx.
            WorkflowSubjectRef refNoVersion = new WorkflowSubjectRef("Article", "art-10", null);

            engine.start(new StartCommand("stability-required-def", state, state.idempotencyKey(), null, refNoVersion))
                    .map(id -> {
                        ctx.verify(() -> ctx.failNow(new AssertionError(
                                "Start must have failed with WorkflowSubjectVersionUnavailableException")));
                        return null;
                    })
                    .recover(t -> Future.succeededFuture(t)
                            .compose(ex -> {
                                ctx.verify(() -> assertInstanceOf(
                                        WorkflowSubjectVersionUnavailableException.class,
                                        ex,
                                        "must fail with WorkflowSubjectVersionUnavailableException"));
                                // The whole start tx must have rolled back — no instance row.
                                return countInstances();
                            })
                            .map(count -> {
                                ctx.verify(() -> assertEquals(
                                        0L, count, "no workflow_instances row must exist after start-tx rollback"));
                                return null;
                            }))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                        } else {
                            ctx.completeNow();
                        }
                    });
        }

        @Test
        @DisplayName(
                "null snapshot after committed wait fails on signal-driven transition; workflow stays at wait step")
        void nullSnapshotFailsAtTaskCreationAfterCommittedWait(VertxTestContext ctx) {
            StabilityState state = StabilityState.of("null-snap-wait-" + UUID.randomUUID());
            // No subjectRef version — start commits OK (wait step), but the signal-driven
            // transition into the stability-required task fails.
            WorkflowSubjectRef refNoVersion = new WorkflowSubjectRef("Article", "art-20", null);
            String signalDedupKey = "advance-" + UUID.randomUUID();

            startWorkflow("signal-then-stability-def", state, refNoVersion)
                    .compose(workflowId -> {
                        // Start committed cleanly — instance must be WAITING on SIGNAL.
                        return pool.withTransaction(tx -> instanceRepo.findById(workflowId, tx))
                                .compose(optInst -> {
                                    ctx.verify(() -> {
                                        assertTrue(optInst.isPresent());
                                        assertEquals(
                                                WorkflowStatus.WAITING,
                                                optInst.get().status());
                                        assertEquals(
                                                WaitType.SIGNAL, optInst.get().waitType(), "must be waiting on SIGNAL");
                                    });

                                    // Send the signal — transition should fail at stability guard.
                                    return engine.signal(workflowId, "advance", state, signalDedupKey)
                                            .map(v -> {
                                                ctx.verify(
                                                        () -> ctx.failNow(
                                                                new AssertionError(
                                                                        "Signal must have failed with WorkflowSubjectVersionUnavailableException")));
                                                return null;
                                            })
                                            .recover(t -> Future.succeededFuture(t)
                                                    .compose(ex -> {
                                                        ctx.verify(
                                                                () -> assertInstanceOf(
                                                                        WorkflowSubjectVersionUnavailableException
                                                                                .class,
                                                                        ex,
                                                                        "signal transition must fail with WorkflowSubjectVersionUnavailableException"));
                                                        // Workflow must still be WAITING on SIGNAL (prior step
                                                        // unchanged).
                                                        return pool.withTransaction(
                                                                tx -> instanceRepo.findById(workflowId, tx));
                                                    })
                                                    .map(optUpdated -> {
                                                        ctx.verify(() -> {
                                                            assertTrue(optUpdated.isPresent());
                                                            var inst = optUpdated.get();
                                                            assertEquals(
                                                                    WorkflowStatus.WAITING,
                                                                    inst.status(),
                                                                    "workflow must still be WAITING after signal failure");
                                                            assertEquals(
                                                                    WaitType.SIGNAL,
                                                                    inst.waitType(),
                                                                    "workflow must still be waiting on SIGNAL");
                                                            assertEquals(
                                                                    "wait-signal",
                                                                    inst.currentStepId(),
                                                                    "current_step must remain at wait-signal");
                                                        });
                                                        return null;
                                                    }));
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
    }

    // =========================================================================
    // Per-task semantics — unreached branch does not block
    // =========================================================================

    /**
     * Tests proving that a stability-required task on an unreached branch does not block workflows
     * that never reach it.
     */
    @Nested
    @DisplayName("per-task semantics — unreached branch")
    class PerTaskSemantics {

        @Test
        @DisplayName("stability-required task on unreached branch does not block workflows routing around it")
        void pathNeverReachedDoesNotBlock(VertxTestContext ctx) {
            // Route "branch-b-*" → takes the "done" branch, never reaches "review-task".
            StabilityState state = StabilityState.of("branch-b-" + UUID.randomUUID());
            // No versioned subjectRef — but the stability-required task is never reached.
            WorkflowSubjectRef refNoVersion = new WorkflowSubjectRef("Article", "art-30", null);

            startWorkflow("decision-branch-stability-def", state, refNoVersion)
                    .compose(workflowId -> pool.withTransaction(tx -> instanceRepo.findById(workflowId, tx)))
                    .map(optInst -> {
                        ctx.verify(() -> {
                            assertTrue(optInst.isPresent(), "instance must exist");
                            assertEquals(
                                    WorkflowStatus.COMPLETED,
                                    optInst.get().status(),
                                    "workflow must complete normally when stability-required task is unreached");
                        });
                        return null;
                    })
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                        } else {
                            ctx.completeNow();
                        }
                    });
        }
    }

    // =========================================================================
    // Disabled — ignores reviewed version
    // =========================================================================

    /**
     * Tests for tasks that do NOT require version stability.
     */
    @Nested
    @DisplayName("stability disabled — engine ignores reviewedSubjectVersion")
    class StabilityDisabled {

        @Test
        @DisplayName("non-stability task accepts reviewedSubjectVersion mismatch (field unused for completion logic)")
        void disabledIgnoresReviewed(VertxTestContext ctx) {
            StabilityState state = StabilityState.of("disabled-" + UUID.randomUUID());
            // Snapshot is v3 but caller will pass v99 — engine must not enforce this.
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-40", "v3");
            String idemKey = "idem-disabled-" + UUID.randomUUID();

            startWorkflow("stability-not-required-def", state, ref)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                        // Pass mismatched reviewedSubjectVersion — engine should ignore it.
                        TaskCompletionCommand cmd = new TaskCompletionCommand(
                                taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), "v99");
                        return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                                .map(result -> {
                                    ctx.verify(() -> assertEquals(
                                            TaskMutationResult.APPLIED,
                                            result,
                                            "non-stability task must succeed with mismatched reviewedSubjectVersion"));
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
    }

    // =========================================================================
    // History records reviewed version
    // =========================================================================

    /**
     * Tests verifying that the {@code TASK_COMPLETED} history payload records the caller-supplied
     * {@code reviewedSubjectVersion}.
     */
    @Nested
    @DisplayName("history records reviewedSubjectVersion")
    class HistoryRecordsReviewedVersion {

        @Test
        @DisplayName("TASK_COMPLETED history payload contains reviewedSubjectVersion for stability-required task")
        void historyRecordsReviewedVersionForStabilityRequired(VertxTestContext ctx) {
            StabilityState state = StabilityState.of("history-stability-" + UUID.randomUUID());
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-50", "v3");
            String idemKey = "idem-history-stab-" + UUID.randomUUID();

            startWorkflow("stability-required-def", state, ref)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                        TaskCompletionCommand cmd = new TaskCompletionCommand(
                                taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), "v3");
                        return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                                .compose(r -> findHistoryPayload(workflowId, WorkflowEntryType.TASK_COMPLETED))
                                .map(payloadJson -> {
                                    ctx.verify(() -> {
                                        assertNotNull(payloadJson);
                                        JsonObject payload = new JsonObject(payloadJson);
                                        assertEquals(
                                                "v3",
                                                payload.getString("reviewedSubjectVersion"),
                                                "history payload must record reviewedSubjectVersion");
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

        @Test
        @DisplayName("TASK_COMPLETED history payload contains reviewedSubjectVersion for non-stability task")
        void historyRecordsReviewedVersionForNonStabilityTask(VertxTestContext ctx) {
            StabilityState state = StabilityState.of("history-nostab-" + UUID.randomUUID());
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-51", "v3");
            String idemKey = "idem-history-nostab-" + UUID.randomUUID();

            startWorkflow("stability-not-required-def", state, ref)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                        // Pass v99 — mismatch is allowed since stability is not required.
                        TaskCompletionCommand cmd = new TaskCompletionCommand(
                                taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), "v99");
                        return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                                .compose(r -> findHistoryPayload(workflowId, WorkflowEntryType.TASK_COMPLETED))
                                .map(payloadJson -> {
                                    ctx.verify(() -> {
                                        assertNotNull(payloadJson);
                                        JsonObject payload = new JsonObject(payloadJson);
                                        // The caller-supplied value (v99) is recorded regardless.
                                        assertEquals(
                                                "v99",
                                                payload.getString("reviewedSubjectVersion"),
                                                "history payload must record caller-supplied reviewedSubjectVersion=v99");
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

        @Test
        @DisplayName("TASK_COMPLETED history payload records null reviewedSubjectVersion for non-stability task")
        void historyRecordsNullReviewedVersionForNonStabilityTask(VertxTestContext ctx) {
            StabilityState state = StabilityState.of("history-nullrev-" + UUID.randomUUID());
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-52", "v3");
            String idemKey = "idem-history-nullrev-" + UUID.randomUUID();

            startWorkflow("stability-not-required-def", state, ref)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                        // Caller passes null reviewedSubjectVersion — non-stability task accepts it.
                        TaskCompletionCommand cmd = new TaskCompletionCommand(
                                taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), null);
                        return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                                .compose(r -> findHistoryPayload(workflowId, WorkflowEntryType.TASK_COMPLETED))
                                .map(payloadJson -> {
                                    ctx.verify(() -> {
                                        assertNotNull(payloadJson);
                                        JsonObject payload = new JsonObject(payloadJson);
                                        // null is recorded as JSON null — key is present but value is null
                                        assertNull(
                                                payload.getString("reviewedSubjectVersion"),
                                                "history payload must record null reviewedSubjectVersion");
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
    }

    // =========================================================================
    // Idempotency
    // =========================================================================

    /**
     * Tests verifying idempotency behavior with {@code reviewedSubjectVersion} as part of the
     * fingerprint.
     */
    @Nested
    @DisplayName("idempotency — reviewedSubjectVersion participates in fingerprint")
    class IdempotencyTests {

        @Test
        @DisplayName("idempotent retry with same key and same reviewedSubjectVersion returns LOST_TO_RACE")
        void idempotentRetryReturnsLostToRace(VertxTestContext ctx) {
            StabilityState state = StabilityState.of("idem-retry-" + UUID.randomUUID());
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-60", "v3");
            String idemKey = "idem-retry-key-" + UUID.randomUUID();

            startWorkflow("stability-required-def", state, ref)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                        TaskCompletionCommand cmd = new TaskCompletionCommand(
                                taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), "v3");

                        return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                                .compose(firstResult -> {
                                    ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, firstResult));
                                    // Second call with identical command.
                                    return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx));
                                })
                                .compose(secondResult -> {
                                    ctx.verify(() -> assertEquals(
                                            TaskMutationResult.LOST_TO_RACE,
                                            secondResult,
                                            "second call with same key+fingerprint must be LOST_TO_RACE"));
                                    // Only one TASK_COMPLETED history entry.
                                    return countHistoryEntries(workflowId, WorkflowEntryType.TASK_COMPLETED);
                                })
                                .map(count -> {
                                    ctx.verify(() -> assertEquals(1L, count, "only one TASK_COMPLETED history entry"));
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

        @Test
        @DisplayName(
                "same idempotency key but different reviewedSubjectVersion fails with WorkflowIdempotencyConflictException")
        void idempotencyConflictDifferentReviewedVersion(VertxTestContext ctx) {
            // For this test, use a non-stability task so the first completion succeeds regardless
            // of the reviewedSubjectVersion value, then we verify that the same key with a different
            // reviewed version fails as a conflict.
            StabilityState state = StabilityState.of("idem-conflict-" + UUID.randomUUID());
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-70", "v3");
            String idemKey = "idem-conflict-key-" + UUID.randomUUID();

            startWorkflow("stability-not-required-def", state, ref)
                    .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                        // First completion with reviewedSubjectVersion="v3".
                        TaskCompletionCommand cmd1 = new TaskCompletionCommand(
                                taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), "v3");
                        // Second attempt with same key but different reviewedSubjectVersion="v4".
                        TaskCompletionCommand cmd2 = new TaskCompletionCommand(
                                taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), "v4");

                        return pool.withTransaction(tx -> engine.taskCompleted(cmd1, tx))
                                .compose(firstResult -> {
                                    ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, firstResult));
                                    return pool.withTransaction(tx -> engine.taskCompleted(cmd2, tx));
                                })
                                .map(secondResult -> {
                                    ctx.verify(() -> ctx.failNow(new AssertionError(
                                            "Second call must have failed with WorkflowIdempotencyConflictException")));
                                    return null;
                                });
                    }))
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            ctx.verify(
                                    () -> assertInstanceOf(
                                            WorkflowIdempotencyConflictException.class,
                                            ar.cause(),
                                            "different reviewedSubjectVersion with same key must fail with WorkflowIdempotencyConflictException"));
                            ctx.completeNow();
                        }
                    });
        }
    }
}
