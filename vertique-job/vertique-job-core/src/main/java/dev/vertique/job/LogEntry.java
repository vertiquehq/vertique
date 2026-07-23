// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import java.time.Instant;

/**
 * A single buffered log entry produced by a job handler via {@link JobLogger}.
 *
 * <p>Entries are buffered in-memory during execution and may be flushed to a
 * {@link JobRepository} after the job completes.
 *
 * @param level     the severity level string (e.g. {@code "INFO"}, {@code "WARN"}, {@code "ERROR"})
 * @param message   the log message text
 * @param loggedAt  the instant at which this entry was created
 */
public record LogEntry(String level, String message, Instant loggedAt) {}
