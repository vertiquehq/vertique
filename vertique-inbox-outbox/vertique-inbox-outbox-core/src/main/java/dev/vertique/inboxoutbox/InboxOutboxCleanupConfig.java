// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vertique.core.exception.ValidationException;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for the inbox and outbox cleanup job.
 *
 * <p>Deserialized from the {@code inboxOutbox.cleanup} section of the application config.
 * The cleanup job runs periodically to remove old records from the inbox, published outbox,
 * dead-letter and discarded outbox tables, preventing unbounded table growth.
 *
 * <p>Cleanup cadence is owned by the framework's cron infrastructure (see
 * {@code OutboxMaintenanceCron} in {@code vertique-inbox-outbox-postgresql}). Operators
 * tune via {@code cron.jobs.outbox-cleanup.cron} config. The previous {@code cleanupIntervalHours}
 * field has been removed; configs that still set it are accepted (and the value ignored) thanks
 * to {@link JsonIgnoreProperties} on this class, so upgrades do not break on existing YAML.
 *
 * <p>Example YAML:
 * <pre>{@code
 * inboxOutbox:
 *   cleanup:
 *     publishedRetentionDays: 7
 *     deadLetterRetentionDays: 30
 *     discardedRetentionDays: 7
 *     inboxRetentionDays: 30
 *     cleanupBatchSize: 1000
 * }</pre>
 *
 * <p>Cleanup cadence is configured under {@code cron.jobs.outbox-cleanup.cron}; see the cron-job
 * module documentation for the override surface.
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public class InboxOutboxCleanupConfig {

    /**
     * Number of days to retain published outbox entries before the cleanup job deletes them.
     * Default: 7 days.
     */
    @Builder.Default
    private final int publishedRetentionDays = 7;

    /**
     * Number of days to retain dead-letter outbox entries before the cleanup job deletes them.
     * Dead-letter entries are kept longer than published entries to allow for inspection and
     * manual intervention. Default: 30 days.
     */
    @Builder.Default
    private final int deadLetterRetentionDays = 30;

    /**
     * Number of days to retain discarded outbox entries before the cleanup job deletes them. A discarded entry
     * can be brought back until then, so the window is the time an operator has to undo a discard.
     * Default: 7 days.
     */
    @Builder.Default
    private final int discardedRetentionDays = 7;

    /**
     * Number of days to retain processed inbox deduplication records before the cleanup job
     * deletes them. Default: 30 days.
     */
    @Builder.Default
    private final int inboxRetentionDays = 30;

    /**
     * Maximum number of records to delete in a single cleanup batch across each table.
     * Batching prevents long-running transactions and reduces lock contention. Default: 1 000.
     */
    @Builder.Default
    private final int cleanupBatchSize = 1_000;

    /** Largest accepted {@code discardedRetentionDays}. */
    public static final int MAX_DISCARDED_RETENTION_DAYS = 3650;

    /**
     * Creates the config; reached through the builder and Jackson deserialization. Only the discarded window is
     * range-checked: it is the time an operator has to undo a discard, so a zero or negative value, which would
     * purge every discarded entry at the next cleanup, is refused.
     *
     * @param publishedRetentionDays  retention of published entries, in days
     * @param deadLetterRetentionDays retention of dead-letter entries, in days
     * @param discardedRetentionDays  retention of discarded entries; 1 to {@value #MAX_DISCARDED_RETENTION_DAYS}
     * @param inboxRetentionDays      retention of inbox records, in days
     * @param cleanupBatchSize        records deleted per batch
     * @throws ValidationException if {@code discardedRetentionDays} is outside its range
     */
    InboxOutboxCleanupConfig(
            int publishedRetentionDays,
            int deadLetterRetentionDays,
            int discardedRetentionDays,
            int inboxRetentionDays,
            int cleanupBatchSize) {
        if (discardedRetentionDays < 1 || discardedRetentionDays > MAX_DISCARDED_RETENTION_DAYS) {
            throw new ValidationException(
                    "discardedRetentionDays must be between 1 and " + MAX_DISCARDED_RETENTION_DAYS);
        }
        this.publishedRetentionDays = publishedRetentionDays;
        this.deadLetterRetentionDays = deadLetterRetentionDays;
        this.discardedRetentionDays = discardedRetentionDays;
        this.inboxRetentionDays = inboxRetentionDays;
        this.cleanupBatchSize = cleanupBatchSize;
    }
}
