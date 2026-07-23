// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for the outbox relay worker.
 *
 * <p>Deserialized from the {@code inboxOutbox.relay} section of the application config.
 * All duration fields use milliseconds (the {@code Ms} suffix). Defaults are tuned for
 * production workloads with low-latency delivery when using the
 * {@link RelayStrategy#LISTEN_NOTIFY} strategy.
 *
 * <p>Example YAML:
 * <pre>{@code
 * inboxOutbox:
 *   relay:
 *     pollingIntervalMs: 1000
 *     batchSize: 50
 *     leaseTimeoutMs: 30000
 *     maxAttempts: 20
 *     backoffBaseDelayMs: 1000
 *     backoffMaxDelayMs: 300000
 *     strategy: LISTEN_NOTIFY
 *     instances: 1
 * }</pre>
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class OutboxRelayConfig {

    /**
     * Interval in milliseconds between polling cycles when using the
     * {@link RelayStrategy#POLLING} strategy, or the fallback polling interval when using
     * {@link RelayStrategy#LISTEN_NOTIFY}. Default: 1 000 ms (1 s).
     */
    @Builder.Default
    private final long pollingIntervalMs = 1_000L;

    /**
     * Maximum number of outbox entries to claim and process in a single relay cycle.
     * Default: 50.
     */
    @Builder.Default
    private final int batchSize = 50;

    /**
     * Duration in milliseconds after which a claimed entry is considered stale and may be
     * re-claimed by another relay worker. Should be set longer than the expected maximum
     * delivery time. Default: 30 000 ms (30 s).
     */
    @Builder.Default
    private final long leaseTimeoutMs = 30_000L;

    /**
     * Maximum number of delivery attempts before an entry is moved to dead-letter state.
     * Default: 20.
     */
    @Builder.Default
    private final int maxAttempts = 20;

    /**
     * Base delay in milliseconds for the exponential backoff formula applied between retry
     * attempts. Default: 1 000 ms (1 s).
     */
    @Builder.Default
    private final long backoffBaseDelayMs = 1_000L;

    /**
     * Maximum delay in milliseconds that the exponential backoff will produce, regardless of
     * how many attempts have been made. Default: 300 000 ms (5 minutes).
     */
    @Builder.Default
    private final long backoffMaxDelayMs = 300_000L;

    /**
     * Strategy used to detect pending outbox entries. Default: {@link RelayStrategy#LISTEN_NOTIFY}.
     */
    @Builder.Default
    private final RelayStrategy strategy = RelayStrategy.LISTEN_NOTIFY;

    /**
     * Number of relay verticle instances to deploy. More instances increase throughput by
     * claiming and delivering entries in parallel. Default: 1.
     */
    @Builder.Default
    private final int instances = 1;
}
