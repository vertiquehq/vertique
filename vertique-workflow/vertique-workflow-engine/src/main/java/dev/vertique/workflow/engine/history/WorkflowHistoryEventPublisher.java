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
import dev.vertique.workflow.history.WorkflowHistoryCommit;
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
import java.util.Collections;
import java.util.HashSet;
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
 * highest history sequence, before and after the work. When the transaction commits, it reads the entries of that
 * range on a pooled connection, maps them to events and hands one {@link WorkflowHistoryCommit} to every listener. A
 * failure of the read, of the mapping or of a listener is logged and never reaches the action.
 *
 * <p>An action that fails, or whose transaction does not commit, is reported once to
 * {@link WorkflowHistoryListener#onHistoryFailed} through whichever signal arrives first: the work's own failure or
 * the transaction's settlement failure.
 *
 * <p>A public method that runs in a transaction can be reached from inside another wrapped site of the same
 * transaction. The inner call for an instance whose wrapped work is still running only calls its work: the outer call
 * announces the entries, and announcing them twice would overlap their ranges. Calls that follow one another, or that
 * concern other instances, register on their own.
 *
 * <p>With no listener bound the wrappers only call the work: no statement runs and no actor is captured. The result of
 * the work, its failure included, is always returned unchanged.
 */
@Singleton
public final class WorkflowHistoryEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowHistoryEventPublisher.class);

    private final Set<WorkflowHistoryListener> listeners;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final SqlClient readClient;
    private final Supplier<CommandActor> actors;
    private final Clock clock;
    private final BiFunction<WorkflowInstanceId, SqlClient, Future<Void>> lock;

    /**
     * The instances whose wrapped work is running, by the transaction it runs in. The key is the connection's
     * {@link Transaction} object, which does not override {@code equals}, so the lookup is by identity; the entry of a
     * transaction that is dropped goes with it.
     */
    private final Map<Transaction, Set<WorkflowInstanceId>> running = Collections.synchronizedMap(new WeakHashMap<>());

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
        this.listeners = Set.copyOf(listeners);
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
            Future<Void> locked = takeLock && id != null ? acquire(attempt, id, tx) : Future.succeededFuture();
            announced = locked.compose(ignored -> highestSequence(id, tx).compose(after -> invoke(attempt, work)
                    .compose(result -> settle(attempt, after, tx).map(settled -> result))));
        } catch (RuntimeException | Error thrown) {
            leave(transaction, id);
            throw thrown;
        }
        return leaving(announced, transaction, id);
    }

    private Future<Void> acquire(Attempt attempt, WorkflowInstanceId id, SqlClient tx) {
        Future<Void> locked;
        try {
            locked = lock.apply(id, tx);
        } catch (RuntimeException | Error thrown) {
            reportFailure(attempt, thrown, null);
            throw thrown;
        }
        return locked.onFailure(failure -> reportFailure(attempt, failure, null));
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
        synchronized (running) {
            return running.computeIfAbsent(transaction, key -> new HashSet<>()).add(id);
        }
    }

    private void leave(@Nullable Transaction transaction, @Nullable WorkflowInstanceId id) {
        if (transaction == null || id == null) {
            return;
        }
        synchronized (running) {
            Set<WorkflowInstanceId> inside = running.get(transaction);
            if (inside != null && inside.remove(id) && inside.isEmpty()) {
                running.remove(transaction);
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
     * logged and the range is then not announced; the action goes on.
     */
    private Future<Long> highestSequence(@Nullable WorkflowInstanceId id, SqlClient tx) {
        if (id == null) {
            return Future.succeededFuture(null);
        }
        try {
            return history.nextSequence(id, tx).<Long>map(next -> next - 1).recover(failure -> {
                log.warn("History sequence of instance {} could not be read", id.value(), failure);
                return Future.succeededFuture(null);
            });
        } catch (RuntimeException failure) {
            log.warn("History sequence of instance {} could not be read", id.value(), failure);
            return Future.succeededFuture(null);
        }
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
     * Registers the settlement callbacks after the work succeeded: the range is read when the transaction commits, and
     * a failed settlement is reported. The returned future always succeeds, once the registration is made.
     */
    private Future<Void> settle(Attempt attempt, @Nullable Long after, SqlClient tx) {
        WorkflowInstanceId id = attempt.instanceId;
        return highestSequence(id, tx).<Void>map(upTo -> {
            Runnable onCommit = after != null && upTo != null && upTo > after && id != null
                    ? () -> collectAndDeliver(id, after, upTo)
                    : () -> {};
            try {
                TransactionCompletion.onSettled(tx, onCommit, failure -> reportFailure(attempt, null, failure));
            } catch (IllegalStateException cannotSignal) {
                log.error(
                        "History events of instance {} are not announced: the transaction cannot signal its outcome",
                        id == null ? null : id.value(),
                        cannotSignal);
            }
            return null;
        });
    }

    // --- After the commit ---

    private void collectAndDeliver(WorkflowInstanceId id, long after, long upTo) {
        try {
            history.listRange(id, after, upTo, readClient).onComplete(read -> {
                try {
                    if (read.failed()) {
                        log.error("History events of instance {} could not be read", id.value(), read.cause());
                        return;
                    }
                    deliverCommit(id, read.result());
                } catch (RuntimeException failure) {
                    log.error("History events of instance {} could not be announced", id.value(), failure);
                }
            });
        } catch (RuntimeException failure) {
            log.error("History events of instance {} could not be read", id.value(), failure);
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
        WorkflowHistoryCommit commit = new WorkflowHistoryCommit(id, events);
        for (WorkflowHistoryListener listener : listeners) {
            try {
                listener.onHistoryCommitted(commit);
            } catch (Exception failure) {
                log.warn(
                        "History listener {} failed for instance {}",
                        listener.getClass().getName(),
                        id.value(),
                        failure);
            }
        }
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
        for (WorkflowHistoryListener listener : listeners) {
            try {
                listener.onHistoryFailed(failure);
            } catch (Exception thrown) {
                log.warn(
                        "History listener {} failed for the failure of instance {}",
                        listener.getClass().getName(),
                        failure.instanceId() == null
                                ? null
                                : failure.instanceId().value(),
                        thrown);
            }
        }
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
}
