// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.recording;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
import dev.vertique.workflow.migration.DefaultWorkflowMigrationRegistry;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.OpenTask;
import dev.vertique.workflow.postgresql.executor.RecordedMigrationIT.Versioned;
import dev.vertique.workflow.postgresql.executor.RecordedRecoveryIT.BreakableTimeout;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.History;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.OrderSignalFixture;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.RaceForkFixture;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Signal;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.DueDateTask;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.ParallelWait;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.SegmentPause;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TaskDriver;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimeoutWait;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerRow;
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
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that cancelling an instance is a recorded-era operation: under the instance's execution lock it settles
 * every pending, deferred, claimed, parked and dead-lettered command and every unsettled timer, cancels reserved and open
 * tasks, marks the instance cancelled and logs one entry for the cancellation and one for each cancelled command; that
 * an instance cancelled before it was initialized stays without state and can be queried; that the open-signal counter
 * is returned by cancelling and by ending; and that every kind of command that meets an ended or failed instance settles
 * as the outcome table states.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 90, unit = TimeUnit.SECONDS)
public class RecordedCancelIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_recorded_cancel_it")
            .withMigration("classpath:db/migration/workflow");

    static final String WHY = "operator-cancelled";

    private static final String SECRET = "zz-secret-marker";
    private static final java.time.Duration DEAD_LETTERED = java.time.Duration.ofSeconds(45);

    @Test
    @DisplayName(
            "a cancellation reason is cut at 255 code points and stripped of control characters before it is stored")
    void cancellationReasonIsBoundedBeforeItIsStored() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new TimeoutWait(), List.of("approve", "note", "finish"))) {
            Node node = f.node("engine").build();

            // Given a waiting instance and a reason of 400 code points with a line break, an escape and a supplementary
            // character in it
            WorkflowInstanceId id = f.waiting(node, "bounded-reason");
            String reason = "a\nb\u001bc" + "\uD83D\uDE00".repeat(100) + "z".repeat(300);

            // When the caller cancels the instance with it
            await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.cancel(id, reason, tx))));

            // Then the history and the instance hold at most 255 code points, without control characters
            String storedInHistory = f.history(id).stream()
                    .filter(h -> h.type().equals("CANCELLED"))
                    .findFirst()
                    .orElseThrow()
                    .payload()
                    .getString("reason");
            String storedOnInstance = f.row("SELECT error_message FROM workflow_instances WHERE id=$1", id)
                    .getString("error_message");
            for (String stored : List.of(storedInHistory, storedOnInstance)) {
                assertThat(stored.codePointCount(0, stored.length())).isEqualTo(255);
                assertThat(stored).startsWith("a?b?c\uD83D\uDE00");
                assertThat(stored.chars().anyMatch(Character::isISOControl)).isFalse();
            }
        }
    }

    @Test
    @DisplayName("cancel settles a deferred head, a parked signal and a pending timer command with their timer row")
    void cancelSettlesDeferredParkedAndTimerCommands() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new TimeoutWait(), List.of("approve", "note", "finish"))) {
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine").build();

            // Given an instance waiting with a pending timeout, a parked note, and an approve deferred as the head
            WorkflowInstanceId id = f.waiting(node, "cancel-all");
            TimerRow timeout = timers.only(id, "TIMEOUT");
            f.record(node, id, "note", signal("parked"), "k-note");
            f.idle(node, id);
            f.record(node, id, "approve", signal("head"), "k-approve");
            Node lacking =
                    f.node("lacking").withRegistry(registryLackingTheFixture()).build();
            lacking.startExecutor();
            try {
                awaitCondition("the approve to be deferred", IDLE, () -> f.signal(id, "k-approve")
                        .state()
                        .equals("DEFERRED"));
            } finally {
                lacking.stopExecutor();
            }
            assertThat(f.signal(id, "k-note").state()).isEqualTo("PARKED");
            assertThat(timers.byId(timeout.id()).state()).isEqualTo("PENDING");

            // When the caller cancels the instance
            cancel(f, node, id);

            // Then the instance is cancelled, the start stays complete and the three commands are cancelled with
            // their tokens cleared
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("CANCELLED");
            List<CommandRow> commands = f.fixture.commandsOf(id);
            assertThat(commands)
                    .filteredOn(row -> row.kind().equals("START"))
                    .extracting(CommandRow::state)
                    .containsExactly("COMPLETE");
            List<CommandRow> cancelled =
                    commands.stream().filter(row -> !row.kind().equals("START")).toList();
            assertThat(cancelled).hasSize(3).allSatisfy(row -> {
                assertThat(row.state()).isEqualTo("CANCELLED");
                assertThat(row.claimToken()).isNull();
                assertThat(row.leaseUntil()).isNull();
            });

            // And the timer row is cancelled, the counter is back at zero, and the history holds the cancellation
            // and one entry for each cancelled command
            assertThat(timers.timerRowOf(timeout).status()).isEqualTo("CANCELLED");
            assertThat(f.openSignals(id)).isZero();
            List<String> history = f.fixture.historyOf(id);
            assertThat(history).filteredOn("CANCELLED"::equals).hasSize(1);
            assertThat(history).filteredOn("COMMAND_CANCELLED"::equals).hasSize(3);
            for (CommandRow row : cancelled) {
                assertThat(f.entry(id, "COMMAND_CANCELLED", row.id())).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("cancel waits for a running segment, then cancels the command behind it, and no segment commits later")
    void cancelWaitsForTheRunningSegment() {
        try (SignalFixture f = order()) {
            SegmentPause pause = new SegmentPause();
            Node node = f.node("engine").runner(pause.decorator()).build();

            // Given a paid and a shipped signal recorded, and the paid segment paused inside the execution lock
            WorkflowInstanceId id = f.waiting(node, "cancel-waits");
            f.record(node, id, "paid", signal("first"), "k-paid");
            f.record(node, id, "shipped", signal("second"), "k-shipped");
            pause.armInsideLock();
            node.startExecutor();
            try {
                pause.awaitReached();
                assertThat(f.signal(id, "k-paid").state()).isEqualTo("CLAIMED");

                // When the caller cancels, and the segment is released
                Future<Void> cancellation = cancelAsync(f, node, id);
                awaitCondition(
                        "the cancel to wait for the execution lock",
                        IDLE,
                        () -> f.fixture.ungrantedAdvisoryLocks() == 1);
                assertThat(cancellation.isComplete()).isFalse();
                pause.release();
                await(cancellation);

                // Then the segment committed first, and the command behind it is cancelled once cancel completes
                assertThat(f.signal(id, "k-paid").state()).isEqualTo("COMPLETE");
                CommandRow behind = f.signal(id, "k-shipped");
                assertThat(behind.state()).isEqualTo("CANCELLED");
                assertThat(behind.claimToken()).isNull();
                assertThat(f.fixture.instanceOf(id).status()).isEqualTo("CANCELLED");

                // And nothing commits for the instance afterwards: the history ends with the cancellation entries
                node.awaitIdle(id);
                assertThat(f.signal(id, "k-shipped").state()).isEqualTo("CANCELLED");
                assertThat(f.fixture.instanceOf(id).status()).isEqualTo("CANCELLED");
                List<History> history = f.history(id);
                assertThat(history.subList(history.size() - 2, history.size()))
                        .extracting(History::type)
                        .containsExactlyInAnyOrder("CANCELLED", "COMMAND_CANCELLED");
                long segment = f.entry(
                                id, "COMMAND_COMPLETED", f.signal(id, "k-paid").id())
                        .sequence();
                assertThat(segment).isLessThan(history.get(history.size() - 2).sequence());
                assertThat(history)
                        .filteredOn(h -> h.type().equals("CANCELLED"))
                        .hasSize(1);
                assertThat(history)
                        .filteredOn(h -> h.type().equals("COMMAND_CANCELLED"))
                        .hasSize(1);
            } finally {
                pause.release();
                node.stopExecutor();
            }
        }
    }

    @Test
    @DisplayName("a signal racing a waiting cancel is refused as ended or recorded and cancelled, never left pending")
    void signalRacingCancelIsRefusedOrCancelled() {
        try (SignalFixture f = order()) {
            Node node = f.node("engine").build();
            for (int run = 0; run < 20; run++) {
                WorkflowInstanceId id = f.waiting(node, "race-" + run);
                String key = "k-" + run;
                boolean signalFirst = run % 2 == 0;
                Throwable laterFailure;
                try (Sessions sessions = Sessions.open(node)) {
                    // Given the first session holds the execution lock with its uncommitted work
                    Future<Void> second;
                    if (signalFirst) {
                        await(node.engine.signal(id, "paid", signal("paid"), key, sessions.a));
                        second = node.engine.cancel(id, WHY, sessions.b);
                    } else {
                        await(node.engine.cancel(id, WHY, sessions.a));
                        second = node.engine.signal(id, "paid", signal("paid"), key, sessions.b);
                    }

                    // When the second session waits for the lock, and the first commits
                    awaitCondition("the second session to wait", IDLE, () -> f.fixture.ungrantedAdvisoryLocks() == 1);
                    assertThat(second.isComplete()).isFalse();
                    sessions.commitA();
                    laterFailure = outcomeOf(second);
                    if (laterFailure == null) {
                        sessions.commitB();
                    } else {
                        sessions.rollbackB();
                    }
                }

                // Then the signal was recorded and then cancelled, or refused as ended; no command is left active
                String label = "repetition " + run + (signalFirst ? " (signal first)" : " (cancel first)");
                assertThat(f.fixture.instanceOf(id).status()).as(label).isEqualTo("CANCELLED");
                assertThat(f.fixture.commandsOf(id))
                        .as(label)
                        .extracting(CommandRow::state)
                        .doesNotContain("PENDING", "DEFERRED", "CLAIMED", "PARKED", "DEAD");
                if (signalFirst) {
                    assertThat(laterFailure).as(label).isNull();
                    assertThat(f.signals(id))
                            .as(label)
                            .extracting(CommandRow::state)
                            .containsExactly("CANCELLED");
                } else {
                    assertThat(laterFailure).as(label).isInstanceOf(WorkflowSignalRejectedException.class);
                    assertThat(f.signals(id)).as(label).isEmpty();
                    assertThat(f.dedup(id, key)).as(label).isZero();
                }
                assertThat(f.openSignals(id)).as(label).isZero();
            }
        }
    }

    @Test
    @DisplayName("cancel settles dead letters and task reservations")
    void cancelSettlesDeadLettersAndReservations() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new DueDateTask(), List.of("note"))) {
            TimerFixture timers = new TimerFixture(f);
            TaskDriver tasks = new TaskDriver(f);
            CommandFaults faults = new CommandFaults();
            Node node = f.node("engine")
                    .leaseSeconds(5)
                    .maxAttempts(1)
                    .commands(faults.decorator())
                    .build();

            // Given instance A with a dead-lettered signal and a dead-lettered completion whose task is reserved
            OpenTask a = tasks.open(node, "A");
            f.record(node, a.id(), "note", signal("dead"), "k-dead-signal");
            tasks.complete(node, a, new JsonObject(), "k-dead-completion");
            faults.crashEverySegmentOf(a.id());
            node.startExecutor();
            try {
                awaitCondition(
                        "the signal and the completion to be dead-lettered",
                        DEAD_LETTERED,
                        () -> f.signal(a.id(), "k-dead-signal").state().equals("DEAD")
                                && tasks.completions(a.id()).getFirst().state().equals("DEAD"));
            } finally {
                faults.crashSegmentsOf(a.id(), 0);
                node.stopExecutor();
            }
            assertThat(tasks.statusOf(a)).isEqualTo("COMPLETING");
            assertThat(f.openSignals(a.id())).isZero();

            // And instance B with a pending completion whose task is reserved
            OpenTask b = tasks.open(node, "B");
            tasks.complete(node, b, new JsonObject(), "k-pending-completion");
            assertThat(tasks.statusOf(b)).isEqualTo("COMPLETING");
            assertThat(tasks.completions(b.id()).getFirst().state()).isEqualTo("PENDING");

            // When both are cancelled
            cancel(f, node, a.id());
            cancel(f, node, b.id());

            // Then A's commands are cancelled and logged, its task is cancelled and its counter is still zero
            assertThat(f.fixture.instanceOf(a.id()).status()).isEqualTo("CANCELLED");
            assertThat(f.signal(a.id(), "k-dead-signal").state()).isEqualTo("CANCELLED");
            CommandRow deadCompletion = tasks.completions(a.id()).getFirst();
            assertThat(deadCompletion.state()).isEqualTo("CANCELLED");
            assertThat(deadCompletion.claimToken()).isNull();
            assertThat(f.entry(
                            a.id(),
                            "COMMAND_CANCELLED",
                            f.signal(a.id(), "k-dead-signal").id()))
                    .isNotNull();
            assertThat(f.entry(a.id(), "COMMAND_CANCELLED", deadCompletion.id()))
                    .isNotNull();
            assertThat(tasks.statusOf(a)).isEqualTo("CANCELLED");
            assertThat(f.openSignals(a.id())).isZero();

            // And B's completion is cancelled and its task is cancelled
            CommandRow pendingCompletion = tasks.completions(b.id()).getFirst();
            assertThat(pendingCompletion.state()).isEqualTo("CANCELLED");
            assertThat(f.entry(b.id(), "COMMAND_CANCELLED", pendingCompletion.id()))
                    .isNotNull();
            assertThat(tasks.statusOf(b)).isEqualTo("CANCELLED");
            assertThat(f.fixture.instanceOf(b.id()).status()).isEqualTo("CANCELLED");

            // And the due dates of both tasks are cancelled with their timer rows
            for (OpenTask task : List.of(a, b)) {
                TimerRow due = timers.only(task.id(), "DUE");
                assertThat(due.state()).isEqualTo("CANCELLED");
                assertThat(timers.timerRowOf(due).status()).isEqualTo("CANCELLED");
            }
        }
    }

    // --- The outcome table ---

    /** The kinds of command the outcome table names, with the instance arrangement each needs. */
    enum Kind {
        SIGNAL,
        BRANCH_SIGNAL_OF_A_LIVE_BRANCH,
        BRANCH_SIGNAL_OF_A_SUPERSEDED_BRANCH,
        COMPLETION,
        RETRY,
        MIGRATION,
        TIMER
    }

    /**
     * One cell of the table: the kind of command, the status its instance has when the command's segment runs, and how
     * the command settles.
     *
     * @param kind     the command kind
     * @param status   the status of the instance
     * @param expected the state of the command afterwards
     */
    record Cell(Kind kind, String status, String expected) {
        @Override
        public String toString() {
            return kind + " meets " + status + " -> " + expected;
        }
    }

    static Stream<Cell> outcomeTable() {
        List<String> ended = List.of("COMPLETED", "COMPENSATED", "EXPIRED");
        Stream.Builder<Cell> table = Stream.builder();
        // Signal (instance-addressed): parked; rejected; cancelled
        table.add(new Cell(Kind.SIGNAL, "FAILED", "PARKED"));
        ended.forEach(status -> table.add(new Cell(Kind.SIGNAL, status, "REJECTED")));
        table.add(new Cell(Kind.SIGNAL, "CANCELLED", "CANCELLED"));
        // Signal (branch-addressed): stale if its branch ended, else parked; stale; cancelled
        table.add(new Cell(Kind.BRANCH_SIGNAL_OF_A_LIVE_BRANCH, "FAILED", "PARKED"));
        table.add(new Cell(Kind.BRANCH_SIGNAL_OF_A_SUPERSEDED_BRANCH, "FAILED", "STALE"));
        ended.forEach(status -> table.add(new Cell(Kind.BRANCH_SIGNAL_OF_A_LIVE_BRANCH, status, "STALE")));
        table.add(new Cell(Kind.BRANCH_SIGNAL_OF_A_LIVE_BRANCH, "CANCELLED", "CANCELLED"));
        // Task completion: rejected with the reservation reopened; rejected with the task cancelled; cancelled
        table.add(new Cell(Kind.COMPLETION, "FAILED", "REJECTED"));
        ended.forEach(status -> table.add(new Cell(Kind.COMPLETION, status, "REJECTED")));
        table.add(new Cell(Kind.COMPLETION, "CANCELLED", "CANCELLED"));
        // Retry: executes; rejected; cancelled
        table.add(new Cell(Kind.RETRY, "FAILED", "COMPLETE"));
        ended.forEach(status -> table.add(new Cell(Kind.RETRY, status, "REJECTED")));
        table.add(new Cell(Kind.RETRY, "CANCELLED", "CANCELLED"));
        // Migration: rejected; rejected; cancelled
        table.add(new Cell(Kind.MIGRATION, "FAILED", "REJECTED"));
        ended.forEach(status -> table.add(new Cell(Kind.MIGRATION, status, "REJECTED")));
        table.add(new Cell(Kind.MIGRATION, "CANCELLED", "CANCELLED"));
        // Timer commands: stale; stale; cancelled
        table.add(new Cell(Kind.TIMER, "FAILED", "STALE"));
        ended.forEach(status -> table.add(new Cell(Kind.TIMER, status, "STALE")));
        table.add(new Cell(Kind.TIMER, "CANCELLED", "CANCELLED"));
        return table.build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outcomeTable")
    @DisplayName("a command that meets an ended or failed instance settles as the outcome table states")
    void commandsMeetingEndedInstancesFollowTheOutcomeTable(Cell cell) {
        Arranged arranged = Arranged.of(cell);
        try (SignalFixture f = arranged.fixture()) {
            CommandFaults faults = new CommandFaults();
            Node node = f.node("engine")
                    .commands(faults.decorator())
                    .migrations(new DefaultWorkflowMigrationRegistry(Set.of(), f.registry))
                    .build();

            // Given an instance with a command of the kind at the head of its queue, executors stopped
            Armed armed = arranged.arm(f, node, cell);
            boolean flips = !(cell.kind() == Kind.RETRY && cell.status().equals("FAILED"));
            if (flips) {
                // And the command is claimed before the instance takes the status
                faults.afterClaimOf(armed.id(), claim -> setStatus(f, armed.id(), cell.status()));
            }

            // When the command's segment runs
            node.startExecutor();
            try {
                awaitCondition("the command to settle", IDLE, () -> !List.of("PENDING", "CLAIMED", "DEFERRED")
                        .contains(armed.command(f).state()));
                node.awaitIdle(armed.id());
            } finally {
                node.stopExecutor();
            }

            // Then the command is settled as the table states, logged, and its token is cleared
            CommandRow settled = armed.command(f);
            assertThat(settled.state()).as(cell.toString()).isEqualTo(cell.expected());
            assertThat(settled.claimToken()).isNull();
            String logged =
                    switch (cell.expected()) {
                        case "REJECTED" -> "COMMAND_REJECTED";
                        case "STALE" -> "COMMAND_STALE";
                        case "CANCELLED" -> "COMMAND_CANCELLED";
                        case "COMPLETE" -> "COMMAND_COMPLETED";
                        default -> "SIGNAL_PARKED";
                    };
            assertThat(f.entry(armed.id(), logged, settled.id())).isNotNull();
            if (!cell.expected().equals("COMPLETE") && !cell.expected().equals("PARKED")) {
                assertThat(f.entry(armed.id(), logged, settled.id()).payload().getString("reason"))
                        .matches("(?s)^[A-Z][A-Z_]*: .+");
            }

            // And the instance keeps the status it has, except that a retry recovers a failed instance
            String status = cell.kind() == Kind.RETRY && cell.expected().equals("COMPLETE") ? "WAITING" : cell.status();
            assertThat(f.fixture.instanceOf(armed.id()).status()).isEqualTo(status);

            // And the kind's own side effects are as the table states
            arranged.verify(f, armed, cell);
        }
    }

    /** What the fixture of a kind arranged: the instance and how to read its command. */
    record Armed(WorkflowInstanceId id, Function<SignalFixture, CommandRow> reader, Map<String, Object> facts) {
        CommandRow command(SignalFixture f) {
            return reader.apply(f);
        }
    }

    /** The definitions and arrangement of one kind of command. */
    static final class Arranged {
        private final Kind kind;
        private final Definition primary;
        private final List<String> names;
        private final JsonObject branches;
        private final BreakableTimeout breakable;

        private Arranged(Kind kind, Definition primary, List<String> names, JsonObject branches) {
            this.kind = kind;
            this.primary = primary;
            this.names = names;
            this.branches = branches;
            this.breakable = primary instanceof BreakableTimeout broken ? broken : null;
        }

        static Arranged of(Cell cell) {
            return switch (cell.kind()) {
                case SIGNAL ->
                    new Arranged(
                            cell.kind(),
                            new OrderSignalFixture(),
                            List.of("paid", "shipped", "note"),
                            new JsonObject());
                case BRANCH_SIGNAL_OF_A_LIVE_BRANCH ->
                    new Arranged(
                            cell.kind(),
                            new ParallelWait(),
                            List.of(),
                            new JsonObject().put("fork/a", new JsonArray().add("ack")));
                case BRANCH_SIGNAL_OF_A_SUPERSEDED_BRANCH ->
                    new Arranged(
                            cell.kind(),
                            new RaceForkFixture(),
                            List.of(),
                            new JsonObject().put("fork/b2", new JsonArray().add("ack")));
                case COMPLETION -> new Arranged(cell.kind(), new DueDateTask(), List.of(), new JsonObject());
                case RETRY ->
                    new Arranged(cell.kind(), new BreakableTimeout(), List.of("approve", "finish"), new JsonObject());
                case MIGRATION ->
                    new Arranged(cell.kind(), migrationVersion(1), List.of("paid", "note"), new JsonObject());
                case TIMER ->
                    new Arranged(
                            cell.kind(), new TimeoutWait(), List.of("approve", "note", "finish"), new JsonObject());
            };
        }

        SignalFixture fixture() {
            return new SignalFixture(db.toPoolConfig(), primary, names, branches);
        }

        Armed arm(SignalFixture f, Node node, Cell cell) {
            String ref = "table-" + kind.name().toLowerCase();
            switch (kind) {
                case SIGNAL -> {
                    WorkflowInstanceId id = f.waiting(node, ref);
                    f.record(node, id, "paid", signal("paid"), "k");
                    return new Armed(id, fx -> fx.signal(id, "k"), Map.of());
                }
                case BRANCH_SIGNAL_OF_A_LIVE_BRANCH -> {
                    WorkflowInstanceId id = f.waiting(node, ref);
                    await(f.caller(() -> node.pool.withTransaction(
                            tx -> node.engine.signal(id, "ack", signal("ack"), "k", "fork", "a", tx))));
                    return new Armed(id, fx -> fx.signal(id, "k"), Map.of());
                }
                case BRANCH_SIGNAL_OF_A_SUPERSEDED_BRANCH -> {
                    WorkflowInstanceId id = f.waiting(node, ref);
                    await(f.caller(() -> node.pool.withTransaction(
                            tx -> node.engine.signal(id, "ack", signal("ack"), "k", "fork", "b2", tx))));
                    return new Armed(id, fx -> fx.signal(id, "k"), Map.of());
                }
                case COMPLETION -> {
                    TaskDriver tasks = new TaskDriver(f);
                    OpenTask task = tasks.open(node, ref);
                    tasks.complete(node, task, new JsonObject(), "k");
                    return new Armed(
                            task.id(), fx -> tasks.completions(task.id()).getFirst(), Map.of("task", task));
                }
                case RETRY -> {
                    TimerFixture timers = new TimerFixture(f);
                    WorkflowInstanceId id = f.waiting(node, ref);
                    timers.dueNow(timers.only(id, "TIMEOUT").id());
                    f.idle(node, id);
                    assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
                    if (cell.status().equals("FAILED")) {
                        breakable.broken.set(false);
                    }
                    await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.retry(id, tx))));
                    return new Armed(
                            id,
                            fx -> fx.fixture.commandsOf(id).stream()
                                    .filter(row -> row.kind().equals("RETRY"))
                                    .findFirst()
                                    .orElseThrow(),
                            Map.of());
                }
                case MIGRATION -> {
                    seedVersion(f, migrationVersion(2), List.of("paid", "note"));
                    WorkflowInstanceId id = f.waiting(node, ref);
                    await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.migrate(id, 2L, tx))));
                    return new Armed(
                            id,
                            fx -> fx.fixture.commandsOf(id).stream()
                                    .filter(row -> row.kind().equals("MIGRATION"))
                                    .findFirst()
                                    .orElseThrow(),
                            Map.of());
                }
                case TIMER -> {
                    TimerFixture timers = new TimerFixture(f);
                    WorkflowInstanceId id = f.waiting(node, ref);
                    TimerRow timeout = timers.only(id, "TIMEOUT");
                    timers.dueNow(timeout.id());
                    return new Armed(
                            id,
                            fx -> fx.fixture.commandsOf(id).stream()
                                    .filter(row -> row.id() == timeout.id())
                                    .findFirst()
                                    .orElseThrow(),
                            Map.of());
                }
            }
            throw new IllegalStateException("unknown kind " + kind);
        }

        void verify(SignalFixture f, Armed armed, Cell cell) {
            switch (kind) {
                case SIGNAL, BRANCH_SIGNAL_OF_A_LIVE_BRANCH, BRANCH_SIGNAL_OF_A_SUPERSEDED_BRANCH -> {
                    // a parked signal stays open; every other outcome returns its slot
                    assertThat(f.openSignals(armed.id()))
                            .isEqualTo(cell.expected().equals("PARKED") ? 1 : 0);
                    if (cell.expected().equals("PARKED")) {
                        assertThat(armed.command(f).attempts()).isZero();
                    }
                }
                case COMPLETION -> {
                    OpenTask task = (OpenTask) armed.facts().get("task");
                    String taskStatus = new TaskDriver(f).statusOf(task);
                    assertThat(taskStatus).isEqualTo(cell.status().equals("FAILED") ? "OPEN" : "CANCELLED");
                }
                case MIGRATION ->
                    assertThat(f.row("SELECT definition_version FROM workflow_instances WHERE id=$1", armed.id())
                                    .getLong("definition_version"))
                            .isEqualTo(1L);
                default -> {}
            }
        }
    }

    @Test
    @DisplayName("a claimed command cancelled in its own segment is logged with the canceller, not with its recorder")
    void claimedCommandCancelledInItsOwnSegmentIsLoggedWithTheCanceller() {
        try (SignalFixture f = order()) {
            CommandFaults faults = new CommandFaults();
            Node node = f.node("engine").commands(faults.decorator()).build();
            WorkflowInstanceId id = f.waiting(node, "claimed-canceller");
            // Given a signal that user "recorder" recorded, and an instance whose cancellation by "canceller" is
            // logged after the signal's command was claimed and before its segment ran, so that the claimed command
            // meets the cancelled instance itself
            await(f.caller(() -> asUser(
                    "recorder",
                    f,
                    () -> node.pool.withTransaction(tx -> node.engine.signal(id, "paid", signal("paid"), "k", tx)))));
            faults.afterClaimOf(id, claim -> setStatus(f, id, "CANCELLED").compose(done -> f.fixture
                    .observer()
                    .preparedQuery("INSERT INTO workflow_history (workflow_id, sequence, entry_type, payload_json,"
                            + " recorded_at, actor_kind, actor_id, attribution_status) SELECT $1, COALESCE(MAX(sequence),"
                            + " 0) + 1, 'CANCELLED', '{}'::jsonb, now(), 'USER', 'canceller', 'VERIFIED' FROM"
                            + " workflow_history WHERE workflow_id = $1")
                    .execute(Tuple.of(id.value()))
                    .mapEmpty()));

            // When the command's segment runs
            node.startExecutor();
            try {
                awaitCondition("the command to settle", IDLE, () -> f.signal(id, "k")
                        .state()
                        .equals("CANCELLED"));
                node.awaitIdle(id);
            } finally {
                node.stopExecutor();
            }

            // Then the command-cancelled entry carries the canceller, while the command row keeps its recorder
            long command = f.signal(id, "k").id();
            var entry = await(f.fixture
                            .observer()
                            .preparedQuery("SELECT actor_kind, actor_id FROM workflow_history WHERE workflow_id = $1"
                                    + " AND entry_type = 'COMMAND_CANCELLED'"
                                    + " AND COALESCE(command_id, (payload_json->>'commandId')::bigint) = $2")
                            .execute(Tuple.of(id.value(), command)))
                    .iterator()
                    .next();
            assertThat(entry.getString("actor_id")).isEqualTo("canceller");
            assertThat(entry.getString("actor_kind")).isEqualTo("USER");
            var row = await(f.fixture
                            .observer()
                            .preparedQuery("SELECT actor_id FROM workflow_commands WHERE id = $1")
                            .execute(Tuple.of(command)))
                    .iterator()
                    .next();
            assertThat(row.getString("actor_id")).isEqualTo("recorder");
        }
    }

    private static <T> Future<T> asUser(String user, SignalFixture f, java.util.function.Supplier<Future<T>> work) {
        return f.caller(() -> {
            var identity = dev.vertique.security.SecurityIdentity.user(
                    new dev.vertique.security.PrincipalRef(dev.vertique.security.PrincipalType.USER, user, Map.of()));
            var scope = f.fixture
                    .holder()
                    .bind(
                            dev.vertique.security.SecurityContext.class,
                            dev.vertique.security.SecurityContexts.unauthenticated(identity));
            return work.get().onComplete(ignored -> scope.close());
        });
    }

    // --- Deferral, counters, uninitialized instances and the ordinary end ---

    @Test
    @DisplayName("a deferral observed before a cancel writes nothing")
    void deferralObservedBeforeACancelWritesNothing() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given engine A that lacks the skeleton workflow and whose deferral is held at a gate, and a start
            // recorded on engine B
            HeldCall held = new HeldCall("defer", arguments -> true);
            Node b = fixture.node("B").build();
            Node a = fixture.node("A")
                    .withRegistry(fixture.registryWithout(ExecutorFixture.SKELETON))
                    .pollIntervalMs(100)
                    .commands(held.decorator())
                    .build();
            WorkflowInstanceId x = b.record(startOf("deferral-vs-cancel"));
            CommandRow before = fixture.commandOf(x);
            held.arm();
            a.startExecutor();
            try {
                held.awaitHeld();

                // When a caller cancels the instance and commits, and the gate opens
                await(b.pool.withTransaction(tx -> b.engine.cancel(x, WHY, tx)));
                held.release();
                held.awaitFinished();

                // Then A's deferral writes nothing: the command stays cancelled, undeferred and unmoved
                CommandRow after = fixture.commandOf(x);
                assertThat(after.state()).isEqualTo("CANCELLED");
                assertThat(after.deferralCount()).isZero();
                assertThat(after.availableAt()).isEqualTo(before.availableAt());
                assertThat(fixture.historyOf(x)).doesNotContain("COMMAND_DEFERRED");
                assertThat(fixture.instanceOf(x).status()).isEqualTo("CANCELLED");
            } finally {
                held.release();
                a.stopExecutor();
            }
        }
    }

    @Test
    @DisplayName("cancel with parked and dead-lettered signals returns the open-signal counter to zero")
    void cancelWithParkedAndDeadSignalsReturnsOpenSignalsToZero() {
        try (SignalFixture f = order()) {
            CommandFaults faults = new CommandFaults();
            Node node = f.node("engine")
                    .leaseSeconds(5)
                    .maxAttempts(1)
                    .recordingConfig(recording(3, 262144))
                    .commands(faults.decorator())
                    .build();
            Signal secret = new Signal(false, "card-" + SECRET, "value-" + SECRET, false, false);

            // Given instance A with a dead-lettered signal, two parked signals and a pending signal, and instance B
            WorkflowInstanceId a = f.waiting(node, "counter-a");
            WorkflowInstanceId b = f.waiting(node, "counter-b");
            f.record(node, a, "note", secret, "dead");
            faults.crashSegmentsOf(a, 1);
            node.startExecutor();
            try {
                awaitCondition("the signal to be dead-lettered", DEAD_LETTERED, () -> f.signal(a, "dead")
                        .state()
                        .equals("DEAD"));
            } finally {
                faults.crashSegmentsOf(a, 0);
                node.stopExecutor();
            }
            f.record(node, a, "note", secret, "parked-1");
            f.record(node, a, "note", secret, "parked-2");
            f.idle(node, a);
            f.record(node, a, "note", secret, "pending");
            assertThat(f.signal(a, "dead").state()).isEqualTo("DEAD");
            assertThat(f.signal(a, "parked-1").state()).isEqualTo("PARKED");
            assertThat(f.signal(a, "parked-2").state()).isEqualTo("PARKED");
            assertThat(f.signal(a, "pending").state()).isEqualTo("PENDING");
            assertThat(f.openSignals(a)).isEqualTo(3);

            // When A is cancelled
            cancel(f, node, a);

            // Then the four signals are cancelled, each logged with the reason code and no payload value
            assertThat(f.signals(a)).hasSize(4).allSatisfy(row -> {
                assertThat(row.state()).isEqualTo("CANCELLED");
                assertThat(row.claimToken()).isNull();
                History entry = f.entry(a, "COMMAND_CANCELLED", row.id());
                assertThat(entry.payload().getString("reason")).startsWith(ReasonCodes.INSTANCE_CANCELLED + ": ");
                assertThat(entry.text()).doesNotContain(SECRET);
            });
            assertThat(f.fixture.historyTextsOf(a)).noneMatch(text -> text.contains(SECRET));

            // And A's counter is zero
            assertThat(f.openSignals(a)).isZero();

            // When one signal is recorded for B and three on a new instance C
            f.record(node, b, "note", signal("b"), "b-1");
            WorkflowInstanceId c = f.waiting(node, "counter-c");
            f.recordSignals(node, c, "note", 3);

            // Then B counts its signal and C records three without reaching the limit
            assertThat(f.openSignals(b)).isEqualTo(1);
            assertThat(f.openSignals(c)).isEqualTo(3);
            assertThat(f.signals(c)).hasSize(3);
        }
    }

    @Test
    @DisplayName("cancelling before initialization leaves no state and the instance can still be queried")
    void cancelBeforeInitializationLeavesNoStateAndQueriesSucceed() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            CommandFaults faults = new CommandFaults();
            Node node = fixture.node("engine")
                    .leaseSeconds(5)
                    .maxAttempts(1)
                    .commands(faults.decorator())
                    .build();

            // Given instance Q whose start was dead-lettered, and instance P recorded and committed, executors stopped
            WorkflowInstanceId q = node.record(startOf("dead-start"));
            faults.crashEverySegmentOf(q);
            node.startExecutor();
            try {
                awaitCondition("the start to be dead-lettered", DEAD_LETTERED, () -> fixture.commandOf(q)
                        .state()
                        .equals("DEAD"));
            } finally {
                faults.crashSegmentsOf(q, 0);
                node.stopExecutor();
            }
            WorkflowInstanceId p = node.record(startOf("pending-start"));
            assertThat(fixture.instanceOf(p).status()).isEqualTo("PENDING");
            assertThat(fixture.instanceOf(q).status()).isEqualTo("FAILED");
            assertThat(fixture.instanceOf(p).stateJson()).isNull();
            assertThat(fixture.instanceOf(q).stateJson()).isNull();

            // When both are cancelled and read through the query operation
            await(node.pool.withTransaction(tx -> node.engine.cancel(p, WHY, tx)));
            await(node.pool.withTransaction(tx -> node.engine.cancel(q, WHY, tx)));
            WorkflowView viewOfP = await(node.engine.query(p));
            WorkflowView viewOfQ = await(node.engine.query(q));

            // Then both are cancelled without state or step, each start is cancelled and logged, and queries answer
            for (WorkflowInstanceId id : List.of(p, q)) {
                assertThat(fixture.instanceOf(id).status()).isEqualTo("CANCELLED");
                assertThat(fixture.instanceOf(id).stateJson()).isNull();
                assertThat(fixture.instanceOf(id).currentStepId()).isNull();
                CommandRow start = fixture.commandOf(id);
                assertThat(start.kind()).isEqualTo("START");
                assertThat(start.state()).isEqualTo("CANCELLED");
                assertThat(start.claimToken()).isNull();
                assertThat(fixture.historyOf(id))
                        .filteredOn("CANCELLED"::equals)
                        .hasSize(1);
                assertThat(fixture.historyOf(id))
                        .filteredOn("COMMAND_CANCELLED"::equals)
                        .hasSize(1);
            }
            for (WorkflowView view : List.of(viewOfP, viewOfQ)) {
                assertThat(view.instance().status().name()).isEqualTo("CANCELLED");
                assertThat(view.instance().stateJson()).isNull();
                assertThat(view.instance().currentStepId()).isNull();
            }

            // When an executor starts and runs until idle
            node.startExecutor();
            try {
                node.awaitIdle(p);
                node.awaitIdle(q);
            } finally {
                node.stopExecutor();
            }

            // Then no segment ran for either: no initial state, no intent, no initial-state entry, still cancelled
            assertThat(fixture.initialStates).isEmpty();
            for (WorkflowInstanceId id : List.of(p, q)) {
                assertThat(fixture.intentsOf(id)).isEmpty();
                assertThat(fixture.historyOf(id)).doesNotContain("START", "STATE_BASELINE");
                assertThat(fixture.instanceOf(id).status()).isEqualTo("CANCELLED");
                assertThat(fixture.instanceOf(id).stateJson()).isNull();
                assertThat(fixture.commandOf(id).state()).isEqualTo("CANCELLED");
            }
        }
    }

    @Test
    @DisplayName("after an ordinary end the counter reaches zero once the remaining signals settle")
    void counterReachesZeroAfterAnOrdinaryEndOnceRemainingSignalsSettle() {
        Definition endsOnApprove = new Definition("cancel-ordinary-end", wf -> {
            wf.initialStep("approve");
            waitFor(wf, "approve", "approve", "after-approve");
            wf.decide("after-approve", state -> state.ref().startsWith("end-") ? "done" : "note-wait");
            waitFor(wf, "note-wait", "note", "done");
            wf.complete("done");
        });
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), endsOnApprove, List.of("approve", "note"))) {
            AtomicLong second = new AtomicLong(-1);
            HeldCall gate = new HeldCall(
                    "claim", arguments -> arguments[0] instanceof CommandHead head && head.commandId() == second.get());
            Node recorder = f.node("recorder").build();
            Node executor = f.node("executor").commands(gate.decorator()).build();

            // Given E waiting at approve with a parked note, then approve and a second note recorded together
            WorkflowInstanceId e = f.waiting(recorder, "end-ordinary");
            f.record(recorder, e, "note", signal("first"), "k-note-1");
            f.idle(recorder, e);
            assertThat(f.signal(e, "k-note-1").state()).isEqualTo("PARKED");
            await(f.caller(() -> recorder.pool.withTransaction(tx -> recorder.engine
                    .signal(e, "approve", signal("approve"), "k-approve", tx)
                    .compose(v -> recorder.engine.signal(e, "note", signal("second"), "k-note-2", tx)))));
            assertThat(f.openSignals(e)).isEqualTo(3);

            // And the executor's claim of the second note is held at a gate
            second.set(f.signal(e, "k-note-2").id());
            gate.arm();

            // When the executor runs approve's segment, and the gate holds the next claim
            executor.startExecutor();
            try {
                gate.awaitHeld();

                // Then E is completed, the parked note was rejected by the ending segment, the second note is still
                // pending and the counter reads one
                assertThat(f.fixture.instanceOf(e).status()).isEqualTo("COMPLETED");
                assertThat(f.signal(e, "k-approve").state()).isEqualTo("COMPLETE");
                CommandRow parked = f.signal(e, "k-note-1");
                assertThat(parked.state()).isEqualTo("REJECTED");
                assertThat(parked.lastError()).matches("(?s)^[A-Z][A-Z_]*: .+");
                assertThat(f.signal(e, "k-note-2").state()).isEqualTo("PENDING");
                assertThat(f.openSignals(e)).isEqualTo(1);

                // When the gate opens and the instance is idle
                gate.release();
                executor.awaitIdle(e);
            } finally {
                gate.release();
                executor.stopExecutor();
            }

            // Then the second note is rejected by the outcome table and the counter reads zero
            CommandRow remaining = f.signal(e, "k-note-2");
            assertThat(remaining.state()).isEqualTo("REJECTED");
            assertThat(remaining.lastError()).matches("(?s)^[A-Z][A-Z_]*: .+");
            assertThat(f.openSignals(e)).isZero();
            assertThat(f.fixture.instanceOf(e).status()).isEqualTo("COMPLETED");
        }
    }

    // --- Helpers ---

    static SignalFixture order() {
        return new SignalFixture(db.toPoolConfig(), new OrderSignalFixture(), List.of("paid", "shipped", "note"));
    }

    /** Cancels the instance in a caller transaction of its own and commits it. */
    static void cancel(SignalFixture f, Node node, WorkflowInstanceId id) {
        await(cancelAsync(f, node, id));
    }

    static Future<Void> cancelAsync(SignalFixture f, Node node, WorkflowInstanceId id) {
        return f.caller(() -> node.pool.withTransaction(tx -> node.engine.cancel(id, WHY, tx)));
    }

    /** A registry that holds a workflow of another name only, so that a node built over it cannot run the fixture. */
    private static DefaultWorkflowRegistry registryLackingTheFixture() {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(new OrderSignalFixture("cancel-other-order"));
        return registry;
    }

    /** The failure of the future, or {@code null} when it succeeded. */
    private static Throwable outcomeOf(Future<?> future) {
        try {
            await(future);
            return null;
        } catch (AssertionError failure) {
            if (failure.getCause() == null || failure.getCause() instanceof java.util.concurrent.TimeoutException) {
                throw failure;
            }
            return failure.getCause();
        }
    }

    /** Sets the instance row to the status as the segment that ended or failed it would have left it. */
    private static Future<Void> setStatus(SignalFixture f, WorkflowInstanceId id, String status) {
        return f.fixture
                .observer()
                .preparedQuery("UPDATE workflow_instances SET status = $2, version = version + 1, updated_at = now(),"
                        + " wait_type = NULL, wait_key = NULL, wait_aux_id = NULL, completed_at = now(),"
                        + " error_type = CASE WHEN $2 = 'FAILED' THEN 'DETERMINISTIC_FAILURE' END"
                        + " WHERE id = $1")
                .execute(Tuple.of(id.value(), status))
                .mapEmpty();
    }

    private static Versioned migrationVersion(long version) {
        return new Versioned("recorded-migration-order", version, wf -> {
            wf.initialStep("paid");
            waitFor(wf, "paid", "paid", "note-wait");
            waitFor(wf, "note-wait", "note", "done");
            wf.complete("done");
        });
    }

    /** Records a version of a fixture definition as available in the durable registry, without activating it. */
    private static void seedVersion(SignalFixture f, Definition code, List<String> signalNames) {
        f.registry.register(code);
        RuntimeWorkflow runtime = f.registry.codeDefinitions().stream()
                .filter(candidate -> candidate.plan().definitionId().equals(code.definitionId())
                        && candidate.plan().definitionVersion() == code.definitionVersion())
                .findFirst()
                .orElseThrow();
        Instant now = Instant.now();
        await(f.store.upsertCode(
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
                                .put("branches", new JsonObject()),
                        null),
                false));
    }

    /** Two read-committed sessions on the node's pool, each in a transaction of its own. */
    static final class Sessions implements AutoCloseable {
        final SqlConnection a;
        final SqlConnection b;
        private final Transaction aTx;
        private final Transaction bTx;
        private boolean aOpen;
        private boolean bOpen;

        private Sessions(Node node) {
            a = await(node.pool.getConnection());
            SqlConnection second = null;
            Transaction first = null;
            Transaction other = null;
            try {
                first = await(a.begin());
                aOpen = true;
                second = await(node.pool.getConnection());
                other = await(second.begin());
                bOpen = true;
            } catch (RuntimeException | AssertionError e) {
                if (aOpen) {
                    await(first.rollback());
                }
                if (second != null) {
                    await(second.close());
                }
                await(a.close());
                throw e;
            }
            aTx = first;
            b = second;
            bTx = other;
        }

        static Sessions open(Node node) {
            return new Sessions(node);
        }

        void commitA() {
            await(aTx.commit());
            aOpen = false;
        }

        void commitB() {
            await(bTx.commit());
            bOpen = false;
        }

        void rollbackB() {
            await(bTx.rollback());
            bOpen = false;
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

    /**
     * Holds the first call of a command-repository member that satisfies a condition until the test releases it. The
     * test learns when the call is held and when it has finished.
     */
    static final class HeldCall {
        private final String member;
        private final Predicate<Object[]> condition;
        private final java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        private final CompletableFuture<Void> held = new CompletableFuture<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private final AtomicReference<Promise<Void>> gate = new AtomicReference<>();
        private volatile boolean released;

        HeldCall(String member, Predicate<Object[]> condition) {
            this.member = member;
            this.condition = condition;
        }

        /** The next call that satisfies the condition is held. */
        void arm() {
            armed.set(true);
        }

        /** Waits until a call is held. */
        void awaitHeld() {
            try {
                held.get(IDLE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new AssertionError("no call of " + member + " was held", e);
            }
        }

        /** Waits until the held call has finished. */
        void awaitFinished() {
            try {
                finished.get(IDLE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new AssertionError("the held call of " + member + " did not finish", e);
            }
        }

        /** Lets the held call, and every later one, go on; harmless when none is held. */
        void release() {
            released = true;
            Promise<Void> promise = gate.get();
            if (promise != null) {
                promise.tryComplete();
            }
        }

        @SuppressWarnings("unchecked")
        UnaryOperator<WorkflowCommandRepository<SqlClient>> decorator() {
            return ExecutorFixture.intercepting(member, (call, method, arguments, real) -> {
                if (released || !condition.test(arguments) || !armed.compareAndSet(true, false)) {
                    return real.call();
                }
                Promise<Void> promise = ((ContextInternal) Vertx.currentContext()).promise();
                gate.set(promise);
                held.complete(null);
                if (released) {
                    promise.tryComplete();
                }
                return promise.future().compose(ignored -> {
                    try {
                        return ((Future<Object>) real.call()).onComplete(result -> finished.complete(null));
                    } catch (Exception e) {
                        finished.complete(null);
                        return Future.failedFuture(e);
                    }
                });
            });
        }
    }
}
