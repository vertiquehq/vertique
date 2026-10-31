// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql.maintenance;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vertique.core.exception.ConfigurationException;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for the job execution retention job.
 *
 * <p>Deserialized from the {@code job.retention} section of the application config. The retention
 * job runs periodically and deletes finished job executions, with their logs, once they are older
 * than {@link #retentionSeconds()}, so {@code job_executions} and {@code job_logs} do not grow
 * without bound. Only executions that cannot run again are deleted: {@code SUCCEEDED},
 * {@code CANCELLED} and {@code DEAD_LETTER}, and the {@code FAILED} and {@code ABANDONED} executions of
 * cron jobs, which are never re-enqueued.
 *
 * <p>The cadence is owned by the framework's cron infrastructure (see {@link JobMaintenanceCron}).
 * Operators tune it with {@code cron.jobs.job-execution-retention.cron}.
 *
 * <p>Example YAML:
 * <pre>{@code
 * job:
 *   retention:
 *     retentionSeconds: 604800
 *     batchSize: 1000
 * }</pre>
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobRetentionConfig {

    /**
     * The least {@link #retentionSeconds()} allows: one hour. A finished execution must stay long
     * enough for a late completion, a late log write and a cron fire-time check to find it.
     */
    public static final long MIN_RETENTION_SECONDS = 3_600L;

    /** The default for {@link #retentionSeconds()}: seven days, the same as published outbox rows. */
    public static final long DEFAULT_RETENTION_SECONDS = 7L * 24 * 60 * 60;

    /**
     * How long, in seconds, a finished execution is kept before the retention job deletes it. The
     * age is counted from the moment the execution finished. Default: 604 800 (7 days).
     */
    @Builder.Default
    private final long retentionSeconds = DEFAULT_RETENTION_SECONDS;

    /**
     * The most executions deleted in one batch. Each batch is its own short transaction, so a large
     * backlog does not hold locks for long. Default: 1 000.
     */
    @Builder.Default
    private final int batchSize = 1_000;

    /**
     * Checks that the retention is at least {@link #MIN_RETENTION_SECONDS} and the batch size is positive.
     *
     * @return this configuration
     * @throws ConfigurationException if {@code retentionSeconds} is below the minimum or {@code batchSize} is not positive
     */
    public JobRetentionConfig validate() {
        if (retentionSeconds < MIN_RETENTION_SECONDS) {
            throw new ConfigurationException(
                    "job.retention.retentionSeconds must be >= " + MIN_RETENTION_SECONDS + ", got " + retentionSeconds);
        }
        if (batchSize <= 0) {
            throw new ConfigurationException("job.retention.batchSize must be > 0, got " + batchSize);
        }
        return this;
    }
}
