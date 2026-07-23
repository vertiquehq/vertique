// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.retention;

import dev.vertique.workflow.retention.WorkflowRetentionService;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * PostgreSQL-backed implementation of {@link WorkflowRetentionService}. Owns service-level policy
 * (terminal-status routing, batch-size validation/clamping, {@link Instant} → TIMESTAMPTZ
 * conversion, {@link RetentionResult}/{@link PurgeResult} construction) and delegates SQL,
 * transactions, and exception translation to {@link PgWorkflowRetentionRepository}.
 *
 * <p>Archival targets the {@code completed_at} column (set by the optimistic-update path for all
 * terminal statuses), not {@code updated_at}. The {@code before} parameter is an inclusive upper
 * bound: rows with {@code completed_at <= before} are eligible.
 *
 * <p>The {@code batchSize} parameter is validated to be {@code > 0} and clamped to {@code 10000}.
 * Applications should call archive/purge methods repeatedly while
 * {@link RetentionResult#moreRowsRemaining()} (or {@link PurgeResult#moreRowsRemaining()}) is
 * {@code true} to drain large backlogs without locking rows for long periods. The repository's
 * {@code FOR UPDATE SKIP LOCKED} subquery means concurrent batch runs (e.g., rolling cron jobs on
 * different nodes) progress without blocking one another.
 */
@Singleton
public final class PgWorkflowRetentionService implements WorkflowRetentionService {

    /** Maximum allowed batch size; requests above this value are clamped. */
    private static final int MAX_BATCH_SIZE = 10_000;

    private final PgWorkflowRetentionRepository repository;

    /**
     * Creates a new retention service that delegates persistence to the given repository.
     *
     * @param repository the PostgreSQL retention repository owning archive/purge SQL, transactions,
     *                   and exception translation
     */
    @Inject
    public PgWorkflowRetentionService(PgWorkflowRetentionRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public Future<RetentionResult> archiveCompletedBefore(
            Instant before, @Nullable String definitionId, int batchSize) {
        return archive("COMPLETED", before, definitionId, batchSize);
    }

    /** {@inheritDoc} */
    @Override
    public Future<RetentionResult> archiveFailedBefore(Instant before, @Nullable String definitionId, int batchSize) {
        return archive("FAILED", before, definitionId, batchSize);
    }

    /** {@inheritDoc} */
    @Override
    public Future<RetentionResult> archiveCancelledBefore(
            Instant before, @Nullable String definitionId, int batchSize) {
        return archive("CANCELLED", before, definitionId, batchSize);
    }

    /** {@inheritDoc} */
    @Override
    public Future<RetentionResult> archiveExpiredBefore(Instant before, @Nullable String definitionId, int batchSize) {
        return archive("EXPIRED", before, definitionId, batchSize);
    }

    /** {@inheritDoc} */
    @Override
    public Future<RetentionResult> archiveCompensatedBefore(
            Instant before, @Nullable String definitionId, int batchSize) {
        return archive("COMPENSATED", before, definitionId, batchSize);
    }

    /** {@inheritDoc} */
    @Override
    public Future<PurgeResult> purgeArchivedBefore(Instant before, @Nullable String definitionId, int batchSize) {
        int clampedBatch = clampBatchSize(batchSize);
        return repository
                .purgeArchivedBefore(toOffsetDateTime(before), definitionId, clampedBatch)
                .map(count -> new PurgeResult(count, count == clampedBatch));
    }

    /**
     * Routes an archive request through the repository, applying batch validation/clamping and
     * wrapping the resulting row count in a {@link RetentionResult}.
     *
     * @param status       the terminal status string (e.g., {@code "COMPLETED"})
     * @param before       upper-bound cutoff for {@code completed_at}
     * @param definitionId optional filter; null means all definitions
     * @param batchSize    requested batch limit; validated and clamped before delegation
     * @return a {@link Future} resolving to the archival result (count + more-remaining flag)
     */
    private Future<RetentionResult> archive(
            String status, Instant before, @Nullable String definitionId, int batchSize) {
        int clampedBatch = clampBatchSize(batchSize);
        return repository
                .archiveBefore(status, toOffsetDateTime(before), definitionId, clampedBatch)
                .map(count -> new RetentionResult(count, count == clampedBatch));
    }

    /**
     * Validates that {@code batchSize} is positive and clamps it to {@link #MAX_BATCH_SIZE}.
     *
     * @param batchSize the requested batch size
     * @return the clamped batch size
     * @throws IllegalArgumentException if {@code batchSize} is {@code <= 0}
     */
    private static int clampBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0, got: " + batchSize);
        }
        return Math.min(batchSize, MAX_BATCH_SIZE);
    }

    /**
     * Converts an {@link Instant} to an {@link OffsetDateTime} at UTC for TIMESTAMPTZ parameters.
     *
     * @param instant the instant to convert
     * @return the UTC {@link OffsetDateTime}
     */
    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
