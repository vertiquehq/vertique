// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

/**
 * Discriminates the scheduling mechanism that created a job execution.
 *
 * <p>The type is recorded on every {@link JobExecution} and propagated into
 * the {@link JobDispatchContext} so handlers can branch on execution mode if needed.
 */
public enum JobType {

    /** Timer-based recurring execution driven by a cron expression. */
    CRON,

    /** Database-backed deferred execution triggered at a specific time. */
    DELAYED,

    /** Chunk-oriented batch processing with progress tracking. */
    BATCH
}
