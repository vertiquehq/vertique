// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.retention;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * PostgreSQL persistence component for workflow retention. Owns the archive/purge SQL,
 * transaction execution, and exception translation. Service-level concerns (status routing,
 * batch-size validation, {@link Instant}/{@link OffsetDateTime} conversion, result construction)
 * live in {@link PgWorkflowRetentionService} which delegates to this repository.
 *
 * <p>Both operations use {@code FOR UPDATE SKIP LOCKED} on a correlated subquery so multiple
 * concurrent batch runs (e.g., rolling cron jobs on different application nodes) progress without
 * blocking one another. Each method opens its own transaction via {@link Pool#withTransaction}.
 *
 * <p>Cutoff semantics:
 * <ul>
 *   <li>Archive: {@code completed_at <= cutoff} — set by the optimistic-update path for every
 *       terminal status, matching {@code idx_workflow_instances_archive_sweep}.</li>
 *   <li>Purge: {@code archived_at <= cutoff} — matching
 *       {@code idx_workflow_instances_archived_at}.</li>
 * </ul>
 *
 * <p>Both queries use {@code ORDER BY <cutoff column> ASC, id ASC} for deterministic batch
 * progression so workers picking from the same pool of eligible rows under contention see a
 * stable scan order.
 *
 * <p><strong>Archive and purge do not share a clock.</strong> {@link #archiveBefore} stamps
 * {@code archived_at} with the database's {@code NOW()} — {@code transaction_timestamp()}, the
 * instant its transaction began — whereas the {@code cutoff} both methods compare against is
 * supplied by the caller and therefore comes from the caller's clock. The two clocks drift
 * independently, so a row archived moments ago may carry an {@code archived_at} that is
 * <em>greater</em> than a cutoff derived from the caller's own {@code now()}, and the purge will
 * skip it. Nothing here is wrong for production use, where retention cutoffs sit hours or days in
 * the past; but no caller — a test above all — may assume that archiving and then immediately
 * purging with a caller-side {@code now()} cutoff deletes the rows it just archived. Where that
 * round trip must be exercised, seed {@code archived_at} explicitly so both sides of the
 * comparison come from one clock.
 */
@Singleton
public final class PgWorkflowRetentionRepository extends PgSqlRepository {

    /**
     * Archive SQL: sets {@code archived_at = NOW()} on up to {@code $4} unarchived rows of the
     * given status whose {@code completed_at <= $2}, optionally filtered by {@code definition_id}.
     *
     * <p>Parameters: $1=status, $2=completed_at cutoff, $3=definition_id (nullable text), $4=limit.
     */
    private static final String SQL_ARCHIVE = "UPDATE workflow_instances"
            + " SET archived_at = NOW()"
            + " WHERE id IN ("
            + "  SELECT id FROM workflow_instances"
            + "  WHERE status = $1"
            + "    AND completed_at <= $2"
            + "    AND archived_at IS NULL"
            + "    AND ($3::text IS NULL OR definition_id = $3)"
            + "  ORDER BY completed_at ASC, id ASC"
            + "  LIMIT $4"
            + "  FOR UPDATE SKIP LOCKED"
            + ")";

    /**
     * Purge SQL: deletes up to {@code $3} archived rows whose {@code archived_at <= $1},
     * optionally filtered by {@code definition_id}.
     *
     * <p>Parameters: $1=archived_at cutoff, $2=definition_id (nullable text), $3=limit.
     */
    private static final String SQL_PURGE = "DELETE FROM workflow_instances"
            + " WHERE id IN ("
            + "  SELECT id FROM workflow_instances"
            + "  WHERE archived_at IS NOT NULL"
            + "    AND archived_at <= $1"
            + "    AND ($2::text IS NULL OR definition_id = $2)"
            + "  ORDER BY archived_at ASC, id ASC"
            + "  LIMIT $3"
            + "  FOR UPDATE SKIP LOCKED"
            + ")";

    /**
     * Constructs a retention repository.
     *
     * @param pool            the PostgreSQL connection pool used to open transactions
     * @param exceptionMapper the PostgreSQL exception mapper used to translate SQL errors to
     *                        typed domain exceptions
     */
    @Inject
    public PgWorkflowRetentionRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
        super(pool, exceptionMapper);
    }

    /**
     * Archives up to {@code limit} unarchived workflow instances of the given terminal {@code status}
     * whose {@code completed_at <= cutoff}, optionally filtered by {@code definitionId}. Opens its
     * own transaction; rolls back on SQL failure.
     *
     * @param status       the terminal status string (e.g., {@code "COMPLETED"})
     * @param cutoff       inclusive upper bound on {@code completed_at}; rows with
     *                     {@code completed_at <= cutoff} are eligible
     * @param definitionId optional definition-id filter; null means all definitions
     * @param limit        maximum number of rows to archive in this transaction; assumed to be
     *                     pre-validated and clamped by the caller
     * @return a {@link Future} resolving to the number of rows actually archived (always
     *         {@code <= limit}); a value equal to {@code limit} signals more eligible rows likely
     *         remain
     */
    public Future<Integer> archiveBefore(
            String status, OffsetDateTime cutoff, @Nullable String definitionId, int limit) {
        Tuple params = Tuple.of(status, cutoff, definitionId, limit);
        return transaction().execute("workflow_instances archiveBefore", conn -> conn.preparedQuery(SQL_ARCHIVE)
                .execute(params)
                .map(rs -> rs.rowCount()));
    }

    /**
     * Purges up to {@code limit} previously archived workflow instances whose
     * {@code archived_at <= cutoff}, optionally filtered by {@code definitionId}. CASCADE FKs on
     * {@code workflow_history}, {@code workflow_tasks}, {@code workflow_timers}, and
     * {@code workflow_dedup} drop dependent rows automatically. Opens its own transaction; rolls
     * back on SQL failure.
     *
     * @param cutoff       inclusive upper bound on {@code archived_at}
     * @param definitionId optional definition-id filter; null means all definitions
     * @param limit        maximum number of rows to delete in this transaction; assumed to be
     *                     pre-validated and clamped by the caller
     * @return a {@link Future} resolving to the number of rows actually deleted
     */
    public Future<Integer> purgeArchivedBefore(OffsetDateTime cutoff, @Nullable String definitionId, int limit) {
        Tuple params = Tuple.of(cutoff, definitionId, limit);
        return transaction().execute("workflow_instances purgeArchived", conn -> conn.preparedQuery(SQL_PURGE)
                .execute(params)
                .map(rs -> rs.rowCount()));
    }
}
