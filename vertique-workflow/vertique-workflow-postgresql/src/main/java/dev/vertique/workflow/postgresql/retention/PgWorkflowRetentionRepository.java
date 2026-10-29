// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.retention;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.postgresql.PgTableNames;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * PostgreSQL persistence component for workflow retention. Owns the archive/purge SQL,
 * transaction execution, and exception translation. Service-level concerns (status routing,
 * batch-size validation, {@link Instant}/{@link OffsetDateTime} conversion, result construction)
 * live in {@link PgWorkflowRetentionService} which delegates to this repository.
 *
 * <p>Both operations pick their candidates by their cutoff column, and try each candidate's execution lock without
 * waiting, before they touch the instance's row: an instance whose lock another transaction holds (a segment running,
 * a command being recorded, a cancellation, another retention run) is skipped this run, keeps no lock of any kind and
 * is removed by a later one, so multiple concurrent batch runs (e.g., rolling cron jobs on different application nodes)
 * progress without blocking one another or the writers of the instances they skip. Under the lock a second statement reads the
 * eligibility again, with a snapshot taken after the lock was won, and keeps out every instance that
 * is no longer eligible (a purge also needs an ended or {@code FAILED} instance: an archived instance that a retry
 * returned to work is never purged while it lives) or has an active command ({@code PENDING}, {@code DEFERRED} or
 * {@code CLAIMED}): a retry recorded on an archive-eligible failed instance keeps it. Each method
 * opens its own transaction via {@link Pool#withTransaction}, so the locks are released when the
 * batch ends.
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

    /** Name of the migration instance whose schema mapping places the workflow tables. */
    private static final String INSTANCE_NAME = "workflow";

    /** The statuses an instance can be purged in: the ended ones and {@code FAILED}; a live instance is never purged. */
    private static final String ENDED_OR_FAILED = "('COMPLETED','COMPENSATED','CANCELLED','EXPIRED','FAILED')";

    /** The namespace prefix of the execution lock's key, as the executors derive it. */
    private static final String EXECUTION_LOCK_NAMESPACE = "vertique.workflow.execution-lock:";

    // --- SQL statements ---
    //
    // The statements are built once at construction because every table reference is qualified with
    // the schema mapped for the workflow instance (when there is one), so the text depends on the
    // configured placement rather than being a compile-time constant.

    /**
     * Archive candidate SQL: up to {@code $4} unarchived rows of the given status whose {@code completed_at <= $2},
     * optionally filtered by {@code definition_id}, locked with {@code SKIP LOCKED}, and then only those whose
     * execution lock this transaction won without waiting.
     *
     * <p>Parameters: $1=status, $2=completed_at cutoff, $3=definition_id (nullable text), $4=limit.
     */
    private final String sqlArchiveCandidates;

    /**
     * Archive eligibility SQL, run once the execution locks are held: the candidates ({@code $1}) that are still of
     * status {@code $2}, past the cutoff {@code $3}, unarchived and without an active command.
     */
    private final String sqlArchiveEligible;

    /** Archive write SQL: sets {@code archived_at = NOW()} on the instances in {@code $1}. */
    private final String sqlArchive;

    /**
     * Purge candidate SQL: up to {@code $3} archived rows whose {@code archived_at <= $1}, optionally filtered by
     * {@code definition_id}, locked with {@code SKIP LOCKED}, and then only those whose execution lock this
     * transaction won without waiting.
     *
     * <p>Parameters: $1=archived_at cutoff, $2=definition_id (nullable text), $3=limit.
     */
    private final String sqlPurgeCandidates;

    /**
     * Purge eligibility SQL, run once the execution locks are held: the candidates ({@code $1}) that are still archived
     * at or before the cutoff {@code $2} and without an active command.
     */
    private final String sqlPurgeEligible;

    /** Purge write SQL: deletes the instances in {@code $1}. */
    private final String sqlPurge;

    /**
     * Constructs a retention repository whose table is not schema-qualified, so it resolves
     * through the connection's default schema.
     *
     * @param pool            the PostgreSQL connection pool used to open transactions
     * @param exceptionMapper the PostgreSQL exception mapper used to translate SQL errors to
     *                        typed domain exceptions
     */
    @Inject
    public PgWorkflowRetentionRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
        this(pool, exceptionMapper, DbSchemas.empty());
    }

    /**
     * Constructs a retention repository that qualifies every table reference, including the
     * subselects, with the schema mapped to the {@code workflow} instance, when {@code schemas}
     * holds one. Without a mapping the statements are identical to those of the two-argument
     * constructor.
     *
     * @param pool            the PostgreSQL connection pool used to open transactions
     * @param exceptionMapper the PostgreSQL exception mapper used to translate SQL errors to
     *                        typed domain exceptions
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment
     *                                  PostgreSQL identifier
     */
    public PgWorkflowRetentionRepository(Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas) {
        super(pool, exceptionMapper);

        String instances = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_instances");

        String commands = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_commands");
        String noActiveCommand = " AND NOT EXISTS (SELECT 1 FROM " + commands + " c WHERE c.workflow_id = i.id"
                + " AND c.state IN ('PENDING','DEFERRED','CLAIMED'))";

        this.sqlArchiveCandidates = "SELECT cand.id FROM ("
                + "  SELECT id FROM " + instances
                + "  WHERE status = $1"
                + "    AND completed_at <= $2"
                + "    AND archived_at IS NULL"
                + "    AND ($3::text IS NULL OR definition_id = $3)"
                + "  ORDER BY completed_at ASC, id ASC"
                + "  LIMIT $4"
                + ") cand WHERE " + tryExecutionLock("cand.id");

        this.sqlArchiveEligible = "SELECT i.id FROM " + instances + " i"
                + " WHERE i.id = ANY($1::uuid[])"
                + "   AND i.status = $2"
                + "   AND i.completed_at <= $3"
                + "   AND i.archived_at IS NULL"
                + noActiveCommand;

        this.sqlArchive = "UPDATE " + instances + " SET archived_at = NOW() WHERE id = ANY($1::uuid[])";

        this.sqlPurgeCandidates = "SELECT cand.id FROM ("
                + "  SELECT id FROM " + instances
                + "  WHERE archived_at IS NOT NULL"
                + "    AND archived_at <= $1"
                + "    AND status IN " + ENDED_OR_FAILED
                + "    AND ($2::text IS NULL OR definition_id = $2)"
                + "  ORDER BY archived_at ASC, id ASC"
                + "  LIMIT $3"
                + ") cand WHERE " + tryExecutionLock("cand.id");

        this.sqlPurgeEligible = "SELECT i.id FROM " + instances + " i"
                + " WHERE i.id = ANY($1::uuid[])"
                + "   AND i.archived_at IS NOT NULL"
                + "   AND i.archived_at <= $2"
                + "   AND i.status IN " + ENDED_OR_FAILED
                + noActiveCommand;

        this.sqlPurge = "DELETE FROM " + instances + " WHERE id = ANY($1::uuid[])";
    }

    /**
     * The SQL that tries an instance's execution lock without waiting. The two integers are the two halves of the first
     * eight bytes of the SHA-256 of the namespace-prefixed instance id, the derivation the executors and requesters use
     * for the same lock, so a retention run and a running segment exclude one another.
     *
     * @param idColumn the expression of the instance id
     * @return a boolean SQL expression that is true when the transaction now holds the lock
     */
    private static String tryExecutionLock(String idColumn) {
        String digest = "sha256(convert_to('" + EXECUTION_LOCK_NAMESPACE + "' || " + idColumn + "::text, 'UTF8'))";
        return "pg_try_advisory_xact_lock("
                + "('x' || encode(substring(" + digest + " from 1 for 4), 'hex'))::bit(32)::int, "
                + "('x' || encode(substring(" + digest + " from 5 for 4), 'hex'))::bit(32)::int)";
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
        Tuple candidates = Tuple.of(status, cutoff, definitionId, limit);
        return transaction()
                .execute("workflow_instances archiveBefore", conn -> conn.preparedQuery(sqlArchiveCandidates)
                        .execute(candidates)
                        .compose(locked -> conn.preparedQuery(sqlArchiveEligible)
                                .execute(Tuple.of(idsOf(locked), status, cutoff))
                                .compose(eligible -> {
                                    UUID[] ids = idsOf(eligible);
                                    return ids.length == 0
                                            ? Future.succeededFuture(0)
                                            : conn.preparedQuery(sqlArchive)
                                                    .execute(Tuple.of(ids))
                                                    .map(rs -> rs.rowCount());
                                })));
    }

    /**
     * Purges up to {@code limit} previously archived workflow instances whose
     * {@code archived_at <= cutoff}, optionally filtered by {@code definitionId}. CASCADE FKs on
     * {@code workflow_history}, {@code workflow_tasks}, {@code workflow_timers},
     * {@code workflow_dedup}, {@code workflow_branch_tokens}, and {@code workflow_join_states}
     * drop dependent rows automatically. Opens its own transaction; rolls back on SQL failure.
     *
     * @param cutoff       inclusive upper bound on {@code archived_at}
     * @param definitionId optional definition-id filter; null means all definitions
     * @param limit        maximum number of rows to delete in this transaction; assumed to be
     *                     pre-validated and clamped by the caller
     * @return a {@link Future} resolving to the number of rows actually deleted
     */
    public Future<Integer> purgeArchivedBefore(OffsetDateTime cutoff, @Nullable String definitionId, int limit) {
        Tuple candidates = Tuple.of(cutoff, definitionId, limit);
        return transaction().execute("workflow_instances purgeArchived", conn -> conn.preparedQuery(sqlPurgeCandidates)
                .execute(candidates)
                .compose(locked -> conn.preparedQuery(sqlPurgeEligible)
                        .execute(Tuple.of(idsOf(locked), cutoff))
                        .compose(eligible -> {
                            UUID[] ids = idsOf(eligible);
                            return ids.length == 0
                                    ? Future.succeededFuture(0)
                                    : conn.preparedQuery(sqlPurge)
                                            .execute(Tuple.of(ids))
                                            .map(rs -> rs.rowCount());
                        })));
    }

    private static UUID[] idsOf(RowSet<Row> rows) {
        UUID[] ids = new UUID[rows.size()];
        int index = 0;
        for (Row row : rows) {
            ids[index++] = row.getUUID("id");
        }
        return ids;
    }
}
