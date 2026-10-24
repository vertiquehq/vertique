// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.events.WorkflowEventIntent;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.ops.StartCommand;
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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests asserting that the workflow events of a start are emitted by the first segment, which the executor
 * runs after the start is recorded: {@link WorkflowEventType#WORKFLOW_STARTED} with the idempotency key,
 * {@link WorkflowEventType#WORKFLOW_COMPLETED} and {@link WorkflowEventType#WORKFLOW_FAILED}.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PgWorkflowEngineStartEventsIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_start_events_it")
            .withMigration("classpath:db/migration/workflow");

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    /** Minimal workflow state for test plans. */
    record State(String id) {}

    /** Contract marker for complete-immediately plans. */
    interface CompleteContract {}

    /** Contract marker for fail-immediately plans. */
    interface FailContract {}

    /** A minimal workflow: starts → transitions immediately to the complete node. */
    static final WorkflowDefinition<State, CompleteContract> COMPLETE_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<CompleteContract> contract() {
            return CompleteContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "event-emit-complete";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(State.class, s -> s).initialStep("done").complete("done");
        }
    };

    /** A workflow that reaches a fail node with no compensable steps (instance stays {@code FAILED}). */
    static final WorkflowDefinition<State, FailContract> FAIL_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<FailContract> contract() {
            return FailContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "event-emit-fail";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(State.class, s -> s)
                    .initialStep("fail-step")
                    .fail("fail-step", "UNIT_TEST_ERROR", s -> "test failure");
        }
    };

    static Pool pool;
    static WorkflowEngineHandle engine;
    static final List<WorkflowSideEffectIntent> capturedIntents = new CopyOnWriteArrayList<>();

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
        WorkflowSideEffectRecorder<SqlClient> eventRecorder = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.WORKFLOW_EVENT;
            }

            @Override
            public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient sqlTx) {
                capturedIntents.add(intent);
                return Future.succeededFuture(RecorderResult.empty());
            }
        };
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(COMPLETE_DEF);
        registry.register(FAIL_DEF);
        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                new PgWorkflowInstanceRepository(pool, ex),
                new PgWorkflowHistoryRepository(pool, ex),
                new PgWorkflowDedupRepository(pool, ex),
                Set.of(eventRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(ex),
                new PgTaskStore(pool, ex),
                Clock.systemUTC());
        ctx.completeNow();
    }

    @BeforeEach
    void clear() {
        capturedIntents.clear();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    /** Records a start and returns once the executor ran it. */
    private static WorkflowInstanceId startAndAwait(StartCommand command) throws Exception {
        WorkflowInstanceId id =
                engine.start(command).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        engine.executionProbe()
                .awaitIdle(id, IDLE_TIMEOUT)
                .toCompletionStage()
                .toCompletableFuture()
                .get(15, TimeUnit.SECONDS);
        return id;
    }

    private static WorkflowEventIntent findEvent(WorkflowInstanceId id, WorkflowEventType type) {
        return capturedIntents.stream()
                .filter(i -> i.kind() == IntentKind.WORKFLOW_EVENT)
                .map(i -> (WorkflowEventIntent) i.payload())
                .filter(e -> e.eventType() == type && id.equals(e.workflowId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " event captured for " + id.value()));
    }

    @Test
    @DisplayName("WORKFLOW_STARTED: the first segment emits the event with the idempotencyKey attribute")
    void firstSegmentEmitsWorkflowStartedEvent() throws Exception {
        StartCommand cmd = new StartCommand(
                "event-emit-complete",
                CompleteContract.class.getName(),
                new State("s1"),
                "started-key-1",
                null,
                null,
                null,
                null,
                null);

        // When the start is recorded, nothing is emitted in the caller
        WorkflowInstanceId id =
                engine.start(cmd).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        engine.executionProbe()
                .awaitIdle(id, IDLE_TIMEOUT)
                .toCompletionStage()
                .toCompletableFuture()
                .get(15, TimeUnit.SECONDS);

        // Then the executor's first segment emitted it
        WorkflowEventIntent event = findEvent(id, WorkflowEventType.WORKFLOW_STARTED);
        assertEquals(WorkflowEventType.WORKFLOW_STARTED, event.eventType());
        assertEquals("started-key-1", event.attributes().get("idempotencyKey"));
    }

    @Test
    @DisplayName("WORKFLOW_COMPLETED: a start that reaches a complete node emits WORKFLOW_COMPLETED")
    void firstSegmentThatReachesACompleteNodeEmitsWorkflowCompletedEvent() throws Exception {
        WorkflowInstanceId id = startAndAwait(new StartCommand(
                "event-emit-complete",
                CompleteContract.class.getName(),
                new State("c1"),
                "completed-key-1",
                null,
                null,
                null,
                null,
                null));

        WorkflowEventIntent event = findEvent(id, WorkflowEventType.WORKFLOW_COMPLETED);
        assertEquals(WorkflowEventType.WORKFLOW_COMPLETED, event.eventType());
        assertNotNull(event.workflowId(), "workflowId must not be null");
        assertEquals("event-emit-complete", event.definitionId());
    }

    @Test
    @DisplayName("WORKFLOW_FAILED: a start that reaches a fail node emits WORKFLOW_FAILED with errorType")
    void firstSegmentThatReachesAFailNodeEmitsWorkflowFailedEvent() throws Exception {
        WorkflowInstanceId id = startAndAwait(new StartCommand(
                "event-emit-fail",
                FailContract.class.getName(),
                new State("f1"),
                "failed-key-1",
                null,
                null,
                null,
                null,
                null));

        WorkflowEventIntent event = findEvent(id, WorkflowEventType.WORKFLOW_FAILED);
        assertEquals(WorkflowEventType.WORKFLOW_FAILED, event.eventType());
        assertEquals("UNIT_TEST_ERROR", event.attributes().get("errorType"));
    }
}
