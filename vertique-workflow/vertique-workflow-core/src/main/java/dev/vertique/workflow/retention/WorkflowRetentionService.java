// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.retention;

import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Service SPI for workflow instance archival and purge operations.
 *
 * <p>Provides batch methods to soft-delete (archive) terminal workflow instances by status and
 * optional definition id, and to hard-delete (purge) previously archived rows. All operations
 * are app-driven — no built-in cleanup verticle is provided. Apps wire their own scheduled cron
 * tasks or background jobs to invoke these methods.
 *
 * <p>Archival compares {@code completed_at}, the instant the instance reached a terminal status
 * ({@code COMPLETED}, {@code FAILED}, {@code COMPENSATED}, {@code CANCELLED} or {@code EXPIRED}),
 * not {@code updated_at}. Purge compares {@code archived_at}, the instant the archive call marked
 * the instance, which comes from the database clock. In both, the {@code before} parameter is an
 * inclusive upper bound: an instance whose timestamp equals {@code before} is eligible.
 *
 * <p>An instance whose execution lock another transaction holds, or that has an active command, is
 * skipped by the call and is not included in its count; a later call handles it once it is eligible.
 *
 * <p>All batch operations validate {@code batchSize > 0} and cap it at {@code 10000}.
 *
 * <p>The PostgreSQL implementation lives in {@code vertique-workflow-postgresql}. This interface
 * is SQL-free so that application code depending on workflow-core can reference the service type
 * without pulling in the PostgreSQL stack.
 */
public interface WorkflowRetentionService {

    /**
     * Archives workflow instances that completed at or before {@code before}.
     *
     * <p>Archives up to {@code batchSize} rows in a single transaction. Call repeatedly while
     * {@link RetentionResult#moreRowsRemaining()} is {@code true} to drain the backlog.
     *
     * @param before       cutoff instant; only instances with {@code completed_at <= before} are
     *     archived; must not be null
     * @param definitionId optional filter; null means all definitions
     * @param batchSize    maximum number of rows to archive in this call; must be {@code > 0}
     * @return a {@link Future} resolving to the archival result
     */
    Future<RetentionResult> archiveCompletedBefore(Instant before, @Nullable String definitionId, int batchSize);

    /**
     * Archives workflow instances that failed at or before {@code before}.
     *
     * @param before       cutoff instant; must not be null
     * @param definitionId optional filter; null means all definitions
     * @param batchSize    maximum number of rows to archive; must be {@code > 0}
     * @return a {@link Future} resolving to the archival result
     */
    Future<RetentionResult> archiveFailedBefore(Instant before, @Nullable String definitionId, int batchSize);

    /**
     * Archives workflow instances that were cancelled at or before {@code before}.
     *
     * @param before       cutoff instant; must not be null
     * @param definitionId optional filter; null means all definitions
     * @param batchSize    maximum number of rows to archive; must be {@code > 0}
     * @return a {@link Future} resolving to the archival result
     */
    Future<RetentionResult> archiveCancelledBefore(Instant before, @Nullable String definitionId, int batchSize);

    /**
     * Archives workflow instances that expired at or before {@code before}.
     *
     * @param before       cutoff instant; must not be null
     * @param definitionId optional filter; null means all definitions
     * @param batchSize    maximum number of rows to archive; must be {@code > 0}
     * @return a {@link Future} resolving to the archival result
     */
    Future<RetentionResult> archiveExpiredBefore(Instant before, @Nullable String definitionId, int batchSize);

    /**
     * Archives workflow instances that were fully compensated at or before {@code before}.
     *
     * @param before       cutoff instant; must not be null
     * @param definitionId optional filter; null means all definitions
     * @param batchSize    maximum number of rows to archive; must be {@code > 0}
     * @return a {@link Future} resolving to the archival result
     */
    Future<RetentionResult> archiveCompensatedBefore(Instant before, @Nullable String definitionId, int batchSize);

    /**
     * Permanently purges previously archived workflow instances whose {@code archived_at}
     * is at or before {@code before}.
     *
     * <p>Only rows that have already been soft-deleted (archived) are eligible. Purging a row
     * removes it permanently from the primary table.
     *
     * @param before       cutoff instant; only instances with {@code archived_at <= before} are
     *     purged; must not be null
     * @param definitionId optional filter; null means all definitions
     * @param batchSize    maximum number of rows to purge; must be {@code > 0}
     * @return a {@link Future} resolving to the purge result
     */
    Future<PurgeResult> purgeArchivedBefore(Instant before, @Nullable String definitionId, int batchSize);

    // --- Result types ---

    /**
     * Result of a single archival batch operation.
     *
     * @param archivedCount     the number of rows archived in this call
     * @param moreRowsRemaining {@code true} when {@code archivedCount} equals the batch size in effect
     *     (the requested size, capped at {@code 10000}). It is a hint to call again, not a count:
     *     it can be {@code true} when nothing is left, and {@code false} while skipped instances
     *     are still eligible
     */
    record RetentionResult(int archivedCount, boolean moreRowsRemaining) {}

    /**
     * Result of a single purge batch operation.
     *
     * @param purgedCount       the number of rows permanently deleted in this call
     * @param moreRowsRemaining {@code true} when {@code purgedCount} equals the batch size in effect
     *     (the requested size, capped at {@code 10000}). It is a hint to call again, not a count:
     *     it can be {@code true} when nothing is left, and {@code false} while skipped instances
     *     are still eligible
     */
    record PurgeResult(int purgedCount, boolean moreRowsRemaining) {}
}
