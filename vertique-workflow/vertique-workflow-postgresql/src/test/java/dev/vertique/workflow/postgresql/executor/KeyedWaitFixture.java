// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
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
import dev.vertique.workflow.plan.RaceSafety;
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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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

    /** An order that waits like {@link #ORDER} for {@code a-done}, then for {@code b-done} keyed by what the first signal set. */
    static final String CHAIN = "order-chain";

    /**
     * A fork of two branches: {@code b1} waits at {@code wait-a} for {@code shipment-confirmed} keyed by {@code refA}
     * and {@code b2} waits at {@code wait-b} for {@code label-printed} keyed by {@code refB}.
     */
    static final String FORK = "order-fork";

    /** The fork of {@link #FORK} whose wait at {@code wait-a} gives up after an hour. */
    static final String FORK_WITH_TIMEOUT = "order-fork-with-timeout";

    /** The value inside the message of the throwing accessor of {@link KeyBehaviour#THROWING}. */
    static final String SECRET = "SECRET-KEY-7";

    /** The key of {@link KeyBehaviour#NUL}, which holds a NUL character. */
    static final String NUL_KEY = "PAY-\0-7";

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

    /**
     * The start payload of the fork definitions.
     *
     * @param ref  the label of the instance
     * @param refA the value the wait of branch {@code b1} is keyed by
     * @param refB the value the wait of branch {@code b2} is keyed by
     */
    record ForkStart(String ref, String refA, String refB) {}

    /**
     * The state of the fork definitions.
     *
     * @param ref     the label of the instance
     * @param refA    the value the wait of branch {@code b1} is keyed by
     * @param refB    the value the wait of branch {@code b2} is keyed by
     * @param shipped the reference of the shipment, once {@code shipment-confirmed} applied
     * @param printed the reference of the label, once {@code label-printed} applied
     */
    record ForkState(
            String ref,
            String refA,
            String refB,
            @Nullable String shipped,
            @Nullable String printed) {}

    /** The contract of the fork definitions. */
    interface ForkContract {
        @WorkflowSignal("shipment-confirmed")
        Future<Void> shipmentConfirmed(WorkflowInstanceId id, Payment payload, @SignalDedupKey String key);

        @WorkflowSignal("label-printed")
        Future<Void> labelPrinted(WorkflowInstanceId id, Payment payload, @SignalDedupKey String key);
    }

    /** The contract of the chain definition. */
    interface ChainContract {
        @WorkflowSignal("a-done")
        Future<Void> aDone(WorkflowInstanceId id, Payment payload, @SignalDedupKey String key);

        @WorkflowSignal("b-done")
        Future<Void> bDone(WorkflowInstanceId id, Payment payload, @SignalDedupKey String key);
    }

    /** Where the wait of a definition under the key rule sits. */
    enum KeyShape {
        /** On the main path of the instance. */
        MAIN,
        /** In a branch of a fork. */
        BRANCH
    }

    /** A key accessor that breaks the key rule, as a named lambda over the reference the instance started with. */
    enum KeyBehaviour {
        /** Computes no key. */
        NULL(reference -> null),
        /** Computes a key of blanks. */
        BLANK(reference -> "   "),
        /** Throws an exception whose own message holds a value that must never be repeated. */
        THROWING(reference -> {
            throw new IllegalStateException("lookup failed for " + SECRET);
        }),
        /** Computes a key that holds a NUL character. */
        NUL(reference -> NUL_KEY);

        final UnaryOperator<String> accessor;

        KeyBehaviour(UnaryOperator<String> accessor) {
            this.accessor = accessor;
        }
    }

    /**
     * A branch token, as the tests read it.
     *
     * @param branchId      the branch
     * @param status        the status of the token
     * @param currentStepId the step the token stands at
     */
    record BranchRow(String branchId, String status, String currentStepId) {}

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
            seed(chain(), List.of("a-done", "b-done"));
            seed(fork(FORK, ForkState::refA, ForkState::refB, false), List.of(), forkBranches());
            seed(fork(FORK_WITH_TIMEOUT, ForkState::refA, ForkState::refB, true), List.of(), forkBranches());
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
        return startWith(node, definitionId, new OrderStart(ref, paymentReference), scope);
    }

    /**
     * Records, and commits, the start of a fork definition in the order scope.
     *
     * @param node         the node whose engine records the start
     * @param definitionId the fork definition to start
     * @param refA         the value the wait of branch {@code b1} is keyed by
     * @param refB         the value the wait of branch {@code b2} is keyed by
     * @return the instance
     */
    WorkflowInstanceId startFork(Node node, String definitionId, String refA, String refB) {
        String ref = definitionId + "-" + UUID.randomUUID();
        return startWith(node, definitionId, new ForkStart(ref, refA, refB), SCOPE);
    }

    private WorkflowInstanceId startWith(Node node, String definitionId, Object payload, @Nullable String scope) {
        StartCommand command = new StartCommand(
                definitionId,
                runtimeOf(definitionId).contractName(),
                payload,
                "idem-" + UUID.randomUUID(),
                null,
                null,
                1L,
                scope,
                null);
        return await(caller(() -> node.pool.withTransaction(tx -> node.engine.start(command, tx))));
    }

    /**
     * Registers a definition whose wait takes its key from the accessor, over the reference the instance starts with.
     * It must be called before the node that runs the definition is built.
     *
     * @param shape    where the wait sits: the main path, or branch {@code b1} of a fork whose branch {@code b2} waits
     *                 without a key
     * @param tag      a label that makes the definition id unique within the fixture
     * @param accessor the accessor the key is computed by
     * @return the id of the definition
     */
    String keyRule(KeyShape shape, String tag, UnaryOperator<String> accessor) {
        String id = "key-rule-" + shape.name().toLowerCase(java.util.Locale.ROOT) + "-" + tag;
        if (shape == KeyShape.MAIN) {
            seed(keyedOrder(id, state -> accessor.apply(state.paymentReference())), List.of("payment-captured"));
        } else {
            seed(fork(id, state -> accessor.apply(state.refA()), null, false), List.of(), forkBranches());
        }
        return id;
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

    /** The state of the instance, with its status, step, wait and version, as one JSON object. */
    JsonObject snapshot(WorkflowInstanceId id) {
        return await(fixture.observer()
                        .preparedQuery("SELECT status, current_step_id, wait_type, wait_key, state_json, version"
                                + " FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .toJson();
    }

    /** The branch tokens of the instance, in no particular order. */
    List<BranchRow> branchTokens(WorkflowInstanceId id) {
        List<BranchRow> rows = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT branch_id, status, current_step_id FROM workflow_branch_tokens"
                        + " WHERE workflow_id = $1")
                .execute(Tuple.of(id.value())))) {
            rows.add(new BranchRow(
                    row.getString("branch_id"), row.getString("status"), row.getString("current_step_id")));
        }
        return rows;
    }

    /** The timer the branch's wait carries, or {@code null} when it carries none. */
    @Nullable
    UUID branchTimeoutTimerOf(WorkflowInstanceId id, String branchId) {
        return await(fixture.observer()
                        .preparedQuery("SELECT wait_aux_id FROM workflow_branch_tokens"
                                + " WHERE workflow_id = $1 AND branch_id = $2")
                        .execute(Tuple.of(id.value(), branchId)))
                .iterator()
                .next()
                .getUUID("wait_aux_id");
    }

    /** The number of signal commands of the instance that are still open, as the queue counts them. */
    long openSignals(WorkflowInstanceId id) {
        return count("SELECT open_signals FROM workflow_command_queues WHERE workflow_id = $1", Tuple.of(id.value()));
    }

    /** Every error text stored for the instance: the last error of each command and the error columns of the row. */
    String storedErrorText(WorkflowInstanceId id) {
        List<String> texts = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT COALESCE(last_error, '') FROM workflow_commands WHERE workflow_id = $1")
                .execute(Tuple.of(id.value())))) {
            texts.add(row.getString(0));
        }
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT COALESCE(error_type, '') || ' ' || COALESCE(error_message, '')"
                        + " FROM workflow_instances WHERE id = $1")
                .execute(Tuple.of(id.value())))) {
            texts.add(row.getString(0));
        }
        return String.join("\n", texts);
    }

    /**
     * Every entry the workflow modules logged at {@code level} or above since the fixture opened, each with the text of
     * the exception it carries, causes included.
     */
    List<String> loggedEntries(Level level) {
        List<String> entries = new ArrayList<>();
        for (ILoggingEvent event : fixture.logged(level)) {
            StringBuilder entry = new StringBuilder(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                entry.append('\n').append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            entries.add(entry.toString());
        }
        return entries;
    }

    /** The entries of {@link #loggedEntries} as one text. */
    String loggedText(Level level) {
        return String.join("\n", loggedEntries(level));
    }

    /**
     * The messages of the exceptions of the given type name that the workflow modules logged at {@code level} or
     * above, searched through the cause chain of every logged exception.
     */
    List<String> loggedMessagesOf(String exceptionSimpleName, Level level) {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : fixture.logged(level)) {
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                if (proxy.getClassName().endsWith("." + exceptionSimpleName)) {
                    messages.add(proxy.getMessage());
                }
            }
        }
        return messages;
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

    private void seed(Definition<?, ?> code, List<String> signalNames) {
        seed(code, signalNames, new JsonObject());
    }

    private void seed(Definition<?, ?> code, List<String> signalNames, JsonObject branches) {
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
                                .put("branches", branches),
                        null),
                true));
    }

    /** The signals the branches of the fork definitions declare, by {@code fork/branch}. */
    private static JsonObject forkBranches() {
        return new JsonObject()
                .put("fork/b1", new JsonArray().add("shipment-confirmed"))
                .put("fork/b2", new JsonArray().add("label-printed"));
    }

    private static Definition<OrderState, OrderContract> order(String id, boolean withTimeout) {
        return orderDefinition(id, OrderContract.class, wf -> {
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

    private static Definition<OrderState, OrderContract> refundOrder() {
        return orderDefinition(ORDER_REFUND, OrderContract.class, wf -> {
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

    private static Definition<OrderState, InvoiceContract> invoice() {
        return orderDefinition(INVOICE, InvoiceContract.class, wf -> {
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

    /** An order whose only wait takes its key from the function. */
    private static Definition<OrderState, OrderContract> keyedOrder(String id, Function<OrderState, String> key) {
        return orderDefinition(id, OrderContract.class, wf -> {
            wf.initialStep("wait-payment");
            wf.waitForSignal("wait-payment", "payment-captured", Payment.class)
                    .correlateBy(key)
                    .onSignal((state, payment) ->
                            new OrderState(state.ref(), state.paymentReference(), payment.reference()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
    }

    /**
     * An order that waits at {@code wait-a} for {@code a-done} keyed by its payment reference, whose signal replaces
     * the payment reference with the payload's, and then waits at {@code wait-b} for {@code b-done}, keyed by the
     * payment reference again.
     */
    private static Definition<OrderState, ChainContract> chain() {
        return orderDefinition(CHAIN, ChainContract.class, wf -> {
            wf.initialStep("wait-a");
            wf.waitForSignal("wait-a", "a-done", Payment.class)
                    .correlateBy(OrderState::paymentReference)
                    .onSignal((state, payment) -> new OrderState(state.ref(), payment.reference(), null))
                    .toStepOnSignal("wait-b")
                    .build();
            wf.waitForSignal("wait-b", "b-done", Payment.class)
                    .correlateBy(OrderState::paymentReference)
                    .onSignal((state, payment) ->
                            new OrderState(state.ref(), state.paymentReference(), payment.reference()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
    }

    /**
     * A fork of two branches that wait for a signal each: {@code b1} at {@code wait-a}, keyed by {@code keyA}, and
     * {@code b2} at {@code wait-b}, keyed by {@code keyB} or, when that is {@code null}, without a key.
     */
    private static Definition<ForkState, ForkContract> fork(
            String id,
            Function<ForkState, String> keyA,
            @Nullable Function<ForkState, String> keyB,
            boolean timeoutOnA) {
        return new Definition<>(
                id,
                ForkContract.class,
                ForkState.class,
                wf -> wf.init(
                        ForkStart.class, start -> new ForkState(start.ref(), start.refA(), start.refB(), null, null)),
                wf -> {
                    wf.initialStep("fork");
                    wf.fork("fork")
                            .branch("b1", "wait-a", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                            .branch("b2", "wait-b", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                            .join("join");
                    if (timeoutOnA) {
                        wf.waitForSignal("wait-a", "shipment-confirmed", Payment.class)
                                .correlateBy(keyA)
                                .onSignal((state, payment) -> new ForkState(
                                        state.ref(), state.refA(), state.refB(), payment.reference(), state.printed()))
                                .toStepOnSignal("a-done")
                                .timeoutAfter(Duration.ofHours(1))
                                .onTimeout(state -> state)
                                .toStepOnTimeout("a-timed-out");
                        wf.complete("a-timed-out");
                    } else {
                        wf.waitForSignal("wait-a", "shipment-confirmed", Payment.class)
                                .correlateBy(keyA)
                                .onSignal((state, payment) -> new ForkState(
                                        state.ref(), state.refA(), state.refB(), payment.reference(), state.printed()))
                                .toStepOnSignal("a-done")
                                .build();
                    }
                    wf.complete("a-done");
                    if (keyB == null) {
                        wf.waitForSignal("wait-b", "label-printed", Payment.class)
                                .onSignal((state, payment) -> new ForkState(
                                        state.ref(), state.refA(), state.refB(), state.shipped(), payment.reference()))
                                .toStepOnSignal("b-done")
                                .build();
                    } else {
                        wf.waitForSignal("wait-b", "label-printed", Payment.class)
                                .correlateBy(keyB)
                                .onSignal((state, payment) -> new ForkState(
                                        state.ref(), state.refA(), state.refB(), state.shipped(), payment.reference()))
                                .toStepOnSignal("b-done")
                                .build();
                    }
                    wf.complete("b-done");
                    wf.join("join")
                            .allRequired((state, results) -> state)
                            .toStep("done")
                            .onFailure("done")
                            .endJoin();
                    wf.complete("done");
                });
    }

    private static <C> Definition<OrderState, C> orderDefinition(
            String id, Class<C> contract, Consumer<WorkflowBuilder<OrderState>> body) {
        return new Definition<>(
                id,
                contract,
                OrderState.class,
                wf -> wf.init(OrderStart.class, start -> new OrderState(start.ref(), start.paymentReference(), null)),
                body);
    }

    /** A code-first definition over a state and a contract. */
    private static final class Definition<S, C> implements WorkflowDefinition<S, C> {
        private final String id;
        private final Class<C> contract;
        private final Class<S> stateType;
        private final Consumer<WorkflowBuilder<S>> init;
        private final Consumer<WorkflowBuilder<S>> body;

        Definition(
                String id,
                Class<C> contract,
                Class<S> stateType,
                Consumer<WorkflowBuilder<S>> init,
                Consumer<WorkflowBuilder<S>> body) {
            this.id = id;
            this.contract = contract;
            this.stateType = stateType;
            this.init = init;
            this.body = body;
        }

        @Override
        public Class<C> contract() {
            return contract;
        }

        @Override
        public Class<S> stateType() {
            return stateType;
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
        public void define(WorkflowBuilder<S> wf) {
            init.accept(wf);
            body.accept(wf);
        }
    }
}
