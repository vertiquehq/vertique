// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.history.CommandKind;
import dev.vertique.workflow.history.WorkflowHistoryEvent;
import dev.vertique.workflow.history.WorkflowHistoryEvent.CommandChanged;
import dev.vertique.workflow.history.WorkflowHistoryEvent.CommandDisposition;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedRecoveryIT.BreakableTimeout;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerFixture;
import dev.vertique.workflow.postgresql.history.RecordedHistory;
import dev.vertique.workflow.state.HistoryActor;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that the history written by the executor's own transactions is announced once: the failure disposition of a
 * segment that failed, a retry recorded in a caller's transaction and the segment that runs it, and the deferral of a
 * command this node cannot run. A listener that throws is bound next to the recording one.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
public class ExecutorHistoryAnnouncementIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_executor_history_announcement_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("a failed segment's disposition and the retry a caller records in its own transaction are announced")
    void failureDispositionAndCallerOwnedRetryAreAnnounced() {
        failAndRetry(false);
    }

    @Test
    @DisplayName("a retry the runner opens a transaction for is announced like one a caller records")
    void runnerOwnedRetryIsAnnounced() {
        failAndRetry(true);
    }

    private void failAndRetry(boolean runnerOwned) {
        BreakableTimeout breakable = new BreakableTimeout();
        RecordedHistory recorded = new RecordedHistory();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), breakable, List.of("approve", "finish"))) {
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine")
                    .singleSegments()
                    .historyListeners(Set.of(recorded, recorded.thrower()))
                    .build();

            // Given an instance whose timeout path failed its segment, so that the disposition failed the instance
            WorkflowInstanceId id = f.waiting(node, "announced-failure");
            timers.dueNow(timers.only(id, "TIMEOUT").id());
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");

            // When the path is fixed and the retry is recorded, then run by the executor
            breakable.broken.set(false);
            if (runnerOwned) {
                await(f.caller(() -> node.engine.retry(id)));
            } else {
                await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.retry(id, tx))));
            }
            node.runOneSegment();
            f.idle(node, id);
            awaitCondition("the announcements to catch up", IDLE, () -> announced(recorded, id)
                    .equals(stored(f, id)));

            // Then every entry is announced once and in order, with the failure and the retry among them
            List<WorkflowHistoryEvent> events = recorded.eventsOf(id);
            assertThat(announced(recorded, id)).isEqualTo(stored(f, id)).doesNotHaveDuplicates();
            WorkflowHistoryEvent failed = only(events, WorkflowHistoryEvent.InstanceFailed.class);
            WorkflowHistoryEvent retried = only(events, WorkflowHistoryEvent.InstanceRetried.class);

            // And the failure of a timer is the system's doing, for the timer command; the retry entry is written by
            // the segment that runs it, which records no command for it, and none is inferred
            assertThat(failed.entryInfo().commandKind()).isEqualTo(CommandKind.TIMER);
            assertThat(failed.entryInfo().actor().kind()).isEqualTo(HistoryActor.Kind.SYSTEM);
            assertThat(failed.entryInfo().actor().reason()).isEqualTo("timer");
            assertThat(retried.entryInfo().commandKind()).isNull();

            // And none that a timer wrote is without the system actor
            assertThat(events)
                    .filteredOn(event -> event.entryInfo().commandKind() == CommandKind.TIMER)
                    .isNotEmpty()
                    .allSatisfy(event ->
                            assertThat(event.entryInfo().actor().kind()).isEqualTo(HistoryActor.Kind.SYSTEM));

            // And nothing was reported as failed, since an internal retry is not, while the throwing listener changed
            // nothing
            assertThat(recorded.failures()).isEmpty();
            assertThat(recorded.thrown()).isEqualTo(recorded.commits().size());
        }
    }

    @Test
    @DisplayName("the dead-lettering of a command at its claim is announced with the failure of its instance")
    void claimTimeDeadLetterIsAnnounced() {
        RecordedHistory recorded = new RecordedHistory();
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given two attempts and a start whose every segment is abandoned
            CommandFaults faults = new CommandFaults();
            Node node = fixture.node("engine")
                    .leaseSeconds(5)
                    .maxAttempts(2)
                    .commands(faults.decorator())
                    .historyListeners(Set.of(recorded, recorded.thrower()))
                    .build();
            WorkflowInstanceId id = node.record(startOf("announced-dead-letter"));
            faults.crashEverySegmentOf(id);

            // When the executor runs until the command is dead-lettered
            node.startExecutor();
            awaitCondition("the start to be dead-lettered", Duration.ofSeconds(40), () -> fixture.commandOf(id)
                    .state()
                    .equals("DEAD"));
            awaitCondition("the announcements to catch up", IDLE, () -> announced(recorded, id)
                    .equals(stored(fixture, id)));
            node.stopExecutor();

            // Then the dead-lettering and the failure are announced, for the command, once and in order
            List<WorkflowHistoryEvent> events = recorded.eventsOf(id);
            assertThat(announced(recorded, id)).isEqualTo(stored(fixture, id)).doesNotHaveDuplicates();
            assertThat(events).anySatisfy(event -> assertThat(event)
                    .isInstanceOfSatisfying(CommandChanged.class, changed -> assertThat(changed.disposition())
                            .isEqualTo(CommandDisposition.DEAD_LETTERED)));
            WorkflowHistoryEvent failed = only(events, WorkflowHistoryEvent.InstanceFailed.class);
            assertThat(failed.entryInfo().commandKind()).isEqualTo(CommandKind.START);
            assertThat(recorded.failures()).isEmpty();
        }
    }

    @Test
    @DisplayName("the deferral of a command this node cannot run is announced once")
    void deferralIsAnnounced() {
        RecordedHistory recorded = new RecordedHistory();
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a start recorded on a node that holds the workflow, and a node without it that announces
            Node b = fixture.node("B").build();
            Node a = fixture.node("A")
                    .withRegistry(fixture.registryWithout(ExecutorFixture.SKELETON))
                    .pollIntervalMs(100)
                    .historyListeners(Set.of(recorded, recorded.thrower()))
                    .build();
            WorkflowInstanceId id = b.record(startOf("announced-deferral"));

            // When the node without the workflow peeks the command
            a.startExecutor();
            awaitCondition("A to defer the start", IDLE, () -> "DEFERRED"
                    .equals(fixture.commandOf(id).state()));
            awaitCondition("the deferral to be announced", IDLE, () -> !recorded.eventsOf(id)
                    .isEmpty());
            a.stopExecutor();

            // Then the deferral entry is announced once, and nothing else of the instance
            List<WorkflowHistoryEvent> events = recorded.eventsOf(id);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst())
                    .isInstanceOfSatisfying(CommandChanged.class, changed -> assertThat(changed.disposition())
                            .isEqualTo(CommandDisposition.DEFERRED));
            assertThat(announced(recorded, id))
                    .isEqualTo(List.of(await(fixture.observer()
                                    .preparedQuery("SELECT sequence FROM workflow_history WHERE workflow_id = $1"
                                            + " AND entry_type = 'COMMAND_DEFERRED'")
                                    .execute(Tuple.of(id.value())))
                            .iterator()
                            .next()
                            .getLong("sequence")));
            assertThat(recorded.failures()).isEmpty();
        }
    }

    // --- Helpers ---

    private static <E extends WorkflowHistoryEvent> E only(List<WorkflowHistoryEvent> events, Class<E> type) {
        List<E> matching =
                events.stream().filter(type::isInstance).map(type::cast).toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private static List<Long> announced(RecordedHistory recorded, WorkflowInstanceId id) {
        return recorded.eventsOf(id).stream()
                .map(event -> event.entryInfo().sequence())
                .toList();
    }

    private static List<Long> stored(SignalFixture f, WorkflowInstanceId id) {
        return stored(f.fixture, id);
    }

    /** The sequences of the instance's history, without the state baselines that are not announced. */
    private static List<Long> stored(ExecutorFixture fixture, WorkflowInstanceId id) {
        List<Long> sequences = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT sequence FROM workflow_history WHERE workflow_id = $1"
                        + " AND entry_type <> 'STATE_BASELINE' ORDER BY sequence")
                .execute(Tuple.of(id.value())))) {
            sequences.add(row.getLong("sequence"));
        }
        return sequences;
    }
}
