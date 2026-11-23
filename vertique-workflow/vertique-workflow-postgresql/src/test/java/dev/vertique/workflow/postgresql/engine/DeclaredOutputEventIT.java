// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;

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
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.events.WorkflowEventEnvelope;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.events.binding.WorkflowEventOutboxBinding;
import dev.vertique.workflow.events.compose.WorkflowEventsComposeValidator;
import dev.vertique.workflow.events.recorder.WorkflowEventSideEffectRecorder;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.types.WorkflowType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * Verifies, against a real database and the real event recorder, what a definition with a declared output stores and
 * emits as its instance completes: the output columns of the row, the instance view, the one completion event whose
 * envelope carries the output as a nested object, and a completion history entry that is as it was without an output.
 * A definition without an output stores nothing extra and emits an event with no attributes.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DeclaredOutputEventIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);
    private static final long WAIT_SECONDS = 15;

    private static final String TEST_DESTINATION = "declared-output-event-target";
    private static final WorkflowEventOutboxBinding BINDING =
            new WorkflowEventOutboxBinding(DestinationType.SERVICE, TEST_DESTINATION);

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("workflow_declared_output_event_it");

    static Pool pool;
    static WorkflowEngineHandle engine;

    // --- Domain types ---

    /**
     * The state of the definitions under test.
     *
     * @param orderId the order the instance handles
     * @param lines the number of lines of the order
     */
    record OrderState(String orderId, int lines) {}

    /**
     * What an instance of the definition with an output produces; the type carries a logical name.
     *
     * @param orderId the order the receipt is for
     * @param trackingCode a code derived from the state
     */
    @WorkflowType("declared.output.event.Receipt")
    record Receipt(String orderId, String trackingCode) {}

    interface WithOutputContract {}

    interface WithoutOutputContract {}

    private static final class Def<C> implements WorkflowDefinition<OrderState, C> {
        private final String id;
        private final Class<C> contract;
        private final boolean declareOutput;

        Def(String id, Class<C> contract, boolean declareOutput) {
            this.id = id;
            this.contract = contract;
            this.declareOutput = declareOutput;
        }

        @Override
        public Class<C> contract() {
            return contract;
        }

        @Override
        public Class<OrderState> stateType() {
            return OrderState.class;
        }

        @Override
        public String definitionId() {
            return id;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<OrderState> wf) {
            if (declareOutput) {
                wf.output(Receipt.class, state -> new Receipt(state.orderId(), "TRK-" + state.lines()));
            }
            wf.init(OrderState.class, state -> state).initialStep("done").complete("done");
        }
    }

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("classpath:db/migration/workflow")
                .table("flyway_workflow_history")
                .load()
                .migrate();
        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
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
        PgInboxOutboxRepository outboxRepo = new PgInboxOutboxRepository(pool, exMapper);
        OutboxService outboxService =
                (tx, entry) -> outboxRepo.insert(entry, OutboxMetadata.empty(), UUID.randomUUID(), tx);
        OutboxDestinationHandler handler = new OutboxDestinationHandler() {
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
        WorkflowEventSideEffectRecorder recorder = new WorkflowEventSideEffectRecorder(
                outboxService, BINDING, new WorkflowEventsComposeValidator(Set.of(handler), BINDING));

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(new Def<>("declared-output-event-with", WithOutputContract.class, true));
        registry.register(new Def<>("declared-output-event-without", WithoutOutputContract.class, false));

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                new PgWorkflowInstanceRepository(pool, exMapper),
                new PgWorkflowHistoryRepository(pool, exMapper),
                new PgWorkflowDedupRepository(pool, exMapper),
                Set.of(recorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(exMapper),
                new PgTaskStore(pool, exMapper),
                Clock.systemUTC());
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables() {
        join(WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE outbox, workflow_timers, workflow_tasks, workflow_history,"
                                + " workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
                .execute());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("a definition with an output stores it with COMPLETED, shows it in the view and emits it once")
    void completionStoresShowsAndEmitsTheOutput() {
        // Given a definition that declares a Receipt as its output and an order of three lines
        String key = "declared-output-" + UUID.randomUUID();

        // When an instance starts and runs to its end
        WorkflowInstanceId id = startAndAwait("declared-output-event-with", WithOutputContract.class, "o-1", 3, key);

        // Then the row is COMPLETED with the mapper's value and the logical name of the output class
        Row row = instanceRow(id);
        assertThat(row.getString("status")).isEqualTo("COMPLETED");
        JsonObject expected = new JsonObject().put("orderId", "o-1").put("trackingCode", "TRK-3");
        assertThat(new JsonObject(row.getValue("output_json").toString())).isEqualTo(expected);
        assertThat(row.getString("output_type_name")).isEqualTo("declared.output.event.Receipt");

        // And the instance view shows both
        WorkflowView view = join(engine.query(id));
        assertThat(view.instance().outputTypeName()).isEqualTo("declared.output.event.Receipt");
        assertThat(new JsonObject(view.instance().outputJson())).isEqualTo(expected);

        // And exactly one completion event carries the stored value as a nested object, in an envelope of version 1
        List<Row> outbox = outboxRows(id, WorkflowEventType.WORKFLOW_COMPLETED);
        assertThat(outbox).hasSize(1);
        JsonObject payload = (JsonObject) outbox.getFirst().getValue("payload");
        assertThat(payload.getInteger("schemaVersion")).isEqualTo(1);
        assertThat(payload.getJsonObject("attributes").getValue("output")).isInstanceOf(JsonObject.class);
        assertThat(payload.getJsonObject("attributes").getJsonObject("output")).isEqualTo(expected);
        WorkflowEventEnvelope envelope = payload.mapTo(WorkflowEventEnvelope.class);
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.attributes()).containsOnlyKeys("output");

        // And the completion history entry names the step and carries no output
        List<JsonObject> completed = completedHistory(id);
        assertThat(completed).hasSize(1);
        assertThat(completed.getFirst()).isEqualTo(new JsonObject().put("stepId", "done"));
    }

    @Test
    @DisplayName("a definition without an output stores no output and emits a completion event with no attributes")
    void completionWithoutAnOutputStoresAndEmitsNothingExtra() {
        // Given a definition that declares no output
        String key = "declared-output-" + UUID.randomUUID();

        // When an instance starts and runs to its end
        WorkflowInstanceId id =
                startAndAwait("declared-output-event-without", WithoutOutputContract.class, "o-2", 1, key);

        // Then the row is COMPLETED with both output columns null
        Row row = instanceRow(id);
        assertThat(row.getString("status")).isEqualTo("COMPLETED");
        assertThat(row.getValue("output_json")).isNull();
        assertThat(row.getString("output_type_name")).isNull();
        WorkflowView view = join(engine.query(id));
        assertThat(view.instance().outputJson()).isNull();
        assertThat(view.instance().outputTypeName()).isNull();

        // And the one completion event has an empty attributes map, in an envelope of version 1
        List<Row> outbox = outboxRows(id, WorkflowEventType.WORKFLOW_COMPLETED);
        assertThat(outbox).hasSize(1);
        JsonObject payload = (JsonObject) outbox.getFirst().getValue("payload");
        assertThat(payload.getInteger("schemaVersion")).isEqualTo(1);
        WorkflowEventEnvelope envelope = payload.mapTo(WorkflowEventEnvelope.class);
        assertThat(envelope.attributes() == null || envelope.attributes().isEmpty())
                .isTrue();

        // And the completion history entry is the same as with an output: the step and nothing else
        assertThat(completedHistory(id)).containsExactly(new JsonObject().put("stepId", "done"));
    }

    // --- Helpers ---

    private static WorkflowInstanceId startAndAwait(
            String definitionId, Class<?> contract, String orderId, int lines, String idempotencyKey) {
        StartCommand command = new StartCommand(
                definitionId,
                contract.getName(),
                new OrderState(orderId, lines),
                idempotencyKey,
                null,
                null,
                null,
                null,
                null);
        WorkflowInstanceId id = join(engine.start(command));
        join(engine.executionProbe().awaitIdle(id, IDLE_TIMEOUT));
        return id;
    }

    private static Row instanceRow(WorkflowInstanceId id) {
        return join(pool.preparedQuery("SELECT status, output_json, output_type_name FROM workflow_instances"
                                + " WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next();
    }

    private static List<Row> outboxRows(WorkflowInstanceId id, WorkflowEventType type) {
        List<Row> rows = new ArrayList<>();
        for (Row row :
                join(pool.preparedQuery("SELECT * FROM outbox WHERE aggregate_id = $1 AND event_type = $2 ORDER BY id")
                        .execute(Tuple.of(id.value().toString(), type.name())))) {
            rows.add(row);
        }
        return rows;
    }

    private static List<JsonObject> completedHistory(WorkflowInstanceId id) {
        List<JsonObject> payloads = new ArrayList<>();
        for (Row row : join(pool.preparedQuery("SELECT payload_json FROM workflow_history"
                        + " WHERE workflow_id = $1 AND entry_type = 'COMPLETED' ORDER BY sequence")
                .execute(Tuple.of(id.value())))) {
            payloads.add(row.getJsonObject("payload_json"));
        }
        return payloads;
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future did not complete: " + e, e);
        }
    }
}
