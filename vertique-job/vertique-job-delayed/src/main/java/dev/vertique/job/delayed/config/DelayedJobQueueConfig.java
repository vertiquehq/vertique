// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.job.delayed.BackoffStrategyType;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Per-queue configuration for the delayed job poller, deserialized from
 * {@code delayedJob.queues.<queueName>} in the application config.
 *
 * <p>This is the typed, validated per-queue record assembled at the {@code DelayedJobModule}
 * provider boundary (via {@link DelayedJobsConfig#fromConfig}); the poller depends on it, never on
 * the raw {@link io.vertx.core.json.JsonObject}. The object key is injected into {@link #name()} via
 * {@link dev.vertique.core.json.KeyedBy @KeyedBy("name")} on the parent
 * {@link DelayedJobsConfig#queues()} list before Jackson builds this instance.
 *
 * <p>All non-identity fields have sensible defaults so that no explicit configuration is required for
 * basic usage with the {@code "default"} queue.
 *
 * <p>Example configuration:
 * <pre>{@code
 * {
 *   "delayedJob": {
 *     "queues": {
 *       "default": {
 *         "sleepDelayMs": 5000,
 *         "maxConcurrentJobs": 5,
 *         "backoffStrategy": "LINEAR",
 *         "backoffBaseDelayMs": 30000,
 *         "backoffMaxDelayMs": 3600000
 *       }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Future-scheduled jobs use a single state ({@code ENQUEUED}) with a future
 * {@code scheduled_at} column value. The poller's claim query filters by
 * {@code scheduled_at <= NOW()}, so no separate schedule-check interval is needed.
 *
 * <p>The per-attempt retry limit is configured per job via
 * {@link dev.vertique.job.delayed.DelayedJob#maxAttempts()}. Queue-level defaults are not supported
 * for retry counts — set the value on each job at enqueue time.
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class DelayedJobQueueConfig {

    /**
     * The queue name (identity). Injected from the keyed-object key during boundary parsing; never
     * blank after {@link #validate()}.
     */
    private final String name;

    /**
     * Delay in milliseconds between poll cycles. Defaults to {@code 5000} (5 seconds).
     */
    @Builder.Default
    private final long sleepDelayMs = 5000L;

    /**
     * Maximum number of jobs that can be in-flight simultaneously for this queue.
     * Defaults to {@code 5}.
     */
    @Builder.Default
    private final int maxConcurrentJobs = 5;

    /**
     * Backoff strategy to use for retry delays. Supported values: {@code "LINEAR"},
     * {@code "FIXED"}, {@code "EXPONENTIAL"} (case-insensitive). Defaults to {@code "LINEAR"}.
     */
    @Builder.Default
    private final String backoffStrategy = "LINEAR";

    /**
     * Base delay in milliseconds for the backoff strategy. Defaults to {@code 30000} (30 seconds).
     */
    @Builder.Default
    private final long backoffBaseDelayMs = 30_000L;

    /**
     * Maximum delay in milliseconds for the backoff strategy. Defaults to {@code 3600000} (1 hour).
     */
    @Builder.Default
    private final long backoffMaxDelayMs = 3_600_000L;

    /**
     * Number of poller verticle instances to deploy for this queue. Defaults to {@code 1}.
     *
     * <p>Multiple instances multiply throughput — each maintains its own concurrency semaphore
     * of {@link #maxConcurrentJobs()} permits. Total concurrency for a queue is
     * {@code instances × maxConcurrentJobs}. Safe because {@code FOR UPDATE SKIP LOCKED}
     * prevents duplicate claims across instances.
     */
    @Builder.Default
    private final int instances = 1;

    /**
     * Validates this queue config's invariants after construction. The boundary provider calls this
     * once per parsed queue so malformed config fails fast at startup with a stable, secret-free,
     * identity-bearing message.
     *
     * @return this instance, for fluent chaining at the boundary
     * @throws ConfigurationException if {@code name} is blank, a numeric field is non-positive, or
     *     {@code backoffStrategy} is not one of {@code FIXED}/{@code LINEAR}/{@code EXPONENTIAL}
     */
    public DelayedJobQueueConfig validate() {
        if (name == null || name.isBlank()) {
            throw new ConfigurationException("delayedJob.queues.<name> must be non-blank");
        }
        if (sleepDelayMs <= 0) {
            throw new ConfigurationException(
                    "delayedJob.queues[" + name + "].sleepDelayMs must be > 0, got " + sleepDelayMs);
        }
        if (maxConcurrentJobs <= 0) {
            throw new ConfigurationException(
                    "delayedJob.queues[" + name + "].maxConcurrentJobs must be > 0, got " + maxConcurrentJobs);
        }
        if (backoffBaseDelayMs <= 0) {
            throw new ConfigurationException(
                    "delayedJob.queues[" + name + "].backoffBaseDelayMs must be > 0, got " + backoffBaseDelayMs);
        }
        if (backoffMaxDelayMs <= 0) {
            throw new ConfigurationException(
                    "delayedJob.queues[" + name + "].backoffMaxDelayMs must be > 0, got " + backoffMaxDelayMs);
        }
        if (instances <= 0) {
            throw new ConfigurationException("delayedJob.queues[" + name + "].instances must be > 0, got " + instances);
        }
        try {
            BackoffStrategyType.fromConfig(backoffStrategy);
        } catch (IllegalArgumentException ex) {
            throw new ConfigurationException("delayedJob.queues[" + name + "].backoffStrategy must be one of "
                    + "FIXED/LINEAR/EXPONENTIAL (case-insensitive), got '" + backoffStrategy + "'");
        }
        return this;
    }
}
