// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import dev.vertique.db.TransactionCompletion;
import dev.vertique.workflow.engine.executor.CommandActors;
import dev.vertique.workflow.engine.spi.CommandActor;
import dev.vertique.workflow.engine.spi.CommittedHistoryEntry;
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
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * Creates the publisher.
     *
     * @param listeners the listeners to notify; empty when the application binds none
     * @param history   the history repository
     * @param pool      the pool the committed range is read from, after the commit
     * @param actors    the capture of the actor of the current caller
     */
    @Inject
    public WorkflowHistoryEventPublisher(
            Set<WorkflowHistoryListener> listeners,
            WorkflowHistoryRepository<SqlClient> history,
            Pool pool,
            CommandActors actors) {
        this(listeners, history, pool, actors::capture, Clock.systemUTC());
    }

    WorkflowHistoryEventPublisher(
            Set<WorkflowHistoryListener> listeners,
            WorkflowHistoryRepository<SqlClient> history,
            SqlClient readClient,
            Supplier<CommandActor> actors,
            Clock clock) {
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
     * Runs the work of an action on a known instance and reports what it wrote once its transaction commits.
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
        if (!hasListeners()) {
            return work.get();
        }
        Attempt attempt = attempt(action, id);
        return highestSequence(id, tx).compose(after -> invoke(attempt, work)
                .compose(result -> settle(attempt, after, tx).map(settled -> result)));
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
        if (!hasListeners()) {
            return work.get();
        }
        Attempt attempt = attempt(action, null);
        return invoke(attempt, work).compose(created -> {
            attempt.instanceId = created;
            return settle(attempt, 0L, tx).map(settled -> created);
        });
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
        if (!attempt.reported.compareAndSet(false, true)) {
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

    Attempt attempt(WorkflowHistoryFailure.Action action, @Nullable WorkflowInstanceId id) {
        return new Attempt(action, id, captureActor());
    }

    /** Captures the actor first, before any asynchronous hop; a failed capture falls back to no actor. */
    private Actor captureActor() {
        try {
            CommandActor actor = actors.get();
            return new Actor(
                    new HistoryActor(HistoryActor.Kind.valueOf(actor.kind().name()), actor.id(), actor.reason()),
                    actor.onBehalfOf(),
                    HistoryAttribution.valueOf(actor.attribution().name()));
        } catch (RuntimeException failure) {
            log.warn("The actor of a workflow action could not be captured; it is reported as absent", failure);
            return new Actor(new HistoryActor(HistoryActor.Kind.NONE, null, null), null, HistoryAttribution.ABSENT);
        }
    }

    private record Actor(HistoryActor actor, @Nullable String onBehalfOf, HistoryAttribution attribution) {}

    /** One attempt of an action: its identity, its captured actor and whether its failure was reported. */
    static final class Attempt {
        final UUID attemptId = UUID.randomUUID();
        final WorkflowHistoryFailure.Action action;
        final HistoryActor actor;
        final @Nullable String onBehalfOf;
        final HistoryAttribution attribution;
        final AtomicBoolean reported = new AtomicBoolean();
        volatile @Nullable WorkflowInstanceId instanceId;

        private Attempt(WorkflowHistoryFailure.Action action, @Nullable WorkflowInstanceId id, Actor captured) {
            this.action = action;
            this.instanceId = id;
            this.actor = captured.actor();
            this.onBehalfOf = captured.onBehalfOf();
            this.attribution = captured.attribution();
        }
    }
}
