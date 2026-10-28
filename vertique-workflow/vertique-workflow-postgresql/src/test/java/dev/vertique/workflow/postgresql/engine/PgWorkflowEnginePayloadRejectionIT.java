// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.DecodeException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for the rejection of caller payload mistakes on both task-completion paths of
 * the engine: the instance task path and the branch task path.
 *
 * <p>A completion whose decision is not declared fails in the caller's transaction with
 * {@link WorkflowInvalidPayloadException}, value-free, and records nothing. A completion whose payload
 * presence contradicts the decision type, whose payload cannot be bound to the declared type, or whose
 * decision applicator throws is recorded and then rejected by the executor: the command is
 * {@code REJECTED} with a sanitized reason that carries no payload value and no exception text, the
 * task is {@code OPEN} again and a corrected completion under a fresh idempotency key applies. The
 * underlying cause is never logged above DEBUG.
 *
 * <p>Each path has its own nested class with identical test names. The registry is built without a
 * plan validator so the branch-owned task plan is accepted.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PgWorkflowEnginePayloadRejectionIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_payload_rejection_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    // --- Constants ---

    /** Value placed in payloads that must never be echoed by a failure message, detail or log line. */
    private static final String SENTINEL = "ZZSENTINEL";

    /** Value carried by the exception that the failing decision applicator throws. */
    private static final String APPLICATOR_SENTINEL = "ZZAPPLICATOR";

    private static final String INSTANCE_DEFINITION_ID = "payload-rejection-instance";
    private static final String BRANCH_DEFINITION_ID = "payload-rejection-branch";

    private static final String VOID_PAYLOAD_MESSAGE = "payload is not accepted for a decision without a payload type";
    private static final String BINDING_MESSAGE = "payload does not bind to the declared type";
    private static final String UNKNOWN_DECISION_MESSAGE = "decision is not declared for this task step";
    private static final String REJECTION_MESSAGE = "invalid decision payload";

    // --- Domain types ---

    /** Start payload and workflow state of both fixtures. */
    record State(String id) {}

    /** Payload of the {@code comment} and {@code explode} decisions. */
    record Comment(String comment) {}

    /** Payload of the {@code count} decision; a string in {@code n} fails with a message that echoes it. */
    record Count(int n) {}

    interface InstanceContract {}

    interface BranchContract {
        @WorkflowSignal("my-signal")
        Future<Void> mySignal(WorkflowInstanceId id, Object payload, @SignalDedupKey String dedupKey);
    }

    // --- Workflow definitions ---

    /**
     * Instance fixture: one task step with the decisions {@code approve} (no payload),
     * {@code comment} (a {@link Comment}), {@code count} (a {@link Count}) and {@code explode}
     * (applicator throws).
     */
    static final WorkflowDefinition<State, InstanceContract> INSTANCE_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<InstanceContract> contract() {
            return InstanceContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return INSTANCE_DEFINITION_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(State.class, s -> s)
                    .initialStep("review")
                    .task("review")
                    .assignToRole("reviewers")
                    .decision("approve", void.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .decision("comment", Comment.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .decision("count", Count.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .decision("explode", Comment.class)
                    .onDecision((s, p) -> {
                        throw new IllegalStateException("applicator rejected " + p.comment());
                    })
                    .toStep("done")
                    .build()
                    .complete("done");
        }
    };

    /**
     * Branch fixture: a fork whose branch A runs the same task step as the instance fixture and
     * whose branch B waits on a signal, so branch A's completion leaves the parent at the join.
     */
    static final WorkflowDefinition<State, BranchContract> BRANCH_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<BranchContract> contract() {
            return BranchContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return BRANCH_DEFINITION_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(State.class, s -> s)
                    .fork("fork")
                    .branch("branch-a", "task-step", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("branch-b", "signal-step", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .task("task-step")
                    .assignToRole("reviewers")
                    .decision("approve", void.class)
                    .onDecision((s, p) -> s)
                    .toStep("complete-a")
                    .decision("comment", Comment.class)
                    .onDecision((s, p) -> s)
                    .toStep("complete-a")
                    .decision("count", Count.class)
                    .onDecision((s, p) -> s)
                    .toStep("complete-a")
                    .decision("explode", Comment.class)
                    .onDecision((s, p) -> {
                        throw new IllegalStateException("applicator rejected " + p.comment());
                    })
                    .toStep("complete-a")
                    .build()
                    .complete("complete-a")
                    .waitForSignal("signal-step", "my-signal", Object.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("complete-b")
                    .build()
                    .complete("complete-b")
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .onFailure("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("fork");
        }
    };

    // --- Lifecycle ---

    private CapturedLogs logs;

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

        PgDbExceptionMapper ex = new PgDbExceptionMapper();
        PgWorkflowInstanceRepository instances = new PgWorkflowInstanceRepository(pool, ex);
        PgWorkflowHistoryRepository history = new PgWorkflowHistoryRepository(pool, ex);
        PgWorkflowDedupRepository dedup = new PgWorkflowDedupRepository(pool, ex);
        PgBranchTokenRepository branches = new PgBranchTokenRepository(pool, ex);
        PgJoinStateRepository joins = new PgJoinStateRepository(pool, ex);

        // No plan validator, so the branch-owned task plan is accepted.
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(INSTANCE_DEF);
        registry.register(BRANCH_DEF);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instances,
                history,
                dedup,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(ex),
                new PgTaskStore(pool, ex),
                Clock.systemUTC(),
                branches,
                joins);
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables() {
        WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
                                + " workflow_tasks, workflow_history, workflow_dedup, workflow_instances"
                                + " RESTART IDENTITY CASCADE")
                .execute()
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    @AfterEach
    void detachLogs() {
        if (logs != null) {
            logs.close();
            logs = null;
        }
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // =========================================================================
    // Instance task path
    // =========================================================================

    /** Completion of a task owned by the workflow instance itself. */
    @Nested
    @DisplayName("Instance task path")
    class InstancePath {

        @Test
        @DisplayName("a void decision with a payload is recorded, rejected by the executor and the task reopens")
        void voidDecisionWithAPayloadFailsOnBothPathsAndLeavesTheTaskOpen() {
            assertVoidDecisionWithPayloadRejected(INSTANCE_DEFINITION_ID);
        }

        @Test
        @DisplayName("an unbindable payload is rejected with a reason and history that carry no token")
        void unbindablePayloadFailsWithTheDecodeCauseAndNoTokenInAnyField() {
            assertUnbindablePayloadRejectedValueFree(INSTANCE_DEFINITION_ID);
        }

        @Test
        @DisplayName("an unknown decision fails with one decision detail")
        void unknownDecisionFailsWithOneDecisionDetail() {
            assertUnknownDecisionRejected(INSTANCE_DEFINITION_ID);
        }

        @Test
        @DisplayName("a rejected completion applies on a retry with a fresh key and a corrected payload")
        void rejectedCompletionRetriesWithTheSameKeyAndACorrectedPayload() {
            assertRejectedCompletionRetries(INSTANCE_DEFINITION_ID);
        }

        @Test
        @DisplayName("the rejected payload cause is never logged above DEBUG")
        void rejectedPayloadCauseIsNeverLoggedAboveDebug() {
            assertRejectedCauseLoggedAtDebugOnly(INSTANCE_DEFINITION_ID);
        }

        @Test
        @DisplayName("a failing applicator rejects the completion without copying its exception message")
        void applicatorFailureChainsTheCauseWithoutTheMessage() {
            assertApplicatorFailureRejected(INSTANCE_DEFINITION_ID);
        }
    }

    // =========================================================================
    // Branch task path
    // =========================================================================

    /** Completion of a task owned by a fork branch. */
    @Nested
    @DisplayName("Branch task path")
    class BranchPath {

        @Test
        @DisplayName("a void decision with a payload is recorded, rejected by the executor and the task reopens")
        void voidDecisionWithAPayloadFailsOnBothPathsAndLeavesTheTaskOpen() {
            assertVoidDecisionWithPayloadRejected(BRANCH_DEFINITION_ID);
        }

        @Test
        @DisplayName("an unbindable payload is rejected with a reason and history that carry no token")
        void unbindablePayloadFailsWithTheDecodeCauseAndNoTokenInAnyField() {
            assertUnbindablePayloadRejectedValueFree(BRANCH_DEFINITION_ID);
        }

        @Test
        @DisplayName("an unknown decision fails with one decision detail")
        void unknownDecisionFailsWithOneDecisionDetail() {
            assertUnknownDecisionRejected(BRANCH_DEFINITION_ID);
        }

        @Test
        @DisplayName("a rejected completion applies on a retry with a fresh key and a corrected payload")
        void rejectedCompletionRetriesWithTheSameKeyAndACorrectedPayload() {
            assertRejectedCompletionRetries(BRANCH_DEFINITION_ID);
        }

        @Test
        @DisplayName("the rejected payload cause is never logged above DEBUG")
        void rejectedPayloadCauseIsNeverLoggedAboveDebug() {
            assertRejectedCauseLoggedAtDebugOnly(BRANCH_DEFINITION_ID);
        }

        @Test
        @DisplayName("a failing applicator rejects the completion without copying its exception message")
        void applicatorFailureChainsTheCauseWithoutTheMessage() {
            assertApplicatorFailureRejected(BRANCH_DEFINITION_ID);
        }
    }

    // =========================================================================
    // Shared scenarios (one per proof, parameterised by the path's definition)
    // =========================================================================

    private void assertVoidDecisionWithPayloadRejected(String definitionId) {
        // Given: an open task on a decision without a payload type
        UUID taskId = startAndFindOpenTask(definitionId);

        // When: it is completed with a payload
        TaskMutationResult recorded = complete(taskId, "approve", Map.of("x", 1), "idem-" + UUID.randomUUID())
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // Then: the completion was recorded and rejected with a sanitized reason, and the task is open again
        assertEquals(TaskMutationResult.APPLIED, recorded, "the completion is recorded");
        assertRejectedAndReopened(taskId, "DETERMINISTIC_FAILURE");
    }

    private void assertUnbindablePayloadRejectedValueFree(String definitionId) {
        // Given: an open task on a decision typed Count
        UUID taskId = startAndFindOpenTask(definitionId);

        // When: it is completed with a payload whose int field holds the sentinel string
        complete(taskId, "count", unbindableCount(), "idem-" + UUID.randomUUID())
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // Then: rejected with a reason and history that carry no token, and the task is open again
        assertRejectedAndReopened(taskId, "DETERMINISTIC_FAILURE");
        assertFalse(
                recordedText(taskId).contains(SENTINEL), "neither the reason nor the history may carry the payload");
    }

    private void assertUnknownDecisionRejected(String definitionId) {
        // Given: an open task
        UUID taskId = startAndFindOpenTask(definitionId);

        // When: it is completed with a decision name the step does not declare
        WorkflowInvalidPayloadException rejection =
                completeExpectingInvalidPayload(taskId, "nope", null, "idem-" + UUID.randomUUID());

        // Then: one decision detail, the name is not echoed, task open
        assertEquals(REJECTION_MESSAGE, rejection.getMessage());
        assertSingleDetail(rejection, UNKNOWN_DECISION_MESSAGE, "decision");
        assertFalse(rejection.getMessage().contains("nope"), "message must not echo the decision name");
        assertFalse(rejection.violations().toString().contains("nope"), "no detail may echo the decision name");
        assertEquals("OPEN", taskStatus(taskId), "a rejected completion must leave the task OPEN");
        assertEquals(0L, commandRows(taskId), "a refused completion records no command");
    }

    private void assertRejectedCompletionRetries(String definitionId) {
        // Given: a completion recorded and then rejected by the executor for an unbindable payload under key k1
        UUID taskId = startAndFindOpenTask(definitionId);
        String k1 = "idem-k1-" + UUID.randomUUID();
        complete(taskId, "count", unbindableCount(), k1)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        assertRejectedAndReopened(taskId, "DETERMINISTIC_FAILURE");
        assertEquals(1L, dedupRows(k1), "the recorded attempt holds its claim");

        // When: the corrected payload is sent under the same key, then under a fresh key
        CompletionException reused =
                assertThrows(CompletionException.class, () -> complete(taskId, "count", Map.of("n", 1), k1)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());
        String k2 = "idem-k2-" + UUID.randomUUID();
        TaskMutationResult retry = complete(taskId, "count", Map.of("n", 1), k2)
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // Then: the key is spent, the fresh key applies, and the task is completed
        assertInstanceOf(
                WorkflowIdempotencyConflictException.class,
                reused.getCause(),
                "the rejected key now belongs to the rejected request");
        assertEquals(TaskMutationResult.APPLIED, retry, "the corrected completion under a fresh key is recorded");
        assertEquals("COMPLETED", taskStatus(taskId));
        assertEquals(1L, dedupRows(k2), "the fresh key holds its own claim");
    }

    private void assertRejectedCauseLoggedAtDebugOnly(String definitionId) {
        // Given: an engine logger capture at INFO and an open task
        logs = CapturedLogs.attach(Level.INFO);
        UUID taskId = startAndFindOpenTask(definitionId);
        logs.clear();

        // When: an unbindable payload is recorded and rejected by the executor
        complete(taskId, "count", unbindableCount(), "idem-info-" + UUID.randomUUID())
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // Then: no event at INFO or above carries the decode cause or the payload value
        for (ILoggingEvent event : logs.events()) {
            if (event.getLevel().isGreaterOrEqual(Level.INFO)) {
                assertFalse(
                        CapturedLogs.carriesDecodeException(event),
                        "the decode cause must not be logged at " + event.getLevel());
                assertFalse(
                        event.getFormattedMessage().contains(SENTINEL),
                        "no log line at " + event.getLevel() + " may carry the payload value");
            }
        }

        // When: the same rejection runs with DEBUG enabled
        logs.setLevel(Level.DEBUG);
        logs.clear();
        complete(taskId, "count", unbindableCount(), "idem-debug-" + UUID.randomUUID())
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // Then: exactly one event carries the decode cause, and no message carries the payload value
        List<ILoggingEvent> events = logs.events();
        long withCause =
                events.stream().filter(CapturedLogs::carriesDecodeException).count();
        assertEquals(1L, withCause, "exactly one DEBUG event must carry the decode cause");
        events.stream()
                .filter(CapturedLogs::carriesDecodeException)
                .forEach(event -> assertEquals(Level.DEBUG, event.getLevel(), "the cause is logged at DEBUG only"));
        assertTrue(
                events.stream().noneMatch(event -> event.getFormattedMessage().contains(SENTINEL)),
                "no log line may carry the payload value");
    }

    private void assertApplicatorFailureRejected(String definitionId) {
        // Given: an open task whose decision applicator throws an exception carrying the payload text
        UUID taskId = startAndFindOpenTask(definitionId);

        // When: it is completed with a valid payload and the executor applies it
        complete(taskId, "explode", Map.of("comment", APPLICATOR_SENTINEL), "idem-" + UUID.randomUUID())
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // Then: the command is rejected with a sanitized reason that copies neither the exception message nor the
        // payload, the task is open again, and the failure is named by class only in the executor's log
        assertRejectedAndReopened(taskId, "DETERMINISTIC_FAILURE");
        assertFalse(
                recordedText(taskId).contains(APPLICATOR_SENTINEL),
                "neither the reason nor the history may copy the application exception message");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * A payload whose {@code n} is the sentinel string, so it cannot bind to the {@code int} of
     * {@link Count}; the binder's message echoes the string.
     */
    private static Map<String, Object> unbindableCount() {
        return Map.of("n", SENTINEL);
    }

    /**
     * Starts the definition and returns the id of its single open task.
     *
     * @param definitionId the fixture definition to start
     * @return the open task id
     */
    private UUID startAndFindOpenTask(String definitionId) {
        String id = UUID.randomUUID().toString();
        WorkflowInstanceId workflowId = engine.start(new StartCommand(
                        definitionId,
                        contractNameOf(definitionId),
                        new State(id),
                        "start-" + id,
                        null,
                        null,
                        null,
                        null,
                        null))
                .compose(started ->
                        engine.executionProbe().awaitIdle(started, IDLE_TIMEOUT).map(idle -> started))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        UUID taskId = pool.preparedQuery(
                        "SELECT task_id FROM workflow_tasks WHERE workflow_id = $1 AND status = 'OPEN'")
                .execute(Tuple.of(workflowId.value()))
                .map(rs -> rs.iterator().hasNext() ? rs.iterator().next().getUUID("task_id") : null)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        assertNotNull(taskId, "the started workflow must have created an open task");
        return taskId;
    }

    /**
     * Returns the contract name of a fixture definition.
     *
     * @param definitionId the id of one of this test's fixture definitions
     * @return the name of the definition's contract
     */
    private static String contractNameOf(String definitionId) {
        return switch (definitionId) {
            case INSTANCE_DEFINITION_ID -> InstanceContract.class.getName();
            case BRANCH_DEFINITION_ID -> BranchContract.class.getName();
            default -> throw new IllegalArgumentException("unknown fixture definition: " + definitionId);
        };
    }

    private static Future<TaskMutationResult> complete(UUID taskId, String decision, Object payload, String key) {
        TaskCompletionCommand cmd =
                new TaskCompletionCommand(taskId, decision, payload, key, new WorkflowActor.User("alice"), null);
        return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                .compose(result ->
                        engine.executionProbe().awaitAllIdle(IDLE_TIMEOUT).map(idle -> result));
    }

    /**
     * Completes the task and returns the payload rejection the engine fails the future with.
     *
     * @param taskId the open task
     * @param decision the decision name
     * @param payload the raw payload, may be {@code null}
     * @param key the idempotency key
     * @return the rejection; the assertion fails when the future fails with any other type
     */
    private static WorkflowInvalidPayloadException completeExpectingInvalidPayload(
            UUID taskId, String decision, Object payload, String key) {
        CompletionException failure =
                assertThrows(CompletionException.class, () -> complete(taskId, decision, payload, key)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());
        return assertInstanceOf(
                WorkflowInvalidPayloadException.class,
                failure.getCause(),
                "completion must fail with WorkflowInvalidPayloadException");
    }

    private static void assertSingleDetail(WorkflowInvalidPayloadException rejection, String message, String type) {
        List<ViolationDetail> violations = rejection.violations();
        assertEquals(1, violations.size(), "exactly one detail expected, got " + violations);
        ViolationDetail detail = violations.get(0);
        assertNull(detail.path());
        assertEquals(message, detail.message());
        assertEquals(type, detail.type());
    }

    private static String taskStatus(UUID taskId) {
        return pool.preparedQuery("SELECT status FROM workflow_tasks WHERE task_id = $1")
                .execute(Tuple.of(taskId))
                .map(rs -> rs.iterator().next().getString("status"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    private static long dedupRows(String key) {
        return pool.preparedQuery("SELECT count(*) FROM workflow_dedup WHERE kind = 'task-complete' AND key = $1")
                .execute(Tuple.of(key))
                .map(rs -> rs.iterator().next().getLong(0))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    /**
     * Asserts that the task's only recorded completion was rejected with a reason starting with the code, that its
     * history entry carries the same reason, and that the task is {@code OPEN} again with no owner.
     */
    private static void assertRejectedAndReopened(UUID taskId, String reasonCode) {
        Row command = pool.preparedQuery("SELECT state, last_error, id FROM workflow_commands WHERE task_id = $1")
                .execute(Tuple.of(taskId))
                .map(rs -> rs.iterator().next())
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        assertEquals("REJECTED", command.getString("state"));
        assertTrue(
                command.getString("last_error").startsWith(reasonCode),
                "reason must start with the code, was: " + command.getString("last_error"));
        assertEquals("OPEN", taskStatus(taskId), "a rejected completion must leave the task OPEN");
        assertNull(taskOwner(taskId), "a reopened task has no reservation owner");
        long rejections = pool.preparedQuery(
                        "SELECT count(*) FROM workflow_history WHERE (payload_json->>'commandId')::bigint = $1"
                                + " AND entry_type = 'COMMAND_REJECTED' AND payload_json->>'reason' LIKE $2")
                .execute(Tuple.of(command.getLong("id"), reasonCode + "%"))
                .map(rs -> rs.iterator().next().getLong(0))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        assertEquals(1L, rejections, "the rejection is logged with the same sanitized reason");
    }

    /** The reasons and history payloads of every command recorded for the task, as one string. */
    private static String recordedText(UUID taskId) {
        return pool.preparedQuery("SELECT coalesce(string_agg(c.last_error, ' '), '') || ' ' ||"
                        + " coalesce((SELECT string_agg(h.payload_json::text, ' ') FROM workflow_history h"
                        + " WHERE (h.payload_json->>'commandId')::bigint = ANY (SELECT id FROM workflow_commands WHERE task_id = $1)), '')"
                        + " AS text FROM workflow_commands c WHERE c.task_id = $1")
                .execute(Tuple.of(taskId))
                .map(rs -> rs.iterator().next().getString("text"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    private static Long taskOwner(UUID taskId) {
        return pool.preparedQuery("SELECT reservation_command_id FROM workflow_tasks WHERE task_id = $1")
                .execute(Tuple.of(taskId))
                .map(rs -> rs.iterator().next().getLong("reservation_command_id"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    private static long commandRows(UUID taskId) {
        return pool.preparedQuery("SELECT count(*) FROM workflow_commands WHERE task_id = $1")
                .execute(Tuple.of(taskId))
                .map(rs -> rs.iterator().next().getLong(0))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    /**
     * Captures events of the engine logger tree and restores the logger on {@link #close()}.
     */
    static final class CapturedLogs implements AutoCloseable {

        private final Logger logger;
        private final Level originalLevel;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private CapturedLogs(Logger logger, Level level) {
            this.logger = logger;
            this.originalLevel = logger.getLevel();
            logger.setLevel(level);
            appender.start();
            logger.addAppender(appender);
        }

        static CapturedLogs attach(Level level) {
            return new CapturedLogs((Logger) LoggerFactory.getLogger("dev.vertique.workflow.engine"), level);
        }

        void setLevel(Level level) {
            logger.setLevel(level);
        }

        void clear() {
            appender.list.clear();
        }

        List<ILoggingEvent> events() {
            return new ArrayList<>(appender.list);
        }

        /** Whether the event's throwable, or any cause in its chain, is a Vert.x {@link DecodeException}. */
        static boolean carriesDecodeException(ILoggingEvent event) {
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                if (DecodeException.class.getName().equals(proxy.getClassName())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
        }
    }
}
