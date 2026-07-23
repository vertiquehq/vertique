// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import java.util.List;

/**
 * Buffered logger for job handler output.
 *
 * <p>Entries written through this logger are buffered in-memory during execution and can be
 * flushed to a {@link JobRepository} after the job completes, providing a per-execution audit
 * trail without requiring a live database connection during job execution.
 *
 * <p>Implementations must be thread-safe to allow concurrent log writes from multiple threads.
 * The {@link DefaultJobLogger} uses a {@link java.util.concurrent.CopyOnWriteArrayList} for
 * thread-safe buffering.
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
     * Returns a snapshot of all buffered log entries in the order they were written.
     * The returned list is an unmodifiable view (or copy) and is safe to read from any thread.
     *
     * @return an unmodifiable list of buffered entries
     */
    List<LogEntry> entries();
}
