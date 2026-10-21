// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.client.WorkflowClientFactory;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.contract.SignalDedupKeyed;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowQuery;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
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
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for signals and queries sent to instances pinned to a definition version:
 * a signal the pinned version does not wait for is refused naming that version, an awaited signal
 * is delivered on every pinned version of a contract, and a client of another contract cannot
 * signal or query the instance. A refusal changes nothing the instance or the database holds.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SignalByPinIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("signal_by_pin_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;

    // --- Fixture types ---

    record ApprovalStart(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "approval-start-" + id;
        }
    }

    record ApprovalState(String id, String decision) {}

    record ApprovalSignal(String note) implements SignalDedupKeyed {
        @Override
        public String dedupKey() {
            return "approval-signal-" + note;
        }
    }

    /** Contract {@code approval}: declares both signals; the pinned version decides which it awaits. */
    @WorkflowContract("approval")
    interface ApprovalContract {

        @WorkflowStart
        Future<WorkflowInstanceId> start(ApprovalStart payload);

        @WorkflowSignal("approve")
        Future<Void> approve(WorkflowInstanceId id, ApprovalSignal payload);

        @WorkflowSignal("escalate")
        Future<Void> escalate(WorkflowInstanceId id, ApprovalSignal payload);

        @WorkflowQuery("view")
        Future<WorkflowView> view(WorkflowInstanceId id);
    }

    /** Contract {@code other}: declares {@code approve} with the same payload type as {@code approval}. */
    @WorkflowContract("other")
    interface OtherContract {

        @WorkflowStart
        Future<WorkflowInstanceId> start(ApprovalStart payload);

        @WorkflowSignal("approve")
        Future<Void> approve(WorkflowInstanceId id, ApprovalSignal payload);

        @WorkflowQuery("view")
        Future<WorkflowView> view(WorkflowInstanceId id);
    }

    /** Definition that waits for one signal and then completes; each subclass is a distinct class. */
    abstract static class SingleWaitDefinition<C> implements WorkflowDefinition<ApprovalState, C> {

        private final Class<C> contract;
        private final String id;
        private final long version;
        private final String signal;

        SingleWaitDefinition(Class<C> contract, String id, long version, String signal) {
            this.contract = contract;
            this.id = id;
            this.version = version;
            this.signal = signal;
        }

        @Override
        public Class<C> contract() {
            return contract;
        }

        @Override
        public Class<ApprovalState> stateType() {
            return ApprovalState.class;
        }

        @Override
        public String definitionId() {
            return id;
        }

        @Override
        public long definitionVersion() {
            return version;
        }

        @Override
        public void define(WorkflowBuilder<ApprovalState> wf) {
            wf.init(ApprovalStart.class, start -> new ApprovalState(start.id(), "pending"))
                    .initialStep("wait")
                    .waitForSignal("wait", signal, ApprovalSignal.class)
                    .onSignal((state, payload) -> new ApprovalState(state.id(), payload.note()))
                    .toStepOnSignal("done")
                    .build()
                    .complete("done");
        }
    }

    /** {@code purchase-approval} v1 waits for {@code approve}. */
    static final class PurchaseApprovalV1 extends SingleWaitDefinition<ApprovalContract> {
        PurchaseApprovalV1() {
            super(ApprovalContract.class, "purchase-approval", 1L, "approve");
        }
    }

    /** {@code purchase-approval} v2 waits for {@code escalate}, under the same contract as v1. */
    static final class PurchaseApprovalV2 extends SingleWaitDefinition<ApprovalContract> {
        PurchaseApprovalV2() {
            super(ApprovalContract.class, "purchase-approval", 2L, "escalate");
        }
    }

    /** {@code refund-flow} v1 of the {@code other} contract waits for {@code approve}. */
    static final class RefundFlowV1 extends SingleWaitDefinition<OtherContract> {
        RefundFlowV1() {
            super(OtherContract.class, "refund-flow", 1L, "approve");
        }
    }

    /** An engine over the shared pool with the given definitions registered, and a client factory. */
    record Harness(WorkflowEngineHandle engine, WorkflowClientFactory factory) {}

    /** What an instance and its database rows hold; a refused operation must leave it equal. */
    record Snapshot(
            WorkflowStatus status,
            String waitKey,
            String stateJson,
            long instanceVersion,
            int historyLength,
            long dedupRows,
            long timerRows) {}

    @BeforeAll
    static void setUp(Vertx vertx) {
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
    }

    @BeforeEach
    void truncateTables() throws Exception {
        await(pool.query("TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances"
                        + " RESTART IDENTITY CASCADE")
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
    @DisplayName("a signal the pinned version does not await fails naming that version and changes nothing")
    void signalNotAwaitedByPinnedVersionFailsNamingVersion() throws Exception {
        // Given: only purchase-approval v1 is registered; it waits for approve, not for escalate
        Harness harness = open(new PurchaseApprovalV1());
        WorkflowInstanceId id = startPinned(harness, "purchase-approval", "approval", 1L, "state-marker-4");
        Snapshot before = snapshot(harness, id);
        assertThat(before.status()).isEqualTo(WorkflowStatus.WAITING);
        assertThat(before.waitKey()).isEqualTo("approve");

        // When: a client of the contract signals escalate; creating that client does not throw
        ApprovalContract client = harness.factory().create(ApprovalContract.class, "purchase-approval");
        Throwable failure = failureOf(client.escalate(id, new ApprovalSignal("payload-marker-4")));

        // Then: the signal is refused naming the instance, the signal and the pinned version
        assertThat(failure)
                .isInstanceOf(WorkflowSignalRejectedException.class)
                .hasMessageContaining("'purchase-approval' v1")
                .hasMessageContaining("escalate")
                .hasMessageContaining(id.value().toString())
                .message()
                .doesNotContain("payload-marker-4")
                .doesNotContain("state-marker-4");

        // and: the instance, its history, the dedup rows and the timers are unchanged
        assertThat(snapshot(harness, id)).isEqualTo(before);
    }

    @Test
    @DisplayName("an awaited signal is delivered on each pinned version of one contract")
    void awaitedSignalIsDeliveredOnPinnedVersion() throws Exception {
        // Given: purchase-approval v1 (waits for approve) and v2 (waits for escalate) under one
        // contract, and one instance pinned to each
        Harness harness = open(new PurchaseApprovalV1(), new PurchaseApprovalV2());
        WorkflowInstanceId pinnedToV1 = startPinned(harness, "purchase-approval", "approval", 1L, "state-v1");
        WorkflowInstanceId pinnedToV2 = startPinned(harness, "purchase-approval", "approval", 2L, "state-v2");
        assertThat(snapshot(harness, pinnedToV1).waitKey()).isEqualTo("approve");
        assertThat(snapshot(harness, pinnedToV2).waitKey()).isEqualTo("escalate");

        // When: the client sends approve to the v1 instance and escalate to the v2 instance
        ApprovalContract client = harness.factory().create(ApprovalContract.class, "purchase-approval");
        await(client.approve(pinnedToV1, new ApprovalSignal("v1-decision")));
        await(client.escalate(pinnedToV2, new ApprovalSignal("v2-decision")));

        // Then: both instances completed and each history records its signal
        WorkflowView v1 = await(harness.engine().query(pinnedToV1));
        WorkflowView v2 = await(harness.engine().query(pinnedToV2));
        assertThat(v1.instance().status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(v2.instance().status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(v1.instance().definitionVersion()).isEqualTo(1L);
        assertThat(v2.instance().definitionVersion()).isEqualTo(2L);
        assertThat(signalHistory(v1)).singleElement().asString().contains("approve", "v1-decision");
        assertThat(signalHistory(v2)).singleElement().asString().contains("escalate", "v2-decision");
    }

    @Test
    @DisplayName("a client of another contract can neither signal nor query the instance")
    void signalAndQueryThroughAnotherContractsClientAreRefused() throws Exception {
        // Given: purchase-approval v1 of contract approval with one waiting instance, and refund-flow
        // v1 of contract other, which declares approve with the same payload type
        Harness harness = open(new PurchaseApprovalV1(), new RefundFlowV1());
        WorkflowInstanceId id = startPinned(harness, "purchase-approval", "approval", 1L, "state-marker-7");
        Snapshot before = snapshot(harness, id);
        OtherContract foreign = harness.factory().create(OtherContract.class, "refund-flow");
        ApprovalContract own = harness.factory().create(ApprovalContract.class, "purchase-approval");

        // When: the foreign client sends approve to the purchase-approval instance
        Throwable signalFailure = failureOf(foreign.approve(id, new ApprovalSignal("payload-marker-7")));

        // Then: it is refused naming the instance's version and both contracts, and nothing changes
        assertContractMismatch(signalFailure, "payload-marker-7", "state-marker-7");
        assertThat(snapshot(harness, id)).as("after the refused signal").isEqualTo(before);

        // When: the foreign client queries the instance
        Throwable queryFailure = failureOf(foreign.view(id));

        // Then: it is refused the same way and nothing changes
        assertContractMismatch(queryFailure, "payload-marker-7", "state-marker-7");
        assertThat(snapshot(harness, id)).as("after the refused query").isEqualTo(before);

        // When: the instance's own client queries and then signals it
        WorkflowView viaOwnClient = await(own.view(id));
        await(own.approve(id, new ApprovalSignal("own-decision")));

        // Then: both succeed
        assertThat(viaOwnClient.instance().id()).isEqualTo(id);
        assertThat(viaOwnClient.instance().status()).isEqualTo(WorkflowStatus.WAITING);
        assertThat(snapshot(harness, id).status()).isEqualTo(WorkflowStatus.COMPLETED);

        // and: the contract-less query on the engine handle still returns the view
        WorkflowView viaEngine = await(harness.engine().query(id));
        assertThat(viaEngine.instance().id()).isEqualTo(id);
        assertThat(viaEngine.instance().status()).isEqualTo(WorkflowStatus.COMPLETED);
    }

    // --- Helpers ---

    private static void assertContractMismatch(Throwable failure, String... forbiddenInMessage) {
        assertThat(failure)
                .isInstanceOf(WorkflowDefinitionContractMismatchException.class)
                .hasMessageContaining("'purchase-approval' v1")
                .hasMessageContaining("contract 'approval'")
                .hasMessageContaining("contract 'other'");
        WorkflowDefinitionContractMismatchException mismatch = (WorkflowDefinitionContractMismatchException) failure;
        assertThat(mismatch.definitionKey().definitionId()).isEqualTo("purchase-approval");
        assertThat(mismatch.definitionKey().definitionVersion()).isEqualTo(1L);
        for (String forbidden : forbiddenInMessage) {
            assertThat(mismatch.getMessage()).doesNotContain(forbidden);
        }
    }

    /** Builds an engine over the shared pool with the given definitions registered. */
    private static Harness open(WorkflowDefinition<?, ?>... definitions) {
        PgDbExceptionMapper exMapper = new PgDbExceptionMapper();
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        for (WorkflowDefinition<?, ?> definition : definitions) {
            registry.register(definition);
        }
        WorkflowEngineHandle engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                new PgWorkflowInstanceRepository(pool, exMapper),
                new PgWorkflowHistoryRepository(pool, exMapper),
                new PgWorkflowDedupRepository(pool, exMapper),
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(new PgDbExceptionMapper()),
                new PgTaskStore(pool, new PgDbExceptionMapper()),
                Clock.systemUTC());
        return new Harness(engine, new WorkflowClientFactory(engine, registry));
    }

    /** Starts an instance pinned to {@code version}; the instance waits for the version's signal. */
    private static WorkflowInstanceId startPinned(
            Harness harness, String definitionId, String contractName, long version, String stateMarker)
            throws Exception {
        ApprovalStart payload = new ApprovalStart(stateMarker + "-" + UUID.randomUUID());
        StartCommand command = new StartCommand(
                definitionId, contractName, payload, payload.idempotencyKey(), null, null, version, null, null);
        WorkflowInstanceId id = await(harness.engine().start(command));
        assertThat(snapshot(harness, id).status()).isEqualTo(WorkflowStatus.WAITING);
        return id;
    }

    /** Reads the instance, its history and its dedup and timer rows as one comparable value. */
    private static Snapshot snapshot(Harness harness, WorkflowInstanceId id) throws Exception {
        WorkflowView view = await(harness.engine().query(id));
        WorkflowInstance instance = view.instance();
        long dedupRows = count("workflow_dedup", id);
        long timerRows = count("workflow_timers", id);
        return new Snapshot(
                instance.status(),
                instance.waitKey(),
                instance.stateJson(),
                instance.version(),
                view.recentHistory().size(),
                dedupRows,
                timerRows);
    }

    private static long count(String table, WorkflowInstanceId id) throws Exception {
        return await(pool.preparedQuery("SELECT count(*) AS c FROM " + table + " WHERE workflow_id = $1")
                .execute(Tuple.of(id.value()))
                .map(rows -> rows.iterator().next().getLong("c")));
    }

    private static List<String> signalHistory(WorkflowView view) {
        return view.recentHistory().stream()
                .filter(entry -> entry.entryType() == WorkflowEntryType.SIGNAL_RECEIVED)
                .map(entry -> entry.payloadJson())
                .toList();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            await(future);
        } catch (ExecutionException e) {
            return e.getCause();
        }
        return fail("the operation was expected to fail");
    }
}
