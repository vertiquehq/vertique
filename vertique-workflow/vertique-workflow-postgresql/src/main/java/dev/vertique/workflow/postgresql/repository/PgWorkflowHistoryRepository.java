// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.postgresql.PgTableNames;
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

    /** Name of the migration instance whose schema mapping places the workflow tables. */
    private static final String INSTANCE_NAME = "workflow";

    // --- SQL statements ---
    //
    // The statements are built once at construction because every table reference is qualified with
    // the schema mapped for the workflow instance (when there is one), so the text depends on the
    // configured placement rather than being a compile-time constant.

    private final String sqlAppend;
    private final String sqlNextSequence;
    private final String sqlListByInstance;
    private final String sqlListRecentByInstance;

    // --- Constructor ---

    /**
     * Creates a new repository whose table is not schema-qualified, so it resolves through the
     * connection's default schema.
     *
     * @param pool   the PostgreSQL connection pool
     * @param mapper the exception mapper for translating SQL errors to domain exceptions
     */
    @Inject
    public PgWorkflowHistoryRepository(Pool pool, PgDbExceptionMapper mapper) {
        this(pool, mapper, DbSchemas.empty());
    }

    /**
     * Creates a new repository that qualifies every table reference with the schema mapped to the
     * {@code workflow} instance, when {@code schemas} holds one. Without a mapping the statements
     * are identical to those of the two-argument constructor.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the exception mapper for translating SQL errors to domain exceptions
     * @param schemas the schema placement; an empty mapping leaves table names unqualified
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment
     *                                  PostgreSQL identifier
     */
    public PgWorkflowHistoryRepository(Pool pool, PgDbExceptionMapper mapper, DbSchemas schemas) {
        super(pool, mapper);

        String history = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_history");

        this.sqlAppend = "INSERT INTO " + history + " (workflow_id, sequence, entry_type, payload_json, recorded_at)"
                + " VALUES ($1, $2, $3, $4, $5)";

        this.sqlNextSequence =
                "SELECT COALESCE(MAX(sequence), 0) + 1 AS next_seq FROM " + history + " WHERE workflow_id = $1";

        this.sqlListByInstance = "SELECT workflow_id, sequence, entry_type, payload_json, recorded_at" + " FROM "
                + history + " WHERE workflow_id = $1 ORDER BY sequence ASC";

        this.sqlListRecentByInstance = "SELECT workflow_id, sequence, entry_type, payload_json, recorded_at"
                + " FROM " + history + " WHERE workflow_id = $1"
                + " ORDER BY sequence DESC LIMIT $2";
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
        return tx.preparedQuery(sqlAppend)
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
        return tx.preparedQuery(sqlNextSequence)
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
        return tx.preparedQuery(sqlListRecentByInstance)
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
        return tx.preparedQuery(sqlListByInstance)
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
