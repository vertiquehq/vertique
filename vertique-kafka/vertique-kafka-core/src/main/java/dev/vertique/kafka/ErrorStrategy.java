// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

/**
 * Error handling strategy for Kafka consumer dispatch failures.
 */
public enum ErrorStrategy {
    /** Log the error and commit the offset, skipping the record. */
    SKIP,
    /** Publish the original record to a dead-letter topic, then commit. */
    DEAD_LETTER,
    /**
     * Pause the consumer and retry via Kafka redelivery. Requires {@link CommitStrategy#MANUAL}.
     * After {@link RetryConfig#maxRetries()} exhausted, falls back to
     * {@link RetryConfig#exhaustedStrategy()}.
     */
    RETRY
}
