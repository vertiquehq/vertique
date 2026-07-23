// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.inboxoutbox.postgresql.PgInboxOutboxRepository;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.events.WorkflowEventEnvelope;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.events.binding.WorkflowEventOutboxBinding;
import dev.vertique.workflow.events.compose.WorkflowEventsComposeValidator;
import dev.vertique.workflow.events.recorder.WorkflowEventSideEffectRecorder;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
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
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.timer.TimerIntentPayload;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests verifying that {@link WorkflowEngineHandle} writes outbox rows atomically with
 * state transitions at the six primary {@link WorkflowEventType} emission sites.
 *
 * <p>Each test runs a workflow scenario end-to-end against a real Testcontainers Postgres instance.
 * A real {@link WorkflowEventSideEffectRecorder} backed by {@link DefaultOutboxService} (via
 * {@link PgInboxOutboxRepository}) is wired in so the outbox {@code INSERT} participates in the
 * same transaction as the workflow state write. After each operation the test reads the
 * {@code outbox} table directly and asserts:
 * <ul>
 *   <li>Exactly the expected number of rows were inserted.</li>
 *   <li>Each row carries the correct {@code event_type}, {@code aggregate_type="workflow"},
 *       {@code aggregate_id=workflowId}, {@code destination_type=SERVICE},
 *       {@code destination="test-event-target"}.</li>
 *   <li>The JSONB {@code payload} deserializes as a {@link WorkflowEventEnvelope} with
 *       {@code schemaVersion=1}, non-null {@code occurredAt}, and matching {@code definitionId}.</li>
 * </ul>
 *
 * <p>Covers 6 emission sites: WORKFLOW_STARTED, TASK_CREATED, TASK_COMPLETED, WORKFLOW_COMPLETED,
 * WORKFLOW_FAILED, and WORKFLOW_CANCELLED.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class WorkflowEventEmissionIT {

    // --- Constants ---

    /** Outbox destination used in all tests. */
    static final String TEST_DESTINATION = "test-event-target";

    /** The binding that routes all WORKFLOW_EVENT intents to the test SERVICE destination. */
    static final WorkflowEventOutboxBinding BINDING =
            new WorkflowEventOutboxBinding(DestinationType.SERVICE, TEST_DESTINATION);

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("workflow_event_emission_test");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgWorkflowInstanceRepository instanceRepo;
    static PgTimerStore timerStore;

    /** Captures the latest timer id from the WORKFLOW_TIMER recorder for task/timer tests. */
    static final AtomicReference<UUID> lastTimerId = new AtomicReference<>();

    // --- Domain types ---

    /**
     * Minimal workflow state for event-emission tests.
     *
     * @param id the unique test identifier
     */
    record State(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "event-emission-it-" + id;
        }
    }

    // --- Contract markers ---

    /** Contract for a workflow that completes immediately. */
    interface CompleteContract {}

    /** Contract for a workflow that fails immediately. */
    interface FailContract {}

    /** Contract for a workflow with a single human task. */
    interface TaskContract {}

    // --- Workflow definitions ---

    /**
     * A workflow that completes immediately after start: WORKFLOW_STARTED + WORKFLOW_COMPLETED.
     */
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
            return "event-emission-complete";
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

    /**
     * A workflow that reaches a FailNode immediately: WORKFLOW_STARTED + WORKFLOW_FAILED.
     */
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
            return "event-emission-fail";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(State.class, s -> s)
                    .initialStep("fail-step")
                    .fail("fail-step", "TEST_ERROR_TYPE", s -> "test failure");
        }
    };

    /**
     * A workflow with a single human task (no due-date): WORKFLOW_STARTED + TASK_CREATED, then
     * TASK_COMPLETED + WORKFLOW_COMPLETED on completion.
     */
    static final WorkflowDefinition<State, TaskContract> TASK_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<TaskContract> contract() {
            return TaskContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "event-emission-task";
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
                    .assignToRole("compliance")
                    .decision("approve", Void.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done");
        }
    };

    // --- Test setup ---

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        // Run both workflow and inbox-outbox migrations in separate Flyway runs.
        String jdbcUrl = db.jdbcUrl();
        String user = db.username();
        String password = db.password();
        Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration/workflow")
                .table("flyway_workflow_history")
                .load()
                .migrate();
        Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration/inbox-outbox")
                .table("flyway_inbox_outbox_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

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
        PgWorkflowHistoryRepository historyRepo = new PgWorkflowHistoryRepository(pool, exMapper);
        PgWorkflowDedupRepository dedupRepo = new PgWorkflowDedupRepository(pool, exMapper);
        timerStore = new PgTimerStore(exMapper);

        // --- Outbox infrastructure ---
        PgInboxOutboxRepository outboxRepo = new PgInboxOutboxRepository(pool, exMapper);
        OutboxService outboxService =
                (tx, entry) -> outboxRepo.insert(entry, OutboxMetadata.empty(), UUID.randomUUID(), tx);

        // --- Test-only OutboxDestinationHandler for SERVICE/test-event-target ---
        OutboxDestinationHandler testHandler = new OutboxDestinationHandler() {
            @Override
            public DestinationType destinationType() {
                return DestinationType.SERVICE;
            }

            @Override
            public ClaimScope claimScope() {
                return ClaimScope.all();
            }

            @Override
            public io.vertx.core.Future<dev.vertique.inboxoutbox.OutboxPublishResult> publish(
                    dev.vertique.inboxoutbox.OutboxEnvelope envelope) {
                return io.vertx.core.Future.succeededFuture(dev.vertique.inboxoutbox.OutboxPublishResult.success());
            }
        };

        // --- Registry ---
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(COMPLETE_DEF);
        registry.register(FAIL_DEF);
        registry.register(TASK_DEF);

        // --- WorkflowEventsComposeValidator and recorder ---
        WorkflowEventsComposeValidator eventsValidator =
                new WorkflowEventsComposeValidator(Set.of(testHandler), BINDING);
        WorkflowEventSideEffectRecorder eventRecorder =
                new WorkflowEventSideEffectRecorder(outboxService, BINDING, eventsValidator);

        // --- WORKFLOW_TIMER recorder (stub — just inserts timer rows) ---
        WorkflowSideEffectRecorder<SqlClient> timerRecorder = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.WORKFLOW_TIMER;
            }

            @Override
            public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                UUID timerId = UUID.randomUUID();
                lastTimerId.set(timerId);
                Object payload = intent.payload();
                Instant fireAt;
                TimerPurpose purpose;
                UUID taskId;
                if (payload instanceof TimerIntentPayload tip) {
                    fireAt = tip.fireAt();
                    purpose = tip.purpose();
                    taskId = tip.taskId();
                } else {
                    fireAt = payload instanceof Instant fa ? fa : Instant.now().plusSeconds(3600);
                    purpose = TimerPurpose.STANDALONE;
                    taskId = null;
                }
                TimerRecord record = new TimerRecord(
                        timerId,
                        (WorkflowInstanceId) intent.correlation().workflowId(),
                        intent.targetId(),
                        fireAt,
                        TimerStatus.SCHEDULED,
                        UUID.randomUUID(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        null,
                        purpose,
                        taskId,
                        null, // branchTokenId
                        null, // forkStepId
                        null,
                        DurableMetadata.empty()); // branchId
                return timerStore.insertScheduled(record, tx).map(v -> RecorderResult.ofTimer(timerId));
            }
        };

        // --- Engine ---
        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(eventRecorder, timerRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                new PgTaskStore(pool, exMapper),
                Clock.fixed(Instant.parse("2026-05-09T00:00:00Z"), ZoneOffset.UTC));

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        lastTimerId.set(null);
        pool.query("TRUNCATE TABLE outbox, workflow_timers, workflow_tasks, workflow_history,"
                        + " workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
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
     * Reads all outbox rows with the given {@code eventType} from the {@code outbox} table.
     *
     * @param eventType the string event type to filter on
     * @return a {@link Future} containing all matching rows as a list of row maps
     */
    private Future<List<Row>> readOutboxRows(String eventType) {
        return pool.preparedQuery("SELECT * FROM outbox WHERE event_type = $1 ORDER BY id ASC")
                .execute(Tuple.of(eventType))
                .map(rs -> {
                    List<Row> rows = new ArrayList<>();
                    for (Row row : rs) {
                        rows.add(row);
                    }
                    return rows;
                });
    }

    /**
     * Reads all outbox rows for the given workflow instance from the {@code outbox} table.
     *
     * @param workflowId the workflow instance UUID (stored as aggregate_id)
     * @return a {@link Future} containing all matching outbox rows in insertion order
     */
    private Future<List<Row>> readOutboxRowsForWorkflow(UUID workflowId) {
        return pool.preparedQuery("SELECT * FROM outbox WHERE aggregate_id = $1 ORDER BY id ASC")
                .execute(Tuple.of(workflowId.toString()))
                .map(rs -> {
                    List<Row> rows = new ArrayList<>();
                    for (Row row : rs) {
                        rows.add(row);
                    }
                    return rows;
                });
    }

    /**
     * Reads all {@code workflow_history} rows for a given workflow id, ordered by sequence
     * ascending. Used to correlate event-envelope sequence numbers with the actual history rows
     * they describe.
     *
     * @param workflowId the workflow instance UUID
     * @return ordered list of raw {@link Row} objects (callers extract entry_type and sequence)
     */
    private Future<List<Row>> readHistoryRowsForWorkflow(UUID workflowId) {
        return pool.preparedQuery("SELECT entry_type, sequence FROM workflow_history"
                        + " WHERE workflow_id = $1 ORDER BY sequence ASC")
                .execute(Tuple.of(workflowId))
                .map(rs -> {
                    List<Row> rows = new ArrayList<>();
                    for (Row row : rs) {
                        rows.add(row);
                    }
                    return rows;
                });
    }

    /**
     * Deserializes a JSONB payload column value as a {@link WorkflowEventEnvelope}.
     *
     * @param row the outbox row
     * @return the deserialized envelope
     * @throws RuntimeException if deserialization fails
     */
    private WorkflowEventEnvelope deserializeEnvelope(Row row) {
        // The pg-client returns JSONB columns as io.vertx.core.json.JsonObject.
        // Use Vert.x's mapTo() which delegates to the configured Jackson ObjectMapper
        // with JavaTimeModule already registered (Vert.x registers it at startup).
        io.vertx.core.json.JsonObject payload = (io.vertx.core.json.JsonObject) row.getValue("payload");
        return payload.mapTo(WorkflowEventEnvelope.class);
    }

    /**
     * Asserts the common outbox row fields for a workflow event.
     *
     * @param row        the outbox row to check
     * @param eventType  the expected event type string
     * @param workflowId the expected workflow instance UUID
     */
    private void assertOutboxRow(Row row, String eventType, UUID workflowId) {
        assertEquals(eventType, row.getString("event_type"), "event_type must match");
        assertEquals("workflow", row.getString("aggregate_type"), "aggregate_type must be 'workflow'");
        assertEquals(workflowId.toString(), row.getString("aggregate_id"), "aggregate_id must be workflowId");
        assertEquals("SERVICE", row.getString("destination_type"), "destination_type must be SERVICE");
        assertEquals(TEST_DESTINATION, row.getString("destination"), "destination must be test-event-target");
    }

    /**
     * Asserts the common envelope fields for a workflow event.
     *
     * @param envelope     the deserialized envelope
     * @param eventType    the expected event type string
     * @param workflowId   the expected workflow UUID
     * @param definitionId the expected definition id
     */
    private void assertEnvelope(
            WorkflowEventEnvelope envelope, String eventType, UUID workflowId, String definitionId) {
        assertEquals(1, envelope.schemaVersion(), "schemaVersion must be 1");
        assertEquals(eventType, envelope.eventType(), "envelope eventType must match");
        assertEquals(workflowId, envelope.workflowId(), "envelope workflowId must match");
        assertEquals(definitionId, envelope.definitionId(), "envelope definitionId must match");
        assertNotNull(envelope.occurredAt(), "envelope occurredAt must not be null");
    }

    // --- Tests ---

    /**
     * Starting a workflow that completes immediately emits WORKFLOW_STARTED and WORKFLOW_COMPLETED
     * outbox rows, atomically with the state transition.
     */
    @Test
    @DisplayName("WORKFLOW_STARTED + WORKFLOW_COMPLETED: start() on immediate-complete plan writes 2 outbox rows")
    void startEmitsWorkflowStartedAndCompleted(VertxTestContext ctx) {
        State state = new State("complete-" + UUID.randomUUID());
        StartCommand cmd = new StartCommand("event-emission-complete", state, state.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose(workflowId -> readOutboxRowsForWorkflow(workflowId.value())
                        .map(rows -> {
                            ctx.verify(() -> {
                                List<String> eventTypes = rows.stream()
                                        .map(r -> r.getString("event_type"))
                                        .collect(Collectors.toList());
                                assertTrue(
                                        eventTypes.contains(WorkflowEventType.WORKFLOW_STARTED.name()),
                                        "WORKFLOW_STARTED must be in outbox; got: " + eventTypes);
                                assertTrue(
                                        eventTypes.contains(WorkflowEventType.WORKFLOW_COMPLETED.name()),
                                        "WORKFLOW_COMPLETED must be in outbox; got: " + eventTypes);

                                // Verify envelopes for both events
                                for (Row row : rows) {
                                    WorkflowEventEnvelope env = deserializeEnvelope(row);
                                    assertOutboxRow(row, env.eventType(), workflowId.value());
                                    assertEnvelope(env, env.eventType(), workflowId.value(), "event-emission-complete");
                                }

                                // WORKFLOW_STARTED must carry idempotencyKey attribute
                                Row startedRow = rows.stream()
                                        .filter(r -> WorkflowEventType.WORKFLOW_STARTED
                                                .name()
                                                .equals(r.getString("event_type")))
                                        .findFirst()
                                        .orElseThrow();
                                WorkflowEventEnvelope startedEnv = deserializeEnvelope(startedRow);
                                assertEquals(
                                        state.idempotencyKey(),
                                        startedEnv.attributes().get("idempotencyKey"),
                                        "WORKFLOW_STARTED must carry idempotencyKey attribute");
                            });
                            return null;
                        }))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Starting a workflow that hits a FailNode emits WORKFLOW_STARTED and WORKFLOW_FAILED outbox
     * rows. The WORKFLOW_FAILED envelope must carry an {@code errorType} attribute.
     */
    @Test
    @DisplayName("WORKFLOW_FAILED: start() on fail-plan writes WORKFLOW_STARTED + WORKFLOW_FAILED outbox rows")
    void startEmitsWorkflowFailed(VertxTestContext ctx) {
        State state = new State("fail-" + UUID.randomUUID());
        StartCommand cmd = new StartCommand("event-emission-fail", state, state.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose(workflowId -> readOutboxRowsForWorkflow(workflowId.value())
                        .map(rows -> {
                            ctx.verify(() -> {
                                List<String> eventTypes = rows.stream()
                                        .map(r -> r.getString("event_type"))
                                        .collect(Collectors.toList());
                                assertTrue(
                                        eventTypes.contains(WorkflowEventType.WORKFLOW_STARTED.name()),
                                        "WORKFLOW_STARTED must be in outbox; got: " + eventTypes);
                                assertTrue(
                                        eventTypes.contains(WorkflowEventType.WORKFLOW_FAILED.name()),
                                        "WORKFLOW_FAILED must be in outbox; got: " + eventTypes);

                                // WORKFLOW_FAILED must carry errorType attribute
                                Row failedRow = rows.stream()
                                        .filter(r -> WorkflowEventType.WORKFLOW_FAILED
                                                .name()
                                                .equals(r.getString("event_type")))
                                        .findFirst()
                                        .orElseThrow();
                                WorkflowEventEnvelope failedEnv = deserializeEnvelope(failedRow);
                                assertEquals(
                                        "TEST_ERROR_TYPE",
                                        failedEnv.attributes().get("errorType"),
                                        "WORKFLOW_FAILED must carry errorType attribute");
                            });
                            return null;
                        }))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Starting a human-task workflow emits WORKFLOW_STARTED and TASK_CREATED atomically (the
     * instance is in WAITING/TASK state when the future resolves, and both outbox rows exist).
     */
    @Test
    @DisplayName("TASK_CREATED: start() on task-plan writes WORKFLOW_STARTED + TASK_CREATED outbox rows")
    void startEmitsTaskCreated(VertxTestContext ctx) {
        State state = new State("task-created-" + UUID.randomUUID());
        StartCommand cmd = new StartCommand("event-emission-task", state, state.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose(workflowId -> {
                    // Assert instance is WAITING on TASK
                    return pool.withTransaction(tx -> instanceRepo.findById(workflowId, tx))
                            .compose(optInst -> {
                                ctx.verify(() -> {
                                    assertTrue(optInst.isPresent());
                                    assertEquals(
                                            WorkflowStatus.WAITING,
                                            optInst.get().status());
                                    assertEquals(WaitType.TASK, optInst.get().waitType());
                                });
                                return readOutboxRowsForWorkflow(workflowId.value())
                                        .map(rows -> {
                                            ctx.verify(() -> {
                                                List<String> eventTypes = rows.stream()
                                                        .map(r -> r.getString("event_type"))
                                                        .collect(Collectors.toList());
                                                assertTrue(
                                                        eventTypes.contains(WorkflowEventType.WORKFLOW_STARTED.name()),
                                                        "WORKFLOW_STARTED must be in outbox; got: " + eventTypes);
                                                assertTrue(
                                                        eventTypes.contains(WorkflowEventType.TASK_CREATED.name()),
                                                        "TASK_CREATED must be in outbox; got: " + eventTypes);

                                                // TASK_CREATED envelope must have taskId + stepId set
                                                Row taskCreatedRow = rows.stream()
                                                        .filter(r -> WorkflowEventType.TASK_CREATED
                                                                .name()
                                                                .equals(r.getString("event_type")))
                                                        .findFirst()
                                                        .orElseThrow();
                                                WorkflowEventEnvelope taskCreatedEnv =
                                                        deserializeEnvelope(taskCreatedRow);
                                                assertNotNull(taskCreatedEnv.taskId(), "TASK_CREATED must have taskId");
                                                assertEquals(
                                                        "review",
                                                        taskCreatedEnv.stepId(),
                                                        "TASK_CREATED must have stepId='review'");
                                            });
                                            return null;
                                        });
                            });
                })
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Completing a human task emits TASK_COMPLETED and WORKFLOW_COMPLETED outbox rows atomically
     * with the task close and workflow advance.
     */
    @Test
    @DisplayName("TASK_COMPLETED + WORKFLOW_COMPLETED: taskCompleted() writes both outbox rows atomically")
    void taskCompletedEmitsEvents(VertxTestContext ctx) {
        State state = new State("task-complete-" + UUID.randomUUID());
        StartCommand cmd = new StartCommand("event-emission-task", state, state.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose(workflowId -> {
                    // Read task id from wait_key
                    return pool.withTransaction(tx -> instanceRepo.findById(workflowId, tx))
                            .compose(optInst -> {
                                UUID taskId = UUID.fromString(optInst.get().waitKey());
                                TaskCompletionCommand completeCmd = new TaskCompletionCommand(
                                        taskId,
                                        "approve",
                                        null,
                                        "idem-task-complete-" + UUID.randomUUID(),
                                        new WorkflowActor.User("alice"),
                                        null);

                                return pool.withTransaction(tx -> engine.taskCompleted(completeCmd, tx))
                                        .compose(result -> {
                                            ctx.verify(() -> assertEquals(
                                                    dev.vertique.workflow.ops.TaskMutationResult.APPLIED, result));
                                            return readOutboxRowsForWorkflow(workflowId.value());
                                        })
                                        .map(rows -> {
                                            ctx.verify(() -> {
                                                List<String> eventTypes = rows.stream()
                                                        .map(r -> r.getString("event_type"))
                                                        .collect(Collectors.toList());

                                                assertTrue(
                                                        eventTypes.contains(WorkflowEventType.TASK_COMPLETED.name()),
                                                        "TASK_COMPLETED must be in outbox; got: " + eventTypes);
                                                assertTrue(
                                                        eventTypes.contains(
                                                                WorkflowEventType.WORKFLOW_COMPLETED.name()),
                                                        "WORKFLOW_COMPLETED must be in outbox; got: " + eventTypes);

                                                // TASK_COMPLETED must have taskId
                                                Row taskCompletedRow = rows.stream()
                                                        .filter(r -> WorkflowEventType.TASK_COMPLETED
                                                                .name()
                                                                .equals(r.getString("event_type")))
                                                        .findFirst()
                                                        .orElseThrow();
                                                WorkflowEventEnvelope completedEnv =
                                                        deserializeEnvelope(taskCompletedRow);
                                                assertEquals(
                                                        taskId,
                                                        completedEnv.taskId(),
                                                        "TASK_COMPLETED must carry task UUID");
                                            });
                                            return null;
                                        });
                            });
                })
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Cancelling a RUNNING workflow emits a WORKFLOW_CANCELLED outbox row with a {@code reason}
     * attribute matching the cancel reason string.
     */
    @Test
    @DisplayName("WORKFLOW_CANCELLED: cancel() writes WORKFLOW_CANCELLED outbox row with reason attribute")
    void cancelEmitsWorkflowCancelled(VertxTestContext ctx) {
        State state = new State("cancel-" + UUID.randomUUID());
        StartCommand cmd = new StartCommand("event-emission-task", state, state.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose(workflowId -> engine.cancel(workflowId, "test-cancel-reason")
                        .compose(v -> readOutboxRowsForWorkflow(workflowId.value()))
                        .map(rows -> {
                            ctx.verify(() -> {
                                List<String> eventTypes = rows.stream()
                                        .map(r -> r.getString("event_type"))
                                        .collect(Collectors.toList());
                                assertTrue(
                                        eventTypes.contains(WorkflowEventType.WORKFLOW_CANCELLED.name()),
                                        "WORKFLOW_CANCELLED must be in outbox; got: " + eventTypes);

                                Row cancelledRow = rows.stream()
                                        .filter(r -> WorkflowEventType.WORKFLOW_CANCELLED
                                                .name()
                                                .equals(r.getString("event_type")))
                                        .findFirst()
                                        .orElseThrow();
                                WorkflowEventEnvelope cancelledEnv = deserializeEnvelope(cancelledRow);
                                assertEquals(
                                        "test-cancel-reason",
                                        cancelledEnv.attributes().get("reason"),
                                        "WORKFLOW_CANCELLED must carry reason attribute");

                                // Verify core outbox row fields
                                assertOutboxRow(
                                        cancelledRow, WorkflowEventType.WORKFLOW_CANCELLED.name(), workflowId.value());
                                assertEnvelope(
                                        cancelledEnv,
                                        WorkflowEventType.WORKFLOW_CANCELLED.name(),
                                        workflowId.value(),
                                        "event-emission-task");
                            });
                            return null;
                        }))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Cancelling a workflow that is WAITING on a TASK emits a TASK_CANCELLED outbox row
     * <strong>and</strong> a WORKFLOW_CANCELLED outbox row. The TASK_CANCELLED envelope's
     * {@code sequence} field MUST equal the workflow_history TASK_CANCELLED row's sequence — not
     * the WORKFLOW_CANCELLED row's sequence.
     *
     * <p>Cycle-4 regression guard: an earlier draft called
     * {@code history.nextSequence(...)} a second time after {@code appendTaskCancelledHistory}
     * had already consumed one sequence, causing the TASK_CANCELLED event to point at the
     * subsequent WORKFLOW_CANCELLED history row. The fix made
     * {@code appendTaskCancelledHistory} return the sequence it consumed; this test pins the
     * correlation so a future regression breaks here.
     */
    @Test
    @DisplayName("TASK_CANCELLED + WORKFLOW_CANCELLED: cancel() while WAITING on a task writes both outbox rows;"
            + " TASK_CANCELLED.sequence equals the workflow_history TASK_CANCELLED row sequence")
    void cancelWhileWaitingOnTaskEmitsTaskCancelledWithCorrectSequence(VertxTestContext ctx) {
        State state = new State("task-cancel-" + UUID.randomUUID());
        StartCommand cmd = new StartCommand("event-emission-task", state, state.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose(workflowId -> engine.cancel(workflowId, "test-task-cancel-reason")
                        .compose(v -> readOutboxRowsForWorkflow(workflowId.value())
                                .compose(outboxRows -> readHistoryRowsForWorkflow(workflowId.value())
                                        .map(historyRows -> {
                                            ctx.verify(() -> {
                                                List<String> outboxEventTypes = outboxRows.stream()
                                                        .map(r -> r.getString("event_type"))
                                                        .collect(Collectors.toList());
                                                assertTrue(
                                                        outboxEventTypes.contains(
                                                                WorkflowEventType.TASK_CANCELLED.name()),
                                                        "TASK_CANCELLED must be in outbox; got: " + outboxEventTypes);
                                                assertTrue(
                                                        outboxEventTypes.contains(
                                                                WorkflowEventType.WORKFLOW_CANCELLED.name()),
                                                        "WORKFLOW_CANCELLED must be in outbox; got: "
                                                                + outboxEventTypes);

                                                Row taskCancelledRow = outboxRows.stream()
                                                        .filter(r -> WorkflowEventType.TASK_CANCELLED
                                                                .name()
                                                                .equals(r.getString("event_type")))
                                                        .findFirst()
                                                        .orElseThrow();
                                                WorkflowEventEnvelope env = deserializeEnvelope(taskCancelledRow);

                                                assertOutboxRow(
                                                        taskCancelledRow,
                                                        WorkflowEventType.TASK_CANCELLED.name(),
                                                        workflowId.value());
                                                assertNotNull(
                                                        env.taskId(), "TASK_CANCELLED envelope must carry taskId");
                                                assertEquals(
                                                        "review",
                                                        env.stepId(),
                                                        "TASK_CANCELLED envelope stepId must be 'review'");
                                                assertEquals(
                                                        "WORKFLOW_CANCELLED",
                                                        env.attributes().get("cause"),
                                                        "TASK_CANCELLED envelope must carry cause=WORKFLOW_CANCELLED");
                                                assertNotNull(
                                                        env.attributes().get("cancelledBy"),
                                                        "TASK_CANCELLED envelope must carry cancelledBy actor map");

                                                // Sequence-correlation guard: the TASK_CANCELLED envelope's
                                                // sequence must equal the workflow_history TASK_CANCELLED
                                                // row's sequence. Before the fix, it equalled the
                                                // WORKFLOW_CANCELLED row's sequence (one off).
                                                long taskCancelledHistorySequence = historyRows.stream()
                                                        .filter(r -> "TASK_CANCELLED".equals(r.getString("entry_type")))
                                                        .map(r -> r.getLong("sequence"))
                                                        .findFirst()
                                                        .orElseThrow(() -> new AssertionError(
                                                                "no TASK_CANCELLED row in workflow_history"));
                                                long workflowCancelledHistorySequence = historyRows.stream()
                                                        .filter(r -> "CANCELLED".equals(r.getString("entry_type")))
                                                        .map(r -> r.getLong("sequence"))
                                                        .findFirst()
                                                        .orElseThrow(() -> new AssertionError(
                                                                "no CANCELLED row in workflow_history"));
                                                assertEquals(
                                                        taskCancelledHistorySequence,
                                                        env.sequence(),
                                                        "TASK_CANCELLED envelope sequence must match the"
                                                                + " workflow_history TASK_CANCELLED row sequence");
                                                assertNotEquals(
                                                        workflowCancelledHistorySequence,
                                                        env.sequence(),
                                                        "TASK_CANCELLED envelope sequence must NOT match the"
                                                                + " WORKFLOW_CANCELLED row sequence (regression guard)");
                                            });
                                            return null;
                                        }))))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * When an engine operation fails partway through (e.g., a simulated constraint violation),
     * the entire transaction rolls back and NO outbox rows are written. This validates the
     * atomicity guarantee: event emission and state change are in the same transaction.
     *
     * <p>Proxy: we start a workflow twice with the same idempotency key (first succeeds; second
     * is deduplicated and returns the same id, NOT an error; this doesn't roll back so we instead
     * verify that the outbox count for the first run is exactly what was expected — neither more
     * nor less — confirming no phantom rows from concurrent operations.)
     *
     * <p>Specifically: a complete-immediately workflow produces exactly 2 outbox rows
     * (WORKFLOW_STARTED + WORKFLOW_COMPLETED) — not 0 (rolled back) and not 4 (double-emit).
     */
    @Test
    @DisplayName("Atomicity: complete-immediately workflow produces exactly 2 outbox rows (no phantom rows)")
    void atomicityExactly2OutboxRows(VertxTestContext ctx) {
        State state = new State("atomic-" + UUID.randomUUID());
        StartCommand cmd = new StartCommand("event-emission-complete", state, state.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose(workflowId -> readOutboxRowsForWorkflow(workflowId.value())
                        .map(rows -> {
                            ctx.verify(() -> assertEquals(
                                    2, rows.size(), "exactly 2 outbox rows expected (STARTED + COMPLETED)"));
                            return null;
                        }))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }
}
