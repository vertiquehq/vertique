// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.engine.spi.CommandActor;
import dev.vertique.workflow.engine.spi.CommittedHistoryEntry;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.history.HistoryAttribution;
import dev.vertique.workflow.history.WorkflowHistoryCommit;
import dev.vertique.workflow.history.WorkflowHistoryEvent;
import dev.vertique.workflow.history.WorkflowHistoryFailure;
import dev.vertique.workflow.history.WorkflowHistoryFailure.Action;
import dev.vertique.workflow.history.WorkflowHistoryListener;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.HistoryActor;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies when and how the publisher tells listeners about committed history and failed actions. */
@SuppressWarnings("unchecked")
class WorkflowHistoryEventPublisherTest {

    private static final WorkflowInstanceId ID = new WorkflowInstanceId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");

    private final WorkflowHistoryRepository<SqlClient> history = mock(WorkflowHistoryRepository.class);
    private final SqlClient readClient = mock(SqlClient.class);
    private final SqlConnection tx = mock(SqlConnection.class);
    private final Transaction transaction = mock(Transaction.class);
    private final Promise<Void> completion = Promise.promise();

    private final List<WorkflowHistoryCommit> commits = new ArrayList<>();
    private final List<WorkflowHistoryFailure> failures = new ArrayList<>();
    private final AtomicInteger actorCaptures = new AtomicInteger();

    /** Records what it receives. */
    private final WorkflowHistoryListener recorder = new WorkflowHistoryListener() {
        @Override
        public void onHistoryCommitted(WorkflowHistoryCommit commit) {
            commits.add(commit);
        }

        @Override
        public void onHistoryFailed(WorkflowHistoryFailure failure) {
            failures.add(failure);
        }
    };

    /** Throws from both callbacks. */
    private final WorkflowHistoryListener thrower = new WorkflowHistoryListener() {
        @Override
        public void onHistoryCommitted(WorkflowHistoryCommit commit) {
            throw new IllegalStateException("listener broke");
        }

        @Override
        public void onHistoryFailed(WorkflowHistoryFailure failure) {
            throw new IllegalStateException("listener broke");
        }
    };

    @BeforeEach
    void connection() {
        when(tx.transaction()).thenReturn(transaction);
        when(transaction.completion()).thenReturn(completion.future());
    }

    private WorkflowHistoryEventPublisher publisher(WorkflowHistoryListener... listeners) {
        return publisher(
                () -> {
                    actorCaptures.incrementAndGet();
                    return new CommandActor(
                            CommandActor.Kind.USER, "alice", null, "bob", CommandActor.Attribution.VERIFIED);
                },
                listeners);
    }

    private WorkflowHistoryEventPublisher publisher(
            java.util.function.Supplier<CommandActor> actor, WorkflowHistoryListener... listeners) {
        Set<WorkflowHistoryListener> set = new LinkedHashSet<>(List.of(listeners));
        return new WorkflowHistoryEventPublisher(set, history, readClient, actor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CommittedHistoryEntry committed(long sequence, WorkflowEntryType type) {
        return new CommittedHistoryEntry(
                new WorkflowHistoryEntry(ID, sequence, type, "{}", NOW),
                type == WorkflowEntryType.START ? "START" : null);
    }

    private void sequences(long before, long afterWork) {
        when(history.nextSequence(ID, tx))
                .thenReturn(Future.succeededFuture(before + 1), Future.succeededFuture(afterWork + 1));
    }

    @Test
    @DisplayName("with no listener the wrappers only call the work: no statement, no actor capture")
    void passesThroughWithoutListeners() {
        // Given no listener
        WorkflowHistoryEventPublisher publisher = publisher();
        AtomicInteger calls = new AtomicInteger();

        // When both wrappers run their work
        Future<String> around = publisher.around(Action.SIGNAL, ID, tx, () -> {
            calls.incrementAndGet();
            return Future.succeededFuture("done");
        });
        Future<WorkflowInstanceId> creating = publisher.aroundCreating(Action.START, tx, () -> {
            calls.incrementAndGet();
            return Future.succeededFuture(ID);
        });

        // Then the work ran, nothing else did, and the results are the work's
        assertThat(publisher.hasListeners()).isFalse();
        assertThat(calls).hasValue(2);
        assertThat(around.result()).isEqualTo("done");
        assertThat(creating.result()).isEqualTo(ID);
        assertThat(actorCaptures).hasValue(0);
        verifyNoInteractions(history, tx, readClient);
    }

    @Test
    @DisplayName("a commit delivers one commit with the mapped events in order, after the commit only")
    void deliversCommittedEventsInOrder() {
        // Given a listener and a range of three entries, one of which is a baseline
        sequences(2, 4);
        when(history.listRange(ID, 2L, 4L, readClient))
                .thenReturn(Future.succeededFuture(List.of(
                        committed(3, WorkflowEntryType.START),
                        committed(4, WorkflowEntryType.STATE_BASELINE),
                        committed(5, WorkflowEntryType.COMPLETED))));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When the work succeeds
        Future<String> result = publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("ok"));

        // Then the result is the work's and nothing is read or delivered before the commit
        assertThat(result.result()).isEqualTo("ok");
        assertThat(commits).isEmpty();
        verify(history, never()).listRange(any(), anyLong(), anyLong(), any());

        // When the transaction commits
        completion.complete();

        // Then one commit holds the events in sequence order without the baseline
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).instanceId()).isEqualTo(ID);
        assertThat(commits.get(0).events())
                .extracting(e -> e.entryInfo().sequence())
                .containsExactly(3L, 5L);
        assertThat(commits.get(0).events().get(0)).isInstanceOf(WorkflowHistoryEvent.InstanceStarted.class);
        assertThat(commits.get(0).events().get(0).entryInfo().commandKind())
                .isEqualTo(dev.vertique.workflow.history.CommandKind.START);
        assertThat(failures).isEmpty();
        // and the only statements inside the transaction were the two sequence reads
        verify(history, times(2)).nextSequence(ID, tx);
    }

    @Test
    @DisplayName("a start reads the range from sequence 0 for the id the work yields")
    void creatingReadsFromZero() {
        // Given a start that writes entries 1 and 2
        when(history.nextSequence(ID, tx)).thenReturn(Future.succeededFuture(3L));
        when(history.listRange(ID, 0L, 2L, readClient))
                .thenReturn(Future.succeededFuture(List.of(
                        committed(1, WorkflowEntryType.START), committed(2, WorkflowEntryType.COMMAND_RECORDED))));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When the start succeeds and commits
        Future<WorkflowInstanceId> result =
                publisher.aroundCreating(Action.START, tx, () -> Future.succeededFuture(ID));
        completion.complete();

        // Then the work's id is returned and its entries are delivered
        assertThat(result.result()).isEqualTo(ID);
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).events()).hasSize(2);
        verify(history, times(1)).nextSequence(ID, tx);
    }

    @Test
    @DisplayName("work that wrote no entry delivers nothing and reads no range")
    void noEntriesNoDelivery() {
        // Given work that leaves the highest sequence unchanged
        sequences(2, 2);
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When it succeeds and the transaction commits
        publisher.around(Action.CANCEL, ID, tx, () -> Future.succeededFuture("ok"));
        completion.complete();

        // Then nothing is read or delivered
        verify(history, never()).listRange(any(), anyLong(), anyLong(), any());
        assertThat(commits).isEmpty();
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("a throwing listener does not stop the others and does not reach the action")
    void listenerIsolation() {
        // Given a throwing listener and a recording one
        sequences(0, 1);
        when(history.listRange(ID, 0L, 1L, readClient))
                .thenReturn(Future.succeededFuture(List.of(committed(1, WorkflowEntryType.START))));
        WorkflowHistoryEventPublisher publisher = publisher(thrower, recorder);

        // When the action commits
        Future<String> result = publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("ok"));
        completion.complete();

        // Then the recording listener still got the commit and the action succeeded
        assertThat(result.succeeded()).isTrue();
        assertThat(commits).hasSize(1);
    }

    @Test
    @DisplayName("a failed or throwing range read is swallowed")
    void readFailureIsSwallowed() {
        // Given a range read that fails
        sequences(0, 1);
        when(history.listRange(ID, 0L, 1L, readClient))
                .thenReturn(Future.failedFuture(new RuntimeException("db down")));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When the action commits
        Future<String> result = publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("ok"));
        completion.complete();

        // Then no commit is delivered and no failure is reported
        assertThat(result.succeeded()).isTrue();
        assertThat(commits).isEmpty();
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("a read that throws instead of failing is swallowed too")
    void readThrowIsSwallowed() {
        sequences(0, 1);
        when(history.listRange(ID, 0L, 1L, readClient)).thenThrow(new IllegalStateException("broken"));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("ok"));
        completion.complete();

        assertThat(commits).isEmpty();
    }

    @Test
    @DisplayName("a failing work is reported once and its failure propagates unchanged")
    void workFailurePropagatesAndIsReported() {
        // Given a work that fails
        when(history.nextSequence(ID, tx)).thenReturn(Future.succeededFuture(1L));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);
        IllegalArgumentException boom = new IllegalArgumentException("secret detail");

        // When it runs
        Future<String> result = publisher.around(Action.TASK_COMPLETE, ID, tx, () -> Future.failedFuture(boom));

        // Then the very same failure comes back and one failure is reported without the message
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(boom);
        assertThat(failures).hasSize(1);
        WorkflowHistoryFailure failure = failures.get(0);
        assertThat(failure.action()).isEqualTo(Action.TASK_COMPLETE);
        assertThat(failure.instanceId()).isEqualTo(ID);
        assertThat(failure.errorType()).isEqualTo("IllegalArgumentException");
        assertThat(failure.occurredAt()).isEqualTo(NOW);
        assertThat(failure.actor()).isEqualTo(new HistoryActor(HistoryActor.Kind.USER, "alice", null));
        assertThat(failure.onBehalfOf()).isEqualTo("bob");
        assertThat(failure.attribution()).isEqualTo(HistoryAttribution.VERIFIED);
        // and no settlement is registered for a failed work
        verify(tx, never()).transaction();
    }

    @Test
    @DisplayName("a work that throws synchronously is reported and rethrown")
    void synchronousThrowIsReported() {
        WorkflowHistoryEventPublisher publisher = publisher(recorder);
        IllegalStateException boom = new IllegalStateException("x");

        assertThatThrownBy(() -> publisher.aroundCreating(Action.START, tx, () -> {
                    throw boom;
                }))
                .isSameAs(boom);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).instanceId()).isNull();
        assertThat(failures.get(0).errorType()).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("a rolled-back transaction after a successful work is reported with the settlement's error type")
    void settlementFailureIsReported() {
        // Given a work that succeeded
        sequences(0, 1);
        WorkflowHistoryEventPublisher publisher = publisher(recorder);
        publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("ok"));

        // When the transaction rolls back
        completion.fail(new IllegalStateException("rolled back"));

        // Then one failure is reported with the settlement's type and nothing is delivered as committed
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).errorType()).isEqualTo("IllegalStateException");
        assertThat(commits).isEmpty();
        verify(history, never()).listRange(any(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("a failure is reported exactly once however many signals arrive, and the work's type wins")
    void failureReportedOnceWithErrorTypePrecedence() {
        // Given an attempt
        WorkflowHistoryEventPublisher publisher = publisher(recorder);
        WorkflowHistoryEventPublisher.Attempt attempt = publisher.attempt(Action.RETRY, ID);

        // When both the work failure and the settlement failure arrive
        publisher.reportFailure(attempt, new UnsupportedOperationException(), new IllegalStateException());
        publisher.reportFailure(attempt, null, new IllegalStateException());

        // Then one report exists and it names the work's failure
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).errorType()).isEqualTo("UnsupportedOperationException");

        // When only the settlement failure arrives
        publisher.reportFailure(publisher.attempt(Action.RETRY, ID), null, new IllegalStateException());
        // Then it names the settlement's failure
        assertThat(failures.get(1).errorType()).isEqualTo("IllegalStateException");

        // When neither arrives
        publisher.reportFailure(publisher.attempt(Action.RETRY, ID), null, null);
        // Then the error type is null
        assertThat(failures.get(2).errorType()).isNull();
        assertThat(failures.stream().map(WorkflowHistoryFailure::attemptId).distinct())
                .hasSize(3);
    }

    @Test
    @DisplayName("a throwing listener does not stop the failure from reaching the others")
    void failureListenerIsolation() {
        WorkflowHistoryEventPublisher publisher = publisher(thrower, recorder);

        publisher.reportFailure(publisher.attempt(Action.CANCEL, ID), new RuntimeException(), null);

        assertThat(failures).hasSize(1);
    }

    @Test
    @DisplayName("an actor that cannot be captured falls back to no actor and the action still runs and reports")
    void actorCaptureFailureFallsBack() {
        // Given an actor capture that throws
        when(history.nextSequence(ID, tx)).thenReturn(Future.succeededFuture(1L));
        WorkflowHistoryEventPublisher publisher = publisher(
                () -> {
                    throw new IllegalStateException("mapper broke");
                },
                recorder);

        // When the work fails
        Future<String> result =
                publisher.around(Action.SIGNAL, ID, tx, () -> Future.failedFuture(new RuntimeException("no")));

        // Then the action's failure is unchanged and reported with the unattributed actor
        assertThat(result.failed()).isTrue();
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).actor()).isEqualTo(new HistoryActor(HistoryActor.Kind.NONE, null, null));
        assertThat(failures.get(0).attribution()).isEqualTo(HistoryAttribution.ABSENT);
        assertThat(failures.get(0).onBehalfOf()).isNull();
    }

    @Test
    @DisplayName("a transaction that cannot signal is logged and does not alter the action's result")
    void unsupportedTransactionDoesNotAlterResult() {
        // Given a client that is not a connection, so the commit signal refuses it
        SqlClient notAConnection = mock(SqlClient.class);
        when(history.nextSequence(ID, notAConnection))
                .thenReturn(Future.succeededFuture(1L), Future.succeededFuture(2L));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When the work succeeds
        Future<String> result = publisher.around(Action.SIGNAL, ID, notAConnection, () -> Future.succeededFuture("ok"));

        // Then the result is the work's, unchanged
        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo("ok");
        assertThat(commits).isEmpty();
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("a failed sequence read does not fail the action")
    void sequenceReadFailureDoesNotFailTheAction() {
        when(history.nextSequence(ID, tx)).thenReturn(Future.failedFuture(new RuntimeException("read")));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        Future<String> result = publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("ok"));
        completion.complete();

        assertThat(result.result()).isEqualTo("ok");
        assertThat(commits).isEmpty();
    }

    @Test
    @DisplayName("deliverCommit maps entries and delivers them without a database")
    void deliverCommitSeam() {
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        publisher.deliverCommit(ID, List.of(committed(1, WorkflowEntryType.START)));
        publisher.deliverCommit(ID, List.of(committed(2, WorkflowEntryType.STATE_BASELINE)));
        publisher.deliverCommit(ID, List.of());

        assertThat(commits).hasSize(1);
        assertThatThrownBy(() -> new WorkflowHistoryCommit(ID, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
