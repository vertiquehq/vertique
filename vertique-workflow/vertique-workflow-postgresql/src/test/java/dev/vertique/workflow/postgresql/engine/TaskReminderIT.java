// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.events.binding.WorkflowEventOutboxBinding;
import dev.vertique.workflow.events.compose.WorkflowEventsComposeValidator;
import dev.vertique.workflow.events.recorder.WorkflowEventSideEffectRecorder;
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
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.timer.TimerIntentPayload;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for the task-reminder lifecycle in {@link WorkflowEngineHandle}.
 *
 * <p>Each test creates a real Testcontainers Postgres database with both the workflow schema and
 * the outbox schema. A real {@link WorkflowEventSideEffectRecorder} is wired in, backed by
 * {@link PgInboxOutboxRepository}, so TASK_REMINDER events are written to the {@code outbox} table
 * atomically with the reminder history entry.
 *
 * <p>Timer firing is simulated directly by calling
 * {@link WorkflowEngineHandle#taskReminderFired(WorkflowInstanceId, UUID, UUID, io.vertx.sqlclient.SqlClient)}
 * inside a {@link Pool#withTransaction} — bypassing the delayed-job infrastructure. After each
 * simulated fire, the test uses {@link PgTimerStore#markFired} to put the timer row into the
 * terminal state (mirroring what {@code WorkflowTimerFireExecutor} would do in production).
 *
 * <p>Covers:
 * <ul>
 *   <li>One-shot reminders: each offset fires once; last fire has {@code isLast=true}.</li>
 *   <li>Recurring reminders: each fire schedules the next; stops after {@code maxFires}.</li>
 *   <li>Cancel cascade on task completion: pending reminders become CANCELLED.</li>
 *   <li>Cancel cascade on workflow cancel: pending reminders become CANCELLED.</li>
 *   <li>Cancel cascade on due-date expiry: pending reminders become CANCELLED.</li>
 *   <li>Reminders survive reassignment: still SCHEDULED after {@code taskReassigned}.</li>
 *   <li>Idempotent fire on closed task: no event emitted, timer still ends FIRED.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class TaskReminderIT {

    // --- Constants ---

    static final String TEST_DESTINATION = "test-reminder-target";
    static final WorkflowEventOutboxBinding BINDING =
            new WorkflowEventOutboxBinding(DestinationType.SERVICE, TEST_DESTINATION);

    // --- Container setup ---

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("workflow_task_reminder_test");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgWorkflowInstanceRepository instanceRepo;
    static PgWorkflowHistoryRepository historyRepo;
    static PgTimerStore timerStore;

    /** All timer ids inserted by the fake WORKFLOW_TIMER recorder, in insertion order. */
    static final CopyOnWriteArrayList<UUID> insertedTimerIds = new CopyOnWriteArrayList<>();

    // --- Domain types ---

    /**
     * Minimal workflow state for reminder tests.
     *
     * @param id unique test identifier
     */
    record State(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "task-reminder-it-" + id;
        }
    }

    // --- Contract markers ---

    /** Contract for a one-shot reminder plan. */
    interface OneShotContract {}

    /** Contract for a recurring reminder plan. */
    interface RecurringContract {}

    /** Contract for a reminder + due-date plan. */
    interface DueDateReminderContract {}

    // --- Workflow definitions ---

    /**
     * A task with two one-shot reminders at 200ms and 400ms after creation.
     * Decision "approve" completes the workflow.
     */
    static final WorkflowDefinition<State, OneShotContract> ONE_SHOT_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<OneShotContract> contract() {
            return OneShotContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "reminder-one-shot";
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
                    .reminderAt(Duration.ofMillis(200), Duration.ofMillis(400))
                    .build()
                    .complete("done");
        }
    };

    /**
     * A task with a recurring reminder every 150ms, max 3 fires.
     */
    static final WorkflowDefinition<State, RecurringContract> RECURRING_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<RecurringContract> contract() {
            return RecurringContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "reminder-recurring";
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
                    .reminderEvery(Duration.ofMillis(150), 3)
                    .build()
                    .complete("done");
        }
    };

    /**
     * A task with a 1-hour due-date and a one-shot reminder at 200ms.
     * Used to verify cancel cascade on due-date expiry.
     */
    static final WorkflowDefinition<State, DueDateReminderContract> DUE_DATE_REMINDER_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<DueDateReminderContract> contract() {
            return DueDateReminderContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "reminder-due-date";
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
                    .dueIn(Duration.ofHours(1))
                    .onDue(s -> s)
                    .toStepOnDue("escalate")
                    .reminderAt(Duration.ofMillis(200))
                    .build()
                    .complete("done")
                    .complete("escalate");
        }
    };

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        // Run both workflow and inbox-outbox migrations.
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
        historyRepo = new PgWorkflowHistoryRepository(pool, exMapper);
        PgWorkflowDedupRepository dedupRepo = new PgWorkflowDedupRepository(pool, exMapper);
        timerStore = new PgTimerStore(exMapper);

        // Outbox infrastructure
        PgInboxOutboxRepository outboxRepo = new PgInboxOutboxRepository(pool, exMapper);
        OutboxService outboxService =
                (tx, entry) -> outboxRepo.insert(entry, OutboxMetadata.empty(), UUID.randomUUID(), tx);

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
            public Future<dev.vertique.inboxoutbox.OutboxPublishResult> publish(
                    dev.vertique.inboxoutbox.OutboxEnvelope envelope) {
                return Future.succeededFuture(dev.vertique.inboxoutbox.OutboxPublishResult.success());
            }
        };

        // Event recorder
        WorkflowEventsComposeValidator eventsValidator =
                new WorkflowEventsComposeValidator(Set.of(testHandler), BINDING);
        WorkflowEventSideEffectRecorder eventRecorder =
                new WorkflowEventSideEffectRecorder(outboxService, BINDING, eventsValidator);

        // Timer recorder — records all timer ids in insertion order
        WorkflowSideEffectRecorder<io.vertx.sqlclient.SqlClient> timerRecorder = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.WORKFLOW_TIMER;
            }

            @Override
            public Future<RecorderResult> record(WorkflowSideEffectIntent intent, io.vertx.sqlclient.SqlClient tx) {
                UUID timerId = UUID.randomUUID();
                insertedTimerIds.add(timerId);
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

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(ONE_SHOT_DEF);
        registry.register(RECURRING_DEF);
        registry.register(DUE_DATE_REMINDER_DEF);

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
                Clock.systemUTC());

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        insertedTimerIds.clear();
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
     * Reads the task UUID from the instance's {@code wait_key} column.
     *
     * @param workflowId the workflow instance to inspect
     * @return a {@link Future} containing the task UUID
     */
    private Future<UUID> readTaskId(WorkflowInstanceId workflowId) {
        return pool.withTransaction(tx -> instanceRepo.findById(workflowId, tx)).map(optInst -> {
            assertTrue(optInst.isPresent(), "instance must exist");
            assertEquals(WaitType.TASK, optInst.get().waitType(), "must be WAITING on TASK");
            return UUID.fromString(optInst.get().waitKey());
        });
    }

    /**
     * Counts outbox rows with the given {@code eventType} for the workflow instance.
     *
     * @param workflowId the workflow UUID
     * @param eventType  the event type string to count
     * @return a {@link Future} containing the count
     */
    private Future<Long> countOutboxEvents(UUID workflowId, String eventType) {
        return pool.preparedQuery("SELECT COUNT(*) FROM outbox WHERE aggregate_id = $1 AND event_type = $2")
                .execute(Tuple.of(workflowId.toString(), eventType))
                .map(rs -> rs.iterator().next().getLong(0));
    }

    /**
     * Reads all TASK_REMINDER outbox rows for the workflow, in insertion order.
     *
     * @param workflowId the workflow UUID
     * @return a {@link Future} containing the rows
     */
    private Future<List<Row>> readReminderOutboxRows(UUID workflowId) {
        return pool.preparedQuery("SELECT * FROM outbox WHERE aggregate_id = $1 AND event_type = $2 ORDER BY id ASC")
                .execute(Tuple.of(workflowId.toString(), WorkflowEventType.TASK_REMINDER.name()))
                .map(rs -> {
                    List<Row> rows = new ArrayList<>();
                    for (Row row : rs) {
                        rows.add(row);
                    }
                    return rows;
                });
    }

    /**
     * Reads the {@code status} column from the timer row with the given id.
     *
     * @param timerId the timer UUID
     * @return a {@link Future} containing the status string
     */
    private Future<String> readTimerStatus(UUID timerId) {
        return pool.preparedQuery("SELECT status FROM workflow_timers WHERE timer_id = $1")
                .execute(Tuple.of(timerId))
                .map(rs -> {
                    var it = rs.iterator();
                    assertTrue(it.hasNext(), "timer row must exist for " + timerId);
                    return it.next().getString("status");
                });
    }

    /**
     * Counts history entries of the given type for the workflow instance.
     *
     * @param workflowId the workflow instance id
     * @param entryType  the entry type to count
     * @return a {@link Future} containing the count
     */
    private Future<Long> countHistory(WorkflowInstanceId workflowId, WorkflowEntryType entryType) {
        return historyRepo.listByInstance(workflowId, pool).map(entries -> entries.stream()
                .filter(e -> e.entryType() == entryType)
                .count());
    }

    /**
     * Simulates the {@code WorkflowTimerFireExecutor}'s work for a reminder timer:
     * calls {@link WorkflowEngineHandle#taskReminderFired} inside a transaction, then marks the timer
     * as {@code FIRED}. Returns a succeeded future even if the task was already closed (mirrors
     * the executor's no-STALE_NOOP contract for reminders).
     *
     * @param workflowId the owning workflow instance id
     * @param taskId     the task UUID
     * @param timerId    the reminder timer UUID to fire
     * @return a succeeded future when the fire + mark sequence completes
     */
    private Future<Void> fireReminderTimer(WorkflowInstanceId workflowId, UUID taskId, UUID timerId) {
        // Look up the timer's persisted fire_at and pass it as the scheduledFireAt anchor (cycle-4
        // SPI extension: recurring reminders re-anchor off this instant to avoid drift).
        return pool.withTransaction(tx -> timerStore.lockForFiring(timerId, tx).compose(opt -> {
                    Instant scheduledFireAt = opt.map(r -> r.fireAt()).orElse(Instant.now());
                    return engine.taskReminderFired(workflowId, taskId, timerId, scheduledFireAt, tx);
                }))
                .compose(v -> pool.withTransaction(
                        tx -> timerStore.markFired(timerId, Instant.now(), tx).mapEmpty()));
    }

    /**
     * Reads the payload of the nth TASK_REMINDER outbox row (0-indexed) for the workflow.
     *
     * @param workflowId the workflow UUID
     * @param index      0-based index into the reminder rows ordered by insertion
     * @return a {@link Future} containing the parsed payload as a {@link JsonObject}
     */
    private Future<JsonObject> readReminderEventPayload(UUID workflowId, int index) {
        return readReminderOutboxRows(workflowId).map(rows -> {
            assertTrue(rows.size() > index, "expected at least " + (index + 1) + " reminder rows; got " + rows.size());
            return (JsonObject) rows.get(index).getValue("payload");
        });
    }

    // --- Tests ---

    /**
     * One-shot reminders: each of the two offset timers fires once, producing two TASK_REMINDER
     * outbox rows. The second row has {@code isLast=true}. The workflow remains WAITING throughout.
     * Both timer rows transition to FIRED.
     */
    @Test
    @DisplayName("One-shot: 2 reminder timers fire; second has isLast=true; workflow stays WAITING")
    void oneShotRemindersFire(VertxTestContext ctx) {
        State state = new State("one-shot-" + UUID.randomUUID());
        engine.start(new StartCommand("reminder-one-shot", state, state.idempotencyKey(), null, null))
                .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                    // After start, insertedTimerIds has 2 one-shot reminder timer ids
                    assertEquals(2, insertedTimerIds.size(), "2 reminder timers should be scheduled on start");
                    UUID timerId1 = insertedTimerIds.get(0);
                    UUID timerId2 = insertedTimerIds.get(1);

                    return fireReminderTimer(workflowId, taskId, timerId1)
                            .compose(v -> fireReminderTimer(workflowId, taskId, timerId2))
                            .compose(v -> {
                                // Assert: 2 TASK_REMINDER outbox rows
                                return readReminderOutboxRows(workflowId.value())
                                        .compose(rows -> {
                                            ctx.verify(() -> assertEquals(
                                                    2, rows.size(), "exactly 2 TASK_REMINDER outbox rows expected"));

                                            // Parse payload of each row and check isLast.
                                            // The outbox payload is a WorkflowEventEnvelope; reminderIndex
                                            // and isLast are nested inside the "attributes" object.
                                            JsonObject payload1 =
                                                    (JsonObject) rows.get(0).getValue("payload");
                                            JsonObject payload2 =
                                                    (JsonObject) rows.get(1).getValue("payload");
                                            JsonObject attrs1 = payload1.getJsonObject("attributes");
                                            JsonObject attrs2 = payload2.getJsonObject("attributes");
                                            ctx.verify(() -> {
                                                assertNotNull(attrs1, "first reminder payload must have attributes");
                                                assertEquals(
                                                        1,
                                                        attrs1.getInteger("reminderIndex"),
                                                        "first reminder must have reminderIndex=1");
                                                assertEquals(
                                                        false,
                                                        attrs1.getBoolean("isLast"),
                                                        "first reminder must have isLast=false");
                                                assertNotNull(attrs2, "second reminder payload must have attributes");
                                                assertEquals(
                                                        2,
                                                        attrs2.getInteger("reminderIndex"),
                                                        "second reminder must have reminderIndex=2");
                                                assertEquals(
                                                        true,
                                                        attrs2.getBoolean("isLast"),
                                                        "second reminder must have isLast=true");
                                            });

                                            // Assert both timer rows are FIRED
                                            return readTimerStatus(timerId1)
                                                    .compose(s1 -> readTimerStatus(timerId2)
                                                            .compose(s2 -> {
                                                                ctx.verify(() -> {
                                                                    assertEquals("FIRED", s1, "timerId1 must be FIRED");
                                                                    assertEquals("FIRED", s2, "timerId2 must be FIRED");
                                                                });

                                                                // Workflow must still be WAITING on TASK
                                                                return pool.withTransaction(
                                                                        tx -> instanceRepo.findById(workflowId, tx));
                                                            }))
                                                    .map(optInst -> {
                                                        ctx.verify(() -> {
                                                            assertTrue(optInst.isPresent());
                                                            assertEquals(
                                                                    WorkflowStatus.WAITING,
                                                                    optInst.get()
                                                                            .status());
                                                            assertEquals(
                                                                    WaitType.TASK,
                                                                    optInst.get()
                                                                            .waitType());
                                                        });
                                                        return null;
                                                    });
                                        });
                            });
                }))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Recurring reminders: 3 fires capped by maxFires=3; each fire schedules the next (only 1
     * reminder timer row at a time). After 3 fires, no 4th timer is scheduled.
     */
    @Test
    @DisplayName("Recurring: 3 fires with maxFires=3; 3rd has isLast=true; no 4th timer scheduled")
    void recurringRemindersCappedAtMaxFires(VertxTestContext ctx) {
        State state = new State("recurring-" + UUID.randomUUID());
        engine.start(new StartCommand("reminder-recurring", state, state.idempotencyKey(), null, null))
                .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                    // After start, 1 recurring reminder timer is scheduled
                    assertEquals(1, insertedTimerIds.size(), "1 recurring reminder timer should be scheduled");
                    UUID timerId1 = insertedTimerIds.get(0);

                    // Fire 1st — schedules 2nd (insertedTimerIds will have 2 entries)
                    return fireReminderTimer(workflowId, taskId, timerId1).compose(v -> {
                        assertEquals(2, insertedTimerIds.size(), "2nd timer must be scheduled after 1st fire");
                        UUID timerId2 = insertedTimerIds.get(1);

                        // Fire 2nd — schedules 3rd
                        return fireReminderTimer(workflowId, taskId, timerId2).compose(v2 -> {
                            assertEquals(3, insertedTimerIds.size(), "3rd timer must be scheduled after 2nd fire");
                            UUID timerId3 = insertedTimerIds.get(2);

                            // Fire 3rd — should NOT schedule 4th (isLast=true)
                            return fireReminderTimer(workflowId, taskId, timerId3)
                                    .compose(v3 -> {
                                        assertEquals(
                                                3,
                                                insertedTimerIds.size(),
                                                "no 4th timer must be scheduled after 3rd fire (maxFires reached)");

                                        return countOutboxEvents(
                                                        workflowId.value(), WorkflowEventType.TASK_REMINDER.name())
                                                .compose(count -> {
                                                    ctx.verify(() ->
                                                            assertEquals(3L, count, "3 TASK_REMINDER events expected"));

                                                    // Verify 3rd event has isLast=true.
                                                    // reminderIndex and isLast are nested inside "attributes".
                                                    return readReminderEventPayload(workflowId.value(), 2)
                                                            .map(envelope -> {
                                                                JsonObject attrs = envelope.getJsonObject("attributes");
                                                                ctx.verify(() -> {
                                                                    assertNotNull(
                                                                            attrs, "3rd reminder must have attributes");
                                                                    assertEquals(
                                                                            3,
                                                                            attrs.getInteger("reminderIndex"),
                                                                            "3rd event must have reminderIndex=3");
                                                                    assertEquals(
                                                                            true,
                                                                            attrs.getBoolean("isLast"),
                                                                            "3rd event must have isLast=true");
                                                                });
                                                                return null;
                                                            });
                                                });
                                    });
                        });
                    });
                }))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Cancel cascade on task completion: any SCHEDULED reminder timers for the task are cancelled
     * when the task is completed via {@code taskCompleted}.
     *
     * <p>After start, one recurring reminder timer is SCHEDULED. We complete the task before firing
     * the reminder. The timer row must become CANCELLED.
     */
    @Test
    @DisplayName("Reminder cancel cascade: pending reminder becomes CANCELLED when task is completed")
    void reminderCancelledOnTaskCompletion(VertxTestContext ctx) {
        State state = new State("cancel-complete-" + UUID.randomUUID());
        engine.start(new StartCommand("reminder-recurring", state, state.idempotencyKey(), null, null))
                .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                    assertEquals(1, insertedTimerIds.size(), "1 reminder timer must be scheduled");
                    UUID reminderId = insertedTimerIds.get(0);

                    // Verify timer is initially SCHEDULED
                    return readTimerStatus(reminderId).compose(status -> {
                        ctx.verify(() -> assertEquals("SCHEDULED", status, "reminder must start SCHEDULED"));

                        // Complete the task
                        TaskCompletionCommand completeCmd = new TaskCompletionCommand(
                                taskId,
                                "approve",
                                null,
                                "idem-cancel-" + UUID.randomUUID(),
                                new WorkflowActor.User("alice"),
                                null);

                        return pool.withTransaction(tx -> engine.taskCompleted(completeCmd, tx))
                                .compose(result -> {
                                    ctx.verify(
                                            () -> assertEquals(TaskMutationResult.APPLIED, result, "must be APPLIED"));

                                    // The reminder timer must now be CANCELLED
                                    return readTimerStatus(reminderId);
                                })
                                .map(cancelledStatus -> {
                                    ctx.verify(() -> assertEquals(
                                            "CANCELLED",
                                            cancelledStatus,
                                            "pending reminder must be CANCELLED after task completion"));
                                    return null;
                                });
                    });
                }))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Cancel cascade on workflow cancellation: pending reminder timers are cancelled when
     * {@link WorkflowEngineHandle#cancel(WorkflowInstanceId, String)} is called.
     */
    @Test
    @DisplayName("Reminder cancel cascade: pending reminder becomes CANCELLED when workflow is cancelled")
    void reminderCancelledOnWorkflowCancel(VertxTestContext ctx) {
        State state = new State("cancel-wf-" + UUID.randomUUID());
        engine.start(new StartCommand("reminder-recurring", state, state.idempotencyKey(), null, null))
                .compose(workflowId -> {
                    assertEquals(1, insertedTimerIds.size(), "1 reminder timer must be scheduled");
                    UUID reminderId = insertedTimerIds.get(0);

                    return readTimerStatus(reminderId).compose(status -> {
                        ctx.verify(() -> assertEquals("SCHEDULED", status, "reminder must start SCHEDULED"));

                        // Cancel the workflow
                        return engine.cancel(workflowId, "workflow-cancel-test")
                                .compose(v -> readTimerStatus(reminderId))
                                .map(cancelledStatus -> {
                                    ctx.verify(() -> assertEquals(
                                            "CANCELLED",
                                            cancelledStatus,
                                            "pending reminder must be CANCELLED after workflow cancel"));
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
     * Cancel cascade on due-date expiry: pending reminder timers are cancelled when the task's
     * due-date timer fires and the task expires.
     *
     * <p>Uses the DUE_DATE_REMINDER_DEF which has both a due-date and a reminder.
     */
    @Test
    @DisplayName("Reminder cancel cascade: pending reminder becomes CANCELLED when task due-date expires")
    void reminderCancelledOnDueDateExpiry(VertxTestContext ctx) {
        State state = new State("cancel-due-" + UUID.randomUUID());
        engine.start(new StartCommand("reminder-due-date", state, state.idempotencyKey(), null, null))
                .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                    // insertedTimerIds[0] = due-date timer, insertedTimerIds[1] = reminder timer
                    // (order: handleHumanTaskNode calls scheduleDueDateTimer first, then scheduleReminderTimers)
                    assertTrue(insertedTimerIds.size() >= 2, "due-date + reminder timers must both be scheduled");
                    UUID dueDateTimerId = insertedTimerIds.get(0);
                    UUID reminderId = insertedTimerIds.get(1);

                    return readTimerStatus(reminderId).compose(status -> {
                        ctx.verify(() -> assertEquals("SCHEDULED", status, "reminder must start SCHEDULED"));

                        // Backdate the due-date timer's fire_at to the past and then fire it
                        return pool.preparedQuery("UPDATE workflow_timers SET fire_at = NOW() - INTERVAL '1 minute'"
                                        + " WHERE timer_id = $1")
                                .execute(Tuple.of(dueDateTimerId))
                                .compose(v ->
                                        pool.withTransaction(tx -> engine.timerFired(workflowId, dueDateTimerId, tx)))
                                .compose(result -> {
                                    ctx.verify(() -> assertEquals(
                                            dev.vertique.workflow.ops.TimerFiringResult.APPLIED,
                                            result,
                                            "due-date timerFired must return APPLIED"));

                                    // Reminder must now be CANCELLED
                                    return readTimerStatus(reminderId);
                                })
                                .map(cancelledStatus -> {
                                    ctx.verify(() -> assertEquals(
                                            "CANCELLED",
                                            cancelledStatus,
                                            "reminder must be CANCELLED after due-date expiry"));
                                    return null;
                                });
                    });
                }))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Reassignment does not cancel reminders: a SCHEDULED reminder remains SCHEDULED after the task
     * is reassigned via {@code taskReassigned}. The new assignee inherits the existing schedule.
     */
    @Test
    @DisplayName("Reminders survive reassignment: SCHEDULED reminder stays SCHEDULED after taskReassigned")
    void remindersSurviveReassignment(VertxTestContext ctx) {
        State state = new State("reassign-reminder-" + UUID.randomUUID());
        engine.start(new StartCommand("reminder-recurring", state, state.idempotencyKey(), null, null))
                .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                    assertEquals(1, insertedTimerIds.size(), "1 reminder timer must be scheduled");
                    UUID reminderId = insertedTimerIds.get(0);

                    TaskReassignmentCommand reassignCmd = new TaskReassignmentCommand(
                            taskId,
                            new TaskAssignment.Role("legal"),
                            new WorkflowActor.User("manager-bob"),
                            "idem-reassign-" + UUID.randomUUID(),
                            "needs specialist");

                    return pool.withTransaction(tx -> engine.taskReassigned(reassignCmd, tx))
                            .compose(result -> {
                                ctx.verify(() ->
                                        assertEquals(TaskMutationResult.APPLIED, result, "reassign must be APPLIED"));
                                // Reminder must still be SCHEDULED after reassignment
                                return readTimerStatus(reminderId);
                            })
                            .map(status -> {
                                ctx.verify(() -> assertEquals(
                                        "SCHEDULED", status, "reminder must remain SCHEDULED after reassignment"));
                                return null;
                            });
                }))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Idempotent reminder fire on closed task: calling {@code taskReminderFired} after the task
     * has been completed succeeds (no exception), emits no TASK_REMINDER event, writes no history
     * entry, and the timer row ends as FIRED (not FAILED).
     *
     * <p>This validates that a closed task is treated as a normal no-op outcome by the engine, not
     * as a {@code TIMER_INCONSISTENCY} error.
     */
    @Test
    @DisplayName("Idempotent fire on closed task: no event emitted; timer ends FIRED not FAILED")
    void reminderFireOnClosedTaskIsIdempotent(VertxTestContext ctx) {
        State state = new State("idempotent-closed-" + UUID.randomUUID());
        engine.start(new StartCommand("reminder-recurring", state, state.idempotencyKey(), null, null))
                .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                    assertEquals(1, insertedTimerIds.size(), "1 reminder timer must be scheduled");
                    // insertedTimerIds.get(0) is the initial reminder timer — cancel cascade will
                    // set it to CANCELLED when the task completes. We don't need to reference it
                    // directly; the test focuses on the racing timer created below.

                    // Complete the task first (task becomes COMPLETED, workflow becomes COMPLETED)
                    TaskCompletionCommand completeCmd = new TaskCompletionCommand(
                            taskId,
                            "approve",
                            null,
                            "idem-idempotent-" + UUID.randomUUID(),
                            new WorkflowActor.User("alice"),
                            null);

                    return pool.withTransaction(tx -> engine.taskCompleted(completeCmd, tx))
                            .compose(result -> {
                                ctx.verify(() -> assertEquals(TaskMutationResult.APPLIED, result));

                                // Now fire the reminder against the already-closed task
                                // (the cancel cascade already set reminderId to CANCELLED)
                                // We re-schedule a fake new reminder timer to simulate the race:
                                // a reminder that was already dispatched to the delayed-job queue
                                // arrives after the task completed.
                                UUID racingTimerId = UUID.randomUUID();
                                TimerRecord raceRecord = new TimerRecord(
                                        racingTimerId,
                                        workflowId,
                                        "review",
                                        Instant.now().plusSeconds(1),
                                        TimerStatus.SCHEDULED,
                                        UUID.randomUUID(),
                                        Instant.now(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        TimerPurpose.TASK_REMINDER,
                                        taskId,
                                        null, // branchTokenId
                                        null, // forkStepId
                                        null,
                                        DurableMetadata.empty()); // branchId

                                return pool.withTransaction(tx -> timerStore.insertScheduled(raceRecord, tx))
                                        .compose(v -> {
                                            // Count TASK_REMINDER events BEFORE the racing fire
                                            return countOutboxEvents(
                                                    workflowId.value(), WorkflowEventType.TASK_REMINDER.name());
                                        })
                                        .compose(countBefore -> {
                                            ctx.verify(() -> assertEquals(
                                                    0L, countBefore, "no TASK_REMINDER events before racing fire"));

                                            // Fire the racing reminder against the closed task
                                            return fireReminderTimer(workflowId, taskId, racingTimerId);
                                        })
                                        .compose(v -> {
                                            // Count TASK_REMINDER events AFTER the racing fire — must still be 0
                                            return countOutboxEvents(
                                                    workflowId.value(), WorkflowEventType.TASK_REMINDER.name());
                                        })
                                        .compose(countAfter -> {
                                            ctx.verify(() -> assertEquals(
                                                    0L,
                                                    countAfter,
                                                    "no TASK_REMINDER events must be emitted for closed-task fire"));

                                            // Timer must be FIRED (not FAILED)
                                            return readTimerStatus(racingTimerId);
                                        })
                                        .map(timerStatus -> {
                                            ctx.verify(() -> assertEquals(
                                                    "FIRED",
                                                    timerStatus,
                                                    "racing reminder timer must be FIRED, not FAILED"));
                                            return null;
                                        });
                            });
                }))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * History entries: each reminder fire writes exactly one {@code TASK_REMINDER_FIRED} history
     * entry. After two fires of a one-shot plan, exactly two history entries exist.
     */
    @Test
    @DisplayName("TASK_REMINDER_FIRED history entries: one per reminder fire")
    void reminderHistoryEntriesWritten(VertxTestContext ctx) {
        State state = new State("history-" + UUID.randomUUID());
        engine.start(new StartCommand("reminder-one-shot", state, state.idempotencyKey(), null, null))
                .compose(workflowId -> readTaskId(workflowId).compose(taskId -> {
                    assertEquals(2, insertedTimerIds.size(), "2 one-shot timers scheduled");
                    UUID timerId1 = insertedTimerIds.get(0);
                    UUID timerId2 = insertedTimerIds.get(1);

                    return fireReminderTimer(workflowId, taskId, timerId1)
                            .compose(v -> countHistory(workflowId, WorkflowEntryType.TASK_REMINDER_FIRED))
                            .compose(countAfter1 -> {
                                ctx.verify(() -> assertEquals(
                                        1L, countAfter1, "1 TASK_REMINDER_FIRED history after first fire"));

                                return fireReminderTimer(workflowId, taskId, timerId2)
                                        .compose(v -> countHistory(workflowId, WorkflowEntryType.TASK_REMINDER_FIRED));
                            })
                            .map(countAfter2 -> {
                                ctx.verify(() -> assertEquals(
                                        2L, countAfter2, "2 TASK_REMINDER_FIRED history after both fires"));
                                return null;
                            });
                }))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }
}
