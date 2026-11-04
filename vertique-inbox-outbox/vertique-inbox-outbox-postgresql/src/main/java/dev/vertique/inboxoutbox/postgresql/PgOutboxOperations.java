// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import dev.vertique.inboxoutbox.ops.OutboxEntryNotFoundException;
import dev.vertique.inboxoutbox.ops.OutboxEntryStateException;
import dev.vertique.inboxoutbox.ops.OutboxOperations;
import dev.vertique.inboxoutbox.ops.OutboxTransition;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

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
 */
public class PgOutboxOperations implements OutboxOperations {

    /** How long a command waits for a lock before it fails with a retryable persistence exception. */
    static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(30);

    private static final Set<OutboxEntryState> RETRYABLE =
            Set.of(OutboxEntryState.DEAD_LETTER, OutboxEntryState.DISCARDED);
    private static final Set<OutboxEntryState> DISCARDABLE =
            Set.of(OutboxEntryState.PENDING, OutboxEntryState.DEAD_LETTER, OutboxEntryState.DISCARDED);

    private static final String ISOLATION_STATEMENT = "SET TRANSACTION ISOLATION LEVEL READ COMMITTED";
    private static final String NOTIFY_CHANNEL = "transactional_outbox_channel";

    private final Pool pool;
    private final PgDbExceptionMapper exceptionMapper;

    private final String sqlSetLockTimeout;
    private final String sqlLockEntry;
    private final String sqlRetry;
    private final String sqlNotify;
    private final String sqlDiscard;

    /**
     * Creates the service, qualifying every table reference with the schema mapped to the {@code inbox-outbox}
     * instance when {@code schemas} holds one.
     *
     * @param pool            the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL failure mapper for exception translation
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment PostgreSQL
     *                                  identifier
     */
    public PgOutboxOperations(Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas) {
        this(pool, exceptionMapper, schemas, DEFAULT_LOCK_TIMEOUT);
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
        this.pool = Objects.requireNonNull(pool, "pool");
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper, "exceptionMapper");
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
        Throwable invalid = invalid(entryId, expectedState, RETRYABLE, "retried");
        if (invalid != null) {
            return Future.failedFuture(invalid);
        }
        return inTransaction(
                "outbox entry retry", conn -> lockEntry(conn, entryId).compose(current -> {
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
                }));
    }

    @Override
    public Future<OutboxTransition> discard(long entryId, OutboxEntryState expectedState) {
        Throwable invalid = invalid(entryId, expectedState, DISCARDABLE, "discarded");
        if (invalid != null) {
            return Future.failedFuture(invalid);
        }
        return inTransaction(
                "outbox entry discard", conn -> lockEntry(conn, entryId).compose(current -> {
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
                }));
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
