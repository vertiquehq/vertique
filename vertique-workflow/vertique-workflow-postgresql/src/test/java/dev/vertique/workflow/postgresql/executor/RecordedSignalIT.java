// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.failureOf;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.sleep;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.IsolationLevel;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowSignalLimitException;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CorrelationCtx;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies transactional signal recording, ordered execution, refusal ordering, isolation and record-time bounds. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class RecordedSignalIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_recorded_signal_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("recording refuses only a missing or ended instance or an undeclared name, after duplicate lookup")
    void signalIsRefusedAtRecordTimeOnlyForMissingEndedOrUndeclared() {
        try (SignalFixture f = order()) {
            // Given A waiting at paid and B completed by paid/k1, with all executors stopped
            Node node = f.node("recorder").build();
            WorkflowInstanceId a = f.waiting(node, "A");
            WorkflowInstanceId b = f.waiting(node, "end-B");
            f.record(node, b, "paid", signal("paid"), "k1");
            f.idle(node, b);
            assertThat(f.snapshot(b).getString("status")).isEqualTo("COMPLETED");

            // When caller transactions record valid, undeclared, duplicate, ended and missing signals
            f.record(node, a, "paid", signal("paid"), "k2");
            Throwable undeclared = f.refusalAndCommitOtherWork(node, a, "refund", "k3");
            f.record(node, b, "paid", signal("ignored"), "k1");
            Throwable ended = f.refusalAndCommitOtherWork(node, b, "paid", "k4");
            WorkflowInstanceId missing = new WorkflowInstanceId(UUID.randomUUID());
            Throwable absent = failureOf(f.recordAsync(node, missing, "paid", signal("missing"), "k5"), IDLE);

            // Then the valid recording is pending, business refusals claimed no key even though their callers committed
            assertThat(f.signals(a)).singleElement().satisfies(row -> {
                assertThat(row.state()).isEqualTo("PENDING");
                assertThat(row.attempts()).isZero();
            });
            assertThat(undeclared)
                    .isInstanceOf(WorkflowSignalRejectedException.class)
                    .hasMessageContaining(a.value().toString())
                    .hasMessageContaining("refund")
                    .hasMessageContaining("1");
            assertThat(f.dedup(a, "k3")).isZero();
            assertThat(f.businessRows("k3")).isEqualTo(1);
            assertThat(f.signals(b)).hasSize(1);
            assertThat(ended).isInstanceOf(WorkflowSignalRejectedException.class);
            assertThat(f.dedup(b, "k4")).isZero();
            assertThat(f.businessRows("k4")).isEqualTo(1);
            assertThat(absent).isInstanceOf(WorkflowInstanceNotFoundException.class);
            assertThat(f.dedup(missing, "k5")).isZero();
        }
    }

    @Test
    @DisplayName("a signal recorded behind a pending start applies in the next segment")
    void signalRecordedBehindAPendingStartAppliesNext() {
        try (SignalFixture f = order()) {
            // Given the order definition and a stopped executor
            Node node = f.node("engine").build();
            // When separate caller transactions record start then paid, before the executor starts
            WorkflowInstanceId id = node.record(f.start("pending"));
            f.record(node, id, "paid", signal("paid"), "paid");
            assertThat(f.fixture.commandsOf(id)).extracting(CommandRow::state).containsExactly("PENDING", "PENDING");
            f.idle(node, id);
            // Then each command completed in its own ordered segment, and paid applied after the start reached its wait
            List<CommandRow> commands = f.fixture.commandsOf(id);
            assertThat(commands).extracting(CommandRow::state).containsExactly("COMPLETE", "COMPLETE");
            assertThat(commands).extracting(CommandRow::seq).isSorted();
            assertThat(f.snapshot(id).getString("current_step_id")).isEqualTo("shipped");
            List<History> history = f.history(id);
            assertThat(f.commandHistory(id, "COMMAND_COMPLETED"))
                    .containsExactly(commands.get(0).id(), commands.get(1).id());
            History startEnd = f.entry(id, "COMMAND_COMPLETED", commands.get(0).id());
            History received = f.signalHistory(id).getFirst();
            assertThat(startEnd.sequence()).isLessThan(received.sequence());
            assertThat(startEnd.transaction()).isNotEqualTo(received.transaction());
            assertThat(history).filteredOn(h -> h.type().equals("START")).hasSize(1);
        }
    }

    @Test
    @DisplayName("later signals cannot overtake a deferred or backing-off head")
    void laterSignalsNeverOvertakeADeferredOrBackingOffHead() {
        try (SignalFixture f = order()) {
            // Given waiting instances, a hidden pinned class on A's only executor, and a one-shot transient fault on B
            Definition backoff = new OrderSignalFixture("backoff-order");
            f.seed(backoff, List.of("paid", "shipped", "note"), new JsonObject());
            Node recorder = f.node("recorder").build();
            WorkflowInstanceId a = f.waiting(recorder, "deferred");
            WorkflowInstanceId b = await(f.caller(
                    () -> recorder.pool.withTransaction(tx -> recorder.engine.start(f.start(backoff, "backoff"), tx))));
            f.idle(recorder, b);
            AtomicBoolean hidden = new AtomicBoolean(true);
            var visible = f.registry;
            var unavailable = new DefaultWorkflowRegistry();
            unavailable.register(backoff);
            var switchable = (dev.vertique.workflow.registry.WorkflowRegistry) java.lang.reflect.Proxy.newProxyInstance(
                    visible.getClass().getClassLoader(),
                    new Class<?>[] {dev.vertique.workflow.registry.WorkflowRegistry.class},
                    (proxy, method, args) -> {
                        try {
                            return method.invoke(hidden.get() ? unavailable : visible, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            AtomicBoolean failB = new AtomicBoolean(true);
            Node executor = f.node("only-executor")
                    .withRegistry(switchable)
                    .runner(f.failSegmentsFor(b, failB))
                    .transientBackoffMs(30_000, 30_000)
                    .build();
            f.record(recorder, a, "paid", signal("a-head"), "a-head");
            f.record(recorder, a, "shipped", signal("a-later"), "a-later");
            f.record(recorder, b, "paid", signal("b-head"), "b-head");
            f.record(recorder, b, "shipped", signal("b-later"), "b-later");
            executor.startExecutor();
            awaitCondition("A's head to defer", IDLE, () -> f.signal(a, "a-head")
                    .state()
                    .equals("DEFERRED"));
            awaitCondition(
                    "B's head to back off", IDLE, () -> f.signal(b, "b-head").transientCount() == 1);
            long aHead = f.signal(a, "a-head").id();
            // When executors run for five seconds while the heads are unavailable
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < until) {
                assertThat(f.signal(a, "a-later").state()).isEqualTo("PENDING");
                assertThat(f.signal(b, "b-later").state()).isEqualTo("PENDING");
                assertThat(f.signalHistory(a)).isEmpty();
                assertThat(f.signalHistory(b)).isEmpty();
                sleep(50);
            }
            // When the code is available and the fixture expires both backoffs
            executor.stopExecutor();
            hidden.set(false);
            f.fixture.fastForward(aHead);
            f.fixture.fastForward(f.signal(b, "b-head").id());
            executor.startExecutor();
            executor.awaitIdle(a);
            executor.awaitIdle(b);
            executor.stopExecutor();
            // Then both queues settled in sequence order
            f.assertSignalsInOrder(a, "paid", "shipped");
            f.assertSignalsInOrder(b, "paid", "shipped");
        }
    }

    @Test
    @DisplayName("a throwing updater rejects only its command and retains the dedup key with sanitized reasons")
    void throwingUpdaterRejectsTheSignalAndKeepsItsDedupKey() {
        try (SignalFixture f = order()) {
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, "throwing");
            JsonObject before = f.snapshot(id);
            // When a caller records an updater failure containing a secret, then repeats its committed key
            f.record(node, id, "paid", new Signal(true, "4111-secret", "bad", false, false), "k1");
            f.idle(node, id);
            f.record(node, id, "paid", signal("duplicate"), "k1");
            // Then only the signal was rejected; its reason contains framework facts, never the thrown text or class
            CommandRow command = f.signals(id).getFirst();
            assertThat(command.state()).isEqualTo("REJECTED");
            assertThat(command.lastError())
                    .startsWith("DETERMINISTIC_FAILURE")
                    .doesNotContain("4111-secret", "updater echoed", "IllegalArgumentException");
            History rejected = f.entry(id, "COMMAND_REJECTED", command.id());
            assertThat(rejected.reason())
                    .startsWith("DETERMINISTIC_FAILURE")
                    .doesNotContain("4111-secret", "updater echoed", "IllegalArgumentException");
            assertThat(rejected.text())
                    .doesNotContain(
                            "4111-secret", "updater echoed", "IllegalArgumentException", "WorkflowDefinitionException");
            assertThat(f.snapshot(id)).isEqualTo(before);
            assertThat(f.signals(id)).hasSize(1);
            assertThat(f.dedup(id, "k1")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a late signal to a losing first-success branch is stale and never parked")
    void lateSignalToALosingBranchIsStaleAndNeverParked() {
        try (SignalFixture f = new SignalFixture(
                db.toPoolConfig(),
                new RaceForkFixture(),
                List.of(),
                new JsonObject().put("fork/b2", new JsonArray().add("ack")))) {
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, "race");
            assertThat(f.scalar(
                            "SELECT count(*) FROM workflow_branch_tokens WHERE workflow_id=$1 AND branch_id='b2' AND"
                                    + " status='SUPERSEDED'",
                            id))
                    .isEqualTo(1);
            JsonObject before = f.snapshot(id);
            // When ack is recorded for the superseded sibling
            await(f.caller(() -> node.pool.withTransaction(
                    tx -> node.engine.signal(id, "ack", signal("ack"), "late", "fork", "b2", tx))));
            f.idle(node, id);
            // Then the command settles stale and names itself in history, without changing the instance
            CommandRow late = f.signals(id).getFirst();
            assertThat(late.state()).isEqualTo("STALE");
            assertThat(f.entry(id, "COMMAND_STALE", late.id()).text()).contains("ack");
            assertThat(f.signals(id)).noneMatch(row -> row.state().equals("PARKED"));
            assertThat(f.snapshot(id)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("a legacy definition without stored names records an undeclared signal for executor rejection")
    void legacyRowWithoutNamesIsRejectedByTheExecutor() {
        try (SignalFixture f = order()) {
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, "legacy");
            await(f.fixture
                    .observer()
                    .query("UPDATE workflow_definitions SET signal_names_json=NULL")
                    .execute());
            // When a caller records a name the legacy plan never declares
            f.record(node, id, "refund", signal("refund"), "refund");
            assertThat(f.signals(id))
                    .singleElement()
                    .extracting(CommandRow::state)
                    .isEqualTo("PENDING");
            f.idle(node, id);
            // Then refusal belongs to the executor and its sanitized history names the signal
            CommandRow command = f.signals(id).getFirst();
            assertThat(command.state()).isEqualTo("REJECTED");
            assertThat(f.entry(id, "COMMAND_REJECTED", command.id()).text()).contains("refund");
        }
    }

    @Test
    @DisplayName("an explicit carrier is stored without identity and its correlation reaches the updater intent")
    void explicitCarrierIsStoredWithoutTheIdentitySnapshot() {
        try (SignalFixture f = order()) {
            Node node = f.node("engine").carryContext().build();
            WorkflowInstanceId id = f.waiting(node, "carrier");
            // When an explicit carrier has correlation E and an authenticated identity snapshot
            DurableMetadata carrier = ExplicitCarriers.withSnapshot("E");
            await(f.caller(() -> node.pool.withTransaction(
                    tx -> node.engine.signal(id, "paid", signal("paid"), "carrier", null, null, carrier, tx))));
            // Then the recorded row keeps correlation E and strips identity before execution
            JsonObject stored = f.row(
                            "SELECT explicit_carrier_json FROM workflow_commands WHERE workflow_id=$1 AND"
                                    + " kind='SIGNAL'",
                            id)
                    .getJsonObject("explicit_carrier_json");
            DurableMetadata recorded = DurableMetadata.fromCarrier(stored);
            assertThat(recorded.body("correlation")).contains(new JsonObject().put("correlationId", "E"));
            assertThat(recorded.body("identity-snapshot")).isEmpty();
            f.idle(node, id);
            assertThat(f.intentCorrelations(id)).containsExactly("E");
        }
    }

    @Test
    @DisplayName("a start and signal rolled back by their caller leave no durable workflow rows")
    void startAndSignalInARolledBackTransactionLeaveNothing() {
        try (SignalFixture f = order()) {
            Node node = f.node("engine").build();
            WorkflowInstanceId[] recorded = new WorkflowInstanceId[1];
            RuntimeException rollback = new RuntimeException("intentional caller rollback");
            // When one caller records both commands successfully, then explicitly rolls back
            Throwable failed = failureOf(
                    f.caller(() -> node.pool.withTransaction(tx -> node.engine
                            .start(f.start("rollback"), tx)
                            .compose(id -> {
                                recorded[0] = id;
                                return node.engine.signal(id, "paid", signal("paid"), "rollback-paid", tx);
                            })
                            .compose(v -> Future.failedFuture(rollback)))),
                    IDLE);
            assertThat(failed).isSameAs(rollback);
            WorkflowInstanceId id = recorded[0];
            assertThat(id).isNotNull();
            // Then neither command nor its dedup or queue escaped the rollback, before or after executor startup
            f.assertAbsent(id);
            f.idle(node, id);
            f.assertAbsent(id);
        }
    }

    @Test
    @DisplayName("a start and two signals execute once each in sequence on three engines")
    void startAndTwoSignalsRunInSequenceOnceEachOnThreeExecutors() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new SequenceFixture(), List.of("a", "b"))) {
            // Given three stopped executors, each observed at every claim and holding its segments back 300 ms,
            // and one caller recording a whole queue
            ClaimOrderProbe probe = new ClaimOrderProbe(f.fixture.observer(), f.fixture.vertx(), 300);
            List<Node> nodes = List.of("one", "two", "three").stream()
                    .map(name -> f.node(name)
                            .commands(probe.commands())
                            .runner(probe.segments())
                            .build())
                    .toList();
            Node caller = nodes.getFirst();
            WorkflowInstanceId id = await(f.caller(() -> caller.pool.withTransaction(
                    tx -> caller.engine.start(f.start("three"), tx).compose(created -> caller.engine
                            .signal(created, "a", signal("a"), "a", tx)
                            .compose(v -> caller.engine.signal(created, "b", signal("b"), "b", tx))
                            .map(created)))));
            // When all three executors compete until idle
            nodes.forEach(Node::startExecutor);
            caller.awaitIdle(id);
            nodes.forEach(Node::stopExecutor);
            // Then the existing final attempt/order assertions are the sensitivity experiment's only detectors
            List<CommandRow> commands = f.fixture.commandsOf(id);
            assertThat(commands).hasSize(3).extracting(CommandRow::state).containsOnly("COMPLETE");
            assertThat(commands).extracting(CommandRow::attempts).containsExactly(1, 1, 1);
            assertThat(f.commandHistory(id, "COMMAND_COMPLETED"))
                    .containsExactlyElementsOf(
                            commands.stream().map(CommandRow::id).toList());
            assertThat(f.history(id)).filteredOn(h -> h.type().equals("START")).hasSize(1);
            f.assertSignalsInOrder(id, "a", "b");
            assertThat(f.snapshot(id).getString("status")).isEqualTo("COMPLETED");
            // And the claims themselves were made in sequence, one per command, never past an unsettled earlier one
            assertThat(probe.claims())
                    .as("claims in the order they returned")
                    .containsExactlyElementsOf(
                            commands.stream().map(CommandRow::id).toList());
            assertThat(probe.claimedPastAnUnsettledEarlierCommand())
                    .as("commands claimed while an earlier command of the instance was unsettled")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("ten transient signal segment failures spend only the successful attempt")
    void signalSegmentFailingTransientlyTenTimesIsAppliedOnce() {
        try (SignalFixture f = order()) {
            Node recorder = f.node("recorder").build();
            WorkflowInstanceId id = f.waiting(recorder, "ten-faults");
            FailingTransactions faults = FailingTransactions.times(10, FailingTransactions.connectionFailure());
            Node executor = f.node("executor")
                    .maxAttempts(5)
                    .transientBackoffMs(10, 50)
                    .runner(faults)
                    .build();
            // When the paid segment loses its connection ten times before succeeding
            f.record(recorder, id, "paid", signal("paid"), "paid");
            executor.startExecutor();
            try {
                awaitCondition(
                        "the paid signal to settle through its backoffs",
                        IDLE,
                        () -> f.signals(id).getFirst().state().equals("COMPLETE"));
            } finally {
                executor.stopExecutor();
            }
            // Then the signal applied once, with all ten transient retries and one spent attempt
            CommandRow command = f.signals(id).getFirst();
            assertThat(command.state()).isEqualTo("COMPLETE");
            assertThat(command.transientCount()).isEqualTo(10);
            assertThat(command.attempts()).isEqualTo(1);
            assertThat(faults.failed()).isEqualTo(10);
            assertThat(f.signals(id)).noneMatch(row -> row.state().equals("DEAD"));
            f.assertSignalsInOrder(id, "paid");
            assertThat(f.snapshot(id).getString("current_step_id")).isEqualTo("shipped");
        }
    }

    @Test
    @DisplayName("the open-signal cap refuses without claiming dedup and frees one slot on settlement")
    void signalBeyondTheOpenSignalCapIsRefusedUntilOneSettles() throws Exception {
        try (SignalFixture f = order()) {
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, "cap");
            // Given 99 unawaited notes and one paid, all recorded with the default cap of 100
            f.recordSignals(node, id, "note", 99);
            f.record(node, id, "paid", signal("paid"), "paid");
            long historyBefore = f.fixture.rowsOf("workflow_history", id);
            // When the caller catches the refusal and commits independent valid work in that same transaction
            Throwable refusal = f.refusalAndCommitOtherWork(node, id, "note", "k-over");
            // Then an early dedup claim cannot disappear behind a whole-transaction rollback
            assertThat(refusal)
                    .isInstanceOf(WorkflowSignalLimitException.class)
                    .hasMessageContaining(id.value().toString())
                    .hasMessageContaining("100");
            assertThat(f.businessRows("k-over")).isEqualTo(1);
            assertThat(f.signals(id)).hasSize(100).extracting(CommandRow::state).containsOnly("PENDING");
            assertThat(f.dedup(id, "k-over")).isZero();
            assertThat(f.fixture.rowsOf("workflow_history", id)).isEqualTo(historyBefore);
            assertThat(f.openSignals(id)).isEqualTo(100);
            // When paid settles, notes stay parked and counted; the refused key can then be retried
            f.idle(node, id);
            assertThat(f.signals(id))
                    .filteredOn(row -> row.state().equals("PARKED"))
                    .hasSize(99);
            assertThat(f.openSignals(id)).isEqualTo(99);
            f.record(node, id, "note", signal("over"), "k-over");
            assertThat(f.signal(id, "k-over").state()).isEqualTo("PENDING");
            assertThat(f.openSignals(id)).isEqualTo(100);

            // Given another instance with a cap of two and one open note
            Node capped =
                    f.node("cap-two").recordingConfig(recording(2, 262144)).build();
            WorkflowInstanceId raced = f.waiting(capped, "cap-two");
            f.record(capped, raced, "note", signal("first"), "first");
            // When two caller threads race for the last slot
            var threads = Executors.newFixedThreadPool(2);
            CyclicBarrier ready = new CyclicBarrier(2);
            try {
                var left = threads.submit(() -> f.raceRecord(capped, raced, "left", ready));
                var right = threads.submit(() -> f.raceRecord(capped, raced, "right", ready));
                List<Boolean> committed = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
                // Then precisely one commits and the losing recording leaves no row or key
                assertThat(committed).containsExactlyInAnyOrder(true, false);
                assertThat(f.signals(raced)).hasSize(2);
                assertThat(f.openSignals(raced)).isEqualTo(2);
                assertThat(f.dedup(raced, "left") + f.dedup(raced, "right")).isEqualTo(1);
            } finally {
                threads.shutdownNow();
                assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("the cap holds with a stale repeatable-read snapshot in fifty fresh instances")
    void openSignalCapHoldsUnderRepeatableRead() {
        try (SignalFixture f = order()) {
            Node node = f.node("cap-two").recordingConfig(recording(2, 262144)).build();
            for (int run = 0; run < 50; run++) {
                // Given one open note, stopped executors and two repeatable-read sessions, B's snapshot fixed first
                WorkflowInstanceId id = f.waiting(node, "repeatable-" + run);
                f.record(node, id, "note", signal("first"), "first");
                Throwable bFailure;
                try (TwoSessions sessions = TwoSessions.repeatableRead(node)) {
                    await(sessions.b
                            .preparedQuery("SELECT id FROM workflow_instances WHERE id=$1")
                            .execute(Tuple.of(id.value())));
                    // When A records and commits before B tries to record from its old snapshot
                    await(node.engine.signal(id, "note", signal("a"), "k-a", sessions.a));
                    sessions.commitA();
                    bFailure = sessions.tryB(() -> node.engine.signal(id, "note", signal("b"), "k-b", sessions.b));
                }
                // Then a fresh observation transaction sees exactly two open commands and the maintained counter
                JsonObject finalRows = await(f.fixture.observer().withTransaction(tx -> tx.preparedQuery(
                                "SELECT q.open_signals, (SELECT count(*) FROM workflow_commands c WHERE"
                                        + " c.workflow_id=q.workflow_id AND kind='SIGNAL' AND state IN"
                                        + " ('PENDING','DEFERRED','CLAIMED','PARKED')) AS open_rows, (SELECT count(*) FROM"
                                        + " workflow_dedup d WHERE d.workflow_id=q.workflow_id AND kind='signal' AND"
                                        + " key='k-b') AS b_keys FROM workflow_command_queues q WHERE q.workflow_id=$1")
                        .execute(Tuple.of(id.value()))
                        .map(rows -> rows.iterator().next().toJson())));
                // The counterless COUNT variant must fail this assertion; an accepted 40001 is never detection itself.
                assertThat(finalRows.getInteger("open_signals"))
                        .as("run %s final open_signals", run)
                        .isEqualTo(2);
                assertThat(finalRows.getLong("open_rows"))
                        .as("run %s open rows", run)
                        .isEqualTo(2);
                assertThat(finalRows.getLong("b_keys"))
                        .as("run %s B's dedup key", run)
                        .isZero();
                assertThat(bFailure).as("run %s B records nothing", run).isNotNull();
                assertThat(bFailure instanceof WorkflowSignalLimitException || serializationFailure(bFailure))
                        .as("run %s B fails with the cap or SQLSTATE 40001: %s", run, bFailure)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("payload and explicit-carrier bounds refuse before a held execution lock, after duplicate lookup")
    void oversizedSignalPayloadOrCarrierIsRefusedBeforeTheLock() {
        try (SignalFixture f = order()) {
            Node node = f.node("bounded")
                    .carryContext()
                    .recordingConfig(recording(100, 1024))
                    .build();
            WorkflowInstanceId id = f.waiting(node, "bounds");
            f.record(node, id, "paid", signal("duplicate"), "k-dup");
            List<CommandRow> before = f.signals(id);
            long history = f.fixture.rowsOf("workflow_history", id);
            int open = f.openSignals(id);
            // Given another session holds the execution lock throughout every call; no lock_timeout is installed
            try (ExecutorFixture.HeldLock held = f.fixture.holdExecutionLock(id)) {
                assertThat(f.fixture.holdersOfX(id)).hasSize(1);
                // When payload size, nesting depth and explicit-carrier size each exceed the recording bounds
                assertThat(failureOf(
                                f.recordAsync(node, id, "paid", Payloads.ofSize(2048), "k1"), Duration.ofSeconds(2)))
                        .isInstanceOf(WorkflowInvalidPayloadException.class);
                assertThat(failureOf(f.recordAsync(node, id, "paid", Payloads.nested(65), "k2"), Duration.ofSeconds(2)))
                        .isInstanceOf(WorkflowInvalidPayloadException.class);
                assertThat(failureOf(
                                f.caller(() -> node.pool.withTransaction(tx -> node.engine.signal(
                                        id,
                                        "paid",
                                        signal("ok"),
                                        "k3",
                                        null,
                                        null,
                                        ExplicitCarriers.ofSize(2048),
                                        tx))),
                                Duration.ofSeconds(2)))
                        .isInstanceOf(WorkflowInvalidPayloadException.class);
                // And a committed duplicate has the same oversized payload
                await(f.recordAsync(node, id, "paid", Payloads.ofSize(2048), "k-dup"), Duration.ofSeconds(2));
                // Then each refusal completed while the lock was still held, and no database side effect escaped
                assertThat(f.fixture.holdersOfX(id)).hasSize(1);
                assertThat(f.fixture.ungrantedAdvisoryLocks()).isZero();
                assertThat(f.signals(id)).isEqualTo(before);
                assertThat(f.fixture.rowsOf("workflow_history", id)).isEqualTo(history);
                assertThat(f.openSignals(id)).isEqualTo(open);
                for (String key : List.of("k1", "k2", "k3")) {
                    assertThat(f.dedup(id, key)).as(key).isZero();
                }
            }
        }
    }

    static SignalFixture order() {
        return new SignalFixture(db.toPoolConfig(), new OrderSignalFixture(), List.of("paid", "shipped", "note"));
    }

    static WorkflowRecordingConfig recording(int cap, int maxPayloadBytes) {
        return new WorkflowRecordingConfig(5000L, maxPayloadBytes, cap);
    }

    static Signal signal(String value) {
        return new Signal(false, "", value, false, false);
    }

    static boolean serializationFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessException data && "40001".equals(data.sqlState())) return true;
            if (cause instanceof io.vertx.sqlclient.DatabaseException data && "40001".equals(data.getSqlState()))
                return true;
        }
        return false;
    }

    record Signal(boolean fail, String card, String value, boolean spin, boolean cancelOrder) {}

    record Start(String ref) {}

    record State(String ref, List<String> applied, boolean spin, boolean cancelOrder) {
        State add(String name, Signal payload) {
            if (payload.fail()) throw new IllegalArgumentException("updater echoed " + payload.card());
            List<String> next = new ArrayList<>(applied);
            next.add(name + ":" + payload.value());
            return new State(ref, next, payload.spin(), payload.cancelOrder());
        }
    }

    interface SignalContract {
        @WorkflowSignal("paid")
        Future<Void> paid(WorkflowInstanceId id, Signal payload, @SignalDedupKey String key);

        @WorkflowSignal("shipped")
        Future<Void> shipped(WorkflowInstanceId id, Signal payload, @SignalDedupKey String key);

        @WorkflowSignal("note")
        Future<Void> note(WorkflowInstanceId id, Signal payload, @SignalDedupKey String key);

        @WorkflowSignal("a")
        Future<Void> a(WorkflowInstanceId id, Signal payload, @SignalDedupKey String key);

        @WorkflowSignal("b")
        Future<Void> b(WorkflowInstanceId id, Signal payload, @SignalDedupKey String key);

        @WorkflowSignal("ack")
        Future<Void> ack(WorkflowInstanceId id, Signal payload, @SignalDedupKey String key);

        @WorkflowSignal("finish")
        Future<Void> finish(WorkflowInstanceId id, Signal payload, @SignalDedupKey String key);

        @WorkflowSignal("approve")
        Future<Void> approve(WorkflowInstanceId id, Signal payload, @SignalDedupKey String key);

        @WorkflowSignal("tick")
        Future<Void> tick(WorkflowInstanceId id, Signal payload, @SignalDedupKey String key);

        @WorkflowSignal("heavy")
        Future<Void> heavy(WorkflowInstanceId id, Signal payload, @SignalDedupKey String key);

        @WorkflowSignal("done")
        Future<Void> done(WorkflowInstanceId id, Signal payload, @SignalDedupKey String key);
    }

    /** A code-first definition with no runtime or production replacements. */
    static class Definition implements WorkflowDefinition<State, SignalContract> {
        private final String id;
        private final Consumer<WorkflowBuilder<State>> body;

        Definition(String id, Consumer<WorkflowBuilder<State>> body) {
            this.id = id;
            this.body = body;
        }

        @Override
        public Class<SignalContract> contract() {
            return SignalContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
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
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.ref(), List.of(), false, false));
            body.accept(wf);
        }
    }

    static void waitFor(WorkflowBuilder<State> wf, String step, String name, String next) {
        wf.waitForSignal(step, name, Signal.class)
                .onSignal((state, payload) -> state.add(name, payload))
                .toStepOnSignal(next)
                .build();
    }

    static final class OrderSignalFixture extends Definition {
        OrderSignalFixture() {
            this("recorded-order");
        }

        OrderSignalFixture(String id) {
            super(id, wf -> {
                wf.initialStep("paid");
                waitFor(wf, "paid", "paid", "paid-intent");
                wf.dispatch("paid-intent", "signal.service", state -> Map.of("ref", state.ref()), "after-paid")
                        .decide("after-paid", state -> state.ref().startsWith("end-") ? "done" : "shipped");
                waitFor(wf, "shipped", "shipped", "after-shipped");
                wf.decide("after-shipped", state -> state.ref().startsWith("notes-") ? "note" : "done");
                waitFor(wf, "note", "note", "note");
                wf.complete("done");
            });
        }
    }

    static final class SequenceFixture extends Definition {
        SequenceFixture() {
            super("recorded-sequence", wf -> {
                wf.initialStep("a");
                waitFor(wf, "a", "a", "b");
                waitFor(wf, "b", "b", "done");
                wf.complete("done");
            });
        }
    }

    static final class RaceForkFixture extends Definition {
        RaceForkFixture() {
            super("recorded-race", wf -> {
                wf.initialStep("fork")
                        .fork("fork")
                        .branch("b1", "winner", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .branch("b2", "ack", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .join("join");
                wf.complete("winner");
                waitFor(wf, "ack", "ack", "loser-done");
                wf.complete("loser-done");
                wf.join("join")
                        .firstSuccess((state, results) -> state)
                        .toStep("after-race")
                        .onFailure("done")
                        .endJoin();
                waitFor(wf, "after-race", "finish", "done");
                wf.complete("done");
            });
        }
    }

    record History(long sequence, String type, Long commandId, JsonObject payload, String transaction) {
        String text() {
            return type + " " + payload.encode();
        }

        String reason() {
            for (String key : List.of("reason", "lastError", "message")) {
                String value = payload.getString(key);
                if (value != null && value.startsWith("DETERMINISTIC_FAILURE")) return value;
                if (value != null && value.startsWith("STEP_LIMIT")) return value;
            }
            return payload.getString("reasonCode", "") + ": " + payload.getString("reason", "");
        }
    }

    /** Shared SQL observations remain in test scope; each fixture owns its pools, engines, hooks and Vert.x. */
    static class SignalFixture implements AutoCloseable {
        final ExecutorFixture fixture;
        final Definition definition;
        final DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        final PgWorkflowDefinitionRegistryStore store;
        final RecordingIntents intents = new RecordingIntents();
        final RecordingObserver observer = new RecordingObserver();

        SignalFixture(DbPoolConfig db, Definition definition, List<String> names) {
            this(db, definition, names, new JsonObject());
        }

        SignalFixture(DbPoolConfig db, Definition definition, List<String> names, JsonObject branches) {
            this.fixture = ExecutorFixture.open(db);
            this.definition = definition;
            try {
                store = new PgWorkflowDefinitionRegistryStore(
                        fixture.observer(), new PgDbExceptionMapper(), Optional.empty());
                seed(definition, names, branches);
                await(fixture.observer()
                        .query("CREATE TABLE IF NOT EXISTS signal_it_business (label TEXT NOT NULL)")
                        .execute());
                await(fixture.observer()
                        .query("CREATE TABLE IF NOT EXISTS signal_it_outbox (workflow_id UUID NOT NULL, step_id TEXT"
                                + " NOT NULL, correlation TEXT)")
                        .execute());
                await(fixture.observer()
                        .query("TRUNCATE signal_it_business, signal_it_outbox")
                        .execute());
            } catch (RuntimeException | AssertionError e) {
                fixture.close();
                throw e;
            }
        }

        void seed(Definition code, List<String> names, JsonObject branches) {
            registry.register(code);
            RuntimeWorkflow runtime = registry.allRegistered().stream()
                    .filter(candidate -> candidate.plan().definitionId().equals(code.definitionId()))
                    .findFirst()
                    .orElseThrow();
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
                                    .put("instance", new JsonArray(names))
                                    .put("branches", branches),
                            null),
                    true));
        }

        ExecutorFixture.NodeSpec node(String name) {
            return fixture.node(name)
                    .pollIntervalMs(100)
                    .withRegistry(registry)
                    .definitionStore(store)
                    .recorders(intents)
                    .observers(observer);
        }

        List<Node> three() {
            return List.of(
                    node("one").build(), node("two").build(), node("three").build());
        }

        StartCommand start(String ref) {
            return start(definition, ref);
        }

        StartCommand start(Definition code, String ref) {
            RuntimeWorkflow runtime = registry.allRegistered().stream()
                    .filter(candidate -> candidate.plan().definitionId().equals(code.definitionId()))
                    .findFirst()
                    .orElseThrow();
            return new StartCommand(
                    code.definitionId(), runtime.contractName(), new Start(ref), ref, null, null, null, null, null);
        }

        WorkflowInstanceId waiting(Node node, String ref) {
            WorkflowInstanceId id =
                    await(caller(() -> node.pool.withTransaction(tx -> node.engine.start(start(ref), tx))));
            idle(node, id);
            return id;
        }

        void idle(Node node, WorkflowInstanceId id) {
            node.startExecutor();
            try {
                node.awaitIdle(id);
            } finally {
                node.stopExecutor();
            }
        }

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

        Future<Void> recordAsync(Node node, WorkflowInstanceId id, String name, Object payload, String key) {
            return caller(() -> node.pool.withTransaction(tx -> node.engine.signal(id, name, payload, key, tx)));
        }

        void record(Node node, WorkflowInstanceId id, String name, Object payload, String key) {
            await(recordAsync(node, id, name, payload, key));
        }

        void recordSignals(Node node, WorkflowInstanceId id, String name, int count) {
            await(caller(() -> node.pool.withTransaction(tx -> {
                Future<Void> chain = Future.succeededFuture();
                for (int i = 0; i < count; i++) {
                    String key = name + "-" + i;
                    chain = chain.compose(v -> node.engine.signal(id, name, RecordedSignalIT.signal(key), key, tx));
                }
                return chain;
            })));
        }

        Throwable refusalAndCommitOtherWork(Node node, WorkflowInstanceId id, String name, String key) {
            return await(caller(() -> node.pool.withTransaction(tx -> node.engine
                    .signal(id, name, RecordedSignalIT.signal("refused"), key, tx)
                    .<Throwable>map(v -> null)
                    .recover(Future::succeededFuture)
                    .compose(refusal -> tx.preparedQuery("INSERT INTO signal_it_business(label) VALUES ($1)")
                            .execute(Tuple.of(key))
                            .map(refusal)))));
        }

        long businessRows(String label) {
            return await(fixture.observer()
                            .preparedQuery("SELECT count(*) FROM signal_it_business WHERE label=$1")
                            .execute(Tuple.of(label)))
                    .iterator()
                    .next()
                    .getLong(0);
        }

        boolean raceRecord(Node node, WorkflowInstanceId id, String key, CyclicBarrier ready) throws Exception {
            ready.await(5, TimeUnit.SECONDS);
            return await(recordAsync(node, id, "note", RecordedSignalIT.signal(key), key)
                    .map(true)
                    .recover(failure -> {
                        assertThat(failure).isInstanceOf(WorkflowSignalLimitException.class);
                        return Future.succeededFuture(false);
                    }));
        }

        List<CommandRow> signals(WorkflowInstanceId id) {
            return fixture.commandsOf(id).stream()
                    .filter(row -> row.kind().equals("SIGNAL"))
                    .toList();
        }

        CommandRow signal(WorkflowInstanceId id, String key) {
            long commandId = row(
                            "SELECT id FROM workflow_commands WHERE workflow_id=$1 AND dedup_key=$2",
                            Tuple.of(id.value(), key))
                    .getLong("id");
            return signals(id).stream()
                    .filter(row -> row.id() == commandId)
                    .findFirst()
                    .orElseThrow();
        }

        long dedup(WorkflowInstanceId id, String key) {
            return row(
                            "SELECT count(*) AS n FROM workflow_dedup WHERE workflow_id=$1 AND kind='signal' AND"
                                    + " key=$2",
                            Tuple.of(id.value(), key))
                    .getLong("n");
        }

        int openSignals(WorkflowInstanceId id) {
            return row("SELECT open_signals FROM workflow_command_queues WHERE workflow_id=$1", id)
                    .getInteger("open_signals");
        }

        JsonObject snapshot(WorkflowInstanceId id) {
            return row(
                    "SELECT status, current_step_id, wait_type, wait_key, state_json, version FROM workflow_instances"
                            + " WHERE id=$1",
                    id);
        }

        JsonObject row(String sql, WorkflowInstanceId id) {
            return row(sql, Tuple.of(id.value()));
        }

        JsonObject row(String sql, Tuple tuple) {
            return await(fixture.observer().preparedQuery(sql).execute(tuple))
                    .iterator()
                    .next()
                    .toJson();
        }

        long scalar(String sql, WorkflowInstanceId id) {
            return await(fixture.observer().preparedQuery(sql).execute(Tuple.of(id.value())))
                    .iterator()
                    .next()
                    .getLong(0);
        }

        List<History> history(WorkflowInstanceId id) {
            List<History> result = new ArrayList<>();
            for (Row row : await(fixture.observer()
                    .preparedQuery(
                            "SELECT sequence, entry_type, command_id, payload_json, xmin::text AS transaction_id FROM"
                                    + " workflow_history WHERE workflow_id=$1 ORDER BY sequence")
                    .execute(Tuple.of(id.value())))) {
                JsonObject payload = row.getJsonObject("payload_json");
                Long command = row.getLong("command_id");
                if (command == null) command = payload.getLong("commandId");
                result.add(new History(
                        row.getLong("sequence"),
                        row.getString("entry_type"),
                        command,
                        payload,
                        row.getString("transaction_id")));
            }
            return result;
        }

        History entry(WorkflowInstanceId id, String type, long commandId) {
            return history(id).stream()
                    .filter(h ->
                            h.type().equals(type) && Long.valueOf(commandId).equals(h.commandId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no " + type + " history for command " + commandId + ": " + history(id)));
        }

        List<History> signalHistory(WorkflowInstanceId id) {
            return history(id).stream()
                    .filter(h -> h.type().equals("SIGNAL_RECEIVED"))
                    .toList();
        }

        List<Long> commandHistory(WorkflowInstanceId id, String type) {
            return history(id).stream()
                    .filter(h -> h.type().equals(type))
                    .map(History::commandId)
                    .toList();
        }

        void assertSignalsInOrder(WorkflowInstanceId id, String... names) {
            assertThat(signals(id)).extracting(CommandRow::state).containsOnly("COMPLETE");
            assertThat(signalHistory(id)).hasSize(names.length);
            assertThat(signalHistory(id).stream()
                            .map(h -> h.payload().getString("signalName"))
                            .toList())
                    .containsExactly(names);
            assertThat(signalHistory(id).stream().map(History::commandId).toList())
                    .containsExactlyElementsOf(
                            signals(id).stream().map(CommandRow::id).toList());
        }

        List<String> intentCorrelations(WorkflowInstanceId id) {
            List<String> result = new ArrayList<>();
            for (Row row : await(fixture.observer()
                    .preparedQuery("SELECT correlation FROM signal_it_outbox WHERE workflow_id=$1 ORDER BY step_id")
                    .execute(Tuple.of(id.value())))) result.add(row.getString(0));
            return result;
        }

        void assertAbsent(WorkflowInstanceId id) {
            assertThat(fixture.instanceOf(id)).isNull();
            for (String table : List.of("workflow_commands", "workflow_dedup", "workflow_command_queues"))
                assertThat(fixture.rowsOf(table, id)).as(table).isZero();
        }

        UnaryOperator<WorkflowTransactionRunner<SqlClient>> failSegmentsFor(
                WorkflowInstanceId id, AtomicBoolean armed) {
            return real -> new WorkflowTransactionRunner<>() {
                @Override
                public <R> Future<R> inTransaction(
                        @Nullable IsolationLevel level, java.util.function.Function<SqlClient, Future<R>> work) {
                    return real.inTransaction(level, work);
                }

                @Override
                public <R> Future<R> inTransaction(
                        @Nullable IsolationLevel level,
                        Duration deadline,
                        java.util.function.Function<SqlClient, Future<R>> work) {
                    return real.inTransaction(level, deadline, tx -> tx.preparedQuery(
                                    "SELECT count(*) FROM workflow_commands WHERE workflow_id=$1 AND state='CLAIMED'")
                            .execute(Tuple.of(id.value()))
                            .compose(rows -> {
                                boolean fail =
                                        rows.iterator().next().getLong(0) > 0 && armed.compareAndSet(true, false);
                                return work.apply(tx)
                                        .compose(value -> fail
                                                ? Future.failedFuture(FailingTransactions.connectionFailure()
                                                        .get())
                                                : Future.succeededFuture(value));
                            }));
                }
            };
        }

        @Override
        public void close() {
            intents.failOnce.set(false);
            fixture.close();
        }

        final class RecordingIntents implements WorkflowSideEffectRecorder<SqlClient> {
            final AtomicBoolean failOnce = new AtomicBoolean();

            void failOnceAfterOutboxWrite() {
                failOnce.set(true);
            }

            @Override
            public IntentKind kind() {
                return IntentKind.SERVICE;
            }

            @Override
            public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                String correlation = fixture.holder()
                        .current(CorrelationCtx.class)
                        .map(CorrelationCtx::correlationId)
                        .orElse(null);
                return tx.preparedQuery(
                                "INSERT INTO signal_it_outbox(workflow_id, step_id, correlation) VALUES ($1,$2,$3)")
                        .execute(Tuple.of(
                                intent.correlation().workflowId().value(),
                                intent.correlation().stepId(),
                                correlation))
                        .compose(rows -> failOnce.compareAndSet(true, false)
                                ? Future.failedFuture(
                                        FailingTransactions.connectionFailure().get())
                                : Future.succeededFuture(RecorderResult.empty()));
            }
        }
    }

    static final class RecordingObserver implements WorkflowExecutorObserver {
        final List<WorkflowExecutorEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(WorkflowExecutorEvent event) {
            events.add(event);
        }
    }

    static final class ExplicitCarriers {
        static DurableMetadata withSnapshot(String correlation) {
            return DurableMetadata.of("correlation", new JsonObject().put("correlationId", correlation))
                    .merge(
                            DurableMetadata.of("identity-snapshot", new JsonObject().put("subject", "user-77")),
                            dev.vertique.core.context.DurableMetadata.MergePolicy.CALLER_WINS);
        }

        static DurableMetadata ofSize(int bytes) {
            return DurableMetadata.of("correlation", new JsonObject().put("correlationId", "x".repeat(bytes)));
        }
    }

    static final class Payloads {
        static JsonObject ofSize(int bytes) {
            return new JsonObject().put("value", "x".repeat(bytes));
        }

        static JsonObject nested(int depth) {
            JsonObject value = new JsonObject().put("leaf", true);
            for (int i = 0; i < depth; i++) value = new JsonObject().put("child", value);
            return value;
        }
    }

    static final class TwoSessions implements AutoCloseable {
        final SqlConnection a;
        final SqlConnection b;
        private boolean aOpen;
        private boolean bOpen;
        private Transaction aTx;
        private Transaction bTx;

        private TwoSessions(Node node) {
            a = await(node.pool.getConnection());
            SqlConnection second = null;
            try {
                second = await(node.pool.getConnection());
                b = second;
                aTx = await(a.begin());
                aOpen = true;
                await(a.query("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ").execute());
                bTx = await(b.begin());
                bOpen = true;
                await(b.query("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ").execute());
            } catch (RuntimeException | AssertionError e) {
                try {
                    if (aOpen) await(aTx.rollback());
                } finally {
                    if (second != null) await(second.close());
                    await(a.close());
                }
                throw e;
            }
        }

        static TwoSessions repeatableRead(Node node) {
            return new TwoSessions(node);
        }

        void commitA() {
            await(aTx.commit());
            aOpen = false;
        }

        Throwable tryB(Supplier<Future<Void>> action) {
            try {
                await(action.get());
                await(bTx.commit());
                bOpen = false;
                return null;
            } catch (AssertionError failure) {
                if (failure.getCause() == null || failure.getCause() instanceof java.util.concurrent.TimeoutException)
                    throw failure;
                await(bTx.rollback());
                bOpen = false;
                return failure.getCause();
            }
        }

        @Override
        public void close() {
            try {
                try {
                    if (aOpen) await(aTx.rollback());
                } finally {
                    if (bOpen) await(bTx.rollback());
                }
            } finally {
                try {
                    await(a.close());
                } finally {
                    await(b.close());
                }
            }
        }
    }
}
