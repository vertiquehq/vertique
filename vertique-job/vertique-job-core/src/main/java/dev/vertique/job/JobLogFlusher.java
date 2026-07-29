// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import io.vertx.core.Future;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Drains a {@link DefaultJobLogger}'s buffer into a {@link JobRepository}, one instance per
 * execution.
 *
 * <p>Each {@link #flush()} claims the currently buffered entries as a batch and writes them with
 * {@link JobRepository#saveLogs(UUID, List)}. A successful write acknowledges the batch; a failed
 * write returns it to the front of the buffer so the next flush retries it. Delivery is therefore
 * at-least-once in principle, but not in practice for this repository: {@code saveLogs} is a single
 * batched INSERT whose failure rolls back every row in the batch (pinned by
 * {@code PgJobRepositoryIT#saveLogsBatchIsAtomicOnPartialFailure}). Because {@code job_logs} has a
 * surrogate {@code BIGSERIAL} key and no natural key, a partially committed batch could not be
 * deduplicated on retry — the all-or-nothing write is what makes the retry safe.
 *
 * <p><strong>A failed flush never fails the job it belongs to.</strong> {@link #flush()} reports
 * persistence problems by logging a warning and still returns a succeeded future. The warning is
 * deliberately not written back through the {@link JobLogger} — that would feed the failure into
 * the very buffer that failed to flush.
 *
 * <p>Construction with a {@code null} repository or a {@code null} execution id yields a genuine
 * no-op flusher that never touches the repository. This is required, not merely defensive:
 * {@code job_logs.execution_id} is {@code NOT NULL REFERENCES job_executions(id)}, so flushing for
 * an untracked cron fire — which has no {@code job_executions} row — would be a foreign-key
 * violation.
 */
@Slf4j
public final class JobLogFlusher {

    private final JobRepository repository;
    private final UUID executionId;
    private final DefaultJobLogger logger;

    /**
     * Whether this flusher may write to the repository, decided once at construction so
     * {@link #flush()} does not re-derive it on every call.
     */
    private final boolean persistable;

    /**
     * Creates a flusher for one execution.
     *
     * @param repository  the repository that persists log entries, or {@code null} for a no-op
     *                    flusher (in-memory-only mode)
     * @param executionId the execution the entries belong to, or {@code null} when the execution
     *                    has no persisted {@code job_executions} row (untracked cron fire)
     * @param logger      the per-execution logger whose buffer is drained
     */
    public JobLogFlusher(JobRepository repository, UUID executionId, DefaultJobLogger logger) {
        this.repository = repository;
        this.executionId = executionId;
        this.logger = logger;
        this.persistable = repository != null && executionId != null;
    }

    /**
     * Claims the buffered log entries and persists them.
     *
     * <p>Returns immediately without touching the repository when this flusher is a no-op or when
     * the claim is empty — either because nothing is buffered or because a previous flush is still
     * in flight. On a write failure the batch is returned to the buffer for the next flush and a
     * warning is logged.
     *
     * @return a future that completes when the write finishes; always succeeded, because a log
     *     flush failure must not fail the job whose logs these are
     */
    public Future<Void> flush() {
        if (!persistable) {
            return Future.succeededFuture();
        }
        List<LogEntry> batch = logger.claim();
        if (batch.isEmpty()) {
            return Future.succeededFuture();
        }
        return repository
                .saveLogs(executionId, batch)
                .onSuccess(v -> logger.ack())
                .recover(cause -> {
                    logger.nack(batch);
                    log.warn(
                            "Failed to persist {} job log entr{} for execution {} — retained for retry: {}",
                            batch.size(),
                            batch.size() == 1 ? "y" : "ies",
                            executionId,
                            cause.getMessage());
                    return Future.succeededFuture();
                });
    }
}
