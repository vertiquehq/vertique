// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.admin.InboxOutboxAdminConfig;
import dev.vertique.inboxoutbox.admin.OutboxAdminAction;
import dev.vertique.inboxoutbox.admin.OutboxAdminActor;
import dev.vertique.inboxoutbox.admin.OutboxAdminCommand;
import dev.vertique.inboxoutbox.admin.OutboxAdminDisposition;
import dev.vertique.inboxoutbox.admin.OutboxAdminHistoryEntry;
import dev.vertique.inboxoutbox.admin.OutboxAdminHistoryKey;
import dev.vertique.inboxoutbox.admin.OutboxAdminHistoryPage;
import dev.vertique.inboxoutbox.admin.OutboxAdminHistoryQuery;
import dev.vertique.inboxoutbox.admin.OutboxAdminReceipt;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterConflictException;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterConflictException.Reason;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterContent;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterDetail;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterKey;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterNotFoundException;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterPage;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterQuery;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterService;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterSummary;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import dev.vertique.inboxoutbox.postgresql.maintenance.OutboxAdminOperationCleanup;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link OutboxDeadLetterService}, plus the receipt retention cleanup the
 * maintenance service runs as one of its outcomes.
 *
 * <p>Every statement is built once at construction, and every table reference in it is qualified with the
 * schema mapped for the {@code inbox-outbox} instance when there is one, exactly as
 * {@link PgInboxOutboxRepository} does, so the outbox table and the receipt table are always addressed
 * together. Only the optional filters of the dead-letter listing are appended per call, from constant
 * fragments that name no table, and every value is a bound parameter.
 *
 * <p>Reads project only the columns they return: the list never selects payload, headers, metadata or the
 * scope label, and the detail reads at most {@value OutboxDeadLetterDetail#MAX_LAST_ERROR_LENGTH} characters
 * of the last error from the database. {@link #readContent(long)} decides the size limit in the same
 * statement that reads the content, so an oversized value is never transferred.
 *
 * <p>A command runs in one transaction whose first statements set READ COMMITTED explicitly, whatever the
 * server default is, and a transaction-local {@code lock_timeout} (30 seconds), so a command that waits too long
 * for a lock fails with a retryable {@link InboxOutboxPersistenceException} and rolls back its reservation. It
 * then proceeds in a fixed order. It first reserves its operation id by
 * inserting the receipt row with {@code ON CONFLICT DO NOTHING}; a concurrent duplicate blocks on that key
 * until the first transaction ends, and a unique violation is never relied on. It then locks the entry,
 * checks its state and revision, applies the retry or discard, and completes the receipt row before the
 * commit. Any failure rolls the reservation back together with the state change, so only complete receipts
 * are ever committed. A replay of a committed operation id with identical intent returns the stored receipt
 * without looking at the entry.
 *
 * <p>Database failures leave this class as {@link InboxOutboxPersistenceException} whose message carries no
 * SQL, driver text, payload, reason or actor. The semantic exceptions pass through unchanged.
 */
public class PgOutboxDeadLetterService implements OutboxDeadLetterService, OutboxAdminOperationCleanup {

    /** How long a command waits for a lock before it fails with a retryable persistence exception. */
    static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(30);

    private static final String ISOLATION_STATEMENT = "SET TRANSACTION ISOLATION LEVEL READ COMMITTED";
    private static final String NOTIFY_CHANNEL = "transactional_outbox_channel";
    private static final String DEAD_LETTER = "DEAD_LETTER";

    // Dead-letter listing: constant fragments, joined per call for the filters that are present.
    private static final String SUMMARY_COLUMNS = "id, admin_revision, destination_type, destination, event_type,"
            + " aggregate_type, aggregate_id, attempt, max_attempts, error_type, created_at, updated_at";
    private static final String FILTER_ENTRY_ID = " AND id = $";
    private static final String FILTER_DESTINATION_TYPE = " AND destination_type = $";
    private static final String FILTER_DESTINATION = " AND destination = $";
    private static final String FILTER_EVENT_TYPE = " AND event_type = $";
    private static final String FILTER_AGGREGATE_TYPE = " AND aggregate_type = $";
    private static final String FILTER_AGGREGATE_ID = " AND aggregate_id = $";
    private static final String FILTER_ERROR_TYPE = " AND error_type = $";
    private static final String FILTER_FAILED_FROM = " AND updated_at >= $";
    private static final String FILTER_FAILED_BEFORE = " AND updated_at < $";
    private static final String FILTER_CREATED_FROM = " AND created_at >= $";
    private static final String FILTER_CREATED_BEFORE = " AND created_at < $";
    private static final String FILTER_AFTER_PREFIX = " AND (updated_at, id) < ($";
    private static final String LIST_ORDER = " ORDER BY updated_at DESC, id DESC LIMIT $";

    private final Pool pool;
    private final PgDbExceptionMapper exceptionMapper;
    private final PgInboxOutboxExceptionMapper boundaryMapper = new PgInboxOutboxExceptionMapper();
    private final int maxContentBytes;

    private final String sqlSetLockTimeout;
    private final String sqlListPrefix;
    private final String sqlFind;
    private final String sqlReadContent;
    private final String sqlReserve;
    private final String sqlFindOperation;
    private final String sqlLockTarget;
    private final String sqlRetry;
    private final String sqlNotify;
    private final String sqlDiscard;
    private final String sqlComplete;
    private final String sqlHistory;
    private final String sqlHistoryAfter;
    private final String sqlCleanupOperations;

    /**
     * Creates the service, qualifying every table reference with the schema mapped to the
     * {@code inbox-outbox} instance when {@code schemas} holds one.
     *
     * @param pool            the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL failure mapper for exception translation
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     * @param config          the administration configuration (content limit and receipt retention)
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment PostgreSQL
     *                                  identifier
     */
    public PgOutboxDeadLetterService(
            Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas, InboxOutboxAdminConfig config) {
        this(pool, exceptionMapper, schemas, config, DEFAULT_LOCK_TIMEOUT);
    }

    /**
     * Creates the service with an explicit lock timeout for its command transactions; tests use a small value.
     *
     * @param pool            the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL failure mapper for exception translation
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     * @param config          the administration configuration (content limit and receipt retention)
     * @param lockTimeout     how long a command waits for a lock; at least one millisecond
     * @throws IllegalArgumentException if the mapped schema name is not a valid identifier or the timeout is
     *                                  shorter than one millisecond
     */
    PgOutboxDeadLetterService(
            Pool pool,
            PgDbExceptionMapper exceptionMapper,
            DbSchemas schemas,
            InboxOutboxAdminConfig config,
            Duration lockTimeout) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper, "exceptionMapper");
        Objects.requireNonNull(config, "config");
        this.maxContentBytes = config.maxContentBytes();
        long lockTimeoutMs = Objects.requireNonNull(lockTimeout, "lockTimeout").toMillis();
        if (lockTimeoutMs < 1) {
            throw new IllegalArgumentException("lockTimeout must be at least one millisecond");
        }
        // SET LOCAL takes no bind parameter; the value is a number this class formats, never caller input.
        this.sqlSetLockTimeout = "SET LOCAL lock_timeout = '" + lockTimeoutMs + "ms'";

        String outbox = PgTableNames.qualify(schemas, InboxOutboxMigrationsModule.INSTANCE_NAME, "outbox");
        String operations =
                PgTableNames.qualify(schemas, InboxOutboxMigrationsModule.INSTANCE_NAME, "outbox_admin_operations");

        this.sqlListPrefix = "SELECT " + SUMMARY_COLUMNS + " FROM " + outbox + " WHERE state = '" + DEAD_LETTER + "'";

        this.sqlFind = "SELECT " + SUMMARY_COLUMNS + ", available_at, scheduled_at,"
                + " left(last_error, " + OutboxDeadLetterDetail.MAX_LAST_ERROR_LENGTH + ") AS last_error,"
                + " COALESCE(char_length(last_error) > " + OutboxDeadLetterDetail.MAX_LAST_ERROR_LENGTH
                + ", false) AS last_error_truncated"
                + " FROM " + outbox + " WHERE id = $1 AND state = '" + DEAD_LETTER + "'";

        // One statement measures the combined UTF-8 size and returns the text only when it fits, so a large value
        // never leaves the database and no separate size check can race with the read. The JSONB-to-text
        // conversions run once, in the materialized CTE; the size and both CASE expressions read their result.
        this.sqlReadContent = "WITH content AS MATERIALIZED (SELECT admin_revision, payload::text AS payload_text,"
                + " headers::text AS headers_text FROM " + outbox + " WHERE id = $1 AND state = '" + DEAD_LETTER
                + "'), sized AS (SELECT admin_revision, payload_text, headers_text,"
                + " octet_length(payload_text) + COALESCE(octet_length(headers_text), 0) AS content_bytes"
                + " FROM content)"
                + " SELECT admin_revision, content_bytes,"
                + " CASE WHEN content_bytes <= $2 THEN payload_text END AS payload_text,"
                + " CASE WHEN content_bytes <= $2 THEN headers_text END AS headers_text FROM sized";

        this.sqlReserve = "INSERT INTO " + operations
                + " (operation_id, action, entry_id, expected_revision, reason, actor_type, actor_id, recorded_at)"
                + " VALUES ($1, $2, $3, $4, $5, $6, $7, NOW())"
                + " ON CONFLICT (operation_id) DO NOTHING RETURNING recorded_at";

        this.sqlFindOperation = "SELECT action, entry_id, expected_revision, reason, actor_type, actor_id,"
                + " recorded_at, disposition, result_revision FROM " + operations + " WHERE operation_id = $1";

        this.sqlLockTarget = "SELECT state, admin_revision, destination_type, destination, event_type,"
                + " aggregate_type, aggregate_id, attempt, max_attempts, error_type FROM " + outbox
                + " WHERE id = $1 FOR UPDATE";

        // Everything the relay owns on a requeue is reset; the row id, carrier, destination, payload, headers,
        // metadata, identity, scheduling, created_at and max_attempts are left as they are.
        this.sqlRetry = "UPDATE " + outbox + " SET state = 'PENDING', attempt = 0,"
                + " admin_revision = admin_revision + 1, claimed_at = NULL, claimed_by = NULL,"
                + " last_error = NULL, error_type = NULL, available_at = NOW(), updated_at = NOW()"
                + " WHERE id = $1 AND state = '" + DEAD_LETTER + "' AND admin_revision = $2"
                + " RETURNING admin_revision";

        this.sqlNotify = "SELECT pg_notify('" + NOTIFY_CHANNEL + "', $1)";

        this.sqlDiscard =
                "DELETE FROM " + outbox + " WHERE id = $1 AND state = '" + DEAD_LETTER + "' AND admin_revision = $2";

        this.sqlComplete = "UPDATE " + operations + " SET attempt = $2, max_attempts = $3, error_type = $4,"
                + " destination_type = $5, destination = $6, event_type = $7, aggregate_type = $8,"
                + " aggregate_id = $9, disposition = $10, result_revision = $11 WHERE operation_id = $1";

        String historyColumns = "operation_id, entry_id, action, disposition, expected_revision, result_revision,"
                + " recorded_at, actor_type, actor_id, reason, attempt, max_attempts, error_type,"
                + " destination_type, destination, event_type, aggregate_type, aggregate_id";
        String historyBase =
                "SELECT " + historyColumns + " FROM " + operations + " WHERE entry_id = $1 AND disposition IS NOT NULL";
        this.sqlHistory = historyBase + " ORDER BY recorded_at DESC, operation_id DESC LIMIT $2";
        this.sqlHistoryAfter = historyBase + " AND (recorded_at, operation_id) < ($2, $3)"
                + " ORDER BY recorded_at DESC, operation_id DESC LIMIT $4";

        this.sqlCleanupOperations = "WITH batch AS (SELECT operation_id FROM " + operations
                + " WHERE recorded_at < NOW() - ($1 * interval '1 day') ORDER BY recorded_at LIMIT $2)"
                + " DELETE FROM " + operations + " WHERE operation_id IN (SELECT operation_id FROM batch)";
    }

    // --- Reads ---

    @Override
    public Future<OutboxDeadLetterPage> list(OutboxDeadLetterQuery query) {
        if (query == null) {
            return Future.failedFuture(new ValidationException("query must not be null"));
        }
        StringBuilder sql = new StringBuilder(sqlListPrefix);
        Tuple params = Tuple.tuple();
        if (query.entryId() != null) {
            filter(sql, params, FILTER_ENTRY_ID, query.entryId());
        }
        if (query.destinationType() != null) {
            filter(sql, params, FILTER_DESTINATION_TYPE, query.destinationType().id());
        }
        if (query.destination() != null) {
            filter(sql, params, FILTER_DESTINATION, query.destination());
        }
        if (query.eventType() != null) {
            filter(sql, params, FILTER_EVENT_TYPE, query.eventType());
        }
        if (query.aggregateType() != null) {
            filter(sql, params, FILTER_AGGREGATE_TYPE, query.aggregateType());
        }
        if (query.aggregateId() != null) {
            filter(sql, params, FILTER_AGGREGATE_ID, query.aggregateId());
        }
        if (query.errorType() != null) {
            filter(sql, params, FILTER_ERROR_TYPE, query.errorType());
        }
        if (query.failedFrom() != null) {
            filter(sql, params, FILTER_FAILED_FROM, toOffset(query.failedFrom()));
        }
        if (query.failedBefore() != null) {
            filter(sql, params, FILTER_FAILED_BEFORE, toOffset(query.failedBefore()));
        }
        if (query.createdFrom() != null) {
            filter(sql, params, FILTER_CREATED_FROM, toOffset(query.createdFrom()));
        }
        if (query.createdBefore() != null) {
            filter(sql, params, FILTER_CREATED_BEFORE, toOffset(query.createdBefore()));
        }
        OutboxDeadLetterKey after = query.after();
        if (after != null) {
            params.addValue(toOffset(after.failedAt()));
            params.addValue(after.entryId());
            sql.append(FILTER_AFTER_PREFIX)
                    .append(params.size() - 1)
                    .append(", $")
                    .append(params.size())
                    .append(')');
        }
        params.addValue(query.limit() + 1);
        sql.append(LIST_ORDER).append(params.size());

        int limit = query.limit();
        return pool.preparedQuery(sql.toString())
                .execute(params)
                .map(rows -> toPage(rows, limit))
                .recover(t -> Future.failedFuture(translate(t, "outbox dead-letter list")));
    }

    @Override
    public Future<Optional<OutboxDeadLetterDetail>> find(long entryId) {
        if (entryId <= 0) {
            return Future.failedFuture(new ValidationException("entryId must be positive"));
        }
        return pool.preparedQuery(sqlFind)
                .execute(Tuple.of(entryId))
                .map(rows -> rows.size() == 0 ? Optional.<OutboxDeadLetterDetail>empty() : Optional.of(toDetail(rows)))
                .recover(t -> Future.failedFuture(translate(t, "outbox dead-letter find")));
    }

    @Override
    public Future<Optional<OutboxDeadLetterContent>> readContent(long entryId) {
        if (entryId <= 0) {
            return Future.failedFuture(new ValidationException("entryId must be positive"));
        }
        return pool.preparedQuery(sqlReadContent)
                .execute(Tuple.of(entryId, (long) maxContentBytes))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return Optional.<OutboxDeadLetterContent>empty();
                    }
                    Row row = rows.iterator().next();
                    if (row.getLong("content_bytes") > maxContentBytes) {
                        throw new OutboxDeadLetterConflictException(Reason.CONTENT_LIMIT_EXCEEDED);
                    }
                    return Optional.of(new OutboxDeadLetterContent(
                            entryId,
                            row.getLong("admin_revision"),
                            row.getString("payload_text"),
                            toHeaders(row.getString("headers_text"))));
                })
                .recover(t -> Future.failedFuture(translate(t, "outbox dead-letter readContent")));
    }

    @Override
    public Future<OutboxAdminHistoryPage> history(OutboxAdminHistoryQuery query) {
        if (query == null) {
            return Future.failedFuture(new ValidationException("query must not be null"));
        }
        Tuple params = Tuple.of(query.entryId());
        String sql = sqlHistory;
        if (query.after().isPresent()) {
            OutboxAdminHistoryKey after = query.after().get();
            params.addValue(toOffset(after.recordedAt()));
            params.addValue(after.operationId());
            sql = sqlHistoryAfter;
        }
        params.addValue(query.limit() + 1);
        int limit = query.limit();
        return pool.preparedQuery(sql)
                .execute(params)
                .map(rows -> toHistoryPage(rows, limit))
                .recover(t -> Future.failedFuture(translate(t, "outbox dead-letter history")));
    }

    // --- Commands ---

    @Override
    public Future<OutboxAdminReceipt> retry(OutboxAdminCommand command) {
        return execute(OutboxAdminAction.RETRY, command, "outbox dead-letter retry");
    }

    @Override
    public Future<OutboxAdminReceipt> discard(OutboxAdminCommand command) {
        return execute(OutboxAdminAction.DISCARD, command, "outbox dead-letter discard");
    }

    private Future<OutboxAdminReceipt> execute(OutboxAdminAction action, OutboxAdminCommand command, String operation) {
        if (command == null) {
            return Future.failedFuture(new ValidationException("command must not be null"));
        }
        return pool.<OutboxAdminReceipt>withTransaction(conn -> conn.query(ISOLATION_STATEMENT)
                        .execute()
                        .compose(isolated -> conn.query(sqlSetLockTimeout).execute())
                        .compose(bounded -> runCommand(conn, action, command)))
                .recover(t -> Future.failedFuture(translate(t, operation)));
    }

    private Future<OutboxAdminReceipt> runCommand(
            SqlConnection conn, OutboxAdminAction action, OutboxAdminCommand command) {
        OutboxAdminActor actor = command.actor();
        return conn.preparedQuery(sqlReserve)
                .execute(Tuple.of(
                        command.operationId(),
                        action.name(),
                        command.entryId(),
                        command.expectedRevision(),
                        command.reason(),
                        actor.type(),
                        actor.id()))
                .compose(reserved -> {
                    if (reserved.rowCount() == 0) {
                        return replay(conn, action, command);
                    }
                    Instant recordedAt = reserved.iterator()
                            .next()
                            .getOffsetDateTime("recorded_at")
                            .toInstant();
                    return applyToEntry(conn, action, command, recordedAt);
                });
    }

    /** The operation id is already committed: answer from its receipt, or reject a different intent. */
    private Future<OutboxAdminReceipt> replay(
            SqlConnection conn, OutboxAdminAction action, OutboxAdminCommand command) {
        return conn.preparedQuery(sqlFindOperation)
                .execute(Tuple.of(command.operationId()))
                .map(rows -> {
                    if (rows.size() == 0) {
                        throw new OutboxDeadLetterConflictException(Reason.OPERATION_ID_CONFLICT);
                    }
                    Row row = rows.iterator().next();
                    OutboxAdminActor actor = command.actor();
                    boolean sameIntent = action.name().equals(row.getString("action"))
                            && command.entryId() == row.getLong("entry_id")
                            && command.expectedRevision() == row.getLong("expected_revision")
                            && command.reason().equals(row.getString("reason"))
                            && actor.type().equals(row.getString("actor_type"))
                            && actor.id().equals(row.getString("actor_id"));
                    if (!sameIntent) {
                        throw new OutboxDeadLetterConflictException(Reason.OPERATION_ID_CONFLICT);
                    }
                    return toReceipt(command.operationId(), row);
                });
    }

    private Future<OutboxAdminReceipt> applyToEntry(
            SqlConnection conn, OutboxAdminAction action, OutboxAdminCommand command, Instant recordedAt) {
        return conn.preparedQuery(sqlLockTarget)
                .execute(Tuple.of(command.entryId()))
                .compose(rows -> {
                    if (rows.size() == 0) {
                        throw new OutboxDeadLetterNotFoundException(command.entryId());
                    }
                    Row target = rows.iterator().next();
                    if (!DEAD_LETTER.equals(target.getString("state"))) {
                        throw new OutboxDeadLetterConflictException(Reason.NOT_DEAD_LETTER);
                    }
                    long revision = target.getLong("admin_revision");
                    if (revision != command.expectedRevision()) {
                        throw new OutboxDeadLetterConflictException(Reason.REVISION_CONFLICT);
                    }
                    if (action == OutboxAdminAction.RETRY && revision == Long.MAX_VALUE) {
                        throw new OutboxDeadLetterConflictException(Reason.REVISION_EXHAUSTED);
                    }
                    Future<OptionalLong> changed =
                            action == OutboxAdminAction.RETRY ? requeue(conn, command) : delete(conn, command);
                    return changed.compose(resultRevision -> complete(conn, action, command, target, resultRevision))
                            .map(done -> new OutboxAdminReceipt(
                                    command.operationId(),
                                    command.entryId(),
                                    action,
                                    dispositionOf(action),
                                    command.expectedRevision(),
                                    resultRevisionOf(action, command),
                                    recordedAt));
                });
    }

    private Future<OptionalLong> requeue(SqlConnection conn, OutboxAdminCommand command) {
        return conn.preparedQuery(sqlRetry)
                .execute(Tuple.of(command.entryId(), command.expectedRevision()))
                .compose(rows -> {
                    if (rows.rowCount() != 1) {
                        return Future.failedFuture(new OutboxDeadLetterConflictException(Reason.REVISION_CONFLICT));
                    }
                    long resultRevision = rows.iterator().next().getLong("admin_revision");
                    // The same wake-up the insert trigger sends, inside this transaction, so it is delivered only
                    // when the requeue commits.
                    return conn.preparedQuery(sqlNotify)
                            .execute(Tuple.of(String.valueOf(command.entryId())))
                            .map(notified -> OptionalLong.of(resultRevision));
                });
    }

    private Future<OptionalLong> delete(SqlConnection conn, OutboxAdminCommand command) {
        return conn.preparedQuery(sqlDiscard)
                .execute(Tuple.of(command.entryId(), command.expectedRevision()))
                .compose(rows -> rows.rowCount() != 1
                        ? Future.failedFuture(new OutboxDeadLetterConflictException(Reason.REVISION_CONFLICT))
                        : Future.succeededFuture(OptionalLong.empty()));
    }

    private Future<Void> complete(
            SqlConnection conn,
            OutboxAdminAction action,
            OutboxAdminCommand command,
            Row target,
            OptionalLong resultRevision) {
        Tuple params = Tuple.tuple()
                .addValue(command.operationId())
                .addValue(target.getInteger("attempt"))
                .addValue(target.getInteger("max_attempts"))
                .addValue(target.getString("error_type"))
                .addValue(target.getString("destination_type"))
                .addValue(target.getString("destination"))
                .addValue(target.getString("event_type"))
                .addValue(target.getString("aggregate_type"))
                .addValue(target.getString("aggregate_id"))
                .addValue(dispositionOf(action).name())
                .addValue(resultRevision.isPresent() ? (Object) resultRevision.getAsLong() : null);
        return conn.preparedQuery(sqlComplete).execute(params).compose(rows -> {
            if (rows.rowCount() != 1) {
                return Future.failedFuture(new InboxOutboxPersistenceException(
                        "Persistence failure during inbox/outbox operation 'outbox dead-letter receipt'",
                        new IllegalStateException("receipt row missing at completion"),
                        false));
            }
            return Future.succeededFuture();
        });
    }

    // --- Receipt retention ---

    /**
     * {@inheritDoc}
     *
     * <p>Both arguments must be positive: a zero or negative window would put the cutoff at or after the
     * present and delete every receipt.
     *
     * @throws ValidationException (as a failed future) when {@code retentionDays} or {@code batchSize} is not
     *                             positive
     */
    @Override
    public Future<Integer> cleanupOperations(int retentionDays, int batchSize) {
        if (retentionDays < 1 || batchSize < 1) {
            return Future.failedFuture(new ValidationException("retentionDays and batchSize must be positive"));
        }
        return pool.preparedQuery(sqlCleanupOperations)
                .execute(Tuple.of(retentionDays, batchSize))
                .map(RowSet::rowCount)
                .recover(t -> Future.failedFuture(translate(t, "outbox admin operations cleanup")));
    }

    // --- Mapping helpers ---

    private static void filter(StringBuilder sql, Tuple params, String fragment, Object value) {
        params.addValue(value);
        sql.append(fragment).append(params.size());
    }

    private static OutboxAdminDisposition dispositionOf(OutboxAdminAction action) {
        return action == OutboxAdminAction.RETRY ? OutboxAdminDisposition.REQUEUED : OutboxAdminDisposition.DISCARDED;
    }

    private static OptionalLong resultRevisionOf(OutboxAdminAction action, OutboxAdminCommand command) {
        return action == OutboxAdminAction.RETRY
                ? OptionalLong.of(command.expectedRevision() + 1)
                : OptionalLong.empty();
    }

    private static OutboxAdminReceipt toReceipt(UUID operationId, Row row) {
        String disposition = row.getString("disposition");
        if (disposition == null) {
            // A committed receipt is always complete; an incomplete one means the table was edited by hand.
            throw new InboxOutboxPersistenceException(
                    "Persistence failure during inbox/outbox operation 'outbox dead-letter receipt'",
                    new IllegalStateException("committed receipt is incomplete"),
                    false);
        }
        Long resultRevision = row.getLong("result_revision");
        return new OutboxAdminReceipt(
                operationId,
                row.getLong("entry_id"),
                OutboxAdminAction.valueOf(row.getString("action")),
                OutboxAdminDisposition.valueOf(disposition),
                row.getLong("expected_revision"),
                resultRevision == null ? OptionalLong.empty() : OptionalLong.of(resultRevision),
                row.getOffsetDateTime("recorded_at").toInstant());
    }

    private static OutboxDeadLetterPage toPage(RowSet<Row> rows, int limit) {
        List<OutboxDeadLetterSummary> items = new ArrayList<>(Math.min(rows.size(), limit));
        boolean more = false;
        for (Row row : rows) {
            if (items.size() == limit) {
                more = true;
                break;
            }
            items.add(toSummary(row));
        }
        Optional<OutboxDeadLetterKey> next = Optional.empty();
        if (more) {
            OutboxDeadLetterSummary last = items.get(items.size() - 1);
            next = Optional.of(new OutboxDeadLetterKey(last.failedAt(), last.entryId()));
        }
        return new OutboxDeadLetterPage(items, next);
    }

    private static OutboxDeadLetterSummary toSummary(Row row) {
        return new OutboxDeadLetterSummary(
                row.getLong("id"),
                row.getLong("admin_revision"),
                DestinationType.of(row.getString("destination_type")),
                row.getString("destination"),
                row.getString("event_type"),
                row.getString("aggregate_type"),
                row.getString("aggregate_id"),
                row.getInteger("attempt"),
                row.getInteger("max_attempts"),
                row.getString("error_type"),
                row.getOffsetDateTime("created_at").toInstant(),
                row.getOffsetDateTime("updated_at").toInstant());
    }

    private static OutboxDeadLetterDetail toDetail(RowSet<Row> rows) {
        Row row = rows.iterator().next();
        OffsetDateTime scheduledAt = row.getOffsetDateTime("scheduled_at");
        return new OutboxDeadLetterDetail(
                row.getLong("id"),
                row.getLong("admin_revision"),
                DestinationType.of(row.getString("destination_type")),
                row.getString("destination"),
                row.getString("event_type"),
                row.getString("aggregate_type"),
                row.getString("aggregate_id"),
                row.getInteger("attempt"),
                row.getInteger("max_attempts"),
                row.getString("error_type"),
                row.getOffsetDateTime("created_at").toInstant(),
                row.getOffsetDateTime("updated_at").toInstant(),
                row.getOffsetDateTime("available_at").toInstant(),
                scheduledAt == null ? Optional.empty() : Optional.of(scheduledAt.toInstant()),
                row.getString("last_error"),
                row.getBoolean("last_error_truncated"));
    }

    private static OutboxAdminHistoryPage toHistoryPage(RowSet<Row> rows, int limit) {
        List<OutboxAdminHistoryEntry> items = new ArrayList<>(Math.min(rows.size(), limit));
        boolean more = false;
        for (Row row : rows) {
            if (items.size() == limit) {
                more = true;
                break;
            }
            items.add(toHistoryEntry(row));
        }
        Optional<OutboxAdminHistoryKey> next = Optional.empty();
        if (more) {
            OutboxAdminHistoryEntry last = items.get(items.size() - 1);
            next = Optional.of(new OutboxAdminHistoryKey(last.recordedAt(), last.operationId()));
        }
        return new OutboxAdminHistoryPage(items, next);
    }

    private static OutboxAdminHistoryEntry toHistoryEntry(Row row) {
        Long resultRevision = row.getLong("result_revision");
        return new OutboxAdminHistoryEntry(
                row.getUUID("operation_id"),
                row.getLong("entry_id"),
                OutboxAdminAction.valueOf(row.getString("action")),
                OutboxAdminDisposition.valueOf(row.getString("disposition")),
                row.getLong("expected_revision"),
                resultRevision == null ? OptionalLong.empty() : OptionalLong.of(resultRevision),
                row.getOffsetDateTime("recorded_at").toInstant(),
                new OutboxAdminActor(row.getString("actor_type"), row.getString("actor_id")),
                row.getString("reason"),
                row.getInteger("attempt"),
                row.getInteger("max_attempts"),
                row.getString("error_type"),
                DestinationType.of(row.getString("destination_type")),
                row.getString("destination"),
                row.getString("event_type"),
                row.getString("aggregate_type"),
                row.getString("aggregate_id"));
    }

    /** Parses the stored application headers; a header whose stored value is JSON null has no value and is left out. */
    private static Map<String, String> toHeaders(String headersText) {
        if (headersText == null) {
            return Map.of();
        }
        JsonObject json = new JsonObject(headersText);
        Map<String, String> headers = new LinkedHashMap<>();
        json.forEach(entry -> {
            if (entry.getValue() != null) {
                headers.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        });
        return headers;
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    // --- Failure translation ---

    /**
     * Passes the semantic and validation exceptions through unchanged and translates everything else into an
     * {@link InboxOutboxPersistenceException} whose message carries no SQL, driver text or request data.
     */
    private Throwable translate(Throwable failure, String operation) {
        if (failure instanceof OutboxDeadLetterNotFoundException
                || failure instanceof OutboxDeadLetterConflictException
                || failure instanceof ValidationException
                || failure instanceof InboxOutboxPersistenceException) {
            return failure;
        }
        Throwable mapped = boundaryMapper.translate(exceptionMapper.translate(failure, operation), operation);
        if (mapped instanceof InboxOutboxPersistenceException) {
            return mapped;
        }
        return new InboxOutboxPersistenceException(
                "Persistence failure during inbox/outbox operation '" + operation + "' ["
                        + failure.getClass().getSimpleName() + "]",
                failure,
                false);
    }
}
