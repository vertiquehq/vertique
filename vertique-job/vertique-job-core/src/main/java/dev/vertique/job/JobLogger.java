// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import java.util.List;

/**
 * Buffered logger for job handler output.
 *
 * <p>Entries written through this logger are buffered in-memory during execution and flushed to a
 * {@link JobRepository} periodically <em>while the job runs</em> as well as when it completes, so a
 * handler produces per-execution diagnostic logs without holding a live database connection. These
 * are diagnostics, not an audit trail — audit records are the concern of the audit module.
 *
 * <p>Implementations must be thread-safe to allow concurrent log writes from multiple threads.
 * {@link DefaultJobLogger} buffers entries in a lock-guarded deque and drains them in batches.
 */
public interface JobLogger {

    /**
     * Buffers an informational message.
     *
     * @param message the log message
     */
    void info(String message);

    /**
     * Buffers a warning message.
     *
     * @param message the log message
     */
    void warn(String message);

    /**
     * Buffers an error message.
     *
     * @param message the log message
     */
    void error(String message);

    /**
     * Returns a snapshot copy of the log entries <em>still buffered</em>, in the order they were
     * written. Entries already flushed to the {@link JobRepository} are no longer included, so this
     * is not a transcript of everything the execution logged. The returned list is an immutable
     * copy — never a live view — and is safe to read from any thread.
     *
     * @return an immutable list of the entries currently buffered
     */
    List<LogEntry> entries();
}
