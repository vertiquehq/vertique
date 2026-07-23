// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Client-side interface for typed delayed job contracts.
 *
 * <p>Provides methods to enqueue jobs with typed payloads. Implementations are JDK dynamic
 * proxies created by {@link DelayedJobClientFactory} — user code should never implement this
 * interface directly.
 *
 * <p>Job configuration defaults (maxAttempts, queue, priority) are read from the
 * {@link DelayedJobContract} annotation on the extending interface, with runtime overrides
 * from application config. Per-enqueue overrides are available via {@link DelayedJobOptions}.
 *
 * <p>Example:
 * <pre>{@code
 * @DelayedJobContract(name = "deliver-webhook", maxAttempts = 2)
 * interface DeliverWebhookJob extends DelayedJobClient<WebhookPayload> {}
 *
 * // Inject the proxy and enqueue:
 * @Inject DeliverWebhookJob job;
 * job.enqueue(new WebhookPayload("pay_123"));
 * job.enqueue(payload, Duration.ofMinutes(5));
 * }</pre>
 *
 * @param <P> the payload type; must be Jackson-serializable
 * @see DelayedJobContract
 * @see DelayedJobExecutor
 */
public interface DelayedJobClient<P> {

    /**
     * Enqueues a job for immediate execution using contract defaults.
     *
     * @param payload the job payload
     * @return a future of the execution ID
     */
    Future<UUID> enqueue(P payload);

    /**
     * Enqueues a job scheduled for a specific time.
     *
     * @param payload the job payload
     * @param runAt   when the job should become eligible for execution
     * @return a future of the execution ID
     */
    Future<UUID> enqueue(P payload, Instant runAt);

    /**
     * Enqueues a job with a delay from the current time.
     *
     * <p>The delay is computed relative to the proxy invocation time ({@code Instant.now().plus(delay)}),
     * not the database insertion time.
     *
     * @param payload the job payload
     * @param delay   the delay before the job becomes eligible
     * @return a future of the execution ID
     */
    Future<UUID> enqueue(P payload, Duration delay);

    /**
     * Enqueues a job within an existing database transaction using contract defaults.
     *
     * @param payload the job payload
     * @param tx      the SQL client (connection or transaction) for transactional enqueue
     * @return a future of the execution ID
     */
    Future<UUID> enqueue(P payload, SqlClient tx);

    /**
     * Enqueues a job with advanced per-enqueue overrides.
     *
     * @param payload the job payload
     * @param options per-enqueue overrides; {@code null} fields use contract defaults
     * @return a future of the execution ID
     */
    Future<UUID> enqueue(P payload, DelayedJobOptions options);

    /**
     * Enqueues a job within a transaction with advanced per-enqueue overrides.
     *
     * @param payload the job payload
     * @param options per-enqueue overrides; {@code null} fields use contract defaults
     * @param tx      the SQL client for transactional enqueue
     * @return a future of the execution ID
     */
    Future<UUID> enqueue(P payload, DelayedJobOptions options, SqlClient tx);
}
