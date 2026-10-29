// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.recording;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.CommandSettled;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.History;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Signal;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.State;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies parking, command ownership, savepoint rollback, bounded parked work and committed re-queue phases. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ParkedSignalIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_parked_signal_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("unawaited signals park and apply in sequence under their own commands when the wait is reached")
    void unawaitedSignalIsParkedAndAppliedWhenItsWaitIsReached() {
        try (SignalFixture f = shipping(2)) {
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, "shipping");
            // Given two shipped signals, in queue order, accepted only by the later ship wait
            f.record(node, id, "shipped", signal("first"), "first");
            f.record(node, id, "shipped", signal("second"), "second");
            f.idle(node, id);
            List<CommandRow> parked = f.signals(id);
            assertThat(parked).extracting(CommandRow::state).containsExactly("PARKED", "PARKED");
            assertThat(parked).extracting(CommandRow::attempts).containsOnly(0);
            assertThat(f.snapshot(id).getString("current_step_id")).isEqualTo("approve");
            assertThat(f.observer.events)
                    .anyMatch(event -> event instanceof CommandSettled settled
                            && settled.kind().equals("SIGNAL")
                            && settled.outcome().equals("PARKED"));
            // When approve reaches ship
            f.record(node, id, "approve", signal("approve"), "approve");
            long approve = f.signal(id, "approve").id();
            f.idle(node, id);
            // Then both shipped commands complete in their original order, with their own command identities
            assertThat(f.signals(id)).extracting(CommandRow::state).containsOnly("COMPLETE");
            List<History> applied = f.history(id).stream()
                    .filter(h -> h.type().equals("SIGNAL_APPLIED"))
                    .toList();
            assertThat(applied)
                    .extracting(History::commandId)
                    .containsExactly(parked.get(0).id(), parked.get(1).id());
            assertThat(applied).allSatisfy(h -> assertThat(h.commandId()).isNotEqualTo(approve));
            assertThat(state(f, id).getJsonArray("applied").getList())
                    .containsExactly("approve:approve", "shipped:first", "shipped:second");
            assertThat(f.snapshot(id).getString("status")).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("a failing parked signal is rejected alone and its valid sibling still completes the segment")
    void failingParkedSignalIsRejectedAloneAndTheSegmentCompletes() {
        try (SignalFixture f = shipping(1)) {
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, "failing-parked");
            // Given three same-name shipped signals parked at ship: the first throws in its updater, the second is
            // applied and writes its shipping intent before a later decision fails, the third is valid
            f.record(node, id, "shipped", new Signal(true, "4111-secret", "bad", false, false), "bad");
            f.record(node, id, "shipped", new Signal(false, "", "poisoned", true, false), "poisoned");
            f.record(node, id, "shipped", signal("good"), "good");
            f.idle(node, id);
            assertThat(f.signal(id, "bad").state()).isEqualTo("PARKED");
            assertThat(f.signal(id, "poisoned").state()).isEqualTo("PARKED");
            assertThat(f.signal(id, "good").state()).isEqualTo("PARKED");
            // When approve is recorded and executed
            f.record(node, id, "approve", signal("approve"), "approve");
            f.idle(node, id);
            // Then only the failing commands are rejected, with no state change from them
            CommandRow approve = f.signal(id, "approve");
            CommandRow bad = f.signal(id, "bad");
            CommandRow poisoned = f.signal(id, "poisoned");
            CommandRow good = f.signal(id, "good");
            assertThat(approve.state()).isEqualTo("COMPLETE");
            assertThat(bad.state()).isEqualTo("REJECTED");
            assertThat(bad.lastError()).doesNotContain("4111-secret", "updater echoed");
            assertThat(poisoned.state()).isEqualTo("REJECTED");
            assertThat(poisoned.lastError()).doesNotContain("poisoned shipment");
            assertThat(good.state()).isEqualTo("COMPLETE");
            assertThat(state(f, id).getJsonArray("applied").getList())
                    .containsExactly("approve:approve", "shipped:good");
            // And nothing the poisoned signal wrote under its savepoint before it failed survived, the good one's did
            assertThat(f.scalar("SELECT count(*) FROM signal_it_outbox WHERE workflow_id=$1", id))
                    .isEqualTo(1);
            assertThat(f.history(id))
                    .filteredOn(h -> Long.valueOf(poisoned.id()).equals(h.commandId()))
                    .extracting(History::type)
                    .containsExactly("SIGNAL_PARKED", "COMMAND_REJECTED");
            // And all of them settled inside approve's own segment, not in segments of their own
            String approveTransaction =
                    f.entry(id, "COMMAND_COMPLETED", approve.id()).transaction();
            assertThat(f.entry(id, "COMMAND_REJECTED", bad.id()).transaction()).isEqualTo(approveTransaction);
            assertThat(f.entry(id, "COMMAND_REJECTED", poisoned.id()).transaction())
                    .isEqualTo(approveTransaction);
            assertThat(f.entry(id, "SIGNAL_APPLIED", good.id()).transaction()).isEqualTo(approveTransaction);
            assertThat(f.snapshot(id).getString("status")).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("a transient failure after a parked signal's outbox write rolls back the entire segment")
    void transientFailureInAParkedSignalRollsBackTheSegment() {
        try (SignalFixture f = shipping(1)) {
            Node node = f.node("engine")
                    .singleSegments()
                    .transientBackoffMs(30_000, 30_000)
                    .build();
            WorkflowInstanceId id = f.waiting(node, "transient");
            f.record(node, id, "shipped", signal("shipped"), "shipped");
            f.idle(node, id);
            assertThat(f.signal(id, "shipped").state()).isEqualTo("PARKED");
            JsonObject before = f.snapshot(id);
            // Given a one-shot connection failure strictly after the parked signal's transactional outbox write
            f.intents.failOnceAfterOutboxWrite();
            f.record(node, id, "approve", signal("approve"), "approve");
            List<History> historyBefore = f.history(id);
            // When exactly the first approve segment runs and its failure disposition finishes
            node.runOneSegment();
            // Then no part of that segment committed, and its retry restored the claim's attempt
            assertThat(f.snapshot(id)).isEqualTo(before);
            assertThat(f.history(id)).isEqualTo(historyBefore);
            assertThat(f.scalar("SELECT count(*) FROM signal_it_outbox WHERE workflow_id=$1", id))
                    .isZero();
            assertThat(f.signal(id, "shipped").state()).isEqualTo("PARKED");
            CommandRow retry = f.signal(id, "approve");
            assertThat(retry.state()).isEqualTo("PENDING");
            assertThat(retry.transientCount()).isEqualTo(1);
            assertThat(retry.attempts()).isZero();
            // When backoff expires and the same commands retry
            f.fixture.fastForward(retry.id());
            f.idle(node, id);
            // Then each command and outbox intent exists once
            assertThat(f.signals(id)).extracting(CommandRow::state).containsOnly("COMPLETE");
            assertThat(f.scalar("SELECT count(*) FROM signal_it_outbox WHERE workflow_id=$1", id))
                    .isEqualTo(1);
            assertThat(f.history(id))
                    .filteredOn(h -> h.type().equals("SIGNAL_APPLIED"))
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("an instance that completes without reaching a later wait rejects its parked signals")
    void parkedSignalIsRejectedWhenTheInstanceCompletes() {
        try (SignalFixture f = shipping(1)) {
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, "cancel");
            f.record(node, id, "shipped", signal("shipped"), "shipped");
            f.idle(node, id);
            assertThat(f.signal(id, "shipped").state()).isEqualTo("PARKED");
            // When approve chooses cancelOrder and ends without reaching ship
            f.record(node, id, "approve", new Signal(false, "", "cancelOrder", false, true), "approve");
            f.idle(node, id);
            // Then the ended instance rejects the parked command with its own history identity and reason
            assertThat(f.snapshot(id).getString("status")).isEqualTo("COMPLETED");
            CommandRow shipped = f.signal(id, "shipped");
            assertThat(shipped.state()).isEqualTo("REJECTED");
            assertThat(f.entry(id, "COMMAND_REJECTED", shipped.id()).text()).containsIgnoringCase("instance ended");
        }
    }

    @Test
    @DisplayName("parked ticks beyond half the step cap requeue with their sequences and attempts preserved")
    void parkedSignalsBeyondHalfTheStepCapAreRequeuedAndTheInstanceNeverFails() {
        LoopFixture definition = new LoopFixture();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), definition, List.of("approve", "tick"))) {
            Node node = f.node("engine")
                    .singleSegments()
                    .maxStepsPerSegment(1000)
                    .recordingConfig(recording(200, 262144))
                    .build();
            // Given fresh A and B with 60 and 120 ticks parked before approve
            for (int count : List.of(60, 120)) {
                WorkflowInstanceId id = f.waiting(node, "ticks-" + count);
                f.recordSignals(node, id, "tick", count);
                f.idle(node, id);
                List<CommandRow> original = f.signals(id);
                assertThat(original)
                        .hasSize(count)
                        .extracting(CommandRow::state)
                        .containsOnly("PARKED");
                assertThat(original).extracting(CommandRow::attempts).containsOnly(0);
                List<Long> sequences = original.stream().map(CommandRow::seq).toList();
                // When exactly approve's segment runs and commits
                f.record(node, id, "approve", signal("approve"), "approve");
                node.runOneSegment();
                CommandRow approve = f.signal(id, "approve");
                assertThat(approve.state()).isEqualTo("COMPLETE");
                List<CommandRow> afterOne = ticks(f, id);
                int prefix = (int) afterOne.stream()
                        .takeWhile(row -> row.state().equals("COMPLETE"))
                        .count();
                // Then applied ticks are a proper sequence prefix and the last started no later than step 500
                assertThat(prefix)
                        .as("committed tick prefix for %s", count)
                        .isPositive()
                        .isLessThan(count);
                assertThat(LoopFixture.stepsBeforeTick(prefix - 1))
                        .as("last parked tick start for %s", count)
                        .isLessThanOrEqualTo(500);
                assertThat(afterOne).extracting(CommandRow::seq).containsExactlyElementsOf(sequences);
                assertThat(afterOne.subList(0, prefix))
                        .extracting(CommandRow::state)
                        .containsOnly("COMPLETE");
                assertThat(afterOne.subList(prefix, count))
                        .extracting(CommandRow::state)
                        .containsOnly("PENDING");
                assertThat(afterOne).extracting(CommandRow::attempts).containsOnly(0);
                assertThat(f.signals(id))
                        .noneMatch(row -> List.of("REJECTED", "DEAD", "PARKED").contains(row.state()));
                assertThat(state(f, id).getJsonArray("applied").size()).isEqualTo(prefix + 1);
                List<String> expectedApplied = new ArrayList<>();
                expectedApplied.add("approve:approve");
                for (int tick = 0; tick < count; tick++) expectedApplied.add("tick:tick-" + tick);
                assertThat(state(f, id).getJsonArray("applied").getList())
                        .containsExactlyElementsOf(expectedApplied.subList(0, prefix + 1));
                String approveTransaction =
                        f.entry(id, "COMMAND_COMPLETED", approve.id()).transaction();
                for (CommandRow tick : afterOne.subList(0, prefix)) {
                    assertThat(f.entry(id, "SIGNAL_APPLIED", tick.id()).transaction())
                            .isEqualTo(approveTransaction);
                }
                // When requeued ticks execute as their own commands
                f.idle(node, id);
                List<CommandRow> complete = ticks(f, id);
                // Then all ticks complete in sequence; parked applications spent zero attempts, fresh segments one
                assertThat(complete).extracting(CommandRow::seq).containsExactlyElementsOf(sequences);
                assertThat(complete).extracting(CommandRow::state).containsOnly("COMPLETE");
                assertThat(complete.subList(0, prefix))
                        .extracting(CommandRow::attempts)
                        .containsOnly(0);
                assertThat(complete.subList(prefix, count))
                        .extracting(CommandRow::attempts)
                        .containsOnly(1);
                List<Long> tickApplications = f.history(id).stream()
                        .filter(h ->
                                h.type().equals("SIGNAL_APPLIED") || h.type().equals("SIGNAL_RECEIVED"))
                        .filter(h -> complete.stream()
                                .anyMatch(tick -> Long.valueOf(tick.id()).equals(h.commandId())))
                        .map(History::commandId)
                        .distinct()
                        .toList();
                assertThat(tickApplications)
                        .containsExactlyElementsOf(
                                complete.stream().map(CommandRow::id).toList());
                List<String> ownTransactions = new ArrayList<>();
                for (CommandRow tick : complete.subList(prefix, count)) {
                    History own = f.entry(id, "COMMAND_COMPLETED", tick.id());
                    assertThat(own.transaction()).isNotEqualTo(approveTransaction);
                    ownTransactions.add(own.transaction());
                    assertThat(f.history(id))
                            .filteredOn(h -> h.type().equals("COMMAND_COMPLETED")
                                    && h.transaction().equals(own.transaction()))
                            .hasSize(1);
                }
                assertThat(ownTransactions).doesNotHaveDuplicates();
                assertThat(state(f, id).getJsonArray("applied").getList()).containsExactlyElementsOf(expectedApplied);
                assertThat(f.snapshot(id).getString("status")).isEqualTo("WAITING");
                assertThat(f.snapshot(id).getString("current_step_id")).isEqualTo("loop");
                assertThat(f.signals(id))
                        .noneMatch(row -> List.of("REJECTED", "DEAD", "FAILED").contains(row.state()));
                assertThat(f.fixture.historyOf(id)).doesNotContain("FAILED");
            }
        }
    }

    @Test
    @DisplayName("a parked heavy signal that overruns after earlier work requeues and completes in its own segment")
    void parkedSignalOverrunningAfterEarlierWorkIsRequeuedNotRejected() {
        HeavyFixture definition = new HeavyFixture();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), definition, List.of("approve", "heavy", "done"))) {
            Node node =
                    f.node("engine").singleSegments().maxStepsPerSegment(1000).build();
            WorkflowInstanceId id = f.waiting(node, "heavy-run");
            f.record(node, id, "heavy", signal("heavy"), "heavy");
            f.idle(node, id);
            CommandRow original = f.signal(id, "heavy");
            assertThat(original.state()).isEqualTo("PARKED");
            assertThat(original.attempts()).isZero();
            // When approve executes 400 nodes and attempts the 800-node parked operation in that same segment
            f.record(node, id, "approve", signal("approve"), "approve");
            node.runOneSegment();
            // Then the overrun rolled back only heavy's savepoint; the committed wait is ship and heavy is pending
            CommandRow approve = f.signal(id, "approve");
            assertThat(approve.state()).isEqualTo("COMPLETE");
            CommandRow requeued = f.signal(id, "heavy");
            assertThat(requeued.state()).isEqualTo("PENDING");
            assertThat(requeued.id()).isEqualTo(original.id());
            assertThat(requeued.seq()).isEqualTo(original.seq());
            assertThat(requeued.attempts()).isZero();
            assertThat(definition.approveNodes.get()).isEqualTo(399);
            assertThat(definition.heavyStarts.get()).isEqualTo(1);
            assertThat(definition.heavyNodes.get()).isPositive().isLessThan(800);
            assertThat(f.snapshot(id).getString("status")).isEqualTo("WAITING");
            assertThat(f.snapshot(id).getString("current_step_id")).isEqualTo("ship");
            assertThat(state(f, id).getJsonArray("applied").getList()).containsExactly("approve:approve");
            String approveTransaction =
                    f.entry(id, "COMMAND_COMPLETED", approve.id()).transaction();
            assertThat(f.history(id))
                    .noneMatch(h -> h.transaction().equals(approveTransaction)
                            && Long.valueOf(original.id()).equals(h.commandId()));
            // When heavy gets its own segment with the full step budget
            f.idle(node, id);
            CommandRow completed = f.signal(id, "heavy");
            assertThat(completed.state()).isEqualTo("COMPLETE");
            assertThat(completed.attempts()).isEqualTo(1);
            assertThat(f.entry(id, "COMMAND_COMPLETED", completed.id()).transaction())
                    .isNotEqualTo(approveTransaction);
            assertThat(definition.heavyStarts.get()).isEqualTo(2);
            assertThat(f.snapshot(id).getString("status")).isEqualTo("WAITING");
            assertThat(f.snapshot(id).getString("current_step_id")).isEqualTo("done");
            assertThat(state(f, id).getJsonArray("applied").getList())
                    .containsExactly("approve:approve", "heavy:heavy");
            assertThat(f.signals(id))
                    .noneMatch(row -> List.of("FAILED", "REJECTED", "DEAD").contains(row.state()));
            assertThat(f.fixture.historyOf(id)).doesNotContain("FAILED");
            assertThat(f.fixture.historyTextsOf(id)).noneMatch(text -> text.contains("STEP_LIMIT"));
        }
    }

    @Test
    @DisplayName("a signal overrunning the step cap in its savepoint is rejected, a parked one is requeued first")
    void signalOverrunningTheStepCapIsRejectedWithStepLimit() {
        SpinFixture paidSide = new SpinFixture("spin-paid", "paid");
        SpinFixture shipSide = new SpinFixture("spin-ship", "approve");
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), paidSide, List.of("paid"))) {
            f.seed(shipSide, List.of("approve", "shipped"), new JsonObject());
            Node node = f.node("engine").maxStepsPerSegment(50).build();
            WorkflowInstanceId a = await(f.caller(
                    () -> node.pool.withTransaction(tx -> node.engine.start(f.start(paidSide, "spin-a"), tx))));
            f.idle(node, a);
            WorkflowInstanceId b = await(f.caller(
                    () -> node.pool.withTransaction(tx -> node.engine.start(f.start(shipSide, "spin-b"), tx))));
            f.idle(node, b);
            // Given B holds a spinning shipped and a valid shipped parked for the later ship wait
            f.record(node, b, "shipped", new Signal(false, "", "spin", true, false), "spin");
            f.record(node, b, "shipped", signal("valid"), "valid");
            f.idle(node, b);
            assertThat(f.signals(b)).extracting(CommandRow::state).containsExactly("PARKED", "PARKED");
            JsonObject aBefore = f.snapshot(a);
            // When A records a spinning paid and B records approve, then both run until idle
            f.record(node, a, "paid", new Signal(false, "", "spin", true, false), "spin-paid");
            f.idle(node, a);
            f.record(node, b, "approve", signal("approve"), "approve");
            f.idle(node, b);
            // Then A's executing command is rejected with STEP_LIMIT and the instance is untouched
            CommandRow paid = f.signal(a, "spin-paid");
            assertThat(paid.state()).isEqualTo("REJECTED");
            assertThat(paid.lastError()).startsWith("STEP_LIMIT");
            assertThat(f.entry(a, "COMMAND_REJECTED", paid.id()).reason()).startsWith("STEP_LIMIT");
            assertThat(f.snapshot(a)).isEqualTo(aBefore);
            assertThat(f.snapshot(a).getString("status")).isEqualTo("WAITING");
            assertThat(f.snapshot(a).getString("current_step_id")).isEqualTo("paid");
            // And B's approve completed while the spinning parked command was requeued, not rejected, in that segment
            CommandRow approve = f.signal(b, "approve");
            CommandRow spin = f.signal(b, "spin");
            CommandRow valid = f.signal(b, "valid");
            assertThat(approve.state()).isEqualTo("COMPLETE");
            String approveTransaction =
                    f.entry(b, "COMMAND_COMPLETED", approve.id()).transaction();
            assertThat(f.history(b))
                    .noneMatch(h -> h.transaction().equals(approveTransaction)
                            && (Long.valueOf(spin.id()).equals(h.commandId())
                                    || Long.valueOf(valid.id()).equals(h.commandId())));
            // And the spinning command overran again as the executing command of its own segment
            assertThat(spin.state()).isEqualTo("REJECTED");
            assertThat(spin.seq()).isLessThan(valid.seq());
            assertThat(spin.attempts()).isEqualTo(1);
            assertThat(spin.lastError()).startsWith("STEP_LIMIT");
            History spinRejected = f.entry(b, "COMMAND_REJECTED", spin.id());
            assertThat(spinRejected.reason()).startsWith("STEP_LIMIT");
            assertThat(spinRejected.transaction()).isNotEqualTo(approveTransaction);
            assertThat(valid.state()).isEqualTo("COMPLETE");
            assertThat(f.entry(b, "SIGNAL_APPLIED", valid.id()).transaction()).isNotEqualTo(approveTransaction);
            assertThat(state(f, b).getJsonArray("applied").getList())
                    .containsExactly("approve:approve", "shipped:valid");
            assertThat(f.snapshot(b).getString("status")).isNotEqualTo("FAILED");
            assertThat(f.fixture.historyOf(a)).doesNotContain("FAILED");
            assertThat(f.fixture.historyOf(b)).doesNotContain("FAILED");
        }
    }

    @Test
    @DisplayName(
            "a parked signal drained by another user's segment keeps its recorder on its decisions and its intents")
    void drainedParkedSignalKeepsItsRecorderOnDecisionsAndIntents() {
        try (SignalFixture f = shipping(1)) {
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, "drained-actor");
            // Given user B's shipped signal parked at the approve wait
            recordAs(f, node, id, user("B"), "shipped", "shipped");
            f.idle(node, id);
            assertThat(f.signal(id, "shipped").state()).isEqualTo("PARKED");
            f.intents.routed.clear();

            // When user A's approve segment reaches the ship wait and drains the signal
            recordAs(f, node, id, user("A"), "approve", "approve");
            f.idle(node, id);

            // Then the drained signal's decisions carry B and the segment's own carry A
            CommandRow shipped = f.signal(id, "shipped");
            CommandRow approve = f.signal(id, "approve");
            assertThat(shipped.state()).isEqualTo("COMPLETE");
            assertThat(decisionActors(f, id, shipped.id())).isNotEmpty().containsOnly("B");
            assertThat(decisionActors(f, id, approve.id())).isNotEmpty().containsOnly("A");

            // And the shipping intent the drained signal's handler wrote names B, not the segment's user
            assertThat(f.intents.routed)
                    .extracting(intent -> intent.headers().get("x-workflow-actor-id"))
                    .containsExactly("B");

            // And no entry of the drain is given the segment's command: an entry names its own command or none
            assertThat(commandOf(f, id, "SIGNAL_APPLIED", shipped.id())).isEqualTo(shipped.id());
            for (Row row : await(f.fixture
                    .observer()
                    .preparedQuery("SELECT command_id, payload_json->>'commandId' AS named FROM workflow_history"
                            + " WHERE workflow_id = $1 AND command_id IS NOT NULL AND entry_type <> 'DECISION'")
                    .execute(Tuple.of(id.value())))) {
                String named = row.getString("named");
                if (named != null) {
                    assertThat(row.getLong("command_id")).isEqualTo(Long.parseLong(named));
                }
            }
        }
    }

    private static Long commandOf(SignalFixture f, WorkflowInstanceId id, String entryType, long commandId) {
        Row row = await(f.fixture
                        .observer()
                        .preparedQuery("SELECT command_id FROM workflow_history WHERE workflow_id = $1"
                                + " AND entry_type = $2 AND payload_json->>'commandId' = $3")
                        .execute(Tuple.of(id.value(), entryType, String.valueOf(commandId))))
                .iterator()
                .next();
        return row.getLong("command_id");
    }

    private static SecurityIdentity user(String id) {
        return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of()));
    }

    private static void recordAs(
            SignalFixture f, Node node, WorkflowInstanceId id, SecurityIdentity caller, String name, String key) {
        await(f.caller(() -> {
            var scope = f.fixture.holder().bind(SecurityContext.class, SecurityContexts.unauthenticated(caller));
            return node.pool
                    .withTransaction(tx -> node.engine.signal(id, name, signal(key), key, tx))
                    .onComplete(ignored -> scope.close());
        }));
    }

    private static List<String> decisionActors(SignalFixture f, WorkflowInstanceId id, long commandId) {
        List<String> actors = new ArrayList<>();
        for (Row row : await(f.fixture
                .observer()
                .preparedQuery("SELECT actor_id FROM workflow_history WHERE workflow_id = $1"
                        + " AND entry_type = 'DECISION' AND command_id = $2")
                .execute(Tuple.of(id.value(), commandId)))) {
            actors.add(row.getString("actor_id"));
        }
        return actors;
    }

    static SignalFixture shipping(int shipments) {
        return new SignalFixture(db.toPoolConfig(), new ShippingFixture(shipments), List.of("approve", "shipped"));
    }

    static JsonObject state(SignalFixture f, WorkflowInstanceId id) {
        // the state column holds the JSON text of the state as a JSONB string value
        Object stored = f.snapshot(id).getValue("state_json");
        return stored instanceof String text ? new JsonObject(text) : (JsonObject) stored;
    }

    static List<CommandRow> ticks(SignalFixture f, WorkflowInstanceId id) {
        long approve = f.signal(id, "approve").id();
        return f.signals(id).stream().filter(row -> row.id() != approve).toList();
    }

    static final class ShippingFixture extends Definition {
        ShippingFixture(int shipments) {
            super("parked-shipping", wf -> {
                wf.initialStep("approve");
                waitFor(wf, "approve", "approve", "route");
                wf.decide("route", state -> state.cancelOrder() ? "done" : "ship");
                waitFor(wf, "ship", "shipped", "shipping-intent");
                wf.dispatch("shipping-intent", "signal.service", state -> Map.of("ref", state.ref()), "more")
                        .decide("more", state -> {
                            // a payload that sets the spin flag poisons the shipment after its intent was written
                            if (state.spin()) throw new IllegalStateException("poisoned shipment " + state.ref());
                            return state.applied().stream()
                                                    .filter(value -> value.startsWith("shipped:"))
                                                    .count()
                                            < shipments
                                    ? "ship"
                                    : "done";
                        })
                        .complete("done");
            });
        }
    }

    /** A definition whose paid and shipped updaters route a spinning payload into a sixty-node decision loop. */
    static final class SpinFixture extends Definition {
        SpinFixture(String id, String first) {
            super(id, wf -> {
                wf.initialStep(first);
                if (first.equals("paid")) {
                    waitFor(wf, "paid", "paid", "paid-route");
                    wf.decide("paid-route", state -> state.spin() ? "spin-0" : "paid-done");
                    wf.complete("paid-done");
                } else {
                    waitFor(wf, "approve", "approve", "ship");
                    waitFor(wf, "ship", "shipped", "ship-route");
                    wf.decide("ship-route", state -> state.spin() ? "spin-0" : "done");
                    wf.complete("done");
                }
                LoopFixture.pathOf(wf, "spin", 60, "spin-end", () -> {});
                wf.complete("spin-end");
            });
        }
    }

    static final class LoopFixture extends Definition {
        LoopFixture() {
            super("parked-loop", wf -> {
                wf.initialStep("approve");
                waitFor(wf, "approve", "approve", "loop");
                waitFor(wf, "loop", "tick", "tick-0");
                pathOf(wf, "tick", 10, "loop", () -> {});
            });
        }
        // Approve first reaches loop (one node); every tick then executes nine decisions and the same wait node.
        static int stepsBeforeTick(int priorTicks) {
            return 1 + 10 * priorTicks;
        }

        static void pathOf(WorkflowBuilder<State> wf, String prefix, int nodes, String wait, Runnable visited) {
            for (int i = 0; i < nodes - 1; i++) {
                String next = i == nodes - 2 ? wait : prefix + "-" + (i + 1);
                wf.decide(prefix + "-" + i, state -> {
                    visited.run();
                    return next;
                });
            }
        }
    }

    static final class HeavyFixture extends Definition {
        final AtomicInteger approveNodes;
        final AtomicInteger heavyNodes;
        final AtomicInteger heavyStarts;

        HeavyFixture() {
            this(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        }

        private HeavyFixture(AtomicInteger approveNodes, AtomicInteger heavyNodes, AtomicInteger heavyStarts) {
            super("parked-heavy", wf -> {
                wf.initialStep("approve");
                waitFor(wf, "approve", "approve", "approve-0");
                LoopFixture.pathOf(wf, "approve", 400, "ship", approveNodes::incrementAndGet);
                wf.waitForSignal("ship", "heavy", Signal.class)
                        .onSignal((state, payload) -> {
                            heavyStarts.incrementAndGet();
                            return state.add("heavy", payload);
                        })
                        .toStepOnSignal("heavy-0")
                        .build();
                LoopFixture.pathOf(wf, "heavy", 800, "done", heavyNodes::incrementAndGet);
                waitFor(wf, "done", "done", "complete");
                wf.complete("complete");
            });
            this.approveNodes = approveNodes;
            this.heavyNodes = heavyNodes;
            this.heavyStarts = heavyStarts;
        }
    }
}
