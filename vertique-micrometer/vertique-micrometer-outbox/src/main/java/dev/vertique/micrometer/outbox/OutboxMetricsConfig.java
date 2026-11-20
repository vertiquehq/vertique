// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.outbox;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Settings of the outbox metrics adapter, deserialized from the {@code metrics.outbox} section of
 * the application config.
 *
 * <p>Example YAML:
 * <pre>{@code
 * metrics:
 *   outbox:
 *     queueSampleIntervalMs: 5000
 * }</pre>
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class OutboxMetricsConfig {

    /** The default sampling interval, in milliseconds. */
    public static final long DEFAULT_QUEUE_SAMPLE_INTERVAL_MS = 5_000L;

    /** The shortest sampling interval, in milliseconds. */
    public static final long MIN_QUEUE_SAMPLE_INTERVAL_MS = 1_000L;

    /**
     * Time between two reads of the outbox queue size, in milliseconds; at least
     * {@value #MIN_QUEUE_SAMPLE_INTERVAL_MS}. Default: {@value #DEFAULT_QUEUE_SAMPLE_INTERVAL_MS}.
     */
    @Builder.Default
    private final long queueSampleIntervalMs = DEFAULT_QUEUE_SAMPLE_INTERVAL_MS;
}
