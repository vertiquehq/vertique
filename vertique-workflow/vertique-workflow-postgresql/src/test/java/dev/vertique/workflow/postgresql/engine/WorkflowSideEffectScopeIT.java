// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.inboxoutbox.postgresql.PgInboxOutboxRepository;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.services.recorder.OutboxSideEffectRecorder;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Integration test verifying that the service side effect of a workflow instance is written to the outbox in the
 * scope the instance was started in.
 *
 * <p>A real {@link OutboxSideEffectRecorder} writes through the outbox repository on a real Testcontainers Postgres
 * instance, in the same transaction as the workflow state. The test starts an instance in scope {@code acme}, lets
 * it reach a service-dispatch step and reads the {@code outbox} table directly.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class WorkflowSideEffectScopeIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    private static final String TARGET_ID = "integration.purchase.notify";

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("workflow_side_effect_scope_test");

    static Pool pool;
    static WorkflowEngineHandle engine;

    // --- Domain types ---

    /**
     * Minimal workflow state for the side-effect scope test.
     *
     * @param id the unique test identifier
     */
    record State(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "side-effect-scope-it-" + id;
        }
    }

    /** Contract for a workflow that dispatches to a service and completes. */
    interface DispatchContract {}

    /** A workflow that dispatches once to {@link #TARGET_ID} and then completes. */
    static final WorkflowDefinition<State, DispatchContract> DISPATCH_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<DispatchContract> contract() {
            return DispatchContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "side-effect-scope-dispatch";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(State.class, s -> s)
                    .initialStep("notify")
                    .dispatch("notify", TARGET_ID, State::id, "done")
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
        PgWorkflowInstanceRepository instanceRepo = new PgWorkflowInstanceRepository(pool, exMapper);
        PgWorkflowHistoryRepository historyRepo = new PgWorkflowHistoryRepository(pool, exMapper);
        PgWorkflowDedupRepository dedupRepo = new PgWorkflowDedupRepository(pool, exMapper);
        PgTimerStore timerStore = new PgTimerStore(exMapper);

        // --- Outbox infrastructure ---
        PgInboxOutboxRepository outboxRepo = new PgInboxOutboxRepository(pool, exMapper);
        OutboxService outboxService =
                (tx, entry) -> outboxRepo.insert(entry, OutboxMetadata.empty(), UUID.randomUUID(), tx);

        // --- Registry ---
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(DISPATCH_DEF);

        // --- SERVICE recorder over a resolver that knows the one target ---
        WorkflowSideEffectRecorder<SqlClient> serviceRecorder =
                new OutboxSideEffectRecorder(outboxService, resolverFor(TARGET_ID));

        // --- Engine: workflow events have no recorder here, so that kind is optional ---
        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(serviceRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                new PgTaskStore(pool, exMapper),
                Clock.fixed(Instant.parse("2026-05-09T00:00:00Z"), ZoneOffset.UTC));

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE outbox, workflow_timers, workflow_tasks, workflow_history,"
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

    // --- Tests ---

    /** An instance started in scope {@code acme} writes the row of its service side effect in scope {@code acme}. */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("service side-effect rows are in the instance's start scope")
    void sideEffectRowsAreInStartScope() throws Exception {
        // Given: a definition of the default scope, and a start command that names scope acme
        State state = new State("acme-" + UUID.randomUUID());
        StartCommand command = new StartCommand(
                DISPATCH_DEF.definitionId(),
                DispatchContract.class.getName(),
                state,
                state.idempotencyKey(),
                null,
                null,
                null,
                "acme",
                null);

        // When: the instance starts and reaches the service-dispatch step
        WorkflowInstanceId id = await(engine.start(command));
        await(engine.executionProbe().awaitIdle(id, IDLE_TIMEOUT));

        // Then: the one service row of the instance is in scope acme
        List<Row> rows = await(readServiceRows(id));
        assertEquals(1, rows.size(), "one service side-effect row is written");
        assertEquals("acme", rows.get(0).getString("scope_key"));
    }

    // --- Helpers ---

    /** Builds a resolver that resolves {@code targetId} to a request/reply operation with one payload parameter. */
    private static ServiceTargetResolver resolverFor(String targetId) {
        ServiceMethodMeta meta = mock(ServiceMethodMeta.class);
        when(meta.oneWay()).thenReturn(false);
        doReturn(Void.class).when(meta).returnType();
        when(meta.params()).thenReturn(List.of(new ParamMeta("req", ParamSource.PAYLOAD, Object.class, null)));
        ResolvedServiceTarget target = mock(ResolvedServiceTarget.class);
        when(target.meta()).thenReturn(meta);
        when(target.targetId()).thenReturn(targetId);
        when(target.operation()).thenReturn("notify");
        ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
        when(resolver.resolve(targetId)).thenReturn(target);
        return resolver;
    }

    /** Reads the service rows of the instance from the {@code outbox} table. */
    private static Future<List<Row>> readServiceRows(WorkflowInstanceId id) {
        return pool.preparedQuery(
                        "SELECT * FROM outbox WHERE aggregate_id = $1 AND destination_type = 'SERVICE' ORDER BY id ASC")
                .execute(Tuple.of(id.value().toString()))
                .map(rs -> {
                    List<Row> rows = new ArrayList<>();
                    for (Row row : rs) {
                        rows.add(row);
                    }
                    return rows;
                });
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
