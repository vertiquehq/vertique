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
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.annotation.JsonProperty;
import dagger.Component;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.validation.ValidationModule;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
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
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for the opt-in payload constraint enforcement of the engine on the instance task
 * path, the branch task path and the start path.
 *
 * <p>When a {@link BeanValidator} is bound, the coerced payload is checked after coercion and before
 * any mutation. On a start the violation fails the future with {@link WorkflowInvalidPayloadException}
 * carrying one detail per violation whose path is an RFC 6901 pointer over wire names. A task completion is
 * recorded first and applied by the executor, so its violation is not returned to the caller: the command is
 * {@code REJECTED} with a sanitized reason that carries no payload value, key or message, the task is
 * {@code OPEN} again and a corrected completion under a fresh key applies. The tests use the real
 * Hibernate Validator obtained from {@link ValidationModule} through a test Dagger component, and two
 * engines that share the pool, the registry and the stores: one assembled with a bound validator and
 * one without. Each completion runs on the engine that started the instance.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PgWorkflowEnginePayloadConstraintIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_payload_constraint_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static DefaultWorkflowRegistry registry;
    static PgWorkflowInstanceRepository instances;
    static PgWorkflowHistoryRepository history;
    static PgWorkflowDedupRepository dedup;
    static PgBranchTokenRepository branches;
    static PgJoinStateRepository joins;
    static PgTimerStore timers;
    static PgTaskStore tasks;
    static BeanValidator beanValidator;
    static WorkflowEngineHandle validatingEngine;
    static WorkflowEngineHandle plainEngine;

    // --- Constants ---

    /** Value of a payload field that default constraint messages must never echo. */
    private static final String SENTINEL = "ZZSENTINEL";

    /** Value of a payload field that an application message interpolates by choice. */
    private static final String APPLICATION_SENTINEL = "YYSENTINEL";

    /** Caller-chosen map key that no detail field may contain. */
    private static final String KEY_SENTINEL = "ZZKEY";

    private static final String INSTANCE_DEFINITION_ID = "payload-constraint-instance";
    private static final String BRANCH_DEFINITION_ID = "payload-constraint-branch";
    private static final String START_DEFINITION_ID = "payload-constraint-start";

    private static final String DECISION_REJECTION = "invalid decision payload";

    private static final String ENABLED_LINE = "workflow payload constraint enforcement: enabled";
    private static final String DISABLED_LINE = "workflow payload constraint enforcement: disabled";

    // --- Domain types ---

    /** Workflow state of every fixture. */
    record State(String id) {}

    /** Decision payload with a size constraint on a wire-renamed property. */
    record Review(
            @JsonProperty("display_name") @Size(max = 3) String name) {}

    /** Element of the nested decision payload. */
    record Item(@NotBlank String name) {}

    /** Decision payload with a list and a map of constrained elements. */
    record Order(@Valid List<Item> items, @Valid Map<String, Item> byKey) {}

    /** Decision payload with a default-message constraint and an application-message constraint. */
    record Pair(
            @Size(max = 2) String a,

            @Size(max = 2, message = "custom ${validatedValue}")
            String b) {}

    /** Start payload with a required property. */
    record StartPayload(@NotBlank String requester) {}

    interface InstanceContract {}

    interface BranchContract {
        @WorkflowSignal("my-signal")
        Future<Void> mySignal(WorkflowInstanceId id, Object payload, @SignalDedupKey String dedupKey);
    }

    interface StartContract {}

    /** Test component that installs the validation module and exposes the real bean validator. */
    @Singleton
    @Component(modules = ValidationModule.class)
    interface ValidationComponent {
        BeanValidator beanValidator();
    }

    // --- Workflow definitions ---

    /** Instance fixture: one task step with the decisions {@code rename}, {@code nested} and {@code pair}. */
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
                    .decision("rename", Review.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .decision("nested", Order.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .decision("pair", Pair.class)
                    .onDecision((s, p) -> s)
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
                    .decision("rename", Review.class)
                    .onDecision((s, p) -> s)
                    .toStep("complete-a")
                    .decision("nested", Order.class)
                    .onDecision((s, p) -> s)
                    .toStep("complete-a")
                    .decision("pair", Pair.class)
                    .onDecision((s, p) -> s)
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

    /** Start fixture: the start type {@link StartPayload} carries a constraint. */
    static final WorkflowDefinition<State, StartContract> START_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<StartContract> contract() {
            return StartContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return START_DEFINITION_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartPayload.class, p -> new State(p.requester()))
                    .initialStep("done")
                    .complete("done");
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
        instances = new PgWorkflowInstanceRepository(pool, ex);
        history = new PgWorkflowHistoryRepository(pool, ex);
        dedup = new PgWorkflowDedupRepository(pool, ex);
        branches = new PgBranchTokenRepository(pool, ex);
        joins = new PgJoinStateRepository(pool, ex);
        timers = new PgTimerStore(ex);
        tasks = new PgTaskStore(pool, ex);

        // No plan validator, so the branch-owned task plan is accepted.
        registry = new DefaultWorkflowRegistry();
        registry.register(INSTANCE_DEF);
        registry.register(BRANCH_DEF);
        registry.register(START_DEF);
        // A start is checked against the start schema its row stores, so the rows the engine selects from carry it
        InMemoryWorkflowDefinitionRegistryStore.of(registry).withSchemas(PlanSchemas.producer());

        beanValidator = DaggerPgWorkflowEnginePayloadConstraintIT_ValidationComponent.create()
                .beanValidator();
        validatingEngine = engineWithValidator();
        plainEngine = engineWithoutValidator();
        ctx.completeNow();
    }

    /**
     * Empties the workflow tables. The executors of the engines that earlier tests assembled keep polling, and a poll
     * that holds one workflow table while it waits for another can deadlock with the truncate, which locks the tables in
     * a different order. The database picks one of the two as its victim, and the truncate has no work to lose, so it
     * is tried again.
     */
    @BeforeEach
    void truncateTables() {
        for (int attempt = 1; ; attempt++) {
            try {
                pool.query("TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
                                + " workflow_tasks, workflow_history, workflow_dedup, workflow_instances"
                                + " RESTART IDENTITY CASCADE")
                        .execute()
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join();
                return;
            } catch (java.util.concurrent.CompletionException e) {
                boolean deadlock =
                        e.getCause() instanceof io.vertx.pgclient.PgException pg && "40P01".equals(pg.getSqlState());
                if (!deadlock || attempt == 5) {
                    throw e;
                }
            }
        }
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
    // Tests
    // =========================================================================

    @Test
    @DisplayName(
            "a size violation on a wire-renamed property is rejected on both completion paths and a fresh key applies")
    void sizeViolationOnAWireRenamedPropertyFailsOnBothCompletionPaths() {
        for (String definitionId : List.of(INSTANCE_DEFINITION_ID, BRANCH_DEFINITION_ID)) {
            // Given: a validator-bound engine and an open task whose decision carries a size limit
            UUID taskId = startAndFindOpenTask(validatingEngine, definitionId);
            String key = "idem-" + UUID.randomUUID();

            // When: it is completed with a value over the limit under the wire name
            TaskMutationResult recorded =
                    completeSettled(validatingEngine, taskId, "rename", Map.of("display_name", "toolong"), key);

            // Then: the completion was recorded and rejected with a sanitized reason, and the task is open again
            assertEquals(TaskMutationResult.APPLIED, recorded, definitionId);
            assertRejectedAndReopened(taskId, definitionId);
            assertFalse(recordedText(taskId).contains("toolong"), definitionId + ": the reason carries no value");
            assertEquals(1L, dedupRows(key), definitionId + ": a recorded attempt holds its claim");

            // When: a valid payload is sent under a fresh key
            TaskMutationResult retry = completeSettled(
                    validatingEngine, taskId, "rename", Map.of("display_name", "abc"), "idem-" + UUID.randomUUID());

            // Then: the retry is applied
            assertEquals(TaskMutationResult.APPLIED, retry, definitionId);
            assertEquals("COMPLETED", taskStatus(taskId), definitionId);
        }
    }

    @Test
    @DisplayName("without a bound validator the same payload completes")
    void withoutABoundValidatorTheSamePayloadCompletes() {
        for (String definitionId : List.of(INSTANCE_DEFINITION_ID, BRANCH_DEFINITION_ID)) {
            // Given: an engine assembled without a validator and an open task with a size limit
            UUID taskId = startAndFindOpenTask(plainEngine, definitionId);

            // When: it is completed with the value that a bound validator rejects
            TaskMutationResult result = completeSettled(
                    plainEngine, taskId, "rename", Map.of("display_name", "toolong"), "idem-" + UUID.randomUUID());

            // Then: the completion is applied and the task is completed
            assertEquals(TaskMutationResult.APPLIED, result, definitionId);
            assertEquals("COMPLETED", taskStatus(taskId), definitionId);
        }
    }

    @Test
    @DisplayName("nested violations are rejected without echoing a caller-chosen map key")
    void nestedViolationPathIsAnIndexedPointer() {
        for (String definitionId : List.of(INSTANCE_DEFINITION_ID, BRANCH_DEFINITION_ID)) {
            // Given: an open task whose decision holds a list and a map of constrained items
            UUID listTask = startAndFindOpenTask(validatingEngine, definitionId);

            // When: a list item violates its constraint
            completeSettled(
                    validatingEngine,
                    listTask,
                    "nested",
                    Map.of("items", List.of(Map.of("name", "")), "byKey", Map.of()),
                    "idem-" + UUID.randomUUID());

            // Then: the completion is rejected and the task is open again
            assertRejectedAndReopened(listTask, definitionId);

            // Given: a second open task
            UUID mapTask = startAndFindOpenTask(validatingEngine, definitionId);

            // When: a map value under a caller-chosen key violates its constraint
            completeSettled(
                    validatingEngine,
                    mapTask,
                    "nested",
                    Map.of("items", List.of(Map.of("name", "ok")), "byKey", Map.of(KEY_SENTINEL, Map.of("name", ""))),
                    "idem-" + UUID.randomUUID());

            // Then: it is rejected too, and nothing recorded contains the key
            assertRejectedAndReopened(mapTask, definitionId);
            assertFalse(
                    recordedText(mapTask).contains(KEY_SENTINEL),
                    definitionId + ": neither the reason nor the history may carry the key");
        }
    }

    @Test
    @DisplayName("a completion with many violations is rejected once, with a bounded reason")
    void aCompletionWithManyViolationsReportsEveryOne() {
        // Given: a validator-bound engine and an open task whose decision holds a list of constrained items
        UUID taskId = startAndFindOpenTask(validatingEngine, INSTANCE_DEFINITION_ID);
        List<Map<String, String>> blankItems = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            blankItems.add(Map.of("name", ""));
        }

        // When: it is completed with 150 items that each violate their constraint
        completeSettled(
                validatingEngine,
                taskId,
                "nested",
                Map.of("items", blankItems, "byKey", Map.of()),
                "idem-" + UUID.randomUUID());

        // Then: one command is rejected, its reason stays within the column bound and the task is open again
        assertRejectedAndReopened(taskId, INSTANCE_DEFINITION_ID);
        assertEquals(1L, commandRows(taskId), "one recorded completion");
        assertTrue(lastError(taskId).length() <= 4000, "the reason is bounded, not one line per violation");
    }

    @Test
    @DisplayName("a rejected completion carries neither the default nor the application message values")
    void defaultMessagesAreValueFreeAndApplicationMessagesPassThrough() {
        // Given: an open task whose decision has a default-message and an application-message constraint
        UUID taskId = startAndFindOpenTask(validatingEngine, INSTANCE_DEFINITION_ID);

        // When: both properties violate their limits with sentinel values
        completeSettled(
                validatingEngine,
                taskId,
                "pair",
                Map.of("a", SENTINEL, "b", APPLICATION_SENTINEL),
                "idem-" + UUID.randomUUID());

        // Then: the completion is rejected, and neither the reason nor the history echoes a submitted value or an
        // application message
        assertRejectedAndReopened(taskId, INSTANCE_DEFINITION_ID);
        assertFalse(recordedText(taskId).contains(SENTINEL), "nothing recorded may carry a submitted value");
        assertFalse(recordedText(taskId).contains(APPLICATION_SENTINEL), "an application message is not echoed");
    }

    @Test
    @DisplayName("a start payload constraint violation fails the start")
    void startPayloadConstraintViolationFails() {
        // Given: a validator-bound engine and a start type with a required property
        String key = "s1";

        // When: the start payload leaves the property blank
        WorkflowInvalidPayloadException rejection = assertInvalidStart(Map.of("requester", ""), key);

        // Then: the stored schema refuses it at the property, no instance row and no claim
        assertTrue(
                rejection.getMessage().startsWith("Start of definition '" + START_DEFINITION_ID + "' version 1"),
                "the refusal must name the definition version, was: " + rejection.getMessage());
        assertTrue(rejection.getMessage().contains("/requester"), "the refusal must name the property");
        assertEquals(0L, instanceRows(START_DEFINITION_ID), "a rejected start must not create an instance");
        assertEquals(0L, startDedupRows(key), "a rejected start must not retain the claim");

        // When: the same key is retried with a valid payload
        WorkflowInstanceId created = start(validatingEngine, START_DEFINITION_ID, Map.of("requester", "ann"), key);

        // Then: the retry creates the instance and holds the claim
        assertNotNull(created, "the corrected retry must create an instance");
        assertEquals(1L, instanceRows(START_DEFINITION_ID));
        assertEquals(1L, startDedupRows(key), "only the successful attempt may hold the claim");
    }

    @Test
    @DisplayName("the engine logs once at INFO whether payload constraint enforcement is enabled")
    void enforcementStateIsLoggedOnceAtInfoPerEngine() {
        // Given: an engine logger capture at INFO
        logs = CapturedLogs.attach(Level.INFO);

        // When: an engine is assembled with a bound validator
        engineWithValidator();

        // Then: exactly one INFO line says enabled and none says disabled
        assertEquals(1L, logs.infoLines(ENABLED_LINE), "one enabled line expected");
        assertEquals(0L, logs.infoLines(DISABLED_LINE));

        // When: an engine is assembled with an empty optional
        logs.clear();
        PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instances,
                history,
                dedup,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timers,
                tasks,
                Clock.systemUTC(),
                branches,
                joins,
                null,
                Optional.empty(),
                Optional.empty());

        // Then: exactly one INFO line says disabled and none says enabled
        assertEquals(1L, logs.infoLines(DISABLED_LINE), "one disabled line expected");
        assertEquals(0L, logs.infoLines(ENABLED_LINE));

        // When: an engine is assembled through the overload that predates the validator
        logs.clear();
        engineWithoutValidator();

        // Then: exactly one INFO line says disabled and none says enabled
        assertEquals(1L, logs.infoLines(DISABLED_LINE), "one disabled line expected");
        assertEquals(0L, logs.infoLines(ENABLED_LINE));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Assembles an engine with the real bean validator bound. */
    private static WorkflowEngineHandle engineWithValidator() {
        return PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instances,
                history,
                dedup,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timers,
                tasks,
                Clock.systemUTC(),
                branches,
                joins,
                null,
                Optional.empty(),
                Optional.of(beanValidator));
    }

    /** Assembles an engine through the overload that predates the validator, so enforcement is off. */
    private static WorkflowEngineHandle engineWithoutValidator() {
        return PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instances,
                history,
                dedup,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timers,
                tasks,
                Clock.systemUTC(),
                branches,
                joins);
    }

    /**
     * Starts the definition and returns the id of its single open task.
     *
     * @param starter the engine that starts the instance and applies its completions
     * @param definitionId the fixture definition to start
     * @return the open task id
     */
    private UUID startAndFindOpenTask(WorkflowEngineHandle starter, String definitionId) {
        String id = UUID.randomUUID().toString();
        WorkflowInstanceId workflowId = start(starter, definitionId, new State(id), "start-" + id);
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
     * Starts the definition on an engine and waits for the result.
     *
     * @param starter the engine to start on
     * @param definitionId the definition to start
     * @param payload the raw start payload
     * @param key the start idempotency key
     * @return the created instance id; fails with {@link CompletionException} when the start fails
     */
    private static WorkflowInstanceId start(
            WorkflowEngineHandle starter, String definitionId, Object payload, String key) {
        return starter.start(new StartCommand(
                        definitionId, contractNameOf(definitionId), payload, key, null, null, null, null, null))
                .compose(started -> starter.executionProbe()
                        .awaitIdle(started, IDLE_TIMEOUT)
                        .map(idle -> started))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
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
            case START_DEFINITION_ID -> StartContract.class.getName();
            default -> throw new IllegalArgumentException("unknown fixture definition: " + definitionId);
        };
    }

    private static WorkflowInvalidPayloadException assertInvalidStart(Object payload, String key) {
        CompletionException failure = assertThrows(
                CompletionException.class, () -> start(validatingEngine, START_DEFINITION_ID, payload, key));
        return assertInstanceOf(
                WorkflowInvalidPayloadException.class,
                failure.getCause(),
                "start must fail with WorkflowInvalidPayloadException");
    }

    private static Future<TaskMutationResult> complete(
            WorkflowEngineHandle engine, UUID taskId, String decision, Object payload, String key) {
        TaskCompletionCommand cmd =
                new TaskCompletionCommand(taskId, decision, payload, key, new WorkflowActor.User("alice"), null);
        return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                .compose(result ->
                        engine.executionProbe().awaitAllIdle(IDLE_TIMEOUT).map(idle -> result));
    }

    /**
     * Records a completion on an engine, waits for its executor to settle it and returns the recorded result.
     *
     * @param engine the engine that started the task's instance
     * @param taskId the open task
     * @param decision the decision name
     * @param payload the raw payload
     * @param key the idempotency key
     * @return the result of recording the completion
     */
    private static TaskMutationResult completeSettled(
            WorkflowEngineHandle engine, UUID taskId, String decision, Object payload, String key) {
        return complete(engine, taskId, decision, payload, key)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    /**
     * Asserts that the task's only recorded completion was rejected with a sanitized reason, that its history entry
     * carries the same reason, and that the task is {@code OPEN} again with no reservation owner.
     */
    private static void assertRejectedAndReopened(UUID taskId, String context) {
        Row command = pool.preparedQuery("SELECT id, state, last_error FROM workflow_commands WHERE task_id = $1"
                        + " ORDER BY id DESC LIMIT 1")
                .execute(Tuple.of(taskId))
                .map(rs -> rs.iterator().next())
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        assertEquals("REJECTED", command.getString("state"), context);
        assertTrue(command.getString("last_error").startsWith("DETERMINISTIC_FAILURE"), context);
        assertEquals("OPEN", taskStatus(taskId), context + ": a rejected completion must leave the task OPEN");
        assertNull(reservationOwner(taskId), context);
        assertEquals(
                1L,
                count(
                        "SELECT count(*) FROM workflow_history WHERE (payload_json->>'commandId')::bigint = $1"
                                + " AND entry_type = 'COMMAND_REJECTED'",
                        Tuple.of(command.getLong("id"))),
                context + ": the rejection is logged");
    }

    /** The reasons and history payloads of every command recorded for the task, as one string. */
    private static String recordedText(UUID taskId) {
        return pool.preparedQuery("SELECT coalesce(string_agg(last_error, ' '), '') || ' ' ||"
                        + " coalesce((SELECT string_agg(h.payload_json::text, ' ') FROM workflow_history h"
                        + " WHERE (h.payload_json->>'commandId')::bigint IN (SELECT id FROM workflow_commands WHERE task_id = $1)), '') AS text"
                        + " FROM workflow_commands WHERE task_id = $1")
                .execute(Tuple.of(taskId))
                .map(rs -> rs.iterator().next().getString("text"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    private static String lastError(UUID taskId) {
        return pool.preparedQuery(
                        "SELECT last_error FROM workflow_commands WHERE task_id = $1 ORDER BY id DESC LIMIT 1")
                .execute(Tuple.of(taskId))
                .map(rs -> rs.iterator().next().getString("last_error"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    private static long commandRows(UUID taskId) {
        return count("SELECT count(*) FROM workflow_commands WHERE task_id = $1", Tuple.of(taskId));
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
        return count("SELECT count(*) FROM workflow_dedup WHERE kind = 'task-complete' AND key = $1", Tuple.of(key));
    }

    private static long instanceRows(String definitionId) {
        return count("SELECT count(*) FROM workflow_instances WHERE definition_id = $1", Tuple.of(definitionId));
    }

    private static long startDedupRows(String key) {
        return count(
                "SELECT count(*) FROM workflow_dedup WHERE kind = 'start' AND dedup_namespace = $1 AND key = $2",
                Tuple.of(START_DEFINITION_ID, key));
    }

    private static long count(String sql, Tuple params) {
        return pool.preparedQuery(sql)
                .execute(params)
                .map(rs -> rs.iterator().next().getLong(0))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    private static Long reservationOwner(UUID taskId) {
        return pool.preparedQuery("SELECT reservation_command_id FROM workflow_tasks WHERE task_id = $1")
                .execute(Tuple.of(taskId))
                .map(rs -> rs.iterator().next().getLong("reservation_command_id"))
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

        void clear() {
            appender.list.clear();
        }

        List<ILoggingEvent> events() {
            return new ArrayList<>(appender.list);
        }

        /** Counts the events at exactly INFO whose formatted message contains {@code text}. */
        long infoLines(String text) {
            return events().stream()
                    .filter(event -> event.getLevel() == Level.INFO)
                    .filter(event -> event.getFormattedMessage().contains(text))
                    .count();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
        }
    }
}
