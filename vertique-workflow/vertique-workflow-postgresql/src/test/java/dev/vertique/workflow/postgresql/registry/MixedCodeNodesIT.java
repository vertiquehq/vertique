// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.ActivationFixtures.DEFAULT;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowVersionNotRunnableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.FailingScopeRowIT.Deployment;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.postgresql.registry.MixedCodeFixtures.Shape;
import dev.vertique.workflow.postgresql.registry.failing.v1.FinanceDecision;
import dev.vertique.workflow.registry.store.DefinitionFailure.Rule;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies what a node does with work it cannot run because its deployed code lacks the version in question: a start
 * that resolves to the version, and a task completion or query on an instance pinned to it, fail to the caller with a
 * typed error and change nothing; a signal or branch signal for such an instance is recorded, since recording needs no
 * code, and applied by a node that has it; a timer command is deferred there, for a node with the
 * code to run; and no node fails an instance because it cannot run
 * its pin.
 *
 * <p>Two nodes share one database. Node B is deployed with version 1 of a definition only. Node A is started after it
 * with versions 1 and 2, so version 2 is published, becomes current, and reaches B only as a committed record.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class MixedCodeNodesIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    /** The contract of the code definitions of these tests; it declares the signals they wait for. */
    @WorkflowContract("mixed-code-c")
    public interface MixedContract extends OrderDocumentSignals {}

    /** The contract of a version that a node registers after the version that its instances are pinned to is gone. */
    @WorkflowContract("mixed-code-other")
    public interface OtherContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_mixed_code_nodes_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String MIXED_CONTRACT_NAME =
            MixedContract.class.getAnnotation(WorkflowContract.class).value();
    private static final String OTHER_CONTRACT_NAME =
            OtherContract.class.getAnnotation(WorkflowContract.class).value();

    @Test
    @DisplayName("a start on a node without the current version's code fails typed and creates no instance")
    void startOnNodeWithoutCurrentVersionCodeFailsTypedAndCreatesNoInstance(Vertx vertx) {
        String id = "mixed-code-start";
        DefinitionKey current = new DefinitionKey(DEFAULT, id, 2);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given node B with version 1 only and node A with versions 1 and 2, B having applied the activation
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_SIGNAL);
            await(b.app().definitionPropagationPoller().pollNow());

            // When a client on B starts the definition with an idempotency key
            Throwable refused = ActivationFixtures.failureOf(
                    () -> b.app().workflowOperations().start(start(id, "k1")));

            // Then the start fails typed, naming the scope, the id and the version and nothing else
            assertThat(refused).isInstanceOfSatisfying(WorkflowVersionNotRunnableException.class, failure -> {
                assertThat(failure.definitionKey()).isEqualTo(current);
                assertThat(failure.scopeKey()).isEqualTo(DEFAULT);
                assertThat(failure.getMessage()).contains(DEFAULT, id, "2").doesNotContain("OrderDocState");
            });

            // And neither an instance nor a start deduplication row exists for the key
            assertThat(database.instanceCount(id)).isZero();
            assertThat(database.dedupCount(id)).isZero();

            // And the same start on A creates an instance pinned to version 2
            WorkflowInstanceId started = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, started, 0);
            assertThat(versionOf(database, started)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a signal through a node without the pinned code is recorded and applied by a capable node")
    void signalThroughNodeWithoutPinnedCodeIsRecordedAndAppliedByACapableNode(Vertx vertx) {
        String id = "mixed-code-signal";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance started on A and waiting for a signal, and B with version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_SIGNAL);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, instance, 0);
            assertThat(versionOf(database, instance)).isEqualTo(2);
            Rows before = Rows.of(database, instance);
            assertThat(before.instance()).contains("WAITING");

            // When B receives the signal
            Throwable refused = ActivationFixtures.failureOf(() -> b.app()
                    .workflowOperations()
                    .signal(instance, "inventory.reserved", new InventoryReservedDoc("r1"), "d1"));

            // Then it is recorded: recording needs no code for the pinned version, and B applies nothing
            assertThat(refused)
                    .as("recording through a node without the pinned code")
                    .isNull();

            // And the instance and its history are as they were, with the key recorded for the signal
            assertThat(Rows.of(database, instance)).isEqualTo(before.withDedup(before.dedup() + 1));
            assertThat(dedupRowsWithKey(database, "d1")).isEqualTo(1);

            // And the same signal through A, with the same key, is a duplicate, and A's executor applies the recorded
            // command and advances the instance
            await(a.app()
                    .workflowOperations()
                    .signal(instance, "inventory.reserved", new InventoryReservedDoc("r1"), "d1"));
            await(a.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
            assertThat(database.instanceStatus(instance.value())).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("task completion through a node without the pinned code is recorded and applied by a capable node")
    void taskCompletionThroughNodeWithoutPinnedCodeIsRecordedAndAppliedByACapableNode(Vertx vertx) {
        String id = "mixed-code-task";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance on A waiting for a human task, and B with version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_TASK);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, instance, 0);
            UUID task = openTask(database, instance);
            Rows before = Rows.of(database, instance);

            // When B completes the task with a valid decision, and then queries the instance
            TaskCompletionCommand completion =
                    new TaskCompletionCommand(task, "approve", null, "c1", new WorkflowActor.User("reviewer"), null);
            Throwable completionRefused = ActivationFixtures.failureOf(() ->
                    b.app().pool().withTransaction(tx -> b.app().taskCallbacks().taskCompleted(completion, tx)));
            WorkflowView readOnB = await(b.app().workflowOperations().query(instance));

            // Then it is recorded: recording needs no code for the pinned version, and B applies nothing
            assertThat(completionRefused)
                    .as("recording through a node without the pinned code")
                    .isNull();
            assertThat(readOnB.instance().id()).isEqualTo(instance);

            // And the instance is unchanged, the key is recorded and the task is reserved by the recorded completion
            Rows recorded = Rows.of(database, instance);
            assertThat(recorded.instance()).isEqualTo(before.instance());
            assertThat(recorded.dedup()).isEqualTo(before.dedup() + 1);
            assertThat(before.tasks()).contains("OPEN");
            assertThat(recorded.tasks()).contains("COMPLETING");

            // And the same completion through A, with the same key, is a duplicate, and A's executor applies the
            // recorded command and advances the instance
            WorkflowView view = await(a.app().workflowOperations().query(instance));
            assertThat(view.instance().status()).isEqualTo(WorkflowStatus.WAITING);
            TaskMutationResult completed = await(
                    a.app().pool().withTransaction(tx -> a.app().taskCallbacks().taskCompleted(completion, tx)));
            await(a.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
            assertThat(completed).isEqualTo(TaskMutationResult.APPLIED);
            assertThat(database.instanceStatus(instance.value())).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("a start replay on a node without the pinned code compares the contract stored for the pin")
    void startReplayOnNodeWithoutPinnedCodeComparesTheContract(Vertx vertx) {
        String id = "mixed-code-replay";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance started on A and B with version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_SIGNAL);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, instance, 0);
            Rows before = Rows.of(database, instance);
            StartCommand otherContract = new StartCommand(
                    id, OTHER_CONTRACT_NAME, new PlaceOrderDoc("order-k1"), "k1", null, null, null, DEFAULT, null);

            // When B replays the start through the contract of the instance and through another contract
            WorkflowInstanceId sameContract = await(b.app().workflowOperations().start(start(id, "k1")));
            Throwable otherContractRefused = ActivationFixtures.failureOf(
                    () -> b.app().workflowOperations().start(otherContract));

            // Then the same contract returns the existing instance on B
            assertThat(sameContract).isEqualTo(instance);

            // and: another contract is an idempotency conflict of kind start, not the existing instance id
            assertThat(otherContractRefused).isInstanceOf(WorkflowIdempotencyConflictException.class);

            // and: once B holds no version of the id at all, the stored contract of the pin still answers the replay
            b.app().workflowRegistry().unregister(new DefinitionKey(DEFAULT, id, 1));
            assertThat(await(b.app().workflowOperations().start(start(id, "k1"))))
                    .isEqualTo(instance);
            assertThat(ActivationFixtures.failureOf(
                            () -> b.app().workflowOperations().start(otherContract)))
                    .isInstanceOf(WorkflowIdempotencyConflictException.class);

            // and: a later version registered under another contract does not replace the stored one
            b.app().workflowRegistry().register(MixedCodeFixtures.code(id, 3, Shape.COMPLETES, OtherContract.class));
            assertThat(await(b.app().workflowOperations().start(start(id, "k1"))))
                    .isEqualTo(instance);
            Throwable storedContractConflict = ActivationFixtures.failureOf(() -> b.app()
                    .workflowOperations()
                    .start(new StartCommand(
                            id,
                            OTHER_CONTRACT_NAME,
                            new PlaceOrderDoc("order-k1"),
                            "k1",
                            null,
                            null,
                            null,
                            DEFAULT,
                            null)));
            assertThat(storedContractConflict)
                    .isInstanceOf(WorkflowIdempotencyConflictException.class)
                    .hasMessageContaining(MIXED_CONTRACT_NAME);

            // and: nothing changed
            assertThat(Rows.of(database, instance)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("a start replay on a node without the pinned code takes no connection besides the start transaction")
    void startReplayOnNodeWithoutPinnedCodeTakesNoSecondConnection(Vertx vertx) {
        String id = "mixed-code-replay-pool";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance started on A, and B with version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_SIGNAL);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, instance, 0);

            // and: every connection of B's pool but one is held
            List<SqlConnection> held = new ArrayList<>();
            try {
                for (int i = 0; i < 9; i++) held.add(await(b.app().pool().getConnection()));

                // When B replays the start in a transaction on the one connection left, which must suffice
                WorkflowInstanceId replayed = await(b.app()
                        .pool()
                        .withTransaction(tx -> b.app().transactionalOperations().start(start(id, "k1"), tx))
                        .timeout(5, TimeUnit.SECONDS));

                // Then the replay returned the existing instance without waiting for another connection
                assertThat(replayed).isEqualTo(instance);
            } finally {
                held.forEach(connection -> connection.close());
            }
        }
    }

    @Test
    @DisplayName("a branch signal through a node without the pinned code is recorded and applied by a capable node")
    void branchSignalThroughNodeWithoutPinnedCodeIsRecordedAndAppliedByACapableNode(Vertx vertx) {
        String id = "mixed-code-branch";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance on A whose fork has a branch waiting for a signal, and B with version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_IN_BRANCHES);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(onDuplicatedContext(
                    vertx, () -> a.app().workflowOperations().start(start(id, "k1"))));
            awaitInitialExecution(database, instance, 2);
            Rows before = Rows.of(database, instance);
            assertThat(before.branches()).contains(MixedCodeFixtures.FIRST_BRANCH);

            // When B receives the branch signal through the branch-aware overload
            InventoryReservedDoc payload = new InventoryReservedDoc("r3");
            Throwable refused = ActivationFixtures.failureOf(
                    () -> onDuplicatedContext(vertx, () -> branchSignal(b, instance, payload, "d3")));

            // Then it is recorded: recording needs no code for the pinned version, and B applies nothing
            assertThat(refused)
                    .as("recording through a node without the pinned code")
                    .isNull();

            // And the branch tokens, the instance and the history are unchanged, with the key recorded for the signal
            assertThat(Rows.of(database, instance)).isEqualTo(before.withDedup(before.dedup() + 1));
            assertThat(count(database, "SELECT count(*) AS total FROM workflow_commands WHERE dedup_key = $1", "d3"))
                    .as("the recorded signal command")
                    .isEqualTo(1);

            // And the same call through A is a duplicate, and A's executor applies the recorded command and advances
            // the
            // branch
            await(onDuplicatedContext(vertx, () -> branchSignal(a, instance, payload, "d3")));
            await(a.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
            assertThat(Rows.of(database, instance).branches()).isNotEqualTo(before.branches());
        }
    }

    @Test
    @DisplayName("an instance of a failing row without a plan records its signal and is not failed")
    void instanceOfFailingRowWithoutAPlanRecordsItsSignalAndIsNotFailed(Vertx vertx) {
        String id = FailingScopeRowIT.PURCHASE;
        DefinitionKey key = new DefinitionKey(FailingScopeRowIT.ACME, id, 1);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            FailingScopeRowIT.own(database, id);

            // Given the first deployment, where acme's document is published and activated, one acme instance waits
            // for a signal and another waits for a scheduled timer
            Boot first = FailingScopeRowIT.boot(database, vertx, Deployment.firstWith(id, true));
            await(first.app()
                    .definitionService()
                    .load(
                            FailingScopeRowIT.ACME,
                            timerDocument(id),
                            DocumentFormat.YAML,
                            source("acme-timer", Instant.parse("2026-10-22T12:00:00Z"))));
            await(first.app().definitionService().publish(key));
            await(first.app().definitionService().activate(FailingScopeRowIT.ACME, id, FailingScopeRowIT.ACME, 1));
            WorkflowInstanceId waitingForSignal = startInAcme(first, "signal-order");
            WorkflowInstanceId waitingForTimer = startInAcme(first, "timer-order");
            await(first.app()
                    .workflowOperations()
                    .signal(waitingForTimer, "inventory.reserved", new InventoryReservedDoc("r4"), "d-first"));
            await(first.app().executionProbe().awaitIdle(waitingForTimer, IDLE_TIMEOUT));
            assertThat(timerStatus(database, waitingForTimer)).isEqualTo("SCHEDULED");
            first.close();

            // And a restart whose catalog no longer lists a class named by the row's binding, so the row is failing
            // and has no registered plan
            Boot second = FailingScopeRowIT.boot(database, vertx, Deployment.sameStateWithoutDecision(id));
            assertThat(FailingScopeRowIT.listed(second, key).failure().rule()).isEqualTo(Rule.NAME_BINDING_UNRESOLVED);
            assertThat(second.app().workflowRegistry().find(key)).isEmpty();
            Rows before = Rows.of(database, waitingForSignal);
            Rows timerInstanceBefore = Rows.of(database, waitingForTimer);

            // When the restarted node receives a signal for the first instance
            Throwable refused = ActivationFixtures.failureOf(() -> second.app()
                    .workflowOperations()
                    .signal(waitingForSignal, "inventory.reserved", new InventoryReservedDoc("r4"), "d4"));

            // Then the signal is recorded, since recording needs no plan, and nothing is applied: the instance row is
            // as it was and its key is kept for the recorded command
            assertThat(refused)
                    .as("recording for an instance whose plan this node lacks")
                    .isNull();
            assertThat(Rows.of(database, waitingForSignal)).isEqualTo(before.withDedup(before.dedup() + 1));
            assertThat(dedupRowsWithKey(database, "d4")).isEqualTo(1);

            // And when the timer of the second instance comes due, its command is deferred and its timer row stays
            // scheduled
            makeTimersDue(database, waitingForTimer);
            awaitCommandState(database, waitingForTimer, "TIMER", "DEFERRED");
            assertThat(timerStatus(database, waitingForTimer)).isEqualTo("SCHEDULED");

            // And neither instance was failed
            Rows timerInstanceAfter = Rows.of(database, waitingForTimer);
            assertThat(timerInstanceAfter.instance()).isEqualTo(timerInstanceBefore.instance());
            assertThat(timerInstanceAfter.tasks()).isEqualTo(timerInstanceBefore.tasks());
            assertThat(timerInstanceAfter.branches()).isEqualTo(timerInstanceBefore.branches());
            assertThat(database.instanceStatus(waitingForSignal.value())).isEqualTo("WAITING");
            assertThat(database.instanceStatus(waitingForTimer.value())).isEqualTo("WAITING");
        }
    }

    // --- Nodes ---

    /**
     * Node B: deployed with version 1 of the definition only. Its command executor polls so rarely that it never looks
     * at the commands of node A: an executor without the pinned code defers a command it peeks, and these tests are
     * about what B's other operations do, with A running the instance.
     */
    private static Boot nodeWithV1Only(DefinitionScenario database, String id) {
        JsonObject config = new JsonObject()
                .put("workflow", new JsonObject().put("executor", new JsonObject().put("pollIntervalMs", 3_600_000L)));
        return database.node(
                none(),
                unobserved(),
                List.of(MixedCodeFixtures.code(id, 1, Shape.COMPLETES, MixedContract.class)),
                config);
    }

    /** Makes the scheduled timers of the instance due now, with their commands. */
    private static void makeTimersDue(DefinitionScenario database, WorkflowInstanceId instance) {
        database.execute(
                "UPDATE workflow_commands SET fire_at = clock_timestamp(), available_at = clock_timestamp()"
                        + " WHERE workflow_id = $1 AND kind = 'TIMER' AND state = 'PENDING'",
                Tuple.of(instance.value()));
        database.execute(
                "UPDATE workflow_timers SET fire_at = clock_timestamp() WHERE workflow_id = $1 AND status = 'SCHEDULED'",
                Tuple.of(instance.value()));
    }

    /** Waits until the command of the kind is in the state; a poll cannot tell the state from a stale read. */
    private static void awaitCommandState(
            DefinitionScenario database, WorkflowInstanceId instance, String kind, String state) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String seen = "no command";
        while (System.nanoTime() < deadline) {
            var rows = await(database.admin()
                    .preparedQuery("SELECT state FROM workflow_commands WHERE workflow_id = $1 AND kind = $2")
                    .execute(Tuple.of(instance.value(), kind)));
            if (rows.iterator().hasNext()) {
                seen = rows.iterator().next().getString("state");
                if (state.equals(seen)) {
                    return;
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting the " + kind + " command", e);
            }
        }
        throw new AssertionError("The " + kind + " command of " + instance + " is " + seen + ", not " + state);
    }

    private static String timerStatus(DefinitionScenario database, WorkflowInstanceId instance) {
        return one(database, "SELECT status FROM workflow_timers WHERE workflow_id = $1", instance.value())
                .getString("status");
    }

    /** Node A: deployed with version 1 and a version 2 that stops where the shape says. */
    private static Boot nodeWithV1AndV2(DefinitionScenario database, String id, Shape version2) {
        List<WorkflowDefinition<?, ?>> code = List.of(
                MixedCodeFixtures.code(id, 1, Shape.COMPLETES, MixedContract.class),
                MixedCodeFixtures.code(id, 2, version2, MixedContract.class));
        return database.node(none(), unobserved(), code);
    }

    /** Delivers the signal to the first branch of the fork, in one transaction on the node. */
    private static Future<Void> branchSignal(
            Boot node, WorkflowInstanceId instance, InventoryReservedDoc payload, String dedupKey) {
        return node.app().pool().withTransaction(tx -> node.app()
                .transactionalOperations()
                .signal(
                        instance,
                        "inventory.reserved",
                        payload,
                        dedupKey,
                        MixedCodeFixtures.FORK_STEP,
                        MixedCodeFixtures.FIRST_BRANCH,
                        tx));
    }

    /** Runs the action on a duplicated Vert.x context, which the engine requires to drive the branches of a fork. */
    private static <T> Future<T> onDuplicatedContext(Vertx vertx, Supplier<Future<T>> action) {
        Promise<T> promise = Promise.promise();
        ((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(ignored -> action.get()
                .onComplete(promise));
        return promise.future();
    }

    // --- Commands and documents ---

    private static StartCommand start(String id, String idempotencyKey) {
        return new StartCommand(
                id,
                MIXED_CONTRACT_NAME,
                new PlaceOrderDoc("order-" + idempotencyKey),
                idempotencyKey,
                null,
                null,
                null,
                DEFAULT,
                null);
    }

    private static WorkflowInstanceId startInAcme(Boot node, String orderId) {
        WorkflowInstanceId instance = await(node.app()
                .workflowOperations()
                .start(FailingScopeRowIT.command(
                        FailingScopeRowIT.ACME,
                        FailingScopeRowIT.PURCHASE,
                        orderId,
                        "start-" + UUID.randomUUID(),
                        null,
                        null)));
        await(node.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
        return instance;
    }

    /** A document that waits for a signal, then for a timer, then for a finance decision named by its binary name. */
    private static byte[] timerDocument(String id) {
        return """
                definitionId: %s
                definitionVersion: 1
                stateType: dev.vertique.workflow.definition.it.fixture.OrderDocState
                contract: %s
                startPayloadType: dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc
                initialStateMapper: doc.order.fromPlaceOrder
                initialStep: wait-approval
                steps:
                  - id: wait-approval
                    type: wait-signal
                    signal: inventory.reserved
                    payloadType: dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc
                    stateReducer: doc.order.applyInventoryReserved
                    next: defer
                  - id: defer
                    type: timer
                    fireAt: PT1H
                    next: finance-review
                  - id: finance-review
                    type: human-task
                    taskType: approval
                    assignment:
                      mode: role
                      value: reviewers
                    decisions:
                      - name: approve
                        payloadType: %s
                        applicator: doc.order.keepState
                        next: done
                  - id: done
                    type: complete
                """.formatted(id, FailingScopeRowIT.ContractC.class.getName(), FinanceDecision.class.getName())
                .getBytes(StandardCharsets.UTF_8);
    }

    // --- Rows ---

    /** Everything a refused call must leave as it was, read straight from the tables. */
    private record Rows(String instance, long history, long dedup, String branches, String tasks, String timers) {

        /** The same rows with another number of deduplication rows. */
        Rows withDedup(long dedup) {
            return new Rows(instance, history, dedup, branches, tasks, timers);
        }

        static Rows of(DefinitionScenario database, WorkflowInstanceId id) {
            Row instance = one(database, """
                    SELECT definition_version, version, status, current_step_id, wait_type, wait_key, state_json::text AS state
                    FROM workflow_instances WHERE id = $1
                    """, id.value());
            String instanceText = String.join(
                    "|",
                    String.valueOf(instance.getLong("definition_version")),
                    String.valueOf(instance.getLong("version")),
                    instance.getString("status"),
                    instance.getString("current_step_id"),
                    String.valueOf(instance.getString("wait_type")),
                    String.valueOf(instance.getString("wait_key")),
                    instance.getString("state"));
            return new Rows(
                    instanceText,
                    count(
                            database,
                            "SELECT count(*) AS total FROM workflow_history WHERE workflow_id = $1",
                            id.value()),
                    count(database, "SELECT count(*) AS total FROM workflow_dedup WHERE workflow_id = $1", id.value()),
                    joined(database, """
                            SELECT branch_id || ':' || current_step_id || ':' || status AS item
                            FROM workflow_branch_tokens WHERE workflow_id = $1 ORDER BY branch_id
                            """, id.value()),
                    joined(database, """
                            SELECT task_id::text || ':' || status || ':' || reminders_fired_count AS item
                            FROM workflow_tasks WHERE workflow_id = $1 ORDER BY task_id
                            """, id.value()),
                    joined(database, """
                            SELECT purpose || ':' || status || ':' || fire_at AS item
                            FROM workflow_timers WHERE workflow_id = $1 ORDER BY fire_at, timer_id
                            """, id.value()));
        }
    }

    private static UUID openTask(DefinitionScenario database, WorkflowInstanceId instance) {
        return one(
                        database,
                        "SELECT task_id FROM workflow_tasks WHERE workflow_id = $1 AND status = 'OPEN'",
                        instance.value())
                .getUUID("task_id");
    }

    private static long versionOf(DefinitionScenario database, WorkflowInstanceId instance) {
        return one(database, "SELECT definition_version FROM workflow_instances WHERE id = $1", instance.value())
                .getLong("definition_version");
    }

    private static long dedupRowsWithKey(DefinitionScenario database, String key) {
        return count(database, "SELECT count(*) AS total FROM workflow_dedup WHERE key = $1", key);
    }

    private static Row one(DefinitionScenario database, String sql, Object... parameters) {
        return await(database.admin().preparedQuery(sql).execute(Tuple.from(List.of(parameters))))
                .iterator()
                .next();
    }

    private static long count(DefinitionScenario database, String sql, Object... parameters) {
        return one(database, sql, parameters).getLong("total");
    }

    private static String joined(DefinitionScenario database, String sql, Object... parameters) {
        List<String> items = new ArrayList<>();
        for (Row row : await(database.admin().preparedQuery(sql).execute(Tuple.from(List.of(parameters))))) {
            items.add(row.getString("item"));
        }
        return String.join(",", items);
    }

    private record StartSnapshot(
            String commandState,
            java.time.OffsetDateTime availableAt,
            Integer deferralCount,
            Integer attempts,
            String lastError,
            String instanceStatus,
            long branchCount) {}

    /** Waits for the START command and its setup state; awaitIdle only means no command is currently runnable. */
    private static void awaitInitialExecution(
            DefinitionScenario database, WorkflowInstanceId instance, long expectedBranchCount) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        StartSnapshot snapshot = null;
        while (true) {
            var rows = await(database.admin().preparedQuery("""
                            SELECT c.state AS command_state, c.available_at, c.deferral_count, c.attempts, c.last_error,
                                   i.status AS instance_status,
                                   count(b.branch_id) AS branch_count
                            FROM workflow_instances i
                            LEFT JOIN workflow_commands c ON c.workflow_id = i.id AND c.kind = 'START'
                            LEFT JOIN workflow_branch_tokens b ON b.workflow_id = i.id
                            WHERE i.id = $1
                            GROUP BY c.state, c.available_at, c.deferral_count, c.attempts, c.last_error, i.status
                            """).execute(Tuple.of(instance.value())));
            if (rows.iterator().hasNext()) {
                Row row = rows.iterator().next();
                snapshot = new StartSnapshot(
                        row.getString("command_state"),
                        row.getOffsetDateTime("available_at"),
                        row.getInteger("deferral_count"),
                        row.getInteger("attempts"),
                        row.getString("last_error"),
                        row.getString("instance_status"),
                        row.getLong("branch_count"));
                if ("COMPLETE".equals(snapshot.commandState())
                        && !"PENDING".equals(snapshot.instanceStatus())
                        && snapshot.branchCount() >= expectedBranchCount) {
                    return;
                }
                if ("FAILED".equals(snapshot.instanceStatus())
                        || List.of("DEAD", "REJECTED", "FAILED", "CANCELLED").contains(snapshot.commandState())) {
                    throw new AssertionError("START did not complete successfully for " + instance + ": " + snapshot);
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("START setup did not reach its expected state for " + instance + ": "
                        + (snapshot == null ? "instance or START command row is missing" : snapshot));
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting START setup for " + instance, e);
            }
        }
    }

    // --- Fixtures ---

    private static void own(DefinitionScenario database, String id) {
        ActivationFixtures.clearActivations(database.admin(), id);
        database.ownRows(id);
        database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), id));
    }

    private static BootObservation unobserved() {
        return new BootObservation("mixed-code-unused", 1, "unused", false);
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }
}
