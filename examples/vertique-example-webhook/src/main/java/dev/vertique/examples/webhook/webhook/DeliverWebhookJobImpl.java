// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.webhook.webhook;

import dev.vertique.job.JobContext;
import dev.vertique.job.delayed.DelayedJobExecutor;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Server-side executor for the webhook delivery job.
 *
 * <p>Dispatched through the service infrastructure with full support for interceptors,
 * MDC propagation, and configurable scaling.
 */
@Singleton
public class DeliverWebhookJobImpl implements DelayedJobExecutor<WebhookPayload, DeliverWebhookJob> {

    private final WebhookClient webhookClient;

    /**
     * Creates a new executor backed by the given webhook client.
     *
     * @param webhookClient the REST client used to deliver webhook notifications
     */
    @Inject
    DeliverWebhookJobImpl(WebhookClient webhookClient) {
        this.webhookClient = webhookClient;
    }

    /**
     * Delivers the webhook notification for the given payment.
     *
     * @param payload the job payload containing the payment identifier
     * @param ctx     the job execution context for logging and progress tracking
     * @return a future that completes when the notification has been delivered
     */
    @Override
    public Future<Void> execute(WebhookPayload payload, JobContext ctx) {
        ctx.logger().info("Delivering webhook for payment " + payload.paymentId());
        return webhookClient.notify(payload.paymentId());
    }
}
