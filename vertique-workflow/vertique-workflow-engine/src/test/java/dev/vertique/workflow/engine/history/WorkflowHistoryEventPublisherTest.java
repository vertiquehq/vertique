// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private static final int PAGE = WorkflowHistoryEventPublisher.PAGE_SIZE;

    private final WorkflowHistoryRepository<SqlClient> history = mock(WorkflowHistoryRepository.class);
    private final SqlClient readClient = mock(SqlClient.class);
    private final SqlConnection tx = mock(SqlConnection.class);
    private final Transaction transaction = mock(Transaction.class);
    private final Promise<Void> completion = Promise.promise();

    private final List<WorkflowHistoryCommit> commits = new ArrayList<>();
    private final List<WorkflowHistoryFailure> failures = new ArrayList<>();
    private final AtomicInteger actorCaptures = new AtomicInteger();
    private final List<WorkflowInstanceId> locked = new ArrayList<>();

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
        return new WorkflowHistoryEventPublisher(
                set,
                history,
                readClient,
                (id, client) -> {
                    locked.add(id);
                    return Future.succeededFuture();
                },
                actor,
                Clock.fixed(NOW, ZoneOffset.UTC));
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
        when(history.listRange(ID, 2L, 4L, PAGE, readClient))
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
        verify(history, never()).listRange(any(), anyLong(), anyLong(), anyInt(), any());

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
        when(history.listRange(ID, 0L, 2L, PAGE, readClient))
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
        verify(history, never()).listRange(any(), anyLong(), anyLong(), anyInt(), any());
        assertThat(commits).isEmpty();
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("a throwing listener does not stop the others and does not reach the action")
    void listenerIsolation() {
        // Given a throwing listener and a recording one
        sequences(0, 1);
        when(history.listRange(ID, 0L, 1L, PAGE, readClient))
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
        when(history.listRange(ID, 0L, 1L, PAGE, readClient))
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
        when(history.listRange(ID, 0L, 1L, PAGE, readClient)).thenThrow(new IllegalStateException("broken"));
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
        verify(transaction, never()).completion();
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
        verify(history, never()).listRange(any(), anyLong(), anyLong(), anyInt(), any());
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
    @DisplayName("a failed sequence read is recovered: the work's own result is returned and nothing is announced")
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

    @Test
    @DisplayName("a call nested for the same instance in the same transaction only runs its work")
    void nestedCallForTheSameInstanceIsPassThrough() {
        // Given an outer call whose work calls the wrapper again for the same instance
        sequences(2, 4);
        when(history.listRange(ID, 2L, 4L, PAGE, readClient))
                .thenReturn(Future.succeededFuture(List.of(committed(3, WorkflowEntryType.START))));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);
        AtomicInteger captures = new AtomicInteger();

        // When the inner work fails and the outer work succeeds
        Future<String> result = publisher.around(Action.SIGNAL, ID, tx, () -> {
            Future<String> inner = publisher.around(Action.CANCEL, ID, tx, () -> {
                captures.incrementAndGet();
                return Future.failedFuture(new IllegalStateException("inner"));
            });
            return inner.recover(failure -> Future.succeededFuture("outer"));
        });
        completion.complete();

        // Then the inner call registered nothing and reported nothing, and the range is announced once
        assertThat(result.result()).isEqualTo("outer");
        assertThat(captures).hasValue(1);
        assertThat(actorCaptures).hasValue(1);
        assertThat(commits).hasSize(1);
        assertThat(failures).isEmpty();
        verify(history, times(2)).nextSequence(ID, tx);
        verify(history, times(1)).listRange(ID, 2L, 4L, PAGE, readClient);
    }

    @Test
    @DisplayName("a nested call for another instance registers on its own")
    void nestedCallForAnotherInstanceRegisters() {
        // Given an outer call for one instance and an inner call for another in the same transaction
        WorkflowInstanceId other = new WorkflowInstanceId(UUID.randomUUID());
        sequences(0, 0);
        when(history.nextSequence(other, tx)).thenReturn(Future.succeededFuture(1L), Future.succeededFuture(2L));
        when(history.listRange(other, 0L, 1L, PAGE, readClient))
                .thenReturn(Future.succeededFuture(List.of(new CommittedHistoryEntry(
                        new WorkflowHistoryEntry(other, 1, WorkflowEntryType.START, "{}", NOW), "START"))));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When both succeed and the transaction commits
        publisher.around(
                Action.SIGNAL,
                ID,
                tx,
                () -> publisher.around(Action.SIGNAL, other, tx, () -> {
                    return Future.succeededFuture("inner");
                }));
        completion.complete();

        // Then only the inner instance had entries, and they were announced for it
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).instanceId()).isEqualTo(other);
        verify(history, times(2)).nextSequence(other, tx);
    }

    @Test
    @DisplayName("adjacent ranges of one instance in one transaction are merged and read once")
    void sequentialCallsAreMerged() {
        // Given two calls for one instance in the same transaction, one after the other, writing (0,2] and (2,4]
        when(history.nextSequence(ID, tx))
                .thenReturn(
                        Future.succeededFuture(1L),
                        Future.succeededFuture(3L),
                        Future.succeededFuture(3L),
                        Future.succeededFuture(5L));
        when(history.listRange(ID, 0L, 4L, PAGE, readClient))
                .thenReturn(Future.succeededFuture(
                        List.of(committed(1, WorkflowEntryType.START), committed(3, WorkflowEntryType.COMPLETED))));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When both run and the transaction commits
        publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("one"));
        publisher.around(Action.CANCEL, ID, tx, () -> Future.succeededFuture("two"));
        completion.complete();

        // Then one settlement was registered and the merged range is announced once
        verify(transaction, times(1)).completion();
        verify(history, times(1)).listRange(any(), anyLong(), anyLong(), anyInt(), any());
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).events())
                .extracting(e -> e.entryInfo().sequence())
                .containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("sequences reused after a rollback to a savepoint are announced once")
    void reusedRangeAfterSavepointRollbackIsAnnouncedOnce() {
        // Given action A that wrote (10,12], a rollback to a savepoint that removed its rows, and action B that wrote
        // the same sequences again
        when(history.nextSequence(ID, tx))
                .thenReturn(
                        Future.succeededFuture(11L),
                        Future.succeededFuture(13L),
                        Future.succeededFuture(11L),
                        Future.succeededFuture(13L));
        when(history.listRange(ID, 10L, 12L, PAGE, readClient))
                .thenReturn(Future.succeededFuture(
                        List.of(committed(11, WorkflowEntryType.COMPLETED), committed(12, WorkflowEntryType.FAILED))));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When both actions succeed and the transaction commits
        publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("A"));
        publisher.around(Action.CANCEL, ID, tx, () -> Future.succeededFuture("B"));
        completion.complete();

        // Then the rows are read and delivered once
        verify(history, times(1)).listRange(ID, 10L, 12L, PAGE, readClient);
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).events())
                .extracting(e -> e.entryInfo().sequence())
                .containsExactly(11L, 12L);
    }

    @Test
    @DisplayName("ranges that neither overlap nor touch are read separately, ascending")
    void disjointRangesAreReadSeparately() {
        // Given two calls writing (0,2] and (5,7]
        when(history.nextSequence(ID, tx))
                .thenReturn(
                        Future.succeededFuture(1L),
                        Future.succeededFuture(3L),
                        Future.succeededFuture(6L),
                        Future.succeededFuture(8L));
        when(history.listRange(ID, 0L, 2L, PAGE, readClient))
                .thenReturn(Future.succeededFuture(List.of(committed(1, WorkflowEntryType.START))));
        when(history.listRange(ID, 5L, 7L, PAGE, readClient))
                .thenReturn(Future.succeededFuture(List.of(committed(6, WorkflowEntryType.COMPLETED))));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When both run and the transaction commits
        publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("one"));
        publisher.around(Action.CANCEL, ID, tx, () -> Future.succeededFuture("two"));
        completion.complete();

        // Then each range is announced
        assertThat(commits).hasSize(2);
        assertThat(commits)
                .extracting(c -> c.events().get(0).entryInfo().sequence())
                .containsExactly(1L, 6L);
    }

    @Test
    @DisplayName("every succeeded attempt of a transaction that does not commit is reported once")
    void rollbackReportsEveryAttempt() {
        // Given two succeeded actions in one transaction
        sequences(0, 1);
        when(history.nextSequence(ID, tx))
                .thenReturn(
                        Future.succeededFuture(1L),
                        Future.succeededFuture(2L),
                        Future.succeededFuture(2L),
                        Future.succeededFuture(3L));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);
        publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("one"));
        publisher.around(Action.CANCEL, ID, tx, () -> Future.succeededFuture("two"));

        // When the transaction rolls back
        completion.fail(new IllegalStateException("rolled back"));

        // Then each attempt is reported once and nothing is read
        assertThat(failures).hasSize(2);
        assertThat(failures).extracting(WorkflowHistoryFailure::action).containsExactly(Action.SIGNAL, Action.CANCEL);
        verify(history, never()).listRange(any(), anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("a range larger than one page is read page by page and delivered in order")
    void largeRangeIsReadInPages() {
        // Given a range of one full page and two more entries
        int total = PAGE + 2;
        sequences(0, total);
        List<CommittedHistoryEntry> first = new ArrayList<>();
        for (long sequence = 1; sequence <= PAGE; sequence++) {
            first.add(committed(sequence, WorkflowEntryType.COMPLETED));
        }
        when(history.listRange(ID, 0L, (long) total, PAGE, readClient)).thenReturn(Future.succeededFuture(first));
        when(history.listRange(ID, (long) PAGE, (long) total, PAGE, readClient))
                .thenReturn(Future.succeededFuture(List.of(
                        committed(PAGE + 1, WorkflowEntryType.COMPLETED),
                        committed(PAGE + 2, WorkflowEntryType.COMPLETED))));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When the action commits
        publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("ok"));
        completion.complete();

        // Then two consecutive commits carry the entries in sequence order
        assertThat(commits).hasSize(2);
        assertThat(commits.get(0).events()).hasSize(PAGE);
        assertThat(commits.get(1).events())
                .extracting(e -> e.entryInfo().sequence())
                .containsExactly((long) PAGE + 1, (long) PAGE + 2);
        verify(history, times(2)).listRange(any(), anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("a page that ends exactly at the range reads no further page")
    void fullLastPageEndsTheRange() {
        // Given a range that is exactly one page
        sequences(0, PAGE);
        List<CommittedHistoryEntry> only = new ArrayList<>();
        for (long sequence = 1; sequence <= PAGE; sequence++) {
            only.add(committed(sequence, WorkflowEntryType.COMPLETED));
        }
        when(history.listRange(ID, 0L, (long) PAGE, PAGE, readClient)).thenReturn(Future.succeededFuture(only));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When the action commits
        publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("ok"));
        completion.complete();

        // Then one page was read and delivered
        assertThat(commits).hasSize(1);
        verify(history, times(1)).listRange(any(), anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("a work that throws synchronously or fails frees the instance for a later call")
    void failedWorkLeavesTheRegistration() {
        // Given a call whose work throws synchronously, and one whose work fails
        sequences(0, 0);
        WorkflowHistoryEventPublisher publisher = publisher(recorder);
        Future<String> thrown = publisher.around(Action.SIGNAL, ID, tx, () -> {
            throw new IllegalStateException("sync");
        });
        assertThat(thrown.failed()).isTrue();

        // When a later call for the same instance runs
        AtomicInteger runs = new AtomicInteger();
        when(history.nextSequence(ID, tx)).thenReturn(Future.succeededFuture(1L));
        publisher.around(Action.SIGNAL, ID, tx, () -> {
            runs.incrementAndGet();
            return Future.failedFuture(new IllegalStateException("async"));
        });
        publisher.around(Action.SIGNAL, ID, tx, () -> Future.failedFuture(new IllegalStateException("again")));

        // Then each was its own attempt and was reported
        assertThat(runs).hasValue(1);
        assertThat(failures).hasSize(3);
    }

    @Test
    @DisplayName("a start that answers with an existing instance announces nothing")
    void replayedStartAnnouncesNothing() {
        // Given a start that found its instance already there
        when(history.nextSequence(ID, tx)).thenReturn(Future.succeededFuture(4L));
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When it succeeds and the transaction commits
        Future<WorkflowInstanceId> result =
                publisher.aroundCreating(Action.START, tx, () -> false, () -> Future.succeededFuture(ID));
        completion.complete();

        // Then the id is returned and no range is read
        assertThat(result.result()).isEqualTo(ID);
        assertThat(commits).isEmpty();
        verify(history, never()).listRange(any(), anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("around takes the execution lock before the range starts, and a work that holds it does not")
    void lockPrecedesTheRange() {
        // Given a listener
        sequences(2, 2);
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When an action runs through each wrapper
        publisher.around(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("a"));
        sequences(2, 2);
        publisher.aroundLocked(Action.SIGNAL, ID, tx, () -> Future.succeededFuture("b"));

        // Then only the first took the lock
        assertThat(locked).containsExactly(ID);
    }

    @Test
    @DisplayName("a commit-only wrapper announces commits, reports no failure and captures no actor")
    void commitOnlyReportsNoFailure() {
        // Given a commit-only wrapper whose work fails
        sequences(2, 2);
        WorkflowHistoryEventPublisher publisher = publisher(recorder);

        // When it runs and the transaction rolls back
        Future<String> result =
                publisher.aroundLocked(null, ID, tx, () -> Future.failedFuture(new IllegalStateException("segment")));
        completion.fail(new IllegalStateException("rolled back"));

        // Then the failure is the work's, and nothing is reported
        assertThat(result.failed()).isTrue();
        assertThat(failures).isEmpty();
        assertThat(actorCaptures).hasValue(0);
    }

    @Test
    @DisplayName("a failed lock is reported and fails the action without running the work")
    void failedLockIsReported() {
        // Given a lock that times out
        WorkflowInstanceId other = new WorkflowInstanceId(UUID.randomUUID());
        Set<WorkflowHistoryListener> set = Set.of(recorder);
        WorkflowHistoryEventPublisher publisher = new WorkflowHistoryEventPublisher(
                set,
                history,
                readClient,
                (id, client) -> Future.failedFuture(new IllegalStateException("lock timeout")),
                () -> new CommandActor(CommandActor.Kind.USER, "alice", null, null, CommandActor.Attribution.VERIFIED),
                Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicInteger runs = new AtomicInteger();

        // When the action runs
        Future<String> result = publisher.around(Action.CANCEL, other, tx, () -> {
            runs.incrementAndGet();
            return Future.succeededFuture("never");
        });

        // Then it failed with the lock's failure, reported once, and the work did not run
        assertThat(result.failed()).isTrue();
        assertThat(runs).hasValue(0);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).action()).isEqualTo(Action.CANCEL);
        assertThat(failures.get(0).instanceId()).isEqualTo(other);
    }
}
