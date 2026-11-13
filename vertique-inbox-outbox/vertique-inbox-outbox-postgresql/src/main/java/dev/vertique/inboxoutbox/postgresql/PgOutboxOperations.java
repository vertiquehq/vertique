// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.core.extension.ObserverFailureReporter;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import dev.vertique.inboxoutbox.ops.OutboxCommandFailure;
import dev.vertique.inboxoutbox.ops.OutboxEntryChange;
import dev.vertique.inboxoutbox.ops.OutboxEntryNotFoundException;
import dev.vertique.inboxoutbox.ops.OutboxEntryStateException;
import dev.vertique.inboxoutbox.ops.OutboxOperationListener;
import dev.vertique.inboxoutbox.ops.OutboxOperationType;
import dev.vertique.inboxoutbox.ops.OutboxOperations;
import dev.vertique.inboxoutbox.ops.OutboxTransition;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContextSnapshot;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL implementation of {@link OutboxOperations}.
 *
 * <p>Every statement is built once at construction, and every table reference in it is qualified with the
 * schema mapped for the {@code inbox-outbox} instance when there is one, exactly as
 * {@link PgInboxOutboxRepository} does.
 *
 * <p>A command runs in one transaction whose first statements set READ COMMITTED explicitly, whatever the server
 * default is, and a transaction-local {@code lock_timeout} (30 seconds), so a command that waits too long for a
 * lock fails with a retryable {@link InboxOutboxPersistenceException}. It then locks the entry with
 * {@code SELECT ... FOR UPDATE}, which waits for a relay claim or another command that holds the row and then
 * reads the committed state, compares that state with the one the caller expected, and applies the change with an
 * update that names the state again. The relay's claim skips a locked row, and a row the command has changed no
 * longer matches the claim, so a command and a claim never both succeed on the same entry.
 *
 * <p>Database failures leave this class as an {@link InboxOutboxPersistenceException} whose message carries no
 * SQL, driver text or payload. The semantic exceptions pass through unchanged.
 *
 * <p>Every contributed {@link OutboxOperationListener} is told how each command ended. The call id, the instant and a
 * snapshot of the {@link SecurityContext} bound in the {@link ContextHolder} are taken when the command is called,
 * before any asynchronous hop. A committed command is reported only once {@code withTransaction} has confirmed the
 * commit; a command that was refused, found nothing, conflicted, hit a persistence failure or lost its commit is
 * reported as failed, and never as committed. The caller receives exactly the result it would receive without
 * listeners: each listener call is isolated, and a failure of one is reported through an
 * {@link ObserverFailureReporter} and does not reach the caller or the listeners after it.
 */
public class PgOutboxOperations implements OutboxOperations {

    /** How long a command waits for a lock before it fails with a retryable persistence exception. */
    static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(30);

    private static final Set<OutboxEntryState> RETRYABLE =
            Set.of(OutboxEntryState.DEAD_LETTER, OutboxEntryState.DISCARDED);
    private static final Set<OutboxEntryState> DISCARDABLE =
            Set.of(OutboxEntryState.PENDING, OutboxEntryState.DEAD_LETTER, OutboxEntryState.DISCARDED);

    private static final Logger log = LoggerFactory.getLogger(PgOutboxOperations.class);

    private static final String ISOLATION_STATEMENT = "SET TRANSACTION ISOLATION LEVEL READ COMMITTED";
    private static final String NOTIFY_CHANNEL = "transactional_outbox_channel";

    private final Pool pool;
    private final PgDbExceptionMapper exceptionMapper;
    private final @Nullable ContextHolder holder;
    private final List<OutboxOperationListener> listeners;
    private final Clock clock;

    /** Reports the swallowed failures of operation listeners; one for the service, not one per call. */
    private final ObserverFailureReporter listenerFailureReporter =
            new ObserverFailureReporter(log, "Outbox operation listener");

    private final String sqlSetLockTimeout;
    private final String sqlLockEntry;
    private final String sqlRetry;
    private final String sqlNotify;
    private final String sqlDiscard;

    /**
     * Creates the service without listeners, qualifying every table reference with the schema mapped to the
     * {@code inbox-outbox} instance when {@code schemas} holds one.
     *
     * @param pool            the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL failure mapper for exception translation
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment PostgreSQL
     *                                  identifier
     */
    public PgOutboxOperations(Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas) {
        this(pool, exceptionMapper, schemas, null, Set.of(), Clock.systemUTC());
    }

    /**
     * Creates the service, qualifying every table reference with the schema mapped to the {@code inbox-outbox}
     * instance when {@code schemas} holds one, and reporting every command to {@code listeners}.
     *
     * @param pool            the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL failure mapper for exception translation
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     * @param holder          where the security context of a call is read from when it is made, or {@code null}
     *                        when there is none, in which case reports carry no security snapshot
     * @param listeners       the listeners every command is reported to; may be empty
     * @param clock           the time source of the reports
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment PostgreSQL
     *                                  identifier
     */
    public PgOutboxOperations(
            Pool pool,
            PgDbExceptionMapper exceptionMapper,
            DbSchemas schemas,
            @Nullable ContextHolder holder,
            Set<OutboxOperationListener> listeners,
            Clock clock) {
        this(pool, exceptionMapper, schemas, holder, listeners, clock, DEFAULT_LOCK_TIMEOUT);
    }

    /**
     * Creates the service with an explicit lock timeout for its command transactions; tests use a small value.
     *
     * @param pool            the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL failure mapper for exception translation
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     * @param lockTimeout     how long a command waits for a lock; at least one millisecond
     * @throws IllegalArgumentException if the mapped schema name is not a valid identifier or the timeout is
     *                                  shorter than one millisecond
     */
    PgOutboxOperations(Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas, Duration lockTimeout) {
        this(pool, exceptionMapper, schemas, null, Set.of(), Clock.systemUTC(), lockTimeout);
    }

    /**
     * Creates the service with listeners and an explicit lock timeout; tests use a small timeout.
     *
     * @param pool            the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL failure mapper for exception translation
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     * @param holder          where the security context of a call is read from, or {@code null} when there is none
     * @param listeners       the listeners every command is reported to; may be empty
     * @param clock           the time source of the reports
     * @param lockTimeout     how long a command waits for a lock; at least one millisecond
     * @throws IllegalArgumentException if the mapped schema name is not a valid identifier or the timeout is
     *                                  shorter than one millisecond
     */
    PgOutboxOperations(
            Pool pool,
            PgDbExceptionMapper exceptionMapper,
            DbSchemas schemas,
            @Nullable ContextHolder holder,
            Set<OutboxOperationListener> listeners,
            Clock clock,
            Duration lockTimeout) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper, "exceptionMapper");
        this.holder = holder;
        this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
        this.clock = Objects.requireNonNull(clock, "clock");
        long lockTimeoutMs = Objects.requireNonNull(lockTimeout, "lockTimeout").toMillis();
        if (lockTimeoutMs < 1) {
            throw new IllegalArgumentException("lockTimeout must be at least one millisecond");
        }
        // SET LOCAL takes no bind parameter; the value is a number this class formats, never caller input.
        this.sqlSetLockTimeout = "SET LOCAL lock_timeout = '" + lockTimeoutMs + "ms'";

        String outbox = PgTableNames.qualify(schemas, InboxOutboxMigrationsModule.INSTANCE_NAME, "outbox");

        this.sqlLockEntry = "SELECT state FROM " + outbox + " WHERE id = $1 FOR UPDATE";

        // Everything the relay owns on a requeue is reset; the row id, carrier, destination, payload, headers,
        // metadata, identity, scheduling, created_at and max_attempts are left as they are. The entry becomes
        // available at its scheduled time when that is still ahead, so the claim order by availability stays
        // meaningful.
        this.sqlRetry = "UPDATE " + outbox + " SET state = 'PENDING', attempt = 0,"
                + " claimed_at = NULL, claimed_by = NULL, last_error = NULL, error_type = NULL,"
                + " available_at = GREATEST(NOW(), COALESCE(scheduled_at, NOW())), updated_at = NOW()"
                + " WHERE id = $1 AND state = $2";

        this.sqlNotify = "SELECT pg_notify('" + NOTIFY_CHANNEL + "', $1)";

        // The attempt count and the last error stay, as the record of why the entry was dropped; only the lease goes.
        this.sqlDiscard = "UPDATE " + outbox + " SET state = 'DISCARDED', claimed_at = NULL, claimed_by = NULL,"
                + " updated_at = NOW() WHERE id = $1 AND state = $2";
    }

    @Override
    public Future<OutboxTransition> retry(long entryId, OutboxEntryState expectedState) {
        Call call = Call.start(OutboxOperationType.RETRY, entryId, expectedState, holder, clock, listeners);
        Throwable invalid = invalid(entryId, expectedState, RETRYABLE, "retried");
        if (invalid != null) {
            return reported(call, Future.failedFuture(invalid));
        }
        return reported(call, inTransaction("outbox entry retry", conn -> lockEntry(conn, entryId)
                .compose(current -> {
                    if (current != expectedState) {
                        return Future.failedFuture(new OutboxEntryStateException(entryId, expectedState, current));
                    }
                    return conn.preparedQuery(sqlRetry)
                            .execute(Tuple.of(entryId, current.name()))
                            .compose(rows -> requireOne(rows.rowCount()))
                            // The same wake-up the insert trigger sends, inside this transaction, so it is
                            // delivered only when the requeue commits.
                            .compose(done -> conn.preparedQuery(sqlNotify).execute(Tuple.of(String.valueOf(entryId))))
                            .map(notified -> new OutboxTransition(entryId, current, OutboxEntryState.PENDING, true));
                })));
    }

    @Override
    public Future<OutboxTransition> discard(long entryId, OutboxEntryState expectedState) {
        Call call = Call.start(OutboxOperationType.DISCARD, entryId, expectedState, holder, clock, listeners);
        Throwable invalid = invalid(entryId, expectedState, DISCARDABLE, "discarded");
        if (invalid != null) {
            return reported(call, Future.failedFuture(invalid));
        }
        return reported(call, inTransaction("outbox entry discard", conn -> lockEntry(conn, entryId)
                .compose(current -> {
                    if (current == OutboxEntryState.DISCARDED) {
                        // Already done, whatever the caller expected: repeating a discard is safe.
                        return Future.succeededFuture(
                                new OutboxTransition(entryId, current, OutboxEntryState.DISCARDED, false));
                    }
                    if (current != expectedState) {
                        return Future.failedFuture(new OutboxEntryStateException(entryId, expectedState, current));
                    }
                    return conn.preparedQuery(sqlDiscard)
                            .execute(Tuple.of(entryId, current.name()))
                            .compose(rows -> requireOne(rows.rowCount()))
                            .map(done -> new OutboxTransition(entryId, current, OutboxEntryState.DISCARDED, true));
                })));
    }

    /**
     * Reports how a command ended to every listener and returns the same future, so the caller's result is exactly
     * what it would be without listeners. Nothing is captured or reported when there is no listener.
     */
    private Future<OutboxTransition> reported(@Nullable Call call, Future<OutboxTransition> result) {
        if (call == null) {
            return result;
        }
        result.onComplete(outcome -> {
            if (outcome.succeeded()) {
                OutboxTransition transition = outcome.result();
                notifyListeners(
                        "onOutboxEntryChanged",
                        listener -> listener.onOutboxEntryChanged(new OutboxEntryChange(
                                call.callId(),
                                call.operation(),
                                transition.entryId(),
                                transition.previous(),
                                transition.current(),
                                transition.changed(),
                                call.security(),
                                call.occurredAt())));
            } else {
                Throwable cause = outcome.cause();
                String simpleName = cause.getClass().getSimpleName();
                OutboxCommandFailure failure = new OutboxCommandFailure(
                        call.callId(),
                        call.operation(),
                        call.entryId(),
                        call.expectedState(),
                        simpleName.isEmpty() ? null : simpleName,
                        call.security(),
                        clock.instant());
                notifyListeners("onOutboxCommandFailed", listener -> listener.onOutboxCommandFailed(failure));
            }
        });
        return result;
    }

    /**
     * Gives one event to every listener. An {@link Exception}, {@link LinkageError}, {@link AssertionError} or
     * {@link StackOverflowError} from one listener is reported through the {@link ObserverFailureReporter} and does
     * not stop the listeners after it; any other {@link Error} propagates.
     */
    private void notifyListeners(String callback, Consumer<OutboxOperationListener> delivery) {
        for (OutboxOperationListener listener : listeners) {
            try {
                delivery.accept(listener);
            } catch (Exception | LinkageError | AssertionError | StackOverflowError e) {
                listenerFailureReporter.report(listener.getClass(), callback, e);
            }
        }
    }

    /**
     * What is fixed when a command is called, before any asynchronous hop: the call id, the instant and the
     * snapshot of the caller's security context.
     */
    private record Call(
            UUID callId,
            OutboxOperationType operation,
            long entryId,
            @Nullable OutboxEntryState expectedState,
            @Nullable SecurityContextSnapshot security,
            Instant occurredAt) {

        /** Returns the call, or {@code null} when no listener is there to receive a report of it. */
        static @Nullable Call start(
                OutboxOperationType operation,
                long entryId,
                @Nullable OutboxEntryState expectedState,
                @Nullable ContextHolder holder,
                Clock clock,
                List<OutboxOperationListener> listeners) {
            if (listeners.isEmpty()) {
                return null;
            }
            SecurityContextSnapshot security = holder == null
                    ? null
                    : holder.current(SecurityContext.class)
                            .map(SecurityContext::snapshot)
                            .orElse(null);
            return new Call(UUID.randomUUID(), operation, entryId, expectedState, security, clock.instant());
        }
    }

    private static Throwable invalid(
            long entryId, OutboxEntryState expectedState, Set<OutboxEntryState> allowed, String verb) {
        if (entryId <= 0) {
            return new ValidationException("entryId must be positive");
        }
        if (expectedState == null) {
            return new ValidationException("expectedState must not be null");
        }
        if (!allowed.contains(expectedState)) {
            return new ValidationException("an entry in state " + expectedState + " cannot be " + verb);
        }
        return null;
    }

    private <T> Future<T> inTransaction(String operation, Function<SqlConnection, Future<T>> work) {
        return pool.<T>withTransaction(conn -> conn.query(ISOLATION_STATEMENT)
                        .execute()
                        .compose(isolated -> conn.query(sqlSetLockTimeout).execute())
                        .compose(bounded -> work.apply(conn)))
                .recover(t -> Future.failedFuture(PgOutboxFailures.translate(exceptionMapper, t, operation)));
    }

    /** Locks the entry and returns its committed state, or fails with {@link OutboxEntryNotFoundException}. */
    private Future<OutboxEntryState> lockEntry(SqlConnection conn, long entryId) {
        return conn.preparedQuery(sqlLockEntry).execute(Tuple.of(entryId)).compose(rows -> {
            if (rows.size() == 0) {
                return Future.failedFuture(new OutboxEntryNotFoundException(entryId));
            }
            Row row = rows.iterator().next();
            return Future.succeededFuture(OutboxEntryState.valueOf(row.getString("state")));
        });
    }

    /** The row is locked and its state was just read, so the guarded update must change exactly that row. */
    private static Future<Void> requireOne(int rowCount) {
        if (rowCount != 1) {
            return Future.failedFuture(new InboxOutboxPersistenceException(
                    "Persistence failure during inbox/outbox operation 'outbox entry command'",
                    new IllegalStateException("locked entry changed under the command"),
                    false));
        }
        return Future.succeededFuture();
    }
}
