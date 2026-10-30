// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import dev.vertique.db.TransactionCompletion;
import dev.vertique.workflow.engine.executor.CommandActors;
import dev.vertique.workflow.engine.spi.CommandActor;
import dev.vertique.workflow.engine.spi.CommittedHistoryEntry;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.history.HistoryAttribution;
import dev.vertique.workflow.history.WorkflowHistoryChange;
import dev.vertique.workflow.history.WorkflowHistoryEvent;
import dev.vertique.workflow.history.WorkflowHistoryFailure;
import dev.vertique.workflow.history.WorkflowHistoryListener;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.HistoryActor;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: tells the {@link WorkflowHistoryListener}s what an action wrote to the history of an instance, after its
 * transaction commits, and that an action failed.
 *
 * <p>A site wraps the work it runs in a transaction with {@link #around} (an action on a known instance) or
 * {@link #aroundCreating} (a start). Inside the transaction the publisher runs only two cheap reads of the instance's
 * highest history sequence, before and after the work (a start reads it once, after). The transaction keeps one settlement registration and the
 * ranges its actions wrote, per instance; when it commits, overlapping or adjacent ranges of an instance are merged, so
 * a sequence is read, and delivered, at most once even when a rollback to a savepoint made a later action reuse the
 * sequences of an earlier one. Each range is then read on a pooled connection in pages, mapped to events and handed to
 * every listener as {@link WorkflowHistoryChange}s in sequence order; a range larger than one page arrives as several
 * consecutive changes. Two adjacent actions on an instance give one change. A failure of the read, of the mapping or
 * of a listener is logged and never reaches the action: a listener {@link Exception} is logged with its stack, the
 * others by exception class only, and an {@link Error} is not caught.
 *
 * <p>An action that fails, or whose transaction does not commit, is reported once to
 * {@link WorkflowHistoryListener#onHistoryFailed} through whichever signal arrives first: the work's own failure or
 * the transaction's settlement failure. The report is made on the thread where that signal was observed, and once per
 * attempt even if the caller later recovers and commits the rest of the transaction.
 *
 * <p>A public method that runs in a transaction can be reached from inside another wrapped site of the same
 * transaction. The inner call for an instance whose wrapped work is still running only calls its work: the outer call
 * announces the entries. Calls that follow one another, or that concern other instances, record their own ranges.
 *
 * <p>With no listener bound the wrappers only call the work: no statement runs and no actor is captured. The result of
 * the work, its failure included, is always returned unchanged, with one residual exception. The two sequence reads run
 * as statements of the action's own transaction, and a failed read is recovered here, but a statement that fails inside
 * a PostgreSQL transaction (a statement timeout, a serialization failure under {@code SERIALIZABLE}, a lost connection)
 * leaves that transaction aborted whether or not the failure is recovered: the action's next statement then fails, or
 * the commit becomes a rollback, exactly as if one of the action's own statements had failed. This can only happen
 * while a listener is bound.
 *
 * <p>Commits of one instance are read on independent pooled connections, so two successive commits can reach the
 * listeners out of order; every event carries its {@code sequence} for a consumer that needs the order.
 */
@Singleton
public final class WorkflowHistoryEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowHistoryEventPublisher.class);

    /** The most entries one read of a committed range returns; a larger range is read in several pages. */
    static final int PAGE_SIZE = 1000;

    private final List<WorkflowHistoryListener> listeners;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final SqlClient readClient;
    private final Supplier<CommandActor> actors;
    private final Clock clock;
    private final BiFunction<WorkflowInstanceId, SqlClient, Future<Void>> lock;

    /**
     * What one transaction has announced so far, by the transaction it runs in. The key is the connection's
     * {@link Transaction} object, which does not override {@code equals}, so the lookup is by identity; the entry of a
     * transaction that is dropped goes with it. All access is synchronized on the map.
     */
    private final Map<Transaction, TransactionState> transactions = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Creates the publisher.
     *
     * @param listeners the listeners to notify; empty when the application binds none
     * @param history   the history repository
     * @param pool      the pool the committed range is read from, after the commit
     * @param commands  the command repository whose execution lock an action takes before its range starts
     * @param actors    the capture of the actor of the current caller
     */
    @Inject
    public WorkflowHistoryEventPublisher(
            Set<WorkflowHistoryListener> listeners,
            WorkflowHistoryRepository<SqlClient> history,
            Pool pool,
            WorkflowCommandRepository<SqlClient> commands,
            CommandActors actors) {
        this(listeners, history, pool, commands::lockInstance, actors::capture, Clock.systemUTC());
    }

    /**
     * Returns a publisher with no listener, which only calls the work it is given. For assemblies that do not take part
     * in history announcement.
     *
     * @return a publisher that never reads, captures or reports anything
     */
    public static WorkflowHistoryEventPublisher disabled() {
        return new WorkflowHistoryEventPublisher(
                Set.of(),
                null,
                null,
                (id, tx) -> Future.succeededFuture(),
                () -> {
                    throw new IllegalStateException("no listener is bound");
                },
                Clock.systemUTC());
    }

    WorkflowHistoryEventPublisher(
            Set<WorkflowHistoryListener> listeners,
            WorkflowHistoryRepository<SqlClient> history,
            SqlClient readClient,
            BiFunction<WorkflowInstanceId, SqlClient, Future<Void>> lock,
            Supplier<CommandActor> actors,
            Clock clock) {
        this.lock = lock;
        this.listeners = List.copyOf(listeners);
        this.history = history;
        this.readClient = readClient;
        this.actors = actors;
        this.clock = clock;
    }

    /**
     * Returns whether any listener is bound.
     *
     * @return true when at least one listener is bound
     */
    public boolean hasListeners() {
        return !listeners.isEmpty();
    }

    /**
     * Runs the work of an action on a known instance and reports what it wrote once its transaction commits. The
     * execution lock of the instance is taken first, before the range starts, so no other transaction can write
     * entries inside it; the work takes it again at no cost. The cost is that a call which would have returned without
     * writing, such as a duplicate signal, waits for the lock when a listener is bound.
     *
     * @param action the caller-driven action, for the failure report
     * @param id     the instance the action concerns, or {@code null} when it never resolved one (only a failure is
     *               then reported)
     * @param tx     the transaction the work runs in
     * @param work   the work
     * @param <T>    the result of the work
     * @return the future of the work, unchanged
     */
    public <T> Future<T> around(
            WorkflowHistoryFailure.Action action,
            @Nullable WorkflowInstanceId id,
            SqlClient tx,
            Supplier<Future<T>> work) {
        return run(action, id, tx, true, work);
    }

    /**
     * Runs work that already holds the execution lock of the instance, and reports what it wrote once its transaction
     * commits.
     *
     * @param action the caller-driven action to report when the work fails, or {@code null} to only announce commits
     * @param id     the instance the work concerns
     * @param tx     the transaction the work runs in, which holds the instance's execution lock
     * @param work   the work
     * @param <T>    the result of the work
     * @return the future of the work, unchanged
     */
    public <T> Future<T> aroundLocked(
            WorkflowHistoryFailure.Action action, WorkflowInstanceId id, SqlClient tx, Supplier<Future<T>> work) {
        return run(action, id, tx, false, work);
    }

    private <T> Future<T> run(
            @Nullable WorkflowHistoryFailure.Action action,
            @Nullable WorkflowInstanceId id,
            SqlClient tx,
            boolean takeLock,
            Supplier<Future<T>> work) {
        if (!hasListeners()) {
            return work.get();
        }
        Transaction transaction = transactionOf(tx);
        if (id != null && !enter(transaction, id)) {
            return work.get();
        }
        Future<T> announced;
        try {
            Attempt attempt = attempt(action, id);
            Future<Void> locked =
                    takeLock && id != null ? invoke(attempt, () -> lock.apply(id, tx)) : Future.succeededFuture();
            announced = locked.compose(ignored -> highestSequence(id, tx).compose(after -> invoke(attempt, work)
                    .compose(result -> settle(attempt, after, tx).map(settled -> result))));
        } catch (RuntimeException | Error thrown) {
            leave(transaction, id);
            throw thrown;
        }
        return leaving(announced, transaction, id);
    }

    /**
     * Runs the work of a start, which yields the id of the instance it created, and reports what it wrote once its
     * transaction commits.
     *
     * @param action the caller-driven action, for the failure report
     * @param tx     the transaction the work runs in
     * @param work   the work, yielding the id of the new instance
     * @return the future of the work, unchanged
     */
    public Future<WorkflowInstanceId> aroundCreating(
            WorkflowHistoryFailure.Action action, SqlClient tx, Supplier<Future<WorkflowInstanceId>> work) {
        return aroundCreating(action, tx, () -> true, work);
    }

    /**
     * Runs the work of a start like {@link #aroundCreating(WorkflowHistoryFailure.Action, SqlClient, Supplier)}, for a
     * start that may answer with an instance that already existed, whose history the start did not write.
     *
     * @param action     the caller-driven action, for the failure report
     * @param tx         the transaction the work runs in
     * @param createdNow tells, once the work succeeded, whether it created the instance; when it did not, nothing is
     *                   announced, and only a failure is reported
     * @param work       the work, yielding the id of the instance
     * @return the future of the work, unchanged
     */
    public Future<WorkflowInstanceId> aroundCreating(
            WorkflowHistoryFailure.Action action,
            SqlClient tx,
            BooleanSupplier createdNow,
            Supplier<Future<WorkflowInstanceId>> work) {
        if (!hasListeners()) {
            return work.get();
        }
        Attempt attempt = attempt(action, null);
        return invoke(attempt, work).compose(created -> {
            attempt.instanceId = created;
            return settle(attempt, createdNow.getAsBoolean() ? 0L : null, tx).map(settled -> created);
        });
    }

    // --- Nested calls ---

    private static @Nullable Transaction transactionOf(SqlClient tx) {
        return tx instanceof SqlConnection connection ? connection.transaction() : null;
    }

    /** Marks the instance as being wrapped in the transaction; false when it already is, so the call is nested. */
    private boolean enter(@Nullable Transaction transaction, WorkflowInstanceId id) {
        if (transaction == null) {
            return true;
        }
        synchronized (transactions) {
            return transactions
                    .computeIfAbsent(transaction, key -> new TransactionState())
                    .running
                    .add(id);
        }
    }

    private void leave(@Nullable Transaction transaction, @Nullable WorkflowInstanceId id) {
        if (transaction == null || id == null) {
            return;
        }
        synchronized (transactions) {
            TransactionState state = transactions.get(transaction);
            if (state != null && state.running.remove(id) && state.idle()) {
                transactions.remove(transaction);
            }
        }
    }

    private <T> Future<T> leaving(
            Future<T> announced, @Nullable Transaction transaction, @Nullable WorkflowInstanceId id) {
        if (transaction != null && id != null) {
            announced.onComplete(done -> leave(transaction, id));
        }
        return announced;
    }

    // --- In the transaction ---

    /**
     * The highest sequence of the instance now, or null when there is no instance or the read failed. A failed read is
     * logged and the range is then not announced; the action goes on, with the residual risk described on the class.
     */
    private Future<Long> highestSequence(@Nullable WorkflowInstanceId id, SqlClient tx) {
        if (id == null) {
            return Future.succeededFuture(null);
        }
        try {
            return history.nextSequence(id, tx).<Long>map(next -> next - 1).recover(failure -> {
                logSequenceReadFailure(id, failure);
                return Future.succeededFuture(null);
            });
        } catch (RuntimeException failure) {
            logSequenceReadFailure(id, failure);
            return Future.succeededFuture(null);
        }
    }

    private static void logSequenceReadFailure(WorkflowInstanceId id, Throwable failure) {
        log.warn(
                "History sequence of instance {} could not be read ({}); its entries are not announced",
                id.value(),
                failure.getClass().getName());
    }

    private <T> Future<T> invoke(Attempt attempt, Supplier<Future<T>> work) {
        Future<T> future;
        try {
            future = work.get();
        } catch (RuntimeException | Error thrown) {
            reportFailure(attempt, thrown, null);
            throw thrown;
        }
        return future.onFailure(failure -> reportFailure(attempt, failure, null));
    }

    /**
     * Records the range the work wrote and, for the first success of the transaction, registers its settlement: the
     * ranges are read when the transaction commits, and every succeeded attempt that did not commit is reported. The
     * returned future always succeeds, once the registration is made. A replayed start ({@code after == null}) has no
     * range, so the upper bound is not read, but it is still registered for the failure report.
     */
    private Future<Void> settle(Attempt attempt, @Nullable Long after, SqlClient tx) {
        WorkflowInstanceId id = attempt.instanceId;
        Future<Long> upTo = after == null ? Future.succeededFuture(null) : highestSequence(id, tx);
        return upTo.<Void>map(high -> {
            register(attempt, after, high, tx);
            return null;
        });
    }

    private void register(Attempt attempt, @Nullable Long after, @Nullable Long upTo, SqlClient tx) {
        WorkflowInstanceId id = attempt.instanceId;
        Transaction transaction = transactionOf(tx);
        synchronized (transactions) {
            TransactionState state = transaction == null
                    ? new TransactionState()
                    : transactions.computeIfAbsent(transaction, key -> new TransactionState());
            if (!state.registered) {
                try {
                    TransactionCompletion.onSettled(
                            tx,
                            () -> committed(transaction, state),
                            failure -> settlementFailed(transaction, state, failure));
                    state.registered = true;
                } catch (IllegalStateException cannotSignal) {
                    log.error(
                            "History events of instance {} are not announced: the transaction cannot signal its"
                                    + " outcome",
                            id == null ? null : id.value(),
                            cannotSignal);
                    if (transaction != null && state.idle()) {
                        transactions.remove(transaction);
                    }
                    return;
                }
            }
            state.attempts.add(attempt);
            if (after != null && upTo != null && upTo > after && id != null) {
                state.ranges.computeIfAbsent(id, key -> new ArrayList<>()).add(new long[] {after, upTo});
            }
        }
    }

    // --- After the commit ---

    private void committed(@Nullable Transaction transaction, TransactionState state) {
        Map<WorkflowInstanceId, List<long[]>> ranges;
        synchronized (transactions) {
            ranges = new LinkedHashMap<>(state.ranges);
            forget(transaction, state);
        }
        ranges.forEach((id, written) -> merge(written).forEach(range -> collectAndDeliver(id, range[0], range[1])));
    }

    private void settlementFailed(@Nullable Transaction transaction, TransactionState state, Throwable failure) {
        List<Attempt> attempts;
        synchronized (transactions) {
            attempts = List.copyOf(state.attempts);
            forget(transaction, state);
        }
        attempts.forEach(attempt -> reportFailure(attempt, null, failure));
    }

    /** Drops the state of a transaction that settled; the caller holds the lock on {@link #transactions}. */
    private void forget(@Nullable Transaction transaction, TransactionState state) {
        state.registered = false;
        state.attempts.clear();
        state.ranges.clear();
        if (transaction != null && state.idle()) {
            transactions.remove(transaction);
        }
    }

    /**
     * Merges overlapping and adjacent ranges {@code (after, upTo]} into the fewest ranges that cover them, ascending. A
     * rollback to a savepoint lets a later action write the sequences of an earlier one, so ranges can overlap.
     */
    static List<long[]> merge(List<long[]> ranges) {
        List<long[]> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingLong((long[] range) -> range[0]).thenComparingLong(range -> range[1]));
        List<long[]> merged = new ArrayList<>();
        for (long[] range : sorted) {
            long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && range[0] <= last[1]) {
                last[1] = Math.max(last[1], range[1]);
            } else {
                merged.add(new long[] {range[0], range[1]});
            }
        }
        return merged;
    }

    /** Reads the range page by page and hands every page to the listeners; a page is delivered before the next is read. */
    private void collectAndDeliver(WorkflowInstanceId id, long after, long upTo) {
        try {
            history.listRange(id, after, upTo, PAGE_SIZE, readClient).onComplete(read -> {
                try {
                    if (read.failed()) {
                        log.error(
                                "History events of instance {} could not be read ({})",
                                id.value(),
                                read.cause().getClass().getName());
                        return;
                    }
                    List<CommittedHistoryEntry> page = read.result();
                    deliverCommit(id, page);
                    if (page.size() >= PAGE_SIZE) {
                        long last = page.get(page.size() - 1).entry().sequence();
                        if (last < upTo) {
                            collectAndDeliver(id, last, upTo);
                        }
                    }
                } catch (RuntimeException failure) {
                    log.error(
                            "History events of instance {} could not be announced ({})",
                            id.value(),
                            failure.getClass().getName());
                }
            });
        } catch (RuntimeException failure) {
            log.error(
                    "History events of instance {} could not be read ({})",
                    id.value(),
                    failure.getClass().getName());
        }
    }

    /**
     * Hands the committed entries of an instance to every listener, each in isolation. Nothing is delivered for an
     * empty list.
     */
    void deliverCommit(WorkflowInstanceId id, List<CommittedHistoryEntry> committed) {
        List<WorkflowHistoryEvent> events = committed.stream()
                .map(HistoryEventMapper::map)
                .flatMap(Optional::stream)
                .toList();
        deliverEvents(id, events);
    }

    private void deliverEvents(WorkflowInstanceId id, List<WorkflowHistoryEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        WorkflowHistoryChange change = new WorkflowHistoryChange(id, events);
        ListenerDispatch.forEach(
                listeners,
                listener -> listener.onHistoryChanged(change),
                (listener, thrown) -> log.warn(
                        "History listener {} failed for instance {}",
                        listener.getClass().getName(),
                        id.value(),
                        thrown));
    }

    // --- Failures ---

    /**
     * Reports the attempt as failed, once. {@code workFailure} is the action's own failure and takes precedence
     * for the error type over {@code settlementFailure}.
     */
    void reportFailure(Attempt attempt, @Nullable Throwable workFailure, @Nullable Throwable settlementFailure) {
        if (attempt.action == null || !attempt.reported.compareAndSet(false, true)) {
            return;
        }
        Throwable cause = workFailure != null ? workFailure : settlementFailure;
        WorkflowHistoryFailure failure = new WorkflowHistoryFailure(
                attempt.attemptId,
                attempt.instanceId,
                attempt.action,
                attempt.actor,
                attempt.onBehalfOf,
                attempt.attribution,
                cause == null ? null : cause.getClass().getSimpleName(),
                clock.instant());
        ListenerDispatch.forEach(
                listeners,
                listener -> listener.onHistoryFailed(failure),
                (listener, thrown) -> log.warn(
                        "History listener {} failed for the failure of instance {}",
                        listener.getClass().getName(),
                        failure.instanceId() == null
                                ? null
                                : failure.instanceId().value(),
                        thrown));
    }

    /** An action of {@code null} announces commits only: no failure is reported and no actor is captured. */
    Attempt attempt(@Nullable WorkflowHistoryFailure.Action action, @Nullable WorkflowInstanceId id) {
        return new Attempt(action, id, action == null ? CapturedActor.unattributed() : CapturedActor.capture(actors));
    }

    /** One attempt of an action: its identity, its captured actor and whether its failure was reported. */
    static final class Attempt {
        final UUID attemptId = UUID.randomUUID();
        final @Nullable WorkflowHistoryFailure.Action action;
        final HistoryActor actor;
        final @Nullable String onBehalfOf;
        final HistoryAttribution attribution;
        final AtomicBoolean reported = new AtomicBoolean();
        volatile @Nullable WorkflowInstanceId instanceId;

        private Attempt(
                @Nullable WorkflowHistoryFailure.Action action,
                @Nullable WorkflowInstanceId id,
                CapturedActor captured) {
            this.action = action;
            this.instanceId = id;
            this.actor = captured.actor();
            this.onBehalfOf = captured.onBehalfOf();
            this.attribution = captured.attribution();
        }
    }

    /**
     * What one transaction has announced: the instances whose wrapped work is running, the attempts that succeeded and
     * so wait for the outcome, and the sequence ranges they wrote, per instance. Guarded by the lock on
     * {@code transactions}.
     */
    private static final class TransactionState {
        final Set<WorkflowInstanceId> running = new HashSet<>();
        final List<Attempt> attempts = new ArrayList<>();
        final Map<WorkflowInstanceId, List<long[]>> ranges = new LinkedHashMap<>();
        boolean registered;

        boolean idle() {
            return running.isEmpty() && !registered;
        }
    }
}
