// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.annotation.JsonProperty;
import dagger.Component;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.core.validation.ViolationPathForm;
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
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
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
 * any mutation; a violation fails the future with {@link WorkflowInvalidPayloadException} carrying
 * one detail per violation whose path is an RFC 6901 pointer over wire names. The tests use the real
 * Hibernate Validator obtained from {@link ValidationModule} through a test Dagger component, and two
 * engines that share the pool, the registry and the stores: one assembled with a bound validator and
 * one without. Details are matched by path, never by position.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PgWorkflowEnginePayloadConstraintIT {

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
    private static final String START_REJECTION = "invalid start payload";

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

        beanValidator = DaggerPgWorkflowEnginePayloadConstraintIT_ValidationComponent.create()
                .beanValidator();
        validatingEngine = engineWithValidator();
        plainEngine = engineWithoutValidator();
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables() {
        pool.query("TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
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
    // Tests
    // =========================================================================

    @Test
    @DisplayName("a size violation on a wire-renamed property fails on both completion paths")
    void sizeViolationOnAWireRenamedPropertyFailsOnBothCompletionPaths() {
        for (String definitionId : List.of(INSTANCE_DEFINITION_ID, BRANCH_DEFINITION_ID)) {
            // Given: a validator-bound engine and an open task whose decision carries a size limit
            UUID taskId = startAndFindOpenTask(definitionId);
            String key = "idem-" + UUID.randomUUID();

            // When: it is completed with a value over the limit under the wire name
            WorkflowInvalidPayloadException rejection =
                    completeExpectingViolations(taskId, "rename", Map.of("display_name", "toolong"), key);

            // Then: one size detail at the wire-name pointer, and the task is still open
            assertEquals(DECISION_REJECTION, rejection.getMessage(), definitionId);
            assertEquals(1, rejection.violations().size(), definitionId + ": " + rejection.violations());
            ViolationDetail detail = detailAt(rejection, "/display_name");
            assertEquals("size", detail.type(), definitionId);
            assertEquals("OPEN", taskStatus(taskId), definitionId + ": a rejected completion must leave the task OPEN");
            assertEquals(0L, dedupRows(key), definitionId + ": a rejected attempt must not leave a dedup claim");

            // When: the same key is retried within the limit
            TaskMutationResult retry = complete(validatingEngine, taskId, "rename", Map.of("display_name", "abc"), key)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();

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
            UUID taskId = startAndFindOpenTask(definitionId);

            // When: it is completed with the value that a bound validator rejects
            TaskMutationResult result = complete(
                            plainEngine,
                            taskId,
                            "rename",
                            Map.of("display_name", "toolong"),
                            "idem-" + UUID.randomUUID())
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();

            // Then: the completion is applied and the task is completed
            assertEquals(TaskMutationResult.APPLIED, result, definitionId);
            assertEquals("COMPLETED", taskStatus(taskId), definitionId);
        }
    }

    @Test
    @DisplayName("a nested violation path is an indexed pointer and a map violation ends at the map property")
    void nestedViolationPathIsAnIndexedPointer() {
        for (String definitionId : List.of(INSTANCE_DEFINITION_ID, BRANCH_DEFINITION_ID)) {
            // Given: an open task whose decision holds a list and a map of constrained items
            UUID listTask = startAndFindOpenTask(definitionId);

            // When: a list item violates its constraint
            WorkflowInvalidPayloadException listRejection = completeExpectingViolations(
                    listTask,
                    "nested",
                    Map.of("items", List.of(Map.of("name", "")), "byKey", Map.of()),
                    "idem-" + UUID.randomUUID());

            // Then: one required detail at the indexed pointer
            assertEquals(1, listRejection.violations().size(), definitionId + ": " + listRejection.violations());
            assertEquals("required", detailAt(listRejection, "/items/0/name").type(), definitionId);
            assertEquals("OPEN", taskStatus(listTask), definitionId);

            // Given: a second open task
            UUID mapTask = startAndFindOpenTask(definitionId);

            // When: a map value under a caller-chosen key violates its constraint
            WorkflowInvalidPayloadException mapRejection = completeExpectingViolations(
                    mapTask,
                    "nested",
                    Map.of("items", List.of(Map.of("name", "ok")), "byKey", Map.of(KEY_SENTINEL, Map.of("name", ""))),
                    "idem-" + UUID.randomUUID());

            // Then: one required detail at the map property, and no field contains the key
            assertEquals(1, mapRejection.violations().size(), definitionId + ": " + mapRejection.violations());
            assertEquals("required", detailAt(mapRejection, "/byKey").type(), definitionId);
            assertFalse(
                    mapRejection.getMessage().contains(KEY_SENTINEL),
                    definitionId + ": message must not carry the key");
            assertFalse(
                    mapRejection.violations().toString().contains(KEY_SENTINEL),
                    definitionId + ": no detail field may carry the key");
            assertEquals("OPEN", taskStatus(mapTask), definitionId);
        }
    }

    @Test
    @DisplayName("a completion with many violations is capped at 100 followed by one truncation detail")
    void aCompletionWithManyViolationsIsCappedWithOneTruncationDetail() {
        // Given: a validator-bound engine and an open task whose decision holds a list of constrained items
        UUID taskId = startAndFindOpenTask(INSTANCE_DEFINITION_ID);
        List<Map<String, String>> blankItems = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            blankItems.add(Map.of("name", ""));
        }

        // When: it is completed with 150 items that each violate their constraint
        WorkflowInvalidPayloadException rejection = completeExpectingViolations(
                taskId, "nested", Map.of("items", blankItems, "byKey", Map.of()), "idem-" + UUID.randomUUID());

        // Then: the rejection carries 101 details
        assertEquals(DECISION_REJECTION, rejection.getMessage());
        List<ViolationDetail> details = rejection.violations();
        assertEquals(101, details.size(), "100 real details and one truncation detail expected");

        // And: the last one is the fixed truncation detail
        assertEquals(ViolationDetail.unlocated("further violations omitted", "truncated"), details.get(100));

        // And: the 100 before it are real violations of list items, each at its own pointer
        List<ViolationDetail> real = details.subList(0, 100);
        for (ViolationDetail detail : real) {
            assertEquals("required", detail.type());
            assertEquals(ViolationPathForm.JSON_POINTER, detail.form(), "a located detail states a pointer");
            assertTrue(detail.path().startsWith("/items/"), "a real detail addresses a list item: " + detail.path());
        }
        assertEquals(
                100L,
                real.stream().map(ViolationDetail::path).distinct().count(),
                "the real details address distinct items");
        assertEquals("OPEN", taskStatus(taskId), "a rejected completion must leave the task OPEN");
    }

    @Test
    @DisplayName("default messages are value-free and application messages pass through")
    void defaultMessagesAreValueFreeAndApplicationMessagesPassThrough() {
        // Given: an open task whose decision has a default-message and an application-message constraint
        UUID taskId = startAndFindOpenTask(INSTANCE_DEFINITION_ID);

        // When: both properties violate their limits with sentinel values
        WorkflowInvalidPayloadException rejection = completeExpectingViolations(
                taskId, "pair", Map.of("a", SENTINEL, "b", APPLICATION_SENTINEL), "idem-" + UUID.randomUUID());

        // Then: the exception message is the frozen one
        assertEquals(DECISION_REJECTION, rejection.getMessage());
        assertEquals(2, rejection.violations().size(), "one detail per violation: " + rejection.violations());

        // And: the default message does not carry the submitted value
        ViolationDetail defaultDetail = detailAt(rejection, "/a");
        assertEquals("size", defaultDetail.type());
        assertFalse(defaultDetail.message().contains(SENTINEL), "a default message must be value-free");

        // And: the application message is passed through unchanged, interpolation included
        ViolationDetail applicationDetail = detailAt(rejection, "/b");
        assertEquals("custom " + APPLICATION_SENTINEL, applicationDetail.message());
    }

    @Test
    @DisplayName("a start payload constraint violation fails the start")
    void startPayloadConstraintViolationFails() {
        // Given: a validator-bound engine and a start type with a required property
        String key = "s1";

        // When: the start payload leaves the property blank
        WorkflowInvalidPayloadException rejection = assertInvalidStart(Map.of("requester", ""), key);

        // Then: one required detail at the property pointer, no instance row and no claim
        assertEquals(START_REJECTION, rejection.getMessage());
        assertEquals(1, rejection.violations().size(), "exactly one detail expected: " + rejection.violations());
        assertEquals("required", detailAt(rejection, "/requester").type());
        assertEquals(0L, instanceRows(START_DEFINITION_ID), "a rejected start must not create an instance");
        assertEquals(0L, startDedupRows(key), "a rejected start must not retain the claim");

        // When: the same key is retried with a valid payload
        WorkflowInstanceId created = start(START_DEFINITION_ID, Map.of("requester", "ann"), key);

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
     * @param definitionId the fixture definition to start
     * @return the open task id
     */
    private UUID startAndFindOpenTask(String definitionId) {
        String id = UUID.randomUUID().toString();
        WorkflowInstanceId workflowId = start(definitionId, new State(id), "start-" + id);
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
     * Starts the definition on the validator-bound engine and waits for the result.
     *
     * @param definitionId the definition to start
     * @param payload the raw start payload
     * @param key the start idempotency key
     * @return the created instance id; fails with {@link CompletionException} when the start fails
     */
    private static WorkflowInstanceId start(String definitionId, Object payload, String key) {
        return validatingEngine
                .start(new StartCommand(
                        definitionId, contractNameOf(definitionId), payload, key, null, null, null, null, null))
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
        CompletionException failure =
                assertThrows(CompletionException.class, () -> start(START_DEFINITION_ID, payload, key));
        return assertInstanceOf(
                WorkflowInvalidPayloadException.class,
                failure.getCause(),
                "start must fail with WorkflowInvalidPayloadException");
    }

    private static Future<TaskMutationResult> complete(
            WorkflowEngineHandle engine, UUID taskId, String decision, Object payload, String key) {
        TaskCompletionCommand cmd =
                new TaskCompletionCommand(taskId, decision, payload, key, new WorkflowActor.User("alice"), null);
        return pool.withTransaction(tx -> engine.taskCompleted(cmd, tx));
    }

    /**
     * Completes the task on the validator-bound engine and returns the rejection the future fails with.
     *
     * @param taskId the open task
     * @param decision the decision name
     * @param payload the raw payload
     * @param key the idempotency key
     * @return the rejection; the assertion fails when the future fails with any other type
     */
    private static WorkflowInvalidPayloadException completeExpectingViolations(
            UUID taskId, String decision, Object payload, String key) {
        CompletionException failure =
                assertThrows(CompletionException.class, () -> complete(validatingEngine, taskId, decision, payload, key)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());
        return assertInstanceOf(
                WorkflowInvalidPayloadException.class,
                failure.getCause(),
                "completion must fail with WorkflowInvalidPayloadException");
    }

    /** Returns the single detail whose path equals {@code path}, whatever the detail order. */
    private static ViolationDetail detailAt(WorkflowInvalidPayloadException rejection, String path) {
        List<ViolationDetail> matching = rejection.violations().stream()
                .filter(detail -> path.equals(detail.path()))
                .toList();
        assertEquals(1, matching.size(), "exactly one detail at " + path + " expected in " + rejection.violations());
        return matching.get(0);
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
                "SELECT count(*) FROM workflow_dedup WHERE kind = 'start' AND scope = $1 AND key = $2",
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
