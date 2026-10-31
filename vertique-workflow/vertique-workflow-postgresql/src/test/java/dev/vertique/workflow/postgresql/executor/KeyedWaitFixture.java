// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.core.scope.ScopeKey;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.migration.DefaultWorkflowMigrationRegistry;
import dev.vertique.workflow.migration.MigrationContext;
import dev.vertique.workflow.migration.MigrationResult;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
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
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * An approval that waits at {@code wait-received} for {@code received} and then at {@code wait-approved} for
     * {@code approved}, neither wait declaring a key; its row declares both names among the instance names.
     */
    static final String APPROVAL = "approval";

    /** An approval of the contract of {@link #APPROVAL} whose row declares {@code received} but not {@code approved}. */
    static final String APPROVAL_NARROW = "approval-narrow";

    /** An audit, of another contract, that waits at {@code wait-approved} for a signal named like the approval's. */
    static final String AUDIT = "audit";

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

    /** An order that waits like {@link #ORDER}, then holds on a timer of an hour, so that it neither ends nor waits for a signal. */
    static final String ORDER_HELD = "order-held";

    /** The order of {@link #ORDER_HELD} whose wait gives up after an hour, to the hold. */
    static final String ORDER_HELD_WITH_TIMEOUT = "order-held-with-timeout";

    /** An order whose signal returns it to the same wait, with the same key. */
    static final String LOOPING = "order-looping";

    /** An order that waits like {@link #ORDER_HELD}; its timeout path throws while {@link #timeoutPathBroken} is set. */
    static final String BREAKABLE_TIMEOUT = "order-breakable-timeout";

    /** An order whose initial state throws while {@link #startBroken} is set. */
    static final String BREAKABLE_START = "order-breakable-start";

    /**
     * A fork whose branch {@code a} waits on a timer whose next step throws while {@link #branchTimerPathBroken} is
     * set, and whose branch {@code b} waits at {@code wait-b} for {@code label-printed} keyed by {@code refB}.
     */
    static final String FORK_BREAKABLE_TIMER = "fork-breakable-timer";

    /**
     * A {@code FIRST_SUCCESS} fork: {@code b1} waits at {@code wait-a} for {@code shipment-confirmed} keyed by
     * {@code refA} and {@code b2} at {@code wait-b} for {@code label-printed} keyed by {@code refB}; the join holds
     * on a timer.
     */
    static final String RACE = "fork-race";

    /**
     * Version one waits at {@code w} for {@code shipment-confirmed} keyed by {@code refA}; version two waits at
     * {@code w2} for the same signal keyed by {@code refB}.
     */
    static final String MIGRATING = "order-migrating";

    /** Version one waits at {@code w} keyed by {@code refA}; version two waits at {@code w} without a key. */
    static final String MIGRATING_UNKEYED = "order-migrating-unkeyed";

    /** Version one waits at {@code w} keyed by {@code refA}; version two runs sixty decisions before it waits. */
    static final String MIGRATING_OVERRUN = "order-migrating-overrun";

    /** An {@code ALL_REQUIRED} fork whose failure route is a {@code CompleteNode}, branch {@code a} failing. */
    static final String FORK_FAILING_TO_COMPLETE = "fork-failing-to-complete";

    /**
     * An {@code ALL_REQUIRED} fork whose failure route is a {@code FailNode}, after a compensable dispatch and the wait
     * {@code wait-reserved} for {@code shipment-confirmed} keyed by {@code refA}, which complete the dispatch before the
     * fork.
     */
    static final String FORK_FAILING_TO_COMPENSATE = "fork-failing-to-compensate";

    /** An {@code ALL_REQUIRED} fork whose failure route is a {@code FailNode}, with no compensable step. */
    static final String FORK_FAILING_TO_FAIL = "fork-failing-to-fail";

    /** An {@code ALL_REQUIRED} fork with no failure route. */
    static final String FORK_FAILING_NO_ROUTE = "fork-failing-no-route";

    /** An {@code ALL_REQUIRED} fork whose failure route is a later wait, a timer of an hour. */
    static final String FORK_FAILING_TO_WAIT = "fork-failing-to-wait";

    /**
     * An order that waits like {@link #ORDER} and records the scope the ambient context holds when its signal applies
     * into {@link #observedScopes}.
     */
    static final String ORDER_SCOPE_PROBE = "order-scope-probe";

    /**
     * A fork whose branches {@code b1} and {@code b2} both enter {@code wait-a}, which waits for
     * {@code shipment-confirmed} keyed by {@code refA}, so that the two branch records hold the same key.
     */
    static final String FORK_EQUAL_KEYS = "order-fork-equal-keys";

    /**
     * An order that waits like {@link #ORDER}, gives up after an hour, and whose timeout path returns to the same wait
     * with the same key, so that the new entry equals the old one in step and key.
     */
    static final String ORDER_TIMEOUT_REWAIT = "order-timeout-rewait";

    /**
     * An order that waits like {@link #ORDER}, gives up after an hour, and whose timeout path returns to the same
     * step with another key, the payment reference followed by {@code -2}.
     */
    static final String ORDER_TIMEOUT_REKEY = "order-timeout-rekey";

    /**
     * An order whose timeout path throws while {@link #timeoutPathBroken} is set and otherwise returns to the same
     * wait with the same key.
     */
    static final String BREAKABLE_TIMEOUT_REWAIT = "order-breakable-timeout-rewait";

    /** An order that waits like {@link #ORDER} and whose signal leads to a failure node with no compensable step. */
    static final String ORDER_FAILS_ON_SIGNAL = "order-fails-on-signal";

    /**
     * The fork of {@link #FORK_BREAKABLE_TIMER} whose branch {@code b} returns to {@code wait-b}, under the same key,
     * when its signal applies, so that every application opens a new entry of the same wait.
     */
    static final String FORK_BREAKABLE_TIMER_LOOPING = "fork-breakable-timer-looping";

    /** The race of {@link #RACE} whose branch {@code b2} gives up after an hour. */
    static final String RACE_TIMED = "fork-race-timed";

    /** An {@code ALL_REQUIRED} fork whose failure route is a {@code CompleteNode}, branch {@code b} timed. */
    static final String FORK_ENDING_COMPLETED = "fork-ending-completed";

    /**
     * An {@code ALL_REQUIRED} fork whose failure route is a {@code FailNode}, after a compensable dispatch, branch
     * {@code b} timed.
     */
    static final String FORK_ENDING_COMPENSATED = "fork-ending-compensated";

    /** The fork of {@link #FORK_FAILING_TO_FAIL} whose branch {@code b} gives up after an hour. */
    static final String FORK_FAILING_TO_FAIL_TIMED = "fork-failing-to-fail-timed";

    /** The fork of {@link #FORK_FAILING_TO_FAIL} whose branch {@code b} waits without a key. */
    static final String FORK_FAILING_TO_FAIL_UNKEYED = "fork-failing-to-fail-unkeyed";

    /**
     * Both versions wait at {@code w} for {@code shipment-confirmed} keyed by {@code refA}, give up after an hour, and
     * the migration resumes at {@code w}.
     */
    static final String MIGRATING_TIMED = "order-migrating-timed";

    /** Both versions wait at {@code w} without a key and give up after an hour; the migration resumes at {@code w}. */
    static final String MIGRATING_TIMED_UNKEYED = "order-migrating-timed-unkeyed";

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

    /** The contract of the forks that end with a sibling still waiting. */
    interface EndingContract {
        @WorkflowSignal("stock-reserved")
        Future<Void> stockReserved(WorkflowInstanceId id, Payment payload, @SignalDedupKey String key);

        @WorkflowSignal("a-go")
        Future<Void> aGo(WorkflowInstanceId id, Payment payload, @SignalDedupKey String key);

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

    /** The contract of the approval definitions. */
    interface ApprovalContract {
        @WorkflowSignal("received")
        Future<Void> received(WorkflowInstanceId id, Payment payload, @SignalDedupKey String key);

        @WorkflowSignal("approved")
        Future<Void> approved(WorkflowInstanceId id, Payment payload, @SignalDedupKey String key);
    }

    /** The contract of the audit definition. */
    interface AuditContract {
        @WorkflowSignal("approved")
        Future<Void> approved(WorkflowInstanceId id, Payment payload, @SignalDedupKey String key);
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

    /** While set, the timeout path of {@link #BREAKABLE_TIMEOUT} throws. */
    final AtomicBoolean timeoutPathBroken = new AtomicBoolean(true);

    /** While set, the step after the timer of {@link #FORK_BREAKABLE_TIMER} throws. */
    final AtomicBoolean branchTimerPathBroken = new AtomicBoolean(true);

    /** While set, the initial state of {@link #BREAKABLE_START} throws. */
    final AtomicBoolean startBroken = new AtomicBoolean(true);

    /** The scope keys the ambient context held when a signal of {@link #ORDER_SCOPE_PROBE} applied, in order. */
    final List<String> observedScopes = new CopyOnWriteArrayList<>();

    /** The faults of the nodes built by {@link #deadLettering}. */
    final CommandFaults faults = new CommandFaults();

    private final DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
    private final PgWorkflowDefinitionRegistryStore store;

    private KeyedWaitFixture(DbPoolConfig db, boolean withExits) {
        this.fixture = ExecutorFixture.open(db);
        try {
            store = new PgWorkflowDefinitionRegistryStore(
                    fixture.observer(), new PgDbExceptionMapper(), Optional.empty());
            seed(order(ORDER, false), List.of("payment-captured", "refund-issued"));
            seed(order(ORDER_WITH_TIMEOUT, true), List.of("payment-captured", "refund-issued"));
            seed(refundOrder(), List.of("payment-captured", "refund-issued"));
            seed(invoice(), List.of("payment-captured"));
            seed(approval(APPROVAL), List.of("received", "approved"));
            seed(approval(APPROVAL_NARROW), List.of("received"));
            seed(audit(), List.of("approved"));
            seed(chain(), List.of("a-done", "b-done"));
            seed(fork(FORK, ForkState::refA, ForkState::refB, false), List.of(), forkBranches());
            seed(fork(FORK_WITH_TIMEOUT, ForkState::refA, ForkState::refB, true), List.of(), forkBranches());
            seed(scopeProbeOrder(), List.of("payment-captured", "refund-issued"));
            seed(
                    forkEqualKeys(),
                    List.of(),
                    new JsonObject()
                            .put("fork/b1", new JsonArray().add("shipment-confirmed"))
                            .put("fork/b2", new JsonArray().add("shipment-confirmed")));
            if (withExits) {
                seedExitDefinitions();
            }
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
        return new KeyedWaitFixture(db, false);
    }

    /**
     * Opens the fixture over the database and registers, besides every definition of {@link #open}, the definitions
     * that leave a wait in every way a wait is left, all of them keyed on every signal wait.
     *
     * @param db the database under test, already migrated
     * @return the fixture
     */
    static KeyedWaitFixture openWithExits(DbPoolConfig db) {
        return new KeyedWaitFixture(db, true);
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

    /**
     * Records, and commits, the start of a definition in a scope, as a caller whose ambient context holds that scope
     * would, so that the instance saves the scope in its durable context. The node must carry the context.
     *
     * @param node             the node whose engine records the start
     * @param definitionId     the definition to start
     * @param paymentReference the value the wait is keyed by
     * @param scope            the start scope and the ambient scope
     * @return the instance
     */
    WorkflowInstanceId startInScope(Node node, String definitionId, String paymentReference, String scope) {
        String ref = definitionId + "-" + paymentReference + "-" + UUID.randomUUID();
        StartCommand command = new StartCommand(
                definitionId,
                runtimeOf(definitionId).contractName(),
                new OrderStart(ref, paymentReference),
                "idem-" + UUID.randomUUID(),
                null,
                null,
                1L,
                scope,
                null);
        return await(caller(() -> {
            ContextHolder.Scope ambient =
                    fixture.holder().bind(ScopeContext.class, new ScopeContext(new ScopeKey(scope)));
            try {
                return node.pool
                        .withTransaction(tx -> node.engine.start(command, tx))
                        .eventually(() -> {
                            ambient.close();
                            return Future.succeededFuture();
                        });
            } catch (Throwable failure) {
                ambient.close();
                return Future.failedFuture(failure);
            }
        }));
    }

    /**
     * Records, and commits, the start of {@link #FORK_EQUAL_KEYS}, whose two branches wait for the same signal under
     * the same key.
     *
     * @param node the node whose engine records the start
     * @param key  the key both waits compute
     * @return the instance
     */
    WorkflowInstanceId forkWithEqualBranchKeys(Node node, String key) {
        return startFork(node, FORK_EQUAL_KEYS, key, key);
    }

    private WorkflowInstanceId startWith(Node node, String definitionId, Object payload, @Nullable String scope) {
        return startWith(node, definitionId, payload, null, scope);
    }

    private WorkflowInstanceId startWith(
            Node node, String definitionId, Object payload, @Nullable String businessKey, @Nullable String scope) {
        StartCommand command = new StartCommand(
                definitionId,
                runtimeOf(definitionId).contractName(),
                payload,
                "idem-" + UUID.randomUUID(),
                businessKey,
                null,
                1L,
                scope,
                null);
        return await(caller(() -> node.pool.withTransaction(tx -> node.engine.start(command, tx))));
    }

    /**
     * Records, and commits, the start of {@link #APPROVAL} in the order scope with a business key, and leaves it
     * unrun: the instance is {@code PENDING} until an executor runs its start.
     *
     * @param node        the node whose engine records the start
     * @param businessKey the business key of the instance
     * @return the instance
     */
    WorkflowInstanceId startPending(Node node, String businessKey) {
        return startWithBusinessKey(node, APPROVAL, businessKey, SCOPE);
    }

    /**
     * Records, and commits, the start of a definition that starts from {@link OrderStart}, with a business key.
     *
     * @param node         the node whose engine records the start
     * @param definitionId the definition to start
     * @param businessKey  the business key of the instance
     * @param scope        the start scope, or {@code null} for the default scope
     * @return the instance
     */
    WorkflowInstanceId startWithBusinessKey(
            Node node, String definitionId, String businessKey, @Nullable String scope) {
        String ref = definitionId + "-" + businessKey + "-" + UUID.randomUUID();
        return startWith(node, definitionId, new OrderStart(ref, businessKey), businessKey, scope);
    }

    /**
     * Records, and commits, the start of a fork definition in the order scope with a business key.
     *
     * @param node         the node whose engine records the start
     * @param definitionId the fork definition to start
     * @param businessKey  the business key of the instance
     * @return the instance
     */
    WorkflowInstanceId startForkWithBusinessKey(Node node, String definitionId, String businessKey) {
        String ref = definitionId + "-" + businessKey + "-" + UUID.randomUUID();
        return startWith(node, definitionId, new ForkStart(ref, "SHIP-A", "SHIP-B"), businessKey, SCOPE);
    }

    /**
     * Sets the signal names the stored definition rows of the definition declare, as the plan wrote them.
     *
     * @param definitionId the definition
     * @param names        the document of the instance and branch names, or {@code null} for a row written before the
     *                     column existed, which has no name list
     */
    void setSignalNames(String definitionId, @Nullable JsonObject names) {
        await(fixture.observer()
                .preparedQuery("UPDATE workflow_definitions SET signal_names_json = $2::jsonb WHERE definition_id = $1")
                .execute(Tuple.of(definitionId, names)));
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

    /** Records, and commits, a retry of the instance in a caller transaction. */
    void retry(Node node, WorkflowInstanceId id) {
        await(caller(() -> node.pool.withTransaction(tx -> node.engine.retry(id, tx))));
    }

    /** Records, and commits, the cancellation of the instance in a caller transaction. */
    void cancel(Node node, WorkflowInstanceId id) {
        await(caller(() -> node.pool.withTransaction(tx -> node.engine.cancel(id, "cancelled by the test", tx))));
    }

    /** Records, and commits, the migration of the instance to the version in a caller transaction. */
    void migrate(Node node, WorkflowInstanceId id, long version) {
        await(caller(() -> node.pool.withTransaction(tx -> node.engine.migrate(id, version, tx))));
    }

    /**
     * The failure of a delivery by key that the engine refuses, after a wait of a few seconds.
     *
     * @param node   the node whose engine delivers
     * @param signal the signal
     * @return the failure
     */
    Throwable deliverByKeyFailure(Node node, CorrelatedSignal signal) {
        return ExecutorFixture.failureOf(
                caller(() -> node.pool.withTransaction(tx -> node.engine.signalByKey(signal, tx))),
                Duration.ofSeconds(15));
    }

    /** What a delivery by key answered: a receipt, or the failure of the returned future. */
    record Delivery(
            @Nullable CorrelatedSignalReceipt receipt,
            @Nullable Throwable failure) {

        /** The receipt; fails the proof when the delivery failed. */
        CorrelatedSignalReceipt receiptOrFail() {
            if (failure != null) {
                throw new AssertionError("expected a receipt but the delivery failed with " + failure, failure);
            }
            return receipt;
        }

        /** The failure; fails the proof when the delivery answered with a receipt. */
        Throwable failureOrFail() {
            if (failure == null) {
                throw new AssertionError("expected a failure but the delivery answered " + receipt);
            }
            return failure;
        }
    }

    /**
     * Delivers the signal by key in a caller transaction that commits whatever the outcome was, so that anything a
     * refusal wrote would stay.
     *
     * @param node   the node whose engine delivers
     * @param signal the signal
     * @return the receipt or the failure
     */
    Delivery deliver(Node node, CorrelatedSignal signal) {
        return await(startDelivery(node, signal));
    }

    /**
     * Starts the delivery of {@link #deliver} and returns before it ends, for deliveries that block on a lock.
     *
     * @param node   the node whose engine delivers
     * @param signal the signal
     * @return a future of the receipt or the failure
     */
    Future<Delivery> startDelivery(Node node, CorrelatedSignal signal) {
        return startDelivery(node, connection -> node.engine.signalByKey(signal, connection), () -> {});
    }

    /**
     * Delivers the signal by business key in a caller transaction that commits whatever the outcome was, so that
     * anything a refusal wrote would stay.
     *
     * @param node   the node whose engine delivers
     * @param signal the signal; its key is the business key
     * @return the receipt or the failure
     */
    Delivery deliverByBusinessKey(Node node, CorrelatedSignal signal) {
        return await(startBusinessKeyDelivery(node, signal, () -> {}));
    }

    /**
     * Starts the delivery of {@link #deliverByBusinessKey} and returns before it ends, running the action after the
     * delivery answered and before the caller commits.
     *
     * @param node         the node whose engine delivers
     * @param signal       the signal; its key is the business key
     * @param beforeCommit what the caller does once it has the answer and before it commits
     * @return a future of the receipt or the failure
     */
    Future<Delivery> startBusinessKeyDelivery(Node node, CorrelatedSignal signal, Runnable beforeCommit) {
        return startDelivery(node, connection -> node.engine.signalByBusinessKey(signal, connection), beforeCommit);
    }

    private Future<Delivery> startDelivery(
            Node node, Function<SqlConnection, Future<CorrelatedSignalReceipt>> operation, Runnable beforeCommit) {
        return caller(() -> node.pool.getConnection().compose(connection -> connection
                .begin()
                .compose(transaction -> operation.apply(connection).transform(outcome -> {
                    Delivery delivery = outcome.succeeded()
                            ? new Delivery(outcome.result(), null)
                            : new Delivery(null, outcome.cause());
                    beforeCommit.run();
                    return transaction
                            .commit()
                            .transform(committed -> connection.close())
                            .map(closed -> delivery);
                }))));
    }

    /**
     * The description of an engine whose migration registry holds, for each of {@link #MIGRATING},
     * {@link #MIGRATING_UNKEYED} and {@link #MIGRATING_OVERRUN}, one handler from version one to version two that keeps
     * the state: the first resumes at {@code w2}, the second at {@code w}, the third at the initial step.
     *
     * @param name the name of the node
     * @return the description
     */
    NodeSpec migratingNode(String name) {
        Set<WorkflowMigrationHandler<?, ?>> handlers = Set.of(
                stateKeepingHandler(MIGRATING, "w2"),
                stateKeepingHandler(MIGRATING_UNKEYED, "w"),
                stateKeepingHandler(MIGRATING_OVERRUN, null),
                stateKeepingHandler(MIGRATING_TIMED, "w"),
                stateKeepingHandler(MIGRATING_TIMED_UNKEYED, "w"));
        return node(name).migrations(new DefaultWorkflowMigrationRegistry(handlers, registry));
    }

    /** Makes the instance's timeout timer due now. */
    void fireTimeout(WorkflowInstanceId id) {
        dueNow(id, "TIMEOUT", null);
    }

    /** Makes the timeout timer of the instance's branch due now. */
    void fireTimeout(WorkflowInstanceId id, String branchId) {
        dueNow(id, "TIMEOUT", branchId);
    }

    /** Makes the instance's only fire timer due now. */
    void fireTimer(WorkflowInstanceId id) {
        dueNow(id, "FIRE", null);
    }

    private void dueNow(WorkflowInstanceId id, String timerKind, @Nullable String branchId) {
        List<Long> commands = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT id FROM workflow_commands WHERE workflow_id = $1 AND kind = 'TIMER'"
                        + " AND timer_kind = $2 AND state = 'PENDING'"
                        + " AND ($3::text IS NULL OR target_json->>'branchId' = $3) ORDER BY seq")
                .execute(Tuple.of(id.value(), timerKind, branchId)))) {
            commands.add(row.getLong("id"));
        }
        if (commands.size() != 1) {
            throw new AssertionError(
                    "expected one pending " + timerKind + " timer command of " + id + " but found " + commands);
        }
        long commandId = commands.getFirst();
        OffsetDateTime at = await(fixture.observer()
                        .preparedQuery("UPDATE workflow_commands SET fire_at = clock_timestamp()"
                                + " WHERE id = $1 RETURNING fire_at")
                        .execute(Tuple.of(commandId)))
                .iterator()
                .next()
                .getOffsetDateTime("fire_at");
        await(fixture.observer()
                .preparedQuery("UPDATE workflow_timers SET fire_at = $2 WHERE command_id = $1")
                .execute(Tuple.of(commandId, at)));
        await(fixture.observer()
                .preparedQuery("UPDATE workflow_commands SET available_at = $2 WHERE id = $1")
                .execute(Tuple.of(commandId, at)));
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

    /**
     * A signal command with the row the queue holds for it.
     *
     * @param row    the command row
     * @param target the stored target
     */
    record SignalRow(CommandRow row, JsonObject target) {

        /** The id of the command. */
        long id() {
            return row.id();
        }

        /** The state of the command. */
        String state() {
            return row.state();
        }

        /** The {@code waitId} the target carries, or {@code null} when it carries none. */
        @Nullable
        String waitId() {
            return target.getString("waitId");
        }
    }

    /** The signal commands of the instance with their rows, in queue order. */
    List<SignalRow> signalRows(WorkflowInstanceId id) {
        List<CommandRow> rows = fixture.commandsOf(id).stream()
                .filter(command -> command.kind().equals("SIGNAL"))
                .toList();
        List<SignalCommand> targets = signalCommands(id);
        List<SignalRow> signals = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            signals.add(new SignalRow(rows.get(i), targets.get(i).target()));
        }
        return signals;
    }

    /** The timer commands of the instance in queue order. */
    List<CommandRow> timerCommands(WorkflowInstanceId id) {
        return fixture.commandsOf(id).stream()
                .filter(command -> command.kind().equals("TIMER"))
                .toList();
    }

    /**
     * An entry of the history of an instance.
     *
     * @param sequence  the sequence of the entry
     * @param type      the entry type
     * @param commandId the command the entry names, or {@code null}
     */
    record HistoryRow(long sequence, String type, @Nullable Long commandId) {}

    /** The history of the instance in sequence order, with the command each entry names. */
    List<HistoryRow> historyRows(WorkflowInstanceId id) {
        List<HistoryRow> entries = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT sequence, entry_type, command_id, payload_json FROM workflow_history"
                        + " WHERE workflow_id = $1 ORDER BY sequence")
                .execute(Tuple.of(id.value())))) {
            Long command = row.getLong("command_id");
            Object payload = row.getValue("payload_json");
            if (command == null && payload instanceof JsonObject document) {
                command = document.getLong("commandId");
            }
            entries.add(new HistoryRow(row.getLong("sequence"), row.getString("entry_type"), command));
        }
        return entries;
    }

    /** The commands the history entries of the type name, in sequence order. */
    List<Long> commandsWithHistory(WorkflowInstanceId id, String type) {
        return historyRows(id).stream()
                .filter(entry -> entry.type().equals(type))
                .map(HistoryRow::commandId)
                .toList();
    }

    /** The number of history entries of the type that the instance holds. */
    long historyCount(WorkflowInstanceId id, String type) {
        return historyRows(id).stream()
                .filter(entry -> entry.type().equals(type))
                .count();
    }

    /**
     * A timer row, as the tests read it.
     *
     * @param timerId   the timer
     * @param status    the status of the row
     * @param commandId the command the row links to
     */
    record TimerRecord(
            UUID timerId, String status, @Nullable Long commandId) {}

    /** The timer rows of the instance, oldest first. */
    List<TimerRecord> timerRecords(WorkflowInstanceId id) {
        List<TimerRecord> rows = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT timer_id, status, command_id FROM workflow_timers WHERE workflow_id = $1"
                        + " ORDER BY scheduled_at, timer_id")
                .execute(Tuple.of(id.value())))) {
            rows.add(new TimerRecord(row.getUUID("timer_id"), row.getString("status"), row.getLong("command_id")));
        }
        return rows;
    }

    /** Records, and commits, a signal addressed to a branch of the instance. */
    void signalBranch(
            Node node,
            WorkflowInstanceId id,
            String signalName,
            Object payload,
            String dedupKey,
            String forkStepId,
            String branchId) {
        await(caller(() -> node.pool.withTransaction(
                tx -> node.engine.signal(id, signalName, payload, dedupKey, forkStepId, branchId, tx))));
    }

    /**
     * The description of an engine whose commands are abandoned as a crash on request, with one attempt per command and
     * a lease of a few seconds, so that {@link #deadLetterTimeouts} can dead-letter a timeout.
     *
     * @param name the name of the node
     * @return the description
     */
    NodeSpec deadLettering(String name) {
        return deadLettering(node(name));
    }

    /** The description with the faults of {@link #deadLettering(String)} added. */
    NodeSpec deadLettering(NodeSpec spec) {
        return spec.leaseSeconds(5).maxAttempts(1).commands(faults.decorator());
    }

    /**
     * Makes the timeout of each instance due, abandons its segment as a crash would and waits until its command is
     * dead-lettered. The executor of the node is stopped again when this returns. The node must come from
     * {@link #deadLettering} and each instance must be idle at a timed wait.
     *
     * @param node     the node whose executor runs
     * @param branchId the branch whose timeout is meant, or {@code null} for the timeout of the main wait
     * @param ids      the instances
     */
    void deadLetterTimeouts(Node node, @Nullable String branchId, WorkflowInstanceId... ids) {
        for (WorkflowInstanceId id : ids) {
            faults.crashSegmentsOf(id, 1);
        }
        for (WorkflowInstanceId id : ids) {
            dueNow(id, "TIMEOUT", branchId);
        }
        node.startExecutor();
        try {
            ExecutorFixture.awaitCondition("the timeouts to be dead-lettered", Duration.ofSeconds(45), () -> {
                for (WorkflowInstanceId id : ids) {
                    if (timerCommands(id).stream()
                            .noneMatch(command -> command.state().equals("DEAD"))) {
                        return false;
                    }
                }
                return true;
            });
        } finally {
            node.stopExecutor();
        }
    }

    /** Runs the executors until the instance is idle, then stops them. */
    void idle(Node node, WorkflowInstanceId... ids) {
        node.startExecutor();
        try {
            for (WorkflowInstanceId id : ids) {
                node.awaitIdle(id);
            }
        } finally {
            node.stopExecutor();
        }
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

    /**
     * A dedup row, as the tests read it.
     *
     * @param kind       the kind of the row
     * @param scopeKey   the scope column
     * @param namespace  the namespace column
     * @param key        the key column
     * @param workflowId the instance the row names
     */
    record DedupRow(String kind, String scopeKey, String namespace, String key, UUID workflowId) {}

    /** The dedup rows of kind {@code correlated}, in no particular order. */
    List<DedupRow> dedupRows() {
        List<DedupRow> rows = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .query("SELECT kind, scope_key, dedup_namespace, key, workflow_id FROM workflow_dedup"
                        + " WHERE kind = 'correlated'")
                .execute())) {
            rows.add(new DedupRow(
                    row.getString("kind"),
                    row.getString("scope_key"),
                    row.getString("dedup_namespace"),
                    row.getString("key"),
                    row.getUUID("workflow_id")));
        }
        return rows;
    }

    /**
     * What the tables hold that a delivery writes to.
     *
     * @param commands    the rows of {@code workflow_commands}
     * @param dedup       the rows of {@code workflow_dedup}
     * @param history     the rows of {@code workflow_history}
     * @param openSignals the sum of the open-signal counters of the command queues
     */
    record Written(long commands, long dedup, long history, long openSignals) {}

    /** The row counts of the tables a delivery writes to, read on a connection of their own. */
    Written written() {
        return new Written(
                count("SELECT count(*) FROM workflow_commands", Tuple.tuple()),
                count("SELECT count(*) FROM workflow_dedup", Tuple.tuple()),
                count("SELECT count(*) FROM workflow_history", Tuple.tuple()),
                count("SELECT COALESCE(sum(open_signals), 0)::bigint FROM workflow_command_queues", Tuple.tuple()));
    }

    /** The scope columns of the instance: its start scope and its definition scope. */
    JsonObject scopeColumns(WorkflowInstanceId id) {
        return await(fixture.observer()
                        .preparedQuery("SELECT scope_key, definition_scope_key FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .toJson();
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

    /**
     * Whether the signal wait at the step of the registered version of the definition declares a correlation key.
     *
     * @param definitionId the definition
     * @param version      the version the instance is pinned to
     * @param stepId       the waiting step
     * @return {@code true} when the plan declares a key for the wait
     */
    boolean declaresKey(String definitionId, long version, String stepId) {
        return runtimeOf(definitionId, version).nodeById().get(stepId)
                        instanceof dev.vertique.workflow.plan.WaitSignalNode wait
                && wait.correlationKeyCallbackId() != null;
    }

    private RuntimeWorkflow runtimeOf(String definitionId) {
        return registry.codeDefinitions().stream()
                .filter(candidate -> candidate.plan().definitionId().equals(definitionId)
                        && candidate.plan().definitionVersion() == 1L)
                .findFirst()
                .orElseThrow();
    }

    private RuntimeWorkflow runtimeOf(String definitionId, long version) {
        return registry.codeDefinitions().stream()
                .filter(candidate -> candidate.plan().definitionId().equals(definitionId)
                        && candidate.plan().definitionVersion() == version)
                .findFirst()
                .orElseThrow();
    }

    private void seed(Definition<?, ?> code, List<String> signalNames) {
        seed(code, signalNames, new JsonObject());
    }

    private void seed(Definition<?, ?> code, List<String> signalNames, JsonObject branches) {
        registry.register(code);
        RuntimeWorkflow runtime = runtimeOf(code.definitionId(), code.definitionVersion());
        Instant now = Instant.now();
        await(store.upsertCode(
                new WorkflowDefinitionRecord(
                        new DefinitionKey(WorkflowScopes.DEFAULT, code.definitionId(), code.definitionVersion()),
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
                code.definitionVersion() == 1L));
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

    /** An order that records the ambient scope when its signal applies. */
    private Definition<OrderState, OrderContract> scopeProbeOrder() {
        return orderDefinition(ORDER_SCOPE_PROBE, OrderContract.class, wf -> {
            wf.initialStep("wait-payment");
            wf.waitForSignal("wait-payment", "payment-captured", Payment.class)
                    .correlateBy(OrderState::paymentReference)
                    .onSignal((state, payment) -> {
                        observedScopes.add(fixture.holder()
                                .current(ScopeContext.class)
                                .map(scope -> scope.scopeKey().value())
                                .orElse("<none>"));
                        return new OrderState(state.ref(), state.paymentReference(), payment.reference());
                    })
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
    }

    /**
     * A fork whose two branches enter the one wait step, for {@code shipment-confirmed} keyed by {@code refA}. A plan
     * declares a signal name once, so two branches hold the same key only by sharing the wait.
     */
    private static Definition<ForkState, ForkContract> forkEqualKeys() {
        return forkDefinition(FORK_EQUAL_KEYS, 1, wf -> {
            wf.initialStep("fork");
            wf.fork("fork")
                    .branch("b1", "wait-a", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("b2", "wait-a", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join");
            sameKeyShipmentWait(wf, "wait-a", "a-done");
            wf.complete("a-done");
            wf.join("join")
                    .allRequired((state, results) -> state)
                    .toStep("done")
                    .onFailure("done")
                    .endJoin();
            wf.complete("done");
        });
    }

    /** A wait for {@code shipment-confirmed} keyed by {@code refA}. */
    private static void sameKeyShipmentWait(WorkflowBuilder<ForkState> wf, String step, String next) {
        wf.waitForSignal(step, "shipment-confirmed", Payment.class)
                .correlateBy(ForkState::refA)
                .onSignal((state, payment) ->
                        new ForkState(state.ref(), state.refA(), state.refB(), payment.reference(), state.printed()))
                .toStepOnSignal(next)
                .build();
    }

    /** An approval that waits for {@code received} and then for {@code approved}, with no key on either wait. */
    private static Definition<OrderState, ApprovalContract> approval(String id) {
        return orderDefinition(id, ApprovalContract.class, wf -> {
            wf.initialStep("wait-received");
            wf.waitForSignal("wait-received", "received", Payment.class)
                    .onSignal((state, payment) -> state)
                    .toStepOnSignal("wait-approved")
                    .build();
            wf.waitForSignal("wait-approved", "approved", Payment.class)
                    .onSignal((state, payment) ->
                            new OrderState(state.ref(), state.paymentReference(), payment.reference()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
    }

    /** An audit, of a contract other than the approval's, that waits for {@code approved}. */
    private static Definition<OrderState, AuditContract> audit() {
        return orderDefinition(AUDIT, AuditContract.class, wf -> {
            wf.initialStep("wait-approved");
            wf.waitForSignal("wait-approved", "approved", Payment.class)
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

    /** Registers the definitions that only the proofs of the exits from a wait need. */
    private void seedExitDefinitions() {
        List<String> orderSignals = List.of("payment-captured", "refund-issued");
        seed(heldOrder(ORDER_HELD, false), orderSignals);
        seed(heldOrder(ORDER_HELD_WITH_TIMEOUT, true), orderSignals);
        seed(loopingOrder(), orderSignals);
        seed(breakableTimeoutOrder(), orderSignals);
        seed(breakableStartOrder(), orderSignals);
        seed(
                breakableTimerFork(FORK_BREAKABLE_TIMER, false),
                List.of(),
                new JsonObject().put("fork/b", new JsonArray().add("label-printed")));
        seed(race(), List.of(), forkBranches());
        List<String> shipment = List.of("shipment-confirmed");
        seed(migrating(MIGRATING, 1, "w", ForkState::refA, 0), shipment);
        seed(migrating(MIGRATING, 2, "w2", ForkState::refB, 0), shipment);
        seed(migrating(MIGRATING_UNKEYED, 1, "w", ForkState::refA, 0), shipment);
        seed(migrating(MIGRATING_UNKEYED, 2, "w", null, 0), shipment);
        seed(migrating(MIGRATING_OVERRUN, 1, "w", ForkState::refA, 0), shipment);
        seed(migrating(MIGRATING_OVERRUN, 2, "w", ForkState::refB, 60), shipment);
        JsonObject failingBranches = new JsonObject().put("fork/b", new JsonArray().add("label-printed"));
        seed(failingFork(FORK_FAILING_TO_COMPLETE, FailureRoute.COMPLETE, false), List.of(), failingBranches);
        seed(
                failingFork(FORK_FAILING_TO_COMPENSATE, FailureRoute.FAIL, true),
                List.of("shipment-confirmed"),
                failingBranches);
        seed(failingFork(FORK_FAILING_TO_FAIL, FailureRoute.FAIL, false), List.of(), failingBranches);
        seed(failingFork(FORK_FAILING_NO_ROUTE, FailureRoute.NONE, false), List.of(), failingBranches);
        seed(failingFork(FORK_FAILING_TO_WAIT, FailureRoute.WAIT, false), List.of(), failingBranches);
        seedEntryFenceDefinitions();
    }

    /** Registers the definitions that only the proofs of the entry fence need. */
    private void seedEntryFenceDefinitions() {
        List<String> orderSignals = List.of("payment-captured", "refund-issued");
        seed(timeoutReentering(ORDER_TIMEOUT_REWAIT, false, false), orderSignals);
        seed(timeoutReentering(ORDER_TIMEOUT_REKEY, true, false), orderSignals);
        seed(timeoutReentering(BREAKABLE_TIMEOUT_REWAIT, false, true), orderSignals);
        seed(failingOrder(), orderSignals);
        seed(
                breakableTimerFork(FORK_BREAKABLE_TIMER_LOOPING, true),
                List.of(),
                new JsonObject().put("fork/b", new JsonArray().add("label-printed")));
        seed(raceTimed(), List.of(), forkBranches());
        List<String> shipment = List.of("shipment-confirmed");
        seed(migrating(MIGRATING_TIMED, 1, "w", ForkState::refA, 0, true), shipment);
        seed(migrating(MIGRATING_TIMED, 2, "w", ForkState::refA, 0, true), shipment);
        seed(migrating(MIGRATING_TIMED_UNKEYED, 1, "w", null, 0, true), shipment);
        seed(migrating(MIGRATING_TIMED_UNKEYED, 2, "w", null, 0, true), shipment);
        JsonObject failingBranches = new JsonObject().put("fork/b", new JsonArray().add("label-printed"));
        seed(
                failingFork(FORK_FAILING_TO_FAIL_TIMED, FailureRoute.FAIL, false, SiblingWait.TIMED),
                List.of(),
                failingBranches);
        seed(
                failingFork(FORK_FAILING_TO_FAIL_UNKEYED, FailureRoute.FAIL, false, SiblingWait.UNKEYED),
                List.of(),
                failingBranches);
        JsonObject endingBranches = new JsonObject()
                .put("fork/a", new JsonArray().add("a-go"))
                .put("fork/b", new JsonArray().add("label-printed"));
        seed(endingFork(FORK_ENDING_COMPLETED, FailureRoute.COMPLETE, false), List.of(), endingBranches);
        seed(endingFork(FORK_ENDING_COMPENSATED, FailureRoute.FAIL, true), List.of("stock-reserved"), endingBranches);
    }

    /** An order that waits keyed by its payment reference and then holds on a timer, whether the signal or the timeout leaves the wait. */
    private static Definition<OrderState, OrderContract> heldOrder(String id, boolean withTimeout) {
        return orderDefinition(id, OrderContract.class, wf -> {
            wf.initialStep("wait-payment");
            if (withTimeout) {
                wf.waitForSignal("wait-payment", "payment-captured", Payment.class)
                        .correlateBy(OrderState::paymentReference)
                        .onSignal((state, payment) ->
                                new OrderState(state.ref(), state.paymentReference(), payment.reference()))
                        .toStepOnSignal("hold")
                        .timeoutAfter(Duration.ofHours(1))
                        .onTimeout(state -> state)
                        .toStepOnTimeout("hold");
            } else {
                wf.waitForSignal("wait-payment", "payment-captured", Payment.class)
                        .correlateBy(OrderState::paymentReference)
                        .onSignal((state, payment) ->
                                new OrderState(state.ref(), state.paymentReference(), payment.reference()))
                        .toStepOnSignal("hold")
                        .build();
            }
            wf.timer("hold", Duration.ofHours(1)).toStep("done");
            wf.complete("done");
        });
    }

    /** An order whose signal returns it to the wait it left, under the same key. */
    private static Definition<OrderState, OrderContract> loopingOrder() {
        return orderDefinition(LOOPING, OrderContract.class, wf -> {
            wf.initialStep("wait-payment");
            wf.waitForSignal("wait-payment", "payment-captured", Payment.class)
                    .correlateBy(OrderState::paymentReference)
                    .onSignal((state, payment) -> state)
                    .toStepOnSignal("wait-payment")
                    .build();
        });
    }

    private Definition<OrderState, OrderContract> breakableTimeoutOrder() {
        return orderDefinition(BREAKABLE_TIMEOUT, OrderContract.class, wf -> {
            wf.initialStep("wait-payment");
            wf.waitForSignal("wait-payment", "payment-captured", Payment.class)
                    .correlateBy(OrderState::paymentReference)
                    .onSignal((state, payment) ->
                            new OrderState(state.ref(), state.paymentReference(), payment.reference()))
                    .toStepOnSignal("hold")
                    .timeoutAfter(Duration.ofMinutes(10))
                    .onTimeout(state -> {
                        if (timeoutPathBroken.get()) {
                            throw new IllegalStateException("the timeout path is broken");
                        }
                        return state;
                    })
                    .toStepOnTimeout("hold");
            wf.timer("hold", Duration.ofHours(1)).toStep("done");
            wf.complete("done");
        });
    }

    private Definition<OrderState, OrderContract> breakableStartOrder() {
        return new Definition<>(
                BREAKABLE_START,
                OrderContract.class,
                OrderState.class,
                wf -> wf.init(OrderStart.class, start -> {
                    if (startBroken.get()) {
                        throw new IllegalStateException("the initial state is broken");
                    }
                    return new OrderState(start.ref(), start.paymentReference(), null);
                }),
                wf -> {
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

    /**
     * A fork whose branch {@code a} waits on a timer whose next step throws while {@link #branchTimerPathBroken} is set,
     * and whose branch {@code b} waits at {@code wait-b}.
     *
     * @param looping whether the signal of branch {@code b} returns it to {@code wait-b}, so that the branch never ends
     */
    private Definition<ForkState, ForkContract> breakableTimerFork(String id, boolean looping) {
        return forkDefinition(id, 1, wf -> {
            wf.initialStep("fork");
            wf.fork("fork")
                    .branch("a", "a-timer", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("b", "wait-b", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join");
            wf.timer("a-timer", Duration.ofSeconds(60)).toStep("a-work");
            wf.decide("a-work", state -> {
                if (branchTimerPathBroken.get()) {
                    throw new IllegalStateException("the branch step is broken");
                }
                return "a-done";
            });
            wf.complete("a-done");
            keyedPrintedWait(wf, "wait-b", looping ? "wait-b" : "b-done");
            if (!looping) {
                wf.complete("b-done");
            }
            wf.join("join")
                    .allRequired((state, results) -> state)
                    .toStep("done")
                    .onFailure("done")
                    .endJoin();
            wf.complete("done");
        });
    }

    private static Definition<ForkState, ForkContract> race() {
        return forkDefinition(RACE, 1, wf -> {
            wf.initialStep("fork");
            wf.fork("fork")
                    .branch("b1", "wait-a", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("b2", "wait-b", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join");
            wf.waitForSignal("wait-a", "shipment-confirmed", Payment.class)
                    .correlateBy(ForkState::refA)
                    .onSignal((state, payment) -> new ForkState(
                            state.ref(), state.refA(), state.refB(), payment.reference(), state.printed()))
                    .toStepOnSignal("a-done")
                    .build();
            wf.complete("a-done");
            keyedPrintedWait(wf, "wait-b", "b-done");
            wf.complete("b-done");
            wf.join("join")
                    .firstSuccess((state, results) -> state)
                    .toStep("hold")
                    .onFailure("done")
                    .endJoin();
            wf.timer("hold", Duration.ofHours(1)).toStep("done");
            wf.complete("done");
        });
    }

    /**
     * A version of a definition that waits at one step for {@code shipment-confirmed}, preceded by the given number
     * of decisions.
     *
     * @param key the key function of the wait, or {@code null} for a wait without a key
     * @param spin the number of decisions the initial step runs before the wait
     */
    private static Definition<ForkState, ForkContract> migrating(
            String id, long version, String waitStep, @Nullable Function<ForkState, String> key, int spin) {
        return migrating(id, version, waitStep, key, spin, false);
    }

    /** As the version above, whose wait gives up after an hour when {@code timed}, to the end. */
    private static Definition<ForkState, ForkContract> migrating(
            String id,
            long version,
            String waitStep,
            @Nullable Function<ForkState, String> key,
            int spin,
            boolean timed) {
        return forkDefinition(id, version, wf -> {
            if (spin > 0) {
                wf.initialStep("spin-0");
                for (int i = 0; i < spin - 1; i++) {
                    String next = i == spin - 2 ? waitStep : "spin-" + (i + 1);
                    wf.decide("spin-" + i, state -> next);
                }
            } else {
                wf.initialStep(waitStep);
            }
            var wait = wf.waitForSignal(waitStep, "shipment-confirmed", Payment.class);
            if (key != null) {
                wait = wait.correlateBy(key);
            }
            var signalled = wait.onSignal((state, payment) -> new ForkState(
                            state.ref(), state.refA(), state.refB(), payment.reference(), state.printed()))
                    .toStepOnSignal("done");
            if (timed) {
                signalled
                        .timeoutAfter(Duration.ofHours(1))
                        .onTimeout(state -> state)
                        .toStepOnTimeout("done");
            } else {
                signalled.build();
            }
            wf.complete("done");
        });
    }

    /** Where the join of {@link #failingFork} routes a failure. */
    private enum FailureRoute {
        /** To a {@code CompleteNode}. */
        COMPLETE,
        /** To a {@code FailNode}. */
        FAIL,
        /** To a timer of an hour. */
        WAIT,
        /** Nowhere: the join declares no failure route. */
        NONE
    }

    /**
     * An {@code ALL_REQUIRED} fork whose branch {@code a} fails at a {@code FailNode} while branch {@code b} waits
     * at {@code wait-b} for {@code label-printed} keyed by {@code refB}.
     *
     * @param compensable whether a compensable dispatch, completed by a signal, runs before the fork
     */
    private static Definition<ForkState, ForkContract> failingFork(String id, FailureRoute route, boolean compensable) {
        return failingFork(id, route, compensable, SiblingWait.KEYED);
    }

    /** How the wait of branch {@code b} of {@link #failingFork} is declared. */
    private enum SiblingWait {
        /** A wait keyed by {@code refB}. */
        KEYED,
        /** A wait keyed by {@code refB} that gives up after an hour. */
        TIMED,
        /** A wait without a key. */
        UNKEYED
    }

    /** As the fork above, with the wait of branch {@code b} declared as given. */
    private static Definition<ForkState, ForkContract> failingFork(
            String id, FailureRoute route, boolean compensable, SiblingWait sibling) {
        return forkDefinition(id, 1, wf -> {
            if (compensable) {
                wf.initialStep("reserve");
                wf.dispatchWithCompensation(
                        "reserve", "stock.reserve", state -> Map.of("ref", state.ref()), "release", "wait-reserved");
                wf.compensate("release", "reserve", "stock.release", state -> Map.of("ref", state.ref()));
                wf.waitForSignal("wait-reserved", "shipment-confirmed", Payment.class)
                        .correlateBy(ForkState::refA)
                        .onSignal((state, payment) -> new ForkState(
                                state.ref(), state.refA(), state.refB(), payment.reference(), state.printed()))
                        .toStepOnSignal("fork")
                        .build();
            } else {
                wf.initialStep("fork");
            }
            wf.fork("fork")
                    .branch("a", "a-fail", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("b", "wait-b", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join");
            wf.fail("a-fail", "BranchAFailed", state -> "branch a fails");
            switch (sibling) {
                case KEYED -> keyedPrintedWait(wf, "wait-b", "b-done");
                case TIMED -> {
                    wf.waitForSignal("wait-b", "label-printed", Payment.class)
                            .correlateBy(ForkState::refB)
                            .onSignal((state, payment) -> new ForkState(
                                    state.ref(), state.refA(), state.refB(), state.shipped(), payment.reference()))
                            .toStepOnSignal("b-done")
                            .timeoutAfter(Duration.ofHours(1))
                            .onTimeout(state -> state)
                            .toStepOnTimeout("b-done");
                }
                case UNKEYED ->
                    wf.waitForSignal("wait-b", "label-printed", Payment.class)
                            .onSignal((state, payment) -> new ForkState(
                                    state.ref(), state.refA(), state.refB(), state.shipped(), payment.reference()))
                            .toStepOnSignal("b-done")
                            .build();
            }
            wf.complete("b-done");
            var join = wf.join("join").allRequired((state, results) -> state).toStep("done");
            if (route != FailureRoute.NONE) {
                join.onFailure("join-failed");
            }
            join.endJoin();
            switch (route) {
                case COMPLETE -> wf.complete("join-failed");
                case FAIL -> wf.fail("join-failed", "JoinFailed", state -> "the join failed");
                case WAIT -> wf.timer("join-failed", Duration.ofHours(1)).toStep("done");
                case NONE -> {}
            }
            wf.complete("done");
        });
    }

    /**
     * An {@code ALL_REQUIRED} fork whose branch {@code a} waits for {@code a-go} keyed by {@code refA} and then fails,
     * while branch {@code b} waits at {@code wait-b} for {@code label-printed} keyed by {@code refB} and gives up after
     * an hour.
     *
     * @param compensable whether a compensable dispatch, completed by {@code stock-reserved}, runs before the fork
     */
    private static Definition<ForkState, EndingContract> endingFork(
            String id, FailureRoute route, boolean compensable) {
        return new Definition<>(
                id,
                1L,
                EndingContract.class,
                ForkState.class,
                wf -> wf.init(
                        ForkStart.class, start -> new ForkState(start.ref(), start.refA(), start.refB(), null, null)),
                wf -> {
                    if (compensable) {
                        wf.initialStep("reserve");
                        wf.dispatchWithCompensation(
                                "reserve",
                                "stock.reserve",
                                state -> Map.of("ref", state.ref()),
                                "release",
                                "wait-reserved");
                        wf.compensate("release", "reserve", "stock.release", state -> Map.of("ref", state.ref()));
                        wf.waitForSignal("wait-reserved", "stock-reserved", Payment.class)
                                .correlateBy(ForkState::refA)
                                .onSignal((state, payment) -> new ForkState(
                                        state.ref(), state.refA(), state.refB(), payment.reference(), state.printed()))
                                .toStepOnSignal("fork")
                                .build();
                    } else {
                        wf.initialStep("fork");
                    }
                    wf.fork("fork")
                            .branch("a", "wait-a", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                            .branch("b", "wait-b", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                            .join("join");
                    wf.waitForSignal("wait-a", "a-go", Payment.class)
                            .correlateBy(ForkState::refA)
                            .onSignal((state, payment) -> state)
                            .toStepOnSignal("a-fail")
                            .build();
                    wf.fail("a-fail", "BranchAFailed", state -> "branch a fails");
                    wf.waitForSignal("wait-b", "label-printed", Payment.class)
                            .correlateBy(ForkState::refB)
                            .onSignal((state, payment) -> new ForkState(
                                    state.ref(), state.refA(), state.refB(), state.shipped(), payment.reference()))
                            .toStepOnSignal("b-done")
                            .timeoutAfter(Duration.ofHours(1))
                            .onTimeout(state -> state)
                            .toStepOnTimeout("b-done");
                    wf.complete("b-done");
                    wf.join("join")
                            .allRequired((state, results) -> state)
                            .toStep("done")
                            .onFailure("join-failed")
                            .endJoin();
                    if (route == FailureRoute.COMPLETE) {
                        wf.complete("join-failed");
                    } else {
                        wf.fail("join-failed", "JoinFailed", state -> "the join failed");
                    }
                    wf.complete("done");
                });
    }

    /**
     * An order that waits at {@code wait-payment} for {@code payment-captured} keyed by its payment reference and gives
     * up after an hour, when the timeout path returns to the same step.
     *
     * @param rekey   whether the timeout path appends {@code -2} to the payment reference, so that the new entry has
     *                another key
     * @param breakable whether the timeout path throws while {@link #timeoutPathBroken} is set
     */
    private Definition<OrderState, OrderContract> timeoutReentering(String id, boolean rekey, boolean breakable) {
        return orderDefinition(id, OrderContract.class, wf -> {
            wf.initialStep("wait-payment");
            wf.waitForSignal("wait-payment", "payment-captured", Payment.class)
                    .correlateBy(OrderState::paymentReference)
                    .onSignal((state, payment) ->
                            new OrderState(state.ref(), state.paymentReference(), payment.reference()))
                    .toStepOnSignal("done")
                    .timeoutAfter(breakable ? Duration.ofMinutes(10) : Duration.ofHours(1))
                    .onTimeout(state -> {
                        if (breakable && timeoutPathBroken.get()) {
                            throw new IllegalStateException("the timeout path is broken");
                        }
                        return rekey
                                ? new OrderState(state.ref(), state.paymentReference() + "-2", state.captured())
                                : state;
                    })
                    .toStepOnTimeout("wait-payment");
            wf.complete("done");
        });
    }

    /** An order whose signal leads to a failure node, with no compensable step before it. */
    private static Definition<OrderState, OrderContract> failingOrder() {
        return orderDefinition(ORDER_FAILS_ON_SIGNAL, OrderContract.class, wf -> {
            wf.initialStep("wait-payment");
            wf.waitForSignal("wait-payment", "payment-captured", Payment.class)
                    .correlateBy(OrderState::paymentReference)
                    .onSignal((state, payment) ->
                            new OrderState(state.ref(), state.paymentReference(), payment.reference()))
                    .toStepOnSignal("failed")
                    .build();
            wf.fail("failed", "PaymentFailed", state -> "the payment failed");
        });
    }

    /** The race of {@link #race()} whose branch {@code b2} gives up after an hour, to its end. */
    private static Definition<ForkState, ForkContract> raceTimed() {
        return forkDefinition(RACE_TIMED, 1, wf -> {
            wf.initialStep("fork");
            wf.fork("fork")
                    .branch("b1", "wait-a", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("b2", "wait-b", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join");
            wf.waitForSignal("wait-a", "shipment-confirmed", Payment.class)
                    .correlateBy(ForkState::refA)
                    .onSignal((state, payment) -> new ForkState(
                            state.ref(), state.refA(), state.refB(), payment.reference(), state.printed()))
                    .toStepOnSignal("a-done")
                    .build();
            wf.complete("a-done");
            wf.waitForSignal("wait-b", "label-printed", Payment.class)
                    .correlateBy(ForkState::refB)
                    .onSignal((state, payment) -> new ForkState(
                            state.ref(), state.refA(), state.refB(), state.shipped(), payment.reference()))
                    .toStepOnSignal("b-done")
                    .timeoutAfter(Duration.ofHours(1))
                    .onTimeout(state -> state)
                    .toStepOnTimeout("b-done");
            wf.complete("b-done");
            wf.join("join")
                    .firstSuccess((state, results) -> state)
                    .toStep("hold")
                    .onFailure("done")
                    .endJoin();
            wf.timer("hold", Duration.ofHours(1)).toStep("done");
            wf.complete("done");
        });
    }

    /** A wait for {@code label-printed} keyed by {@code refB}. */
    private static void keyedPrintedWait(WorkflowBuilder<ForkState> wf, String step, String next) {
        wf.waitForSignal(step, "label-printed", Payment.class)
                .correlateBy(ForkState::refB)
                .onSignal((state, payment) ->
                        new ForkState(state.ref(), state.refA(), state.refB(), state.shipped(), payment.reference()))
                .toStepOnSignal(next)
                .build();
    }

    private static Definition<ForkState, ForkContract> forkDefinition(
            String id, long version, Consumer<WorkflowBuilder<ForkState>> body) {
        return new Definition<>(
                id,
                version,
                ForkContract.class,
                ForkState.class,
                wf -> wf.init(
                        ForkStart.class, start -> new ForkState(start.ref(), start.refA(), start.refB(), null, null)),
                body);
    }

    /** A migration from version one to version two of the definition that keeps the state. */
    private static WorkflowMigrationHandler<ForkState, ForkState> stateKeepingHandler(
            String definitionId, @Nullable String resumeStepId) {
        return new WorkflowMigrationHandler<>() {
            @Override
            public String definitionId() {
                return definitionId;
            }

            @Override
            public long fromDefinitionVersion() {
                return 1L;
            }

            @Override
            public long targetDefinitionVersion() {
                return 2L;
            }

            @Override
            public Class<ForkState> sourceStateType() {
                return ForkState.class;
            }

            @Override
            public Class<ForkState> targetStateType() {
                return ForkState.class;
            }

            @Override
            public MigrationResult<ForkState> migrate(ForkState sourceState, MigrationContext ctx) {
                return resumeStepId == null
                        ? MigrationResult.anchorAtInitial(sourceState)
                        : MigrationResult.continueAt(sourceState, resumeStepId);
            }
        };
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
        private final long version;

        Definition(
                String id,
                Class<C> contract,
                Class<S> stateType,
                Consumer<WorkflowBuilder<S>> init,
                Consumer<WorkflowBuilder<S>> body) {
            this(id, 1L, contract, stateType, init, body);
        }

        Definition(
                String id,
                long version,
                Class<C> contract,
                Class<S> stateType,
                Consumer<WorkflowBuilder<S>> init,
                Consumer<WorkflowBuilder<S>> body) {
            this.version = version;
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
            return version;
        }

        @Override
        public void define(WorkflowBuilder<S> wf) {
            init.accept(wf);
            body.accept(wf);
        }
    }
}
