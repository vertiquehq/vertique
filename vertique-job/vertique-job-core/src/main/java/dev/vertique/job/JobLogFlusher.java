// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import io.vertx.core.Future;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
 * <p><strong>Every write is bounded.</strong> {@link JobRepository} is public SPI, so a write may
 * throw synchronously, return {@code null}, or return a future that never settles; each of those
 * would otherwise skip both the ack and the nack and wedge the buffer's single-flight marker
 * forever. All three are converted into the ordinary nack-and-retry path, the last of them by a
 * {@value #WRITE_TIMEOUT_SECONDS}-second timeout applied to the write before the outcome handlers.
 * The accepted cost is duplication: a write that times out here but commits later leaves rows that
 * the retry writes again, because {@code job_logs} has a surrogate key and no natural key to
 * deduplicate on. Duplicate log rows are preferred to a permanently stalled flush loop.
 *
 * <p>Construction with a {@code null} repository, a {@code null} execution id, or a
 * {@link JobContext} that is not a {@link DefaultJobContext} yields a genuine no-op flusher that
 * never touches the repository. The first two are required, not merely defensive:
 * {@code job_logs.execution_id} is {@code NOT NULL REFERENCES job_executions(id)}, so flushing for
 * an untracked cron fire — which has no {@code job_executions} row — would be a foreign-key
 * violation.
 */
@Slf4j
public final class JobLogFlusher {

    /**
     * Upper bound on a single {@link JobRepository#saveLogs(UUID, List)} call, after which the batch
     * is nacked and retried by a later flush.
     */
    private static final long WRITE_TIMEOUT_SECONDS = 5L;

    private final JobRepository repository;
    private final UUID executionId;
    private final DefaultJobLogger logger;

    /**
     * Whether this flusher may write to the repository, decided once at construction so
     * {@link #flush()} does not re-derive it on every call.
     */
    private final boolean persistable;

    /**
     * Creates a flusher for one execution, resolving the drainable buffer from the dispatch-time
     * {@link JobContext}.
     *
     * <p>Only the framework's own {@link DefaultJobContext} owns a drainable buffer — the
     * claim/ack drain protocol is package-private state on {@link DefaultJobLogger}, not part of
     * the public {@link JobLogger} interface. A foreign {@link JobContext} implementation
     * therefore yields a genuine no-op flusher (the same {@code persistable == false} path as a
     * {@code null} repository): there is nothing to claim, and a foreign logger's entries are
     * outside this framework's delivery contract.
     *
     * @param repository  the repository that persists log entries, or {@code null} for a no-op
     *                    flusher (in-memory-only mode)
     * @param executionId the execution the entries belong to, or {@code null} when the execution
     *                    has no persisted {@code job_executions} row (untracked cron fire)
     * @param context     the dispatch-time job context whose logger is drained; anything other
     *                    than a {@link DefaultJobContext} (including {@code null}) yields a no-op
     */
    public JobLogFlusher(JobRepository repository, UUID executionId, JobContext context) {
        this(
                repository,
                executionId,
                context instanceof DefaultJobContext defaultContext ? defaultContext.jobLogger() : null);
    }

    /**
     * Creates a flusher for one execution directly over its concrete logger.
     *
     * <p>Package-private: callers outside {@code dev.vertique.job} reach the concrete logger
     * through the {@link JobContext} overload rather than downcasting the public
     * {@link JobLogger} interface themselves.
     *
     * @param repository  the repository that persists log entries, or {@code null} for a no-op
     *                    flusher (in-memory-only mode)
     * @param executionId the execution the entries belong to, or {@code null} when the execution
     *                    has no persisted {@code job_executions} row (untracked cron fire)
     * @param logger      the per-execution logger whose buffer is drained, or {@code null} for a
     *                    no-op flusher
     */
    JobLogFlusher(JobRepository repository, UUID executionId, DefaultJobLogger logger) {
        this.repository = repository;
        this.executionId = executionId;
        this.logger = logger;
        this.persistable = repository != null && executionId != null && logger != null;
    }

    /**
     * Claims the buffered log entries and persists them.
     *
     * <p>Returns immediately without touching the repository when this flusher is a no-op or when
     * the claim is empty — either because nothing is buffered or because a previous flush is still
     * in flight. On a write failure the batch is returned to the buffer for the next flush and a
     * warning is logged.
     *
     * <p>Every way a {@link JobRepository} implementation can misbehave — throwing synchronously,
     * returning {@code null}, or returning a future that never settles — is folded into that same
     * failure path, so the buffer's in-flight marker is always cleared and a later flush can always
     * claim again.
     *
     * @return a future that completes when the write finishes, times out, or fails; always
     *     succeeded, because a log flush failure must not fail the job whose logs these are
     */
    public Future<Void> flush() {
        if (!persistable) {
            return Future.succeededFuture();
        }
        List<LogEntry> batch = logger.claim();
        if (batch.isEmpty()) {
            return Future.succeededFuture();
        }
        Future<Void> written;
        try {
            written = repository.saveLogs(executionId, batch);
        } catch (RuntimeException e) {
            written = Future.failedFuture(e);
        }
        if (written == null) {
            written = Future.failedFuture(new IllegalStateException("saveLogs returned null"));
        }
        // The timeout wraps the write itself, ahead of the outcome handlers: attaching it to the
        // composed future instead would leave ack/nack bound to the pending inner one, which is
        // exactly the wedge this guards against. A late inner settle lands on an already-completed
        // wrapper and is dropped, so the batch is never both nacked and acked.
        return written.timeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
