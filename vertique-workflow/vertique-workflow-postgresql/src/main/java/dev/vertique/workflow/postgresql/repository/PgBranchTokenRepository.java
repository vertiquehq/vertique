// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed repository for {@link BranchToken} rows in {@code workflow_branch_tokens}
 * (PRD-WF-002).
 *
 * <p>Branch transitions use optimistic concurrency on {@code version}; the
 * {@link #updateOptimistic} method returns the affected row count so callers can detect a stale
 * snapshot. Recovery queries surface RETRY_SCHEDULED branches whose {@code next_retry_at} is due
 * and stale RUNNING branches whose {@code updated_at} is older than a configurable threshold.
 */
@Singleton
public final class PgBranchTokenRepository extends PgSqlRepository implements BranchTokenRepository<SqlClient> {

    /** Name of the migration instance whose schema mapping places the workflow tables. */
    private static final String INSTANCE_NAME = "workflow";

    // --- SQL statements ---
    //
    // The statements are built once at construction because every table reference is qualified with
    // the schema mapped for the workflow instance (when there is one), so the text depends on the
    // configured placement rather than being a compile-time constant.

    private final String sqlInsert;
    private final String sqlUpdateOptimistic;
    private final String sqlFindById;
    private final String sqlFindByIdForUpdate;
    private final String sqlFindByWorkflowAndFork;
    private final String sqlFindByWorkflow;
    private final String sqlFindByWait;

    /**
     * Non-terminal statuses: RUNNING, WAITING, RETRY_SCHEDULED, COMPENSATING. SUPERSEDED is
     * deliberately excluded — a SUPERSEDED branch has already lost a race join and its waits
     * should already have been closed by that path; including it here would re-close already-closed
     * rows under workflow cancel.
     */
    private final String sqlFindActiveByWorkflow;

    private final String sqlFindByStatus;

    /**
     * Creates a new repository whose table is not schema-qualified, so it resolves through the
     * connection's default schema.
     *
     * @param pool the connection pool
     * @param mapper exception mapper for translating SQL errors
     */
    @Inject
    public PgBranchTokenRepository(Pool pool, PgDbExceptionMapper mapper) {
        this(pool, mapper, DbSchemas.empty());
    }

    /**
     * Creates a new repository that qualifies every table reference with the schema mapped to the
     * {@code workflow} instance, when {@code schemas} holds one. Without a mapping the statements
     * are identical to those of the two-argument constructor.
     *
     * @param pool the connection pool
     * @param mapper exception mapper for translating SQL errors
     * @param schemas the schema placement; an empty mapping leaves table names unqualified
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment
     *     PostgreSQL identifier
     */
    public PgBranchTokenRepository(Pool pool, PgDbExceptionMapper mapper, DbSchemas schemas) {
        super(pool, mapper);

        String tokens = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_branch_tokens");

        this.sqlInsert =
                "INSERT INTO " + tokens + " (id, workflow_id, fork_step_id, branch_id, current_step_id, status,"
                        + " wait_type, wait_key, wait_aux_id, result_json, error_type, error_message,"
                        + " attempt_count, max_attempts, next_retry_at, last_error_type, last_error_message,"
                        + " last_error_at, version, created_at, updated_at, metadata)"
                        + " VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11, $12, $13, $14, $15, $16, $17,"
                        + " $18, $19, $20, $21, $22::jsonb)";

        this.sqlUpdateOptimistic = "UPDATE " + tokens
                + " SET current_step_id = $1, status = $2, wait_type = $3, wait_key = $4, wait_aux_id = $5,"
                + " result_json = $6::jsonb, error_type = $7, error_message = $8, attempt_count = $9,"
                + " next_retry_at = $10, last_error_type = $11, last_error_message = $12, last_error_at = $13,"
                + " version = $14, updated_at = $15, metadata = $16::jsonb"
                + " WHERE id = $17 AND version = $18";

        this.sqlFindById = "SELECT * FROM " + tokens + " WHERE id = $1";

        this.sqlFindByIdForUpdate = "SELECT * FROM " + tokens + " WHERE id = $1 FOR UPDATE";

        this.sqlFindByWorkflowAndFork = "SELECT * FROM " + tokens + " WHERE workflow_id = $1 AND fork_step_id = $2"
                + " ORDER BY created_at ASC, branch_id ASC";

        this.sqlFindByWorkflow =
                "SELECT * FROM " + tokens + " WHERE workflow_id = $1 ORDER BY created_at ASC, branch_id ASC";

        this.sqlFindByWait = "SELECT * FROM " + tokens + " WHERE workflow_id = $1 AND fork_step_id = $2"
                + " AND branch_id = $3 AND wait_type = $4 AND wait_key = $5";

        this.sqlFindActiveByWorkflow = "SELECT * FROM " + tokens + " WHERE workflow_id = $1"
                + " AND status NOT IN ('COMPLETED','FAILED','CANCELLED','EXPIRED','SUPERSEDED','COMPENSATED')"
                + " ORDER BY created_at ASC, branch_id ASC";

        this.sqlFindByStatus = "SELECT * FROM " + tokens + " WHERE status = $1" + " ORDER BY created_at ASC LIMIT $2";
    }

    // --- Mutations ---

    /**
     * Inserts a new branch token in the active transaction.
     *
     * @param token the branch token to insert
     * @param tx the active transaction
     * @return a {@link Future} that completes when the row is inserted
     */
    @Override
    public Future<Void> insert(BranchToken token, SqlClient tx) {
        return tx.preparedQuery(sqlInsert)
                .execute(insertTuple(token))
                .<Void>mapEmpty()
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_branch_tokens insert")));
    }

    /**
     * Applies an optimistic-concurrency update; returns the affected row count (0 means stale).
     *
     * @param updated the desired post-update token (its {@link BranchToken#version()} is the new
     *     version to write)
     * @param expectedVersion the caller's previously-observed version
     * @param tx the active transaction
     * @return a {@link Future} of the affected row count: 1 on success, 0 on stale version
     */
    @Override
    public Future<Integer> updateOptimistic(BranchToken updated, long expectedVersion, SqlClient tx) {
        return tx.preparedQuery(sqlUpdateOptimistic)
                .execute(Tuple.from(Arrays.asList(
                        updated.currentStepId(),
                        updated.status().name(),
                        updated.waitType() != null ? updated.waitType().name() : null,
                        updated.waitKey(),
                        updated.waitAuxId(),
                        updated.resultJson(),
                        updated.errorType(),
                        updated.errorMessage(),
                        updated.attemptCount(),
                        updated.nextRetryAt() != null ? toOdt(updated.nextRetryAt()) : null,
                        updated.lastErrorType(),
                        updated.lastErrorMessage(),
                        updated.lastErrorAt() != null ? toOdt(updated.lastErrorAt()) : null,
                        updated.version(),
                        toOdt(updated.updatedAt()),
                        toMetadataJson(updated.metadata()),
                        updated.id(),
                        expectedVersion)))
                .map(rs -> rs.rowCount())
                .recover(t ->
                        Future.failedFuture(exceptionMapper.translate(t, "workflow_branch_tokens updateOptimistic")));
    }

    // --- Reads ---

    /**
     * Finds a branch token by primary key.
     *
     * @param id the branch token id
     * @param tx the active transaction (or read pool)
     * @return a {@link Future} containing the token, or {@link Optional#empty()} when missing
     */
    @Override
    public Future<Optional<BranchToken>> findById(UUID id, SqlClient tx) {
        return tx.preparedQuery(sqlFindById)
                .execute(Tuple.of(id))
                .map(rs -> mapFirst(rs))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_branch_tokens findById")));
    }

    /**
     * Finds a branch token by primary key, taking a row lock for the active transaction.
     *
     * @param id the branch token id
     * @param tx the active transaction
     * @return a {@link Future} containing the token, or {@link Optional#empty()} when missing
     */
    @Override
    public Future<Optional<BranchToken>> findByIdForUpdate(UUID id, SqlClient tx) {
        return tx.preparedQuery(sqlFindByIdForUpdate)
                .execute(Tuple.of(id))
                .map(rs -> mapFirst(rs))
                .recover(t ->
                        Future.failedFuture(exceptionMapper.translate(t, "workflow_branch_tokens findByIdForUpdate")));
    }

    /**
     * Returns all branch tokens belonging to the given workflow instance, ordered by creation time
     * then branch id.
     *
     * @param workflowId the parent workflow instance
     * @param tx the active transaction (or read pool)
     * @return a {@link Future} of the branch tokens
     */
    @Override
    public Future<List<BranchToken>> findByWorkflow(WorkflowInstanceId workflowId, SqlClient tx) {
        return tx.preparedQuery(sqlFindByWorkflow)
                .execute(Tuple.of(workflowId.value()))
                .map(rs -> mapAll(rs))
                .recover(t ->
                        Future.failedFuture(exceptionMapper.translate(t, "workflow_branch_tokens findByWorkflow")));
    }

    /**
     * Returns all non-terminal branch tokens belonging to the given workflow instance, ordered by
     * creation time then branch id.
     *
     * <p>Non-terminal statuses are: {@code RUNNING}, {@code WAITING}, {@code RETRY_SCHEDULED},
     * {@code COMPENSATING}. Terminal and race-resolved statuses ({@code COMPLETED}, {@code FAILED},
     * {@code CANCELLED}, {@code EXPIRED}, {@code SUPERSEDED}, {@code COMPENSATED}) are excluded.
     *
     * <p>Used by the workflow-cancel cascade to identify active branches whose owned waits must be
     * closed before the instance row is updated.
     *
     * @param workflowId the parent workflow instance
     * @param tx the active transaction
     * @return a {@link Future} of the active branch tokens
     */
    @Override
    public Future<List<BranchToken>> findActiveByWorkflow(WorkflowInstanceId workflowId, SqlClient tx) {
        return tx.preparedQuery(sqlFindActiveByWorkflow)
                .execute(Tuple.of(workflowId.value()))
                .map(rs -> mapAll(rs))
                .recover(t -> Future.failedFuture(
                        exceptionMapper.translate(t, "workflow_branch_tokens findActiveByWorkflow")));
    }

    /**
     * Returns all branch tokens belonging to a specific fork group, ordered by creation time then
     * branch id.
     *
     * @param workflowId the parent workflow instance
     * @param forkStepId the fork step id
     * @param tx the active transaction (or read pool)
     * @return a {@link Future} of the branch tokens
     */
    @Override
    public Future<List<BranchToken>> findByWorkflowAndFork(
            WorkflowInstanceId workflowId, String forkStepId, SqlClient tx) {
        return tx.preparedQuery(sqlFindByWorkflowAndFork)
                .execute(Tuple.of(workflowId.value(), forkStepId))
                .map(rs -> mapAll(rs))
                .recover(t -> Future.failedFuture(
                        exceptionMapper.translate(t, "workflow_branch_tokens findByWorkflowAndFork")));
    }

    /**
     * Looks up a single branch token by its (workflowId, forkStepId, branchId) and active wait
     * (waitType, waitKey). Used by the signal/timer/task callback path to resolve which branch
     * token owns an incoming event.
     *
     * @param workflowId the parent workflow instance
     * @param forkStepId the fork step id
     * @param branchId the branch id
     * @param waitType the active wait type (must match the row's {@code wait_type})
     * @param waitKey the active wait key (must match the row's {@code wait_key})
     * @param tx the active transaction
     * @return a {@link Future} containing the branch token if it exists with the matching wait
     */
    @Override
    public Future<Optional<BranchToken>> findByWaitForBranch(
            WorkflowInstanceId workflowId,
            String forkStepId,
            String branchId,
            String waitType,
            String waitKey,
            SqlClient tx) {
        return tx.preparedQuery(sqlFindByWait)
                .execute(Tuple.of(workflowId.value(), forkStepId, branchId, waitType, waitKey))
                .map(rs -> mapFirst(rs))
                .recover(t -> Future.failedFuture(
                        exceptionMapper.translate(t, "workflow_branch_tokens findByWaitForBranch")));
    }

    // --- Helpers ---

    private static Tuple insertTuple(BranchToken t) {
        return Tuple.from(Arrays.asList(
                t.id(),
                t.workflowId().value(),
                t.forkStepId(),
                t.branchId(),
                t.currentStepId(),
                t.status().name(),
                t.waitType() != null ? t.waitType().name() : null,
                t.waitKey(),
                t.waitAuxId(),
                t.resultJson(),
                t.errorType(),
                t.errorMessage(),
                t.attemptCount(),
                t.maxAttempts(),
                t.nextRetryAt() != null ? toOdt(t.nextRetryAt()) : null,
                t.lastErrorType(),
                t.lastErrorMessage(),
                t.lastErrorAt() != null ? toOdt(t.lastErrorAt()) : null,
                t.version(),
                toOdt(t.createdAt()),
                toOdt(t.updatedAt()),
                toMetadataJson(t.metadata())));
    }

    /**
     * Converts a {@link DurableMetadata} document to a {@link JsonObject} carrier for JSONB column
     * binding. Persists as {@code {"context": {...}}} shape. Returns {@code null} when the
     * document is {@code null} or empty (stores SQL {@code NULL} for empty context).
     *
     * @param metadata the durable metadata document, or {@code null}
     * @return the carrier {@link JsonObject}, or {@code null} when empty
     */
    private static JsonObject toMetadataJson(DurableMetadata metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        return metadata.toCarrier();
    }

    private static Optional<BranchToken> mapFirst(io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> rs) {
        var it = rs.iterator();
        if (!it.hasNext()) {
            return Optional.empty();
        }
        return Optional.of(RowMappers.branchToken().map(it.next()));
    }

    private static List<BranchToken> mapAll(io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> rs) {
        List<BranchToken> result = new ArrayList<>();
        rs.forEach(row -> result.add(RowMappers.branchToken().map(row)));
        return List.copyOf(result);
    }

    private static OffsetDateTime toOdt(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    /**
     * Returns branch tokens with the given status, ordered by creation time, up to {@code limit}
     * rows. A convenience query that selects by status without timestamp filtering.
     *
     * @param status the branch status to select
     * @param limit  maximum rows to return
     * @param tx     the active transaction
     * @return a {@link Future} of matching branch tokens
     */
    @Override
    public Future<List<BranchToken>> findByStatus(BranchStatus status, int limit, SqlClient tx) {
        return tx.preparedQuery(sqlFindByStatus)
                .execute(Tuple.of(status.name(), limit))
                .map(rs -> mapAll(rs))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_branch_tokens findByStatus")));
    }
}
