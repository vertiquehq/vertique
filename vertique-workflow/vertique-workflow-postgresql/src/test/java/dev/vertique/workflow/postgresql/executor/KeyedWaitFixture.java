// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.NodeSpec;
import dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Workflows that wait on a signal for a key computed from their state, and the observations a proof of keyed delivery
 * reads: the open wait records, the signal commands and the dedup rows. It runs on an {@link ExecutorFixture}; every
 * start pins version one of its definition, so definitions of different contracts can share a database.
 */
final class KeyedWaitFixture implements AutoCloseable {

    /** An order that waits for {@code payment-captured} keyed by its payment reference. */
    static final String ORDER = "order";

    /** An order, of the same contract, that waits for {@code refund-issued} keyed by its payment reference. */
    static final String ORDER_REFUND = "order-refund";

    /** An order that waits like {@link #ORDER} and gives up after an hour. */
    static final String ORDER_WITH_TIMEOUT = "order-with-timeout";

    /** An invoice, of another contract, that waits for a signal named like the order's {@code payment-captured}. */
    static final String INVOICE = "invoice";

    /** The scope the order instances start in. */
    static final String SCOPE = "acme";

    /**
     * The start payload of every definition here.
     *
     * @param ref              the label of the instance
     * @param paymentReference the value the wait is keyed by
     */
    record OrderStart(String ref, String paymentReference) {}

    /**
     * The state of every definition here.
     *
     * @param ref              the label of the instance
     * @param paymentReference the value the wait is keyed by
     * @param captured         the reference of the captured payment, once the signal applied
     */
    record OrderState(
            String ref, String paymentReference, @Nullable String captured) {}

    /**
     * The payload of every signal here.
     *
     * @param reference the reference of the payment
     */
    record Payment(String reference) {}

    /** The contract of the order definitions. */
    interface OrderContract {
        @WorkflowSignal("payment-captured")
        Future<Void> paymentCaptured(WorkflowInstanceId id, Payment payload, @SignalDedupKey String key);

        @WorkflowSignal("refund-issued")
        Future<Void> refundIssued(WorkflowInstanceId id, Payment payload, @SignalDedupKey String key);
    }

    /** The contract of the invoice definition. */
    interface InvoiceContract {
        @WorkflowSignal("payment-captured")
        Future<Void> paymentCaptured(WorkflowInstanceId id, Payment payload, @SignalDedupKey String key);
    }

    /**
     * An open wait record, as the tests read it.
     *
     * @param waitId        the identifier of the record
     * @param stepId        the waiting step
     * @param forkStepId    the fork step of a branch, or the empty string on the main path
     * @param branchId      the branch, or the empty string on the main path
     * @param scopeKey      the scope of the instance
     * @param contractName  the contract name the instance's plan was registered under
     * @param definitionId  the definition of the instance
     * @param signalName    the awaited signal
     * @param correlationKey the computed key
     */
    record WaitRecord(
            UUID waitId,
            String stepId,
            String forkStepId,
            String branchId,
            String scopeKey,
            String contractName,
            String definitionId,
            String signalName,
            String correlationKey) {}

    /**
     * A signal command, as the tests read it.
     *
     * @param state    the state of the command
     * @param dedupKey the stored dedup key
     * @param target   the stored target
     */
    record SignalCommand(String state, @Nullable String dedupKey, JsonObject target) {}

    /** The fixture the nodes, pools and tables belong to. */
    final ExecutorFixture fixture;

    private final DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
    private final PgWorkflowDefinitionRegistryStore store;

    private KeyedWaitFixture(DbPoolConfig db) {
        this.fixture = ExecutorFixture.open(db);
        try {
            store = new PgWorkflowDefinitionRegistryStore(
                    fixture.observer(), new PgDbExceptionMapper(), Optional.empty());
            seed(order(ORDER, false), List.of("payment-captured", "refund-issued"));
            seed(order(ORDER_WITH_TIMEOUT, true), List.of("payment-captured", "refund-issued"));
            seed(refundOrder(), List.of("payment-captured", "refund-issued"));
            seed(invoice(), List.of("payment-captured"));
        } catch (RuntimeException | AssertionError e) {
            fixture.close();
            throw e;
        }
    }

    /**
     * Opens the fixture over the database and registers every definition.
     *
     * @param db the database under test, already migrated
     * @return the fixture
     */
    static KeyedWaitFixture open(DbPoolConfig db) {
        return new KeyedWaitFixture(db);
    }

    /** The description of an engine and its executor over the registered definitions. */
    NodeSpec node(String name) {
        return fixture.node(name).pollIntervalMs(100).withRegistry(registry).definitionStore(store);
    }

    /** The contract name the registered definition's plan carries. */
    String contractNameOf(String definitionId) {
        return runtimeOf(definitionId).contractName();
    }

    /**
     * Records, and commits, the start of {@link #ORDER} in the order scope, keyed by the payment reference.
     *
     * @param node             the node whose engine records the start
     * @param paymentReference the value the wait is keyed by
     * @return the instance
     */
    WorkflowInstanceId startKeyed(Node node, String paymentReference) {
        return start(node, ORDER, paymentReference, SCOPE);
    }

    /**
     * Records, and commits, the start of a definition, keyed by the payment reference.
     *
     * @param node             the node whose engine records the start
     * @param definitionId     the definition to start
     * @param paymentReference the value the wait is keyed by
     * @param scope            the start scope, or {@code null} for the default scope
     * @return the instance
     */
    WorkflowInstanceId start(Node node, String definitionId, String paymentReference, @Nullable String scope) {
        String ref = definitionId + "-" + paymentReference + "-" + UUID.randomUUID();
        StartCommand command = new StartCommand(
                definitionId,
                runtimeOf(definitionId).contractName(),
                new OrderStart(ref, paymentReference),
                "idem-" + ref,
                null,
                null,
                1L,
                scope,
                null);
        return await(caller(() -> node.pool.withTransaction(tx -> node.engine.start(command, tx))));
    }

    /**
     * Delivers the signal by key in a caller transaction and commits it.
     *
     * @param node   the node whose engine delivers
     * @param signal the signal
     * @return the receipt
     */
    CorrelatedSignalReceipt deliverByKey(Node node, CorrelatedSignal signal) {
        return await(caller(() -> node.pool.withTransaction(tx -> node.engine.signalByKey(signal, tx))));
    }

    /** Records, and commits, a signal addressed to the instance. */
    void signalInstance(Node node, WorkflowInstanceId id, String signalName, Object payload, String dedupKey) {
        await(caller(() -> node.pool.withTransaction(tx -> node.engine.signal(id, signalName, payload, dedupKey, tx))));
    }

    /** The open wait records of the instance, read on a connection of their own, oldest first. */
    List<WaitRecord> waitRecords(WorkflowInstanceId id) {
        List<WaitRecord> records = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT wait_id, step_id, fork_step_id, branch_id, scope_key, contract_name,"
                        + " definition_id, signal_name, correlation_key FROM workflow_signal_waits"
                        + " WHERE instance_id = $1 ORDER BY created_at, step_id")
                .execute(Tuple.of(id.value())))) {
            records.add(new WaitRecord(
                    row.getUUID("wait_id"),
                    row.getString("step_id"),
                    row.getString("fork_step_id"),
                    row.getString("branch_id"),
                    row.getString("scope_key"),
                    row.getString("contract_name"),
                    row.getString("definition_id"),
                    row.getString("signal_name"),
                    row.getString("correlation_key")));
        }
        return records;
    }

    /** The signal commands of the instance in queue order. */
    List<SignalCommand> signalCommands(WorkflowInstanceId id) {
        List<SignalCommand> commands = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT state, dedup_key, target_json FROM workflow_commands"
                        + " WHERE workflow_id = $1 AND kind = 'SIGNAL' ORDER BY seq")
                .execute(Tuple.of(id.value())))) {
            commands.add(new SignalCommand(
                    row.getString("state"), row.getString("dedup_key"), (JsonObject) row.getValue("target_json")));
        }
        return commands;
    }

    /** The number of dedup rows of the instance whose kind is the given one. */
    long dedupRows(WorkflowInstanceId id, String kind) {
        return count(
                "SELECT count(*) FROM workflow_dedup WHERE workflow_id = $1 AND kind = $2", Tuple.of(id.value(), kind));
    }

    /** The number of dedup rows of the instance whose kind is neither a start nor an instance-addressed signal. */
    long correlatedDedupRows(WorkflowInstanceId id) {
        return count(
                "SELECT count(*) FROM workflow_dedup WHERE workflow_id = $1 AND kind NOT IN ('start', 'signal')",
                Tuple.of(id.value()));
    }

    /** The number of dedup rows of kind {@code signal} that the instance holds for the key. */
    long signalDedupRows(WorkflowInstanceId id, String key) {
        return count(
                "SELECT count(*) FROM workflow_dedup WHERE workflow_id = $1 AND kind = 'signal' AND key = $2",
                Tuple.of(id.value(), key));
    }

    /** The timer the instance's wait carries, or {@code null} when it carries none. */
    @Nullable
    UUID timeoutTimerOf(WorkflowInstanceId id) {
        Row row = await(fixture.observer()
                        .preparedQuery("SELECT wait_aux_id FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next();
        return row.getUUID("wait_aux_id");
    }

    private long count(String sql, Tuple parameters) {
        return await(fixture.observer().preparedQuery(sql).execute(parameters))
                .iterator()
                .next()
                .getLong(0);
    }

    /** Runs the work on a context of its own, as a caller outside the engine's would. */
    <T> Future<T> caller(Supplier<Future<T>> work) {
        Promise<T> result = Promise.promise();
        ContextInternal context = ((ContextInternal) fixture.vertx().getOrCreateContext()).duplicate();
        context.runOnContext(ignored -> {
            try {
                work.get().onComplete(result);
            } catch (Throwable e) {
                result.tryFail(e);
            }
        });
        return result.future();
    }

    @Override
    public void close() {
        fixture.close();
    }

    private RuntimeWorkflow runtimeOf(String definitionId) {
        return registry.codeDefinitions().stream()
                .filter(candidate -> candidate.plan().definitionId().equals(definitionId))
                .findFirst()
                .orElseThrow();
    }

    private void seed(Definition<?> code, List<String> signalNames) {
        registry.register(code);
        RuntimeWorkflow runtime = runtimeOf(code.definitionId());
        Instant now = Instant.now();
        await(store.upsertCode(
                new WorkflowDefinitionRecord(
                        new DefinitionKey(WorkflowScopes.DEFAULT, code.definitionId(), 1),
                        DefinitionKind.CODE,
                        runtime.contractName(),
                        runtime.plan().stateTypeName(),
                        runtime.plan().planHash(),
                        DefinitionStatus.AVAILABLE,
                        new DefinitionProvenance("code", "test", null, null, null, now),
                        null,
                        now,
                        now,
                        Map.of(),
                        null,
                        new JsonObject()
                                .put("instance", new JsonArray(signalNames))
                                .put("branches", new JsonObject()),
                        null),
                true));
    }

    private static Definition<OrderContract> order(String id, boolean withTimeout) {
        return new Definition<>(id, OrderContract.class, wf -> {
            wf.initialStep("wait-payment");
            if (withTimeout) {
                wf.waitForSignal("wait-payment", "payment-captured", Payment.class)
                        .correlateBy(OrderState::paymentReference)
                        .onSignal((state, payment) ->
                                new OrderState(state.ref(), state.paymentReference(), payment.reference()))
                        .toStepOnSignal("done")
                        .timeoutAfter(Duration.ofHours(1))
                        .onTimeout(state -> state)
                        .toStepOnTimeout("timed-out");
                wf.complete("timed-out");
            } else {
                wf.waitForSignal("wait-payment", "payment-captured", Payment.class)
                        .correlateBy(OrderState::paymentReference)
                        .onSignal((state, payment) ->
                                new OrderState(state.ref(), state.paymentReference(), payment.reference()))
                        .toStepOnSignal("done")
                        .build();
            }
            wf.complete("done");
        });
    }

    private static Definition<OrderContract> refundOrder() {
        return new Definition<>(ORDER_REFUND, OrderContract.class, wf -> {
            wf.initialStep("wait-refund");
            wf.waitForSignal("wait-refund", "refund-issued", Payment.class)
                    .correlateBy(OrderState::paymentReference)
                    .onSignal((state, payment) ->
                            new OrderState(state.ref(), state.paymentReference(), payment.reference()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
    }

    private static Definition<InvoiceContract> invoice() {
        return new Definition<>(INVOICE, InvoiceContract.class, wf -> {
            wf.initialStep("wait-payment");
            wf.waitForSignal("wait-payment", "payment-captured", Payment.class)
                    .correlateBy(OrderState::paymentReference)
                    .onSignal((state, payment) ->
                            new OrderState(state.ref(), state.paymentReference(), payment.reference()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
    }

    /** A code-first definition over the shared state, start payload and a contract. */
    private static final class Definition<C> implements WorkflowDefinition<OrderState, C> {
        private final String id;
        private final Class<C> contract;
        private final Consumer<WorkflowBuilder<OrderState>> body;

        Definition(String id, Class<C> contract, Consumer<WorkflowBuilder<OrderState>> body) {
            this.id = id;
            this.contract = contract;
            this.body = body;
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
            wf.init(OrderStart.class, start -> new OrderState(start.ref(), start.paymentReference(), null));
            body.accept(wf);
        }
    }
}
