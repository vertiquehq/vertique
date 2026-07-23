// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-backed repository for {@link WorkflowHistoryEntry} persistence.
 *
 * <p>History is append-only. Each entry is assigned a monotonically increasing sequence number
 * scoped to its workflow instance. The sequence is managed by the engine via {@link #nextSequence}.
 *
 * <p>SQL follows the direct {@code tx.preparedQuery(SQL).execute(Tuple)} style used by the
 * reference {@code PgInboxOutboxRepository} implementation.
 */
@Singleton
public final class PgWorkflowHistoryRepository extends PgSqlRepository implements WorkflowHistoryRepository<SqlClient> {

    // --- SQL constants ---

    private static final String SQL_APPEND =
            "INSERT INTO workflow_history (workflow_id, sequence, entry_type, payload_json, recorded_at)"
                    + " VALUES ($1, $2, $3, $4, $5)";

    private static final String SQL_NEXT_SEQUENCE =
            "SELECT COALESCE(MAX(sequence), 0) + 1 AS next_seq FROM workflow_history WHERE workflow_id = $1";

    private static final String SQL_LIST_BY_INSTANCE =
            "SELECT workflow_id, sequence, entry_type, payload_json, recorded_at"
                    + " FROM workflow_history WHERE workflow_id = $1 ORDER BY sequence ASC";

    private static final String SQL_LIST_RECENT_BY_INSTANCE =
            "SELECT workflow_id, sequence, entry_type, payload_json, recorded_at"
                    + " FROM workflow_history WHERE workflow_id = $1"
                    + " ORDER BY sequence DESC LIMIT $2";

    // --- Constructor ---

    /**
     * Creates a new repository with the given connection pool and exception mapper.
     *
     * @param pool   the PostgreSQL connection pool
     * @param mapper the exception mapper for translating SQL errors to domain exceptions
     */
    @Inject
    public PgWorkflowHistoryRepository(Pool pool, PgDbExceptionMapper mapper) {
        super(pool, mapper);
    }

    // --- Write operations ---

    /**
     * Appends a history entry to the {@code workflow_history} table within the given transaction.
     * Uses {@code entry.recordedAt()} when non-null, otherwise falls back to {@link Instant#now()}.
     *
     * @param e  the history entry to append
     * @param tx the active transaction to use for the insert
     * @return a {@link Future} that completes when the row is inserted
     */
    @Override
    public Future<Void> append(WorkflowHistoryEntry e, SqlClient tx) {
        Instant timestamp = e.recordedAt() != null ? e.recordedAt() : Instant.now();
        OffsetDateTime recordedAt = timestamp.atOffset(ZoneOffset.UTC);
        // Send payload_json as JsonObject so the pg-client uses the JSONB wire codec (OID 3802)
        // rather than binding it as text with a server-side ::jsonb cast. The JSONB wire codec
        // stores the value as a proper JSONB object, enabling JSON-path operators (->>'key') in
        // any future query predicates that need them.
        JsonObject payloadJsonObject = new JsonObject(e.payloadJson());
        Tuple params = Tuple.of(e.instanceId().value(), e.sequence(), e.entryType(), payloadJsonObject, recordedAt);
        return tx.preparedQuery(SQL_APPEND)
                .execute(params)
                .<Void>mapEmpty()
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_history append")));
    }

    // --- Sequence operations ---

    /**
     * Returns the next sequence number for a workflow instance within the given transaction.
     *
     * <p>The sequence is computed as {@code MAX(sequence) + 1} for the instance, or {@code 1} if
     * no history entries exist yet. This method must be called inside the same transaction as the
     * subsequent {@link #append} call to guarantee monotonicity.
     *
     * @param id the workflow instance id whose sequence counter to advance
     * @param tx the active transaction to use
     * @return a {@link Future} containing the next sequence number
     */
    @Override
    public Future<Long> nextSequence(WorkflowInstanceId id, SqlClient tx) {
        return tx.preparedQuery(SQL_NEXT_SEQUENCE)
                .execute(Tuple.of(id.value()))
                .map(rs -> rs.iterator().next().getLong("next_seq"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_history nextSequence")));
    }

    // --- Read operations ---

    /**
     * Returns the most recent {@code limit} history entries for the given workflow instance,
     * ordered by sequence descending (most recent first) within the transaction.
     *
     * <p>Callers that need oldest-first ordering should reverse the returned list. This method is
     * used by the migration engine to populate
     * {@link dev.vertique.workflow.migration.MigrationContext#recentHistory()}.
     *
     * @param id    the workflow instance id whose history to retrieve
     * @param limit the maximum number of entries to return; must be positive
     * @param tx    the SQL client or active transaction to use for the query
     * @return a {@link Future} containing up to {@code limit} entries, most recent first
     */
    @Override
    public Future<List<WorkflowHistoryEntry>> listRecentByInstance(WorkflowInstanceId id, int limit, SqlClient tx) {
        return tx.preparedQuery(SQL_LIST_RECENT_BY_INSTANCE)
                .execute(Tuple.of(id.value(), limit))
                .map(rs -> {
                    List<WorkflowHistoryEntry> entries = new ArrayList<>();
                    var mapper = RowMappers.workflowHistoryEntry();
                    for (var row : rs) {
                        entries.add(mapper.map(row));
                    }
                    return entries;
                })
                .recover(t ->
                        Future.failedFuture(exceptionMapper.translate(t, "workflow_history listRecentByInstance")));
    }

    /**
     * Returns all history entries for the given workflow instance, ordered by sequence ascending.
     * Executes against the pool directly (read-only, no locking).
     *
     * @param id the workflow instance id whose history to retrieve
     * @return a {@link Future} containing the ordered history entries
     */
    @Override
    public Future<List<WorkflowHistoryEntry>> listByInstance(WorkflowInstanceId id) {
        return listByInstance(id, pool);
    }

    /**
     * Returns all history entries for the given workflow instance, ordered by sequence ascending.
     * Executes against the given {@link SqlClient}, allowing callers to run the read within the
     * same transaction that holds write locks on the instance row.
     *
     * @param id the workflow instance id whose history to retrieve
     * @param tx the SQL client (or active transaction) to use for the query
     * @return a {@link Future} containing the ordered history entries
     */
    @Override
    public Future<List<WorkflowHistoryEntry>> listByInstance(WorkflowInstanceId id, SqlClient tx) {
        return tx.preparedQuery(SQL_LIST_BY_INSTANCE)
                .execute(Tuple.of(id.value()))
                .map(rs -> {
                    List<WorkflowHistoryEntry> entries = new ArrayList<>();
                    var mapper = RowMappers.workflowHistoryEntry();
                    for (var row : rs) {
                        entries.add(mapper.map(row));
                    }
                    return entries;
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_history listByInstance")));
    }
}
