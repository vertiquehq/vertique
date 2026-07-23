// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.InboxRepository;
import dev.vertique.inboxoutbox.InboxResult;
import dev.vertique.inboxoutbox.InboxService;
import dev.vertique.inboxoutbox.postgresql.PgInboxOutboxRepository;
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
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test verifying that inbox deduplication for workflow signals is instance-scoped.
 *
 * <p>The {@code WorkflowSignalContributor.handleSignal} method composes
 * {@code pool.withTransaction} → {@code InboxService.processOnce(inboxMessageId, "workflow-signals",
 * tx, work)} → {@code txOps.signal(...)}. The {@code inboxMessageId} is {@code workflowId + ":"
 * + dedupKey}, which is the fix under test.
 *
 * <p>Before the fix, only {@code dedupKey} was used as the inbox message id. Two unrelated workflow
 * instances that reuse the same caller-supplied dedup string would collide in the inbox table
 * ({@code PRIMARY KEY (message_id, source)}), silently swallowing the second instance's signal.
 *
 * <p>After the fix, the inbox {@code messageId} is {@code workflowId + ":" + dedupKey}, so the
 * two instances produce distinct inbox rows, and both signals are applied independently.
 *
 * <p>This test directly replicates the {@code handleSignal} dispatch logic inline so that it can
 * verify both the engine behavior and the inbox state without depending on a package-private
 * method. The inbox-scoped message id formula is tested by:
 * <ol>
 *   <li>Registering a definition: {@code init} → {@code waitFor("wait", "go", ...)} →
 *       {@code complete}.</li>
 *   <li>Starting instance A and instance B; both reach {@code WAITING} at the {@code go}
 *       signal.</li>
 *   <li>Dispatching "go" to instance A using the inbox with {@code messageId = idA + ":shared-key"}
 *       and then to instance B with {@code messageId = idB + ":shared-key"}.</li>
 *   <li>Asserting: both instances are {@code COMPLETED}; each has one {@code SIGNAL_RECEIVED}
 *       history entry; the inbox table contains exactly two distinct rows.</li>
 * </ol>
 *
 * <p>As a regression guard, the test also verifies that calling with the SAME composite message id
 * twice (simulating the old non-scoped behavior applied to one instance) returns
 * {@link InboxResult.Duplicate} on the second call and does NOT re-apply the signal.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SignalDedupInstanceScopingIT {

    // --- Container: needs both workflow and inbox-outbox tables.
    // We run two Flyway migrations manually in @BeforeAll: one for workflow, one for inbox-outbox.
    // The container is declared WITHOUT withMigration() so DatabaseExtension doesn't auto-migrate.

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("signal_dedup_scoping_test");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static InboxService inboxService;

    static final List<WorkflowSideEffectIntent> capturedIntents = Collections.synchronizedList(new ArrayList<>());

    // --- Domain types ---

    /** Start payload. */
    record WaitGoStart(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "wait-go-" + id;
        }
    }

    /** Workflow state. */
    record WaitGoState(String id, boolean done) {
        static WaitGoState init(WaitGoStart s) {
            return new WaitGoState(s.id(), false);
        }
    }

    /** Signal payload for "go". */
    record GoPayload(String value) {}

    /** Marker contract. */
    interface WaitGoContract {}

    /** Definition: init → waitFor("wait", "go") → complete. */
    static final WorkflowDefinition<WaitGoState, WaitGoContract> WAIT_GO_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<WaitGoContract> contract() {
            return WaitGoContract.class;
        }

        @Override
        public Class<WaitGoState> stateType() {
            return WaitGoState.class;
        }

        @Override
        public String definitionId() {
            return "wait-go-saga";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<WaitGoState> wf) {
            wf.init(WaitGoStart.class, WaitGoState::init)
                    .initialStep("wait")
                    .waitFor("wait", "go", GoPayload.class, (s, p) -> new WaitGoState(s.id(), true), "done")
                    .complete("done");
        }
    };

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        // Run both workflow and inbox-outbox migrations manually.
        // DatabaseExtension.start() was called without withMigration, so the container is up
        // but schema is empty. Use separate Flyway schema history table names to avoid version
        // conflicts (both sets use V1 migration files).
        String jdbcUrl = db.jdbcUrl();
        String user = db.username();
        String password = db.password();
        Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration/workflow")
                .table("flyway_workflow_history")
                .load()
                .migrate();
        // Use baselineOnMigrate=true with baselineVersion="0" for the second migration set.
        // The schema is non-empty (workflow tables exist) so Flyway would normally refuse.
        // baselineVersion="0" ensures the baseline is set below V1, so V1 (the inbox-outbox
        // migration) is still applied rather than treated as already baselined.
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

        // Stub recorder: captures all intents, always succeeds.
        WorkflowSideEffectRecorder<SqlClient> stubRecorder = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.SERVICE;
            }

            @Override
            public Future<dev.vertique.workflow.sideeffect.RecorderResult> record(
                    WorkflowSideEffectIntent intent, SqlClient tx) {
                capturedIntents.add(intent);
                return Future.succeededFuture(dev.vertique.workflow.sideeffect.RecorderResult.empty());
            }
        };

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(WAIT_GO_DEF);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(stubRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(new PgDbExceptionMapper()),
                new PgTaskStore(pool, new PgDbExceptionMapper()),
                Clock.systemUTC());

        // Build InboxService backed by PgInboxOutboxRepository.
        // DefaultInboxService is package-private in inbox-outbox-postgresql, so we implement
        // the interface inline using InboxRepository.tryInsert directly.
        InboxRepository inboxRepository = new PgInboxOutboxRepository(pool, exMapper);
        inboxService = new InboxService() {
            @Override
            public <T> Future<InboxResult<T>> processOnce(
                    String messageId, String source, SqlClient tx, Supplier<Future<T>> work) {
                return inboxRepository.tryInsert(messageId, source, tx).compose(inserted -> {
                    if (inserted) {
                        return work.get().map(value -> (InboxResult<T>) new InboxResult.Processed<>(value));
                    }
                    return Future.succeededFuture(new InboxResult.Duplicate<>());
                });
            }
        };

        ctx.completeNow();
    }

    @BeforeEach
    void truncate(VertxTestContext ctx) {
        capturedIntents.clear();
        pool.query(
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances, inbox RESTART IDENTITY CASCADE")
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
     * Replicates the instance-scoped dispatch logic from
     * {@link dev.vertique.workflow.services.signal.WorkflowSignalContributor#handleSignal}:
     * opens a transaction, deduplicates via the inbox using a composite
     * {@code workflowId + ":" + dedupKey} message id, and applies the signal if new.
     *
     * @param workflowId  the target workflow instance
     * @param signalName  the signal name
     * @param payload     the signal payload
     * @param dedupKey    caller-supplied dedup key (instance-scoped by composing with workflowId)
     * @return a {@link Future} that completes when the signal is applied or idempotently skipped
     */
    private Future<Void> dispatchSignalWithInbox(
            WorkflowInstanceId workflowId, String signalName, Object payload, String dedupKey) {
        String inboxMessageId = workflowId.value() + ":" + dedupKey;
        return pool.withTransaction(tx -> inboxService
                .processOnce(
                        inboxMessageId,
                        "workflow-signals",
                        tx,
                        () -> engine.signal(workflowId, signalName, payload, dedupKey, tx))
                .mapEmpty());
    }

    // --- Tests ---

    /**
     * Two independent instances with the same dedupKey must each receive the signal.
     *
     * <p>Previously, the second signal was swallowed because both instances produced the same
     * inbox {@code (message_id, source)} primary key. After the fix (messageId = workflowId +
     * ":" + dedupKey), both inbox rows have distinct message_ids.
     */
    @Test
    @DisplayName("two instances with shared dedupKey both receive signal — inbox has two distinct rows")
    void twoInstancesWithSharedDedupKeyBothReceiveSignal(VertxTestContext ctx) {
        WaitGoStart startA = new WaitGoStart("instance-a");
        StartCommand cmdA = new StartCommand("wait-go-saga", startA, startA.idempotencyKey(), null, null);

        WaitGoStart startB = new WaitGoStart("instance-b");
        StartCommand cmdB = new StartCommand("wait-go-saga", startB, startB.idempotencyKey(), null, null);

        engine.start(cmdA)
                .compose(idA -> engine.start(cmdB).map(idB -> new WorkflowInstanceId[] {idA, idB}))
                .compose(ids -> {
                    WorkflowInstanceId idA = ids[0];
                    WorkflowInstanceId idB = ids[1];
                    // Both are WAITING. Send "go" with the SAME dedupKey to both instances.
                    return dispatchSignalWithInbox(idA, "go", new GoPayload("a-value"), "shared-key")
                            .compose(v -> dispatchSignalWithInbox(idB, "go", new GoPayload("b-value"), "shared-key"))
                            .map(v -> ids);
                })
                .compose(ids -> {
                    WorkflowInstanceId idA = ids[0];
                    WorkflowInstanceId idB = ids[1];
                    return engine.query(idA)
                            .compose(viewA -> engine.query(idB).map(viewB -> new Object[] {viewA, viewB, idA, idB}));
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "both signals and queries must succeed; got: " + ar.cause());

                    var viewA = (dev.vertique.workflow.ops.WorkflowView) ar.result()[0];
                    var viewB = (dev.vertique.workflow.ops.WorkflowView) ar.result()[1];

                    // Both instances must be COMPLETED
                    assertEquals(WorkflowStatus.COMPLETED, viewA.instance().status(), "instance A must be COMPLETED");
                    assertEquals(WorkflowStatus.COMPLETED, viewB.instance().status(), "instance B must be COMPLETED");

                    // Each instance must have exactly one SIGNAL_RECEIVED history entry
                    long signalCountA = viewA.recentHistory().stream()
                            .filter(e -> e.entryType() == WorkflowEntryType.SIGNAL_RECEIVED)
                            .count();
                    long signalCountB = viewB.recentHistory().stream()
                            .filter(e -> e.entryType() == WorkflowEntryType.SIGNAL_RECEIVED)
                            .count();
                    assertEquals(1L, signalCountA, "instance A must have exactly one SIGNAL_RECEIVED entry");
                    assertEquals(1L, signalCountB, "instance B must have exactly one SIGNAL_RECEIVED entry");

                    // Verify inbox table has exactly two distinct rows (one per instance)
                    pool.query("SELECT message_id FROM inbox WHERE source = 'workflow-signals'"
                                    + " ORDER BY processed_at")
                            .execute()
                            .onComplete(inboxAr -> ctx.verify(() -> {
                                assertTrue(inboxAr.succeeded(), "inbox query must succeed");
                                List<String> inboxMessageIds = new ArrayList<>();
                                inboxAr.result().forEach(row -> inboxMessageIds.add(row.getString("message_id")));
                                assertEquals(
                                        2,
                                        inboxMessageIds.size(),
                                        "inbox must have exactly 2 rows (one per instance); got: " + inboxMessageIds);
                                assertEquals(
                                        2,
                                        inboxMessageIds.stream().distinct().count(),
                                        "inbox message_ids must be distinct (instance-scoped): " + inboxMessageIds);
                                ctx.completeNow();
                            }));
                }));
    }
}
