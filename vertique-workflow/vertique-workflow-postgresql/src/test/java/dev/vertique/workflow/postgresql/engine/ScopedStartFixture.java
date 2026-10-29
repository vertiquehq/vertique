// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.workflow.client.WorkflowClientFactory;
import dev.vertique.workflow.contract.BusinessKey;
import dev.vertique.workflow.contract.IdempotencyKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.engine.testsupport.ScopedDefinitions;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Definitions, clients and database readers shared by the scoped start proofs.
 *
 * <p>Every definition of the fixture implements the {@code purchase} contract, starts at once into a single
 * completing step and takes a {@link StartPurchase}. The completing step carries a name chosen by the declaration,
 * so two declarations of one id and version differ in plan hash and an instance's pinned plan can be told from
 * another's.
 */
final class ScopedStartFixture {

    static final String ID = "purchase-approval";
    static final String ACME = "acme";
    static final String GLOBEX = "globex";
    static final String DEFAULT = "default";
    static final String PURCHASE_CONTRACT = "purchase";

    private ScopedStartFixture() {}

    /** Start payload of every definition. */
    record StartPurchase(String key) {}

    /** State of every definition. */
    record PurchaseState(String key) {}

    /** The contract of every definition; a client starts through it with its own idempotency and business key. */
    @WorkflowContract("purchase")
    interface PurchaseContract {

        @WorkflowStart
        Future<WorkflowInstanceId> start(
                @IdempotencyKey String idempotencyKey, @BusinessKey String businessKey, StartPurchase payload);
    }

    /** A second contract that no definition of the fixture implements. */
    @WorkflowContract("other")
    interface OtherContract {

        @WorkflowStart
        Future<WorkflowInstanceId> start(
                @IdempotencyKey String idempotencyKey, @BusinessKey String businessKey, StartPurchase payload);
    }

    /** A definition that completes at once in the step named by the declaration. */
    static final class Purchase implements WorkflowDefinition<PurchaseState, PurchaseContract> {
        private final String definitionId;
        private final long definitionVersion;
        private final String completingStep;

        Purchase(String definitionId, long definitionVersion, String completingStep) {
            this.definitionId = definitionId;
            this.definitionVersion = definitionVersion;
            this.completingStep = completingStep;
        }

        @Override
        public Class<PurchaseContract> contract() {
            return PurchaseContract.class;
        }

        @Override
        public Class<PurchaseState> stateType() {
            return PurchaseState.class;
        }

        @Override
        public String definitionId() {
            return definitionId;
        }

        @Override
        public long definitionVersion() {
            return definitionVersion;
        }

        @Override
        public void define(WorkflowBuilder<PurchaseState> wf) {
            wf.init(StartPurchase.class, p -> new PurchaseState(p.key()))
                    .initialStep(completingStep)
                    .complete(completingStep);
        }
    }

    /**
     * An engine over a pool, the registry it resolves from, a client factory over both, and the stored versions of the
     * scopes other than the default one, which the registry does not hold.
     */
    record Harness(
            WorkflowEngineHandle engine,
            DefaultWorkflowRegistry registry,
            WorkflowClientFactory factory,
            ScopedDefinitions scoped) {}

    /**
     * Builds an engine over the pool with a fresh registry that holds the given definitions in the default scope.
     *
     * @param pool the pool of the database
     * @param definitions the definitions to register in the default scope
     * @return the harness
     */
    static Harness open(Pool pool, WorkflowDefinition<?, ?>... definitions) {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        for (WorkflowDefinition<?, ?> definition : definitions) {
            registry.register(definition);
        }
        PgDbExceptionMapper exMapper = new PgDbExceptionMapper();
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
        return new Harness(
                engine, registry, new WorkflowClientFactory(engine, registry), ScopedDefinitions.of(registry));
    }

    /** Empties every table a start writes. */
    static void truncate(Pool pool) throws Exception {
        await(WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances"
                                + " RESTART IDENTITY CASCADE")
                .execute());
    }

    /** Waits for a future on the calling test thread. */
    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /**
     * Runs an operation that returns a future and returns what it failed with, whether the failure was thrown at
     * the call or delivered through the future.
     *
     * @param operation the operation to run
     * @return the failure
     */
    static Throwable failureOf(Callable<Future<?>> operation) throws Exception {
        try {
            await(operation.call());
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("the operation was expected to fail");
    }

    /** Counts the rows of the instance table. */
    static long instanceRows(Pool pool) throws Exception {
        return count(pool, "SELECT count(*) AS c FROM workflow_instances");
    }

    /** Counts the start dedup rows of the dedup table. */
    static long startDedupRows(Pool pool) throws Exception {
        return count(pool, "SELECT count(*) AS c FROM workflow_dedup WHERE kind = 'start'");
    }

    /**
     * Reads the start dedup rows of one idempotency key as {@code scope_key|dedup_namespace}, ordered by scope.
     *
     * @param pool the pool of the database
     * @param idempotencyKey the idempotency key
     * @return one entry per row
     */
    static List<String> startDedupRowsOf(Pool pool, String idempotencyKey) throws Exception {
        RowSet<Row> rows = await(pool.preparedQuery("SELECT scope_key, dedup_namespace FROM workflow_dedup"
                        + " WHERE kind = 'start' AND key = $1 ORDER BY scope_key")
                .execute(Tuple.of(idempotencyKey)));
        List<String> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(row.getString("scope_key") + "|" + row.getString("dedup_namespace"));
        }
        return result;
    }

    private static long count(Pool pool, String sql) throws Exception {
        return await(
                pool.query(sql).execute().map(rows -> rows.iterator().next().getLong("c")));
    }
}
