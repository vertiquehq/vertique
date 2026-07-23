// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.job.JobContext;
import io.vertx.core.Future;

/**
 * Server-side interface for typed delayed job execution.
 *
 * <p>Implementations provide the business logic for processing delayed jobs. The framework
 * dispatches jobs through the service infrastructure ({@code ServiceVerticle},
 * {@code ServiceMethodInvoker}) with full support for interceptors, MDC propagation, and
 * configurable scaling via verticle instances.
 *
 * <p>The type parameter {@code C} links the executor to its client contract interface,
 * ensuring compile-time type safety between the enqueue and execute sides. The payload
 * type {@code P} is constrained by the contract: {@code C extends DelayedJobClient<P>}.
 *
 * <p><strong>Implementation note:</strong> Executors must directly implement this interface
 * with concrete type arguments. Intermediate abstract base classes that forward type parameters
 * (e.g., {@code abstract class BaseJob<P, C> implements DelayedJobExecutor<P, C>}) are not
 * supported — the framework's type resolver cannot trace type variables through intermediate
 * parameterized types.
 *
 * <p>Example:
 * <pre>{@code
 * @Singleton
 * class DeliverWebhookJobImpl
 *         implements DelayedJobExecutor<WebhookPayload, DeliverWebhookJob> {
 *
 *     @Inject
 *     DeliverWebhookJobImpl(WebhookClient webhookClient) { ... }
 *
 *     @Override
 *     public Future<Void> execute(WebhookPayload payload, JobContext ctx) {
 *         ctx.logger().info("Delivering webhook for payment {}", payload.paymentId());
 *         return webhookClient.notify(payload.paymentId());
 *     }
 * }
 * }</pre>
 *
 * @param <P> the payload type; must match the payload type of {@code C}
 * @param <C> the client contract interface extending {@link DelayedJobClient}{@code <P>}
 * @see DelayedJobClient
 * @see DelayedJobContract
 */
public interface DelayedJobExecutor<P, C extends DelayedJobClient<P>> {

    /**
     * Executes the job with the given payload and context.
     *
     * <p>The framework deserializes the payload from the job execution record and injects
     * the {@link JobContext} from the dispatch context. Implementations should use the
     * context for structured logging, progress tracking, and cooperative cancellation.
     *
     * @param payload the deserialized job payload
     * @param ctx     the job execution context
     * @return a future that completes when the job is done; failure triggers retry
     *     according to the contract's {@code maxAttempts} setting
     */
    Future<Void> execute(P payload, JobContext ctx);
}
